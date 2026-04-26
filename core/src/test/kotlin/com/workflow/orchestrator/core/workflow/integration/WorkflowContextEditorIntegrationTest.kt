package com.workflow.orchestrator.core.workflow.integration

import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import org.junit.Assert.assertNotNull

/**
 * Phase 5 T18 — integration smoke test for the editor listener wiring.
 *
 * Constructs a real Project via [BasePlatformTestCase], opens a file, and asserts that
 * [WorkflowContextService] constructs cleanly and observes the platform's
 * [com.intellij.openapi.fileEditor.FileEditorManagerListener] without NPEs.
 *
 * Wraps the body in [LoggedErrorProcessor.executeWith] — any error logged on any thread
 * (including the service's `cs.launch` cascade off-EDT) is rethrown and fails the test.
 * This makes the "doesn't NPE" claim enforced, not aspirational.
 *
 * No Git repo exists in the test fixture, so `activeBranch` will be null. We don't
 * assert anything about its value — only that the service handles the missing-VCS
 * case gracefully (per spec §9.3).
 *
 * This is the only :core test that uses BasePlatformTestCase (JUnit 4).
 * `junit-vintage-engine` is wired in `core/build.gradle.kts` for this file alone.
 */
class WorkflowContextEditorIntegrationTest : BasePlatformTestCase() {

    fun `test active editor change does not throw and state is observable`() {
        // Strict logged-error guard: any ERROR-level log (including from background
        // coroutines on `cs.launch`) propagates as a test failure.
        LoggedErrorProcessor.executeWith<Throwable>(
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    t: Throwable?,
                ): Set<Action> {
                    throw AssertionError("Unexpected logged error: $message", t)
                }
            },
        ) {
            // Open a file in the test fixture.
            val file = myFixture.addFileToProject("src/Foo.kt", "class Foo")
            myFixture.openFileInEditor(file.virtualFile)

            // The service is project-scoped — first getInstance triggers construction
            // (which wires editor + VCS listeners and loads anchor from settings).
            val service = WorkflowContextService.getInstance(project)

            // Allow the async listener cascade to settle.
            Thread.sleep(500)

            assertNotNull("Service state must be observable", service.state.value)
        }
    }
}
