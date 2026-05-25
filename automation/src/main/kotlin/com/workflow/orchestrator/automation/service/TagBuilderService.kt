package com.workflow.orchestrator.automation.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.automation.model.*
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.BuildLogCache
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Service(Service.Level.PROJECT)
class TagBuilderService {

    private val log = Logger.getInstance(TagBuilderService::class.java)
    private val bambooService: BambooService
    private val buildVariableName: String
    private val buildLogCache: BuildLogCache?

    companion object {
        private val DOCKER_TAG_REGEX = Regex("Unique Docker Tag\\s*:\\s*(.+)")
        private val ANSI_ESCAPE_REGEX = Regex("\\x1B\\[[0-9;]*m")

        /**
         * Maximum number of lines to scan for the docker-tag marker.
         *
         * Bamboo build logs are commonly multi-megabyte. Scanning the entire
         * string on every poll tick is an O(N) waste.  The "Unique Docker Tag"
         * line always appears in the early phase of the build (post-docker-build
         * step), so scanning the first [SCAN_LINE_LIMIT] lines is sufficient.
         * If the tag is not found in those lines we bail out early.
         * (Audit finding automation:F-5)
         */
        internal const val SCAN_LINE_LIMIT = 500

        /**
         * Maximum character count of the slice passed to [DOCKER_TAG_REGEX].
         * Acts as a hard byte budget independent of line count (guards against
         * single-line logs with no newlines).
         * (Audit finding automation:F-5)
         */
        internal const val SCAN_CHAR_LIMIT = 65_536  // 64 KB
    }

    /** Project service constructor — used by IntelliJ DI. */
    constructor(project: Project) {
        val settings = PluginSettings.getInstance(project)
        this.bambooService = project.getService(BambooService::class.java)
        this.buildLogCache = BuildLogCache.getInstance(project)
        this.buildVariableName = settings.state.bambooBuildVariableName?.takeIf { it.isNotBlank() } ?: "DockerTagsAsJSON"
        log.info("[Automation:Tags] TagBuilderService initialized, buildVariableName='$buildVariableName'")
    }

    /** Test constructor — allows injecting mocks. */
    constructor(
        bambooService: BambooService,
        buildVariableName: String = "DockerTagsAsJSON",
        buildLogCache: BuildLogCache? = null,
    ) {
        this.bambooService = bambooService
        this.buildVariableName = buildVariableName
        this.buildLogCache = buildLogCache
    }
    private val json = Json { ignoreUnknownKeys = true }
    // A-P2-2: reject pre-release / build-metadata suffixes so e.g. "1.2.3-rc1" or
    // "1.2.3-SNAPSHOT" don't inflate the release-tag count and skew baseline scoring.
    // 4th segment allowed for Maven-style "1.2.3.4" versions still treated as releases.
    private val semverPattern = Regex("""^\d+\.\d+\.\d+(\.\d+)?$""")

