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
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.PlanParser
import com.workflow.orchestrator.agent.loop.TaskProgress
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.observability.HaikuPhraseGenerator
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.agent.tools.subagent.SubagentProgressUpdate
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    /**
     * Channel for feeding user input into a running loop.
     * Used in plan mode: after the LLM presents a plan, the loop waits on this channel.
     * User messages, plan comments, and approve actions all send into this channel.
     * Matches Cline's ask() pattern where the loop suspends until user responds.
     */
    private var userInputChannel: Channel<String>? = null
    /** True when the loop is actively waiting for user input (plan presented, not exploring). */
    private var loopWaitingForInput = false

    /**
     * Pending approval deferred — the agent loop suspends on this while
     * waiting for the user to approve/deny a write tool execution.
     * Completed by the JCEF approval card callbacks.
     */
    private var pendingApproval: CompletableDeferred<ApprovalResult>? = null

    /**
     * The tool name associated with the current pending approval.
     * Used when the user clicks "Allow for Session" — we need to know which tool.
     */
    private var pendingApprovalToolName: String? = null

    /**
     * Current session ID -- tracked so checkpoint display and revert can reference it.
     * Set when executeTask creates or resumes a session, cleared on newChat.
     */
    private var currentSessionId: String? = null

    /** Recent tool calls for Haiku phrase context (FIFO, max 3). */
    private val recentToolCalls = mutableListOf<Pair<String, String>>()

    /** Coroutine job for the 30s Haiku phrase timer. */
    private var phraseTimerJob: Job? = null

    /** Current Haiku-generated session title (null until first generation). */
    private var currentHaikuTitle: String? = null

    /** Last LLM stream text snippet — gives Haiku context about what the agent is thinking. */
    @Volatile private var lastStreamSnippet: String = ""

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

        // Approval gate callbacks — user responds to approval cards for write tools
        dashboard.setCefApprovalCallbacks(
            onApprove = {
                LOG.info("AgentController: tool call approved")
                pendingApproval?.complete(ApprovalResult.APPROVED)
            },
            onDeny = {
                LOG.info("AgentController: tool call denied")
                pendingApproval?.complete(ApprovalResult.DENIED)
            },
            onAllowForSession = { _ ->
                LOG.info("AgentController: tool '${pendingApprovalToolName}' allowed for session")
                pendingApproval?.complete(ApprovalResult.ALLOWED_FOR_SESSION)
            }
        )

        // Checkpoint revert callback — user clicks "Revert" on a checkpoint in the timeline
        dashboard.setCefRevertCheckpointCallback { checkpointId ->
            val sid = currentSessionId
            if (sid != null) {
                revertToCheckpoint(sid, checkpointId)
            } else {
                LOG.warn("AgentController: revert requested but no active session")
            }
        }

        // Wire AskQuestionsTool callbacks
        // Simple mode: show question in chat stream, user types answer via chat input.
        // The tool blocks on pendingQuestions deferred. When the user sends a message,
        // executeTask() intercepts it and resolves the deferred directly.
        com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool.showSimpleQuestionCallback = { question, optionsJson ->
            invokeLater {
                val display = buildString {
                    append(question)
                    if (!optionsJson.isNullOrBlank()) {
                        try {
                            val options = kotlinx.serialization.json.Json.decodeFromString<List<String>>(optionsJson)
                            if (options.isNotEmpty()) {
                                append("\n\nOptions:")
                                options.forEachIndexed { i, opt -> append("\n${i + 1}. $opt") }
                            }
                        } catch (_: Exception) {}
                    }
                }
                // Show question as a status message and unlock input for user to type answer
                dashboard.appendStatus(display, RichStreamingPanel.StatusType.INFO)
                dashboard.setBusy(false) // Unlock input so user can type
            }
        }
        // Wizard mode: structured multi-question UI
        com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool.showQuestionsCallback = { questionsJson ->
            invokeLater { dashboard.showQuestions(questionsJson) }
        }
        // Accumulate individual question answers so we can pass them on submit
        val collectedAnswers = mutableMapOf<String, String>()
        dashboard.setCefQuestionCallbacks(
            onAnswered = { questionId, selectedOptionsJson ->
                collectedAnswers[questionId] = selectedOptionsJson
            },
            onSkipped = { questionId ->
                collectedAnswers[questionId] = "[]"
            },
            onChatAbout = { _, _, _ -> /* Chat about option not used for tool flow */ },
            onSubmitted = {
                // Build JSON from accumulated answers and resolve the pending deferred
                val answersJson = buildString {
                    append("{")
                    collectedAnswers.entries.joinTo(this) { (qid, opts) ->
                        "\"$qid\":$opts"
                    }
                    append("}")
                }
                com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool.resolveQuestions(answersJson)
                collectedAnswers.clear()
            },
            onCancelled = {
                com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool.cancelQuestions()
                collectedAnswers.clear()
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

        // Fetch available models from Sourcegraph and populate the dropdown
        loadModelList()

        // Push available skills to the chat UI for autocomplete/suggestions
        loadSkillsList()
    }

    /**
     * Fetch the model list from Sourcegraph and send to the dashboard dropdown.
     * Uses ModelCache (24h TTL) to avoid redundant API calls.
     * Runs in background — failure is non-fatal (dropdown shows current model only).
     */
    /**
     * Fetch models from Sourcegraph, populate the dropdown, and auto-select the best
     * (latest Opus) model. Uses ModelCache (24h TTL) to avoid redundant API calls.
     * Runs in background — failure is non-fatal.
     */
    private fun loadModelList() {
        val connections = com.workflow.orchestrator.core.settings.ConnectionSettings.getInstance()
        if (connections.state.sourcegraphUrl.isBlank()) return

        val credentialStore = com.workflow.orchestrator.core.auth.CredentialStore()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        scope.launch {
            try {
                val client = com.workflow.orchestrator.core.ai.SourcegraphChatClient(
                    baseUrl = connections.state.sourcegraphUrl.trimEnd('/'),
                    tokenProvider = { credentialStore.getToken(com.workflow.orchestrator.core.model.ServiceType.SOURCEGRAPH) },
                    model = ""
                )
                val models = com.workflow.orchestrator.core.ai.ModelCache.getModels(client)
                if (models.isNotEmpty()) {
                    // Sort by tier (opus first) then by created date (newest first)
                    val sorted = models.sortedWith(compareBy<com.workflow.orchestrator.core.ai.dto.ModelInfo> { it.tier }.thenByDescending { it.created })

                    // Build JSON for the dropdown using formatted display names
                    val modelsJson = sorted.joinToString(",", "[", "]") { m ->
                        val id = m.id.replace("\"", "\\\"")
                        val name = m.displayName.replace("\"", "\\\"")
                        val provider = m.provider.replace("\"", "\\\"")
                        val thinking = m.isThinkingModel
                        """{"id":"$id","name":"$name","provider":"$provider","thinking":$thinking}"""
                    }

                    // Auto-select the best model (latest Opus) if no model is configured
                    val settings = AgentSettings.getInstance(project)
                    val best = com.workflow.orchestrator.core.ai.ModelCache.pickBest(models)
                    if (best != null && settings.state.sourcegraphChatModel.isNullOrBlank()) {
                        settings.state.sourcegraphChatModel = best.id
                        LOG.info("AgentController: auto-selected model: ${best.displayName} (${best.id})")
                    }

                    com.intellij.openapi.application.invokeLater {
                        dashboard.updateModelList(modelsJson)
                        // Show the active model's formatted display name
                        val activeModel = settings.state.sourcegraphChatModel ?: best?.id ?: ""
                        val displayName = models.find { it.id == activeModel }?.displayName ?: activeModel
                        if (displayName.isNotBlank()) {
                            dashboard.setModelName(displayName)
                        }
                    }
                    LOG.info("AgentController: loaded ${models.size} models for dropdown")
                }
            } catch (e: Exception) {
                LOG.debug("AgentController: failed to load model list: ${e.message}")
            }
        }
    }

    /**
     * Push available skills to the dashboard for chat input autocomplete.
     * Skills are loaded from bundled resources + user directories.
     * The React webview uses this list for /skill suggestions and toolbar dropdown.
     */
    private fun loadSkillsList() {
        val basePath = project.basePath ?: return
        try {
            val discovered = com.workflow.orchestrator.agent.prompt.InstructionLoader.discoverSkills(basePath)
            val allSkills = com.workflow.orchestrator.agent.prompt.InstructionLoader.getAvailableSkills(discovered)
            if (allSkills.isNotEmpty()) {
                val skillsJson = allSkills.joinToString(",", "[", "]") { skill ->
                    val name = skill.name.replace("\"", "\\\"")
                    val desc = skill.description.replace("\"", "\\\"").take(200)
                    """{"name":"$name","description":"$desc"}"""
                }
                invokeLater {
                    dashboard.updateSkillsList(skillsJson)
                }
                LOG.info("AgentController: pushed ${allSkills.size} skills to chat UI")
            }
        } catch (e: Exception) {
            LOG.debug("AgentController: failed to load skills list: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════
    //  Core: executeTask — send user message to agent loop
    // ═══════════════════════════════════════════════════

    /**
     * Execute a user task. Called from the chat input, redirect, or example prompts.
     * Safe to call multiple times for multi-turn conversation.
     *
     * If a loop is currently waiting for user input (plan mode), feeds the message
     * into the existing loop's channel instead of starting a new one. This matches
     * Cline's continuous conversation model where plan mode is a dialogue.
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

        // If a simple question is pending (ask_followup_question), resolve it with user's answer
        val pending = com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool.pendingQuestions
        if (pending != null && !pending.isCompleted && currentJob?.isActive == true) {
            LOG.info("AgentController: resolving pending question with user answer")
            dashboard.appendUserMessage(task)
            dashboard.setBusy(true)
            pending.complete(task)
            return
        }

        // If the loop is waiting for user input (plan mode dialogue), feed into it
        val channel = userInputChannel
        if (loopWaitingForInput && channel != null && currentJob?.isActive == true) {
            LOG.info("AgentController: feeding user message into existing loop via channel")
            dashboard.appendUserMessage(task)
            dashboard.setBusy(true)
            // Input is NOT locked — user can always type freely (Cline behavior)
            loopWaitingForInput = false
            runBlocking { channel.send(task) }
            return
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
        // Input is NOT locked — user can always type (Cline behavior)
        taskStartTime = System.currentTimeMillis()

        // Create context manager on first message, reuse on subsequent turns
        val isFirstMessage = contextManager == null
        if (isFirstMessage) {
            val settings = AgentSettings.getInstance(project)
            contextManager = ContextManager(maxInputTokens = settings.state.maxInputTokens)
            dashboard.startSession(task)
        }

        // Generate or update conversation title via Haiku (async, non-blocking)
        generateConversationTitle(task, isFirstMessage)

        // Create a fresh input channel for this loop run
        userInputChannel = Channel(Channel.RENDEZVOUS)

        // Launch the agent loop
        val debugEnabled = AgentSettings.getInstance(project).state.showDebugLog
        if (debugEnabled) {
            dashboard.pushDebugLogEntry("session", "task_start", task.take(200), null)
        }
        currentJob = service.executeTask(
            task = task,
            contextManager = contextManager,
            onStreamChunk = ::onStreamChunk,
            onToolCall = ::onToolCall,
            onTaskProgress = ::onTaskProgress,
            onComplete = { result ->
                if (AgentSettings.getInstance(project).state.showDebugLog) {
                    val status = when (result) {
                        is LoopResult.Completed -> "completed"
                        is LoopResult.Cancelled -> "cancelled"
                        is LoopResult.Failed -> "failed"
                        is LoopResult.SessionHandoff -> "handoff"
                    }
                    dashboard.pushDebugLogEntry("session", "task_end", status, null)
                }
                onComplete(result)
            },
            onPlanResponse = ::onPlanResponse,
            onPlanModeToggled = { enabled -> invokeLater { togglePlanMode(enabled) } },
            userInputChannel = userInputChannel,
            approvalGate = ::approvalGate,
            onCheckpointSaved = ::onCheckpointSaved,
            onSubagentProgress = ::onSubagentProgress,
            onTokenUpdate = ::onTokenUpdate,
            onDebugLog = if (debugEnabled) { level, event, detail, meta ->
                dashboard.pushDebugLogEntry(level, event, detail, meta)
            } else null,
            onSessionStarted = { sid -> currentSessionId = sid }
        )

        // Start 30s Haiku phrase timer (if smart working indicator is enabled)
        startPhraseTimer(task)
    }

    // ═══════════════════════════════════════════════════
    //  Streaming callbacks — agent loop → dashboard
    // ═══════════════════════════════════════════════════

    private fun onStreamChunk(chunk: String) {
        // Capture a rolling snippet of the LLM's output for Haiku phrase context
        lastStreamSnippet = (lastStreamSnippet + chunk).takeLast(150)
        invokeLater {
            dashboard.appendStreamToken(chunk)
        }
    }

    /**
     * Approval gate -- suspends the agent loop until the user approves, denies,
     * or allows a write tool for the session.
     *
     * Ported from Cline's approval flow: Cline shows an approval card in the webview
     * and waits for the user's response before proceeding. We use a CompletableDeferred
     * to suspend the coroutine without blocking a thread.
     *
     * @param toolName the tool requesting approval (e.g. "edit_file", "run_command")
     * @param args the raw JSON arguments string
     * @param riskLevel "low", "medium", or "high" risk classification
     * @return the user's decision
     */
    private suspend fun approvalGate(toolName: String, args: String, riskLevel: String): ApprovalResult {
        val deferred = CompletableDeferred<ApprovalResult>()
        pendingApproval = deferred
        pendingApprovalToolName = toolName

        // Build description, metadata, and diff content for the approval card
        val parsedArgs = try {
            kotlinx.serialization.json.Json.parseToJsonElement(args).jsonObject
        } catch (_: Exception) { null }

        val description: String
        val diffContent: String?

        when (toolName) {
            "edit_file" -> {
                val path = parsedArgs?.get("path")?.jsonPrimitive?.content ?: "unknown"
                val oldString = parsedArgs?.get("old_string")?.jsonPrimitive?.content ?: ""
                val newString = parsedArgs?.get("new_string")?.jsonPrimitive?.content ?: ""
                val editDesc = parsedArgs?.get("description")?.jsonPrimitive?.content
                description = editDesc ?: "Edit $path"
                diffContent = com.workflow.orchestrator.agent.util.DiffUtil.unifiedDiff(oldString, newString, path)
            }
            "create_file" -> {
                val path = parsedArgs?.get("path")?.jsonPrimitive?.content ?: "unknown"
                val content = parsedArgs?.get("content")?.jsonPrimitive?.content ?: ""
                description = "Create $path"
                val preview = if (content.length > 2000) content.take(2000) + "\n... (${content.length} chars total)" else content
                diffContent = com.workflow.orchestrator.agent.util.DiffUtil.unifiedDiff("", preview, path)
            }
            "run_command" -> {
                val command = parsedArgs?.get("command")?.jsonPrimitive?.content ?: "unknown"
                val shell = parsedArgs?.get("shell")?.jsonPrimitive?.content ?: ""
                val cmdDesc = parsedArgs?.get("description")?.jsonPrimitive?.content
                description = cmdDesc ?: "Run: $command"
                diffContent = "$ $command\n(shell: $shell)"
            }
            "revert_file" -> {
                val path = parsedArgs?.get("path")?.jsonPrimitive?.content ?: "unknown"
                description = "Revert $path to last saved state"
                diffContent = null
            }
            else -> {
                description = "Execute $toolName"
                diffContent = args.take(500)
            }
        }

        val metadataJson = """{"tool":"$toolName","riskLevel":"$riskLevel"}"""

        // Show the approval card in the dashboard (on EDT)
        invokeLater {
            dashboard.showApproval(
                toolName = toolName,
                riskLevel = riskLevel,
                description = description,
                metadataJson = metadataJson,
                diffContent = diffContent
            )
        }

        // Suspend until the user responds (approve/deny/allow for session)
        return try {
            deferred.await()
        } finally {
            pendingApproval = null
            pendingApprovalToolName = null
        }
    }

    /**
     * Called by AgentService after a write checkpoint is saved.
     * Updates the checkpoint timeline in the dashboard UI.
     *
     * @param sessionId the session the checkpoint belongs to
     */
    private fun onCheckpointSaved(sessionId: String) {
        currentSessionId = sessionId
        invokeLater {
            val checkpoints = service.listCheckpoints(sessionId)
            val checkpointsJson = buildCheckpointsJson(checkpoints)
            dashboard.updateCheckpoints(checkpointsJson)
        }
    }

    /**
     * Sub-agent progress callback — streams sub-agent lifecycle events to the dashboard.
     * Called by SpawnAgentTool via AgentService when sub-agents report status changes.
     * Not a suspend function — wraps UI updates in invokeLater.
     */
    private fun onSubagentProgress(agentId: String, update: SubagentProgressUpdate) {
        invokeLater {
            when (update.status) {
                "running" -> {
                    val label = update.latestToolCall ?: "Starting..."
                    dashboard.spawnSubAgent(agentId, label)
                }
                "completed" -> {
                    dashboard.completeSubAgent(
                        agentId,
                        update.result ?: "Completed",
                        update.stats?.inputTokens?.plus(update.stats.outputTokens) ?: 0,
                        isError = false
                    )
                }
                "failed" -> {
                    dashboard.completeSubAgent(
                        agentId,
                        update.error ?: "Failed",
                        update.stats?.inputTokens?.plus(update.stats.outputTokens) ?: 0,
                        isError = true
                    )
                }
                else -> {
                    update.latestToolCall?.let { toolCall ->
                        dashboard.addSubAgentToolCall(agentId, toolCall, "")
                    }
                    update.stats?.let { stats ->
                        dashboard.updateSubAgentIteration(agentId, stats.toolCalls)
                    }
                }
            }
        }
    }

    /**
     * Token usage callback — agent loop reports per-call token counts after each API call.
     * The first argument is the current prompt token count (how full the context window is),
     * NOT a cumulative total. The progress bar shows "promptTokens / maxInputTokens".
     */
    private fun onTokenUpdate(promptTokens: Int, completionTokens: Int) {
        invokeLater {
            val maxTokens = AgentSettings.getInstance(project).state.maxInputTokens
            // promptTokens = current context window usage (what matters for the progress bar)
            dashboard.updateProgress("", promptTokens, maxTokens)
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
        // Track recent tool calls for Haiku phrase generator — extract the most useful arg
        if (progress.result.isEmpty() && progress.durationMs == 0L) {
            val contextHint = try {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(progress.args).jsonObject
                // Try common arg names in priority order for a meaningful context hint
                (obj["path"]?.jsonPrimitive?.content?.substringAfterLast("/")
                    ?: obj["action"]?.jsonPrimitive?.content
                    ?: obj["query"]?.jsonPrimitive?.content?.take(40)
                    ?: obj["command"]?.jsonPrimitive?.content?.take(40)
                    ?: obj["project_key"]?.jsonPrimitive?.content
                    ?: obj["pattern"]?.jsonPrimitive?.content?.take(40)
                    ?: obj["issue_key"]?.jsonPrimitive?.content
                    ?: obj["branch"]?.jsonPrimitive?.content
                    ?: "")
            } catch (_: Exception) { "" }
            synchronized(recentToolCalls) {
                recentToolCalls.add(progress.toolName to contextHint)
                if (recentToolCalls.size > 3) recentToolCalls.removeAt(0)
            }
        }

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

                // Show skill banner when use_skill activates a skill
                if (progress.toolName == "use_skill" && !progress.isError) {
                    val skillName = progress.result.substringAfter("'").substringBefore("'")
                    if (skillName.isNotBlank()) {
                        dashboard.showSkillBanner(skillName)
                    }
                }
            }
        }
    }

    private fun onComplete(result: LoopResult) {
        phraseTimerJob?.cancel()
        phraseTimerJob = null

        val durationMs = System.currentTimeMillis() - taskStartTime

        invokeLater {
            // Flush any remaining stream content
            dashboard.flushStreamBuffer()
            // Finalize any open tool chain in the UI (collapse running indicators)
            dashboard.finalizeToolChain()
            // Hide skill banner on task completion
            dashboard.hideSkillBanner()
            // Clear working phrase
            dashboard.setSmartWorkingPhrase("")

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
            // Reset channel state — the loop has finished
            userInputChannel?.close()
            userInputChannel = null
            loopWaitingForInput = false
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

        // Reset all service-level state (plan mode, tools, processes, active task)
        service.resetForNewChat()

        // Reset controller state
        currentJob = null
        phraseTimerJob?.cancel()
        phraseTimerJob = null
        recentToolCalls.clear()
        currentHaikuTitle = null
        lastStreamSnippet = ""
        contextManager?.clearActivePlanPath()
        contextManager = null
        taskStartTime = 0L
        lastTaskText = null
        currentSessionId = null
        pendingApproval?.cancel()
        pendingApproval = null
        pendingApprovalToolName = null
        userInputChannel?.close()
        userInputChannel = null
        loopWaitingForInput = false

        // Reset ALL dashboard UI components to clean state
        dashboard.reset()                                          // Clear chat messages + replay log
        dashboard.hideSkillBanner()                                // Dismiss any active skill banner
        dashboard.setBusy(false)                                   // Stop spinner
        dashboard.setInputLocked(false)                            // Unlock input bar
        dashboard.setPlanMode(false)                                // Exit plan mode in UI
        dashboard.setRalphLoop(false)                              // Exit ralph loop mode
        dashboard.setSteeringMode(false)                           // Exit steering mode
        dashboard.updateCheckpoints("[]")                          // Clear checkpoint timeline
        dashboard.updateEditStats(0, 0, 0)                    // Reset edit counters
        dashboard.updateProgress("", 0, 0)                    // Reset token budget bar
        dashboard.setSmartWorkingPhrase("")                         // Clear working phrase
        dashboard.setSessionTitle("")                               // Clear conversation title
        dashboard.finalizeToolChain()                               // Collapse any open tool chain
        dashboard.focusInput()                                      // Focus the input bar
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

        // Restore the last task text in the input bar so user can edit and re-send
        lastTaskText?.let { dashboard.restoreInputText(it) }

        // Collect files modified since the target checkpoint for rollback notification
        val affectedFiles = service.getFilesModifiedSinceCheckpoint(sessionId, checkpointId)

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
            // Notify UI which files were affected by the rollback
            if (affectedFiles.isNotEmpty()) {
                val rollbackJson = affectedFiles.joinToString(",", """{"affectedFiles":[""", "]}") { file ->
                    "\"${file.replace("\"", "\\\"")}\""
                }
                dashboard.notifyRollback(rollbackJson)
            }
        } else {
            dashboard.appendError("Could not revert to checkpoint. The checkpoint may have been deleted.")
            dashboard.setBusy(false)
            dashboard.setInputLocked(false)
            dashboard.focusInput()
        }
    }

    // ═══════════════════════════════════════════════════
    //  Plan mode lifecycle — continuous conversation (Cline pattern)
    // ═══════════════════════════════════════════════════

    /**
     * Callback from AgentLoop when plan_mode_respond is called.
     * Renders the plan card in the UI. The loop does NOT exit.
     *
     * If needsMoreExploration=true, the loop continues immediately.
     * If needsMoreExploration=false, the loop will wait on userInputChannel
     * for the user to respond (type chat, add comments, or approve).
     */
    private fun onPlanResponse(planText: String, needsMoreExploration: Boolean) {
        // Save plan to disk and store path in ContextManager for compaction survival.
        // Done outside invokeLater so it's synchronous and guaranteed before UI render.
        val sid = currentSessionId
        if (sid != null) {
            try {
                val basePath = project.basePath ?: System.getProperty("user.home")
                val sessionDir = java.io.File(
                    ProjectIdentifier.agentDir(basePath),
                    "sessions/$sid"
                )
                val planFile = java.io.File(sessionDir, "plan.md")
                planFile.parentFile?.mkdirs()
                planFile.writeText(planText, Charsets.UTF_8)
                // Store path in ContextManager so it survives compaction
                contextManager?.setActivePlanPath(planFile.absolutePath)
                LOG.info("AgentController: plan saved to ${planFile.absolutePath}")
            } catch (e: Exception) {
                LOG.warn("AgentController: failed to save plan to disk: ${e.message}")
            }
        } else {
            LOG.warn("AgentController: onPlanResponse called but currentSessionId is null — plan not saved")
        }

        invokeLater {
            // Parse and render the plan card (our custom addition on top of Cline)
            val planJson = PlanParser.parseToJson(planText)
            dashboard.renderPlan(planJson)

            if (!needsMoreExploration) {
                // The loop is now waiting for user input — unlock the UI
                loopWaitingForInput = true
                dashboard.setBusy(false)
                // Input is never locked — user always types freely
                dashboard.focusInput()
            }
        }
    }

    /**
     * User approved the plan — switch to act mode and send approval message into the loop.
     *
     * The loop is waiting on userInputChannel. We send the approval message which:
     * 1. Switches planModeActive to false
     * 2. Feeds the approval instruction into the waiting loop
     * 3. The LLM continues in act mode and implements the plan
     */
    private fun approvePlan() {
        LOG.info("AgentController.approvePlan")

        // Switch to act mode — only the USER can do this (Cline behavior)
        AgentService.planModeActive.set(false)
        dashboard.setPlanMode(false)

        // Feed the approval into the loop's input channel
        val instruction = "The user has approved the plan. Switch to ACT MODE and implement it step by step. " +
            "Follow the plan exactly. Call attempt_completion when all steps are done."

        // Use executeTask which handles the channel-feeding logic
        executeTask(instruction)
    }

    /**
     * User submitted per-step comments on the plan — format and send into the loop.
     *
     * The loop stays in plan mode. The comments go through the userInputChannel,
     * the LLM sees them, revises the plan, and presents again.
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

        // Feed into the loop — stays in plan mode, LLM revises
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
        // Resolve formatted display name from cache, fall back to formatModelName on the raw ID
        val cached = com.workflow.orchestrator.core.ai.ModelCache.getCached()
        val displayName = cached.find { it.id == model }?.displayName
            ?: com.workflow.orchestrator.core.ai.dto.ModelInfo.formatModelName(model.substringAfterLast("::"))
        dashboard.setModelName(displayName)
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

        // Wire the mirror's input callbacks identically to the primary dashboard
        mirror.setCefActionCallbacks(
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
        mirror.setCefCallbacks(
            onUndo = { LOG.info("Undo requested — not implemented in lean rewrite") },
            onViewTrace = { LOG.info("View trace requested — not implemented in lean rewrite") },
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

    /**
     * Start a 30-second timer that generates humorous contextual working phrases via Haiku.
     * Fire-and-forget: failures are silently ignored (the current phrase stays).
     * Gated by smartWorkingIndicator setting.
     */
    private fun startPhraseTimer(task: String) {
        phraseTimerJob?.cancel()

        if (!AgentSettings.getInstance(project).state.smartWorkingIndicator) {
            LOG.info("AgentController: smart working indicator disabled, skipping phrase timer")
            return
        }

        LOG.info("AgentController: starting Haiku phrase timer (30s interval)")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        phraseTimerJob = scope.launch {
            delay(30_000)
            while (isActive) {
                try {
                    val tools = synchronized(recentToolCalls) { recentToolCalls.toList() }
                    val agentThinking = lastStreamSnippet.takeLast(100)
                    LOG.info("AgentController: requesting Haiku phrase (${tools.size} recent tools)")
                    val phrase = HaikuPhraseGenerator.generate(task, tools, agentThinking)
                    if (phrase != null) {
                        LOG.info("AgentController: got Haiku phrase: $phrase")
                        invokeLater { dashboard.setSmartWorkingPhrase(phrase) }
                    } else {
                        LOG.info("AgentController: Haiku phrase returned null")
                    }
                } catch (e: Exception) {
                    LOG.warn("AgentController: Haiku phrase timer error: ${e.message}")
                }
                delay(30_000)
            }
        }
    }

    /**
     * Generate or update the conversation title via Haiku.
     * On first message: generate a fresh title.
     * On subsequent messages: check if scope has shifted enough to warrant a new title.
     * Async — never blocks the agent loop.
     */
    private fun generateConversationTitle(task: String, isFirstMessage: Boolean) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                val newTitle = if (isFirstMessage) {
                    HaikuPhraseGenerator.generateTitle(task)
                } else {
                    val existing = currentHaikuTitle ?: return@launch
                    HaikuPhraseGenerator.checkTitleUpdate(existing, task)
                }

                if (newTitle != null) {
                    currentHaikuTitle = newTitle
                    // Update session metadata in store
                    val sid = currentSessionId
                    if (sid != null) {
                        service.updateSessionTitle(sid, newTitle)
                    }
                    // Push to chat UI top bar
                    invokeLater { dashboard.setSessionTitle(newTitle) }
                    LOG.info("AgentController: conversation title set to: $newTitle")
                }
            } catch (e: Exception) {
                LOG.debug("AgentController: title generation failed: ${e.message}")
            }
        }
    }

    /**
     * Build a contextual working phrase from the current tool call.
     * Shown in the UI status area while the agent is executing tools.
     */
    private fun buildWorkingPhrase(toolName: String, args: String): String {
        val parsedArgs = try {
            kotlinx.serialization.json.Json.parseToJsonElement(args) as? kotlinx.serialization.json.JsonObject
        } catch (_: Exception) { null }

        val path = parsedArgs?.get("path")?.jsonPrimitive?.content
            ?.substringAfterLast("/")

        return when (toolName) {
            "read_file" -> "Reading ${path ?: "file"}..."
            "edit_file" -> "Editing ${path ?: "file"}..."
            "create_file" -> "Creating ${path ?: "file"}..."
            "search_code" -> {
                val query = parsedArgs?.get("query")?.jsonPrimitive?.content?.take(30)
                "Searching${query?.let { " for \"$it\"" } ?: ""}..."
            }
            "glob_files" -> "Finding files..."
            "run_command" -> {
                val cmd = parsedArgs?.get("command")?.jsonPrimitive?.content?.take(40)
                "Running${cmd?.let { " $it" } ?: " command"}..."
            }
            "think" -> "Thinking..."
            "attempt_completion" -> "Completing..."
            "agent" -> "Delegating to sub-agent..."
            "find_definition" -> "Finding definition..."
            "find_references" -> "Finding references..."
            "diagnostics" -> "Running diagnostics..."
            "run_inspections" -> "Running inspections..."
            else -> "Using $toolName..."
        }
    }

    /**
     * Build a JSON array of checkpoint metadata for the dashboard UI.
     * Format: [{"id":"cp-1-123","description":"After edit_file: ...","timestamp":123456}]
     */
    private fun buildCheckpointsJson(
        checkpoints: List<com.workflow.orchestrator.agent.session.CheckpointInfo>
    ): String {
        val sb = StringBuilder("[")
        checkpoints.forEachIndexed { index, cp ->
            if (index > 0) sb.append(",")
            val escapedDesc = cp.description.replace("\"", "\\\"").replace("\n", " ")
            sb.append("""{"id":"${cp.id}","description":"$escapedDesc","timestamp":${cp.createdAt}}""")
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
        phraseTimerJob?.cancel()
        phraseTimerJob = null
        contextManager = null
        currentSessionId = null
        pendingApproval?.cancel()
        pendingApproval = null
        pendingApprovalToolName = null
        userInputChannel?.close()
        userInputChannel = null
        loopWaitingForInput = false
    }
}
