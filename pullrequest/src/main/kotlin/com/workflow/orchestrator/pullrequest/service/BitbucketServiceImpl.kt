package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketPrCommentResponse
import com.workflow.orchestrator.core.bitbucket.BitbucketPrReviewerRef
import com.workflow.orchestrator.core.bitbucket.BitbucketPrUpdateRequest
import com.workflow.orchestrator.core.bitbucket.BitbucketReviewerUser
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.PrComment
import com.workflow.orchestrator.core.model.PrCommentAnchor
import com.workflow.orchestrator.core.model.PrCommentAuthor
import com.workflow.orchestrator.core.model.PrCommentFileType
import com.workflow.orchestrator.core.model.PrCommentLineType
import com.workflow.orchestrator.core.model.PrCommentPermittedOps
import com.workflow.orchestrator.core.model.PrCommentSeverity
import com.workflow.orchestrator.core.model.PrCommentState
import com.workflow.orchestrator.core.model.bitbucket.*
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.services.VcsHostClient
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings

/**
 * Unified Bitbucket service implementation used by both UI panels and AI agent.
 *
 * Wraps the existing [BitbucketBranchClient] (in :core) and maps its responses
 * to shared domain models ([PullRequestData]) with LLM-optimized text summaries.
 */
@Service(Service.Level.PROJECT)
class BitbucketServiceImpl(private val project: Project) : BitbucketService, VcsHostClient {

    private val log = Logger.getInstance(BitbucketServiceImpl::class.java)
    private val settings get() = PluginSettings.getInstance(project)

    private val clientCache = BitbucketBranchClientCache()

    private val client: BitbucketBranchClient?
        get() = clientCache.get()

    private fun resolveRepo(repoName: String?): Pair<String, String>? {
        if (repoName != null) {
            val repo = settings.getRepoByName(repoName)
            if (repo != null && repo.isConfigured) return Pair(repo.bitbucketProjectKey!!, repo.bitbucketRepoSlug!!)
        }
        // Fall back to primary repo
        val primary = settings.getPrimaryRepo()
        if (primary != null && primary.isConfigured) return Pair(primary.bitbucketProjectKey!!, primary.bitbucketRepoSlug!!)
        return null
    }

    override suspend fun listRepos(): ToolResult<List<RepoInfo>> {
        val repos = settings.getRepos()
        val repoInfos = repos.filter { it.isConfigured }.map {
            RepoInfo(it.name ?: "", it.bitbucketProjectKey ?: "", it.bitbucketRepoSlug ?: "", it.isPrimary)
        }
        return ToolResult.success(repoInfos, "Found ${repoInfos.size} configured repos")
    }

    override suspend fun createPullRequest(
        title: String,
        description: String,
        fromBranch: String,
        toBranch: String,
        repoName: String?
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

        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = PullRequestData(
                id = 0, title = title, state = "ERROR",
                fromBranch = fromBranch, toBranch = toBranch,
                link = "", authorName = null
            ),
            summary = "Bitbucket project/repo not configured. Cannot create PR.",
            isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )

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
                val link = pr.links.self.firstOrNull()?.href ?: "${clientCache.cachedUrl}/projects/$projectKey/repos/$repoSlug/pull-requests/${pr.id}"
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

