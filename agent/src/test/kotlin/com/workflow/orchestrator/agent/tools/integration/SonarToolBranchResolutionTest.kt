package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
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
 * Pins the resolution order in [SonarTool.resolveLocalAnalysisBranch]:
 *   - A. file-arg's repo                                       — not wired today (local_analysis
 *                                                                accepts a `files` CSV but resolves
 *                                                                the project root via Maven/Gradle
 *                                                                multi-module scoping). Skipped.
 *   - B. focusPr.fromBranch                                    — anchored "current task" branch.
 *                                                                Authoritative when set.
 *   - C. RepoContextResolver.resolveCurrentEditorRepoOrPrimary — editor/primary fallback, only
 *                                                                consulted when no PR is focused.
 *
 * After the 2026-04-30 editor-coupling rip-out, the previous source B (editor-derived
 * `state.activeBranch`) was demoted: opening any random file in another submodule used
 * to swing Sonar's branch hint, contradicting whatever PR the user was actively working on.
 */
class SonarToolBranchResolutionTest {

    private val tool = SonarTool()

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun pr(branch: String) = PrRef(42, branch, "main", "repo", null, null)

    @Test
    fun `B - focusPr fromBranch wins when a PR is focused`() {
        val project = mockk<Project>(relaxed = true)
        val workflowContext = mockk<WorkflowContextService>()
        every { workflowContext.state } returns MutableStateFlow(
            WorkflowContext(focusPr = pr("feature/foo"))
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

    /**
     * Editor-coupling regression guard: when a PR is focused, the editor's branch must NOT
     * override `focusPr.fromBranch`. Previously, opening any file in another submodule would
     * silently re-target Sonar at the wrong branch.
     */
    @Test
    fun `B wins over C - editor-derived branch must not override focusPr`() {
        val project = mockk<Project>(relaxed = true)
        val workflowContext = mockk<WorkflowContextService>()
        every { workflowContext.state } returns MutableStateFlow(
            WorkflowContext(focusPr = pr("feature/from-pr"))
        )
        every { project.getService(WorkflowContextService::class.java) } returns workflowContext

        // Editor sits on a different branch — must be ignored when focusPr is set.
        val gitRepo = mockk<GitRepository>()
        every { gitRepo.currentBranchName } returns "feature/from-editor-different"
        val repoResolver = mockk<RepoContextResolver>()
        every { repoResolver.resolveCurrentEditorRepoOrPrimary() } returns gitRepo
        every { project.getService(RepoContextResolver::class.java) } returns repoResolver

        val result = tool.resolveLocalAnalysisBranch(project, userProvided = null)

        assertTrue(result is SonarTool.BranchResolution.Use, "expected Use, got: $result")
        assertEquals(
            "feature/from-pr",
            (result as SonarTool.BranchResolution.Use).branch,
            "focusPr.fromBranch must win over editor-derived branch",
        )
    }

    @Test
    fun `C - falls through to editor-or-primary when no PR is focused`() {
        val project = mockk<Project>(relaxed = true)
        val workflowContext = mockk<WorkflowContextService>()
        every { workflowContext.state } returns MutableStateFlow(WorkflowContext(focusPr = null))
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
        every { workflowContext.state } returns MutableStateFlow(WorkflowContext(focusPr = null))
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
     * shared dashboard. Asserts this still fires when the branch comes from focusPr.
     */
    @Test
    fun `protected branch from focusPr is redirected to local-scratch`() {
        val project = mockk<Project>(relaxed = true)
        val workflowContext = mockk<WorkflowContextService>()
        every { workflowContext.state } returns MutableStateFlow(
            WorkflowContext(focusPr = pr("main"))
        )
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
     * `userBranch` (LLM- or user-supplied) wins over auto-detected sources — the function's
     * argument explicitly names the analysis target.
     */
    @Test
    fun `userProvided branch wins over focusPr`() {
        val project = mockk<Project>(relaxed = true)
        val workflowContext = mockk<WorkflowContextService>()
        every { workflowContext.state } returns MutableStateFlow(
            WorkflowContext(focusPr = pr("feature/from-pr"))
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
