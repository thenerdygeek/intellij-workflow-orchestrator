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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Phase 6 / Task T4 contract tests for [ProblemViewTool].
 *
 * The full `execute()` path requires a running IDE (FileEditorManager,
 * WolfTheProblemSolver, DocumentMarkupModel, DumbService, LocalFileSystem), so
 * we test at the contract boundary — parameter schema, worker allow-list, the
 * error-path `isError` semantics, and source-text structural invariants that
 * Phase 7's ToolOutputSpiller will key off (the `TODO(phase7)` handoff markers,
 * the `renderDiagnosticBody` call site, and the "entries built BEFORE cap"
 * ordering contract).
 *
 * The source-text invariant tests follow the pattern used in
 * [RunInspectionsToolTest] and [ListQuickFixesToolTest] — read the canonical
 * tool file from the known module layout and grep for behavioural fingerprints.
 */
class ProblemViewToolTest {
    private val tool = ProblemViewTool()

    @AfterEach
    fun tearDown() {
        unmockkStatic(DumbService::class)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Schema / metadata contract (preserved from prior test)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `tool name is problem_view`() {
        assertEquals("problem_view", tool.name)
    }

    @Test
    fun `description mentions errors and warnings`() {
        assertTrue(tool.description.contains("errors"))
        assertTrue(tool.description.contains("warnings"))
    }

    @Test
    fun `description warns about editor-opened files`() {
        assertTrue(tool.description.contains("opened in the editor"))
    }

    @Test
    fun `parameters include file and severity`() {
        val props = tool.parameters.properties
        assertTrue("file" in props)
        assertTrue("severity" in props)
    }

    @Test
    fun `file parameter is optional`() {
        assertFalse("file" in tool.parameters.required)
    }

    @Test
    fun `severity parameter is optional`() {
        assertFalse("severity" in tool.parameters.required)
    }

    @Test
    fun `severity parameter has enum values`() {
        val severityProp = tool.parameters.properties["severity"]!!
        assertNotNull(severityProp.enumValues)
        assertEquals(listOf("error", "warning", "all"), severityProp.enumValues)
    }

    @Test
    fun `no required parameters`() {
        assertTrue(tool.parameters.required.isEmpty())
    }

    @Test
    fun `allowedWorkers includes ANALYZER, CODER, REVIEWER`() {
        assertEquals(
            setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("problem_view", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertEquals(2, def.function.parameters.properties.size)
        assertTrue(def.function.parameters.required.isEmpty())
    }

    @Test
    fun `file parameter description mentions listing all files`() {
        val desc = tool.parameters.properties["file"]!!.description
        assertTrue(desc.contains("Lists all problem files if omitted"))
    }

    @Test
    fun `severity parameter default is documented in description`() {
        val desc = tool.parameters.properties["severity"]!!.description
        assertTrue(desc.contains("all"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Error-path isError semantics (F6 / Task 5.6 invariant).
    //
    // isError=true is reserved for tool-execution failures (invalid severity,
    // bad file path, DumbService blocked, uncaught exception). When the tool
    // successfully runs and emits problems (or legitimately reports zero
    // problems, or "no files open"), isError=false — the problem list IS the
    // payload, not a failure signal.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `execute with invalid severity returns error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        val result = tool.execute(buildJsonObject {
            put("severity", "critical")
        }, project)

        assertTrue(result.isError, "invalid severity must set isError=true: $result")
        assertTrue(result.content.contains("severity"))
    }

    @Test
    fun `execute with file param handles missing project base path`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns null

        // DumbService guard now runs OUTSIDE the outer try — must mock to avoid
        // raw ClassCastException from the unconfigured Application container.
        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns false

        val result = tool.execute(buildJsonObject {
            put("file", "src/Main.kt")
        }, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("project base path"))
    }

    @Test
    fun `execute with nonexistent file returns error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/nonexistent-project-dir-12345"

        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns false

        val result = tool.execute(buildJsonObject {
            put("file", "nonexistent/File.kt")
        }, project)

        assertTrue(result.isError)
    }

    @Test
    fun `execute without file param handles missing FileEditorManager gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns false

        val result = tool.execute(buildJsonObject {}, project)

        // Without a running IDE, FileEditorManager.getInstance() will throw
        assertTrue(result.isError)
        assertTrue(result.content.contains("Error"))
    }

    @Test
    fun `execute with path traversal returns error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns false

        val result = tool.execute(buildJsonObject {
            put("file", "../../etc/passwd")
        }, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("outside the project"))
    }

    @Test
    fun `execute when DumbService is indexing returns isError with indexing message`() = runTest {
        // F3 / Task 5.2: DumbService guard must short-circuit BEFORE attempting
        // to touch WolfTheProblemSolver or DocumentMarkupModel, whose caches
        // may be stale during indexing.
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns true

        val result = tool.execute(buildJsonObject {
            put("file", "src/Main.kt")
        }, project)

        assertTrue(
            result.isError,
            "DumbService.isDumb=true must set isError=true so the LLM treats this " +
                "as a transient failure, not a successful zero-problems result. Got: $result",
        )
        assertTrue(
            result.content.contains("Indexing", ignoreCase = true),
            "DumbService guard must surface an indexing-specific message so the " +
                "LLM can retry after indexing completes. Got: ${result.content}",
        )
    }

    @Test
    fun `execute when DumbService is indexing short-circuits before file validation`() = runTest {
        // Discriminating assertion: when DumbService=true, the guard must fire
        // BEFORE the path validator so the LLM sees a consistent indexing
        // message regardless of whether the path is bogus. If the guard were
        // placed inside the outer try, the path validator or file-lookup might
        // win first and the LLM would see a confusing "File not found" during
        // indexing instead.
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/nonexistent-project-dir-12345"

        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns true

        val result = tool.execute(buildJsonObject {
            put("file", "does/not/exist.kt")
        }, project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("Indexing", ignoreCase = true),
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
        val text = readSource("ProblemViewTool.kt")
        val markers = Regex("""TODO\(phase7\)""").findAll(text).count()
        assertTrue(
            markers >= 1,
            "Phase 7 handoff contract: ProblemViewTool must mark every " +
                "truncation / cap site with `TODO(phase7)` so Phase 7's executor " +
                "can grep for them. Found $markers marker(s).",
        )
    }

    @Test
    fun `source file returns DiagnosticEntry via renderDiagnosticBody`() {
        val text = readSource("ProblemViewTool.kt")
        assertTrue(
            text.contains("renderDiagnosticBody"),
            "F1: ProblemViewTool must wrap its prose output with " +
                "`renderDiagnosticBody(prose, entries)` so the full per-item list is " +
                "attached structurally for Phase 7 to route to disk.",
        )
        assertTrue(
            text.contains("DiagnosticEntry"),
            "F1: ProblemViewTool must build a list of `DiagnosticEntry` " +
                "objects (one per HighlightInfo) — not just prose.",
        )
    }

    @Test
    fun `source file uses absolute VirtualFile path for DiagnosticEntry file`() {
        // DiagnosticEntry.file kdoc contract (pinned in commit 52b0d867): the
        // file field MUST be the absolute VirtualFile.path, never a
        // project-relative value or presentableUrl. The previous code used
        // `vf.path.removePrefix(project.basePath!!)` which is a RELATIVE
        // path — violates the cross-tool link-back contract.
        val text = readSource("ProblemViewTool.kt")
        assertTrue(
            text.contains("file = vf.path"),
            "DiagnosticEntry.file must be populated from `vf.path` (absolute " +
                "VirtualFile path) — see the kdoc on DiagnosticEntry.file " +
                "(committed in 52b0d867). Cross-tool link-back for Phase 7 " +
                "requires all diagnostic tools to agree on this representation. " +
                "The prior code used a project-relative path, violating the contract.",
        )
        assertFalse(
            text.contains("presentableUrl"),
            "DiagnosticEntry.file must NOT use VirtualFile.presentableUrl — " +
                "it uses a different path shape and breaks Phase 7 link-back.",
        )
    }

    @Test
    fun `source file builds entries before applying the 30-item preview cap`() {
        // F1 ordering contract (matches T2 RunInspectionsTool, T3
        // ListQuickFixesTool): the full DiagnosticEntry list must be built
        // from EVERY collected problem BEFORE `.take(30)` is applied. The
        // previous code applied `.take(30)` inside `collectHighlightProblems`,
        // which meant the returned list was already capped — entries past 30
        // were irretrievably LOST. Phase 7's spiller reads the entries off
        // the ToolResult and must see the lossless list, not the capped
        // preview.
        val text = readSource("ProblemViewTool.kt")
        val entriesIdx = text.indexOf("DiagnosticEntry(")
        assertTrue(entriesIdx >= 0, "expected a DiagnosticEntry(...) constructor call")

        // There must be at least one `.take(` cap site, and the DiagnosticEntry
        // construction must come BEFORE every `.take(...)` that caps the list
        // routed to the prose preview.
        val takeIdx = text.indexOf(".take(")
        assertTrue(takeIdx >= 0, "expected a .take(...) call for the preview cap")
        assertTrue(
            entriesIdx < takeIdx,
            "F1 ordering: DiagnosticEntry construction must precede .take(...) " +
                "so the structured list is built from all problems, not the capped " +
                "preview. entriesIdx=$entriesIdx, takeIdx=$takeIdx",
        )
    }

    @Test
    fun `DumbService check runs outside outer try block`() {
        // F3 / T2 / T3 discipline: the DumbService guard must be a top-level
        // early-return BEFORE the outer `try { ... }` — matches
        // RunInspectionsTool lines 70-72 and ListQuickFixesTool lines 77-79.
        // Regressing this would wrap the guard inside a catch and swallow
        // indexing signals.
        val text = readSource("ProblemViewTool.kt")
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

    @Test
    fun `source file emits ERROR and WARNING severities in canonical uppercase vocabulary`() {
        // HighlightSeverity normalization contract (Task 5.7 severity pinning):
        // ProblemViewTool uses `com.intellij.lang.annotation.HighlightSeverity`
        // (NOT `ProblemHighlightType`), so it cannot call the shared
        // `normalizeSeverity(ProblemHighlightType)` in DiagnosticModels.kt.
        // The current behaviour is to map `HighlightSeverity.ERROR → "ERROR"`
        // and `HighlightSeverity.WARNING → "WARNING"` (SUBSET of the shared
        // 3-value vocabulary — INFO and below are dropped via `continue`).
        // Pin the invariant so any future refactor that collapses these or
        // swaps them for raw `HighlightSeverity.name` (which can be
        // "ERROR"/"WARNING"/"WEAK WARNING" / "INFORMATION" / "TYPO" …) is
        // surfaced by the test suite.
        val text = readSource("ProblemViewTool.kt")
        assertTrue(
            text.contains("\"ERROR\""),
            "ProblemViewTool must emit 'ERROR' (uppercase string) for " +
                "HighlightSeverity.ERROR severity. Found no ERROR literal.",
        )
        assertTrue(
            text.contains("\"WARNING\""),
            "ProblemViewTool must emit 'WARNING' (uppercase string) for " +
                "HighlightSeverity.WARNING severity. Found no WARNING literal.",
        )
    }

    @Test
    fun `source file applies severity filter to per-file and all-open-files modes`() {
        // Gotcha lock: both `getProblemsForFile` and `getProblemsForAllOpenFiles`
        // must funnel through the same `collectHighlightProblems(...)` helper
        // so the severity filter is applied identically in both paths. If a
        // future refactor splits them, double-cap or inconsistent filter
        // behaviour can regress silently.
        val text = readSource("ProblemViewTool.kt")
        val perFileIdx = text.indexOf("getProblemsForFile(")
        val allFilesIdx = text.indexOf("getProblemsForAllOpenFiles(")
        val collectIdx = text.indexOf("collectHighlightProblems(")
        assertTrue(perFileIdx >= 0, "expected getProblemsForFile(...) function")
        assertTrue(allFilesIdx >= 0, "expected getProblemsForAllOpenFiles(...) function")
        assertTrue(collectIdx >= 0, "expected collectHighlightProblems(...) helper")

        // Both call sites must reference the shared helper — count invocations.
        val callCount = Regex("""collectHighlightProblems\(""").findAll(text).count()
        assertTrue(
            callCount >= 2,
            "Expected at least 2 call sites of `collectHighlightProblems(...)` " +
                "(one in getProblemsForFile, one in getProblemsForAllOpenFiles). " +
                "Found $callCount. Refactoring that drops one path risks inconsistent " +
                "severity filter behaviour between per-file and all-open-files modes.",
        )
    }

    @Test
    fun `isError kdoc documents F6 semantics on execute()`() {
        // Task 5.6: `isError` semantics MUST be documented on `execute()` so
        // future maintainers don't invert the invariant by flipping to
        // isError=true when problems are found. Mirrors the kdoc discipline
        // pinned in RunInspectionsTool and ListQuickFixesTool.
        val text = readSource("ProblemViewTool.kt")
        assertTrue(
            text.contains("isError") && text.contains("5.6"),
            "Task 5.6: execute() must carry a kdoc block referencing the 5.6 " +
                "isError contract (legitimate results = isError=false; only " +
                "actual failures set isError=true). Ensures future maintainers " +
                "don't silently flip the invariant. Missing 5.6 reference in kdoc.",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Reads the canonical tool source file directly from the known module
     * layout. Mirrors `RunInspectionsToolTest.readSource()` and
     * `ListQuickFixesToolTest.readSource()` — fails loudly if the layout
     * changes instead of silently matching a fixture.
     *
     * Extraction decision (Option A — copy-paste): T3 already copy-pasted
     * this helper rather than extract to a shared DiagnosticToolTestSupport
     * utility. Keeping consistent with T3 here — the duplication is bounded
     * (T2/T3/T4/T5), dies with Phase 7 when fingerprint tests are deleted,
     * and extraction would require migrating three existing test classes
     * with attendant change risk.
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
