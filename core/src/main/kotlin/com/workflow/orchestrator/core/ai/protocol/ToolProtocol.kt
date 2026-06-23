package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.AssistantMessageContent
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.api.InternalApi

/**
 * Abstraction over a model's tool-calling PARADIGM (orthogonal to transport, which is [com.workflow.orchestrator.core.ai.LlmProvider]).
 *
 * Two paradigms (spec §6):
 *  - XML-in-content (Sourcegraph/Cody, today): tool defs injected into the system prompt as XML,
 *    tool calls emitted as XML in the model's text, parsed by [AssistantMessageContent], results
 *    sent as plain-text "TOOL RESULT:" user turns, kept on-dialect by DialectDriftDetector.
 *  - Native function-calling (Anthropic Messages API, Phase 4): tools in tools:[], structured
 *    tool_use blocks out / tool_result blocks back, no text parsing, no drift machinery.
 *
 * @InternalApi: public so plugin B may implement it later, but unfrozen — we may change it; B recompiles in lockstep.
 */
@InternalApi
interface ToolProtocol {

    /**
     * Tool presentation. XML returns the system-prompt markdown block (today's
     * `ToolPromptBuilder.build`). Native returns null (tools go in the `tools:[]` API
     * field, not the prompt) — the §6c gate in SystemPrompt.build already no-ops on null.
     */
    fun presentTools(tools: List<ToolDefinition>): String?

    /**
     * Response segmentation + tool-call extraction. For XML this is the per-SSE-chunk
     * streaming splitter AND the end-of-stream tool decoder (today's `AssistantMessageParser.parse`).
     *
     * Takes the parser's known tool/param name sets as PARAMETERS (0b-1 still sources them from
     * `LlmBrain.toolNameSet`/`paramNameSet` at the call site; their relocation onto the protocol is
     * a Phase-4 transport-rewiring concern — see the plan's Out-of-scope section).
     */
    fun parseToolCalls(
        text: CharSequence,
        toolNames: Set<String>,
        paramNames: Set<String>,
    ): List<AssistantMessageContent>

    /**
     * UI-splitter helper (GAP1): strip a trailing partial/incomplete XML tag from streaming display
     * text. XML delegates to `AssistantMessageParser.stripPartialTag` (signature `(CharSequence): String`,
     * verified `AssistantMessageParser.kt:192`). Presentation-path only — keeps the visible delta clean
     * while a tool tag is mid-stream. Native returns the text unchanged.
     */
    fun stripPartialTag(text: CharSequence): String

    /**
     * UI-splitter helper (GAP1): true when the accumulated streaming text ends inside an unclosed
     * `<…>` tag. XML delegates to `AssistantMessageParser.endsWithIncompleteTag` (signature
     * `(CharSequence): Boolean`, verified `AssistantMessageParser.kt:217`). Used by AgentLoop to
     * suppress leaking a tool tag's body to the display. Native returns false.
     */
    fun endsWithIncompleteTag(text: CharSequence): Boolean

    /**
     * Wire-send tool-result prefix (XML = "TOOL RESULT:\n"; native = "" because tool_result is
     * a structured block). This is the WIRE convention only — on-disk tool results are always the
     * structured `ContentBlock.ToolResult`, never this prefix.
     */
    val toolResultWirePrefix: String

    /**
     * True when DialectDriftDetector machinery applies (XML only). Gates the drift wiring sites
     * (chokepoint at `MessageStateHandler.consumeDialectDriftFlag` + the resume redaction).
     * Native is structurally drift-free → false.
     */
    val requiresDialectGuard: Boolean

    /**
     * Provider-/paradigm-specific stream-line error classification. XML wraps
     * `GatewayErrorDetector.isUpstreamTimeoutFrame` → "upstream_timeout"; returns null when the
     * line needs no special handling. Native maps HTTP error events (Phase 4).
     */
    fun classifyStreamLine(line: String): String?
}
