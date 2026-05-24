package com.workflow.orchestrator.agent.prompt

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SystemPromptResearchIndexTest {

    @Test
    fun `null research index produces no research_index block`() {
        val prompt = buildMinimalPrompt(researchIndex = null)
        assertFalse(prompt.contains("<research_index>"), "Null research index must NOT emit the tag")
    }

    @Test
    fun `empty research index produces no research_index block`() {
        val prompt = buildMinimalPrompt(researchIndex = "")
        assertFalse(prompt.contains("<research_index>"), "Empty research index must NOT emit the tag")
    }

    @Test
    fun `non-empty research index emits the research_index block`() {
        val researchIndex = "# Research Index\n- [Topic A](file-a.md) — hook A.\n"
        val prompt = buildMinimalPrompt(researchIndex = researchIndex)
        assertTrue(prompt.contains("<research_index>"), "Non-empty research index must emit the tag")
        assertTrue(prompt.contains("[Topic A](file-a.md)"), "Index content must appear verbatim")
        assertTrue(prompt.contains("</research_index>"), "Close tag must appear")
    }

    @Test
    fun `research_index block follows memory_index when both present`() {
        val prompt = buildMinimalPrompt(
            memoryIndex = "# Memory Index\n- [Foo](foo.md) — bar.\n",
            researchIndex = "# Research Index\n- [Topic](file.md) — hook.\n",
        )
        val memIdx = prompt.indexOf("<memory_index>")
        val resIdx = prompt.indexOf("<research_index>")
        assertTrue(memIdx >= 0 && resIdx >= 0, "Both blocks must be present")
        assertTrue(memIdx < resIdx, "research_index must come AFTER memory_index, got mem=$memIdx res=$resIdx")
    }

    /**
     * Helper that calls SystemPrompt.build with the minimum required args for the
     * current SystemPrompt signature. The new params are `researchIndex` and
     * `researchIndexPath` per the task spec.
     *
     * Required params per SystemPrompt.build: projectName, projectPath.
     * All other params have defaults.
     */
    private fun buildMinimalPrompt(
        memoryIndex: String? = null,
        researchIndex: String? = null,
    ): String {
        return SystemPrompt.build(
            projectName = "test-project",
            projectPath = "/tmp/test-project",
            memoryIndex = memoryIndex,
            memoryIndexPath = if (memoryIndex != null) "/tmp/memory/MEMORY.md" else null,
            researchIndex = researchIndex,
            researchIndexPath = if (researchIndex != null) "/tmp/research/RESEARCH.md" else null,
        )
    }
}
