package com.workflow.orchestrator.agent.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.orchestrator.AgentOrchestrator
import com.workflow.orchestrator.agent.orchestrator.AgentProgress
import com.workflow.orchestrator.agent.orchestrator.AgentResult
import com.workflow.orchestrator.agent.orchestrator.ToolCallInfo
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.runtime.ConversationSession
import com.workflow.orchestrator.agent.settings.AgentSettings
import kotlinx.coroutines.*
import java.io.File
import javax.swing.SwingUtilities

/**
 * Controller bridging AgentOrchestrator (backend) ↔ AgentDashboardPanel (UI).
 * Routes orchestrator callbacks to the dashboard, which delegates to JCEF or JEditorPane.
 */
class AgentController(
    private val project: Project,
    private val dashboard: AgentDashboardPanel
) {
    companion object {
        private val LOG = Logger.getInstance(AgentController::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentOrchestrator: AgentOrchestrator? = null
    private var sessionStartMs = 0L
    private var session: ConversationSession? = null
    private val pendingUserMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var sessionAutoApprove = false

    init {
        dashboard.onSendMessage = { message -> executeTask(message) }
        dashboard.cancelButton.addActionListener { cancelTask() }
        dashboard.newChatButton.addActionListener { newChat() }
        dashboard.tracesButton.addActionListener { openLatestTrace() }
        dashboard.settingsLink.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "workflow.orchestrator.agent")
            }
        })

        // Wire JCEF JS→Kotlin action callbacks (undo, view-trace, example prompts)
        dashboard.setCefCallbacks(
            onUndo = { handleUndoRequest() },
            onViewTrace = { openLatestTrace() },
            onPromptSubmitted = { text -> executeTask(text) }
        )

        dashboard.focusInput()
    }

    fun executeTask(task: String) {
        // If agent is already running, queue the message as intervention
        if (currentOrchestrator != null && session != null && dashboard.cancelButton.isEnabled) {
            pendingUserMessages.add(task)
            dashboard.appendUserMessage(task)
            dashboard.appendStatus("Message queued — will be sent to the agent after the current step.", RichStreamingPanel.StatusType.INFO)
            return
        }

        val agentService = try {
            AgentService.getInstance(project)
        } catch (e: Exception) {
            dashboard.appendError("Agent service not available: ${e.message}")
            return
        }
        if (!agentService.isConfigured()) {
            dashboard.appendError("Agent not configured. Set up Sourcegraph connection and enable Agent in Settings.")
            return
        }

        // Create or reuse session — the core multi-turn fix
        if (session == null) {
            session = ConversationSession.create(project, agentService)
            dashboard.startSession(task)
        } else {
            dashboard.appendUserMessage(task)
        }

        val currentSession = session!!
        currentSession.recordUserMessage(task)
        dashboard.setBusy(true)

        // Create orchestrator (lightweight — just brain + registry + project)
        val orchestrator = AgentOrchestrator(currentSession.brain, agentService.toolRegistry, project)
        currentOrchestrator = orchestrator
        sessionStartMs = System.currentTimeMillis()

        val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
        val approvalGate = ApprovalGate(
            approvalRequired = settings?.state?.approvalRequiredForEdits ?: true,
            onApprovalNeeded = { desc, risk -> showApprovalDialog(desc, risk) }
        )

        scope.launch {
            try {
                val result = orchestrator.executeTask(
                    taskDescription = task,
                    session = currentSession,
                    approvalGate = approvalGate,
                    onProgress = { handleProgress(it) },
                    onStreamChunk = { dashboard.appendStreamToken(it) }
                )
                handleResult(result, System.currentTimeMillis() - sessionStartMs)
            } catch (e: CancellationException) {
                dashboard.completeSession(0, 0, emptyList(),
                    System.currentTimeMillis() - sessionStartMs,
                    RichStreamingPanel.SessionStatus.CANCELLED)
            } catch (e: Exception) {
                LOG.error("AgentController: task failed", e)
                dashboard.appendError("Unexpected error: ${e.message}")
                dashboard.completeSession(0, 0, emptyList(),
                    System.currentTimeMillis() - sessionStartMs,
                    RichStreamingPanel.SessionStatus.FAILED)
            } finally {
                dashboard.setBusy(false)
                // Persist conversation state after each turn (best effort)
                session?.let { s ->
                    try {
                        s.persistNewMessages()
                        s.saveMetadata(
                            projectName = project.name,
                            projectPath = project.basePath ?: "",
                            model = try { AgentSettings.getInstance(project).state.sourcegraphChatModel ?: "" } catch (_: Exception) { "" }
                        )
                    } catch (_: Exception) { /* best effort — don't break the UI flow */ }
                }
            }
        }
    }

    fun cancelTask() {
        currentOrchestrator?.cancelTask()
        dashboard.appendStatus("Cancellation requested...", RichStreamingPanel.StatusType.WARNING)
    }

    fun newChat() {
        currentOrchestrator?.cancelTask()
        currentOrchestrator = null
        sessionAutoApprove = false
        session?.let { s ->
            s.markCompleted(true)
            try {
                s.persistNewMessages()
                s.saveMetadata(
                    projectName = project.name,
                    projectPath = project.basePath ?: "",
                    model = try { AgentSettings.getInstance(project).state.sourcegraphChatModel ?: "" } catch (_: Exception) { "" }
                )
            } catch (_: Exception) { /* best effort */ }
        }
        session = null
        dashboard.reset()
        dashboard.focusInput()
    }

    /**
     * Handle undo request from JCEF footer button.
     * Uses the rollback manager stored on the ConversationSession to revert all agent changes.
     */
    private fun handleUndoRequest() {
        val manager = session?.rollbackManager
        val checkpointId = manager?.latestCheckpointId()
        if (manager == null || checkpointId == null) {
            dashboard.appendStatus("No changes to undo.", RichStreamingPanel.StatusType.WARNING)
            return
        }
        SwingUtilities.invokeLater {
            val answer = Messages.showYesNoDialog(
                project,
                "Undo all file changes made by the agent?",
                "Undo Agent Changes",
                "Undo", "Cancel",
                Messages.getWarningIcon()
            )
            if (answer == Messages.YES) {
                if (manager.rollbackToCheckpoint(checkpointId)) {
                    dashboard.appendStatus("All agent changes have been undone.", RichStreamingPanel.StatusType.SUCCESS)
                } else {
                    dashboard.appendError("Rollback failed. Try Edit > Undo or LocalHistory.")
                }
            }
        }
    }

    /**
     * Resume a previous session from History tab.
     * Loads persisted messages and restores conversation context.
     */
    fun resumeSession(sessionId: String) {
        val agentService = try { AgentService.getInstance(project) } catch (_: Exception) {
            dashboard.appendError("Agent service not available.")
            return
        }
        val loaded = ConversationSession.load(sessionId, project, agentService)
        if (loaded == null) {
            dashboard.appendError("Failed to load session $sessionId")
            return
        }

        // Close current session
        session?.markCompleted(true)
        session = loaded
        sessionAutoApprove = false

        // Render loaded conversation to UI
        dashboard.reset()
        dashboard.startSession(loaded.title)

        // Show a summary instead of replaying all messages
        dashboard.appendStatus(
            "Session restored: \"${loaded.title}\" (${loaded.messageCount} messages). Continue the conversation below.",
            RichStreamingPanel.StatusType.SUCCESS
        )
        dashboard.focusInput()
    }

    private fun handleProgress(progress: AgentProgress) {
        val maxTokens = try { AgentSettings.getInstance(project).state.maxInputTokens } catch (_: Exception) { 150_000 }
        SwingUtilities.invokeLater { dashboard.updateProgress(progress.step, progress.tokensUsed, maxTokens) }

        val toolInfo = progress.toolCallInfo
        when {
            progress.step.startsWith("Calling tool:") && toolInfo != null -> {
                // Pre-execution: show tool call as RUNNING before it executes
                dashboard.flushStreamBuffer()
                dashboard.appendToolCall(toolInfo.toolName, toolInfo.args, RichStreamingPanel.ToolCallStatus.RUNNING)
            }
            progress.step.startsWith("Used tool:") && toolInfo != null -> {
                // Post-execution: update with result
                if (toolInfo.editFilePath != null && toolInfo.editOldText != null && toolInfo.editNewText != null) {
                    dashboard.appendEditDiff(toolInfo.editFilePath, toolInfo.editOldText, toolInfo.editNewText, !toolInfo.isError)
                } else {
                    val status = if (toolInfo.isError) RichStreamingPanel.ToolCallStatus.FAILED else RichStreamingPanel.ToolCallStatus.SUCCESS
                    dashboard.appendToolCall(toolInfo.toolName, toolInfo.args, status)
                    dashboard.updateLastToolCall(status, toolInfo.result, toolInfo.durationMs)
                }
            }
            progress.step.startsWith("Used tool:") -> {
                dashboard.appendToolCall(progress.step.removePrefix("Used tool:").trim(), status = RichStreamingPanel.ToolCallStatus.SUCCESS)
            }
            progress.step.contains("complex") || progress.step.contains("plan") -> {
                dashboard.appendStatus(progress.step, RichStreamingPanel.StatusType.WARNING)
            }
        }
    }

    private fun handleResult(result: AgentResult, durationMs: Long) {
        // Process any queued user messages after the current task completes
        val pending = pendingUserMessages.poll()
        if (pending != null) {
            LOG.info("AgentController: processing queued user intervention: ${pending.take(100)}")
            // Schedule follow-up execution after current result is handled
            SwingUtilities.invokeLater { executeTask(pending) }
        }

        when (result) {
            is AgentResult.Completed -> {
                dashboard.flushStreamBuffer()
                dashboard.completeSession(result.totalTokens, 0, result.artifacts, durationMs, RichStreamingPanel.SessionStatus.SUCCESS)
                if (result.artifacts.isNotEmpty()) {
                    dashboard.appendStatus(
                        "Agent modified ${result.artifacts.size} file(s). You can undo all changes via Edit > Undo or LocalHistory.",
                        RichStreamingPanel.StatusType.INFO
                    )
                }
            }
            is AgentResult.Failed -> {
                dashboard.appendError(result.error)
                dashboard.completeSession(0, 0, emptyList(), durationMs, RichStreamingPanel.SessionStatus.FAILED)
                showFailureNotification(result.error)
                // Log trace path for discoverability
                val tracesPath = project.basePath?.let { "$it/.workflow/agent/traces/" }
                if (tracesPath != null) {
                    LOG.info("AgentController: session trace at $tracesPath")
                    dashboard.appendStatus("Debug trace saved. View via notification or at: $tracesPath", RichStreamingPanel.StatusType.INFO)
                }
            }
            is AgentResult.PlanReady -> {
                dashboard.showOrchestrationPlan(result.plan.getAllTasks())
            }
            is AgentResult.Cancelled -> {
                dashboard.appendStatus("Cancelled after ${result.completedSteps} steps.", RichStreamingPanel.StatusType.WARNING)
                dashboard.completeSession(0, result.completedSteps, emptyList(), durationMs, RichStreamingPanel.SessionStatus.CANCELLED)
            }
        }
    }

    private fun showApprovalDialog(description: String, riskLevel: RiskLevel): ApprovalResult {
        // Session-level auto-approve: skip dialog for MEDIUM or lower risk after user opted in
        if (sessionAutoApprove && riskLevel <= RiskLevel.MEDIUM) {
            dashboard.appendStatus("Auto-approved: $description", RichStreamingPanel.StatusType.SUCCESS)
            return ApprovalResult.Approved
        }

        var result: ApprovalResult = ApprovalResult.Rejected
        ApplicationManager.getApplication().invokeAndWait {
            if (riskLevel == RiskLevel.HIGH && description.contains("run_command")) {
                val cmdMatch = Regex("run_command\\((.+?)\\)").find(description)
                val dialog = CommandApprovalDialog(project, cmdMatch?.groupValues?.get(1) ?: description, project.basePath ?: ".", riskLevel.name)
                dialog.show()
                result = if (dialog.approved) ApprovalResult.Approved else ApprovalResult.Rejected
            } else if (riskLevel >= RiskLevel.MEDIUM && !description.contains("run_command")) {
                // File edit approval — enhanced with "Allow All (This Session)" option
                val answer = Messages.showYesNoCancelDialog(
                    project,
                    "The agent wants to modify a file:\n\n$description\n\nYou can undo this change after it's applied.",
                    "Agent File Edit",
                    "Allow",
                    "Block",
                    "Allow All (This Session)",
                    Messages.getQuestionIcon()
                )
                result = when (answer) {
                    Messages.YES -> ApprovalResult.Approved
                    Messages.CANCEL -> { sessionAutoApprove = true; ApprovalResult.Approved }
                    else -> ApprovalResult.Rejected
                }
            } else {
                val answer = Messages.showYesNoDialog(project,
                    "The agent wants to perform:\n\n$description\n\nRisk level: ${riskLevel.name}",
                    "Agent Action Approval", "Allow", "Block", Messages.getQuestionIcon())
                result = if (answer == Messages.YES) ApprovalResult.Approved else ApprovalResult.Rejected
            }
        }
        val type = if (result is ApprovalResult.Approved) RichStreamingPanel.StatusType.SUCCESS else RichStreamingPanel.StatusType.WARNING
        val msg = if (result is ApprovalResult.Approved) "Approved: $description" else "Blocked: $description"
        dashboard.appendStatus(msg, type)
        return result
    }

    /**
     * Open the traces directory in the IDE file browser.
     * Called from toolbar or notification.
     */
    fun openTracesDirectory() {
        val basePath = project.basePath ?: return
        val tracesDir = File(basePath, ".workflow/agent/traces")
        if (!tracesDir.exists()) {
            dashboard.appendStatus("No traces yet — run a task first. Traces will be at: ${tracesDir.absolutePath}", RichStreamingPanel.StatusType.INFO)
            return
        }
        // Open the directory in IntelliJ's project view
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tracesDir)
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
        dashboard.appendStatus("Traces directory: ${tracesDir.absolutePath}", RichStreamingPanel.StatusType.INFO)
    }

    /**
     * Open the most recent trace file in the editor.
     */
    fun openLatestTrace() {
        val basePath = project.basePath ?: return
        val tracesDir = File(basePath, ".workflow/agent/traces")
        val latest = tracesDir.listFiles()
            ?.filter { it.extension == "jsonl" }
            ?.maxByOrNull { it.lastModified() }

        if (latest != null) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(latest)
            if (vf != null) {
                SwingUtilities.invokeLater {
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            }
        } else {
            dashboard.appendStatus("No trace files found at: ${tracesDir.absolutePath}", RichStreamingPanel.StatusType.INFO)
        }
    }

    /**
     * Show a notification with action to view the trace file.
     */
    private fun showFailureNotification(error: String) {
        try {
            val group = NotificationGroupManager.getInstance().getNotificationGroup("workflow.agent")
            group.createNotification(
                "Agent Task Failed",
                error.take(200),
                NotificationType.ERROR
            ).addAction(object : com.intellij.openapi.actionSystem.AnAction("View Trace") {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    openLatestTrace()
                }
            }).addAction(object : com.intellij.openapi.actionSystem.AnAction("Open Traces Folder") {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    openTracesDirectory()
                }
            }).notify(project)
        } catch (_: Exception) {
            // Notification group may not exist
        }
    }

    fun dispose() { scope.cancel() }
}
