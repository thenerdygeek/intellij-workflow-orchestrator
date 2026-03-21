package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.bitbucket.BitbucketPrReviewerRef
import com.workflow.orchestrator.core.bitbucket.BitbucketPrUpdateRequest
import com.workflow.orchestrator.core.bitbucket.BitbucketReviewerUser
import com.workflow.orchestrator.core.model.bitbucket.CommitData
import com.workflow.orchestrator.core.model.bitbucket.PullRequestData
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings

/**
 * Unified Bitbucket service implementation used by both UI panels and AI agent.
 *
 * Wraps the existing [BitbucketBranchClient] (in :core) and maps its responses
 * to shared domain models ([PullRequestData]) with LLM-optimized text summaries.
 */
@Service(Service.Level.PROJECT)
class BitbucketServiceImpl(private val project: Project) : BitbucketService {

    private val log = Logger.getInstance(BitbucketServiceImpl::class.java)
    private val credentialStore = CredentialStore()
    private val settings get() = PluginSettings.getInstance(project)

    @Volatile private var cachedClient: BitbucketBranchClient? = null
    @Volatile private var cachedBaseUrl: String? = null

    private val client: BitbucketBranchClient?
        get() {
            val url = ConnectionSettings.getInstance().state.bitbucketUrl.trimEnd('/')
            if (url.isBlank()) return null
            if (url != cachedBaseUrl || cachedClient == null) {
                cachedBaseUrl = url
                cachedClient = BitbucketBranchClient(
                    baseUrl = url,
                    tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
                )
            }
            return cachedClient
        }

