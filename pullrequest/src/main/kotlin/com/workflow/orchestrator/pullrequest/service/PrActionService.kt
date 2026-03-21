package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketMergeStatus
import com.workflow.orchestrator.core.bitbucket.BitbucketMergeStrategy
import com.workflow.orchestrator.core.bitbucket.BitbucketPrReviewerRef
import com.workflow.orchestrator.core.bitbucket.BitbucketPrUpdateRequest
import com.workflow.orchestrator.core.bitbucket.BitbucketReviewerUser
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings

/**
 * Project-level service for PR actions: approve, unapprove, merge, decline,
 * update description, and add comments.
 *
 * Delegates all HTTP calls to BitbucketBranchClient from :core.
 * Emits WorkflowEvents on success for cross-module communication.
 */
@Service(Service.Level.PROJECT)
class PrActionService(private val project: Project) {

    private val log = Logger.getInstance(PrActionService::class.java)

    companion object {
        fun getInstance(project: Project): PrActionService {
            return project.getService(PrActionService::class.java)
        }
    }

    private val credentialStore = CredentialStore()
    @Volatile private var cachedClient: BitbucketBranchClient? = null
    @Volatile private var cachedBaseUrl: String? = null

    private fun getClient(): BitbucketBranchClient? {
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

    private fun projectKey(): String =
        PluginSettings.getInstance(project).state.bitbucketProjectKey.orEmpty()

    private fun repoSlug(): String =
        PluginSettings.getInstance(project).state.bitbucketRepoSlug.orEmpty()

    private fun isConfigured(): Boolean =
        projectKey().isNotBlank() && repoSlug().isNotBlank()

    /**
     * Approve a pull request.
     */
    suspend fun approve(prId: Int): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Approving PR #$prId")
        return when (val result = client.approvePullRequest(projectKey(), repoSlug(), prId)) {
            is ApiResult.Success -> {
                log.info("[PR:Action] PR #$prId approved successfully")
                val eventBus = project.getService(EventBus::class.java)
                eventBus.emit(WorkflowEvent.PullRequestApproved(
                    prId = prId,
                    byUser = result.data.user.name
                ))
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to approve PR #$prId: ${result.message}")
                ApiResult.Error(result.type, result.message)
            }
        }
    }

