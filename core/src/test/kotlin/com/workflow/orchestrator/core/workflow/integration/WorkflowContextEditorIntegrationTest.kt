package com.workflow.orchestrator.core.workflow.integration

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
 * No Git repo exists in the test fixture, so `activeBranch` will be null. We don't
 * assert anything about its value — only that the service handles the missing-VCS
 * case gracefully (per spec §9.3).
 *
 * This is the only :core test that uses BasePlatformTestCase (JUnit 4).
 * `junit-vintage-engine` is wired in `core/build.gradle.kts` for this file alone.
 */
class WorkflowContextEditorIntegrationTest : BasePlatformTestCase() {

    fun `test active editor change does not throw and state is observable`() {
        // Open a file in the test fixture.
        val file = myFixture.addFileToProject("src/Foo.kt", "class Foo")
        myFixture.openFileInEditor(file.virtualFile)

        // The service is project-scoped — first getInstance triggers construction
        // (which wires editor + VCS listeners and loads anchor from settings).
        val service = WorkflowContextService.getInstance(project)

        // Allow the async listener cascade to settle.
        // Tests run on EDT; the service's recompute uses cs.launch which dispatches
        // off-EDT. A short sleep is acceptable for a smoke test — we're not asserting
        // listener-driven values, just non-crash.
        Thread.sleep(500)

        assertNotNull("Service state must be observable", service.state.value)
    }
}
