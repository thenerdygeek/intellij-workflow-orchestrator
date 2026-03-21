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
import com.workflow.orchestrator.agent.tools.framework.*
import com.workflow.orchestrator.agent.tools.ide.*
import com.workflow.orchestrator.agent.tools.integration.*
import com.workflow.orchestrator.agent.tools.psi.*
import com.workflow.orchestrator.agent.tools.vcs.*
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@Service(Service.Level.PROJECT)
class AgentService(
    private val project: Project
) : Disposable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Reference to the active AgentController, used for session resume from History tab. */
    var activeController: com.workflow.orchestrator.agent.ui.AgentController? = null

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
        @Volatile var status: String = "running" // running, completed, failed, killed
    )

    val backgroundWorkers = java.util.concurrent.ConcurrentHashMap<String, BackgroundWorker>()

    /**
     * Callback invoked when a background worker completes.
     * The parent session uses this to inject a notification into the conversation.
     */
    @Volatile var onBackgroundWorkerCompleted: ((agentId: String, result: String, isError: Boolean) -> Unit)? = null

    /** Current session directory for transcript storage. Set by ConversationSession. */
    @Volatile var currentSessionDir: java.io.File? = null

    fun getBackgroundWorker(agentId: String): BackgroundWorker? = backgroundWorkers[agentId]

    fun killWorker(agentId: String): Boolean {
        val worker = backgroundWorkers[agentId] ?: return false
        worker.job.cancel()
        worker.status = "killed"
        backgroundWorkers.remove(agentId)
        activeWorkerCount.decrementAndGet()
        return true
    }

    fun getWorkerStatus(agentId: String): String? {
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
            register(GlobFilesTool())

            // PSI tools
            register(FileStructureTool())
            register(FindReferencesTool())
            register(FindDefinitionTool())
            register(TypeHierarchyTool())
            register(CallHierarchyTool())

            // Spring PSI tools
            register(SpringContextTool())
            register(SpringEndpointsTool())
            register(SpringBeanGraphTool())

            // Jira integration tools
            register(JiraGetTicketTool())
            register(JiraGetTransitionsTool())
            register(JiraTransitionTool())
            register(JiraCommentTool())
            register(JiraGetCommentsTool())
            register(JiraLogWorkTool())

            // Bamboo integration tools
            register(BambooBuildTool())
            register(BambooGetBuildTool())
            register(BambooTriggerBuildTool())
            register(BambooGetBuildLogTool())
            register(BambooGetTestResultsTool())

            // SonarQube integration tools
            register(SonarIssuesTool())
            register(SonarQualityGateTool())
            register(SonarCoverageTool())
            register(SonarSearchProjectsTool())
            register(SonarAnalysisTasksTool())
            register(SonarProjectHealthTool())

            // Bitbucket integration tools
            register(BitbucketPrTool())

            // Planning tools
            register(CreatePlanTool())
            register(UpdatePlanStepTool())
            register(AskQuestionsTool())

            // Meta-tools
            register(RequestToolsTool())
            register(SpawnAgentTool())
            register(DelegateTaskTool())
            register(ThinkTool())
            register(SaveMemoryTool())
            register(ActivateSkillTool())
            register(DeactivateSkillTool())

            // IDE tools (diagnostics is the primary — combines syntax + semantic checks)
            register(SemanticDiagnosticsTool()) // name = "diagnostics"
            register(FormatCodeTool())
            register(OptimizeImportsTool())
            register(RunInspectionsTool())
            register(RefactorRenameTool())
            register(ListQuickFixesTool())
            register(CompileModuleTool())
            register(RunTestsTool())

            // VCS / navigation tools
            register(GitStatusTool())
            register(GitBlameTool())
            register(FindImplementationsTool())

            // Framework tools
            register(SpringConfigTool())
            register(JpaEntitiesTool())
            register(ProjectModulesTool())

            // Maven Intelligence (Phase 3)
            register(MavenDependenciesTool())
            register(MavenPropertiesTool())
            register(MavenPluginsTool())
            register(MavenProfilesTool())
            register(SpringVersionTool())

            // Spring PSI Intelligence (Phase 3)
            register(SpringProfilesTool())
            register(SpringRepositoriesTool())
            register(SpringSecurityTool())
            register(SpringScheduledTool())
            register(SpringEventListenersTool())
        }
    }

    val brain: LlmBrain by lazy {
        val settings = AgentSettings.getInstance(project)
        val connections = ConnectionSettings.getInstance()
        val credentialStore = CredentialStore()
        OpenAiCompatBrain(
            sourcegraphUrl = connections.state.sourcegraphUrl.trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) },
            model = settings.state.sourcegraphChatModel ?: "anthropic/claude-sonnet-4"
        )
    }

    fun isConfigured(): Boolean {
        val agentSettings = AgentSettings.getInstance(project)
        val connections = ConnectionSettings.getInstance()
        val credentialStore = CredentialStore()
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
