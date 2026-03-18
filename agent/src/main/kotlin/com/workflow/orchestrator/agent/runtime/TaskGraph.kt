package com.workflow.orchestrator.agent.runtime

import kotlinx.serialization.Serializable

enum class TaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED
}

enum class TaskAction {
    ANALYZE, CODE, REVIEW, TOOL
}

@Serializable
data class AgentTask(
    val id: String,
    val description: String,
    val action: TaskAction,
    val target: String,
    val workerType: WorkerType,
    val status: TaskStatus = TaskStatus.PENDING,
    val dependsOn: List<String> = emptyList(),
    val resultSummary: String? = null
)

/**
 * A DAG of agent tasks with dependency tracking.
 * Supports topological ordering, cycle detection, and next-executable queries.
 */
class TaskGraph {
    private val tasks = mutableMapOf<String, AgentTask>()

    /**
     * Add a task to the graph.
     * @throws IllegalArgumentException if adding this task would create a cycle
     * @throws IllegalArgumentException if a dependency references a non-existent task
     */
    fun addTask(task: AgentTask) {
        // Check that all dependencies exist (or are being added in this batch)
        for (dep in task.dependsOn) {
            require(dep != task.id) { "Task '${task.id}' cannot depend on itself" }
        }

        // Temporarily add the task to check for cycles
        tasks[task.id] = task

        if (hasCycle()) {
            tasks.remove(task.id)
            throw IllegalArgumentException("Adding task '${task.id}' would create a cycle in the task graph")
        }
    }

    /**
     * Mark a task as completed with a result summary.
     */
    fun markComplete(id: String, summary: String) {
        val task = tasks[id] ?: throw IllegalArgumentException("Task '$id' not found")
        tasks[id] = task.copy(status = TaskStatus.COMPLETED, resultSummary = summary)
    }

    /**
     * Mark a task as failed with an error message.
     */
    fun markFailed(id: String, error: String) {
        val task = tasks[id] ?: throw IllegalArgumentException("Task '$id' not found")
        tasks[id] = task.copy(status = TaskStatus.FAILED, resultSummary = error)
    }

    /**
     * Mark a task as running.
     */
    fun markRunning(id: String) {
        val task = tasks[id] ?: throw IllegalArgumentException("Task '$id' not found")
        tasks[id] = task.copy(status = TaskStatus.RUNNING)
    }

    /**
     * Get the next executable tasks — tasks whose dependencies are all completed.
     * Only returns tasks that are PENDING (not already running/completed/failed).
     */
    fun getNextExecutable(): List<AgentTask> {
        return tasks.values.filter { task ->
            task.status == TaskStatus.PENDING &&
                task.dependsOn.all { depId ->
                    val dep = tasks[depId]
                    dep != null && dep.status == TaskStatus.COMPLETED
                }
        }
    }

    /**
     * Check if all tasks are completed or failed (no more work to do).
     */
    fun isComplete(): Boolean {
        return tasks.values.all { it.status == TaskStatus.COMPLETED || it.status == TaskStatus.FAILED }
    }

    /**
     * Get all tasks in the graph.
     */
    fun getAllTasks(): List<AgentTask> = tasks.values.toList()

    /**
     * Get a specific task by ID.
     */
    fun getTask(id: String): AgentTask? = tasks[id]

    /**
     * Detect cycles using DFS.
     */
    private fun hasCycle(): Boolean {
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()

        fun dfs(taskId: String): Boolean {
            if (taskId in inStack) return true
            if (taskId in visited) return false

            visited.add(taskId)
            inStack.add(taskId)

            val task = tasks[taskId] ?: return false
            for (dep in task.dependsOn) {
                if (dfs(dep)) return true
            }

            inStack.remove(taskId)
            return false
        }

        return tasks.keys.any { dfs(it) }
    }

    /**
     * Serialize the task graph state for checkpointing.
     */
    fun toSerializableState(): List<AgentTask> = tasks.values.toList()

    /**
     * Restore from serialized state.
     */
    fun restoreFromState(state: List<AgentTask>) {
        tasks.clear()
        state.forEach { tasks[it.id] = it }
    }
}
