package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ToolDefinition

/**
 * Converts ToolDefinition objects to XML schemas for system prompt injection.
 * Ported from Cline's approach where tools are defined in the system prompt
 * instead of the `tools: [...]` request parameter.
 *
 * Output format:
 * ```
 * <tool_definitions>
 *   <tool>
 *     <tool_name>read_file</tool_name>
 *     <description>Read a file's contents</description>
 *     <parameters>
 *       <parameter name="path" type="string" required="true">
 *         The file path to read
 *       </parameter>
 *     </parameters>
 *   </tool>
 *   ...
 * </tool_definitions>
 *
 * <tool_usage_instructions>
 * To call a tool, output XML in this exact format:
 * <tool>
 *   <name>tool_name</name>
 *   <args>
 *     <param_name>value</param_name>
 *   </args>
 * </tool>
 * </tool_usage_instructions>
 * ```
 */
object XmlToolDefinitionBuilder {

    fun build(tools: List<ToolDefinition>): String {
        val sb = StringBuilder()
        sb.appendLine("<tool_definitions>")
        for (tool in tools) {
            sb.appendLine("  <tool>")
            sb.appendLine("    <tool_name>${tool.function.name}</tool_name>")
            sb.appendLine("    <description>${escapeXml(tool.function.description)}</description>")

            val params = tool.function.parameters
            if (params.properties.isNotEmpty()) {
                sb.appendLine("    <parameters>")
                val requiredSet = params.required.toSet()

                for ((name, prop) in params.properties) {
                    val isRequired = name in requiredSet
                    sb.appendLine("      <parameter name=\"$name\" type=\"${prop.type}\" required=\"$isRequired\">")
                    sb.appendLine("        ${escapeXml(prop.description)}")
                    sb.appendLine("      </parameter>")
                }
                sb.appendLine("    </parameters>")
            }

            sb.appendLine("  </tool>")
        }
        sb.appendLine("</tool_definitions>")
        sb.appendLine()
        sb.appendLine(USAGE_INSTRUCTIONS)
        return sb.toString()
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val USAGE_INSTRUCTIONS = """
<tool_usage_instructions>
To call a tool, output XML in this exact format:
<tool>
  <name>tool_name</name>
  <args>
    <param_name>value</param_name>
  </args>
</tool>

Rules:
- After reasoning, ALWAYS emit your tool call as XML. Do not describe tool calls in text.
- You may call multiple tools in one response by emitting multiple <tool> blocks.
- Each <tool> block must have exactly one <name> and one <args> child.
- Parameter values can span multiple lines (e.g., file content).
- Do NOT use native function calling — always use XML tool tags.
- IMPORTANT: Even for large code outputs (100+ lines), you MUST wrap them in a <tool> block.
  Never output code in a fenced code block when a tool call is expected. Always use the
  appropriate tool (create_file, edit_file) with the code in the <content> parameter.
</tool_usage_instructions>
    """.trimIndent()
}
