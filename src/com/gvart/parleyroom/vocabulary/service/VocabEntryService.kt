package com.gvart.parleyroom.vocabulary.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.group.service.GroupService
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.LessonVocabTable
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryGrammarTopicTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTopicTable
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.vocabulary.transfer.AssignVocabRequest
import com.gvart.parleyroom.vocabulary.transfer.AssignVocabResponse
import com.gvart.parleyroom.vocabulary.transfer.QuickAddVocabRequest
import com.gvart.parleyroom.vocabulary.transfer.QuickAddVocabResponse
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryInput
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryPageResponse
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryResponse
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

class VocabEntryService(
    private val groupService: GroupService,
) {

    fun listEntries(
        principal: UserPrincipal,
        q: String?,
        topicId: UUID?,
        level: LanguageLevel?,
        wordType: WordType?,
        page: PageRequest,
    ): VocabEntryPageResponse = transaction {
        if (principal.role == UserRole.STUDENT) throw ForbiddenException("Students cannot browse the library")
        val query = VocabEntryTable.selectAll()
        if (principal.role == UserRole.TEACHER) query.andWhere { VocabEntryTable.teacherId eq principal.id }
        if (!q.isNullOrBlank()) query.andWhere { VocabEntryTable.lemma.lowerCase() like "%${escapeLike(q.trim().lowercase())}%" }
        if (topicId != null) query.andWhere {
            VocabEntryTable.id inSubQuery VocabEntryTopicTable.select(VocabEntryTopicTable.vocabEntryId)
                .where { VocabEntryTopicTable.topicId eq topicId }
        }
        if (level != null) query.andWhere { VocabEntryTable.level eq level }
        if (wordType != null) query.andWhere { VocabEntryTable.wordType eq wordType }

        val total = query.count()
        val rows = query.orderBy(VocabEntryTable.lemma).limit(page.pageSize).offset(page.offset).toList()
        VocabEntryPageResponse(toResponses(rows), total, page.page, page.pageSize)
    }

    fun getEntry(entryId: UUID, principal: UserPrincipal): VocabEntryResponse = transaction {
        toResponses(listOf(requireOwnedEntry(entryId, principal))).single()
    }

    fun createEntry(input: VocabEntryInput, principal: UserPrincipal): VocabEntryResponse = transaction {
        LibraryAccess.requireTeacher(principal)
        if (findDuplicate(principal.id, input, excludeId = null) != null)
            throw ConflictException("'${input.lemma.trim()}' is already in your library", code = "VOCAB_ENTRY_DUPLICATE")
        val id = insertEntry(principal.id, input)
        toResponses(listOf(VocabEntryTable.findByIdOrThrow(id, "Vocab entry"))).single()
    }

    fun updateEntry(entryId: UUID, input: VocabEntryInput, principal: UserPrincipal): VocabEntryResponse = transaction {
        val row = requireOwnedEntry(entryId, principal)
        val teacherId = row[VocabEntryTable.teacherId].value
        if (findDuplicate(teacherId, input, excludeId = entryId) != null)
            throw ConflictException("'${input.lemma.trim()}' is already in your library", code = "VOCAB_ENTRY_DUPLICATE")
        val topicIds = validateInput(teacherId, input)
        val grammarIds = input.grammarTopicIds?.let { LibraryAccess.requireGrammarTopics(teacherId, it) }
        VocabEntryTable.update({ VocabEntryTable.id eq entryId }) { applyInput(it, input) }
        replaceTopics(entryId, topicIds)
        grammarIds?.let { replaceGrammarTopics(entryId, it) }
        toResponses(listOf(VocabEntryTable.findByIdOrThrow(entryId, "Vocab entry"))).single()
    }

    /** Deleting an entry also removes it from every student's vocabulary. */
    fun deleteEntry(entryId: UUID, principal: UserPrincipal) = transaction {
        requireOwnedEntry(entryId, principal)
        VocabEntryTable.deleteWhere { id eq entryId }
    }

    fun assign(entryId: UUID, request: AssignVocabRequest, principal: UserPrincipal): AssignVocabResponse = transaction {
        val row = requireOwnedEntry(entryId, principal)
        val teacherId = row[VocabEntryTable.teacherId].value
        val lessonId = request.lessonId?.let { requireOwnLesson(teacherId, UUID.fromString(it)) }
        val targets = resolveTargets(teacherId, request.studentIds, request.groupId, lessonId, principal)
        if (lessonId != null) linkToLesson(lessonId, entryId)
        assignToStudents(entryId, targets, lessonId)
    }

    fun quickAdd(request: QuickAddVocabRequest, principal: UserPrincipal): QuickAddVocabResponse = transaction {
        LibraryAccess.requireTeacher(principal)
        val lessonId = request.lessonId?.let { requireOwnLesson(principal.id, UUID.fromString(it)) }
        val input = if (lessonId != null && request.entry.sourceLessonId == null)
            request.entry.copy(sourceLessonId = lessonId.toString()) else request.entry

        val existing = findDuplicate(principal.id, input, excludeId = null)
        val entryId = existing?.get(VocabEntryTable.id)?.value ?: insertEntry(principal.id, input)

        val targets = resolveTargets(principal.id, request.studentIds, request.groupId, lessonId, principal)
        if (lessonId != null) linkToLesson(lessonId, entryId)
        val result = assignToStudents(entryId, targets, lessonId)

        QuickAddVocabResponse(
            entry = toResponses(listOf(VocabEntryTable.findByIdOrThrow(entryId, "Vocab entry"))).single(),
            reused = existing != null,
            assigned = result.assigned,
            skipped = result.skipped,
        )
    }

    /** Batch-maps entry rows to responses (loads topic links in one query). Must run in a transaction. */
    fun toResponses(rows: List<ResultRow>): List<VocabEntryResponse> {
        if (rows.isEmpty()) return emptyList()
        val topicsByEntry = topicIdsByEntry(rows.map { it[VocabEntryTable.id].value })
        val grammarByEntry = grammarTopicIdsByEntry(rows.map { it[VocabEntryTable.id].value })
        return rows.map { row ->
            val id = row[VocabEntryTable.id].value
            VocabEntryResponse(
                id = id.toString(),
                teacherId = row[VocabEntryTable.teacherId].value.toString(),
                lemma = row[VocabEntryTable.lemma],
                article = row[VocabEntryTable.article],
                plural = row[VocabEntryTable.plural],
                wordType = row[VocabEntryTable.wordType],
                forms = row[VocabEntryTable.forms],
                government = row[VocabEntryTable.government],
                translations = row[VocabEntryTable.translations],
                explanationDe = row[VocabEntryTable.explanationDe],
                exampleSentence = row[VocabEntryTable.exampleSentence],
                level = row[VocabEntryTable.level],
                topicIds = topicsByEntry[id].orEmpty().map(UUID::toString),
                synonyms = row[VocabEntryTable.synonyms],
                sourceLessonId = row[VocabEntryTable.sourceLessonId]?.value?.toString(),
                grammarTopicIds = grammarByEntry[id].orEmpty().map(UUID::toString),
                createdAt = row[VocabEntryTable.createdAt],
                updatedAt = row[VocabEntryTable.updatedAt],
            )
        }
    }

    private fun requireOwnedEntry(entryId: UUID, principal: UserPrincipal): ResultRow {
        val row = VocabEntryTable.findByIdOrThrow(entryId, "Vocab entry")
        AuthorizationHelper.requireOwnerOrAdmin(row[VocabEntryTable.teacherId].value, principal, "Not your vocab entry")
        return row
    }

    private fun findDuplicate(teacherId: UUID, input: VocabEntryInput, excludeId: UUID?): ResultRow? {
        val articleCond: Op<Boolean> =
            input.article?.let { VocabEntryTable.article eq it } ?: VocabEntryTable.article.isNull()
        val query = VocabEntryTable.selectAll().where {
            (VocabEntryTable.teacherId eq teacherId) and
                    (VocabEntryTable.lemma.lowerCase() eq input.lemma.trim().lowercase()) and
                    (VocabEntryTable.wordType eq input.wordType) and
                    articleCond
        }
        if (excludeId != null) query.andWhere { VocabEntryTable.id neq excludeId }
        return query.singleOrNull()
    }

    private fun insertEntry(teacherId: UUID, input: VocabEntryInput): UUID {
        val topicIds = validateInput(teacherId, input)
        val grammarIds = LibraryAccess.requireGrammarTopics(teacherId, input.grammarTopicIds.orEmpty())
        val now = OffsetDateTime.now()
        val id = VocabEntryTable.insertAndGetId {
            it[VocabEntryTable.teacherId] = teacherId
            applyInput(it, input)
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        replaceTopics(id, topicIds)
        replaceGrammarTopics(id, grammarIds)
        return id
    }

    private fun validateInput(teacherId: UUID, input: VocabEntryInput): List<UUID> {
        VocabDisplay.requireSupportedLanguages(input.translations)
        input.sourceLessonId?.let { requireOwnLesson(teacherId, UUID.fromString(it)) }
        return LibraryAccess.requireTopics(teacherId, input.topicIds)
    }

    private fun applyInput(builder: UpdateBuilder<*>, input: VocabEntryInput) {
        builder[VocabEntryTable.lemma] = input.lemma.trim()
        builder[VocabEntryTable.article] = input.article
        builder[VocabEntryTable.plural] = input.plural
        builder[VocabEntryTable.wordType] = input.wordType
        builder[VocabEntryTable.forms] = input.forms
        builder[VocabEntryTable.government] = input.government
        builder[VocabEntryTable.translations] = input.translations.filterValues { it.isNotBlank() }
        builder[VocabEntryTable.explanationDe] = input.explanationDe
        builder[VocabEntryTable.exampleSentence] = input.exampleSentence
        builder[VocabEntryTable.level] = input.level
        builder[VocabEntryTable.synonyms] = input.synonyms
        builder[VocabEntryTable.sourceLessonId] = input.sourceLessonId?.let(UUID::fromString)
    }

    private fun replaceTopics(entryId: UUID, topicIds: List<UUID>) {
        VocabEntryTopicTable.deleteWhere { vocabEntryId eq entryId }
        if (topicIds.isEmpty()) return
        VocabEntryTopicTable.batchInsert(topicIds) { topicId ->
            this[VocabEntryTopicTable.vocabEntryId] = entryId
            this[VocabEntryTopicTable.topicId] = topicId
        }
    }

    private fun replaceGrammarTopics(entryId: UUID, grammarTopicIds: List<UUID>) {
        VocabEntryGrammarTopicTable.deleteWhere { vocabEntryId eq entryId }
        if (grammarTopicIds.isEmpty()) return
        VocabEntryGrammarTopicTable.batchInsert(grammarTopicIds) { grammarTopicId ->
            this[VocabEntryGrammarTopicTable.vocabEntryId] = entryId
            this[VocabEntryGrammarTopicTable.grammarTopicId] = grammarTopicId
        }
    }

    private fun requireOwnLesson(teacherId: UUID, lessonId: UUID): UUID {
        val lesson = LessonTable.findByIdOrThrow(lessonId, "Lesson")
        if (lesson[LessonTable.teacherId].value != teacherId)
            throw ForbiddenException("Lesson belongs to another teacher")
        return lessonId
    }

    private fun resolveTargets(
        teacherId: UUID,
        studentIds: List<String>,
        groupId: String?,
        lessonId: UUID?,
        principal: UserPrincipal,
    ): List<UUID> {
        val explicit = studentIds.map(UUID::fromString)
        val fromGroup = groupId?.let { id ->
            val uuid = UUID.fromString(id)
            groupService.requireOwnedGroup(uuid, principal)
            groupService.memberIds(uuid)
        }.orEmpty()
        val targets = (explicit + fromGroup).distinct()
        if (targets.isEmpty() && lessonId != null && groupId == null) {
            return LessonStudentTable.selectAll()
                .where { (LessonStudentTable.lessonId eq lessonId) and (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED) }
                .map { it[LessonStudentTable.studentId].value }
        }
        if (explicit.isNotEmpty()) {
            val linked = TeacherStudentTable.selectAll()
                .where { (TeacherStudentTable.teacherId eq teacherId) and (TeacherStudentTable.studentId inList explicit) }
                .map { it[TeacherStudentTable.studentId].value }
                .toSet()
            val unlinked = explicit.filter { it !in linked }
            if (unlinked.isNotEmpty())
                throw BadRequestException(
                    "Teacher does not have a relationship with students: ${unlinked.joinToString()}",
                    code = "STUDENT_NOT_LINKED",
                )
        }
        return targets
    }

    private fun linkToLesson(lessonId: UUID, entryId: UUID) {
        val exists = !LessonVocabTable.selectAll()
            .where { (LessonVocabTable.lessonId eq lessonId) and (LessonVocabTable.vocabEntryId eq entryId) }
            .empty()
        if (exists) return
        val maxIndex = LessonVocabTable.orderIndex.max()
        val next = LessonVocabTable.select(maxIndex)
            .where { LessonVocabTable.lessonId eq lessonId }
            .singleOrNull()?.get(maxIndex)?.plus(1) ?: 0
        LessonVocabTable.insert {
            it[LessonVocabTable.lessonId] = lessonId
            it[vocabEntryId] = entryId
            it[orderIndex] = next
        }
    }

    private fun assignToStudents(entryId: UUID, studentIds: List<UUID>, lessonId: UUID?): AssignVocabResponse {
        if (studentIds.isEmpty()) return AssignVocabResponse(0, 0)
        val already = StudentVocabTable.selectAll()
            .where { (StudentVocabTable.vocabEntryId eq entryId) and (StudentVocabTable.studentId inList studentIds) }
            .map { it[StudentVocabTable.studentId].value }
            .toSet()
        val fresh = studentIds.filter { it !in already }
        val now = OffsetDateTime.now()
        StudentVocabTable.batchInsert(fresh) { studentId ->
            this[StudentVocabTable.studentId] = studentId
            this[StudentVocabTable.vocabEntryId] = entryId
            this[StudentVocabTable.lessonId] = lessonId
            this[StudentVocabTable.addedAt] = now
        }
        return AssignVocabResponse(assigned = fresh.size, skipped = already.size)
    }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        /** Topic ids per entry. Must run in a transaction. */
        fun topicIdsByEntry(entryIds: List<UUID>): Map<UUID, List<UUID>> {
            if (entryIds.isEmpty()) return emptyMap()
            return VocabEntryTopicTable.selectAll()
                .where { VocabEntryTopicTable.vocabEntryId inList entryIds }
                .groupBy({ it[VocabEntryTopicTable.vocabEntryId].value }) { it[VocabEntryTopicTable.topicId].value }
        }

        /** Grammar topic ids per entry. Must run in a transaction. */
        fun grammarTopicIdsByEntry(entryIds: List<UUID>): Map<UUID, List<UUID>> {
            if (entryIds.isEmpty()) return emptyMap()
            return VocabEntryGrammarTopicTable.selectAll()
                .where { VocabEntryGrammarTopicTable.vocabEntryId inList entryIds }
                .groupBy({ it[VocabEntryGrammarTopicTable.vocabEntryId].value }) { it[VocabEntryGrammarTopicTable.grammarTopicId].value }
        }
    }
}
