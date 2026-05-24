package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.model.bamboo.ArtifactData
import com.workflow.orchestrator.core.model.bamboo.BuildChangeData
import com.workflow.orchestrator.core.model.bamboo.BuildJobData
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildStageData
import com.workflow.orchestrator.core.model.bamboo.BuildTriggerData
import com.workflow.orchestrator.core.model.bamboo.FailedTestData
import com.workflow.orchestrator.core.model.bamboo.PlanBranchData
import com.workflow.orchestrator.core.model.bamboo.PlanData
import com.workflow.orchestrator.core.model.bamboo.PlanVariableData
import com.workflow.orchestrator.core.model.bamboo.ProjectData
import com.workflow.orchestrator.core.model.bamboo.TestResultsData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.ui.TimeFormatter
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageDto

/**
 * Unified Bamboo service implementation used by both UI panels and AI agent.
 *
 * Wraps the existing [BambooApiClient] and maps its responses to shared
 * domain models ([BuildResultData]) with LLM-optimized text summaries.
 */
@Service(Service.Level.PROJECT)
class BambooServiceImpl(private val project: Project) : BambooService {

    private val log = Logger.getInstance(BambooServiceImpl::class.java)
    private val credentialStore = CredentialStore()
    private val settings get() = PluginSettings.getInstance(project)

    @Volatile private var cachedClient: BambooApiClient? = null
    @Volatile private var cachedBaseUrl: String? = null

    /** Test-only override: when set, [client] returns this instead of building from settings. */
    @Volatile internal var testClientOverride: BambooApiClient? = null

