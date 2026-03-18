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
 * Responsibilities:
 * - Takes user task input and launches the orchestrator
 * - Routes orchestrator callbacks to RichStreamingPanel methods
 * - Connects ApprovalGate to CommandApprovalDialog / EditApprovalDialog
 * - Handles session lifecycle (start, progress, completion, failure, cancellation)
 * - Manages the coroutine scope for background execution
 *
 * This is the "C" in MVC — the orchestrator and UI exist independently;
 * this controller wires them together.
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
    private val toolCallTimers = mutableMapOf<String, Long>()

    init {
        // Wire button actions
        dashboard.newTaskButton.addActionListener { promptAndExecuteTask() }
        dashboard.cancelButton.addActionListener { cancelTask() }
        dashboard.settingsLink.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "workflow.orchestrator.agent")
            }
        })
    }

    /**
     * Prompt the user for a task description and execute it.
     */
    fun promptAndExecuteTask() {
        val task = Messages.showInputDialog(
            project,
            "Describe the task for the AI agent:",
            "New Agent Task",
            null
        ) ?: return // User cancelled

        if (task.isBlank()) return

        executeTask(task)
    }

    /**
     * Execute a task programmatically (for external callers like actions/shortcuts).
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

        // Prepare UI
        val richPanel = dashboard.getRichPanel()
        richPanel.startSession(task)
        dashboard.cancelButton.isEnabled = true
        dashboard.newTaskButton.isEnabled = false

        // Build approval gate with UI dialogs
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
                SwingUtilities.invokeLater {
                    dashboard.cancelButton.isEnabled = false
                    dashboard.newTaskButton.isEnabled = true
                }
            }
        }
    }

    /**
     * Cancel the currently running task.
     */
    fun cancelTask() {
        currentOrchestrator?.cancelTask()
        dashboard.getRichPanel().appendStatus("Cancellation requested...", RichStreamingPanel.StatusType.WARNING)
    }

    /**
     * Handle progress updates from the orchestrator.
     * Routes to appropriate RichStreamingPanel methods based on the progress step content.
     */
    private fun handleProgress(progress: AgentProgress, richPanel: RichStreamingPanel) {
        val step = progress.step
        val maxTokens = try {
            val cm = AgentSettings.getInstance(project)
            cm.state.maxInputTokens
        } catch (_: Exception) { 150_000 }

        // Update token widget
        SwingUtilities.invokeLater {
            dashboard.updateProgress(step, progress.tokensUsed, maxTokens)
        }

        // Route specific progress steps to rich panel
        val toolInfo = progress.toolCallInfo

        when {
            step.startsWith("Used tool:") && toolInfo != null -> {
                // Rich tool call card with details
                richPanel.flushStreamBuffer()

                if (toolInfo.editFilePath != null && toolInfo.editOldText != null && toolInfo.editNewText != null) {
                    // Edit tool — show inline diff card
                    richPanel.appendEditDiff(
                        filePath = toolInfo.editFilePath,
                        oldText = toolInfo.editOldText,
                        newText = toolInfo.editNewText,
                        accepted = !toolInfo.isError
                    )
                } else {
                    // Regular tool — show tool call card
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
                // Fallback without rich info
                val toolName = step.removePrefix("Used tool:").trim()
                richPanel.appendToolCall(toolName, status = RichStreamingPanel.ToolCallStatus.SUCCESS)
            }
            step.startsWith("Thinking...") -> {
                // Only show first iteration status, not every one (reduces noise)
                if (step.contains("iteration 1)")) {
                    richPanel.appendStatus(step, RichStreamingPanel.StatusType.INFO)
                }
            }
            step.startsWith("Starting") -> {
                richPanel.appendStatus(step, RichStreamingPanel.StatusType.INFO)
            }
            step.contains("Executing:") -> {
                // Orchestrated mode step
                richPanel.appendStatus(step, RichStreamingPanel.StatusType.INFO)
            }
            step.contains("complex") || step.contains("plan") -> {
                richPanel.appendStatus(step, RichStreamingPanel.StatusType.WARNING)
            }
            step == "Task completed" -> {
                // Final — handled in handleResult
            }
        }
    }

    /**
     * Handle the final result from the orchestrator.
     */
    private fun handleResult(result: AgentResult, richPanel: RichStreamingPanel, durationMs: Long) {
        when (result) {
            is AgentResult.Completed -> {
                richPanel.flushStreamBuffer()
                richPanel.completeSession(
                    tokensUsed = result.totalTokens,
                    iterations = 0, // Not tracked at this level
                    filesModified = result.artifacts,
                    durationMs = durationMs,
                    status = RichStreamingPanel.SessionStatus.SUCCESS
                )

                // Offer rollback if snapshot exists
                if (result.snapshotRef != null && result.artifacts.isNotEmpty()) {
                    LOG.info("AgentController: snapshot ${result.snapshotRef} available for rollback")
                }
            }
            is AgentResult.Failed -> {
                richPanel.appendError(result.error)
                richPanel.completeSession(
                    tokensUsed = 0,
                    iterations = 0,
                    filesModified = emptyList(),
                    durationMs = durationMs,
                    status = RichStreamingPanel.SessionStatus.FAILED
                )
            }
            is AgentResult.PlanReady -> {
                // Show plan in the left panel and wait for approval
                SwingUtilities.invokeLater {
                    dashboard.showOrchestrationPlan(result.plan.getAllTasks())
                }
                richPanel.appendStatus(
                    "Plan generated with ${result.plan.getAllTasks().size} tasks. Review in the left panel.",
                    RichStreamingPanel.StatusType.INFO
                )
                // TODO: Add approve/reject buttons for plan execution
            }
            is AgentResult.Cancelled -> {
                richPanel.appendStatus("Task cancelled after ${result.completedSteps} steps.", RichStreamingPanel.StatusType.WARNING)
                richPanel.completeSession(
                    tokensUsed = 0,
                    iterations = result.completedSteps,
                    filesModified = emptyList(),
                    durationMs = durationMs,
                    status = RichStreamingPanel.SessionStatus.CANCELLED
                )
            }
        }
    }

    /**
     * Show an approval dialog on the EDT and return the result.
     * Blocks the calling coroutine (on Dispatchers.IO) until the user responds.
     */
    private fun showApprovalDialog(description: String, riskLevel: RiskLevel): ApprovalResult {
        var result: ApprovalResult = ApprovalResult.Rejected
        val latch = java.util.concurrent.CountDownLatch(1)

        ApplicationManager.getApplication().invokeAndWait {
            if (riskLevel == RiskLevel.HIGH && description.contains("run_command")) {
                // Shell command approval
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
                // Generic approval via simple dialog
                val answer = Messages.showYesNoDialog(
                    project,
                    "The agent wants to perform:\n\n$description\n\nRisk level: ${riskLevel.name}",
                    "Agent Action Approval",
                    "Allow",
                    "Block",
                    Messages.getQuestionIcon()
                )
                result = if (answer == Messages.YES) ApprovalResult.Approved else ApprovalResult.Rejected
            }
            latch.countDown()
        }

        latch.await()

        // Show approval/rejection in the rich panel
        val statusType = if (result is ApprovalResult.Approved)
            RichStreamingPanel.StatusType.SUCCESS else RichStreamingPanel.StatusType.WARNING
        val statusMsg = if (result is ApprovalResult.Approved)
            "Approved: $description" else "Blocked: $description"
        dashboard.getRichPanel().appendStatus(statusMsg, statusType)

        return result
    }

    /**
     * Dispose resources when the controller is no longer needed.
     */
    fun dispose() {
        scope.cancel()
    }
}
