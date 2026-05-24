package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketMergeStatus
import com.workflow.orchestrator.core.bitbucket.BitbucketMergeStrategy
import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
import com.workflow.orchestrator.core.bitbucket.BitbucketPrReviewerRef
import com.workflow.orchestrator.core.bitbucket.BitbucketPrUpdateRequest
import com.workflow.orchestrator.core.bitbucket.BitbucketReviewerUser
import com.workflow.orchestrator.core.bitbucket.RepoCoords
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
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

    private val clientCache = BitbucketBranchClientCache()

    private fun getClient(): BitbucketBranchClient? = clientCache.get()

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
     *
     * The previous `version: Int` parameter was dropped in PR 3 of the
     * 2026-05-07 write-ops fix plan — the underlying
     * [BitbucketBranchClient.mergePullRequestWithRetry] helper fetches the
     * fresh `version` itself and refetches + retries once on a 409 stale-
     * version response. Per `feedback_no_compat_shims.md` the parameter is
     * removed cleanly rather than ignored.
     */
    suspend fun merge(
        prId: Int,
        strategyId: String? = null,
        deleteSourceBranch: Boolean = false,
        commitMessage: String? = null
    ): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Merging PR #$prId (strategy=$strategyId, deleteBranch=$deleteSourceBranch)")
        return when (val result = client.mergePullRequestWithRetry(
            repo = RepoCoords(projectKey(), repoSlug()),
            prId = prId,
            strategyId = strategyId,
            deleteSourceBranch = deleteSourceBranch,
            commitMessage = commitMessage,
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
     * Decline a pull request, retrying once on 409 Conflict (stale version).
     *
     * The caller supplies a [cachedVersion] from their last-known state (e.g.
     * `currentPr?.version`). `decline` always refetches a fresh version before
     * posting the decline request, so [cachedVersion] is intentionally unused
     * in the primary attempt — passing it is optional and retained only for API
     * compatibility. On a 409 the cache is force-evicted and a second attempt
     * is made with the freshly-fetched version (mirrors the [modifyPullRequest]
     * retry pattern used by [updateTitle]).
     */
    suspend fun decline(prId: Int, cachedVersion: Int = 0): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        val proj = projectKey()
        val repo = repoSlug()

        // Attempt 1: fetch a fresh version, then POST decline.
        val first = fetchAndDecline(client, proj, repo, prId)
        if (first is ApiResult.Success) {
            log.info("[PR:Action] PR #$prId declined")
            val eventBus = project.getService(EventBus::class.java)
            eventBus.emit(WorkflowEvent.PullRequestDeclined(prId = prId))
            return ApiResult.Success(Unit)
        }

        val firstError = first as ApiResult.Error
        val is409 = firstError.type == ErrorType.VALIDATION_ERROR &&
            firstError.message.contains("version conflict", ignoreCase = true)

        if (!is409) {
            log.warn("[PR:Action] Failed to decline PR #$prId: ${firstError.message}")
            return ApiResult.Error(firstError.type, firstError.message)
        }

        // 409: the version was stale. Force-evict the cached GET so the retry
        // fetches from the network instead of replaying the same stale entry.
        log.info("[PR:Action] PR #$prId decline hit 409 — retrying once with refetched version")
        com.workflow.orchestrator.core.http.HttpResponseCache.invalidateByPrefix(
            "/rest/api/1.0/projects/$proj/repos/$repo/pull-requests/$prId"
        )

        // Attempt 2: refetch and decline again.
        return when (val second = fetchAndDecline(client, proj, repo, prId)) {
            is ApiResult.Success -> {
                log.info("[PR:Action] PR #$prId declined (retry)")
                val eventBus = project.getService(EventBus::class.java)
                eventBus.emit(WorkflowEvent.PullRequestDeclined(prId = prId))
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to decline PR #$prId after retry: ${second.message}")
                ApiResult.Error(second.type, second.message)
            }
        }
    }

    /**
     * GET the current version of [prId], then POST to the decline endpoint.
     * Returns a version-conflict error unchanged so the caller can decide to retry.
     */
    private suspend fun fetchAndDecline(
        client: BitbucketBranchClient,
        projectKey: String,
        repoSlug: String,
        prId: Int,
    ): ApiResult<Unit> {
        val current = when (val r = client.getPullRequestDetail(projectKey, repoSlug, prId)) {
            is ApiResult.Success -> r.data
            is ApiResult.Error -> return r
        }
        return when (val r = client.declinePullRequest(projectKey, repoSlug, prId, current.version)) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Error -> r
        }
    }

    /**
     * Update a pull request title.
     *
     * Routes through [BitbucketBranchClient.modifyPullRequest] so the GET-PUT
     * cycle refetches and retries once if the cached `version` is stale —
     * fixing the 409 race called out by audit P0 finding #2 (PR 3 of the
     * 2026-05-07 write-ops fix plan). The mutator preserves description and
     * reviewers because Bitbucket's PUT replaces the entire PR.
     */
    suspend fun updateTitle(prId: Int, newTitle: String): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Updating title for PR #$prId via modifyPullRequest")
        return when (val result = client.modifyPullRequest(
            repo = RepoCoords(projectKey(), repoSlug()),
            prId = prId,
        ) { current -> updateTitleMutator(current, newTitle) }) {
            is ApiResult.Success -> {
                log.info("[PR:Action] PR #$prId title updated to: $newTitle")
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                log.warn("[PR:Action] Failed to update PR #$prId title: ${result.message}")
                ApiResult.Error(result.type, result.message)
            }
        }
    }

    /**
     * Update a pull request description.
     *
     * Routes through [BitbucketBranchClient.modifyPullRequest] so the GET-PUT
     * cycle refetches and retries once if the cached `version` is stale —
     * fixing the same 409 race the audit called out for `updateTitle` /
     * `addReviewer` / `removeReviewer` in PR 3, extended to `updateDescription`
     * here as a PR 6 follow-up. The previous explicit `version` parameter is
     * dropped (per `feedback_no_compat_shims.md`) — the helper fetches the
     * fresh value itself, the same way [updateTitle] does.
     */
    suspend fun updateDescription(prId: Int, description: String): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Updating description for PR #$prId via modifyPullRequest")
        return when (val result = client.modifyPullRequest(
            repo = RepoCoords(projectKey(), repoSlug()),
            prId = prId,
        ) { current -> updateDescriptionMutator(current, description) }) {
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
     *
     * The optional [diffType] / [fromHash] / [toHash] arguments pin the comment to
     * a specific commit pair so it stays anchored to the exact code the reviewer
     * saw, even when new commits land on the PR. Pass `diffType="COMMIT"` plus
     * `toHash=PR.toRef.latestCommit` for AI / batch reviews — see
     * [com.workflow.orchestrator.core.bitbucket.InlineCommentDiffType] (audit
     * finding #7, PR 6 of the 2026-05-07 write-ops fix plan).
     */
    suspend fun addInlineComment(
        prId: Int,
        filePath: String,
        lineNumber: Int,
        lineType: String,
        text: String,
        diffType: String? = null,
        fromHash: String? = null,
        toHash: String? = null,
    ): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Adding inline comment to PR #$prId at $filePath:$lineNumber")
        return when (val result = client.addInlineComment(
            projectKey = projectKey(),
            repoSlug = repoSlug(),
            prId = prId,
            filePath = filePath,
            lineNumber = lineNumber,
            lineType = lineType,
            text = text,
            diffType = diffType,
            fromHash = fromHash,
            toHash = toHash,
        )) {
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
     *
     * Routes through [BitbucketBranchClient.modifyPullRequest] so the
     * read-modify-write cycle refetches and retries once on a 409 stale-
     * version response (audit P0 finding #2; PR 3 of the 2026-05-07 write-
     * ops fix plan). The "already a reviewer" preflight runs against the
     * fresh PR state inside the mutator so the check survives the retry.
     */
    suspend fun addReviewer(prId: Int, username: String): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Adding reviewer '$username' to PR #$prId via modifyPullRequest")
        // Pre-check against a fresh GET *outside* the mutator so we can return a
        // VALIDATION_ERROR with a clean "already a reviewer" message instead of
        // throwing a typed exception out of the mutator. The mutator itself then
        // assumes the user is absent (which the inner GET on retry re-confirms;
        // a concurrent add by someone else would surface as a 409 → STALE_VERSION
        // and we deliberately do NOT mask that as success).
        val precheck = client.getPullRequestDetail(projectKey(), repoSlug(), prId)
        if (precheck is ApiResult.Success && precheck.data.reviewers.any { it.user.name == username }) {
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "'$username' is already a reviewer")
        }

        return when (val result = client.modifyPullRequest(
            repo = RepoCoords(projectKey(), repoSlug()),
            prId = prId,
        ) { current -> addReviewerMutator(current, username) }) {
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
     *
     * Routes through [BitbucketBranchClient.modifyPullRequest] so the GET-PUT
     * cycle refetches and retries once if the cached `version` is stale (audit
     * P0 finding #2; PR 3 of the 2026-05-07 write-ops fix plan).
     */
    suspend fun removeReviewer(prId: Int, username: String): ApiResult<Unit> {
        val client = getClient()
            ?: return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket not configured")
        if (!isConfigured())
            return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Bitbucket project/repo not configured")

        log.info("[PR:Action] Removing reviewer '$username' from PR #$prId via modifyPullRequest")
        return when (val result = client.modifyPullRequest(
            repo = RepoCoords(projectKey(), repoSlug()),
            prId = prId,
        ) { current -> removeReviewerMutator(current, username) }) {
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

// --- Internal pure mutators for the modifyPullRequest helper ---
//
// Pulled out as top-level functions so they're cheaply unit-testable without
// instantiating the project-scoped [PrActionService]. Each one threads
// `current.version` through the new [BitbucketPrUpdateRequest] — this is what
// makes [BitbucketBranchClient.modifyPullRequest]'s 409 retry actually retry
// with a fresh version on the second attempt (the helper re-invokes the
// mutator on the refetched PR). All three preserve every field except the
// one being mutated, because Bitbucket DC's PUT replaces the whole PR.

internal fun updateTitleMutator(current: BitbucketPrDetail, newTitle: String): BitbucketPrUpdateRequest =
    BitbucketPrUpdateRequest(
        title = newTitle,
        description = current.description ?: "",
        version = current.version,
        reviewers = current.reviewers.map {
            BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name))
        },
    )

internal fun updateDescriptionMutator(current: BitbucketPrDetail, newDescription: String): BitbucketPrUpdateRequest =
    BitbucketPrUpdateRequest(
        title = current.title,
        description = newDescription,
        version = current.version,
        reviewers = current.reviewers.map {
            BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name))
        },
    )

internal fun addReviewerMutator(current: BitbucketPrDetail, username: String): BitbucketPrUpdateRequest {
    val existingRefs = current.reviewers.map {
        BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name))
    }
    // Idempotent on retry: if a concurrent caller already added the same user,
    // the PUT body is identical to the current state — Bitbucket accepts it
    // (PUT is set-replace, not append).
    val updatedReviewers = if (existingRefs.any { it.user.name == username }) {
        existingRefs
    } else {
        existingRefs + BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = username))
    }
    return BitbucketPrUpdateRequest(
        title = current.title,
        description = current.description ?: "",
        version = current.version,
        reviewers = updatedReviewers,
    )
}

internal fun removeReviewerMutator(current: BitbucketPrDetail, username: String): BitbucketPrUpdateRequest =
    BitbucketPrUpdateRequest(
        title = current.title,
        description = current.description ?: "",
        version = current.version,
        reviewers = current.reviewers
            .filter { it.user.name != username }
            .map { BitbucketPrReviewerRef(user = BitbucketReviewerUser(name = it.user.name)) },
    )
