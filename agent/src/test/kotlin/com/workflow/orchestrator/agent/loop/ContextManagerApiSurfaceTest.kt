package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.Choice
import com.workflow.orchestrator.core.ai.dto.ContentPart
import com.workflow.orchestrator.core.ai.dto.FunctionCall
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.ToolCall
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.ai.dto.UsageInfo
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Non-compaction public-API coverage for ContextManager.
 *
 * Tests the 28-method public API surface that must remain stable
 * across the refactor. These tests compile and pass against both
 * the old and new ContextManager, with one exception:
 *   - "addToolResult no longer mutates fileReadIndices" validates
 *     the lazy-dedup fix which is new in the redesigned implementation.
 */
class ContextManagerApiSurfaceTest {

    private lateinit var cm: ContextManager

    @BeforeEach
    fun setUp() {
        cm = ContextManager(maxInputTokens = 10_000)
    }

    // ---- Message management ----

    @Nested
    inner class MessageManagement {

        @Test
        fun `addUserMessage appends user role message`() {
            cm.addUserMessage("hello world")
            val messages = cm.getMessages()
            assertEquals(1, messages.size)
            assertEquals("user", messages[0].role)
            assertEquals("hello world", messages[0].content)
        }

        @Test
        fun `addUserMessageWithParts appends message with parts and flat text mirror`() {
            val parts = listOf(
                ContentPart.Text("describe this"),
                ContentPart.Image(sha256 = "abc123", mime = "image/png"),
            )
            cm.addUserMessageWithParts(parts)
            val messages = cm.getMessages()
            assertEquals(1, messages.size)
            assertEquals("user", messages[0].role)
            assertEquals("describe this", messages[0].content)
            assertEquals(2, messages[0].parts?.size)
        }

        @Test
        fun `addUserMessageWithParts sets null content when no text parts`() {
            val parts = listOf(ContentPart.Image(sha256 = "abc123", mime = "image/png"))
            cm.addUserMessageWithParts(parts)
            val msg = cm.getMessages()[0]
            assertNull(msg.content)
            assertEquals(1, msg.parts?.size)
        }

        @Test
        fun `addAssistantMessage appends assistant role message`() {
            val assistant = ChatMessage(role = "assistant", content = "I can help")
            cm.addAssistantMessage(assistant)
            val messages = cm.getMessages()
            assertEquals(1, messages.size)
            assertEquals("assistant", messages[0].role)
            assertEquals("I can help", messages[0].content)
        }

        @Test
        fun `addToolResult appends tool role message with correct toolCallId`() {
            cm.addToolResult(toolCallId = "call_1", content = "file contents", isError = false)
            val messages = cm.getMessages()
            assertEquals(1, messages.size)
            assertEquals("tool", messages[0].role)
            assertEquals("call_1", messages[0].toolCallId)
            assertEquals("file contents", messages[0].content)
        }

        @Test
        fun `addToolResult prefixes content with ERROR marker when isError=true`() {
            cm.addToolResult(toolCallId = "call_err", content = "not found", isError = true)
            val msg = cm.getMessages()[0]
            assertTrue(msg.content!!.contains("[ERROR]"))
            assertTrue(msg.content!!.contains("not found"))
        }

        @Test
        fun `addToolResult with imageRefs creates message with parts`() {
            val imageRef = com.workflow.orchestrator.agent.session.ContentBlock.ImageRef(
                sha256 = "sha256abc",
                mime = "image/png",
                size = 12345L
            )
            cm.addToolResult(
                toolCallId = "call_img",
                content = "image result",
                isError = false,
                imageRefs = listOf(imageRef)
            )
            val msg = cm.getMessages()[0]
            assertNotNull(msg.parts)
            assertTrue(msg.parts!!.any { it is ContentPart.Image })
        }

        @Test
        fun `getMessages returns system prompt first followed by conversation messages`() {
            cm.setSystemPrompt("You are an AI assistant.")
            cm.addUserMessage("Hello")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "Hi"))
            val messages = cm.getMessages()
            assertEquals(3, messages.size)
            assertEquals("system", messages[0].role)
            assertEquals("You are an AI assistant.", messages[0].content)
            assertEquals("user", messages[1].role)
            assertEquals("assistant", messages[2].role)
        }

        @Test
        fun `setSystemPrompt replaces previous system prompt`() {
            cm.setSystemPrompt("first prompt")
            cm.setSystemPrompt("second prompt")
            cm.addUserMessage("hi")
            val messages = cm.getMessages()
            assertEquals(2, messages.size)
            assertEquals("second prompt", messages[0].content)
        }

        @Test
        fun `setSystemPrompt does not add to conversation message count`() {
            cm.setSystemPrompt("system instructions")
            assertEquals(0, cm.messageCount())
            cm.addUserMessage("user turn")
            assertEquals(1, cm.messageCount())
        }

        @Test
        fun `messageCount returns only non-system messages`() {
            cm.setSystemPrompt("sys")
            cm.addUserMessage("u1")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a1"))
            cm.addToolResult(toolCallId = "c1", content = "r1", isError = false)
            assertEquals(3, cm.messageCount())
        }

        @Test
        fun `messages are tracked in order — user then assistant then tool`() {
            cm.addUserMessage("task")
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCall(id = "c1", type = "function", function = FunctionCall("read_file", "{}"))
                    )
                )
            )
            cm.addToolResult(toolCallId = "c1", content = "contents", isError = false)
            val messages = cm.getMessages()
            assertEquals(3, messages.size)
            assertEquals("user", messages[0].role)
            assertEquals("assistant", messages[1].role)
            assertEquals("tool", messages[2].role)
        }
    }

    // ---- Token tracking ----

    @Nested
    inner class TokenTracking {

        @Test
        fun `updateTokens sets lastPromptTokens used by utilizationPercent`() {
            cm = ContextManager(maxInputTokens = 1_000)
            cm.updateTokens(promptTokens = 500)
            assertEquals(50.0, cm.utilizationPercent(), 0.1)
        }

        @Test
        fun `utilizationPercent falls back to tokenEstimate when no API response yet`() {
            cm = ContextManager(maxInputTokens = 10_000)
            cm.setSystemPrompt("sys")
            cm.addUserMessage("hello world")
            val util = cm.utilizationPercent()
            assertTrue(util > 0.0, "utilization should be positive without API tokens")
            assertTrue(util < 100.0, "utilization should be under 100%")
        }

        @Test
        fun `shouldCompact returns false below threshold`() {
            cm = ContextManager(maxInputTokens = 1_000, compactionThreshold = 0.88)
            cm.updateTokens(promptTokens = 870) // 87% — just below 88%
            assertFalse(cm.shouldCompact())
        }

        @Test
        fun `shouldCompact returns true at threshold`() {
            cm = ContextManager(maxInputTokens = 1_000, compactionThreshold = 0.88)
            cm.updateTokens(promptTokens = 881) // 88.1% — just above 88%
            assertTrue(cm.shouldCompact())
        }

        @Test
        fun `shouldCompact returns true above threshold`() {
            cm = ContextManager(maxInputTokens = 1_000, compactionThreshold = 0.88)
            cm.updateTokens(promptTokens = 950) // 95%
            assertTrue(cm.shouldCompact())
        }

        @Test
        fun `tokenEstimate uses chars divided by 3_5 plus per-message overhead`() {
            cm = ContextManager(maxInputTokens = 100_000)
            cm.setSystemPrompt("abcd")     // 4 chars
            cm.addUserMessage("abcdefgh") // 8 chars
            // chars = 4 + 8 = 12, messageTokens = (12 / 3.5).toInt() = 3
            // overhead = (1 + 1) * 4 = 8 (1 message + 1 for system)
            // toolDefinitionTokens = 0
            // total = 3 + 8 = 11
            assertEquals(11, cm.tokenEstimate())
        }

        @Test
        fun `setToolDefinitionTokens is included in tokenEstimate`() {
            cm = ContextManager(maxInputTokens = 100_000)
            cm.setSystemPrompt("s")
            cm.setToolDefinitionTokens(500)
            val estimate = cm.tokenEstimate()
            // Must be > 500 (includes tool def tokens)
            assertTrue(estimate > 500)
        }

        @Test
        fun `currentInputTokens returns lastPromptTokens when available`() {
            cm = ContextManager(maxInputTokens = 10_000)
            cm.updateTokens(promptTokens = 1234)
            assertEquals(1234, cm.currentInputTokens())
        }

        @Test
        fun `currentInputTokens returns tokenEstimate when no API data`() {
            cm = ContextManager(maxInputTokens = 10_000)
            cm.addUserMessage("hello")
            val tokens = cm.currentInputTokens()
            assertEquals(cm.tokenEstimate(), tokens)
        }

        @Test
        fun `lastPromptTokens is invalidated when onHistoryOverwrite is called after compaction`() = runTest {
            val brain = SummarizationFakeBrain("TASK: test summary")
            cm = ContextManager(maxInputTokens = 1_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            var overwriteCalled = false
            cm.onHistoryOverwrite = { _, _ -> overwriteCalled = true }

            // Fill context to trigger compaction
            for (i in 1..8) {
                cm.addUserMessage("u$i " + "x".repeat(50))
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i " + "x".repeat(50)))
            }
            cm.updateTokens(promptTokens = 900) // 90% — triggers compaction

            cm.compact(brain, force = true)

            // After compaction, lastPromptTokens should be null (invalidated),
            // so utilizationPercent falls back to tokenEstimate which must be lower
            val utilAfter = cm.utilizationPercent()
            assertTrue(utilAfter < 90.0, "After compaction util should drop from stale 90%, got $utilAfter")
        }
    }

    // ---- Budget / effectiveMaxInputTokens ----

    @Nested
    inner class Budget {

        @Test
        fun `effectiveMaxInputTokens returns maxInputTokens fallback when no catalog`() {
            cm = ContextManager(maxInputTokens = 42_000)
            assertEquals(42_000, cm.effectiveMaxInputTokens())
        }

        @Test
        fun `effectiveMaxInputTokens falls back to maxInputTokens when currentModelRef returns null`() {
            // No catalog wired + null provider: must fall back to constructor maxInputTokens
            cm = ContextManager(
                maxInputTokens = 55_000,
                modelCatalogService = null,
                currentModelRef = { null }
            )
            assertEquals(55_000, cm.effectiveMaxInputTokens())
        }

        @Test
        fun `effectiveMaxInputTokens falls back to maxInputTokens when no catalog provided`() {
            // With currentModelRef wired but no catalog: falls back to constructor value
            cm = ContextManager(
                maxInputTokens = 77_000,
                modelCatalogService = null,
                currentModelRef = { "some-model" }
            )
            assertEquals(77_000, cm.effectiveMaxInputTokens())
        }

        @Test
        fun `maxInputTokensFor returns FALLBACK_MAX_INPUT_TOKENS when no catalog wired`() {
            cm = ContextManager(maxInputTokens = 10_000)
            val result = cm.maxInputTokensFor("any-model-ref")
            assertEquals(ContextManager.FALLBACK_MAX_INPUT_TOKENS, result)
        }

        @Test
        fun `FALLBACK_MAX_INPUT_TOKENS is 90000`() {
            assertEquals(90_000, ContextManager.FALLBACK_MAX_INPUT_TOKENS)
        }
    }

    // ---- Skill / plan storage ----

    @Nested
    inner class SkillAndPlanStorage {

        @Test
        fun `setActiveSkill stores content retrievable by getActiveSkill`() {
            val content = "# My Skill\nDo the thing."
            cm.setActiveSkill(content)
            assertEquals(content, cm.getActiveSkill())
        }

        @Test
        fun `getActiveSkill returns null when no skill is active`() {
            assertNull(cm.getActiveSkill())
        }

        @Test
        fun `clearActiveSkill removes the skill`() {
            cm.setActiveSkill("some skill")
            cm.clearActiveSkill()
            assertNull(cm.getActiveSkill())
        }

        @Test
        fun `setActivePlanPath stores path retrievable by getActivePlanPath`() {
            val path = "/home/user/.workflow-orchestrator/project/plans/plan1.md"
            cm.setActivePlanPath(path)
            assertEquals(path, cm.getActivePlanPath())
        }

        @Test
        fun `getActivePlanPath returns null when no plan is set`() {
            assertNull(cm.getActivePlanPath())
        }

        @Test
        fun `clearActivePlanPath removes the plan path`() {
            cm.setActivePlanPath("/some/path")
            cm.clearActivePlanPath()
            assertNull(cm.getActivePlanPath())
        }
    }

    // ---- compactTurn image utility ----

    @Nested
    inner class ImageUtility {

        @Test
        fun `compactTurn strips image parts and appends placeholder`() {
            val msg = ChatMessage(
                role = "user",
                content = "look at this",
                parts = listOf(
                    ContentPart.Text("look at this"),
                    ContentPart.Image(sha256 = "sha1", mime = "image/png"),
                )
            )
            val compacted = cm.compactTurn(msg)
            assertNull(compacted.parts)
            assertTrue(compacted.content!!.contains("look at this"))
            assertTrue(compacted.content!!.contains("image"))
        }

        @Test
        fun `compactTurn is a no-op when message has no image parts`() {
            val msg = ChatMessage(role = "assistant", content = "plain text")
            val result = cm.compactTurn(msg)
            assertEquals(msg.content, result.content)
            assertNull(result.parts)
        }

        @Test
        fun `compactTurn does not modify original message object`() {
            val original = ChatMessage(
                role = "user",
                content = "original",
                parts = listOf(ContentPart.Image(sha256 = "x", mime = "image/jpeg"))
            )
            val compacted = cm.compactTurn(original)
            // Original must be unchanged (data class copy semantics)
            assertEquals(1, original.parts!!.size)
            assertNotSame(original, compacted)
        }
    }

    // ---- Task store ----

    @Nested
    inner class TaskStoreIntegration {

        @Test
        fun `attachTaskStore wires the store and renderTaskProgressMarkdown returns null when no tasks`() {
            val store = makeTaskStore()
            cm.attachTaskStore(store)
            // Empty store → null markdown
            assertNull(cm.renderTaskProgressMarkdown())
        }

        @Test
        fun `renderTaskProgressMarkdown returns null when no store is attached`() {
            assertNull(cm.renderTaskProgressMarkdown())
        }

        @Test
        fun `currentTasks returns empty list when no store attached`() {
            assertTrue(cm.currentTasks().isEmpty())
        }

        @Test
        fun `currentTasks returns empty list when store has no tasks`() {
            val store = makeTaskStore()
            cm.attachTaskStore(store)
            assertTrue(cm.currentTasks().isEmpty())
        }

        @Test
        fun `renderTaskProgressMarkdown renders checklist when tasks exist`() = runTest {
            val store = makeTaskStore()
            store.addTask(
                Task(id = "1", subject = "Write tests", description = "test description")
            )
            cm.attachTaskStore(store)
            val md = cm.renderTaskProgressMarkdown()
            assertNotNull(md)
            assertTrue(md!!.contains("Write tests"))
            assertTrue(md.contains("[ ]"))
        }

        private fun makeTaskStore(): com.workflow.orchestrator.agent.session.TaskStore {
            val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"), "context-mgr-test-${System.nanoTime()}")
            tmpDir.mkdirs()
            return com.workflow.orchestrator.agent.session.TaskStore(baseDir = tmpDir, sessionId = "test-session")
        }
    }

    // ---- Prune/collapse helpers ----

    @Nested
    inner class PruneAndCollapseHelpers {

        private val nudge = "[ERROR] You must use a tool."

        @Test
        fun `pruneTrailingNudgePairs removes only the contiguous trailing pairs`() {
            // pruneTrailingNudgePairs is called after a fresh assistant turn is added,
            // so the preserved tail is a non-nudge assistant turn that anchors the scan.
            // A tool-call assistant turn acts as the barrier (isTextOnlyAssistant=false).
            cm.addUserMessage("task")                                                         // 0
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = ""))             // 1
            cm.addUserMessage(nudge)                                                          // 2 interior pair — leave alone
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCall(id = "c1", type = "function", function = FunctionCall("read_file", "{}"))
                    )
                )
            )                                                                                 // 3 barrier (tool-call)
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = ""))             // 4
            cm.addUserMessage(nudge)                                                          // 5 trailing pair — prune
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "fresh"))        // 6 preserved tail

            val removed = cm.pruneTrailingNudgePairs(nudge)
            assertEquals(1, removed)
            // Interior nudge must survive (the barrier at index 3 stops the scan)
            assertTrue(cm.getMessages().any { it.content == nudge })
        }

        @Test
        fun `pruneAllNudgePairs removes all matching pairs`() {
            cm.addUserMessage("task")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = ""))
            cm.addUserMessage(nudge)
            cm.addUserMessage("continue")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = ""))
            cm.addUserMessage(nudge)

            val removed = cm.pruneAllNudgePairs(nudge)
            assertEquals(2, removed)
            assertTrue(cm.getMessages().none { it.content == nudge })
        }

        @Test
        fun `pruneAllNudgePairs does not touch assistant turns with tool calls`() {
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(
                        ToolCall(id = "c1", type = "function", function = FunctionCall("read_file", "{}"))
                    )
                )
            )
            cm.addUserMessage(nudge)
            val removed = cm.pruneAllNudgePairs(nudge)
            assertEquals(0, removed)
            assertEquals(2, cm.messageCount())
        }

        @Test
        fun `collapseLastCompletionToolPair collapses attempt_completion pair`() {
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant",
                    content = "Here is my result",
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_comp",
                            type = "function",
                            function = FunctionCall("attempt_completion", """{"result":"done"}""")
                        )
                    )
                )
            )
            cm.addToolResult(toolCallId = "call_comp", content = "Task completed.", isError = false)

            val collapsed = cm.collapseLastCompletionToolPair()
            assertTrue(collapsed)
            // Pair should be replaced with a single assistant message
            assertEquals(1, cm.messageCount())
            val remaining = cm.getMessages()[0]
            assertEquals("assistant", remaining.role)
            assertTrue(remaining.toolCalls.isNullOrEmpty())
        }

        @Test
        fun `collapseLastCompletionToolPair returns false when no completion pair at tail`() {
            cm.addUserMessage("task")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "just text"))
            assertFalse(cm.collapseLastCompletionToolPair())
        }

        @Test
        fun `slidingWindow keeps only the most recent fraction of messages`() {
            cm.setSystemPrompt("sys")
            for (i in 1..10) {
                cm.addUserMessage("msg $i")
            }
            cm.slidingWindow(keepFraction = 0.3)
            val messages = cm.getMessages()
            // 3 messages + system prompt = 4
            assertEquals(4, messages.size)
            assertEquals("system", messages[0].role)
        }
    }

    // ---- Checkpoint persistence ----

    @Nested
    inner class CheckpointPersistence {

        @Test
        fun `exportMessages returns conversation messages without system prompt`() {
            cm.setSystemPrompt("sys")
            cm.addUserMessage("u1")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a1"))
            val exported = cm.exportMessages()
            assertEquals(2, exported.size)
            assertTrue(exported.none { it.role == "system" })
        }

        @Test
        fun `restoreMessages replaces current conversation and invalidates tokens`() {
            cm = ContextManager(maxInputTokens = 1_000)
            cm.updateTokens(900) // high tokens
            assertTrue(cm.shouldCompact())

            cm.restoreMessages(
                listOf(ChatMessage(role = "user", content = "short"))
            )
            assertFalse(cm.shouldCompact(), "Token count should be invalidated after restore")
        }

        @Test
        fun `getSystemPromptContent returns the set system prompt`() {
            cm.setSystemPrompt("My system prompt")
            assertEquals("My system prompt", cm.getSystemPromptContent())
        }

        @Test
        fun `getSystemPromptContent returns null before setSystemPrompt is called`() {
            assertNull(cm.getSystemPromptContent())
        }

        @Test
        fun `clearMessages clears all conversation messages`() {
            cm.addUserMessage("u1")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a1"))
            assertEquals(2, cm.messageCount())
            cm.clearMessages()
            assertEquals(0, cm.messageCount())
        }
    }

    // ---- addToolResult no longer mutates fileReadIndices eagerly ----

    @Nested
    inner class LazyDedupTracking {

        /**
         * Validates the "dead-tracking fix" from the plan:
         * addToolResult must NOT maintain a live fileReadIndices map.
         * The index is built lazily inside deduplicateFileReads().
         * So calling deduplicateFileReads() after addToolResult for duplicate
         * files must still work correctly (lazy build + dedup happens together).
         */
        @Test
        fun `deduplicateFileReads works correctly on messages added via addToolResult`() {
            // First read
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCall(id = "c1", type = "function", function = FunctionCall("read_file", "{}"))
                    )
                )
            )
            cm.addToolResult(
                toolCallId = "c1",
                content = "[read_file for 'foo.kt'] Result:\noriginal content",
                isError = false,
                toolName = "read_file"
            )

            // Second read of the same file
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCall(id = "c2", type = "function", function = FunctionCall("read_file", "{}"))
                    )
                )
            )
            cm.addToolResult(
                toolCallId = "c2",
                content = "[read_file for 'foo.kt'] Result:\nupdated content",
                isError = false,
                toolName = "read_file"
            )

            // deduplicateFileReads() must lazily build indices and perform dedup
            val savedPct = cm.deduplicateFileReads()
            assertTrue(savedPct > 0.0, "dedup should save space on duplicate file reads")

            val toolResults = cm.getMessages().filter { it.role == "tool" }
            assertTrue(toolResults[0].content!!.contains("previously read"), "first read should be deduped")
            assertTrue(toolResults[1].content!!.contains("updated content"), "latest read should be preserved")
        }
    }

    // ---- Fake LlmBrain for compaction tests ----

    class SummarizationFakeBrain(private val summaryResponse: String) : LlmBrain {
        override val modelId: String = "fake-summarizer"

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> = ApiResult.Success(
            ChatCompletionResponse(
                id = "fake",
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

        override suspend fun chatStream(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            onChunk: suspend (StreamChunk) -> Unit
        ): ApiResult<ChatCompletionResponse> = throw UnsupportedOperationException()

        override fun estimateTokens(text: String): Int = text.length / 4
    }
}
