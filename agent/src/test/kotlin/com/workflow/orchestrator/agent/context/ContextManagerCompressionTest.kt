package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatCompletionResponse
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.Choice
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContextManagerCompressionTest {

    // Budget: maxInputTokens=10000, tMax=7000, tRetained=4000
    // TokenEstimator: ~1 token per 3.5 chars + 4 overhead per message
    // A 350-char message ≈ 104 tokens.
    // We build context to ~5000 tokens (above tRetained=4000, below tMax=7000).
    // Auto-compress never fires (needs >7000). compressWithLlm compresses down to ~4000.

    companion object {
        private const val MAX_TOKENS = 10_000
        private const val T_MAX_RATIO = 0.70    // tMax = 7000
        private const val T_RETAINED_RATIO = 0.40 // tRetained = 4000
    }

    private fun createMockBrain(response: ApiResult<ChatCompletionResponse>): LlmBrain {
        return mockk {
            coEvery { chat(any(), any(), any(), any()) } returns response
            every { estimateTokens(any()) } answers { (firstArg<String>().length / 3.5).toInt() + 1 }
            every { modelId } returns "test-model"
        }
    }

    private fun successResponse(content: String): ApiResult<ChatCompletionResponse> {
        return ApiResult.Success(
            ChatCompletionResponse(
                id = "test-id",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = content),
                        finishReason = "stop"
                    )
                ),
                usage = null
            )
        )
    }

    private fun createCm(): ContextManager = ContextManager(
        maxInputTokens = MAX_TOKENS,
        tMaxRatio = T_MAX_RATIO,
        tRetainedRatio = T_RETAINED_RATIO
    )

    /**
     * Build context above tRetained (4000) but below tMax (7000) with tool messages.
     * Auto-compress never fires. compressWithLlm will find messages to drop.
     * Tool messages are among the oldest non-system = droppable.
     */
    private fun buildAboveRetainedWithTool(): ContextManager {
        val cm = createCm()
        cm.addMessage(ChatMessage(role = "system", content = "You are a helpful assistant."))

        // Add a tool message early (will be among oldest non-system)
        cm.addMessage(ChatMessage(role = "user", content = "Analyze the build log"))
        cm.addMessage(ChatMessage(
            role = "tool",
            content = "Build FAILED: src/Main.kt:42 NullPointerException in method foo(). " +
                "Stack trace: at com.example.Main.foo(Main.kt:42) at com.example.Main.main(Main.kt:10)",
            toolCallId = "tc-1"
        ))
        cm.addMessage(ChatMessage(role = "assistant", content = "I see the build failed with an NPE."))

        // Add more messages to push above tRetained=4000
        // Each 350-char pair ≈ 208 tokens. Need ~4000 total. Already have ~80.
        // Need ~(4000-80)/208 ≈ 19 pairs. Use 20 to be safe.
        for (i in 1..20) {
            cm.addMessage(ChatMessage(role = "user", content = "User msg $i: ${"X".repeat(350)}"))
            cm.addMessage(ChatMessage(role = "assistant", content = "Asst msg $i: ${"Y".repeat(350)}"))
        }

        // Should be above tRetained but below tMax
        assertTrue(cm.currentTokens > 4000, "Expected >4000 tokens, got ${cm.currentTokens}")
        assertTrue(cm.currentTokens < 7000, "Expected <7000 tokens (no auto-compress), got ${cm.currentTokens}")

        return cm
    }

    /**
     * Build context above tRetained but below tMax WITHOUT any tool messages.
     */
    private fun buildAboveRetainedWithoutTool(): ContextManager {
        val cm = createCm()
        cm.addMessage(ChatMessage(role = "system", content = "You are a helpful assistant."))

        for (i in 1..21) {
            cm.addMessage(ChatMessage(role = "user", content = "User msg $i: ${"X".repeat(350)}"))
            cm.addMessage(ChatMessage(role = "assistant", content = "Asst msg $i: ${"Y".repeat(350)}"))
        }

        assertTrue(cm.currentTokens > 4000, "Expected >4000 tokens, got ${cm.currentTokens}")
        assertTrue(cm.currentTokens < 7000, "Expected <7000 tokens, got ${cm.currentTokens}")

        return cm
    }

    @Test
    fun `compressWithLlm uses LLM when tool results are present`() = runTest {
        val mockBrain = createMockBrain(successResponse("Summary: src/Main.kt:42 NPE in foo()"))
        val cm = buildAboveRetainedWithTool()

        val messagesBefore = cm.messageCount
        val tokensBefore = cm.currentTokens

        cm.compressWithLlm(mockBrain)

        // LLM should have been called because tool messages are in the dropped set
        coVerify(exactly = 1) { mockBrain.chat(any(), isNull(), eq(500), isNull()) }

        // Messages should be fewer after compression
        assertTrue(cm.messageCount < messagesBefore, "Should have fewer messages after compression")

        // Tokens should be reduced
        assertTrue(cm.currentTokens < tokensBefore, "Tokens should decrease after compression")

        // LLM summary should appear in anchored summaries (system message)
        val allMsgs = cm.getMessages()
        assertTrue(
            allMsgs.any { it.role == "system" && it.content?.contains("LLM") == true },
            "Should contain LLM-generated summary"
        )
    }

    @Test
    fun `compressWithLlm skips LLM when no tool results in dropped messages`() = runTest {
        val mockBrain = createMockBrain(successResponse("Should not be called"))
        val cm = buildAboveRetainedWithoutTool()

        cm.compressWithLlm(mockBrain)

        // LLM should NOT have been called — no tool messages in dropped set
        coVerify(exactly = 0) { mockBrain.chat(any(), any(), any(), any()) }

        // Truncation summary should exist
        val allMsgs = cm.getMessages()
        assertTrue(
            allMsgs.any { it.role == "system" && it.content?.contains("Compressed Context Summary") == true },
            "Should have truncation summary"
        )
    }

    @Test
    fun `compressWithLlm falls back to truncation on LLM error`() = runTest {
        val errorResult: ApiResult<ChatCompletionResponse> = ApiResult.Error(ErrorType.SERVER_ERROR, "LLM unavailable")
        val mockBrain = createMockBrain(errorResult)
        val cm = buildAboveRetainedWithTool()

        // Should NOT throw
        cm.compressWithLlm(mockBrain)

        // LLM was called (tool messages present) but returned error
        coVerify(exactly = 1) { mockBrain.chat(any(), any(), any(), any()) }

        // Fallback truncation summary should exist
        val allMsgs = cm.getMessages()
        assertTrue(
            allMsgs.any { it.role == "system" && it.content?.contains("Compressed Context Summary") == true },
            "Should have fallback truncation summary despite LLM error"
        )
    }

    @Test
    fun `compress adds boundary marker warning about lossy context`() {
        // Use tight thresholds so auto-compression fires
        val cm = ContextManager(
            maxInputTokens = 3000,
            tMaxRatio = 0.60,    // tMax = 1800
            tRetainedRatio = 0.30 // tRetained = 900
        )
        cm.addMessage(ChatMessage(role = "system", content = "You are a helpful assistant."))

        // Add enough messages to trigger auto-compress (>1800 tokens)
        for (i in 1..15) {
            cm.addMessage(ChatMessage(role = "user", content = "User msg $i: ${"X".repeat(200)}"))
            cm.addMessage(ChatMessage(role = "assistant", content = "Asst msg $i: ${"Y".repeat(200)}"))
        }

        // After auto-compression, the boundary marker should be in the context
        val allMsgs = cm.getMessages()
        val systemContent = allMsgs.filter { it.role == "system" }.mapNotNull { it.content }.joinToString("\n")
        assertTrue(
            systemContent.contains("CONTEXT COMPRESSED") || systemContent.contains("lossy"),
            "Should contain compression boundary marker, got: ${systemContent.take(500)}"
        )
    }

    @Test
    fun `compressWithLlm adds boundary marker`() = runTest {
        val mockBrain = createMockBrain(successResponse("Summary: analyzed build logs"))
        val cm = buildAboveRetainedWithTool()

        cm.compressWithLlm(mockBrain)

        val allMsgs = cm.getMessages()
        val systemContent = allMsgs.filter { it.role == "system" }.mapNotNull { it.content }.joinToString("\n")
        assertTrue(
            systemContent.contains("CONTEXT COMPRESSED"),
            "Should contain compression boundary marker after LLM compression"
        )
        assertTrue(
            systemContent.contains("lossy summary"),
            "Boundary marker should warn about lossy summary"
        )
        assertTrue(
            systemContent.contains("re-read a file before editing"),
            "Boundary marker should instruct to re-read files"
        )
    }

    @Test
    fun `compressWithLlm does nothing when context is small`() = runTest {
        val mockBrain = createMockBrain(successResponse("Should not be called"))
        val cm = createCm()

        cm.addMessage(ChatMessage(role = "user", content = "Short message"))

        val messagesBefore = cm.messageCount
        cm.compressWithLlm(mockBrain)

        // Below tRetained, nothing to compress (tokensToRemove <= 0)
        assertEquals(messagesBefore, cm.messageCount)
        coVerify(exactly = 0) { mockBrain.chat(any(), any(), any(), any()) }
    }
}
