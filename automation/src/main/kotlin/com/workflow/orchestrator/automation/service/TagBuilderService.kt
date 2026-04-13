package com.workflow.orchestrator.automation.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.automation.model.*
import com.workflow.orchestrator.core.services.BambooService
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

    /** Project service constructor — used by IntelliJ DI. */
    constructor(project: Project) {
        val settings = PluginSettings.getInstance(project)
        this.bambooService = project.getService(BambooService::class.java)
        this.buildVariableName = settings.state.bambooBuildVariableName ?: "dockerTagsAsJson"
        log.info("[Automation:Tags] TagBuilderService initialized, buildVariableName='$buildVariableName'")
    }

    /** Test constructor — allows injecting mocks. */
    constructor(bambooService: BambooService, buildVariableName: String = "dockerTagsAsJson") {
        this.bambooService = bambooService
        this.buildVariableName = buildVariableName
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val semverPattern = Regex("""^\d+\.\d+\.\d+.*$""")

    /**
     * Scores and ranks recent builds, collecting diagnostic info about each step.
     * Returns the ranked runs plus diagnostics explaining what happened.
     */
    suspend fun scoreAndRankRuns(
        suitePlanKey: String,
        maxResults: Int = 10
    ): Pair<List<BaselineRun>, BaselineDiagnostics> {
        log.info("[Automation:Tags] Scoring and ranking runs for plan '$suitePlanKey', maxResults=$maxResults")
        val buildsResult = bambooService.getRecentBuilds(suitePlanKey, maxResults)
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

        val totalBuilds = buildsResult.data.size
        log.info("[Automation:Tags] Found $totalBuilds recent builds for '$suitePlanKey'")
        var buildsWithVars = 0
        var buildsWithTags = 0
        val skippedReasons = mutableListOf<String>()

        val ranked = buildsResult.data.mapNotNull { build ->
            log.info("[Automation:Tags] Checking build #${build.buildNumber} (buildResultKey=${build.buildResultKey}, state=${build.state})")

            val resultKey = build.buildResultKey.ifBlank { "${suitePlanKey}-${build.buildNumber}" }
            log.info("[Automation:Tags]   Build #${build.buildNumber}: fetching variables with key='$resultKey'")
            val varsResult = bambooService.getBuildVariables(resultKey)
            if (varsResult.isError) {
                val reason = "#${build.buildNumber}: variables fetch failed — ${varsResult.summary}"
                log.info("[Automation:Tags]   $reason")
                skippedReasons.add(reason)
                return@mapNotNull null
            }
            val variables = varsResult.data.associate { it.name to it.value }
            buildsWithVars++
            log.info("[Automation:Tags]   Build #${build.buildNumber}: fetched ${variables.size} variables: ${variables.keys}")

            val dockerTagsJson = variables[buildVariableName]
            if (dockerTagsJson == null) {
                val reason = "#${build.buildNumber}: no '$buildVariableName' in variables [${variables.keys.joinToString()}]"
                log.info("[Automation:Tags]   $reason")
                skippedReasons.add(reason)
                return@mapNotNull null
            }

            val tags = parseDockerTagsJson(dockerTagsJson)
            if (tags.isEmpty()) {
                val reason = "#${build.buildNumber}: dockerTagsAsJson parsed to empty — ${dockerTagsJson.take(100)}"
                log.info("[Automation:Tags]   $reason")
                skippedReasons.add(reason)
                return@mapNotNull null
            }
            buildsWithTags++

            val releaseCount = tags.values.count { semverPattern.matches(it) }
            val successStages = build.stages.count { it.state.equals("Successful", ignoreCase = true) }
            val failedStages = build.stages.count { it.state.equals("Failed", ignoreCase = true) }
            val score = (releaseCount * 10) + (successStages * 5) - (failedStages * 20)

            log.info("[Automation:Tags]   Build #${build.buildNumber}: ${tags.size} services, $releaseCount release tags, score=$score")

            BaselineRun(
                buildNumber = build.buildNumber,
                resultKey = resultKey,
                dockerTags = tags,
                releaseTagCount = releaseCount,
                totalServices = tags.size,
                successfulStages = successStages,
                failedStages = failedStages,
                triggeredAt = java.time.Instant.now(),
                score = score
            )
        }.sortedByDescending { it.score }

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
        return BaselineLoadResult(tags = tags, selectedBuild = best, diagnostics = diagnostics)
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

    fun buildTriggerVariables(
        entries: List<TagEntry>,
        extraVars: Map<String, String>
    ): Map<String, String> {
        log.info("[Automation:Tags] Building trigger variables using variable name '$buildVariableName' with ${extraVars.size} extra vars")
        val result = mutableMapOf<String, String>()
        result[buildVariableName] = buildJsonPayload(entries)
        result.putAll(extraVars)
        return result
    }

    /**
     * Detect the current repo's docker tag from its CI build log.
     * Returns a [TagDetectionResult] with diagnostic info for the UI.
     */
    suspend fun detectDockerTag(serviceCiPlanKey: String, branchName: String): TagDetectionResult {
        log.info("[Automation:Tags] Detecting docker tag: plan=$serviceCiPlanKey, branch=$branchName")

        val buildResult = bambooService.getLatestBuild(serviceCiPlanKey, branchName)
        if (buildResult.isError) {
            log.warn("[Automation:Tags] No build found for $serviceCiPlanKey/$branchName: ${buildResult.summary}")
            return TagDetectionResult.noBuild(branchName)
        }

        val resultKey = buildResult.data.buildResultKey
        log.info("[Automation:Tags] Found build $resultKey, fetching log...")

        val logResult = bambooService.getBuildLog(resultKey)
        if (logResult.isError) {
            log.warn("[Automation:Tags] Failed to fetch build log for $resultKey: ${logResult.summary}")
            return TagDetectionResult.noTagInLog(resultKey)
        }

        val dockerTagRegex = Regex("Unique Docker Tag\\s*:\\s*(.+)")
        val match = dockerTagRegex.find(logResult.data)
        val tag = match?.groupValues?.get(1)?.trim()
            ?.replace(Regex("\\x1B\\[[0-9;]*m"), "") // Strip ANSI escape codes

        return if (tag != null) {
            log.info("[Automation:Tags] Detected docker tag: '$tag' from $resultKey")
            TagDetectionResult.success(tag, resultKey)
        } else {
            log.warn("[Automation:Tags] 'Unique Docker Tag' not found in build log for $resultKey")
            TagDetectionResult.noTagInLog(resultKey)
        }
    }

    /** Legacy method — delegates to [detectDockerTag]. */
    suspend fun extractDockerTagFromBuildLog(serviceCiPlanKey: String, branchName: String): String? =
        detectDockerTag(serviceCiPlanKey, branchName).tag

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
