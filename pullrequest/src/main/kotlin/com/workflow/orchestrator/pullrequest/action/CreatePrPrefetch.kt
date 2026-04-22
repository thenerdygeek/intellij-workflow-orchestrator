package com.workflow.orchestrator.pullrequest.action

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketUser
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.jira.TransitionMeta
import com.workflow.orchestrator.core.services.jira.TicketTransitionService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.util.TicketKeyExtractor
import com.workflow.orchestrator.core.workflow.JiraTicketProvider
import com.workflow.orchestrator.core.workflow.TicketContext
import com.workflow.orchestrator.core.workflow.TicketTransition
import com.workflow.orchestrator.core.bitbucket.PrService
import com.workflow.orchestrator.pullrequest.service.PrDescriptionGenerator
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Per-repo data prefetched before opening [com.workflow.orchestrator.pullrequest.ui.CreatePrDialog].
 */
data class RepoPrefetch(
    val config: RepoConfig,
    val sourceBranch: String,
    val remoteBranches: List<String>,
    val defaultTarget: String,
    /**
     * Repo-level reviewers fetched from Bitbucket's default-reviewers plugin. Full
     * `BitbucketUser` (name + displayName + email) so the dialog can render friendly
     * "Display Name" chips with username-in-tooltip instead of raw usernames.
     */
    val repoDefaultReviewers: List<BitbucketUser> = emptyList()
)

/**
 * Holds all data collected before opening [com.workflow.orchestrator.pullrequest.ui.CreatePrDialog].
 */
