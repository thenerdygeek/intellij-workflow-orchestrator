package com.workflow.orchestrator.core.ai

/**
 * Content blocks from parsing an LLM assistant message (Cline faithful port).
 *
 * The parser returns a list of these blocks. Each has a `partial` flag:
 * - partial=true: block is still being built (more text expected)
 * - partial=false: block is complete and ready for presentation/execution
 */
sealed class AssistantMessageContent {
    abstract val partial: Boolean
}

/**
 * Text content between tool calls (reasoning, explanations).
 * partial=true while text is still arriving before the next tool tag or stream end.
 */
data class TextContent(
    val content: String,
    override val partial: Boolean
) : AssistantMessageContent()

/**
 * A tool invocation parsed from XML tags.
 * partial=true while the closing </tool_name> tag hasn't arrived yet.
 *
 * Format: <tool_name><param1>value1</param1></tool_name>
 */
data class ToolUseContent(
    val name: String,
    val params: MutableMap<String, String> = mutableMapOf(),
    override var partial: Boolean = true
) : AssistantMessageContent()