    /**
     * Scores and ranks recent builds, collecting diagnostic info about each step.
     * Returns the ranked runs plus diagnostics explaining what happened.
     *
     * **Pagination contract (PR 7 audit #6).** The probe at
     * `tools/atlassian-probe/probe_bamboo.py mirror_baseline_detection` proved
     * the previous "fetch N total builds" shape is wrong: when only 3 of the
     * last 10 builds are parseable (have `dockerTagsAsJson`), the user gets a
     * 3-entry baseline picker instead of a useful 10-entry one. The fix is to
     * fetch in pages of [PAGE_SIZE] and keep walking until either
     * [targetParseable] parseable builds have been accumulated OR [maxWalk]
     * total builds have been visited (defensive cap so we don't walk an
     * infinite history when the plan has zero parseable builds).
     *
     * @param suitePlanKey  the plan to query.
     * @param targetParseable target number of *parseable* builds (with
     *   dockerTagsAsJson) to return. Default 10. Single-arg callers retain
     *   the old "10 total" behavior because [maxWalk] also defaults to 10
     *   when omitted.
     * @param maxWalk hard cap on total builds visited. When equal to
     *   [targetParseable], the helper degenerates to the legacy single-page
     *   fetch (preserves backwards compatibility for existing call sites).
     */
    suspend fun scoreAndRankRuns(
        suitePlanKey: String,
        targetParseable: Int = 10,
        maxWalk: Int = targetParseable
    ): Pair<List<BaselineRun>, BaselineDiagnostics> {
        require(targetParseable > 0) { "targetParseable must be positive" }
        require(maxWalk >= targetParseable) { "maxWalk ($maxWalk) must be >= targetParseable ($targetParseable)" }

        log.info("[Automation:Tags] Scoring runs for plan '$suitePlanKey' " +
            "(targetParseable=$targetParseable, maxWalk=$maxWalk)")

        // Single-page legacy mode: targetParseable == maxWalk → one fetch, no
        // pagination. Preserves the pre-PR-7 behavior for existing call sites
        // that pass only `maxResults`.
        val singlePageMode = targetParseable == maxWalk

        val buildsResult = if (singlePageMode) {
            bambooService.getRecentBuilds(suitePlanKey, targetParseable)
        } else {
            // Multi-page: fetch maxWalk in one request (Bamboo's max-results)
            // and walk through it client-side. Bamboo's `result/{plan}` collection
            // endpoint accepts `max-results=200`; capping at maxWalk means we
            // never over-fetch.
            bambooService.getRecentBuilds(suitePlanKey, maxWalk)
        }
        if (buildsResult.isError) {
            log.info("[Automation:Tags] getRecentBuilds failed for '$suitePlanKey': ${buildsResult.summary}")
            return emptyList<BaselineRun>() to BaselineDiagnostics(
                buildsQueried = 0,
                buildsWithVariables = 0,
                buildsWithDockerTags = 0,
                bambooError = buildsResult.summary,
                skippedReasons = emptyList()
            )
        }

        val totalBuilds = buildsResult.data!!.size
        log.info("[Automation:Tags] Found $totalBuilds recent builds for '$suitePlanKey'")
        var buildsWithVars = 0
        var buildsWithTags = 0
        val skippedReasons = mutableListOf<String>()

        // Stop walking once we accumulate [targetParseable] parseable builds —
        // this is the difference from the old `mapNotNull` shape. We use a
        // mutable list with an early-exit instead of a lazy sequence to keep
        // the existing diagnostics counters intact.
        val ranked = mutableListOf<BaselineRun>()
        for (build in buildsResult.data!!) {
            log.info("[Automation:Tags] Checking build #${build.buildNumber} (buildResultKey=${build.buildResultKey}, state=${build.state})")

            val resultKey = build.buildResultKey.ifBlank { "${suitePlanKey}-${build.buildNumber}" }
            val variables = build.variables
            if (variables.isEmpty()) {
                val reason = "#${build.buildNumber}: no variables on build (likely in-progress or unexpanded result)"
                log.info("[Automation:Tags]   $reason")
                skippedReasons.add(reason)
                continue
            }
            buildsWithVars++
            log.info("[Automation:Tags]   Build #${build.buildNumber}: ${variables.size} inline variables: ${variables.keys}")

            // Case-insensitive lookup — Bamboo variable may be DockerTagsAsJson or dockerTagsAsJson
            val dockerTagsJson = variables.entries
                .firstOrNull { it.key.equals(buildVariableName, ignoreCase = true) }?.value
            if (dockerTagsJson == null) {
                val reason = "#${build.buildNumber}: no '$buildVariableName' in variables [${variables.keys.joinToString()}]"
                log.info("[Automation:Tags]   $reason")
                skippedReasons.add(reason)
                continue
            }

            val tags = parseDockerTagsJson(dockerTagsJson)
            if (tags.isEmpty()) {
                val reason = "#${build.buildNumber}: dockerTagsAsJson parsed to empty — ${dockerTagsJson.take(100)}"
                log.info("[Automation:Tags]   $reason")
                skippedReasons.add(reason)
                continue
            }
            buildsWithTags++

            val releaseCount = tags.values.count { semverPattern.matches(it) }
            val successStages = build.stages.count { it.state.equals("Successful", ignoreCase = true) }
            val failedStages = build.stages.count { it.state.equals("Failed", ignoreCase = true) }
            val score = (releaseCount * 10) + (successStages * 5) - (failedStages * 20)

            log.info("[Automation:Tags]   Build #${build.buildNumber}: ${tags.size} services, $releaseCount release tags, score=$score")

            ranked.add(BaselineRun(
                buildNumber = build.buildNumber,
                resultKey = resultKey,
                dockerTags = tags,
                releaseTagCount = releaseCount,
                totalServices = tags.size,
                successfulStages = successStages,
                failedStages = failedStages,
                triggeredAt = java.time.Instant.now(),
                score = score
            ))

            // Early exit: stop walking once we have enough parseable builds.
            // This is the pagination contract — we walk up to maxWalk total
            // builds but cut off as soon as targetParseable parseable ones
            // are accumulated. (See KDoc above.)
            if (ranked.size >= targetParseable) {
                log.info("[Automation:Tags] Reached targetParseable=$targetParseable after walking " +
                    "${buildsResult.data!!.indexOf(build) + 1} builds — stopping.")
                break
            }
        }

        ranked.sortByDescending { it.score }

        log.info("[Automation:Tags] Scored ${ranked.size} baseline runs for plan '$suitePlanKey'")

        val diagnostics = BaselineDiagnostics(
            buildsQueried = totalBuilds,
            buildsWithVariables = buildsWithVars,
            buildsWithDockerTags = buildsWithTags,
            bambooError = null,
            skippedReasons = skippedReasons
        )
        return ranked to diagnostics
    }