data class CreatePrContext(
    val repos: List<RepoPrefetch>,
    val initialSelectedRepoIndex: Int,
    val initialTicketKeys: List<String>,
    val initialTicketContexts: Map<String, TicketContext>,
    val transitions: List<TicketTransition>,
    val defaultTitle: String,
    val defaultReviewers: List<String>,
    /**
     * Rich [TransitionMeta] objects for the primary ticket, fetched from [TicketTransitionService].
     * Used by the "Transition ticket after PR creation" combo in [CreatePrDialog] so users can
     * pick a post-PR transition without opening a separate screen. Empty when no ticket is resolved
     * or the service is unavailable.
     */
    val transitionMetas: List<TransitionMeta> = emptyList()
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
 * - Returns [PrefetchResult.Failure] if no repos are configured/detected, or all repo
 *   prefetches fail.
 * - Does NOT fail on missing Jira provider, getBranches errors (falls back to empty list),
 *   getTransitions errors (empty list), or getTicketContext returning null.
 * - Single-repo failure → [PrefetchResult.Failure]; one-repo-of-many failure → that repo
 *   is excluded and the rest succeed.
 *
 * Multi-ticket resolution order:
 * 1. Branch-name regex match → first key (from selected repo's branch)
 * 2. PluginSettings.activeTicketId → second key (only if different from branch key)
 */
object CreatePrPrefetch {

    const val ERROR_BITBUCKET_NOT_CONFIGURED = "Bitbucket not configured"
    const val ERROR_GIT_NOT_DETECTED = "Git repository not detected"

    private val log = Logger.getInstance(CreatePrPrefetch::class.java)

    suspend fun run(project: Project): PrefetchResult {
        val settings = PluginSettings.getInstance(project)
        val resolver = RepoContextResolver.getInstance(project)

        // 1. Resolve repo list: configured → auto-detected → failure
        val configuredRepos = settings.getRepos().filter { it.isConfigured }
        val repos: List<RepoConfig> = if (configuredRepos.isNotEmpty()) {
            configuredRepos
        } else {
            val detected = resolver.autoDetectRepos().filter { it.isConfigured }
            if (detected.isEmpty()) {
                return PrefetchResult.Failure(ERROR_GIT_NOT_DETECTED)
            }
            detected
        }

        // 2. Prefetch each repo in parallel; nulls are silently dropped
        val repoPrefetches: List<RepoPrefetch> = coroutineScope {
            repos.map { config ->
                async(Dispatchers.IO) { prefetchOneRepo(project, config) }
            }.awaitAll()
        }.filterNotNull()

        if (repoPrefetches.isEmpty()) {
            return PrefetchResult.Failure(ERROR_BITBUCKET_NOT_CONFIGURED)
        }

        // 3. Resolve initial selected repo index from the current editor's git root
        val editorGitRoot = ReadAction.compute<String?, Throwable> {
            resolver.resolveCurrentEditorRepoOrPrimary()?.root?.path
        }
        val initialSelectedRepoIndex = if (editorGitRoot != null) {
            repoPrefetches.indexOfFirst { it.config.localVcsRootPath == editorGitRoot }
                .takeIf { it >= 0 } ?: 0
        } else 0

        val selectedBranch = repoPrefetches[initialSelectedRepoIndex].sourceBranch

        // 4. Resolve ticket keys from the selected repo's branch
        val branchKey = TicketKeyExtractor.extractFromBranch(selectedBranch)
        val activeKey = settings.state.activeTicketId?.takeIf { it.isNotBlank() }
        val keys = listOfNotNull(branchKey, activeKey?.takeIf { it != branchKey })
        log.info("[PR:Prefetch] resolvedKeys=$keys selectedBranch='$selectedBranch'")

        // 5. Parallel fetch: Jira contexts + transitions + transitionMetas + default reviewers
        return coroutineScope {
            val jiraProvider = JiraTicketProvider.getInstance()

            val contextPairsDeferred = async(Dispatchers.IO) {
                if (jiraProvider == null || keys.isEmpty()) return@async emptyList<Pair<String, TicketContext?>>()
                coroutineScope {
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

            val transitionsDeferred = async(Dispatchers.IO) {
                val primaryKey = keys.firstOrNull()
                if (jiraProvider == null || primaryKey.isNullOrBlank()) return@async emptyList<TicketTransition>()
                try { jiraProvider.getAvailableTransitions(primaryKey) } catch (e: Exception) {
                    log.warn("[PR:Prefetch] getAvailableTransitions failed: ${e.message}")
                    emptyList()
                }
            }

            val transitionMetasDeferred = async(Dispatchers.IO) {
                val primaryKey = keys.firstOrNull()
                if (primaryKey.isNullOrBlank()) return@async emptyList<TransitionMeta>()
                try {
                    val result = project.service<TicketTransitionService>().getAvailableTransitions(primaryKey)
                    if (!result.isError) result.data.orEmpty() else emptyList()
                } catch (e: Exception) {
                    log.warn("[PR:Prefetch] TicketTransitionService.getAvailableTransitions failed: ${e.message}")
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

            val contextPairs = contextPairsDeferred.await()
            val transitions = transitionsDeferred.await()
            val transitionMetas = transitionMetasDeferred.await()
            val defaultReviewers = defaultReviewersDeferred.await()

            val initialTicketContexts: Map<String, TicketContext> = contextPairs
                .filter { (_, ctx) -> ctx != null }
                .associate { (key, ctx) -> key to ctx!! }

            val primaryContext = contextPairs.firstOrNull()?.second

            val defaultTitle = try {
                PrDescriptionGenerator.generateTitle(project, primaryContext, selectedBranch)
            } catch (e: Exception) {
                log.warn("[PR:Prefetch] generateTitle failed: ${e.message}")
                selectedBranch.replace("-", " ")
            }

            PrefetchResult.Success(
                CreatePrContext(
                    repos = repoPrefetches,
                    initialSelectedRepoIndex = initialSelectedRepoIndex,
                    initialTicketKeys = keys,
                    initialTicketContexts = initialTicketContexts,
                    transitions = transitions,
                    defaultTitle = defaultTitle,
                    defaultReviewers = defaultReviewers,
                    transitionMetas = transitionMetas
                )
            )
        }
    }

    /**
     * Prefetch data for a single [RepoConfig]. Returns null if the Bitbucket client cannot
     * be constructed (URL blank) or if the git repo cannot be found — so the caller can
     * silently skip this repo while still succeeding on others.
     */
    private suspend fun prefetchOneRepo(project: Project, config: RepoConfig): RepoPrefetch? {
        return try {
            val client = BitbucketBranchClient.forRepo(config) ?: run {
                log.warn("[PR:Prefetch] No client for repo '${config.displayLabel}'")
                return null
            }

            val gitRepo = ReadAction.compute<git4idea.repo.GitRepository?, Throwable> {
                GitRepositoryManager.getInstance(project).repositories
                    .find { it.root.path == config.localVcsRootPath }
            }

            val sourceBranch = ReadAction.compute<String?, Throwable> {
                gitRepo?.currentBranchName
            } ?: run {
                log.warn("[PR:Prefetch] No current branch for repo '${config.displayLabel}'")
                return null
            }

            val remoteBranches = try {
                when (val r = client.getBranches(
                    config.bitbucketProjectKey.orEmpty(),
                    config.bitbucketRepoSlug.orEmpty()
                )) {
                    is ApiResult.Success -> r.data.map { it.displayId }
                    is ApiResult.Error -> {
                        log.warn("[PR:Prefetch] getBranches failed for '${config.displayLabel}': ${r.message}")
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                log.warn("[PR:Prefetch] getBranches exception for '${config.displayLabel}': ${e.message}")
                emptyList()
            }

            val defaultTarget = if (gitRepo != null) {
                try {
                    com.workflow.orchestrator.core.util.DefaultBranchResolver.getInstance(project)
                        .resolve(gitRepo)
                } catch (e: Exception) {
                    config.defaultTargetBranch.orEmpty().ifBlank { "develop" }
                }
            } else {
                config.defaultTargetBranch.orEmpty().ifBlank { "develop" }
            }

            val repoDefaultReviewers: List<BitbucketUser> = try {
                when (val r = client.getDefaultReviewers(
                    config.bitbucketProjectKey.orEmpty(),
                    config.bitbucketRepoSlug.orEmpty()
                )) {
                    is ApiResult.Success -> r.data
                    is ApiResult.Error -> {
                        log.warn("[PR:Prefetch] getDefaultReviewers failed for '${config.displayLabel}': ${r.message}")
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                log.warn("[PR:Prefetch] getDefaultReviewers exception for '${config.displayLabel}': ${e.message}")
                emptyList()
            }

            log.info("[PR:Prefetch] Prefetched repo '${config.displayLabel}': branch='$sourceBranch' remoteBranches=${remoteBranches.size} defaultTarget='$defaultTarget' repoDefaultReviewers=${repoDefaultReviewers.size}")

            RepoPrefetch(
                config = config,
                sourceBranch = sourceBranch,
                remoteBranches = remoteBranches,
                defaultTarget = defaultTarget,
                repoDefaultReviewers = repoDefaultReviewers
            )
        } catch (e: Exception) {
            log.warn("[PR:Prefetch] prefetchOneRepo failed for '${config.displayLabel}': ${e.message}")
            null
        }
    }
}
