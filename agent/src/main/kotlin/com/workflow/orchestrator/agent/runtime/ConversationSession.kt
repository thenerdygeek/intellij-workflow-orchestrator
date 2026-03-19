package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.FunctionCall
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.context.RepoMapGenerator
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.context.WorkingSet
import com.workflow.orchestrator.agent.orchestrator.PromptAssembler
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.service.GlobalSessionIndex
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.DynamicToolSelector
import java.util.UUID

/**
 * Long-lived conversation session that persists across user messages.
 *
 * Owns the ContextManager — the core fix for multi-turn conversation.
 * Instead of creating a new ContextManager per message, one session
 * keeps the conversation alive until the user clicks "New Chat".
 *
 * Lifecycle:
 *   User sends first message -> ConversationSession.create()
 *   User sends follow-up -> session.addUserMessage() + reuse contextManager
 *   User clicks "New Chat" -> session marked completed, new session created
 *   IDE restarts -> session loaded from JSONL (Task 2)
 */
class ConversationSession private constructor(
    val sessionId: String,
    val contextManager: ContextManager,
    val brain: LlmBrain,
    val toolDefinitions: List<ToolDefinition>,
    val tools: Map<String, AgentTool>,
    val systemPrompt: String,
    val reservedTokens: Int,
    val createdAt: Long,
    var title: String = "",
    var lastMessageAt: Long = createdAt,
    var messageCount: Int = 0,
    var status: String = "active" // "active", "completed", "interrupted", "failed"
) {
    /** Whether the system prompt has been added to context yet. */
    var initialized: Boolean = false
        internal set

    /** Tracks files the agent has recently read or edited. LRU cache with 10 file limit. */
    val workingSet: WorkingSet = WorkingSet()

    /** Store for JSONL persistence. */
    val store: ConversationStore = ConversationStore(sessionId)

    /** Tracks how many messages have already been persisted to avoid duplicates. */
    var persistedMessageCount: Int = 0

    /**
     * Initialize the session's context with system prompt.
     * Called once on first message. Subsequent messages skip this.
     */
    fun initialize() {
        if (initialized) return
        contextManager.addMessage(ChatMessage(role = "system", content = systemPrompt))
        initialized = true
    }

    /**
     * Record that a user message was sent in this session.
     * The actual message is added to contextManager by SingleAgentSession.
     */
    fun recordUserMessage(message: String) {
        if (title.isBlank()) title = message.take(100)
        lastMessageAt = System.currentTimeMillis()
        messageCount++
    }

    /**
     * Mark session as completed (successful or failed).
     * Also updates the global session index.
     */
    fun markCompleted(success: Boolean) {
        status = if (success) "completed" else "failed"
        try {
            GlobalSessionIndex.getInstance().updateSession(sessionId) { it.copy(status = status) }
        } catch (_: Exception) { /* best effort — index may not be available in tests */ }
    }

    /**
     * Convert a ChatMessage to PersistedMessage and append to JSONL.
     */
    fun persistMessage(message: ChatMessage) {
        val persisted = PersistedMessage(
            role = message.role,
            content = message.content,
            toolCalls = message.toolCalls?.map { tc ->
                PersistedToolCall(tc.id, tc.function.name, tc.function.arguments)
            },
            toolCallId = message.toolCallId
        )
        store.saveMessage(persisted)
    }

    /**
     * Persist only messages added since the last persist call.
     * Safe to call repeatedly — tracks offset via [persistedMessageCount].
     */
    fun persistNewMessages() {
        val allMessages = contextManager.getMessages()
        val newMessages = allMessages.drop(persistedMessageCount)
        for (msg in newMessages) {
            persistMessage(msg)
        }
        persistedMessageCount = allMessages.size
    }

    /** Save a checkpoint for this session (call after each tool execution). */
    fun saveCheckpoint(iteration: Int, tokensUsed: Int, lastToolCall: String?, rollbackCheckpointId: String?) {
        try {
            SessionCheckpoint.save(
                SessionCheckpoint(
                    sessionId = sessionId,
                    phase = "executing",
                    iteration = iteration,
                    tokensUsed = tokensUsed,
                    lastToolCall = lastToolCall,
                    touchedFiles = emptyList(),
                    rollbackCheckpointId = rollbackCheckpointId,
                    timestamp = System.currentTimeMillis()
                ),
                store.sessionDirectory
            )
        } catch (_: Exception) { /* best effort */ }
    }

    /** Delete checkpoint when session completes normally. */
    fun deleteCheckpoint() {
        try { SessionCheckpoint.delete(store.sessionDirectory) } catch (_: Exception) {}
    }

    /**
     * Save session metadata to disk.
     * Call after each turn so sessions are discoverable even if the IDE crashes.
     * Also updates the global session index for cross-project history.
     */
    fun saveMetadata(projectName: String, projectPath: String, model: String) {
        store.saveMetadata(SessionMetadata(
            sessionId = sessionId,
            projectName = projectName,
            projectPath = projectPath,
            title = title,
            model = model,
            createdAt = createdAt,
            lastMessageAt = lastMessageAt,
            messageCount = messageCount,
            status = status
        ))
        // Also update the global index
        try {
            GlobalSessionIndex.getInstance().updateSession(sessionId) { entry ->
                entry.copy(
                    title = title,
                    lastMessageAt = lastMessageAt,
                    messageCount = messageCount,
                    status = status
                )
            }
        } catch (_: Exception) { /* best effort — index may not be available in tests */ }
    }

    companion object {
        /**
         * Create a new conversation session with fresh context.
         * Called on first message or "New Chat".
         */
        fun create(project: Project, agentService: AgentService): ConversationSession {
            val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
            val maxInputTokens = settings?.state?.maxInputTokens ?: 150_000

            // Calculate tool definitions and their token overhead
            val allTools = agentService.toolRegistry.allTools().associateBy { it.name }
            val allToolDefs = agentService.toolRegistry.allTools().map { it.toToolDefinition() }
            val toolDefTokens = TokenEstimator.estimateToolDefinitions(allToolDefs)

            // Generate repo map
            val repoMap = try {
                RepoMapGenerator.generate(project, maxTokens = 1500)
            } catch (_: Exception) { "" }

            // Build system prompt
            val promptAssembler = PromptAssembler(agentService.toolRegistry)
            val systemPrompt = promptAssembler.buildSingleAgentPrompt(
                projectName = project.name,
                projectPath = project.basePath,
                repoMapContext = repoMap.ifBlank { null }
            )
            val systemPromptTokens = TokenEstimator.estimate(systemPrompt)

            // Calculate reserved tokens
            val reservedTokens = toolDefTokens + systemPromptTokens + 200

            // Create context manager with reserved tokens
            val contextManager = ContextManager(
                maxInputTokens = maxInputTokens,
                reservedTokens = reservedTokens
            )

            val session = ConversationSession(
                sessionId = UUID.randomUUID().toString().take(12),
                contextManager = contextManager,
                brain = agentService.brain,
                toolDefinitions = allToolDefs,
                tools = allTools,
                systemPrompt = systemPrompt,
                reservedTokens = reservedTokens,
                createdAt = System.currentTimeMillis()
            )

            // Register in the global session index for cross-project history
            try {
                GlobalSessionIndex.getInstance().addSession(GlobalSessionIndex.SessionEntry(
                    sessionId = session.sessionId,
                    projectName = project.name,
                    projectPath = project.basePath ?: "",
                    title = "",
                    createdAt = session.createdAt,
                    lastMessageAt = session.createdAt,
                    messageCount = 0,
                    status = "active"
                ))
            } catch (_: Exception) { /* best effort — index may not be available in tests */ }

            return session
        }

        /**
         * Load a session from disk by replaying persisted messages into a fresh context.
         *
         * Creates the full session infrastructure (brain, tools, system prompt) from
         * the current project + agentService, then replays the stored messages so the
         * LLM sees the full conversation history on the next turn.
         *
         * Returns null if metadata or messages are missing/corrupt.
         */
        fun load(sessionId: String, project: Project, agentService: AgentService): ConversationSession? {
            val store = ConversationStore(sessionId)
            val metadata = store.loadMetadata() ?: return null
            val messages = store.loadMessages()
            if (messages.isEmpty()) return null

            // Create a fresh session to get brain, tools, system prompt
            val session = create(project, agentService)

            // Override identity and timestamps with persisted values
            // Note: sessionId from create() is different — we need to construct
            // with the original sessionId. Use the fresh session's infra but
            // build a new ConversationSession with the original ID.
            val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
            val maxInputTokens = settings?.state?.maxInputTokens ?: 150_000

            val loaded = ConversationSession(
                sessionId = sessionId,
                contextManager = ContextManager(
                    maxInputTokens = maxInputTokens,
                    reservedTokens = session.reservedTokens
                ),
                brain = session.brain,
                toolDefinitions = session.toolDefinitions,
                tools = session.tools,
                systemPrompt = session.systemPrompt,
                reservedTokens = session.reservedTokens,
                createdAt = metadata.createdAt,
                title = metadata.title,
                lastMessageAt = metadata.lastMessageAt,
                messageCount = metadata.messageCount,
                status = metadata.status
            )

            // Replay messages into context manager
            for (msg in messages) {
                val chatMsg = ChatMessage(
                    role = msg.role,
                    content = msg.content,
                    toolCalls = msg.toolCalls?.map { tc ->
                        ToolCall(tc.id, function = FunctionCall(tc.name, tc.arguments))
                    },
                    toolCallId = msg.toolCallId
                )
                loaded.contextManager.addMessage(chatMsg)
            }

            loaded.initialized = true // system prompt already in replayed messages
            loaded.persistedMessageCount = messages.size // all messages already on disk

            return loaded
        }
    }
}
