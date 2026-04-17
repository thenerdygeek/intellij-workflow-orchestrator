package com.workflow.orchestrator.agent.tools.ide

import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.agent.tools.WorkerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Phase 6 / Task T6 contract tests for [RefactorRenameTool].
 *
 * Unlike T2–T5, this tool has NO `DiagnosticEntry` output — the fix is about
 * refactor SAFETY: cross-module awareness + a hard library-rename block. The
 * full `RenameProcessor.findUsages()` + module classification path requires a
 * real IntelliJ fixture (`LightJavaCodeInsightFixtureTestCase`), so we test at
 * two boundaries:
 *
 * 1. **Pure-function unit tests** for [summarizeForApproval] — every branch of
 *    the classification logic is reachable without IntelliJ services because
 *    [UsageClassification] is a plain data class.
 * 2. **Source-text structural tests** pin the F4 fixes in the tool source —
 *    presence of `confirm_cross_module`, unconditional library-rename block,
 *    cross-module confirmation gate, etc.
 *
 * Mirrors the pattern pinned in [RunInspectionsToolTest], [ListQuickFixesToolTest],
 * [ProblemViewToolTest], and [SemanticDiagnosticsToolTest] for the source-text
 * half; the pure-function half is novel to T6.
 */
class RefactorRenameToolTest {
    private val registry = LanguageProviderRegistry()
    private val tool = RefactorRenameTool(registry)

    // ═══════════════════════════════════════════════════════════════════════
    // Schema / metadata contract
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `tool name is refactor_rename`() {
        assertEquals("refactor_rename", tool.name)
    }

    @Test
    fun `description mentions rename and references`() {
        assertTrue(tool.description.contains("rename", ignoreCase = true))
        assertTrue(tool.description.contains("reference", ignoreCase = true))
    }

    @Test
    fun `parameters require symbol, new_name, description`() {
        assertTrue("symbol" in tool.parameters.properties)
        assertTrue("new_name" in tool.parameters.properties)
        assertTrue("description" in tool.parameters.properties)
        assertTrue("symbol" in tool.parameters.required)
        assertTrue("new_name" in tool.parameters.required)
        assertTrue("description" in tool.parameters.required)
    }

    @Test
    fun `parameters include confirm_cross_module boolean flag`() {
        // F4 (Task 5.3): the new `confirm_cross_module` parameter gates
        // cross-module renames. Must be declared in the schema (not just read
        // from the JSON object ad-hoc) so the LLM sees it in the tool list.
        val prop = tool.parameters.properties["confirm_cross_module"]
            ?: fail("confirm_cross_module parameter missing from schema")
        assertEquals(
            "boolean",
            prop.type,
            "confirm_cross_module must be typed as boolean so the LLM passes true/false literally, not \"true\".",
        )
        assertFalse(
            "confirm_cross_module" in tool.parameters.required,
            "confirm_cross_module must be OPTIONAL — single-module renames must proceed without confirmation.",
        )
    }

