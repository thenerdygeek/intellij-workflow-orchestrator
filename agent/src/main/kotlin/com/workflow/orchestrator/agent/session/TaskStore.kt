package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.agent.loop.Task
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class TaskStore(
    private val baseDir: File,
    val sessionId: String,
) {
    class CycleException(message: String) : IllegalStateException(message)

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val tasks: MutableList<Task> = mutableListOf()

    private val sessionDir: File get() = File(baseDir, "sessions/$sessionId")
    private val tasksFile: File get() = File(sessionDir, "tasks.json")

    suspend fun addTask(task: Task) = mutex.withLock {
        checkNoCycles(task)
        tasks.add(task)
        saveInternal()
    }

    suspend fun updateTask(id: String, patch: (Task) -> Task): Task? = mutex.withLock {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return@withLock null
        val updated = patch(tasks[idx]).copy(updatedAt = System.currentTimeMillis())
        checkNoCycles(updated)
        tasks[idx] = updated
        saveInternal()
        updated
    }

    /**
     * Read a task by id. Not suspend / not mutex-locked — this is an intentional
     * dirty-read safe for single-coroutine callers (the agent loop). Do NOT add
     * `mutex.withLock` here: `updateTask` calls that lambda from within a mutex-held
     * block (for tools' post-update lookup), and locking here would deadlock.
     */
    fun getTask(id: String): Task? = tasks.firstOrNull { it.id == id }

    /**
     * Snapshot copy of the current task list. Same unsynchronized-read rationale
     * as [getTask] — safe for single-coroutine callers, do not add `withLock`.
     */
    fun listTasks(): List<Task> = tasks.toList()

    suspend fun loadFromDisk() = mutex.withLock {
        tasks.clear()
        if (tasksFile.exists()) {
            try {
                val loaded: List<Task> = json.decodeFromString(tasksFile.readText())
                tasks.addAll(loaded)
            } catch (_: Exception) { /* corrupted: start fresh */ }
        }
    }

    private fun saveInternal() {
        sessionDir.mkdirs()
        AtomicFileWriter.write(tasksFile, json.encodeToString(tasks))
    }

    private fun checkNoCycles(candidate: Task) {
        val snapshot = tasks.associateBy { it.id } + (candidate.id to candidate)
        for (startingEdge in listOf(EdgeKind.BLOCKED_BY, EdgeKind.BLOCKS)) {
            val visited = mutableSetOf<String>()
            val stack = ArrayDeque<String>().apply { addLast(candidate.id) }
            var first = true
            while (stack.isNotEmpty()) {
                val curr = stack.removeLast()
                if (!first && curr == candidate.id) {
                    throw CycleException(
                        "Updating task '${candidate.id}' would create a ${startingEdge.name.lowercase()} cycle"
                    )
                }
                first = false
                if (!visited.add(curr)) continue
                val t = snapshot[curr] ?: continue
                val edges = when (startingEdge) {
                    EdgeKind.BLOCKED_BY -> t.blockedBy
                    EdgeKind.BLOCKS -> t.blocks
                }
                for (dep in edges) stack.addLast(dep)
            }
        }
    }

    private enum class EdgeKind { BLOCKED_BY, BLOCKS }
}
