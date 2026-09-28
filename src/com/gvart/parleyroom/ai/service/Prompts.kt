package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.transfer.NachbereitungMode
import com.gvart.parleyroom.document.service.DocumentBlockValidator

/**
 * Prompt texts. System prompts live in `resources/ai/` (editable without code changes); the user
 * message is a set of tagged sections, which the fake provider also reads.
 */
object Prompts {

    const val TASK_GENERATE = "generate"
    const val TASK_REFINE = "refine"
    const val TASK_FILL = "fill_translations"
    const val TASK_SUGGEST_TAGS = "suggest_tags"
    const val VALIDATION_ERRORS = "validation_errors"

    private fun resource(name: String): String =
        Prompts::class.java.classLoader.getResource("ai/$name")!!.readText()

    private val nachbereitungSystem: String by lazy {
        resource("nachbereitung-system.md").replace("{{BLOCK_SCHEMA}}", DocumentBlockValidator.schemaText.trim())
    }
    private val clubSystem: String by lazy { resource("nachbereitung-club.md") }
    val fillSystem: String by lazy { resource("fill-translations-system.md") }
    val suggestTagsSystem: String by lazy { resource("suggest-tags-system.md") }

    fun nachbereitungSystem(mode: NachbereitungMode): String =
        if (mode == NachbereitungMode.CLUB) nachbereitungSystem + "\n\n" + clubSystem else nachbereitungSystem

    fun generate(mode: NachbereitungMode, context: String, notes: String, prompt: String): String = buildString {
        section("task", TASK_GENERATE)
        section("mode", mode.name)
        section("context", context)
        section("notes", notes)
        section("teacher_instructions", prompt.ifBlank { "(no special instructions: follow the defaults)" })
    }

    fun refine(mode: NachbereitungMode, context: String, notes: String, prompt: String, currentOutput: String, instruction: String): String =
        buildString {
            section("task", TASK_REFINE)
            section("mode", mode.name)
            section("context", context)
            section("notes", notes)
            section("teacher_instructions", prompt.ifBlank { "(none)" })
            section("current_output", currentOutput)
            section("refine_instruction", instruction)
            append("Apply the refine instruction to current_output and keep everything else as it is ")
            append("(including the teacher's manual edits). Answer with the complete new JSON object.")
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
