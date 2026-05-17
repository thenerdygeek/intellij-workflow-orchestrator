package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.Choice
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.ai.dto.UsageInfo
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies that an active plan pointer survives compaction via re-injection into
 * the post-summary message list. Focuses narrowly on plan re-injection; full
 * compaction-path coverage lives in [ContextManagerSummarizationTest].
 */
class PlanCompactionTest {

    private lateinit var cm: ContextManager

    @BeforeEach
    fun setUp() {
        cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
    }

    // ── Plan storage API ─────────────────────────────────────────────────────

    @Test
    fun `setActivePlanPath stores path`() {
        cm.setActivePlanPath("/tmp/session/plan.md")
        assertEquals("/tmp/session/plan.md", cm.getActivePlanPath())
    }

    @Test
    fun `getActivePlanPath returns null when no plan`() {
        assertNull(cm.getActivePlanPath())
    }

    @Test
    fun `clearActivePlanPath removes stored path`() {
        cm.setActivePlanPath("/tmp/plan.md")
        cm.clearActivePlanPath()
        assertNull(cm.getActivePlanPath())
    }

    // ── Re-injection via compact() ───────────────────────────────────────────

    @Test
    fun `active plan is re-injected after compact() returns Compacted`() = runTest {
        val brain = ConstantBrain("TASK: implement plan\nSTATE: mid-implementation")
        cm.setSystemPrompt("sys")
        cm.setActivePlanPath("/home/user/.workflow-orchestrator/project/plans/plan1.md")
        for (i in 1..8) {
            cm.addUserMessage("u$i")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
        }
        cm.updateTokens(promptTokens = 9_000) // 90% — above 88%

        val result = cm.compact(brain, force = false)

        assertTrue(result is ContextManager.CompactResult.Compacted, "Expected Compacted, got $result")
        val planMsg = cm.getMessages().find { it.content?.contains("[Active Plan]") == true }
        assertNotNull(planMsg, "Active plan must be re-injected after compact()")
        assertTrue(planMsg!!.content!!.contains("plan1.md"))
    }

    @Test
    fun `plan path is still accessible via getActivePlanPath after compact()`() = runTest {
        val brain = ConstantBrain("TASK: test\nSTATE: done")
        cm.setSystemPrompt("sys")
        cm.setActivePlanPath("/home/user/project/plans/plan.md")
        for (i in 1..8) {
            cm.addUserMessage("u$i")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
        }
        cm.updateTokens(promptTokens = 9_000)

        cm.compact(brain, force = false)

        assertEquals("/home/user/project/plans/plan.md", cm.getActivePlanPath(),
            "getActivePlanPath() must still return the original path after compact()")
    }

    // ── Re-injection idempotency ─────────────────────────────────────────────

    @Test
    fun `reInjectActivePlan does not duplicate when plan already present`() {
        cm.setSystemPrompt("sys")
        cm.setActivePlanPath("/tmp/plan.md")
        cm.addUserMessage("Hello")

        cm.reInjectActivePlan()
        val countAfterFirst = cm.getMessages().count { it.content?.contains("[Active Plan]") == true }

        cm.reInjectActivePlan()
        val countAfterSecond = cm.getMessages().count { it.content?.contains("[Active Plan]") == true }

        assertEquals(countAfterFirst, countAfterSecond,
            "Re-injection must not duplicate when plan message is already in recent messages")
    }

    @Test
    fun `reInjectActivePlan is a no-op when no plan is active`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("Hello")

        val sizeBefore = cm.getMessages().size
        cm.reInjectActivePlan()
        val sizeAfter = cm.getMessages().size

        assertEquals(sizeBefore, sizeAfter, "No messages must be added when no active plan")
    }

    // ── Helper brain ─────────────────────────────────────────────────────────

    private class ConstantBrain(private val response: String) : LlmBrain {
        override val modelId: String = "constant-brain"

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> = ApiResult.Success(
            ChatCompletionResponse(
                id = "fake-id",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = response),
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
