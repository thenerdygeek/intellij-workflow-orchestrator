package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranch
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import com.workflow.orchestrator.core.util.DefaultBranchResolver
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-ticket multi-repo branch lookup. Given a Jira ticket key (e.g. `ABC-123`),
 * returns one [TicketRepoBranch] per configured [RepoConfig] that has a Bitbucket
 * branch whose `displayId` matches the *anchored* ticket regex (avoids
 * `ABC-1` matching `feature/ABC-12`).
 *
 * **Returns:** [LocateResult.Configured] with rows (possibly empty) when the
 * project has at least one Bitbucket-coords-configured repo; [LocateResult.NoReposConfigured]
 * otherwise. The two states are visually distinct in the UI.
 *
 * **Cache.** Per-ticket entries with TTL = [TTL_MS]. Full clear on
 * [WorkflowEvent.TicketChanged] / [WorkflowEvent.BranchChanged] — data set is small
 * (<= N repos x 1 ticket).
 *
 * **Parallelism.** Bitbucket lookups run concurrently via `coroutineScope { ... async }`.
 * Total time is bounded by the slowest repo, not the sum.
 *
 * **Why a service, not a method on JiraTicketProvider?** This is a *Bitbucket*
 * lookup keyed by ticket, not a Jira fetch. It also coordinates against the
 * project's `RepoConfig` list and `GitRepositoryManager` — concerns that belong
 * in :core, not :jira.
 */