    /**
     * Loads baseline tags with full diagnostic info for the UI.
     */
    suspend fun loadBaselineWithDiagnostics(suitePlanKey: String): BaselineLoadResult {
        log.info("[Automation:Tags] Loading baseline for plan '$suitePlanKey'")
        val (ranked, diagnostics) = scoreAndRankRuns(suitePlanKey)

        if (ranked.isEmpty()) {
            log.info("[Automation:Tags] No baseline runs found — ${diagnostics.toStatusText()}")
            return BaselineLoadResult(tags = emptyList(), selectedBuild = null, diagnostics = diagnostics)
        }

        val best = ranked[0]
        log.info("[Automation:Tags] Selected baseline: build #${best.buildNumber}, ${best.releaseTagCount}/${best.totalServices} release tags, score=${best.score}")
        val tags = best.dockerTags.map { (service, tag) ->
            TagEntry(
                serviceName = service,
                currentTag = tag,
                latestReleaseTag = null,
                source = TagSource.BASELINE,
                registryStatus = RegistryStatus.UNKNOWN,
                isDrift = false,
                isCurrentRepo = false
            )
        }
        // PR 7 #8: surface the full ranked list so the UI can populate a
        // dropdown of alternatives; the user can swap any of these in.
        return BaselineLoadResult(
            tags = tags,
            selectedBuild = best,
            diagnostics = diagnostics,
            allRanked = ranked
        )
    }

    /**
     * Returns the [TagEntry] list for an arbitrary [BaselineRun] from the
     * ranked alternatives — used by the baseline picker dropdown when the
     * user selects a non-default run (PR 7 #8). Pure transformation; no I/O.
     */
    fun tagsForRun(run: BaselineRun): List<TagEntry> =
        run.dockerTags.map { (service, tag) ->
            TagEntry(
                serviceName = service,
                currentTag = tag,
                latestReleaseTag = null,
                source = TagSource.BASELINE,
                registryStatus = RegistryStatus.UNKNOWN,
                isDrift = false,
                isCurrentRepo = false
            )
        }

    /** Legacy method — delegates to [loadBaselineWithDiagnostics]. */
    suspend fun loadBaseline(suitePlanKey: String): List<TagEntry> =
        loadBaselineWithDiagnostics(suitePlanKey).tags

    fun replaceCurrentRepoTag(
        entries: List<TagEntry>,
        context: CurrentRepoContext
    ): List<TagEntry> {
        if (context.featureBranchTag == null) return entries
        log.debug("[Automation:Tags] Replacing tag for service '${context.serviceName}' with feature branch tag '${context.featureBranchTag}'")
        return entries.map { entry ->
            if (entry.serviceName == context.serviceName) {
                entry.copy(
                    currentTag = context.featureBranchTag,
                    source = TagSource.AUTO_DETECTED,
                    isCurrentRepo = true
                )
            } else {
                entry
            }
        }
    }

    fun buildJsonPayload(entries: List<TagEntry>): String {
        val map = entries.associate { it.serviceName to JsonPrimitive(it.currentTag) }
        val payload = JsonObject(map).toString()
        log.info("[Automation:Tags] Built JSON payload with ${entries.size} services, length=${payload.length}")
        log.debug("[Automation:Tags] JSON preview: ${payload.take(200)}${if (payload.length > 200) "..." else ""}")
        return payload
    }

