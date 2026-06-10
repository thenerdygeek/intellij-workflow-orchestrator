package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text contract: `DelegationInboundStartupActivity.projectClosing` must never call
 * `runBlockingCancellable` on the EDT.
 *
 * The platform fires `projectClosing` on the EDT when the user closes the project WINDOW
 * (`CloseProjectWindowHelper.windowClosing`), and `runBlockingCancellable` asserts
 * background-thread (`assertBackgroundThreadAndNoWriteAction`) — the unguarded call threw
 * `IllegalStateException` on every window-close in the 2026-06-10 installed build, which
 * meant `closeAllForProjectClose()` NEVER ran and inbound channels were reaped without
 * their terminal `project_closed` Result.
 *
 * The contract pinned here: the handler branches on `isDispatchThread`, the EDT branch
 * bridges via `runWithModalProgressBlocking` (which pumps the event queue), and the
 * cancellable bridge survives only on the background branch. Headless tests cannot
 * exercise the real window-close EDT path, hence the source-text pin (same pattern as
 * `DialogModalityContractTest`).
 */
class DelegationProjectClosingEdtContractTest {

    private fun mainSourceRoot(): File {
        val d = System.getProperty("user.dir")
        return if (File("$d/src/main/kotlin").isDirectory) {
            File("$d/src/main/kotlin")
        } else {
            File("$d/agent/src/main/kotlin")
        }
    }

    private fun activitySource(): String {
        val file = File(
            mainSourceRoot(),
            "com/workflow/orchestrator/agent/delegation/DelegationInboundStartupActivity.kt",
        )
        assertTrue(file.isFile, "DelegationInboundStartupActivity.kt not found at ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `projectClosing branches on isDispatchThread before any blocking bridge`() {
        val src = activitySource()
        val closingBody = src.substringAfter("fun projectClosing")
        assertTrue(
            "isDispatchThread" in closingBody,
            "projectClosing must check ApplicationManager.getApplication().isDispatchThread — " +
                "runBlockingCancellable is forbidden on EDT (window-close fires there)",
        )
        val edtCheckIdx = closingBody.indexOf("isDispatchThread")
        val cancellableIdx = closingBody.indexOf("runBlockingCancellable")
        assertTrue(
            cancellableIdx > edtCheckIdx,
            "runBlockingCancellable must only appear AFTER the isDispatchThread branch " +
                "(i.e. on the background-thread path), never unguarded",
        )
    }

    @Test
    fun `EDT branch bridges via runWithModalProgressBlocking`() {
        val src = activitySource()
        assertTrue(
            "runWithModalProgressBlocking" in src,
            "the EDT branch must use runWithModalProgressBlocking (pumps the event queue) — " +
                "it is the platform-sanctioned EDT blocking bridge per the assertion message",
        )
    }
}
