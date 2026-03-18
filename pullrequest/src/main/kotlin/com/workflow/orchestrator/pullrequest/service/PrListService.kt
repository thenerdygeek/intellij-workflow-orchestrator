package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Project-level service that maintains lists of PRs authored by and
 * assigned for review to the current user. Polls Bitbucket every 60 seconds.
 *
 * Delegates all HTTP calls to BitbucketBranchClient from :core.
 */
@Service(Service.Level.PROJECT)
class PrListService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(PrListService::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _myPrs = MutableStateFlow<List<BitbucketPrDetail>>(emptyList())
    val myPrs: StateFlow<List<BitbucketPrDetail>> = _myPrs.asStateFlow()

    private val _reviewingPrs = MutableStateFlow<List<BitbucketPrDetail>>(emptyList())
    val reviewingPrs: StateFlow<List<BitbucketPrDetail>> = _reviewingPrs.asStateFlow()

    private var pollingJob: Job? = null

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L

        fun getInstance(project: Project): PrListService {
            return project.getService(PrListService::class.java)
        }
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("[PR:List] Polling error: ${e.message}", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /** Cached Bitbucket username — auto-detected on first refresh. */
    @Volatile
    private var cachedUsername: String? = null

    suspend fun refresh() {
        val connSettings = ConnectionSettings.getInstance().state
        val bitbucketUrl = connSettings.bitbucketUrl.trimEnd('/')
        if (bitbucketUrl.isBlank()) {
            log.info("[PR:List] Bitbucket URL not configured, skipping refresh")
            return
        }

        val settings = PluginSettings.getInstance(project).state
        val projectKey = settings.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.bitbucketRepoSlug.orEmpty()
        if (projectKey.isBlank() || repoSlug.isBlank()) {
            log.info("[PR:List] Bitbucket project/repo not configured, skipping refresh")
            return
        }

        val credentialStore = CredentialStore()
        val client = BitbucketBranchClient(
            baseUrl = bitbucketUrl,
            tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
        )

        // Auto-detect username on first call (or use saved setting)
        val username = cachedUsername
            ?: connSettings.bitbucketUsername.takeIf { it.isNotBlank() }
            ?: run {
                val result = client.getCurrentUsername()
                if (result is ApiResult.Success && result.data.isNotBlank()) {
                    cachedUsername = result.data
                    // Save for future sessions
                    connSettings.bitbucketUsername = result.data
                    log.info("[PR:List] Auto-detected Bitbucket username: ${result.data}")
                    result.data
                } else {
                    log.warn("[PR:List] Could not detect username — PR list may not filter correctly")
                    null
                }
            }

        log.info("[PR:List] Refreshing PRs for $projectKey/$repoSlug (username=$username)")

        // Fetch PRs authored by the current user
        when (val result = client.getMyPullRequests(projectKey, repoSlug, username = username)) {
            is ApiResult.Success -> {
                _myPrs.value = result.data
                log.info("[PR:List] Found ${result.data.size} authored PRs")
            }
            is ApiResult.Error -> {
                log.warn("[PR:List] Failed to fetch my PRs: ${result.message}")
            }
        }

        // Fetch PRs where the current user is a reviewer
        when (val result = client.getReviewingPullRequests(projectKey, repoSlug, username = username)) {
            is ApiResult.Success -> {
                _reviewingPrs.value = result.data
                log.info("[PR:List] Found ${result.data.size} reviewing PRs")
            }
            is ApiResult.Error -> {
                log.warn("[PR:List] Failed to fetch reviewing PRs: ${result.message}")
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
