package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

/**
 * Phase 7 Task 6.11 — Acceptance test suite that pins the Phase 7 spiller wiring contract.
 *
 * Verifies: when a tool's raw output exceeds [ToolOutputConfig.SPILL_THRESHOLD_CHARS] (30K)
 * and a real [ToolOutputSpiller] is provided, [AgentTool.spillOrFormat] produces:
 *   1. A short preview (head-20 + tail-10 lines + file-reference footer)
 *   2. A spill file on disk containing the full unmodified content
 *
 * Also pins the degradation contract: when no spiller is wired (plain `mockk<Project>
 * (relaxed = true)` — the headless-test default), [AgentTool.spillOrFormat] returns content
 * unchanged with `spilledToFile = null`.
 *
 * Per-category smoke: source-text assertions that one representative tool from each wired
 * category (runtime, debug, inspection, DB, PSI) contains a `spillOrFormat(` call.
 *
 * ## Testing strategy
 *
 * [AgentTool.spillOrFormat] resolves the spiller via IntelliJ's `Project.getServiceIfCreated`
 * which requires the platform service container — not available in headless unit tests without
 * a full `LightPlatformTestCase` setup. To test the spill logic at the unit level without the
 * platform overhead, tests 1–6 use [WiredDummyTool] which overrides [spillOrFormat] to call
 * the supplied [ToolOutputSpiller] directly (bypassing the service lookup). This correctly
 * tests the [ToolOutputSpiller.SpillResult] contract — the same object returned by the
 * production path. Test 7 exercises the production [AgentTool.spillOrFormat] no-op path using
 * [DummyTool] + a relaxed mock, verifying the ClassCastException guard degrades cleanly.
 */
class SpillingWiringTest {

    // ─── Tool stubs ───────────────────────────────────────────────────────────

    /**
     * Minimal [AgentTool] whose [spillOrFormat] is wired directly to a provided
     * [ToolOutputSpiller], bypassing the IntelliJ service lookup. Used for tests 1–6
     * that verify the actual spill logic under a real spiller.
     */
    private class WiredDummyTool(private val spiller: ToolOutputSpiller) : AgentTool {
        override val name = "dummy_spill_test_tool"
        override val description = "test stub for SpillingWiringTest (wired)"
        override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override suspend fun execute(params: JsonObject, project: Project): ToolResult =
            throw UnsupportedOperationException("not used in unit tests")

        /** Directly delegate to the provided spiller — no IntelliJ service lookup. */
        override suspend fun spillOrFormat(
            content: String,
            project: Project,
        ): ToolOutputSpiller.SpillResult = spiller.spill(name, content)
    }

    /**
     * Minimal [AgentTool] that uses the default production [spillOrFormat] implementation
     * (IntelliJ service lookup path). Used for Test 7 (degradation / no-op contract).
     */
    private object DummyTool : AgentTool {
        override val name = "dummy_spill_test_tool"
        override val description = "test stub for SpillingWiringTest (production path)"
        override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override suspend fun execute(params: JsonObject, project: Project): ToolResult =
            throw UnsupportedOperationException("not used in unit tests")
        // spillOrFormat uses the default implementation from AgentTool interface
    }

    // ─── Test 1 — Large content (100K, many lines) spills to disk ────────────

    @Test
    fun `raw content over 30K spills to disk with short preview`(@TempDir spillDir: Path) = runTest {
        // 2000 lines × ~50 chars each ≈ 100K — well above 30K threshold and > 30 lines
        val rawContent = (1..2_000).joinToString("\n") { "line-$it: " + "a".repeat(40) }
        val tool = WiredDummyTool(ToolOutputSpiller(spillDir))
        val project = mockk<Project>(relaxed = true)

        val result = tool.spillOrFormat(rawContent, project)

        assertTrue(result.preview.length < 3_000,
            "Preview should be short (< 3000 chars) but was ${result.preview.length}")
        assertNotNull(result.spilledToFile, "spilledToFile must be non-null for >30K content")
        assertTrue(File(result.spilledToFile!!).exists(),
            "Spill file must exist on disk at ${result.spilledToFile}")
        assertEquals(rawContent, File(result.spilledToFile!!).readText(),
            "Spill file must contain the full unmodified content")
        assertTrue(result.preview.contains("[Output saved to:"),
            "Preview must contain the spill-marker footer")
    }

