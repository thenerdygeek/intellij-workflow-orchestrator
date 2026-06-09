package com.workflow.orchestrator.agent.tools.builtin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-text safety pins for the F4 fix: EditFileTool, CreateFileTool, DeleteFileTool no longer
 * use the blocking invokeAndWaitIfNeeded EDT bridge; they use the suspend writeAction API instead.
 *
 * **Why blocking was wrong:** invokeAndWaitIfNeeded { WriteCommandAction { } } blocks the calling
 * thread (typically an IO coroutine thread) until the EDT slice completes. AgentLoop's
 * withTimeoutOrNull(120s) cannot interrupt a blocked thread — only a suspended coroutine can be
 * cancelled. A slow VFS write or stuck WriteCommandAction would freeze the agent loop indefinitely.
 *
 * **Why writeAction is correct:** writeAction (com.intellij.openapi.application) is a suspend
 * function that suspends the coroutine, switches to EDT, acquires the write lock, runs the block,
 * returns the result, and respects coroutine cancellation throughout. Same write-lock semantics
 * as WriteCommandAction.runWriteCommandAction but coroutine-friendly.
 *
 * Full integration tests (cancel-in-flight, EDT-switch verification) require a real IntelliJ
 * Application instance. These source-text pins are the unit-testable proxy for that invariant,
 * following the established RunInvocationLeakTest pattern.
 */
class WriteToolsSuspendApiTest {

    private fun readKtSource(relPath: String): String {
        val candidates = listOf(
            relPath,
            "../$relPath",
            "../../$relPath",
        )
        for (path in candidates) {
            val f = java.io.File(path)
            if (f.exists()) return f.readText()
        }
        error("Cannot locate $relPath from working directory '${java.io.File(".").absolutePath}'")
    }

    private val editFileSource by lazy {
        readKtSource("agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt")
    }
    private val createFileSource by lazy {
        readKtSource("agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt")
    }
    private val deleteFileSource by lazy {
        readKtSource("agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/DeleteFileTool.kt")
    }

    // ── EditFileTool ─────────────────────────────────────────────────────────────

    @Test
    fun `EditFileTool does not import invokeAndWaitIfNeeded`() {
        assertFalse(
            editFileSource.contains("import com.intellij.openapi.application.invokeAndWaitIfNeeded"),
            "EditFileTool must not import invokeAndWaitIfNeeded — use writeAction (F-1)"
        )
    }

    @Test
    fun `EditFileTool imports writeAction`() {
        assertTrue(
            editFileSource.contains("import com.intellij.openapi.application.writeAction"),
            "EditFileTool must import writeAction from com.intellij.openapi.application — F-1"
        )
    }

    @Test
    fun `EditFileTool writeViaDocument is suspend`() {
        assertTrue(
            // `internal` (not `private`) so it's drivable by EditFilePersistenceFixtureTest (bug #3).
            editFileSource.contains("internal suspend fun writeViaDocument"),
            "EditFileTool.writeViaDocument must be suspend so writeAction { } can be called — F-1"
        )
    }

    @Test
    fun `EditFileTool writeViaVfs is suspend`() {
        assertTrue(
            editFileSource.contains("private suspend fun writeViaVfs"),
            "EditFileTool.writeViaVfs must be suspend so writeAction { } can be called — F-1"
        )
    }

    @Test
    fun `EditFileTool uses writeAction not invokeAndWaitIfNeeded in write paths`() {
        // Count production code usage (not comments or doc strings)
        val codeLines = editFileSource.lines().filter { line ->
            !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")
        }
        val codeText = codeLines.joinToString("\n")
        assertTrue(
            codeText.contains("writeAction {"),
            "EditFileTool must call writeAction { } in the write paths — F-1"
        )
        assertFalse(
            codeText.contains("invokeAndWaitIfNeeded"),
            "EditFileTool must not contain invokeAndWaitIfNeeded in production code — F-1"
        )
    }

    // ── CreateFileTool ───────────────────────────────────────────────────────────

    @Test
    fun `CreateFileTool does not import invokeAndWaitIfNeeded`() {
        assertFalse(
            createFileSource.contains("import com.intellij.openapi.application.invokeAndWaitIfNeeded"),
            "CreateFileTool must not import invokeAndWaitIfNeeded — use writeAction (F-1)"
        )
    }

    @Test
    fun `CreateFileTool imports writeAction`() {
        assertTrue(
            createFileSource.contains("import com.intellij.openapi.application.writeAction"),
            "CreateFileTool must import writeAction from com.intellij.openapi.application — F-1"
        )
    }

    @Test
    fun `CreateFileTool writeViaVfs is suspend`() {
        assertTrue(
            createFileSource.contains("private suspend fun writeViaVfs"),
            "CreateFileTool.writeViaVfs must be suspend so writeAction { } can be called — F-1"
        )
    }

    @Test
    fun `CreateFileTool uses writeAction not invokeAndWaitIfNeeded in write paths`() {
        val codeLines = createFileSource.lines().filter { line ->
            !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")
        }
        val codeText = codeLines.joinToString("\n")
        assertTrue(
            codeText.contains("writeAction {"),
            "CreateFileTool must call writeAction { } in the write path — F-1"
        )
        assertFalse(
            codeText.contains("invokeAndWaitIfNeeded"),
            "CreateFileTool must not contain invokeAndWaitIfNeeded in production code — F-1"
        )
    }

    // ── DeleteFileTool ───────────────────────────────────────────────────────────

    @Test
    fun `DeleteFileTool does not import invokeAndWaitIfNeeded`() {
        assertFalse(
            deleteFileSource.contains("import com.intellij.openapi.application.invokeAndWaitIfNeeded"),
            "DeleteFileTool must not import invokeAndWaitIfNeeded — use writeAction (F-1)"
        )
    }

    @Test
    fun `DeleteFileTool imports writeAction`() {
        assertTrue(
            deleteFileSource.contains("import com.intellij.openapi.application.writeAction"),
            "DeleteFileTool must import writeAction from com.intellij.openapi.application — F-1"
        )
    }

    @Test
    fun `DeleteFileTool deleteViaVfs is suspend`() {
        assertTrue(
            deleteFileSource.contains("private suspend fun deleteViaVfs"),
            "DeleteFileTool.deleteViaVfs must be suspend so writeAction { } can be called — F-1"
        )
    }

    @Test
    fun `DeleteFileTool uses writeAction not invokeAndWaitIfNeeded in delete path`() {
        val codeLines = deleteFileSource.lines().filter { line ->
            !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")
        }
        val codeText = codeLines.joinToString("\n")
        assertTrue(
            codeText.contains("writeAction {"),
            "DeleteFileTool must call writeAction { } in the delete path — F-1"
        )
        assertFalse(
            codeText.contains("invokeAndWaitIfNeeded"),
            "DeleteFileTool must not contain invokeAndWaitIfNeeded in production code — F-1"
        )
    }
}
