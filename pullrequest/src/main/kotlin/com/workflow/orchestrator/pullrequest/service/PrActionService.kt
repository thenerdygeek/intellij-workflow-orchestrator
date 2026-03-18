package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketMergeStatus
import com.workflow.orchestrator.core.bitbucket.BitbucketMergeStrategy
import com.workflow.orchestrator.core.bitbucket.BitbucketPrUpdateRequest
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

    private fun createClient(): BitbucketBranchClient? {
        val connSettings = ConnectionSettings.getInstance().state
        val bitbucketUrl = connSettings.bitbucketUrl.trimEnd('/')
        if (bitbucketUrl.isBlank()) return null

        val credentialStore = CredentialStore()
        return BitbucketBranchClient(
            baseUrl = bitbucketUrl,
            tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
        )
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
        val client = createClient()
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
        val client = createClient()
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
        val client = createClient() ?: return null
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
        val client = createClient() ?: return emptyList()
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
        val client = createClient()
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
        val client = createClient()
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
        val client = createClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Updating description for PR #$prId")
        val updateRequest = BitbucketPrUpdateRequest(
            title = "",  // will be preserved by Bitbucket when empty
            description = description,
            version = version
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
        val client = createClient()
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
}
