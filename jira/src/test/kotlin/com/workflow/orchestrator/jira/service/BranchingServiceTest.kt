package com.workflow.orchestrator.jira.service

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.bitbucket.BitbucketBranch
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.DevStatusBranch
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.workflow.orchestrator.jira.api.dto.JiraIssueFields
import com.workflow.orchestrator.jira.api.dto.JiraStatus
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Pins the multi-repo "no silent fallback" contract for [BranchingService].
 *
 * `localVcsRootPath` is the user-pinned Git root from the Start Work dialog. When
 * it doesn't match any current repo (project re-opened with stale state, repo
 * unmounted, typo in the dropdown's source list), the historical behaviour was
 * to fall back to the editor's repo and then to `repositories.first()` — both of
 * which silently checked out the wrong submodule in multi-repo projects. The
 * current contract is a hard `ApiResult.Error(NOT_FOUND, ...)` instead.
 *
 * One test per public function (`useExistingBranch`, `startWork`) because both
 * had near-identical fallback blocks that were replaced independently.
 */
class BranchingServiceTest {

    private lateinit var apiClient: JiraApiClient
    private lateinit var activeTicketService: ActiveTicketService
    private lateinit var project: Project
    private lateinit var service: BranchingService

    private val testIssue = JiraIssue(
        id = "10001", key = "PROJ-123",
        fields = JiraIssueFields(
            summary = "Fix login",
            status = JiraStatus(name = "To Do"),
        ),
    )

    @BeforeEach
    fun setUp() {
        apiClient = mockk(relaxed = true)
        activeTicketService = mockk(relaxed = true)
        project = mockk(relaxed = true)
        service = BranchingService(project, apiClient, activeTicketService)

        // Stub IntelliJ's suspending read-action builders so they invoke their lambda
        // inline on the calling coroutine. The real implementations look up
        // `ReadWriteActionSupport` via `ApplicationManager`, which NPEs without a
        // running platform.
        mockkStatic("com.intellij.openapi.application.CoroutinesKt")
        coEvery { readAction<Any?>(any()) } coAnswers { firstArg<() -> Any?>().invoke() }
        coEvery { readActionBlocking<Any?>(any()) } coAnswers { firstArg<() -> Any?>().invoke() }
        coEvery { smartReadAction<Any?>(any(), any()) } coAnswers { secondArg<() -> Any?>().invoke() }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun stubRepo(rootPath: String): GitRepository {
        val vf = mockk<VirtualFile>(relaxed = true)
        every { vf.path } returns rootPath
        val repo = mockk<GitRepository>(relaxed = true)
        every { repo.root } returns vf
        return repo
    }

    private fun stubRepositoryManager(vararg repos: GitRepository) {
        mockkStatic(GitRepositoryManager::class)
        val mgr = mockk<GitRepositoryManager>(relaxed = true)
        every { GitRepositoryManager.getInstance(project) } returns mgr
        every { mgr.repositories } returns repos.toList()
    }

    @Test
    fun `useExistingBranch hard-errors when localVcsRootPath does not match any known repo`() = runTest {
        val pinned = "/work/multi/serviceA"
        val other = "/work/multi/serviceB"
        stubRepositoryManager(stubRepo(other))

        val result = service.useExistingBranch(
            issue = testIssue,
            branchName = "feature/PROJ-123",
            localVcsRootPath = pinned,
        )

        assertTrue(result is ApiResult.Error, "Expected Error, got: $result")
        val err = result as ApiResult.Error
        assertEquals(ErrorType.NOT_FOUND, err.type)
        assertEquals(
            "Configured VCS root '$pinned' not found — repick in Start Work dialog",
            err.message,
        )
    }

    // ── JIRA-COV-10: fetchLinkedBranches primary path and Bitbucket fallback ─

    @Test
    fun `fetchLinkedBranches returns dev-status branches when dev-status API returns non-empty`() = runTest {
        val devBranch1 = DevStatusBranch(name = "feature/PROJ-123-login-fix", url = "https://bitbucket/branch1")
        val devBranch2 = DevStatusBranch(name = "feature/PROJ-123-unit-tests", url = "https://bitbucket/branch2")
        coEvery { apiClient.getDevStatusBranches(testIssue.id) } returns ApiResult.Success(
            listOf(devBranch1, devBranch2)
        )

        val result = service.fetchLinkedBranches(testIssue, allBranches = emptyList())

        assertEquals(2, result.size, "Both dev-status branches must be returned")
        assertTrue(result.contains("feature/PROJ-123-login-fix"))
        assertTrue(result.contains("feature/PROJ-123-unit-tests"))
    }

    @Test
    fun `fetchLinkedBranches falls back to Bitbucket name filter when dev-status returns empty list`() = runTest {
        coEvery { apiClient.getDevStatusBranches(testIssue.id) } returns ApiResult.Success(emptyList())

        val matchingBranch = BitbucketBranch(
            id = "refs/heads/feature/PROJ-123-fix",
            displayId = "feature/PROJ-123-fix",
            latestCommit = "abc123"
        )
        val nonMatchingBranch = BitbucketBranch(
            id = "refs/heads/feature/OTHER-456-unrelated",
            displayId = "feature/OTHER-456-unrelated",
            latestCommit = "def456"
        )
        val allBranches = listOf(matchingBranch, nonMatchingBranch)

        val result = service.fetchLinkedBranches(testIssue, allBranches)

        assertEquals(1, result.size,
            "Fallback must return only branches whose displayId contains the ticket key; got: $result")
        assertEquals("feature/PROJ-123-fix", result[0])
    }

    @Test
    fun `fetchLinkedBranches returns empty list when both dev-status and Bitbucket search find nothing`() = runTest {
        coEvery { apiClient.getDevStatusBranches(testIssue.id) } returns ApiResult.Success(emptyList())

        val unrelatedBranch = BitbucketBranch(
            id = "refs/heads/main",
            displayId = "main",
            latestCommit = "abc123"
        )

        val result = service.fetchLinkedBranches(testIssue, allBranches = listOf(unrelatedBranch))

        assertTrue(result.isEmpty(),
            "When neither dev-status nor Bitbucket filter finds a match, result must be empty; got: $result")
    }

    @Test
    fun `startWork hard-errors when localVcsRootPath does not match any known repo`() = runTest {
        val pinned = "/work/multi/serviceA"
        val other = "/work/multi/serviceB"
        stubRepositoryManager(stubRepo(other))

        // The remote-create step runs before the repo-resolution check; stub it to
        // succeed so we exercise the local checkout branch.
        val branchClient = mockk<BitbucketBranchClient>(relaxed = true)
        val createdBranch = BitbucketBranch(
            id = "refs/heads/feature/PROJ-123",
            displayId = "feature/PROJ-123",
            latestCommit = "abc123",
        )
        coEvery {
            branchClient.createBranch("PROJ", "service-a-repo", "feature/PROJ-123", "main")
        } returns ApiResult.Success(createdBranch)

        val result = service.startWork(
            issue = testIssue,
            branchName = "feature/PROJ-123",
            sourceBranch = "main",
            branchClient = branchClient,
            projectKey = "PROJ",
            repoSlug = "service-a-repo",
            localVcsRootPath = pinned,
        )

        assertTrue(result is ApiResult.Error, "Expected Error, got: $result")
        val err = result as ApiResult.Error
        assertEquals(ErrorType.NOT_FOUND, err.type)
        assertEquals(
            "Configured VCS root '$pinned' not found — repick in Start Work dialog",
            err.message,
        )
    }
}
