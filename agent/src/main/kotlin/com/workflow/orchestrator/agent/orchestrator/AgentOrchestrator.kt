package com.workflow.orchestrator.agent.orchestrator

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
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
 * Top-level orchestrator that routes tasks through complexity classification,
 * plans complex tasks via LLM, and executes plans using WorkerSession.
 *
 * Flow:
 * 1. Classify task complexity via ComplexityRouter
 * 2. SIMPLE -> fast path (single WorkerSession with CODER)
 * 3. COMPLEX -> LLM generates a plan (TaskGraph) -> user approves -> executePlan()
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
     * Execute a task end-to-end. Routes through complexity classification first.
     * SIMPLE tasks are executed immediately; COMPLEX tasks return a PlanReady result
     * for the user to approve before calling [executePlan].
     */
    suspend fun executeTask(
        taskDescription: String,
        onProgress: (AgentProgress) -> Unit = {}
    ): AgentResult {
        cancelled.set(false)

        // Step 1: Classify complexity
        onProgress(AgentProgress("Classifying task complexity...", tokensUsed = 0))
        val complexity = ComplexityRouter.route(taskDescription, brain)
        LOG.info("AgentOrchestrator: task classified as $complexity")

        if (cancelled.get()) return AgentResult.Cancelled(0)

        // Step 2: Route based on complexity
        return if (complexity == TaskComplexity.SIMPLE) {
            onProgress(AgentProgress("Executing simple task (fast path)...", WorkerType.CODER))
            executeSimpleTask(taskDescription, onProgress)
        } else {
            onProgress(AgentProgress("Planning complex task...", WorkerType.ORCHESTRATOR))
            createPlan(taskDescription, onProgress)
        }
    }

    /**
     * Fast path: execute a simple task directly with a single CODER worker.
     */
    private suspend fun executeSimpleTask(
        taskDescription: String,
        onProgress: (AgentProgress) -> Unit
    ): AgentResult {
        val workerType = WorkerType.CODER
        val contextManager = ContextManager(maxInputTokens = 150_000)
        val toolsList = toolRegistry.getToolsForWorker(workerType)
        val toolsMap = toolsList.associateBy { it.name }
        val toolDefs = toolRegistry.getToolDefinitionsForWorker(workerType)
        val systemPrompt = OrchestratorPrompts.getSystemPrompt(workerType)

        val session = WorkerSession()
        val result = session.execute(
            workerType = workerType,
            systemPrompt = systemPrompt,
            task = taskDescription,
            tools = toolsMap,
            toolDefinitions = toolDefs,
            brain = brain,
            contextManager = contextManager,
            project = project
        )

        return if (result.content.isNotBlank() && !result.content.startsWith("Error:")) {
            onProgress(AgentProgress("Task completed", workerType, result.tokensUsed, 1, 1))
            AgentResult.Completed(
                summary = result.summary,
                artifacts = result.artifacts,
                totalTokens = result.tokensUsed
            )
        } else {
            AgentResult.Failed(
                error = if (result.content.isBlank()) "Worker produced empty result" else result.content
            )
        }
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
                val workerType = task.workerType
                onProgress(AgentProgress(
                    step = "Executing: ${task.description}",
                    workerType = workerType,
                    tokensUsed = totalTokens,
                    totalTasks = totalCount,
                    completedTasks = completedCount
                ))

                val contextManager = ContextManager()
                val toolsList = toolRegistry.getToolsForWorker(workerType)
                val toolsMap = toolsList.associateBy { it.name }
                val toolDefs = toolRegistry.getToolDefinitionsForWorker(workerType)
                val systemPrompt = OrchestratorPrompts.getSystemPrompt(workerType)

                val session = WorkerSession()
                val workerResult = session.execute(
                    workerType = workerType,
                    systemPrompt = systemPrompt,
                    task = task.description,
                    tools = toolsMap,
                    toolDefinitions = toolDefs,
                    brain = brain,
                    contextManager = contextManager,
                    project = project
                )

                totalTokens += workerResult.tokensUsed
                artifacts.addAll(workerResult.artifacts)

                if (workerResult.content.startsWith("Error:")) {
                    taskGraph.markFailed(task.id, workerResult.content)
                } else {
                    taskGraph.markComplete(task.id, workerResult.summary)
                    completedCount++
                }

                // Checkpoint after each task
                checkpointStore?.save("current", AgentCheckpoint(
                    taskId = "current",
                    taskGraphState = taskGraph.toSerializableState(),
                    completedSummaries = taskGraph.getAllTasks()
                        .filter { it.status == TaskStatus.COMPLETED }
                        .associate { it.id to (it.resultSummary ?: "") },
                    timestamp = System.currentTimeMillis()
                ))
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
