package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.config.AiRuntime
import com.gvart.parleyroom.ai.data.DraftBundleTable
import com.gvart.parleyroom.ai.data.DraftScope
import com.gvart.parleyroom.ai.transfer.DraftBundleResponse
import com.gvart.parleyroom.ai.transfer.DraftKind
import com.gvart.parleyroom.ai.transfer.GenerateDraftRequest
import com.gvart.parleyroom.ai.transfer.OnboardingMaterial
import com.gvart.parleyroom.ai.transfer.OnboardingResponse
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.registration.data.RegistrationMaterialTable
import com.gvart.parleyroom.registration.data.RegistrationTable
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Onboarding of an invited student: on signup a student-scope words draft is started from the
 * materials the teacher attached to the invite (their lessons elsewhere). The teacher reviews and
 * sends it like any draft; its Send also marks the grammar covered ([DraftSendService]).
 */
class OnboardingService(
    private val ai: AiRuntime,
    private val drafts: DraftBundleService,
) {

    private val log = LoggerFactory.getLogger(OnboardingService::class.java)
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    /** Starts the draft without blocking or failing the signup: without AI or readable materials nothing happens. */
    fun startInBackground(studentId: UUID, teacherId: UUID) {
        if (ai.gateway == null) return
        if (transaction { readableMaterialIds(studentId, teacherId) }.isEmpty()) return
        scope.launch {
            runCatching { start(studentId, teacherId) }
                .onFailure { log.warn("Onboarding draft for student {} (teacher {}) not started", studentId, teacherId, it) }
        }
    }

    /**
     * Generates the onboarding words draft as the teacher. An open student draft that is not an
     * onboarding one is the teacher's own work: 409 AI_DRAFT_OPEN instead of overwriting it.
     */
    fun start(studentId: UUID, teacherId: UUID): DraftBundleResponse {
        GenerationJobs.requireGateway(ai)
        val (principal, materialIds) = transaction {
            val open = drafts.openBundle(DraftScope.STUDENT, teacherId, null, studentId)
            if (open != null && !open[DraftBundleTable.onboarding])
                throw ConflictException("The student has an open AI draft: send or discard it first", code = "AI_DRAFT_OPEN")
            val teacher = UserTable.selectAll().where { UserTable.id eq teacherId }.single()
            UserPrincipal(teacherId, teacher[UserTable.email], teacher[UserTable.role]) to readableMaterialIds(studentId, teacherId)
        }
        if (materialIds.isEmpty())
            throw BadRequestException("The invite has no materials with readable text", code = "AI_MATERIAL_NO_TEXT")
        val request = GenerateDraftRequest(
            prompt = Prompts.ONBOARDING_INSTRUCTIONS,
            kinds = listOf(DraftKind.WORDS),
            materialIds = materialIds.map(UUID::toString),
        )
        return drafts.generateForStudent(studentId, request, principal, onboarding = true, requireText = false)
    }

    fun status(studentId: UUID, principal: UserPrincipal): OnboardingResponse = transaction {
        requireTeacherOf(studentId, principal)
        val latest = DraftBundleTable.selectAll()
            .where {
                (DraftBundleTable.scope eq DraftScope.STUDENT) and (DraftBundleTable.teacherId eq principal.id) and
                        (DraftBundleTable.studentId eq studentId) and (DraftBundleTable.onboarding eq true)
            }
            .orderBy(DraftBundleTable.createdAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
        OnboardingResponse(
            aiAvailable = ai.gateway != null,
            materials = inviteMaterials(studentId, principal.id).map {
                OnboardingMaterial(it[MaterialTable.id].value.toString(), it[MaterialTable.name], MaterialSources.isReadable(it))
            },
            bundle = latest?.let(drafts::summary),
        )
    }

    /** The teacher starts (again) the onboarding draft; errors are returned, not logged. */
    fun retry(studentId: UUID, principal: UserPrincipal): DraftBundleResponse {
        transaction { requireTeacherOf(studentId, principal) }
        return start(studentId, principal.id)
    }

    fun shutdown() {
        supervisor.cancel()
    }

    private fun requireTeacherOf(studentId: UUID, principal: UserPrincipal) {
        LibraryAccess.requireTeacher(principal)
        AuthorizationHelper.requireAccessToStudent(studentId, principal)
    }

    /** The materials (still existing) of the teacher's invite the student signed up with. Must run in a transaction. */
    private fun inviteMaterials(studentId: UUID, teacherId: UUID): List<ResultRow> =
        (RegistrationTable innerJoin RegistrationMaterialTable innerJoin MaterialTable)
            .selectAll()
            .where { (RegistrationTable.userId eq studentId) and (RegistrationTable.invitedBy eq teacherId) }
            .orderBy(RegistrationMaterialTable.createdAt to SortOrder.ASC, MaterialTable.name to SortOrder.ASC)
            .toList()

    private fun readableMaterialIds(studentId: UUID, teacherId: UUID): List<UUID> =
        inviteMaterials(studentId, teacherId).filter(MaterialSources::isReadable)
            .map { it[MaterialTable.id].value }.distinct().take(MaterialSources.MAX_MATERIALS)
}
