package com.workflow.orchestrator.bamboo.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.*
import java.io.OutputStream

class BambooBuildRunState(
    private val environment: ExecutionEnvironment,
    private val configuration: BambooBuildRunConfiguration
) : RunProfileState {

    private val log = Logger.getInstance(BambooBuildRunState::class.java)

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val processHandler = BambooBuildProcessHandler(environment, configuration)
        val console = com.intellij.execution.filters.TextConsoleBuilderFactory.getInstance()
            .createBuilder(environment.project)
            .console
        console.attachToProcess(processHandler)
        processHandler.startNotify()
        return DefaultExecutionResult(console, processHandler)
    }
}

class BambooBuildProcessHandler(
    private val environment: ExecutionEnvironment,
    private val configuration: BambooBuildRunConfiguration
) : ProcessHandler() {

    private val log = Logger.getInstance(BambooBuildProcessHandler::class.java)
    private val parentJob = SupervisorJob()
    private val scope = CoroutineScope(parentJob + Dispatchers.IO)

    init {
        // Cancel coroutine scope when project is disposed to prevent leaks
        Disposer.register(environment.project, { parentJob.cancel() })
    }

    override fun startNotify() {
        super.startNotify()
        scope.launch { runBuild() }
    }

    private suspend fun runBuild() {
        val project = environment.project
        val settings = PluginSettings.getInstance(project)
        val bambooUrl = settings.state.bambooUrl.orEmpty().trimEnd('/')

        if (bambooUrl.isBlank()) {
            printOutput("ERROR: Bamboo URL is not configured. Go to Settings > Tools > Workflow Orchestrator.\n")
            destroyProcess()
            return
        }

        val planKey = configuration.getPlanKey()
        if (planKey.isBlank()) {
            printOutput("ERROR: No Bamboo plan key configured. Set it in the run configuration or plugin settings.\n")
            destroyProcess()
            return
        }

        val branch = configuration.getBranch()
        val variables = configuration.getBuildVariables()

        // Bamboo branch builds use planKey + branch suffix (e.g., PROJ-PLAN0 for branches)
        // Pass the branch as a build variable so the Bamboo plan can use it
        val effectiveVariables = if (branch.isNotBlank()) {
            variables + ("bamboo.planRepository.1.branch" to branch)
        } else {
            variables
        }

        printOutput("=== Bamboo Build Runner ===\n")
        printOutput("Plan Key: $planKey\n")
        if (branch.isNotBlank()) printOutput("Branch: $branch\n")
        if (effectiveVariables.isNotEmpty()) {
            printOutput("Variables:\n")
            effectiveVariables.forEach { (k, v) -> printOutput("  $k = $v\n") }
        }
        printOutput("\n")

        val credentialStore = CredentialStore()
        val client = BambooApiClient(
            baseUrl = bambooUrl,
            tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) },
            connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
            readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
        )

        printOutput("Triggering build...\n")

        when (val triggerResult = client.triggerBuild(planKey, effectiveVariables)) {
            is ApiResult.Success -> {
                val resultKey = triggerResult.data.buildResultKey
                val buildNumber = triggerResult.data.buildNumber
                printOutput("Build triggered successfully. Result key: $resultKey (build #$buildNumber)\n\n")
                pollBuildStatus(client, resultKey)
            }
            is ApiResult.Error -> {
                printOutput("ERROR: Failed to trigger build: ${triggerResult.message}\n")
                destroyProcess()
            }
        }
    }

    private suspend fun pollBuildStatus(client: BambooApiClient, resultKey: String) {
        printOutput("Polling build status every 15 seconds...\n\n")

        while (isProcessTerminating.not() && isProcessTerminated.not()) {
            when (val result = client.getBuildResult(resultKey)) {
                is ApiResult.Success -> {
                    val dto = result.data
                    val state = dto.state
                    val lifecycle = dto.lifeCycleState

                    printOutput("[${java.time.LocalTime.now()}] Status: $lifecycle | Result: $state\n")

                    // Print stage info
                    for (stage in dto.stages.stage) {
                        printOutput("  Stage '${stage.name}': ${stage.lifeCycleState} (${stage.state})\n")
                    }

                    if (lifecycle == "Finished") {
                        printOutput("\n=== Build Finished ===\n")
                        printOutput("Final result: $state\n")
                        printOutput("Duration: ${dto.buildDurationInSeconds}s\n")
                        destroyProcess()
                        return
                    }
                }
                is ApiResult.Error -> {
                    printOutput("WARNING: Failed to get build status: ${result.message}\n")
                }
            }

            delay(15_000)
        }
    }

    private fun printOutput(text: String) {
        notifyTextAvailable(text, ProcessOutputTypes.STDOUT)
    }

    override fun destroyProcessImpl() {
        scope.cancel()
        notifyProcessTerminated(0)
    }

    override fun detachProcessImpl() {
        scope.cancel()
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null
}
