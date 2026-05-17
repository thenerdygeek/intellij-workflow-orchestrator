package com.workflow.orchestrator.agent.tools.subagent

import com.workflow.orchestrator.agent.ui.ThinkingTagSplitter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubagentRunnerStreamPipelineTest {

    /**
     * Smoke test that the runner's stream pipeline is wired to ThinkingTagSplitter.
     * The full integration is tested separately (P2.T7); this asserts the splitter
     * itself behaves as we depend on inside SubagentRunner.
     */
    @Test
    fun `ThinkingTagSplitter separates thinking from prose end-to-end`() {
        val splitter = ThinkingTagSplitter()
        val parts = splitter.consume("<thinking>plan A</thinking>now I will do it")
        val thinkingDeltas = parts.filterIsInstance<ThinkingTagSplitter.Part.ThinkingDelta>().joinToString("") { it.text }
        val thinkingEnds = parts.count { it is ThinkingTagSplitter.Part.ThinkingEnd }
        val proseText = parts.filterIsInstance<ThinkingTagSplitter.Part.Text>().joinToString("") { it.text }
        assertEquals("plan A", thinkingDeltas)
        assertEquals(1, thinkingEnds)
        assertTrue(proseText.contains("now I will do it"), "Prose must contain 'now I will do it', was: $proseText")
        assertFalse("<thinking>" in proseText, "Prose must not contain raw <thinking> tags")
    }
}
