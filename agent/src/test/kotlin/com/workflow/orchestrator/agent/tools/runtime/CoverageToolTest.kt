package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoverageToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = CoverageTool()

    // ═══════════════════════════════════════════════════
    // Tool metadata
    // ═══════════════════════════════════════════════════

    @Test
    fun `tool name is coverage`() {
        assertEquals("coverage", tool.name)
    }

    @Test
    fun `action enum contains both actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(2, actions!!.size)
        assertTrue("run_with_coverage" in actions)
        assertTrue("get_file_coverage" in actions)
    }

    @Test
    fun `only action is required`() {
        assertEquals(listOf("action"), tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes all expected types`() {
        assertEquals(
            setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("coverage", def.function.name)
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.description.isNotBlank())
    }

    // ═══════════════════════════════════════════════════
    // Action dispatch
    // ═══════════════════════════════════════════════════

    @Test
    fun `missing action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("action"))
    }

    @Test
    fun `unknown action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "nonexistent") }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown action"))
        assertTrue(result.content.contains("run_with_coverage"))
        assertTrue(result.content.contains("get_file_coverage"))
    }

    // ═══════════════════════════════════════════════════
    // Parameter validation: run_with_coverage
    // ═══════════════════════════════════════════════════

    @Test
    fun `run_with_coverage without test_class returns error`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "run_with_coverage") }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("test_class"))
    }

    // ═══════════════════════════════════════════════════
    // Parameter validation: get_file_coverage
    // ═══════════════════════════════════════════════════

    @Test
    fun `get_file_coverage without file_path returns error`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "get_file_coverage") }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("file_path"))
    }

    @Test
    fun `get_file_coverage with no prior run returns error`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("action", "get_file_coverage")
            put("file_path", "com/example/Foo.kt")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Run 'run_with_coverage' first"))
    }

    @Test
    fun `get_file_coverage with cached snapshot returns data`() = runTest {
        // Seed the cache
        tool.lastSnapshot = CoverageSnapshot(
            files = mapOf(
                "com.example.FooService" to FileCoverageResult(
                    coveredLines = 40,
                    totalLines = 50,
                    uncoveredRanges = listOf(10..14, 30..30)
                )
            )
        )

        val result = tool.execute(buildJsonObject {
            put("action", "get_file_coverage")
            put("file_path", "com.example.FooService")
        }, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("80.0%"))
        assertTrue(result.content.contains("40/50"))
        assertTrue(result.content.contains("10-14"))
        assertTrue(result.content.contains("30"))
    }

    @Test
    fun `get_file_coverage matches by filename suffix`() = runTest {
        tool.lastSnapshot = CoverageSnapshot(
            files = mapOf(
                "com.example.FooService" to FileCoverageResult(
                    coveredLines = 10,
                    totalLines = 10,
                    uncoveredRanges = emptyList()
                )
            )
        )

        val result = tool.execute(buildJsonObject {
            put("action", "get_file_coverage")
            put("file_path", "FooService")
        }, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("100.0%"))
    }

    @Test
    fun `get_file_coverage for unknown file lists available files`() = runTest {
        tool.lastSnapshot = CoverageSnapshot(
            files = mapOf(
                "com.example.FooService" to FileCoverageResult(10, 10, emptyList())
            )
        )

        val result = tool.execute(buildJsonObject {
            put("action", "get_file_coverage")
            put("file_path", "BarService")
        }, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("com.example.FooService"))
    }

    // ═══════════════════════════════════════════════════
    // FileCoverageResult calculations
    // ═══════════════════════════════════════════════════

    @Test
    fun `coveragePercent calculates correctly`() {
        val result = FileCoverageResult(coveredLines = 75, totalLines = 100, uncoveredRanges = emptyList())
        assertEquals(75.0, result.coveragePercent, 0.001)
    }

    @Test
    fun `coveragePercent with zero total returns 0`() {
        val result = FileCoverageResult(coveredLines = 0, totalLines = 0, uncoveredRanges = emptyList())
        assertEquals(0.0, result.coveragePercent, 0.001)
    }

    @Test
    fun `coveragePercent full coverage`() {
        val result = FileCoverageResult(coveredLines = 50, totalLines = 50, uncoveredRanges = emptyList())
        assertEquals(100.0, result.coveragePercent, 0.001)
    }

    @Test
    fun `coveragePercent partial coverage`() {
        val result = FileCoverageResult(coveredLines = 51, totalLines = 65, uncoveredRanges = emptyList())
        assertEquals(51.0 / 65.0 * 100, result.coveragePercent, 0.001)
    }

    // ═══════════════════════════════════════════════════
    // Coverage summary formatting
    // ═══════════════════════════════════════════════════

    @Test
    fun `formatCoverageSummary formats multiple files correctly`() {
        val snapshot = CoverageSnapshot(
            files = mapOf(
                "com.example.FooController" to FileCoverageResult(
                    coveredLines = 30,
                    totalLines = 46,
                    uncoveredRanges = listOf(22..30, 55..60)
                ),
                "com.example.FooService" to FileCoverageResult(
                    coveredLines = 51,
                    totalLines = 65,
                    uncoveredRanges = listOf(45..48, 62..62)
                ),
                "com.example.BarService" to FileCoverageResult(
                    coveredLines = 20,
                    totalLines = 20,
                    uncoveredRanges = emptyList()
                )
            )
        )

        val output = CoverageTool.formatCoverageSnapshotPublic(snapshot)

        // Verify header
        assertTrue(output.contains("Coverage (3 files):"))

        // Verify individual file entries
        assertTrue(output.contains("FooController"))
        assertTrue(output.contains("FooService"))
        assertTrue(output.contains("BarService"))

        // Verify uncovered ranges are shown
        assertTrue(output.contains("lines 22-30"))
        assertTrue(output.contains("lines 55-60"))
        assertTrue(output.contains("lines 45-48"))
        assertTrue(output.contains("line 62"))

        // 100% file should not show uncovered ranges
        assertTrue(output.contains("BarService — 100.0%"))

        // Verify overall coverage
        val totalCovered = 30 + 51 + 20
        val totalLines = 46 + 65 + 20
        val expectedOverall = String.format("%.1f", totalCovered.toDouble() / totalLines * 100)
        assertTrue(output.contains("Overall: ${expectedOverall}% line coverage"))
    }

    @Test
    fun `formatCoverageSummary with empty snapshot`() {
        val snapshot = CoverageSnapshot(files = emptyMap())
        val output = CoverageTool.formatCoverageSnapshotPublic(snapshot)
        assertTrue(output.contains("Coverage (0 files):"))
        assertTrue(output.contains("Overall: 0.0%"))
    }

    // ═══════════════════════════════════════════════════
    // collapseToRanges utility
    // ═══════════════════════════════════════════════════

    @Test
    fun `collapseToRanges with empty list returns empty`() {
        assertEquals(emptyList<IntRange>(), CoverageTool.collapseToRanges(emptyList()))
    }

    @Test
    fun `collapseToRanges collapses contiguous lines`() {
        val ranges = CoverageTool.collapseToRanges(listOf(3, 4, 5, 10, 12, 13))
        assertEquals(3, ranges.size)
        assertEquals(3..5, ranges[0])
        assertEquals(10..10, ranges[1])
        assertEquals(12..13, ranges[2])
    }

    @Test
    fun `collapseToRanges single line`() {
        val ranges = CoverageTool.collapseToRanges(listOf(42))
        assertEquals(1, ranges.size)
        assertEquals(42..42, ranges[0])
    }

    @Test
    fun `collapseToRanges all contiguous`() {
        val ranges = CoverageTool.collapseToRanges(listOf(1, 2, 3, 4, 5))
        assertEquals(1, ranges.size)
        assertEquals(1..5, ranges[0])
    }

    @Test
    fun `collapseToRanges handles unsorted input`() {
        val ranges = CoverageTool.collapseToRanges(listOf(5, 3, 4, 1))
        assertEquals(2, ranges.size)
        assertEquals(1..1, ranges[0])
        assertEquals(3..5, ranges[1])
    }
}
