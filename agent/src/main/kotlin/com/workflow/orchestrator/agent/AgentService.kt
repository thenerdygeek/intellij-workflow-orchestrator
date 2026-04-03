package com.workflow.orchestrator.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.brain.OpenAiCompatBrain
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.runtime.AgentDefinitionRegistry
import com.workflow.orchestrator.agent.runtime.AgentRollbackManager
import com.workflow.orchestrator.agent.runtime.ChangeLedger
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
    /** Shared scope for background workers — cancelled in [dispose] so detached coroutines don't leak. */
    val backgroundWorkerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val credentialStore = CredentialStore()

    /** Reference to the active AgentController, used for session resume from History tab. */
    var activeController: com.workflow.orchestrator.agent.ui.AgentController? = null

    /** Resume an interrupted session by delegating to the active controller. */
    fun resumeSession(sessionId: String) {
        activeController?.resumeSession(sessionId)
    }

    fun resumeRalphLoop(originalPrompt: String) {
        activeController?.executeTask(originalPrompt)
    }

    /**
     * Cohesive session state — groups all per-session managers into one object.
     * New code should prefer reading from this; old @Volatile fields below are
     * kept for backward compatibility until all consumers are migrated.
     */
    @Volatile var activeScope: com.workflow.orchestrator.agent.runtime.SessionScope? = null

    /** Plan manager for the current agent session, set by SingleAgentSession. */
    @Volatile var currentPlanManager: PlanManager? = null

    /** Question manager for the current agent session, set by AgentController. */
    @Volatile var currentQuestionManager: QuestionManager? = null

    /** Skill manager for the current agent session, set by AgentController. */
    @Volatile var currentSkillManager: SkillManager? = null

    /** Change ledger for the current session. Set by ConversationSession/AgentController.
     *  COMPRESSION: Tools use this to record changes that feed the changeLedgerAnchor. */
    @Volatile var currentChangeLedger: ChangeLedger? = null

    /** Rollback manager for the current session. Set by AgentOrchestrator. */
    @Volatile var currentRollbackManager: AgentRollbackManager? = null

    /** Ralph Loop orchestrator — manages iterative self-improvement loops. Wired by AgentController. */
    @Volatile var ralphOrchestrator: com.workflow.orchestrator.agent.ralph.RalphLoopOrchestrator? = null

    /** Ralph iteration context to inject into next session's system prompt. Set by AgentController, consumed by ConversationSession.create(). */
    @Volatile var ralphIterationContext: String? = null

    /** Current iteration number in the ReAct loop. Set by SingleAgentSession. */
    @Volatile var currentIteration: Int? = null

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

    /** Per-session file ownership registry. Created by ConversationSession, shared across all workers. */
    @Volatile var fileOwnershipRegistry: com.workflow.orchestrator.agent.runtime.FileOwnershipRegistry? = null

    /** Per-session message bus. Created by ConversationSession, shared across all workers. */
    @Volatile var workerMessageBus: com.workflow.orchestrator.agent.runtime.WorkerMessageBus? = null

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

    /** Context bridge for the current agent session — tools use this to inject mid-loop system messages. */
    @Volatile var currentContextBridge: com.workflow.orchestrator.agent.context.EventSourcedContextBridge? = null

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
        fileOwnershipRegistry?.releaseAll(agentId)
        workerMessageBus?.closeInbox(agentId)
        return true
    }

    /** Kill all background workers — called when user presses Stop to ensure nothing keeps running. */
    fun killAllWorkers() {
        val ids = backgroundWorkers.keys.toList()
        for (id in ids) {
            killWorker(id)
        }
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
            register(CreateFileTool())
            register(EditFileTool())
            register(SearchCodeTool())
            register(RunCommandTool())
            register(SendStdinTool())
            register(KillProcessTool())
            register(AskUserInputTool())
            register(GlobFilesTool())
            register(ProjectContextTool())

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

            // Spring integration — single meta-tool replacing 15 individual Spring/Boot/JPA tools
            register(SpringTool())

            // Jira integration — single meta-tool replacing 15 individual jira_* tools
            register(JiraTool())

            // Bamboo integration — split into builds (status/trigger/stop/logs/tests) and plans (list/search/variables/branches)
            register(BambooBuildsTool())
            register(BambooPlansTool())

            // SonarQube integration — single meta-tool replacing 9 individual sonar_* tools
            register(SonarTool())

            // Bitbucket integration — split into PR management, code review, and repo operations
            register(BitbucketPrTool())
            register(BitbucketReviewTool())
            register(BitbucketRepoTool())

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
            register(ThinkTool())
            register(CurrentTimeTool())
            register(WorkerCompleteTool())
            register(SendMessageToParentTool())
            register(CoreMemoryReadTool())
            register(CoreMemoryAppendTool())
            register(CoreMemoryReplaceTool())
            register(ArchivalMemoryInsertTool())
            register(ArchivalMemorySearchTool())
            register(ConversationSearchTool())
            register(SkillTool())

            // Change tracking tools
            register(ListChangesTool())
            register(RollbackChangesTool())
            register(RevertFileTool())

            // IDE tools (diagnostics is the primary — combines syntax + semantic checks)
            register(SemanticDiagnosticsTool()) // name = "diagnostics"
            register(ProblemViewTool()) // name = "problem_view"
            register(FormatCodeTool())
            register(OptimizeImportsTool())
            register(RunInspectionsTool())
            register(RefactorRenameTool())
            register(ListQuickFixesTool())

            // Runtime integration — split into config (run configurations) and exec (processes/tests/compile)
            register(RuntimeConfigTool())
            register(RuntimeExecTool())
            register(CoverageTool())
            // Debug integration — split into breakpoints, stepping, and inspection
            register(DebugBreakpointsTool(debugController))
            register(DebugStepTool(debugController))
            register(DebugInspectTool(debugController))

            // VCS integration — single meta-tool replacing 11 individual git/vcs tools
            register(GitTool())
            register(FindImplementationsTool())

            // Build system integration — single meta-tool replacing 11 Maven/Gradle/module tools
            register(BuildTool())
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

    /** Create a lightweight brain using the cheapest available model (Haiku preferred). */
    fun cheapBrain(): LlmBrain? {
        val connections = ConnectionSettings.getInstance()
        val cheapModel = ModelCache.pickCheapest(ModelCache.getCached())?.id ?: return null
        return OpenAiCompatBrain(
            sourcegraphUrl = connections.state.sourcegraphUrl.trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) },
            model = cheapModel
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
        backgroundWorkerScope.cancel()
    }

    companion object {
        /** Whether plan mode is currently active — single source of truth.
         *  Read by SingleAgentSession to filter tools before LLM calls.
         *  Set by EnablePlanModeTool, AgentController (UI toggle), and PlanManager (on approval). */
        val planModeActive = java.util.concurrent.atomic.AtomicBoolean(false)

        fun getInstance(project: Project): AgentService {
            return project.service<AgentService>()
        }
    }
}
