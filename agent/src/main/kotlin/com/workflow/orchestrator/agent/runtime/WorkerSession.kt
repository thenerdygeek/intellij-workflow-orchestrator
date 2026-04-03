package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.EventSourcedContextBridge
import com.workflow.orchestrator.agent.security.OutputValidator
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Result of a worker session execution.
 */
data class WorkerResult(
    val content: String,
    val summary: String,
    val tokensUsed: Int,
    val artifacts: List<String> = emptyList(),
    val isError: Boolean = false
)

/**
 * Executes a single worker lifecycle using the ReAct (Reason + Act) loop.
 *
 * Loop: send message to brain with tools ->
 *   if tool_calls in response, execute tools, add results, repeat ->
 *   if no tool_calls, return final response.
 *
 * Max iterations: 10 to prevent infinite loops.
 *
 * Supports optional transcript recording for resume capability.
 * Pass a [transcriptStore] and [agentId] to persist all messages to JSONL.
 */
class WorkerSession(
    private val maxIterations: Int = 10,
    private val parentJob: kotlinx.coroutines.Job? = null,
    private val transcriptStore: WorkerTranscriptStore? = null,
    val agentId: String = WorkerTranscriptStore.generateAgentId(),
    private val uiCallbacks: AgentService.SubAgentCallbacks? = null,
    private val messageBus: WorkerMessageBus? = null,
    private val fileOwnership: FileOwnershipRegistry? = null
) {
    companion object {
        private val LOG = Logger.getInstance(WorkerSession::class.java)
        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * Record a message to the transcript store if available.
     */
    private fun recordMessage(role: String, content: String?, toolCallId: String? = null) {
        transcriptStore?.appendMessage(agentId, WorkerTranscriptStore.TranscriptMessage(
            role = role,
            content = content,
            toolCallId = toolCallId
        ))
    }

    /**
     * Execute a worker session with the ReAct loop.
     *
     * @param workerType The type of worker (ANALYZER, CODER, etc.)
     * @param systemPrompt The system prompt for this worker
     * @param task The task description/instructions
     * @param tools Map of tool name to AgentTool (available tools for this worker)
     * @param toolDefinitions The tool definitions to send to the LLM
     * @param brain The LLM brain
     * @param bridge Event-sourced context bridge for conversation management
     * @param project The IntelliJ project (needed for tool execution)
     * @return WorkerResult with the final response and metadata
     */
    suspend fun execute(
        workerType: WorkerType,
        systemPrompt: String,
        task: String,
        tools: Map<String, AgentTool>,
        toolDefinitions: List<ToolDefinition>,
        brain: LlmBrain,
        bridge: EventSourcedContextBridge,
        project: Project,
        maxOutputTokens: Int? = null
    ): WorkerResult {
        LOG.info("WorkerSession: starting $workerType worker for task: ${task.take(100)}")

        // Initialize context with system prompt and task
        bridge.addSystemPrompt(systemPrompt)
        recordMessage("system", systemPrompt)

        bridge.addUserMessage(task)
        recordMessage("user", task)

        return withContext(WorkerContext(
            agentId = agentId,
            workerType = workerType,
            messageBus = messageBus,
            fileOwnership = fileOwnership
        )) {
            runReactLoop(tools, toolDefinitions, brain, bridge, project, maxOutputTokens)
        }
    }

    /**
     * Execute the ReAct loop from an already-populated bridge.
     * Used for resume — the context already contains the previous conversation.
     */
    suspend fun executeFromContext(
        tools: Map<String, AgentTool>,
        toolDefinitions: List<ToolDefinition>,
        brain: LlmBrain,
        bridge: EventSourcedContextBridge,
        project: Project,
        maxOutputTokens: Int? = null
    ): WorkerResult {
        LOG.info("WorkerSession: resuming agent $agentId from existing context")
        return withContext(WorkerContext(
            agentId = agentId,
            workerType = WorkerType.ORCHESTRATOR,
            messageBus = messageBus,
            fileOwnership = fileOwnership
        )) {
            runReactLoop(tools, toolDefinitions, brain, bridge, project, maxOutputTokens)
        }
    }

    /**
     * Core ReAct loop shared by [execute] and [executeFromContext].
     */
    private suspend fun runReactLoop(
        tools: Map<String, AgentTool>,
        toolDefinitions: List<ToolDefinition>,
        brain: LlmBrain,
        bridge: EventSourcedContextBridge,
        project: Project,
        maxOutputTokens: Int? = null
    ): WorkerResult {
        var totalTokensUsed = 0
        val allArtifacts = mutableListOf<String>()
        var consecutiveNoToolResponses = 0
        val loopGuard = LoopGuard()

        for (iteration in 1..maxIterations) {
            if (parentJob?.isActive == false) {
                LOG.info("WorkerSession: cancelled by parent at iteration $iteration")
                return WorkerResult(
                    content = "Worker cancelled by parent session.",
                    summary = "Cancelled at iteration $iteration",
                    tokensUsed = totalTokensUsed,
                    artifacts = allArtifacts,
                    isError = true
                )
            }

            // Drain pending messages from parent orchestrator
            if (messageBus != null) {
                val pending = messageBus.drain(agentId)
                if (pending.isNotEmpty()) {
                    val formatted = pending.joinToString("\n") { msg ->
                        "<parent_message type=\"${msg.type.name.lowercase()}\" timestamp=\"${msg.timestamp}\">\n" +
                        "${msg.content}\n" +
                        "</parent_message>"
                    }
                    bridge.addSystemMessage(formatted)
                    recordMessage("system", formatted)
                    LOG.info("WorkerSession: injected ${pending.size} parent messages at iteration $iteration")
                }
            }

            LOG.info("WorkerSession: iteration $iteration/$maxIterations")
            uiCallbacks?.onIteration?.invoke(agentId, iteration)

            val messages = bridge.getMessages()
            val activeToolDefs = if (tools.isNotEmpty()) toolDefinitions else null

            val result = brain.chat(messages, activeToolDefs, maxOutputTokens)

            when (result) {
                is ApiResult.Success -> {
                    val response = result.data
                    val usage = response.usage
                    if (usage != null) {
                        totalTokensUsed += usage.totalTokens
                        // Reconcile token count with API
                        if (usage.promptTokens > 0) {
                            bridge.updateTokensFromUsage(usage.promptTokens)
                        }
                    }

                    val choice = response.choices.firstOrNull() ?: break
                    val message = choice.message
                    val toolCalls = message.toolCalls

                    // Add assistant message to context
                    if (!toolCalls.isNullOrEmpty()) {
                        bridge.addAssistantToolCalls(message)
                    } else {
                        bridge.addAssistantMessage(message)
                    }
                    recordMessage("assistant", message.content)

                    if (toolCalls.isNullOrEmpty()) {
                        val content = message.content ?: ""
                        consecutiveNoToolResponses++

                        // First no-tool response with iterations remaining: nudge to use worker_complete
                        if (consecutiveNoToolResponses == 1 && iteration < maxIterations) {
                            LOG.info("WorkerSession: no tool calls at iteration $iteration — nudging to use worker_complete")
                            val nudge = "You responded without calling any tools. " +
                                "If you have completed the task, call worker_complete with the COMPLETE output " +
                                "of your work — the orchestrator only sees your worker_complete result, not your " +
                                "tool call history. If you have more work to do, make your next tool call now."
                            bridge.addUserMessage(nudge)
                            recordMessage("user", nudge)
                            continue
                        }

                        // Second consecutive no-tool response (or last iteration): force-accept escape hatch
                        LOG.warn("WorkerSession: force-accepting after $consecutiveNoToolResponses no-tool responses at iteration $iteration")
                        val securityIssues = OutputValidator.validate(content)
                        if (securityIssues.isNotEmpty()) {
                            LOG.warn("WorkerSession: output validation flagged: ${securityIssues.joinToString()}")
                        }
                        val summary = if (content.length > 200) content.take(200) + "..." else content
                        LOG.info("WorkerSession: completed (force-accept) after $iteration iterations")
                        uiCallbacks?.onMessage?.invoke(agentId, content)
                        return WorkerResult(
                            content = content,
                            summary = summary,
                            tokensUsed = totalTokensUsed,
                            artifacts = allArtifacts,
                            isError = false
                        )
                    }

                    consecutiveNoToolResponses = 0

                    // Doom loop detection: check each tool call before execution, skip if detected
                    val doomSkipped = mutableSetOf<String>()
                    for (tc in toolCalls) {
                        val doomMessage = loopGuard.checkDoomLoop(tc.function.name, tc.function.arguments)
                        if (doomMessage != null) {
                            bridge.addToolError(tc.id, doomMessage, "Doom loop detected — execution skipped", tc.function.name)
                            doomSkipped.add(tc.id)
                        }
                    }

                    // Execute tool calls and add results
                    for (toolCall in toolCalls) {
                        if (toolCall.id in doomSkipped) continue

                        val toolName = toolCall.function.name

                        if (parentJob?.isActive == false) {
                            LOG.info("WorkerSession: cancelled before tool '$toolName' execution")
                            return WorkerResult("Cancelled", "Cancelled", totalTokensUsed, allArtifacts, isError = true)
                        }

                        val tool = tools[toolName]

                        if (tool == null) {
                            val availableTools = tools.keys.joinToString(", ")
                            val errorContent = "Error: Tool '$toolName' is not available. Available tools: $availableTools. Please use one of these."
                            bridge.addToolError(
                                toolCallId = toolCall.id,
                                content = errorContent,
                                summary = "Tool not found: $toolName",
                                toolName = toolName
                            )
                            recordMessage("tool", errorContent, toolCallId = toolCall.id)
                            continue
                        }

                        // Pre-edit read enforcement: block edit_file if file not read in this session
                        if (toolName == "edit_file") {
                            val editPathMatch = Regex(""""path"\s*:\s*"([^"]+)"""").find(toolCall.function.arguments)
                            val editPath = editPathMatch?.groupValues?.get(1)
                            if (editPath != null) {
                                val preEditWarning = loopGuard.checkPreEditRead(editPath)
                                if (preEditWarning != null) {
                                    bridge.addToolError(toolCall.id, preEditWarning, "Edit blocked: file not read", toolCall.function.name)
                                    recordMessage("tool", preEditWarning, toolCallId = toolCall.id)
                                    continue
                                }
                            }
                        }

                        uiCallbacks?.onToolCall?.invoke(agentId, toolName, toolCall.function.arguments)
                        val toolStartMs = System.currentTimeMillis()

                        try {
                            val params = json.decodeFromString<JsonObject>(toolCall.function.arguments)
                            val toolResult = tool.execute(params, project)
                            val toolDurationMs = System.currentTimeMillis() - toolStartMs

                            uiCallbacks?.onToolResult?.invoke(agentId, toolName, toolResult.content, toolDurationMs, toolResult.isError)

                            if (toolResult.isError) {
                                bridge.addToolError(
                                    toolCallId = toolCall.id,
                                    content = toolResult.content,
                                    summary = toolResult.summary,
                                    toolName = toolName
                                )
                            } else {
                                bridge.addToolResult(
                                    toolCallId = toolCall.id,
                                    content = toolResult.content,
                                    summary = toolResult.summary,
                                    toolName = toolName
                                )
                            }
                            recordMessage("tool", toolResult.content, toolCallId = toolCall.id)

                            allArtifacts.addAll(toolResult.artifacts)

                            // Clear file read tracking after successful edit so agent can re-read after edit
                            if (toolName == "edit_file" && !toolResult.isError) {
                                val editPathMatch = Regex(""""path"\s*:\s*"([^"]+)"""").find(toolCall.function.arguments)
                                editPathMatch?.groupValues?.get(1)?.let { loopGuard.clearFileRead(it) }
                            }

                            // Exit early when worker signals completion via worker_complete
                            if (toolResult.isCompletion) {
                                LOG.info("WorkerSession: worker_complete called at iteration $iteration — exiting loop")
                                uiCallbacks?.onMessage?.invoke(agentId, toolResult.content)
                                return WorkerResult(
                                    content = toolResult.content,
                                    summary = toolResult.summary,
                                    tokensUsed = totalTokensUsed,
                                    artifacts = allArtifacts,
                                    isError = false
                                )
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e // Never swallow CancellationException — propagate for structured concurrency
                        } catch (e: Exception) {
                            val toolDurationMs = System.currentTimeMillis() - toolStartMs
                            LOG.warn("WorkerSession: tool '$toolName' failed", e)
                            val errorContent = "Error executing tool '$toolName': ${e.message}"
                            uiCallbacks?.onToolResult?.invoke(agentId, toolName, errorContent, toolDurationMs, true)
                            bridge.addToolError(
                                toolCallId = toolCall.id,
                                content = errorContent,
                                summary = "Tool error: $toolName",
                                toolName = toolName
                            )
                            recordMessage("tool", errorContent, toolCallId = toolCall.id)
                        }
                    }
                }

                is ApiResult.Error -> {
                    LOG.warn("WorkerSession: LLM call failed: ${result.message}")
                    return WorkerResult(
                        content = "Error: LLM call failed: ${result.message}",
                        summary = "Failed: ${result.message}",
                        tokensUsed = totalTokensUsed,
                        artifacts = allArtifacts,
                        isError = true
                    )
                }
            }
        }

        LOG.warn("WorkerSession: reached max iterations ($maxIterations)")
        return WorkerResult(
            content = "Reached maximum iterations ($maxIterations) without completing",
            summary = "Incomplete: max iterations reached",
            tokensUsed = totalTokensUsed,
            artifacts = allArtifacts,
            isError = true
        )
    }
}
