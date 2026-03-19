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
        dashboard.focusInput()
    }

    fun executeTask(task: String) {
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

        val orchestrator = AgentOrchestrator(agentService.brain, agentService.toolRegistry, project)
        currentOrchestrator = orchestrator
        sessionStartMs = System.currentTimeMillis()

        dashboard.startSession(task)
        dashboard.setBusy(true)

        val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
        val approvalGate = ApprovalGate(
            approvalRequired = settings?.state?.approvalRequiredForEdits ?: true,
            onApprovalNeeded = { desc, risk -> showApprovalDialog(desc, risk) }
        )

        scope.launch {
            try {
                val result = orchestrator.executeTask(
                    taskDescription = task,
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
        dashboard.reset()
        dashboard.focusInput()
    }

    private fun handleProgress(progress: AgentProgress) {
        val maxTokens = try { AgentSettings.getInstance(project).state.maxInputTokens } catch (_: Exception) { 150_000 }
        SwingUtilities.invokeLater { dashboard.updateProgress(progress.step, progress.tokensUsed, maxTokens) }

        val toolInfo = progress.toolCallInfo
        when {
            progress.step.startsWith("Used tool:") && toolInfo != null -> {
                dashboard.flushStreamBuffer()
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
        when (result) {
            is AgentResult.Completed -> {
                dashboard.flushStreamBuffer()
                dashboard.completeSession(result.totalTokens, 0, result.artifacts, durationMs, RichStreamingPanel.SessionStatus.SUCCESS)
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
        var result: ApprovalResult = ApprovalResult.Rejected
        ApplicationManager.getApplication().invokeAndWait {
            if (riskLevel == RiskLevel.HIGH && description.contains("run_command")) {
                val cmdMatch = Regex("run_command\\((.+?)\\)").find(description)
                val dialog = CommandApprovalDialog(project, cmdMatch?.groupValues?.get(1) ?: description, project.basePath ?: ".", riskLevel.name)
                dialog.show()
                result = if (dialog.approved) ApprovalResult.Approved else ApprovalResult.Rejected
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
