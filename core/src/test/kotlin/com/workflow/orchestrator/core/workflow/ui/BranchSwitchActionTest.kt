package com.workflow.orchestrator.core.workflow.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase D — pins the dirty-tree-guard contract for [BranchSwitchAction].
 *
 * Three scenarios:
 * - **No focusPr** — link click while in Live mode is a no-op (Result.NoFocusPr).
 * - **Dirty tree** — tracked changes scoped to the repo OR untracked files block
 *   the switch and surface a warning notification (single-repo blast radius).
 * - **Clean tree** — `GitBrancher.checkout` is invoked exactly once on the
 *   resolved repo only (NOT on every repo in a multi-module project).
 *
 * NotificationGroupManager and GitBrancher are stubbed because they require a
 * running platform; the goal is to lock in the routing logic + guard, not to
 * exercise real git.
 */
class BranchSwitchActionTest {

    @AfterEach fun teardown() = unmockkAll()

    private fun stubProject(
        state: WorkflowContext,
        repos: List<RepoConfig> = emptyList(),
    ): Project {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state.activeTicketId } returns null
        every { settings.getRepos() } returns repos
        every { project.getService(PluginSettings::class.java) } returns settings
        mockkStatic(PluginSettings::class)
        every { PluginSettings.getInstance(project) } returns settings

        val service = mockk<WorkflowContextService>(relaxed = true)
        every { service.state } returns MutableStateFlow(state)
        mockkStatic(WorkflowContextService::class)
        every { WorkflowContextService.getInstance(project) } returns service
        return project
    }

    private fun repoConfig(name: String, rootPath: String): RepoConfig =
        RepoConfig().apply {
            this.name = name
            this.localVcsRootPath = rootPath
        }

    private fun stubRepo(rootPath: String): GitRepository {
        val vf = mockk<VirtualFile>(relaxed = true)
        every { vf.path } returns rootPath
        val repo = mockk<GitRepository>(relaxed = true)
        every { repo.root } returns vf
        return repo
    }

    @Test fun `no focus PR returns NoFocusPr without touching git`() = runTest {
        val project = stubProject(WorkflowContext())
        val result = BranchSwitchAction.trySwitch(project)
        assertEquals(BranchSwitchAction.Result.NoFocusPr, result)
    }

    @Test fun `dirty tracked changes in resolved repo block checkout and surface warning`() = runTest {
        val repoRoot = "/work/multi/serviceA"
        val pr = PrRef(42, "feat/x", "main", "repoA", null, null)
        val state = WorkflowContext(focusPr = pr)
        val project = stubProject(state, repos = listOf(repoConfig("repoA", repoRoot)))

        val repo = stubRepo(repoRoot)
        mockkStatic(GitRepositoryManager::class)
        val mgr = mockk<GitRepositoryManager>(relaxed = true)
        every { GitRepositoryManager.getInstance(project) } returns mgr
        every { mgr.repositories } returns listOf(repo)

        // RepoContextResolver fallback path — not used because preferredRoot matches, but
        // mock it anyway to avoid NPE on edge paths.
        mockkObject(RepoContextResolver.Companion)
        every { RepoContextResolver.getInstance(project) } returns mockk(relaxed = true)

        // Notification machinery doesn't exist in unit test env — stub.
        mockkStatic(com.intellij.notification.NotificationGroupManager::class)
        val nGroup = mockk<com.intellij.notification.NotificationGroup>(relaxed = true)
        every { com.intellij.notification.NotificationGroupManager.getInstance() } returns mockk(relaxed = true) {
            every { getNotificationGroup(any()) } returns nGroup
        }

        // Tracked dirty: one change rooted inside the repo path.
        val change = mockk<com.intellij.openapi.vcs.changes.Change>(relaxed = true)
        val changedVf = mockk<VirtualFile>(relaxed = true)
        every { changedVf.path } returns "$repoRoot/src/Main.kt"
        every { change.virtualFile } returns changedVf
        val clm = mockk<ChangeListManager>(relaxed = true)
        every { clm.allChanges } returns listOf(change)
        mockkStatic(ChangeListManager::class)
        every { ChangeListManager.getInstance(project) } returns clm

        // Untracked: zero from the holder (mock returns empty list by default via relaxed).
        every { repo.untrackedFilesHolder.retrieveUntrackedFilePaths() } returns emptyList()

        val result = BranchSwitchAction.trySwitch(project)
        assertTrue(result is BranchSwitchAction.Result.Dirty,
            "Expected Dirty, got: $result")
        val dirty = result as BranchSwitchAction.Result.Dirty
        assertEquals(pr.fromBranch, dirty.branch)
        assertEquals(repoRoot, dirty.repoRoot)
        assertEquals(1, dirty.dirtyCount)
    }

    @Test fun `change outside resolved repo path is ignored (single-repo guard)`() = runTest {
        val repoRoot = "/work/multi/serviceA"
        val otherRoot = "/work/multi/serviceB"
        val pr = PrRef(42, "feat/x", "main", "repoA", null, null)
        val state = WorkflowContext(focusPr = pr)
        val project = stubProject(
            state,
            repos = listOf(
                repoConfig("repoA", repoRoot),
                repoConfig("repoB", otherRoot),
            ),
        )

        val repoA = stubRepo(repoRoot)
        // Sibling repo with its OWN dirty change must not block serviceA's checkout.
        val repoB = stubRepo(otherRoot)
        mockkStatic(GitRepositoryManager::class)
        val mgr = mockk<GitRepositoryManager>(relaxed = true)
        every { GitRepositoryManager.getInstance(project) } returns mgr
        every { mgr.repositories } returns listOf(repoA, repoB)

        mockkObject(RepoContextResolver.Companion)
        every { RepoContextResolver.getInstance(project) } returns mockk(relaxed = true)

        val changeInB = mockk<com.intellij.openapi.vcs.changes.Change>(relaxed = true)
        val vfB = mockk<VirtualFile>(relaxed = true)
        every { vfB.path } returns "$otherRoot/src/Main.kt"
        every { changeInB.virtualFile } returns vfB
        val clm = mockk<ChangeListManager>(relaxed = true)
        every { clm.allChanges } returns listOf(changeInB)
        mockkStatic(ChangeListManager::class)
        every { ChangeListManager.getInstance(project) } returns clm

        every { repoA.untrackedFilesHolder.retrieveUntrackedFilePaths() } returns emptyList()
        every { repoB.untrackedFilesHolder.retrieveUntrackedFilePaths() } returns emptyList()

        // Deterministic success — the stub does nothing on `checkout(...)` and
        // `relaxed = true` returns `Unit`, so `try { ... }` in `trySwitch` succeeds.
        mockkStatic(git4idea.branch.GitBrancher::class)
        val brancher = mockk<git4idea.branch.GitBrancher>(relaxed = true)
        every { git4idea.branch.GitBrancher.getInstance(project) } returns brancher

        val result = BranchSwitchAction.trySwitch(project)
        assertTrue(result is BranchSwitchAction.Result.Switched,
            "Expected Switched (clean repo, deterministic stub), got: $result")
        // Critical assertion (single-repo blast-radius): only the resolved repo
        // (serviceA), not serviceB, is passed to checkout — even though the
        // dirty change lives in serviceB.
        verify(exactly = 1) {
            brancher.checkout(pr.fromBranch, false, listOf(repoA), null)
        }
        verify(exactly = 0) {
            brancher.checkout(any(), any(), match { it.contains(repoB) }, any())
        }
    }
}
