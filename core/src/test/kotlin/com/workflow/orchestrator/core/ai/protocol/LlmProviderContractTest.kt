package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.LlmProvider
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmProviderContractTest {
    @Test fun `LlmProvider is an LlmBrain and exposes a ToolProtocol`() {
        // A minimal anonymous provider verifies the interface shape compiles + carries a protocol.
        val provider: LlmProvider = object : LlmProvider {
            override val modelId = "test::v1::model"
            override val toolProtocol = XmlToolProtocol()
            override suspend fun chat(messages: List<com.workflow.orchestrator.core.ai.dto.ChatMessage>, tools: List<com.workflow.orchestrator.core.ai.dto.ToolDefinition>?, maxTokens: Int?, toolChoice: kotlinx.serialization.json.JsonElement?) =
                ApiResult.Error(ErrorType.SERVER_ERROR, "unused")
            override suspend fun chatStream(messages: List<com.workflow.orchestrator.core.ai.dto.ChatMessage>, tools: List<com.workflow.orchestrator.core.ai.dto.ToolDefinition>?, maxTokens: Int?, onChunk: suspend (com.workflow.orchestrator.core.ai.dto.StreamChunk) -> Unit) =
                ApiResult.Error(ErrorType.SERVER_ERROR, "unused")
            override fun estimateTokens(text: String) = text.length / 4
            override suspend fun getCatalog(force: Boolean) = null
            override fun getContextWindow(modelRef: String, tier: String) = null
            override fun supportsVision(modelRef: String) = false
            override fun supportsTools(modelRef: String) = true
            override fun getDefaultChatModel(): String? = null
            override fun getLatestStreamApiVersion() = 8
            override fun getStatus(modelRef: String): String? = null
            override fun buildFallbackChain(): List<String> = emptyList()
            override fun classifyStreamLine(line: String): String? = toolProtocol.classifyStreamLine(line)
            override fun classifyHttpError(statusCode: Int, body: String): String? = null
        }
        assertTrue(provider is LlmBrain)
        assertNotNull(provider.toolProtocol)
        assertTrue(provider.supportsTools("any"))
    }
}
