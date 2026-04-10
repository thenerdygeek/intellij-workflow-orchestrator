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
import com.workflow.orchestrator.agent.loop.PlanJson
import com.workflow.orchestrator.agent.loop.PlanStep
import com.workflow.orchestrator.agent.loop.SteeringMessage
import com.workflow.orchestrator.agent.loop.TaskProgress
import com.workflow.orchestrator.agent.loop.ToolCallProgress
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
        wireCallbacks()
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
            invokeLater {
                // Flush any in-progress stream + finalize tool chain so the question
                // appears AFTER prior tool calls, not mixed in
                dashboard.flushStreamBuffer()
                dashboard.finalizeToolChain()

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
                    dashboard.showQuestions(wizardJson)
                } else {
                    // Questions WITHOUT options → user types their answer freely in the chat input.
                    // In XML mode (always on), the question text was already streamed to the UI
                    // as part of the LLM's text content (before the tool block was parsed).
                    dashboard.setBusy(false)
                    dashboard.setInputLocked(false)
                    dashboard.focusInput()
                }
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
                // Convert the live question wizard into a frozen Q&A chat bubble
                // BEFORE resolving the tool, so the snapshot reflects what was answered.
                dashboard.finalizeQuestionsAsMessage()
                if (pendingApprovalChoice) {
                    // System-level approval choice — route to our handler, not AskQuestionsTool
                    handleApprovalChoice(collectedAnswers)
                } else {
                    // Normal LLM-initiated question — resolve the pending deferred
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

        // Sync chat animation setting
        dashboard.setChatAnimationsEnabled(AgentSettings.getInstance(project).state.chatAnimationsEnabled)

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
            onArtifactRendered = { payload ->
                invokeLater {
                    dashboard.renderArtifact(payload.title, payload.source)
                }
            },
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
                    // Use raw toolName so the matching key in updateSubAgentToolCall works.
                    update.toolStartName?.let { name ->
                        dashboard.addSubAgentToolCall(agentId, name, update.toolStartArgs ?: "")
                    }
                    // Tool completing — flip the matching RUNNING chip to COMPLETED/ERROR.
                    update.toolCompleteName?.let { name ->
                        dashboard.updateSubAgentToolCall(
                            agentId,
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
                    output = progress.output ?: progress.result.takeIf { it.isNotBlank() },
                    diff = progress.editDiff
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
    }

    fun newChat() {
        LOG.info("AgentController.newChat")

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
        prepareForReplay("Resuming session...")

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

        // Build the approval instruction
        val stepsChecklist = currentPlanData?.steps?.joinToString("\n") { step ->
            "- [ ] ${step.title}"
        } ?: ""

        val instruction = buildString {
            appendLine("The user has approved the plan.")
            if (stepsChecklist.isNotBlank()) {
                appendLine()
                appendLine("Task checklist:")
                appendLine(stepsChecklist)
            }
        }.trim()

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

    fun dispose() {
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
    }
}
