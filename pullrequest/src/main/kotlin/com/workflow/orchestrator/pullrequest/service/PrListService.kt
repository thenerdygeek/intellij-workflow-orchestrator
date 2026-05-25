package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.bitbucket.PrState
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
class PrListService(
    private val project: Project,
    private val cs: CoroutineScope,
) {

    private val log = Logger.getInstance(PrListService::class.java)

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
            scope = cs,
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
    private var currentState: String = PrState.OPEN

    /**
     * Changes the PR state filter and triggers a refresh.
     * Valid values: [PrState.OPEN], [PrState.MERGED], [PrState.DECLINED].
     */
    fun setState(state: String) {
        currentState = state
        cs.launch(Dispatchers.IO) { refresh() }
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
                    // D2: PersistentStateComponent state must mutate on EDT to avoid corrupt
                    // XML persistence and missed StateChanged notifications. Previously this
                    // ran on Dispatchers.IO (audit finding pullrequest:F-3).
                    withContext(Dispatchers.EDT) {
                        connSettings.bitbucketUsername = result.data
                    }
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

        // R-SWAP-1 / R-SWAP-2 (2026-05-07 Bitbucket audit): use the cross-repo dashboard
        // endpoint for AUTHOR and REVIEWER instead of iterating per-repo. Each call is one
        // round-trip regardless of repo count. The "ALL" role has no dashboard equivalent,
        // so the per-repo loop is kept (capped via repoFetchSemaphore) for that bucket only.
        val configuredRepoKeys: Set<String> = repoEntries
            .map { (p, r, _) -> "$p/$r".lowercase() }
            .toSet()
        val repoNameByKey: Map<String, String> = repoEntries
            .associate { (p, r, name) -> "$p/$r".lowercase() to name }

        val allMyPrs: List<BitbucketPrDetail>
        val allReviewingPrs: List<BitbucketPrDetail>
        val allRepoPrsList: List<BitbucketPrDetail>

        coroutineScope {
            val authorDeferred = async {
                fetchDashboardPrs(client, "AUTHOR", configuredRepoKeys, repoNameByKey)
            }
            val reviewerDeferred = async {
                fetchDashboardPrs(client, "REVIEWER", configuredRepoKeys, repoNameByKey)
            }
            val allRepoDeferred = async {
                repoEntries.map { (projectKey, repoSlug, repoName) ->
                    async {
                        repoFetchSemaphore.withPermit {
                            val results = fetchAllPages(client, projectKey, repoSlug, null, "ALL")
                            results.forEach { it.repoName = repoName }
                            results
                        }
                    }
                }.awaitAll().flatten()
            }
            allMyPrs = authorDeferred.await()
            allReviewingPrs = reviewerDeferred.await()
            allRepoPrsList = allRepoDeferred.await()
        }

        _myPrs.value = allMyPrs
        log.info("[PR:List] Found ${allMyPrs.size} authored PRs (dashboard, scoped to ${repoEntries.size} configured repos, state=$currentState)")

        _reviewingPrs.value = allReviewingPrs
        log.info("[PR:List] Found ${allReviewingPrs.size} reviewing PRs (dashboard, scoped to ${repoEntries.size} configured repos, state=$currentState)")

        _allRepoPrs.value = allRepoPrsList
        log.info("[PR:List] Found ${allRepoPrsList.size} total repo PRs across ${repoEntries.size} repos (state=$currentState)")
    }

    /**
     * Calls the cross-repo dashboard endpoint and filters the response down to repos
     * the user has configured in the plugin (the dashboard returns every repo the
     * user has access to, which can be more than what's wired into this project).
     *
     * Each PR's `repoName` transient field is populated from the configured repo's
     * displayLabel for the multi-repo grouping in the UI.
     */
    private suspend fun fetchDashboardPrs(
        client: BitbucketBranchClient,
        role: String,
        configuredRepoKeys: Set<String>,
        repoNameByKey: Map<String, String>,
    ): List<BitbucketPrDetail> {
        val results = mutableListOf<BitbucketPrDetail>()
        var start = 0
        var isLast = false
        while (!isLast && results.size < 100) {
            val page = client.getDashboardPullRequests(role, currentState, limit = 25, start = start)
            if (page is ApiResult.Success) {
                page.data.values.forEach { pr ->
                    val repoSlug = pr.toRef?.repository?.slug.orEmpty()
                    val projKey = pr.toRef?.repository?.project?.key.orEmpty()
                    if (projKey.isBlank() || repoSlug.isBlank()) return@forEach
                    val key = "$projKey/$repoSlug".lowercase()
                    if (configuredRepoKeys.isEmpty() || key in configuredRepoKeys) {
                        pr.repoName = repoNameByKey[key] ?: repoSlug
                        results += pr
                    }
                }
                isLast = page.data.isLastPage
                start = page.data.nextPageStart ?: break
            } else {
                if (page is ApiResult.Error) {
                    log.warn("[PR:List] Dashboard $role page start=$start failed: ${page.message}")
                }
                break
            }
        }
        return results
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

}
