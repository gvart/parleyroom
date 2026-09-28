package com.gvart.parleyroom.library.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.Sql
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.document.data.DocumentGrammarTopicTable
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.document.data.DocumentTopicTable
import com.gvart.parleyroom.document.service.DocumentSupport
import com.gvart.parleyroom.lesson.data.LessonGrammarTopicTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.data.LessonTopicTable
import com.gvart.parleyroom.library.transfer.CoverageSource
import com.gvart.parleyroom.library.transfer.CoveredStudent
import com.gvart.parleyroom.library.transfer.GrammarChecklistItem
import com.gvart.parleyroom.library.transfer.GrammarLevelGroup
import com.gvart.parleyroom.library.transfer.GrammarTopicLibrary
import com.gvart.parleyroom.library.transfer.LevelCounts
import com.gvart.parleyroom.library.transfer.LibraryLessonRef
import com.gvart.parleyroom.library.transfer.LibrarySummary
import com.gvart.parleyroom.library.transfer.LibraryTotals
import com.gvart.parleyroom.library.transfer.SubtreeCounts
import com.gvart.parleyroom.library.transfer.TopicCounts
import com.gvart.parleyroom.library.transfer.TopicLibrary
import com.gvart.parleyroom.material.data.MaterialGrammarTopicTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialTopicTable
import com.gvart.parleyroom.material.service.MaterialService
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.topic.service.GrammarTopicService
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.topic.service.TopicService
import com.gvart.parleyroom.topic.transfer.TopicRef
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTopicTable
import com.gvart.parleyroom.vocabulary.service.VocabEntryService
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Read-only views over a teacher's library (brief §5.5). Every view runs a fixed number of
 * grouped queries, independent of the number of topics.
 */
