package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.data.DraftItemKind
import com.gvart.parleyroom.ai.data.DraftItemTable
import com.gvart.parleyroom.ai.transfer.DraftDocument
import com.gvart.parleyroom.ai.transfer.DraftGrammarTopic
import com.gvart.parleyroom.ai.transfer.DraftItemResponse
import com.gvart.parleyroom.ai.transfer.DraftKind
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
import org.jetbrains.exposed.v1.jdbc.update
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
            matchedEntry = word?.libraryEntryId?.let(matched::get),
            createdAt = row[DraftItemTable.createdAt],
            updatedAt = row[DraftItemTable.updatedAt],
        )
    }

    /**
     * Items for a validated model answer, in display order, matched against the library. Tags given by
     * a [catalog] key are library tags; named ones are matched by name or stay new (created at Send).
     * Must run in a transaction.
     */
    fun fromAi(
        draft: ValidatedDraft, teacherId: UUID, level: LanguageLevel?, sourceLessonId: UUID?, recipients: List<UUID>, catalog: TagCatalog,
    ): List<NewDraftItem> {
        val matcher = LibraryMatcher(teacherId)
        fun topics(list: List<AiTopic>) = list.mapNotNull { catalog.topic(it, matcher) }.distinctBy { it.id ?: it.name.lowercase() }
        fun grammar(list: List<AiGrammarTopic>) = list.mapNotNull { catalog.grammar(it, matcher) }.distinctBy { it.id ?: it.name.lowercase() }
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
        return word.copy(entry = word.entry.copy(topicIds = emptyList(), grammarTopicIds = null), libraryEntryId = entryId?.toString(), matched = entryId != null,
            alreadyAssigned = assigned)
    }

    /** What [newWords] dropped. */
    data class WordFilter(val kept: Int, val known: Int, val repeated: Int)

    /**
     * Only words new to the learner: drops the ones [known] has (the library entry, or the same
     * normalized lemma) and repeats within the answer (same normalized lemma). Other items pass.
     */
    fun newWords(items: List<NewDraftItem>, known: StudentWords?): Pair<List<NewDraftItem>, WordFilter> {
        val seen = mutableSetOf<String>()
        var dropKnown = 0
        var dropRepeated = 0
        val kept = items.filter { item ->
            if (item.kind != DraftItemKind.WORD) return@filter true
            val word = json.decodeFromJsonElement<DraftWord>(item.payload)
            when {
                known?.has(word) == true -> { dropKnown++; false }
                !seen.add(LibraryMatcher.normalizeLemma(word.entry.lemma)) -> { dropRepeated++; false }
                else -> true
            }
        }
        return kept to WordFilter(kept.count { it.kind == DraftItemKind.WORD }, dropKnown, dropRepeated)
    }

    fun insert(bundleId: UUID, items: List<NewDraftItem>, firstPosition: Int = 0) {
        DraftItemTable.batchInsert(items.withIndex().toList()) { (index, item) ->
            this[DraftItemTable.bundleId] = bundleId
            this[DraftItemTable.kind] = item.kind
            this[DraftItemTable.position] = firstPosition + index
            this[DraftItemTable.payload] = item.payload
        }
    }

    /** The current items in the model's output format for a refine: library tags by [catalog] key, short block ids. */
    fun toAiJson(items: List<ResultRow>, target: DraftTarget, catalog: TagCatalog, kinds: Set<DraftKind> = DraftKind.entries.toSet()): String {
        fun topics(list: List<DraftTopic>) = list.map(catalog::toAi)
        fun grammar(list: List<DraftGrammarTopic>) = list.map(catalog::toAi)
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
            DraftTarget.BUNDLE -> AiDraftOutput(
                words = if (DraftKind.WORDS in kinds) words else emptyList(),
                homework = if (DraftKind.HOMEWORK in kinds) AiHomework(exercise, tasks) else null,
            )
            DraftTarget.WORD -> AiDraftOutput(words = words)
            DraftTarget.EXERCISE_DOCUMENT -> AiDraftOutput(homework = AiHomework(document = exercise))
            DraftTarget.TASK -> AiDraftOutput(homework = AiHomework(tasks = tasks))
            DraftTarget.NOTES_DOCUMENT -> AiDraftOutput(notes = notes)
        }
        return AiOutputParser.json.encodeToString(AiDraftOutput.serializer(), output)
    }

    /** The item kinds a generation kind produces. */
    fun itemKinds(kinds: Set<DraftKind>): Set<DraftItemKind> = kinds.flatMap {
        when (it) {
            DraftKind.WORDS -> listOf(DraftItemKind.WORD)
            DraftKind.HOMEWORK -> listOf(DraftItemKind.EXERCISE_DOCUMENT, DraftItemKind.TASK)
        }
    }.toSet()

    /** Display order: words, exercise document, tasks, notes; within a kind the previous order. */
    fun renumber(bundleId: UUID) {
        DraftItemTable.selectAll().where { DraftItemTable.bundleId eq bundleId }.toList()
            .sortedWith(compareBy({ it[DraftItemTable.kind].ordinal }, { it[DraftItemTable.position] }))
            .forEachIndexed { index, row ->
                if (row[DraftItemTable.position] != index)
                    DraftItemTable.update({ DraftItemTable.id eq row[DraftItemTable.id] }) { it[position] = index }
            }
    }

    fun targetOf(kind: DraftItemKind): DraftTarget = when (kind) {
        DraftItemKind.WORD -> DraftTarget.WORD
        DraftItemKind.EXERCISE_DOCUMENT -> DraftTarget.EXERCISE_DOCUMENT
        DraftItemKind.TASK -> DraftTarget.TASK
        DraftItemKind.NOTES_DOCUMENT -> DraftTarget.NOTES_DOCUMENT
    }
}
