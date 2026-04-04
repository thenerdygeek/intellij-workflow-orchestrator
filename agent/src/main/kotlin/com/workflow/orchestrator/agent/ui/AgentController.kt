package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.hooks.HookEvent
import com.workflow.orchestrator.agent.hooks.HookResult
import com.workflow.orchestrator.agent.hooks.HookType
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.PlanParser
import com.workflow.orchestrator.agent.loop.TaskProgress
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import kotlinx.coroutines.*

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
    /** Last task text for retry button. Gap 17. */
    private var lastTaskText: String? = null
    /** Current plan text, stored for the approve/revise flow. Priority 1. */
    private var currentPlan: String? = null

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
            onActivateSkill = { skillName -> executeTask("/skill $skillName") },
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

        // Plan approval callbacks — full plan lifecycle (Priority 1)
        dashboard.setCefPlanCallbacks(
            onApprove = ::approvePlan,
            onRevise = ::revisePlan
        )

        // Tool kill callback — Gap 5
        dashboard.setCefKillCallback { toolCallId ->
            LOG.info("AgentController: kill requested for tool call $toolCallId")
            ProcessRegistry.kill(toolCallId)
        }

        // Gap 11: Wire AskQuestionsTool to dashboard question wizard
        com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool.showQuestionsCallback = { questionsJson ->
            invokeLater { dashboard.showQuestions(questionsJson) }
        }
        dashboard.setCefQuestionCallbacks(
            onAnswered = { _, _ -> /* Individual answers handled by wizard */ },
            onSkipped = { _ -> /* Individual skips handled by wizard */ },
            onChatAbout = { _, _, _ -> /* Chat about option not used for tool flow */ },
            onSubmitted = {
                // User submitted all answers — resolve the pending deferred
                // The answers are collected by the React webview and passed as JSON
                com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool.resolveQuestions("{}")
            },
            onCancelled = {
                com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool.cancelQuestions()
            },
            onEdit = { _ -> /* Re-editing handled by wizard */ }
        )

        // Gap 11: Wire AskUserInputTool's show callback to dashboard process input view
        com.workflow.orchestrator.agent.tools.builtin.AskUserInputTool.showInputCallback = { processId, description, prompt, command ->
            invokeLater { dashboard.showProcessInput(processId, description, prompt, command) }
        }
        dashboard.setCefProcessInputCallbacks { input ->
            com.workflow.orchestrator.agent.tools.builtin.AskUserInputTool.resolveInput(input)
        }

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

        // Gap 15+17: Track last task for retry and session title
        lastTaskText = task

        // USER_PROMPT_SUBMIT hook (ported from Cline's UserPromptSubmit hook)
        // Fires after user input, before processing. Cancellable: can block the message.
        // Cline: "Executes when the user submits a prompt to Cline."
        val hookManager = service.hookManager
        if (hookManager.hasHooks(HookType.USER_PROMPT_SUBMIT)) {
            val hookResult = runBlocking {
                hookManager.dispatch(
                    HookEvent(
                        type = HookType.USER_PROMPT_SUBMIT,
                        data = mapOf(
                            "message" to task
                        )
                    )
                )
            }
            if (hookResult is HookResult.Cancel) {
                LOG.info("AgentController: USER_PROMPT_SUBMIT hook cancelled: ${hookResult.reason}")
                return
            }
        }

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
                    // Display token usage summary (ported from Cline's cost tracking)
                    if (result.inputTokens > 0 || result.outputTokens > 0) {
                        val inputK = formatTokenCount(result.inputTokens)
                        val outputK = formatTokenCount(result.outputTokens)
                        dashboard.appendStatus(
                            "Used ${inputK} input + ${outputK} output tokens",
                            RichStreamingPanel.StatusType.INFO
                        )
                    }
                    // Gap 21: Show edit stats if any changes were made
                    if (result.linesAdded > 0 || result.linesRemoved > 0) {
                        dashboard.updateEditStats(
                            result.linesAdded,
                            result.linesRemoved,
                            result.filesModified.size
                        )
                    }
                    // Gap 1+14: Pass actual modified files from loop tracking
                    dashboard.completeSession(
                        tokensUsed = result.tokensUsed,
                        iterations = result.iterations,
                        filesModified = result.filesModified,
                        durationMs = durationMs,
                        status = RichStreamingPanel.SessionStatus.SUCCESS
                    )
                }

                is LoopResult.Failed -> {
                    dashboard.appendError(result.error)
                    if (result.inputTokens > 0 || result.outputTokens > 0) {
                        val inputK = formatTokenCount(result.inputTokens)
                        val outputK = formatTokenCount(result.outputTokens)
                        dashboard.appendStatus(
                            "Used ${inputK} input + ${outputK} output tokens",
                            RichStreamingPanel.StatusType.INFO
                        )
                    }
                    dashboard.completeSession(
                        tokensUsed = result.tokensUsed,
                        iterations = result.iterations,
                        filesModified = result.filesModified,
                        durationMs = durationMs,
                        status = RichStreamingPanel.SessionStatus.FAILED
                    )
                    // Gap 17: Show retry button so user can re-execute the last task
                    lastTaskText?.let { dashboard.showRetryButton(it) }
                }

                is LoopResult.Cancelled -> {
                    dashboard.appendStatus("Task cancelled.", RichStreamingPanel.StatusType.INFO)
                    dashboard.completeSession(
                        tokensUsed = result.tokensUsed,
                        iterations = result.iterations,
                        filesModified = result.filesModified,
                        durationMs = durationMs,
                        status = RichStreamingPanel.SessionStatus.CANCELLED
                    )
                }

                is LoopResult.PlanPresented -> {
                    // Priority 1: Plan presented for user review — render plan card, keep session alive.
                    // Do NOT call completeSession() — the session stays active for approve/revise.
                    currentPlan = result.plan

                    // Parse the free-text plan into structured JSON for the JCEF plan card
                    val planJson = PlanParser.parseToJson(result.plan)
                    dashboard.renderPlan(planJson)

                    // Display token usage so far
                    if (result.inputTokens > 0 || result.outputTokens > 0) {
                        val inputK = formatTokenCount(result.inputTokens)
                        val outputK = formatTokenCount(result.outputTokens)
                        dashboard.appendStatus(
                            "Plan exploration used ${inputK} input + ${outputK} output tokens",
                            RichStreamingPanel.StatusType.INFO
                        )
                    }

                    // Unlock input so the user can interact with the plan (approve/comment)
                    dashboard.setBusy(false)
                    dashboard.setInputLocked(false)
                    dashboard.focusInput()
                    currentJob = null
                    return@invokeLater // Skip the common unlock at the bottom
                }

                is LoopResult.SessionHandoff -> {
                    // Session handoff (ported from Cline's new_task):
                    // Notify user, then automatically start a new session with preserved context
                    dashboard.appendStatus(
                        "Context limit reached. Starting fresh session with preserved context.",
                        RichStreamingPanel.StatusType.INFO
                    )
                    if (result.inputTokens > 0 || result.outputTokens > 0) {
                        val inputK = formatTokenCount(result.inputTokens)
                        val outputK = formatTokenCount(result.outputTokens)
                        dashboard.appendStatus(
                            "Previous session used ${inputK} input + ${outputK} output tokens",
                            RichStreamingPanel.StatusType.INFO
                        )
                    }

                    // Reset context for the new session
                    contextManager = null

                    // Auto-start fresh session with handoff context
                    currentJob = service.startHandoffSession(
                        handoffContext = result.context,
                        onStreamChunk = ::onStreamChunk,
                        onToolCall = ::onToolCall,
                        onTaskProgress = ::onTaskProgress,
                        onComplete = ::onComplete
                    )
                    // Don't unlock input — the new session is running
                    return@invokeLater
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
        currentPlan = null
        lastTaskText = null

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

    /**
     * List checkpoints for the current session.
     *
     * Ported from Cline's checkpoint reversion UI:
     * returns the list of checkpoints so the UI can display them
     * and allow the user to revert.
     */
    fun listCheckpoints(sessionId: String): List<com.workflow.orchestrator.agent.session.CheckpointInfo> {
        return service.listCheckpoints(sessionId)
    }

    /**
     * Revert to a specific checkpoint in a session.
     *
     * Ported from Cline's checkpoint reversion:
     * restores the conversation to the checkpoint state and continues.
     */
    fun revertToCheckpoint(sessionId: String, checkpointId: String) {
        LOG.info("AgentController.revertToCheckpoint: session=$sessionId, checkpoint=$checkpointId")

        // Cancel any running task first
        if (currentJob?.isActive == true) {
            service.cancelCurrentTask()
        }
        currentJob = null

        // Reset UI
        dashboard.reset()
        dashboard.setBusy(true)
        dashboard.setInputLocked(true)
        taskStartTime = System.currentTimeMillis()

        dashboard.appendStatus("Reverting to checkpoint...", RichStreamingPanel.StatusType.INFO)

        // Reset context manager — the reverted session creates its own
        contextManager = null

        val job = service.revertToCheckpoint(
            sessionId = sessionId,
            checkpointId = checkpointId,
            onStreamChunk = ::onStreamChunk,
            onToolCall = ::onToolCall,
            onTaskProgress = ::onTaskProgress,
            onComplete = ::onComplete
        )

        if (job != null) {
            currentJob = job
        } else {
            dashboard.appendError("Could not revert to checkpoint. The checkpoint may have been deleted.")
            dashboard.setBusy(false)
            dashboard.setInputLocked(false)
            dashboard.focusInput()
        }
    }

    // ═══════════════════════════════════════════════════
    //  Plan mode lifecycle — Priority 1
    // ═══════════════════════════════════════════════════

    /**
     * User approved the plan — switch to act mode and implement it.
     *
     * Flow: plan_mode_respond -> PlanPresented -> user clicks Approve ->
     * switch to act mode -> send approved plan to LLM -> LLM implements step by step.
     */
    private fun approvePlan() {
        LOG.info("AgentController.approvePlan")
        val plan = currentPlan
        if (plan == null) {
            LOG.warn("AgentController.approvePlan: no plan to approve")
            return
        }

        // Switch to act mode
        AgentService.planModeActive.set(false)
        dashboard.setPlanMode(false)

        // Clear the stored plan
        currentPlan = null

        // Send the approved plan as a user message to continue the loop
        val instruction = buildString {
            appendLine("The user approved this plan. Now switch to ACT MODE and implement it step by step.")
            appendLine()
            appendLine("APPROVED PLAN:")
            appendLine(plan)
            appendLine()
            appendLine("Execute each step in order. Use tools to implement the changes. " +
                "Call attempt_completion when all steps are done.")
        }
        executeTask(instruction)
    }

    /**
     * User submitted per-step comments on the plan — send to LLM for revision.
     *
     * Flow: plan_mode_respond -> PlanPresented -> user adds comments ->
     * send comments to LLM -> LLM revises plan -> PlanPresented (revised).
     *
     * @param commentsJson JSON array of step comments: [{"stepId":"1","comment":"..."}]
     */
    private fun revisePlan(commentsJson: String) {
        LOG.info("AgentController.revisePlan: $commentsJson")

        // Parse the comments JSON into a human-readable revision request
        val revisionMessage = buildString {
            appendLine("I have comments on your plan. Please revise it:")
            appendLine()
            try {
                val comments = kotlinx.serialization.json.Json.parseToJsonElement(commentsJson)
                if (comments is kotlinx.serialization.json.JsonArray) {
                    for (item in comments) {
                        if (item is kotlinx.serialization.json.JsonObject) {
                            val stepId = item["stepId"]?.let {
                                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                            } ?: "?"
                            val comment = item["comment"]?.let {
                                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                            } ?: ""
                            if (comment.isNotBlank()) {
                                appendLine("- Step $stepId: $comment")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // If JSON parsing fails, use the raw text
                appendLine(commentsJson)
            }
            appendLine()
            appendLine("Please revise the plan and present the updated version using plan_mode_respond.")
        }

        // Keep plan mode active — the LLM will explore more if needed and present a revised plan
        executeTask(revisionMessage)
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

    /**
     * Format token count for display: "45K" for large counts, exact for small.
     * Ported from Cline's webview token display pattern.
     */
    private fun formatTokenCount(tokens: Int): String {
        return if (tokens >= 1000) {
            "${tokens / 1000}K"
        } else {
            tokens.toString()
        }
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
