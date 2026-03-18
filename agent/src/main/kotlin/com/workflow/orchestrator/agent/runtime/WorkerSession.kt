package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.security.OutputValidator
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Result of a worker session execution.
 */
data class WorkerResult(
    val content: String,
    val summary: String,
    val tokensUsed: Int,
    val artifacts: List<String> = emptyList()
)

/**
 * Executes a single worker lifecycle using the ReAct (Reason + Act) loop.
 *
 * Loop: send message to brain with tools ->
 *   if tool_calls in response, execute tools, add results, repeat ->
 *   if no tool_calls, return final response.
 *
 * Max iterations: 10 to prevent infinite loops.
 */
class WorkerSession(
    private val maxIterations: Int = 10
) {
    companion object {
        private val LOG = Logger.getInstance(WorkerSession::class.java)
        private val json = Json { ignoreUnknownKeys = true }
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
     * @param contextManager Manages conversation history with compression
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
        contextManager: ContextManager,
        project: Project
    ): WorkerResult {
        LOG.info("WorkerSession: starting $workerType worker for task: ${task.take(100)}")

        // Initialize context with system prompt and task
        contextManager.addMessage(ChatMessage(role = "system", content = systemPrompt))
        contextManager.addMessage(ChatMessage(role = "user", content = task))

        var totalTokensUsed = 0
        val allArtifacts = mutableListOf<String>()

        for (iteration in 1..maxIterations) {
            LOG.info("WorkerSession: iteration $iteration/$maxIterations")

            val messages = contextManager.getMessages()
            val activeToolDefs = if (tools.isNotEmpty()) toolDefinitions else null

            val result = brain.chat(messages, activeToolDefs)

            when (result) {
                is ApiResult.Success -> {
                    val response = result.data
                    val usage = response.usage
                    if (usage != null) {
                        totalTokensUsed += usage.totalTokens
                    }

                    val choice = response.choices.firstOrNull() ?: break
                    val message = choice.message
                    val toolCalls = message.toolCalls

                    // Add assistant message to context
                    contextManager.addAssistantMessage(message)

                    if (toolCalls.isNullOrEmpty()) {
                        // No tool calls — final response
                        val content = message.content ?: ""

                        // Validate output for sensitive data before returning
                        val securityIssues = OutputValidator.validate(content)
                        if (securityIssues.isNotEmpty()) {
                            LOG.warn("WorkerSession: output validation flagged: ${securityIssues.joinToString()}")
                        }

                        val summary = if (content.length > 200) content.take(200) + "..." else content
                        LOG.info("WorkerSession: completed after $iteration iterations")
                        return WorkerResult(
                            content = content,
                            summary = summary,
                            tokensUsed = totalTokensUsed,
                            artifacts = allArtifacts
                        )
                    }

                    // Execute tool calls and add results
                    for (toolCall in toolCalls) {
                        val toolName = toolCall.function.name
                        val tool = tools[toolName]

                        if (tool == null) {
                            contextManager.addToolResult(
                                toolCallId = toolCall.id,
                                content = "Error: Tool '$toolName' not found",
                                summary = "Tool not found: $toolName"
                            )
                            continue
                        }

                        try {
                            val params = json.decodeFromString<JsonObject>(toolCall.function.arguments)
                            val toolResult = tool.execute(params, project)

                            contextManager.addToolResult(
                                toolCallId = toolCall.id,
                                content = toolResult.content,
                                summary = toolResult.summary
                            )

                            allArtifacts.addAll(toolResult.artifacts)
                        } catch (e: Exception) {
                            LOG.warn("WorkerSession: tool '$toolName' failed", e)
                            contextManager.addToolResult(
                                toolCallId = toolCall.id,
                                content = "Error executing tool '$toolName': ${e.message}",
                                summary = "Tool error: $toolName"
                            )
                        }
                    }
                }

                is ApiResult.Error -> {
                    LOG.warn("WorkerSession: LLM call failed: ${result.message}")
                    return WorkerResult(
                        content = "Error: LLM call failed: ${result.message}",
                        summary = "Failed: ${result.message}",
                        tokensUsed = totalTokensUsed,
                        artifacts = allArtifacts
                    )
                }
            }
        }

        LOG.warn("WorkerSession: reached max iterations ($maxIterations)")
        return WorkerResult(
            content = "Reached maximum iterations ($maxIterations) without completing",
            summary = "Incomplete: max iterations reached",
            tokensUsed = totalTokensUsed,
            artifacts = allArtifacts
        )
    }
}
