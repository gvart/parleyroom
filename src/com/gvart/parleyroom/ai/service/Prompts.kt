package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.data.DraftMode
import com.gvart.parleyroom.document.service.DocumentBlockValidator

/**
 * Prompt texts. System prompts live in `resources/ai/` (editable without code changes); the user
 * message is a set of tagged sections, which the fake provider also reads.
 */
object Prompts {

    const val TASK_GENERATE = "generate"
    const val TASK_REFINE = "refine"
    const val TASK_REFINE_DOCUMENT = "refine_document"
    const val TASK_FILL = "fill_translations"
    const val TASK_SUGGEST_TAGS = "suggest_tags"
    const val TASK_SENTENCE_FEEDBACK = "sentence_feedback"
    const val VALIDATION_ERRORS = "validation_errors"

    /** Block types numbered "Übung n" in the portal; keep identical to `isExercise` in the portal's documents/lib/blocks.ts. */
    val EXERCISE_BLOCK_TYPES = listOf(
        "gap_fill", "multiple_choice", "error_correction", "free_sentences", "writing_task", "reading", "media", "exam_part", "free_form",
    )

    /** The app numbers exercises itself, and the teacher refers to them by that number. */
    val exerciseNumbering: String =
        "The teacher refers to exercises as 'Übung N': N counts only exercise blocks (types ${EXERCISE_BLOCK_TYPES.joinToString()}) " +
                "in document order, starting at 1. The app shows these numbers itself, so do not write numbers into titles or instructions."

    private fun resource(name: String): String =
        Prompts::class.java.classLoader.getResource("ai/$name")!!.readText()

    private val blockRules: String by lazy {
        resource("blocks.md").replace("{{BLOCK_SCHEMA}}", DocumentBlockValidator.schemaText.trim())
    }
    private val draftSystem: String by lazy { resource("nachbereitung-system.md").replace("{{BLOCK_RULES}}", blockRules) }
    private val clubSystem: String by lazy { resource("nachbereitung-club.md") }
    val documentRefineSystem: String by lazy { resource("document-refine-system.md").replace("{{BLOCK_RULES}}", blockRules) }
    val fillSystem: String by lazy { resource("fill-translations-system.md") }
    val suggestTagsSystem: String by lazy { resource("suggest-tags-system.md") }
    val sentenceFeedbackSystem: String by lazy { resource("sentence-feedback-system.md") }

    fun draftSystem(mode: DraftMode): String =
        if (mode == DraftMode.CLUB) draftSystem + "\n\n" + clubSystem else draftSystem

    /** The `<target>` of a refine. */
    fun targetName(target: DraftTarget): String = when (target) {
        DraftTarget.BUNDLE -> "bundle"
        DraftTarget.WORD -> "word"
        DraftTarget.EXERCISE_DOCUMENT -> "document"
        DraftTarget.TASK -> "task"
        DraftTarget.NOTES_DOCUMENT -> "notes"
    }

    fun generate(mode: DraftMode, context: String, notes: String, pastNotes: String, prompt: String): String = buildString {
        section("task", TASK_GENERATE)
        section("mode", mode.name)
        section("context", context)
        section("notes", notes.ifBlank { "(none)" })
        section("past_lesson_notes", pastNotes.ifBlank { "(none)" })
        section("teacher_instructions", prompt.ifBlank { "(no special instructions: follow the defaults)" })
        append(exerciseNumbering)
    }

    fun refine(
        mode: DraftMode, target: DraftTarget, context: String, notes: String, pastNotes: String, prompt: String,
        currentOutput: String, instruction: String,
    ): String = buildString {
        section("task", TASK_REFINE)
        section("mode", mode.name)
        section("target", targetName(target))
        section("context", context)
        section("notes", notes.ifBlank { "(none)" })
        section("past_lesson_notes", pastNotes.ifBlank { "(none)" })
        section("teacher_instructions", prompt.ifBlank { "(none)" })
        section("current_output", currentOutput)
        section("refine_instruction", instruction)
        append(exerciseNumbering).append('\n')
        append("Apply the refine instruction to current_output and keep everything else as it is ")
        append("(including the teacher's manual edits). Answer with the JSON object for the target only.")
    }

    fun refineDocument(level: String, libraryWords: String, currentDocument: String, instruction: String): String = buildString {
        section("task", TASK_REFINE_DOCUMENT)
        section("level", level)
        section("library_words", libraryWords.ifBlank { "(none)" })
        section("current_document", currentDocument)
        section("refine_instruction", instruction)
        append(exerciseNumbering)
    }

    fun fill(entries: String, fields: List<String>): String = buildString {
        section("task", TASK_FILL)
        section("fields", fields.joinToString(", "))
        section("entries", entries)
    }

    /** Only the material's name, its extracted text and the library names: nothing about students. */
    fun suggestTags(name: String, sourceKind: String, text: String, topicPaths: List<String>, grammar: List<String>): String = buildString {
        section("task", TASK_SUGGEST_TAGS)
        section("material_name", name)
        section("source", sourceKind)
        // The text is the teacher's file: keep it from closing our sections.
        section("material_text", text.replace("</", "< /").ifBlank { "(no text: use the name only)" })
        section("library_topics", topicPaths.joinToString("\n").ifEmpty { "(empty library)" })
        section("library_grammar_topics", grammar.joinToString("\n").ifEmpty { "(empty library)" })
    }

    /** Only the sentence, the word data, the level and a language code: nothing about the student. */
    fun sentenceFeedback(sentence: String, targetWord: String, level: String, translationLanguage: String?): String = buildString {
        section("task", TASK_SENTENCE_FEEDBACK)
        section("level", level)
        section("target_word", targetWord)
        // The sentence is student input: keep it from closing our sections.
        section("sentence", sentence.replace("</", "< /"))
        translationLanguage?.let { section("translation_language", it) }
    }

    fun retry(issues: List<Issue>): String = buildString {
        section(VALIDATION_ERRORS, issues.joinToString("\n") { "- ${it.pointer.ifEmpty { "/" }}: ${it.message}" })
        append("Your answer was rejected. Fix every problem listed above and answer again with the complete JSON object only.")
    }

    /** The text of a `<name>…</name>` section, or null. */
    fun section(text: String, name: String): String? {
        val start = text.indexOf("<$name>").takeIf { it >= 0 } ?: return null
        val end = text.indexOf("</$name>", start).takeIf { it >= 0 } ?: return null
        return text.substring(start + name.length + 2, end).trim()
    }

    private fun StringBuilder.section(name: String, body: String) {
        append("<").append(name).append(">\n").append(body.trim()).append("\n</").append(name).append(">\n\n")
    }
}