    /**
     * Detects the current repo's docker tag from its CI build log using the resolved
     * branch-chain key. This is the canonical Phase-B path — it consults
     * [BuildLogCache] first (cache-first, O(1)) and falls back to a Bamboo REST call
     * only when the cache is cold.
     *
     * Cache hit: extracts tag from [WorkflowEvent.BuildLogReady.logText] — no API call.
     * Cache miss: calls [BambooService.getLatestBuild] with [chainKey] as the `planKey`
     *   arg (single unbranched call — chain key already encodes the branch-plan identity).
     *   Then fetches the build log via [BambooService.getBuildLog].
     *
     * @param chainKey  the resolved branch-chain key (e.g. `PROJ-PLANKEY523`).
     *   Obtained from [WorkflowContextService.state].value.focusBuild.chainKey.
     */
    suspend fun detectDockerTag(chainKey: String): TagDetectionResult {
        log.info("[Automation:Tags] Detecting docker tag via chainKey='$chainKey'")

        // Cache-first: the Bamboo poller already cached the latest BuildLogReady keyed
        // by chain key. Reuse it to avoid burning a Bamboo round-trip on panel mount.
        // Only return the cached verdict when it's actionable — a SUCCESS event whose
        // log lacks the tag line may be a partial fetch (Bamboo flushes job logs after
        // it flips the build to Successful), so we fall through to a live REST fetch
        // and recover. This pairs with BuildMonitor's per-job fetch retry to make sure
        // a single bad first poll can't strand the UI.
        val cached = buildLogCache?.getLatest(chainKey)
        if (cached != null) {
            log.info("[Automation:Tags] Cache hit for chainKey='$chainKey' (build #${cached.buildNumber})")
            when (cached.status) {
                com.workflow.orchestrator.core.events.WorkflowEvent.BuildEventStatus.FAILED ->
                    return TagDetectionResult.buildFailed(cached.planKey, cached.buildNumber)
                com.workflow.orchestrator.core.events.WorkflowEvent.BuildEventStatus.SUCCESS -> {
                    if (cached.logText.isNotEmpty()) {
                        val tag = extractDockerTagFromLog(cached.logText)
                        if (tag != null) return TagDetectionResult.success(tag, cached.resultKey)
                        log.info("[Automation:Tags] Cached log for $chainKey has no tag — re-validating via REST")
                    } else {
                        log.info("[Automation:Tags] Cached log for $chainKey is empty — re-validating via REST")
                    }
                    // fall through to REST fallback below
                }
            }
        }

        // REST fallback: cache cold — fetch directly. Pass chainKey as the planKey arg;
        // the unbranched form returns the chain's latest build without branch resolution.
        log.info("[Automation:Tags] Cache miss for chainKey='$chainKey' — falling back to REST")
        val buildResult = bambooService.getLatestBuild(chainKey)
        if (buildResult.isError) {
            log.warn("[Automation:Tags] No build found for chainKey='$chainKey': ${buildResult.summary}")
            return TagDetectionResult.noBuild("(unknown)")
        }

        val resultKey = buildResult.data!!.buildResultKey
        log.info("[Automation:Tags] Found build $resultKey, fetching log...")

        val logResult = bambooService.getBuildLog(resultKey)
        if (logResult.isError) {
            log.warn("[Automation:Tags] Failed to fetch build log for $resultKey: ${logResult.summary}")
            return TagDetectionResult.logFetchFailed(resultKey)
        }

        val tag = extractDockerTagFromLog(logResult.data!!)
        return if (tag != null) {
            log.info("[Automation:Tags] Detected docker tag: '$tag' from $resultKey")
            TagDetectionResult.success(tag, resultKey)
        } else {
            log.warn("[Automation:Tags] 'Unique Docker Tag' not found in build log for $resultKey")
            TagDetectionResult.noTagInLog(resultKey)
        }
    }

    /**
     * Extract docker tag from pre-fetched build log text.
     * Pure function — no API calls. Used by event-driven path (BuildLogReady).
     *
     * Scans only the first [SCAN_LINE_LIMIT] lines (capped at [SCAN_CHAR_LIMIT]
     * characters) of the log to avoid O(N) traversal of multi-MB build logs on
     * every poll tick. The "Unique Docker Tag" marker is always emitted in the
     * early build phase; tags beyond this window are not real.
     * (Audit finding automation:F-5)
     */
    fun extractDockerTagFromLog(logText: String): String? {
        // Bound the input: take the first SCAN_CHAR_LIMIT chars, then keep only
        // the first SCAN_LINE_LIMIT lines of that slice. This is O(min(N, limit))
        // rather than O(N) and eliminates the ReDoS surface on pathological logs.
        val slice = logText.take(SCAN_CHAR_LIMIT)
            .lineSequence()
            .take(SCAN_LINE_LIMIT)
            .joinToString("\n")

        val match = DOCKER_TAG_REGEX.find(slice) ?: return null
        return match.groupValues[1].trim()
            .replace(ANSI_ESCAPE_REGEX, "")
            .takeIf { it.isNotBlank() }
    }

    private fun parseDockerTagsJson(jsonStr: String): Map<String, String> {
        return try {
            val obj = json.decodeFromString<JsonObject>(jsonStr)
            val result = obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
            log.debug("[Automation:Tags] Parsed dockerTagsJson with ${result.size} entries")
            result
        } catch (e: Exception) {
            log.error("[Automation:Tags] Failed to parse dockerTagsJson: ${e.message}")
            emptyMap()
        }
    }
}
