package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.context.RepoMapGenerator
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.orchestrator.PromptAssembler
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.AgentTool
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
        private set

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
     */
    fun markCompleted(success: Boolean) {
        status = if (success) "completed" else "failed"
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

            return ConversationSession(
                sessionId = UUID.randomUUID().toString().take(12),
                contextManager = contextManager,
                brain = agentService.brain,
                toolDefinitions = allToolDefs,
                tools = allTools,
                systemPrompt = systemPrompt,
                reservedTokens = reservedTokens,
                createdAt = System.currentTimeMillis()
            )
        }
    }
}
