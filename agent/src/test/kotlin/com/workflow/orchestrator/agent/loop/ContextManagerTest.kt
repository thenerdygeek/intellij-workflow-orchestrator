package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ContextManagerTest {

    private lateinit var cm: ContextManager

    @BeforeEach
    fun setUp() {
        cm = ContextManager(maxInputTokens = 1000)
    }

    // ---- Message management ----

    @Nested
    inner class MessageManagement {

        @Test
        fun `system prompt is first in getMessages`() {
            cm.setSystemPrompt("You are a helpful assistant.")
            cm.addUserMessage("Hello")

            val messages = cm.getMessages()
            assertEquals("system", messages[0].role)
            assertEquals("You are a helpful assistant.", messages[0].content)
            assertEquals(2, messages.size)
        }

        @Test
        fun `messages tracked in order - user, assistant, tool`() {
            cm.setSystemPrompt("sys")
            cm.addUserMessage("What is 2+2?")
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant",
                    content = "Let me calculate that.",
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_1",
                            type = "function",
                            function = FunctionCall(name = "calculator", arguments = "{\"expr\":\"2+2\"}")
                        )
                    )
                )
            )
            cm.addToolResult(toolCallId = "call_1", content = "4", isError = false)

            val messages = cm.getMessages()
            assertEquals(4, messages.size)
            assertEquals("system", messages[0].role)
            assertEquals("user", messages[1].role)
            assertEquals("assistant", messages[2].role)
            assertEquals("tool", messages[3].role)
        }

        @Test
        fun `tool results have correct role and toolCallId`() {
            cm.addToolResult(toolCallId = "call_abc", content = "result text", isError = false)

            val messages = cm.getMessages()
            // No system prompt set, so first message is the tool result
            assertEquals(1, messages.size)
            val msg = messages[0]
            assertEquals("tool", msg.role)
            assertEquals("call_abc", msg.toolCallId)
            assertEquals("result text", msg.content)
        }

        @Test
        fun `error tool results include error prefix in content`() {
            cm.addToolResult(toolCallId = "call_err", content = "file not found", isError = true)

            val msg = cm.getMessages()[0]
            assertEquals("tool", msg.role)
            assertTrue(msg.content!!.contains("ERROR"))
            assertTrue(msg.content!!.contains("file not found"))
        }

        @Test
        fun `messageCount returns count of non-system messages`() {
            cm.setSystemPrompt("sys")
            cm.addUserMessage("hi")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "hello"))
            assertEquals(2, cm.messageCount())
        }

        @Test
        fun `setSystemPrompt replaces previous system prompt`() {
            cm.setSystemPrompt("first")
            cm.setSystemPrompt("second")
            cm.addUserMessage("hi")

            val messages = cm.getMessages()
            assertEquals(2, messages.size)
            assertEquals("second", messages[0].content)
        }
    }

    // ---- Token tracking ----

    @Nested
    inner class TokenTracking {

        @Test
        fun `utilization uses API-reported tokens when available`() {
            cm = ContextManager(maxInputTokens = 1000)
            cm.updateTokens(promptTokens = 500)

            assertEquals(50.0, cm.utilizationPercent(), 0.01)
        }

        @Test
        fun `utilization uses local estimate when no API data`() {
            cm = ContextManager(maxInputTokens = 1000)
            cm.setSystemPrompt("sys") // 3 bytes -> ~0.75 tokens
            cm.addUserMessage("hello world") // 11 bytes -> ~2.75 tokens

            // Should use bytes/4 estimate, result should be > 0 and < 100
            val util = cm.utilizationPercent()
            assertTrue(util > 0.0, "utilization should be positive")
            assertTrue(util < 100.0, "utilization should be under 100%")
        }

        @Test
        fun `shouldCompact returns false below threshold`() {
            cm = ContextManager(maxInputTokens = 1000, compactionThreshold = 0.85)
            cm.updateTokens(promptTokens = 800) // 80%
            assertFalse(cm.shouldCompact())
        }

        @Test
        fun `shouldCompact returns true above threshold`() {
            cm = ContextManager(maxInputTokens = 1000, compactionThreshold = 0.85)
            cm.updateTokens(promptTokens = 860) // 86%
            assertTrue(cm.shouldCompact())
        }

        @Test
        fun `tokenEstimate returns bytes div 4 estimate`() {
            cm.setSystemPrompt("abcd") // 4 bytes -> 1 token
            cm.addUserMessage("abcdefgh") // 8 bytes -> 2 tokens

            // estimate for "abcd" + "abcdefgh" = 12 bytes / 4 = 3
            assertEquals(3, cm.tokenEstimate())
        }
    }

    // ---- Compaction stage 1: Trim old tool results ----

    @Nested
    inner class TrimOldToolResults {

        @Test
        fun `replaces old tool results with placeholder but keeps recent ones`() {
            cm.setSystemPrompt("sys")
            // Add 8 tool results
            for (i in 1..8) {
                cm.addAssistantMessage(
                    ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            ToolCall(id = "call_$i", type = "function", function = FunctionCall(name = "tool_$i", arguments = "{}"))
                        )
                    )
                )
                cm.addToolResult(toolCallId = "call_$i", content = "x".repeat(100), isError = false)
            }

            cm.trimOldToolResults(keepRecent = 5)

            val messages = cm.getMessages()
            val toolMessages = messages.filter { it.role == "tool" }

            // First 3 tool results should be trimmed
            for (i in 0..2) {
                assertTrue(
                    toolMessages[i].content!!.contains("[Result trimmed"),
                    "Tool result $i should be trimmed"
                )
            }
            // Last 5 should be intact
            for (i in 3..7) {
                assertEquals("x".repeat(100), toolMessages[i].content)
            }
        }

        @Test
        fun `no-op when fewer tool results than keepRecent`() {
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant", content = null,
                    toolCalls = listOf(ToolCall(id = "c1", type = "function", function = FunctionCall("t", "{}")))
                )
            )
            cm.addToolResult(toolCallId = "c1", content = "short", isError = false)

            cm.trimOldToolResults(keepRecent = 5)

            val tool = cm.getMessages().first { it.role == "tool" }
            assertEquals("short", tool.content)
        }
    }

    // ---- Compaction stage 2: LLM summarization ----

    @Nested
    inner class LlmSummarization {

        @Test
        fun `replaces old messages with summary from LLM`() = runTest {
            val fakeBrain = FakeLlmBrain(summaryResponse = "TASK: Testing\nFILES: none\nDONE: nothing\nERRORS: none\nPENDING: test")
            cm = ContextManager(maxInputTokens = 1000)
            cm.setSystemPrompt("sys")
            // Add 10 user/assistant exchanges
            for (i in 1..10) {
                cm.addUserMessage("question $i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "answer $i"))
            }
            val countBefore = cm.messageCount()

            // Simulate API reporting 900/1000 tokens used (90%) to trigger stage 2 compaction
            cm.updateTokens(promptTokens = 900)
            cm.compact(fakeBrain)

            // After summarization: message count should be smaller
            assertTrue(cm.messageCount() < countBefore, "message count should decrease after compaction")
            // The summary should be present as a user message
            val messages = cm.getMessages()
            val summaryMsg = messages.find { it.content?.contains("TASK:") == true }
            assertNotNull(summaryMsg, "summary message should exist")
        }

        @Test
        fun `compact preserves system prompt`() = runTest {
            val fakeBrain = FakeLlmBrain(summaryResponse = "TASK: test\nFILES: a.kt")
            cm.setSystemPrompt("important system prompt")
            for (i in 1..10) {
                cm.addUserMessage("q$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }

            // Simulate high utilization to trigger compaction
            cm.updateTokens(promptTokens = 900)
            cm.compact(fakeBrain)

            val messages = cm.getMessages()
            assertEquals("system", messages[0].role)
            assertEquals("important system prompt", messages[0].content)
        }
    }

    // ---- Compaction stage 3: Sliding window ----

    @Nested
    inner class SlidingWindow {

        @Test
        fun `keeps only recent messages`() {
            cm.setSystemPrompt("sys")
            for (i in 1..10) {
                cm.addUserMessage("msg $i")
            }

            cm.slidingWindow(keepFraction = 0.3)

            val messages = cm.getMessages()
            // system + last 3 out of 10 messages
            assertEquals(4, messages.size)
            assertEquals("system", messages[0].role)
            assertEquals("msg 8", messages[1].content)
            assertEquals("msg 9", messages[2].content)
            assertEquals("msg 10", messages[3].content)
        }

        @Test
        fun `sliding window with no system prompt`() {
            for (i in 1..10) {
                cm.addUserMessage("msg $i")
            }

            cm.slidingWindow(keepFraction = 0.5)

            val messages = cm.getMessages()
            assertEquals(5, messages.size)
            assertEquals("msg 6", messages[0].content)
        }
    }

    // ---- Fake LlmBrain for testing ----

    private class FakeLlmBrain(private val summaryResponse: String) : LlmBrain {
        override val modelId: String = "fake-model"

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> {
            return ApiResult.Success(
                ChatCompletionResponse(
                    id = "fake-id",
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = ChatMessage(role = "assistant", content = summaryResponse),
                            finishReason = "stop"
                        )
                    ),
                    usage = UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150)
                )
            )
        }

        override suspend fun chatStream(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            onChunk: suspend (StreamChunk) -> Unit
        ): ApiResult<ChatCompletionResponse> {
            throw UnsupportedOperationException("Not used in compaction")
        }

        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
    }
}
