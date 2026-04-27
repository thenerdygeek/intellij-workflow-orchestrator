package com.workflow.orchestrator.agent.prompt

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.RepoRef
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase 5 T17 — verifies the `<workflow_context>` block is injected into
 * `EnvironmentDetailsBuilder.build()` output when WorkflowContextService state
 * is non-empty, and omitted when state is empty. Spec §6.1.
 */
class EnvironmentDetailsBuilderWorkflowContextTest {

    @BeforeEach
    fun setUp() {
        installReadActionInlineShim()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `workflow_context block included when state is non-empty`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val service = mockk<WorkflowContextService>()
        every { service.state } returns MutableStateFlow(
            WorkflowContext(
                activeTicket = TicketRef("AFTER8TE-912", "Fix login"),
                activeBranch = "feat/login-fix",
                activeRepo = RepoRef("repo", "P", "repo", "/p/repo"),
                focusPr = PrRef(42, "feat/login-fix", "main", "repo", null, null),
            )
        )
        every { project.getService(WorkflowContextService::class.java) } returns service

        val out = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null,
        )
        assertTrue(out.contains("<workflow_context>"), "block opening tag must appear")
        assertTrue(out.contains("</workflow_context>"), "block closing tag must appear")
        assertTrue(out.contains("Active ticket: AFTER8TE-912"), "active ticket key must appear")
        assertTrue(out.contains("Active branch: feat/login-fix"), "active branch must appear")
        assertTrue(out.contains("Focused PR: #42"), "focused PR id must appear")
        // activeBranch == focusPr.fromBranch AND activeRepo.name == focusPr.repoName → InteractionMode.Live
        assertTrue(out.contains("Interaction mode: Live"), "interaction mode must reflect derived value")
    }

    @Test
    fun `workflow_context block omitted when state is empty`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val service = mockk<WorkflowContextService>()
        every { service.state } returns MutableStateFlow(WorkflowContext())
        every { project.getService(WorkflowContextService::class.java) } returns service

        val out = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null,
        )
        assertFalse(out.contains("<workflow_context>"), "block must not appear when state is empty")
    }

    @Test
    fun `read-only interaction mode rendered when active branch differs from PR source branch`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val service = mockk<WorkflowContextService>()
        every { service.state } returns MutableStateFlow(
            WorkflowContext(
                activeTicket = TicketRef("PROJ-1", "Investigate"),
                activeBranch = "feat/other-branch",
                focusPr = PrRef(7, "feat/login-fix", "main", "repo", null, null),
            )
        )
        every { project.getService(WorkflowContextService::class.java) } returns service

        val out = EnvironmentDetailsBuilder.build(
            project = project,
            planModeEnabled = false,
            contextManager = null,
        )
        assertTrue(out.contains("<workflow_context>"))
        assertTrue(out.contains("Interaction mode: ReadOnly"), "read-only mode must surface when branch != PR.fromBranch")
    }
}
