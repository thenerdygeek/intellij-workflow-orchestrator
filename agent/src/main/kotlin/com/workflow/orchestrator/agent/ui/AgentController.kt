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
 * Controller that bridges the AgentOrchestrator (backend) with the AgentDashboardPanel (UI).
 *
 * Chat-first interaction model:
 * - User types in the chat input → Enter to send
 * - Agent streams its response into the rich panel
 * - Tool calls appear as inline cards
 * - Edits appear as inline diffs
 * - User can send follow-up messages
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
        // Wire chat input
        dashboard.onSendMessage = { message -> executeTask(message) }

        // Wire buttons
        dashboard.cancelButton.addActionListener { cancelTask() }
        dashboard.newChatButton.addActionListener { newChat() }
        dashboard.settingsLink.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "workflow.orchestrator.agent")
            }
        })

        // Focus input on init
        dashboard.focusInput()
    }

    /**
     * Execute a task from the chat input.
     */
    fun executeTask(task: String) {
        val agentService = try {
            AgentService.getInstance(project)
        } catch (e: Exception) {
            LOG.warn("AgentController: AgentService not available", e)
            dashboard.getRichPanel().appendError("Agent service not available: ${e.message}")
            return
        }

        if (!agentService.isConfigured()) {
            dashboard.getRichPanel().appendError(
                "Agent not configured. Set up Sourcegraph connection and enable Agent in Settings."
            )
            return
        }

        val orchestrator = AgentOrchestrator(agentService.brain, agentService.toolRegistry, project)
        currentOrchestrator = orchestrator
        sessionStartMs = System.currentTimeMillis()

        // Show user message in the chat
        val richPanel = dashboard.getRichPanel()
        richPanel.startSession(task)
        dashboard.setBusy(true)

        // Build approval gate
        val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
        val approvalGate = ApprovalGate(
            approvalRequired = settings?.state?.approvalRequiredForEdits ?: true,
            onApprovalNeeded = { description, riskLevel -> showApprovalDialog(description, riskLevel) }
        )

        // Execute in background
        scope.launch {
            try {
                val result = orchestrator.executeTask(
                    taskDescription = task,
                    approvalGate = approvalGate,
                    onProgress = { progress -> handleProgress(progress, richPanel) },
                    onStreamChunk = { chunk -> richPanel.appendStreamToken(chunk) }
                )

                val durationMs = System.currentTimeMillis() - sessionStartMs
                handleResult(result, richPanel, durationMs)
            } catch (e: CancellationException) {
                richPanel.completeSession(
                    tokensUsed = 0, iterations = 0, filesModified = emptyList(),
                    durationMs = System.currentTimeMillis() - sessionStartMs,
                    status = RichStreamingPanel.SessionStatus.CANCELLED
                )
            } catch (e: Exception) {
                LOG.error("AgentController: task execution failed", e)
                richPanel.appendError("Unexpected error: ${e.message}")
                richPanel.completeSession(
                    tokensUsed = 0, iterations = 0, filesModified = emptyList(),
                    durationMs = System.currentTimeMillis() - sessionStartMs,
                    status = RichStreamingPanel.SessionStatus.FAILED
                )
            } finally {
                dashboard.setBusy(false)
            }
        }
    }

    fun cancelTask() {
        currentOrchestrator?.cancelTask()
        dashboard.getRichPanel().appendStatus("Cancellation requested...", RichStreamingPanel.StatusType.WARNING)
    }

    fun newChat() {
        currentOrchestrator?.cancelTask()
        currentOrchestrator = null
        dashboard.reset()
        dashboard.focusInput()
    }

    private fun handleProgress(progress: AgentProgress, richPanel: RichStreamingPanel) {
        val step = progress.step
        val maxTokens = try {
            AgentSettings.getInstance(project).state.maxInputTokens
        } catch (_: Exception) { 150_000 }

        SwingUtilities.invokeLater {
            dashboard.updateProgress(step, progress.tokensUsed, maxTokens)
        }

        val toolInfo = progress.toolCallInfo

        when {
            step.startsWith("Used tool:") && toolInfo != null -> {
                richPanel.flushStreamBuffer()

                if (toolInfo.editFilePath != null && toolInfo.editOldText != null && toolInfo.editNewText != null) {
                    richPanel.appendEditDiff(
                        filePath = toolInfo.editFilePath,
                        oldText = toolInfo.editOldText,
                        newText = toolInfo.editNewText,
                        accepted = !toolInfo.isError
                    )
                } else {
                    val status = if (toolInfo.isError)
                        RichStreamingPanel.ToolCallStatus.FAILED
                    else
                        RichStreamingPanel.ToolCallStatus.SUCCESS

                    richPanel.appendToolCall(
                        toolName = toolInfo.toolName,
                        args = toolInfo.args,
                        status = status
                    )
                    richPanel.updateLastToolCall(
                        status = status,
                        result = toolInfo.result,
                        durationMs = toolInfo.durationMs
                    )
                }
            }
            step.startsWith("Used tool:") -> {
                val toolName = step.removePrefix("Used tool:").trim()
                richPanel.appendToolCall(toolName, status = RichStreamingPanel.ToolCallStatus.SUCCESS)
            }
            step.contains("complex") || step.contains("plan") -> {
                richPanel.appendStatus(step, RichStreamingPanel.StatusType.WARNING)
            }
        }
    }

    private fun handleResult(result: AgentResult, richPanel: RichStreamingPanel, durationMs: Long) {
        when (result) {
            is AgentResult.Completed -> {
                richPanel.flushStreamBuffer()
                richPanel.completeSession(
                    tokensUsed = result.totalTokens,
                    iterations = 0,
                    filesModified = result.artifacts,
                    durationMs = durationMs,
                    status = RichStreamingPanel.SessionStatus.SUCCESS
                )
            }
            is AgentResult.Failed -> {
                richPanel.appendError(result.error)
                richPanel.completeSession(
                    tokensUsed = 0, iterations = 0, filesModified = emptyList(),
                    durationMs = durationMs,
                    status = RichStreamingPanel.SessionStatus.FAILED
                )
            }
            is AgentResult.PlanReady -> {
                SwingUtilities.invokeLater {
                    dashboard.showOrchestrationPlan(result.plan.getAllTasks())
                }
                richPanel.appendStatus(
                    "Plan generated with ${result.plan.getAllTasks().size} tasks. Review in the left panel.",
                    RichStreamingPanel.StatusType.INFO
                )
            }
            is AgentResult.Cancelled -> {
                richPanel.appendStatus("Task cancelled after ${result.completedSteps} steps.", RichStreamingPanel.StatusType.WARNING)
                richPanel.completeSession(
                    tokensUsed = 0, iterations = result.completedSteps, filesModified = emptyList(),
                    durationMs = durationMs,
                    status = RichStreamingPanel.SessionStatus.CANCELLED
                )
            }
        }
    }

    private fun showApprovalDialog(description: String, riskLevel: RiskLevel): ApprovalResult {
        var result: ApprovalResult = ApprovalResult.Rejected
        val latch = java.util.concurrent.CountDownLatch(1)

        ApplicationManager.getApplication().invokeAndWait {
            if (riskLevel == RiskLevel.HIGH && description.contains("run_command")) {
                val cmdMatch = Regex("run_command\\((.+?)\\)").find(description)
                val command = cmdMatch?.groupValues?.get(1) ?: description
                val dialog = CommandApprovalDialog(
                    project = project,
                    command = command,
                    workingDir = project.basePath ?: ".",
                    riskAssessment = riskLevel.name
                )
                dialog.show()
                result = if (dialog.approved) ApprovalResult.Approved else ApprovalResult.Rejected
            } else {
                val answer = Messages.showYesNoDialog(
                    project,
                    "The agent wants to perform:\n\n$description\n\nRisk level: ${riskLevel.name}",
                    "Agent Action Approval",
                    "Allow", "Block",
                    Messages.getQuestionIcon()
                )
                result = if (answer == Messages.YES) ApprovalResult.Approved else ApprovalResult.Rejected
            }
            latch.countDown()
        }

        latch.await()

        val statusType = if (result is ApprovalResult.Approved)
            RichStreamingPanel.StatusType.SUCCESS else RichStreamingPanel.StatusType.WARNING
        val statusMsg = if (result is ApprovalResult.Approved)
            "Approved: $description" else "Blocked: $description"
        dashboard.getRichPanel().appendStatus(statusMsg, statusType)

        return result
    }

    fun dispose() {
        scope.cancel()
    }
}
