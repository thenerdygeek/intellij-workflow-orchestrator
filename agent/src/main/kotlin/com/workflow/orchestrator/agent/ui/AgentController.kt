package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.tools.ArtifactRenderResult
import com.workflow.orchestrator.agent.tools.builtin.ArtifactResultRegistry
import com.workflow.orchestrator.agent.hooks.HookEvent
import com.workflow.orchestrator.agent.hooks.HookResult
import com.workflow.orchestrator.agent.hooks.HookType
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.PlanJson
import com.workflow.orchestrator.agent.loop.PlanStep
import com.workflow.orchestrator.agent.loop.SteeringMessage
import com.workflow.orchestrator.agent.loop.TaskProgress
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import com.workflow.orchestrator.agent.session.HistoryItem
import com.workflow.orchestrator.agent.session.MessageStateHandler
import com.workflow.orchestrator.agent.session.ResumeHelper
import com.workflow.orchestrator.agent.session.UiAsk
import com.workflow.orchestrator.agent.session.UiMessage
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.settings.ToolPreferences
import com.workflow.orchestrator.agent.observability.HaikuPhraseGenerator
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.agent.tools.subagent.SubagentProgressUpdate
import com.workflow.orchestrator.agent.ui.plan.AgentPlanEditor
import com.workflow.orchestrator.agent.ui.plan.AgentPlanVirtualFile
import com.workflow.orchestrator.agent.util.JsEscape
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
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
) : Disposable {
    companion object {
        private val LOG = Logger.getInstance(AgentController::class.java)

        /** Commands handled directly by the controller (not LLM skills). */
        private val BUILTIN_COMMANDS = setOf("compact")
    }

    private val service = AgentService.getInstance(project)
    private var contextManager: ContextManager? = null
    private var currentJob: Job? = null
    private var taskStartTime: Long = 0L
    /** Last task text for retry button (may include XML mention context). Gap 17. */
    private var lastTaskText: String? = null
    /** Clean display text for retry/restore (without XML). Null = same as lastTaskText. */
    private var lastDisplayText: String? = null
    /** Mentions JSON for retry display chips. */
    private var lastDisplayMentionsJson: String? = null
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
     * Thread-safe queue for mid-turn steering messages.
     * When the user sends a message while the loop is actively running (not waiting for input),
     * the message is added here instead of cancelling the current task.
     * The AgentLoop drains this at the start of each iteration.
     */
    private val steeringQueue = java.util.concurrent.ConcurrentLinkedQueue<SteeringMessage>()
    private val steeringCounter = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Pending approval deferred — the agent loop suspends on this while
     * waiting for the user to approve/deny a write tool execution.
     * Completed by the JCEF approval card callbacks.
     *
     * **Concurrency invariant:** only one approval gate is ever pending at
     * a time, because:
     *  1. Within a single orchestrator session, `AgentLoop.executeToolCalls`
     *     walks `toolCalls` sequentially — each `approvalGate.invoke(...)`
     *     call fully `await()`s before the next iteration runs.
     *  2. Subagents spawned via [SpawnAgentTool] → [SubagentRunner] construct
     *     their `AgentLoop` **without** an `approvalGate`, so parallel
     *     research subagents never touch this field.
     *
     * If this invariant is ever violated (e.g. someone wires `approvalGate =
     * ::approvalGate` into `SubagentRunner`) the reentry will be caught by
     * the guarded log warning in [approvalGate]. Before restoring that
     * wiring, convert this field to a `ConcurrentHashMap<String,
     * CompletableDeferred<ApprovalResult>>` keyed by toolCallId.
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

    /**
     * Session ID being viewed (read-only) from the history panel.
     * Set by [showSession], cleared when the user starts a new chat or resumes.
     * The user must explicitly click "Resume" to start execution.
     */
    private var viewedSessionId: String? = null

    /**
     * Structured plan data from the last plan_mode_respond call.
     * Populated in [onPlanResponse] from the LLM's structured steps and used by
     * [openPlanInEditor] to open the plan in a full JCEF editor tab.
     */
    private var currentPlanData: PlanJson? = null

    /**
     * True while the programmatic "Approve & Clear Context" / "Just Approve" question
     * is showing. Routes the question-submitted callback to [handleApprovalChoice]
     * instead of the normal [AskQuestionsTool] resolution path.
     */
    private var pendingApprovalChoice = false

    /** Recent tool calls for Haiku phrase context (FIFO, max 3). */
    private val recentToolCalls = mutableListOf<Pair<String, String>>()

    /** Coroutine job for the 30s Haiku phrase timer. */
    private var phraseTimerJob: Job? = null

    /** Current Haiku-generated session title (null until first generation). */
    private var currentHaikuTitle: String? = null

    /** Last LLM stream text snippet — gives Haiku context about what the agent is thinking. */
    @Volatile private var lastStreamSnippet: String = ""

    /** Coalesces rapid-fire stream chunks into ~16ms batched bridge dispatches. */
    private val streamBatcher = StreamBatcher(
        onFlush = { batched -> dashboard.appendStreamToken(batched) }
    )

    /** Resolves @file, @folder, @symbol, @tool, /skill, #ticket mentions into rich context for the LLM. */
    private val mentionContextBuilder = MentionContextBuilder(project)

    /** Shared provider — set once from AgentTabProvider, reused for mirrors. */
    private var sharedMentionSearchProvider: MentionSearchProvider? = null

    /** Wire the shared mention search provider so context builder can reuse cached ticket data. */
    fun setMentionSearchProvider(provider: MentionSearchProvider) {
        sharedMentionSearchProvider = provider
        mentionContextBuilder.mentionSearchProvider = provider
    }

    init {
        Disposer.register(this, streamBatcher)
        wireCallbacks()
        // Register the push callback used by RenderArtifactTool → ArtifactResultRegistry
        // to forward interactive artifacts into the webview. The tool drives the full
        // async render round-trip through the registry; this callback is the only
        // outbound hop (Kotlin → webview). The result postback comes back via the
        // _reportArtifactResult JCEF bridge registered in AgentCefPanel.
        ArtifactResultRegistry.getInstance(project).setPushCallback { payload ->
            invokeLater {
                dashboard.renderArtifact(payload.title, payload.source, payload.renderId)
            }
        }
    }

    // ═══════════════════════════════════════════════════
    //  Callback wiring — dashboard actions → controller
    // ═══════════════════════════════════════════════════

    /**
     * Wire the action / secondary / navigation / mention callbacks shared by the
     * primary dashboard panel and any "View in Editor" mirror panels.
     */
    private fun wireSharedDashboardCallbacks(panel: AgentDashboardPanel) {
        panel.setCefActionCallbacks(
            onCancel = ::cancelTask,
            onNewChat = ::newChat,
            onSendMessage = ::executeTask,
            onChangeModel = ::changeModel,
            onTogglePlanMode = ::togglePlanMode,
            onToggleRalphLoop = { /* Ralph loop not wired in lean rewrite */ },
            onActivateSkill = { skillName ->
                if (skillName in BUILTIN_COMMANDS) executeTask("/$skillName")
                else executeTask("/skill $skillName")
            },
            onRequestFocusIde = { /* No-op: focus returns to IDE naturally */ },
            onOpenSettings = ::openSettings,
            onOpenMemorySettings = ::openMemorySettings,
            onOpenToolsPanel = ::showToolsPanel
        )
        panel.setCefMentionCallbacks(::executeTaskWithMentions)
        panel.setCefCallbacks(
            onUndo = { LOG.info("Undo requested — not implemented in lean rewrite") },
            onViewTrace = { LOG.info("View trace requested — not implemented in lean rewrite") },
            onPromptSubmitted = ::executeTask
        )
        panel.setCefNavigationCallbacks(onNavigateToFile = ::navigateToFile)
        panel.onSendMessage = ::executeTask
    }

    private fun wireCallbacks() {
        // Action callbacks shared with mirror panels
        wireSharedDashboardCallbacks(dashboard)

        // Retry callback — re-executes last task with original mention context + clean display
        dashboard.setCefRetryCallback {
            lastTaskText?.let { task ->
                executeTask(task, lastDisplayText, lastDisplayMentionsJson)
            }
        }

        // Plan approval callbacks — full plan lifecycle (Priority 1)
        dashboard.setCefPlanCallbacks(
            onApprove = ::approvePlan,
            onRevise = ::revisePlan
        )

        // "View Plan" button — opens the plan in a full JCEF editor tab
        dashboard.setCefFocusPlanEditorCallback {
            openPlanInEditor()
        }

        // "Revise" button in the chat card — delegates to the open plan editor tab
        dashboard.setCefRevisePlanFromEditorCallback {
            val editors = FileEditorManager.getInstance(project).allEditors
            val planEditor = editors.filterIsInstance<AgentPlanEditor>().firstOrNull()
            planEditor?.triggerRevise()
        }

        // Tool kill callback — Gap 5
        dashboard.setCefKillCallback { toolCallId ->
            LOG.info("AgentController: kill requested for tool call $toolCallId")
            ProcessRegistry.kill(toolCallId)
        }

        // Artifact render-result callback — sandbox iframe posts render outcome back
        // to Kotlin. Decode the JSON into an ArtifactRenderResult and hand off to the
        // ArtifactResultRegistry so the suspended render_artifact tool call resumes.
        dashboard.setCefArtifactResultCallback { json ->
            parseAndDispatchArtifactResult(json)
        }

        // Interactive render round-trip — JS reports whether interactive UI
        // (questions, plans, approvals) rendered successfully. On failure, Kotlin
        // shows a fallback so the user is never stuck with an invisible widget.
        dashboard.setCefInteractiveRenderCallback { json ->
            handleInteractiveRenderResult(json)
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

        // Steering cancel callback — user clicks "Cancel" on a queued steering message
        dashboard.setCefCancelSteeringCallback { steeringId ->
            steeringQueue.removeIf { it.id == steeringId }
            dashboard.removeQueuedSteeringMessage(steeringId)
            LOG.info("AgentController: cancelled steering message $steeringId")
        }

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
            LOG.info("ask_followup_question: callback fired (question=${question.take(80)}, hasOptions=${!optionsJson.isNullOrBlank()})")
            // Drain stream batcher before UI flush so buffered tokens appear before the question
            streamBatcher.flush()
            invokeLater {
                LOG.info("ask_followup_question: invokeLater running on EDT, dispatching to dashboard")
                try {
                    // Flush any in-progress stream + finalize tool chain so the question
                    // appears AFTER prior tool calls, not mixed in
                    dashboard.flushStreamBuffer()
                    dashboard.finalizeToolChain()

                    // CRITICAL: Clear busy and unlock input FIRST — before any complex
                    // rendering that could silently fail on the JCEF/JS side.
                    // The agent is waiting for the user's answer, not processing.
                    // The JS-side showQuestions bridge also calls setBusy(false) as a
                    // safety net, but we do it here first so even if showQuestions
                    // never reaches the JS side, the UI is never stuck.
                    dashboard.setBusy(false)
                    dashboard.setInputLocked(false)

                    // Parse options if provided
                    val options = if (!optionsJson.isNullOrBlank()) {
                        try {
                            kotlinx.serialization.json.Json.decodeFromString<List<String>>(optionsJson)
                        } catch (_: Exception) { emptyList() }
                    } else emptyList()

                    if (options.isNotEmpty()) {
                        // Questions WITH options → use the QuestionView wizard UI
                        // (clickable radio buttons with descriptions, Skip/Cancel actions)
                        val wizardJson = buildString {
                            append("""{"questions":[{"id":"q1","question":""")
                            append(JsEscape.toJsonString(question))
                            append(""","type":"single","options":[""")
                            options.forEachIndexed { i, opt ->
                                if (i > 0) append(",")
                                append("""{"id":"o${i + 1}","label":""")
                                append(JsEscape.toJsonString(opt))
                                append("}")
                            }
                            append("]}]}")
                        }
                        LOG.info("ask_followup_question: showing wizard with ${options.size} options")
                        dashboard.showQuestions(wizardJson)
                    } else {
                        // Questions WITHOUT options → user types their answer freely in the chat input.
                        // The question text lives in the tool call params (not in the streamed text),
                        // so we must display it explicitly as an agent message. Use the streaming
                        // pipeline (appendStreamToken + flush) to render it as a normal agent message.
                        dashboard.appendStreamToken(question)
                        dashboard.flushStreamBuffer()
                    }
                    dashboard.focusInput()
                } catch (e: Exception) {
                    LOG.warn("ask_followup_question callback failed: ${e.message}", e)
                    // Ensure the UI is never stuck — clear busy and unlock input even on failure.
                    // Also show the question as plain text so the user at least sees it.
                    dashboard.setBusy(false)
                    dashboard.setInputLocked(false)
                    dashboard.appendStreamToken(question)
                    dashboard.flushStreamBuffer()
                    dashboard.focusInput()
                }
            }
        }
        // Wizard mode: structured multi-question UI
        com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool.showQuestionsCallback = { questionsJson ->
            invokeLater {
                // Clear busy FIRST — before the showQuestions bridge call which could
                // silently fail on the JCEF/JS side
                dashboard.setBusy(false)
                dashboard.setInputLocked(false)
                try {
                    dashboard.showQuestions(questionsJson)
                } catch (e: Exception) {
                    LOG.warn("ask_questions wizard callback failed: ${e.message}", e)
                }
                dashboard.focusInput()
            }
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
                // Convert the live question wizard into a frozen Q&A chat bubble
                // BEFORE resolving the tool, so the snapshot reflects what was answered.
                dashboard.finalizeQuestionsAsMessage()
                if (pendingApprovalChoice) {
                    // System-level approval choice — route to our handler, not AskQuestionsTool
                    handleApprovalChoice(collectedAnswers)
                } else {
                    // Normal LLM-initiated question — resolve the pending deferred.
                    // Restore busy state so the user sees the agent is processing their answer.
                    dashboard.setBusy(true)
                    val answersJson = collectedAnswers.entries.joinToString(",", "{", "}") { (qid, opts) ->
                        "\"$qid\":$opts"
                    }
                    com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool.resolveQuestions(answersJson)
                }
                collectedAnswers.clear()
            },
            onCancelled = {
                if (pendingApprovalChoice) {
                    // User cancelled the approval choice — revert to plan mode and
                    // resume the suspended loop so the user can continue discussing
                    pendingApprovalChoice = false
                    AgentService.planModeActive.set(true)
                    dashboard.setPlanMode(true)
                    collectedAnswers.clear()
                    executeTask("The user is still reviewing the plan. Continue in plan mode.")
                } else {
                    com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool.cancelQuestions()
                    collectedAnswers.clear()
                }
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

        // "View in Editor" toolbar button — opens the chat in a full editor tab
        dashboard.setOnViewInEditor(::openChatInEditorTab)

        // Tool toggle — user enables/disables a tool via the Tools panel checkbox
        dashboard.setCefToolToggleCallback { toolName, enabled ->
            LOG.info("AgentController: tool toggle — $toolName enabled=$enabled")
            ToolPreferences.getInstance(project).setToolEnabled(toolName, enabled)
        }

        // Skill dismiss — user clicks the X on the active skill banner
        dashboard.setCefSkillCallbacks(onDismiss = {
            LOG.info("AgentController: skill dismissed by user")
            contextManager?.clearActiveSkill()
        })

        // Kill sub-agent — user clicks the kill button on a running sub-agent card
        dashboard.setCefKillSubAgentCallback { agentId ->
            LOG.info("AgentController: kill sub-agent requested — $agentId")
            val spawnTool = service.registry.get("agent")
                as? com.workflow.orchestrator.agent.tools.builtin.SpawnAgentTool
            val killed = spawnTool?.cancelAgent(agentId) ?: false
            if (killed) {
                LOG.info("AgentController: subagent $agentId cancelled")
            } else {
                LOG.warn("AgentController: subagent $agentId not found in running agents")
            }
        }

        // Session history callbacks — user navigates, deletes, favorites sessions
        dashboard.setCefHistoryCallbacks(
            onShowSession = { sessionId -> showSession(sessionId) },
            onDeleteSession = { sessionId -> handleDeleteSession(sessionId) },
            onToggleFavorite = { sessionId -> handleToggleFavorite(sessionId) },
            onStartNewSession = { handleStartNewSession() },
            onBulkDeleteSessions = { json -> handleBulkDeleteSessions(json) },
            onExportSession = { sessionId -> handleExportSession(sessionId) },
            onExportAllSessions = { handleExportAllSessions() },
            onRequestHistory = { showHistory() },
            onResumeViewedSession = { resumeViewedSession() },
        )

        // Set model name from settings
        val model = AgentSettings.getInstance(project).state.sourcegraphChatModel
        if (!model.isNullOrBlank()) {
            dashboard.setModelName(model)
        }

        // Push initial memory stats so the TopBar indicator shows from first paint,
        // even before the user sends any task. (Review M2.) callJs buffers when the
        // webview hasn't loaded yet, so this will fire on first paint.
        pushMemoryStats()

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
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
                // Only send user-invocable skills to the UI for slash command / dropdown display.
                // LLM-only skills (systematic-debugging, tdd, etc.) and the auto-injected meta-skill
                // should not appear in the user's skill picker — they're triggered by the LLM.
                val uiSkills = allSkills.filter { it.userInvocable }
                // Built-in commands (not LLM skills — handled directly by the controller)
                val builtInCommands = listOf(
                    """{"name":"compact","description":"Compact conversation context to free up space"}"""
                )
                val skillEntries = uiSkills.map { skill ->
                    val name = skill.name.replace("\"", "\\\"")
                    val desc = skill.description.replace("\"", "\\\"").take(200)
                    """{"name":"$name","description":"$desc"}"""
                }
                val skillsJson = (builtInCommands + skillEntries).joinToString(",", "[", "]")
                invokeLater {
                    dashboard.updateSkillsList(skillsJson)
                }
                LOG.info("AgentController: pushed ${uiSkills.size} user-invocable skills to chat UI (${allSkills.size} total)")
            }
        } catch (e: Exception) {
            LOG.debug("AgentController: failed to load skills list: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════
    //  /compact — manual context compaction
    // ═══════════════════════════════════════════════════

    /**
     * Handle /compact slash command — directly compact the conversation context
     * without an LLM round-trip. Shows utilization stats before/after.
     *
     * Safety: blocks when the agent loop is actively running to avoid concurrent
     * mutation of ContextManager (which is not thread-safe).
     */
    private fun handleCompactCommand() {
        // Show /compact as a user message for conversation continuity
        invokeLater { dashboard.appendUserMessage("/compact") }

        val cm = contextManager
        if (cm == null) {
            invokeLater { dashboard.appendStatus("No active session to compact.", RichStreamingPanel.StatusType.WARNING) }
            return
        }

        // Block when loop is actively running — ContextManager is not thread-safe
        if (currentJob?.isActive == true) {
            invokeLater {
                dashboard.appendStatus(
                    "Cannot compact while agent is running. Wait for the current turn to complete or cancel first.",
                    RichStreamingPanel.StatusType.WARNING
                )
            }
            return
        }

        val utilBefore = cm.utilizationPercent()
        if (utilBefore <= 70.0) {
            invokeLater {
                dashboard.appendStatus(
                    "Context at ${"%.0f".format(utilBefore)}% — compaction not needed (threshold: 70%).",
                    RichStreamingPanel.StatusType.INFO
                )
            }
            return
        }

        invokeLater { dashboard.appendStatus("Compacting context...", RichStreamingPanel.StatusType.INFO) }

        // Assign to currentJob so dispose/newChat can cancel if needed.
        // Safe: we verified no active job above.
        currentJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val result = service.compactContext(cm)
                if (result == null) {
                    invokeLater {
                        dashboard.appendStatus(
                            "Context at ${"%.0f".format(utilBefore)}% — below compaction threshold.",
                            RichStreamingPanel.StatusType.INFO
                        )
                    }
                    return@launch
                }
                val (tokensBefore, tokensAfter) = result
                val utilAfter = cm.utilizationPercent()
                val saved = tokensBefore - tokensAfter
                invokeLater {
                    dashboard.appendStatus(
                        "Compacted: ${"%.0f".format(utilBefore)}% → ${"%.0f".format(utilAfter)}% " +
                            "($tokensBefore → $tokensAfter tokens, saved $saved)",
                        RichStreamingPanel.StatusType.INFO
                    )
                }
            } catch (e: Exception) {
                LOG.warn("Manual compaction failed", e)
                invokeLater {
                    dashboard.appendError("Compaction failed: ${e.message}")
                }
            } finally {
                currentJob = null
            }
        }
    }

    //  Core: executeTask — send user message to agent loop
    // ═══════════════════════════════════════════════════

    /** Display user message in chat UI, with mention chips if available. */
    private fun displayUserMessage(text: String, mentionsJson: String?) {
        if (mentionsJson != null) {
            dashboard.appendUserMessageWithMentions(text, mentionsJson)
        } else {
            dashboard.appendUserMessage(text)
        }
    }

    /**
     * Execute a user task with resolved mention context.
     * Called from the JCEF bridge when the user sends a message containing @file, @folder,
     * @symbol, @tool, /skill, or #ticket mentions.
     *
     * Resolves mentions into rich context (file contents, ticket details, etc.) on a background
     * thread, then prepends the context to the task and delegates to [executeTask].
     */
    private fun executeTaskWithMentions(text: String, mentionsJson: String) {
        if (text.isBlank() && mentionsJson == "[]") return

        val mentions = try {
            val arr = Json.parseToJsonElement(mentionsJson).jsonArray
            arr.map { elem ->
                val obj = elem.jsonObject
                MentionContextBuilder.Mention(
                    type = obj["type"]?.jsonPrimitive?.content ?: "",
                    name = obj["label"]?.jsonPrimitive?.content ?: "",
                    value = obj["path"]?.jsonPrimitive?.content ?: ""
                )
            }
        } catch (e: Exception) {
            LOG.warn("AgentController: failed to parse mentions JSON, falling back to plain text", e)
            executeTask(text)
            return
        }

        if (mentions.isEmpty()) {
            executeTask(text)
            return
        }

        // Resolve mention context on IO thread (may hit Jira API, read files),
        // then execute on EDT.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            val context = try {
                mentionContextBuilder.buildContext(mentions)
            } catch (e: Exception) {
                LOG.warn("AgentController: mention context build failed, sending without context", e)
                null
            }

            val taskWithContext = if (context != null) {
                "$text\n\n<mention_context>\n$context</mention_context>"
            } else {
                text
            }

            invokeLater {
                // Display clean text with chips in UI; pass XML-enriched text to LLM
                executeTask(taskWithContext, displayText = text, displayMentionsJson = mentionsJson)
            }
        }
    }

    /**
     * Execute a user task. Called from the chat input, redirect, or example prompts.
     * Safe to call multiple times for multi-turn conversation.
     *
     * If a loop is currently waiting for user input (plan mode), feeds the message
     * into the existing loop's channel instead of starting a new one. This matches
     * Cline's continuous conversation model where plan mode is a dialogue.
     *
     * @param displayText Clean text for UI display (without XML context). Null = use task.
     * @param displayMentionsJson JSON array of mentions for chip rendering. Null = no chips.
     */
    fun executeTask(task: String, displayText: String? = null, displayMentionsJson: String? = null) {
        if (task.isBlank()) return

        // Intercept /compact — direct context compaction without LLM round-trip
        if (task.trim().equals("/compact", ignoreCase = true)) {
            handleCompactCommand()
            return
        }

        LOG.info("AgentController.executeTask: ${task.take(80)}")

        // The text shown in the UI — clean text without mention XML
        val uiText = displayText ?: task

        // Gap 15+17: Track last task for retry and session title
        lastTaskText = task
        lastDisplayText = displayText
        lastDisplayMentionsJson = displayMentionsJson

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
            displayUserMessage(uiText, displayMentionsJson)
            dashboard.setBusy(true)
            pending.complete(task)
            return
        }

        // If the loop is waiting for user input (plan mode dialogue), feed into it
        val channel = userInputChannel
        if (loopWaitingForInput && channel != null && currentJob?.isActive == true) {
            LOG.info("AgentController: feeding user message into existing loop via channel")
            displayUserMessage(uiText, displayMentionsJson)
            dashboard.setBusy(true)
            // Input is NOT locked — user can always type freely (Cline behavior)
            loopWaitingForInput = false
            runBlocking { channel.send(task) }
            return
        }

        // If the loop is actively running (not waiting), queue as a steering message.
        // Ported from Claude Code's mid-turn steering: the message is injected into the
        // conversation context at the start of the next loop iteration, so the LLM sees
        // it before its next response. This avoids cancelling the current task.
        if (currentJob?.isActive == true && !loopWaitingForInput) {
            val steeringId = "steer-${System.currentTimeMillis()}-${steeringCounter.incrementAndGet()}"
            LOG.info("AgentController: queuing steering message: ${task.take(80)}")
            steeringQueue.offer(SteeringMessage(id = steeringId, text = task))
            dashboard.addQueuedSteeringMessage(steeringId, uiText)
            return
        }

        // If viewing a previous session, resume it with the user's message
        if (viewedSessionId != null) {
            LOG.info("AgentController: user typed while viewing session — resuming with message")
            resumeViewedSession(task)
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
        displayUserMessage(uiText, displayMentionsJson)
        dashboard.setBusy(true)
        // Input is NOT locked — user can always type (Cline behavior)
        taskStartTime = System.currentTimeMillis()

        // Push current memory stats to the TopBar indicator at task start.
        pushMemoryStats()

        // Create context manager on first message, reuse on subsequent turns
        val isFirstMessage = contextManager == null
        if (isFirstMessage) {
            val settings = AgentSettings.getInstance(project)
            contextManager = ContextManager(maxInputTokens = settings.state.maxInputTokens)
            if (displayMentionsJson != null) {
                dashboard.startSessionWithMentions(uiText, displayMentionsJson)
            } else {
                dashboard.startSession(uiText)
            }
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
            sessionId = currentSessionId,
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
            onRetry = { attempt, maxAttempts, reason, delayMs ->
                invokeLater {
                    val delaySec = delayMs / 1000
                    dashboard.appendStatus(
                        "$reason — retrying ($attempt/$maxAttempts) in ${delaySec}s...",
                        RichStreamingPanel.StatusType.WARNING
                    )
                }
            },
            onModelSwitch = { _, to, reason ->
                invokeLater {
                    val cached = com.workflow.orchestrator.core.ai.ModelCache.getCached()
                    val displayName = cached.find { it.id == to }?.displayName
                        ?: com.workflow.orchestrator.core.ai.dto.ModelInfo.formatModelName(to.substringAfterLast("::"))
                    dashboard.setModelName(displayName)
                    // Subtle in-chip indicator instead of a noisy chat status line.
                    // - "Network error — falling back": fallback ON, tooltip explains why
                    // - "Escalating back": optimistically clear (silent recovery — Option X)
                    // - "Escalation failed — reverting": fallback ON again with the failure reason
                    when {
                        reason.startsWith("Escalating back", ignoreCase = true) ->
                            dashboard.setModelFallbackState(false, null)
                        else ->
                            dashboard.setModelFallbackState(true, "$reason — now using $displayName")
                    }
                }
            },
            onPlanResponse = { text, explore, steps -> onPlanResponse(text, explore, steps) },
            onPlanModeToggled = { enabled -> invokeLater { togglePlanMode(enabled) } },
            userInputChannel = userInputChannel,
            approvalGate = ::approvalGate,
            onCheckpointSaved = ::onCheckpointSaved,
            onSubagentProgress = ::onSubagentProgress,
            onTokenUpdate = ::onTokenUpdate,
            onDebugLog = if (debugEnabled) { level, event, detail, meta ->
                dashboard.pushDebugLogEntry(level, event, detail, meta)
            } else null,
            onSessionStarted = { sid -> currentSessionId = sid },
            steeringQueue = steeringQueue,
            onSteeringDrained = { drainedIds ->
                invokeLater {
                    dashboard.promoteQueuedSteeringMessages(drainedIds)
                }
            }
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
        streamBatcher.append(chunk)
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
        // Defensive reentry guard — see the invariant described on [pendingApproval].
        // If a second approvalGate call arrives while the first is still waiting,
        // cancel the previous deferred so the old await() throws instead of hanging
        // forever (a race we would otherwise have no way to detect). This should
        // never fire under the current architecture; a warning here is the signal
        // that someone plumbed the approval gate into a parallel worker.
        val stale = pendingApproval
        if (stale != null && !stale.isCompleted) {
            LOG.warn(
                "AgentController: approvalGate re-entered while a prior approval " +
                    "(tool='${pendingApprovalToolName}') was still pending — " +
                    "cancelling the stale deferred. New tool='$toolName'. " +
                    "This indicates a concurrency bug — see pendingApproval docs."
            )
            stale.cancel(CancellationException("approvalGate re-entered"))
        }
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
            // Only clear the slot if we still own it — if a reentrant caller
            // replaced our deferred while we were suspended, leave its entry alone.
            if (pendingApproval === deferred) {
                pendingApproval = null
                pendingApprovalToolName = null
            }
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
                    // SpawnAgentTool emits "running" exactly once per child, with the
                    // human-readable label set on the same update. The webview dedupes
                    // on agentId, so this call materialises one card per real run.
                    val label = update.label ?: update.latestToolCall ?: "Starting..."
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
                    // Tool starting — add a RUNNING tool chip to the subagent's chain.
                    // The toolCallId from [ToolCallProgress] is threaded through so the
                    // webview can key parallel tool calls by exact ID instead of relying
                    // on a first-RUNNING-by-name lookup (which would swap results for
                    // parallel calls to the same tool — e.g. concurrent read_files).
                    update.toolStartName?.let { name ->
                        dashboard.addSubAgentToolCall(
                            agentId,
                            update.toolCallId,
                            name,
                            update.toolStartArgs ?: ""
                        )
                    }
                    // Tool completing — flip the matching RUNNING chip to COMPLETED/ERROR.
                    update.toolCompleteName?.let { name ->
                        dashboard.updateSubAgentToolCall(
                            agentId,
                            update.toolCallId,
                            name,
                            update.toolCompleteResult ?: "",
                            update.toolCompleteDurationMs,
                            update.toolCompleteIsError
                        )
                    }
                    update.stats?.let { stats ->
                        dashboard.updateSubAgentIteration(agentId, stats.toolCalls)
                    }
                }
            }
        }
    }

    /**
     * Push current agent memory stats (core memory chars + archival entry count) to
     * the TopBar indicator. Best-effort — logs and continues on failure. Safe to call
     * from any thread (switches to EDT internally via invokeLater).
     */
    private fun pushMemoryStats() {
        try {
            val stats = service.getMemoryStats() ?: return
            invokeLater { dashboard.updateMemoryStats(stats.first, stats.second) }
        } catch (e: Exception) {
            LOG.warn("[AgentController] Failed to push memory stats (non-fatal)", e)
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
     * The LLM's task_progress checklist (focus-chain) is the sole source of truth
     * for execution progress. LLM-provided steps drive both the plan card and progress.
     *
     * On each update, we replace the plan card's steps entirely with the LLM's
     * checklist items. This handles the LLM adding, removing, or reordering items.
     */
    private fun onTaskProgress(progress: TaskProgress) {
        invokeLater {
            val summary = "${progress.completedCount}/${progress.totalCount} steps completed"
            dashboard.appendStatus(summary, RichStreamingPanel.StatusType.INFO)

            // Build execution steps directly from the LLM's task_progress checklist.
            // The first incomplete item is "running"; completed items are "completed"; rest "pending".
            var foundFirstIncomplete = false
            val steps = progress.items.mapIndexed { index, item ->
                val status = when {
                    item.completed -> "completed"
                    !foundFirstIncomplete -> { foundFirstIncomplete = true; "running" }
                    else -> "pending"
                }
                PlanStep(
                    id = (index + 1).toString(),
                    title = item.description,
                    status = status
                )
            }
            val stepsJson = Json.encodeToString(steps)
            dashboard.replaceExecutionSteps(stepsJson)
        }
    }

    /** Tool names that render through dedicated UI paths, not generic tool cards. */
    private val COMMUNICATION_TOOLS = setOf(
        "plan_mode_respond",   // Rendered by onPlanResponse callback → PlanCard
        "ask_followup_question", // Rendered by showSimpleQuestionCallback → text or QuestionView
        "ask_questions",       // Rendered by showQuestionsCallback → QuestionView wizard
        "attempt_completion",  // Rendered by onComplete callback → CompletionCard
    )

    private fun onToolCall(progress: ToolCallProgress) {
        // ── Communication tools: render as text, not tool cards ──
        // These tools have dedicated UI rendering paths (callbacks, cards, wizards).
        // Showing them as generic tool call cards would duplicate or conflict with their real UI.

        // Skip entirely: these are fully handled by other callbacks
        if (progress.toolName in setOf("plan_mode_respond", "ask_followup_question", "ask_questions", "attempt_completion")) return

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
                    output = progress.output ?: progress.result.takeIf { it.isNotBlank() },
                    diff = progress.editDiff,
                    toolCallId = progress.toolCallId
                )

                // Show skill banner when use_skill activates a skill
                if (progress.toolName == "use_skill" && !progress.isError) {
                    val skillName = progress.result.substringAfter("'").substringBefore("'")
                    if (skillName.isNotBlank()) {
                        dashboard.showSkillBanner(skillName)
                    }
                }

                // Push diff explanation directly to chat UI when generate_explanation returns a diff.
                // This renders the diff immediately as a DiffHtml component so the LLM does not
                // need to re-output it in markdown, saving tokens.
                if (progress.toolName == "generate_explanation" && !progress.isError && progress.editDiff != null) {
                    val title = try {
                        kotlinx.serialization.json.Json.parseToJsonElement(progress.args)
                            .jsonObject["title"]?.jsonPrimitive?.content ?: "Diff"
                    } catch (_: Exception) { "Diff" }
                    dashboard.appendDiffExplanation(title, progress.editDiff)
                }
            }
        }
    }

    private fun onComplete(result: LoopResult) {
        phraseTimerJob?.cancel()
        phraseTimerJob = null

        val durationMs = System.currentTimeMillis() - taskStartTime

        // Drain the stream batcher before UI flush so no buffered tokens are lost
        streamBatcher.flush()

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
                    lastTaskText?.let { dashboard.showRetryButton(lastDisplayText ?: it) }
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
            // Clear any orphaned steering messages that were queued after the last drain
            steeringQueue.clear()

            // Refresh memory stats in TopBar after task completion.
            // Note: auto-memory extraction runs asynchronously in AgentService after
            // completion, so this reflects pre-extraction state. A subsequent task
            // start will pick up any newly-written entries.
            pushMemoryStats()
        }
    }

    // ═══════════════════════════════════════════════════
    //  User actions
    // ═══════════════════════════════════════════════════

    fun cancelTask() {
        LOG.info("AgentController.cancelTask")
        service.cancelCurrentTask()
        // Immediately reset controller state so the next user message starts a fresh loop.
        // Don't wait for onComplete — it fires async and there's a race if the user
        // types a message right after stopping (the message would hit the steering queue
        // or channel path with stale state instead of starting a new loop).
        clearActiveLoopState()
        invokeLater {
            dashboard.setBusy(false)
            dashboard.focusInput()
        }
    }

    /**
     * Clear all transient state associated with the active agent loop iteration.
     * Shared by [cancelTask], [newChat], and [dispose] to keep them in sync.
     */
    private fun clearActiveLoopState() {
        currentJob = null
        pendingApprovalChoice = false
        userInputChannel?.close()
        userInputChannel = null
        loopWaitingForInput = false
        steeringQueue.clear()
        streamBatcher.clear()
    }

    fun newChat() {
        LOG.info("AgentController.newChat")
        resetForNewChat()
    }

    /**
     * Reset all controller and dashboard state for a fresh session.
     * Does NOT show history — callers decide the next view.
     */
    private fun resetForNewChat() {
        // Reset all service-level state (plan mode, tools, processes, active task)
        service.resetForNewChat()

        // Reset controller state
        clearActiveLoopState()
        phraseTimerJob?.cancel()
        phraseTimerJob = null
        recentToolCalls.clear()
        currentHaikuTitle = null
        lastStreamSnippet = ""
        contextManager?.clearActivePlanPath()
        contextManager = null
        taskStartTime = 0L
        lastTaskText = null
        lastDisplayText = null
        lastDisplayMentionsJson = null
        currentSessionId = null
        currentPlanData = null
        pendingApproval?.cancel()
        pendingApproval = null
        pendingApprovalToolName = null
        viewedSessionId = null

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

    // ═══════════════════════════════════════════════════
    //  Session history — list, delete, favorite, navigate
    // ═══════════════════════════════════════════════════

    private val historyJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * Load the global session index and push it to the webview as the history list.
     * File I/O runs on Dispatchers.IO to avoid blocking the CEF thread.
     */
    fun showHistory() {
        val basePath = project.basePath ?: return
        val baseDir = ProjectIdentifier.agentDir(basePath)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val items = MessageStateHandler.loadGlobalIndex(baseDir)
            val json = historyJson.encodeToString(items)
            invokeLater { dashboard.loadSessionHistory(json) }
        }
    }

    /**
     * Delete a session from disk and refresh the history list.
     */
    fun handleDeleteSession(sessionId: String) {
        val basePath = project.basePath ?: return
        val baseDir = ProjectIdentifier.agentDir(basePath)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            MessageStateHandler.deleteSession(baseDir, sessionId)
            val items = MessageStateHandler.loadGlobalIndex(baseDir)
            val json = historyJson.encodeToString(items)
            invokeLater { dashboard.loadSessionHistory(json) }
        }
    }

    /**
     * Toggle the favorite flag on a session and refresh the history list.
     */
    fun handleToggleFavorite(sessionId: String) {
        val basePath = project.basePath ?: return
        val baseDir = ProjectIdentifier.agentDir(basePath)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            MessageStateHandler.toggleFavorite(baseDir, sessionId)
            val items = MessageStateHandler.loadGlobalIndex(baseDir)
            val json = historyJson.encodeToString(items)
            invokeLater { dashboard.loadSessionHistory(json) }
        }
    }

    /**
     * Start a fresh session from the history view.
     * Resets state without showing history (avoids history→chat flicker).
     */
    fun handleStartNewSession() {
        resetForNewChat()
        dashboard.showChatView()
    }

    /**
     * Bulk-delete multiple sessions from disk and refresh the history list.
     * @param sessionIdsJson JSON array of session ID strings.
     */
    fun handleBulkDeleteSessions(sessionIdsJson: String) {
        val basePath = project.basePath ?: return
        val baseDir = ProjectIdentifier.agentDir(basePath)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val ids = historyJson.decodeFromString<List<String>>(sessionIdsJson)
                for (id in ids) {
                    MessageStateHandler.deleteSession(baseDir, id)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to parse bulk delete session IDs", e)
            }
            val items = MessageStateHandler.loadGlobalIndex(baseDir)
            val json = historyJson.encodeToString(items)
            invokeLater { dashboard.loadSessionHistory(json) }
        }
    }

    /**
     * Export a single session as markdown and copy to clipboard.
     */
    fun handleExportSession(sessionId: String) {
        val basePath = project.basePath ?: return
        val baseDir = ProjectIdentifier.agentDir(basePath)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val markdown = formatSessionAsMarkdown(baseDir, sessionId)
            if (markdown != null) {
                com.workflow.orchestrator.core.ui.ClipboardUtil.copyToClipboard(markdown)
                invokeLater {
                    dashboard.showToast("Session exported to clipboard", "SUCCESS", 3000)
                }
            }
        }
    }

    /**
     * Export all sessions as markdown and copy to clipboard.
     */
    fun handleExportAllSessions() {
        val basePath = project.basePath ?: return
        val baseDir = ProjectIdentifier.agentDir(basePath)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val items = MessageStateHandler.loadGlobalIndex(baseDir)
            if (items.isEmpty()) return@launch
            val parts = items.mapNotNull { item ->
                formatSessionAsMarkdown(baseDir, item.id)
            }
            if (parts.isNotEmpty()) {
                val combined = parts.joinToString("\n\n---\n\n")
                com.workflow.orchestrator.core.ui.ClipboardUtil.copyToClipboard(combined)
                invokeLater {
                    dashboard.showToast("${parts.size} sessions exported to clipboard", "SUCCESS", 3000)
                }
            }
        }
    }

    /**
     * Format a session's UI messages as a markdown string.
     * Returns null if the session has no messages.
     *
     * The first SAY.TEXT message is the user's task (stored at session start).
     * Subsequent SAY.TEXT messages are agent responses.
     */
    private fun formatSessionAsMarkdown(baseDir: java.io.File, sessionId: String): String? {
        val sessionDir = java.io.File(baseDir, "sessions/$sessionId")
        val messages = MessageStateHandler.loadUiMessages(sessionDir)
        if (messages.isEmpty()) return null

        // Find the session task from the global index
        val items = MessageStateHandler.loadGlobalIndex(baseDir)
        val task = items.find { it.id == sessionId }?.task ?: "Untitled Session"

        val sb = StringBuilder()
        sb.appendLine("# $task")
        sb.appendLine()

        var firstSayText = true
        for (msg in messages) {
            val text = msg.text?.takeIf { it.isNotBlank() } ?: continue
            when (msg.type) {
                com.workflow.orchestrator.agent.session.UiMessageType.SAY -> {
                    when (msg.say) {
                        com.workflow.orchestrator.agent.session.UiSay.TEXT -> {
                            if (firstSayText) {
                                // First SAY.TEXT is the user's original task
                                sb.appendLine("**User:** $text")
                                sb.appendLine()
                                firstSayText = false
                            } else {
                                sb.appendLine("**Agent:** $text")
                                sb.appendLine()
                            }
                        }
                        else -> {} // skip tool calls, status, etc.
                    }
                }
                com.workflow.orchestrator.agent.session.UiMessageType.ASK -> {
                    when (msg.ask) {
                        com.workflow.orchestrator.agent.session.UiAsk.FOLLOWUP -> {
                            sb.appendLine("**User:** $text")
                            sb.appendLine()
                        }
                        com.workflow.orchestrator.agent.session.UiAsk.COMPLETION_RESULT -> {
                            sb.appendLine("**Agent (completion):** $text")
                            sb.appendLine()
                        }
                        else -> {}
                    }
                }
            }
        }

        return sb.toString().trimEnd()
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
     * We replicate this: load session + messages from MessageStateHandler,
     * rebuild ContextManager, and re-enter the agent loop.
     */
    /**
     * Show a previous session read-only (view conversation without starting execution).
     *
     * Loads the UI messages and pushes them to the webview so the user can review
     * the conversation. If the session is not completed, a "Resume" bar is shown
     * at the bottom so the user can explicitly choose to continue execution.
     */
    fun showSession(sessionId: String) {
        LOG.info("AgentController.showSession: $sessionId (view-only)")

        // Cancel any running task first
        if (currentJob?.isActive == true) {
            service.cancelCurrentTask()
        }
        currentJob = null
        viewedSessionId = sessionId

        val basePath = project.basePath ?: System.getProperty("user.home")
        val sessionDir = java.io.File(ProjectIdentifier.agentDir(basePath), "sessions/$sessionId")
        if (!sessionDir.exists()) {
            LOG.warn("AgentController.showSession: session dir not found for $sessionId")
            return
        }

        // Load UI messages from disk
        val savedUiMessages = MessageStateHandler.loadUiMessages(sessionDir)
        if (savedUiMessages.isEmpty()) {
            LOG.warn("AgentController.showSession: no ui messages for $sessionId")
            return
        }

        // Trim trailing resume markers
        val trimmed = ResumeHelper.trimResumeMessages(savedUiMessages)

        // Determine if this session was already completed
        val isCompleted = ResumeHelper.determineResumeAskType(trimmed) == UiAsk.RESUME_COMPLETED_TASK

        // Push messages to webview (switches to chat view and shows conversation)
        dashboard.reset()
        postStateToWebview(trimmed)
        dashboard.showChatView()
        dashboard.setBusy(false)
        dashboard.setInputLocked(false)

        // Show the resume bar if the session is resumable (not completed)
        if (!isCompleted) {
            dashboard.showResumeBar(sessionId)
        }
    }

    /**
     * Resume a session that the user is currently viewing. Called when the user
     * clicks "Resume" in the resume bar, optionally with a message to add.
     */
    fun resumeViewedSession(userText: String? = null) {
        val sessionId = viewedSessionId
        if (sessionId == null) {
            LOG.warn("AgentController.resumeViewedSession: no viewed session to resume")
            return
        }
        viewedSessionId = null
        dashboard.hideResumeBar()
        resumeSession(sessionId, userText)
    }

    fun resumeSession(sessionId: String, userText: String? = null) {
        LOG.info("AgentController.resumeSession: $sessionId")
        viewedSessionId = null
        prepareForReplay("Resuming session...")

        // Create a fresh input channel for the resumed loop
        userInputChannel = Channel(Channel.RENDEZVOUS)
        taskStartTime = System.currentTimeMillis()

        val debugEnabled = AgentSettings.getInstance(project).state.showDebugLog

        // Attempt resume — AgentService rebuilds the ContextManager from JSONL checkpoint.
        // Pass ALL interactive callbacks so the resumed session has full functionality
        // (approvals, plans, checkpoints, steering, token display, etc.).
        val job = service.resumeSession(
            sessionId = sessionId,
            userText = userText,
            onStreamChunk = ::onStreamChunk,
            onToolCall = ::onToolCall,
            onTaskProgress = ::onTaskProgress,
            onComplete = { result ->
                if (debugEnabled) {
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
            onUiMessagesLoaded = { uiMessages -> postStateToWebview(uiMessages) },
            onRetry = { attempt, maxAttempts, reason, delayMs ->
                invokeLater {
                    val delaySec = delayMs / 1000
                    dashboard.appendStatus(
                        "$reason — retrying ($attempt/$maxAttempts) in ${delaySec}s...",
                        RichStreamingPanel.StatusType.WARNING
                    )
                }
            },
            onModelSwitch = { _, to, reason ->
                invokeLater {
                    val cached = com.workflow.orchestrator.core.ai.ModelCache.getCached()
                    val displayName = cached.find { it.id == to }?.displayName
                        ?: com.workflow.orchestrator.core.ai.dto.ModelInfo.formatModelName(to.substringAfterLast("::"))
                    dashboard.setModelName(displayName)
                    when {
                        reason.startsWith("Escalating back", ignoreCase = true) ->
                            dashboard.setModelFallbackState(false, null)
                        else ->
                            dashboard.setModelFallbackState(true, "$reason — now using $displayName")
                    }
                }
            },
            onPlanResponse = { text, explore, steps -> onPlanResponse(text, explore, steps) },
            onPlanModeToggled = { enabled -> invokeLater { togglePlanMode(enabled) } },
            userInputChannel = userInputChannel,
            approvalGate = ::approvalGate,
            onCheckpointSaved = ::onCheckpointSaved,
            onSubagentProgress = ::onSubagentProgress,
            onTokenUpdate = ::onTokenUpdate,
            onDebugLog = if (debugEnabled) { level, event, detail, meta ->
                dashboard.pushDebugLogEntry(level, event, detail, meta)
            } else null,
            onSessionStarted = { sid -> currentSessionId = sid },
            steeringQueue = steeringQueue,
            onSteeringDrained = { drainedIds ->
                invokeLater {
                    dashboard.promoteQueuedSteeringMessages(drainedIds)
                }
            },
        )

        if (job != null) {
            currentJob = job
            // Reset contextManager — the resumed session creates its own
            contextManager = null
            // Push memory stats for resumed session
            pushMemoryStats()
            // Start working indicator
            startPhraseTimer("Resumed session")
        } else {
            // Resume failed — notify user
            failReplay("Could not resume session. The session may have been deleted or has no saved messages.")
        }
    }

    /**
     * Cancel the current task and reset the dashboard UI before replaying a saved
     * session (resume / checkpoint revert). Shows the supplied status message.
     */
    private fun prepareForReplay(statusMessage: String) {
        if (currentJob?.isActive == true) {
            service.cancelCurrentTask()
        }
        currentJob = null
        dashboard.reset()
        dashboard.setBusy(true)
        dashboard.setInputLocked(true)
        taskStartTime = System.currentTimeMillis()
        dashboard.appendStatus(statusMessage, RichStreamingPanel.StatusType.INFO)
    }

    /** Show an error and unlock the input bar after a failed replay attempt. */
    private fun failReplay(message: String) {
        dashboard.appendError(message)
        dashboard.setBusy(false)
        dashboard.setInputLocked(false)
        dashboard.focusInput()
    }

    /**
     * Serialize the full UI messages array and push it to the webview for session rehydration.
     *
     * Called during session resume so the chat UI displays the complete conversation history
     * from the previous session. The bridge function `_loadSessionState` (registered in
     * jcef-bridge.ts, Task 9) replaces the chatStore messages with the deserialized array.
     */
    fun postStateToWebview(uiMessages: List<UiMessage>) {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val messagesJson = json.encodeToString(uiMessages)
        dashboard.loadSessionState(messagesJson)
    }

    /**
     * Revert to a specific checkpoint in a session.
     *
     * Ported from Cline's checkpoint reversion:
     * restores the conversation to the checkpoint state and continues.
     */
    fun revertToCheckpoint(sessionId: String, checkpointId: String) {
        LOG.info("AgentController.revertToCheckpoint: session=$sessionId, checkpoint=$checkpointId")
        prepareForReplay("Reverting to checkpoint...")

        // Restore the last task text in the input bar so user can edit and re-send
        (lastDisplayText ?: lastTaskText)?.let { dashboard.restoreInputText(it) }

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
            failReplay("Could not revert to checkpoint. The checkpoint may have been deleted.")
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
    private fun onPlanResponse(planText: String, needsMoreExploration: Boolean, planSteps: List<String>) {
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
            // Build plan from LLM-provided steps (not parsed from markdown).
            // The LLM provides structured step titles via the mandatory "steps" parameter.
            val steps = planSteps.mapIndexed { index, title ->
                PlanStep(id = (index + 1).toString(), title = title)
            }
            val summary = planText.lines()
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?.trim()?.take(300)
                ?: "Plan with ${steps.size} steps"
            val planData = PlanJson(summary = summary, steps = steps, markdown = planText)
            currentPlanData = planData
            val planJson = Json.encodeToString(planData)
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
     * User clicked approve — show a programmatic question asking whether to clear
     * the research context before execution. The actual mode switch and approval
     * instruction happen in [handleApprovalChoice] after the user picks an option.
     */
    private fun approvePlan() {
        LOG.info("AgentController.approvePlan — showing context clearing choice")
        pendingApprovalChoice = true

        val questionsJson = """
        {
            "questions": [{
                "id": "approval_mode",
                "question": "How would you like to proceed?",
                "type": "single",
                "options": [
                    {
                        "id": "clear_context",
                        "label": "Approve & Clear Context (recommended)",
                        "description": "Clears research history to free context budget. Keeps plan, active skill, facts, and guardrails."
                    },
                    {
                        "id": "keep_context",
                        "label": "Just Approve",
                        "description": "Keeps the full conversation context from the planning phase."
                    }
                ]
            }]
        }
        """.trimIndent()

        invokeLater { dashboard.showQuestions(questionsJson) }
    }

    /**
     * Handle the user's choice from the programmatic approval question.
     * Clears context if requested, then switches to act mode and feeds the
     * approval instruction into the waiting loop.
     */
    private fun handleApprovalChoice(answers: Map<String, String>) {
        pendingApprovalChoice = false

        // Parse the selected option — answers map: questionId → JSON array of selected option IDs
        val selectedJson = answers["approval_mode"] ?: "[\"keep_context\"]"
        val clearContext = selectedJson.contains("clear_context")

        LOG.info("AgentController.handleApprovalChoice — clearContext=$clearContext")

        if (clearContext) {
            contextManager?.clearMessages()
        }

        // Switch to act mode
        AgentService.planModeActive.set(false)
        dashboard.setPlanMode(false)

        // Mark the plan as approved in the UI — switches PlanSummaryCard → PlanProgressWidget
        dashboard.approvePlanInUi()

        // Build the approval instruction.
        // When context was cleared, the plan content is no longer in the conversation.
        // Include it inline so the LLM can proceed without needing read_file on the
        // external plan path (which PathValidator blocks as outside the project).
        val stepsChecklist = currentPlanData?.steps?.joinToString("\n") { step ->
            "- [ ] ${step.title}"
        } ?: ""

        val instruction = buildString {
            appendLine("The user has approved the plan.")
            if (clearContext) {
                val planMarkdown = currentPlanData?.markdown
                if (!planMarkdown.isNullOrBlank()) {
                    appendLine()
                    appendLine("<approved_plan>")
                    appendLine(planMarkdown)
                    appendLine("</approved_plan>")
                }
            }
            if (stepsChecklist.isNotBlank()) {
                appendLine()
                appendLine("Task checklist:")
                appendLine(stepsChecklist)
            }
        }.trim()

        executeTask(instruction)
    }

    /**
     * User submitted per-line comments on the plan — format and inject into the loop.
     *
     * The loop stays in plan mode. The revision message goes through the
     * [userInputChannel] directly (NOT through [executeTask]) so it does NOT
     * appear as a user-typed message in the chat. A small status indicator
     * is shown instead.
     *
     * @param commentsJson v2 JSON: `{"comments":[{line,content,comment}],"markdown":"..."}`
     */
    private fun revisePlan(commentsJson: String) {
        LOG.info("AgentController.revisePlan: $commentsJson")

        val revisionMessage = buildRevisionMessage(commentsJson)
        val commentCount = countRevisionComments(commentsJson)

        // Show a status indicator instead of a fake "user" message
        dashboard.appendStatus("Plan revision requested with $commentCount comment(s)")
        dashboard.setBusy(true)

        // Inject directly into the channel — bypass executeTask to avoid
        // displaying the generated prompt as a user message (Bug 1 fix)
        val channel = userInputChannel
        if (loopWaitingForInput && channel != null && currentJob?.isActive == true) {
            loopWaitingForInput = false
            runBlocking { channel.send(revisionMessage) }
        } else if (currentJob?.isActive == true) {
            // Loop is running but not waiting — queue as steering
            val steeringId = "steer-revise-${System.currentTimeMillis()}"
            steeringQueue.offer(SteeringMessage(id = steeringId, text = revisionMessage))
        } else {
            LOG.warn("AgentController.revisePlan: no active loop to receive revision")
            dashboard.setBusy(false)
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

    /**
     * Route the TopBar memory indicator click directly to the Memory sub-page,
     * skipping the top-level "Workflow Orchestrator" page. Fixes first-time UX
     * where IntelliJ would otherwise show the General page by default. (Review M1.)
     */
    private fun openMemorySettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            com.workflow.orchestrator.agent.settings.AgentMemoryConfigurable::class.java
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
        wireSharedDashboardCallbacks(mirror)
        // Reuse the shared provider so mirrors benefit from the ticket context cache
        sharedMentionSearchProvider?.let { mirror.setMentionSearchProvider(it) }
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

    /**
     * Open the current plan in a full JCEF editor tab (AgentPlanEditor).
     * Wires approve/revise/comment-count callbacks so the editor tab stays in sync
     * with the chat panel's plan card.
     *
     * Called when the user clicks "View Plan" in the chat plan card.
     */
    private fun openPlanInEditor() {
        val plan = currentPlanData ?: return
        val sid = currentSessionId ?: "unknown"
        val vf = AgentPlanVirtualFile(plan, sid)

        invokeLater {
            val editors = FileEditorManager.getInstance(project).openFile(vf, true)
            val planEditor = editors.filterIsInstance<AgentPlanEditor>().firstOrNull()
            if (planEditor != null) {
                planEditor.onApprove = ::approvePlan
                planEditor.onRevise = ::revisePlan
                planEditor.onCommentCountChanged = { count ->
                    dashboard.setPlanCommentCount(count)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════

    private fun buildToolsJson(): String =
        service.registry.allTools().joinToString(",", "[", "]") { tool ->
            val escapedName = tool.name.replace("\"", "\\\"")
            val escapedDesc = tool.description.take(200).replace("\"", "\\\"").replace("\n", " ")
            """{"name":"$escapedName","description":"$escapedDesc","enabled":true}"""
        }

    /**
     * Format token count for display: "45K" for large counts, exact for small.
     * Ported from Cline's webview token display pattern.
     */
    private fun formatTokenCount(tokens: Int): String =
        if (tokens >= 1000) "${tokens / 1000}K" else tokens.toString()

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
                    currentSessionId?.let { service.updateSessionTitle(it, newTitle) }
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
     * Build a JSON array of checkpoint metadata for the dashboard UI.
     * Format: [{"id":"cp-1-123","description":"After edit_file: ...","timestamp":123456}]
     */
    private fun buildCheckpointsJson(
        checkpoints: List<com.workflow.orchestrator.agent.session.CheckpointInfo>
    ): String = checkpoints.joinToString(",", "[", "]") { cp ->
        val escapedDesc = cp.description.replace("\"", "\\\"").replace("\n", " ")
        """{"id":"${cp.id}","description":"$escapedDesc","timestamp":${cp.createdAt}}"""
    }

    /**
     * Parse a render-outcome JSON payload from the webview and forward the decoded
     * result to [ArtifactResultRegistry] so the corresponding suspended
     * `render_artifact` tool call resumes.
     *
     * Payload shape (from `ArtifactRenderer` / `sandbox-main.ts`):
     * ```
     * { "renderId": "...", "status": "success" | "error",
     *   "heightPx": <int?>,
     *   "phase": "render" | "transpile" | "runtime" | "init",
     *   "message": "...",
     *   "missingSymbols": ["Foo", "Bar"],
     *   "line": <int?> }
     * ```
     *
     * Malformed payloads are dropped with a warning — never throw here, the JCEF
     * bridge runs on the EDT and a throw would propagate into browser land.
     */
    private fun parseAndDispatchArtifactResult(rawJson: String) {
        try {
            val obj = Json.parseToJsonElement(rawJson).jsonObject
            val renderId = obj["renderId"]?.jsonPrimitive?.content ?: run {
                LOG.warn("artifact result missing renderId: $rawJson")
                return
            }
            val status = obj["status"]?.jsonPrimitive?.content ?: "error"
            val result: ArtifactRenderResult = if (status == "success") {
                val height = obj["heightPx"]?.jsonPrimitive?.content?.toIntOrNull()
                ArtifactRenderResult.Success(heightPx = height)
            } else {
                val phase = obj["phase"]?.jsonPrimitive?.content ?: "runtime"
                val message = obj["message"]?.jsonPrimitive?.content ?: "Unknown render error"
                val missing = obj["missingSymbols"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }
                    ?: emptyList()
                val line = obj["line"]?.jsonPrimitive?.content?.toIntOrNull()
                ArtifactRenderResult.RenderError(
                    phase = phase,
                    message = message,
                    missingSymbols = missing,
                    line = line,
                )
            }
            ArtifactResultRegistry.getInstance(project).reportResult(renderId, result)
        } catch (e: Exception) {
            LOG.warn("failed to parse artifact result JSON: $rawJson", e)
        }
    }

    /**
     * Handle round-trip confirmation from JS interactive renders (questions, plans, approvals).
     *
     * Payload: `{ "type": "question"|"plan"|"approval", "status": "ok"|"error", "message"?: "..." }`
     *
     * On error, the JS side already rendered a fallback (plain text). This handler logs
     * the result so we have diagnostic visibility into silent JCEF failures.
     */
    private fun handleInteractiveRenderResult(rawJson: String) {
        try {
            val obj = Json.parseToJsonElement(rawJson).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: "unknown"
            val status = obj["status"]?.jsonPrimitive?.content ?: "unknown"
            val message = obj["message"]?.jsonPrimitive?.content

            if (status == "ok") {
                LOG.info("Interactive render confirmed: type=$type, status=ok")
                // Signal the watchdog timer in AskQuestionsTool that the UI rendered.
                // This prevents the 10s watchdog from auto-resolving the deferred
                // when the UI is alive but the user just hasn't answered yet.
                if (type == "question") {
                    com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool.uiRenderConfirmed = true
                }
            } else {
                LOG.warn("Interactive render FAILED: type=$type, status=$status, message=$message")
                // JS side already handled the fallback rendering.
                // Push a debug log entry so the user can see it in the API debug tab.
                dashboard.pushDebugLogEntry("warn", "render_failed",
                    "Interactive $type render failed: ${message ?: "unknown error"}", null)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to parse interactive render result: $rawJson", e)
        }
    }

    override fun dispose() {
        LOG.info("AgentController.dispose")
        if (currentJob?.isActive == true) {
            service.cancelCurrentTask()
        }
        clearActiveLoopState()
        phraseTimerJob?.cancel()
        phraseTimerJob = null
        contextManager = null
        currentSessionId = null
        currentPlanData = null
        pendingApproval?.cancel()
        pendingApproval = null
        pendingApprovalToolName = null
        // Drop the artifact push callback so the registry cannot invoke a
        // stale dashboard reference if a render fires in the window between
        // this dispose and a new controller installing its own callback.
        // Matches the "remove listener on tear-down" pattern used elsewhere.
        ArtifactResultRegistry.getInstance(project).setPushCallback(null)
    }
}

// ── Plan revision helpers (extracted for testability) ──────────────────────────

/**
 * Build a human-readable revision message from the plan editor's v2 JSON payload.
 *
 * v2 format: `{"comments": [{line, content, comment}], "markdown": "..."}`
 *
 * The output is injected into the agent loop as a user message so the LLM
 * can see the per-line comments and revise the plan accordingly.
 */
internal fun buildRevisionMessage(commentsJson: String): String {
    return buildString {
        appendLine("I have comments on your plan. Please revise it:")
        appendLine()
        try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(commentsJson)
            if (root is kotlinx.serialization.json.JsonObject && root.containsKey("comments")) {
                val comments = root["comments"]!!.jsonArray
                for (item in comments) {
                    val obj = item.jsonObject
                    val line = obj["line"]?.jsonPrimitive?.intOrNull
                    val content = obj["content"]?.jsonPrimitive?.content ?: ""
                    val comment = obj["comment"]?.jsonPrimitive?.content ?: ""
                    if (comment.isNotBlank()) {
                        if (line != null && content.isNotBlank()) {
                            appendLine("- Line $line (`$content`): $comment")
                        } else {
                            appendLine("- $comment")
                        }
                    }
                }
            } else {
                // Unknown format — include raw text as fallback
                appendLine(commentsJson)
            }
        } catch (_: Exception) {
            // Invalid JSON — include raw text so the LLM can still try
            appendLine(commentsJson)
        }
        appendLine()
        appendLine("Please revise the plan and present the updated version using plan_mode_respond.")
    }
}

/**
 * Count the number of non-blank comments in a v2 revision payload.
 * Returns 0 for invalid or empty payloads.
 */
internal fun countRevisionComments(commentsJson: String): Int {
    return try {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(commentsJson)
        if (root is kotlinx.serialization.json.JsonObject && root.containsKey("comments")) {
            root["comments"]!!.jsonArray.count { item ->
                val comment = item.jsonObject["comment"]?.jsonPrimitive?.content ?: ""
                comment.isNotBlank()
            }
        } else 0
    } catch (_: Exception) {
        0
    }
}
