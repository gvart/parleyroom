package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.data.DraftItemKind
import com.gvart.parleyroom.ai.data.DraftItemTable
import com.gvart.parleyroom.ai.transfer.DraftDocument
import com.gvart.parleyroom.ai.transfer.DraftGrammarTopic
import com.gvart.parleyroom.ai.transfer.DraftItemResponse
import com.gvart.parleyroom.ai.transfer.DraftTask
import com.gvart.parleyroom.ai.transfer.DraftTopic
import com.gvart.parleyroom.ai.transfer.DraftWord
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** A draft item's content before it is stored. */
data class NewDraftItem(val kind: DraftItemKind, val payload: JsonElement)

/** Draft item payloads: (de)serialization, library matching and the model-facing form. */
object DraftItems {

    private val json = GenerationJobs.json

    fun word(row: ResultRow): DraftWord = json.decodeFromJsonElement(row[DraftItemTable.payload])
    fun document(row: ResultRow): DraftDocument = json.decodeFromJsonElement(row[DraftItemTable.payload])
    fun task(row: ResultRow): DraftTask = json.decodeFromJsonElement(row[DraftItemTable.payload])

    fun payload(word: DraftWord): JsonElement = json.encodeToJsonElement(word)
    fun payload(document: DraftDocument): JsonElement = json.encodeToJsonElement(document)
    fun payload(task: DraftTask): JsonElement = json.encodeToJsonElement(task)

    fun toResponse(row: ResultRow, matched: Map<String, VocabEntryResponse>): DraftItemResponse {
        val kind = row[DraftItemTable.kind]
        val word = if (kind == DraftItemKind.WORD) word(row) else null
        return DraftItemResponse(
            id = row[DraftItemTable.id].value.toString(),
            kind = kind,
            position = row[DraftItemTable.position],
            approved = row[DraftItemTable.approved],
            word = word,
            document = if (kind == DraftItemKind.EXERCISE_DOCUMENT || kind == DraftItemKind.NOTES_DOCUMENT) document(row) else null,
            task = if (kind == DraftItemKind.TASK) task(row) else null,
            matchedEntry = word?.matchedEntryId?.let(matched::get),
            createdAt = row[DraftItemTable.createdAt],
            updatedAt = row[DraftItemTable.updatedAt],
        )
    }

    /** Items for a validated model answer, in display order, matched against the library. Must run in a transaction. */
    fun fromAi(draft: ValidatedDraft, teacherId: UUID, level: LanguageLevel?, sourceLessonId: UUID?, recipients: List<UUID>): List<NewDraftItem> {
        val matcher = LibraryMatcher(teacherId)
        fun topics(list: List<AiTopic>) = list.map { DraftTopic(matcher.matchTopic(it.name, it.parentName)?.toString(), it.name, it.parentName) }
        fun grammar(list: List<AiGrammarTopic>) = list.map { DraftGrammarTopic(matcher.matchGrammar(it.name)?.toString(), it.name, it.level) }
        fun document(doc: ValidatedDocument) = DraftDocument(doc.title, doc.blocks, topics(doc.topics), grammar(doc.grammarTopics), level)

        val words = draft.words.map { word ->
            val draftWord = DraftWord(word.toInput(sourceLessonId?.toString()), topics(word.topics), grammar(word.grammarTopics))
            NewDraftItem(DraftItemKind.WORD, payload(match(draftWord, matcher, recipients)))
        }
        val exercise = draft.exerciseDocument?.let { NewDraftItem(DraftItemKind.EXERCISE_DOCUMENT, payload(document(it))) }
        val tasks = draft.tasks.map { task ->
            NewDraftItem(DraftItemKind.TASK, payload(DraftTask(task.title, task.instructions, task.responseType, topics(task.topics), grammar(task.grammarTopics))))
        }
        val notes = draft.notesDocument?.let { NewDraftItem(DraftItemKind.NOTES_DOCUMENT, payload(document(it))) }
        return words + listOfNotNull(exercise) + tasks + listOfNotNull(notes)
    }

