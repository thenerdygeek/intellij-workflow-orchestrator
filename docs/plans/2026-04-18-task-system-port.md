# Task System Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the plugin's Cline-ported `task_progress`-markdown-parameter system with Claude Code's typed task system — four dedicated tools (`TaskCreate`/`TaskUpdate`/`TaskList`/`TaskGet`), a persistent `TaskStore`, `blocks/blockedBy` DAG, and `pending/in_progress/completed/deleted` status enum.

**Architecture:** Data-first port. Phase 1 adds `Task`, `TaskStatus`, and `TaskStore` (mirrors `MessageStateHandler` persistence discipline). Phase 2 adds the four tools and wires them into `AgentService`. Phase 3 rewires `SystemPrompt` (Sections 2 and 5), `ContextManager`, `LoopDetector` ignored-params, and the three bundled skills that reference `task_progress` (`writing-plans`, `subagent-driven`, `brainstorm`). Phase 4 migrates bridge contracts. Phase 5 rewires the UI — `PlanProgressWidget` reads from `TaskStore`, `PlanSummaryCard` loses its step list, `AgentController` progress-sourcing logic reads `TaskStore` instead of the task_progress markdown. Phase 6 deletes the legacy plumbing (`TaskProgress.kt`, `injectTaskProgress`, `PlanStep`, `plan_mode_respond.steps`). Phase 7 gate-verifies. Tasks are hook-exempt per Claude Code's model.

**Tech Stack:** Kotlin 2.1.10, `kotlinx.serialization`, `kotlinx.coroutines`, JUnit 5, MockK, React 19 + TypeScript + Zustand (webview), JCEF bridges.

**Research basis:** `docs/research/2026-04-18-claude-code-task-system-research.md` (official Anthropic docs + vetted community sources).

**Persistence scope:** Session-scoped (`~/.workflow-orchestrator/{proj}/agent/sessions/{sessionId}/tasks.json`) — matches existing `MessageStateHandler` convention. Deliberately deviates from Claude Code's global scope.

---

## Pre-Work: Worktree & Branch Setup

This port is a distinct workstream from the current `feature/tooling-architecture-enhancements` branch (DONE and awaiting merge). Create a new worktree from latest `main`.

- [ ] **Step 1: Create worktree from main**

```bash
cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin
git fetch origin main
git worktree add .worktrees/task-system-port -b feature/task-system-port origin/main
cd .worktrees/task-system-port
```

Verify: `git status` shows a clean tree; `git branch --show-current` prints `feature/task-system-port`.

- [ ] **Step 2: Sanity-check build**

```bash
./gradlew :agent:test
```

Expected: existing test suite passes on the new branch.

---

## Phase 1 — Data Layer

Ship `Task`, `TaskStatus`, and `TaskStore`. All new files. No existing-code modification. Fully reversible.

### Task 1.1: `Task` data class and `TaskStatus` enum

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/Task.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/TaskSerializationTest.kt`

- [ ] **Step 1: Write the failing test**

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/TaskSerializationTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.loop

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TaskSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `task with all fields round-trips through JSON`() {
        val original = Task(
            id = "task-1",
            subject = "Implement OAuth2 flow",
            description = "Add OAuth2 support to login middleware",
            activeForm = "Implementing OAuth2 flow",
            status = TaskStatus.IN_PROGRESS,
            owner = "coder-agent",
            blocks = listOf("task-2", "task-3"),
            blockedBy = listOf("task-0"),
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Task>(encoded)
        assertEquals(original.id, decoded.id)
        assertEquals(original.subject, decoded.subject)
        assertEquals(original.status, decoded.status)
        assertEquals(original.blocks, decoded.blocks)
        assertEquals(original.blockedBy, decoded.blockedBy)
    }

    @Test
    fun `task with only required fields defaults correctly`() {
        val t = Task(id = "t1", subject = "Do thing", description = "details")
        assertEquals(TaskStatus.PENDING, t.status)
        assertEquals(null, t.owner)
        assertEquals(null, t.activeForm)
        assertEquals(emptyList<String>(), t.blocks)
        assertEquals(emptyList<String>(), t.blockedBy)
    }

    @Test
    fun `TaskStatus enum has four values`() {
        val values = TaskStatus.values().map { it.name }.toSet()
        assertEquals(setOf("PENDING", "IN_PROGRESS", "COMPLETED", "DELETED"), values)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :agent:test --tests "*TaskSerializationTest*"
```

Expected: `FAIL` — `Task` and `TaskStatus` don't exist.

- [ ] **Step 3: Write the implementation**

Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/Task.kt`:

```kotlin
package com.workflow.orchestrator.agent.loop

import kotlinx.serialization.Serializable

@Serializable
enum class TaskStatus { PENDING, IN_PROGRESS, COMPLETED, DELETED }

@Serializable
data class Task(
    val id: String,
    val subject: String,
    val description: String,
    val activeForm: String? = null,
    val status: TaskStatus = TaskStatus.PENDING,
    val owner: String? = null,
    val blocks: List<String> = emptyList(),
    val blockedBy: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :agent:test --tests "*TaskSerializationTest*"
```

Expected: `PASS` (3 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/Task.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/TaskSerializationTest.kt
git commit -m "feat(agent): add Task record and TaskStatus enum"
```

---

### Task 1.2: `TaskStore` — persistence with mutex discipline

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/TaskStore.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/session/TaskStorePersistenceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/session/TaskStorePersistenceTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.agent.loop.Task
import com.workflow.orchestrator.agent.loop.TaskStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TaskStorePersistenceTest {

    @Test
    fun `addTask writes tasks json to session dir`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        store.addTask(Task(id = "t-1", subject = "Write tests", description = "Add coverage"))

        val jsonFile = File(tmp, "sessions/sess-1/tasks.json")
        assertTrue(jsonFile.exists(), "tasks.json must exist after addTask")
        assertTrue(jsonFile.readText().contains("Write tests"))
    }

    @Test
    fun `tasks reload from disk via loadFromDisk`(@TempDir tmp: File) = runTest {
        val first = TaskStore(baseDir = tmp, sessionId = "sess-1")
        first.addTask(Task(id = "t-1", subject = "A", description = "a"))
        first.addTask(Task(id = "t-2", subject = "B", description = "b"))

        val second = TaskStore(baseDir = tmp, sessionId = "sess-1")
        second.loadFromDisk()

        val tasks = second.listTasks()
        assertEquals(2, tasks.size)
        assertEquals(setOf("t-1", "t-2"), tasks.map { it.id }.toSet())
    }

    @Test
    fun `updateTask mutates status and persists`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        store.addTask(Task(id = "t-1", subject = "A", description = "a"))

        store.updateTask("t-1") { it.copy(status = TaskStatus.IN_PROGRESS) }

        val updated = store.getTask("t-1")
        assertEquals(TaskStatus.IN_PROGRESS, updated?.status)

        val reloaded = TaskStore(baseDir = tmp, sessionId = "sess-1").also { it.loadFromDisk() }
        assertEquals(TaskStatus.IN_PROGRESS, reloaded.getTask("t-1")?.status)
    }

    @Test
    fun `getTask returns null for unknown id`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        assertNull(store.getTask("nonexistent"))
    }

    @Test
    fun `deleted tasks remain in list`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        store.addTask(Task(id = "t-1", subject = "A", description = "a"))
        store.updateTask("t-1") { it.copy(status = TaskStatus.DELETED) }

        val all = store.listTasks()
        assertEquals(1, all.size)
        assertEquals(TaskStatus.DELETED, all[0].status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :agent:test --tests "*TaskStorePersistenceTest*"
```

Expected: `FAIL` — `TaskStore` doesn't exist.

- [ ] **Step 3: Implement `TaskStore`**

Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/TaskStore.kt`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :agent:test --tests "*TaskStorePersistenceTest*"
```

Expected: `PASS` (5 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/session/TaskStore.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/session/TaskStorePersistenceTest.kt
git commit -m "feat(agent): add TaskStore with atomic JSON persistence and mutex"
```

---

### Task 1.3: Cycle detection tests

The cycle-detection code was landed with `TaskStore` in Task 1.2 (it's part of `updateTask`'s contract). This task adds the test that pins the cycle-rejection behavior.

**Files:**
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/session/TaskStoreCycleDetectionTest.kt`

- [ ] **Step 1: Write the test**

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/session/TaskStoreCycleDetectionTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.agent.loop.Task
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TaskStoreCycleDetectionTest {

    @Test
    fun `direct cycle is rejected`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        store.addTask(Task(id = "A", subject = "A", description = "a"))
        store.addTask(Task(id = "B", subject = "B", description = "b", blockedBy = listOf("A")))

        val ex = assertThrows(TaskStore.CycleException::class.java) {
            runTest { store.updateTask("A") { it.copy(blockedBy = listOf("B")) } }
        }
        assertTrue(ex.message!!.contains("cycle", ignoreCase = true))
    }

    @Test
    fun `transitive cycle is rejected`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        store.addTask(Task(id = "A", subject = "A", description = "a"))
        store.addTask(Task(id = "B", subject = "B", description = "b", blockedBy = listOf("A")))
        store.addTask(Task(id = "C", subject = "C", description = "c", blockedBy = listOf("B")))

        assertThrows(TaskStore.CycleException::class.java) {
            runTest { store.updateTask("A") { it.copy(blockedBy = listOf("C")) } }
        }
    }

    @Test
    fun `acyclic chain is accepted`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        store.addTask(Task(id = "A", subject = "A", description = "a"))
        store.addTask(Task(id = "B", subject = "B", description = "b", blockedBy = listOf("A")))
        store.addTask(Task(id = "C", subject = "C", description = "c", blockedBy = listOf("B")))

        assertEquals(listOf("B"), store.getTask("C")?.blockedBy)
    }
}
```

- [ ] **Step 2: Run test to verify it passes immediately**

```bash
./gradlew :agent:test --tests "*TaskStoreCycleDetectionTest*"
```

Expected: `PASS` (3 tests) — cycle detection was implemented in Task 1.2.

- [ ] **Step 3: Commit**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/session/TaskStoreCycleDetectionTest.kt
git commit -m "test(agent): pin TaskStore cycle-detection contract"
```

---

### Task 1.4: Phase 1 gate

- [ ] **Step 1: Clean rebuild of the agent module**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

Expected: all tests pass, including the three new test files.

- [ ] **Step 2: Plugin verifier**

```bash
./gradlew verifyPlugin
```

Expected: `BUILD SUCCESSFUL`.

---

## Phase 2 — Tool Implementations

Four new tools + AgentService registration + hook-exemption. Old `task_progress` system still operates (removed in Phase 6).

### Task 2.1: `TaskCreateTool`

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskCreateTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskCreateToolTest.kt`

- [ ] **Step 1: Write the failing test**

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskCreateToolTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.TaskStatus
import com.workflow.orchestrator.agent.session.TaskStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TaskCreateToolTest {

    @Test
    fun `creates a pending task with generated id`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskCreateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            params = buildJsonObject {
                put("subject", "Fix login bug")
                put("description", "Users see 500 on /login")
            },
            project = project,
        )

        assertFalse(result.isError, "expected success, got: ${result.content}")
        val task = store.listTasks().single()
        assertEquals("Fix login bug", task.subject)
        assertEquals(TaskStatus.PENDING, task.status)
        assertTrue(task.id.isNotBlank())
    }

    @Test
    fun `missing subject returns error`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskCreateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            params = buildJsonObject { put("description", "no subject") },
            project = project,
        )

        assertTrue(result.isError)
        assertEquals(0, store.listTasks().size)
    }

    @Test
    fun `activeForm and metadata are optional`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskCreateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            params = buildJsonObject {
                put("subject", "Implement OAuth2 flow")
                put("description", "Add OAuth2 to auth middleware")
                put("activeForm", "Implementing OAuth2 flow")
            },
            project = project,
        )

        assertFalse(result.isError)
        val task = store.listTasks().single()
        assertEquals("Implementing OAuth2 flow", task.activeForm)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :agent:test --tests "*TaskCreateToolTest*"
