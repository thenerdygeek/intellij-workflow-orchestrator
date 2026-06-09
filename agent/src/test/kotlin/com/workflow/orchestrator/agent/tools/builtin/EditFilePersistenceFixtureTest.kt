package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

/**
 * Data-loss bug #3 reproduction — `edit_file` silent-no-op false-success.
 *
 * Uses an in-memory [LightVirtualFile] + the platform's real Document/FileDocumentManager so the
 * test exercises the actual `writeViaDocument` code path WITHOUT a `LocalFileSystem` real-disk
 * file (which would drag in the project-indexing machinery that hangs `BasePlatformTestCase`).
 *
 * Runs OFF the EDT (`runInDispatchThread = false`) so `runBlocking` + the tool's
 * `withContext(Dispatchers.EDT)` / `WriteCommandAction` don't deadlock the EDT.
 *
 * The defect: `writeViaDocument` returns `true` even when `old_string` is absent from the Document
 * (the `if (offset >= 0)` guard silently skips the replace). `execute()` then treats the write as
 * a success — so a no-op edit is reported as done with the file unchanged (false success). The fix
 * makes `writeViaDocument` return `true` only when it actually replaced, so the `||` fallback chain
 * writes the validated content instead of silently no-op'ing.
 */
class EditFilePersistenceFixtureTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    private fun docText(vFile: LightVirtualFile): String =
        runReadAction { FileDocumentManager.getInstance().getDocument(vFile)?.text ?: "" }

    /**
     * REPRO (RED before fix) + control, in ONE test method.
     *
     * Single method on purpose: a *second* [BasePlatformTestCase] lifecycle in the same JVM hangs
     * on an "Indexing timeout" in this headless env, so we exercise both the no-op and the
     * happy-path replacement within one platform lifecycle.
     *
     * - No-op case: `old_string` is absent from the Document. Before the fix `writeViaDocument`
     *   returned `true` (the silent no-op → false-success data-loss). After the fix it returns
     *   `false`, so `execute()`'s `|| writeViaVfs || writeViaFileIo` chain persists the validated
     *   content instead of reporting a phantom success.
     * - Control: a real match replaces and reports `true`.
     */
    fun `test writeViaDocument reports failure on a no-op and success on a real replacement`() = runBlocking {
        // ── No-op: old_string absent → must NOT report success, must NOT mutate ─────────────
        val absent = LightVirtualFile("absent.md", "AAA\nBBB\nCCC\n")
        assertFalse(docText(absent).contains("TARGET"))

        val noOpReported = EditFileTool().writeViaDocument(
            vFile = absent,
            project = project,
            rawPath = "absent.md",
            oldString = "TARGET", // absent from the document
            newString = "X",
            replaceAll = false,
        )
        assertFalse(
            "writeViaDocument must return false when nothing was replaced (else edit_file " +
                "short-circuits the fallback writers and silently loses the edit)",
            noOpReported,
        )
        assertEquals("AAA\nBBB\nCCC\n", docText(absent))

        // ── Control: real match → replaces and reports success ──────────────────────────────
        val present = LightVirtualFile("present.md", "AAA\nTARGET\nCCC\n")
        val replaceReported = EditFileTool().writeViaDocument(
            vFile = present,
            project = project,
            rawPath = "present.md",
            oldString = "TARGET",
            newString = "REPLACED",
            replaceAll = false,
        )
        assertTrue("a real replacement must report success", replaceReported)
        assertEquals("AAA\nREPLACED\nCCC\n", docText(present))
    }
}
