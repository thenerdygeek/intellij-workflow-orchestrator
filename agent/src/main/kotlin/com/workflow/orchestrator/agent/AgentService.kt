package com.workflow.orchestrator.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.brain.OpenAiCompatBrain
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.runtime.AgentDefinitionRegistry
import com.workflow.orchestrator.agent.runtime.PlanManager
import com.workflow.orchestrator.agent.runtime.QuestionManager
import com.workflow.orchestrator.agent.runtime.SkillManager
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.builtin.*
import com.workflow.orchestrator.agent.tools.config.*
import com.workflow.orchestrator.agent.tools.debug.*
import com.workflow.orchestrator.agent.tools.framework.*
import com.workflow.orchestrator.agent.tools.ide.*
import com.workflow.orchestrator.agent.tools.integration.*
import com.workflow.orchestrator.agent.tools.psi.*
import com.workflow.orchestrator.agent.tools.runtime.*
import com.workflow.orchestrator.agent.tools.vcs.*
import com.workflow.orchestrator.core.ai.ModelCache
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.agent.runtime.AgentFileLogger
import com.workflow.orchestrator.core.settings.ConnectionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

enum class WorkerStatus { RUNNING, COMPLETED, FAILED, KILLED }

@Service(Service.Level.PROJECT)
class AgentService(
    private val project: Project
) : Disposable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val credentialStore = CredentialStore()

    /** Reference to the active AgentController, used for session resume from History tab. */
    var activeController: com.workflow.orchestrator.agent.ui.AgentController? = null

    /** Resume an interrupted session by delegating to the active controller. */
    fun resumeSession(sessionId: String) {
        activeController?.resumeSession(sessionId)
    }

    /** Plan manager for the current agent session, set by SingleAgentSession. */
    @Volatile var currentPlanManager: PlanManager? = null

    /** Question manager for the current agent session, set by AgentController. */
    @Volatile var currentQuestionManager: QuestionManager? = null

    /** Skill manager for the current agent session, set by AgentController. */
    @Volatile var currentSkillManager: SkillManager? = null

    /** Agent definition registry for custom subagents, set by ConversationSession. */
    @Volatile var agentDefinitionRegistry: AgentDefinitionRegistry? = null

    /** Tools requested by LLM via request_tools, expanded on next iteration. */
    val pendingToolActivations = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** Number of currently active worker sessions (max 5). */
    val activeWorkerCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** Total tokens consumed across all workers in the current session. */
    val totalSessionTokens = java.util.concurrent.atomic.AtomicLong(0)

    /** Tracks delegation attempts per retry key to limit retries (max 2 per key). */
    val delegationAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Tracks background workers for lifecycle management.
     * Key: agentId, Value: BackgroundWorker with job handle and metadata.
     */
    data class BackgroundWorker(
        val agentId: String,
        val job: kotlinx.coroutines.Job,
        val subagentType: String,
        val description: String,
        val startedAt: Long = System.currentTimeMillis(),
        @Volatile var status: WorkerStatus = WorkerStatus.RUNNING
    )

    val backgroundWorkers = java.util.concurrent.ConcurrentHashMap<String, BackgroundWorker>()

    /** Shared debug controller — registered with Disposer for proper lifecycle management. */
    val debugController: AgentDebugController by lazy {
        AgentDebugController(project).also {
            Disposer.register(this, it)
        }
    }

    /** Structured JSONL file logger for all agent activity — one instance per project, lifecycle tied to the service. */
    val agentFileLogger: AgentFileLogger by lazy {
        AgentFileLogger(project, project.basePath ?: System.getProperty("user.home"))
    }

    /**
     * Callback invoked when a background worker completes.
     * The parent session uses this to inject a notification into the conversation.
     */
    @Volatile var onBackgroundWorkerCompleted: ((agentId: String, result: String, isError: Boolean) -> Unit)? = null

    /** Current session directory for transcript storage. Set by ConversationSession. */
    @Volatile var currentSessionDir: java.io.File? = null

    /** ContextManager for the current agent session — tools use this to inject mid-loop system messages. */
    @Volatile var currentContextManager: com.workflow.orchestrator.agent.context.ContextManager? = null

    /** Callback invoked when the LLM enables plan mode via enable_plan_mode tool. */
    @Volatile var onPlanModeEnabled: ((Boolean) -> Unit)? = null

    /**
     * Callbacks fired by [com.workflow.orchestrator.agent.tools.builtin.SpawnAgentTool] and
     * [com.workflow.orchestrator.agent.runtime.WorkerSession] so the UI can render a live
     * sub-agent task boundary card.
     *
     * Wired by [com.workflow.orchestrator.agent.ui.AgentController] to [AgentCefPanel] bridge methods.
     */
    data class SubAgentCallbacks(
        /** Sub-agent just spawned — create the boundary card. */
        val onSpawn: (agentId: String, label: String) -> Unit,
        /** Worker started a new ReAct iteration. */
        val onIteration: (agentId: String, iteration: Int) -> Unit,
        /** Worker called a tool — add it to the active tool chain. */
        val onToolCall: (agentId: String, toolName: String, toolArgs: String) -> Unit,
        /** Tool finished — update the tool card with result + duration. */
        val onToolResult: (agentId: String, toolName: String, result: String, durationMs: Long, isError: Boolean) -> Unit,
        /** Worker produced a text message (nudge response or worker_complete) — flush active tool chain into a message. */
        val onMessage: (agentId: String, textContent: String) -> Unit,
        /** Worker finished — mark the boundary card complete/error and store the summary. */
        val onComplete: (agentId: String, textContent: String, tokensUsed: Int, isError: Boolean) -> Unit
    )

    @Volatile var subAgentCallbacks: SubAgentCallbacks? = null

    fun getBackgroundWorker(agentId: String): BackgroundWorker? = backgroundWorkers[agentId]

    fun killWorker(agentId: String): Boolean {
        val worker = backgroundWorkers[agentId] ?: return false
        worker.job.cancel()
        worker.status = WorkerStatus.KILLED
        backgroundWorkers.remove(agentId)
        activeWorkerCount.decrementAndGet()
        return true
    }

    fun getWorkerStatus(agentId: String): WorkerStatus? {
        return backgroundWorkers[agentId]?.status
    }

    fun listBackgroundWorkers(): List<BackgroundWorker> {
        return backgroundWorkers.values.toList().sortedByDescending { it.startedAt }
    }

    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry().apply {
            // Builtin tools
            register(ReadFileTool())
            register(EditFileTool())
            register(SearchCodeTool())
            register(RunCommandTool())
            register(SendStdinTool())
            register(KillProcessTool())
            register(AskUserInputTool())
            register(GlobFilesTool())

            // PSI tools
            register(FileStructureTool())
            register(FindReferencesTool())
            register(FindDefinitionTool())
            register(TypeHierarchyTool())
            register(CallHierarchyTool())
            register(GetMethodBodyTool())
            register(GetAnnotationsTool())
            register(TypeInferenceTool())
            register(StructuralSearchTool())
            register(DataFlowAnalysisTool())
            register(ReadWriteAccessTool())
            register(TestFinderTool())

            // Spring PSI tools
            register(SpringContextTool())
            register(SpringEndpointsTool())
            register(SpringBootEndpointsTool())
            register(SpringBeanGraphTool())

            // Jira integration — single meta-tool replacing 15 individual jira_* tools
            register(JiraTool())

            // Bamboo integration — single meta-tool replacing 18 individual bamboo_* tools
            register(BambooTool())

            // SonarQube integration — single meta-tool replacing 9 individual sonar_* tools
            register(SonarTool())

            // Bitbucket integration — single meta-tool replacing 26 individual bitbucket_* tools
            register(BitbucketTool())

            // Database tools (read-only agent access to local/docker/QA/sandbox databases)
            register(com.workflow.orchestrator.agent.tools.database.DbListProfilesTool())
            register(com.workflow.orchestrator.agent.tools.database.DbQueryTool())
            register(com.workflow.orchestrator.agent.tools.database.DbSchemaTool())

            // Planning tools
            register(EnablePlanModeTool())
            register(CreatePlanTool())
            register(UpdatePlanStepTool())
            register(AskQuestionsTool())

            // Meta-tools
            register(RequestToolsTool())
            register(SpawnAgentTool())
            register(DelegateTaskTool())
            register(ThinkTool())
            register(WorkerCompleteTool())
            register(CoreMemoryReadTool())
            register(CoreMemoryAppendTool())
            register(CoreMemoryReplaceTool())
            register(ArchivalMemoryInsertTool())
            register(ArchivalMemorySearchTool())
            register(ConversationSearchTool())
            register(ActivateSkillTool())
            register(DeactivateSkillTool())

            // IDE tools (diagnostics is the primary — combines syntax + semantic checks)
            register(SemanticDiagnosticsTool()) // name = "diagnostics"
            register(ProblemViewTool()) // name = "problem_view"
            register(FormatCodeTool())
            register(OptimizeImportsTool())
            register(RunInspectionsTool())
            register(RefactorRenameTool())
            register(ListQuickFixesTool())
            register(CompileModuleTool())
            register(RunTestsTool())

            // Runtime & Debug tools
            register(GetRunConfigurationsTool())
            register(GetRunningProcessesTool())
            register(GetRunOutputTool())
            register(GetTestResultsTool())
            // Debug integration — single meta-tool replacing 24 individual debug tools
            register(DebugTool(debugController))
            register(CreateRunConfigTool())
            register(ModifyRunConfigTool())
            register(DeleteRunConfigTool())

            // VCS integration — single meta-tool replacing 11 individual git/vcs tools
            register(GitTool())
            register(FindImplementationsTool())

            // Framework tools
            register(SpringConfigTool())
            register(JpaEntitiesTool())
            register(ProjectModulesTool())
            register(ModuleDependencyGraphTool())

            // Maven Intelligence (Phase 3)
            register(MavenDependenciesTool())
            register(MavenDependencyTreeTool())
            register(MavenPropertiesTool())
            register(MavenPluginsTool())
            register(MavenProfilesTool())
            register(SpringVersionTool())

            // Gradle Intelligence (Phase 3)
            register(GradleDependenciesTool())
            register(GradleTasksTool())
            register(GradlePropertiesTool())

            // Spring PSI Intelligence (Phase 3)
            register(SpringProfilesTool())
            register(SpringRepositoriesTool())
            register(SpringSecurityTool())
            register(SpringScheduledTool())
            register(SpringEventListenersTool())
            register(SpringBootAutoConfigTool())
            register(SpringBootConfigPropertiesTool())
            register(SpringBootActuatorTool())
        }
    }

    val brain: LlmBrain by lazy {
        val settings = AgentSettings.getInstance(project)
        val connections = ConnectionSettings.getInstance()
        val model = settings.state.sourcegraphChatModel
            ?: ModelCache.pickBest(ModelCache.getCached())?.id
            ?: throw IllegalStateException("No model configured. Open Settings > Agent and load models.")
        OpenAiCompatBrain(
            sourcegraphUrl = connections.state.sourcegraphUrl.trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) },
            model = model
        )
    }

    fun isConfigured(): Boolean {
        val agentSettings = AgentSettings.getInstance(project)
        val connections = ConnectionSettings.getInstance()
        return agentSettings.state.agentEnabled &&
            connections.state.sourcegraphUrl.isNotBlank() &&
            !credentialStore.getToken(ServiceType.SOURCEGRAPH).isNullOrBlank()
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): AgentService {
            return project.service<AgentService>()
        }
    }
}
