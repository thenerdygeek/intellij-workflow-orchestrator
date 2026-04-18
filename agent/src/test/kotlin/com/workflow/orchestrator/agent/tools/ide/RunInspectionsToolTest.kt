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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Phase 6 / Task T2 contract tests for [RunInspectionsTool].
 *
 * The full PSI-driven `execute()` path requires a running IDE (InspectionManager,
 * PsiManager, InspectionProjectProfileManager, DumbService, LocalFileSystem), so
 * we test at the contract boundary — parameter schema, worker allow-list, the
 * error-path `isError` semantics, and source-text structural invariants that
 * Phase 7's ToolOutputSpiller will key off (the `TODO(phase7)` handoff markers
 * and the `renderDiagnosticBody` call site).
 *
 * The source-text invariant tests follow the pattern used in
 * [com.workflow.orchestrator.agent.tools.runtime.RunInvocationLeakTest] — read
 * the canonical tool file from the known module layout and grep for
 * behavioural fingerprints.
 */
class RunInspectionsToolTest {
    private val tool = RunInspectionsTool()

    @AfterEach
    fun tearDown() {
        unmockkStatic(DumbService::class)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Schema / metadata contract
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `tool name is run_inspections`() {
        assertEquals("run_inspections", tool.name)
    }

    @Test
    fun `description mentions inspections`() {
        assertTrue(
            tool.description.contains("inspection", ignoreCase = true),
            "description should reference inspections: ${tool.description}",
        )
    }

    @Test
    fun `parameters include path and severity`() {
        val props = tool.parameters.properties
        assertTrue("path" in props)
        assertTrue("severity" in props)
    }

    @Test
    fun `path is required, severity is optional`() {
        assertTrue("path" in tool.parameters.required)
        assertFalse("severity" in tool.parameters.required)
    }

    @Test
    fun `severity has enum values ERROR WARNING INFO`() {
        val severityProp = tool.parameters.properties["severity"]!!
        assertNotNull(severityProp.enumValues)
        assertEquals(listOf("ERROR", "WARNING", "INFO"), severityProp.enumValues)
    }

    @Test
    fun `allowedWorkers includes CODER, REVIEWER, ANALYZER`() {
        assertEquals(
            setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER),
            tool.allowedWorkers,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Error-path isError semantics (F6 invariant).
    //
    // isError=true is reserved for tool-execution failures (missing path,
    // missing file, invalid PSI, DumbService blocked, exception). When the
    // tool successfully runs and emits problems, isError=false — problems
    // are the payload, not a failure signal.
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
    fun `execute with nonexistent file path returns error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/nonexistent-project-dir-12345"

        // DumbService.isDumb now runs OUTSIDE the outer try/catch (matches
        // the pattern in TopologyAction, ModuleDetailAction, etc.), so we
        // mock it explicitly to avoid a raw ClassCastException from the
        // unconfigured Application container in the mock fixture.
        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns false

        val result = tool.execute(buildJsonObject {
            put("path", "does/not/exist.kt")
        }, project)

        // Either the VFS lookup returns null ("File not found") or the
        // ReadAction.nonBlocking call itself throws (mock project has no
        // Application container for the read action path), which is caught
        // by the outer try and surfaced as "Error running inspections". Both
        // outcomes MUST set isError=true.
        assertTrue(
            result.isError,
            "nonexistent file must set isError=true: $result",
        )
        // Discriminating assertion: the error message must name the error
        // source — either the VFS "File not found" branch or the outer-try
        // "Error running inspections" fallback. A generic result without
        // either marker would indicate the error path is not firing.
        assertTrue(
            result.content.contains("File not found", ignoreCase = true) ||
                result.content.contains("Error running inspections", ignoreCase = true),
            "error content should identify the failure source " +
                "(expected 'File not found' or 'Error running inspections'): ${result.content}",
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
        val text = readSource("RunInspectionsTool.kt")
        val markers = Regex("""TODO\(phase7\)""").findAll(text).count()
        assertTrue(
            markers >= 1,
            "Phase 7 handoff contract: RunInspectionsTool must mark every " +
                "truncation / cap site with `TODO(phase7)` so Phase 7's executor " +
                "can grep for them. Found $markers marker(s).",
        )
    }

    @Test
    fun `source file returns DiagnosticEntry via renderDiagnosticBody`() {
        val text = readSource("RunInspectionsTool.kt")
        assertTrue(
            text.contains("renderDiagnosticBody"),
            "F1: RunInspectionsTool must wrap its prose output with " +
                "`renderDiagnosticBody(prose, entries)` so the full per-item list is " +
                "attached structurally for Phase 7 to route to disk.",
        )
        assertTrue(
            text.contains("DiagnosticEntry"),
            "F1: RunInspectionsTool must build a list of `DiagnosticEntry` " +
                "objects (one per ProblemInfo) — not just prose.",
        )

        // Phase 7: MAX_PROBLEMS has been replaced by PREVIEW_ENTRIES + spillOrFormat.
        // The old constant must be gone and the new constant must be present.
        assertFalse(
            text.contains("MAX_PROBLEMS"),
            "Phase 7: MAX_PROBLEMS hard-cap constant must be removed — replaced by " +
                "PREVIEW_ENTRIES (for the prose head-preview) + spillOrFormat (for disk spill).",
        )
        assertTrue(
            text.contains("PREVIEW_ENTRIES"),
            "Phase 7: PREVIEW_ENTRIES constant must be present — it drives the head-preview " +
                "entry count for the inline prose, replacing the removed MAX_PROBLEMS cap.",
        )
        assertTrue(
            text.contains("spillOrFormat"),
            "Phase 7: spillOrFormat must be called to route the full JSON body to disk " +
                "when it exceeds the 30K threshold.",
        )
    }

    /**
     * F5 contract: when the tool successfully moves to
     * `LocalInspectionToolWrapper.processFile(...)`, the deprecated manual
     * `PsiRecursiveElementWalkingVisitor` walk should no longer appear.
     *
     * DISABLED because Phase 6 falls back to the existing manual walk — the
     * `LocalInspectionToolWrapper.processFile(file, session)` overload proposed
     * by the phase 6 plan is not exposed on the public platform API surface we
     * depend against (see the F5 TODO(phase7) note in RunInspectionsTool.kt).
     * Re-enable once Phase 7 (or a follow-up spike) validates the platform
     * API shape.
     */
    @Test
    @Disabled(
        "F5 deferred: LocalInspectionToolWrapper.processFile is not in the public API " +
            "(see docs/superpowers/research/2026-03-20-intellij-api-signatures.md §4). " +
            "Re-enable when upstream exposes a public wrapper, or migrate to whatever " +
            "succeeds it. See TODO(phase7) in RunInspectionsTool.kt."
    )
    fun `source file does not use deprecated buildVisitor walk`() {
        val text = readSource("RunInspectionsTool.kt")
        assertFalse(
            text.contains("PsiRecursiveElementWalkingVisitor"),
            "F5: `PsiRecursiveElementWalkingVisitor` is the deprecated manual-walk " +
                "fingerprint. Replace with `LocalInspectionToolWrapper.processFile(...)` " +
                "or an equivalent non-deprecated wrapper API.",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Reads the canonical tool source file directly from the known module
     * layout. Mirrors `RunInvocationLeakTest.readSource()` — fails loudly if
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