    /**
     * Remove approval from a pull request.
     */
    suspend fun unapprove(prId: Int): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Removing approval from PR #$prId")
        return when (val result = client.unapprovePullRequest(projectKey(), repoSlug(), prId)) {
            is ApiResult.Success -> {
                log.info("[PR:Action] PR #$prId approval removed")
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to unapprove PR #$prId: ${result.message}")
                ApiResult.Error(result.type, result.message)
            }
        }
    }

    /**
     * Check merge preconditions for a pull request.
     * Returns null if the check fails (e.g., network error).
     */
    suspend fun checkMergeStatus(prId: Int): BitbucketMergeStatus? {
        val client = getClient() ?: return null
        if (!isConfigured()) return null

        log.info("[PR:Action] Checking merge status for PR #$prId")
        return when (val result = client.getMergeStatus(projectKey(), repoSlug(), prId)) {
            is ApiResult.Success -> {
                log.info("[PR:Action] Merge status for PR #$prId: canMerge=${result.data.canMerge}, vetoes=${result.data.vetoes.size}")
                result.data
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to check merge status for PR #$prId: ${result.message}")
                null
            }
        }
    }

    /**
     * Get available merge strategies for the configured repository.
     * Returns only enabled strategies.
     */
    suspend fun getMergeStrategies(): List<BitbucketMergeStrategy> {
        val client = getClient() ?: return emptyList()
        if (!isConfigured()) return emptyList()

        log.info("[PR:Action] Fetching merge strategies")
        return when (val result = client.getMergeStrategies(projectKey(), repoSlug())) {
            is ApiResult.Success -> {
                val enabled = result.data.strategies.filter { it.enabled }
                log.info("[PR:Action] Found ${enabled.size} enabled merge strategies")
                enabled
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to fetch merge strategies: ${result.message}")
                emptyList()
            }
        }
    }

    /**
     * Merge a pull request with optional strategy and delete-source-branch.
     */
    suspend fun merge(
        prId: Int,
        version: Int,
        strategyId: String? = null,
        deleteSourceBranch: Boolean = false,
        commitMessage: String? = null
    ): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Merging PR #$prId (version=$version, strategy=$strategyId, deleteBranch=$deleteSourceBranch)")
        return when (val result = client.mergePullRequest(
            projectKey(), repoSlug(), prId, version,
            strategyId = strategyId,
            deleteSourceBranch = deleteSourceBranch,
            commitMessage = commitMessage
        )) {
            is ApiResult.Success -> {
                log.info("[PR:Action] PR #$prId merged successfully")
                val eventBus = project.getService(EventBus::class.java)
                eventBus.emit(WorkflowEvent.PullRequestMerged(prId = prId))
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to merge PR #$prId: ${result.message}")
                ApiResult.Error(result.type, result.message)
            }
        }
    }

    /**
     * Decline a pull request.
     */
    suspend fun decline(prId: Int, version: Int): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Declining PR #$prId (version=$version)")
        return when (val result = client.declinePullRequest(projectKey(), repoSlug(), prId, version)) {
            is ApiResult.Success -> {
                log.info("[PR:Action] PR #$prId declined")
                val eventBus = project.getService(EventBus::class.java)
                eventBus.emit(WorkflowEvent.PullRequestDeclined(prId = prId))
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to decline PR #$prId: ${result.message}")
                ApiResult.Error(result.type, result.message)
            }
        }
    }

    /**
     * Update a pull request description.
     */
    suspend fun updateDescription(prId: Int, description: String, version: Int): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Updating description for PR #$prId — fetching current PR to preserve title/reviewers")

        // Fetch current PR to preserve title and reviewers (Bitbucket PUT replaces the entire PR)
        val currentPr = client.getPullRequestDetail(projectKey(), repoSlug(), prId)
        val existingPr = (currentPr as? ApiResult.Success)?.data

        val updateRequest = BitbucketPrUpdateRequest(
            title = existingPr?.title ?: "",
            description = description,
            version = existingPr?.version ?: version,
            reviewers = existingPr?.reviewers?.map {
                BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name))
            } ?: emptyList()
        )
        return when (val result = client.updatePullRequest(projectKey(), repoSlug(), prId, updateRequest)) {
            is ApiResult.Success -> {
                log.info("[PR:Action] PR #$prId description updated")
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to update PR #$prId: ${result.message}")
                ApiResult.Error(result.type, result.message)
            }
        }
    }

    /**
     * Add a comment to a pull request.
     */
    suspend fun addComment(prId: Int, text: String): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Adding comment to PR #$prId")
        return when (val result = client.addPullRequestComment(projectKey(), repoSlug(), prId, text)) {
            is ApiResult.Success -> {
                log.info("[PR:Action] Comment added to PR #$prId")
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to add comment to PR #$prId: ${result.message}")
                ApiResult.Error(result.type, result.message)
            }
        }
    }

    /**
     * Add an inline comment to a specific file/line in a pull request.
     */
    suspend fun addInlineComment(
        prId: Int, filePath: String, lineNumber: Int, lineType: String, text: String
    ): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Adding inline comment to PR #$prId at $filePath:$lineNumber")
        return when (val result = client.addInlineComment(projectKey(), repoSlug(), prId, filePath, lineNumber, lineType, text)) {
            is ApiResult.Success -> {
                log.info("[PR:Action] Inline comment added to PR #$prId at $filePath:$lineNumber")
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to add inline comment to PR #$prId: ${result.message}")
                ApiResult.Error(result.type, result.message)
            }
        }
    }

    /**
     * Add a reviewer to a pull request.
     * Fetches current PR state, adds the user to reviewers, and PUT updates.
     */
    suspend fun addReviewer(prId: Int, username: String): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Adding reviewer '$username' to PR #$prId")

        val currentPr = client.getPullRequestDetail(projectKey(), repoSlug(), prId)
        val existingPr = when (currentPr) {
            is ApiResult.Success -> currentPr.data
            is ApiResult.Error -> return ApiResult.Error(currentPr.type, "Failed to fetch PR: ${currentPr.message}")
        }

        // Check if already a reviewer
        if (existingPr.reviewers.any { it.user.name == username }) {
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "'$username' is already a reviewer")
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
        return when (val result = client.updatePullRequest(projectKey(), repoSlug(), prId, updateRequest)) {
            is ApiResult.Success -> {
                log.info("[PR:Action] Reviewer '$username' added to PR #$prId")
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to add reviewer '$username' to PR #$prId: ${result.message}")
                ApiResult.Error(result.type, result.message)
            }
        }
    }

    /**
     * Remove a reviewer from a pull request.
     * Fetches current PR state, removes the user from reviewers, and PUT updates.
     */
    suspend fun removeReviewer(prId: Int, username: String): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Removing reviewer '$username' from PR #$prId")

        val currentPr = client.getPullRequestDetail(projectKey(), repoSlug(), prId)
        val existingPr = when (currentPr) {
            is ApiResult.Success -> currentPr.data
            is ApiResult.Error -> return ApiResult.Error(currentPr.type, "Failed to fetch PR: ${currentPr.message}")
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
        return when (val result = client.updatePullRequest(projectKey(), repoSlug(), prId, updateRequest)) {
            is ApiResult.Success -> {
                log.info("[PR:Action] Reviewer '$username' removed from PR #$prId")
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to remove reviewer '$username' from PR #$prId: ${result.message}")
                ApiResult.Error(result.type, result.message)
            }
        }
    }

    /**
     * Set a reviewer's status to NEEDS_WORK on a pull request.
     */
    suspend fun setNeedsWork(prId: Int, username: String): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Setting NEEDS_WORK for '$username' on PR #$prId")
        return when (val result = client.setReviewerStatus(projectKey(), repoSlug(), prId, username, "NEEDS_WORK")) {
            is ApiResult.Success -> {
                log.info("[PR:Action] Reviewer '$username' set to NEEDS_WORK on PR #$prId")
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to set NEEDS_WORK for '$username' on PR #$prId: ${result.message}")
                ApiResult.Error(result.type, result.message)
            }
        }
    }

    /**
     * Reply to an existing comment on a pull request.
     */
    suspend fun replyToComment(prId: Int, parentCommentId: Int, text: String): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Replying to comment #$parentCommentId on PR #$prId")
        return when (val result = client.replyToComment(projectKey(), repoSlug(), prId, parentCommentId, text)) {
            is ApiResult.Success -> {
                log.info("[PR:Action] Reply added to comment #$parentCommentId on PR #$prId")
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to reply to comment #$parentCommentId on PR #$prId: ${result.message}")
                ApiResult.Error(result.type, result.message)
            }
        }
    }
}
