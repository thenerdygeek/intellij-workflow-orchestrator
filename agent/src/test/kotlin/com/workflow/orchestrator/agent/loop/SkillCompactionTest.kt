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
 * Verifies that an active skill survives compaction via re-injection into the
 * post-summary message list. Focuses narrowly on skill re-injection; full
 * compaction-path coverage lives in [ContextManagerSummarizationTest].
 */
class SkillCompactionTest {

    private lateinit var cm: ContextManager

    @BeforeEach
    fun setUp() {
        cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.88)
    }

    // ── Skill storage API ────────────────────────────────────────────────────

    @Test
    fun `setActiveSkill stores skill content`() {
        cm.setActiveSkill("# TDD Skill\nWrite tests first.")
        assertEquals("# TDD Skill\nWrite tests first.", cm.getActiveSkill())
    }

    @Test
    fun `getActiveSkill returns null when no skill active`() {
        assertNull(cm.getActiveSkill())
    }

    @Test
    fun `clearActiveSkill removes stored skill`() {
        cm.setActiveSkill("Some skill content")
        cm.clearActiveSkill()
        assertNull(cm.getActiveSkill())
    }

    // ── Re-injection via compact() ───────────────────────────────────────────

    @Test
    fun `active skill is re-injected after compact() returns Compacted`() = runTest {
        val brain = ConstantBrain("TASK: test\nSTATE: done")
        cm.setSystemPrompt("sys")
        cm.setActiveSkill("# TDD Skill\nAlways write tests first.")
        for (i in 1..8) {
            cm.addUserMessage("u$i")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
        }
        cm.updateTokens(promptTokens = 9_000) // 90% — above 88%

        val result = cm.compact(brain, force = false)

        assertTrue(result is ContextManager.CompactResult.Compacted, "Expected Compacted, got $result")
        val skillMsg = cm.getMessages().find { it.content?.contains("[Active Skill]") == true }
        assertNotNull(skillMsg, "Active skill must be re-injected after compact()")
        assertTrue(skillMsg!!.content!!.contains("TDD Skill"))
    }

    @Test
    fun `skill content is still accessible via getActiveSkill after compact()`() = runTest {
        val brain = ConstantBrain("TASK: test\nSTATE: done")
        cm.setSystemPrompt("sys")
        cm.setActiveSkill("# My Skill\nDo the right thing.")
        for (i in 1..8) {
            cm.addUserMessage("u$i")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "a$i"))
        }
        cm.updateTokens(promptTokens = 9_000)

        cm.compact(brain, force = false)

        assertEquals("# My Skill\nDo the right thing.", cm.getActiveSkill(),
            "getActiveSkill() must still return the original skill content after compact()")
    }

    // ── Re-injection idempotency ─────────────────────────────────────────────

    @Test
    fun `reInjectActiveSkill does not duplicate when skill already present`() {
        cm.setSystemPrompt("sys")
        cm.setActiveSkill("# My Skill")
        cm.addUserMessage("Hello")
        cm.addAssistantMessage(ChatMessage(role = "assistant", content = "Hi"))

        cm.reInjectActiveSkill()
        val countAfterFirst = cm.getMessages().count { it.content?.contains("[Active Skill]") == true }

        cm.reInjectActiveSkill()
        val countAfterSecond = cm.getMessages().count { it.content?.contains("[Active Skill]") == true }

        assertEquals(countAfterFirst, countAfterSecond,
            "Re-injection must not duplicate when skill message is already in recent messages")
    }

    @Test
    fun `reInjectActiveSkill is a no-op when no skill is active`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("Hello")
        cm.addAssistantMessage(ChatMessage(role = "assistant", content = "Hi"))

        val sizeBefore = cm.getMessages().size
        cm.reInjectActiveSkill()
        val sizeAfter = cm.getMessages().size

        assertEquals(sizeBefore, sizeAfter, "No messages must be added when no active skill")
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
