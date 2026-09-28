package com.gvart.parleyroom.document.service

import com.gvart.parleyroom.common.service.singleOrNotFound
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.document.data.DocumentVersionReason
import com.gvart.parleyroom.document.data.DocumentVersionTable
import com.gvart.parleyroom.document.transfer.DocumentResponse
import com.gvart.parleyroom.document.transfer.DocumentVersionResponse
import com.gvart.parleyroom.document.transfer.DocumentVersionSummary
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Version history: snapshots of title + blocks taken before a change. Autosaves snapshot
 * at most every [AUTOSAVE_INTERVAL]; share, restore and duplicate always snapshot.
 */
class DocumentVersionService(
    private val support: DocumentSupport,
) {

    fun listVersions(documentId: UUID, principal: UserPrincipal): List<DocumentVersionSummary> = transaction {
        support.requireOwnedOrAdmin(documentId, principal)
        DocumentVersionTable.selectAll()
            .where { DocumentVersionTable.documentId eq documentId }
            .orderBy(DocumentVersionTable.number, SortOrder.DESC)
            .map {
                DocumentVersionSummary(
                    id = it[DocumentVersionTable.id].value.toString(),
                    number = it[DocumentVersionTable.number],
                    reason = it[DocumentVersionTable.reason],
                    title = it[DocumentVersionTable.title],
                    blockCount = it[DocumentVersionTable.blocks].size,
                    createdBy = it[DocumentVersionTable.createdBy]?.value?.toString(),
                    createdAt = it[DocumentVersionTable.createdAt],
                )
            }
    }

    fun getVersion(documentId: UUID, versionId: UUID, principal: UserPrincipal): DocumentVersionResponse = transaction {
        support.requireOwnedOrAdmin(documentId, principal)
        val row = findVersion(documentId, versionId)
        DocumentVersionResponse(
            id = row[DocumentVersionTable.id].value.toString(),
            documentId = documentId.toString(),
            number = row[DocumentVersionTable.number],
            reason = row[DocumentVersionTable.reason],
            title = row[DocumentVersionTable.title],
            blockCount = row[DocumentVersionTable.blocks].size,
            createdBy = row[DocumentVersionTable.createdBy]?.value?.toString(),
            blocks = row[DocumentVersionTable.blocks],
            createdAt = row[DocumentVersionTable.createdAt],
        )
    }

    fun restore(documentId: UUID, versionId: UUID, principal: UserPrincipal): DocumentResponse = transaction {
        val document = support.requireOwned(documentId, principal)
        val version = findVersion(documentId, versionId)
        snapshot(document, DocumentVersionReason.RESTORE, principal)
        DocumentTable.update({ DocumentTable.id eq documentId }) {
            it[title] = version[DocumentVersionTable.title]
            it[blocks] = version[DocumentVersionTable.blocks]
        }
        support.bumpRevision(documentId)
        support.toResponse(support.findDocument(documentId), principal)
    }

    /** Snapshot for an autosave: only when there is none yet or the latest is old enough. */
    fun snapshotForAutosave(document: ResultRow, principal: UserPrincipal) {
        val latest = DocumentVersionTable.select(DocumentVersionTable.createdAt)
            .where { DocumentVersionTable.documentId eq document[DocumentTable.id] }
            .orderBy(DocumentVersionTable.number, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(DocumentVersionTable.createdAt)
        if (latest == null || !latest.isAfter(OffsetDateTime.now().minus(AUTOSAVE_INTERVAL)))
            snapshot(document, DocumentVersionReason.AUTOSAVE, principal)
    }

    /** Stores the document's current title + blocks and keeps only the newest [MAX_VERSIONS]. */
    fun snapshot(document: ResultRow, reason: DocumentVersionReason, principal: UserPrincipal) {
        val documentId = document[DocumentTable.id].value
        val number = (DocumentVersionTable.select(DocumentVersionTable.number)
            .where { DocumentVersionTable.documentId eq documentId }
            .orderBy(DocumentVersionTable.number, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(DocumentVersionTable.number) ?: 0) + 1
        DocumentVersionTable.insert {
            it[DocumentVersionTable.documentId] = documentId
            it[DocumentVersionTable.number] = number
            it[DocumentVersionTable.reason] = reason
            it[title] = document[DocumentTable.title]
            it[blocks] = document[DocumentTable.blocks]
            it[createdBy] = principal.id
            it[createdAt] = OffsetDateTime.now()
        }
        DocumentVersionTable.deleteWhere {
            (DocumentVersionTable.documentId eq documentId) and (DocumentVersionTable.number lessEq number - MAX_VERSIONS)
        }
    }

    private fun findVersion(documentId: UUID, versionId: UUID): ResultRow = DocumentVersionTable.selectAll()
        .where { (DocumentVersionTable.id eq versionId) and (DocumentVersionTable.documentId eq documentId) }
        .singleOrNotFound("Document version")

    companion object {
        val AUTOSAVE_INTERVAL: Duration = Duration.ofMinutes(10)
        const val MAX_VERSIONS = 30
    }
}