class LibraryService(
    private val topicService: TopicService,
    private val grammarTopicService: GrammarTopicService,
    private val vocabEntryService: VocabEntryService,
    private val documentSupport: DocumentSupport,
    private val materialService: MaterialService,
) {

    fun summary(principal: UserPrincipal, level: LanguageLevel?): LibrarySummary = transaction {
        LibraryAccess.requireTeacher(principal)
        val teacher = principal.id

        val words = countByLevel("SELECT level::text, count(*) FROM vocab_entries WHERE teacher_id = ? GROUP BY level", teacher)
        val documents = countByLevel("SELECT level::text, count(*) FROM documents WHERE owner_id = ? GROUP BY level", teacher)
        val materials = countByLevel("SELECT level::text, count(*) FROM materials WHERE teacher_id = ? GROUP BY level", teacher)
        val grammar = countByLevel("SELECT level::text, count(*) FROM grammar_topics WHERE teacher_id = ? GROUP BY level", teacher)
        val topics = countByLevel(
            """
            SELECT l, count(*) FROM topics,
                   unnest(CASE WHEN cardinality(levels) = 0 THEN ARRAY[NULL]::varchar[] ELSE levels END) AS l
            WHERE teacher_id = ? GROUP BY l
            """.trimIndent(),
            teacher,
        )
        val levels = (LanguageLevel.entries + listOf(null)).map { l ->
            LevelCounts(l, words[l] ?: 0, documents[l] ?: 0, materials[l] ?: 0, topics[l] ?: 0, grammar[l] ?: 0)
        }
        val topicCount = Sql.long("SELECT count(*) FROM topics WHERE teacher_id = ?", teacher)
        val totals = LibraryTotals(
            words = words.values.sum(), documents = documents.values.sum(), materials = materials.values.sum(),
            topics = topicCount, grammarTopics = grammar.values.sum(),
        )

        val levelArgs = listOfNotNull(level?.name)
        val levelCond = { alias: String -> if (level != null) " AND $alias.level::text = ?" else "" }
        val directWords = countByTag(
            "SELECT t.topic_id, count(*) FROM vocab_entry_topics t JOIN vocab_entries e ON e.id = t.vocab_entry_id " +
                    "WHERE e.teacher_id = ?${levelCond("e")} GROUP BY t.topic_id", teacher, *levelArgs.toTypedArray(),
        )
        val directDocuments = countByTag(
            "SELECT t.topic_id, count(*) FROM document_topics t JOIN documents d ON d.id = t.document_id " +
                    "WHERE d.owner_id = ?${levelCond("d")} GROUP BY t.topic_id", teacher, *levelArgs.toTypedArray(),
        )
        val directMaterials = countByTag(
            "SELECT t.topic_id, count(*) FROM material_topics t JOIN materials m ON m.id = t.material_id " +
                    "WHERE m.teacher_id = ?${levelCond("m")} GROUP BY t.topic_id", teacher, *levelArgs.toTypedArray(),
        )
        val lessons = countByTag(
            "SELECT t.topic_id, count(*) FROM lesson_topics t JOIN lessons l ON l.id = t.lesson_id " +
                    "WHERE l.teacher_id = ? GROUP BY t.topic_id", teacher,
        )
        val covered = countByTag(
            "SELECT topic_id, count(DISTINCT student_id) FROM (${Coverage.lessonStudents("lesson_topics", "topic_id")} " +
                    "UNION ${Coverage.vocabStudents()}) c GROUP BY topic_id",
            teacher, teacher,
        )
        val subtreeWords = subtreeCount("vocab_entry_topics", "vocab_entry_id", "vocab_entries", "teacher_id", teacher, level)
        val subtreeDocuments = subtreeCount("document_topics", "document_id", "documents", "owner_id", teacher, level)
        val subtreeMaterials = subtreeCount("material_topics", "material_id", "materials", "teacher_id", teacher, level)

        val topicIds = TopicTable.select(TopicTable.id).where { TopicTable.teacherId eq teacher }
            .orderBy(TopicTable.name).map { it[TopicTable.id].value }
        LibrarySummary(levels, totals, topicIds.map { id ->
            TopicCounts(
                topicId = id.toString(),
                words = directWords[id] ?: 0,
                documents = directDocuments[id] ?: 0,
                materials = directMaterials[id] ?: 0,
                lessons = lessons[id] ?: 0,
                coveredStudents = covered[id] ?: 0,
                subtree = SubtreeCounts(subtreeWords[id] ?: 0, subtreeDocuments[id] ?: 0, subtreeMaterials[id] ?: 0),
            )
        })
    }

    fun topic(topicId: UUID, principal: UserPrincipal, level: LanguageLevel?): TopicLibrary = transaction {
        LibraryAccess.requireTeacher(principal)
        val all = TopicTable.selectAll().where { TopicTable.teacherId eq principal.id }.associateBy { it[TopicTable.id].value }
        val row = all[topicId] ?: throw NotFoundException("Topic not found", code = "TOPIC_NOT_FOUND")

        val path = generateSequence(row[TopicTable.parentId]?.value) { all[it]?.get(TopicTable.parentId)?.value }
            .mapNotNull { all[it] }.map(::ref).toList().reversed()
        val children = all.values.filter { it[TopicTable.parentId]?.value == topicId }.map(::ref).sortedBy { it.name.lowercase() }

        val words = VocabEntryTable.selectAll()
            .where { VocabEntryTable.id inSubQuery tagged(VocabEntryTopicTable, VocabEntryTopicTable.vocabEntryId, VocabEntryTopicTable.topicId, topicId) }
            .withLevel(VocabEntryTable.level, level)
            .orderBy(VocabEntryTable.lemma)
            .toList()
        TopicLibrary(
            topic = topicService.toResponse(row),
            path = path,
            children = children,
            words = vocabEntryService.toResponses(words),
            documents = documents(tagged(DocumentTopicTable, DocumentTopicTable.documentId, DocumentTopicTable.topicId, topicId), level, principal),
            materials = materials(tagged(MaterialTopicTable, MaterialTopicTable.materialId, MaterialTopicTable.topicId, topicId), level),
            lessons = lessons(tagged(LessonTopicTable, LessonTopicTable.lessonId, LessonTopicTable.topicId, topicId)),
            coveredBy = Coverage.students(principal.id, topicId, "lesson_topics", "topic_id", withVocab = true),
        )
    }

    fun grammarChecklist(principal: UserPrincipal, level: LanguageLevel?): List<GrammarLevelGroup> = transaction {
        LibraryAccess.requireTeacher(principal)
        val teacher = principal.id
        val query = GrammarTopicTable.selectAll().where { GrammarTopicTable.teacherId eq teacher }
        if (level != null) query.andWhere { GrammarTopicTable.level eq level }
        val rows = query.orderBy(GrammarTopicTable.position to SortOrder.ASC, GrammarTopicTable.name to SortOrder.ASC).toList()

        val documents = countByTag(
            "SELECT t.grammar_topic_id, count(*) FROM document_grammar_topics t JOIN documents d ON d.id = t.document_id " +
                    "WHERE d.owner_id = ? GROUP BY t.grammar_topic_id", teacher,
        )
        val materials = countByTag(
            "SELECT t.grammar_topic_id, count(*) FROM material_grammar_topics t JOIN materials m ON m.id = t.material_id " +
                    "WHERE m.teacher_id = ? GROUP BY t.grammar_topic_id", teacher,
        )
        val lessons = countByTag(
            "SELECT t.grammar_topic_id, count(*) FROM lesson_grammar_topics t JOIN lessons l ON l.id = t.lesson_id " +
                    "WHERE l.teacher_id = ? GROUP BY t.grammar_topic_id", teacher,
        )
        val covered = countByTag(
            "SELECT grammar_topic_id, count(DISTINCT student_id) FROM " +
                    "(${Coverage.lessonStudents("lesson_grammar_topics", "grammar_topic_id")}) c GROUP BY grammar_topic_id",
            teacher,
        )
        rows.groupBy { it[GrammarTopicTable.level] }
            .toSortedMap(compareBy(nullsLast()) { it })
            .map { (groupLevel, topics) ->
                GrammarLevelGroup(groupLevel, topics.map { row ->
                    val id = row[GrammarTopicTable.id].value
                    GrammarChecklistItem(grammarTopicService.toResponse(row), documents[id] ?: 0, materials[id] ?: 0, lessons[id] ?: 0, covered[id] ?: 0)
                })
            }
    }

    fun grammarTopic(grammarTopicId: UUID, principal: UserPrincipal): GrammarTopicLibrary = transaction {
        LibraryAccess.requireTeacher(principal)
        val row = GrammarTopicTable.selectAll()
            .where { (GrammarTopicTable.id eq grammarTopicId) and (GrammarTopicTable.teacherId eq principal.id) }
            .singleOrNull() ?: throw NotFoundException("Grammar topic not found", code = "GRAMMAR_TOPIC_NOT_FOUND")
        GrammarTopicLibrary(
            grammarTopic = grammarTopicService.toResponse(row),
            documents = documents(tagged(DocumentGrammarTopicTable, DocumentGrammarTopicTable.documentId, DocumentGrammarTopicTable.grammarTopicId, grammarTopicId), null, principal),
            materials = materials(tagged(MaterialGrammarTopicTable, MaterialGrammarTopicTable.materialId, MaterialGrammarTopicTable.grammarTopicId, grammarTopicId), null),
            lessons = lessons(tagged(LessonGrammarTopicTable, LessonGrammarTopicTable.lessonId, LessonGrammarTopicTable.grammarTopicId, grammarTopicId)),
            coveredBy = Coverage.students(principal.id, grammarTopicId, "lesson_grammar_topics", "grammar_topic_id", withVocab = false),
        )
    }

    private fun documents(ids: Query, level: LanguageLevel?, principal: UserPrincipal) = documentSupport.toSummaries(
        DocumentTable.selectAll().where { (DocumentTable.id inSubQuery ids) and (DocumentTable.ownerId eq principal.id) }
            .withLevel(DocumentTable.level, level)
            .orderBy(DocumentTable.updatedAt, SortOrder.DESC)
            .toList(),
        principal,
    )

    private fun materials(ids: Query, level: LanguageLevel?) = materialService.toResponses(
        MaterialTable.selectAll().where { MaterialTable.id inSubQuery ids }
            .withLevel(MaterialTable.level, level)
            .orderBy(MaterialTable.name)
            .toList(),
    )

    private fun lessons(ids: Query) = LessonTable.selectAll().where { LessonTable.id inSubQuery ids }
        .orderBy(LessonTable.scheduledAt, SortOrder.DESC)
        .map {
            LibraryLessonRef(
                id = it[LessonTable.id].value.toString(),
                title = it[LessonTable.title],
                scheduledAt = it[LessonTable.scheduledAt],
                status = it[LessonTable.status],
                groupId = it[LessonTable.groupId]?.value?.toString(),
            )
        }

    private fun tagged(table: Table, item: Column<EntityID<UUID>>, tag: Column<EntityID<UUID>>, tagId: UUID): Query =
        table.select(item).where { tag eq tagId }

    private fun Query.withLevel(column: Column<LanguageLevel?>, level: LanguageLevel?): Query =
        if (level == null) this else andWhere { column eq level }

    private fun ref(row: ResultRow) = TopicRef(row[TopicTable.id].value.toString(), row[TopicTable.name])

    /** `level::text, count` rows → counts keyed by level (null = no level). */
    private fun countByLevel(sql: String, vararg args: Any): Map<LanguageLevel?, Long> =
        Sql.rows(sql, *args) { rs -> (rs.getString(1)?.let(LanguageLevel::valueOf)) to rs.getLong(2) }
            .groupingBy { it.first }.fold(0L) { acc, pair -> acc + pair.second }

    /** `tag uuid, count` rows → counts keyed by tag id. */
    private fun countByTag(sql: String, vararg args: Any): Map<UUID, Long> =
        Sql.rows(sql, *args) { rs -> rs.getObject(1, UUID::class.java) to rs.getLong(2) }.toMap()

    /** Distinct items tagged with each topic or any of its descendants. */
    private fun subtreeCount(
        tagTable: String, itemColumn: String, itemTable: String, ownerColumn: String, teacher: UUID, level: LanguageLevel?,
    ): Map<UUID, Long> {
        val levelCond = if (level != null) " AND i.level::text = ?" else ""
        return countByTag(
            """
            WITH RECURSIVE sub(ancestor, topic) AS (
                SELECT id, id FROM topics WHERE teacher_id = ?
                UNION ALL
                SELECT s.ancestor, t.id FROM topics t JOIN sub s ON t.parent_id = s.topic
            )
            SELECT s.ancestor, count(DISTINCT tt.$itemColumn)
            FROM sub s
            JOIN $tagTable tt ON tt.topic_id = s.topic
            JOIN $itemTable i ON i.id = tt.$itemColumn
            WHERE i.$ownerColumn = ?$levelCond
            GROUP BY s.ancestor
            """.trimIndent(),
            teacher, teacher, *listOfNotNull(level?.name).toTypedArray(),
        )
    }
}

