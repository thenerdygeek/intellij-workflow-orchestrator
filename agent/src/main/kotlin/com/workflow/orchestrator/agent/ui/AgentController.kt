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
import com.workflow.orchestrator.agent.util.AgentStringUtils
import com.workflow.orchestrator.agent.orchestrator.AgentOrchestrator
import com.workflow.orchestrator.agent.orchestrator.AgentProgress
import com.workflow.orchestrator.agent.orchestrator.AgentResult
import com.workflow.orchestrator.agent.orchestrator.ToolCallInfo
import com.workflow.orchestrator.agent.orchestrator.PromptAssembler
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.runtime.AgentPlan
import com.workflow.orchestrator.agent.runtime.ConversationSession
import com.workflow.orchestrator.agent.runtime.PlanManager
import com.workflow.orchestrator.core.util.ProjectIdentifier
import com.workflow.orchestrator.agent.settings.AgentSettings
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.workflow.orchestrator.agent.settings.ToolPreferences
import com.workflow.orchestrator.agent.tools.ToolCategoryRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
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
    private var currentTaskJob: kotlinx.coroutines.Job? = null
    private var currentOrchestrator: AgentOrchestrator? = null
    private var sessionStartMs = 0L
    private var session: ConversationSession? = null
    private val pendingUserMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var sessionAutoApprove = false
    private var currentPlanFile: com.workflow.orchestrator.agent.ui.plan.AgentPlanVirtualFile? = null
    private var planModeEnabled = false
    @Volatile
    private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var currentApprovalGate: ApprovalGate? = null
    private val mentionContextBuilder by lazy { MentionContextBuilder(project) }

    init {
        // Tie coroutine scope to project lifecycle — cancel when project closes
        com.intellij.openapi.util.Disposer.register(project, com.intellij.openapi.Disposable {
            scope.cancel()
            ProcessRegistry.killAll()
        })

        // Wire all JCEF toolbar/input callbacks via the unified bridge
        dashboard.setCefActionCallbacks(
            onCancel = { cancelTask() },
            onNewChat = { newChat() },
            onSendMessage = { text -> executeTask(text) },
            onChangeModel = { modelId ->
                try {
                    val settings = AgentSettings.getInstance(project)
                    settings.state.sourcegraphChatModel = modelId
                    settings.state.userManuallySelectedModel = true
                    // Show the human-readable name, not the raw ID
                    val displayName = com.workflow.orchestrator.agent.api.dto.ModelInfo(id = modelId).displayName
                    dashboard.setModelName(displayName)
                } catch (_: Exception) {}
            },
            onTogglePlanMode = { enabled ->
                planModeEnabled = enabled
                if (enabled) {
                    // Inject planning constraints into any active session context so the LLM
                    // immediately sees planning mode rules — mirrors what EnablePlanModeTool does.
                    try {
                        AgentService.getInstance(project).currentContextManager
                            ?.addSystemMessage(PromptAssembler.FORCED_PLANNING_RULES)
                    } catch (_: Exception) {}
                    dashboard.setPlanMode(true)
                } else {
                    dashboard.setPlanMode(false)
                }
            },
            onActivateSkill = { name -> executeTask("/$name") },
            onRequestFocusIde = {
                ApplicationManager.getApplication().invokeLater {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                        .selectedTextEditor?.contentComponent?.requestFocusInWindow()
                }
            },
            onOpenSettings = {
                ApplicationManager.getApplication().invokeLater {
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, "workflow.orchestrator.agent")
                }
            },
            onOpenToolsPanel = { showToolsPanel() }
        )

        // Wire mention-aware send callback
        dashboard.setCefMentionCallbacks(
            onSendWithMentions = { text, mentionsJson -> handleMessageWithMentions(text, mentionsJson) }
        )

        // Wire tool call approval callbacks — connects JCEF approve/deny to ApprovalGate
        dashboard.setCefApprovalCallbacks(
            onApprove = {
                currentApprovalGate?.respondToApproval(ApprovalResult.Approved)
                pendingApprovalDeferred?.complete(true)
                pendingApprovalDeferred = null
            },
            onDeny = {
                currentApprovalGate?.respondToApproval(ApprovalResult.Rejected("Rejected by user"))
                pendingApprovalDeferred?.complete(false)
                pendingApprovalDeferred = null
            },
            onAllowForSession = { toolName ->
                currentApprovalGate?.allowToolForSession(toolName)
                pendingApprovalDeferred?.complete(true)
                pendingApprovalDeferred = null
            }
        )

        // Wire ask_user_input callbacks
        com.workflow.orchestrator.agent.tools.builtin.AskUserInputTool.showInputCallback = { processId, description, prompt, command ->
            com.intellij.openapi.application.invokeLater {
                dashboard.showProcessInput(processId, description, prompt, command)
            }
        }
        dashboard.setCefProcessInputCallbacks(
            onInput = { input -> com.workflow.orchestrator.agent.tools.builtin.AskUserInputTool.resolveInput(input) }
        )

        // Wire interactive HTML message callback
        dashboard.setCefInteractiveHtmlCallback { json ->
            LOG.info("Interactive HTML message: ${json.take(200)}")
        }

        // Wire "Open in Editor Tab" button for visualizations (chart, flow, mermaid, diff, etc.)
        dashboard.setCefEditorTabCallback { payload ->
            try {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(payload)
                val type = json.jsonObject["type"]?.jsonPrimitive?.content ?: "unknown"
                val content = json.jsonObject["content"]?.jsonPrimitive?.content ?: payload
                if (type == "plan") {
                    // Navigate to the already-open plan editor tab rather than opening a second tab.
                    ApplicationManager.getApplication().invokeLater {
                        val planFile = currentPlanFile
                        if (planFile != null) {
                            FileEditorManager.getInstance(project).openFile(planFile, true)
                        }
                    }
                } else {
                    AgentVisualizationEditor.openVisualization(project, type, content)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to open visualization in editor tab: ${e.message}")
            }
        }

        // Wire diff hunk callbacks — apply or reject diff hunks via DiffHunkApplier
        dashboard.setCefDiffHunkCallbacks(
            onAccept = { filePath, hunkIndex, editedContent ->
                LOG.info("Accepted diff hunk #$hunkIndex for $filePath${if (editedContent != null) " (edited)" else ""}")
                scope.launch(Dispatchers.IO) {
                    try {
                        val file = File(filePath)
                        if (!file.exists()) {
                            LOG.warn("DiffHunk: file not found: $filePath")
                            return@launch
                        }
                        if (editedContent != null) {
                            // User edited the hunk content before accepting — write directly
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                                    if (vf != null) {
                                        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
                                        doc?.setText(editedContent)
                                    }
                                }
                            }
                        } else {
                            LOG.info("DiffHunk: accepted hunk #$hunkIndex for $filePath (no edit — original hunk applied by UI)")
                        }
                    } catch (e: Exception) {
                        LOG.warn("DiffHunk: failed to apply hunk #$hunkIndex for $filePath", e)
                        LOG.warn("DiffHunk: error applying hunk at $filePath: ${e.message}")
                    }
                }
            },
            onReject = { filePath, hunkIndex ->
                LOG.info("Rejected diff hunk #$hunkIndex for $filePath")
            }
        )

        // Wire kill tool call callback — kills running process by tool call ID
        dashboard.setCefKillCallback { toolCallId ->
            ProcessRegistry.kill(toolCallId)
        }

        // Wire JCEF JS→Kotlin action callbacks (undo, view-trace, example prompts)
        dashboard.setCefCallbacks(
            onUndo = { handleUndoRequest() },
            onViewTrace = { openLatestTrace() },
            onPromptSubmitted = { text -> executeTask(text) }
        )

        // Wire JCEF plan card callbacks (approve, revise)
        dashboard.setCefPlanCallbacks(
            onApprove = { session?.planManager?.approvePlan() },
            onRevise = { revisionJson ->
                try {
                    val json = kotlinx.serialization.json.Json.parseToJsonElement(revisionJson).jsonObject
                    // New format: { comments: [{line, comment}], fullMarkdown }
                    val commentsArray = json["comments"]?.jsonArray
                    val fullMarkdown = json["fullMarkdown"]?.jsonPrimitive?.contentOrNull

                    if (commentsArray != null) {
                        val revisions = commentsArray.map { item ->
                            val obj = item.jsonObject
                            val line = obj["line"]?.jsonPrimitive?.contentOrNull ?: ""
                            val comment = obj["comment"]?.jsonPrimitive?.contentOrNull ?: ""
                            PlanRevisionComment(line = line, comment = comment)
                        }
                        session?.planManager?.revisePlanWithContext(revisions, fullMarkdown)
                    } else {
                        // Legacy format: { lineId: commentText }
                        val comments = kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(revisionJson)
                        session?.planManager?.revisePlan(comments)
                    }
                } catch (e: Exception) {
                    LOG.warn("AgentController: failed to parse plan revision", e)
                    dashboard.appendError("Failed to parse plan comments. Please try again.")
                }
            }
        )

        // Set current model name, fetch model list, and sync debug log visibility
        try {
            val settings = AgentSettings.getInstance(project)
            val model = settings.state.sourcegraphChatModel ?: ""
            dashboard.setModelName(model)
            loadModelList()
            // Always sync debug log visibility to JCEF on startup
            dashboard.setDebugLogVisible(settings.state.showDebugLog)
        } catch (_: Exception) {}

        // Wire click-to-navigate file paths in JCEF chat output
        dashboard.setCefNavigationCallbacks(
            onNavigateToFile = { filePath, line ->
                val basePath = project.basePath ?: return@setCefNavigationCallbacks
                val fullPath = if (filePath.startsWith("/") || filePath.startsWith(basePath)) filePath
                               else "$basePath/$filePath"
                val file = java.io.File(fullPath)
                if (file.exists()) {
                    val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                    if (vf != null) {
                        ApplicationManager.getApplication().invokeLater {
                            val editors = FileEditorManager.getInstance(project).openFile(vf, true)
                            if (line > 0) {
                                val textEditor = editors.filterIsInstance<com.intellij.openapi.fileEditor.TextEditor>().firstOrNull()
                                textEditor?.let {
                                    val lineIdx = maxOf(0, line - 1)
                                    if (lineIdx < it.editor.document.lineCount) {
                                        val offset = it.editor.document.getLineStartOffset(lineIdx)
                                        it.editor.caretModel.moveToOffset(offset)
                                        it.editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )

        // Wire JCEF tool toggle callback — persists enable/disable to ToolPreferences
        dashboard.setCefToolToggleCallback { toolName, enabled ->
            try {
                ToolPreferences.getInstance(project).setToolEnabled(toolName, enabled)
            } catch (_: Exception) {}
        }

        dashboard.setMentionSearchProvider(MentionSearchProvider(project))

        dashboard.setOnViewInEditor { viewInEditor() }

        dashboard.focusInput()
    }

    // ═══════════════════════════════════════════════════
    //  Mirror panel management — "View in Editor" support
    // ═══════════════════════════════════════════════════

    /**
     * Register [panel] as a mirror of the primary dashboard.
     * The mirror receives all output broadcasts and has its own input callbacks
     * wired back to this controller via [wireExtraPanel].
     */
    fun addMirrorPanel(panel: AgentDashboardPanel) {
        dashboard.addMirror(panel)
        wireExtraPanel(panel)
    }

    fun removeMirrorPanel(panel: AgentDashboardPanel) {
        dashboard.removeMirror(panel)
    }

    /**
     * Wire all input callbacks on [panel] back to this controller.
     * Called when a new editor-tab panel is created — mirrors the init{} wiring.
     */
    private fun wireExtraPanel(panel: AgentDashboardPanel) {
        panel.setCefActionCallbacks(
            onCancel = { cancelTask() },
            onNewChat = { newChat() },
            onSendMessage = { text -> executeTask(text) },
            onChangeModel = { modelId ->
                try {
                    val settings = AgentSettings.getInstance(project)
                    settings.state.sourcegraphChatModel = modelId
                    settings.state.userManuallySelectedModel = true
                    val displayName = com.workflow.orchestrator.agent.api.dto.ModelInfo(id = modelId).displayName
                    dashboard.setModelName(displayName)
                } catch (_: Exception) {}
            },
            onTogglePlanMode = { enabled ->
                planModeEnabled = enabled
                if (enabled) {
                    try {
                        AgentService.getInstance(project).currentContextManager
                            ?.addSystemMessage(PromptAssembler.FORCED_PLANNING_RULES)
                    } catch (_: Exception) {}
                    dashboard.setPlanMode(true)
                } else {
                    dashboard.setPlanMode(false)
                }
            },
            onActivateSkill = { name -> executeTask("/$name") },
            onRequestFocusIde = {
                ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).selectedTextEditor?.contentComponent?.requestFocusInWindow()
                }
            },
            onOpenSettings = {
                ApplicationManager.getApplication().invokeLater {
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, "workflow.orchestrator.agent")
                }
            },
            onOpenToolsPanel = { showToolsPanel() }
        )
        panel.setCefMentionCallbacks(
            onSendWithMentions = { text, mentionsJson -> handleMessageWithMentions(text, mentionsJson) }
        )
        panel.setCefApprovalCallbacks(
            onApprove = {
                currentApprovalGate?.respondToApproval(ApprovalResult.Approved)
                pendingApprovalDeferred?.complete(true)
                pendingApprovalDeferred = null
            },
            onDeny = {
                currentApprovalGate?.respondToApproval(ApprovalResult.Rejected("Rejected by user"))
                pendingApprovalDeferred?.complete(false)
                pendingApprovalDeferred = null
            },
            onAllowForSession = { toolName ->
                currentApprovalGate?.allowToolForSession(toolName)
                pendingApprovalDeferred?.complete(true)
                pendingApprovalDeferred = null
            }
        )
        panel.setCefProcessInputCallbacks(
            onInput = { input -> com.workflow.orchestrator.agent.tools.builtin.AskUserInputTool.resolveInput(input) }
        )
        panel.setCefCallbacks(
            onUndo = { handleUndoRequest() },
            onViewTrace = { openLatestTrace() },
            onPromptSubmitted = { text -> executeTask(text) }
        )
        panel.setCefPlanCallbacks(
            onApprove = { session?.planManager?.approvePlan() },
            onRevise = { revisionJson ->
                try {
                    val json = kotlinx.serialization.json.Json.parseToJsonElement(revisionJson).jsonObject
                    val commentsArray = json["comments"]?.jsonArray
                    val fullMarkdown = json["fullMarkdown"]?.jsonPrimitive?.contentOrNull
                    if (commentsArray != null) {
                        val revisions = commentsArray.map { item ->
                            val obj = item.jsonObject
                            val line = obj["line"]?.jsonPrimitive?.contentOrNull ?: ""
                            val comment = obj["comment"]?.jsonPrimitive?.contentOrNull ?: ""
                            PlanRevisionComment(line = line, comment = comment)
                        }
                        session?.planManager?.revisePlanWithContext(revisions, fullMarkdown)
                    } else {
                        val comments = kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(revisionJson)
                        session?.planManager?.revisePlan(comments)
                    }
                } catch (e: Exception) {
                    LOG.warn("AgentController: failed to parse plan revision from mirror panel", e)
                }
            }
        )
        panel.setCefNavigationCallbacks(
            onNavigateToFile = { filePath, line ->
                val basePath = project.basePath ?: return@setCefNavigationCallbacks
                val fullPath = if (filePath.startsWith("/") || filePath.startsWith(basePath)) filePath
                               else "$basePath/$filePath"
                val file = java.io.File(fullPath)
                if (file.exists()) {
                    val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                    if (vf != null) {
                        ApplicationManager.getApplication().invokeLater {
                            val editors = FileEditorManager.getInstance(project).openFile(vf, true)
                            if (line > 0) {
                                val textEditor = editors.filterIsInstance<com.intellij.openapi.fileEditor.TextEditor>().firstOrNull()
                                textEditor?.let {
                                    val lineIdx = maxOf(0, line - 1)
                                    if (lineIdx < it.editor.document.lineCount) {
                                        val offset = it.editor.document.getLineStartOffset(lineIdx)
                                        it.editor.caretModel.moveToOffset(offset)
                                        it.editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
        panel.setCefDiffHunkCallbacks(
            onAccept = { filePath, hunkIndex, editedContent ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val file = File(filePath)
                        if (editedContent != null && file.exists()) {
                            ApplicationManager.getApplication().invokeLater {
                                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                                    if (vf != null) {
                                        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
                                        doc?.setText(editedContent)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        LOG.warn("DiffHunk (mirror): failed to apply hunk #$hunkIndex for $filePath", e)
                    }
                }
            },
            onReject = { filePath, hunkIndex -> LOG.info("Rejected diff hunk #$hunkIndex for $filePath (mirror)") }
        )
        panel.setCefKillCallback { toolCallId -> ProcessRegistry.kill(toolCallId) }
        panel.setCefKillSubAgentCallback { agentId ->
            try { AgentService.getInstance(project).killWorker(agentId) } catch (_: Exception) {}
        }
        panel.setCefEditorTabCallback { payload ->
            try {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(payload)
                val type = json.jsonObject["type"]?.jsonPrimitive?.content ?: "unknown"
                val content = json.jsonObject["content"]?.jsonPrimitive?.content ?: payload
                if (type == "plan") {
                    ApplicationManager.getApplication().invokeLater {
                        currentPlanFile?.let { FileEditorManager.getInstance(project).openFile(it, true) }
                    }
                } else {
                    AgentVisualizationEditor.openVisualization(project, type, content)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to open visualization from mirror panel: ${e.message}")
            }
        }
        panel.setCefToolToggleCallback { toolName, enabled ->
            try { ToolPreferences.getInstance(project).setToolEnabled(toolName, enabled) } catch (_: Exception) {}
        }
        panel.setMentionSearchProvider(MentionSearchProvider(project))
        panel.setOnViewInEditor { viewInEditor() }
    }

    private fun viewInEditor() {
        ApplicationManager.getApplication().invokeLater {
            // Reuse an existing open tab if one is already showing
            val existing = FileEditorManager.getInstance(project).openFiles
                .filterIsInstance<AgentChatVirtualFile>()
                .firstOrNull()
            val file = existing ?: AgentChatVirtualFile(project)
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

    private fun showToolsPanel() {
        val agentService = try { AgentService.getInstance(project) } catch (_: Exception) { return }
        val prefs = try { ToolPreferences.getInstance(project) } catch (_: Exception) { null }
        val allRegisteredTools = agentService.toolRegistry.allTools()

        // Build JSON using JsonObject/JsonArray builders with explicit JsonPrimitive wrapping
        // (kotlinx.serialization can't serialize Map<String, Any?> — no serializer for Any)
        val categoriesArray = kotlinx.serialization.json.buildJsonArray {
            for (cat in ToolCategoryRegistry.CATEGORIES) {
                val toolsArray = kotlinx.serialization.json.buildJsonArray {
                    for (toolName in cat.tools) {
                        val tool = allRegisteredTools.find { it.name == toolName } ?: continue
                        val paramsArray = kotlinx.serialization.json.buildJsonArray {
                            for ((pname, prop) in tool.parameters.properties) {
                                add(kotlinx.serialization.json.buildJsonObject {
                                    put("name", kotlinx.serialization.json.JsonPrimitive(pname))
                                    put("type", kotlinx.serialization.json.JsonPrimitive(prop.type))
                                    put("description", kotlinx.serialization.json.JsonPrimitive(prop.description))
                                    put("required", kotlinx.serialization.json.JsonPrimitive(pname in tool.parameters.required))
                                })
                            }
                        }
                        val schema = try {
                            prettyJson.encodeToString(com.workflow.orchestrator.agent.api.dto.ToolDefinition.serializer(), tool.toToolDefinition())
                        } catch (_: Exception) { "" }

                        add(kotlinx.serialization.json.buildJsonObject {
                            put("name", kotlinx.serialization.json.JsonPrimitive(toolName))
                            put("description", kotlinx.serialization.json.JsonPrimitive(tool.description))
                            put("enabled", kotlinx.serialization.json.JsonPrimitive(prefs?.isToolEnabled(toolName) ?: true))
                            put("active", kotlinx.serialization.json.JsonPrimitive(false))
                            put("badge", kotlinx.serialization.json.JsonPrimitive(cat.badgePrefix))
                            put("categoryColor", kotlinx.serialization.json.JsonPrimitive(cat.color))
                            put("parameters", paramsArray)
                            put("schema", kotlinx.serialization.json.JsonPrimitive(schema))
                        })
                    }
                }
                add(kotlinx.serialization.json.buildJsonObject {
                    put("displayName", kotlinx.serialization.json.JsonPrimitive(cat.displayName))
                    put("color", kotlinx.serialization.json.JsonPrimitive(cat.color))
                    put("badgePrefix", kotlinx.serialization.json.JsonPrimitive(cat.badgePrefix))
                    put("tools", toolsArray)
                })
            }
        }

        val json = kotlinx.serialization.json.buildJsonObject {
            put("categories", categoriesArray)
        }.toString()
        dashboard.showToolsPanel(json)
    }

    private fun handleMessageWithMentions(text: String, mentionsJson: String) {
        // Parse mentions from JSON
        val mentions = try {
            kotlinx.serialization.json.Json.parseToJsonElement(mentionsJson).jsonArray.map { el ->
                val obj = el.jsonObject
                MentionContextBuilder.Mention(
                    type = obj["type"]?.jsonPrimitive?.content ?: "file",
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    value = obj["value"]?.jsonPrimitive?.content ?: ""
                )
            }
        } catch (_: Exception) { emptyList() }

        // Build mention context (reads files, generates trees) then execute
        if (mentions.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val context = mentionContextBuilder.buildContext(mentions)
                if (context != null) {
                    session?.contextManager?.setMentionAnchor(
                        com.workflow.orchestrator.agent.api.dto.ChatMessage(
                            role = "system",
                            content = "<mentioned_context>\n$context</mentioned_context>"
                        )
                    )
                } else {
                    session?.contextManager?.setMentionAnchor(null)
                }
                SwingUtilities.invokeLater {
                    executeTask(text)
                }
            }
        } else {
            // No mentions — clear any previous anchor and send normally
            session?.contextManager?.setMentionAnchor(null)
            executeTask(text)
        }
    }

    fun executeTaskWithMentions(prompt: String, filePaths: List<String>) {
        if (filePaths.isNotEmpty()) {
            val mentions = filePaths.map { path ->
                MentionContextBuilder.Mention(
                    type = "file",
                    name = java.io.File(path).name,
                    value = path
                )
            }
            scope.launch(Dispatchers.IO) {
                val context = mentionContextBuilder.buildContext(mentions)
                if (context != null) {
                    session?.contextManager?.setMentionAnchor(
                        com.workflow.orchestrator.agent.api.dto.ChatMessage(
                            role = "system",
                            content = "<mentioned_context>\n$context</mentioned_context>"
                        )
                    )
                }
                SwingUtilities.invokeLater { executeTask(prompt) }
            }
        } else {
            executeTask(prompt)
        }
    }

    fun executeTask(task: String) {
        // Check for skill /command invocation
        if (task.startsWith("/") && session != null) {
            val parts = task.removePrefix("/").split(" ", limit = 2)
            val skillName = parts[0]
            val args = parts.getOrNull(1)
            val skillMgr = session?.skillManager
            if (skillMgr != null) {
                val skill = skillMgr.registry.getSkill(skillName)
                if (skill != null && skill.userInvocable) {
                    skillMgr.activateSkill(skillName, args)
                    // The onSkillActivated callback handles context injection + banner
                    // Now send a message to the LLM with the skill context
                    val userMessage = args ?: "I've activated the ${skill.name} skill. Please follow the skill instructions."
                    // Fall through to normal executeTask with the modified message
                    // (userMessage does NOT start with "/" so no infinite recursion)
                    executeTask(userMessage)
                    return
                } else if (skill != null && !skill.userInvocable) {
                    dashboard.appendStatus("Skill '${skillName}' cannot be invoked directly. It's auto-triggered by the agent.", RichStreamingPanel.StatusType.WARNING)
                    return
                } else if (skill == null) {
                    dashboard.appendStatus(
                        "Skill '${skillName}' not found. Available: ${skillMgr.registry.getUserInvocableSkills().joinToString { "/${it.name}" }}",
                        RichStreamingPanel.StatusType.WARNING
                    )
                    return
                }
            }
        }

        // If agent is already running, queue the message as intervention
        if (currentOrchestrator != null && session != null) {
            pendingUserMessages.add(task)
            dashboard.appendUserMessage(task)
            dashboard.appendStatus("Message queued — will be sent to the agent after the current step.", RichStreamingPanel.StatusType.INFO)
            return
        }

        // Helper: always push debug entries (not gated by showDebugLog setting)
        fun debugLog(level: String, event: String, detail: String, meta: Map<String, Any?>? = null) {
            dashboard.pushDebugLogEntry(level, event, detail, meta)
        }

        debugLog("info", "execute", "executeTask called", mapOf("taskLength" to task.length))

        val agentService = try {
            AgentService.getInstance(project)
        } catch (e: Exception) {
            debugLog("error", "init_fail", "Agent service not available: ${e.message}")
            dashboard.appendError("Agent service not available: ${e.message}")
            return
        }
        if (!agentService.isConfigured()) {
            debugLog("error", "init_fail", "Agent not configured — missing Sourcegraph URL or token or agent not enabled")
            dashboard.appendError("Agent not configured. Set up Sourcegraph connection and enable Agent in Settings.")
            return
        }
        debugLog("info", "config_ok", "Agent service configured and ready")

        // Create or reuse session — session creation moved off EDT into coroutine
        // (ConversationSession.create() calls RepoMapGenerator which does a blocking ReadAction
        //  that recursively walks the entire PSI tree — must NOT run on EDT)
        val isNewSession = session == null
        if (isNewSession) {
            dashboard.startSession(task)
            dashboard.appendStatus("Initializing agent...", RichStreamingPanel.StatusType.INFO)
        } else {
            dashboard.appendUserMessage(task)
        }

        dashboard.setBusy(true)

        sessionStartMs = System.currentTimeMillis()

        currentTaskJob = scope.launch {
            try {
                // Create session off EDT (RepoMapGenerator + skill discovery + memory loading are heavy)
                if (isNewSession) {
                    debugLog("info", "session", "Creating new ConversationSession...")
                    session = ConversationSession.create(project, agentService, planMode = planModeEnabled)
                    wireSessionCallbacks(session!!)
                    debugLog("info", "session", "Session created: ${session!!.sessionId}")
                }
                val currentSession = session!!
                currentSession.recordUserMessage(task)

                // Set PlanManager and SkillManager on AgentService so tools can find it
                try {
                    val agentSvc = AgentService.getInstance(project)
                    agentSvc.currentPlanManager = currentSession.planManager
                    agentSvc.currentQuestionManager = currentSession.questionManager
                    agentSvc.currentSkillManager = currentSession.skillManager
                } catch (_: Exception) {}

                val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
                val approvalGate = ApprovalGate(
                    approvalRequired = settings?.state?.approvalRequiredForEdits ?: true,
                    onApprovalNeeded = { desc, risk -> showApprovalDialog(desc, risk) },
                    approvalCallback = { toolName, risk, params ->
                        val metadata = buildApprovalMetadata(toolName, params)
                        val description = buildApprovalDescription(toolName, params)
                        val diffContent = buildUnifiedDiff(toolName, params)
                        dashboard.showApproval(
                            toolName = toolName,
                            riskLevel = risk.name,
                            description = description,
                            metadataJson = metadata,
                            diffContent = diffContent
                        )
                    }
                )
                currentApprovalGate = approvalGate

                // Wire streaming output from RunCommandTool → JCEF
                com.workflow.orchestrator.agent.tools.builtin.RunCommandTool.streamCallback = { toolCallId, chunk ->
                    com.intellij.openapi.application.invokeLater {
                        dashboard.appendToolOutput(toolCallId, chunk)
                    }
                }

                // Create orchestrator (lightweight — just brain + registry + project)
                debugLog("info", "orchestrator", "Creating AgentOrchestrator...")
                val orchestrator = AgentOrchestrator(currentSession.brain, agentService.toolRegistry, project)
                currentOrchestrator = orchestrator

                // Debug log callback — always active for observability
                val onDebugLog: (String, String, String, Map<String, Any?>?) -> Unit =
                    { level, event, detail, meta -> debugLog(level, event, detail, meta) }

                debugLog("info", "llm_call", "Calling orchestrator.executeTask...", mapOf(
                    "model" to (settings?.state?.sourcegraphChatModel ?: "unknown"),
                    "tools" to currentSession.activeToolNames.size
                ))
                // Launch reaper coroutine to clean up idle processes
                val reaperJob = launch {
                    while (isActive) {
                        delay(10_000)
                        ProcessRegistry.reapIdleProcesses()
                    }
                }

                val result = orchestrator.executeTask(
                    taskDescription = task,
                    session = currentSession,
                    approvalGate = approvalGate,
                    onProgress = { handleProgress(it) },
                    onStreamChunk = { dashboard.appendStreamToken(it) },
                    onDebugLog = onDebugLog
                )
                debugLog("info", "result", "Orchestrator returned: ${result::class.simpleName}")
                reaperJob.cancel()
                handleResult(result, System.currentTimeMillis() - sessionStartMs)
            } catch (e: CancellationException) {
                debugLog("warn", "cancelled", "Task cancelled by user")
                dashboard.completeSession(0, 0, emptyList(),
                    System.currentTimeMillis() - sessionStartMs,
                    RichStreamingPanel.SessionStatus.CANCELLED)
            } catch (e: Exception) {
                LOG.error("AgentController: task failed", e)
                debugLog("error", "crash", "${e::class.simpleName}: ${e.message}", mapOf(
                    "stackTrace" to (e.stackTrace.take(5).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" })
                ))
                dashboard.appendError("Unexpected error: ${e.message}")
                dashboard.completeSession(0, 0, emptyList(),
                    System.currentTimeMillis() - sessionStartMs,
                    RichStreamingPanel.SessionStatus.FAILED)
            } finally {
                currentOrchestrator = null
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
        pendingApprovalDeferred?.complete(false)
        pendingApprovalDeferred = null
        currentOrchestrator?.cancelTask()
        ProcessRegistry.killAll()
        currentTaskJob?.cancel()
        currentTaskJob = null
        dashboard.appendStatus("Stopped.", RichStreamingPanel.StatusType.WARNING)
    }

    fun newChat() {
        pendingApprovalDeferred?.complete(false)
        pendingApprovalDeferred = null
        currentOrchestrator?.cancelTask()
        ProcessRegistry.killAll()
        currentTaskJob?.cancel()
        currentTaskJob = null
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
        session?.skillManager?.deactivateSkill()
        session?.questionManager?.clear()
        session = null
        currentPlanFile = null
        dashboard.reset()
        dashboard.focusInput()
    }

    /**
     * Wire all UI callbacks on a newly created session.
     * Called once per session from within the coroutine (off EDT).
     */
    private fun wireSessionCallbacks(currentSession: ConversationSession) {
        // Only wire callbacks once per session (not on every turn)
        if (currentSession.planManager.onPlanCreated != null) return

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

        // Re-render approved plan to JCEF so PlanSummaryCard transitions to PlanProgressWidget.
        // This fires for approval from BOTH the chat card and the plan editor tab.
        currentSession.planManager.onPlanApproved = { plan ->
            val json = PlanManager.json.encodeToString(AgentPlan.serializer(), plan)
            ApplicationManager.getApplication().invokeLater { dashboard.renderPlan(json) }
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
            dashboard.setInputLocked(true)  // Lock input while wizard is active
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
            dashboard.setInputLocked(false)  // Unlock input
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

        // Wire SkillManager callbacks
        currentSession.skillManager?.let { sm ->
            sm.onSkillActivated = { skill ->
                currentSession.contextManager.setSkillAnchor(
                    com.workflow.orchestrator.agent.api.dto.ChatMessage(
                        role = "system",
                        content = "<active_skill name=\"${skill.entry.name}\">\n${skill.content}\n</active_skill>"
                    )
                )
                try { dashboard.showSkillBanner(skill.entry.name) } catch (_: Exception) {}
            }
            sm.onSkillDeactivated = {
                currentSession.contextManager.setSkillAnchor(null)
                try { dashboard.hideSkillBanner() } catch (_: Exception) {}
            }
        }

        // Skills list — serialize to JSON for JCEF input bar
        val skillsJson = kotlinx.serialization.json.buildJsonArray {
            currentSession.skillManager?.registry?.getUserInvocableSkills()?.forEach { skill ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive(skill.name))
                    put("description", kotlinx.serialization.json.JsonPrimitive(skill.description))
                })
            }
        }.toString()
        dashboard.updateSkillsList(skillsJson)

        dashboard.setCefSkillCallbacks(
            onDismiss = { currentSession.skillManager?.deactivateSkill() }
        )

        val agentSvc = try { AgentService.getInstance(project) } catch (_: Exception) { null }

        // Wire LLM-triggered plan mode: highlights the Plan button and persists planModeEnabled
        agentSvc?.onPlanModeEnabled = { enabled ->
            planModeEnabled = enabled
            SwingUtilities.invokeLater { dashboard.setPlanMode(enabled) }
        }

        // Wire background worker completion notification
        agentSvc?.onBackgroundWorkerCompleted = { agentId, resultMessage, isError ->
            SwingUtilities.invokeLater {
                if (isError) {
                    dashboard.appendError("Background agent $agentId: $resultMessage")
                } else {
                    dashboard.appendStatus(resultMessage, RichStreamingPanel.StatusType.SUCCESS)
                }
                // Inject into LLM context so it knows about the completion
                session?.contextManager?.addMessage(
                    com.workflow.orchestrator.agent.api.dto.ChatMessage(
                        role = "system",
                        content = "<background_agent_completed agent_id=\"$agentId\">\n$resultMessage\n</background_agent_completed>"
                    )
                )
            }
        }

        // Wire sub-agent boundary card callbacks → JCEF bridge
        agentSvc?.subAgentCallbacks = AgentService.SubAgentCallbacks(
            onSpawn = { agentId, label ->
                SwingUtilities.invokeLater { dashboard.spawnSubAgent(agentId, label) }
            },
            onIteration = { agentId, iteration ->
                SwingUtilities.invokeLater { dashboard.updateSubAgentIteration(agentId, iteration) }
            },
            onToolCall = { agentId, toolName, toolArgs ->
                SwingUtilities.invokeLater { dashboard.addSubAgentToolCall(agentId, toolName, toolArgs) }
            },
            onToolResult = { agentId, toolName, result, durationMs, isError ->
                SwingUtilities.invokeLater { dashboard.updateSubAgentToolCall(agentId, toolName, result, durationMs, isError) }
            },
            onMessage = { agentId, textContent ->
                SwingUtilities.invokeLater { dashboard.updateSubAgentMessage(agentId, textContent) }
            },
            onComplete = { agentId, textContent, tokensUsed, isError ->
                SwingUtilities.invokeLater { dashboard.completeSubAgent(agentId, textContent, tokensUsed, isError) }
            }
        )

        // Wire kill-sub-agent button: JS → Kotlin → AgentService.killWorker
        dashboard.setCefKillSubAgentCallback { agentId ->
            try { agentSvc?.killWorker(agentId) } catch (_: Exception) {}
        }
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

        // Wire callbacks so plan/question/skill UI works on resumed session
        wireSessionCallbacks(loaded)

        // Wire PlanManager and QuestionManager on AgentService so tools can find them
        try {
            agentService.currentPlanManager = loaded.planManager
            agentService.currentQuestionManager = loaded.questionManager
        } catch (_: Exception) {}

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
        val maxTokens = try { AgentSettings.getInstance(project).state.maxInputTokens } catch (_: Exception) { AgentSettings.DEFAULTS.maxInputTokens }
        SwingUtilities.invokeLater { dashboard.updateProgress(progress.step, progress.tokensUsed, maxTokens) }

        val toolInfo = progress.toolCallInfo
        when {
            progress.step == "__flush_stream__" -> {
                // Text-only response: flush so the next iteration starts a fresh message
                dashboard.flushStreamBuffer()
                return
            }
            progress.step == "__completion__" && toolInfo != null -> {
                // Completion event — render as a dedicated completion card, not a tool call
                dashboard.flushStreamBuffer()
                dashboard.finalizeToolChain()
                dashboard.appendCompletionSummary(toolInfo.result, toolInfo.output)
                return
            }
            progress.step.startsWith("Calling tool:") && toolInfo != null -> {
                // Pre-execution: show tool call as RUNNING before it executes
                dashboard.flushStreamBuffer()
                dashboard.appendToolCall(toolInfo.toolCallId, toolInfo.toolName, toolInfo.args, RichStreamingPanel.ToolCallStatus.RUNNING)
            }
            progress.step.startsWith("Used tool:") && toolInfo != null -> {
                // Post-execution: update the existing RUNNING entry (don't append a new one)
                if (toolInfo.editFilePath != null && toolInfo.editOldText != null && toolInfo.editNewText != null) {
                    dashboard.appendEditDiff(toolInfo.editFilePath, toolInfo.editOldText, toolInfo.editNewText, !toolInfo.isError)
                } else {
                    val status = if (toolInfo.isError) RichStreamingPanel.ToolCallStatus.FAILED else RichStreamingPanel.ToolCallStatus.SUCCESS
                    dashboard.updateLastToolCall(status, toolInfo.result, toolInfo.durationMs, toolInfo.toolName, toolInfo.output)
                }

                // Track files in working set
                if (!toolInfo.isError) {
                    val filePath = toolInfo.editFilePath ?: run {
                        val pathMatch = AgentStringUtils.JSON_FILE_PATH_REGEX.find(toolInfo.args)
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
                dashboard.appendToolCall(toolName = progress.step.removePrefix("Used tool:").trim(), status = RichStreamingPanel.ToolCallStatus.SUCCESS)
            }
            progress.step.contains("complex") || progress.step.contains("plan") -> {
                dashboard.appendStatus(progress.step, RichStreamingPanel.StatusType.WARNING)
            }
            progress.step.contains("failed") || progress.step.contains("retry") || progress.step.contains("switching") -> {
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
                // Log trace path for discoverability (traces are per-session under sessionsDir)
                val tracesPath = project.basePath?.let {
                    session?.store?.sessionDirectory?.resolve("traces")
                        ?: ProjectIdentifier.sessionsDir(it)
                }
                if (tracesPath != null) {
                    LOG.info("AgentController: session trace at $tracesPath")
                    dashboard.appendStatus("Debug trace saved. View via notification or at: $tracesPath", RichStreamingPanel.StatusType.INFO)
                }
                // Show retry button with the original user message (skip internal nudges/injected messages)
                session?.let { s ->
                    val messages = try { s.contextManager.getMessages() } catch (_: Exception) { emptyList() }
                    val lastUserMsg = messages.lastOrNull { msg ->
                        msg.role == "user" && !isInternalNudge(msg.content)
                    }?.content
                    if (!lastUserMsg.isNullOrBlank()) {
                        dashboard.showRetryButton(lastUserMsg)
                    }
                }
            }
            is AgentResult.Cancelled -> {
                dashboard.appendStatus("Cancelled after ${result.completedSteps} steps.", RichStreamingPanel.StatusType.WARNING)
                dashboard.completeSession(0, result.completedSteps, emptyList(), durationMs, RichStreamingPanel.SessionStatus.CANCELLED)
            }
            is AgentResult.ContextRotated -> {
                dashboard.appendStatus(
                    "Context full — rotating to fresh session. ${result.summary}",
                    RichStreamingPanel.StatusType.WARNING
                )
                dashboard.completeSession(result.tokensUsed, 0, emptyList(), durationMs, RichStreamingPanel.SessionStatus.CANCELLED)
            }
        }
    }

    private fun buildApprovalDescription(toolName: String, params: Map<String, Any?>): String {
        // Use LLM-provided description if available (like Claude Code's Bash description param)
        // Some tools use "action_description" to avoid collision with domain "description" (e.g., PR body)
        val llmDescription = (params["description"] as? String)?.takeIf { it.isNotBlank() }
            ?: (params["action_description"] as? String)?.takeIf { it.isNotBlank() }
        if (llmDescription != null) return llmDescription

        return buildString {
            append(toolName)
            val path = params["path"] as? String ?: params["file_path"] as? String
            if (path != null) append(" — $path")
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun buildApprovalMetadata(toolName: String, params: Map<String, Any?>): String {
        val items = mutableListOf<Map<String, String>>()

        when (toolName) {
            "edit_file" -> {
                params["path"]?.let { items.add(mapOf("key" to "File", "value" to it.toString())) }
            }
            "run_command" -> {
                params["command"]?.let { items.add(mapOf("key" to "Command", "value" to it.toString())) }
                params["working_directory"]?.let { items.add(mapOf("key" to "Directory", "value" to it.toString())) }
            }
            "read_file" -> {
                params["path"]?.let { items.add(mapOf("key" to "File", "value" to it.toString())) }
                val from = params["from_line"] ?: params["offset"]
                val to = params["to_line"] ?: params["limit"]
                if (from != null || to != null) items.add(mapOf("key" to "Lines", "value" to "${from ?: "start"}-${to ?: "end"}"))
            }
            "search_code" -> {
                params["pattern"]?.let { items.add(mapOf("key" to "Pattern", "value" to it.toString())) }
                params["path"]?.let { items.add(mapOf("key" to "Scope", "value" to it.toString())) }
            }
            "glob_files" -> {
                params["pattern"]?.let { items.add(mapOf("key" to "Pattern", "value" to it.toString())) }
                params["path"]?.let { items.add(mapOf("key" to "Path", "value" to it.toString())) }
            }
            "refactor_rename" -> {
                params["old_name"]?.let { items.add(mapOf("key" to "Old", "value" to it.toString())) }
                params["new_name"]?.let { items.add(mapOf("key" to "New", "value" to it.toString())) }
                params["path"]?.let { items.add(mapOf("key" to "Scope", "value" to it.toString())) }
            }
            "jira_transition" -> {
                params["issue_key"]?.let { items.add(mapOf("key" to "Ticket", "value" to it.toString())) }
                params["transition_name"]?.let { items.add(mapOf("key" to "Transition", "value" to it.toString())) }
            }
            "jira_comment" -> {
                params["issue_key"]?.let { items.add(mapOf("key" to "Ticket", "value" to it.toString())) }
                (params["body"] as? String)?.let { items.add(mapOf("key" to "Comment", "value" to truncate(it, 80))) }
            }
            "bamboo_trigger_build" -> {
                params["plan_key"]?.let { items.add(mapOf("key" to "Plan", "value" to it.toString())) }
            }
            "bitbucket_create_pr" -> {
                params["title"]?.let { items.add(mapOf("key" to "Title", "value" to it.toString())) }
                params["from_branch"]?.let { items.add(mapOf("key" to "From", "value" to it.toString())) }
                params["to_branch"]?.let { items.add(mapOf("key" to "To", "value" to it.toString())) }
            }
            "bitbucket_merge_pr" -> {
                params["pr_id"]?.let { items.add(mapOf("key" to "PR", "value" to "#${it}")) }
                params["strategy"]?.let { items.add(mapOf("key" to "Strategy", "value" to it.toString())) }
            }
            else -> {
                // Generic: show all params as metadata
                params.forEach { (key, value) ->
                    if (value != null) {
                        items.add(mapOf("key" to key, "value" to truncate(value.toString(), 100)))
                    }
                }
            }
        }

        // Serialize to JSON using buildJsonArray
        val jsonArray = buildJsonArray {
            items.forEach { map ->
                add(buildJsonObject {
                    map.forEach { (k, v) -> put(k, v) }
                })
            }
        }
        return jsonArray.toString()
    }

    private fun truncate(s: String, maxLen: Int): String =
        if (s.length <= maxLen) s else s.take(maxLen - 3) + "..."

    private fun buildUnifiedDiff(toolName: String, params: Map<String, Any?>): String? {
        if (toolName != "edit_file") return null
        val path = params["path"] as? String ?: return null
        val oldString = params["old_string"] as? String ?: return null
        val newString = params["new_string"] as? String ?: return null

        val oldLines = oldString.lines()
        val newLines = newString.lines()

        return buildString {
            appendLine("--- a/$path")
            appendLine("+++ b/$path")
            appendLine("@@ -1,${oldLines.size} +1,${newLines.size} @@")
            oldLines.forEach { appendLine("-$it") }
            newLines.forEach { appendLine("+$it") }
        }.trimEnd()
    }

    private fun showApprovalDialog(description: String, riskLevel: RiskLevel): ApprovalResult {
        // Session-level auto-approve: skip dialog for MEDIUM or lower risk after user opted in
        if (sessionAutoApprove && riskLevel <= RiskLevel.MEDIUM) {
            dashboard.appendStatus("Auto-approved: $description", RichStreamingPanel.StatusType.SUCCESS)
            return ApprovalResult.Approved
        }

        var result: ApprovalResult = ApprovalResult.Rejected()
        ApplicationManager.getApplication().invokeAndWait {
            when {
                // Command execution — show CommandApprovalDialog with command text
                description.contains("run_command") -> {
                    val cmdMatch = Regex("run_command\\((.+?)\\)").find(description)
                    val dialog = CommandApprovalDialog(project, cmdMatch?.groupValues?.get(1) ?: description, project.basePath ?: ".", riskLevel.name)
                    dialog.show()
                    result = if (dialog.approved) ApprovalResult.Approved else ApprovalResult.Rejected()
                    if (dialog.allowAll) sessionAutoApprove = true
                }

                // File edit — try to show diff via EditApprovalDialog
                description.contains("edit_file") -> {
                    try {
                        val pathMatch = AgentStringUtils.JSON_FILE_PATH_REGEX.find(description)
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
                            result = if (dialog.approved) ApprovalResult.Approved else ApprovalResult.Rejected()
                        } else {
                            // Parsing partial — show what we could extract
                            val fileInfo = filePath ?: "unknown file"
                            result = showGenericApprovalDialog(
                                "The agent wants to edit $fileInfo.\n\nCould not parse the full edit details.\nYou can undo this change after it's applied.",
                                "Agent File Edit"
                            )
                        }
                    } catch (e: Exception) {
                        result = showGenericApprovalDialog(
                            "The agent wants to edit a file.\n\nError parsing edit details: ${e.message?.take(100)}\nYou can undo this change after it's applied.",
                            "Agent File Edit"
                        )
                    }
                }

                // Any other tool at MEDIUM+ risk — show the actual description
                riskLevel >= RiskLevel.MEDIUM -> {
                    // Extract the tool name from description for a meaningful message
                    val toolName = description.substringBefore("(").trim()
                    result = showGenericApprovalDialog(
                        "The agent wants to perform:\n\n$toolName\n\n${description.take(300)}\n\nRisk level: ${riskLevel.name}",
                        "Agent Action Approval"
                    )
                }

                // LOW risk — simple yes/no
                else -> {
                    val answer = Messages.showYesNoDialog(project,
                        "The agent wants to perform:\n\n$description\n\nRisk level: ${riskLevel.name}",
                        "Agent Action Approval", "Allow", "Block", Messages.getQuestionIcon())
                    result = if (answer == Messages.YES) ApprovalResult.Approved else ApprovalResult.Rejected()
                }
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
        // Traces are per-session: {sessionsDir}/{sessionId}/traces/
        // Open the current session's traces dir if a session is active, otherwise the sessions root
        val tracesDir = session?.store?.sessionDirectory?.resolve("traces")
            ?: ProjectIdentifier.sessionsDir(basePath)
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
        // Traces are per-session: {sessionsDir}/{sessionId}/traces/trace.jsonl
        // Prefer current session's trace; fall back to scanning all session directories
        val latest = session?.store?.sessionDirectory?.resolve("traces")
            ?.listFiles()?.filter { it.extension == "jsonl" }?.maxByOrNull { it.lastModified() }
            ?: ProjectIdentifier.sessionsDir(basePath)
                .walkTopDown()
                .filter { it.name == "trace.jsonl" }
                .maxByOrNull { it.lastModified() }

        if (latest != null) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(latest)
            if (vf != null) {
                SwingUtilities.invokeLater {
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            }
        } else {
            val sessionsDir = ProjectIdentifier.sessionsDir(basePath)
            dashboard.appendStatus("No trace files found. Run a task first. Expected location: $sessionsDir/{sessionId}/traces/trace.jsonl", RichStreamingPanel.StatusType.INFO)
        }
    }

    /**
     * Show a generic approval dialog with Allow / Block / Allow All options.
     * Used as fallback when specialized dialogs (CommandApproval, EditApproval) can't be shown.
     */
    private fun showGenericApprovalDialog(message: String, title: String): ApprovalResult {
        val answer = Messages.showYesNoCancelDialog(
            project,
            message,
            title,
            "Allow", "Block", "Allow All (This Session)",
            Messages.getQuestionIcon()
        )
        return when (answer) {
            Messages.YES -> ApprovalResult.Approved
            Messages.CANCEL -> { sessionAutoApprove = true; ApprovalResult.Approved }
            else -> ApprovalResult.Rejected()
        }
    }

    /**
     * Fetch available models from Sourcegraph and send them to the chat UI dropdown.
     */
    private fun loadModelList() {
        scope.launch {
            try {
                val settings = AgentSettings.getInstance(project)
                val connections = com.workflow.orchestrator.core.settings.ConnectionSettings.getInstance()
                val credentialStore = com.workflow.orchestrator.core.auth.CredentialStore()
                val url = connections.state.sourcegraphUrl
                val token = credentialStore.getToken(com.workflow.orchestrator.core.model.ServiceType.SOURCEGRAPH)
                if (url.isBlank() || token.isNullOrBlank()) return@launch

                val client = com.workflow.orchestrator.agent.api.SourcegraphChatClient(
                    baseUrl = url.trimEnd('/'),
                    tokenProvider = { token },
                    model = "",
                )
                val result = client.listModels()
                if (result is com.workflow.orchestrator.core.model.ApiResult.Success) {
                    val models = result.data.data
                        .sortedWith(compareBy<com.workflow.orchestrator.agent.api.dto.ModelInfo> { it.tier }.thenBy { it.displayName })
                    val json = kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(
                            kotlinx.serialization.serializer<ModelListEntry>()
                        ),
                        models.map { ModelListEntry(id = it.id, name = it.displayName, description = it.displayProvider) }
                    )
                    SwingUtilities.invokeLater {
                        dashboard.updateModelList(json)
                        // Also set the display name for the current model
                        val currentId = settings.state.sourcegraphChatModel ?: ""
                        val currentModel = models.find { it.id == currentId }
                        if (currentModel != null) {
                            dashboard.setModelName(currentModel.displayName)
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.debug("AgentController: failed to load model list: ${e.message}")
            }
        }
    }

    @kotlinx.serialization.Serializable
    private data class ModelListEntry(val id: String, val name: String, val description: String)

    /**
     * Show a notification with action to view the trace file.
     */
    /** Check if a message is an internally-injected nudge/warning (not an actual user message). */
    private fun isInternalNudge(content: String?): Boolean {
        if (content == null) return false
        return content.startsWith("You responded without calling any tools") ||
            content.startsWith("You MUST call attempt_completion") ||
            content.startsWith("IMPORTANT: Your last") ||
            content.startsWith("Your previous response indicated tool calls") ||
            content.startsWith("<system_warning>") ||
            content.startsWith("<backpressure") ||
            content.startsWith("<self_correction")
    }

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
