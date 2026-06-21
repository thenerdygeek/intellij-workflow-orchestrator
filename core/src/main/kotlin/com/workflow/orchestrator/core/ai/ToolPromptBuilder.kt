package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ToolDefinition

/**
 * Builds markdown tool definitions for system prompt injection (Cline faithful port).
 *
 * Ported from Cline's PromptBuilder.buildUsageSection(). Each tool becomes a
 * markdown section with description, parameter list, and XML usage example.
 */
object ToolPromptBuilder {

    fun build(tools: List<ToolDefinition>): String = buildString {
        appendLine(FORMAT_INSTRUCTIONS)
        appendLine()
        for (tool in tools) {
            append(sectionFor(tool))
            appendLine()
        }
    }

    /**
     * Per-tool emitted-section size in characters (name + description + params + usage),
     * for measuring which tools dominate the §6c tool-definitions block. Sorted descending.
     * Diagnostic only — does not affect the prompt.
     */
    fun perToolSizes(tools: List<ToolDefinition>): List<Pair<String, Int>> =
        tools.map { it.function.name to sectionFor(it).length }
            .sortedByDescending { it.second }

    private fun sectionFor(tool: ToolDefinition): String = buildString {
        val fn = tool.function
        appendLine("## ${fn.name}")
        appendLine("Description: ${escapeXml(fn.description)}")
        val params = fn.parameters
        if (params.properties.isNotEmpty()) {
            appendLine("Parameters:")
            val requiredSet = params.required.toSet()
            for ((name, prop) in params.properties) {
                val req = if (name in requiredSet) "required" else "optional"
                appendLine("- $name: ($req) ${escapeXml(prop.description)}")
                if (!prop.enumValues.isNullOrEmpty()) {
                    appendLine("    Allowed values: ${prop.enumValues.joinToString(", ")}")
                }
            }
        }
        appendLine("Usage:")
        appendLine("<${fn.name}>")
        for ((name, _) in params.properties) {
            appendLine("<$name>$name value here</$name>")
        }
        appendLine("</${fn.name}>")
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val FORMAT_INSTRUCTIONS = """
# Tool Use Format

You have tools to assist you. To use a tool, write the XML tag for that tool with its parameters as child tags:

<tool_name>
<parameter_name>value</parameter_name>
</tool_name>

- Always use the XML format shown above — do not use JSON or code blocks for tool calls.
- You may use multiple tools in one response.
- For parameters containing code (content, new_string, old_string, diff), write the code directly — no escaping needed.
- Even for large code blocks (100+ lines), use the appropriate tool with XML tags.
    - To run a long tool in the background and keep working, add a sibling tag <run_in_background>true</run_in_background> to that tool call. The tool starts detached; you get a brief “started in background” acknowledgement and its result is delivered to you automatically when it finishes. Use this only for slow, independent work (builds, long shell commands, broad searches) — never for a result you need before your next step. Not all tools support it (control-flow tools ignore it and run inline).
    """.trimIndent()
}