    // ─── Test 2 — Under-threshold content is passed through unchanged ─────────

    @Test
    fun `content under 30K threshold is returned unchanged without spill file`(@TempDir spillDir: Path) = runTest {
        val rawContent = "x".repeat(1_000)
        val tool = WiredDummyTool(ToolOutputSpiller(spillDir))
        val project = mockk<Project>(relaxed = true)

        val result = tool.spillOrFormat(rawContent, project)

        assertEquals(rawContent, result.preview, "Under-threshold: preview must equal raw content")
        assertNull(result.spilledToFile, "Under-threshold: spilledToFile must be null")
    }

    // ─── Test 3 — Exactly at threshold: no spill ──────────────────────────────

    @Test
    fun `content at exactly 30000 chars is not spilled`(@TempDir spillDir: Path) = runTest {
        val rawContent = "z".repeat(30_000)
        val tool = WiredDummyTool(ToolOutputSpiller(spillDir))
        val project = mockk<Project>(relaxed = true)

        val result = tool.spillOrFormat(rawContent, project)

        assertEquals(rawContent, result.preview, "Exactly at threshold: preview must equal raw content")
        assertNull(result.spilledToFile, "Exactly at threshold: no spill file expected")
    }

    // ─── Test 4 — One char over threshold spills ──────────────────────────────

    @Test
    fun `content at 30001 chars is spilled`(@TempDir spillDir: Path) = runTest {
        // Use multi-line content so the head/tail preview is shorter than the raw content.
        // 601 lines × ~50 chars ≈ 30050 chars — just over the 30K threshold.
        val rawContent = (1..601).joinToString("\n") { "ln$it:" + "y".repeat(45) }
        assertTrue(rawContent.length > 30_000, "Pre-condition: content must exceed threshold")
        val tool = WiredDummyTool(ToolOutputSpiller(spillDir))
        val project = mockk<Project>(relaxed = true)

        val result = tool.spillOrFormat(rawContent, project)

        assertNotNull(result.spilledToFile, "spilledToFile must be non-null for >30K content")
        assertTrue(result.preview.length < rawContent.length,
            "Preview must be shorter than raw content for >30K multi-line input")
    }

    // ─── Test 5 — Newlines preserved through spill ────────────────────────────

    @Test
    fun `newlines in content are preserved in spill file`(@TempDir spillDir: Path) = runTest {
        val lines = (1..2_000).map { "structured-line-$it: some data here" }
        val rawContent = lines.joinToString("\n")
        assertTrue(rawContent.length > 30_000, "Pre-condition: content must exceed threshold")

        val tool = WiredDummyTool(ToolOutputSpiller(spillDir))
        val project = mockk<Project>(relaxed = true)
        val result = tool.spillOrFormat(rawContent, project)

        assertNotNull(result.spilledToFile)
        val recoveredLines = File(result.spilledToFile!!).readText().lines()
        assertEquals(lines.first(), recoveredLines.first(), "First line must survive round-trip")
        assertEquals(lines.last(), recoveredLines.last(), "Last line must survive round-trip")
        assertEquals(lines.size, recoveredLines.size, "Line count must be preserved")
    }

    // ─── Test 6 — Head-20 + tail-10 content correctness ──────────────────────

    @Test
    fun `preview contains exactly head-20 and tail-10 lines from 100-line content`(@TempDir spillDir: Path) = runTest {
        // 100 lines each padded to 400 chars → total ≫ 30K
        val lines = (0 until 100).map { i -> "line-$i:" + "x".repeat(400) }
        val rawContent = lines.joinToString("\n")
        assertTrue(rawContent.length > 30_000, "Pre-condition: content must exceed threshold")

        val tool = WiredDummyTool(ToolOutputSpiller(spillDir))
        val project = mockk<Project>(relaxed = true)
        val result = tool.spillOrFormat(rawContent, project)

        assertNotNull(result.spilledToFile)

        // Head: first 20 lines (indices 0–19) must appear in preview
        for (i in 0 until 20) {
            assertTrue(result.preview.contains("line-$i:"),
                "Preview must contain head line $i (line-$i:)")
        }

        // Middle: lines 20–89 must NOT appear in preview
        for (i in 20 until 90) {
            assertTrue(!result.preview.contains("line-$i:"),
                "Preview must NOT contain middle line $i (line-$i:)")
        }

        // Tail: last 10 lines (indices 90–99) must appear in preview
        for (i in 90 until 100) {
            assertTrue(result.preview.contains("line-$i:"),
                "Preview must contain tail line $i (line-$i:)")
        }

        // Omission marker between head and tail
        assertTrue(result.preview.contains("..."),
            "Preview must contain the omission separator '...'")
    }

