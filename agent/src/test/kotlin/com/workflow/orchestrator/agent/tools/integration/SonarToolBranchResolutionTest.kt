package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import git4idea.repo.GitRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Repo-resolution sweep — item 8.
 *
 * Pins the A → B → C resolution order in [SonarTool.resolveLocalAnalysisBranch]:
 *   - A. file-arg's repo                                  — not wired today (local_analysis
 *                                                           accepts a `files` CSV but resolves
 *                                                           the project root via Maven/Gradle
 *                                                           multi-module scoping). Skipped.
 *   - B. WorkflowContextService.state.activeBranch         — user's "currently working in"
 *                                                           signal mirrored across all 6 tabs.
 *   - C. RepoContextResolver.resolveCurrentEditorRepoOrPrimary — fresh editor/primary fallback.
 *
 * Plan: docs/architecture/repo-resolution-sweep-plan.md.
 */
class SonarToolBranchResolutionTest {

    private val tool = SonarTool()

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `B - WorkflowContextService activeBranch wins when present`() {
        val project = mockk<Project>(relaxed = true)
        val workflowContext = mockk<WorkflowContextService>()
        every { workflowContext.state } returns MutableStateFlow(
            WorkflowContext(activeBranch = "feature/foo")
        )
        every { project.getService(WorkflowContextService::class.java) } returns workflowContext

        // RepoContextResolver still gets queried (B and C are evaluated independently before
        // chaining), so mock it to return null and prove B is selected.
        val repoResolver = mockk<RepoContextResolver>()
        every { repoResolver.resolveCurrentEditorRepoOrPrimary() } returns null
        every { project.getService(RepoContextResolver::class.java) } returns repoResolver

        val result = tool.resolveLocalAnalysisBranch(project, userProvided = null)

        assertTrue(
            result is SonarTool.BranchResolution.Use,
            "expected Use, got: $result"
        )
        assertEquals("feature/foo", (result as SonarTool.BranchResolution.Use).branch)
    }

    @Test
    fun `C - falls through to editor-or-primary when WorkflowContext branch is null`() {
        val project = mockk<Project>(relaxed = true)
        val workflowContext = mockk<WorkflowContextService>()
        every { workflowContext.state } returns MutableStateFlow(WorkflowContext(activeBranch = null))
        every { project.getService(WorkflowContextService::class.java) } returns workflowContext

        val gitRepo = mockk<GitRepository>()
        every { gitRepo.currentBranchName } returns "feature/from-editor"
        val repoResolver = mockk<RepoContextResolver>()
        every { repoResolver.resolveCurrentEditorRepoOrPrimary() } returns gitRepo
        every { project.getService(RepoContextResolver::class.java) } returns repoResolver

        val result = tool.resolveLocalAnalysisBranch(project, userProvided = null)

        assertTrue(
            result is SonarTool.BranchResolution.Use,
            "expected Use, got: $result"
        )
        assertEquals("feature/from-editor", (result as SonarTool.BranchResolution.Use).branch)
    }

    @Test
    fun `Omit - all sources blank returns Omit`() {
        val project = mockk<Project>(relaxed = true)
        val workflowContext = mockk<WorkflowContextService>()
        every { workflowContext.state } returns MutableStateFlow(WorkflowContext(activeBranch = null))
        every { project.getService(WorkflowContextService::class.java) } returns workflowContext

        val repoResolver = mockk<RepoContextResolver>()
        every { repoResolver.resolveCurrentEditorRepoOrPrimary() } returns null
        every { project.getService(RepoContextResolver::class.java) } returns repoResolver

        val result = tool.resolveLocalAnalysisBranch(project, userProvided = null)

        assertTrue(
            result is SonarTool.BranchResolution.Omit,
            "expected Omit when no branch source returns a value, got: $result"
        )
    }

    /**
     * Protected branch redirect: when the resolved branch is `main`/`master`/`develop`/`release`/
     * `hotfix`/`trunk`, publish to a `local-scratch-<branch>` name instead of overwriting the
     * shared dashboard. Asserts this still fires when the branch comes from WorkflowContext.
     */
    @Test
    fun `protected branch from WorkflowContext is redirected to local-scratch`() {
        val project = mockk<Project>(relaxed = true)
        val workflowContext = mockk<WorkflowContextService>()
        every { workflowContext.state } returns MutableStateFlow(WorkflowContext(activeBranch = "main"))
        every { project.getService(WorkflowContextService::class.java) } returns workflowContext

        val repoResolver = mockk<RepoContextResolver>()
        every { repoResolver.resolveCurrentEditorRepoOrPrimary() } returns null
        every { project.getService(RepoContextResolver::class.java) } returns repoResolver

        val result = tool.resolveLocalAnalysisBranch(project, userProvided = null)

        assertTrue(
            result is SonarTool.BranchResolution.Use,
            "expected Use with redirect, got: $result"
        )
        val use = result as SonarTool.BranchResolution.Use
        assertTrue(
            use.branch.startsWith("local-scratch-") && use.branch.contains("main"),
            "expected local-scratch redirect for protected name, got branch: ${use.branch}"
        )
    }

    /**
     * `userBranch` (LLM- or user-supplied) wins over the auto-detected WorkflowContext branch —
     * the function's argument explicitly names the analysis target.
     */
    @Test
    fun `userProvided branch wins over WorkflowContext`() {
        val project = mockk<Project>(relaxed = true)
        val workflowContext = mockk<WorkflowContextService>()
        every { workflowContext.state } returns MutableStateFlow(
            WorkflowContext(activeBranch = "feature/from-context")
        )
        every { project.getService(WorkflowContextService::class.java) } returns workflowContext

        val repoResolver = mockk<RepoContextResolver>()
        every { repoResolver.resolveCurrentEditorRepoOrPrimary() } returns null
        every { project.getService(RepoContextResolver::class.java) } returns repoResolver

        val result = tool.resolveLocalAnalysisBranch(project, userProvided = "feature/user-supplied")

        assertTrue(
            result is SonarTool.BranchResolution.Use,
            "expected Use, got: $result"
        )
        assertEquals("feature/user-supplied", (result as SonarTool.BranchResolution.Use).branch)
    }
}
