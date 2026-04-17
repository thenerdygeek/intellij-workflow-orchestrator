package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
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
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Phase 6 / Task T5 contract tests for [SemanticDiagnosticsTool].
 *
 * The full `execute()` path requires a running IDE (LocalFileSystem,
 * PsiManager, DumbService, the LanguageIntelligenceProvider), so we test at
 * the contract boundary — parameter schema, worker allow-list, error-path
 * `isError` semantics (Task 5.6), and source-text structural invariants
 * that Phase 7's ToolOutputSpiller will key off.
 *
 * Mirrors the pattern pinned in [RunInspectionsToolTest], [ListQuickFixesToolTest],
 * and [ProblemViewToolTest].
 */
class SemanticDiagnosticsToolTest {
    private val registry = LanguageProviderRegistry()
    private val tool = SemanticDiagnosticsTool(registry)

    @AfterEach
    fun tearDown() {
        unmockkStatic(DumbService::class)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Schema / metadata contract
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `tool name is diagnostics`() {
        assertEquals("diagnostics", tool.name)
    }

    @Test
    fun `description mentions compilation errors and semantic analysis`() {
        assertTrue(tool.description.contains("compilation", ignoreCase = true))
        assertTrue(tool.description.contains("semantic", ignoreCase = true))
    }

    @Test
    fun `parameters require path`() {
        assertTrue("path" in tool.parameters.properties)
        assertTrue("path" in tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes CODER, REVIEWER, ANALYZER`() {
        assertEquals(
            setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER),
            tool.allowedWorkers,
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("diagnostics", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertTrue("path" in def.function.parameters.required)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Error-path isError semantics (Task 5.6 invariant).
    //
    // isError=true is reserved for tool-execution failures (missing path,
    // DumbService blocked, file-not-found, PSI parse failure, PSI invalidation,
    // uncaught exception). When the tool successfully runs and emits problems
    // (or legitimately reports zero errors, or "unsupported language"), the
    // problem list IS the payload — isError=false.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `execute without path returns error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        val result = tool.execute(buildJsonObject {}, project)

        assertTrue(result.isError, "missing path must set isError=true: $result")
        assertTrue(result.content.contains("path", ignoreCase = true))
    }

    @Test
    fun `execute with path traversal returns error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns false

        val result = tool.execute(buildJsonObject {
            put("path", "../../etc/passwd")
        }, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("outside the project"))
    }

    @Test
    fun `execute when DumbService is indexing returns isError with indexing message`() = runTest {
        // Task 5.2 / F3: DumbService guard must short-circuit BEFORE attempting
        // to touch LocalFileSystem, PsiManager, or the provider. Its cache may
        // be stale during indexing. Mirrors the pattern pinned for T2/T3/T4.
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns true

        val result = tool.execute(buildJsonObject {
            put("path", "src/Main.kt")
        }, project)

        assertTrue(
            result.isError,
            "DumbService.isDumb=true must set isError=true so the LLM treats " +
                "this as a transient failure, not a successful zero-problems result. " +
                "Got: $result",
        )
        assertTrue(
            result.content.contains("indexing", ignoreCase = true),
            "DumbService guard must surface an indexing-specific message so the " +
                "LLM can retry after indexing completes. Got: ${result.content}",
        )
    }

    @Test
    fun `execute when DumbService is indexing short-circuits before file validation`() = runTest {
        // Discriminating assertion: when DumbService=true, the guard must fire
        // BEFORE path validation / file lookup so the LLM sees a consistent
        // indexing message regardless of whether the file exists. If the guard
        // were placed inside the outer try, the PathValidator or
        // findFileByIoFile would win first and the LLM would see a confusing
        // "File not found" during indexing instead.
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/nonexistent-project-dir-12345"

        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns true

        val result = tool.execute(buildJsonObject {
            put("path", "does/not/exist.kt")
        }, project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("indexing", ignoreCase = true),
            "Indexing guard must run before path validation so the LLM sees the " +
                "indexing signal, not a misleading 'File not found'. Got: ${result.content}",
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
        val text = readSource("SemanticDiagnosticsTool.kt")
        val markers = Regex("""TODO\(phase7\)""").findAll(text).count()
        assertTrue(
            markers >= 1,
            "Phase 7 handoff contract: SemanticDiagnosticsTool must mark every " +
                "truncation / cap site with `TODO(phase7)` so Phase 7's executor " +
                "can grep for them. Found $markers marker(s).",
        )
    }

    @Test
    fun `source file returns DiagnosticEntry via renderDiagnosticBody`() {
        val text = readSource("SemanticDiagnosticsTool.kt")
        assertTrue(
            text.contains("renderDiagnosticBody"),
            "F1 (Task 5.1): SemanticDiagnosticsTool must wrap its prose output with " +
                "`renderDiagnosticBody(prose, entries)` so the full per-item list is " +
                "attached structurally for Phase 7 to route to disk.",
        )
        assertTrue(
            text.contains("DiagnosticEntry"),
            "F1 (Task 5.1): SemanticDiagnosticsTool must build a list of " +
                "`DiagnosticEntry` objects (one per provider diagnostic) — not just prose.",
        )
    }

    @Test
    fun `source file uses absolute VirtualFile path for DiagnosticEntry file`() {
        // DiagnosticEntry.file kdoc contract (pinned in commit 52b0d867): the
        // file field MUST be the absolute VirtualFile.path, never a
        // project-relative value or presentableUrl. The current prose uses
        // `vf.name`, which is fine for prose — but entries must use `vf.path`.
        val text = readSource("SemanticDiagnosticsTool.kt")
        assertTrue(
            text.contains("file = vf.path"),
            "DiagnosticEntry.file must be populated from `vf.path` (absolute " +
                "VirtualFile path) — see the kdoc on DiagnosticEntry.file " +
                "(committed in 52b0d867). Cross-tool link-back for Phase 7 " +
                "requires all diagnostic tools to agree on this representation.",
        )
        assertFalse(
            text.contains("file = vf.name"),
            "DiagnosticEntry.file must NOT use VirtualFile.name — it's not " +
                "absolute and breaks Phase 7 link-back.",
        )
        assertFalse(
            text.contains("presentableUrl"),
            "DiagnosticEntry.file must NOT use VirtualFile.presentableUrl — " +
                "it uses a different path shape and breaks Phase 7 link-back.",
        )
    }

    @Test
    fun `source file builds entries before applying the preview cap`() {
        // F1 ordering contract (matches T2 RunInspectionsTool, T3
        // ListQuickFixesTool, T4 ProblemViewTool): the full DiagnosticEntry
        // list must be built from `relevantProblems` (the edit-range-filtered
        // list) BEFORE `.take(...)` is applied. The pre-T5 code computed
        // `relevantProblems.take(20)` inline and used that for prose, which is
        // fine — but entries must come from the uncapped `relevantProblems`.
        val text = readSource("SemanticDiagnosticsTool.kt")
        val entriesIdx = text.indexOf("DiagnosticEntry(")
        assertTrue(entriesIdx >= 0, "expected a DiagnosticEntry(...) constructor call")

        // There must be at least one `.take(` cap site, and the DiagnosticEntry
        // construction must come BEFORE every `.take(...)` used for the prose
        // preview.
        val takeIdx = text.indexOf(".take(")
        assertTrue(takeIdx >= 0, "expected a .take(...) call for the preview cap")
        assertTrue(
            entriesIdx < takeIdx,
            "F1 ordering: DiagnosticEntry construction must precede .take(...) " +
                "so the structured list is built from all relevantProblems, not the " +
                "capped preview. entriesIdx=$entriesIdx, takeIdx=$takeIdx",
        )
    }

    @Test
    fun `DumbService check runs outside outer try block`() {
        // Task 5.2 / F3 / T2 / T3 / T4 discipline: the DumbService guard must
        // be a top-level early-return BEFORE the outer `try { ... }` — matches
        // RunInspectionsTool lines 69-71, ListQuickFixesTool lines 77-79,
        // ProblemViewTool lines 106-111. Regressing this would wrap the guard
        // inside a catch and swallow indexing signals.
        val text = readSource("SemanticDiagnosticsTool.kt")
        val dumbIdx = text.indexOf("DumbService.isDumb(project)")
        val tryIdx = text.indexOf("return try {")
        assertTrue(dumbIdx >= 0, "expected DumbService.isDumb(project) guard")
        assertTrue(tryIdx >= 0, "expected `return try {` outer block opener")
        assertTrue(
            dumbIdx < tryIdx,
            "Task 5.2 / F3: DumbService.isDumb(project) must appear BEFORE " +
                "`return try {` so indexing-state signals are surfaced as their own " +
                "ToolResult, not swallowed by the outer catch. dumbIdx=$dumbIdx, " +
                "tryIdx=$tryIdx",
        )
    }

    @Test
    fun `source file uses DiagnosticSubsystem dot PROVIDER for toolId`() {
        // Cross-tool vocabulary contract (DiagnosticSubsystem in
        // DiagnosticModels.kt): T5 entries are produced by the language
        // provider, so toolId MUST be the shared constant `PROVIDER` rather
        // than an ad-hoc literal. The constant was added in commit b2e62c27
        // explicitly for T5. Using a bare "provider" literal would drift from
        // the closed vocabulary that Phase 7 chips/filters rely on.
        val text = readSource("SemanticDiagnosticsTool.kt")
        assertTrue(
            text.contains("DiagnosticSubsystem.PROVIDER"),
            "Task 5.1: DiagnosticEntry.toolId must use " +
                "`DiagnosticSubsystem.PROVIDER` (the constant added in b2e62c27 for " +
                "T5). Using a bare string literal drifts from the closed vocabulary.",
        )
    }

    @Test
    fun `source file normalizes provider severity via shared helper`() {
        // Task 5.7 severity pinning: the provider's DiagnosticInfo.severity is
        // a String emitted by the implementation — currently "ERROR" (upper)
        // from both JavaKotlinProvider and PythonProvider. To keep T5 robust
        // against future providers that emit mixed-case or unknown strings,
        // the tool must funnel provider severity through a shared
        // normalizer (`normalizeProviderSeverity`) — this is the THIRD
        // normalization site (alongside ProblemHighlightType and
        // HighlightSeverity) where extraction is justified.
        val text = readSource("SemanticDiagnosticsTool.kt")
        assertTrue(
            text.contains("normalizeProviderSeverity"),
            "Task 5.7: DiagnosticEntry.severity must be built via the shared " +
                "`normalizeProviderSeverity(diag.severity)` helper in DiagnosticModels.kt. " +
                "Raw provider strings may drift from the canonical uppercase vocabulary.",
        )
    }

    @Test
    fun `isError kdoc documents 5-6 semantics on execute`() {
        // Task 5.6: `isError` semantics MUST be documented on `execute()` so
        // future maintainers don't invert the invariant by flipping to
        // isError=true when problems are found. Mirrors the kdoc discipline
        // pinned in RunInspectionsTool, ListQuickFixesTool, ProblemViewTool.
        val text = readSource("SemanticDiagnosticsTool.kt")
        assertTrue(
            text.contains("isError") && text.contains("5.6"),
            "Task 5.6: execute() must carry a kdoc block referencing the 5.6 " +
                "isError contract (legitimate results = isError=false; only " +
                "actual failures set isError=true). Ensures future maintainers " +
                "don't silently flip the invariant. Missing 5.6 reference in kdoc.",
        )
    }

    @Test
    fun `isError kdoc enumerates success and error sites`() {
        // T5 has MORE success taxonomy than T4 (no errors near changes,
        // no errors overall, unsupported language, N issues with scope).
        // The kdoc must enumerate both the success payloads AND the error
        // sites so the F6 invariant stays pinned.
        val text = readSource("SemanticDiagnosticsTool.kt")
        // At least two of these phrases must appear in the kdoc as markers of
        // enumerated taxonomy.
        val successMarkers = listOf(
            "No errors near",
            "unsupported",
            "intelligence not available",
            "No errors overall",
            "issue(s) in",
        )
        val successHit = successMarkers.count { text.contains(it, ignoreCase = true) }
        assertTrue(
            successHit >= 2,
            "Task 5.6: kdoc must enumerate the success taxonomy. Found only " +
                "$successHit of ${successMarkers.size} success markers. T5 has " +
                "more success branches than T4 because of the edit-range filter.",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Reads the canonical tool source file directly from the known module
     * layout. Mirrors [RunInspectionsToolTest.readSource],
     * [ListQuickFixesToolTest.readSource], and
     * [ProblemViewToolTest.readSource] — fails loudly if the layout changes
     * instead of silently matching a fixture.
     *
     * Extraction decision (Option A — copy-paste): T3/T4 already copy-pasted
     * this helper rather than extract to a shared DiagnosticToolTestSupport
     * utility. Keeping consistent with T3/T4 here — the duplication is
     * bounded (T2/T3/T4/T5), dies with Phase 7 when fingerprint tests are
     * deleted, and extraction would require migrating four existing test
     * classes with attendant change risk.
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
