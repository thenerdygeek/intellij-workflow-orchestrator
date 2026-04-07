package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.polling.SmartPoller
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    private val _allRepoPrs = MutableStateFlow<List<BitbucketPrDetail>>(emptyList())
    val allRepoPrs: StateFlow<List<BitbucketPrDetail>> = _allRepoPrs.asStateFlow()

    private var poller: SmartPoller? = null

    private fun getOrCreatePoller(): SmartPoller {
        return poller ?: SmartPoller(
            name = "PR-List",
            baseIntervalMs = 60_000,
            maxIntervalMs = 300_000,
            scope = scope,
            action = {
                val oldMySize = _myPrs.value.size
                val oldReviewSize = _reviewingPrs.value.size
                refresh()
                _myPrs.value.size != oldMySize || _reviewingPrs.value.size != oldReviewSize
            }
        ).also { poller = it }
    }

    companion object {
        fun getInstance(project: Project): PrListService {
            return project.getService(PrListService::class.java)
        }
    }

    fun startPolling() = getOrCreatePoller().start()

    fun stopPolling() { poller?.stop() }

    fun setVisible(visible: Boolean) { poller?.setVisible(visible) }

    /** Current PR state filter (OPEN, MERGED, DECLINED). */
    @Volatile
    private var currentState: String = "OPEN"

    /**
     * Changes the PR state filter and triggers a refresh.
     * Valid values: "OPEN", "MERGED", "DECLINED".
     */
    fun setState(state: String) {
        currentState = state
        scope.launch { refresh() }
    }

    /** Cached Bitbucket username — auto-detected on first refresh. */
    @Volatile
    private var cachedUsername: String? = null

    /** Limits concurrent repo fetches to 3 to prevent monopolizing the connection pool. */
    private val repoFetchSemaphore = Semaphore(3)

    private val clientCache = BitbucketBranchClientCache()
    private val pluginSettings by lazy { PluginSettings.getInstance(project) }

    private fun getClient(): BitbucketBranchClient =
        clientCache.get() ?: error("Bitbucket URL not configured")

    suspend fun refresh() {
        val connSettings = ConnectionSettings.getInstance().state
        val bitbucketUrl = connSettings.bitbucketUrl.trimEnd('/')
        if (bitbucketUrl.isBlank()) {
            log.info("[PR:List] Bitbucket URL not configured, skipping refresh")
            return
        }

        val client = getClient()

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

        // Collect repos to query — use multi-repo list, fall back to scalar settings
        val repos = pluginSettings.getRepos().filter { it.isConfigured }
        val repoEntries = if (repos.isNotEmpty()) {
            repos.map { Triple(it.bitbucketProjectKey ?: "", it.bitbucketRepoSlug ?: "", it.displayLabel) }
        } else {
            val settings = pluginSettings.state
            val projectKey = settings.bitbucketProjectKey.orEmpty()
            val repoSlug = settings.bitbucketRepoSlug.orEmpty()
            if (projectKey.isBlank() || repoSlug.isBlank()) {
                log.info("[PR:List] Bitbucket project/repo not configured, skipping refresh")
                return
            }
            listOf(Triple(projectKey, repoSlug, repoSlug))
        }

        // Fetch PRs from all repos in parallel, limited to 3 concurrent repo fetches
        // to prevent monopolizing the connection pool (10-40 API calls otherwise)
        val allMyPrs: List<BitbucketPrDetail>
        val allReviewingPrs: List<BitbucketPrDetail>
        val allRepoPrsList: List<BitbucketPrDetail>

        coroutineScope {
            val results = repoEntries.map { (projectKey, repoSlug, repoName) ->
                async {
                    repoFetchSemaphore.withPermit {
                        log.info("[PR:List] Refreshing PRs for $projectKey/$repoSlug (username=$username, state=$currentState)")

                        // Fetch PRs authored by the current user (paginated)
                        val myResults = fetchAllPages(client, projectKey, repoSlug, username, "AUTHOR")
                        myResults.forEach { it.repoName = repoName }

                        // Fetch PRs where the current user is a reviewer (paginated)
                        val reviewResults = fetchAllPages(client, projectKey, repoSlug, username, "REVIEWER")
                        reviewResults.forEach { it.repoName = repoName }

                        // Fetch all PRs in the repo (no role filter — all users)
                        val allResults = fetchAllPages(client, projectKey, repoSlug, null, "ALL")
                        allResults.forEach { it.repoName = repoName }

                        Triple(myResults, reviewResults, allResults)
                    }
                }
            }.awaitAll()

            allMyPrs = results.flatMap { it.first }
            allReviewingPrs = results.flatMap { it.second }
            allRepoPrsList = results.flatMap { it.third }
        }

        _myPrs.value = allMyPrs
        log.info("[PR:List] Found ${allMyPrs.size} authored PRs across ${repoEntries.size} repos (state=$currentState)")

        _reviewingPrs.value = allReviewingPrs
        log.info("[PR:List] Found ${allReviewingPrs.size} reviewing PRs across ${repoEntries.size} repos (state=$currentState)")

        _allRepoPrs.value = allRepoPrsList
        log.info("[PR:List] Found ${allRepoPrsList.size} total repo PRs across ${repoEntries.size} repos (state=$currentState)")
    }

    /**
     * Fetches all pages of PRs for the given role (AUTHOR, REVIEWER, or ALL),
     * capped at 100 results to avoid excessive API calls.
     */
    private suspend fun fetchAllPages(
        client: BitbucketBranchClient,
        projectKey: String,
        repoSlug: String,
        username: String?,
        role: String
    ): List<BitbucketPrDetail> {
        val results = mutableListOf<BitbucketPrDetail>()
        var start = 0
        var isLast = false
        while (!isLast && results.size < 100) {
            val result = when (role) {
                "AUTHOR" -> client.getMyPullRequests(projectKey, repoSlug, currentState, username, start, 25)
                "REVIEWER" -> client.getReviewingPullRequests(projectKey, repoSlug, currentState, username, start, 25)
                else -> client.getRepoPullRequests(projectKey, repoSlug, currentState, start, 25)
            }
            if (result is ApiResult.Success) {
                results.addAll(result.data.values)
                isLast = result.data.isLastPage
                start = result.data.nextPageStart ?: break
            } else {
                if (result is ApiResult.Error) {
                    log.warn("[PR:List] Failed to fetch $role PRs (page start=$start): ${result.message}")
                }
                break
            }
        }
        return results
    }

    override fun dispose() {
        scope.cancel()
    }
}
