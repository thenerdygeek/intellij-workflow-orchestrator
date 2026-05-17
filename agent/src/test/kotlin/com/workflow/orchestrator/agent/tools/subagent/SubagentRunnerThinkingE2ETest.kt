package com.workflow.orchestrator.agent.tools.subagent

import com.workflow.orchestrator.agent.ui.ThinkingTagSplitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end thinking-pipeline parity: when chunks containing <thinking>...</thinking>
 * blocks arrive at SubagentRunner's stream pipeline, the resulting progress events
 * must (a) emit thinkingDelta + thinkingEnd events and (b) never leak raw tags in
 * the streamDelta prose stream.
 *
 * SubagentRunner uses [ThinkingTagSplitter] directly, so this test exercises the
 * splitter against representative real-world chunk shapes (split mid-tag, multi-block,
 * empty block) to lock in the parity contract.
 */
class SubagentRunnerThinkingE2ETest {

    @Test
    fun `multi-block thinking-then-prose-then-thinking-then-prose separates cleanly`() {
        val splitter = ThinkingTagSplitter()
        val parts = splitter.consume("<thinking>step one</thinking>doing X<thinking>step two</thinking>doing Y")
        val thinking = parts.filterIsInstance<ThinkingTagSplitter.Part.ThinkingDelta>().joinToString("|") { it.text }
        val ends = parts.count { it is ThinkingTagSplitter.Part.ThinkingEnd }
        val prose = parts.filterIsInstance<ThinkingTagSplitter.Part.Text>().joinToString("") { it.text }
        assertEquals("step one|step two", thinking)
        assertEquals(2, ends)
        assertTrue(prose.contains("doing X"), "Prose must contain 'doing X'")
        assertTrue(prose.contains("doing Y"), "Prose must contain 'doing Y'")
        assertFalse("<thinking>" in prose)
        assertFalse("</thinking>" in prose)
    }

    @Test
    fun `chunk boundary inside open tag does not leak partial tag`() {
        val splitter = ThinkingTagSplitter()
        val first = splitter.consume("<thi")
        val proseFirst = first.filterIsInstance<ThinkingTagSplitter.Part.Text>().joinToString("") { it.text }
        assertFalse("<thi" in proseFirst, "Partial open tag must be held back, not leaked as text")

        val second = splitter.consume("nking>plan</thinking>done")
        val proseSecond = second.filterIsInstance<ThinkingTagSplitter.Part.Text>().joinToString("") { it.text }
        val thinking = second.filterIsInstance<ThinkingTagSplitter.Part.ThinkingDelta>().joinToString("") { it.text }
        val ends = second.count { it is ThinkingTagSplitter.Part.ThinkingEnd }
        assertEquals("plan", thinking)
        assertEquals(1, ends)
        assertTrue(proseSecond.contains("done"), "Prose must contain 'done'")
        assertFalse("<thinking>" in proseSecond)
        assertFalse("</thinking>" in proseSecond)
    }

    @Test
    fun `empty thinking block emits no events`() {
        val splitter = ThinkingTagSplitter()
        val parts = splitter.consume("<thinking></thinking>just prose")
        val thinking = parts.filterIsInstance<ThinkingTagSplitter.Part.ThinkingDelta>().joinToString("") { it.text }
        val ends = parts.count { it is ThinkingTagSplitter.Part.ThinkingEnd }
        val prose = parts.filterIsInstance<ThinkingTagSplitter.Part.Text>().joinToString("") { it.text }
        assertEquals("", thinking, "Empty block emits no delta")
        assertEquals(0, ends, "Empty block emits no end (per ThinkingTagSplitter contract)")
        assertTrue(prose.contains("just prose"), "Prose must contain 'just prose'")
    }
}
