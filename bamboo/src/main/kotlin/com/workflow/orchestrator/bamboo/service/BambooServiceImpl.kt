package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildStageData
import com.workflow.orchestrator.core.model.bamboo.BuildTriggerData
import com.workflow.orchestrator.core.model.bamboo.FailedTestData
import com.workflow.orchestrator.core.model.bamboo.PlanVariableData
import com.workflow.orchestrator.core.model.bamboo.TestResultsData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto

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

    private val client: BambooApiClient?
        get() {
            val url = settings.connections.bambooUrl.orEmpty().trimEnd('/')
            if (url.isBlank()) return null
            if (url != cachedBaseUrl || cachedClient == null) {
                cachedBaseUrl = url
                cachedClient = BambooApiClient(
                    baseUrl = url,
                    tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) },
                    connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
                    readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
                )
            }
            return cachedClient
        }

    override suspend fun getLatestBuild(planKey: String): ToolResult<BuildResultData> {
        val api = client ?: return notConfiguredError("fetch latest build for $planKey")

        return when (val result = api.getLatestResult(planKey)) {
            is ApiResult.Success -> mapBuildResult(result.data)
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch latest build for $planKey: ${result.message}")
                buildErrorResult(planKey, 0, result)
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
        planKey: String,
        variables: Map<String, String>
    ): ToolResult<BuildTriggerData> {
        val api = client ?: return ToolResult(
            data = BuildTriggerData(buildKey = "", buildNumber = 0, link = ""),
            summary = "Bamboo not configured. Cannot trigger build for $planKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        return when (val result = api.triggerBuild(planKey, variables)) {
            is ApiResult.Success -> {
                val qr = result.data
                val data = BuildTriggerData(
                    buildKey = qr.buildResultKey,
                    buildNumber = qr.buildNumber,
                    link = "${cachedBaseUrl}/browse/${qr.buildResultKey}"
                )
                ToolResult.success(
                    data = data,
                    summary = "Build triggered: ${data.buildKey} (#${data.buildNumber})\nLink: ${data.link}"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to trigger build for $planKey: ${result.message}")
                ToolResult(
                    data = BuildTriggerData(buildKey = "", buildNumber = 0, link = ""),
                    summary = "Error triggering build for $planKey: ${result.message}",
                    isError = true,
                    hint = when (result.type) {
                        com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED ->
                            "Check your Bamboo token in Settings."
                        com.workflow.orchestrator.core.model.ErrorType.FORBIDDEN ->
                            "You may not have permission to trigger this plan."
                        com.workflow.orchestrator.core.model.ErrorType.NOT_FOUND ->
                            "Verify the plan key is correct (e.g., PROJ-PLAN)."
                        else -> "Check Bamboo connection in Settings."
                    }
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
                        com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED ->
                            "Check your Bamboo token in Settings."
                        com.workflow.orchestrator.core.model.ErrorType.FORBIDDEN ->
                            "You may not have permission to restart this build."
                        com.workflow.orchestrator.core.model.ErrorType.NOT_FOUND ->
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

        return when (val result = api.getVariables(planKey)) {
            is ApiResult.Success -> {
                val data = result.data.map { PlanVariableData(name = it.name, value = it.value) }
                ToolResult.success(
                    data = data,
                    summary = "Plan $planKey has ${data.size} variable(s): ${data.joinToString { it.name }}"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch plan variables for $planKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching plan variables for $planKey: ${result.message}",
                    isError = true,
                    hint = "Check Bamboo connection in Settings."
                )
            }
        }
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

        return when (val result = api.triggerBuild(planKey, variables, stage)) {
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
                        com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED ->
                            "Check your Bamboo token in Settings."
                        com.workflow.orchestrator.core.model.ErrorType.FORBIDDEN ->
                            "You may not have permission to trigger this plan."
                        else -> "Check Bamboo connection in Settings."
                    }
                )
            }
        }
    }

    // --- Private helpers ---

    private fun mapBuildResult(dto: BambooResultDto): ToolResult<BuildResultData> {
        val stages = dto.stages.stage.map { stage ->
            BuildStageData(
                name = stage.name,
                state = stage.state.ifBlank { stage.lifeCycleState },
                durationSeconds = stage.buildDurationInSeconds
            )
        }

        // Aggregate test counts from stages (Bamboo doesn't always include them at top level)
        val data = BuildResultData(
            planKey = dto.plan?.key ?: dto.key.substringBeforeLast("-"),
            buildNumber = dto.buildNumber,
            state = dto.state.ifBlank { dto.lifeCycleState },
            durationSeconds = dto.buildDurationInSeconds,
            stages = stages
        )

        val durationFormatted = formatDuration(data.durationSeconds)
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
                com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED ->
                    "Check your Bamboo token in Settings."
                com.workflow.orchestrator.core.model.ErrorType.NOT_FOUND ->
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

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (mins > 0) append("${mins}m ")
            if (secs > 0 || isEmpty()) append("${secs}s")
        }.trim()
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): BambooServiceImpl =
            project.getService(BambooService::class.java) as BambooServiceImpl
    }
}
