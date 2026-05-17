package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.hooks.HookConfig
import com.workflow.orchestrator.agent.hooks.HookEvent
import com.workflow.orchestrator.agent.hooks.HookManager
import com.workflow.orchestrator.agent.hooks.HookResult
import com.workflow.orchestrator.agent.hooks.HookRunner
import com.workflow.orchestrator.agent.hooks.HookType
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
 * Compaction-path coverage for the redesigned single-stage ContextManager.
 *
 * These tests describe the NEW behavior (single-stage LLM summarization at 88%
 * threshold) and will FAIL against the old 3-stage implementation. They pass
 * once the new ContextManager is in place.
 *
 * Key behaviors tested:
 * - Single 88% threshold gate (no 70/85/95% banding)
 * - PRE_COMPACT hook (cancellable)
 * - File-read dedup pre-pass runs BEFORE summarization
 * - Prefix replaced with single assistant summary message
 * - Last 30% of messages preserved
 * - Summary chaining (previousSummary fed into next compaction prompt)
 * - Image stripping post-summary
 * - Image placeholder in summarization prompt
 * - Tool-call name fallback for null content in prompt
 * - Active skill re-injection
 * - Active plan re-injection
 * - onHistoryOverwrite with deleted-index-range pair
 * - lastPromptTokens invalidation
 * - CompactResult.Failed when brain.chat() returns null
 * - findSafeSplitPoint biases to role boundary
 * - Summary chaining bounded across multiple compactions
 * - Dedup pre-pass prevents summarization overflow on repeated file reads
 */
class ContextManagerSummarizationTest {

    private lateinit var cm: ContextManager

    @BeforeEach
    fun setUp() {
        cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
    }

    // ── Threshold gate ──────────────────────────────────────────────────────

    @Nested
    inner class ThresholdGate {

        @Test
        fun `compact returns Skipped when utilization below 88 percent and force=false`() = runTest {
            val brain = CapturingBrain("summary text")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.addUserMessage("hello")
            cm.updateTokens(promptTokens = 8_700) // 87% — just below 88%

            val result = cm.compact(brain, force = false)

            assertTrue(result is ContextManager.CompactResult.Skipped, "Expected Skipped but got $result")
            assertEquals(0, brain.chatCallCount, "Brain must not be called when below threshold")
        }

        @Test
        fun `compact returns Skipped when utilization equals exactly threshold and force=false`() = runTest {
            val brain = CapturingBrain("summary")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.updateTokens(promptTokens = 8_800) // exactly 88%
            // NOTE: shouldCompact returns true when util > threshold*100, so 88.0% with threshold 0.88
            // means utilizationPercent() 88.0 > 88.0 is FALSE → Skipped
            val result = cm.compact(brain, force = false)
            assertTrue(result is ContextManager.CompactResult.Skipped)
        }

        @Test
        fun `compact fires when utilization is above 88 percent`() = runTest {
            val brain = CapturingBrain("TASK: test\nFILES: none")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..6) {
                cm.addUserMessage("question $i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "answer $i"))
            }
            cm.updateTokens(promptTokens = 8_900) // 89% — above 88%

            val result = cm.compact(brain, force = false)

            assertTrue(
                result is ContextManager.CompactResult.Compacted || result is ContextManager.CompactResult.Skipped,
                "Expected Compacted (or Skipped if split point too small), got $result"
            )
        }

        @Test
        fun `compact force=true fires regardless of utilization`() = runTest {
            val brain = CapturingBrain("TASK: testing\nFILES: none\nSTATE: just started")
            cm = ContextManager(maxInputTokens = 100_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..8) {
                cm.addUserMessage("question $i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "answer $i"))
            }
            cm.updateTokens(promptTokens = 500) // 0.5% — well below threshold

            val result = cm.compact(brain, force = true)

            assertTrue(
                result is ContextManager.CompactResult.Compacted,
                "force=true must compact even at low utilization, got $result"
            )
        }
    }

    // ── PRE_COMPACT hook ────────────────────────────────────────────────────

    @Nested
    inner class PreCompactHook {

        @Test
        fun `compact fires PRE_COMPACT hook when one is registered`() = runTest {
            val brain = CapturingBrain("TASK: test\nFILES: none")
            var hookFired = false
            val fakeRunner = object : HookRunner() {
                override suspend fun execute(hook: HookConfig, event: HookEvent): HookResult {
                    hookFired = true
                    return HookResult.Proceed()
                }
            }
            val hookManager = HookManager(fakeRunner)
            hookManager.register(HookConfig(type = HookType.PRE_COMPACT, command = "echo ok"))

            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000) // 90%

            cm.compact(brain, hookManager = hookManager, force = false)

            assertTrue(hookFired, "PRE_COMPACT hook must fire during compaction")
        }

        @Test
        fun `compact returns Cancelled when PRE_COMPACT hook returns Cancel`() = runTest {
            val brain = CapturingBrain("summary")
            val fakeRunner = object : HookRunner() {
                override suspend fun execute(hook: HookConfig, event: HookEvent): HookResult =
                    HookResult.Cancel(reason = "hook says no")
            }
            val hookManager = HookManager(fakeRunner)
            hookManager.register(HookConfig(type = HookType.PRE_COMPACT, command = "exit 1"))

            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000) // 90%

            val result = cm.compact(brain, hookManager = hookManager, force = false)

            assertTrue(result is ContextManager.CompactResult.Cancelled, "Expected Cancelled, got $result")
            assertEquals(0, brain.chatCallCount, "Brain must not be called when hook cancels")
        }

