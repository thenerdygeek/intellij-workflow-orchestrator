package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [buildRevisionMessage] — the pure function that converts
 * plan editor JSON (v2 per-line comments) into a human-readable revision
 * request for the LLM.
 *
 * These tests were written BEFORE the fix to prove the bug exists:
 * the old code silently dropped all comments because it expected v1
 * (JsonArray) format but received v2 (JsonObject with "comments" key).
 */
class PlanRevisionMessageTest {

    // ── v2 format: {"comments": [{line, content, comment}], "markdown": "..."} ──

    @Nested
    inner class V2FormatParsing {

        @Test
        fun `v2 payload with two comments produces message containing both comment texts`() {
            val v2Json = """{"comments":[{"line":10,"content":"val customer = order.customer","comment":"Handle empty string too"},{"line":28,"content":"### 3. Run tests","comment":"Add integration tests"}],"markdown":"## Goal\nFix NPE in PaymentService"}"""

            val message = buildRevisionMessage(v2Json)

            assertTrue(message.contains("Handle empty string too"),
                "Revision message must include comment text 'Handle empty string too'")
            assertTrue(message.contains("Add integration tests"),
                "Revision message must include comment text 'Add integration tests'")
        }

        @Test
        fun `v2 payload includes line numbers in formatted output`() {
            val v2Json = """{"comments":[{"line":42,"content":"fun process()","comment":"Rename to processPayment"}],"markdown":"## Goal\nRefactor"}"""

            val message = buildRevisionMessage(v2Json)

            assertTrue(message.contains("42"),
                "Revision message should reference line number 42")
            assertTrue(message.contains("Rename to processPayment"),
                "Revision message must include comment text")
        }

        @Test
        fun `v2 payload with empty comments array produces no comment lines`() {
            val v2Json = """{"comments":[],"markdown":"## Goal\nFix NPE"}"""

            val message = buildRevisionMessage(v2Json)

            // Should still have the header and footer but no comment bullets
            assertTrue(message.contains("plan_mode_respond"),
                "Message should still ask LLM to use plan_mode_respond")
            assertFalse(message.contains("- Line"),
                "No comment bullet points for empty comments")
        }

        @Test
        fun `v2 payload with blank comment text skips that comment`() {
            val v2Json = """{"comments":[{"line":5,"content":"val x = 1","comment":""},{"line":10,"content":"val y = 2","comment":"Fix this"}],"markdown":"## Goal\nCleanup"}"""

            val message = buildRevisionMessage(v2Json)

            assertTrue(message.contains("Fix this"),
                "Non-blank comment should be included")
            // The blank comment should be skipped, so only one bullet
            val bulletCount = message.lines().count { it.trimStart().startsWith("- ") }
            assertEquals(1, bulletCount, "Should have exactly 1 bullet point (blank comment skipped)")
        }

        @Test
        fun `v2 payload includes line content for context`() {
            val v2Json = """{"comments":[{"line":10,"content":"val customer = order.customer","comment":"Handle null"}],"markdown":"## Goal\nFix"}"""

            val message = buildRevisionMessage(v2Json)

            assertTrue(message.contains("val customer = order.customer"),
                "Revision message should include the line content for LLM context")
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `invalid JSON falls back to raw text`() {
            val invalidJson = "this is not json at all"

            val message = buildRevisionMessage(invalidJson)

            // Should not crash, should include the raw text as fallback
            assertTrue(message.contains("this is not json at all"),
                "Invalid JSON should be included as raw fallback text")
            assertTrue(message.contains("plan_mode_respond"),
                "Message should still ask LLM to use plan_mode_respond")
        }

        @Test
        fun `empty string produces graceful message`() {
            val message = buildRevisionMessage("")

            assertTrue(message.contains("plan_mode_respond"),
                "Message should still ask LLM to use plan_mode_respond")
        }

        @Test
        fun `comment with missing line number still includes comment text`() {
            // line could be missing in edge cases
            val v2Json = """{"comments":[{"content":"val x = 1","comment":"Fix this"}],"markdown":"## Goal\nFix"}"""

            val message = buildRevisionMessage(v2Json)

            assertTrue(message.contains("Fix this"),
                "Comment without line number should still be included")
        }
    }

    // ── Return type for direct channel injection (Bug 1 fix) ──

    @Nested
    inner class RevisionMetadata {

        @Test
        fun `countRevisionComments returns correct count for v2 payload`() {
            val v2Json = """{"comments":[{"line":10,"content":"x","comment":"A"},{"line":20,"content":"y","comment":"B"},{"line":30,"content":"z","comment":""}],"markdown":"## Goal"}"""

            val count = countRevisionComments(v2Json)

            // Only non-blank comments count
            assertEquals(2, count, "Should count only non-blank comments")
        }

        @Test
        fun `countRevisionComments returns 0 for empty comments`() {
            val v2Json = """{"comments":[],"markdown":"## Goal"}"""

            assertEquals(0, countRevisionComments(v2Json))
        }

        @Test
        fun `countRevisionComments returns 0 for invalid JSON`() {
            assertEquals(0, countRevisionComments("not json"))
        }
    }
}
