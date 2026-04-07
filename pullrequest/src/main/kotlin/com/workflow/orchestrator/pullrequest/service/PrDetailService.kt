package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketBuildStatus
import com.workflow.orchestrator.core.bitbucket.BitbucketCommit
import com.workflow.orchestrator.core.bitbucket.BitbucketPrActivity
import com.workflow.orchestrator.core.bitbucket.BitbucketPrChange
import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings

/**
 * Project-level service for fetching detailed PR information:
 * activities (comments, approvals), file changes, and diffs.
 *
 * Delegates all HTTP calls to BitbucketBranchClient from :core.
 */
@Service(Service.Level.PROJECT)
class PrDetailService(private val project: Project) {

    private val log = Logger.getInstance(PrDetailService::class.java)

    companion object {
        fun getInstance(project: Project): PrDetailService {
            return project.getService(PrDetailService::class.java)
        }
    }

    @Volatile private var cachedClient: BitbucketBranchClient? = null
    @Volatile private var cachedBaseUrl: String? = null

    private fun getClient(): BitbucketBranchClient? {
        val url = ConnectionSettings.getInstance().state.bitbucketUrl.trimEnd('/')
        if (url.isBlank()) return null
        if (url != cachedBaseUrl || cachedClient == null) {
            cachedBaseUrl = url
            cachedClient = BitbucketBranchClient.fromConfiguredSettings()
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
     * Fetches a single PR by ID.
     */
    suspend fun getDetail(prId: Int): BitbucketPrDetail? {
        val client = getClient() ?: return null
        if (!isConfigured()) return null

        log.info("[PR:Detail] Fetching PR #$prId")
        return when (val result = client.getPullRequestDetail(projectKey(), repoSlug(), prId)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                log.warn("[PR:Detail] Failed to fetch PR #$prId: ${result.message}")
                null
            }
        }
    }

    /**
     * Fetches activities (comments, approvals, status changes) for a PR.
     */
    suspend fun getActivities(prId: Int): List<BitbucketPrActivity> {
        val client = getClient() ?: return emptyList()
        if (!isConfigured()) return emptyList()

        log.info("[PR:Detail] Fetching activities for PR #$prId")
        return when (val result = client.getPullRequestActivities(projectKey(), repoSlug(), prId)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                log.warn("[PR:Detail] Failed to fetch activities for PR #$prId: ${result.message}")
                emptyList()
            }
        }
    }

    /**
     * Fetches file changes for a PR.
     */
    suspend fun getChanges(prId: Int): List<BitbucketPrChange> {
        val client = getClient() ?: return emptyList()
        if (!isConfigured()) return emptyList()

        log.info("[PR:Detail] Fetching changes for PR #$prId")
        return when (val result = client.getPullRequestChanges(projectKey(), repoSlug(), prId)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                log.warn("[PR:Detail] Failed to fetch changes for PR #$prId: ${result.message}")
                emptyList()
            }
        }
    }

    /**
     * Fetches file content at a specific ref (commit hash or branch name).
     * Returns empty string for new/deleted files or on error.
     */
    suspend fun getFileContent(filePath: String, atRef: String): String {
        val client = getClient() ?: return ""
        if (!isConfigured()) return ""

        log.info("[PR:Detail] Fetching file content: $filePath at $atRef")
        return try {
            when (val result = client.getFileContent(projectKey(), repoSlug(), filePath, atRef)) {
                is ApiResult.Success -> result.data ?: ""
                is ApiResult.Error -> {
                    log.warn("[PR:Detail] Failed to fetch file content: ${result.message}")
                    ""
                }
            }
        } catch (e: Exception) {
            log.warn("[PR:Detail] Error fetching file content: ${e.message}")
            ""
        }
    }

    /**
     * Fetches commits for a PR.
     */
    suspend fun getCommits(prId: Int): List<BitbucketCommit> {
        val client = getClient() ?: return emptyList()
        if (!isConfigured()) return emptyList()

        log.info("[PR:Detail] Fetching commits for PR #$prId")
        val result = client.getPullRequestCommits(projectKey(), repoSlug(), prId)
        return (result as? ApiResult.Success)?.data?.values ?: emptyList()
    }

    /**
     * Fetches build statuses for a commit from Bitbucket Server.
     */
    suspend fun getBuildStatus(commitId: String): List<BitbucketBuildStatus> {
        val client = getClient() ?: return emptyList()

        log.info("[PR:Detail] Fetching build statuses for commit ${commitId.take(8)}")
        val result = client.getBuildStatuses(commitId)
        return (result as? ApiResult.Success)?.data ?: emptyList()
    }

    /**
     * Fetches the raw diff for a PR.
     */
    suspend fun getDiff(prId: Int): String? {
        val client = getClient() ?: return null
        if (!isConfigured()) return null

        log.info("[PR:Detail] Fetching diff for PR #$prId")
        return when (val result = client.getPullRequestDiff(projectKey(), repoSlug(), prId)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                log.warn("[PR:Detail] Failed to fetch diff for PR #$prId: ${result.message}")
                null
            }
        }
    }
}
