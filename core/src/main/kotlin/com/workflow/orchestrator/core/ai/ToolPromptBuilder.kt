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
            appendLine()
        }
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val FORMAT_INSTRUCTIONS = """
# Tool Use Format

You have tools to assist you. To call a tool, write its name as an XML tag with each parameter as a child tag. For example, to read a file:

<read_file>
<path>src/main/kotlin/Example.kt</path>
</read_file>

The exact tag names and parameters for each available tool are defined in the catalog below.

- Always use the XML format shown above — do not use JSON or code blocks for tool calls.
- The tool's own tag is the outermost wrapper — never nest the call inside any other container tag.
- You may use multiple tools in one response.
- For parameters containing code (content, new_string, old_string, diff), write the code directly — no escaping needed.
- Even for large code blocks (100+ lines), use the appropriate tool with XML tags.
    """.trimIndent()
}