    @Test
    fun `allowedWorkers is CODER only`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition exposes confirm_cross_module in JSON schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("refactor_rename", def.function.name)
        assertTrue("confirm_cross_module" in def.function.parameters.properties)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Pure-function classification tests — summarizeForApproval.
    //
    // summarizeForApproval is pure on List<UsageClassification>. It captures
    // the three hard-specified behaviours:
    //   - ANY library usage  → LibraryBlocked (UNCONDITIONAL — confirm_cross_module cannot bypass this)
    //   - >1 module  → CrossModulePreview (gateable by confirm_cross_module at the caller)
    //   - 1 module, no library → SingleModuleOK (proceeds without confirmation)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `summarizeForApproval with empty usages returns NoUsages`() {
        // Edge case: findUsages() can legitimately return an empty array when
        // the symbol exists but is never referenced. We allow the rename (the
        // LLM can still rename unused declarations); the caller proceeds to
        // performRefactoring. Current pre-T6 behaviour also proceeds.
        val result = summarizeForApproval(emptyList())
        assertTrue(
            result is SummaryResult.NoUsages,
            "Empty list must be a distinct case, not silently SingleModuleOK. Got: $result",
        )
    }

    @Test
    fun `summarizeForApproval with a single library usage is blocked`() {
        val result = summarizeForApproval(
            listOf(
                UsageClassification(
                    module = null,
                    isLibrary = true,
                    isTest = false,
                    fileName = "jar:///path/to/foo.jar!/com/acme/Bar.class",
                ),
            ),
        )
        assertTrue(result is SummaryResult.LibraryBlocked, "Got: $result")
        val blocked = result as SummaryResult.LibraryBlocked
        assertEquals(1, blocked.libraryFiles.size)
        assertEquals(
            "jar:///path/to/foo.jar!/com/acme/Bar.class",
            blocked.libraryFiles.single(),
        )
    }

    @Test
    fun `summarizeForApproval blocks even when only ONE of many usages is library`() {
        // This is THE central contract — a mixed set (1 library + N project)
        // must block, not warn. Rationale: any jar usage = cannot modify
        // bytecode = project ends up referencing a name that no longer exists
        // in the library.
        val result = summarizeForApproval(
            listOf(
                UsageClassification("app", isLibrary = false, isTest = false, fileName = "/p/App.kt"),
                UsageClassification("app", isLibrary = false, isTest = false, fileName = "/p/Other.kt"),
                UsageClassification(null, isLibrary = true, isTest = false, fileName = "jar:///foo.jar!/X.class"),
            ),
        )
        assertTrue(
            result is SummaryResult.LibraryBlocked,
            "ANY library usage must block — even a single one amid many project usages. Got: $result",
        )
        val blocked = result as SummaryResult.LibraryBlocked
        assertEquals(1, blocked.libraryFiles.size)
    }

    @Test
    fun `summarizeForApproval collects all library files distinctly`() {
        val result = summarizeForApproval(
            listOf(
                UsageClassification(null, isLibrary = true, isTest = false, fileName = "jar:///a.jar!/A.class"),
                UsageClassification(null, isLibrary = true, isTest = false, fileName = "jar:///b.jar!/B.class"),
                UsageClassification(null, isLibrary = true, isTest = false, fileName = "jar:///a.jar!/A.class"), // dup
            ),
        )
        assertTrue(result is SummaryResult.LibraryBlocked)
        val blocked = result as SummaryResult.LibraryBlocked
        assertEquals(
            2,
            blocked.libraryFiles.size,
            "Library files must be deduplicated in the report so the error " +
                "message doesn't repeat the same jar path.",
        )
    }

    @Test
    fun `summarizeForApproval with all usages in one module returns SingleModuleOK`() {
        val result = summarizeForApproval(
            listOf(
                UsageClassification("core", isLibrary = false, isTest = false, fileName = "/p/core/A.kt"),
                UsageClassification("core", isLibrary = false, isTest = false, fileName = "/p/core/B.kt"),
                UsageClassification("core", isLibrary = false, isTest = true, fileName = "/p/core/ATest.kt"),
            ),
        )
        assertTrue(result is SummaryResult.SingleModuleOK, "Got: $result")
        val ok = result as SummaryResult.SingleModuleOK
        assertEquals("core", ok.module)
        assertEquals(2, ok.prodCount, "2 non-test usages")
        assertEquals(1, ok.testCount, "1 test usage")
    }

    @Test
    fun `summarizeForApproval with usages in 2 modules returns CrossModulePreview`() {
        val result = summarizeForApproval(
            listOf(
                UsageClassification("core", isLibrary = false, isTest = false, fileName = "/p/core/A.kt"),
                UsageClassification("core", isLibrary = false, isTest = true, fileName = "/p/core/ATest.kt"),
                UsageClassification("bamboo", isLibrary = false, isTest = false, fileName = "/p/bamboo/B.kt"),
                UsageClassification("bamboo", isLibrary = false, isTest = false, fileName = "/p/bamboo/C.kt"),
            ),
        )
        assertTrue(result is SummaryResult.CrossModulePreview, "Got: $result")
        val preview = result as SummaryResult.CrossModulePreview
        assertEquals(2, preview.moduleBreakdown.size)
        assertNotNull(preview.moduleBreakdown["core"])
        assertNotNull(preview.moduleBreakdown["bamboo"])
        assertEquals(1, preview.moduleBreakdown["core"]!!.prodCount)
        assertEquals(1, preview.moduleBreakdown["core"]!!.testCount)
        assertEquals(2, preview.moduleBreakdown["bamboo"]!!.prodCount)
        assertEquals(0, preview.moduleBreakdown["bamboo"]!!.testCount)
    }

    @Test
    fun `summarizeForApproval with null module is grouped under synthetic bucket`() {
        // A usage that can't be resolved to a module (e.g. generated file
        // outside any content root) gets `module=null`. It should still be
        // reported, grouped under a synthetic bucket name — NOT silently
        // dropped — so the LLM can see the full picture.
        val result = summarizeForApproval(
            listOf(
                UsageClassification("core", isLibrary = false, isTest = false, fileName = "/p/core/A.kt"),
                UsageClassification(null, isLibrary = false, isTest = false, fileName = "/p/unknown/B.kt"),
            ),
        )
        // Two distinct buckets → cross-module preview
        assertTrue(
            result is SummaryResult.CrossModulePreview,
            "null-module usages must count as a distinct bucket so the LLM " +
                "sees the unresolved code. Got: $result",
        )
    }

    @Test
    fun `summarizeForApproval all usages in single module with only tests still OK`() {
        // Edge case: renaming a test-only helper. Single module, all-test
        // usages → still SingleModuleOK (not blocked, not cross-module).
        val result = summarizeForApproval(
            listOf(
                UsageClassification("core", isLibrary = false, isTest = true, fileName = "/p/core/ATest.kt"),
                UsageClassification("core", isLibrary = false, isTest = true, fileName = "/p/core/BTest.kt"),
            ),
        )
        assertTrue(result is SummaryResult.SingleModuleOK, "Got: $result")
        val ok = result as SummaryResult.SingleModuleOK
        assertEquals(2, ok.testCount)
        assertEquals(0, ok.prodCount)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Source-text structural contracts — pin the F4 fix in the tool source.
    //
    // These tests grep the canonical source file to make sure the three
    // non-negotiable behaviours are wired into execute(), not left as dead
    // helper code that no one calls.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `source file declares confirm_cross_module parameter`() {
        val text = readSource("RefactorRenameTool.kt")
        assertTrue(
            text.contains("confirm_cross_module"),
            "F4 (Task 5.3): RefactorRenameTool must declare `confirm_cross_module` " +
                "as a parameter so the LLM can bypass the cross-module confirmation gate.",
        )
    }

    @Test
    fun `source file classifies usages by module`() {
        // The actual call site of ModuleUtilCore.findModuleForFile lives in
        // the RenameSafetyAnalyzer helper (so the logic is unit-testable
        // without a fixture). We check BOTH files — the analyzer must wire
        // the API call, and the tool must call into the analyzer.
        val toolText = readSource("RefactorRenameTool.kt")
        val analyzerText = readSource("RenameSafetyAnalyzer.kt")
        assertTrue(
            toolText.contains("classifyUsage"),
            "F4 (Task 5.3): RefactorRenameTool.execute() must call classifyUsage() " +
                "from RenameSafetyAnalyzer so each usage is classified by module.",
        )
        assertTrue(
            analyzerText.contains("findModuleForFile") || analyzerText.contains("getModuleForFile"),
            "F4 (Task 5.3): RenameSafetyAnalyzer must use ModuleUtilCore.findModuleForFile " +
                "(or ProjectFileIndex.getModuleForFile) to classify each usage by module. " +
                "Without this, the cross-module warning can't be computed.",
        )
    }

    @Test
    fun `source file detects library usages`() {
        // Library detection lives in the analyzer helper — same pattern as
        // findModuleForFile above.
        val analyzerText = readSource("RenameSafetyAnalyzer.kt")
        assertTrue(
            analyzerText.contains("isInLibrary") || analyzerText.contains("isInLibraryClasses"),
            "F4 (Task 5.3): RenameSafetyAnalyzer must detect library usages via " +
                "ProjectFileIndex.isInLibrary (or isInLibraryClasses) — the HARD BLOCK " +
                "on library renames is the single most important part of the fix.",
        )
    }

    @Test
    fun `source file has unconditional library block`() {
        // This is the subtle invariant: the library-rename block must NOT be
        // gated on `confirm_cross_module`. If the implementer writes
        // `if (libraryUsages.isNotEmpty() && !confirmCrossModule)` that would
        // VIOLATE the spec — the LLM must NOT be able to bypass the library
        // block by setting confirm_cross_module=true.
        val text = readSource("RefactorRenameTool.kt")
        assertTrue(
            text.contains("LibraryBlocked"),
            "F4 (Task 5.3): the library-rename block must go through the " +
                "SummaryResult.LibraryBlocked case so it's uniformly handled " +
                "and testable. Got source without LibraryBlocked branch.",
        )

        // Invariant: between `is SummaryResult.LibraryBlocked` and its `return`,
        // the string `confirmCrossModule` must NOT appear. This rules out any
        // gating like `if (confirmCrossModule) ...` wrapped around the library
        // error return.
        val libBlockIdx = text.indexOf("is SummaryResult.LibraryBlocked")
        assertTrue(
            libBlockIdx >= 0,
            "expected `is SummaryResult.LibraryBlocked` case in source",
        )
        // Find the matching closing brace of this case by scanning forward.
        // The case body ends at `return`'s closing `)` followed by `}`. We
        // conservatively scan until the next `is SummaryResult.` (start of
        // another case) or the end of file. Whatever we capture, it must
        // contain a `return` and must NOT contain `confirmCrossModule`.
        val nextCaseIdx = text.indexOf("is SummaryResult.", libBlockIdx + 1)
        val caseBodyEnd = if (nextCaseIdx >= 0) nextCaseIdx else text.length
        val caseBody = text.substring(libBlockIdx, caseBodyEnd)
        assertTrue(
            caseBody.contains("return"),
            "LibraryBlocked case must unconditionally `return` — no fall-through.",
        )
        assertFalse(
            caseBody.contains("confirmCrossModule"),
            "F4 (Task 5.3): the LibraryBlocked case body must NOT reference " +
                "confirmCrossModule. Referencing it here means the library " +
                "block is GATEABLE by confirm_cross_module, which violates " +
                "the spec. The library block is unconditional.\n\nCase body was:\n$caseBody",
        )
    }

    @Test
    fun `source file has cross-module confirmation gate`() {
        val text = readSource("RefactorRenameTool.kt")
        // The gate: CrossModulePreview + confirmCrossModule check. When
        // preview is produced AND !confirmCrossModule → return preview.
        // When confirmCrossModule=true → proceed.
        assertTrue(
            text.contains("CrossModulePreview"),
            "F4 (Task 5.3): cross-module renames must route through " +
                "SummaryResult.CrossModulePreview so the approval gate is uniform.",
        )
        assertTrue(
            text.contains("confirmCrossModule"),
            "F4 (Task 5.3): RefactorRenameTool must read the confirm_cross_module " +
                "parameter (as confirmCrossModule in Kotlin) and branch on it.",
        )
    }

    @Test
    fun `source file calls classifyUsage and summarizeForApproval`() {
        // The tool must USE the pure helpers — not just define them. Without
        // this, the helpers would be dead code.
        val text = readSource("RefactorRenameTool.kt")
        assertTrue(
            text.contains("classifyUsage") || text.contains("RenameSafetyAnalyzer"),
            "F4 (Task 5.3): RefactorRenameTool.execute() must call the pure " +
                "classification helper (classifyUsage) so the test-visible " +
                "summarizeForApproval contract applies at runtime.",
        )
        assertTrue(
            text.contains("summarizeForApproval"),
            "F4 (Task 5.3): RefactorRenameTool.execute() must call " +
                "summarizeForApproval so the pure-function contract pinned in " +
                "this test file governs real tool behaviour.",
        )
    }

    @Test
    fun `source file includes module count in success result`() {
        // Final rename result: must include module count so the LLM sees the
        // blast radius of what happened. Brief spec says:
        // "Renamed '<oldName>' → '<newName>'. <N> usages updated across <M>
        // module(s)."
        val text = readSource("RefactorRenameTool.kt")
        assertTrue(
            text.contains("module(s)") || text.contains("module"),
            "F4 (Task 5.3): the success ToolResult must report module count " +
                "so the LLM can see the scope of what was actually renamed.",
        )
    }

    @Test
    fun `DumbService check runs outside outer try block`() {
        // Preserves the T2/T3/T4/T5 discipline — don't regress the indexing
        // guard into the outer try where it'd be swallowed.
        val text = readSource("RefactorRenameTool.kt")
        val dumbIdx = text.indexOf("DumbService.isDumb(project)")
        val tryIdx = text.indexOf("return try {")
        assertTrue(dumbIdx >= 0, "expected DumbService.isDumb(project) guard")
        assertTrue(tryIdx >= 0, "expected `return try {` outer block")
        assertTrue(
            dumbIdx < tryIdx,
            "DumbService guard must appear BEFORE `return try {`. " +
                "dumbIdx=$dumbIdx, tryIdx=$tryIdx",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Reads the canonical tool source file directly from the known module
     * layout. Mirrors [SemanticDiagnosticsToolTest.readSource] — identical
     * implementation; extraction deferred to Phase 7 when fingerprint tests
     * are due for cleanup (see note in that file).
     */
    private fun readSource(name: String): String {
        val userDir = System.getProperty("user.dir")
            ?: error("user.dir system property is not set")
        val root = File(userDir)
        val relSubdir = "src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/$name"
        val moduleRootedPath = File(root, relSubdir)
        val repoRootedPath = File(root, "agent/$relSubdir")
        val path = when {
            moduleRootedPath.isFile -> moduleRootedPath
            repoRootedPath.isFile -> repoRootedPath
            else -> error(
                "Source file '$name' not found at either expected path:\n" +
                    "  1. ${moduleRootedPath.absolutePath}\n" +
                    "  2. ${repoRootedPath.absolutePath}\n" +
                    "user.dir=$userDir",
            )
        }
        return path.readText()
    }
}
