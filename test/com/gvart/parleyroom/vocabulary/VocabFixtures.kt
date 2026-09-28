package com.gvart.parleyroom.vocabulary

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.data.WordType
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

/** Direct DB seeding of a library entry plus a student's copy of it. Returns the student_vocab id. */
fun seedStudentVocab(
    studentId: UUID,
    lemma: String = "Haus",
    teacherId: UUID = UUID.fromString(IntegrationTest.TEACHER_ID),
    status: StudentVocabStatus = StudentVocabStatus.NEW,
    due: OffsetDateTime? = null,
): UUID = transaction {
    val now = OffsetDateTime.now()
    val entryId = VocabEntryTable.insertAndGetId {
        it[VocabEntryTable.teacherId] = teacherId
        it[VocabEntryTable.lemma] = lemma
        it[wordType] = WordType.NOUN
        it[translations] = mapOf("en" to "house")
        it[synonyms] = emptyList()
        it[createdAt] = now
        it[updatedAt] = now
    }
    StudentVocabTable.insertAndGetId {
        it[StudentVocabTable.studentId] = studentId
        it[vocabEntryId] = entryId
        it[StudentVocabTable.status] = status
        it[StudentVocabTable.due] = due
        it[addedAt] = now.minusDays(7)
    }.value
}
