package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.TaskProgress
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import com.workflow.orchestrator.agent.settings.AgentSettings
import kotlinx.coroutines.Job

/**
 * Bridges the JCEF chat dashboard to [AgentService].
 *
 * Responsibilities:
 * - Routes user actions (send, cancel, new chat, plan toggle) from the dashboard to the service
 * - Wires streaming text, tool call progress, and completion callbacks back to the dashboard
 * - Owns a [ContextManager] for multi-turn conversation within a chat session
 * - Manages mirror panels (e.g. "View in Editor" editor tabs)
 */
class AgentController(
    private val project: Project,
    private val dashboard: AgentDashboardPanel
) {
    companion object {
        private val LOG = Logger.getInstance(AgentController::class.java)
    }

    private val service = AgentService.getInstance(project)
    private var contextManager: ContextManager? = null
    private var currentJob: Job? = null
    private var taskStartTime: Long = 0L

    init {
        wireCallbacks()
    }

    // ═══════════════════════════════════════════════════
    //  Callback wiring — dashboard actions → controller
    // ═══════════════════════════════════════════════════

    private fun wireCallbacks() {
        // Primary action callbacks from the JCEF toolbar/input bar
        dashboard.setCefActionCallbacks(
            onCancel = ::cancelTask,
            onNewChat = ::newChat,
            onSendMessage = ::executeTask,
            onChangeModel = ::changeModel,
            onTogglePlanMode = ::togglePlanMode,
            onToggleRalphLoop = { /* Ralph loop not wired in lean rewrite */ },
            onActivateSkill = { /* Skills not wired in lean rewrite */ },
            onRequestFocusIde = { /* No-op: focus returns to IDE naturally */ },
            onOpenSettings = ::openSettings,
            onOpenToolsPanel = ::showToolsPanel
        )

        // Secondary callbacks
        dashboard.setCefCallbacks(
            onUndo = { LOG.info("Undo requested — not implemented in lean rewrite") },
            onViewTrace = { LOG.info("View trace requested — not implemented in lean rewrite") },
            onPromptSubmitted = ::executeTask
        )

        // Plan approval callbacks (no-op stubs — plan lifecycle not in lean rewrite)
        dashboard.setCefPlanCallbacks(
            onApprove = { LOG.info("Plan approved — not implemented in lean rewrite") },
            onRevise = { LOG.info("Plan revised — not implemented in lean rewrite") }
        )

        // File navigation — open file in editor when user clicks a path in chat
        dashboard.setCefNavigationCallbacks(onNavigateToFile = ::navigateToFile)

        // "View in Editor" toolbar button — opens the chat in a full editor tab
        dashboard.setOnViewInEditor(::openChatInEditorTab)

        // The fallback onSendMessage for non-JCEF (RichStreamingPanel) path
        dashboard.onSendMessage = ::executeTask

        // Set model name from settings
        val model = AgentSettings.getInstance(project).state.sourcegraphChatModel
        if (!model.isNullOrBlank()) {
            dashboard.setModelName(model)
        }
    }

    // ═══════════════════════════════════════════════════
    //  Core: executeTask — send user message to agent loop
    // ═══════════════════════════════════════════════════

    /**
     * Execute a user task. Called from the chat input, redirect, or example prompts.
     * Safe to call multiple times for multi-turn conversation.
     */
    fun executeTask(task: String) {
        if (task.isBlank()) return

        LOG.info("AgentController.executeTask: ${task.take(80)}")

        // Cancel any running task before starting a new one
        currentJob?.let { job ->
            if (job.isActive) {
                LOG.info("AgentController: cancelling previous task before starting new one")
                service.cancelCurrentTask()
            }
        }

        // Show user message in the chat UI
        dashboard.appendUserMessage(task)
        dashboard.setBusy(true)
        dashboard.setInputLocked(true)
        taskStartTime = System.currentTimeMillis()

        // Create context manager on first message, reuse on subsequent turns
        if (contextManager == null) {
            val settings = AgentSettings.getInstance(project)
            contextManager = ContextManager(maxInputTokens = settings.state.maxInputTokens)
            dashboard.startSession(task)
        }

        // Launch the agent loop
        currentJob = service.executeTask(
            task = task,
            contextManager = contextManager,
            onStreamChunk = ::onStreamChunk,
            onToolCall = ::onToolCall,
            onTaskProgress = ::onTaskProgress,
            onComplete = ::onComplete
        )
    }

    // ═══════════════════════════════════════════════════
    //  Streaming callbacks — agent loop → dashboard
    // ═══════════════════════════════════════════════════

    private fun onStreamChunk(chunk: String) {
        invokeLater {
            dashboard.appendStreamToken(chunk)
        }
    }

    /**
     * Task progress callback — agent loop reports checklist updates.
     *
     * Port of Cline's FocusChainManager say("task_progress", ...):
     * Cline sends the markdown checklist to the webview for display.
     * We send it to the dashboard as a status message showing progress.
     */
    private fun onTaskProgress(progress: TaskProgress) {
        invokeLater {
            val summary = "${progress.completedCount}/${progress.totalCount} steps completed"
            dashboard.appendStatus(summary, RichStreamingPanel.StatusType.INFO)
        }
    }

    private fun onToolCall(progress: ToolCallProgress) {
        invokeLater {
            if (progress.result.isEmpty() && progress.durationMs == 0L) {
                // Tool call starting
                dashboard.appendToolCall(
                    toolCallId = progress.toolCallId,
                    toolName = progress.toolName,
                    args = progress.args,
                    status = RichStreamingPanel.ToolCallStatus.RUNNING
                )
            } else {
                // Tool call completed
                val status = if (progress.isError) {
                    RichStreamingPanel.ToolCallStatus.FAILED
                } else {
                    RichStreamingPanel.ToolCallStatus.SUCCESS
                }
                dashboard.updateLastToolCall(
                    status = status,
                    result = progress.result,
                    durationMs = progress.durationMs,
                    toolName = progress.toolName,
                    output = progress.result.takeIf { it.isNotBlank() }
                )
            }
        }
    }

    private fun onComplete(result: LoopResult) {
        val durationMs = System.currentTimeMillis() - taskStartTime

        invokeLater {
            // Flush any remaining stream content
            dashboard.flushStreamBuffer()

            when (result) {
                is LoopResult.Completed -> {
                    dashboard.appendCompletionSummary(result.summary, result.verifyCommand)
                    dashboard.completeSession(
                        tokensUsed = result.tokensUsed,
                        iterations = result.iterations,
                        filesModified = emptyList(), // File tracking not in lean rewrite
                        durationMs = durationMs,
                        status = RichStreamingPanel.SessionStatus.SUCCESS
                    )
                }

                is LoopResult.Failed -> {
                    dashboard.appendError(result.error)
                    dashboard.completeSession(
                        tokensUsed = result.tokensUsed,
                        iterations = result.iterations,
                        filesModified = emptyList(),
                        durationMs = durationMs,
                        status = RichStreamingPanel.SessionStatus.FAILED
                    )
                }

                is LoopResult.Cancelled -> {
                    dashboard.appendStatus("Task cancelled.", RichStreamingPanel.StatusType.INFO)
                    dashboard.completeSession(
                        tokensUsed = result.tokensUsed,
                        iterations = result.iterations,
                        filesModified = emptyList(),
                        durationMs = durationMs,
                        status = RichStreamingPanel.SessionStatus.CANCELLED
                    )
                }
            }

            dashboard.setBusy(false)
            dashboard.setInputLocked(false)
            dashboard.focusInput()
            currentJob = null
        }
    }

    // ═══════════════════════════════════════════════════
    //  User actions
    // ═══════════════════════════════════════════════════

    fun cancelTask() {
        LOG.info("AgentController.cancelTask")
        service.cancelCurrentTask()
        // The onComplete callback with LoopResult.Cancelled will handle UI cleanup
    }

    fun newChat() {
        LOG.info("AgentController.newChat")
        // Cancel any running task
        if (currentJob?.isActive == true) {
            service.cancelCurrentTask()
        }
        currentJob = null

        // Reset conversation state
        contextManager = null
        taskStartTime = 0L

        // Reset dashboard UI
        dashboard.reset()
        dashboard.setBusy(false)
        dashboard.setInputLocked(false)
        dashboard.focusInput()
    }

    /**
     * Resume a previous session from its checkpoint.
     *
     * Port of Cline's task resumption flow:
     * - Cline's webview sends "resumeTask" with taskId
     * - ClineProvider loads HistoryItem + apiConversationHistory from disk
     * - Creates new Task instance with restored state
     * - Task picks up execution from the checkpoint
     *
     * We replicate this: load session + messages from SessionStore,
     * rebuild ContextManager, and re-enter the agent loop.
     */
    fun resumeSession(sessionId: String) {
        LOG.info("AgentController.resumeSession: $sessionId")

        // Cancel any running task first
        if (currentJob?.isActive == true) {
            service.cancelCurrentTask()
        }
        currentJob = null

        // Reset UI for the resumed session
        dashboard.reset()
        dashboard.setBusy(true)
        dashboard.setInputLocked(true)
        taskStartTime = System.currentTimeMillis()

        // Show that we're resuming
        dashboard.appendStatus("Resuming session...", RichStreamingPanel.StatusType.INFO)

        // Attempt resume — AgentService rebuilds the ContextManager from JSONL checkpoint
        val job = service.resumeSession(
            sessionId = sessionId,
            onStreamChunk = ::onStreamChunk,
            onToolCall = ::onToolCall,
            onTaskProgress = ::onTaskProgress,
            onComplete = ::onComplete
        )

        if (job != null) {
            currentJob = job
            // Reset contextManager — the resumed session creates its own
            contextManager = null
        } else {
            // Resume failed — notify user
            dashboard.appendError("Could not resume session. The session may have been deleted or has no saved messages.")
            dashboard.setBusy(false)
            dashboard.setInputLocked(false)
            dashboard.focusInput()
        }
    }

    /**
     * List resumable sessions for the session history panel.
     * Returns sessions that were interrupted (not completed).
     */
    fun listResumableSessions(): List<com.workflow.orchestrator.agent.session.Session> {
        return service.sessionStore.list().filter {
            it.status != com.workflow.orchestrator.agent.session.SessionStatus.COMPLETED &&
            it.messageCount > 0
        }
    }

    private fun togglePlanMode(enabled: Boolean) {
        LOG.info("AgentController.togglePlanMode: $enabled")
        AgentService.planModeActive.set(enabled)
        dashboard.setPlanMode(enabled)
    }

    private fun changeModel(model: String) {
        LOG.info("AgentController.changeModel: $model")
        val settings = AgentSettings.getInstance(project)
        settings.state.sourcegraphChatModel = model
        dashboard.setModelName(model)
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            "Workflow Orchestrator"
        )
    }

    private fun showToolsPanel() {
        val toolsJson = buildToolsJson()
        dashboard.showToolsPanel(toolsJson)
    }

    // ═══════════════════════════════════════════════════
    //  Mirror panel support (editor tab)
    // ═══════════════════════════════════════════════════

    /**
     * Register a mirror dashboard panel (e.g. "View in Editor" tab).
     * The mirror receives all output calls from the primary dashboard.
     */
    fun addMirrorPanel(mirror: AgentDashboardPanel) {
        dashboard.addMirror(mirror)

        // Wire the mirror's input callbacks back to this controller
        mirror.setCefActionCallbacks(
            onCancel = ::cancelTask,
            onNewChat = ::newChat,
            onSendMessage = ::executeTask,
            onChangeModel = ::changeModel,
            onTogglePlanMode = ::togglePlanMode,
            onToggleRalphLoop = {},
            onActivateSkill = {},
            onRequestFocusIde = {},
            onOpenSettings = ::openSettings,
            onOpenToolsPanel = ::showToolsPanel
        )
        mirror.setCefCallbacks(
            onUndo = {},
            onViewTrace = {},
            onPromptSubmitted = ::executeTask
        )
        mirror.setCefNavigationCallbacks(onNavigateToFile = ::navigateToFile)
        mirror.onSendMessage = ::executeTask
    }

    fun removeMirrorPanel(mirror: AgentDashboardPanel) {
        dashboard.removeMirror(mirror)
    }

    // ═══════════════════════════════════════════════════
    //  Navigation helpers
    // ═══════════════════════════════════════════════════

    private fun navigateToFile(path: String, line: Int) {
        invokeLater {
            try {
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) ?: return@invokeLater
                val descriptor = com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, line.coerceAtLeast(0), 0)
                FileEditorManager.getInstance(project).openEditor(descriptor, true)
            } catch (e: Exception) {
                LOG.warn("AgentController: failed to navigate to $path:$line", e)
            }
        }
    }

    private fun openChatInEditorTab() {
        invokeLater {
            val chatFile = AgentChatVirtualFile(project)
            FileEditorManager.getInstance(project).openFile(chatFile, true)
        }
    }

    // ═══════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════

    private fun buildToolsJson(): String {
        val tools = service.registry.allTools()
        val sb = StringBuilder("[")
        tools.forEachIndexed { index, tool ->
            if (index > 0) sb.append(",")
            val escapedName = tool.name.replace("\"", "\\\"")
            val escapedDesc = tool.description.take(200).replace("\"", "\\\"").replace("\n", " ")
            sb.append("""{"name":"$escapedName","description":"$escapedDesc","enabled":true}""")
        }
        sb.append("]")
        return sb.toString()
    }

    fun dispose() {
        LOG.info("AgentController.dispose")
        if (currentJob?.isActive == true) {
            service.cancelCurrentTask()
        }
        currentJob = null
        contextManager = null
    }
}