    /** Sets the read-only match fields: library entry with the same dedupe key, and whether every recipient has it. */
    fun match(word: DraftWord, matcher: LibraryMatcher, recipients: List<UUID>): DraftWord {
        val entryId = matcher.matchEntry(word.entry.lemma, word.entry.article, word.entry.wordType)?.get(VocabEntryTable.id)?.value
        val assigned = entryId != null && recipients.isNotEmpty() && StudentVocabTable.selectAll()
            .where { (StudentVocabTable.vocabEntryId eq entryId) and (StudentVocabTable.studentId inList recipients) }
            .count() == recipients.size.toLong()
        return word.copy(entry = word.entry.copy(topicIds = emptyList()), matchedEntryId = entryId?.toString(), alreadyAssigned = assigned)
    }

    fun insert(bundleId: UUID, items: List<NewDraftItem>, firstPosition: Int = 0) {
        DraftItemTable.batchInsert(items.withIndex().toList()) { (index, item) ->
            this[DraftItemTable.bundleId] = bundleId
            this[DraftItemTable.kind] = item.kind
            this[DraftItemTable.position] = firstPosition + index
            this[DraftItemTable.payload] = item.payload
        }
    }

    /** The current items in the model's output format for a refine: names only, short block ids. */
    fun toAiJson(items: List<ResultRow>, target: DraftTarget): String {
        fun topics(list: List<DraftTopic>) = list.map { AiTopic(it.name, it.parentName) }
        fun grammar(list: List<DraftGrammarTopic>) = list.map { AiGrammarTopic(it.name, it.level) }
        fun aiDocument(row: ResultRow) = document(row).let {
            AiDocument(it.title, AiBlocks.toAi(it.blocks, emptyMap(), emptyMap()), topics(it.topics), grammar(it.grammarTopics))
        }
        val words = items.filter { it[DraftItemTable.kind] == DraftItemKind.WORD }.map { row ->
            val w = word(row)
            val e = w.entry
            AiWord(e.lemma, e.article, e.plural, e.wordType, e.forms, e.government, e.translations, e.explanationDe,
                e.exampleSentence, e.level, e.synonyms, topics(w.topics), grammar(w.grammarTopics))
        }
        val exercise = items.firstOrNull { it[DraftItemTable.kind] == DraftItemKind.EXERCISE_DOCUMENT }?.let(::aiDocument)
        val tasks = items.filter { it[DraftItemTable.kind] == DraftItemKind.TASK }.map { row ->
            task(row).let { AiTask(it.title, it.instructions, it.responseType, topics(it.topics), grammar(it.grammarTopics)) }
        }
        val notes = items.firstOrNull { it[DraftItemTable.kind] == DraftItemKind.NOTES_DOCUMENT }?.let(::aiDocument)
        val output = when (target) {
            DraftTarget.BUNDLE -> AiDraftOutput(words = words, homework = AiHomework(exercise, tasks))
            DraftTarget.WORD -> AiDraftOutput(words = words)
            DraftTarget.EXERCISE_DOCUMENT -> AiDraftOutput(homework = AiHomework(document = exercise))
            DraftTarget.TASK -> AiDraftOutput(homework = AiHomework(tasks = tasks))
            DraftTarget.NOTES_DOCUMENT -> AiDraftOutput(notes = notes)
        }
        return AiOutputParser.json.encodeToString(AiDraftOutput.serializer(), output)
    }

    fun targetOf(kind: DraftItemKind): DraftTarget = when (kind) {
        DraftItemKind.WORD -> DraftTarget.WORD
        DraftItemKind.EXERCISE_DOCUMENT -> DraftTarget.EXERCISE_DOCUMENT
        DraftItemKind.TASK -> DraftTarget.TASK
        DraftItemKind.NOTES_DOCUMENT -> DraftTarget.NOTES_DOCUMENT
    }
}
