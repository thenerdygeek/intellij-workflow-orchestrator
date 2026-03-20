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
import com.workflow.orchestrator.agent.runtime.AgentPlan
import com.workflow.orchestrator.agent.runtime.ConversationSession
import com.workflow.orchestrator.agent.runtime.PlanManager
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.settings.ToolPreferences
import com.workflow.orchestrator.agent.tools.ToolCategoryRegistry
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
    private var currentPlanFile: com.workflow.orchestrator.agent.ui.plan.AgentPlanVirtualFile? = null

    init {
        // Tie coroutine scope to project lifecycle — cancel when project closes
        com.intellij.openapi.util.Disposer.register(project, com.intellij.openapi.Disposable { scope.cancel() })

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

        // Wire JCEF plan card callbacks (approve, revise)
        dashboard.setCefPlanCallbacks(
            onApprove = { session?.planManager?.approvePlan() },
            onRevise = { commentsJson ->
                try {
                    val comments = kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(commentsJson)
                    session?.planManager?.revisePlan(comments)
                } catch (e: Exception) {
                    dashboard.appendError("Failed to parse plan comments. Please try again.")
                }
            }
        )

        // Set model name
        try {
            val model = AgentSettings.getInstance(project).state.sourcegraphChatModel ?: ""
            dashboard.setModelName(model)
        } catch (_: Exception) {}

        // Wire tools button — opens JCEF categorized tools panel
        dashboard.toolsButton.addActionListener { showToolsPanel() }

        // Wire JCEF tool toggle callback — persists enable/disable to ToolPreferences
        dashboard.setCefToolToggleCallback { toolName, enabled ->
            try {
                ToolPreferences.getInstance(project).setToolEnabled(toolName, enabled)
            } catch (_: Exception) {}
        }

        dashboard.focusInput()
    }

    private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

    private fun showToolsPanel() {
        val agentService = try { AgentService.getInstance(project) } catch (_: Exception) { return }
        val prefs = try { ToolPreferences.getInstance(project) } catch (_: Exception) { null }
        val allRegisteredTools = agentService.toolRegistry.allTools()

        val categories = ToolCategoryRegistry.CATEGORIES.map { cat ->
            val toolsInCat = cat.tools.mapNotNull { toolName ->
                val tool = allRegisteredTools.find { it.name == toolName } ?: return@mapNotNull null
                val params = tool.parameters.properties.map { (pname, prop) ->
                    mapOf(
                        "name" to pname,
                        "type" to prop.type,
                        "description" to prop.description,
                        "required" to (pname in tool.parameters.required)
                    )
                }
                val schema = try {
                    prettyJson.encodeToString(com.workflow.orchestrator.agent.api.dto.ToolDefinition.serializer(), tool.toToolDefinition())
                } catch (_: Exception) { "" }

                mapOf(
                    "name" to toolName,
                    "description" to tool.description,
                    "enabled" to (prefs?.isToolEnabled(toolName) ?: true),
                    "active" to false,
                    "badge" to cat.badgePrefix,
                    "categoryColor" to cat.color,
                    "parameters" to params,
                    "schema" to schema
                )
            }
            mapOf(
                "displayName" to cat.displayName,
                "color" to cat.color,
                "badgePrefix" to cat.badgePrefix,
                "tools" to toolsInCat
            )
        }

        val json = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<Map<String, Any?>>(),
            mapOf("categories" to categories)
        )
        dashboard.showToolsPanel(json)
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
            session = ConversationSession.create(project, agentService, planMode = dashboard.isPlanMode)
            dashboard.startSession(task)
        } else {
            dashboard.appendUserMessage(task)
        }

        val currentSession = session!!
        currentSession.recordUserMessage(task)

        // Set PlanManager on AgentService so tools can find it
        try {
            val agentSvc = AgentService.getInstance(project)
            agentSvc.currentPlanManager = currentSession.planManager
            agentSvc.currentQuestionManager = currentSession.questionManager
        } catch (_: Exception) {}

        // Wire PlanManager UI callbacks
        currentSession.planManager.onPlanCreated = { plan ->
            val json = PlanManager.json.encodeToString(AgentPlan.serializer(), plan)
            dashboard.renderPlan(json)
            // Open full-screen plan in editor tab (don't steal focus from chat)
            ApplicationManager.getApplication().invokeLater {
                val virtualFile = com.workflow.orchestrator.agent.ui.plan.AgentPlanVirtualFile(plan, currentSession.sessionId)
                FileEditorManager.getInstance(project).openFile(virtualFile, false)
                currentPlanFile = virtualFile
            }
        }
        currentSession.planManager.onStepUpdated = { stepId, status ->
            dashboard.updatePlanStep(stepId, status)
            // Update editor tab
            ApplicationManager.getApplication().invokeLater {
                currentPlanFile?.let { file ->
                    currentSession.planManager.currentPlan?.let { file.currentPlan = it }
                    FileEditorManager.getInstance(project)
                        .getEditors(file)
                        .filterIsInstance<com.workflow.orchestrator.agent.ui.plan.AgentPlanEditor>()
                        .forEach { editor -> editor.updatePlanStep(stepId, status) }
                }
            }
        }

        // Set session directory for plan persistence
        currentSession.planManager.sessionDir = currentSession.store.sessionDirectory

        // Wire anchor update: sets/updates the <active_plan> system message
        currentSession.planManager.onPlanAnchorUpdate = { plan ->
            currentSession.contextManager.setPlanAnchor(
                com.workflow.orchestrator.agent.context.PlanAnchor.createPlanMessage(plan)
            )
        }

        // Wire QuestionManager UI callbacks
        currentSession.questionManager.onQuestionsCreated = { questionSet ->
            val json = QuestionManager.json.encodeToString(
                QuestionSet.serializer(), questionSet
            )
            dashboard.showQuestions(json)
        }
        currentSession.questionManager.onShowQuestion = { index ->
            dashboard.showQuestion(index)
        }
        currentSession.questionManager.onShowSummary = { result ->
            val json = QuestionManager.json.encodeToString(
                QuestionResult.serializer(), result
            )
            dashboard.showQuestionSummary(json)
        }
        currentSession.questionManager.onSubmitted = {
            dashboard.enableChatInput()
        }

        // Wire JCEF → QuestionManager callbacks
        dashboard.setCefQuestionCallbacks(
            onAnswered = { questionId, optionsJson ->
                val options = kotlinx.serialization.json.Json.decodeFromString<List<String>>(optionsJson)
                currentSession.questionManager.answerQuestion(questionId, options)
            },
            onSkipped = { questionId ->
                currentSession.questionManager.skipQuestion(questionId)
            },
            onChatAbout = { questionId, optionLabel, message ->
                currentSession.questionManager.setChatMessage(questionId, optionLabel, message)
            },
            onSubmitted = {
                currentSession.questionManager.submitAnswers()
            },
            onCancelled = {
                currentSession.questionManager.cancelQuestions()
            },
            onEdit = { questionId ->
                currentSession.questionManager.editQuestion(questionId)
            }
        )

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
        session?.questionManager?.clear()
        session = null
        currentPlanFile = null
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

                // Track files in working set
                if (!toolInfo.isError) {
                    val filePath = toolInfo.editFilePath ?: run {
                        val pathMatch = Regex(""""path"\s*:\s*"([^"]+)"""").find(toolInfo.args)
                        pathMatch?.groupValues?.get(1)
                    }
                    if (filePath != null) {
                        when {
                            toolInfo.toolName.contains("edit") -> session?.workingSet?.recordEdit(filePath)
                            toolInfo.toolName.contains("read") || toolInfo.toolName.contains("file_structure") ||
                            toolInfo.toolName.contains("diagnostics") -> session?.workingSet?.recordRead(filePath, 0)
                        }
                    }
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
                // File edit approval — try to show diff via EditApprovalDialog
                if (description.contains("edit_file")) {
                    try {
                        val pathMatch = Regex(""""path"\s*:\s*"([^"]+)"""").find(description)
                        val oldMatch = Regex(""""old_string"\s*:\s*"([^"]*?)"""").find(description)
                        val newMatch = Regex(""""new_string"\s*:\s*"([^"]*?)"""").find(description)

                        val filePath = pathMatch?.groupValues?.get(1)
                        val oldString = oldMatch?.groupValues?.get(1)
                        val newString = newMatch?.groupValues?.get(1)

                        if (filePath != null && oldString != null && newString != null) {
                            val basePath = project.basePath ?: ""
                            val fullPath = if (filePath.startsWith("/")) filePath else "$basePath/$filePath"
                            val file = File(fullPath)
                            val originalContent = if (file.exists()) file.readText() else ""
                            val proposedContent = originalContent.replace(oldString, newString)

                            val dialog = EditApprovalDialog(
                                project = project,
                                filePath = filePath,
                                originalContent = originalContent,
                                proposedContent = proposedContent,
                                editDescription = "Agent wants to edit: $filePath"
                            )
                            dialog.show()
                            result = if (dialog.approved) ApprovalResult.Approved else ApprovalResult.Rejected
                        } else {
                            // Fallback: parsing failed — use plain dialog with session auto-approve
                            val answer = Messages.showYesNoCancelDialog(
                                project,
                                "The agent wants to modify a file:\n\n$description\n\nYou can undo this change after it's applied.",
                                "Agent File Edit",
                                "Allow", "Block", "Allow All (This Session)",
                                Messages.getQuestionIcon()
                            )
                            result = when (answer) {
                                Messages.YES -> ApprovalResult.Approved
                                Messages.CANCEL -> { sessionAutoApprove = true; ApprovalResult.Approved }
                                else -> ApprovalResult.Rejected
                            }
                        }
                    } catch (e: Exception) {
                        // Fallback on any error — use plain dialog
                        val answer = Messages.showYesNoCancelDialog(
                            project,
                            "The agent wants to modify a file:\n\n$description\n\nYou can undo this change after it's applied.",
                            "Agent File Edit",
                            "Allow", "Block", "Allow All (This Session)",
                            Messages.getQuestionIcon()
                        )
                        result = when (answer) {
                            Messages.YES -> ApprovalResult.Approved
                            Messages.CANCEL -> { sessionAutoApprove = true; ApprovalResult.Approved }
                            else -> ApprovalResult.Rejected
                        }
                    }
                } else {
                    // Non-edit_file medium+ risk — plain dialog with session auto-approve
                    val answer = Messages.showYesNoCancelDialog(
                        project,
                        "The agent wants to modify a file:\n\n$description\n\nYou can undo this change after it's applied.",
                        "Agent File Edit",
                        "Allow", "Block", "Allow All (This Session)",
                        Messages.getQuestionIcon()
                    )
                    result = when (answer) {
                        Messages.YES -> ApprovalResult.Approved
                        Messages.CANCEL -> { sessionAutoApprove = true; ApprovalResult.Approved }
                        else -> ApprovalResult.Rejected
                    }
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
