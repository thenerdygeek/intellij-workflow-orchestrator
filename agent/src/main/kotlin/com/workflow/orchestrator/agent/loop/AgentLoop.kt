package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.truncateOutput
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core ReAct loop: call LLM -> execute tools -> repeat.
 *
 * Follows the Codex CLI + Cline pattern:
 * - Exit ONLY via `attempt_completion` tool (isCompletion=true).
 *   "No tool calls" is NOT completion — the model must explicitly complete.
 * - Empty responses get continuation prompts (fail after 3 consecutive).
 * - Text-only responses get a nudge to use tools or attempt_completion.
 * - Compaction runs when context utilization exceeds threshold.
 */
class AgentLoop(
    private val brain: LlmBrain,
    private val tools: Map<String, AgentTool>,
    private val toolDefinitions: List<ToolDefinition>,
    private val contextManager: ContextManager,
    private val project: Project,
    private val onStreamChunk: (String) -> Unit = {},
    private val onToolCall: (ToolCallProgress) -> Unit = {},
    private val maxIterations: Int = 200,
    private val planMode: Boolean = false
) {
    private val cancelled = AtomicBoolean(false)
    private var totalTokensUsed = 0

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val LOG = Logger.getInstance(AgentLoop::class.java)
        private const val MAX_CONSECUTIVE_EMPTIES = 3
        private const val MAX_API_RETRIES = 5
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val CONTINUATION_PROMPT =
            "Your previous response was empty. Please use the available tools to take action on the task, or call attempt_completion if you are done."
        private const val TEXT_ONLY_NUDGE =
            "Please use tools to take action, or call attempt_completion if you're done. Do not just describe what you plan to do — take action with tools."

        /** Tools that mutate state — blocked when plan mode is active. */
        val WRITE_TOOLS = setOf(
            "edit_file", "create_file", "run_command", "revert_file",
            "kill_process", "send_stdin", "format_code", "optimize_imports",
            "refactor_rename"
        )

        /** Error types that are transient and safe to retry. */
        private val RETRYABLE_ERRORS = setOf(
            ErrorType.RATE_LIMITED,
            ErrorType.SERVER_ERROR,
            ErrorType.NETWORK_ERROR,
            ErrorType.TIMEOUT
        )
    }

    /**
     * Run the ReAct loop. Returns when:
     * - A tool returns isCompletion=true (Completed)
     * - An unrecoverable error occurs (Failed)
     * - The loop is cancelled (Cancelled)
     * - Max iterations exceeded (Failed)
     */
    suspend fun run(task: String): LoopResult {
        if (cancelled.get()) {
            return LoopResult.Cancelled(iterations = 0, tokensUsed = 0)
        }

        contextManager.addUserMessage(task)

        var iteration = 0
        var consecutiveEmpties = 0
        var apiRetryCount = 0

        while (!cancelled.get() && iteration < maxIterations) {
            iteration++

            // Stage 0: Compact if needed
            if (contextManager.shouldCompact()) {
                contextManager.compact(brain)
            }

            // Stage 1: Call LLM
            val apiResult = brain.chatStream(
                messages = contextManager.getMessages(),
                tools = toolDefinitions,
                onChunk = { chunk ->
                    chunk.choices.firstOrNull()?.delta?.content?.let { onStreamChunk(it) }
                }
            )

            // Stage 2: Handle API errors with retry for transient failures
            if (apiResult is ApiResult.Error) {
                if (apiResult.type in RETRYABLE_ERRORS && apiRetryCount < MAX_API_RETRIES) {
                    apiRetryCount++
                    val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl (apiRetryCount - 1))
                    LOG.warn("[AgentLoop] Retryable API error (${apiResult.type}), retry $apiRetryCount/$MAX_API_RETRIES after ${delayMs}ms")
                    delay(delayMs)
                    iteration-- // Don't count retries as iterations
                    continue
                }
                return LoopResult.Failed(
                    error = apiResult.message,
                    iterations = iteration,
                    tokensUsed = totalTokensUsed
                )
            }

            val response = (apiResult as ApiResult.Success).data

            // Reset retry count on successful API call
            apiRetryCount = 0

            // Stage 3: Update token tracking
            response.usage?.let { usage ->
                totalTokensUsed += usage.totalTokens
                contextManager.updateTokens(usage.promptTokens)
            }

            val choice = response.choices.firstOrNull() ?: continue
            val assistantMessage = choice.message

            // Stage 4: Add assistant message to context
            contextManager.addAssistantMessage(assistantMessage)

            val hasToolCalls = !assistantMessage.toolCalls.isNullOrEmpty()
            val hasContent = !assistantMessage.content.isNullOrBlank()

            when {
                // Case A: Tool calls present — execute them
                hasToolCalls -> {
                    consecutiveEmpties = 0
                    val completionResult = executeToolCalls(assistantMessage.toolCalls!!, iteration)
                    if (completionResult != null) return completionResult
                }

                // Case B: Text only — nudge model to use tools
                hasContent -> {
                    consecutiveEmpties = 0
                    contextManager.addUserMessage(TEXT_ONLY_NUDGE)
                }

                // Case C: Empty response — inject continuation, track consecutive count
                else -> {
                    consecutiveEmpties++
                    if (consecutiveEmpties >= MAX_CONSECUTIVE_EMPTIES) {
                        return LoopResult.Failed(
                            error = "Failed after $MAX_CONSECUTIVE_EMPTIES consecutive empty responses from model.",
                            iterations = iteration,
                            tokensUsed = totalTokensUsed
                        )
                    }
                    contextManager.addUserMessage(CONTINUATION_PROMPT)
                }
            }
        }

        if (cancelled.get()) {
            return LoopResult.Cancelled(iterations = iteration, tokensUsed = totalTokensUsed)
        }

        return LoopResult.Failed(
            error = "Exceeded maximum iterations ($maxIterations). The task may be too complex or the model is stuck.",
            iterations = iteration,
            tokensUsed = totalTokensUsed
        )
    }

    /**
     * Cancel the running loop. Safe to call from any thread.
     */
    fun cancel() {
        cancelled.set(true)
        brain.cancelActiveRequest()
    }

    /**
     * Execute tool calls from the assistant message.
     * Returns a LoopResult if a completion tool was called, null otherwise.
     */
    private suspend fun executeToolCalls(toolCalls: List<ToolCall>, iteration: Int): LoopResult? {
        for (call in toolCalls) {
            if (cancelled.get()) {
                return LoopResult.Cancelled(iterations = iteration, tokensUsed = totalTokensUsed)
            }

            val toolName = call.function.name
            val toolCallId = call.id
            val startTime = System.currentTimeMillis()

            val tool = tools[toolName]
            if (tool == null) {
                // Unknown tool
                val errorMsg = "Unknown tool: '$toolName'. Available tools: ${tools.keys.joinToString(", ")}"
                contextManager.addToolResult(toolCallId = toolCallId, content = errorMsg, isError = true)
                onToolCall(
                    ToolCallProgress(
                        toolName = toolName,
                        args = call.function.arguments,
                        result = errorMsg,
                        durationMs = System.currentTimeMillis() - startTime,
                        isError = true,
                        toolCallId = toolCallId
                    )
                )
                continue
            }

            // Plan mode guard: block write tools even if the LLM hallucinates them
            if (planMode && toolName in WRITE_TOOLS) {
                val errorMsg = "Error: '$toolName' is blocked in plan mode. You can only read, search, and analyze code."
                contextManager.addToolResult(toolCallId = toolCallId, content = errorMsg, isError = true)
                onToolCall(
                    ToolCallProgress(
                        toolName = toolName,
                        args = call.function.arguments,
                        result = errorMsg,
                        durationMs = System.currentTimeMillis() - startTime,
                        isError = true,
                        toolCallId = toolCallId
                    )
                )
                continue
            }

            // Parse arguments
            val params: JsonObject = try {
                json.decodeFromString<JsonObject>(call.function.arguments)
            } catch (e: Exception) {
                val errorMsg = "Invalid JSON arguments for '$toolName': ${e.message}"
                contextManager.addToolResult(toolCallId = toolCallId, content = errorMsg, isError = true)
                onToolCall(
                    ToolCallProgress(
                        toolName = toolName,
                        args = call.function.arguments,
                        result = errorMsg,
                        durationMs = System.currentTimeMillis() - startTime,
                        isError = true,
                        toolCallId = toolCallId
                    )
                )
                continue
            }

            // Execute tool
            val toolResult = try {
                tool.execute(params, project)
            } catch (e: Exception) {
                val errorMsg = "Tool '$toolName' threw exception: ${e.message}"
                contextManager.addToolResult(toolCallId = toolCallId, content = errorMsg, isError = true)
                onToolCall(
                    ToolCallProgress(
                        toolName = toolName,
                        args = call.function.arguments,
                        result = errorMsg,
                        durationMs = System.currentTimeMillis() - startTime,
                        isError = true,
                        toolCallId = toolCallId
                    )
                )
                continue
            }

            val durationMs = System.currentTimeMillis() - startTime
            val truncatedContent = truncateOutput(toolResult.content)

            // Add result to context
            contextManager.addToolResult(
                toolCallId = toolCallId,
                content = truncatedContent,
                isError = toolResult.isError
            )

            // Notify callback
            onToolCall(
                ToolCallProgress(
                    toolName = toolName,
                    args = call.function.arguments,
                    result = toolResult.summary,
                    durationMs = durationMs,
                    isError = toolResult.isError,
                    toolCallId = toolCallId
                )
            )

            // Check for completion
            if (toolResult.isCompletion) {
                return LoopResult.Completed(
                    summary = toolResult.content,
                    iterations = iteration,
                    tokensUsed = totalTokensUsed,
                    verifyCommand = toolResult.verifyCommand
                )
            }
        }
        return null
    }
}
