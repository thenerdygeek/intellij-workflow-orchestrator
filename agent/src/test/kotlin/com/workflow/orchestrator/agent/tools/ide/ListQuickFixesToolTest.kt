package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Phase 6 / Task T3 contract tests for [ListQuickFixesTool].
 *
 * The full PSI-driven `execute()` path requires a running IDE (InspectionManager,
 * PsiManager, InspectionProjectProfileManager, DumbService, LocalFileSystem), so
 * we test at the contract boundary — parameter schema, worker allow-list, the
 * error-path `isError` semantics, and source-text structural invariants that
 * Phase 7's ToolOutputSpiller will key off (the `TODO(phase7)` handoff markers
 * and the `renderDiagnosticBody` call site).
 *
 * The source-text invariant tests follow the pattern used in
 * [RunInspectionsToolTest] — read the canonical tool file from the known module
 * layout and grep for behavioural fingerprints.
 */
class ListQuickFixesToolTest {
    private val tool = ListQuickFixesTool()

    @AfterEach
    fun tearDown() {
        unmockkStatic(DumbService::class)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Schema / metadata contract
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `tool name is list_quickfixes`() {
        assertEquals("list_quickfixes", tool.name)
    }

    @Test
    fun `description mentions quick fixes`() {
        assertTrue(
            tool.description.contains("quick fix", ignoreCase = true),
            "description should reference quick fixes: ${tool.description}",
        )
    }

    @Test
    fun `parameters include path and line`() {
        val props = tool.parameters.properties
        assertTrue("path" in props)
        assertTrue("line" in props)
    }

    @Test
    fun `path and line are both required`() {
        assertTrue("path" in tool.parameters.required)
        assertTrue("line" in tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes CODER, ANALYZER, REVIEWER`() {
        assertEquals(
            setOf(WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER),
            tool.allowedWorkers,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Error-path isError semantics (F6 / Task 5.6 invariant).
    //
    // isError=true is reserved for tool-execution failures (missing path,
    // missing/invalid line, missing file, invalid PSI, DumbService blocked,
    // exception). When the tool successfully runs and emits quick fixes (or
    // legitimately reports zero fixes for a valid line), isError=false —
    // the fix list IS the payload, not a failure signal.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `execute without path returns error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        val result = tool.execute(buildJsonObject { }, project)

        assertTrue(result.isError, "missing 'path' must set isError=true: $result")
        assertTrue(
            result.content.contains("path", ignoreCase = true),
            "error message should mention 'path': ${result.content}",
        )
    }

    @Test
    fun `execute without line returns error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        val result = tool.execute(buildJsonObject {
            put("path", "does/not/matter.kt")
        }, project)

        assertTrue(result.isError, "missing 'line' must set isError=true: $result")
        assertTrue(
            result.content.contains("line", ignoreCase = true),
            "error message should mention 'line': ${result.content}",
        )
    }

    @Test
    fun `execute with non-positive line returns error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        val result = tool.execute(buildJsonObject {
            put("path", "does/not/matter.kt")
            put("line", 0)
        }, project)

        assertTrue(result.isError, "line < 1 must set isError=true: $result")
        assertTrue(
            result.content.contains("line", ignoreCase = true),
            "error message should mention 'line': ${result.content}",
        )
    }

    @Test
    fun `execute with nonexistent file path returns error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/nonexistent-project-dir-12345"

        // DumbService.isDumb runs OUTSIDE the outer try/catch (matches the
        // T2 RunInspectionsTool pattern), so we mock it explicitly to avoid
        // a raw ClassCastException from the unconfigured Application container
        // in the mock fixture.
        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns false

        val result = tool.execute(buildJsonObject {
            put("path", "does/not/exist.kt")
            put("line", 1)
        }, project)

        // Either the VFS lookup returns null ("File not found") or the
        // ReadAction.nonBlocking call itself throws (mock project has no
        // Application container for the read action path), which is caught
        // by the outer try and surfaced as "Error listing quick fixes". Both
        // outcomes MUST set isError=true.
        assertTrue(
            result.isError,
            "nonexistent file must set isError=true: $result",
        )
        assertTrue(
            result.content.contains("File not found", ignoreCase = true) ||
                result.content.contains("Error listing quick fixes", ignoreCase = true),
            "error content should identify the failure source " +
                "(expected 'File not found' or 'Error listing quick fixes'): ${result.content}",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Source-text structural contracts.
    //
    // These pin the Phase 7 ToolOutputSpiller handoff contract — Phase 7 greps
    // for `TODO(phase7)` markers at truncation sites, and relies on the tool
    // wrapping its prose through `renderDiagnosticBody(...)` so the structured
    // JSON suffix can be split off and routed to disk.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `source file contains TODO(phase7) markers at truncation sites`() {
        val text = readSource("ListQuickFixesTool.kt")
        val markers = Regex("""TODO\(phase7\)""").findAll(text).count()
        assertTrue(
            markers >= 1,
            "Phase 7 handoff contract: ListQuickFixesTool must mark every " +
                "truncation / cap site with `TODO(phase7)` so Phase 7's executor " +
                "can grep for them. Found $markers marker(s).",
        )
    }

    @Test
    fun `source file returns DiagnosticEntry via renderDiagnosticBody`() {
        val text = readSource("ListQuickFixesTool.kt")
        assertTrue(
            text.contains("renderDiagnosticBody"),
            "F1: ListQuickFixesTool must wrap its prose output with " +
                "`renderDiagnosticBody(prose, entries)` so the full per-item list is " +
                "attached structurally for Phase 7 to route to disk.",
        )
        assertTrue(
            text.contains("DiagnosticEntry"),
            "F1: ListQuickFixesTool must build a list of `DiagnosticEntry` " +
                "objects (one per quick fix) — not just prose.",
        )

        // The MAX_FIXES cap site must have a TODO(phase7) comment within 3
        // lines (either direction) so Phase 7's grep-driven spiller rewrite can
        // locate it reliably.
        val lines = text.lines()
        val capLineIdx = lines.indexOfFirst { it.contains("MAX_FIXES") && !it.contains("private const val") }
        assertTrue(
            capLineIdx >= 0,
            "expected at least one non-definition reference to MAX_FIXES in the source",
        )
        val windowStart = (capLineIdx - 3).coerceAtLeast(0)
        val windowEnd = (capLineIdx + 3).coerceAtMost(lines.lastIndex)
        val window = lines.subList(windowStart, windowEnd + 1).joinToString("\n")
        assertTrue(
            window.contains("TODO(phase7)"),
            "F1/Phase-7 handoff: the MAX_FIXES cap site (line ${capLineIdx + 1}) " +
                "must have an adjacent `TODO(phase7)` comment within 3 lines so the " +
                "Phase 7 executor can locate it. Window:\n$window",
        )
    }

    @Test
    fun `source file builds entries before applying MAX_FIXES cap`() {
        // F1 ordering contract (matches T2 RunInspectionsTool): the full
        // DiagnosticEntry list must be constructed from EVERY collected fix
        // BEFORE `take(MAX_FIXES)` is applied. Phase 7's spiller reads the
        // entries off the ToolResult and must see the lossless list, not
        // the capped preview.
        val text = readSource("ListQuickFixesTool.kt")
        val entriesIdx = text.indexOf("DiagnosticEntry(")
        val takeIdx = text.indexOf("take(MAX_FIXES")
        assertTrue(entriesIdx >= 0, "expected a DiagnosticEntry(...) constructor call")
        assertTrue(takeIdx >= 0, "expected a take(MAX_FIXES...) call for the preview cap")
        assertTrue(
            entriesIdx < takeIdx,
            "F1 ordering: DiagnosticEntry construction must precede take(MAX_FIXES) " +
                "so the structured list is built from all fixes, not the capped preview. " +
                "entriesIdx=$entriesIdx, takeIdx=$takeIdx",
        )
    }

    @Test
    fun `source file uses absolute VirtualFile path for DiagnosticEntry file`() {
        // DiagnosticEntry.file kdoc contract (pinned in commit 52b0d867): the
        // file field MUST be the absolute VirtualFile.path, never a
        // project-relative value or presentableUrl. Grep for the T2-style
        // `file = vf.path` assignment.
        val text = readSource("ListQuickFixesTool.kt")
        assertTrue(
            text.contains("file = vf.path"),
            "DiagnosticEntry.file must be populated from `vf.path` (absolute " +
                "VirtualFile path) — see the kdoc on DiagnosticEntry.file " +
                "(committed in 52b0d867). Cross-tool link-back for Phase 7 " +
                "requires all diagnostic tools to agree on this representation.",
        )
        assertFalse(
            text.contains("presentableUrl"),
            "DiagnosticEntry.file must NOT use VirtualFile.presentableUrl — " +
                "it uses a different path shape and breaks Phase 7 link-back.",
        )
    }

    @Test
    fun `source file marks every quick-fix entry with hasQuickFix=true`() {
        // Every entry produced by ListQuickFixesTool IS a quick fix by
        // construction — the tool only emits entries when it walked into a
        // `problem.fixes` list. The `hasQuickFix` field must therefore be
        // true on every entry; this differs from RunInspectionsTool, whose
        // entries may or may not have fixes.
        val text = readSource("ListQuickFixesTool.kt")
        assertTrue(
            text.contains("hasQuickFix = true"),
            "T3 invariant: ListQuickFixesTool only produces entries FROM quick " +
                "fixes, so every DiagnosticEntry must set `hasQuickFix = true`. " +
                "No conditional fallback — hardcode true.",
        )
    }

    @Test
    fun `DumbService check runs outside outer try block`() {
        // F3 / T2 discipline: the DumbService guard must be a top-level
        // early-return BEFORE the outer `try { ... }` — matches
        // RunInspectionsTool lines 70-72. Regressing this would wrap
        // the guard inside a catch and swallow indexing signals.
        val text = readSource("ListQuickFixesTool.kt")
        val dumbIdx = text.indexOf("DumbService.isDumb(project)")
        val tryIdx = text.indexOf("return try {")
        assertTrue(dumbIdx >= 0, "expected DumbService.isDumb(project) guard")
        assertTrue(tryIdx >= 0, "expected `return try {` outer block opener")
        assertTrue(
            dumbIdx < tryIdx,
            "F3: DumbService.isDumb(project) must appear BEFORE `return try {` " +
                "so indexing-state signals are surfaced as their own ToolResult, " +
                "not swallowed by the outer catch. dumbIdx=$dumbIdx, tryIdx=$tryIdx",
        )
    }

    /**
     * F6 contract: when the tool successfully moves to the already-computed
     * DaemonCodeAnalyzerImpl / HighlightInfo path, the redundant manual
     * `PsiRecursiveElementWalkingVisitor` walk should no longer appear.
     *
     * DISABLED because Phase 6 falls back to the existing manual walk — the
     * `DaemonCodeAnalyzerImpl.getFileHighlightingRanges()` API is on the `Impl`
     * class (not part of the documented public API surface in
     * `docs/superpowers/research/2026-03-20-intellij-api-signatures.md`), and
     * `IntentionManager.getAvailableActions(editor, psiFile)` requires an
     * Editor instance which this tool has no way to obtain for unopened files.
     * The audit also classifies F6 as "redundant work" (performance), not a
     * safety issue — fallback is acceptable. Re-enable when a public
     * replacement API lands or a verified non-impl path is validated.
     */
    @Test
    @Disabled(
        "F6 deferred: DaemonCodeAnalyzerImpl.getFileHighlightingRanges is impl-level " +
            "(not in docs/superpowers/research/2026-03-20-intellij-api-signatures.md) " +
            "and IntentionManager.getAvailableActions needs an Editor instance the tool " +
            "cannot obtain for unopened files. See TODO(phase7) in ListQuickFixesTool.kt."
    )
    fun `source file does not re-run inspection suite per quick fix lookup`() {
        val text = readSource("ListQuickFixesTool.kt")
        assertFalse(
            text.contains("PsiRecursiveElementWalkingVisitor"),
            "F6: `PsiRecursiveElementWalkingVisitor` is the redundant manual-walk " +
                "fingerprint. Replace with a read of " +
                "`DaemonCodeAnalyzerImpl.getFileHighlightingRanges()` + " +
                "`HighlightInfo.findRegisteredQuickFix(...)` (or the successor " +
                "public API) so quick-fix extraction doesn't re-run the full " +
                "inspection suite.",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Reads the canonical tool source file directly from the known module
     * layout. Mirrors `RunInspectionsToolTest.readSource()` — fails loudly if
     * the layout changes instead of silently matching a fixture.
     */
    private fun readSource(name: String): String {
        val userDir = System.getProperty("user.dir")
            ?: error("user.dir system property is not set")
        val root = File(userDir)
        val relSubdir = "src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/$name"
        val moduleRootedPath = File(root, relSubdir)          // user.dir == <repo>/agent
        val repoRootedPath = File(root, "agent/$relSubdir")   // user.dir == <repo>
        val path = when {
            moduleRootedPath.isFile -> moduleRootedPath
            repoRootedPath.isFile -> repoRootedPath
            else -> error(
                "Source file '$name' not found at either expected path:\n" +
                    "  1. ${moduleRootedPath.absolutePath}\n" +
                    "  2. ${repoRootedPath.absolutePath}\n" +
                    "user.dir=$userDir — module layout may have changed.",
            )
        }
        return path.readText()
    }
}
