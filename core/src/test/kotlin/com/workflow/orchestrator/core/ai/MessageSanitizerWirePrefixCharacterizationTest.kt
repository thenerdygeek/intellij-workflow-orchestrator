package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageSanitizerWirePrefixCharacterizationTest {
    @Test fun `tool role is coerced to user with the TOOL RESULT prefix`() {
        val input = listOf(
            ChatMessage(role = "user", content = "hi"),
            ChatMessage(role = "tool", content = "file contents", toolCallId = "abc"),
        )
        val out = MessageSanitizer.sanitizeForAnthropic(input)
        // VERIFY against the real pipeline: the tool turn arrives as a user turn whose content
        // begins with "TOOL RESULT:\n". (Phase 2/5 merges may combine turns — assert the prefix
        // is present in the merged user content rather than exact list size.)
        assertTrue(out.any { it.role == "user" && (it.content ?: "").contains("TOOL RESULT:\nfile contents") })
        assertEquals("user", out.last().role) // Phase 6 invariant
    }

    @Test fun `the extracted constant equals the literal it replaces (no cross-component drift)`() {
        // Compaction no-regression guard: the ONE cross-component coupling the constant extraction
        // touches is the wire string `MessageSanitizer` emits. ContextManager.snapToToolBoundary keys
        // on role == "tool" (NOT on this text), and the on-disk dedup operates on structured
        // ContentBlock.ToolResult content (NOT the wire prefix) — so compaction output is unchanged.
        // Pin the value equivalence so the extraction can never silently diverge.
        assertEquals(
            "TOOL RESULT:\n",
            com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol.TOOL_RESULT_WIRE_PREFIX,
        )
    }
}
