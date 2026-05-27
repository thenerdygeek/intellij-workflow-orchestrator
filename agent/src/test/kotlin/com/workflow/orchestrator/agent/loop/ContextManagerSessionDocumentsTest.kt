package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.SessionDocument
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
 * Verifies that session-documents manifest is re-injected into the reassembled
 * history after compaction — the same pattern as active-skill / active-plan
 * re-injection.
 *
 * Test approach: real compaction path (mirrors ContextManagerTwoTierTest).
 * We seed enough messages to pass the 88% threshold, call compact(force=true),
 * and assert the reassembled history contains a message whose content holds
 * both the "[Session Documents]" header and the exact absolute path.
 */
class ContextManagerSessionDocumentsTest {

    private lateinit var cm: ContextManager

    @BeforeEach
    fun setUp() {
        cm = ContextManager(maxInputTokens = 10_000)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun fakeBrain(summaryText: String = "MOCK-SUMMARY"): LlmBrain =
        object : LlmBrain {
            override val modelId = "fake-model"
            override suspend fun chat(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>?,
                maxTokens: Int?,
                toolChoice: JsonElement?,
            ): ApiResult<ChatCompletionResponse> = ApiResult.Success(
                ChatCompletionResponse(
                    id = "fake",
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = ChatMessage(role = "assistant", content = summaryText),
                            finishReason = "stop",
                        )
                    ),
                    usage = UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150),
                )
            )
            override suspend fun chatStream(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>?,
                maxTokens: Int?,
                onChunk: suspend (StreamChunk) -> Unit,
            ): ApiResult<ChatCompletionResponse> = throw UnsupportedOperationException()
            override fun estimateTokens(text: String): Int = text.length / 4
        }

    /** Seed enough messages to cross the 88% compaction threshold. */
    private fun seedBigMessages() {
        val big = "x".repeat(1050) // ~300 tokens each
        // Pre-user messages
        repeat(3) {
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
            cm.addToolResult(toolCallId = "pre$it", content = big, isError = false, toolName = "read_file")
        }
        cm.addUserMessage("anchor")
        // Post-user messages (many — ensures L3 also runs)
        repeat(7) {
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
            cm.addToolResult(toolCallId = "t$it", content = big, isError = false, toolName = "read_file")
        }
    }

    // ── buildSessionDocumentsMessage unit tests ───────────────────────────────

    @Test
    fun `buildSessionDocumentsMessage returns null when no provider is set`() {
        assertNull(cm.buildSessionDocumentsMessage())
    }

    @Test
    fun `buildSessionDocumentsMessage returns null when provider returns empty list`() {
        cm.setSessionDocumentsProvider { emptyList() }
        assertNull(cm.buildSessionDocumentsMessage())
    }

    @Test
    fun `buildSessionDocumentsMessage returns message containing header and exact path`() {
        cm.setSessionDocumentsProvider {
            listOf(SessionDocument("design.pdf", "/abs/downloads/jira-1/design.pdf"))
        }
        val msg = cm.buildSessionDocumentsMessage()
        assertNotNull(msg)
        assertTrue(msg!!.contains("[Session Documents]"), "must contain '[Session Documents]' header")
        assertTrue(
            msg.contains("/abs/downloads/jira-1/design.pdf"),
            "must contain exact absolute path",
        )
        assertTrue(msg.contains("design.pdf"), "must contain display name")
    }

    @Test
    fun `buildSessionDocumentsMessage lists multiple documents`() {
        cm.setSessionDocumentsProvider {
            listOf(
                SessionDocument("spec.pdf", "/abs/downloads/jira-1/spec.pdf"),
                SessionDocument("report.docx", "/abs/downloads/jira-2/report.docx"),
            )
        }
        val msg = cm.buildSessionDocumentsMessage()!!
        assertTrue(msg.contains("/abs/downloads/jira-1/spec.pdf"))
        assertTrue(msg.contains("/abs/downloads/jira-2/report.docx"))
    }

    // ── full-compaction re-injection tests ────────────────────────────────────

    @Test
    fun `compact re-injects session-documents manifest into reassembled history`() = runTest {
        cm.setSessionDocumentsProvider {
            listOf(SessionDocument("design.pdf", "/abs/downloads/jira-1/design.pdf"))
        }
        seedBigMessages()

        val result = cm.compact(fakeBrain(), force = true, iterationsSinceLastUser = 7)
        assertTrue(result is ContextManager.CompactResult.Compacted, "expected Compacted, got $result")

        val msgs = cm.exportMessages()
        val manifest = msgs.find { it.content?.contains("[Session Documents]") == true }
        assertNotNull(manifest, "reassembled history must contain a [Session Documents] message")
        assertTrue(
            manifest!!.content!!.contains("/abs/downloads/jira-1/design.pdf"),
            "manifest must contain the exact absolute path",
        )
    }

    @Test
    fun `compact does not inject session-documents when provider is not set`() = runTest {
        // No provider set
        seedBigMessages()

        val result = cm.compact(fakeBrain(), force = true, iterationsSinceLastUser = 7)
        assertTrue(result is ContextManager.CompactResult.Compacted, "expected Compacted, got $result")

        val msgs = cm.exportMessages()
        assertNull(
            msgs.find { it.content?.contains("[Session Documents]") == true },
            "must NOT inject manifest when no provider is set",
        )
    }

    @Test
    fun `compact does not inject session-documents when provider returns empty list`() = runTest {
        cm.setSessionDocumentsProvider { emptyList() }
        seedBigMessages()

        val result = cm.compact(fakeBrain(), force = true, iterationsSinceLastUser = 7)
        assertTrue(result is ContextManager.CompactResult.Compacted, "expected Compacted, got $result")

        val msgs = cm.exportMessages()
        assertNull(
            msgs.find { it.content?.contains("[Session Documents]") == true },
            "must NOT inject manifest when provider returns empty list",
        )
    }

    @Test
    fun `compact does not duplicate manifest on second consecutive compaction`() = runTest {
        cm.setSessionDocumentsProvider {
            listOf(SessionDocument("design.pdf", "/abs/downloads/jira-1/design.pdf"))
        }
        // First compaction
        seedBigMessages()
        cm.compact(fakeBrain(), force = true, iterationsSinceLastUser = 7)

        // Second compaction (add more messages to cross threshold again)
        val big = "x".repeat(1050)
        repeat(5) {
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
            cm.addToolResult(toolCallId = "u$it", content = big, isError = false, toolName = "read_file")
        }
        cm.compact(fakeBrain(), force = true, iterationsSinceLastUser = 12)

        val msgs = cm.exportMessages()
        val count = msgs.count { it.content?.contains("[Session Documents]") == true }
        assertEquals(1, count, "manifest must appear exactly once — no duplicates")
    }

    @Test
    fun `manifest message has assistant role matching active-plan re-injection shape`() = runTest {
        cm.setSessionDocumentsProvider {
            listOf(SessionDocument("spec.txt", "/some/path/spec.txt"))
        }
        seedBigMessages()
        cm.compact(fakeBrain(), force = true, iterationsSinceLastUser = 7)

        val manifest = cm.exportMessages()
            .find { it.content?.contains("[Session Documents]") == true }
        assertNotNull(manifest)
        assertEquals("assistant", manifest!!.role, "manifest message must have assistant role")
    }

    @Test
    fun `degenerate-path compaction also re-injects manifest`() = runTest {
        cm.setSessionDocumentsProvider {
            listOf(SessionDocument("design.pdf", "/abs/downloads/jira-1/design.pdf"))
        }
        // No user message → degenerate path
        val big = "x".repeat(350)
        repeat(5) { cm.addAssistantMessage(ChatMessage(role = "assistant", content = big)) }

        val result = cm.compact(fakeBrain(), force = true, iterationsSinceLastUser = 5)
        assertTrue(result is ContextManager.CompactResult.Compacted, "expected Compacted, got $result")

        val manifest = cm.exportMessages()
            .find { it.content?.contains("[Session Documents]") == true }
        assertNotNull(manifest, "degenerate-path must also re-inject manifest")
        assertTrue(manifest!!.content!!.contains("/abs/downloads/jira-1/design.pdf"))
    }
}
