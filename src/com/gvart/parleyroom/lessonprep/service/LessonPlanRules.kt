package com.gvart.parleyroom.lessonprep.service

import com.gvart.parleyroom.ai.service.Issue
import com.gvart.parleyroom.lessonprep.transfer.LessonPlanContent
import java.util.UUID
import kotlin.math.abs

/**
 * The shape of a lesson plan, checked for the model's answer and for the teacher's edits alike.
 * Pointers are relative to the plan ("/timePlan/0/minutes").
 */
object LessonPlanRules {

    val PHASES = listOf("WARMUP", "INPUT", "GRAMMAR", "PRACTICE", "EXAM_PRACTICE", "WRAPUP")
    val ACTIVITY_TYPES = listOf("SPEAKING_CARDS", "ROLE_PLAY", "EXERCISE", "GAME", "OTHER")
    val ARTICLES = listOf("der", "die", "das")

    const val MAX_PHASES = 10
    const val MAX_PHRASES = 20
    const val MAX_WORDS = 30
    const val MAX_ACTIVITIES = 8
    const val MAX_SKIP = 10
    const val MAX_ITEM_IDS = 30
    const val MAX_PHASE_MINUTES = 240
    /** The time plan may miss the lesson length by this much. */
    const val MINUTES_TOLERANCE = 5
    private const val SHORT = 300
    private const val LONG = 2_000

    fun shape(plan: LessonPlanContent): List<Issue> {
        val issues = mutableListOf<Issue>()
        fun text(pointer: String, value: String?, max: Int, required: Boolean = true) {
            when {
                value.isNullOrBlank() -> if (required) issues += Issue(pointer, "is required")
                value.length > max -> issues += Issue(pointer, "must be at most $max characters")
            }
        }
        fun count(pointer: String, size: Int, min: Int, max: Int) {
            if (size !in min..max) issues += Issue(pointer, "must have $min..$max entries")
        }

        text("/lessonGoal", plan.lessonGoal, SHORT)
        count("/timePlan", plan.timePlan.size, 1, MAX_PHASES)
        plan.timePlan.take(MAX_PHASES).forEachIndexed { i, phase ->
            if (phase.phase !in PHASES) issues += Issue("/timePlan/$i/phase", "must be one of ${PHASES.joinToString()}")
            text("/timePlan/$i/title", phase.title, 200)
            if (phase.minutes !in 1..MAX_PHASE_MINUTES) issues += Issue("/timePlan/$i/minutes", "must be 1..$MAX_PHASE_MINUTES")
            text("/timePlan/$i/description", phase.description, LONG, required = false)
        }
        count("/phrases", plan.phrases.size, 0, MAX_PHRASES)
        plan.phrases.take(MAX_PHRASES).forEachIndexed { i, phrase ->
            text("/phrases/$i/de", phrase.de, SHORT)
            text("/phrases/$i/note", phrase.note, SHORT, required = false)
        }
        count("/vocabulary", plan.vocabulary.size, 0, MAX_WORDS)
        plan.vocabulary.take(MAX_WORDS).forEachIndexed { i, word ->
            text("/vocabulary/$i/lemma", word.lemma, 100)
            if (word.article != null && word.article !in ARTICLES) issues += Issue("/vocabulary/$i/article", "must be der, die, das or null")
            text("/vocabulary/$i/plural", word.plural, 100, required = false)
            text("/vocabulary/$i/example", word.example, SHORT)
        }
        count("/activities", plan.activities.size, 0, MAX_ACTIVITIES)
        plan.activities.take(MAX_ACTIVITIES).forEachIndexed { i, activity ->
            if (activity.type !in ACTIVITY_TYPES) issues += Issue("/activities/$i/type", "must be one of ${ACTIVITY_TYPES.joinToString()}")
            text("/activities/$i/title", activity.title, 200)
            text("/activities/$i/instructions", activity.instructions, LONG)
            text("/activities/$i/materialsNeeded", activity.materialsNeeded, SHORT, required = false)
        }
        count("/skipIfShort", plan.skipIfShort.size, 0, MAX_SKIP)
        plan.skipIfShort.take(MAX_SKIP).forEachIndexed { i, skip -> text("/skipIfShort/$i", skip, SHORT) }
        plan.suggestedHomework?.let {
            text("/suggestedHomework/summary", it.summary, 500)
            text("/suggestedHomework/prompt", it.prompt, LONG)
        }
        count("/roadmapItemIds", plan.roadmapItemIds.size, 0, MAX_ITEM_IDS)
        if (plan.roadmapItemIds.toSet().size != plan.roadmapItemIds.size) issues += Issue("/roadmapItemIds", "must not repeat an id")
        return issues
    }

    /** Stored plans (and teacher edits) name roadmap items by uuid. */
    fun uuidIssues(plan: LessonPlanContent): List<Issue> = plan.roadmapItemIds.mapIndexedNotNull { i, id ->
        Issue("/roadmapItemIds/$i", "must be a UUID").takeIf { runCatching { UUID.fromString(id) }.isFailure }
    }

    /** The model's answer: [shape], a time plan that fills the lesson, a suggested homework, and only item keys it was given. */
    fun generated(plan: LessonPlanContent, lessonMinutes: Int, itemKeys: Set<String>): List<Issue> {
        val issues = shape(plan).toMutableList()
        val total = plan.timePlan.sumOf { it.minutes }
        if (abs(total - lessonMinutes) > MINUTES_TOLERANCE)
            issues += Issue("/timePlan", "the minutes add up to $total but the lesson has $lessonMinutes (±$MINUTES_TOLERANCE)")
        if (plan.suggestedHomework == null) issues += Issue("/suggestedHomework", "is required")
        plan.roadmapItemIds.forEachIndexed { i, key ->
            if (key !in itemKeys) issues += Issue("/roadmapItemIds/$i", "unknown item '$key': use only keys from <roadmap>")
        }
        return issues
    }
}