    override suspend fun getPullRequestCommits(prId: Int, repoName: String?): ToolResult<List<CommitData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bitbucket not configured. Cannot fetch commits for PR #$prId.",
            isError = true,
            hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )

        val maxPages = 20  // safety cap: ~1000 commits at limit=50
        val aggregated = mutableListOf<CommitData>()
        var cursor = 0
        var pages = 0
        while (pages < maxPages) {
            when (val page = api.getPullRequestCommits(projectKey, repoSlug, prId, start = cursor)) {
                is ApiResult.Error -> {
                    log.warn("[BitbucketService] Failed to fetch commits for PR #$prId: ${page.message}")
                    return ToolResult(
                        data = emptyList(),
                        summary = "Error fetching commits for PR #$prId: ${page.message}",
                        isError = true,
                        hint = "Check Bitbucket connection in Settings."
                    )
                }
                is ApiResult.Success -> {
                    aggregated += page.data.values.map { c ->
                        CommitData(
                            id = c.id,
                            displayId = c.displayId,
                            message = c.message,
                            author = c.author?.name,
                            timestamp = c.authorTimestamp
                        )
                    }
                    if (page.data.isLastPage || page.data.nextPageStart == null) {
                        return ToolResult.success(
                            data = aggregated,
                            summary = "PR #$prId has ${aggregated.size} commit(s)"
                        )
                    }
                    cursor = page.data.nextPageStart!!
                    pages++
                }
            }
        }
        return ToolResult.success(
            data = aggregated,
            summary = "PR #$prId has ${aggregated.size} commit(s) (truncated at page cap)"
        )
    }

    override suspend fun addInlineComment(
        prId: Int,
        filePath: String,
        line: Int,
        lineType: String,
        text: String,
        repoName: String?,
        diffType: String?,
        fromHash: String?,
        toHash: String?,
    ): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot add inline comment to PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        return when (val result = api.addInlineComment(
            projectKey = projectKey,
            repoSlug = repoSlug,
            prId = prId,
            filePath = filePath,
            lineNumber = line,
            lineType = lineType,
            text = text,
            diffType = diffType,
            fromHash = fromHash,
            toHash = toHash,
        )) {
            is ApiResult.Success -> ToolResult.success(Unit, "Inline comment added to PR #$prId at $filePath:$line")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to add inline comment to PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error adding inline comment: ${result.message}", isError = true,
                    hint = "Verify the file path and line number are valid for this PR.")
            }
        }
    }

    override suspend fun replyToComment(prId: Int, parentCommentId: Int, text: String, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot reply to comment on PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        return when (val result = api.replyToComment(projectKey, repoSlug, prId, parentCommentId, text)) {
            is ApiResult.Success -> ToolResult.success(Unit, "Reply added to comment #$parentCommentId on PR #$prId")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to reply to comment #$parentCommentId on PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error replying to comment: ${result.message}", isError = true,
                    hint = "Verify the comment ID is valid.")
            }
        }
    }

    override suspend fun setReviewerStatus(prId: Int, username: String, status: String, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot set reviewer status on PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        return when (val result = api.setReviewerStatus(projectKey, repoSlug, prId, username, status)) {
            is ApiResult.Success -> ToolResult.success(Unit, "Reviewer '$username' status set to $status on PR #$prId")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to set reviewer status on PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error setting reviewer status: ${result.message}", isError = true,
                    hint = "Valid statuses: APPROVED, NEEDS_WORK, UNAPPROVED.")
            }
        }
    }

    override suspend fun getFileContent(filePath: String, atRef: String, repoName: String?): ToolResult<String> {
        val api = client ?: return ToolResult(
            data = "", summary = "Bitbucket not configured. Cannot fetch file content.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = "", summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )

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

    /**
     * Agent entry point for adding a reviewer. Routes through the same
     * [PrActionService.addReviewer] path the dialog uses so the
     * [BitbucketBranchClient.modifyPullRequest] retry-on-409 logic isn't
     * duplicated (PR 6 follow-up to PR 3 of the 2026-05-07 write-ops fix plan).
     *
     * For multi-repo agents (where [repoName] selects a non-primary repo) we
     * detect the mismatch up-front and fall back to the legacy direct-PUT path
     * so the call still works against the chosen repo. PrActionService is
     * single-repo (settings-resolved) by construction; refactoring it to
     * accept [RepoCoords] is out of scope for this PR.
     */
    override suspend fun addReviewer(prId: Int, username: String, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot add reviewer to PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        // Multi-repo agent path: PrActionService is single-repo (resolves via
        // settings.bitbucketProjectKey/RepoSlug). When the agent targets a
        // non-primary repo, route directly through modifyPullRequest with the
        // resolved coords so we still get retry-on-409 without leaking
        // settings-vs-tool repo selection through PrActionService.
        if (!isPrimaryRepo(projectKey, repoSlug)) {
            // Pre-check against a fresh GET so we can return the friendly
            // "already a reviewer" message; the modifyPullRequest mutator on
            // retry is idempotent if a concurrent caller added them.
            val pre = api.getPullRequestDetail(projectKey, repoSlug, prId)
            if (pre is ApiResult.Success && pre.data.reviewers.any { it.user.name == username }) {
                return ToolResult(data = Unit, summary = "'$username' is already a reviewer on PR #$prId.",
                    isError = true, hint = "Use setReviewerStatus to change their review status.")
            }
            return modifyPullRequestForAgent(api, prId, projectKey, repoSlug, "add reviewer '$username'") { current ->
                BitbucketPrUpdateRequest(
                    title = current.title,
                    description = current.description ?: "",
                    version = current.version,
                    reviewers = (current.reviewers.map {
                        BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name))
                    } + BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = username)))
                        .distinctBy { it.user.name },  // idempotent on retry race
                )
            }
        }

        val result = PrActionService.getInstance(project).addReviewer(prId, username)
        return result.toToolResult(
            okSummary = "Reviewer '$username' added to PR #$prId",
            errPrefix = "Error adding reviewer",
            hint = "Verify the username is correct.",
        )
    }

    /**
     * Agent entry point for updating a PR title. See [addReviewer] for the
     * single-repo / multi-repo routing rationale.
     */
    override suspend fun updatePrTitle(prId: Int, newTitle: String, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot update PR #$prId title.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        if (!isPrimaryRepo(projectKey, repoSlug)) {
            return modifyPullRequestForAgent(api, prId, projectKey, repoSlug, "update title") { current ->
                BitbucketPrUpdateRequest(
                    title = newTitle,
                    description = current.description ?: "",
                    version = current.version,
                    reviewers = current.reviewers.map {
                        BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name))
                    },
                )
            }
        }

        val result = PrActionService.getInstance(project).updateTitle(prId, newTitle)
        return result.toToolResult(
            okSummary = "PR #$prId title updated to: $newTitle",
            errPrefix = "Error updating PR title",
            hint = "Check Bitbucket connection in Settings.",
        )
    }

    // --- Branch operations ---

    override suspend fun getBranches(filter: String?, repoName: String?): ToolResult<List<BranchData>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured. Cannot fetch branches.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )

        return when (val result = api.getBranches(projectKey, repoSlug, filter ?: "")) {
            is ApiResult.Success -> {
                val branches = result.data.map { b ->
                    BranchData(id = b.id, displayId = b.displayId, latestCommit = b.latestCommit, isDefault = b.isDefault)
                }
                ToolResult.success(branches, "Found ${branches.size} branch(es)${if (!filter.isNullOrBlank()) " matching '$filter'" else ""}")
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch branches: ${result.message}")
                ToolResult(data = emptyList(), summary = "Error fetching branches: ${result.message}", isError = true,
                    hint = "Check Bitbucket connection in Settings.")
            }
        }
    }

    override suspend fun createBranch(name: String, startPoint: String, repoName: String?): ToolResult<BranchData> {
        val api = client ?: return ToolResult(
            data = BranchData(id = "", displayId = "", latestCommit = null),
            summary = "Bitbucket not configured. Cannot create branch.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = BranchData(id = "", displayId = "", latestCommit = null),
            summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )

        return when (val result = api.createBranch(projectKey, repoSlug, name, startPoint)) {
            is ApiResult.Success -> {
                val b = result.data
                val data = BranchData(id = b.id, displayId = b.displayId, latestCommit = b.latestCommit, isDefault = b.isDefault)
                ToolResult.success(data, "Branch '${data.displayId}' created from '$startPoint'")
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to create branch '$name': ${result.message}")
                ToolResult(data = BranchData(id = "", displayId = name, latestCommit = null),
                    summary = "Error creating branch '$name': ${result.message}", isError = true,
                    hint = "Check the start point is a valid commit or branch name.")
            }
        }
    }

    // --- Users ---

    override suspend fun searchUsers(filter: String, repoName: String?): ToolResult<List<BitbucketUserData>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured. Cannot search users.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )

        return when (val result = api.getUsers(filter)) {
            is ApiResult.Success -> {
                val users = result.data.map { u ->
                    BitbucketUserData(name = u.name, displayName = u.displayName, emailAddress = u.emailAddress)
                }
                ToolResult.success(users, "Found ${users.size} user(s) matching '$filter'")
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to search users: ${result.message}")
                ToolResult(data = emptyList(), summary = "Error searching users: ${result.message}", isError = true,
                    hint = "Check Bitbucket connection in Settings.")
            }
        }
    }

    // --- PR listing ---

    override suspend fun getPullRequestsForBranch(branchName: String, repoName: String?): ToolResult<List<PullRequestData>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured. Cannot fetch PRs for branch.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )

        return when (val result = api.getPullRequestsForBranch(projectKey, repoSlug, branchName)) {
            is ApiResult.Success -> {
                val prs = result.data.map { pr ->
                    PullRequestData(
                        id = pr.id, title = pr.title, state = pr.state,
                        fromBranch = pr.fromRef?.displayId ?: branchName,
                        toBranch = pr.toRef?.displayId ?: "",
                        link = pr.links.self.firstOrNull()?.href ?: "",
                        authorName = null
                    )
                }
                ToolResult.success(prs, "Found ${prs.size} PR(s) for branch '$branchName'")
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch PRs for branch '$branchName': ${result.message}")
                ToolResult(data = emptyList(), summary = "Error fetching PRs for branch: ${result.message}", isError = true,
                    hint = "Check Bitbucket connection in Settings.")
            }
        }
    }

    override suspend fun getMyPullRequests(state: String, repoName: String?): ToolResult<List<PullRequestData>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured. Cannot fetch my PRs.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )
        val username = resolveCurrentUsername(api) ?: return ToolResult(
            data = emptyList(), summary = "Cannot resolve Bitbucket username. Set it in Settings or ensure the server is reachable.",
            isError = true, hint = "Set your Bitbucket username in Settings > Workflow Orchestrator > Connections."
        )

        return when (val result = api.getMyPullRequests(projectKey, repoSlug, state, username = username)) {
            is ApiResult.Success -> {
                val prs = result.data.values.map { it.toPullRequestData() }
                ToolResult.success(prs, "Found ${prs.size} authored PR(s) with state '$state'")
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch my PRs: ${result.message}")
                ToolResult(data = emptyList(), summary = "Error fetching my PRs: ${result.message}", isError = true,
                    hint = "Check Bitbucket connection in Settings.")
            }
        }
    }

    /**
     * Resolves the current Bitbucket username.
     * Priority: ConnectionSettings.bitbucketUsername → API whoami endpoint fallback.
     * Returns null if neither source yields a non-blank username.
     */
    private suspend fun resolveCurrentUsername(api: BitbucketBranchClient): String? {
        val fromSettings = ConnectionSettings.getInstance().state.bitbucketUsername.takeIf { it.isNotBlank() }
        if (fromSettings != null) return fromSettings
        return (api.getCurrentUsername() as? ApiResult.Success)?.data?.takeIf { it.isNotBlank() }
    }

    override suspend fun getReviewingPullRequests(state: String, repoName: String?): ToolResult<List<PullRequestData>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured. Cannot fetch reviewing PRs.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )
        val username = resolveCurrentUsername(api) ?: return ToolResult(
            data = emptyList(), summary = "Cannot resolve Bitbucket username. Set it in Settings or ensure the server is reachable.",
            isError = true, hint = "Set your Bitbucket username in Settings > Workflow Orchestrator > Connections."
        )

        return when (val result = api.getReviewingPullRequests(projectKey, repoSlug, state, username = username)) {
            is ApiResult.Success -> {
                val prs = result.data.values.map { it.toPullRequestData() }
                ToolResult.success(prs, "Found ${prs.size} reviewing PR(s) with state '$state'")
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch reviewing PRs: ${result.message}")
                ToolResult(data = emptyList(), summary = "Error fetching reviewing PRs: ${result.message}", isError = true,
                    hint = "Check Bitbucket connection in Settings.")
            }
        }
    }

    // --- PR detail ---

    override suspend fun getPullRequestDetail(prId: Int, repoName: String?): ToolResult<PullRequestDetailData> {
        val api = client ?: return ToolResult(
            data = emptyPrDetail(prId),
            summary = "Bitbucket not configured. Cannot fetch PR #$prId detail.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyPrDetail(prId), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )

        return when (val result = api.getPullRequestDetail(projectKey, repoSlug, prId)) {
            is ApiResult.Success -> {
                val pr = result.data
                val data = pr.toPullRequestDetailData()
                val reviewerSummary = data.reviewers.joinToString(", ") { "${it.displayName} (${it.status})" }
                ToolResult.success(data, buildString {
                    append("PR #${data.id}: ${data.title} [${data.state}]")
                    append("\n${data.fromBranch} -> ${data.toBranch}")
                    if (data.reviewers.isNotEmpty()) append("\nReviewers: $reviewerSummary")
                })
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch PR #$prId detail: ${result.message}")
                ToolResult(data = emptyPrDetail(prId), summary = "Error fetching PR #$prId detail: ${result.message}", isError = true,
                    hint = "Verify the PR exists.")
            }
        }
    }

    override suspend fun getPullRequestActivities(prId: Int, repoName: String?): ToolResult<List<PrActivityData>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured. Cannot fetch PR #$prId activities.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )

        return when (val result = api.getPullRequestActivities(projectKey, repoSlug, prId)) {
            is ApiResult.Success -> {
                val activities = result.data.values.map { a ->
                    PrActivityData(
                        id = a.id,
                        action = a.action,
                        userName = a.user.displayName.ifBlank { a.user.name },
                        timestamp = a.createdDate,
                        commentText = a.comment?.text,
                        commentId = a.comment?.id,
                        filePath = a.commentAnchor?.path ?: a.comment?.anchor?.path,
                        lineNumber = a.commentAnchor?.line ?: a.comment?.anchor?.line
                    )
                }
                ToolResult.success(activities, "PR #$prId has ${activities.size} activit${if (activities.size == 1) "y" else "ies"}")
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch PR #$prId activities: ${result.message}")
                ToolResult(data = emptyList(), summary = "Error fetching PR #$prId activities: ${result.message}", isError = true,
                    hint = "Verify the PR exists.")
            }
        }
    }

    override suspend fun getPullRequestChanges(prId: Int, repoName: String?): ToolResult<List<PrChangeData>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured. Cannot fetch PR #$prId changes.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )

        return when (val result = api.getPullRequestChanges(projectKey, repoSlug, prId)) {
            is ApiResult.Success -> {
                val changes = result.data.values.map { c ->
                    PrChangeData(path = c.path.toString, changeType = c.type, srcPath = c.srcPath?.toString)
                }
                ToolResult.success(changes, "PR #$prId has ${changes.size} changed file(s)")
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch PR #$prId changes: ${result.message}")
                ToolResult(data = emptyList(), summary = "Error fetching PR #$prId changes: ${result.message}", isError = true,
                    hint = "Verify the PR exists.")
            }
        }
    }

    override suspend fun getPullRequestDiff(prId: Int, repoName: String?): ToolResult<String> {
        val api = client ?: return ToolResult(
            data = "", summary = "Bitbucket not configured. Cannot fetch PR #$prId diff.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = "", summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )

        return when (val result = api.getPullRequestDiff(projectKey, repoSlug, prId)) {
            is ApiResult.Success -> {
                ToolResult.success(result.data, "PR #$prId diff fetched (${result.data.length} chars)")
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch PR #$prId diff: ${result.message}")
                ToolResult(data = "", summary = "Error fetching PR #$prId diff: ${result.message}", isError = true,
                    hint = "Verify the PR exists.")
            }
        }
    }

    // --- Build status ---

    override suspend fun getBuildStatuses(commitId: String, repoName: String?): ToolResult<List<BuildStatusData>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured. Cannot fetch build statuses.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )

        return when (val result = api.getBuildStatuses(commitId)) {
            is ApiResult.Success -> {
                val statuses = result.data.map { s ->
                    BuildStatusData(state = s.state, name = s.name ?: s.key, url = s.url, key = s.key)
                }
                ToolResult.success(statuses, "Commit ${commitId.take(8)} has ${statuses.size} build status(es)")
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch build statuses for $commitId: ${result.message}")
                ToolResult(data = emptyList(), summary = "Error fetching build statuses: ${result.message}", isError = true,
                    hint = "Check Bitbucket connection in Settings.")
            }
        }
    }

    // --- PR actions ---

    override suspend fun approvePullRequest(prId: Int, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot approve PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        return when (val result = api.approvePullRequest(projectKey, repoSlug, prId)) {
            is ApiResult.Success -> ToolResult.success(Unit, "PR #$prId approved")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to approve PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error approving PR #$prId: ${result.message}", isError = true,
                    hint = "You may already have approved this PR or may not be a reviewer.")
            }
        }
    }

    override suspend fun unapprovePullRequest(prId: Int, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot unapprove PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        return when (val result = api.unapprovePullRequest(projectKey, repoSlug, prId)) {
            is ApiResult.Success -> ToolResult.success(Unit, "PR #$prId approval removed")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to unapprove PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error removing approval from PR #$prId: ${result.message}", isError = true,
                    hint = "Check Bitbucket connection in Settings.")
            }
        }
    }

    /**
     * Agent entry point for merging a PR. Routes through [PrActionService.merge] for the
     * primary repo so [BitbucketBranchClient.mergePullRequestWithRetry]'s built-in
     * fetch-fresh-version + 409-retry logic is not bypassed (ARC-5 fix — mirrors the
     * [addReviewer] / [updatePrTitle] routing pattern). For non-primary repos, delegates
     * to [mergePullRequestForAgent] which calls [mergePullRequestWithRetry] directly.
     */
    override suspend fun mergePullRequest(
        prId: Int,
        strategy: String?,
        deleteSourceBranch: Boolean,
        commitMessage: String?,
        repoName: String?
    ): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot merge PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        if (!isPrimaryRepo(projectKey, repoSlug)) {
            return mergePullRequestForAgent(api, prId, projectKey, repoSlug, strategy, deleteSourceBranch, commitMessage)
        }

        val result = PrActionService.getInstance(project).merge(
            prId = prId,
            strategyId = strategy,
            deleteSourceBranch = deleteSourceBranch,
            commitMessage = commitMessage,
        )
        return result.toToolResult(
            okSummary = "PR #$prId merged successfully",
            errPrefix = "Error merging PR #$prId",
            hint = "Check merge preconditions with checkMergeStatus first.",
        )
    }

    /**
     * Non-primary-repo fallback for [mergePullRequest]: calls
     * [BitbucketBranchClient.mergePullRequestWithRetry] directly with the resolved coords,
     * getting the same fetch-fresh-version + 409-retry that [PrActionService.merge] provides
     * for the primary repo.
     */
    private suspend fun mergePullRequestForAgent(
        api: BitbucketBranchClient,
        prId: Int,
        projectKey: String,
        repoSlug: String,
        strategy: String?,
        deleteSourceBranch: Boolean,
        commitMessage: String?,
    ): ToolResult<Unit> {
        return when (val result = api.mergePullRequestWithRetry(
            repo = com.workflow.orchestrator.core.bitbucket.RepoCoords(projectKey, repoSlug),
            prId = prId,
            strategyId = strategy,
            deleteSourceBranch = deleteSourceBranch,
            commitMessage = commitMessage,
        )) {
            is ApiResult.Success -> ToolResult.success(Unit, "PR #$prId merged successfully")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to merge PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error merging PR #$prId: ${result.message}", isError = true,
                    hint = "Check merge preconditions with checkMergeStatus first.")
            }
        }
    }

    /**
     * Agent entry point for declining a PR. Routes through [PrActionService.decline] for
     * the primary repo so the 409-retry logic (fetch-fresh-version → POST → refetch-on-409)
     * is not bypassed (ARC-1 / COR-3 fix — mirrors the [addReviewer] / [updatePrTitle]
     * routing pattern). For non-primary repos, uses a private helper that calls the client's
     * decline endpoint directly with the freshly-fetched version.
     */
    override suspend fun declinePullRequest(prId: Int, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot decline PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        if (!isPrimaryRepo(projectKey, repoSlug)) {
            return declinePullRequestForAgent(api, prId, projectKey, repoSlug)
        }

        val result = PrActionService.getInstance(project).decline(prId)
        return result.toToolResult(
            okSummary = "PR #$prId declined",
            errPrefix = "Error declining PR #$prId",
            hint = "The PR may already be declined or merged.",
        )
    }

    /**
     * Non-primary-repo fallback for [declinePullRequest]: fetches the current version and
     * posts the decline. Unlike the primary-repo path (which routes through
     * [PrActionService.decline] and its 409-retry), this path does a single attempt since
     * there is no cached version to invalidate. Concurrent edits are unlikely for the decline
     * case, but the GET-then-POST pattern is still safer than using a stale version.
     */
    private suspend fun declinePullRequestForAgent(
        api: BitbucketBranchClient,
        prId: Int,
        projectKey: String,
        repoSlug: String,
    ): ToolResult<Unit> {
        val currentPr = api.getPullRequestDetail(projectKey, repoSlug, prId)
        val prDetail = when (currentPr) {
            is ApiResult.Success -> currentPr.data
            is ApiResult.Error -> return ToolResult(data = Unit,
                summary = "Error fetching PR #$prId for decline: ${currentPr.message}", isError = true,
                hint = "Verify the PR exists.")
        }
        return when (val result = api.declinePullRequest(projectKey, repoSlug, prId, prDetail.version)) {
            is ApiResult.Success -> ToolResult.success(Unit, "PR #$prId declined")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to decline PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error declining PR #$prId: ${result.message}", isError = true,
                    hint = "The PR may already be declined or merged.")
            }
        }
    }

    /**
     * Agent entry point for updating a PR description. Routes through
     * [PrActionService.updateDescription] for the primary repo (which uses
     * [BitbucketBranchClient.modifyPullRequest] with built-in 409 retry) and falls back
     * to [modifyPullRequestForAgent] for non-primary repos — same pattern as
     * [addReviewer] / [updatePrTitle] (ARC-1 / COR-3 fix).
     */
    override suspend fun updatePrDescription(prId: Int, description: String, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot update PR #$prId description.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        if (!isPrimaryRepo(projectKey, repoSlug)) {
            return modifyPullRequestForAgent(api, prId, projectKey, repoSlug, "update description") { current ->
                updateDescriptionMutator(current, description)
            }
        }

        val result = PrActionService.getInstance(project).updateDescription(prId, description)
        return result.toToolResult(
            okSummary = "PR #$prId description updated",
            errPrefix = "Error updating PR description",
            hint = "Check Bitbucket connection in Settings.",
        )
    }

    override suspend fun addPrComment(prId: Int, text: String, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot add comment to PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        return when (val result = api.addPullRequestComment(projectKey, repoSlug, prId, text)) {
            is ApiResult.Success -> ToolResult.success(Unit, "Comment added to PR #$prId (comment #${result.data.id})")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to add comment to PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error adding comment to PR #$prId: ${result.message}", isError = true,
                    hint = "Verify the PR exists.")
            }
        }
    }

    override suspend fun checkMergeStatus(prId: Int, repoName: String?): ToolResult<MergeStatusData> {
        val api = client ?: return ToolResult(
            data = MergeStatusData(canMerge = false, conflicted = false, vetoes = emptyList()),
            summary = "Bitbucket not configured. Cannot check merge status for PR #$prId.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = MergeStatusData(canMerge = false, conflicted = false, vetoes = emptyList()),
            summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )

        return when (val result = api.getMergeStatus(projectKey, repoSlug, prId)) {
            is ApiResult.Success -> {
                val status = result.data
                val data = MergeStatusData(
                    canMerge = status.canMerge,
                    conflicted = status.conflicted,
                    vetoes = status.vetoes.map { v ->
                        if (v.detailedMessage.isNotBlank()) "${v.summaryMessage}: ${v.detailedMessage}"
                        else v.summaryMessage
                    }
                )
                ToolResult.success(data, buildString {
                    append("PR #$prId merge status: ")
                    append(if (data.canMerge) "CAN merge" else "CANNOT merge")
                    if (data.conflicted) append(" (CONFLICTED)")
                    if (data.vetoes.isNotEmpty()) append("\nVetoes: ${data.vetoes.joinToString("; ")}")
                })
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to check merge status for PR #$prId: ${result.message}")
                ToolResult(data = MergeStatusData(canMerge = false, conflicted = false, vetoes = emptyList()),
                    summary = "Error checking merge status for PR #$prId: ${result.message}", isError = true,
                    hint = "Verify the PR exists.")
            }
        }
    }

    /**
     * Agent entry point for removing a reviewer. See [addReviewer] for the
     * single-repo / multi-repo routing rationale.
     */
    override suspend fun removeReviewer(prId: Int, username: String, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot remove reviewer from PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        if (!isPrimaryRepo(projectKey, repoSlug)) {
            // Pre-check (single GET) so the "not a reviewer" path stays a friendly
            // VALIDATION_ERROR rather than a no-op PUT.
            val pre = api.getPullRequestDetail(projectKey, repoSlug, prId)
            if (pre is ApiResult.Success && pre.data.reviewers.none { it.user.name == username }) {
                return ToolResult(data = Unit, summary = "'$username' is not a reviewer on PR #$prId.",
                    isError = true, hint = "Check the username is correct.")
            }
            return modifyPullRequestForAgent(api, prId, projectKey, repoSlug, "remove reviewer '$username'") { current ->
                BitbucketPrUpdateRequest(
                    title = current.title,
                    description = current.description ?: "",
                    version = current.version,
                    reviewers = current.reviewers
                        .filter { it.user.name != username }
                        .map { BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name)) },
                )
            }
        }

        val result = PrActionService.getInstance(project).removeReviewer(prId, username)
        return result.toToolResult(
            okSummary = "Reviewer '$username' removed from PR #$prId",
            errPrefix = "Error removing reviewer",
            hint = "Verify the username is correct.",
        )
    }

    // --- Private helpers ---

    private fun com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail.toPullRequestData(): PullRequestData =
        PullRequestData(
            id = id, title = title, state = state,
            fromBranch = fromRef?.displayId ?: "",
            toBranch = toRef?.displayId ?: "",
            link = links?.self?.firstOrNull()?.href ?: "",
            authorName = author?.user?.displayName ?: author?.user?.name
        )

    private fun com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail.toPullRequestDetailData(): PullRequestDetailData =
        PullRequestDetailData(
            id = id, title = title, description = description, state = state,
            fromBranch = fromRef?.displayId ?: "",
            toBranch = toRef?.displayId ?: "",
            authorName = author?.user?.displayName ?: author?.user?.name,
            reviewers = reviewers.map { r ->
                ReviewerData(
                    username = r.user.name,
                    displayName = r.user.displayName.ifBlank { r.user.name },
                    approved = r.approved,
                    status = r.status
                )
            },
            createdDate = createdDate, updatedDate = updatedDate, version = version,
            toRefLatestCommit = toRef?.latestCommit
        )

    private fun emptyPrDetail(prId: Int) = PullRequestDetailData(
        id = prId, title = "", description = null, state = "ERROR",
        fromBranch = "", toBranch = "", authorName = null,
        reviewers = emptyList(), createdDate = 0, updatedDate = 0, version = 0
    )

    private fun notConfiguredError(detail: String): ToolResult<Unit> = ToolResult(
        data = Unit, summary = "Bitbucket not configured. $detail",
        isError = true, hint = "Set up Bitbucket connection in Settings > Tools > Workflow Orchestrator > General."
    )

    private fun repoNotConfiguredError(): ToolResult<Unit> = ToolResult(
        data = Unit, summary = "Bitbucket project/repo not configured.",
        isError = true, hint = "Set Bitbucket project key and repo slug in Settings."
    )

    /**
     * True iff the given coords match the project-scoped settings that
     * [PrActionService] uses internally. The agent path can target a non-primary
     * repo via `repoName`; PrActionService is single-repo, so we route mismatched
     * coords through [modifyPullRequestForAgent] instead. (PR 6 of the 2026-05-07
     * write-ops fix plan.)
     */
    private fun isPrimaryRepo(projectKey: String, repoSlug: String): Boolean {
        val s = settings.state
        return s.bitbucketProjectKey == projectKey && s.bitbucketRepoSlug == repoSlug
    }

    /**
     * Multi-repo agent fallback that talks to [BitbucketBranchClient.modifyPullRequest]
     * directly with the agent-supplied [projectKey] / [repoSlug]. Used when
     * [PrActionService] (which is settings-scoped to the primary repo) cannot
     * be delegated to without leaking repo state.
     */
    private suspend fun modifyPullRequestForAgent(
        api: BitbucketBranchClient,
        prId: Int,
        projectKey: String,
        repoSlug: String,
        opLabel: String,
        mutate: (com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail) -> BitbucketPrUpdateRequest,
    ): ToolResult<Unit> {
        val result = api.modifyPullRequest(
            repo = com.workflow.orchestrator.core.bitbucket.RepoCoords(projectKey, repoSlug),
            prId = prId,
            mutate = mutate,
        )
        return when (result) {
            is ApiResult.Success -> ToolResult.success(Unit, "$opLabel succeeded on PR #$prId")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] $opLabel failed on PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error: $opLabel — ${result.message}", isError = true,
                    hint = "Check Bitbucket connection or refresh the PR and retry.")
            }
        }
    }

    private fun ApiResult<Unit>.toToolResult(
        okSummary: String,
        errPrefix: String,
        hint: String,
    ): ToolResult<Unit> = when (this) {
        is ApiResult.Success -> ToolResult.success(Unit, okSummary)
        is ApiResult.Error -> ToolResult(data = Unit, summary = "$errPrefix: $message", isError = true, hint = hint)
    }

    // --- PR comments ---

    override suspend fun listPrComments(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        onlyOpen: Boolean,
        onlyInline: Boolean,
    ): ToolResult<List<PrComment>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured. Cannot fetch PR #$prId comments.",
            isError = true, hint = "Set up Bitbucket connection in Settings > Tools > Workflow Orchestrator > General."
        )

        // Paginate via isLastPage / nextPageStart to handle PRs with large activity timelines.
        // BitbucketBranchClient.listPrComments delegates to getPullRequestActivities (20-page cap
        // per call). When that cap is hit, isLastPage=false and nextPageStart is set. We loop
        // up to 50 pages at the service level to collect all comments.
        val maxPages = 50
        val allComments = mutableListOf<BitbucketPrCommentResponse>()
        var cursor = 0
        var pages = 0
        while (pages < maxPages) {
            when (val result = api.listPrComments(projectKey, repoSlug, prId, start = cursor)) {
                is ApiResult.Error -> {
                    log.warn("[BitbucketService] Failed to list PR #$prId comments (page $pages): ${result.message}")
                    return ToolResult(data = emptyList(), summary = "listPrComments failed: ${result.message}", isError = true,
                        hint = "Verify the PR exists and Bitbucket connection is configured.")
                }
                is ApiResult.Success -> {
                    allComments += result.data.values
                    if (result.data.isLastPage || result.data.nextPageStart == null) break
                    cursor = result.data.nextPageStart!!
                    pages++
                }
            }
        }
        if (pages >= maxPages) {
            log.warn("[BitbucketService] PR #$prId comments truncated at $maxPages pages (${allComments.size} collected)")
        }

        var mapped = allComments.map { it.toPrComment() }
        if (onlyOpen) mapped = mapped.filter { it.state == PrCommentState.OPEN }
        if (onlyInline) mapped = mapped.filter { it.anchor != null }
        return ToolResult.success(mapped, "${mapped.size} PR comment(s) on PR #$prId")
    }

    override suspend fun getPrComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
    ): ToolResult<PrComment> {
        val api = client ?: return ToolResult(
            data = PrComment(id = "", version = 0, text = "", author = emptyCommentAuthor(),
                createdDate = 0, updatedDate = 0, state = PrCommentState.OPEN, severity = PrCommentSeverity.NORMAL),
            summary = "Bitbucket not configured. Cannot fetch comment $commentId on PR #$prId.",
            isError = true, hint = "Set up Bitbucket connection in Settings > Tools > Workflow Orchestrator > General."
        )
        return when (val result = api.getPrComment(projectKey, repoSlug, prId, commentId)) {
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch comment $commentId on PR #$prId: ${result.message}")
                ToolResult(
                    data = PrComment(id = "", version = 0, text = "", author = emptyCommentAuthor(),
                        createdDate = 0, updatedDate = 0, state = PrCommentState.OPEN, severity = PrCommentSeverity.NORMAL),
                    summary = "getPrComment failed: ${result.message}", isError = true,
                    hint = "Verify the comment ID and PR exist."
                )
            }
            is ApiResult.Success -> ToolResult.success(result.data.toPrComment(), "Comment ${result.data.id} on PR #$prId")
        }
    }

    override suspend fun editPrComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
        text: String,
        expectedVersion: Int,
    ): ToolResult<PrComment> {
        val api = client ?: return ToolResult(
            data = PrComment(id = "", version = 0, text = "", author = emptyCommentAuthor(),
                createdDate = 0, updatedDate = 0, state = PrCommentState.OPEN, severity = PrCommentSeverity.NORMAL),
            summary = "Bitbucket not configured. Cannot edit comment $commentId on PR #$prId.",
            isError = true, hint = "Set up Bitbucket connection in Settings > Tools > Workflow Orchestrator > General."
        )
        return when (val result = api.editPrComment(projectKey, repoSlug, prId, commentId, text, expectedVersion)) {
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to edit comment $commentId on PR #$prId: ${result.message}")
                ToolResult(
                    data = PrComment(id = "", version = 0, text = "", author = emptyCommentAuthor(),
                        createdDate = 0, updatedDate = 0, state = PrCommentState.OPEN, severity = PrCommentSeverity.NORMAL),
                    summary = result.message, isError = true,
                    hint = "The version may be stale — re-fetch the comment and retry."
                )
            }
            is ApiResult.Success -> ToolResult.success(result.data.toPrComment(), "Comment $commentId updated on PR #$prId")
        }
    }

    override suspend fun deletePrComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
        expectedVersion: Int,
    ): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot delete comment $commentId on PR #$prId.")
        return when (val result = api.deletePrComment(projectKey, repoSlug, prId, commentId, expectedVersion)) {
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to delete comment $commentId on PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = result.message, isError = true,
                    hint = "The version may be stale — re-fetch the comment and retry.")
            }
            is ApiResult.Success -> ToolResult.success(Unit, "Comment $commentId deleted from PR #$prId")
        }
    }

    override suspend fun resolvePrComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
    ): ToolResult<PrComment> {
        val api = client ?: return ToolResult(
            data = PrComment(id = "", version = 0, text = "", author = emptyCommentAuthor(),
                createdDate = 0, updatedDate = 0, state = PrCommentState.OPEN, severity = PrCommentSeverity.NORMAL),
            summary = "Bitbucket not configured. Cannot resolve comment $commentId on PR #$prId.",
            isError = true, hint = "Set up Bitbucket connection in Settings > Tools > Workflow Orchestrator > General."
        )
        return when (val result = api.resolvePrComment(projectKey, repoSlug, prId, commentId)) {
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to resolve comment $commentId on PR #$prId: ${result.message}")
                ToolResult(
                    data = PrComment(id = "", version = 0, text = "", author = emptyCommentAuthor(),
                        createdDate = 0, updatedDate = 0, state = PrCommentState.OPEN, severity = PrCommentSeverity.NORMAL),
                    summary = result.message, isError = true,
                    hint = "Verify the comment exists and is in an open state.")
            }
            is ApiResult.Success -> ToolResult.success(result.data.toPrComment(), "Comment $commentId resolved on PR #$prId")
        }
    }

    override suspend fun reopenPrComment(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
    ): ToolResult<PrComment> {
        val api = client ?: return ToolResult(
            data = PrComment(id = "", version = 0, text = "", author = emptyCommentAuthor(),
                createdDate = 0, updatedDate = 0, state = PrCommentState.OPEN, severity = PrCommentSeverity.NORMAL),
            summary = "Bitbucket not configured. Cannot reopen comment $commentId on PR #$prId.",
            isError = true, hint = "Set up Bitbucket connection in Settings > Tools > Workflow Orchestrator > General."
        )
        return when (val result = api.reopenPrComment(projectKey, repoSlug, prId, commentId)) {
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to reopen comment $commentId on PR #$prId: ${result.message}")
                ToolResult(
                    data = PrComment(id = "", version = 0, text = "", author = emptyCommentAuthor(),
                        createdDate = 0, updatedDate = 0, state = PrCommentState.OPEN, severity = PrCommentSeverity.NORMAL),
                    summary = result.message, isError = true,
                    hint = "Verify the comment exists and is in a resolved state.")
            }
            is ApiResult.Success -> ToolResult.success(result.data.toPrComment(), "Comment $commentId reopened on PR #$prId")
        }
    }

    private fun emptyCommentAuthor() = PrCommentAuthor(name = "", displayName = "")

    // --- 2026-05-07 audit additions (recommendations doc §3, §4) ---

    override suspend fun getBlockerCommentsCount(prId: Int, repoName: String?): ToolResult<Int> {
        val api = client ?: return ToolResult(
            data = 0, summary = "Bitbucket not configured.", isError = true,
            hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = 0, summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )
        return when (val result = api.getBlockerComments(projectKey, repoSlug, prId, countOnly = true)) {
            is ApiResult.Success -> ToolResult.success(result.data.effectiveCount, "PR #$prId has ${result.data.effectiveCount} blocker comment(s)")
            is ApiResult.Error -> ToolResult(
                data = 0, summary = "Error fetching blocker count: ${result.message}", isError = true,
                hint = "Verify the PR exists."
            )
        }
    }

    override suspend fun getPullRequestParticipants(prId: Int, repoName: String?): ToolResult<List<ParticipantData>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured.", isError = true,
            hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )
        return when (val result = api.getPullRequestParticipants(projectKey, repoSlug, prId)) {
            is ApiResult.Success -> {
                val participants = result.data.values.map { p ->
                    ParticipantData(
                        username = p.user.name,
                        displayName = p.user.displayName.ifBlank { p.user.name },
                        role = p.role,
                        approved = p.approved,
                        status = p.status,
                        lastReviewedCommit = p.lastReviewedCommit,
                    )
                }
                ToolResult.success(participants, "PR #$prId has ${participants.size} participant(s)")
            }
            is ApiResult.Error -> ToolResult(
                data = emptyList(), summary = "Error fetching participants: ${result.message}", isError = true,
                hint = "Verify the PR exists."
            )
        }
    }

    override suspend fun getPullRequestsForCommit(sha: String, repoName: String?): ToolResult<List<PullRequestData>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured.", isError = true,
            hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )
        return when (val result = api.getCommitPullRequests(projectKey, repoSlug, sha)) {
            is ApiResult.Success -> {
                val prs = result.data.values.map { it.toPullRequestData() }
                ToolResult.success(prs, "Commit ${sha.take(8)} is in ${prs.size} PR(s)")
            }
            is ApiResult.Error -> ToolResult(
                data = emptyList(), summary = "Error reverse-looking-up PRs: ${result.message}", isError = true,
                hint = "Verify the commit and repo configuration."
            )
        }
    }

    override suspend fun getCommitBuildStats(sha: String): ToolResult<BuildStatsData> {
        val api = client ?: return ToolResult(
            data = BuildStatsData(0, 0, 0), summary = "Bitbucket not configured.", isError = true,
            hint = "Set up Bitbucket connection in Settings."
        )
        return when (val result = api.getCommitBuildStats(sha)) {
            is ApiResult.Success -> {
                val stats = BuildStatsData(
                    successful = result.data.successful,
                    failed = result.data.failed,
                    inProgress = result.data.inProgress,
                )
                ToolResult.success(
                    stats,
                    "Commit ${sha.take(8)} builds: ${stats.successful} ok, ${stats.failed} failed, ${stats.inProgress} running"
                )
            }
            is ApiResult.Error -> ToolResult(
                data = BuildStatsData(0, 0, 0),
                summary = "Error fetching build stats: ${result.message}", isError = true,
                hint = "Verify the commit SHA."
            )
        }
    }

    override suspend fun getLinkedJiraIssues(prId: Int, repoName: String?): ToolResult<List<JiraIssueRef>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured.", isError = true,
            hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )
        return when (val result = api.getLinkedJiraIssues(projectKey, repoSlug, prId)) {
            is ApiResult.Success -> {
                val refs = result.data.map { JiraIssueRef(it.key, it.url) }
                ToolResult.success(refs, "PR #$prId has ${refs.size} linked Jira issue(s)")
            }
            is ApiResult.Error -> ToolResult(
                data = emptyList(), summary = "Error fetching linked Jira issues: ${result.message}", isError = true,
                hint = "Verify the PR exists; ignore if Jira-link plugin is not installed."
            )
        }
    }

    override suspend fun getRequiredBuilds(repoName: String?): ToolResult<List<RequiredBuildsCondition>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured.", isError = true,
            hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )
        return when (val result = api.getRequiredBuilds(projectKey, repoSlug)) {
            is ApiResult.Success -> {
                val conditions = result.data.values.map { c ->
                    RequiredBuildsCondition(id = c.id, buildParentKeys = c.buildParentKeys)
                }
                ToolResult.success(conditions, "$projectKey/$repoSlug has ${conditions.size} required-build condition(s)")
            }
            is ApiResult.Error -> ToolResult(
                data = emptyList(), summary = "Error fetching required builds: ${result.message}", isError = true,
                hint = "Verify repo config; required-builds plugin may not be installed."
            )
        }
    }

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

}

