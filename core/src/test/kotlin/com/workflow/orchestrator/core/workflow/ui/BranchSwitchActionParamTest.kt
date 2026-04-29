package com.workflow.orchestrator.core.workflow.ui

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * Phase E — `trySwitchTo(project, repoRoot, branch)` is the per-row entry point
 * used by `CurrentWorkSection`. It must NOT consult `WorkflowContextService`'s
 * `activeRepo`; the row is the user-action signal.
 */
class BranchSwitchActionParamTest {

    @AfterEach fun teardown() = unmockkAll()

    @Test fun `trySwitchTo returns NoRepoResolved when no GitRepository matches the path`() = runTest {
        val project = mockk<Project>(relaxed = true)

        mockkStatic(GitRepositoryManager::class)
        val mgr = mockk<GitRepositoryManager>(relaxed = true)
        every { GitRepositoryManager.getInstance(project) } returns mgr
        every { mgr.repositories } returns emptyList()

        val result = BranchSwitchAction.trySwitchTo(
            project = project,
            repoRoot = "/no/such/path",
            branch = "feature/ABC-123",
        )
        assertInstanceOf(BranchSwitchAction.Result.NoRepoResolved::class.java, result)
    }
}
