package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
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
    // Test helpers
    // ═══════════════════════════════════════════════════

    private fun simpleDetail(
        coveredLines: Int,
        totalLines: Int,
        coveredBranches: Int = 0,
        totalBranches: Int = 0,
        noneLines: List<Int> = emptyList(),
        methods: List<MethodCoverageDetail> = emptyList()
    ) = FileCoverageDetail(
        coveredLines = coveredLines,
        totalLines = totalLines,
        coveredBranches = coveredBranches,
        totalBranches = totalBranches,
        lineCoveragePercent = if (totalLines == 0) 0.0 else coveredLines.toDouble() / totalLines * 100,
        branchCoveragePercent = if (totalBranches == 0) 0.0 else coveredBranches.toDouble() / totalBranches * 100,
        methods = methods,
        lines = noneLines.map { n ->
            LineCoverageDetail(n, 0, LineCoverageStatus.NONE, null, emptyList(), emptyList())
        }
    )

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
        tool.lastSnapshot = CoverageSnapshot(
            files = mapOf(
                "com.example.FooService" to simpleDetail(
                    coveredLines = 40, totalLines = 50,
                    noneLines = listOf(10, 11, 12, 13, 14, 30)
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
        assertTrue(result.content.contains("10"))
        assertTrue(result.content.contains("30"))
    }

    @Test
    fun `get_file_coverage matches by filename suffix`() = runTest {
        tool.lastSnapshot = CoverageSnapshot(
            files = mapOf(
                "com.example.FooService" to simpleDetail(coveredLines = 10, totalLines = 10)
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
            files = mapOf("com.example.FooService" to simpleDetail(10, 10))
        )

        val result = tool.execute(buildJsonObject {
            put("action", "get_file_coverage")
            put("file_path", "BarService")
        }, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("com.example.FooService"))
    }

    // ═══════════════════════════════════════════════════
    // FileCoverageDetail calculations
    // ═══════════════════════════════════════════════════

    @Test
    fun `lineCoveragePercent calculates correctly`() {
        val detail = simpleDetail(75, 100)
        assertEquals(75.0, detail.lineCoveragePercent, 0.001)
    }

    @Test
    fun `lineCoveragePercent with zero total returns 0`() {
        val detail = simpleDetail(0, 0)
        assertEquals(0.0, detail.lineCoveragePercent, 0.001)
    }

    @Test
    fun `lineCoveragePercent full coverage`() {
        val detail = simpleDetail(50, 50)
        assertEquals(100.0, detail.lineCoveragePercent, 0.001)
    }

    @Test
    fun `lineCoveragePercent partial coverage`() {
        val detail = simpleDetail(51, 65)
        assertEquals(51.0 / 65.0 * 100, detail.lineCoveragePercent, 0.001)
    }

    @Test
    fun `branchCoveragePercent calculates correctly`() {
        val detail = simpleDetail(10, 20, coveredBranches = 6, totalBranches = 10)
        assertEquals(60.0, detail.branchCoveragePercent, 0.001)
    }

    @Test
    fun `branchCoveragePercent is zero when no branches`() {
        val detail = simpleDetail(10, 20, coveredBranches = 0, totalBranches = 0)
        assertEquals(0.0, detail.branchCoveragePercent, 0.001)
    }

    // ═══════════════════════════════════════════════════
    // formatFileCoverageDetail — line coverage section
    // ═══════════════════════════════════════════════════

    @Test
    fun `formatFileCoverageDetail shows class name and line percent`() {
        val detail = simpleDetail(80, 100)
        val out = CoverageTool.formatFileCoverageDetailPublic("com.example.OrderService", detail)
        assertTrue(out.contains("OrderService"))
        assertTrue(out.contains("80.0%"))
        assertTrue(out.contains("80/100"))
    }

    @Test
    fun `formatFileCoverageDetail shows NA when no branch data`() {
        val detail = simpleDetail(10, 20)
        val out = CoverageTool.formatFileCoverageDetailPublic("com.example.Foo", detail)
        assertTrue(out.contains("N/A"))
    }

    @Test
    fun `formatFileCoverageDetail shows branch percent when branch data present`() {
        val detail = simpleDetail(10, 20, coveredBranches = 4, totalBranches = 8)
        val out = CoverageTool.formatFileCoverageDetailPublic("com.example.Foo", detail)
        assertTrue(out.contains("50.0%"))
        assertTrue(out.contains("4/8"))
    }

    @Test
    fun `formatFileCoverageDetail all covered shows all lines covered message`() {
        val detail = simpleDetail(50, 50)
        val out = CoverageTool.formatFileCoverageDetailPublic("com.example.Foo", detail)
        assertTrue(out.contains("All lines covered"))
    }

    // ═══════════════════════════════════════════════════
    // formatFileCoverageDetail — methods section
    // ═══════════════════════════════════════════════════

    @Test
    fun `formatFileCoverageDetail shows uncovered methods with cross symbol`() {
        val detail = simpleDetail(5, 20, methods = listOf(
            MethodCoverageDetail("processRefund(Ljava/lang/String;)V", 0, 8, 0, 4),
            MethodCoverageDetail("validate()V", 5, 12, 2, 4)
        ))
        val out = CoverageTool.formatFileCoverageDetailPublic("com.example.Foo", detail)
        assertTrue(out.contains("✗"))
        assertTrue(out.contains("processRefund"))
        assertTrue(out.contains("0/8 lines"))
    }

    @Test
    fun `formatFileCoverageDetail shows partial methods with tilde`() {
        val detail = simpleDetail(5, 20, methods = listOf(
            MethodCoverageDetail("applyDiscount(D)V", 4, 8, 1, 4)
        ))
        val out = CoverageTool.formatFileCoverageDetailPublic("com.example.Foo", detail)
        assertTrue(out.contains("~"))
        assertTrue(out.contains("applyDiscount"))
        assertTrue(out.contains("4/8 lines"))
    }

    @Test
    fun `formatFileCoverageDetail shows all covered when all methods covered`() {
        val detail = simpleDetail(20, 20, methods = listOf(
            MethodCoverageDetail("doWork()V", 20, 20, 4, 4)
        ))
        val out = CoverageTool.formatFileCoverageDetailPublic("com.example.Foo", detail)
        assertTrue(out.contains("all covered"))
    }

    @Test
    fun `formatFileCoverageDetail strips JVM descriptor from method name display`() {
        val detail = simpleDetail(0, 10, methods = listOf(
            MethodCoverageDetail("processOrder(Lcom/example/Order;)V", 0, 10, 0, 0)
        ))
        val out = CoverageTool.formatFileCoverageDetailPublic("com.example.Foo", detail)
        // Should show just method name, not full JVM descriptor
        assertTrue(out.contains("processOrder"))
        assertFalse(out.contains("Lcom/example"))
    }

    // ═══════════════════════════════════════════════════
    // formatFileCoverageDetail — uncovered lines section
    // ═══════════════════════════════════════════════════

    @Test
    fun `formatFileCoverageDetail shows NONE lines`() {
        val detail = FileCoverageDetail(
            coveredLines = 8, totalLines = 10,
            coveredBranches = 0, totalBranches = 0,
            lineCoveragePercent = 80.0, branchCoveragePercent = 0.0,
            methods = emptyList(),
            lines = listOf(
                LineCoverageDetail(42, 0, LineCoverageStatus.NONE, null, emptyList(), emptyList()),
                LineCoverageDetail(43, 0, LineCoverageStatus.NONE, null, emptyList(), emptyList())
            )
        )
        val out = CoverageTool.formatFileCoverageDetailPublic("com.example.Foo", detail)
        assertTrue(out.contains("Uncovered / Partial Lines"))
        assertTrue(out.contains("42"))
        assertTrue(out.contains("NONE"))
    }

    @Test
    fun `formatFileCoverageDetail shows PARTIAL lines with branch detail`() {
        val detail = FileCoverageDetail(
            coveredLines = 9, totalLines = 10,
            coveredBranches = 1, totalBranches = 2,
            lineCoveragePercent = 90.0, branchCoveragePercent = 50.0,
            methods = emptyList(),
            lines = listOf(
                LineCoverageDetail(
                    lineNumber = 67, hits = 5,
                    status = LineCoverageStatus.PARTIAL,
                    methodSignature = "applyDiscount(D)V",
                    jumps = listOf(JumpCoverageDetail(0, trueHits = 5, falseHits = 0)),
                    switches = emptyList()
                )
            )
        )
        val out = CoverageTool.formatFileCoverageDetailPublic("com.example.Foo", detail)
        assertTrue(out.contains("PARTIAL"))
        assertTrue(out.contains("hits=5"))
        assertTrue(out.contains("if[0]"))
        assertTrue(out.contains("true=5"))
        assertTrue(out.contains("false=0"))
        assertTrue(out.contains("applyDiscount"))
    }

    @Test
    fun `formatFileCoverageDetail shows switch branch detail`() {
        val detail = FileCoverageDetail(
            coveredLines = 8, totalLines = 10,
            coveredBranches = 1, totalBranches = 4,
            lineCoveragePercent = 80.0, branchCoveragePercent = 25.0,
            methods = emptyList(),
            lines = listOf(
                LineCoverageDetail(
                    lineNumber = 89, hits = 0,
                    status = LineCoverageStatus.NONE,
                    methodSignature = null,
                    jumps = emptyList(),
                    switches = listOf(
                        SwitchCoverageDetail(0, listOf(1 to 3, 2 to 0, null to 0))
                    )
                )
            )
        )
        val out = CoverageTool.formatFileCoverageDetailPublic("com.example.Foo", detail)
        assertTrue(out.contains("switch[0]"))
        assertTrue(out.contains("case(1)=3"))
        assertTrue(out.contains("case(2)=0"))
        assertTrue(out.contains("default=0"))
    }

    @Test
    fun `formatFileCoverageDetail collapses consecutive plain NONE lines into range`() {
        val detail = FileCoverageDetail(
            coveredLines = 7, totalLines = 10,
            coveredBranches = 0, totalBranches = 0,
            lineCoveragePercent = 70.0, branchCoveragePercent = 0.0,
            methods = emptyList(),
            lines = listOf(
                LineCoverageDetail(10, 0, LineCoverageStatus.NONE, null, emptyList(), emptyList()),
                LineCoverageDetail(11, 0, LineCoverageStatus.NONE, null, emptyList(), emptyList()),
                LineCoverageDetail(12, 0, LineCoverageStatus.NONE, null, emptyList(), emptyList())
            )
        )
        val out = CoverageTool.formatFileCoverageDetailPublic("com.example.Foo", detail)
        assertTrue(out.contains("lines 10–12") || out.contains("lines 10-12"))
        // Should not show them individually
        assertFalse(out.contains("line 11 [NONE]"))
    }

    @Test
    fun `formatFileCoverageDetail does not collapse NONE lines with branches`() {
        val detail = FileCoverageDetail(
            coveredLines = 8, totalLines = 10,
            coveredBranches = 0, totalBranches = 2,
            lineCoveragePercent = 80.0, branchCoveragePercent = 0.0,
            methods = emptyList(),
            lines = listOf(
                LineCoverageDetail(10, 0, LineCoverageStatus.NONE, null,
                    listOf(JumpCoverageDetail(0, 0, 0)), emptyList()),
                LineCoverageDetail(11, 0, LineCoverageStatus.NONE, null, emptyList(), emptyList())
            )
        )
        val out = CoverageTool.formatFileCoverageDetailPublic("com.example.Foo", detail)
        // Line 10 has branches — must be shown individually
        assertTrue(out.contains("if[0]"))
    }

    // ═══════════════════════════════════════════════════
    // formatCoverageSummary (multi-file overview)
    // ═══════════════════════════════════════════════════

    @Test
    fun `formatCoverageSummary shows file count and line percents`() {
        val snapshot = CoverageSnapshot(
            files = mapOf(
                "com.example.FooController" to simpleDetail(30, 46),
                "com.example.FooService" to simpleDetail(51, 65),
                "com.example.BarService" to simpleDetail(20, 20)
            )
        )
        val out = CoverageTool.formatCoverageSnapshotPublic(snapshot)
        assertTrue(out.contains("Coverage (3 files):"))
        assertTrue(out.contains("FooController"))
        assertTrue(out.contains("FooService"))
        assertTrue(out.contains("BarService"))
        assertTrue(out.contains("100.0%"))
    }

    @Test
    fun `formatCoverageSummary shows branch percent when branch data present`() {
        val snapshot = CoverageSnapshot(
            files = mapOf(
                "com.example.Foo" to simpleDetail(10, 20, coveredBranches = 6, totalBranches = 10)
            )
        )
        val out = CoverageTool.formatCoverageSnapshotPublic(snapshot)
        assertTrue(out.contains("60.0% branches"))
    }

    @Test
    fun `formatCoverageSummary omits branch info when no branch data`() {
        val snapshot = CoverageSnapshot(
            files = mapOf("com.example.Foo" to simpleDetail(10, 20))
        )
        val out = CoverageTool.formatCoverageSnapshotPublic(snapshot)
        assertFalse(out.contains("branches"))
    }

    @Test
    fun `formatCoverageSummary shows overall line and branch coverage`() {
        val snapshot = CoverageSnapshot(
            files = mapOf(
                "com.example.A" to simpleDetail(30, 46, coveredBranches = 8, totalBranches = 10),
                "com.example.B" to simpleDetail(51, 65, coveredBranches = 12, totalBranches = 20)
            )
        )
        val out = CoverageTool.formatCoverageSnapshotPublic(snapshot)
        val totalCovered = 30 + 51
        val totalLines = 46 + 65
        val expectedLine = String.format("%.1f", totalCovered.toDouble() / totalLines * 100)
        assertTrue(out.contains("Overall: ${expectedLine}% line coverage"))
        assertTrue(out.contains("branch coverage"))
    }

    @Test
    fun `formatCoverageSummary with empty snapshot`() {
        val out = CoverageTool.formatCoverageSnapshotPublic(CoverageSnapshot(files = emptyMap()))
        assertTrue(out.contains("Coverage (0 files):"))
        assertTrue(out.contains("Overall: 0.0%"))
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

    // ═══════════════════════════════════════════════════
    // Data model — LineCoverageStatus
    // ═══════════════════════════════════════════════════

    @Test
    fun `LineCoverageStatus has three values`() {
        val values = LineCoverageStatus.values()
        assertTrue(LineCoverageStatus.FULL in values)
        assertTrue(LineCoverageStatus.PARTIAL in values)
        assertTrue(LineCoverageStatus.NONE in values)
    }

    @Test
    fun `JumpCoverageDetail holds true and false hits`() {
        val jump = JumpCoverageDetail(index = 0, trueHits = 5, falseHits = 0)
        assertEquals(5, jump.trueHits)
        assertEquals(0, jump.falseHits)
    }

    @Test
    fun `SwitchCoverageDetail holds keyed and default cases`() {
        val sw = SwitchCoverageDetail(index = 0, cases = listOf(1 to 3, 2 to 0, null to 1))
        assertEquals(3, sw.cases.size)
        assertEquals(null, sw.cases[2].first)  // default
        assertEquals(1, sw.cases[2].second)    // default hit once
    }

    @Test
    fun `MethodCoverageDetail reports correct stats`() {
        val m = MethodCoverageDetail("doWork()V", coveredLines = 4, totalLines = 8, coveredBranches = 2, totalBranches = 6)
        assertEquals(4, m.coveredLines)
        assertEquals(8, m.totalLines)
        assertEquals(2, m.coveredBranches)
        assertEquals(6, m.totalBranches)
    }
}
