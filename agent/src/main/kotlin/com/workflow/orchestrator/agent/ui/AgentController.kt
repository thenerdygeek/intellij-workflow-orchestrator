package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.orchestrator.AgentOrchestrator
import com.workflow.orchestrator.agent.orchestrator.AgentProgress
import com.workflow.orchestrator.agent.orchestrator.AgentResult
import com.workflow.orchestrator.agent.orchestrator.ToolCallInfo
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.settings.AgentSettings
import kotlinx.coroutines.*
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

    fun dispose() { scope.cancel() }
}