```

Expected: `FAIL` — `TaskCreateTool` doesn't exist.

- [ ] **Step 3: Implement `TaskCreateTool`**

Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskCreateTool.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.loop.Task
import com.workflow.orchestrator.agent.session.TaskStore
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class TaskCreateTool(
    private val storeProvider: () -> TaskStore?,
) : AgentTool {

    override val name = "task_create"

    override val description =
        "Create a new task in the session's task list. Use for work that requires 3+ distinct steps or " +
            "multi-file changes worth user-visible progress tracking. Skip for trivial single-edit work. " +
            "Create ONE task per call — there is no batch API. Prefer concise outcome-focused subjects " +
            "(\"Fix auth bug\", not \"Read file and identify bug and edit line 42 and run tests\"). " +
            "Use the optional activeForm field for the present-continuous string shown while the task is " +
            "in_progress (e.g. subject=\"Implement OAuth2 flow\", activeForm=\"Implementing OAuth2 flow\")."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "subject" to ParameterProperty(
                type = "string",
                description = "Brief imperative title describing the outcome (e.g. \"Fix auth bug in login flow\")."
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Full details — what needs to be done, any acceptance criteria or context."
            ),
            "activeForm" to ParameterProperty(
                type = "string",
                description = "Optional present-continuous form shown in UI while the task is in_progress " +
                    "(e.g. \"Implementing OAuth2 flow\"). Falls back to subject if omitted."
            ),
        ),
        required = listOf("subject", "description"),
    )

    override val allowedWorkers = WorkerType.values().toSet()

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val store = storeProvider()
            ?: return ToolResult(
                content = "Task store is not available in this session.",
                summary = "task_create failed: store unavailable",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )

        val subject = params["subject"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(
                content = "Missing required parameter: subject",
                summary = "task_create failed: missing subject",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )

        val description = params["description"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(
                content = "Missing required parameter: description",
                summary = "task_create failed: missing description",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )

        val activeForm = params["activeForm"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val task = Task(
            id = UUID.randomUUID().toString(),
            subject = subject,
            description = description,
            activeForm = activeForm,
        )
        store.addTask(task)

        return ToolResult(
            content = "Created task ${task.id}: $subject",
            summary = "Created task: $subject",
            tokenEstimate = 20,
            isError = false,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :agent:test --tests "*TaskCreateToolTest*"
```

