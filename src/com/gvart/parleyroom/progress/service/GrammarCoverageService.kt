package com.gvart.parleyroom.progress.service

import com.gvart.parleyroom.progress.data.StudentGrammarCoverageTable
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import java.time.OffsetDateTime
import java.util.UUID

class GrammarCoverageService {

    /**
     * Marks every [grammarTopicIds] as covered for every [studentIds] (a COVERED floor in progress).
     * The first coverage of a student × topic wins; later calls are no-ops. Must run in a transaction
     * (joins the caller's, e.g. a draft send).
     */
    fun markCovered(teacherId: UUID, studentIds: Collection<UUID>, grammarTopicIds: Collection<UUID>, bundleId: UUID?) {
        val now = OffsetDateTime.now()
        studentIds.distinct().forEach { student ->
            grammarTopicIds.distinct().forEach { topic ->
                StudentGrammarCoverageTable.insertIgnore {
                    it[studentId] = student
                    it[grammarTopicId] = topic
                    it[StudentGrammarCoverageTable.teacherId] = teacherId
                    it[StudentGrammarCoverageTable.bundleId] = bundleId
                    it[coveredAt] = now
                }
            }
        }
    }
}
