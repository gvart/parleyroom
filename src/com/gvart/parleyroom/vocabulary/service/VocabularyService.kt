package com.gvart.parleyroom.vocabulary.service

import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.VocabStatus
import com.gvart.parleyroom.vocabulary.data.VocabularyWordTable
import com.gvart.parleyroom.vocabulary.transfer.CreateVocabularyWordRequest
import com.gvart.parleyroom.vocabulary.transfer.UpdateVocabularyWordRequest
import com.gvart.parleyroom.vocabulary.transfer.VocabularyPageResponse
import com.gvart.parleyroom.vocabulary.transfer.VocabularyWordResponse
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

class VocabularyService {

    fun getWords(
        principal: UserPrincipal,
        studentId: UUID?,
        status: VocabStatus?,
        page: PageRequest,
    ): VocabularyPageResponse = transaction {
        val query = when (principal.role) {
            UserRole.ADMIN -> VocabularyWordTable.selectAll()
            UserRole.TEACHER -> {
                VocabularyWordTable.join(
                    TeacherStudentTable, JoinType.INNER,
                    onColumn = VocabularyWordTable.studentId,
                    otherColumn = TeacherStudentTable.studentId,
                ).selectAll()
                    .where { TeacherStudentTable.teacherId eq principal.id }
            }
            UserRole.STUDENT -> VocabularyWordTable.selectAll()
                .where { VocabularyWordTable.studentId eq principal.id }
        }

        if (studentId != null) {
            AuthorizationHelper.requireAccessToStudent(studentId, principal)
            query.andWhere { VocabularyWordTable.studentId eq studentId }
        }

        if (status != null) {
            query.andWhere { VocabularyWordTable.status eq status }
        }

        val total = query.count()
        val items = query
            .limit(page.pageSize)
            .offset(page.offset)
            .map(::toResponse)

        VocabularyPageResponse(
            words = items,
            total = total,
            page = page.page,
            pageSize = page.pageSize,
        )
    }

    fun getWord(wordId: UUID, principal: UserPrincipal): VocabularyWordResponse = transaction {
        val word = findWord(wordId)
        AuthorizationHelper.requireAccessToStudent(word[VocabularyWordTable.studentId].value, principal)
        toResponse(word)
    }

    fun createWord(request: CreateVocabularyWordRequest, principal: UserPrincipal): VocabularyWordResponse = transaction {
        val studentId = UUID.fromString(request.studentId)
        AuthorizationHelper.requireAccessToStudent(studentId, principal)

        val existing = VocabularyWordTable.selectAll()
            .where {
                (VocabularyWordTable.studentId eq studentId) and
                        (VocabularyWordTable.german eq request.german)
            }.singleOrNull()

        if (existing != null)
            throw ConflictException("Word '${request.german}' already exists for this student")

        val now = OffsetDateTime.now()
        val id = VocabularyWordTable.insertAndGetId {
            it[VocabularyWordTable.studentId] = studentId
            it[lessonId] = request.lessonId?.let(UUID::fromString)
            it[german] = request.german
            it[english] = request.english
            it[exampleSentence] = request.exampleSentence
            it[exampleTranslation] = request.exampleTranslation
            it[category] = request.category
            it[addedAt] = now
        }

        VocabularyWordTable.selectAll()
            .where { VocabularyWordTable.id eq id }
            .single()
            .let(::toResponse)
    }

    fun updateWord(wordId: UUID, request: UpdateVocabularyWordRequest, principal: UserPrincipal): VocabularyWordResponse = transaction {
        val word = findWord(wordId)
        AuthorizationHelper.requireAccessToStudent(word[VocabularyWordTable.studentId].value, principal)

        VocabularyWordTable.update({ VocabularyWordTable.id eq wordId }) {
            if (request.german != null) it[german] = request.german
            if (request.english != null) it[english] = request.english
            if (request.exampleSentence != null) it[exampleSentence] = request.exampleSentence
            if (request.exampleTranslation != null) it[exampleTranslation] = request.exampleTranslation
            if (request.category != null) it[category] = request.category
        }

        VocabularyWordTable.selectAll()
            .where { VocabularyWordTable.id eq wordId }
            .single()
            .let(::toResponse)
    }

    fun deleteWord(wordId: UUID, principal: UserPrincipal) = transaction {
        val word = findWord(wordId)
        AuthorizationHelper.requireAccessToStudent(word[VocabularyWordTable.studentId].value, principal)
        VocabularyWordTable.deleteWhere { id eq wordId }
    }

    fun reviewWord(wordId: UUID, principal: UserPrincipal): VocabularyWordResponse = transaction {
        val word = findWord(wordId)
        val studentId = word[VocabularyWordTable.studentId].value

        AuthorizationHelper.requireAccessToStudent(studentId, principal)

        val currentCount = word[VocabularyWordTable.reviewCount]
        val newCount = currentCount + 1
        val newStatus = if (newCount >= 5) VocabStatus.LEARNED else VocabStatus.REVIEW
        val daysUntilNext = 1L shl minOf(newCount, 6) // 2^count, capped at 64 days
        val nextReview = OffsetDateTime.now().plusDays(daysUntilNext)

        VocabularyWordTable.update({ VocabularyWordTable.id eq wordId }) {
            it[reviewCount] = newCount
            it[status] = newStatus
            it[nextReviewAt] = nextReview
        }

        VocabularyWordTable.selectAll()
            .where { VocabularyWordTable.id eq wordId }
            .single()
            .let(::toResponse)
    }

    private fun findWord(wordId: UUID): ResultRow =
        VocabularyWordTable.selectAll()
            .where { VocabularyWordTable.id eq wordId }
            .singleOrNull() ?: throw NotFoundException("Vocabulary word not found")

    private fun toResponse(row: ResultRow) = VocabularyWordResponse(
        id = row[VocabularyWordTable.id].value.toString(),
        studentId = row[VocabularyWordTable.studentId].value.toString(),
        lessonId = row[VocabularyWordTable.lessonId]?.value?.toString(),
        german = row[VocabularyWordTable.german],
        english = row[VocabularyWordTable.english],
        exampleSentence = row[VocabularyWordTable.exampleSentence],
        exampleTranslation = row[VocabularyWordTable.exampleTranslation],
        category = row[VocabularyWordTable.category],
        status = row[VocabularyWordTable.status],
        nextReviewAt = row[VocabularyWordTable.nextReviewAt],
        reviewCount = row[VocabularyWordTable.reviewCount],
        addedAt = row[VocabularyWordTable.addedAt],
    )
}
