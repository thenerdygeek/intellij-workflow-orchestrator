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

    fun getTask(id: String): Task? = tasks.firstOrNull { it.id == id }

    fun listTasks(): List<Task> = tasks.toList()

    fun loadFromDisk() {
        check(!mutex.isLocked) { "loadFromDisk must only be called during init, before concurrent access" }
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
        val visited = mutableSetOf<String>()
        val stack = ArrayDeque<String>().apply { add(candidate.id) }
        while (stack.isNotEmpty()) {
            val curr = stack.removeLast()
            if (!visited.add(curr)) continue
            val t = snapshot[curr] ?: continue
            for (dep in t.blockedBy) {
                if (dep == candidate.id) throw CycleException(
                    "Updating task '${candidate.id}' would create a blockedBy cycle"
                )
                stack.addLast(dep)
            }
        }
    }
}