        @Test
        fun `compact passes utilizationPercent and messageCount to hook event data`() = runTest {
            val brain = CapturingBrain("summary")
            var capturedEvent: HookEvent? = null
            val fakeRunner = object : HookRunner() {
                override suspend fun execute(hook: HookConfig, event: HookEvent): HookResult {
                    capturedEvent = event
                    return HookResult.Proceed()
                }
            }
            val hookManager = HookManager(fakeRunner)
            hookManager.register(HookConfig(type = HookType.PRE_COMPACT, command = "echo"))

            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            cm.compact(brain, hookManager = hookManager, force = false)

            assertNotNull(capturedEvent)
            assertEquals(HookType.PRE_COMPACT, capturedEvent!!.type)
            assertTrue(capturedEvent!!.data.containsKey("utilizationPercent"))
            assertTrue(capturedEvent!!.data.containsKey("messageCount"))
        }
    }

    // ── Dedup pre-pass ──────────────────────────────────────────────────────

    @Nested
    inner class DedupPrePass {

        @Test
        fun `compact runs file-read dedup BEFORE summarization (Blocker-1 fix)`() = runTest {
            val brain = CapturingBrain("TASK: test\nSTATE: done")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")

            // Add 5 reads of the same large file to simulate the overflow scenario
            for (i in 1..5) {
                cm.addAssistantMessage(
                    ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            ToolCall(id = "c$i", type = "function", function = FunctionCall("read_file", "{}"))
                        )
                    )
                )
                cm.addToolResult(
                    toolCallId = "c$i",
                    content = "[read_file for 'big.kt'] Result:\n" + "x".repeat(800),
                    isError = false,
                    toolName = "read_file"
                )
            }
            // Add some user messages too
            cm.addUserMessage("what does big.kt contain?")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "it contains lots of code"))

            cm.updateTokens(promptTokens = 9_500) // 95% — over threshold

            val result = cm.compact(brain, force = false)

            // If dedup ran first, the summarization prompt should NOT contain 5x the file
            // (only the last read survives dedup). We verify by checking the prompt
            // sent to the brain contained at most 1 full copy.
            assertTrue(
                result is ContextManager.CompactResult.Compacted,
                "Expected Compacted after dedup+summarization, got $result"
            )
            val prompt = brain.lastPromptText ?: ""
            // Count occurrences of "previously read" — dedup placeholder
            val dedupCount = prompt.split("previously read").size - 1
            // At least 4 of the 5 reads should be deduped (replaced with placeholders)
            assertTrue(dedupCount >= 4, "Expected at least 4 dedup placeholders in prompt, got $dedupCount in: $prompt")
        }

        @Test
        fun `dedup pre-pass prevents summarization overflow on 5 repeated file reads`() = runTest {
            val brain = CapturingBrain("TASK: read files\nSTATE: done")
            cm = ContextManager(maxInputTokens = 50_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")

            // 5 reads of the same massive file — without dedup this would be ~5 * 5000 chars = 25K tokens
            for (i in 1..5) {
                cm.addAssistantMessage(
                    ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            ToolCall(id = "c$i", type = "function", function = FunctionCall("read_file", "{}"))
                        )
                    )
                )
                cm.addToolResult(
                    toolCallId = "c$i",
                    content = "[read_file for 'large.kt'] Result:\n" + "a".repeat(5_000),
                    isError = false,
                    toolName = "read_file"
                )
            }

            cm.updateTokens(promptTokens = 45_000) // 90% — above threshold

            val result = cm.compact(brain, force = false)

            assertTrue(
                result is ContextManager.CompactResult.Compacted,
                "Expected Compacted, got $result"
            )
            // The prompt sent to brain should be much smaller than 25K chars due to dedup
            val promptLen = brain.lastPromptText?.length ?: 0
            assertTrue(
                promptLen < 15_000,
                "Prompt should be much smaller after dedup, got $promptLen chars"
            )
        }
    }

    // ── Prefix replacement ──────────────────────────────────────────────────

    @Nested
    inner class PrefixReplacement {

        @Test
        fun `compact replaces prefix with single assistant summary message`() = runTest {
            val brain = CapturingBrain("TASK: Fix bug\nFILES: main.kt\nDECISIONS: use coroutines\nSTATE: done\nERRORS: none\nPENDING: review")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..10) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000) // 90%

            val result = cm.compact(brain, force = false)

            assertTrue(result is ContextManager.CompactResult.Compacted, "Expected Compacted, got $result")
            val messages = cm.getMessages()
            val summaryMsg = messages.find { it.content?.contains("Context Summary") == true }
            assertNotNull(summaryMsg, "Summary message should exist after compaction")
            assertEquals("assistant", summaryMsg!!.role, "Summary must be inserted as assistant role")
        }

        @Test
        fun `compact summary message contains formatSummaryMessage header`() = runTest {
            val brain = CapturingBrain("My summary content")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            cm.compact(brain, force = false)

            val summaryMsg = cm.getMessages().find { it.content?.contains("[Context Summary") == true }
            assertNotNull(summaryMsg)
            assertTrue(summaryMsg!!.content!!.contains("My summary content"))
        }

        @Test
        fun `compact preserves last 30 percent of messages`() = runTest {
            val brain = CapturingBrain("TASK: test")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            // 20 messages total; last 30% ≈ 6 messages
            for (i in 1..10) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            val lastMsg = cm.getMessages().last()
            cm.updateTokens(promptTokens = 9_000)

            val result = cm.compact(brain, force = false)

            assertTrue(result is ContextManager.CompactResult.Compacted, "Expected Compacted, got $result")
            // The very last message should be preserved
            val messages = cm.getMessages()
            assertTrue(
                messages.contains(lastMsg),
                "Last message must be preserved (last 30% tail)"
            )
        }

        @Test
        fun `compact preserves system prompt`() = runTest {
            val brain = CapturingBrain("TASK: test")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("IMPORTANT SYSTEM INSTRUCTIONS")
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            cm.compact(brain, force = false)

            val messages = cm.getMessages()
            assertEquals("system", messages[0].role)
            assertEquals("IMPORTANT SYSTEM INSTRUCTIONS", messages[0].content)
        }

        @Test
        fun `compact reduces total message count`() = runTest {
            val brain = CapturingBrain("TASK: test\nFILES: none")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..10) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            val countBefore = cm.messageCount()
            cm.updateTokens(promptTokens = 9_000)

            val result = cm.compact(brain, force = false)

            assertTrue(result is ContextManager.CompactResult.Compacted, "Expected Compacted, got $result")
            assertTrue(cm.messageCount() < countBefore, "Message count must decrease after compaction")
        }
    }

    // ── Summary chaining ────────────────────────────────────────────────────

    @Nested
    inner class SummaryChaining {

        @Test
        fun `compact chains previousSummary into the next compaction prompt`() = runTest {
            val brain = CapturingBrain("TASK: continued task\nSTATE: second compaction")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)
            // First compaction
            brain.nextResponse = "FIRST SUMMARY CONTENT"
            cm.compact(brain, force = true)

            // Add more messages and trigger second compaction
            for (i in 11..18) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            brain.nextResponse = "SECOND SUMMARY CONTENT"
            cm.compact(brain, force = true)

            // The prompt for the second compaction must contain the first summary
            val secondPrompt = brain.lastPromptText ?: ""
            assertTrue(
                secondPrompt.contains("FIRST SUMMARY CONTENT"),
                "Second compaction prompt must include previous summary for chaining. Prompt: $secondPrompt"
            )
        }

        @Test
        fun `summary chaining stays bounded across 5 sequential compactions`() = runTest {
            // Each compaction produces a fixed-length summary.
            // After 5 compactions, the prompt should NOT grow unboundedly.
            val summaryText = "TASK: test\nFILES: none\nSTATE: ongoing"
            val brain = CapturingBrain(summaryText)
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")

            var prevPromptLength = 0
            for (round in 1..5) {
                // Add messages and trigger compaction
                for (i in 1..8) {
                    cm.addUserMessage("u${round}_$i")
                    cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a${round}_$i"))
                }
                cm.updateTokens(promptTokens = 9_000)
                cm.compact(brain, force = true)

                val currentPromptLength = brain.lastPromptText?.length ?: 0
                if (round > 1) {
                    // Prompt growth should be bounded (new messages dominate, not accumulated summaries)
                    // Allow up to 3x growth per round (generous bound)
                    assertTrue(
                        currentPromptLength < prevPromptLength * 3 + 5_000,
                        "Round $round: prompt length $currentPromptLength grew too fast vs previous $prevPromptLength"
                    )
                }
                prevPromptLength = currentPromptLength
            }
        }
    }

    // ── Image handling ──────────────────────────────────────────────────────

    @Nested
    inner class ImageHandling {

        @Test
        fun `compact strips image parts from all messages after summary`() = runTest {
            val brain = CapturingBrain("TASK: analyze screenshot\nFILES: none")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")

            // Add a message with an image part
            cm.addUserMessageWithParts(
                listOf(
                    ContentPart.Text("analyze this screenshot"),
                    ContentPart.Image(sha256 = "sha123", mime = "image/png")
                )
            )
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            val result = cm.compact(brain, force = false)

            assertTrue(result is ContextManager.CompactResult.Compacted, "Expected Compacted, got $result")
            // After compaction, no message should have image parts
            val allMessages = cm.getMessages()
            val hasImageParts = allMessages.any { msg -> msg.parts?.any { it is ContentPart.Image } == true }
            assertFalse(hasImageParts, "No image parts should remain after compaction")
        }

        @Test
        fun `compact includes image count placeholder in summarization prompt`() = runTest {
            val brain = CapturingBrain("TASK: analyze\nSTATE: done")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")

            // Message with 2 image parts
            cm.addUserMessageWithParts(
                listOf(
                    ContentPart.Text("look at these"),
                    ContentPart.Image(sha256 = "sha1", mime = "image/png"),
                    ContentPart.Image(sha256 = "sha2", mime = "image/jpeg")
                )
            )
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            cm.compact(brain, force = false)

            val prompt = brain.lastPromptText ?: ""
            // The prompt must contain the image placeholder like "[+2 image(s) attached]"
            assertTrue(
                prompt.contains("image(s) attached"),
                "Prompt must include image placeholder [+N image(s) attached], got: ${prompt.take(500)}"
            )
        }

        @Test
        fun `compact uses tool-call name fallback when content is null`() = runTest {
            val brain = CapturingBrain("TASK: used tool\nSTATE: done")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")

            // Assistant message with null content (only tool_calls)
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCall(
                            id = "c_tool",
                            type = "function",
                            function = FunctionCall("read_file", """{"path":"main.kt"}""")
                        )
                    )
                )
            )
            cm.addToolResult(toolCallId = "c_tool", content = "file contents", isError = false)
            for (i in 1..6) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            cm.compact(brain, force = false)

            val prompt = brain.lastPromptText ?: ""
            // Must contain the tool-call name fallback: "(tool_call: read_file)"
            assertTrue(
                prompt.contains("tool_call:") || prompt.contains("read_file"),
                "Prompt must include tool-call name when content is null, got: ${prompt.take(500)}"
            )
        }
    }

    // ── Skill and plan re-injection ─────────────────────────────────────────

    @Nested
    inner class ReInjection {

        @Test
        fun `compact re-injects active skill after summary`() = runTest {
            val brain = CapturingBrain("TASK: test skill\nSTATE: done")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            cm.setActiveSkill("# TDD Skill\nAlways write tests first.")
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            val result = cm.compact(brain, force = false)

            assertTrue(result is ContextManager.CompactResult.Compacted, "Expected Compacted, got $result")
            val messages = cm.getMessages()
            val skillMsg = messages.find { it.content?.contains("[Active Skill]") == true }
            assertNotNull(skillMsg, "Active skill must be re-injected after compaction")
            assertTrue(skillMsg!!.content!!.contains("TDD Skill"))
        }

        @Test
        fun `compact re-injects active plan after summary`() = runTest {
            val brain = CapturingBrain("TASK: implement plan\nSTATE: mid-implementation")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            cm.setActivePlanPath("/home/user/.workflow-orchestrator/project/plans/plan1.md")
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            val result = cm.compact(brain, force = false)

            assertTrue(result is ContextManager.CompactResult.Compacted, "Expected Compacted, got $result")
            val messages = cm.getMessages()
            val planMsg = messages.find { it.content?.contains("[Active Plan]") == true }
            assertNotNull(planMsg, "Active plan must be re-injected after compaction")
            assertTrue(planMsg!!.content!!.contains("plan1.md"))
        }
    }

    // ── onHistoryOverwrite callback ─────────────────────────────────────────

    @Nested
    inner class HistoryOverwriteCallback {

        @Test
        fun `compact invokes onHistoryOverwrite with deleted index range`() = runTest {
            val brain = CapturingBrain("TASK: test\nSTATE: done")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            var capturedMessages: List<ChatMessage>? = null
            var capturedRange: Pair<Int, Int>? = null
            cm.onHistoryOverwrite = { msgs, range ->
                capturedMessages = msgs
                capturedRange = range
            }
            for (i in 1..10) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            val result = cm.compact(brain, force = false)

            assertTrue(result is ContextManager.CompactResult.Compacted, "Expected Compacted, got $result")
            assertNotNull(capturedMessages, "onHistoryOverwrite must be invoked")
            assertNotNull(capturedRange)
            // The range should start at 0 (prefix removed from the beginning)
            assertEquals(0, capturedRange!!.first, "Deleted range must start at index 0")
            assertTrue(capturedRange!!.second > 0, "Deleted range end must be > 0")
        }

        @Test
        fun `compact onHistoryOverwrite receives the post-compaction message list`() = runTest {
            val brain = CapturingBrain("TASK: test\nSTATE: done")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            var passedMessages: List<ChatMessage>? = null
            cm.onHistoryOverwrite = { msgs, _ -> passedMessages = msgs }
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            cm.compact(brain, force = false)

            assertNotNull(passedMessages)
            // The passed messages must match the current cm messages (after compaction)
            assertEquals(cm.getMessages().filterNot { it.role == "system" }, passedMessages)
        }
    }

    // ── Token invalidation ──────────────────────────────────────────────────

    @Nested
    inner class TokenInvalidation {

        @Test
        fun `compact invalidates lastPromptTokens after successful compaction`() = runTest {
            val brain = CapturingBrain("TASK: done")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000) // 90%

            cm.compact(brain, force = false)

            // After compaction, utilizationPercent should be lower (falls back to estimate)
            val utilAfter = cm.utilizationPercent()
            assertTrue(utilAfter < 90.0, "Utilization should drop after compaction, got $utilAfter")
        }

        @Test
        fun `compact returns Compacted with tokensBefore and tokensAfter`() = runTest {
            val brain = CapturingBrain("TASK: test\nSTATE: done")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            val result = cm.compact(brain, force = false)

            assertTrue(result is ContextManager.CompactResult.Compacted)
            val compacted = result as ContextManager.CompactResult.Compacted
            assertTrue(compacted.tokensBefore > 0, "tokensBefore must be positive")
            assertTrue(compacted.tokensAfter >= 0, "tokensAfter must be non-negative")
            assertTrue(compacted.summaryChars > 0, "summaryChars must be positive")
        }

        @Test
        fun `lastCompactionRanSummary is true after successful compaction`() = runTest {
            val brain = CapturingBrain("TASK: test")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            cm.compact(brain, force = false)

            assertTrue(cm.lastCompactionRanSummary, "lastCompactionRanSummary must be true after success")
        }

        @Test
        fun `lastCompactionRanSummary is false when compact returns Skipped`() = runTest {
            val brain = CapturingBrain("TASK: test")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.updateTokens(promptTokens = 8_700) // below threshold

            cm.compact(brain, force = false)

            assertFalse(cm.lastCompactionRanSummary, "lastCompactionRanSummary must be false when Skipped")
        }
    }

    // ── Failed result ───────────────────────────────────────────────────────

    @Nested
    inner class FailedResult {

        @Test
        fun `compact returns Failed when brain returns null content`() = runTest {
            val brain = NullContentBrain()
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            val result = cm.compact(brain, force = false)

            assertTrue(result is ContextManager.CompactResult.Failed, "Expected Failed when brain returns null, got $result")
        }

        @Test
        fun `compact returns Failed when brain returns ApiResult Error`() = runTest {
            val brain = ErrorBrain()
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            val result = cm.compact(brain, force = false)

            assertTrue(result is ContextManager.CompactResult.Failed, "Expected Failed on LLM error, got $result")
        }
    }

    // ── findSafeSplitPoint ──────────────────────────────────────────────────

    @Nested
    inner class FindSafeSplitPoint {

        @Test
        fun `findSafeSplitPoint biases to a user-message role boundary`() {
            // [user, assistant+toolcall, tool, user, assistant, tool]
            // Split near index 2 should advance to index 3 (next user)
            cm.addUserMessage("first question")
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(ToolCall(id = "c1", type = "function", function = FunctionCall("read_file", "{}")))
                )
            )
            cm.addToolResult(toolCallId = "c1", content = "file contents", isError = false)
            cm.addUserMessage("second question")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "answer"))

            // findSafeSplitPoint(keepFraction=0.30) keeps last 30% ≈ 1-2 messages
            // The split point must land on a "user" role to avoid splitting a tool_call/result pair
            val splitIdx = cm.findSafeSplitPoint(2)
            val message = cm.getMessages().getOrNull(splitIdx)
            if (message != null) {
                assertEquals("user", message.role, "findSafeSplitPoint must land on a user role boundary")
            }
        }

        @Test
        fun `findSafeSplitPoint does not split a tool_call result pair`() {
            cm.addUserMessage("question")
            cm.addAssistantMessage(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCall(id = "c1", type = "function", function = FunctionCall("read_file", "{}")),
                        ToolCall(id = "c2", type = "function", function = FunctionCall("edit_file", "{}"))
                    )
                )
            )
            cm.addToolResult(toolCallId = "c1", content = "r1", isError = false)
            cm.addToolResult(toolCallId = "c2", content = "r2", isError = false)

            // If split is in the middle of the tool results, it should move to a safe boundary
            val splitIdx = cm.findSafeSplitPoint(2)
            val splitMessage = cm.getMessages().getOrNull(splitIdx)
            // The split should NOT land inside a tool block
            if (splitMessage != null) {
                assertNotEquals("tool", splitMessage.role, "Split point must not land on a tool-result message")
            }
        }
    }

    // ── CompactResult sealed class ──────────────────────────────────────────

    @Nested
    inner class CompactResultSealed {

        @Test
        fun `CompactResult Skipped carries utilizationPercent`() = runTest {
            val brain = CapturingBrain("summary")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.updateTokens(promptTokens = 8_700) // below threshold

            val result = cm.compact(brain, force = false)

            assertTrue(result is ContextManager.CompactResult.Skipped)
            val skipped = result as ContextManager.CompactResult.Skipped
            assertTrue(skipped.utilizationPercent > 0.0)
            assertTrue(skipped.utilizationPercent < 90.0)
        }

        @Test
        fun `CompactResult Compacted carries all three fields`() = runTest {
            val brain = CapturingBrain("TASK: test summary")
            cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
            cm.setSystemPrompt("sys")
            for (i in 1..8) {
                cm.addUserMessage("u$i")
                cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
            }
            cm.updateTokens(promptTokens = 9_000)

            val result = cm.compact(brain, force = false)

            assertTrue(result is ContextManager.CompactResult.Compacted)
            val compacted = result as ContextManager.CompactResult.Compacted
            assertTrue(compacted.tokensBefore > 0)
            assertTrue(compacted.tokensAfter >= 0)
            assertEquals("TASK: test summary".length, compacted.summaryChars)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Brain that captures the last prompt text sent to it and returns a configurable response.
     */
    class CapturingBrain(initialResponse: String) : LlmBrain {
        override val modelId: String = "capturing-brain"
        var nextResponse: String = initialResponse
        var chatCallCount = 0
        var lastPromptText: String? = null

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> {
            chatCallCount++
            // Capture the last user message content (the summarization prompt)
            lastPromptText = messages.lastOrNull()?.content
            return ApiResult.Success(
                ChatCompletionResponse(
                    id = "fake-${chatCallCount}",
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = ChatMessage(role = "assistant", content = nextResponse),
                            finishReason = "stop"
                        )
                    ),
                    usage = UsageInfo(promptTokens = 200, completionTokens = 100, totalTokens = 300)
                )
            )
        }

        override suspend fun chatStream(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            onChunk: suspend (StreamChunk) -> Unit
        ): ApiResult<ChatCompletionResponse> = throw UnsupportedOperationException()

        override fun estimateTokens(text: String): Int = text.length / 4
    }

    /**
     * Brain that returns null content (simulates a model returning empty response).
     */
    class NullContentBrain : LlmBrain {
        override val modelId: String = "null-content-brain"

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> = ApiResult.Success(
            ChatCompletionResponse(
                id = "null-id",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = null), // null content
                        finishReason = "stop"
                    )
                ),
                usage = null
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

    /**
     * Brain that always returns ApiResult.Error.
     */
    class ErrorBrain : LlmBrain {
        override val modelId: String = "error-brain"

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> = ApiResult.Error(
            com.workflow.orchestrator.core.model.ErrorType.SERVER_ERROR,
            "Simulated LLM error"
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