    /**
     * The lazy [BambooApiClient] used for all REST calls. Exposed as `internal` so that
     * sibling classes in `:bamboo` (notably [com.workflow.orchestrator.bamboo.workflow.LatestBuildLookupImpl],
     * the EP wired into the cross-module workflow context) can perform thin Bamboo lookups
     * without going through the heavyweight [getLatestBuild] mapper. Returns null when
     * Bamboo is not configured.
     */
    internal val client: BambooApiClient?
        get() {
            testClientOverride?.let { return it }
            val url = settings.connections.bambooUrl.orEmpty().trimEnd('/')
            if (url.isBlank()) return null
            if (url != cachedBaseUrl || cachedClient == null) {
                cachedBaseUrl = url
                val timeouts = com.workflow.orchestrator.core.http.HttpClientFactory.timeoutsFromSettings(project)
                cachedClient = BambooApiClient(
                    baseUrl = url,
                    tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) },
                    connectTimeoutSeconds = timeouts.connectSeconds,
                    readTimeoutSeconds = timeouts.readSeconds
                )
            }
            return cachedClient
        }

    override suspend fun getLatestBuild(chainKey: String): ToolResult<BuildResultData> {
        val api = client ?: return notConfiguredError("fetch latest build for $chainKey")
        return when (val result = api.getLatestResult(chainKey)) {
            is ApiResult.Success -> mapBuildResult(result.data)
            is ApiResult.Error -> {
                log.warn("[BambooService] No build for chain $chainKey: ${result.message}")
                buildErrorResult(chainKey, 0, result)
            }
        }
    }

    override suspend fun getBuild(buildKey: String): ToolResult<BuildResultData> {
        val api = client ?: return notConfiguredError("fetch build $buildKey")

        return when (val result = api.getBuildResult(buildKey)) {
            is ApiResult.Success -> mapBuildResult(result.data)
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch build $buildKey: ${result.message}")
                buildErrorResult(buildKey, 0, result)
            }
        }
    }

    override suspend fun triggerBuild(
        chainKey: String,
        variables: Map<String, String>,
        stages: Set<String>?
    ): ToolResult<BuildTriggerData> {
        val api = client ?: return ToolResult(
            data = BuildTriggerData(buildKey = "", buildNumber = 0, link = ""),
            summary = "Bamboo not configured. Cannot trigger build for $chainKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.queueBuildWithStageSelection(chainKey, variables, stages)) {
            is ApiResult.Success -> {
                val qr = result.data
                val data = BuildTriggerData(
                    buildKey = qr.buildResultKey,
                    buildNumber = qr.buildNumber,
                    link = "${cachedBaseUrl}/browse/${qr.buildResultKey}"
                )
                val stagesSummary = when {
                    stages == null -> "all stages"
                    else -> stages.joinToString(", ")
                }
                ToolResult.success(
                    data = data,
                    summary = "Build triggered: ${data.buildKey} (#${data.buildNumber}) — stages: $stagesSummary\nLink: ${data.link}"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to trigger build for $chainKey: ${result.message}")
                ToolResult(
                    data = BuildTriggerData(buildKey = "", buildNumber = 0, link = ""),
                    summary = "Error triggering build for $chainKey: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        ErrorType.AUTH_FAILED ->
                            "Check your Bamboo token in Settings."
                        ErrorType.FORBIDDEN ->
                            "You may not have permission to trigger this plan."
                        ErrorType.NOT_FOUND ->
                            "Verify the plan key is correct (e.g., PROJ-PLAN)."
                        ErrorType.VALIDATION_ERROR ->
                            result.message
                        else -> "Check Bamboo connection in Settings."
                    },
                    // Carry the structured ErrorType so QueueService can distinguish
                    // transient (retry on next poll tick) from permanent (mark
                    // FAILED_TO_TRIGGER). See QueueService.handleWaitingLocal.
                    payload = result.type
                )
            }
        }
    }

    override suspend fun testConnection(): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Bamboo not configured.",
            isError = true,
            hint = "Set Bamboo URL and token in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getPlans()) {
            is ApiResult.Success -> {
                ToolResult.success(Unit, "Bamboo connection successful. Found ${result.data.size} plans.")
            }
            is ApiResult.Error -> {
                ToolResult(
                    data = Unit,
                    summary = "Bamboo connection failed: ${result.message}",
                    isError = true,
                    hint = "Check URL and token in Settings."
                )
            }
        }
    }

    override suspend fun getBuildLog(resultKey: String): ToolResult<String> {
        val api = client ?: return ToolResult(
            data = "",
            summary = "Bamboo not configured. Cannot fetch build log for $resultKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getBuildLog(resultKey)) {
            is ApiResult.Success -> ToolResult.success(
                data = result.data,
                summary = "Build log for $resultKey: ${result.data.length} chars"
            )
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch build log for $resultKey: ${result.message}")
                ToolResult(
                    data = "",
                    summary = "Error fetching build log for $resultKey: ${result.message}",
                    isError = true,
                    hint = "Check Bamboo connection in Settings."
                )
            }
        }
    }

    override suspend fun getTestResults(resultKey: String): ToolResult<TestResultsData> {
        val api = client ?: return ToolResult(
            data = TestResultsData(total = 0, passed = 0, failed = 0, skipped = 0),
            summary = "Bamboo not configured. Cannot fetch test results for $resultKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getTestResults(resultKey)) {
            is ApiResult.Success -> {
                val dto = result.data.testResults
                val failedTests = dto.failedTests.testResult.map { tc ->
                    FailedTestData(
                        className = tc.className,
                        methodName = tc.methodName,
                        message = null
                    )
                }
                val data = TestResultsData(
                    total = dto.all,
                    passed = dto.successful,
                    failed = dto.failed,
                    skipped = dto.skipped,
                    failedTests = failedTests
                )
                ToolResult.success(
                    data = data,
                    summary = "Tests for $resultKey: ${data.total} total, ${data.passed} passed, ${data.failed} failed, ${data.skipped} skipped"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch test results for $resultKey: ${result.message}")
                ToolResult(
                    data = TestResultsData(total = 0, passed = 0, failed = 0, skipped = 0),
                    summary = "Error fetching test results for $resultKey: ${result.message}",
                    isError = true,
                    hint = "Check Bamboo connection in Settings."
                )
            }
        }
    }

    override suspend fun rerunFailedJobs(planKey: String, buildNumber: Int): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Bamboo not configured. Cannot rerun failed jobs for $planKey #$buildNumber.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.rerunFailedJobs(planKey, buildNumber)) {
            is ApiResult.Success -> ToolResult.success(
                data = Unit,
                summary = "Rerun triggered for $planKey #$buildNumber."
            )
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to rerun failed jobs for $planKey #$buildNumber: ${result.message}")
                ToolResult(
                    data = Unit,
                    summary = "Error rerunning failed jobs for $planKey #$buildNumber: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        ErrorType.AUTH_FAILED ->
                            "Check your Bamboo token in Settings."
                        ErrorType.AUTH_REDIRECT ->
                            "Your Bamboo session expired. Re-authenticate in Settings."
                        ErrorType.FORBIDDEN ->
                            "You may not have permission to restart this build."
                        ErrorType.NOT_FOUND ->
                            "Build not found. Verify plan key and build number."
                        else -> "Check Bamboo connection in Settings."
                    }
                )
            }
        }
    }

    override suspend fun getPlanVariables(planKey: String): ToolResult<List<PlanVariableData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bamboo not configured. Cannot fetch plan variables for $planKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        // Strategy A: variableContext expand (validated Bamboo 10.2 primary path).
        // Note: plan-level variableContext returns `key`/`value` shape (not `name`/`value`).
        // We map dto.key → PlanVariableData.name so callers see a uniform name/value surface.
        val contextResult = api.getPlanVariableContext(planKey)
        if (contextResult is ApiResult.Success && contextResult.data.isNotEmpty()) {
            // Preserve isPassword / variableType from the DTO — UI uses isPassword to
            // render JBPasswordField + suppress logging the value (audit P1, PR 7 #1).
            val data = contextResult.data.map {
                PlanVariableData(
                    name = it.key,
                    value = it.value,
                    isPassword = it.isPassword,
                    variableType = it.variableType
                )
            }
            log.info("[BambooService] Got ${data.size} plan variable(s) via variableContext for $planKey")
            return ToolResult.success(
                data = data,
                summary = "Plan $planKey has ${data.size} variable(s): ${data.joinToString { it.name }}"
            )
        }

        // Strategy C: fall back to most recent build's variables
        // (Strategy B — /plan/{key}/variable — was removed: 404 on Bamboo 10.2 per §8.3 audit)
        log.info("[BambooService] Plan variable endpoints failed for $planKey, falling back to last build's variables")
        val recentResult = api.getRecentResults(planKey, maxResults = 1)
        if (recentResult is ApiResult.Success) {
            val latestBuild = recentResult.data.firstOrNull()
            if (latestBuild != null) {
                val resultKey = "${planKey}-${latestBuild.buildNumber}"
                val varsResult = api.getBuildVariables(resultKey)
                if (varsResult is ApiResult.Success) {
                    // Strategy C variables come from a build-level endpoint that does not carry
                    // isPassword metadata. Default to isPassword=true (fail-safe: over-mask rather
                    // than leak secrets). Callers and UI will render these as password fields until
                    // the variableContext path succeeds and provides explicit classification.
                    val data = varsResult.data.entries.map { PlanVariableData(name = it.key, value = it.value, isPassword = true) }
                    log.info("[BambooService] Got ${data.size} variable(s) from last build $resultKey as fallback (all marked isPassword=true)")
                    return ToolResult.success(
                        data = data,
                        summary = "Plan $planKey: ${data.size} variable(s) from last build #${latestBuild.buildNumber}"
                    )
                }
            }
        }

        val errorMsg = when (contextResult) {
            is ApiResult.Error -> contextResult.message
            else -> "variableContext returned empty"
        }
        log.warn("[BambooService] All strategies failed for plan variables of $planKey: $errorMsg")
        return ToolResult(
            data = emptyList(),
            summary = "Error fetching plan variables for $planKey: $errorMsg",
            isError = true,
            hint = "Check Bamboo connection in Settings."
        )
    }

    override suspend fun triggerStage(
        planKey: String,
        variables: Map<String, String>,
        stage: String?
    ): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Bamboo not configured. Cannot trigger stage for $planKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        // Delegate to queueBuildWithStageSelection; stage=null → all stages.
        val stageSet = if (stage != null) setOf(stage) else null
        return when (val result = api.queueBuildWithStageSelection(planKey, variables, stageSet)) {
            is ApiResult.Success -> ToolResult.success(
                data = Unit,
                summary = "Stage '${stage ?: "all"}' triggered for $planKey (#${result.data.buildNumber})."
            )
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to trigger stage for $planKey: ${result.message}")
                ToolResult(
                    data = Unit,
                    summary = "Error triggering stage for $planKey: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        ErrorType.AUTH_FAILED ->
                            "Check your Bamboo token in Settings."
                        ErrorType.FORBIDDEN ->
                            "You may not have permission to trigger this plan."
                        else -> "Check Bamboo connection in Settings."
                    }
                )
            }
        }
    }

    override suspend fun stopBuild(resultKey: String): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Bamboo not configured. Cannot stop build $resultKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.stopBuild(resultKey)) {
            is ApiResult.Success -> ToolResult.success(
                data = Unit,
                summary = "Build $resultKey stopped."
            )
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to stop build $resultKey: ${result.message}")
                ToolResult(
                    data = Unit,
                    summary = "Error stopping build $resultKey: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        ErrorType.AUTH_FAILED ->
                            "Check your Bamboo token in Settings."
                        ErrorType.AUTH_REDIRECT ->
                            "Your Bamboo session expired. Re-authenticate in Settings."
                        ErrorType.FORBIDDEN ->
                            "You may not have permission to stop this build."
                        ErrorType.NOT_FOUND ->
                            "Build not found. It may have already finished."
                        else -> "Check Bamboo connection in Settings."
                    }
                )
            }
        }
    }

    override suspend fun cancelBuild(resultKey: String): ToolResult<Unit> {
        val api = client ?: return ToolResult(
            data = Unit,
            summary = "Bamboo not configured. Cannot cancel build $resultKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.cancelBuild(resultKey)) {
            is ApiResult.Success -> ToolResult.success(
                data = Unit,
                summary = "Build $resultKey cancelled."
            )
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to cancel build $resultKey: ${result.message}")
                ToolResult(
                    data = Unit,
                    summary = "Error cancelling build $resultKey: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        ErrorType.AUTH_FAILED ->
                            "Check your Bamboo token in Settings."
                        ErrorType.AUTH_REDIRECT ->
                            "Your Bamboo session expired. Re-authenticate in Settings."
                        ErrorType.FORBIDDEN ->
                            "You may not have permission to cancel this build."
                        ErrorType.NOT_FOUND ->
                            "Build not found. It may have already started or finished."
                        else -> "Check Bamboo connection in Settings."
                    }
                )
            }
        }
    }

    override suspend fun getArtifacts(resultKey: String): ToolResult<List<ArtifactData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bamboo not configured. Cannot fetch artifacts for $resultKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getArtifacts(resultKey)) {
            is ApiResult.Success -> {
                val data = result.data.map { artifact ->
                    ArtifactData(
                        name = artifact.name,
                        downloadUrl = artifact.link?.href ?: "",
                        producerJobKey = artifact.producerJobKey,
                        shared = artifact.shared,
                        size = artifact.size
                    )
                }
                ToolResult.success(
                    data = data,
                    summary = "Build $resultKey has ${data.size} artifact(s): ${data.joinToString { it.name }}"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch artifacts for $resultKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching artifacts for $resultKey: ${result.message}",
                    isError = true,
                    hint = "Check Bamboo connection in Settings."
                )
            }
        }
    }

    override suspend fun downloadArtifact(artifactUrl: String, targetFile: java.io.File): ToolResult<Boolean> {
        val api = client ?: return ToolResult(
            data = false,
            summary = "Bamboo not configured. Cannot download artifact.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return try {
            val success = api.downloadArtifact(artifactUrl, targetFile)
            if (success) {
                ToolResult.success(
                    data = true,
                    summary = "Downloaded artifact to ${targetFile.absolutePath}"
                )
            } else {
                ToolResult(
                    data = false,
                    summary = "Failed to download artifact from $artifactUrl",
                    isError = true
                )
            }
        } catch (e: Exception) {
            log.warn("[BambooService] Failed to download artifact: ${e.message}")
            ToolResult(
                data = false,
                summary = "Error downloading artifact: ${e.message}",
                isError = true
            )
        }
    }

    override suspend fun getRecentBuilds(chainKey: String, maxResults: Int): ToolResult<List<BuildResultData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bamboo not configured. Cannot fetch recent builds for $chainKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getRecentResults(chainKey, maxResults)) {
            is ApiResult.Success -> {
                val builds = result.data.map { dto ->
                    BuildResultData(
                        planKey = dto.plan?.key ?: dto.key.substringBeforeLast("-"),
                        buildNumber = dto.buildNumber,
                        state = dto.state.ifBlank { dto.lifeCycleState },
                        durationSeconds = dto.buildDurationInSeconds,
                        buildResultKey = dto.buildResultKey.ifBlank { dto.key },
                        buildRelativeTime = dto.buildRelativeTime,
                        stages = dto.stages.stage.map { it.toBuildStageData() },
                        lifeCycleState = dto.lifeCycleState,  // A-P1-1
                        variables = dto.variables.variable.associate { it.name to it.value }
                    )
                }
                ToolResult.success(
                    data = builds,
                    summary = "Found ${builds.size} recent builds for $chainKey"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch recent builds for $chainKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching recent builds for $chainKey: ${result.message}",
                    isError = true,
                    hint = "Check Bamboo connection in Settings."
                )
            }
        }
    }

    override suspend fun getPlans(): ToolResult<List<PlanData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bamboo not configured. Cannot fetch plans.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getPlans()) {
            is ApiResult.Success -> {
                val data = result.data.map { dto ->
                    // Prefer the canonical projectKey from the DTO. Fall back to substring
                    // splitting only as a defensive path for older Bamboo versions that omit
                    // the field — that split is wrong for hyphenated project keys (e.g.
                    // MY-PROJ-PLAN returns "MY" instead of "MY-PROJ").
                    val projectKey = dto.projectKey.ifBlank { dto.key.substringBefore("-") }
                    PlanData(
                        key = dto.key,
                        name = dto.name,
                        shortName = dto.shortName.ifBlank { dto.name },
                        projectKey = projectKey,
                        projectName = "",
                        enabled = dto.enabled
                    )
                }
                ToolResult.success(
                    data = data,
                    summary = "Found ${data.size} plan(s): ${data.joinToString { it.key }}"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch plans: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching plans: ${result.message}",
                    isError = true,
                    hint = "Check Bamboo connection in Settings."
                )
            }
        }
    }

    override suspend fun getProjectPlans(projectKey: String): ToolResult<List<PlanData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bamboo not configured. Cannot fetch plans for project $projectKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getProjectPlans(projectKey)) {
            is ApiResult.Success -> {
                val data = result.data.map { dto ->
                    PlanData(
                        key = dto.key,
                        name = dto.name,
                        shortName = dto.shortName.ifBlank { dto.name },
                        projectKey = projectKey,
                        projectName = "",
                        enabled = dto.enabled
                    )
                }
                ToolResult.success(
                    data = data,
                    summary = "Project $projectKey has ${data.size} plan(s): ${data.joinToString { it.key }}"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch plans for project $projectKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching plans for project $projectKey: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        ErrorType.NOT_FOUND ->
                            "Verify the project key is correct."
                        else -> "Check Bamboo connection in Settings."
                    }
                )
            }
        }
    }

    override suspend fun searchPlans(query: String): ToolResult<List<PlanData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bamboo not configured. Cannot search plans.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.searchPlans(query)) {
            is ApiResult.Success -> {
                val data = result.data.map { entity ->
                    // Prefer the canonical projectKey from the search entity. Fall back to
                    // substring splitting only as a defensive path for older Bamboo versions
                    // that omit the field — that split is wrong for hyphenated project keys.
                    val projectKey = entity.projectKey.ifBlank { entity.key.substringBefore("-") }
                    PlanData(
                        key = entity.key,
                        name = entity.planName,
                        projectKey = projectKey,
                        projectName = entity.projectName,
                        enabled = true
                    )
                }
                ToolResult.success(
                    data = data,
                    summary = "Search '$query' found ${data.size} plan(s): ${data.joinToString { it.key }}"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to search plans with query '$query': ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error searching plans: ${result.message}",
                    isError = true,
                    hint = "Check Bamboo connection in Settings."
                )
            }
        }
    }

    override suspend fun getPlanShortName(planKey: String): ToolResult<String> {
        val api = client ?: return ToolResult(
            data = "",
            summary = "Bamboo not configured.",
            isError = true,
            hint = "Set Bamboo URL and token in Settings > Tools > Workflow Orchestrator > General."
        )
        return when (val result = api.getPlanInfo(planKey)) {
            is ApiResult.Success -> ToolResult.success(
                data = result.data.shortName,
                summary = "Plan $planKey shortName: ${result.data.shortName.ifBlank { "(empty)" }}"
            )
            is ApiResult.Error -> {
                log.debug("[BambooService] getPlanShortName($planKey) failed: ${result.message}")
                ToolResult(
                    data = "",
                    summary = "Could not fetch shortName for $planKey: ${result.message}",
                    isError = true
                )
            }
        }
    }

    override suspend fun getPlanBranches(planKey: String, repoName: String?): ToolResult<List<PlanBranchData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bamboo not configured. Cannot fetch branches for $planKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getBranches(planKey)) {
            is ApiResult.Success -> {
                val data = result.data.map { dto ->
                    PlanBranchData(
                        key = dto.key,
                        name = dto.name,
                        shortName = dto.shortName,
                        enabled = dto.enabled
                    )
                }
                ToolResult.success(
                    data = data,
                    summary = "Plan $planKey has ${data.size} branch(es): ${data.joinToString { it.name }}"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch branches for $planKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching branches for $planKey: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        ErrorType.NOT_FOUND ->
                            "Verify the plan key is correct."
                        else -> "Check Bamboo connection in Settings."
                    }
                )
            }
        }
    }

    override suspend fun getRunningBuilds(planKey: String, repoName: String?): ToolResult<List<BuildResultData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bamboo not configured. Cannot fetch running builds for $planKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getRunningAndQueuedBuilds(planKey)) {
            is ApiResult.Success -> {
                val builds = result.data.map { dto ->
                    BuildResultData(
                        planKey = dto.plan?.key ?: dto.key.substringBeforeLast("-"),
                        buildNumber = dto.buildNumber,
                        state = dto.state.ifBlank { dto.lifeCycleState },
                        durationSeconds = dto.buildDurationInSeconds,
                        buildResultKey = dto.buildResultKey.ifBlank { dto.key },
                        buildRelativeTime = dto.buildRelativeTime,
                        stages = dto.stages.stage.map { it.toBuildStageData() },
                        lifeCycleState = dto.lifeCycleState  // A-P1-1
                    )
                }
                ToolResult.success(
                    data = builds,
                    summary = "Found ${builds.size} running/queued build(s) for $planKey"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch running builds for $planKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching running builds for $planKey: ${result.message}",
                    isError = true,
                    hint = "Check Bamboo connection in Settings."
                )
            }
        }
    }

    override suspend fun getBuildVariables(resultKey: String): ToolResult<List<PlanVariableData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bamboo not configured. Cannot fetch build variables for $resultKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getBuildVariables(resultKey)) {
            is ApiResult.Success -> {
                val data = result.data.entries.map { PlanVariableData(name = it.key, value = it.value) }
                ToolResult.success(
                    data = data,
                    summary = "Build $resultKey has ${data.size} variable(s): ${data.joinToString { it.name }}"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch build variables for $resultKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching build variables for $resultKey: ${result.message}",
                    isError = true,
                    hint = "Check Bamboo connection in Settings."
                )
            }
        }
    }

    override suspend fun getProjects(): ToolResult<List<ProjectData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bamboo not configured. Cannot fetch projects.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getProjects()) {
            is ApiResult.Success -> {
                val data = result.data.map { dto ->
                    ProjectData(
                        key = dto.key,
                        name = dto.name,
                        description = dto.description
                    )
                }
                ToolResult.success(
                    data = data,
                    summary = "Found ${data.size} project(s): ${data.joinToString { it.key }}"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch projects: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching projects: ${result.message}",
                    isError = true,
                    hint = "Check Bamboo connection in Settings."
                )
            }
        }
    }

    override suspend fun autoDetectPlan(
        repoRoot: java.nio.file.Path?,
        remoteUrl: String,
        branchName: String?,
        preferredMaster: String?
    ): ToolResult<String> {
        if (remoteUrl.isBlank() && repoRoot == null) {
            return ToolResult(
                data = "",
                summary = "No git remote URL or repo root provided",
                isError = true
            )
        }
        val api = client ?: return ToolResult(
            data = "",
            summary = "Bamboo not configured. Cannot auto-detect plan.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )
        // Only pass settings when we have a repoRoot; settings is needed for the deep-scan
        // toggle (Tier 4 gate) which is only reached after T0/T1 run. When repoRoot is null,
        // T0 and T1 are both skipped and the waterfall returns NOT_FOUND immediately (deep
        // scan disabled by default), so settings access is unnecessary and would crash in
        // tests that run without a real IntelliJ project.
        val effectiveSettings = if (repoRoot != null) settings else null
        val planDetection = PlanDetectionService(api, effectiveSettings)
        return when (val result = planDetection.autoDetect(repoRoot, remoteUrl, branchName, preferredMaster)) {
            is com.workflow.orchestrator.core.model.ApiResult.Success -> ToolResult(
                data = result.data,
                summary = "Auto-detected Bamboo plan: ${result.data}"
            )
            is com.workflow.orchestrator.core.model.ApiResult.Error -> ToolResult(
                data = "",
                summary = result.message,
                isError = true
            )
        }
    }

    /**
     * Legacy entry point: delegates to the new waterfall with null repoRoot.
     * T0 and T1 are skipped (no repoRoot); falls to the deep-scan gate.
     * For backward compatibility, passes settings so the deep-scan toggle is respected
     * when a caller explicitly uses this method on a configured service.
     *
     * Note: in unit tests that mock the project without PluginSettings, this path
     * returns NOT_FOUND (deep scan disabled) rather than executing the N+1 scan.
     * Tests that want the N+1 scan behavior should call autoDetectPlan(null, url, null)
     * with a service that has deep scan enabled, or call BambooServiceImplAutoDetectPlanTest
     * which tests autoDetectPlan(url) via testClientOverride.
     */
    override suspend fun autoDetectPlan(gitRemoteUrl: String): ToolResult<String> {
        if (gitRemoteUrl.isBlank()) {
            return ToolResult(
                data = "",
                summary = "No git remote URL provided",
                isError = true
            )
        }
        val api = client ?: return ToolResult(
            data = "",
            summary = "Bamboo not configured. Cannot auto-detect plan.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )
        // Use the legacy N+1 scan directly for the 1-arg overload to preserve backward compat.
        val planDetection = PlanDetectionService(api, null)
        val legacyResult = planDetection.legacyN1ScanPublic(gitRemoteUrl)
        return when (legacyResult) {
            is com.workflow.orchestrator.core.model.ApiResult.Success -> ToolResult(
                data = legacyResult.data,
                summary = "Auto-detected Bamboo plan: ${legacyResult.data}"
            )
            is com.workflow.orchestrator.core.model.ApiResult.Error -> ToolResult(
                data = "",
                summary = legacyResult.message,
                isError = true
            )
        }
    }

    override suspend fun getBuildChanges(resultKey: String): ToolResult<List<BuildChangeData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bamboo not configured. Cannot fetch build changes for $resultKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.getBuildChanges(resultKey)) {
            is ApiResult.Success -> {
                val data = result.data.map { dto ->
                    BuildChangeData(
                        userName = dto.userName,
                        fullName = dto.fullName,
                        comment = dto.comment,
                        changesetId = dto.changesetId,
                        commitUrl = dto.commitUrl,
                        date = dto.date
                    )
                }
                ToolResult.success(
                    data = data,
                    summary = "Build $resultKey has ${data.size} commit(s): " +
                        data.joinToString { "${it.changesetId.take(8)} by ${it.userName}" }
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch build changes for $resultKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching build changes for $resultKey: ${result.message}",
                    isError = true,
                    hint = "Check Bamboo connection in Settings."
                )
            }
        }
    }

    // --- Private helpers ---

    /**
     * Map a raw [BambooStageDto] (from `?expand=stages.stage.results.result` responses)
     * to the `:core` model. Populates [BuildStageData.jobs] when the stage has expanded
     * job results — the `resultKey` carried per job is what the agent uses to fetch
     * per-job logs (see [BuildJobData] kdoc).
     */
    private fun BambooStageDto.toBuildStageData(): BuildStageData =
        BuildStageData(
            name = name,
            state = state.ifBlank { lifeCycleState },
            durationSeconds = buildDurationInSeconds,
            jobs = results.result.map { job ->
                BuildJobData(
                    name = job.plan?.shortName ?: job.buildResultKey,
                    state = job.state.ifBlank { job.lifeCycleState },
                    durationSeconds = job.buildDurationInSeconds,
                    resultKey = job.buildResultKey.ifBlank { job.key }
                )
            }
        )

    private fun mapBuildResult(dto: BambooResultDto): ToolResult<BuildResultData> {
        val stages = dto.stages.stage.map { it.toBuildStageData() }

        // Aggregate test counts from stages (Bamboo doesn't always include them at top level)
        val data = BuildResultData(
            planKey = dto.plan?.key ?: dto.key.substringBeforeLast("-"),
            buildNumber = dto.buildNumber,
            state = dto.state.ifBlank { dto.lifeCycleState },
            durationSeconds = dto.buildDurationInSeconds,
            stages = stages,
            buildResultKey = dto.buildResultKey.ifBlank { dto.key },
            buildRelativeTime = dto.buildRelativeTime,
            lifeCycleState = dto.lifeCycleState  // A-P1-1
        )

        val durationFormatted = TimeFormatter.formatDurationSeconds(data.durationSeconds)
        val stagesSummary = if (stages.isNotEmpty()) {
            stages.joinToString(" | ") { "${it.name}: ${it.state}" }
        } else ""

        val summary = buildString {
            append("${data.planKey} #${data.buildNumber}: ${data.state} ($durationFormatted)")
            if (stagesSummary.isNotEmpty()) append("\nStages: $stagesSummary")
        }

        return ToolResult.success(data = data, summary = summary)
    }

    private fun buildErrorResult(key: String, buildNumber: Int, error: ApiResult.Error): ToolResult<BuildResultData> {
        return ToolResult(
            data = BuildResultData(
                planKey = key, buildNumber = buildNumber,
                state = "ERROR", durationSeconds = 0
            ),
            summary = "Error fetching build $key: ${error.message}",
            isError = true,
            hint = when (error.type) {
                ErrorType.AUTH_FAILED ->
                    "Check your Bamboo token in Settings."
                ErrorType.NOT_FOUND ->
                    "Verify the plan/build key is correct."
                else -> "Check Bamboo connection in Settings."
            }
        )
    }

    private fun notConfiguredError(operation: String): ToolResult<BuildResultData> {
        return ToolResult(
            data = BuildResultData(planKey = "", buildNumber = 0, state = "ERROR", durationSeconds = 0),
            summary = "Bamboo not configured. Cannot $operation.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): BambooServiceImpl =
            project.getService(BambooService::class.java) as BambooServiceImpl
    }
}
