package com.gvart.parleyroom.practice

import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.TEACHER
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.practice.service.FsrsState
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTopicTable
import com.gvart.parleyroom.vocabulary.data.WordType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

/** Direct DB seeding of practice words. */
object PracticeFixtures {

    /** A library entry plus the student's copy. A reviewed card (state > 0) needs [due] and gets stability 5. */
    fun word(
        lemma: String,
        wordType: WordType = WordType.NOUN,
        article: NounArticle? = if (wordType == WordType.NOUN) NounArticle.DAS else null,
        translations: Map<String, String> = mapOf("ru" to "$lemma-ru", "en" to "$lemma-en"),
        explanationDe: String? = "Erklärung: $lemma",
        government: String? = null,
        topicIds: List<UUID> = emptyList(),
        studentId: UUID = STUDENT,
        state: FsrsState = FsrsState.NEW,
        due: OffsetDateTime? = null,
        addedAt: OffsetDateTime = OffsetDateTime.now().minusDays(7),
    ): UUID = transaction {
        val now = OffsetDateTime.now()
        val entryId = VocabEntryTable.insertAndGetId {
            it[teacherId] = TEACHER
            it[VocabEntryTable.lemma] = lemma
            it[VocabEntryTable.article] = article
            it[VocabEntryTable.wordType] = wordType
            it[plural] = if (wordType == WordType.NOUN) "die ${lemma}e" else null
            it[forms] = if (wordType == WordType.NOUN) "des ${lemma}s" else null
            it[VocabEntryTable.translations] = translations
            it[VocabEntryTable.explanationDe] = explanationDe
            it[VocabEntryTable.government] = government
            it[exampleSentence] = "Das $lemma ist hier."
            it[synonyms] = emptyList()
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        topicIds.forEach { topicId ->
            VocabEntryTopicTable.insert { it[vocabEntryId] = entryId; it[VocabEntryTopicTable.topicId] = topicId }
        }
        StudentVocabTable.insertAndGetId {
            it[StudentVocabTable.studentId] = studentId
            it[vocabEntryId] = entryId
            it[StudentVocabTable.state] = state.code
            it[StudentVocabTable.due] = due
            if (state != FsrsState.NEW) {
                it[stability] = 5.0
                it[difficulty] = 5.0
                it[reps] = 1
                it[lastReview] = (due ?: now).minusDays(5)
            }
            it[StudentVocabTable.addedAt] = addedAt
        }.value
    }

    fun setLevel(level: LanguageLevel?, studentId: UUID = STUDENT) = transaction {
        UserTable.update({ UserTable.id eq studentId }) { it[UserTable.level] = level }
    }
}
