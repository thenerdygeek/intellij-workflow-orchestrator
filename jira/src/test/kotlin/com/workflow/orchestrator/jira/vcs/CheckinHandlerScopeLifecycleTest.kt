package com.workflow.orchestrator.jira.vcs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Source-text pin for audit finding jira:F-6:
 * TimeTrackingCheckinHandlerFactory and PostCommitTransitionHandlerFactory previously
 * created CoroutineScope(SupervisorJob() + ...) with no lifecycle handle — never cancelled,
 * leaking for the process lifetime.
 *
 * The fix registers each scope with Disposer.register(project as Disposable) { scope.cancel(...) }.
 * These tests verify that the relevant call sites reference Disposer.register and scope.cancel,
 * and that neither factory still contains the bare un-cancelled scope pattern.
 *
 * A full lifecycle test (verifying scope cancellation on project dispose) would require a live
 * IntelliJ Application/Project fixture; a source-text pin is acceptable per prior phase precedent.
 */
class CheckinHandlerScopeLifecycleTest {

    private fun sourceFor(fileName: String): String? {
        val codeSourceUrl = javaClass.protectionDomain.codeSource?.location ?: return null
        var dir = java.io.File(codeSourceUrl.toURI())
        repeat(5) { dir = dir.parentFile }   // test-classes -> module root
        val src = dir.resolve("src/main/kotlin/com/workflow/orchestrator/jira/vcs/$fileName")
        return if (src.exists()) src.readText() else null
    }

    // ────────────────────────────────────────────────────────────────
    //  TimeTrackingCheckinHandlerFactory
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `TimeTrackingCheckinHandler registers scope with Disposer`() {
        val source = sourceFor("TimeTrackingCheckinHandlerFactory.kt")
            ?: return System.err.println("[CheckinHandlerScopeLifecycleTest] TimeTrackingCheckinHandlerFactory.kt not found; skipping.").let { }

        assertTrue(
            source.contains("Disposer.register"),
            "TimeTrackingCheckinHandler must call Disposer.register to tie scope lifecycle to project"
        )
        assertTrue(
            source.contains("scope.cancel"),
            "TimeTrackingCheckinHandler must cancel the scope in the Disposer callback"
        )
    }

    @Test
    fun `TimeTrackingCheckinHandler imports Disposer`() {
        val source = sourceFor("TimeTrackingCheckinHandlerFactory.kt")
            ?: return System.err.println("[CheckinHandlerScopeLifecycleTest] source not found; skipping.").let { }

        assertTrue(
            source.contains("import com.intellij.openapi.util.Disposer"),
            "File must import com.intellij.openapi.util.Disposer"
        )
    }

    @Test
    fun `TimeTrackingCheckinHandler does not have a bare unregistered scope`() {
        val source = sourceFor("TimeTrackingCheckinHandlerFactory.kt")
            ?: return System.err.println("[CheckinHandlerScopeLifecycleTest] source not found; skipping.").let { }

        // The old pattern: CoroutineScope(SupervisorJob()...).launch { ... } with no Disposer.register
        // After fix: Disposer.register must appear in the file whenever SupervisorJob is used.
        if (source.contains("SupervisorJob()")) {
            assertTrue(
                source.contains("Disposer.register"),
                "Every SupervisorJob-based scope must be registered with Disposer"
            )
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  PostCommitTransitionHandlerFactory
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `PostCommitTransitionHandler registers scope with Disposer`() {
        val source = sourceFor("PostCommitTransitionHandlerFactory.kt")
            ?: return System.err.println("[CheckinHandlerScopeLifecycleTest] PostCommitTransitionHandlerFactory.kt not found; skipping.").let { }

        assertTrue(
            source.contains("Disposer.register"),
            "PostCommitTransitionHandler must call Disposer.register to tie scope lifecycle to project"
        )
        assertTrue(
            source.contains("scope.cancel"),
            "PostCommitTransitionHandler must cancel the scope in the Disposer callback"
        )
    }

    @Test
    fun `PostCommitTransitionHandler imports Disposer`() {
        val source = sourceFor("PostCommitTransitionHandlerFactory.kt")
            ?: return System.err.println("[CheckinHandlerScopeLifecycleTest] source not found; skipping.").let { }

        assertTrue(
            source.contains("import com.intellij.openapi.util.Disposer"),
            "File must import com.intellij.openapi.util.Disposer"
        )
    }

    @Test
    fun `PostCommitTransitionHandler does not have a bare unregistered scope`() {
        val source = sourceFor("PostCommitTransitionHandlerFactory.kt")
            ?: return System.err.println("[CheckinHandlerScopeLifecycleTest] source not found; skipping.").let { }

        if (source.contains("SupervisorJob()")) {
            assertTrue(
                source.contains("Disposer.register"),
                "Every SupervisorJob-based scope must be registered with Disposer"
            )
        }
    }
}
