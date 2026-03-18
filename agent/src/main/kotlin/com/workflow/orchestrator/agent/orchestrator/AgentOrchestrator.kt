package com.workflow.orchestrator.agent.orchestrator

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Result of an agent orchestration run.
 */
sealed class AgentResult {
    /** Plan was created and is ready for user approval before execution. */
    data class PlanReady(val plan: TaskGraph, val description: String) : AgentResult()

    /** All tasks completed successfully. */
    data class Completed(val summary: String, val artifacts: List<String>, val totalTokens: Int) : AgentResult()

    /** Execution failed with an error. */
    data class Failed(val error: String, val partialResults: String? = null) : AgentResult()

    /** User cancelled the task. */
    data class Cancelled(val completedSteps: Int) : AgentResult()
}

/**
 * Progress update emitted during orchestration.
 */
data class AgentProgress(
    val step: String,
    val workerType: WorkerType? = null,
    val tokensUsed: Int = 0,
    val totalTasks: Int = 0,
    val completedTasks: Int = 0
)

/**
 * Top-level orchestrator that manages task execution in two modes:
 *
 * 1. SINGLE_AGENT (default): One ReAct loop with ALL tools. The LLM decides
 *    whether to analyze, code, review, or call enterprise tools. If the token
 *    budget is exceeded, auto-escalates to orchestrated mode.
 *
 * 2. ORCHESTRATED: LLM generates a plan (TaskGraph) -> user approves -> executePlan()
 *    with individual WorkerSessions per step. Triggered by auto-escalation or
 *    explicit user request via [requestPlan].
 */