Expected: `PASS` (3 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskCreateTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskCreateToolTest.kt
git commit -m "feat(agent): add task_create tool"
```

---

### Task 2.2: `TaskUpdateTool`

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskUpdateTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskUpdateToolTest.kt`

- [ ] **Step 1: Write the failing test**

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskUpdateToolTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.Task
import com.workflow.orchestrator.agent.loop.TaskStatus
import com.workflow.orchestrator.agent.session.TaskStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TaskUpdateToolTest {

    @Test
    fun `updates status to in_progress`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "t-1", subject = "A", description = "a"))
        val tool = TaskUpdateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            buildJsonObject {
                put("taskId", "t-1")
                put("status", "in_progress")
            },
            project,
        )

        assertFalse(result.isError)
        assertEquals(TaskStatus.IN_PROGRESS, store.getTask("t-1")?.status)
    }

    @Test
    fun `addBlockedBy appends to existing list`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "A", subject = "A", description = "a"))
        store.addTask(Task(id = "B", subject = "B", description = "b"))
        store.addTask(Task(id = "C", subject = "C", description = "c"))
        val tool = TaskUpdateTool { store }
        val project = mockk<Project>(relaxed = true)

        tool.execute(
            buildJsonObject {
                put("taskId", "C")
                putJsonArray("addBlockedBy") { add("A"); add("B") }
            },
            project,
        )

        assertEquals(listOf("A", "B"), store.getTask("C")?.blockedBy)
    }

    @Test
    fun `unknown taskId returns error`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskUpdateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            buildJsonObject {
                put("taskId", "nonexistent")
                put("status", "completed")
            },
            project,
        )

        assertTrue(result.isError)
    }

    @Test
    fun `cycle-creating update is rejected with error toolresult`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "A", subject = "A", description = "a"))
        store.addTask(Task(id = "B", subject = "B", description = "b", blockedBy = listOf("A")))
        val tool = TaskUpdateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            buildJsonObject {
                put("taskId", "A")
                putJsonArray("addBlockedBy") { add("B") }
            },
            project,
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("cycle", ignoreCase = true))
    }

    @Test
    fun `deleted status marks task deleted but keeps it in store`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "t-1", subject = "A", description = "a"))
        val tool = TaskUpdateTool { store }
        val project = mockk<Project>(relaxed = true)

        tool.execute(
            buildJsonObject {
                put("taskId", "t-1")
                put("status", "deleted")
            },
            project,
        )

        assertEquals(TaskStatus.DELETED, store.getTask("t-1")?.status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :agent:test --tests "*TaskUpdateToolTest*"
```

Expected: `FAIL` — `TaskUpdateTool` doesn't exist.

- [ ] **Step 3: Implement `TaskUpdateTool`**

Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskUpdateTool.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.loop.TaskStatus
import com.workflow.orchestrator.agent.session.TaskStore
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class TaskUpdateTool(
    private val storeProvider: () -> TaskStore?,
) : AgentTool {

    override val name = "task_update"

    override val description =
        "Update a task's status, content, ownership, or dependencies. One status transition per call. " +
            "Read the current task via task_get before updating to avoid stale overwrites. " +
            "Mark a task `deleted` when it is no longer relevant — stale tasks pollute the context " +
            "and confuse progress tracking. Mark `completed` only when the work is actually finished " +
            "(tests passing, changes verified); for in-progress work keep it as `in_progress`."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "taskId" to ParameterProperty(type = "string", description = "ID of the task to update."),
            "status" to ParameterProperty(
                type = "string",
                description = "New status — one of: pending, in_progress, completed, deleted."
            ),
            "subject" to ParameterProperty(type = "string", description = "New subject (imperative form)."),
            "description" to ParameterProperty(type = "string", description = "New description."),
            "activeForm" to ParameterProperty(type = "string", description = "New present-continuous form."),
            "owner" to ParameterProperty(type = "string", description = "New owner (agent name)."),
            "addBlocks" to ParameterProperty(
                type = "array",
                description = "Task IDs that should be blocked by this task.",
                items = ParameterProperty(type = "string", description = "Task ID"),
            ),
            "addBlockedBy" to ParameterProperty(
                type = "array",
                description = "Task IDs that must complete before this task can start.",
                items = ParameterProperty(type = "string", description = "Task ID"),
            ),
        ),
        required = listOf("taskId"),
    )

    override val allowedWorkers = WorkerType.values().toSet()

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val store = storeProvider()
            ?: return errorResult("Task store is not available in this session.")

        val taskId = params["taskId"]?.jsonPrimitive?.content
            ?: return errorResult("Missing required parameter: taskId")

        val statusArg = params["status"]?.jsonPrimitive?.content?.uppercase()
        val parsedStatus: TaskStatus? = statusArg?.let {
            runCatching { TaskStatus.valueOf(it) }.getOrNull()
                ?: return errorResult("Invalid status '$statusArg'. Expected: pending, in_progress, completed, deleted.")
        }

        val addBlocks = params["addBlocks"]?.asStringList().orEmpty()
        val addBlockedBy = params["addBlockedBy"]?.asStringList().orEmpty()
        val newSubject = params["subject"]?.jsonPrimitive?.content
        val newDescription = params["description"]?.jsonPrimitive?.content
        val newActiveForm = params["activeForm"]?.jsonPrimitive?.content
        val newOwner = params["owner"]?.jsonPrimitive?.content

        val updated = try {
            store.updateTask(taskId) { t ->
                t.copy(
                    status = parsedStatus ?: t.status,
                    subject = newSubject ?: t.subject,
                    description = newDescription ?: t.description,
                    activeForm = newActiveForm ?: t.activeForm,
                    owner = newOwner ?: t.owner,
                    blocks = (t.blocks + addBlocks).distinct(),
                    blockedBy = (t.blockedBy + addBlockedBy).distinct(),
                )
            }
        } catch (e: TaskStore.CycleException) {
            return errorResult("Update rejected (cycle): ${e.message}")
        }

        if (updated == null) return errorResult("Task not found: $taskId")

        return ToolResult(
            content = "Updated task $taskId" + (parsedStatus?.let { " to status=${it.name.lowercase()}" } ?: ""),
            summary = "Updated task: ${updated.subject}",
            tokenEstimate = 20,
            isError = false,
        )
    }

    private fun errorResult(msg: String) = ToolResult(
        content = msg,
        summary = "task_update failed: $msg",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )

    private fun kotlinx.serialization.json.JsonElement.asStringList(): List<String>? =
        runCatching { this.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :agent:test --tests "*TaskUpdateToolTest*"
```

Expected: `PASS` (5 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskUpdateTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskUpdateToolTest.kt
git commit -m "feat(agent): add task_update tool with cycle-rejection and dependency patching"
```

---

### Task 2.3: `TaskListTool` (minimal return fields)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskListTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskListToolTest.kt`

Per research finding: Claude Code's `TaskList` returns *minimal* fields — id, subject, status, owner, blockedBy. Full description/metadata require `TaskGet`. This keeps list-view cheap for context.

- [ ] **Step 1: Write the failing test**

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskListToolTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.Task
import com.workflow.orchestrator.agent.loop.TaskStatus
import com.workflow.orchestrator.agent.session.TaskStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TaskListToolTest {

    @Test
    fun `list returns summary content with minimal fields`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "t-1", subject = "A", description = "a-long-description"))
        store.addTask(Task(id = "t-2", subject = "B", description = "b-long-description", status = TaskStatus.IN_PROGRESS))
        val tool = TaskListTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject { }, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("t-1"))
        assertTrue(result.content.contains("A"))
        assertTrue(result.content.contains("B"))
        assertTrue(result.content.contains("in_progress"))
        assertFalse(
            result.content.contains("a-long-description"),
            "description must NOT be in list output (force task_get for details)",
        )
    }

    @Test
    fun `empty list returns explanatory message`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskListTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject { }, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("No tasks", ignoreCase = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :agent:test --tests "*TaskListToolTest*"
```

Expected: `FAIL`.

- [ ] **Step 3: Implement `TaskListTool`**

Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskListTool.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.session.TaskStore
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject

class TaskListTool(
    private val storeProvider: () -> TaskStore?,
) : AgentTool {

    override val name = "task_list"

    override val description =
        "List all tasks in the session with minimal fields (id, subject, status, owner, blockedBy). " +
            "Does NOT include description or metadata — use task_get with an id for those. Prefer " +
            "working on tasks in id order (lowest first) when multiple are available; earlier tasks " +
            "often set up context for later ones. Tasks with non-empty blockedBy cannot start until " +
            "those dependencies are completed."

    override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())

    override val allowedWorkers = WorkerType.values().toSet()

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val store = storeProvider()
            ?: return ToolResult(
                content = "Task store is not available in this session.",
                summary = "task_list failed: store unavailable",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )

        val tasks = store.listTasks()
        if (tasks.isEmpty()) {
            return ToolResult(
                content = "No tasks in this session.",
                summary = "No tasks",
                tokenEstimate = 5,
                isError = false,
            )
        }

        val rendered = tasks.joinToString("\n") { t ->
            val owner = t.owner?.let { " [owner: $it]" }.orEmpty()
            val blockedBy = if (t.blockedBy.isEmpty()) "" else " [blockedBy: ${t.blockedBy.joinToString(",")}]"
            "- ${t.id}  ${t.status.name.lowercase()}  ${t.subject}$owner$blockedBy"
        }

        return ToolResult(
            content = rendered,
            summary = "${tasks.size} tasks",
            tokenEstimate = rendered.length / 4,
            isError = false,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :agent:test --tests "*TaskListToolTest*"
```

Expected: `PASS` (2 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskListTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskListToolTest.kt
git commit -m "feat(agent): add task_list tool returning minimal summary fields"
```

---

### Task 2.4: `TaskGetTool`

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskGetTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskGetToolTest.kt`

- [ ] **Step 1: Write the failing test**

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskGetToolTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.Task
import com.workflow.orchestrator.agent.session.TaskStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TaskGetToolTest {

    @Test
    fun `returns full task details including description`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "t-1", subject = "A", description = "detailed description here"))
        val tool = TaskGetTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject { put("taskId", "t-1") }, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("detailed description here"))
        assertTrue(result.content.contains("t-1"))
        assertTrue(result.content.contains("A"))
    }

    @Test
    fun `unknown id returns error`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskGetTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject { put("taskId", "nope") }, project)

        assertTrue(result.isError)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :agent:test --tests "*TaskGetToolTest*"
```

Expected: `FAIL`.

- [ ] **Step 3: Implement `TaskGetTool`**

Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskGetTool.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.session.TaskStore
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class TaskGetTool(
    private val storeProvider: () -> TaskStore?,
) : AgentTool {

    override val name = "task_get"

    override val description =
        "Retrieve the full details of a single task — subject, description, status, activeForm, owner, " +
            "blocks, blockedBy, timestamps. Use when you need context beyond what task_list provides. " +
            "Verify `blockedBy` is empty before starting work on a pending task."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "taskId" to ParameterProperty(type = "string", description = "ID of the task to retrieve.")
        ),
        required = listOf("taskId"),
    )

    override val allowedWorkers = WorkerType.values().toSet()

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val store = storeProvider()
            ?: return ToolResult(
                content = "Task store is not available in this session.",
                summary = "task_get failed: store unavailable",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )

        val taskId = params["taskId"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: taskId",
                summary = "task_get failed: missing taskId",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )

        val task = store.getTask(taskId)
            ?: return ToolResult(
                content = "Task not found: $taskId",
                summary = "task_get failed: unknown id",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )

        val body = buildString {
            appendLine("id: ${task.id}")
            appendLine("subject: ${task.subject}")
            appendLine("status: ${task.status.name.lowercase()}")
            task.activeForm?.let { appendLine("activeForm: $it") }
            task.owner?.let { appendLine("owner: $it") }
            if (task.blocks.isNotEmpty()) appendLine("blocks: ${task.blocks.joinToString(",")}")
            if (task.blockedBy.isNotEmpty()) appendLine("blockedBy: ${task.blockedBy.joinToString(",")}")
            appendLine()
            appendLine("description:")
            appendLine(task.description)
        }

        return ToolResult(
            content = body,
            summary = "task_get: ${task.subject}",
            tokenEstimate = body.length / 4,
            isError = false,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :agent:test --tests "*TaskGetToolTest*"
```

