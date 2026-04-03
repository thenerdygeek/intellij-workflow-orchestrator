package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoveragePreviewLogicTest {

    @Test
    fun `extractUncoveredRegions finds contiguous uncovered blocks with context`() {
        val lines = (1..20).map { "line $it of code" }
        val statuses = mapOf(
            1 to LineCoverageStatus.COVERED,
            2 to LineCoverageStatus.COVERED,
            3 to LineCoverageStatus.UNCOVERED,
            4 to LineCoverageStatus.UNCOVERED,
            5 to LineCoverageStatus.COVERED,
            10 to LineCoverageStatus.PARTIAL,
            15 to LineCoverageStatus.UNCOVERED
        )
        val regions = CoveragePreviewPanel.extractUncoveredRegions(lines, statuses, contextLines = 2)
        assertTrue(regions.isNotEmpty())
        // First region should cover lines 3-4 (uncovered) with 2 lines context each side
        val first = regions[0]
        assertTrue(first.lines.any { it.status == LineCoverageStatus.UNCOVERED })
        // Context lines should be included (line 1 or 2 before, line 5 or 6 after)
        assertTrue(first.lines.any { it.lineNumber <= 2 })
        assertTrue(first.lines.any { it.lineNumber >= 5 })
    }

    @Test
    fun `extractUncoveredRegions returns empty for fully covered file`() {
        val lines = (1..5).map { "line $it" }
        val statuses = (1..5).associate { it to LineCoverageStatus.COVERED }
        val regions = CoveragePreviewPanel.extractUncoveredRegions(lines, statuses, contextLines = 2)
        assertTrue(regions.isEmpty())
    }

    @Test
    fun `extractUncoveredRegions handles partial coverage as uncovered region`() {
        val lines = (1..10).map { "line $it" }
        val statuses = mapOf(
            5 to LineCoverageStatus.PARTIAL
        )
        val regions = CoveragePreviewPanel.extractUncoveredRegions(lines, statuses, contextLines = 2)
        assertEquals(1, regions.size)
        val region = regions[0]
        assertTrue(region.lines.any { it.status == LineCoverageStatus.PARTIAL })
        // Context: lines 3-7 (5 +/- 2)
        assertEquals(3, region.lines.first().lineNumber)
        assertEquals(7, region.lines.last().lineNumber)
    }

    @Test
    fun `extractUncoveredRegions merges overlapping regions`() {
        val lines = (1..20).map { "line $it" }
        // Lines 3 and 6 are uncovered, with contextLines=2 they overlap (3: 1-5, 6: 4-8)
        val statuses = mapOf(
            3 to LineCoverageStatus.UNCOVERED,
            6 to LineCoverageStatus.UNCOVERED
        )
        val regions = CoveragePreviewPanel.extractUncoveredRegions(lines, statuses, contextLines = 2)
        assertEquals(1, regions.size, "Overlapping regions should be merged")
        val region = regions[0]
        assertEquals(1, region.lines.first().lineNumber)
        assertEquals(8, region.lines.last().lineNumber)
    }

    @Test
    fun `extractUncoveredRegions does not merge non-overlapping regions`() {
        val lines = (1..20).map { "line $it" }
        // Lines 2 and 15 are uncovered, with contextLines=1 they don't overlap
        val statuses = mapOf(
            2 to LineCoverageStatus.UNCOVERED,
            15 to LineCoverageStatus.UNCOVERED
        )
        val regions = CoveragePreviewPanel.extractUncoveredRegions(lines, statuses, contextLines = 1)
        assertEquals(2, regions.size, "Non-overlapping regions should stay separate")
    }

    @Test
    fun `extractUncoveredRegions clamps context to file boundaries`() {
        val lines = (1..5).map { "line $it" }
        val statuses = mapOf(
            1 to LineCoverageStatus.UNCOVERED,
            5 to LineCoverageStatus.UNCOVERED
        )
        val regions = CoveragePreviewPanel.extractUncoveredRegions(lines, statuses, contextLines = 3)
        // With only 5 lines and context=3, everything merges into one region
        assertEquals(1, regions.size)
        assertEquals(1, regions[0].lines.first().lineNumber)
        assertEquals(5, regions[0].lines.last().lineNumber)
    }

    @Test
    fun `extractUncoveredRegions with empty statuses returns empty`() {
        val lines = (1..10).map { "line $it" }
        val statuses = emptyMap<Int, LineCoverageStatus>()
        val regions = CoveragePreviewPanel.extractUncoveredRegions(lines, statuses, contextLines = 2)
        assertTrue(regions.isEmpty())
    }

    @Test
    fun `extractUncoveredRegions with empty lines returns empty`() {
        val lines = emptyList<String>()
        val statuses = mapOf(1 to LineCoverageStatus.UNCOVERED)
        val regions = CoveragePreviewPanel.extractUncoveredRegions(lines, statuses, contextLines = 2)
        assertTrue(regions.isEmpty())
    }

    @Test
    fun `extractUncoveredRegions preserves correct line text`() {
        val lines = listOf("alpha", "beta", "gamma", "delta", "epsilon")
        val statuses = mapOf(3 to LineCoverageStatus.UNCOVERED)
        val regions = CoveragePreviewPanel.extractUncoveredRegions(lines, statuses, contextLines = 1)
        assertEquals(1, regions.size)
        val region = regions[0]
        // Lines 2-4 (context=1 around line 3)
        assertEquals("beta", region.lines[0].text)
        assertEquals("gamma", region.lines[1].text)
        assertEquals("delta", region.lines[2].text)
    }

    @Test
    fun `extractUncoveredRegions assigns correct statuses to context lines`() {
        val lines = (1..10).map { "line $it" }
        val statuses = mapOf(
            4 to LineCoverageStatus.COVERED,
            5 to LineCoverageStatus.UNCOVERED,
            6 to LineCoverageStatus.COVERED
        )
        val regions = CoveragePreviewPanel.extractUncoveredRegions(lines, statuses, contextLines = 1)
        assertEquals(1, regions.size)
        val region = regions[0]
        // Line 4 = COVERED, Line 5 = UNCOVERED, Line 6 = COVERED
        val line4 = region.lines.first { it.lineNumber == 4 }
        val line5 = region.lines.first { it.lineNumber == 5 }
        val line6 = region.lines.first { it.lineNumber == 6 }
        assertEquals(LineCoverageStatus.COVERED, line4.status)
        assertEquals(LineCoverageStatus.UNCOVERED, line5.status)
        assertEquals(LineCoverageStatus.COVERED, line6.status)
    }
}
