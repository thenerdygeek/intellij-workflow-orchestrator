package com.workflow.orchestrator.pullrequest.action

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.util.TicketKeyExtractor
import com.workflow.orchestrator.core.workflow.JiraTicketProvider
import com.workflow.orchestrator.core.workflow.TicketContext
import com.workflow.orchestrator.core.workflow.TicketTransition
import com.workflow.orchestrator.core.bitbucket.PrService
import com.workflow.orchestrator.pullrequest.service.PrDescriptionGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Holds all data collected before opening [com.workflow.orchestrator.pullrequest.ui.CreatePrDialog].
 */
data class CreatePrContext(
    val sourceBranch: String,
    val remoteBranches: List<String>,
    val initialTicketKeys: List<String>,
    val initialTicketContexts: Map<String, TicketContext>,
    val transitions: List<TicketTransition>,
    val defaultTitle: String,
    val defaultReviewers: List<String>
)

sealed class PrefetchResult {
    data class Success(val context: CreatePrContext) : PrefetchResult()
    data class Failure(val message: String) : PrefetchResult()
}

/**
 * Shared prefetch helper used by [CreatePrLauncherImpl] to collect all data
 * required to open the Create PR dialog. Extracted from PrBar.openCreatePrDialog()
 * in Phase 6 so that both the Build tab (via PrBar → launcher EP) and the PR tab
 * button share the same lookup path.
 *
 * Resolution contract:
 * - Returns [PrefetchResult.Failure] if Bitbucket is not configured or no Git branch is detected.
 * - Does NOT fail on missing Jira provider, getBranches errors (falls back to empty list),
 *   getTransitions errors (empty list), or getTicketContext returning null.
 *
 * Multi-ticket resolution order:
 * 1. Branch-name regex match → first key
 * 2. PluginSettings.activeTicketId → second key (only if different from branch key)
 */
object CreatePrPrefetch {

    const val ERROR_BITBUCKET_NOT_CONFIGURED = "Bitbucket not configured"
    const val ERROR_GIT_NOT_DETECTED = "Git repository not detected"

    private val log = Logger.getInstance(CreatePrPrefetch::class.java)

    suspend fun run(project: Project): PrefetchResult {
        val settings = PluginSettings.getInstance(project)

        // 1. Verify Bitbucket is configured
        val client = BitbucketBranchClient.fromConfiguredSettings()
            ?: return PrefetchResult.Failure(ERROR_BITBUCKET_NOT_CONFIGURED)

        // 2. Resolve current Git branch (ReadAction — must not block EDT)
        val currentBranch = ReadAction.compute<String?, Throwable> {
            RepoContextResolver.getInstance(project).resolveCurrentEditorRepoOrPrimary()
                ?.currentBranchName
        } ?: return PrefetchResult.Failure(ERROR_GIT_NOT_DETECTED)

        log.info("[PR:Prefetch] currentBranch='$currentBranch'")

        val projectKey = settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()

        // 3. Resolve ticket keys
        val branchKey = TicketKeyExtractor.extractFromBranch(currentBranch)
        val activeKey = settings.state.activeTicketId?.takeIf { it.isNotBlank() }
        val keys = listOfNotNull(branchKey, activeKey?.takeIf { it != branchKey })
        log.info("[PR:Prefetch] resolvedKeys=$keys")

        // 4. Parallel fetch: remote branches + ticket contexts + transitions + default reviewers
        return coroutineScope {
            val remoteBranchesDeferred = async(Dispatchers.IO) {
                try {
                    when (val r = client.getBranches(projectKey, repoSlug)) {
                        is ApiResult.Success -> r.data.map { it.displayId }
                        is ApiResult.Error -> {
                            log.warn("[PR:Prefetch] getBranches failed: ${r.message}")
                            emptyList()
                        }
                    }
                } catch (e: Exception) {
                    log.warn("[PR:Prefetch] getBranches exception: ${e.message}")
                    emptyList<String>()
                }
            }

            val jiraProvider = JiraTicketProvider.getInstance()

            // Ticket contexts — parallel per key
            val contextPairsDeferred = async(Dispatchers.IO) {
                if (jiraProvider == null || keys.isEmpty()) return@async emptyList<Pair<String, TicketContext?>>()
                coroutineScope {
                    // keys fetched in parallel — fan-out bounded by max 5 chips via TicketChipInput
                    keys.map { key ->
                        async(Dispatchers.IO) {
                            val ctx = try { jiraProvider.getTicketContext(key) } catch (e: Exception) {
                                log.warn("[PR:Prefetch] getTicketContext($key) failed: ${e.message}")
                                null
                            }
                            key to ctx
                        }
                    }.awaitAll()
                }
            }

            // Transitions — from the first (primary) key
            val transitionsDeferred = async(Dispatchers.IO) {
                val primaryKey = keys.firstOrNull()
                if (jiraProvider == null || primaryKey.isNullOrBlank()) return@async emptyList<TicketTransition>()
                try { jiraProvider.getAvailableTransitions(primaryKey) } catch (e: Exception) {
                    log.warn("[PR:Prefetch] getAvailableTransitions failed: ${e.message}")
                    emptyList()
                }
            }

            val defaultReviewersDeferred = async(Dispatchers.IO) {
                try {
                    PrService.getInstance(project).buildDefaultReviewers().map { it.user.name }
                } catch (e: Exception) {
                    log.warn("[PR:Prefetch] buildDefaultReviewers failed: ${e.message}")
                    emptyList()
                }
            }

            // Await all
            val remoteBranches = remoteBranchesDeferred.await()
            val contextPairs = contextPairsDeferred.await()
            val transitions = transitionsDeferred.await()
            val defaultReviewers = defaultReviewersDeferred.await()

            // Build ticket map (exclude keys whose context fetch returned null)
            val initialTicketContexts: Map<String, TicketContext> = contextPairs
                .filter { (_, ctx) -> ctx != null }
                .associate { (key, ctx) -> key to ctx!! }

            // Primary context for default title
            val primaryContext = contextPairs.firstOrNull()?.second

            val defaultTitle = try {
                PrDescriptionGenerator.generateTitle(project, primaryContext, currentBranch)
            } catch (e: Exception) {
                log.warn("[PR:Prefetch] generateTitle failed: ${e.message}")
                currentBranch.replace("-", " ")
            }

            PrefetchResult.Success(
                CreatePrContext(
                    sourceBranch = currentBranch,
                    remoteBranches = remoteBranches,
                    initialTicketKeys = keys,
                    initialTicketContexts = initialTicketContexts,
                    transitions = transitions,
                    defaultTitle = defaultTitle,
                    defaultReviewers = defaultReviewers
                )
            )
        }
    }
}