private fun BitbucketPrCommentResponse.toPrComment(): PrComment = PrComment(
    id = id.toString(),
    version = version,
    text = text,
    author = PrCommentAuthor(
        name = author.name,
        displayName = author.displayName,
        emailAddress = author.emailAddress,
        avatarUrl = author.avatarUrl,
    ),
    createdDate = createdDate,
    updatedDate = updatedDate,
    anchor = anchor?.let { a ->
        PrCommentAnchor(
            path = a.path,
            srcPath = a.srcPath,
            line = a.line,
            lineType = a.lineType?.let { runCatching { PrCommentLineType.valueOf(it) }.getOrNull() },
            fileType = a.fileType?.let { runCatching { PrCommentFileType.valueOf(it) }.getOrNull() },
            fromHash = a.fromHash,
            toHash = a.toHash,
        )
    },
    state = runCatching { PrCommentState.valueOf(state) }.getOrDefault(PrCommentState.OPEN),
    severity = runCatching { PrCommentSeverity.valueOf(severity) }.getOrDefault(PrCommentSeverity.NORMAL),
    replies = comments.map { it.toPrComment() },
    permittedOperations = permittedOperations?.let {
        PrCommentPermittedOps(
            editable = it.editable,
            deletable = it.deletable,
            transitionable = it.transitionable,
        )
    },
)
