package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.orchestrator.OrchestratorPrompts
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.NumberFormat
import java.util.Locale

/**
 * Delegates a scoped task to a worker subagent.
 *
 * Only available to ORCHESTRATOR-level sessions (the main agent).
 * Workers cannot call delegate_task, preventing nested delegation.
 *
 * The tool spawns a WorkerSession with a fresh ContextManager, filtered tools,
 * and a 5-minute timeout. On failure or timeout, file changes are rolled back
 * via LocalHistory.
 */
class DelegateTaskTool : AgentTool {

    companion object {
        private val LOG = Logger.getInstance(DelegateTaskTool::class.java)
        private val FILE_PATH_PATTERN = Regex("""[\w/\\.-]+\.\w{1,10}""")
        private const val MAX_CONCURRENT_WORKERS = 5
        private const val MAX_RETRY_ATTEMPTS = 2
        private const val WORKER_TIMEOUT_MS = 300_000L // 5 minutes
        private val VALID_WORKER_TYPES = setOf("coder", "analyzer", "reviewer", "tooler")
    }

    override val name = "delegate_task"

    override val description =
        "Spawn a scoped worker subagent to perform a task. Worker types: coder (edits code), " +
            "analyzer (reads and analyzes code), reviewer (reviews changes), tooler (interacts with " +
            "Jira/Bamboo/SonarQube/Bitbucket). Workers cannot delegate further."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "task" to ParameterProperty(
                type = "string",
                description = "Detailed description of what the worker should do (min 50 characters)"
            ),
            "worker_type" to ParameterProperty(
                type = "string",
                description = "Type of worker to spawn",
                enumValues = VALID_WORKER_TYPES.toList()
            ),
            "context" to ParameterProperty(
                type = "string",
                description = "Relevant context for the worker, must contain at least one file path"
            )
        ),
        required = listOf("task", "worker_type", "context")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // --- 1. Validate parameters ---
        val task = params["task"]?.jsonPrimitive?.content
            ?: return errorResult("Error: 'task' parameter required")

        val workerTypeStr = params["worker_type"]?.jsonPrimitive?.content
            ?: return errorResult("Error: 'worker_type' parameter required")

        val context = params["context"]?.jsonPrimitive?.content
            ?: return errorResult("Error: 'context' parameter required")

        if (task.length < 50) {
            return errorResult(
                "Error: 'task' must be at least 50 characters to provide sufficient instruction " +
                    "for the worker. Current length: ${task.length}"
            )
        }

        if (workerTypeStr !in VALID_WORKER_TYPES) {
            return errorResult(
                "Error: Invalid worker_type '$workerTypeStr'. Must be one of: ${VALID_WORKER_TYPES.joinToString()}"
            )
        }

        if (!FILE_PATH_PATTERN.containsMatchIn(context)) {
            return errorResult(
                "Error: 'context' must contain at least one file path (e.g., src/main/kotlin/Foo.kt)"
            )
        }

        val workerType = when (workerTypeStr) {
            "coder" -> WorkerType.CODER
            "analyzer" -> WorkerType.ANALYZER
            "reviewer" -> WorkerType.REVIEWER
            "tooler" -> WorkerType.TOOLER
            else -> return errorResult("Error: Invalid worker_type '$workerTypeStr'")
        }

        // --- 2. Check resource limits ---
        val agentService: AgentService
        val settings: AgentSettings
        try {
            agentService = AgentService.getInstance(project)
            settings = AgentSettings.getInstance(project)
        } catch (e: Exception) {
            LOG.warn("DelegateTaskTool: failed to get services", e)
            return errorResult("Error: Agent services not available: ${e.message}")
        }

        if (agentService.activeWorkerCount.get() >= MAX_CONCURRENT_WORKERS) {
            return errorResult(
                "Error: Maximum concurrent workers ($MAX_CONCURRENT_WORKERS) reached. " +
                    "Wait for a running worker to complete before delegating another task."
            )
        }

        if (agentService.totalSessionTokens.get() >= settings.state.maxSessionTokens) {
            return errorResult(
                "Error: Session token budget exceeded (${formatNumber(agentService.totalSessionTokens.get())} / " +
                    "${formatNumber(settings.state.maxSessionTokens.toLong())}). Cannot spawn new workers."
            )
        }

        // --- 3. Check retry limit ---
        val filePaths = FILE_PATH_PATTERN.findAll(context).map { it.value }.toList().sorted()
        val retryKey = "$workerType:${filePaths.joinToString(",")}"
        val currentAttempts = agentService.delegationAttempts.getOrDefault(retryKey, 0)
        if (currentAttempts >= MAX_RETRY_ATTEMPTS) {
            return errorResult(
                "Error: Retry limit ($MAX_RETRY_ATTEMPTS) reached for $workerType worker on files: " +
                    "${filePaths.joinToString()}. Try a different approach or handle this task directly."
            )
        }

        // --- 4. Spawn worker ---
        agentService.activeWorkerCount.incrementAndGet()

        // Create rollback checkpoint
        val rollbackManager = AgentRollbackManager(project)
        val checkpointId = rollbackManager.createCheckpoint("delegate_task: $workerType - ${task.take(60)}")

        // Create event log for telemetry
        val sessionId = "worker-${System.currentTimeMillis()}"
        val eventLog = AgentEventLog(sessionId, project.basePath ?: ".")
        eventLog.log(AgentEventType.WORKER_SPAWNED, "type=$workerType, task=${task.take(100)}")

        try {
            // Fresh context manager for the worker (150K budget)
            val contextManager = ContextManager(
                maxInputTokens = settings.state.maxInputTokens
            )

            // Get worker-specific prompt and tools
            val systemPrompt = OrchestratorPrompts.getSystemPrompt(workerType)
            val toolsForWorker = agentService.toolRegistry.getToolsForWorker(workerType)
            val toolMap = toolsForWorker.associateBy { it.name }
            val toolDefinitions = agentService.toolRegistry.getToolDefinitionsForWorker(workerType)

            // Build the full task with context
            val fullTask = """
                |$task
                |
                |## Context
                |$context
            """.trimMargin()

            // Execute with timeout
            val workerSession = WorkerSession(maxIterations = 10)
            val workerResult: WorkerResult = withTimeout(WORKER_TIMEOUT_MS) {
                workerSession.execute(
                    workerType = workerType,
                    systemPrompt = systemPrompt,
                    task = fullTask,
                    tools = toolMap,
                    toolDefinitions = toolDefinitions,
                    brain = agentService.brain,
                    contextManager = contextManager,
                    project = project
                )
            }

            // Track tokens
            agentService.totalSessionTokens.addAndGet(workerResult.tokensUsed.toLong())

            if (workerResult.isError) {
                // Worker failed — increment retry counter, rollback
                agentService.delegationAttempts.merge(retryKey, 1) { a, b -> a + b }
                rollbackManager.rollbackToCheckpoint(checkpointId)
                eventLog.log(AgentEventType.WORKER_FAILED, "error=${workerResult.summary}")
                eventLog.log(AgentEventType.WORKER_ROLLED_BACK, "checkpoint=$checkpointId")

                return ToolResult(
                    content = "Worker ($workerTypeStr) failed: ${workerResult.content}\n\n" +
                        "File changes have been rolled back. Retry attempts: ${currentAttempts + 1}/$MAX_RETRY_ATTEMPTS",
                    summary = "Worker failed: ${workerResult.summary}",
                    tokenEstimate = workerResult.tokensUsed,
                    isError = true
                )
            }

            // Success
            eventLog.log(
                AgentEventType.WORKER_COMPLETED,
                "tokens=${workerResult.tokensUsed}, artifacts=${workerResult.artifacts.size}"
            )

            val filesModified = workerResult.artifacts.ifEmpty { filePaths }
            val formattedTokens = formatNumber(workerResult.tokensUsed.toLong())

            return ToolResult(
                content = "Worker ($workerTypeStr) completed successfully.\n" +
                    "Files modified: $filesModified\n" +
                    "Summary: ${workerResult.summary}\n" +
                    "Tokens used: $formattedTokens\n\n" +
                    "Note: The above files were modified by the worker. " +
                    "Re-read them if needed as your cached version may be stale.",
                summary = "Worker ($workerTypeStr) completed: ${workerResult.summary.take(100)}",
                tokenEstimate = workerResult.tokensUsed,
                artifacts = workerResult.artifacts
            )

        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Timeout — rollback
            rollbackManager.rollbackToCheckpoint(checkpointId)
            agentService.delegationAttempts.merge(retryKey, 1) { a, b -> a + b }
            eventLog.log(AgentEventType.WORKER_TIMED_OUT, "timeout=${WORKER_TIMEOUT_MS}ms")
            eventLog.log(AgentEventType.WORKER_ROLLED_BACK, "checkpoint=$checkpointId")

            return ToolResult(
                content = "Error: Worker ($workerTypeStr) timed out after ${WORKER_TIMEOUT_MS / 1000} seconds. " +
                    "File changes have been rolled back. Consider breaking the task into smaller pieces.",
                summary = "Worker timed out after ${WORKER_TIMEOUT_MS / 1000}s",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } catch (e: Exception) {
            // Unexpected error — rollback
            LOG.warn("DelegateTaskTool: worker execution failed", e)
            rollbackManager.rollbackToCheckpoint(checkpointId)
            agentService.delegationAttempts.merge(retryKey, 1) { a, b -> a + b }
            eventLog.log(AgentEventType.WORKER_FAILED, "exception=${e.message}")
            eventLog.log(AgentEventType.WORKER_ROLLED_BACK, "checkpoint=$checkpointId")

            return ToolResult(
                content = "Error: Worker ($workerTypeStr) failed: ${e.message}\n" +
                    "File changes have been rolled back.",
                summary = "Worker error: ${e.message?.take(100)}",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } finally {
            agentService.activeWorkerCount.decrementAndGet()
        }
    }

    private fun errorResult(message: String): ToolResult {
        return ToolResult(
            content = message,
            summary = message.take(120),
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    private fun formatNumber(value: Long): String {
        return NumberFormat.getNumberInstance(Locale.US).format(value)
    }
}
