package com.workflow.orchestrator.agent.migration

import com.workflow.orchestrator.agent.session.ApiMessage
import com.workflow.orchestrator.agent.session.ApiRole
import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.agent.session.toChatMessage
import com.workflow.orchestrator.core.ai.MessageSanitizer
import com.workflow.orchestrator.core.ai.dto.ChatMessage

/**
 * Scenario helpers for XML-in-content migration tests.
 *
 * Each helper simulates a slice of the production pipeline so individual
 * scenarios can assert on the right boundary:
 *   - [parseAssistantResponse]: raw LLM text → parsed blocks (parser only)
 *   - [persistAssistantTurn]: raw LLM text → on-disk ContentBlock list
 *   - [renderForNextTurn]: ApiMessage history → outbound ChatMessage list
 *     after MessageSanitizer (the actual wire input shape)
 */
object XmlInContentScenarios {

    fun persistAssistantTurn(rawText: String): List<ContentBlock> =
        listOf(ContentBlock.Text(rawText))

    fun renderForNextTurn(history: List<ApiMessage>): List<ChatMessage> {
        val chat = history.map { it.toChatMessage() }
        return MessageSanitizer.sanitizeForAnthropic(chat)
    }

    fun userTurn(text: String) =
        ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text(text)))

    fun assistantTurn(rawText: String) =
        ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text(rawText)))

    fun legacyAssistantWithToolUse(prose: String, toolName: String, paramsJson: String) =
        ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(
                ContentBlock.Text(prose),
                ContentBlock.ToolUse(id = "toolu_test", name = toolName, input = paramsJson)
            )
        )

    fun toolResultTurn(toolUseId: String, content: String) =
        ApiMessage(
            role = ApiRole.USER,
            content = listOf(ContentBlock.ToolResult(toolUseId = toolUseId, content = content))
        )
}
