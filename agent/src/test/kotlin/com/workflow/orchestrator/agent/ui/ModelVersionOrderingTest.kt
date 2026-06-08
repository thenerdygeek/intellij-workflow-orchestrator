package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelVersionOrderingTest {

    @Test
    fun `versionKey strips thinking, latest and date suffixes`() {
        assertEquals("claude-opus-4-5", ModelVersionOrdering.versionKey("claude-opus-4-5-thinking-latest"))
        assertEquals("claude-opus-4-5", ModelVersionOrdering.versionKey("claude-opus-4-5-20250101"))
        assertEquals("claude-opus-4-5", ModelVersionOrdering.versionKey("claude-opus-4-5"))
    }

    @Test
    fun `versionKey is case-insensitive for thinking and latest`() {
        assertEquals("claude-opus-4-5", ModelVersionOrdering.versionKey("claude-opus-4-5-Thinking-LATEST"))
    }

    @Test
    fun `versionNums extracts digit groups from the version key`() {
        assertEquals(listOf(4, 6), ModelVersionOrdering.versionNums("claude-opus-4-6"))
        assertEquals(listOf(3), ModelVersionOrdering.versionNums("claude-3-opus"))
        assertEquals(listOf(4, 5), ModelVersionOrdering.versionNums("claude-opus-4-5-thinking-latest"))
    }

    @Test
    fun `compareByVersionAsc orders by numeric version, not string`() {
        assertTrue(ModelVersionOrdering.compareByVersionAsc("claude-opus-4-6", "claude-opus-4-5") > 0)
        assertTrue(ModelVersionOrdering.compareByVersionAsc("claude-opus-4-5", "claude-opus-4-6") < 0)
        assertEquals(0, ModelVersionOrdering.compareByVersionAsc("claude-opus-4-5", "claude-opus-4-5"))
    }

    @Test
    fun `compareByVersionAsc treats a missing trailing group as zero`() {
        // [4] vs [4,5] → 4-4=0 then 0-5 < 0
        assertTrue(ModelVersionOrdering.compareByVersionAsc("claude-opus-4", "claude-opus-4-5") < 0)
    }

    @Test
    fun `latest alias does not outrank a numerically newer model (the -latest timestamp bug)`() {
        // The regression this logic fixes: opus-4-5-thinking-latest must sort BELOW opus-4-6,
        // because version keys compare 4-5 vs 4-6 — not the refreshed `created` timestamp.
        assertTrue(
            ModelVersionOrdering.compareByVersionAsc("claude-opus-4-5-thinking-latest", "claude-opus-4-6") < 0,
            "the -latest alias of an older generation must not outrank the newer numbered model",
        )
    }
}