    override suspend fun createPullRequest(
        title: String,
        description: String,
        fromBranch: String,
        toBranch: String
    ): ToolResult<PullRequestData> {
        val api = client ?: return ToolResult(
            data = PullRequestData(
                id = 0, title = "", state = "ERROR",
                fromBranch = fromBranch, toBranch = toBranch,
                link = "", authorName = null
            ),
            summary = "Bitbucket not configured. Cannot create pull request.",
            isError = true,
            hint = "Set up Bitbucket connection in Settings > Tools > Workflow Orchestrator > General."
        )

        val projectKey = settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()
        if (projectKey.isBlank() || repoSlug.isBlank()) {
            return ToolResult(
                data = PullRequestData(
                    id = 0, title = title, state = "ERROR",
                    fromBranch = fromBranch, toBranch = toBranch,
                    link = "", authorName = null
                ),
                summary = "Bitbucket project/repo not configured. Cannot create PR.",
                isError = true,
                hint = "Set Bitbucket project key and repo slug in Settings."
            )
        }

        return when (val result = api.createPullRequest(
            projectKey = projectKey,
            repoSlug = repoSlug,
            title = title,
            description = description,
            fromBranch = fromBranch,
            toBranch = toBranch
        )) {
            is ApiResult.Success -> {
                val pr = result.data
                val link = pr.links.self.firstOrNull()?.href ?: "${cachedBaseUrl}/projects/$projectKey/repos/$repoSlug/pull-requests/${pr.id}"
                val data = PullRequestData(
                    id = pr.id,
                    title = pr.title,
                    state = pr.state,
                    fromBranch = pr.fromRef?.displayId ?: fromBranch,
                    toBranch = pr.toRef?.displayId ?: toBranch,
                    link = link,
                    authorName = null
                )

                ToolResult.success(
                    data = data,
                    summary = buildString {
                        append("PR #${data.id} created: ${data.title}")
                        append("\n${data.fromBranch} -> ${data.toBranch}")
                        append("\nLink: ${data.link}")
                    }
                )
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to create PR: ${result.message}")
                ToolResult(
                    data = PullRequestData(
                        id = 0, title = title, state = "ERROR",
                        fromBranch = fromBranch, toBranch = toBranch,
                        link = "", authorName = null
                    ),
                    summary = "Error creating pull request: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED ->
                            "Check your Bitbucket token in Settings."
                        com.workflow.orchestrator.core.model.ErrorType.FORBIDDEN ->
                            "You may not have permission to create PRs in this repo."
                        com.workflow.orchestrator.core.model.ErrorType.NOT_FOUND ->
                            "Verify project key and repo slug are correct."
                        else -> "Check Bitbucket connection in Settings."
                    }
                )
            }
        }
    }

    override suspend fun getPullRequestCommits(prId: Int): ToolResult<List<CommitData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bitbucket not configured. Cannot fetch commits for PR #$prId.",
            isError = true,
            hint = "Set up Bitbucket connection in Settings."
        )
        val projectKey = settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()
        if (projectKey.isBlank() || repoSlug.isBlank()) {
            return ToolResult(data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
                hint = "Set Bitbucket project key and repo slug in Settings.")
        }

        return when (val result = api.getPullRequestCommits(projectKey, repoSlug, prId)) {
            is ApiResult.Success -> {
                val commits = result.data.values.map { c ->
                    CommitData(
                        id = c.id,
                        displayId = c.displayId,
                        message = c.message,
                        author = c.author?.name,
                        timestamp = c.authorTimestamp
                    )
                }
                ToolResult.success(
                    data = commits,
                    summary = "PR #$prId has ${commits.size} commit(s)"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch commits for PR #$prId: ${result.message}")
                ToolResult(data = emptyList(), summary = "Error fetching commits for PR #$prId: ${result.message}", isError = true,
                    hint = "Check Bitbucket connection in Settings.")
            }
        }
    }

    override suspend fun addInlineComment(prId: Int, filePath: String, line: Int, lineType: String, text: String): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot add inline comment to PR #$prId.")
        val projectKey = settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()
        if (projectKey.isBlank() || repoSlug.isBlank()) return repoNotConfiguredError()

        return when (val result = api.addInlineComment(projectKey, repoSlug, prId, filePath, line, lineType, text)) {
            is ApiResult.Success -> ToolResult.success(Unit, "Inline comment added to PR #$prId at $filePath:$line")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to add inline comment to PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error adding inline comment: ${result.message}", isError = true,
                    hint = "Verify the file path and line number are valid for this PR.")
            }
        }
    }

    override suspend fun replyToComment(prId: Int, parentCommentId: Int, text: String): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot reply to comment on PR #$prId.")
        val projectKey = settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()
        if (projectKey.isBlank() || repoSlug.isBlank()) return repoNotConfiguredError()

        return when (val result = api.replyToComment(projectKey, repoSlug, prId, parentCommentId, text)) {
            is ApiResult.Success -> ToolResult.success(Unit, "Reply added to comment #$parentCommentId on PR #$prId")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to reply to comment #$parentCommentId on PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error replying to comment: ${result.message}", isError = true,
                    hint = "Verify the comment ID is valid.")
            }
        }
    }

    override suspend fun setReviewerStatus(prId: Int, username: String, status: String): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot set reviewer status on PR #$prId.")
        val projectKey = settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()
        if (projectKey.isBlank() || repoSlug.isBlank()) return repoNotConfiguredError()

        return when (val result = api.setReviewerStatus(projectKey, repoSlug, prId, username, status)) {
            is ApiResult.Success -> ToolResult.success(Unit, "Reviewer '$username' status set to $status on PR #$prId")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to set reviewer status on PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error setting reviewer status: ${result.message}", isError = true,
                    hint = "Valid statuses: APPROVED, NEEDS_WORK, UNAPPROVED.")
            }
        }
    }

    override suspend fun getFileContent(filePath: String, atRef: String): ToolResult<String> {
        val api = client ?: return ToolResult(
            data = "", summary = "Bitbucket not configured. Cannot fetch file content.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val projectKey = settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()
        if (projectKey.isBlank() || repoSlug.isBlank()) {
            return ToolResult(data = "", summary = "Bitbucket project/repo not configured.", isError = true,
                hint = "Set Bitbucket project key and repo slug in Settings.")
        }

        return when (val result = api.getFileContent(projectKey, repoSlug, filePath, atRef)) {
            is ApiResult.Success -> {
                val content = result.data
                ToolResult.success(
                    data = content,
                    summary = "Fetched $filePath at $atRef (${content.length} chars)"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch file $filePath: ${result.message}")
                ToolResult(data = "", summary = "Error fetching file $filePath: ${result.message}", isError = true,
                    hint = "Verify the file path and ref are correct.")
            }
        }
    }

    override suspend fun addReviewer(prId: Int, username: String): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot add reviewer to PR #$prId.")
        val projectKey = settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()
        if (projectKey.isBlank() || repoSlug.isBlank()) return repoNotConfiguredError()

        // Fetch current PR to preserve existing reviewers (PUT replaces entire PR)
        val currentPr = api.getPullRequestDetail(projectKey, repoSlug, prId)
        val existingPr = when (currentPr) {
            is ApiResult.Success -> currentPr.data
            is ApiResult.Error -> return ToolResult(data = Unit,
                summary = "Error fetching PR #$prId: ${currentPr.message}", isError = true,
                hint = "Verify the PR exists.")
        }

        if (existingPr.reviewers.any { it.user.name == username }) {
            return ToolResult(data = Unit, summary = "'$username' is already a reviewer on PR #$prId.",
                isError = true, hint = "Use setReviewerStatus to change their review status.")
        }

        val updatedReviewers = existingPr.reviewers.map {
            BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name))
        } + BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = username))

        val updateRequest = BitbucketPrUpdateRequest(
            title = existingPr.title,
            description = existingPr.description ?: "",
            version = existingPr.version,
            reviewers = updatedReviewers
        )
        return when (val result = api.updatePullRequest(projectKey, repoSlug, prId, updateRequest)) {
            is ApiResult.Success -> ToolResult.success(Unit, "Reviewer '$username' added to PR #$prId")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to add reviewer '$username' to PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error adding reviewer: ${result.message}", isError = true,
                    hint = "Verify the username is correct.")
            }
        }
    }

    override suspend fun updatePrTitle(prId: Int, newTitle: String): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot update PR #$prId title.")
        val projectKey = settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()
        if (projectKey.isBlank() || repoSlug.isBlank()) return repoNotConfiguredError()

        // Fetch current PR to preserve description/reviewers (PUT replaces entire PR)
        val currentPr = api.getPullRequestDetail(projectKey, repoSlug, prId)
        val existingPr = when (currentPr) {
            is ApiResult.Success -> currentPr.data
            is ApiResult.Error -> return ToolResult(data = Unit,
                summary = "Error fetching PR #$prId: ${currentPr.message}", isError = true,
                hint = "Verify the PR exists.")
        }

        val updateRequest = BitbucketPrUpdateRequest(
            title = newTitle,
            description = existingPr.description ?: "",
            version = existingPr.version,
            reviewers = existingPr.reviewers.map {
                BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name))
            }
        )
        return when (val result = api.updatePullRequest(projectKey, repoSlug, prId, updateRequest)) {
            is ApiResult.Success -> ToolResult.success(Unit, "PR #$prId title updated to: $newTitle")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to update PR #$prId title: ${result.message}")
                ToolResult(data = Unit, summary = "Error updating PR title: ${result.message}", isError = true,
                    hint = "Check Bitbucket connection in Settings.")
            }
        }
    }

    private fun notConfiguredError(detail: String): ToolResult<Unit> = ToolResult(
        data = Unit, summary = "Bitbucket not configured. $detail",
        isError = true, hint = "Set up Bitbucket connection in Settings > Tools > Workflow Orchestrator > General."
    )

    private fun repoNotConfiguredError(): ToolResult<Unit> = ToolResult(
        data = Unit, summary = "Bitbucket project/repo not configured.",
        isError = true, hint = "Set Bitbucket project key and repo slug in Settings."
    )

    override suspend fun testConnection(): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Bitbucket not configured.",
            isError = true,
            hint = "Set Bitbucket URL and token in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getProjects()) {
            is ApiResult.Success -> {
                ToolResult.success(Unit, "Bitbucket connection successful. Found ${result.data.size} projects.")
            }
            is ApiResult.Error -> {
                ToolResult(
                    data = Unit,
                    summary = "Bitbucket connection failed: ${result.message}",
                    isError = true,
                    hint = "Check URL and token in Settings."
                )
            }
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): BitbucketServiceImpl =
            project.getService(BitbucketService::class.java) as BitbucketServiceImpl
    }
}