Expected: `PASS` (2 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskGetTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskGetToolTest.kt
git commit -m "feat(agent): add task_get tool returning full task details"
```

---

### Task 2.5: Register tools in `AgentService` and wire `TaskStore` lifecycle

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` (around the existing `registerAllTools()` function near line 450, and session lifecycle around line 258)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt` — add Task* tools to the `HOOK_EXEMPT` set

- [ ] **Step 1: Write the integration test**

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskToolsRegistrationTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TaskToolsRegistrationTest {

    @Test
    fun `task tool names are stable`() {
        assertEquals("task_create", TaskCreateTool { null }.name)
        assertEquals("task_update", TaskUpdateTool { null }.name)
        assertEquals("task_list", TaskListTool { null }.name)
        assertEquals("task_get", TaskGetTool { null }.name)
    }

    @Test
    fun `task tools available to all worker types`() {
        val allWorkers = com.workflow.orchestrator.agent.tools.WorkerType.values().toSet()
        assertEquals(allWorkers, TaskCreateTool { null }.allowedWorkers)
        assertEquals(allWorkers, TaskUpdateTool { null }.allowedWorkers)
        assertEquals(allWorkers, TaskListTool { null }.allowedWorkers)
        assertEquals(allWorkers, TaskGetTool { null }.allowedWorkers)
    }
}
```

- [ ] **Step 2: Run test to verify it passes immediately**

```bash
./gradlew :agent:test --tests "*TaskToolsRegistrationTest*"
```

Expected: `PASS` (2 tests).

- [ ] **Step 3: Add `TaskStore` to the session lifecycle in `AgentService`**

Locate the session-start code path (near `MessageStateHandler` creation, roughly lines 258 and 450 of `AgentService.kt`). Add:

```kotlin
// At class level with other session-scoped fields:
private var taskStore: TaskStore? = null

// Where MessageStateHandler is created for a session, alongside it:
taskStore = TaskStore(baseDir = projectAgentDir, sessionId = sessionId).also { it.loadFromDisk() }

// When a session ends / new chat is started, null it out:
taskStore = null
```

Expose a provider function:
```kotlin
fun currentTaskStore(): TaskStore? = taskStore
```

- [ ] **Step 4: Register the four tools in `registerAllTools()`**

In `AgentService.kt` `registerAllTools()` (around line 450), add:

```kotlin
toolRegistry.registerCore(TaskCreateTool { currentTaskStore() })
toolRegistry.registerCore(TaskUpdateTool { currentTaskStore() })
toolRegistry.registerCore(TaskListTool { currentTaskStore() })
toolRegistry.registerCore(TaskGetTool { currentTaskStore() })
```

- [ ] **Step 5: Add tools to `HOOK_EXEMPT` in `AgentLoop.kt`**

In `AgentLoop.kt` companion object, add (or create if missing):

```kotlin
/** Tools that bypass PreToolUse/PostToolUse hooks. Ported from Claude Code's task-system behavior. */
private val HOOK_EXEMPT: Set<String> = setOf("task_create", "task_update", "task_list", "task_get")
```

Wrap the `hookManager.dispatch(PRE_TOOL_USE, ...)` block (around line 1241) with:

```kotlin
if (toolName !in HOOK_EXEMPT && hookManager != null && hookManager.hasHooks(HookType.PRE_TOOL_USE)) {
    // ... existing dispatch logic ...
}
```

Do the same guard around any POST_TOOL_USE dispatch.

- [ ] **Step 6: Run full agent test suite**

```bash
./gradlew :agent:test
```

Expected: all tests pass. Existing tests still green; new registration test green.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskToolsRegistrationTest.kt
git commit -m "feat(agent): register task tools in AgentService and mark them hook-exempt"
```

---

### Task 2.6: Phase 2 gate

- [ ] **Step 1: Full agent test suite**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

Expected: all tests pass (existing ~112 test files + new task tests).

- [ ] **Step 2: `verifyPlugin`**

```bash
./gradlew verifyPlugin
```

Expected: `BUILD SUCCESSFUL`.

---

## Phase 3 — System Prompt Rewiring

Swap the task-progress source from `ContextManager.taskProgressMarkdown` to `TaskStore`. `SystemPrompt` section 2 reads typed tasks. Add stale-task cleanup guidance to Rules. Regenerate snapshots.

### Task 3.1: `ContextManager` receives `TaskStore` via setter

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt`

- [ ] **Step 1: Write the failing test**

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ContextManagerTaskStoreTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.TaskStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContextManagerTaskStoreTest {
    @Test
    fun `renderTaskProgressMarkdown renders tasks from attached store`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "t-1", subject = "Write tests", description = "…", status = TaskStatus.COMPLETED))
        store.addTask(Task(id = "t-2", subject = "Implement feature", description = "…", status = TaskStatus.IN_PROGRESS))
        store.addTask(Task(id = "t-3", subject = "Deploy", description = "…"))

        val cm = ContextManager(maxInputTokens = 150_000).also { it.attachTaskStore(store) }

        val md = cm.renderTaskProgressMarkdown()
        assertNotNull(md)
        assertTrue(md!!.contains("[x] Write tests"))
        assertTrue(md.contains("[ ] Implement feature"))
        assertTrue(md.contains("[ ] Deploy"))
    }

    @Test
    fun `renderTaskProgressMarkdown returns null when no store or no tasks`(@TempDir tmp: File) = runTest {
        val cmNoStore = ContextManager(maxInputTokens = 150_000)
        assertNull(cmNoStore.renderTaskProgressMarkdown())

        val cmEmpty = ContextManager(maxInputTokens = 150_000).also {
            it.attachTaskStore(TaskStore(baseDir = tmp, sessionId = "empty"))
        }
        assertNull(cmEmpty.renderTaskProgressMarkdown())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :agent:test --tests "*ContextManagerTaskStoreTest*"
```

Expected: `FAIL` — `attachTaskStore` / `renderTaskProgressMarkdown` don't exist.

- [ ] **Step 3: Add `TaskStore` hook to `ContextManager`**

Add to `ContextManager.kt` near line 104 (replacing the `taskProgressMarkdown` field):

```kotlin
// Remove this line (will be fully deleted in Phase 6; for now leave in place for parallel operation):
// private var taskProgressMarkdown: String? = null

// ADD: task store reference
private var taskStore: com.workflow.orchestrator.agent.session.TaskStore? = null

fun attachTaskStore(store: com.workflow.orchestrator.agent.session.TaskStore) {
    taskStore = store
}

/**
 * Render current tasks as a Markdown checklist. Returns null if no store or no tasks.
 * Format: "- [x] completed" / "- [ ] other".
 */
fun renderTaskProgressMarkdown(): String? {
    val store = taskStore ?: return null
    val tasks = store.listTasks().filter { it.status != TaskStatus.DELETED }
    if (tasks.isEmpty()) return null
    return tasks.joinToString("\n") { t ->
        val box = if (t.status == TaskStatus.COMPLETED) "[x]" else "[ ]"
        "- $box ${t.subject}"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :agent:test --tests "*ContextManagerTaskStoreTest*"
```

Expected: `PASS` (2 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ContextManagerTaskStoreTest.kt
git commit -m "feat(agent): ContextManager can render task progress from TaskStore"
```

---

### Task 3.2: `SystemPrompt` Section 2 (Task Progress) — full rewrite

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt` (lines 141–167 — the `taskProgress(progress: String?)` function)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` — attach `TaskStore` to `ContextManager` on session start; pass `contextManager.renderTaskProgressMarkdown()` into `SystemPrompt.build(taskProgress = ...)`

- [ ] **Step 1: Replace the `taskProgress()` section body**

In `SystemPrompt.kt`, replace the function body at lines 144–167 with:

```kotlin
/**
 * Section 2: Task Progress
 * Rewritten for typed task system (task_create/task_update/task_list/task_get).
 * Replaces Cline's task_progress-markdown-parameter-on-every-tool pattern.
 * The `progress` arg is a pre-rendered Markdown checklist from
 * ContextManager.renderTaskProgressMarkdown(), shown at the bottom for LLM awareness.
 */
private fun taskProgress(progress: String?): String = """TASK MANAGEMENT

Track work using the task_create, task_update, task_list, and task_get tools. These are dedicated tools with typed state — not a parameter on other tool calls.

**When to create tasks:**
- Work that requires 3+ distinct steps or touches multiple files.
- Work spanning multiple phases where user-visible progress tracking helps.
- Skip tasks for trivial single-edit fixes; skip purely informational exchanges.

**How to create tasks:**
- One task per task_create call — there is no batch API. Creating 10 tasks requires 10 calls; this is intentional back-pressure against over-decomposition.
- Use imperative outcome-focused subjects ("Fix auth bug in login flow"), NOT action-by-action breakdowns ("Read file, then edit line 42, then run tests").
- Provide a description with context and acceptance criteria; subjects stay concise.
- Optionally provide activeForm (present-continuous, e.g. "Fixing auth bug") — shown in the UI while the task is in_progress.

**Status workflow:**
- pending → in_progress → completed. Mark deleted when a task is no longer relevant.
- Flip to in_progress when you begin work; flip to completed the moment the work is verified (tests passing, changes applied). Do not batch.
- Only one task should typically be in_progress at a time per worker.
- **Stale tasks are actively harmful.** When a task is no longer relevant or has been superseded, mark it deleted — do not leave it in the list.

**Dependencies:**
- Use addBlockedBy on task_update to express "this task can't start until X and Y complete."
- Before starting work on a pending task, check task_get — verify its blockedBy list is empty.
- Cycles are rejected by the store; do not create circular dependencies.

**Plan mode vs act mode:**
- Tasks are available in both modes, but the common pattern is act-mode task creation as work begins. Plan mode is primarily for strategic exploration (writing the plan document). Creating tasks during plan mode is permitted but unusual.

**Reading the task list:**
- task_list returns minimal fields (id, subject, status, owner, blockedBy) — cheap to call often.
- task_get returns full details including description. Use when you need context beyond task_list.
""" + (if (progress.isNullOrBlank()) "" else "\nCurrent tasks:\n$progress")
```

Note: the function now returns `String` (non-null) instead of `String?` — it always contributes a section. Update the call site:

At line 58–62 of `SystemPrompt.kt`:

```kotlin
// BEFORE:
// taskProgress(taskProgress)?.let {
//     append(SECTION_SEP)
//     append(it)
// }

// AFTER:
append(SECTION_SEP)
append(taskProgress(taskProgress))
```

- [ ] **Step 2: Attach `TaskStore` to `ContextManager` and wire into `SystemPrompt.build`**

In `AgentService.kt`, wherever `ContextManager` is instantiated for a session, add:

```kotlin
contextManager.attachTaskStore(taskStore ?: return@apply)
```

Wherever `SystemPrompt.build(...)` is called with `taskProgress = ...`, change the source:

```kotlin
// BEFORE:
// taskProgress = contextManager.getTaskProgress()

// AFTER:
taskProgress = contextManager.renderTaskProgressMarkdown()
```

- [ ] **Step 3: Regenerate golden snapshots**

```bash
./gradlew :agent:test --tests "*generate all golden snapshots*"
```

Review the regenerated `prompt-snapshots/*.txt` diffs. Expected: Section 2 replaced with the new `TASK MANAGEMENT` content in all seven files.

- [ ] **Step 4: Verify snapshots**

```bash
./gradlew :agent:test --tests "*SNAPSHOT*"
```

Expected: `PASS`.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/test/resources/prompt-snapshots/
git commit -m "feat(agent): rewrite SystemPrompt Section 2 for typed task system"
```

---

### Task 3.3: `SystemPrompt` Section 5 (Capabilities) — add task_* tools to the core tools listing

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt` (around line 232, in the `capabilities()` function)

- [ ] **Step 1: Update the core tools listing**

In `SystemPrompt.kt` around line 232 (the `capabilities()` function's "Core tools (always available)" bullet list), add a new line after the "Communication" line:

```kotlin
// BEFORE (line 232):
// appendLine("- Communication: ask_followup_question, attempt_completion, plan_mode_respond, enable_plan_mode")

// AFTER:
appendLine("- Communication: ask_followup_question, attempt_completion, plan_mode_respond, enable_plan_mode")
appendLine("- Tasks: task_create, task_update, task_list, task_get")
```

- [ ] **Step 2: Regenerate golden snapshots**

```bash
./gradlew :agent:test --tests "*generate all golden snapshots*"
./gradlew :agent:test --tests "*SNAPSHOT*"
```

Expected: all seven snapshots pick up the new bullet, nothing else changes.

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt \
        agent/src/test/resources/prompt-snapshots/
git commit -m "feat(agent): list task_* tools in SystemPrompt Capabilities section"
```

---

### Task 3.4: `LoopDetector.IGNORED_PARAMS` — remove `task_progress`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/LoopDetector.kt` (line 74)

The loop detector ignored `task_progress` when computing hash similarity (because the LLM rewrites the markdown on every call). With the param gone, the entry is obsolete. It can't be removed *before* Phase 6.2 (which deletes the param) without a brief window where identical tool calls differing only in task_progress could trigger loop detection. Remove in Phase 6 alongside the param deletion — deferred to Task 6.2 subtask.

- [ ] **Step 1: Acknowledge deferral**

No action in Phase 3. This is recorded under Task 6.2 Step 2 (the task_progress extraction deletion) — remove `task_progress` from `LoopDetector.IGNORED_PARAMS` in the same commit.

---

### Task 3.5: Update three bundled skill files

**Files:**
- Modify: `agent/src/main/resources/skills/writing-plans/SKILL.md`
- Modify: `agent/src/main/resources/skills/subagent-driven/SKILL.md`
- Modify: `agent/src/main/resources/skills/brainstorm/SKILL.md`

These skills tell the LLM to use `task_progress` on tool calls. Once Phase 6 deletes that parameter, any skill still recommending it would steer the LLM toward a non-existent API. Update skills **before** Phase 6 so the transition is clean.

- [ ] **Step 1: Rewrite `writing-plans/SKILL.md` "How the Plan UI Works" section**

Replace lines 20–33 with:

```markdown
## How the Plan UI Works

Your plan flows through a specific UI pipeline. Understanding this ensures your plan renders correctly:

1. **You call `plan_mode_respond(response=plan_markdown)`** — a full markdown plan for the document viewer (with code blocks, tables, etc.)
2. **Plan summary card** renders in chat — shows the plan summary, approve/revise buttons, and comment count
3. **Plan editor** opens as a full tab when user clicks "View Plan" — the user sees the full markdown with line numbers and can **add inline comments on specific lines**
4. **Approval** switches to act mode. You then use the task_create / task_update / task_list / task_get tools to track execution progress — these flow through the same PlanProgressWidget as a task checklist.
5. **Revision** sends the user's line-level comments back to you. You revise and call `plan_mode_respond` again.

**Execution tracking after approval:** Once in act mode, create tasks via `task_create` (one call per task, outcome-focused subjects) as you scope work. Update status via `task_update` as work progresses (pending → in_progress → completed). Mark deleted for anything superseded. The user sees these in the progress widget in real time.
```

Also search the rest of the file (lines 60, 111, 172, 174) for other `task_progress` or `steps` references:

- Line ~60: `## Plan Markdown Format` section — remove mention of `steps` parameter driving progress card; plans don't need a rigid task-header structure since tasks are tracked separately via `task_create`.
- Lines 111: remove "The `steps` array drives the progress card. Keep titles concise… During execution, your `task_progress` checklist should use matching titles…" — replace with: "After approval, create tasks via task_create (one call per outcome-sized work item). Tasks are separate from the plan document; the plan describes strategy, tasks track execution."
- Lines 172, 174: remove `task_progress` references in the Execution Options section. Replace line 174 with: "During execution (either approach), use task_create and task_update to track progress. Tasks appear in the progress widget with spinner/check icons reflecting status."

- [ ] **Step 2: Update `subagent-driven/SKILL.md`**

Search the file for `task_progress`:
- Line 34: `h. Update progress via task_progress checklist on next tool call` → replace with: `h. Update progress via task_update calls as subagent completes each task`
- Line 248 example: replace the `task_progress="…"` example with a `task_update(taskId="…", status="completed")` example.

- [ ] **Step 3: Update `brainstorm/SKILL.md`**

Lines 151 and 156 describe the brainstorm skill's outputs:
- Line 151: `- \`task_progress\` — a markdown checklist summarizing the tasks` → replace with: `- call task_create for each outcome-sized work item identified in the brainstorm (one tool call per task). Use imperative subjects.`
- Line 156: the `task_progress="- [ ] …\n- [ ] …"` example → replace with a sequence of `task_create(subject="…", description="…")` calls, one per item.

- [ ] **Step 4: Verify no `task_progress` references remain in skills**

```bash
grep -rn "task_progress" agent/src/main/resources/skills/
```

Expected: empty output.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/resources/skills/writing-plans/SKILL.md \
        agent/src/main/resources/skills/subagent-driven/SKILL.md \
        agent/src/main/resources/skills/brainstorm/SKILL.md
git commit -m "docs(skills): update writing-plans/subagent-driven/brainstorm to reference task_* tools"
```

---

### Task 3.6: Phase 3 gate

- [ ] **Step 1: Full agent test suite**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

Expected: all tests pass, including the seven regenerated snapshots.

- [ ] **Step 2: `verifyPlugin`**

```bash
./gradlew verifyPlugin
```

Expected: `BUILD SUCCESSFUL`.

---

## Phase 4 — Bridge Contracts

Delete `plan-step-update.json`. Modify `plan-data.json`. Add three new task contracts. Update `bridge-contracts.test.ts`.

### Task 4.1: Delete `plan-step-update.json` contract and its tests

**Files:**
- Delete: `agent/webview/src/__tests__/contracts/plan-step-update.json`
- Modify: `agent/webview/src/__tests__/bridge-contracts.test.ts` — remove the import and the "Plan Step Update Contract" describe block (lines 20 and 127–160)

- [ ] **Step 1: Delete the contract file**

```bash
git rm agent/webview/src/__tests__/contracts/plan-step-update.json
```

- [ ] **Step 2: Remove the import from `bridge-contracts.test.ts`**

Delete line 20:
```typescript
import planStepUpdateContract from './contracts/plan-step-update.json';
```

- [ ] **Step 3: Remove the `describe` block**

Delete lines 127–160 (the entire `// ── Plan Step Update Contract ──` section and its `describe` block).

- [ ] **Step 4: Run webview tests**

```bash
cd agent/webview && npm test -- --run bridge-contracts
```

Expected: remaining contract tests pass; `Plan Step Update Contract` tests no longer execute.

- [ ] **Step 5: Commit**

```bash
git add agent/webview/src/__tests__/bridge-contracts.test.ts
git commit -m "chore(webview): remove plan-step-update bridge contract and tests"
```

---

### Task 4.2: Modify `plan-data.json` to drop `steps`

**Files:**
- Modify: `agent/webview/src/__tests__/contracts/plan-data.json`

- [ ] **Step 1: Rewrite `plan-data.json`**

Replace its contents with:

```json
{
  "description": "Contract for renderPlan(json) bridge function. Kotlin sends this JSON, React parses it as AgentPlanData. steps field removed — progress now tracked via task_* bridges.",
  "payload": {
    "goal": "Fix null pointer exception in PaymentService.processRefund()",
    "approach": "Add null guard clause at method entry before accessing customer reference",
    "title": "Fix NPE in PaymentService",
    "testing": "Run `./gradlew :payment:test` and verify PaymentServiceTest.testRefundWithNullCustomer passes",
    "approved": false
  },
  "required_fields": ["goal"],
  "optional_fields": ["approach", "title", "testing", "approved"]
}
```

- [ ] **Step 2: Update `bridge-contracts.test.ts` assertions**

Remove step-shape assertions around line 120–124 (the ones iterating `data.steps`). The `Plan Data` describe block should only assert on `goal`, `approach`, `title`, `testing`, `approved`.

- [ ] **Step 3: Run webview tests**

```bash
cd agent/webview && npm test -- --run bridge-contracts
```

Expected: `PASS`.

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/__tests__/contracts/plan-data.json \
        agent/webview/src/__tests__/bridge-contracts.test.ts
git commit -m "chore(webview): remove steps from plan-data contract"
```

---

### Task 4.3: Add `task-create.json`, `task-update.json`, `task-list.json` contracts

**Files:**
- Create: `agent/webview/src/__tests__/contracts/task-create.json`
- Create: `agent/webview/src/__tests__/contracts/task-update.json`
- Create: `agent/webview/src/__tests__/contracts/task-list.json`

- [ ] **Step 1: Create `task-create.json`**

```json
{
  "description": "Contract for applyTaskCreate(task) bridge function. Kotlin sends a newly created task; React appends it to the tasks array in chatStore.",
  "payload": {
    "id": "task-abc123",
    "subject": "Fix auth bug in login flow",
    "description": "Users see 500 on /login when cookies expire mid-request.",
    "activeForm": "Fixing auth bug",
    "status": "pending",
    "owner": null,
    "blocks": [],
    "blockedBy": [],
    "createdAt": 1744985000000,
    "updatedAt": 1744985000000
  },
  "required_fields": ["id", "subject", "description", "status"],
  "optional_fields": ["activeForm", "owner", "blocks", "blockedBy", "createdAt", "updatedAt"],
  "valid_statuses": ["pending", "in_progress", "completed", "deleted"]
}
```

- [ ] **Step 2: Create `task-update.json`**

```json
{
  "description": "Contract for applyTaskUpdate(task) bridge function. Kotlin sends a task after TaskUpdate executes; React replaces the matching task in chatStore by id.",
  "payload": {
    "id": "task-abc123",
    "subject": "Fix auth bug in login flow",
    "description": "Users see 500 on /login when cookies expire mid-request.",
    "activeForm": "Fixing auth bug",
    "status": "in_progress",
    "owner": "coder-agent",
    "blocks": [],
    "blockedBy": [],
    "createdAt": 1744985000000,
    "updatedAt": 1744985500000
  },
  "required_fields": ["id", "subject", "description", "status"],
  "optional_fields": ["activeForm", "owner", "blocks", "blockedBy", "createdAt", "updatedAt"],
  "valid_statuses": ["pending", "in_progress", "completed", "deleted"]
}
```

- [ ] **Step 3: Create `task-list.json`**

```json
{
  "description": "Contract for setTasks(tasks) bridge function. Kotlin sends the full task array (session rehydration or bulk sync); React replaces chatStore.tasks with this value.",
  "payload": [
    {
      "id": "task-1",
      "subject": "Write tests",
      "description": "Add coverage for auth flow",
      "status": "completed",
      "blocks": [],
      "blockedBy": []
    },
    {
      "id": "task-2",
      "subject": "Deploy fix",
      "description": "Push to staging",
      "status": "pending",
      "blocks": [],
      "blockedBy": ["task-1"]
    }
  ],
  "required_fields_per_task": ["id", "subject", "description", "status"]
}
```

- [ ] **Step 4: Add contract tests to `bridge-contracts.test.ts`**

Add to `bridge-contracts.test.ts`:

```typescript
import taskCreateContract from './contracts/task-create.json';
import taskUpdateContract from './contracts/task-update.json';
import taskListContract from './contracts/task-list.json';

describe('Contract: applyTaskCreate (Kotlin → React)', () => {
  it('required fields present', () => {
    for (const field of taskCreateContract.required_fields) {
      expect(Object.keys(taskCreateContract.payload)).toContain(field);
    }
  });
  it('status is a valid enum value', () => {
    expect(taskCreateContract.valid_statuses).toContain(taskCreateContract.payload.status);
  });
});

describe('Contract: applyTaskUpdate (Kotlin → React)', () => {
  it('required fields present', () => {
    for (const field of taskUpdateContract.required_fields) {
      expect(Object.keys(taskUpdateContract.payload)).toContain(field);
    }
  });
  it('status is a valid enum value', () => {
    expect(taskUpdateContract.valid_statuses).toContain(taskUpdateContract.payload.status);
  });
});

describe('Contract: setTasks (Kotlin → React)', () => {
  it('payload is an array', () => {
    expect(Array.isArray(taskListContract.payload)).toBe(true);
  });
  it('every task has required fields', () => {
    for (const task of taskListContract.payload) {
      for (const field of taskListContract.required_fields_per_task) {
        expect(Object.keys(task as object)).toContain(field);
      }
    }
  });
});
```

- [ ] **Step 5: Run webview tests**

```bash
cd agent/webview && npm test -- --run bridge-contracts
```

Expected: three new `describe` blocks pass.

- [ ] **Step 6: Commit**

```bash
git add agent/webview/src/__tests__/contracts/task-create.json \
        agent/webview/src/__tests__/contracts/task-update.json \
        agent/webview/src/__tests__/contracts/task-list.json \
        agent/webview/src/__tests__/bridge-contracts.test.ts
git commit -m "feat(webview): add task-create/update/list bridge contracts"
```

---

### Task 4.4: Phase 4 gate

- [ ] **Step 1: Webview test suite**

```bash
cd agent/webview && npm test
```

Expected: all tests pass.

- [ ] **Step 2: Webview build smoke**

```bash
cd agent/webview && npm run build
```

Expected: `BUILD SUCCESSFUL`.

---

## Phase 5 — UI Migration

Add `Task` type to the bridge, wire `tasks` into `chatStore`, rewire `PlanProgressWidget` to read from `tasks`, strip `PlanSummaryCard` of step rendering, wire Kotlin-side pushes.

### Task 5.1: Add `Task` type and status enum to `bridge/types.ts`

**Files:**
- Modify: `agent/webview/src/bridge/types.ts`

- [ ] **Step 1: Add the type definitions**

Add to `bridge/types.ts`:

```typescript
export type TaskStatus = 'pending' | 'in_progress' | 'completed' | 'deleted';

export interface Task {
  id: string;
  subject: string;
  description: string;
  activeForm?: string;
  status: TaskStatus;
  owner?: string;
  blocks: string[];
  blockedBy: string[];
  createdAt?: number;
  updatedAt?: number;
}
```

Also remove the `steps?: PlanStep[]` field from the `Plan` type and delete the `PlanStep` / `PlanStepStatus` definitions if they're declared here.

- [ ] **Step 2: Commit (no test yet — types are load-bearing for later tasks)**

```bash
git add agent/webview/src/bridge/types.ts
git commit -m "feat(webview): add Task type and TaskStatus to bridge types"
```

---

### Task 5.2: Add `tasks` state to `chatStore`

**Files:**
- Modify: `agent/webview/src/stores/chatStore.ts`

- [ ] **Step 1: Add the state and actions**

Add to `chatStore.ts`:

```typescript
import type { Task } from '@/bridge/types';

// inside the state interface:
tasks: Task[];

// inside the actions:
setTasks: (tasks: Task[]) => set({ tasks }),
applyTaskCreate: (task: Task) => set(state => ({ tasks: [...state.tasks, task] })),
applyTaskUpdate: (task: Task) => set(state => ({
  tasks: state.tasks.map(t => t.id === task.id ? task : t),
})),

// initial value:
tasks: [],
```

- [ ] **Step 2: Commit**

```bash
git add agent/webview/src/stores/chatStore.ts
git commit -m "feat(webview): add tasks state and actions to chatStore"
```

---

### Task 5.3: Wire `applyTaskCreate` / `applyTaskUpdate` / `setTasks` in `jcef-bridge.ts`

**Files:**
- Modify: `agent/webview/src/bridge/jcef-bridge.ts`
- Modify: `agent/webview/src/bridge/globals.d.ts`

- [ ] **Step 1: Register bridge callbacks**

In `jcef-bridge.ts` `initBridge()`:

```typescript
(window as any)._applyTaskCreate = (task: Task) => {
  useChatStore.getState().applyTaskCreate(task);
};
(window as any)._applyTaskUpdate = (task: Task) => {
  useChatStore.getState().applyTaskUpdate(task);
};
(window as any)._setTasks = (tasks: Task[]) => {
  useChatStore.getState().setTasks(tasks);
};
```

- [ ] **Step 2: Update `globals.d.ts`**

```typescript
declare global {
  interface Window {
    _applyTaskCreate?: (task: Task) => void;
    _applyTaskUpdate?: (task: Task) => void;
    _setTasks?: (tasks: Task[]) => void;
    // ... existing globals ...
  }
}
```

Also remove any `_updatePlanStep` declarations.

- [ ] **Step 3: Commit**

```bash
git add agent/webview/src/bridge/jcef-bridge.ts agent/webview/src/bridge/globals.d.ts
git commit -m "feat(webview): add task bridge callbacks (create/update/setTasks)"
```

---

### Task 5.4: Rewire `PlanProgressWidget` to read from `tasks`

**Files:**
- Modify: `agent/webview/src/components/agent/PlanProgressWidget.tsx`

- [ ] **Step 1: Rewrite the component**

Replace the file with:

```tsx
import { useChatStore } from '@/stores/chatStore';
import { Plan as PlanCard } from '@/components/ui/tool-ui/plan';
import type { Task, TaskStatus } from '@/bridge/types';

function mapStatus(s: TaskStatus): 'pending' | 'in_progress' | 'completed' | 'cancelled' {
  switch (s) {
    case 'completed': return 'completed';
    case 'in_progress': return 'in_progress';
    case 'deleted': return 'cancelled';
    default: return 'pending';
  }
}

export function PlanProgressWidget() {
  const tasks = useChatStore(s => s.tasks);
  const visible = tasks.filter((t: Task) => t.status !== 'deleted');
  if (visible.length === 0) return null;

  const todos = visible.map((t: Task) => ({
    id: t.id,
    label: t.activeForm && t.status === 'in_progress' ? t.activeForm : t.subject,
    status: mapStatus(t.status),
    description: t.description,
  }));

  return (
    <div className="my-3" role="region" aria-label="Task progress">
      <PlanCard id="task-progress" title="Tasks" todos={todos} maxVisibleTodos={3} />
    </div>
  );
}
```

- [ ] **Step 2: Update the callsite**

Search for where `<PlanProgressWidget plan={plan} />` is rendered (likely in `ChatView.tsx` or similar). Change to `<PlanProgressWidget />` — no prop needed.

- [ ] **Step 3: Run webview tests**

```bash
cd agent/webview && npm test
```

Expected: passes (existing component tests may need minor updates if they assert prop presence).

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/components/agent/PlanProgressWidget.tsx \
        agent/webview/src/components/chat/ChatView.tsx
git commit -m "feat(webview): PlanProgressWidget reads tasks from chatStore"
```

---

### Task 5.5: Strip `PlanSummaryCard` of step rendering

**Files:**
- Modify: `agent/webview/src/components/agent/PlanSummaryCard.tsx`

- [ ] **Step 1: Remove step-related code**

Remove:
- Line 66: `const stepCount = plan.steps.length;`
- Line 67: `const pendingCount = plan.steps.filter(s => s.status === 'pending').length;`
- Lines 71–76: the `todos` mapping from `plan.steps`
- Any `<PlanCompact todos={todos}/>` render

Keep: the typewriter summary, the Approve/Revise buttons, the comment-count badge, the "View plan" link.

- [ ] **Step 2: Run webview tests**

```bash
cd agent/webview && npm test
```

Expected: passes (some tests may need updates to remove step-list expectations).

- [ ] **Step 3: Commit**

```bash
git add agent/webview/src/components/agent/PlanSummaryCard.tsx
git commit -m "chore(webview): remove step rendering from PlanSummaryCard"
```

---

### Task 5.6: Wire Kotlin-side pushes from `AgentController` + rewire execution-step sourcing

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt` (add `TaskChanged` event)

`AgentController.kt:1356` and `:1367` currently use the task_progress markdown checklist as "the sole source of truth" for building execution step UI data. This has to be rewired to `TaskStore.listTasks()` now that the store is the real source of truth.

- [ ] **Step 1: Push `setTasks` on session load**

Wherever session state is pushed to the webview (look for `_loadSessionState` or similar), also push:

```kotlin
val tasks = taskStore?.listTasks().orEmpty()
agentCefPanel.callJs("_setTasks", json.encodeToString(tasks))
```

- [ ] **Step 2: Push on `task_create` / `task_update` tool results**

After a successful `task_create` or `task_update` execution, look up the affected task and push:

```kotlin
// In the tool post-execution callback / event handler:
val task = taskStore?.getTask(taskId) ?: return
agentCefPanel.callJs(
    if (isCreate) "_applyTaskCreate" else "_applyTaskUpdate",
    json.encodeToString(task),
)
```

The cleanest way is to have `TaskCreateTool` / `TaskUpdateTool` emit a `WorkflowEvent.TaskChanged(taskId, isCreate: Boolean)` — add this event type to `WorkflowEvent.kt` if it doesn't exist. `AgentController` subscribes to the event and pushes to the webview.

- [ ] **Step 3: Rewire execution-step sourcing (AgentController.kt ~line 1356–1367)**

Search for the doc comment: `"The LLM's task_progress checklist (focus-chain) is the sole source of truth"` and the corresponding implementation:

```kotlin
// BEFORE (around line 1367):
// Build execution steps directly from the LLM's task_progress checklist.
// val checklist = contextManager.getTaskProgress() ?: return ...
// val steps = TaskProgress.fromMarkdown(checklist).items.map { ... }
```

Replace with:

```kotlin
// AFTER:
// Build execution steps from TaskStore (authoritative state).
val tasks = taskStore?.listTasks().orEmpty()
    .filter { it.status != TaskStatus.DELETED }
val steps = tasks.map { task ->
    ExecutionStep(
        id = task.id,
        title = if (task.status == TaskStatus.IN_PROGRESS && task.activeForm != null) task.activeForm!! else task.subject,
        status = when (task.status) {
            TaskStatus.COMPLETED -> ExecutionStepStatus.COMPLETED
            TaskStatus.IN_PROGRESS -> ExecutionStepStatus.RUNNING
            else -> ExecutionStepStatus.PENDING
        },
    )
}
```

Update the surrounding doc comment from "task_progress checklist (focus-chain)" to "TaskStore (typed task system)".

- [ ] **Step 4: Smoke test via `runIde`**

```bash
./gradlew runIde
```

Verify manually: open the agent tool window, send a message that triggers `task_create`, confirm the task appears in `PlanProgressWidget`. Send a follow-up that triggers `task_update(status=in_progress)` → status transitions with spinner. `task_update(status=completed)` → checkmark. `task_update(status=deleted)` → task disappears. Close IDE.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt
git commit -m "feat(agent): AgentController pushes task events and sources execution steps from TaskStore"
```

---

### Task 5.7: Phase 5 gate

- [ ] **Step 1: Webview tests**

```bash
cd agent/webview && npm test
```

Expected: `PASS`.

- [ ] **Step 2: Agent tests**

```bash
./gradlew :agent:test
```

Expected: `PASS`.

- [ ] **Step 3: `verifyPlugin`**

```bash
./gradlew verifyPlugin
```

Expected: `BUILD SUCCESSFUL`.

---

## Phase 6 — Remove Old Plumbing (IRREVERSIBLE)

Delete `TaskProgress.kt`, `injectTaskProgress`, `task_progress` extraction, `steps` parameter on `plan_mode_respond`, `PlanStep`. Point-of-no-return phase.

### Task 6.1: Remove `injectTaskProgress` and `TASK_PROGRESS_PROPERTY` from `AgentTool.kt`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt` (lines 156–177)

- [ ] **Step 1: Remove the companion-object block**

Delete from `AgentTool.kt`:
- Line 156: `/** Shared task_progress property injected into every tool schema... */`
- Lines 157–161: `val TASK_PROGRESS_PROPERTY = ParameterProperty(...)`
- Lines 163–177: `fun injectTaskProgress(def: ToolDefinition): ToolDefinition { ... }`

- [ ] **Step 2: Remove call sites**

Search for call sites:
```bash
grep -rn "injectTaskProgress\|TASK_PROGRESS_PROPERTY" agent/src/main/kotlin/
```

Remove every occurrence. Most likely callers: `AgentService.kt`'s tool-definition provider (strip any `.map { injectTaskProgress(it) }` call).

- [ ] **Step 3: Compile and run tests**

```bash
./gradlew :agent:compileKotlin :agent:test
```

Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "refactor(agent): remove injectTaskProgress — task tools replace the pattern"
```

---

### Task 6.2: Remove `task_progress` extraction from `AgentLoop.kt` and `LoopDetector.IGNORED_PARAMS`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/LoopDetector.kt`

- [ ] **Step 1: Remove extraction call and method in AgentLoop.kt**

Delete from `AgentLoop.kt`:
- Line 460: `private const val TASK_PROGRESS_PARAM = "task_progress"`
- Lines 1231–1235: the `extractTaskProgress(params)` call and its comment
- Lines 1713–1728: the `private fun extractTaskProgress(params: JsonObject)` method

- [ ] **Step 2: Remove `task_progress` from `LoopDetector.IGNORED_PARAMS`**

In `LoopDetector.kt` at line 74:

```kotlin
// BEFORE:
// private val IGNORED_PARAMS = setOf("task_progress")

// AFTER (if task_progress was the only entry):
private val IGNORED_PARAMS = emptySet<String>()

// If other params are in the set, just drop "task_progress":
// private val IGNORED_PARAMS = setOf(<other entries without task_progress>)
```

- [ ] **Step 3: Compile and run tests**

```bash
./gradlew :agent:compileKotlin :agent:test
```

Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/LoopDetector.kt
git commit -m "refactor(agent): remove task_progress extraction and LoopDetector ignored-param entry"
```

---

### Task 6.3: Remove `taskProgressMarkdown` from `ContextManager.kt`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt`

- [ ] **Step 1: Remove legacy field and methods**

Delete:
- Line 104: `private var taskProgressMarkdown: String? = null`
- Lines 779–784: `fun setTaskProgress(...)`
- Line 795: `fun getTaskProgress(): String? = taskProgressMarkdown`
- Lines 797–804: `fun getTaskProgressParsed()`

Keep: the new `attachTaskStore` / `renderTaskProgressMarkdown` methods from Task 3.1.

- [ ] **Step 2: Remove any callsites that reference the deleted methods**

```bash
grep -rn "getTaskProgress\|setTaskProgress\|getTaskProgressParsed" agent/src/main/kotlin/
```

Update any remaining callers to use `renderTaskProgressMarkdown()` instead.

- [ ] **Step 3: Compile and run tests**

```bash
./gradlew :agent:compileKotlin :agent:test
```

Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt
git commit -m "refactor(agent): drop taskProgressMarkdown field — ContextManager reads TaskStore"
```

---

### Task 6.4: Remove `steps` and `task_progress` params from `PlanModeRespondTool`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/PlanModeRespondTool.kt`

- [ ] **Step 1: Strip the `steps` parameter**

Remove:
- Lines 69–75: the `"steps"` entry in `properties`
- Line 90: `required = listOf("response", "steps")` → change to `required = listOf("response")`
- Lines 83–88: the `"task_progress"` entry

- [ ] **Step 2: Update description text (line 39+)**

Remove the paragraphs about step titles appearing in the plan progress card and about including `task_progress` in tool calls. Description becomes:

```kotlin
override val description = "Call this ONLY when presenting a new or materially revised implementation plan. " +
    "For conversational replies (answering questions, acknowledging feedback, discussing whether to plan) " +
    "reply with plain text — do not call this tool. If a previously presented plan has become invalid and " +
    "you do not have a replacement ready, call discard_plan to clear it.\n\n" +
    "Present a concrete implementation plan to the user for review and approval. " +
    "This tool should ONLY be used when you have already explored the relevant files and are ready to present " +
    "a plan. DO NOT use this tool to announce what files you're going to read — just read them first. " +
    "This tool is only available in PLAN MODE.\n\n" +
    "Provide the plan as a `response` parameter: a full markdown document with headings, code blocks, " +
    "tables, and file paths. This is rendered in the plan document viewer where the user can add inline " +
    "comments on specific lines.\n\n" +
    "If while writing your response you realize you need more exploration, set needs_more_exploration=true."
```

- [ ] **Step 3: Update `execute()`**

Remove the `planSteps` extraction (lines 112–117, 119–121) and the `steps = planSteps` argument to `ToolResult.planResponse(...)` at line 123. Summary simplifies to:

```kotlin
summary = if (needsMoreExploration) {
    "Plan draft (needs more exploration): ${response.take(200)}"
} else {
    "Plan presented: ${response.take(200)}"
},
```

- [ ] **Step 4: Update `ToolResult.planResponse(...)` factory**

Remove the `steps: List<String>` parameter from the factory. Search callers:
```bash
grep -rn "ToolResult.planResponse" agent/src/
```

Update all callers.

- [ ] **Step 5: Compile and run tests**

```bash
./gradlew :agent:compileKotlin :agent:test
```

Expected: all pass. Tests that asserted on `steps` need to be updated or deleted.

- [ ] **Step 6: Regenerate snapshots if SystemPrompt changed**

```bash
./gradlew :agent:test --tests "*generate all golden snapshots*"
./gradlew :agent:test --tests "*SNAPSHOT*"
```

Expected: `PASS`.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/PlanModeRespondTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolResult.kt \
        agent/src/test/resources/prompt-snapshots/
git commit -m "refactor(agent): drop steps and task_progress params from plan_mode_respond"
```

---

### Task 6.5: Delete `PlanStep` and collapse `PlanJson`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/PlanData.kt`

- [ ] **Step 1: Rewrite `PlanData.kt`**

Replace its contents with:

```kotlin
package com.workflow.orchestrator.agent.loop

import kotlinx.serialization.Serializable

/**
 * Plan data sent to the JCEF webview. Since task progress migrated to TaskStore,
 * this carries only the plan's narrative content.
 */
@Serializable
data class PlanJson(
    val summary: String,
    val markdown: String? = null,
)
```

Delete: `PlanStep`, `PlanStepStatus` imports and declarations.

- [ ] **Step 2: Remove references to `PlanStep` / `PlanStepStatus`**

```bash
grep -rn "PlanStep\|PlanStepStatus" agent/src/main/kotlin/
```

Update or delete each reference. Likely sites: the persistence layer that writes `plan.json`, the JCEF bridge code that sends plan payloads.

- [ ] **Step 3: Compile and run tests**

```bash
./gradlew :agent:compileKotlin :agent:test
```

Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/PlanData.kt
git commit -m "refactor(agent): collapse PlanJson to summary+markdown; delete PlanStep"
```

---

### Task 6.6: Delete `TaskProgress.kt`

**Files:**
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/TaskProgress.kt`
- Delete: any test files that tested `TaskProgress.fromMarkdown` (likely `agent/src/test/kotlin/.../TaskProgressTest.kt` if exists)

- [ ] **Step 1: Check for remaining references**

```bash
grep -rn "TaskProgress\|TaskProgressItem" agent/src/
```

Expected: only the new `Task` class and `TaskStore` references, no `com.workflow.orchestrator.agent.loop.TaskProgress`. Remove any stragglers.

- [ ] **Step 2: Delete the file**

```bash
git rm agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/TaskProgress.kt
```

Also remove any test files that exclusively tested the deleted class.

- [ ] **Step 3: Compile and run tests**

```bash
./gradlew :agent:compileKotlin :agent:test
```

Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add -u
git commit -m "refactor(agent): delete TaskProgress.kt — replaced by Task + TaskStore"
```

---

### Task 6.7: Phase 6 gate

- [ ] **Step 1: Clean rebuild with no build cache**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

Expected: all tests pass.

- [ ] **Step 2: Plugin verifier**

```bash
./gradlew verifyPlugin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Confirm no legacy symbols remain anywhere**

```bash
grep -rn "task_progress\|TaskProgressMarkdown\|TASK_PROGRESS_PROPERTY\|injectTaskProgress\|PlanStep\|taskProgressMarkdown\|getTaskProgress\|setTaskProgress" agent/src/ agent/webview/src/
```

Expected: no matches. Skills updated in Task 3.5 should already be clean. Any stragglers indicate a missed reference — chase it down before gating.

---

## Phase 7 — Integration Verification

Full-stack smoke test and plugin build.

### Task 7.1: End-to-end test suite

- [ ] **Step 1: Agent tests**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Webview tests**

```bash
cd agent/webview && npm test
```

Expected: `PASS`.

- [ ] **Step 3: Plugin verifier**

```bash
./gradlew verifyPlugin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Build distributable**

```bash
./gradlew buildPlugin
```

Expected: ZIP produced in `build/distributions/`.

---

### Task 7.2: Manual `runIde` smoke checklist

- [ ] **Step 1: Launch sandbox IDE**

```bash
./gradlew runIde
```

- [ ] **Step 2: Smoke checks**

In the sandbox IDE, open the Agent tool window and verify:

- [ ] Send "plan a refactor of the auth middleware" → agent enters plan mode, writes a plan
- [ ] Click Approve → agent switches to act mode
- [ ] Agent calls `task_create` a few times → `PlanProgressWidget` shows the tasks with `pending` status
- [ ] Agent calls `task_update(taskId, status: in_progress)` → widget shows spinner on that task (with `activeForm` text if provided)
- [ ] Agent calls `task_update(taskId, status: completed)` → widget shows checkmark
- [ ] Agent calls `task_update(taskId, status: deleted)` → task disappears from widget
- [ ] Close sandbox, reopen, resume session → tasks rehydrate correctly from `tasks.json`
- [ ] No console errors in the JCEF DevTools

- [ ] **Step 3: Capture screenshot / note any regressions**

If the smoke test reveals issues, create follow-up tasks. Do NOT amend this plan retroactively — the regression investigation is its own work.

---

### Task 7.3: Update documentation

**Files:**
- Modify: `CLAUDE.md` (root)
- Modify: `agent/CLAUDE.md`
- Modify: `docs/architecture/` where relevant

- [ ] **Step 1: Update root `CLAUDE.md`**

In the `:agent` module description, replace `"task progress (markdown checklist)"` with `"task system (TaskCreate/TaskUpdate/TaskList/TaskGet with typed TaskStore and blocks/blockedBy DAG)"`.

- [ ] **Step 2: Update `agent/CLAUDE.md`**

- Replace the Task Progress section with a TaskStore section documenting the new persistence path, the four tools, and the hook-exemption behavior.
- Remove references to `task_progress` parameter injection.
- Remove references to `PlanStep` / `PlanJson.steps`.
- Add a pointer to `docs/research/2026-04-18-claude-code-task-system-research.md` and `docs/plans/2026-04-18-task-system-port.md`.

- [ ] **Step 3: Update any architecture diagrams in `docs/architecture/`**

Skim `docs/architecture/index.html` and any SVG/PNG diagrams. Update places that show the `task_progress` markdown parameter flowing through the system.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md agent/CLAUDE.md docs/architecture/
git commit -m "docs: reflect task system port — TaskStore + four tools + hook exemption"
```

---

## Self-Review Checklist (for the plan author, before execution)

1. **Spec coverage:** Every item in the earlier conversation-pinned delta (persistence, four tools, DAG, hook bypass, minimal TaskList, stale-task guidance, cycle detection, bridge contracts, UI rewire, deletions) has a task above. Confirmed.
2. **Placeholder scan:** No "TBD", "similar to above", "add appropriate handling", or bare "write tests" directives. Every code step has complete code.
3. **Type consistency:** `TaskStatus` is `PENDING/IN_PROGRESS/COMPLETED/DELETED` in Kotlin (all uppercase), rendered as lowercase in JSON (via default serializer behavior) and UI. Callers pass lowercase strings (`"in_progress"`) in tool calls. `addBlocks`/`addBlockedBy` are consistent throughout.
4. **Order-of-operations:** Phase 6 is the only irreversible phase. Phases 1–5 can be rolled back by reverting commits. The `TaskStore` + tool additions are additive and coexist with the legacy system until Phase 6 deletes it.
5. **Research alignment:** Every medium-confidence research recommendation is enacted (minimal TaskList, hook-exempt, cycle detection, stale-task guidance).

---

## Execution Handoff

Plan complete and saved to `docs/plans/2026-04-18-task-system-port.md`.

Next step per user's memory-pinned preference (`Always use subagent-driven development`): dispatch subagents phase-by-phase using the `superpowers:subagent-driven-development` skill. Start with Pre-Work (worktree creation), then Phase 1.
