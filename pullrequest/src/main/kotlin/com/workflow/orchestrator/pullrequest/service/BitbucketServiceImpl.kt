package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.bitbucket.BitbucketPrReviewerRef
import com.workflow.orchestrator.core.bitbucket.BitbucketPrUpdateRequest
import com.workflow.orchestrator.core.bitbucket.BitbucketReviewerUser
import com.workflow.orchestrator.core.model.bitbucket.*
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.ToolResult
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

    override suspend fun addInlineComment(prId: Int, filePath: String, line: Int, lineType: String, text: String, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot add inline comment to PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        return when (val result = api.addInlineComment(projectKey, repoSlug, prId, filePath, line, lineType, text)) {
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

    override suspend fun addReviewer(prId: Int, username: String, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot add reviewer to PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

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

    override suspend fun updatePrTitle(prId: Int, newTitle: String, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot update PR #$prId title.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

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

        return when (val result = api.getMyPullRequests(projectKey, repoSlug, state)) {
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

    override suspend fun getReviewingPullRequests(state: String, repoName: String?): ToolResult<List<PullRequestData>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured. Cannot fetch reviewing PRs.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )

        return when (val result = api.getReviewingPullRequests(projectKey, repoSlug, state)) {
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
                val activities = result.data.map { a ->
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

    override suspend fun mergePullRequest(
        prId: Int,
        strategy: String?,
        deleteSourceBranch: Boolean,
        commitMessage: String?,
        repoName: String?
    ): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot merge PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        // Fetch PR to get current version for optimistic locking
        val currentPr = api.getPullRequestDetail(projectKey, repoSlug, prId)
        val prDetail = when (currentPr) {
            is ApiResult.Success -> currentPr.data
            is ApiResult.Error -> return ToolResult(data = Unit,
                summary = "Error fetching PR #$prId for merge: ${currentPr.message}", isError = true,
                hint = "Verify the PR exists.")
        }

        return when (val result = api.mergePullRequest(
            projectKey, repoSlug, prId, prDetail.version,
            strategyId = strategy, deleteSourceBranch = deleteSourceBranch, commitMessage = commitMessage
        )) {
            is ApiResult.Success -> ToolResult.success(Unit, "PR #$prId merged successfully")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to merge PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error merging PR #$prId: ${result.message}", isError = true,
                    hint = "Check merge preconditions with checkMergeStatus first.")
            }
        }
    }

    override suspend fun declinePullRequest(prId: Int, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot decline PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        // Fetch PR to get current version for optimistic locking
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

    override suspend fun updatePrDescription(prId: Int, description: String, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot update PR #$prId description.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        // Fetch current PR to preserve title/reviewers (PUT replaces entire PR)
        val currentPr = api.getPullRequestDetail(projectKey, repoSlug, prId)
        val existingPr = when (currentPr) {
            is ApiResult.Success -> currentPr.data
            is ApiResult.Error -> return ToolResult(data = Unit,
                summary = "Error fetching PR #$prId: ${currentPr.message}", isError = true,
                hint = "Verify the PR exists.")
        }

        val updateRequest = BitbucketPrUpdateRequest(
            title = existingPr.title,
            description = description,
            version = existingPr.version,
            reviewers = existingPr.reviewers.map {
                BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name))
            }
        )
        return when (val result = api.updatePullRequest(projectKey, repoSlug, prId, updateRequest)) {
            is ApiResult.Success -> ToolResult.success(Unit, "PR #$prId description updated")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to update PR #$prId description: ${result.message}")
                ToolResult(data = Unit, summary = "Error updating PR description: ${result.message}", isError = true,
                    hint = "Check Bitbucket connection in Settings.")
            }
        }
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

    override suspend fun removeReviewer(prId: Int, username: String, repoName: String?): ToolResult<Unit> {
        val api = client ?: return notConfiguredError("Cannot remove reviewer from PR #$prId.")
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return repoNotConfiguredError()

        // Fetch current PR to get reviewer list
        val currentPr = api.getPullRequestDetail(projectKey, repoSlug, prId)
        val existingPr = when (currentPr) {
            is ApiResult.Success -> currentPr.data
            is ApiResult.Error -> return ToolResult(data = Unit,
                summary = "Error fetching PR #$prId: ${currentPr.message}", isError = true,
                hint = "Verify the PR exists.")
        }

        if (existingPr.reviewers.none { it.user.name == username }) {
            return ToolResult(data = Unit, summary = "'$username' is not a reviewer on PR #$prId.",
                isError = true, hint = "Check the username is correct.")
        }

        val updatedReviewers = existingPr.reviewers
            .filter { it.user.name != username }
            .map { BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name)) }

        val updateRequest = BitbucketPrUpdateRequest(
            title = existingPr.title,
            description = existingPr.description ?: "",
            version = existingPr.version,
            reviewers = updatedReviewers
        )
        return when (val result = api.updatePullRequest(projectKey, repoSlug, prId, updateRequest)) {
            is ApiResult.Success -> ToolResult.success(Unit, "Reviewer '$username' removed from PR #$prId")
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to remove reviewer '$username' from PR #$prId: ${result.message}")
                ToolResult(data = Unit, summary = "Error removing reviewer: ${result.message}", isError = true,
                    hint = "Verify the username is correct.")
            }
        }
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
            createdDate = createdDate, updatedDate = updatedDate, version = version
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
