package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.AssistantMessageContent
import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.GatewayErrorDetector
import com.workflow.orchestrator.core.ai.ToolPromptBuilder
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.api.InternalApi

/**
 * Today's XML-in-content tool-calling paradigm (Sourcegraph/Cody), wrapped behind [ToolProtocol].
 *
 * Pure delegation — behavior is byte-identical to calling ToolPromptBuilder / AssistantMessageParser /
 * GatewayErrorDetector directly (pinned by XmlToolProtocolCharacterizationTest). The single normalized
 * finish-reason string "upstream_timeout" is the same one AgentLoop already keys on (AgentLoop.kt:1571).
 *
 * The XML parser's known-tag inputs (`toolNameSet`/`paramNameSet`) are NOT held here in 0b-1 — they stay
 * on `LlmBrain` and are passed into [parseToolCalls] as parameters. Their relocation onto the protocol is
 * deferred to Phase 4 (transport-coupled — see the plan's Out-of-scope section).
 */
@InternalApi
class XmlToolProtocol : ToolProtocol {

    override fun presentTools(tools: List<ToolDefinition>): String = ToolPromptBuilder.build(tools)

    override fun parseToolCalls(
        text: CharSequence,
        toolNames: Set<String>,
        paramNames: Set<String>,
    ): List<AssistantMessageContent> = AssistantMessageParser.parse(text, toolNames, paramNames)

    override fun stripPartialTag(text: CharSequence): String = AssistantMessageParser.stripPartialTag(text)

    override fun endsWithIncompleteTag(text: CharSequence): Boolean = AssistantMessageParser.endsWithIncompleteTag(text)

    override val toolResultWirePrefix: String = TOOL_RESULT_WIRE_PREFIX

    override val requiresDialectGuard: Boolean = true

    override fun classifyStreamLine(line: String): String? =
        if (GatewayErrorDetector.isUpstreamTimeoutFrame(line)) UPSTREAM_TIMEOUT else null

    companion object {
        /** Wire-send tool-result prefix. The single source of truth; MessageSanitizer references it (Task 6). */
        const val TOOL_RESULT_WIRE_PREFIX = "TOOL RESULT:\n"
        /** Normalized finish reason consumed by AgentLoop.kt:1571. */
        const val UPSTREAM_TIMEOUT = "upstream_timeout"
    }
}
