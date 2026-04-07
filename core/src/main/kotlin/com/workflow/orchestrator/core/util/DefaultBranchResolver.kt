package com.workflow.orchestrator.core.util

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.settings.PluginSettings
import git4idea.repo.GitRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class DefaultBranchResolver(private val project: Project) : Disposable {

    private val log = Logger.getInstance(DefaultBranchResolver::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, String>()

    init {
        scope.launch {
            project.getService(EventBus::class.java).events.collect { event ->
                if (event is WorkflowEvent.BranchChanged) {
                    log.info("[BranchResolver] BranchChanged → clearing cache")
                    cache.clear()
                }
            }
        }
    }

    suspend fun resolve(repo: GitRepository): String {
        val repoPath = repo.root.path
        val currentBranch = repo.currentBranchName ?: return getFallback()
        val cacheKey = buildOverrideKey(repoPath, currentBranch)

        cache[cacheKey]?.let { return it }

        val result = runPriorityChain(repo, repoPath, currentBranch)
        cache[cacheKey] = result
        return result
    }

    private suspend fun runPriorityChain(repo: GitRepository, repoPath: String, currentBranch: String): String {
        // Priority 1: Per-branch override
        getOverride(repoPath, currentBranch)?.let {
            log.info("[BranchResolver] P1 override: $currentBranch → $it")
            return it
        }

        // Resolve Bitbucket credentials for network priorities
        val settings = PluginSettings.getInstance(project)
        val repoConfig = settings.getRepoForPath(repoPath) ?: settings.getPrimaryRepo()
        val projectKey = repoConfig?.bitbucketProjectKey.orEmpty()
        val repoSlug = repoConfig?.bitbucketRepoSlug.orEmpty()

        if (projectKey.isNotBlank() && repoSlug.isNotBlank()) {
            val client = createBitbucketClient()
            if (client != null) {
                // Priority 2: Existing PR target
                tryPrTarget(client, projectKey, repoSlug, currentBranch)?.let {
                    log.info("[BranchResolver] P2 PR target: $currentBranch → $it")
                    return it
                }

                // Priority 3: Merge-base against PR branches
                tryMergeBase(client, projectKey, repoSlug, repo, currentBranch)?.let {
                    log.info("[BranchResolver] P3 merge-base: $currentBranch → $it")
                    return it
                }

                // Priority 4: Bitbucket default branch
                tryBitbucketDefault(client, projectKey, repoSlug)?.let {
                    log.info("[BranchResolver] P4 Bitbucket default: $it")
                    return it
                }
            }
        }

        // Priority 5: origin/HEAD
        fromOriginHead(repo)?.let {
            log.info("[BranchResolver] P5 origin/HEAD: $it")
            return it
        }

        // Priority 6: Settings fallback
        val fallback = getFallback()
        log.info("[BranchResolver] P6 fallback: $fallback")
        return fallback
    }

    private suspend fun tryPrTarget(
        client: BitbucketBranchClient,
        projectKey: String,
        repoSlug: String,
        currentBranch: String
    ): String? {
        return try {
            when (val result = client.getPullRequestsForBranch(projectKey, repoSlug, currentBranch)) {
                is ApiResult.Success -> {
                    // Sort by ID descending as proxy for most recent
                    // (BitbucketPrResponse doesn't carry updatedDate; API already filters OPEN only)
                    result.data
                        .sortedByDescending { it.id }
                        .firstOrNull()
                        ?.toRef?.displayId
                        ?.takeIf { it.isNotBlank() }
                }
                is ApiResult.Error -> null
            }
        } catch (e: Exception) {
            log.info("[BranchResolver] P2 failed: ${e.message}")
            null
        }
    }

    private suspend fun tryMergeBase(
        client: BitbucketBranchClient,
        projectKey: String,
        repoSlug: String,
        repo: GitRepository,
        currentBranch: String
    ): String? {
        var bestBranch: String? = null
        var bestDivergence = Int.MAX_VALUE

        return try {
            withTimeoutOrNull(5000) {
                val allPrs = when (val result = client.getAllPullRequests(projectKey, repoSlug)) {
                    is ApiResult.Success -> result.data
                    is ApiResult.Error -> return@withTimeoutOrNull null
                }
                if (allPrs.isEmpty()) return@withTimeoutOrNull null

                val originHead = fromOriginHead(repo)

                val prBranches = allPrs.flatMap { pr ->
                    listOfNotNull(
                        pr.fromRef?.displayId?.let { from -> Triple(from, pr.toRef?.displayId, "from") },
                        pr.toRef?.displayId?.let { to -> Triple(to, null, "to") }
                    )
                }

                val uniqueBranches = prBranches.map { it.first }
                    .filter { it != currentBranch }
                    .distinct()

                if (uniqueBranches.isEmpty()) return@withTimeoutOrNull null

                val branchFrequency = uniqueBranches.associateWith { branch ->
                    prBranches.count { it.first == branch }
                }

                val sourcesTargetingOriginHead = if (originHead != null) {
                    allPrs.filter { it.toRef?.displayId == originHead }
                        .mapNotNull { it.fromRef?.displayId }
                        .filter { it != currentBranch }
                        .distinct()
                } else emptyList()

                val ordered = (sourcesTargetingOriginHead +
                    (uniqueBranches - sourcesTargetingOriginHead.toSet())
                        .sortedByDescending { branchFrequency[it] ?: 0 })
                    .distinct()
                    .take(20)

                for (candidate in ordered) {
                    ensureActive()
                    val mergeBase = GitMergeBaseUtil.findMergeBase(
                        project, repo.root, currentBranch, candidate
                    ) ?: continue
                    val divergence = GitMergeBaseUtil.countDivergingCommits(
                        project, repo.root, currentBranch, mergeBase
                    )
                    if (divergence < bestDivergence) {
                        bestDivergence = divergence
                        bestBranch = candidate
                    }
                    if (divergence == 0) break
                }

                bestBranch
            } ?: run {
                if (bestBranch != null) {
                    log.info("[BranchResolver] P3 timeout, returning best so far: $bestBranch")
                }
                bestBranch
            }
        } catch (e: Exception) {
            log.info("[BranchResolver] P3 failed: ${e.message}")
            bestBranch
        }
    }

    private suspend fun tryBitbucketDefault(
        client: BitbucketBranchClient,
        projectKey: String,
        repoSlug: String
    ): String? {
        return try {
            when (val result = client.getDefaultBranch(projectKey, repoSlug)) {
                is ApiResult.Success -> result.data.displayId.takeIf { it.isNotBlank() }
                is ApiResult.Error -> null
            }
        } catch (e: Exception) {
            log.info("[BranchResolver] P4 failed: ${e.message}")
            null
        }
    }

    private fun fromOriginHead(repo: GitRepository): String? {
        val origin = repo.remotes.find { it.name == "origin" } ?: return null
        val originHead = repo.branches.remoteBranches.find {
            it.remote == origin && it.nameForRemoteOperations == "HEAD"
        } ?: return null
        return originHead.nameForLocalOperations.removePrefix("origin/")
            .takeIf { it.isNotBlank() && it != "HEAD" }
    }

    private fun getFallback(): String {
        val settings = PluginSettings.getInstance(project)
        return settings.state.defaultTargetBranch?.takeIf { it.isNotBlank() } ?: "develop"
    }

    // --- Override management ---

    fun setOverride(repoPath: String, branch: String, target: String) {
        val key = buildOverrideKey(repoPath, branch)
        val overrides = loadOverrides().toMutableMap()
        overrides[key] = target
        saveOverrides(overrides)
        cache.clear()
        log.info("[BranchResolver] Override set: $branch → $target")
    }

    private fun getOverride(repoPath: String, branch: String): String? {
        val key = buildOverrideKey(repoPath, branch)
        return loadOverrides()[key]
    }

    fun clearAllOverrides() {
        PluginSettings.getInstance(project).state.branchTargetOverrides = ""
        cache.clear()
    }

    private fun loadOverrides(): Map<String, String> {
        val raw = PluginSettings.getInstance(project).state.branchTargetOverrides.orEmpty()
        return parseOverrides(raw)
    }

    private fun saveOverrides(map: Map<String, String>) {
        PluginSettings.getInstance(project).state.branchTargetOverrides = serializeOverrides(map)
    }

    private fun createBitbucketClient(): BitbucketBranchClient? =
        BitbucketBranchClient.fromConfiguredSettings()

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun getInstance(project: Project): DefaultBranchResolver =
            project.getService(DefaultBranchResolver::class.java)

        fun buildOverrideKey(repoPath: String, branch: String): String = "$repoPath||$branch"

        fun parseOverrides(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            return try {
                val obj = json.parseToJsonElement(raw).jsonObject
                obj.mapValues { it.value.jsonPrimitive.content }
            } catch (e: Exception) {
                emptyMap()
            }
        }

        fun serializeOverrides(map: Map<String, String>): String {
            if (map.isEmpty()) return ""
            val obj = JsonObject(map.mapValues { JsonPrimitive(it.value) })
            return obj.toString()
        }
    }
}