/**
 * "Covered by" rules (API.md): CONFIRMED participants of held lessons (not CANCELLED / REQUEST,
 * scheduled in the past) tagged with the topic, and students owning a tagged library word.
 */
private object Coverage {

    /** (tag id, student id, at) of lesson coverage; one uuid parameter: the teacher. */
    fun lessonStudents(tagTable: String, tagColumn: String) =
        "SELECT t.$tagColumn, ls.student_id FROM $tagTable t " +
                "JOIN lessons l ON l.id = t.lesson_id " +
                "JOIN lesson_students ls ON ls.lesson_id = l.id " +
                "WHERE l.teacher_id = ? AND ls.status = 'CONFIRMED' AND l.status NOT IN ('CANCELLED', 'REQUEST') " +
                "AND l.scheduled_at <= now()"

    /** (topic id, student id) of vocab coverage; one uuid parameter: the teacher. */
    fun vocabStudents() =
        "SELECT t.topic_id, sv.student_id FROM vocab_entry_topics t " +
                "JOIN vocab_entries e ON e.id = t.vocab_entry_id " +
                "JOIN student_vocab sv ON sv.vocab_entry_id = e.id " +
                "WHERE e.teacher_id = ?"

    fun students(teacher: UUID, tagId: UUID, lessonTagTable: String, lessonTagColumn: String, withVocab: Boolean): List<CoveredStudent> {
        val lessonPart = "SELECT ls.student_id, 'LESSON' AS via, l.scheduled_at AS at FROM $lessonTagTable t " +
                "JOIN lessons l ON l.id = t.lesson_id JOIN lesson_students ls ON ls.lesson_id = l.id " +
                "WHERE t.$lessonTagColumn = ? AND l.teacher_id = ? AND ls.status = 'CONFIRMED' " +
                "AND l.status NOT IN ('CANCELLED', 'REQUEST') AND l.scheduled_at <= now()"
        val vocabPart = "SELECT sv.student_id, 'VOCAB' AS via, sv.added_at AS at FROM vocab_entry_topics t " +
                "JOIN vocab_entries e ON e.id = t.vocab_entry_id JOIN student_vocab sv ON sv.vocab_entry_id = e.id " +
                "WHERE t.topic_id = ? AND e.teacher_id = ?"
        val union = if (withVocab) "$lessonPart UNION ALL $vocabPart" else lessonPart
        val args = if (withVocab) arrayOf<Any>(tagId, teacher, tagId, teacher) else arrayOf<Any>(tagId, teacher)
        data class Hit(val studentId: UUID, val firstName: String, val lastName: String, val via: CoverageSource, val at: OffsetDateTime)
        val hits = Sql.rows(
            "SELECT c.student_id, u.first_name, u.last_name, c.via, max(c.at) FROM ($union) c " +
                    "JOIN users u ON u.id = c.student_id GROUP BY c.student_id, u.first_name, u.last_name, c.via",
            *args,
        ) { rs ->
            Hit(
                rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3),
                CoverageSource.valueOf(rs.getString(4)),
                rs.getObject(5, OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC),
            )
        }
        return hits.groupBy { it.studentId }.values
            .map { rows ->
                val first = rows.first()
                CoveredStudent(
                    studentId = first.studentId.toString(),
                    firstName = first.firstName,
                    lastName = first.lastName,
                    via = rows.map { it.via }.sorted(),
                    lastAt = rows.maxOf { it.at },
                )
            }
            .sortedWith(compareBy({ it.lastName.lowercase() }, { it.firstName.lowercase() }))
    }
}
