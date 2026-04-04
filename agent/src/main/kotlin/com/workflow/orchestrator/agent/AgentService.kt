package com.workflow.orchestrator.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import com.workflow.orchestrator.agent.prompt.SystemPrompt
import com.workflow.orchestrator.agent.session.Session
import com.workflow.orchestrator.agent.session.SessionStatus
import com.workflow.orchestrator.agent.session.SessionStore
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.builtin.*
import com.workflow.orchestrator.agent.tools.config.*
import com.workflow.orchestrator.agent.tools.database.*
import com.workflow.orchestrator.agent.tools.debug.AgentDebugController
import com.workflow.orchestrator.agent.tools.debug.DebugBreakpointsTool
import com.workflow.orchestrator.agent.tools.debug.DebugInspectTool
import com.workflow.orchestrator.agent.tools.debug.DebugStepTool
import com.workflow.orchestrator.agent.tools.framework.*
import com.workflow.orchestrator.agent.tools.ide.*
import com.workflow.orchestrator.agent.tools.integration.*
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.agent.tools.psi.*
import com.workflow.orchestrator.agent.tools.runtime.*
import com.workflow.orchestrator.agent.tools.vcs.*
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.ai.OpenAiCompatBrain
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central agent service — wires the AgentLoop, ToolRegistry, ContextManager,
 * SessionStore, and LLM brain together. Exposes [executeTask] for the UI layer.
 *
 * IntelliJ project-level service: one instance per open project.
 */
