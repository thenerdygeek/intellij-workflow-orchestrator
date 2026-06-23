package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.AssistantMessageContent
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.api.InternalApi

/**
 * Marker for the native function-calling paradigm (Anthropic Messages API). SHAPE ONLY — the
 * concrete `AnthropicNativeProtocol` ships in Phase 4 with `AnthropicDirectProvider`.
 *
 * Native invariants the seam relies on (documented here so Phase 4 cannot drift):
 *  - [presentTools] returns null (tools go in the API `tools:[]` field, not the system prompt).
 *  - [toolResultWirePrefix] is "" (tool results are structured `tool_result` blocks, not prefixed text).
 *  - [requiresDialectGuard] is false (structured output cannot drift; the drift sites bypass via the gate).
 *  - [stripPartialTag]/[endsWithIncompleteTag] are no-ops (structured streaming has no XML tags to split).
 *  - [parseToolCalls] consumes structured `content_block_delta`/`input_json_delta` frames (a different
 *    StreamChunk shape) — NOT XML text. Phase 4 widens the streaming surface accordingly.
 */
@InternalApi
interface NativeProtocol : ToolProtocol {
    override fun presentTools(tools: List<ToolDefinition>): String? = null
    override val toolResultWirePrefix: String get() = ""
    override val requiresDialectGuard: Boolean get() = false

    // GAP1 UI-splitter helpers are no-ops under native (no XML tags in structured streaming).
    override fun stripPartialTag(text: CharSequence): String = text.toString()
    override fun endsWithIncompleteTag(text: CharSequence): Boolean = false

    // parseToolCalls + classifyStreamLine are intentionally left abstract — their native shapes
    // (structured deltas; HTTP-error classification) are designed and implemented in Phase 4.
    override fun parseToolCalls(
        text: CharSequence,
        toolNames: Set<String>,
        paramNames: Set<String>,
    ): List<AssistantMessageContent>
}
