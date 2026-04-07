package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildStageData
import com.workflow.orchestrator.core.model.bamboo.BuildTriggerData
import com.workflow.orchestrator.core.model.bamboo.FailedTestData
import com.workflow.orchestrator.core.model.bamboo.ArtifactData
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

    override suspend fun getLatestBuild(planKey: String, branch: String?, repoName: String?): ToolResult<BuildResultData> {
        val api = client ?: return notConfiguredError("fetch latest build for $planKey")

        return when (val result = api.getLatestResult(planKey, branch = branch)) {
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
                        ErrorType.AUTH_FAILED ->
                            "Check your Bamboo token in Settings."
                        ErrorType.FORBIDDEN ->
                            "You may not have permission to trigger this plan."
                        ErrorType.NOT_FOUND ->
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
                        ErrorType.AUTH_FAILED ->
                            "Check your Bamboo token in Settings."
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

    override suspend fun getRecentBuilds(planKey: String, maxResults: Int, branch: String?, repoName: String?): ToolResult<List<BuildResultData>> {
        val api = client ?: return ToolResult(
            data = emptyList(),
            summary = "Bamboo not configured. Cannot fetch recent builds for $planKey.",
            isError = true,
            hint = "Set up Bamboo connection in Settings > Tools > Workflow Orchestrator > General."
        )

        // If branch is specified, resolve the branch plan key first
        val effectivePlanKey = if (branch != null) {
            when (val branchResult = api.getBranches(planKey)) {
                is ApiResult.Success -> {
                    val branchDto = branchResult.data.find { it.name.equals(branch, ignoreCase = true) }
                    if (branchDto != null) {
                        branchDto.key
                    } else {
                        return ToolResult(
                            data = emptyList(),
                            summary = "Branch '$branch' not found in plan $planKey.",
                            isError = true,
                            hint = "Available branches: ${branchResult.data.joinToString { it.name }}. Check the branch name and try again."
                        )
                    }
                }
                is ApiResult.Error -> {
                    log.warn("[BambooService] Failed to fetch branches for $planKey: ${branchResult.message}")
                    return ToolResult(
                        data = emptyList(),
                        summary = "Error fetching branches for $planKey: ${branchResult.message}",
                        isError = true,
                        hint = "Check Bamboo connection in Settings."
                    )
                }
            }
        } else {
            planKey
        }

        return when (val result = api.getRecentResults(effectivePlanKey, maxResults)) {
            is ApiResult.Success -> {
                val builds = result.data.map { dto ->
                    BuildResultData(
                        planKey = dto.plan?.key ?: dto.key.substringBeforeLast("-"),
                        buildNumber = dto.buildNumber,
                        state = dto.state.ifBlank { dto.lifeCycleState },
                        durationSeconds = dto.buildDurationInSeconds,
                        buildResultKey = dto.buildResultKey.ifBlank { dto.key },
                        buildRelativeTime = dto.buildRelativeTime,
                        stages = dto.stages.stage.map { stage ->
                            BuildStageData(
                                name = stage.name,
                                state = stage.state.ifBlank { stage.lifeCycleState },
                                durationSeconds = stage.buildDurationInSeconds
                            )
                        }
                    )
                }
                ToolResult.success(
                    data = builds,
                    summary = "Found ${builds.size} recent builds for $planKey"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BambooService] Failed to fetch recent builds for $planKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching recent builds for $planKey: ${result.message}",
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
                    PlanData(
                        key = dto.key,
                        name = dto.name,
                        projectKey = dto.key.substringBefore("-"),
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
                    PlanData(
                        key = entity.key,
                        name = entity.planName,
                        projectKey = entity.key.substringBefore("-"),
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
                        stages = dto.stages.stage.map { stage ->
                            BuildStageData(
                                name = stage.name,
                                state = stage.state.ifBlank { stage.lifeCycleState },
                                durationSeconds = stage.buildDurationInSeconds
                            )
                        }
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