@Service(Service.Level.PROJECT)
class AgentService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(AgentService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val registry = ToolRegistry()
    val sessionStore: SessionStore

    private var brain: LlmBrain? = null
    private var currentJob: Job? = null
    private var currentLoop: AgentLoop? = null
    private var debugController: AgentDebugController? = null

    /** Tool names that are blocked in plan mode (write/mutate tools). */
    private val writeToolNames = setOf(
        "edit_file", "create_file", "run_command", "revert_file",
        "kill_process", "send_stdin", "format_code", "optimize_imports",
        "refactor_rename"
    )

    init {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val agentDir = ProjectIdentifier.agentDir(basePath)
        sessionStore = SessionStore(agentDir)
        registerAllTools()
    }

    // ── Brain ──────────────────────────────────────────────────────────────

    /**
     * Creates or returns the cached LLM brain.
     * Uses AgentSettings for the model (falls back to LlmBrainFactory auto-resolution).
     */
    private suspend fun getOrCreateBrain(): LlmBrain {
        brain?.let { return it }

        val agentSettings = AgentSettings.getInstance(project)
        val connections = ConnectionSettings.getInstance()
        val sgUrl = connections.state.sourcegraphUrl.trimEnd('/')
        val credentialStore = CredentialStore()
        val tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) }

        val model = agentSettings.state.sourcegraphChatModel
        if (!model.isNullOrBlank() && sgUrl.isNotBlank()) {
            val newBrain = OpenAiCompatBrain(
                sourcegraphUrl = sgUrl,
                tokenProvider = tokenProvider,
                model = model
            )
            brain = newBrain
            return newBrain
        }

        // Fall back to factory auto-resolution
        val resolved = LlmBrainFactory.create(project)
        brain = resolved
        return resolved
    }

    // ── Tool Registration ──────────────────────────────────────────────────

    private fun registerAllTools() {
        // Builtin tools
        safeRegister { ReadFileTool() }
        safeRegister { EditFileTool() }
        safeRegister { CreateFileTool() }
        safeRegister { SearchCodeTool() }
        safeRegister { GlobFilesTool() }
        safeRegister { RunCommandTool() }
        safeRegister { KillProcessTool() }
        safeRegister { SendStdinTool() }
        safeRegister { RevertFileTool() }
        safeRegister { AttemptCompletionTool() }
        safeRegister { ThinkTool() }
        safeRegister { AskQuestionsTool() }
        safeRegister { AskUserInputTool() }
        safeRegister { ProjectContextTool() }
        safeRegister { CurrentTimeTool() }

        // VCS tools
        safeRegister { GitTool() }
        safeRegister { GitStatusTool() }
        safeRegister { GitDiffTool() }
        safeRegister { GitLogTool() }
        safeRegister { GitBranchesTool() }
        safeRegister { GitBlameTool() }
        safeRegister { GitShowCommitTool() }
        safeRegister { GitShowFileTool() }
        safeRegister { GitStashListTool() }
        safeRegister { GitFileHistoryTool() }
        safeRegister { GitMergeBaseTool() }
        safeRegister { ChangelistShelveTool() }

        // PSI / code intelligence tools
        safeRegister { FindDefinitionTool() }
        safeRegister { FindReferencesTool() }
        safeRegister { FindImplementationsTool() }
        safeRegister { FileStructureTool() }
        safeRegister { TypeHierarchyTool() }
        safeRegister { CallHierarchyTool() }
        safeRegister { TypeInferenceTool() }
        safeRegister { DataFlowAnalysisTool() }
        safeRegister { GetMethodBodyTool() }
        safeRegister { GetAnnotationsTool() }
        safeRegister { TestFinderTool() }
        safeRegister { StructuralSearchTool() }
        safeRegister { ReadWriteAccessTool() }

        // IDE tools
        safeRegister { FormatCodeTool() }
        safeRegister { OptimizeImportsTool() }
        safeRegister { RefactorRenameTool() }
        safeRegister { SemanticDiagnosticsTool() }
        safeRegister { RunInspectionsTool() }
        safeRegister { ProblemViewTool() }
        safeRegister { ListQuickFixesTool() }

        // Database tools
        safeRegister { DbListProfilesTool() }
        safeRegister { DbQueryTool() }
        safeRegister { DbSchemaTool() }

        // Framework tools
        safeRegister { BuildTool() }
        safeRegister { SpringTool() }

        // Run config tools
        safeRegister { CreateRunConfigTool() }
        safeRegister { ModifyRunConfigTool() }
        safeRegister { DeleteRunConfigTool() }

        // Integration tools (Jira, Bamboo, Bitbucket, Sonar)
        safeRegister { JiraTool() }
        safeRegister { BambooBuildsTool() }
        safeRegister { BambooPlansTool() }
        safeRegister { BitbucketPrTool() }
        safeRegister { BitbucketRepoTool() }
        safeRegister { BitbucketReviewTool() }
        safeRegister { SonarTool() }

        // Runtime tools
        safeRegister { RuntimeExecTool() }
        safeRegister { RuntimeConfigTool() }
        safeRegister { CoverageTool() }

        // Debug tools (require AgentDebugController)
        registerDebugTools()

        log.info("AgentService: registered ${registry.allTools().size} tools")
    }

    private fun registerDebugTools() {
        try {
            val controller = AgentDebugController(project)
            debugController = controller
            registry.register(DebugStepTool(controller))
            registry.register(DebugInspectTool(controller))
            registry.register(DebugBreakpointsTool(controller))
        } catch (e: Exception) {
            log.warn("AgentService: failed to register debug tools: ${e.message}")
        }
    }

    private inline fun safeRegister(factory: () -> AgentTool) {
        try {
            registry.register(factory())
        } catch (e: Exception) {
            log.warn("AgentService: failed to register tool: ${e.message}")
        }
    }

    // ── Task Execution ─────────────────────────────────────────────────────

    /**
     * Execute a task in the agent loop. Returns a Job for cancellation.
     *
     * @param task The user's request.
     * @param contextManager Reuse for multi-turn, or null for new conversation.
     * @param onStreamChunk Streaming text callback (each LLM chunk).
     * @param onToolCall Tool progress callback.
     * @param onComplete Called when the loop finishes.
     */
    fun executeTask(
        task: String,
        contextManager: ContextManager? = null,
        onStreamChunk: (String) -> Unit = {},
        onToolCall: (ToolCallProgress) -> Unit = {},
        onComplete: (LoopResult) -> Unit = {}
    ): Job {
        val sessionId = UUID.randomUUID().toString()
        val session = Session(
            id = sessionId,
            title = task.take(100),
            status = SessionStatus.ACTIVE
        )
        sessionStore.save(session)

        val job = scope.launch {
            try {
                val brain = getOrCreateBrain()
                val agentSettings = AgentSettings.getInstance(project)

                // Build context manager
                val ctx = contextManager ?: ContextManager(
                    maxInputTokens = agentSettings.state.maxInputTokens
                )

                // Set system prompt if this is a fresh conversation
                if (contextManager == null) {
                    val projectName = project.name
                    val projectPath = project.basePath ?: ""
                    val systemPrompt = SystemPrompt.build(
                        projectName = projectName,
                        projectPath = projectPath,
                        planModeEnabled = planModeActive.get()
                    )
                    ctx.setSystemPrompt(systemPrompt)
                }

                // Build tool definitions, filtering write tools if plan mode is active
                val tools = registry.allTools().associateBy { it.name }
                val toolDefs = if (planModeActive.get()) {
                    tools.values
                        .filter { it.name !in writeToolNames }
                        .map { it.toToolDefinition() }
                } else {
                    tools.values.map { it.toToolDefinition() }
                }

                val loop = AgentLoop(
                    brain = brain,
                    tools = tools,
                    toolDefinitions = toolDefs,
                    contextManager = ctx,
                    project = project,
                    onStreamChunk = onStreamChunk,
                    onToolCall = onToolCall
                )
                currentLoop = loop

                val result = loop.run(task)

                // Update session status
                session.status = when (result) {
                    is LoopResult.Completed -> SessionStatus.COMPLETED
                    is LoopResult.Failed -> SessionStatus.FAILED
                    is LoopResult.Cancelled -> SessionStatus.CANCELLED
                }
                session.lastMessageAt = System.currentTimeMillis()
                session.totalTokens = when (result) {
                    is LoopResult.Completed -> result.tokensUsed
                    is LoopResult.Failed -> result.tokensUsed
                    is LoopResult.Cancelled -> result.tokensUsed
                }
                sessionStore.save(session)

                onComplete(result)
            } catch (e: CancellationException) {
                session.status = SessionStatus.CANCELLED
                session.lastMessageAt = System.currentTimeMillis()
                sessionStore.save(session)
                onComplete(LoopResult.Cancelled(iterations = 0))
            } catch (e: Exception) {
                log.error("AgentService: task execution failed", e)
                session.status = SessionStatus.FAILED
                session.lastMessageAt = System.currentTimeMillis()
                sessionStore.save(session)
                onComplete(LoopResult.Failed(error = e.message ?: "Unknown error"))
            } finally {
                currentLoop = null
            }
        }
        currentJob = job
        return job
    }

    // ── Cancel ─────────────────────────────────────────────────────────────

    /**
     * Cancel the currently running task. Safe to call from any thread.
     */
    fun cancelCurrentTask() {
        currentLoop?.cancel()
        currentJob?.cancel()
    }

    // ── Dispose ────────────────────────────────────────────────────────────

    override fun dispose() {
        cancelCurrentTask()
        scope.cancel("AgentService disposed")
        ProcessRegistry.killAll()
        debugController?.dispose()
    }

    companion object {
        val planModeActive = AtomicBoolean(false)

        fun getInstance(project: Project): AgentService =
            project.service<AgentService>()
    }
}
