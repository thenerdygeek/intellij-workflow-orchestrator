package com.workflow.orchestrator.agent.tools.ide

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text structural contract for the Document/PSI-mutating IDE tools: they run inside
 * `WriteCommandAction` (so the edit is undoable) but historically never flushed the in-memory
 * document to disk, so an external `git diff` (run via run_command) saw stale bytes until the
 * next save trigger (Ctrl+S / frame deactivation / build-through-IDE). This is the same
 * disk-flush gap fixed in EditFileTool — see [EditFileDocumentWriteContractTest] and the
 * project memory note.
 *
 * Like RefactorRenameToolTest's own "source-text structural tests pin the F4 fixes in the tool
 * source", these contracts can't be exercised headlessly (the write paths need a live VFS +
 * Document the MockK harness lacks), so they're pinned as source assertions.
 *
 *  - `format_code` / `optimize_imports` operate on a single file → must call
 *    `FileDocumentManager.saveDocument(...)`.
 *  - `refactor_rename` rewrites usages across many files via `RenameProcessor` → must call
 *    `FileDocumentManager.saveAllDocuments()` so every touched file lands on disk.
 */
class DocumentWriteFlushContractTest {

    @Test
    fun `format_code flushes the document to disk`() {
        val src = locateSource("ide/FormatCodeTool.kt")
        assertTrue(
            src.contains("saveDocument("),
            "FormatCodeTool must call FileDocumentManager.saveDocument(...) after reformatting so " +
                "the in-memory change is flushed to disk and external git/build tooling sees it."
        )
    }

    @Test
    fun `optimize_imports flushes the document to disk`() {
        val src = locateSource("ide/OptimizeImportsTool.kt")
        assertTrue(
            src.contains("saveDocument("),
            "OptimizeImportsTool must call FileDocumentManager.saveDocument(...) after optimizing " +
                "imports so the in-memory change is flushed to disk and external tooling sees it."
        )
    }

    @Test
    fun `refactor_rename flushes all touched documents to disk`() {
        val src = locateSource("ide/RefactorRenameTool.kt")
        assertTrue(
            src.contains("saveAllDocuments("),
            "RefactorRenameTool must call FileDocumentManager.saveAllDocuments() after the rename " +
                "refactoring — rename rewrites usages across multiple files, so a single-document " +
                "save would leave the other touched files stale on disk."
        )
    }

    /** Resolves a tool source file across Gradle / IntelliJ-runner / worktree-parent cwds. */
    private fun locateSource(relPath: String): String {
        val rel = "com/workflow/orchestrator/agent/tools/$relPath"
        val cwd = File(System.getProperty("user.dir"))
        listOf(
            File(cwd, "agent/src/main/kotlin/$rel"),
            File(cwd, "src/main/kotlin/$rel"),
            File(cwd.parentFile ?: cwd, "agent/src/main/kotlin/$rel"),
        ).forEach { if (it.isFile) return it.readText() }
        throw IllegalStateException("Cannot locate $relPath from cwd=${cwd.absolutePath}")
    }
}