    // ─── Test 7 — Unwired spiller (headless defaults) degrades to no-op ───────

    @Test
    fun `no spiller wired degrades to no-op returning content unchanged`() = runTest {
        val rawContent = "b".repeat(100_000)
        // Plain relaxed mock — getServiceIfCreated returns a typed mock proxy which triggers
        // ClassCastException in spillOrFormat (caught → spiller = null → no-op path).
        val project = mockk<Project>(relaxed = true)

        val result = DummyTool.spillOrFormat(rawContent, project)

        assertEquals(rawContent, result.preview,
            "When no spiller is wired, preview must equal the full raw content")
        assertNull(result.spilledToFile,
            "When no spiller is wired, spilledToFile must be null")
    }

    // ─── Test 8 — Per-category smoke: source-text wiring verification ─────────

    /**
     * Light-weight "is it wired?" checks: each representative tool's source file
     * must contain at least one `spillOrFormat(` call. These fail fast if a future
     * refactor accidentally removes the wiring without updating the tests.
     *
     * Tools checked (one per Phase 7 wired category):
     *   - Runtime    : JavaRuntimeExecTool
     *   - Debug      : DebugInspectTool
     *   - Inspection : RunInspectionsTool
     *   - DB         : DbQueryTool
     *   - PSI        : FindReferencesTool
     */
    @Test
    fun `runtime tool JavaRuntimeExecTool is wired to spillOrFormat`() {
        assertToolSourceContainsSpillOrFormat("runtime/JavaRuntimeExecTool.kt")
    }

    @Test
    fun `debug tool DebugInspectTool is wired to spillOrFormat`() {
        assertToolSourceContainsSpillOrFormat("debug/DebugInspectTool.kt")
    }

    @Test
    fun `inspection tool RunInspectionsTool is wired to spillOrFormat`() {
        assertToolSourceContainsSpillOrFormat("ide/RunInspectionsTool.kt")
    }

    @Test
    fun `db tool DbQueryTool is wired to spillOrFormat`() {
        assertToolSourceContainsSpillOrFormat("database/DbQueryTool.kt")
    }

    @Test
    fun `psi tool FindReferencesTool is wired to spillOrFormat`() {
        assertToolSourceContainsSpillOrFormat("psi/FindReferencesTool.kt")
    }

    // ─── Smoke helper ─────────────────────────────────────────────────────────

    /**
     * Searches the source tree for [relPath] under the `tools/` package and asserts
     * the file contains at least one `spillOrFormat(` call.
     */
    private fun assertToolSourceContainsSpillOrFormat(relPath: String) {
        val srcRoot = locateSourceRoot()
        val toolFile = File(srcRoot, "com/workflow/orchestrator/agent/tools/$relPath")
        assertTrue(toolFile.exists(), "Source file not found: ${toolFile.absolutePath}")
        val source = toolFile.readText()
        assertTrue(source.contains("spillOrFormat("),
            "Expected spillOrFormat( call in $relPath but none was found")
    }

    /**
     * Resolves the `src/main/kotlin` source root for the `:agent` module.
     * Handles three execution contexts:
     *  - Gradle: cwd = project root (worktree root)
     *  - IntelliJ runner: cwd = module directory
     *  - Fallback: parent of cwd
     */
    private fun locateSourceRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        val fromProjectRoot = File(cwd, "agent/src/main/kotlin")
        if (fromProjectRoot.isDirectory) return fromProjectRoot
        val fromModuleRoot = File(cwd, "src/main/kotlin")
        if (fromModuleRoot.isDirectory) return fromModuleRoot
        val worktreeParent: File = cwd.parentFile ?: cwd
        val fromWorktree = File(worktreeParent, "agent/src/main/kotlin")
        if (fromWorktree.isDirectory) return fromWorktree
        throw IllegalStateException(
            "Cannot locate agent/src/main/kotlin from cwd=${cwd.absolutePath}"
        )
    }
}