class AgentOrchestrator(
    private val brain: LlmBrain,
    private val toolRegistry: ToolRegistry,
    private val project: Project
) {
    companion object {
        private val LOG = Logger.getInstance(AgentOrchestrator::class.java)
    }

    private val cancelled = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Execute a task end-to-end using single-agent mode (default).
     *
     * The single agent has ALL tools and handles the full task in one ReAct loop.
     * If the task exceeds the token budget, it auto-escalates to orchestrated mode
     * (plan + step-by-step execution).
     *
     * For explicit plan-based execution, use [requestPlan] instead.
     */
    suspend fun executeTask(
        taskDescription: String,
        onProgress: (AgentProgress) -> Unit = {}
    ): AgentResult {
        cancelled.set(false)

        val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
        val maxTokens = settings?.state?.maxInputTokens ?: 150_000

        // Default: Single Agent Mode
        onProgress(AgentProgress("Starting task...", tokensUsed = 0))

        if (cancelled.get()) return AgentResult.Cancelled(0)

        val contextManager = ContextManager(maxInputTokens = maxTokens)
        val allTools = toolRegistry.allTools().associateBy { it.name }
        val allToolDefs = toolRegistry.allTools().map { it.toToolDefinition() }

        val session = SingleAgentSession()
        val result = session.execute(
            task = taskDescription,
            tools = allTools,
            toolDefinitions = allToolDefs,
            brain = brain,
            contextManager = contextManager,
            project = project,
            onProgress = onProgress
        )

        return when (result) {
            is SingleAgentResult.Completed -> {
                AgentResult.Completed(result.summary, result.artifacts, result.tokensUsed)
            }
            is SingleAgentResult.Failed -> {
                AgentResult.Failed(result.error)
            }
            is SingleAgentResult.EscalateToOrchestrated -> {
                // Auto-escalate: create a plan and switch to orchestrated mode
                LOG.info("AgentOrchestrator: auto-escalating to orchestrated mode: ${result.reason}")
                onProgress(AgentProgress("Task too complex for single pass. Creating plan...", WorkerType.ORCHESTRATOR))
                createPlan(taskDescription, onProgress)
            }
        }
    }

    /**
     * Explicitly request a plan for a task (orchestrated mode).
     * Returns [AgentResult.PlanReady] for user approval before calling [executePlan].
     */
    suspend fun requestPlan(
        taskDescription: String,
        onProgress: (AgentProgress) -> Unit = {}
    ): AgentResult {
        cancelled.set(false)
        onProgress(AgentProgress("Creating plan...", WorkerType.ORCHESTRATOR))
        return createPlan(taskDescription, onProgress)
    }

    /**
     * Run a single worker session with the given worker type and task description.
     * Used in orchestrated mode for individual plan steps.
     */
    private suspend fun runWorker(workerType: WorkerType, task: String): WorkerResult {
        val maxTokens = try { AgentSettings.getInstance(project).state.maxInputTokens } catch (_: Exception) { 150_000 }
        val contextManager = ContextManager(maxInputTokens = maxTokens)
        val toolsList = toolRegistry.getToolsForWorker(workerType)
        val toolsMap = toolsList.associateBy { it.name }
        val toolDefs = toolRegistry.getToolDefinitionsForWorker(workerType)
        val systemPrompt = OrchestratorPrompts.getSystemPrompt(workerType)
        val session = WorkerSession()
        return session.execute(workerType, systemPrompt, task, toolsMap, toolDefs, brain, contextManager, project)
    }

    /**
     * Create an execution plan for a complex task via LLM.
     * Returns [AgentResult.PlanReady] for user approval.
     */
    private suspend fun createPlan(
        taskDescription: String,
        onProgress: (AgentProgress) -> Unit
    ): AgentResult {
        val messages = listOf(
            ChatMessage(role = "system", content = OrchestratorPrompts.ORCHESTRATOR_SYSTEM_PROMPT),
            ChatMessage(role = "user", content = "Plan this task:\n$taskDescription")
        )

        val tools = toolRegistry.getToolDefinitionsForWorker(WorkerType.ORCHESTRATOR)
        val result = brain.chat(messages, tools)

        return when (result) {
            is ApiResult.Success -> {
                val content = result.data.choices.firstOrNull()?.message?.content ?: ""
                onProgress(AgentProgress("Plan generated, parsing...", WorkerType.ORCHESTRATOR))
                val taskGraph = parsePlanToTaskGraph(content)
                if (taskGraph != null) {
                    AgentResult.PlanReady(taskGraph, taskDescription)
                } else {
                    AgentResult.Failed("Failed to parse plan from LLM response: ${content.take(200)}")
                }
            }
            is ApiResult.Error -> AgentResult.Failed("LLM error: ${result.message}")
        }
    }

    /**
     * Execute an approved plan (TaskGraph). Called after user approves a PlanReady result.
     * Executes tasks in dependency order, checkpointing after each.
     */
    suspend fun executePlan(
        taskGraph: TaskGraph,
        onProgress: (AgentProgress) -> Unit = {}
    ): AgentResult {
        cancelled.set(false)
        var totalTokens = 0
        val artifacts = mutableListOf<String>()
        var completedCount = 0
        val totalCount = taskGraph.getAllTasks().size

        val projectBasePath = project.basePath
        val checkpointStore = if (projectBasePath != null) {
            CheckpointStore.forProject(projectBasePath)
        } else {
            null
        }

        // Create FileGuard for edit conflict prevention and rollback
        val fileGuard = FileGuard()
        // Snapshot working tree before execution for rollback capability
        if (projectBasePath != null) {
            try { fileGuard.snapshotFiles(project, emptyList()) } catch (_: Exception) { /* best effort */ }
        }

        while (!taskGraph.isComplete()) {
            if (cancelled.get()) return AgentResult.Cancelled(completedCount)

            val nextTasks = taskGraph.getNextExecutable()
            if (nextTasks.isEmpty()) {
                // Check if failed tasks are blocking progress
                val failedTasks = taskGraph.getAllTasks().filter { it.status == TaskStatus.FAILED }
                if (failedTasks.isNotEmpty()) {
                    return AgentResult.Failed(
                        error = "Blocked by failed tasks: ${failedTasks.joinToString { it.id }}",
                        partialResults = "Completed $completedCount of $totalCount tasks"
                    )
                }
                break
            }

            // Execute tasks sequentially (within each wave of ready tasks)
            for (task in nextTasks) {
                if (cancelled.get()) return AgentResult.Cancelled(completedCount)

                taskGraph.markRunning(task.id)
                try { project.getService(EventBus::class.java).emit(WorkflowEvent.AgentTaskStarted(task.id, task.description)) } catch (_: Exception) {}
                val workerType = task.workerType
                onProgress(AgentProgress(
                    step = "Executing: ${task.description}",
                    workerType = workerType,
                    tokensUsed = totalTokens,
                    totalTasks = totalCount,
                    completedTasks = completedCount
                ))

                val workerResult = runWorker(workerType, task.description)

                totalTokens += workerResult.tokensUsed
                artifacts.addAll(workerResult.artifacts)

                if (workerResult.isError) {
                    taskGraph.markFailed(task.id, workerResult.content)
                    try { project.getService(EventBus::class.java).emit(WorkflowEvent.AgentTaskFailed(task.id, workerResult.content)) } catch (_: Exception) {}
                } else {
                    taskGraph.markComplete(task.id, workerResult.summary)
                    completedCount++
                    try { project.getService(EventBus::class.java).emit(WorkflowEvent.AgentTaskCompleted(task.id, workerResult.summary)) } catch (_: Exception) {}
                }

                // Checkpoint after each task (best-effort — never abort plan for I/O errors)
                try { checkpointStore?.save("current", AgentCheckpoint(
                    taskId = "current",
                    taskGraphState = taskGraph.toSerializableState(),
                    completedSummaries = taskGraph.getAllTasks()
                        .filter { it.status == TaskStatus.COMPLETED }
                        .associate { it.id to (it.resultSummary ?: "") },
                    timestamp = System.currentTimeMillis()
                )) } catch (e: Exception) { LOG.warn("Checkpoint save failed (non-fatal)", e) }
            }
        }

        checkpointStore?.delete("current")

        return AgentResult.Completed(
            summary = "Completed $completedCount of $totalCount tasks",
            artifacts = artifacts,
            totalTokens = totalTokens
        )
    }

    /**
     * Cancel the currently running task. The next iteration check will return Cancelled.
     */
    fun cancelTask() {
        cancelled.set(true)
        LOG.info("AgentOrchestrator: cancellation requested")
    }

    /**
     * Parse a JSON plan from LLM output into a TaskGraph.
     * Handles JSON wrapped in markdown code blocks.
     */
    internal fun parsePlanToTaskGraph(content: String): TaskGraph? {
        return try {
            val jsonStr = extractJson(content) ?: return null
            val jsonObj = json.parseToJsonElement(jsonStr).jsonObject
            val tasks = jsonObj["tasks"]?.jsonArray ?: return null

            val graph = TaskGraph()
            for (taskEl in tasks) {
                val obj = taskEl.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: continue
                val description = obj["description"]?.jsonPrimitive?.content ?: ""
                val actionStr = obj["action"]?.jsonPrimitive?.content ?: "CODE"
                val target = obj["target"]?.jsonPrimitive?.content ?: ""
                val workerTypeStr = obj["workerType"]?.jsonPrimitive?.content ?: actionStr
                val dependsOn = obj["dependsOn"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()

                val action = when (actionStr.uppercase()) {
                    "ANALYZE" -> TaskAction.ANALYZE
                    "CODE" -> TaskAction.CODE
                    "REVIEW" -> TaskAction.REVIEW
                    "TOOL" -> TaskAction.TOOL
                    else -> TaskAction.CODE
                }

                val workerType = when (workerTypeStr.uppercase()) {
                    "ANALYZER", "ANALYZE" -> WorkerType.ANALYZER
                    "CODER", "CODE" -> WorkerType.CODER
                    "REVIEWER", "REVIEW" -> WorkerType.REVIEWER
                    "TOOLER", "TOOL" -> WorkerType.TOOLER
                    "ORCHESTRATOR" -> WorkerType.ORCHESTRATOR
                    else -> WorkerType.CODER
                }

                graph.addTask(AgentTask(
                    id = id,
                    description = "$description (target: $target)",
                    action = action,
                    target = target,
                    workerType = workerType,
                    dependsOn = dependsOn
                ))
            }

            if (graph.getAllTasks().isEmpty()) null else graph
        } catch (e: Exception) {
            LOG.warn("AgentOrchestrator: failed to parse plan", e)
            null
        }
    }

    /**
     * Extract JSON from LLM output that may be wrapped in markdown code blocks.
     */
    private fun extractJson(content: String): String? {
        // Try markdown code block first
        val codeBlockMatch = Regex("```(?:json)?\\s*\\n?(\\{[\\s\\S]*?})\\s*\\n?```").find(content)
        if (codeBlockMatch != null) return codeBlockMatch.groupValues[1]

        // Try raw JSON object containing "tasks"
        val jsonMatch = Regex("(\\{[\\s\\S]*\"tasks\"[\\s\\S]*})").find(content)
        if (jsonMatch != null) return jsonMatch.groupValues[1]

        return null
    }
}
