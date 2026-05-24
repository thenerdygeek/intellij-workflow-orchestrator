package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooSearchEntity
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.lookupPlanValidation
import com.workflow.orchestrator.core.settings.recordPlanValidation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

class PlanDetectionService(
    private val apiClient: BambooApiClient,
    private val pluginSettings: PluginSettings? = null
) {

    private val log = Logger.getInstance(PlanDetectionService::class.java)

    // --- Validation cache ---
    // In-memory caches remain as a fast layer for the current session.
    // The persistent cache (pluginSettings) is checked first; in-memory is checked second.
    private val cacheMutex = Mutex()
    private val positiveCache = mutableSetOf<String>()          // indefinite
    private val negativeCache = mutableMapOf<String, Long>()    // 5-min TTL

    // --- Test seams (overrideable in unit tests) ---
    internal var revListRunner: (Path) -> List<String> = ::defaultRevList
    internal var bbClientFactory: () -> BitbucketBranchClient? = { BitbucketBranchClient.fromConfiguredSettings() }

    /**
     * Entry point — five-tier waterfall.
     *
     * Tier 0: parse local `bamboo-specs/bamboo.yml` (0 HTTP calls).
     * Tier 1: walk last 10 commits via git rev-list, query Bitbucket build statuses (1–10 calls).
     * Tier 2: `GET /rest/api/latest/result/byChangeset/{sha}` for last 10 commits (1–10 calls).
     * Tier 3: Linked Repositories + usedBy scan (2 calls).
     * Tier 4: existing N+1 specs scan, gated behind [PluginSettings.State.bambooDeepScanEnabled].
     *
     * After any tier finds a master plan key, [resolveBranchKey] is called to return the
     * branch plan key when [branchName] is non-null and a branch plan exists.
     */
    suspend fun autoDetect(
        repoRoot: Path?,
        gitRemoteUrl: String,
        branchName: String? = null,
        preferredMaster: String? = null
    ): ApiResult<String> {
        // Tier 0 — local bamboo-specs
        if (repoRoot != null) {
            BambooSpecsLocalParser.parsePlanKey(repoRoot)?.let { candidate ->
                if (validate(candidate)) {
                    log.info("[Bamboo:Plan] T0 hit: $candidate")
                    return ApiResult.Success(resolveBranchKey(candidate, branchName))
                }
            }
        }

        // Tier 1 — Bitbucket build-status commit walk
        if (repoRoot != null) {
            val bbClient = bbClientFactory()
            if (bbClient != null) {
                commitWalkPlanKey(repoRoot, bbClient, preferredMaster)?.let { candidate ->
                    if (validate(candidate)) {
                        log.info("[Bamboo:Plan] T1 hit: $candidate")
                        return ApiResult.Success(resolveBranchKey(candidate, branchName))
                    }
                }
            }
        }

        // Tier 2 — Bamboo byChangeset walk
        if (repoRoot != null) {
            byChangesetWalk(repoRoot)?.let { candidate ->
                if (validate(candidate)) {
                    log.info("[Bamboo:Plan] T2 hit: $candidate")
                    return ApiResult.Success(resolveBranchKey(candidate, branchName))
                }
            }
        }

        // Tier 3 — Linked Repositories scan
        linkedRepositoriesScan(gitRemoteUrl)?.let { candidate ->
            if (validate(candidate)) {
                log.info("[Bamboo:Plan] T3 hit: $candidate")
                return ApiResult.Success(resolveBranchKey(candidate, branchName))
            }
        }

        // Tier 4 — existing N+1 specs scan, gated
        val deepScan = pluginSettings?.state?.bambooDeepScanEnabled ?: false
        if (!deepScan) {
            log.info("[Bamboo:Plan] No tier hit and deep scan disabled — giving up")
            return ApiResult.Error(
                ErrorType.NOT_FOUND,
                "no Bamboo plan auto-detected; enable 'Deep scan Bamboo plans' in Settings to fall back to full plan listing"
            )
        }
        return legacyN1Scan(gitRemoteUrl)
    }

    /** Legacy entry point — delegates to the new one with no repoRoot. */
    suspend fun autoDetect(gitRemoteUrl: String): ApiResult<String> =
        autoDetect(null, gitRemoteUrl, null)

    suspend fun search(query: String): ApiResult<List<BambooSearchEntity>> {
        log.info("[Bamboo:Plan] Searching plans with query='$query'")
        return apiClient.searchPlans(query)
    }

    // --- Private helpers ---

    /**
     * Validates a plan key candidate. Consults the persistent [PluginSettings] cache first,
     * then the session-level in-memory cache, then calls the API. Positive entries never
     * expire; negative entries are re-validated after 5 minutes.
     *
     * The lock is released around the suspending HTTP call — the cache-write path holds
     * the mutex only briefly. Two concurrent validators racing on the same candidate may
     * both call the API once, but the writes are idempotent (POSITIVE/NEGATIVE truth
     * values are deterministic for a given key at a given moment).
     */
    internal suspend fun validate(candidate: String): Boolean {
        // 1. Read-side: check both caches under the lock briefly.
        cacheMutex.withLock {
            pluginSettings?.lookupPlanValidation(candidate)?.let { return it }
            if (candidate in positiveCache) return true
            val negExpiry = negativeCache[candidate]
            if (negExpiry != null && System.currentTimeMillis() < negExpiry) return false
        }

        // 2. Network call WITHOUT the lock — long-running suspend.
        val valid = when (val result = apiClient.validatePlan(candidate)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                log.warn("[Bamboo:Plan] validatePlan error for $candidate: ${result.message}")
                false
            }
        }

        // 3. Write-side: brief lock to record the result.
        cacheMutex.withLock {
            if (valid) {
                positiveCache.add(candidate)
            } else {
                negativeCache[candidate] = System.currentTimeMillis() + NEGATIVE_TTL_MS
            }
            pluginSettings?.recordPlanValidation(candidate, valid)
        }
        return valid
    }

    /**
     * Tier 2: Queries Bamboo's byChangeset endpoint for each of the last 10 commits.
     * Returns the first plan key found (Bamboo returns branch-aware plan keys here).
     */
    private suspend fun byChangesetWalk(repoRoot: Path): String? {
        val shas = try {
            revListRunner(repoRoot)
        } catch (e: Exception) {
            log.warn("[Bamboo:Plan:T2] git rev-list failed in $repoRoot: ${e.message}")
            return null
        }
        if (shas.isEmpty()) return null
        for (sha in shas) {
            val results = apiClient.getResultsByChangeset(sha).getOrNull() ?: continue
            if (results.isEmpty()) continue
            val key = results.firstOrNull()?.plan?.key
            if (key != null) {
                log.debug("[Bamboo:Plan:T2] Extracted plan key $key from commit $sha")
                return key
            }
        }
        return null
    }

    /**
     * Tier 3: Lists all Bamboo Linked Repositories, finds one whose URL matches [gitRemoteUrl],
     * then queries which plans use that repository. Returns the first plan key found.
     */
    private suspend fun linkedRepositoriesScan(gitRemoteUrl: String): String? {
        val normalizedTarget = normalizeRepoUrl(gitRemoteUrl)
        val repos = apiClient.getLinkedRepositories().getOrNull() ?: return null
        val matchingRepo = repos.firstOrNull { repo ->
            repo.repositoryUrl?.let { normalizeRepoUrl(it) == normalizedTarget } == true
        } ?: return null
        log.debug("[Bamboo:Plan:T3] Matched linked repository id=${matchingRepo.id} name=${matchingRepo.name}")
        val usages = apiClient.getRepositoryUsedBy(matchingRepo.id).getOrNull() ?: return null
        // entityType "CHAIN" = plan; filter to plans only
        return usages.firstOrNull { it.entityType == "CHAIN" || it.entityType == null }?.key
    }

    /**
     * After a master plan key is found by any tier, attempt to resolve the branch plan key
     * for the current [branchName]. Falls back to [master] if no branch plan is found or
     * if [branchName] is blank.
     *
     * Branch plan keys already look like `PROJ-PLAN-7` (trailing digit segment), so if
     * [master] already matches that pattern we skip resolution to avoid double-nesting.
     *
     * `internal` (was `private`) so the cross-module
     * [com.workflow.orchestrator.bamboo.workflow.ChainKeyResolverImpl] EP can share the
     * branches lookup. The EP itself uses [resolveBranchKeyOrNull] (no master fallback)
     * — that separate entry point exists because `WorkflowContextService.focusPr`
     * needs strict null-on-miss to avoid the master-substitution bug; this method's
     * fallback-to-[master] behaviour stays in service of the auto-detect waterfall.
     */
    internal suspend fun resolveBranchKey(master: String, branchName: String?): String {
        if (branchName.isNullOrBlank()) return master
        // If the candidate already looks like a branch plan key (e.g. PROJ-PLAN-7), skip resolution
        if (master.matches(Regex("^.+-.+-\\d+$"))) return master
        val branches = apiClient.getPlanBranches(master).getOrNull() ?: return master
        val match = branches.firstOrNull { it.shortName?.equals(branchName, ignoreCase = false) == true }
        return if (match != null) {
            log.info("[Bamboo:Plan] Resolved branch plan ${match.key} for branch '$branchName' under master $master")
            match.key
        } else {
            log.info("[Bamboo:Plan] No branch plan found for '$branchName' under $master — falling back to master plan (branch-plan creation may be disabled)")
            master
        }
    }

    /**
     * Strict variant of [resolveBranchKey] that returns null when no branch chain exists
     * for [branchName] under [parentPlanKey] — **no master fallback**. Used by
     * [com.workflow.orchestrator.bamboo.workflow.ChainKeyResolverImpl] (the EP impl
     * consumed by `WorkflowContextService.focusPr`) so a missing branch chain surfaces as
     * "no build for this branch" rather than silently substituting the master chain's
     * latest build (the bug Phase A is unblocking).
     *
     * Lookup logic mirrors [resolveBranchKey] — `apiClient.getPlanBranches(parentPlanKey)`
     * matched by `shortName` (case-sensitive) — so chain-key resolution and the
     * auto-detect waterfall stay in lockstep on the matching rule.
     */
    internal suspend fun resolveBranchKeyOrNull(parentPlanKey: String, branchName: String): String? {
        if (branchName.isBlank()) return null
        // If the parent already looks like a branch plan key, the caller passed the wrong
        // input — no further resolution possible.
        if (parentPlanKey.matches(Regex("^.+-.+-\\d+$"))) return null
        val branches = apiClient.getPlanBranches(parentPlanKey).getOrNull() ?: return null
        val match = branches.firstOrNull { it.shortName?.equals(branchName, ignoreCase = false) == true }
        return if (match != null) {
            log.info("[Bamboo:Plan] Chain-key resolved: ${match.key} for branch '$branchName' under parent $parentPlanKey")
            match.key
        } else {
            log.info("[Bamboo:Plan] No branch chain found for '$branchName' under $parentPlanKey — returning null (no master substitution)")
            null
        }
    }

    private suspend fun commitWalkPlanKey(
        repoRoot: Path,
        bbClient: BitbucketBranchClient,
        preferredMaster: String? = null,
    ): String? {
        val shas = try {
            revListRunner(repoRoot)
        } catch (e: Exception) {
            log.warn("[Bamboo:Plan:T1] git rev-list failed in $repoRoot: ${e.message}")
            return null
        }
        if (shas.isEmpty()) return null

        for (sha in shas) {
            val statusResult = bbClient.getBuildStatuses(sha)
            if (statusResult !is ApiResult.Success) continue
            val statuses = statusResult.data
            if (statuses.isEmpty()) continue

            // Multi-module disambiguation: when caller supplied a preferredMaster
            // (e.g. RepoConfig.bambooPlanKey from the Build tab), prefer a status
            // whose extracted plan key starts with it. Falls back to the first
            // status when no preference matches — same as legacy behaviour.
            val picked = if (!preferredMaster.isNullOrBlank() && statuses.size > 1) {
                statuses.firstOrNull { status ->
                    BitbucketBranchClient.extractPlanKey(status).startsWith(preferredMaster)
                } ?: statuses.first()
            } else {
                statuses.first()
            }

            val planKey = BitbucketBranchClient.extractPlanKey(picked)
            log.debug("[Bamboo:Plan:T1] Extracted plan key $planKey from commit $sha (preferredMaster='${preferredMaster.orEmpty()}', totalStatuses=${statuses.size})")
            return planKey
        }
        return null
    }

    /** Exposed as internal for [com.workflow.orchestrator.bamboo.service.BambooServiceImpl]'s backward-compat 1-arg overload. */
    internal suspend fun legacyN1ScanPublic(gitRemoteUrl: String): ApiResult<String> =
        legacyN1Scan(gitRemoteUrl)

    private suspend fun legacyN1Scan(gitRemoteUrl: String): ApiResult<String> {
        log.info("[Bamboo:Plan] Auto-detecting plan via N+1 scan for remote URL: $gitRemoteUrl")
        val normalizedRemote = normalizeRepoUrl(gitRemoteUrl)
        log.debug("[Bamboo:Plan] Normalized remote URL: $normalizedRemote")

        val plansResult = apiClient.getPlans()
        val plans = plansResult.getOrNull() ?: run {
            log.error("[Bamboo:Plan] Could not fetch plans for auto-detection")
            return ApiResult.Error(
                ErrorType.NETWORK_ERROR,
                "Could not fetch plans for auto-detection"
            )
        }

        log.debug("[Bamboo:Plan] Scanning ${plans.size} plans for matching repository")
        val matches = mutableListOf<String>()
        for (plan in plans) {
            val specsResult = apiClient.getPlanSpecs(plan.key)
            val specsYaml = specsResult.getOrNull() ?: continue
            val repoUrls = extractRepoUrls(specsYaml)
            if (repoUrls.any { normalizeRepoUrl(it) == normalizedRemote }) {
                log.debug("[Bamboo:Plan] Plan ${plan.key} matches repository")
                matches.add(plan.key)
            }
        }

        return when {
            matches.size == 1 -> {
                log.info("[Bamboo:Plan] Auto-detected plan: ${matches[0]}")
                ApiResult.Success(matches[0])
            }
            matches.size > 1 -> {
                log.warn("[Bamboo:Plan] Multiple plans match repository: ${matches.joinToString()}")
                ApiResult.Error(
                    ErrorType.NOT_FOUND,
                    "Multiple plans match this repository: ${matches.joinToString()}"
                )
            }
            else -> {
                log.warn("[Bamboo:Plan] No plan found matching repository: $gitRemoteUrl")
                ApiResult.Error(
                    ErrorType.NOT_FOUND,
                    "No Bamboo plan found matching repository: $gitRemoteUrl"
                )
            }
        }
    }

    companion object {
        private const val NEGATIVE_TTL_MS = 5 * 60 * 1000L  // 5 minutes

        private val URL_REGEX = Regex("""url:\s+(.+)""")

        fun normalizeRepoUrl(url: String): String {
            var normalized = url.trim()
            normalized = normalized.removeSuffix(".git")
            normalized = normalized.replace(Regex("""^(https?|ssh|git)://"""), "")
            normalized = normalized.replace(Regex("""^git@([^:]+):"""), "$1/")
            normalized = normalized.trimEnd('/')
            return normalized
        }

        internal fun extractRepoUrls(specsYaml: String): List<String> {
            return try {
                val yaml = org.yaml.snakeyaml.Yaml(org.yaml.snakeyaml.constructor.SafeConstructor(org.yaml.snakeyaml.LoaderOptions()))
                val data = yaml.load<Any>(specsYaml)
                extractUrlsFromYamlTree(data)
            } catch (_: Exception) {
                // Fallback to regex if YAML parsing fails
                URL_REGEX.findAll(specsYaml).map { it.groupValues[1].trim() }.toList()
            }
        }

        private fun extractUrlsFromYamlTree(node: Any?): List<String> {
            val urls = mutableListOf<String>()
            when (node) {
                is Map<*, *> -> {
                    for ((key, value) in node) {
                        if (key == "url" && value is String) {
                            urls.add(value)
                        } else {
                            urls.addAll(extractUrlsFromYamlTree(value))
                        }
                    }
                }
                is List<*> -> node.forEach { urls.addAll(extractUrlsFromYamlTree(it)) }
            }
            return urls
        }
    }
}

/**
 * Default git rev-list runner: executes `git rev-list -n 10 HEAD` in [repoRoot].
 *
 * Uses [ProcessBuilder] with stderr discarded to avoid the classic Process.exec
 * deadlock where stderr fills the OS pipe buffer (~64K) and blocks the child
 * process from flushing stdout — observed on unborn-branch and shallow-clone
 * setups.
 */
internal fun defaultRevList(repoRoot: Path): List<String> {
    val process = ProcessBuilder("git", "rev-list", "-n", "10", "HEAD")
        .directory(repoRoot.toFile())
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    return output.lines().map { it.trim() }.filter { it.isNotBlank() }
}