@Service(Service.Level.PROJECT)
class TicketBranchLocator internal constructor(
    private val repos: () -> List<RepoConfig>,
    private val currentBranchOf: suspend (RepoConfig) -> String?,
    private val targetBranchOf: suspend (RepoConfig) -> String?,
    private val isPathMounted: suspend (RepoConfig) -> Boolean,
    private val branchClientFactory: (RepoConfig) -> BranchSearchClient?,
    eventsScope: CoroutineScope?,
    eventBus: EventBus?,
) {

    @Suppress("unused") // platform-invoked
    constructor(project: Project, cs: CoroutineScope) : this(
        repos = { PluginSettings.getInstance(project).getRepos() },
        currentBranchOf = currentBranchOf@{ repoConfig ->
            val rootPath = repoConfig.localVcsRootPath ?: return@currentBranchOf null
            readAction {
                GitRepositoryManager.getInstance(project).repositories
                    .firstOrNull { it.root.path == rootPath }
                    ?.currentBranchName
            }
        },
        targetBranchOf = targetBranchOf@{ repoConfig ->
            val rootPath = repoConfig.localVcsRootPath ?: return@targetBranchOf null
            val gitRepo = readAction {
                GitRepositoryManager.getInstance(project).repositories
                    .firstOrNull { it.root.path == rootPath }
            } ?: return@targetBranchOf null
            DefaultBranchResolver.getInstance(project).resolve(gitRepo).ifBlank { null }
        },
        isPathMounted = isPathMounted@{ repoConfig ->
            val rootPath = repoConfig.localVcsRootPath ?: return@isPathMounted false
            readAction {
                GitRepositoryManager.getInstance(project).repositories.any { it.root.path == rootPath }
            }
        },
        branchClientFactory = { repoConfig ->
            BitbucketBranchClient.forRepo(repoConfig)?.let { client -> ProductionBranchSearch(client) }
        },
        eventsScope = cs,
        eventBus = project.getService(EventBus::class.java),
    )

    private val log = Logger.getInstance(TicketBranchLocator::class.java)
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, CacheEntry>()

    init {
        if (eventsScope != null && eventBus != null) {
            eventsScope.launch {
                eventBus.events.collect { event ->
                    when (event) {
                        is WorkflowEvent.TicketChanged, is WorkflowEvent.BranchChanged -> invalidateAll()
                        else -> Unit
                    }
                }
            }
        }
    }

    suspend fun locate(ticketKey: String): LocateResult {
        val key = ticketKey.trim()
        if (key.isEmpty()) return LocateResult.NoReposConfigured

        cacheMutex.withLock {
            val hit = cache[key]
            if (hit != null && System.currentTimeMillis() - hit.fetchedAtMs <= TTL_MS) {
                return hit.result
            }
        }

        val result = fetch(key)

        cacheMutex.withLock {
            cache[key] = CacheEntry(result, System.currentTimeMillis())
        }
        return result
    }

    suspend fun invalidate(ticketKey: String) {
        cacheMutex.withLock { cache.remove(ticketKey.trim()) }
    }

    private suspend fun invalidateAll() {
        cacheMutex.withLock { cache.clear() }
    }

    private suspend fun fetch(ticketKey: String): LocateResult = coroutineScope {
        val configured = repos()
            .filter { !it.bitbucketProjectKey.isNullOrBlank() && !it.bitbucketRepoSlug.isNullOrBlank() }
        if (configured.isEmpty()) return@coroutineScope LocateResult.NoReposConfigured

        val anchored = anchoredRegex(ticketKey)
        val rows = configured
            .map { repoConfig ->
                async {
                    val client = branchClientFactory(repoConfig) ?: return@async null
                    val result = client.search(
                        projectKey = repoConfig.bitbucketProjectKey ?: return@async null,
                        repoSlug = repoConfig.bitbucketRepoSlug ?: return@async null,
                        filterText = ticketKey,
                    )
                    val branches = when (result) {
                        is ApiResult.Success -> result.data
                        is ApiResult.Error -> {
                            log.warn("[Core:TicketBranchLocator] ${repoConfig.displayLabel} lookup failed: ${result.message}")
                            return@async null
                        }
                    }
                    val matching = branches.filter { anchored.containsMatchIn(it.displayId.uppercase()) }
                    val pick = matching.firstOrNull() ?: return@async null
                    TicketRepoBranch(
                        repo = repoConfig,
                        branchDisplayId = pick.displayId,
                        targetBranchDisplayId = targetBranchOf(repoConfig),
                        isCheckedOut = currentBranchOf(repoConfig) == pick.displayId,
                        isPathMounted = isPathMounted(repoConfig),
                        additionalMatchCount = (matching.size - 1).coerceAtLeast(0),
                    )
                }
            }
            .awaitAll()
            .filterNotNull()

        LocateResult.Configured(rows)
    }

    /**
     * Anchored regex: rejects substring false positives where the ticket key is a
     * prefix of another (`ABC-1` vs. `ABC-12`). The lookbehind ensures no
     * letter/digit precedes the key (so `XABC-12` doesn't match `BC-12`); the
     * negative-lookahead `(?!\d)` ensures the digits don't continue.
     */
    private fun anchoredRegex(ticketKey: String): Regex =
        Regex("(?<![A-Z0-9])${Regex.escape(ticketKey.uppercase())}(?!\\d)")

    /**
     * Test seam — narrow contract for what the locator needs from
     * [BitbucketBranchClient] so tests don't have to spin up MockWebServer.
     */
    interface BranchSearchClient {
        suspend fun search(projectKey: String, repoSlug: String, filterText: String): ApiResult<List<BitbucketBranch>>
    }

    private class ProductionBranchSearch(private val client: BitbucketBranchClient) : BranchSearchClient {
        override suspend fun search(projectKey: String, repoSlug: String, filterText: String) =
            client.getBranches(projectKey, repoSlug, filterText)
    }

    private data class CacheEntry(val result: LocateResult, val fetchedAtMs: Long)

    companion object {
        const val TTL_MS: Long = 60_000

        fun getInstance(project: Project): TicketBranchLocator =
            project.getService(TicketBranchLocator::class.java)

        /** Test-only factory: bypasses `Project`/EventBus. */
        internal fun testInstance(
            repos: List<RepoConfig>,
            currentBranchOf: (RepoConfig) -> String?,
            targetBranchOf: (RepoConfig) -> String?,
            isPathMounted: (RepoConfig) -> Boolean,
            branchClientFactory: (RepoConfig) -> BranchSearchClient?,
        ): TicketBranchLocator = TicketBranchLocator(
            repos = { repos },
            currentBranchOf = { currentBranchOf(it) },
            targetBranchOf = { targetBranchOf(it) },
            isPathMounted = { isPathMounted(it) },
            branchClientFactory = branchClientFactory,
            eventsScope = null,
            eventBus = null,
        )
    }
}
