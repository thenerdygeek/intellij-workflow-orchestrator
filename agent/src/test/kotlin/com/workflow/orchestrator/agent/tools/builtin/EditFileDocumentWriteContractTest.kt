package com.workflow.orchestrator.agent.tools.builtin

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text structural contract for [EditFileTool.writeViaDocument] — the editor/Document
 * write path that runs whenever the edited file is backed by a live IntelliJ `Document`
 * (i.e. essentially every text file in an open project).
 *
 * This path CANNOT be exercised by the MockK + `@TempDir` unit harness: with no live
 * `Application`, `findVirtualFile` returns null and `execute` falls through to the
 * `writeViaFileIo` java.io path (which writes to disk and is therefore correct). So the
 * Document path's two latent bugs are invisible to behavioural unit tests and are pinned
 * here as source-text assertions instead — the same approach used by [RefactorRenameToolTest]
 * ("Source-text structural tests pin the F4 fixes in the tool source") and SpillingWiringTest.
 *
 * The two bugs this guards against:
 *
 *  1. **No disk flush.** `Document.replaceString` mutates only IntelliJ's in-memory document.
 *     An external `git diff` (run via run_command) reads the DISK, so it sees nothing until
 *     a save trigger (Ctrl+S, frame deactivation, build-through-IDE) happens to fire. The
 *     write path must call `FileDocumentManager.saveDocument(...)` so the agent's own
 *     follow-up `git diff` is trustworthy.
 *
 *  2. **No undo registration.** The bare coroutine `writeAction { }` builder acquires only
 *     the write LOCK; it does NOT open a `CommandProcessor` command, so the IDE `UndoManager`
 *     never records the change and Ctrl+Z in the editor does nothing. The mutation must run
 *     inside `WriteCommandAction.runWriteCommandAction(...)` (a write action wrapped in a
 *     command) — the same undo-aware path every other write tool in the module uses
 *     (FormatCodeTool, OptimizeImportsTool, RefactorRenameTool, and the project Action files).
 */
class EditFileDocumentWriteContractTest {

    @Test
    fun `writeViaDocument flushes the document to disk`() {
        val body = writeViaDocumentBody()
        assertTrue(
            body.contains("saveDocument("),
            "writeViaDocument must call FileDocumentManager.saveDocument(...) so the in-memory " +
                "edit is flushed to disk — otherwise an external `git diff` sees no change until " +
                "the user presses Ctrl+S. Body was:\n$body"
        )
    }

    @Test
    fun `writeViaDocument registers the edit for undo via a command`() {
        val body = writeViaDocumentBody()
        assertTrue(
            body.contains("runWriteCommandAction"),
            "writeViaDocument must perform the document mutation inside " +
                "WriteCommandAction.runWriteCommandAction(...) so the IDE UndoManager records it " +
                "and Ctrl+Z works. A bare writeAction { } only takes the write lock and is NOT " +
                "undoable. Body was:\n$body"
        )
    }

    /**
     * Extracts just the `writeViaDocument` method body from the production source, so the
     * KDoc/documentation strings elsewhere in EditFileTool.kt that mention "WriteCommandAction"
     * cannot produce a false green.
     */
    private fun writeViaDocumentBody(): String {
        val source = locateEditFileToolSource().readText()
        val start = source.indexOf("private suspend fun writeViaDocument")
        assertTrue(start >= 0, "Could not find writeViaDocument in EditFileTool.kt")
        val end = source.indexOf("private suspend fun writeViaVfs", start)
        assertTrue(end > start, "Could not find writeViaVfs boundary after writeViaDocument")
        return source.substring(start, end)
    }

    /**
     * Resolves EditFileTool.kt across Gradle (cwd = worktree root), IntelliJ runner
     * (cwd = module dir), and worktree-parent execution contexts. Mirrors
     * SpillingWiringTest.locateSourceRoot().
     */
    private fun locateEditFileToolSource(): File {
        val rel = "com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt"
        val cwd = File(System.getProperty("user.dir"))
        listOf(
            File(cwd, "agent/src/main/kotlin/$rel"),
            File(cwd, "src/main/kotlin/$rel"),
            File(cwd.parentFile ?: cwd, "agent/src/main/kotlin/$rel"),
        ).forEach { if (it.isFile) return it }
        throw IllegalStateException("Cannot locate EditFileTool.kt from cwd=${cwd.absolutePath}")
    }
}
