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
            cm.setSystemPrompt("sys")
            cm.addUserMessage("hello world")

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
        fun `tokenEstimate uses chars div 3_5 plus overhead plus tool tokens`() {
            cm.setSystemPrompt("abcd")       // 4 chars
            cm.addUserMessage("abcdefgh")    // 8 chars

            // chars = 4 + 8 = 12, messageTokens = (12 / 3.5).toInt() = 3
            // overhead = 2 * 4 = 8 (system prompt + 1 message)
            // toolDefinitionTokens = 0 (default)
            // total = 3 + 8 + 0 = 11
            assertEquals(11, cm.tokenEstimate())

            // With tool definitions set, they're included
            cm.setToolDefinitionTokens(500)
            assertEquals(511, cm.tokenEstimate())
        }
    }

    // ---- Stage 1: Duplicate file read detection (from Cline) ----

    @Nested
    inner class DuplicateFileReadDetection {

        @Test
        fun `replaces older file reads with dedup notice keeping latest`() {
            cm.setSystemPrompt("sys")

            // First read of file a.kt
            cm.addUserMessage("Read this file")
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant", content = null,
                    toolCalls = listOf(
                        ToolCall(id = "c1", type = "function", function = FunctionCall("read_file", """{"path":"a.kt"}"""))
                    )
                )
            )
            cm.addToolResult(
                toolCallId = "c1",
                content = "[read_file for 'a.kt'] Result:\nfun main() { println(\"hello\") }",
                isError = false,
                toolName = "read_file"
            )

            // Second read of the same file
            cm.addUserMessage("Read it again")
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant", content = null,
                    toolCalls = listOf(
                        ToolCall(id = "c2", type = "function", function = FunctionCall("read_file", """{"path":"a.kt"}"""))
                    )
                )
            )
            cm.addToolResult(
                toolCallId = "c2",
                content = "[read_file for 'a.kt'] Result:\nfun main() { println(\"hello world\") }",
                isError = false,
                toolName = "read_file"
            )

            val percentSaved = cm.deduplicateFileReads()

            assertTrue(percentSaved > 0.0, "Should save space by deduplicating")

            // First read should be replaced with a notice
            val messages = cm.getMessages()
            val firstToolResult = messages.find { it.role == "tool" && it.toolCallId == "call_abc" }
                ?: messages.filter { it.role == "tool" }[0]
            assertTrue(
                firstToolResult.content!!.contains("previously read"),
                "First read should be replaced with dedup notice, got: ${firstToolResult.content}"
            )

            // Second read should be intact
            val toolResults = messages.filter { it.role == "tool" }
            val lastToolResult = toolResults.last()
            assertTrue(
                lastToolResult.content!!.contains("hello world"),
                "Latest read should be preserved intact"
            )
        }

        @Test
        fun `no dedup when each file read is unique`() {
            cm.setSystemPrompt("sys")

            // Read a.kt
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant", content = null,
                    toolCalls = listOf(
                        ToolCall(id = "c1", type = "function", function = FunctionCall("read_file", """{"path":"a.kt"}"""))
                    )
                )
            )
            cm.addToolResult(
                toolCallId = "c1",
                content = "[read_file for 'a.kt'] Result:\ncode a",
                isError = false,
                toolName = "read_file"
            )

            // Read b.kt (different file)
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant", content = null,
                    toolCalls = listOf(
                        ToolCall(id = "c2", type = "function", function = FunctionCall("read_file", """{"path":"b.kt"}"""))
                    )
                )
            )
            cm.addToolResult(
                toolCallId = "c2",
                content = "[read_file for 'b.kt'] Result:\ncode b",
                isError = false,
                toolName = "read_file"
            )

            val percentSaved = cm.deduplicateFileReads()
            assertEquals(0.0, percentSaved, 0.01, "No savings when all reads are unique files")
        }

        @Test
        fun `dedup handles three reads of same file - only latest survives`() {
            cm.setSystemPrompt("sys")

            for (i in 1..3) {
                cm.addAssistantMessage(
                    ChatMessage(
                        role = "assistant", content = null,
                        toolCalls = listOf(
                            ToolCall(id = "c$i", type = "function", function = FunctionCall("read_file", """{"path":"a.kt"}"""))
                        )
                    )
                )
                cm.addToolResult(
                    toolCallId = "c$i",
                    content = "[read_file for 'a.kt'] Result:\nversion $i of the code",
                    isError = false,
                    toolName = "read_file"
                )
            }

            cm.deduplicateFileReads()

            val toolResults = cm.getMessages().filter { it.role == "tool" }
            // First two should be deduped
            assertTrue(toolResults[0].content!!.contains("previously read"))
            assertTrue(toolResults[1].content!!.contains("previously read"))
            // Last one should be intact
            assertTrue(toolResults[2].content!!.contains("version 3"))
        }
    }

    // ---- Stage 2: Conversation truncation (from Cline) ----

    @Nested
    inner class ConversationTruncation {

        @Test
        fun `truncation preserves first user-assistant exchange`() {
            cm = ContextManager(maxInputTokens = 1000)
            // First exchange (the task description)
            cm.addUserMessage("Fix the bug in main.kt")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "I'll look at the code."))
            // Many more exchanges
            for (i in 1..10) {
                cm.addUserMessage("question $i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "answer $i"))
            }

            cm.truncateConversation(TruncationStrategy.HALF)

            val messages = cm.getMessages()
            // First message should still be the original task
            assertEquals("Fix the bug in main.kt", messages[0].content)
        }

        @Test
        fun `truncation preserves last N messages`() {
            cm = ContextManager(maxInputTokens = 1000)
            cm.addUserMessage("task")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "starting"))
            for (i in 1..10) {
                cm.addUserMessage("q$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }

            val countBefore = cm.messageCount()
            cm.truncateConversation(TruncationStrategy.HALF)

            val messages = cm.getMessages()
            val countAfter = messages.size
            assertTrue(countAfter < countBefore, "Some messages should be removed")
            // Last message should still be present
            assertEquals("a10", messages.last().content)
        }

        @Test
        fun `truncation maintains role alternation`() {
            cm = ContextManager(maxInputTokens = 1000)
            for (i in 1..10) {
                cm.addUserMessage("q$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }

            cm.truncateConversation(TruncationStrategy.HALF)

            val messages = cm.getMessages()
            // Check alternation: no two consecutive messages should have the same role
            // (except system which is prepended)
            for (i in 1 until messages.size) {
                val prev = messages[i - 1]
                val curr = messages[i]
                // System prompt can precede user
                if (prev.role == "system") continue
                // After truncation notice (assistant), next should be user
                if (prev.role == "assistant" && curr.role == "user") continue
                if (prev.role == "user" && curr.role == "assistant") continue
                // Tool results follow assistant tool_calls
                if (prev.role == "assistant" && curr.role == "tool") continue
                if (prev.role == "tool" && curr.role == "user") continue
                if (prev.role == "tool" && curr.role == "tool") continue // multiple tool results
                // Allow tool -> assistant for adjacent tool groups
                if (prev.role == "tool" && curr.role == "assistant") continue
                fail<String>("Unexpected role sequence at index $i: ${prev.role} -> ${curr.role}")
            }
        }

        @Test
        fun `LAST_TWO keeps only first and last pair`() {
            cm = ContextManager(maxInputTokens = 1000)
            for (i in 1..10) {
                cm.addUserMessage("q$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }

            cm.truncateConversation(TruncationStrategy.LAST_TWO)

            val messages = cm.getMessages()
            // Should have first pair + truncation notice + last pair = roughly 4 messages
            assertTrue(messages.size <= 6, "LAST_TWO should keep very few messages, got ${messages.size}")
            assertEquals("q1", messages[0].content)
        }

        @Test
        fun `no-op when too few messages to truncate`() {
            cm = ContextManager(maxInputTokens = 1000)
            cm.addUserMessage("task")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "ok"))

            val countBefore = cm.messageCount()
            cm.truncateConversation(TruncationStrategy.HALF)
            assertEquals(countBefore, cm.messageCount(), "Should not truncate when < 4 messages")
        }

        @Test
        fun `truncation inserts notice in first assistant message`() {
            cm = ContextManager(maxInputTokens = 1000)
            for (i in 1..10) {
                cm.addUserMessage("q$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }

            cm.truncateConversation(TruncationStrategy.HALF)

            val messages = cm.getMessages()
            // Second message (first assistant) should contain truncation notice
            val firstAssistant = messages[1]
            assertEquals("assistant", firstAssistant.role)
            assertTrue(
                firstAssistant.content!!.contains("truncated", ignoreCase = true),
                "First assistant message should contain truncation notice"
            )
        }
    }

    // ---- Compaction stage 1: Trim old tool results ----

    @Nested
    inner class TrimOldToolResults {

        @Test
        fun `replaces old tool results with placeholder but keeps recent ones`() {
            cm.setSystemPrompt("sys")
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
                cm.addToolResult(toolCallId = "call_$i", content = "x".repeat(100), isError = false, toolName = "tool_$i")
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
        fun `trimmed placeholder includes tool name`() {
            cm.setSystemPrompt("sys")
            for (i in 1..4) {
                cm.addAssistantMessage(
                    ChatMessage(
                        role = "assistant", content = null,
                        toolCalls = listOf(
                            ToolCall(id = "call_$i", type = "function", function = FunctionCall(name = "read_file", arguments = "{}"))
                        )
                    )
                )
                cm.addToolResult(toolCallId = "call_$i", content = "x".repeat(50), isError = false, toolName = "read_file")
            }

            cm.trimOldToolResults(keepRecent = 2)

            val trimmedMessages = cm.getMessages().filter { it.role == "tool" && it.content!!.contains("[Result trimmed") }
            assertTrue(trimmedMessages.isNotEmpty(), "Should have trimmed messages")
            for (msg in trimmedMessages) {
                assertTrue(
                    msg.content!!.contains("read_file"),
                    "Trimmed placeholder should include tool name, got: ${msg.content}"
                )
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
            // Use a small maxInputTokens so that the byte-estimate of large messages stays above 95%
            cm = ContextManager(maxInputTokens = 200)
            cm.setSystemPrompt("sys")
            // Use large messages so byte/4 estimate stays high even after truncation
            val padding = "x".repeat(100)
            for (i in 1..10) {
                cm.addUserMessage("question $i $padding")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "answer $i $padding"))
            }
            val countBefore = cm.messageCount()

            // Force high utilization: updateTokens is stale after compact() invalidates it,
            // but the large messages ensure tokenEstimate() stays above 95% of 200
            cm.updateTokens(promptTokens = 195)
            cm.compact(fakeBrain)

            assertTrue(cm.messageCount() < countBefore, "message count should decrease after compaction")
            val messages = cm.getMessages()
            val summaryMsg = messages.find { it.content?.contains("TASK:") == true }
            assertNotNull(summaryMsg, "summary message should exist")
        }

        @Test
        fun `compact preserves system prompt`() = runTest {
            val fakeBrain = FakeLlmBrain(summaryResponse = "TASK: test\nFILES: a.kt")
            cm = ContextManager(maxInputTokens = 200)
            cm.setSystemPrompt("important system prompt")
            val padding = "x".repeat(100)
            for (i in 1..10) {
                cm.addUserMessage("q$i $padding")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i $padding"))
            }

            cm.updateTokens(promptTokens = 195)
            cm.compact(fakeBrain)

            val messages = cm.getMessages()
            assertEquals("system", messages[0].role)
            assertEquals("important system prompt", messages[0].content)
        }

        @Test
        fun `summary is inserted as assistant message to avoid consecutive user messages`() = runTest {
            val fakeBrain = FakeLlmBrain(summaryResponse = "TASK: test\nFILES: a.kt\nDONE: stuff")
            cm = ContextManager(maxInputTokens = 200)
            cm.setSystemPrompt("sys")
            val padding = "x".repeat(100)
            for (i in 1..10) {
                cm.addUserMessage("q$i $padding")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i $padding"))
            }

            cm.updateTokens(promptTokens = 195)
            cm.compact(fakeBrain)

            val messages = cm.getMessages()
            val summaryMsg = messages.find { it.content?.contains("[Context Summary]") == true }
            assertNotNull(summaryMsg, "summary message should exist")
            assertEquals("assistant", summaryMsg!!.role, "Summary should be an assistant message, not user")
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

    // ---- Pair-aware compaction ----

    @Nested
    inner class PairAwareCompaction {

        @Test
        fun `findSafeSplitPoint lands on user message boundary`() {
            cm.addUserMessage("first question")
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant", content = null,
                    toolCalls = listOf(
                        ToolCall(id = "c1", type = "function", function = FunctionCall("tool_a", "{}"))
                    )
                )
            )
            cm.addToolResult(toolCallId = "c1", content = "result1", isError = false)
            cm.addUserMessage("second question")
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant", content = null,
                    toolCalls = listOf(
                        ToolCall(id = "c2", type = "function", function = FunctionCall("tool_b", "{}"))
                    )
                )
            )
            cm.addToolResult(toolCallId = "c2", content = "result2", isError = false)

            val safePoint = cm.findSafeSplitPoint(2)
            assertEquals(3, safePoint, "Should find next user message at index 3")
        }

        @Test
        fun `findSafeSplitPoint searches backward when no user message after target`() {
            cm.addUserMessage("question")
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant", content = null,
                    toolCalls = listOf(
                        ToolCall(id = "c1", type = "function", function = FunctionCall("tool_a", "{}")),
                        ToolCall(id = "c2", type = "function", function = FunctionCall("tool_b", "{}"))
                    )
                )
            )
            cm.addToolResult(toolCallId = "c1", content = "r1", isError = false)
            cm.addToolResult(toolCallId = "c2", content = "r2", isError = false)

            val safePoint = cm.findSafeSplitPoint(2)
            assertEquals(0, safePoint, "Should find user message at index 0 (backward search)")
        }

        @Test
        fun `sliding window preserves tool call and result pairing`() {
            for (i in 1..3) {
                cm.addUserMessage("q$i")
                cm.addAssistantMessage(
                    ChatMessage(
                        role = "assistant", content = null,
                        toolCalls = listOf(
                            ToolCall(id = "c$i", type = "function", function = FunctionCall("tool_$i", "{}"))
                        )
                    )
                )
                cm.addToolResult(toolCallId = "c$i", content = "r$i", isError = false)
            }

            cm.slidingWindow(keepFraction = 0.5)

            val messages = cm.getMessages()
            for (i in messages.indices) {
                val msg = messages[i]
                if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                    for (tc in msg.toolCalls!!) {
                        val hasResult = messages.drop(i + 1).any { it.role == "tool" && it.toolCallId == tc.id }
                        assertTrue(hasResult, "Tool call ${tc.id} should have a matching tool result after it")
                    }
                }
            }
        }

        @Test
        fun `llm summarization preserves tool call pairing`() = runTest {
            val fakeBrain = FakeLlmBrain(summaryResponse = "TASK: test\nFILES: a.kt\nDONE: something\nERRORS: none\nPENDING: nothing")
            cm = ContextManager(maxInputTokens = 200)
            cm.setSystemPrompt("sys")

            val padding = "x".repeat(80)
            for (i in 1..5) {
                cm.addUserMessage("question $i $padding")
                cm.addAssistantMessage(
                    ChatMessage(
                        role = "assistant", content = null,
                        toolCalls = listOf(
                            ToolCall(id = "call_$i", type = "function", function = FunctionCall("tool_$i", "{}"))
                        )
                    )
                )
                cm.addToolResult(toolCallId = "call_$i", content = "result $i $padding", isError = false)
            }

            cm.updateTokens(promptTokens = 195)
            cm.compact(fakeBrain)

            val messages = cm.getMessages()
            for (i in messages.indices) {
                val msg = messages[i]
                if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                    for (tc in msg.toolCalls!!) {
                        val hasResult = messages.drop(i + 1).any { it.role == "tool" && it.toolCallId == tc.id }
                        assertTrue(hasResult, "After compaction, tool call ${tc.id} should have matching tool result")
                    }
                }
            }
        }
    }

    // ---- Stale token invalidation (bug fix) ----

    @Nested
    inner class StaleTokenInvalidation {

        @Test
        fun `compact invalidates stale prompt tokens`() = runTest {
            val fakeBrain = FakeLlmBrain(summaryResponse = "TASK: test")
            // Use large maxInputTokens so that after compaction, the byte estimate is much lower
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.70)
            cm.setSystemPrompt("sys")

            for (i in 1..20) {
                cm.addUserMessage("q$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }

            // Set high token count to trigger compaction (96%)
            cm.updateTokens(promptTokens = 9600)
            assertTrue(cm.shouldCompact(), "Should trigger compaction")

            cm.compact(fakeBrain)

            // After compaction, utilization should be recalculated from estimate,
            // not from the stale 9600 value. The byte estimate of 20 short messages
            // is much less than 9600 tokens.
            val utilAfter = cm.utilizationPercent()
            assertTrue(
                utilAfter < 96.0,
                "After compaction, utilization should be lower than before (got $utilAfter)"
            )
        }
    }

    // ---- LLM summarization failure ----

    @Nested
    inner class LlmSummarizationFailure {

        @Test
        fun `LLM failure during summarization leaves messages unchanged`() = runTest {
            val errorBrain = ErrorLlmBrain()
            cm = ContextManager(maxInputTokens = 200)
            cm.setSystemPrompt("sys")

            val padding = "x".repeat(100)
            for (i in 1..10) {
                cm.addUserMessage("question $i $padding")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "answer $i $padding"))
            }

            cm.updateTokens(promptTokens = 195)
            cm.compact(errorBrain)

            val messagesAfter = cm.getMessages()
            val hasErrorPlaceholder = messagesAfter.any {
                it.content?.contains("Compaction failed") == true
            }
            assertFalse(hasErrorPlaceholder, "LLM failure should not insert error placeholder into context")
        }
    }

    // ---- Token estimate counts tool_calls ----

    @Nested
    inner class TokenEstimateToolCalls {

        @Test
        fun `tokenEstimate counts tool call names and arguments`() {
            cm = ContextManager(maxInputTokens = 100_000)
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_1",
                            type = "function",
                            function = FunctionCall(
                                name = "read_file",
                                arguments = """{"path":"src/main/kotlin/App.kt"}"""
                            )
                        )
                    )
                )
            )

            val estimate = cm.tokenEstimate()
            assertTrue(estimate > 0, "Token estimate should be > 0 for tool call messages (got $estimate)")
            assertTrue(estimate > 1, "Token estimate should count tool call content, not just overhead")
        }

        @Test
        fun `tokenEstimate counts both content and tool calls`() {
            cm = ContextManager(maxInputTokens = 100_000)
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant",
                    content = "Let me read that file.",
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_1",
                            type = "function",
                            function = FunctionCall(
                                name = "read_file",
                                arguments = """{"path":"a.kt"}"""
                            )
                        )
                    )
                )
            )

            val estimate = cm.tokenEstimate()
            val contentOnlyEstimate = ("Let me read that file.".toByteArray().size + 4) / 4
            assertTrue(estimate > contentOnlyEstimate, "Estimate should be higher when tool calls are included")
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

    private class ErrorLlmBrain : LlmBrain {
        override val modelId: String = "error-model"

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> {
            return ApiResult.Error(
                com.workflow.orchestrator.core.model.ErrorType.SERVER_ERROR,
                "Simulated LLM failure"
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

    // ---- Checkpoint persistence (ported from Cline's state persistence) ----

    @Nested
    inner class CheckpointPersistence {

        @Test
        fun `exportMessages returns all non-system messages in order`() {
            cm.setSystemPrompt("system prompt")
            cm.addUserMessage("user msg 1")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "reply 1"))
            cm.addUserMessage("user msg 2")

            val exported = cm.exportMessages()

            assertEquals(3, exported.size)
            assertEquals("user", exported[0].role)
            assertEquals("user msg 1", exported[0].content)
            assertEquals("assistant", exported[1].role)
            assertEquals("reply 1", exported[1].content)
            assertEquals("user", exported[2].role)
            assertEquals("user msg 2", exported[2].content)
        }

        @Test
        fun `exportMessages does not include system prompt`() {
            cm.setSystemPrompt("secret system prompt")
            cm.addUserMessage("hello")

            val exported = cm.exportMessages()

            assertEquals(1, exported.size)
            assertTrue(exported.none { it.role == "system" })
        }

        @Test
        fun `restoreMessages replaces current conversation`() {
            cm.setSystemPrompt("sys")
            cm.addUserMessage("old msg 1")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "old reply"))

            assertEquals(2, cm.messageCount())

            val savedMessages = listOf(
                ChatMessage(role = "user", content = "restored msg 1"),
                ChatMessage(role = "assistant", content = "restored reply"),
                ChatMessage(role = "user", content = "restored msg 2")
            )

            cm.restoreMessages(savedMessages)

            assertEquals(3, cm.messageCount())
            val all = cm.getMessages()
            // System prompt should still be first
            assertEquals("system", all[0].role)
            assertEquals("sys", all[0].content)
            // Then restored messages
            assertEquals("restored msg 1", all[1].content)
            assertEquals("restored reply", all[2].content)
            assertEquals("restored msg 2", all[3].content)
        }

        @Test
        fun `restoreMessages invalidates token tracking`() {
            cm = ContextManager(maxInputTokens = 1000)
            cm.updateTokens(900) // Set high token count
            assertTrue(cm.shouldCompact())

            // Restore with fewer messages — should invalidate stale token count
            cm.restoreMessages(listOf(
                ChatMessage(role = "user", content = "short")
            ))

            // Utilization should now be based on byte estimate of "short", not stale 900
            assertFalse(cm.shouldCompact(), "Token count should be invalidated after restore")
        }

        @Test
        fun `restoreMessages rebuilds file read indices`() {
            cm.setSystemPrompt("sys")

            // Restore messages that include file reads
            val savedMessages = listOf(
                ChatMessage(role = "assistant", content = null, toolCalls = listOf(
                    ToolCall(id = "c1", type = "function",
                        function = FunctionCall("read_file", """{"path":"a.kt"}"""))
                )),
                ChatMessage(role = "tool", content = "[read_file for 'a.kt'] Result:\ncode v1",
                    toolCallId = "c1"),
                ChatMessage(role = "assistant", content = null, toolCalls = listOf(
                    ToolCall(id = "c2", type = "function",
                        function = FunctionCall("read_file", """{"path":"a.kt"}"""))
                )),
                ChatMessage(role = "tool", content = "[read_file for 'a.kt'] Result:\ncode v2",
                    toolCallId = "c2")
            )

            cm.restoreMessages(savedMessages)

            // Dedup should work on restored messages (proves file read indices were rebuilt)
            val savedPercent = cm.deduplicateFileReads()
            assertTrue(savedPercent > 0.0,
                "Dedup should work after restore (indices rebuilt)")

            val toolResults = cm.getMessages().filter { it.role == "tool" }
            assertTrue(toolResults[0].content!!.contains("previously read"),
                "First read should be deduped after restore")
            assertTrue(toolResults[1].content!!.contains("code v2"),
                "Latest read should be preserved after restore")
        }

        @Test
        fun `getSystemPromptContent returns system prompt text`() {
            cm.setSystemPrompt("My custom prompt")

            assertEquals("My custom prompt", cm.getSystemPromptContent())
        }

        @Test
        fun `getSystemPromptContent returns null when no system prompt set`() {
            assertNull(cm.getSystemPromptContent())
        }

        @Test
        fun `export then restore roundtrip preserves full conversation`() {
            cm.setSystemPrompt("sys")
            cm.addUserMessage("task description")
            cm.addAssistantMessage(ChatMessage(
                role = "assistant", content = null,
                toolCalls = listOf(
                    ToolCall(id = "call_1", type = "function",
                        function = FunctionCall("read_file", """{"path":"a.kt"}"""))
                )
            ))
            cm.addToolResult(toolCallId = "call_1", content = "file contents here", isError = false, toolName = "read_file")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "I see the file."))
            cm.addUserMessage("Now fix it")

            // Export
            val exported = cm.exportMessages()
            val systemPromptContent = cm.getSystemPromptContent()

            // Create a new ContextManager (simulating a fresh start after crash)
            val cm2 = ContextManager(maxInputTokens = 1000)
            cm2.setSystemPrompt(systemPromptContent!!)
            cm2.restoreMessages(exported)

            // Verify round-trip
            val original = cm.getMessages()
            val restored = cm2.getMessages()
            assertEquals(original.size, restored.size)
            for (i in original.indices) {
                assertEquals(original[i].role, restored[i].role)
                assertEquals(original[i].content, restored[i].content)
                assertEquals(original[i].toolCallId, restored[i].toolCallId)
                assertEquals(original[i].toolCalls?.size, restored[i].toolCalls?.size)
            }
        }
    }
}
