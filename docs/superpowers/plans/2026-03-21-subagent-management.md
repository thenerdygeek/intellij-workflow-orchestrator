# Subagent Management — Resume, Background, Kill

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add subagent lifecycle management matching Claude Code: persistent transcripts with resume capability, background execution with async notification, and kill mechanism for running workers.

**Architecture:** A new `WorkerTranscriptStore` persists worker conversations to JSONL (same pattern as `ConversationStore`). Each worker gets a UUID `agentId`. `SpawnAgentTool` gains `resume` and `run_in_background` parameters. Background workers run in detached coroutines tracked by `AgentService`. Completion notifications are injected into the parent's conversation via `ContextManager`. Kill is via `Job.cancel()` on the tracked coroutine.

**Tech Stack:** Kotlin coroutines (Job tracking, detached launch), JSONL persistence, existing ContextManager/WorkerSession infrastructure

**Reference:** `docs/superpowers/research/2026-03-21-subagent-management-research.md`

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `agent/src/main/kotlin/.../runtime/WorkerTranscriptStore.kt` | Persist/load worker JSONL transcripts + metadata |
| `agent/src/test/kotlin/.../runtime/WorkerTranscriptStoreTest.kt` | Tests for persistence |

### Modified Files

| File | Changes |
|------|---------|
| `agent/src/main/kotlin/.../tools/builtin/SpawnAgentTool.kt` | Add `resume` and `run_in_background` parameters. Fork execution into foreground/background paths. Persist transcript after execution. Resume from saved transcript. |
| `agent/src/main/kotlin/.../runtime/WorkerSession.kt` | Accept optional pre-loaded messages for resume. Return agentId in result. |
| `agent/src/main/kotlin/.../AgentService.kt` | Track background workers: `Map<String, BackgroundWorker>` with Job + metadata. Add `getBackgroundWorker()`, `killWorker()`, `getWorkerStatus()`. |
| `agent/src/main/kotlin/.../runtime/WorkerResult.kt` or wherever WorkerResult is | Add `agentId: String` field. |
| `agent/src/main/kotlin/.../tools/DynamicToolSelector.kt` | Ensure `agent` tool is always available. |
| `agent/CLAUDE.md` | Document resume, background, kill. |

---

## Task 1: WorkerTranscriptStore — Persist and Load Worker Transcripts

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerTranscriptStore.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/WorkerTranscriptStoreTest.kt`

- [ ] **Step 1: Create WorkerTranscriptStore**

```kotlin
package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Persists worker (subagent) transcripts to JSONL for resume capability.
 *
 * Storage layout:
 *   {sessionDir}/subagents/agent-{agentId}.jsonl   — conversation transcript
 *   {sessionDir}/subagents/agent-{agentId}.meta.json — metadata (status, type, timestamps)
 *
 * Follows the same JSONL append-only pattern as ConversationStore.
 */
class WorkerTranscriptStore(private val sessionDir: File) {

    companion object {
        private val LOG = Logger.getInstance(WorkerTranscriptStore::class.java)
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

        fun generateAgentId(): String = UUID.randomUUID().toString().take(12)
    }

    @Serializable
    data class WorkerMetadata(
        val agentId: String,
        val subagentType: String,
        val description: String,
        val status: String = "running",  // running, completed, failed, killed
        val createdAt: Long = System.currentTimeMillis(),
        var completedAt: Long? = null,
        var tokensUsed: Int = 0,
        var summary: String? = null
    )

    @Serializable
    data class TranscriptMessage(
        val role: String,
        val content: String? = null,
        val toolCallId: String? = null,
        val toolCalls: String? = null,  // JSON string of tool calls
        val timestamp: Long = System.currentTimeMillis()
    )

    private val subagentsDir: File get() = File(sessionDir, "subagents").also { it.mkdirs() }

    private fun transcriptFile(agentId: String) = File(subagentsDir, "agent-$agentId.jsonl")
    private fun metadataFile(agentId: String) = File(subagentsDir, "agent-$agentId.meta.json")

    /**
     * Save metadata for a worker.
     */
    fun saveMetadata(metadata: WorkerMetadata) {
        try {
            metadataFile(metadata.agentId).writeText(json.encodeToString(metadata))
        } catch (e: Exception) {
            LOG.warn("WorkerTranscriptStore: failed to save metadata for ${metadata.agentId}", e)
        }
    }

    /**
     * Load metadata for a worker.
     */
    fun loadMetadata(agentId: String): WorkerMetadata? {
        val file = metadataFile(agentId)
        if (!file.isFile) return null
        return try {
            json.decodeFromString<WorkerMetadata>(file.readText())
        } catch (e: Exception) {
            LOG.warn("WorkerTranscriptStore: failed to load metadata for $agentId", e)
            null
        }
    }

    /**
     * Append a message to the worker's transcript (append-only JSONL).
     */
    fun appendMessage(agentId: String, message: TranscriptMessage) {
        try {
            val line = json.encodeToString(message)
            transcriptFile(agentId).appendText(line + "\n")
        } catch (e: Exception) {
            LOG.debug("WorkerTranscriptStore: failed to append message for $agentId: ${e.message}")
        }
    }

    /**
     * Load all messages from a worker's transcript.
     */
    fun loadTranscript(agentId: String): List<TranscriptMessage> {
        val file = transcriptFile(agentId)
        if (!file.isFile) return emptyList()
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try { json.decodeFromString<TranscriptMessage>(line) }
                    catch (_: Exception) { null }
                }
        } catch (e: Exception) {
            LOG.warn("WorkerTranscriptStore: failed to load transcript for $agentId", e)
            emptyList()
        }
    }

    /**
     * Convert transcript messages to ChatMessages for ContextManager replay.
     */
    fun toChatMessages(transcript: List<TranscriptMessage>): List<ChatMessage> {
        return transcript.map { msg ->
            ChatMessage(
                role = msg.role,
                content = msg.content,
                toolCallId = msg.toolCallId
            )
        }
    }

    /**
     * Update worker status in metadata.
     */
    fun updateStatus(agentId: String, status: String, summary: String? = null, tokensUsed: Int? = null) {
        val meta = loadMetadata(agentId) ?: return
        meta.completedAt = if (status != "running") System.currentTimeMillis() else null
        meta.summary = summary ?: meta.summary
        meta.tokensUsed = tokensUsed ?: meta.tokensUsed
        saveMetadata(meta.copy(status = status))
    }

    /**
     * List all worker transcripts in this session.
     */
    fun listWorkers(): List<WorkerMetadata> {
        if (!subagentsDir.isDirectory) return emptyList()
        return subagentsDir.listFiles()
            ?.filter { it.name.endsWith(".meta.json") }
            ?.mapNotNull { file ->
                try { json.decodeFromString<WorkerMetadata>(file.readText()) }
                catch (_: Exception) { null }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }
}
```

- [ ] **Step 2: Write tests**

```kotlin
@Test
fun `saveMetadata and loadMetadata round-trip`() {
    val store = WorkerTranscriptStore(tempDir)
    val meta = WorkerTranscriptStore.WorkerMetadata(
        agentId = "abc123",
        subagentType = "coder",
        description = "fix auth bug"
    )
    store.saveMetadata(meta)
    val loaded = store.loadMetadata("abc123")
    assertNotNull(loaded)
    assertEquals("abc123", loaded!!.agentId)
    assertEquals("coder", loaded.subagentType)
    assertEquals("running", loaded.status)
}

@Test
fun `appendMessage and loadTranscript work`() {
    val store = WorkerTranscriptStore(tempDir)
    store.appendMessage("abc123", WorkerTranscriptStore.TranscriptMessage(role = "system", content = "You are a coder"))
    store.appendMessage("abc123", WorkerTranscriptStore.TranscriptMessage(role = "user", content = "Fix the bug"))
    store.appendMessage("abc123", WorkerTranscriptStore.TranscriptMessage(role = "assistant", content = "I'll read the file"))
    val transcript = store.loadTranscript("abc123")
    assertEquals(3, transcript.size)
    assertEquals("system", transcript[0].role)
    assertEquals("Fix the bug", transcript[1].content)
}

@Test
fun `updateStatus changes metadata`() {
    val store = WorkerTranscriptStore(tempDir)
    store.saveMetadata(WorkerTranscriptStore.WorkerMetadata(agentId = "abc123", subagentType = "coder", description = "test"))
    store.updateStatus("abc123", "completed", summary = "Fixed the bug", tokensUsed = 5000)
    val meta = store.loadMetadata("abc123")
    assertEquals("completed", meta!!.status)
    assertEquals("Fixed the bug", meta.summary)
    assertEquals(5000, meta.tokensUsed)
    assertNotNull(meta.completedAt)
}

@Test
fun `listWorkers returns all workers sorted by recency`() {
    val store = WorkerTranscriptStore(tempDir)
    store.saveMetadata(WorkerTranscriptStore.WorkerMetadata(agentId = "old", subagentType = "coder", description = "old task", createdAt = 1000))
    store.saveMetadata(WorkerTranscriptStore.WorkerMetadata(agentId = "new", subagentType = "reviewer", description = "new task", createdAt = 2000))
    val workers = store.listWorkers()
    assertEquals(2, workers.size)
    assertEquals("new", workers[0].agentId)
}

@Test
fun `toChatMessages converts transcript`() {
    val transcript = listOf(
        WorkerTranscriptStore.TranscriptMessage(role = "system", content = "prompt"),
        WorkerTranscriptStore.TranscriptMessage(role = "user", content = "task")
    )
    val store = WorkerTranscriptStore(tempDir)
    val messages = store.toChatMessages(transcript)
    assertEquals(2, messages.size)
    assertEquals("system", messages[0].role)
}

@Test
fun `loadMetadata returns null for missing agent`() {
    val store = WorkerTranscriptStore(tempDir)
    assertNull(store.loadMetadata("nonexistent"))
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :agent:test --tests "*.WorkerTranscriptStoreTest" -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerTranscriptStore.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/WorkerTranscriptStoreTest.kt
git commit -m "feat(agent): WorkerTranscriptStore for subagent transcript persistence

JSONL-based transcript storage in {sessionDir}/subagents/. Each worker gets
a UUID agentId, metadata file (status/type/timestamps), and append-only
transcript. Supports save/load/update/list operations for resume."
```

---

## Task 2: Background Worker Tracking in AgentService

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

- [ ] **Step 1: Add background worker tracking data structures**

Add after existing worker tracking fields:

```kotlin
/**
 * Tracks background workers for lifecycle management.
 * Key: agentId, Value: BackgroundWorker with job handle and metadata.
 */
data class BackgroundWorker(
    val agentId: String,
    val job: kotlinx.coroutines.Job,
    val subagentType: String,
    val description: String,
    val startedAt: Long = System.currentTimeMillis(),
    @Volatile var status: String = "running" // running, completed, failed, killed
)

val backgroundWorkers = java.util.concurrent.ConcurrentHashMap<String, BackgroundWorker>()

/**
 * Callback invoked when a background worker completes.
 * The parent session uses this to inject a notification into the conversation.
 */
@Volatile var onBackgroundWorkerCompleted: ((agentId: String, result: String, isError: Boolean) -> Unit)? = null

fun getBackgroundWorker(agentId: String): BackgroundWorker? = backgroundWorkers[agentId]

fun killWorker(agentId: String): Boolean {
    val worker = backgroundWorkers[agentId] ?: return false
    worker.job.cancel()
    worker.status = "killed"
    backgroundWorkers.remove(agentId)
    activeWorkerCount.decrementAndGet()
    return true
}

fun getWorkerStatus(agentId: String): String? {
    return backgroundWorkers[agentId]?.status
}

fun listBackgroundWorkers(): List<BackgroundWorker> {
    return backgroundWorkers.values.toList().sortedByDescending { it.startedAt }
}
```

- [ ] **Step 2: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(agent): background worker tracking in AgentService

BackgroundWorker data class with Job handle, metadata, and status.
ConcurrentHashMap for thread-safe tracking. killWorker() cancels the
coroutine job. onBackgroundWorkerCompleted callback for parent notification."
```

---

## Task 3: Add Resume + Background + Kill to SpawnAgentTool

This is the main task — extends the `agent` tool with the three new capabilities.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerSession.kt`

- [ ] **Step 1: Add new parameters to SpawnAgentTool**

Add to the `parameters` map:

```kotlin
"resume" to ParameterProperty(
    type = "string",
    description = "Agent ID to resume from a previous execution. The agent continues with its full previous context preserved."
),
"run_in_background" to ParameterProperty(
    type = "boolean",
    description = "Set to true to run the agent in the background. Returns immediately with the agent ID. You will be notified when it completes."
)
```

- [ ] **Step 2: Add transcript persistence to the existing foreground execution path**

In `execute()`, after a successful WorkerSession run, persist the transcript:

```kotlin
// After workerResult is obtained (successful foreground):
val transcriptStore = WorkerTranscriptStore(sessionDir)
// Save metadata
transcriptStore.saveMetadata(WorkerTranscriptStore.WorkerMetadata(
    agentId = agentId,
    subagentType = subagentType,
    description = description,
    status = if (workerResult.isError) "failed" else "completed",
    tokensUsed = workerResult.tokensUsed,
    summary = workerResult.summary,
    completedAt = System.currentTimeMillis()
))
```

Where does `sessionDir` come from? It needs to be the current session's directory. Get it from:
```kotlin
val sessionDir = try {
    val session = agentService.activeController?.let { /* get session dir */ }
    // OR store on AgentService during session creation
    agentService.currentSessionDir
} catch (_: Exception) { null }
```

Add `@Volatile var currentSessionDir: java.io.File? = null` to `AgentService` and set it in `ConversationSession.create()`.

For the transcript messages themselves: modify `WorkerSession` to record messages as it goes. Add a `transcriptStore` and `agentId` parameter to `WorkerSession`:

```kotlin
class WorkerSession(
    private val maxIterations: Int = 10,
    private val parentJob: kotlinx.coroutines.Job? = null,
    private val transcriptStore: WorkerTranscriptStore? = null,  // NEW
    val agentId: String = WorkerTranscriptStore.generateAgentId() // NEW
)
```

In `WorkerSession.execute()`, after each `contextManager.addMessage()` / `contextManager.addToolResult()`, also call:
```kotlin
transcriptStore?.appendMessage(agentId, WorkerTranscriptStore.TranscriptMessage(
    role = message.role,
    content = message.content,
    toolCallId = message.toolCallId
))
```

- [ ] **Step 3: Add resume execution path**

In `execute()`, before the normal execution, add:

```kotlin
val resumeId = params["resume"]?.jsonPrimitive?.contentOrNull
if (resumeId != null) {
    return executeResume(resumeId, prompt, project, agentService)
}
```

Add the method:

```kotlin
private suspend fun executeResume(
    agentId: String,
    newPrompt: String,
    project: Project,
    agentService: AgentService
): ToolResult {
    val sessionDir = agentService.currentSessionDir
        ?: return errorResult("Error: no active session directory for transcript storage")
    val transcriptStore = WorkerTranscriptStore(sessionDir)
    val metadata = transcriptStore.loadMetadata(agentId)
        ?: return errorResult("Error: agent '$agentId' not found. Available: ${transcriptStore.listWorkers().joinToString { it.agentId }}")

    val transcript = transcriptStore.loadTranscript(agentId)
    if (transcript.isEmpty()) {
        return errorResult("Error: agent '$agentId' has no transcript to resume from")
    }

    // Reconstruct context from transcript
    val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
    val contextManager = ContextManager(
        maxInputTokens = settings?.state?.maxInputTokens ?: AgentSettings.DEFAULTS.maxInputTokens
    )

    // Replay previous messages into context
    val chatMessages = transcriptStore.toChatMessages(transcript)
    for (msg in chatMessages) {
        contextManager.addMessage(msg)
    }

    // Add the new prompt as a user message
    contextManager.addMessage(ChatMessage(role = "user", content = newPrompt))

    // Resolve tools from metadata
    val agentDef = agentService.agentDefinitionRegistry?.getAgent(metadata.subagentType)
    val toolsForWorker = resolveTools(agentDef, metadata.subagentType, agentService)
    val toolMap = toolsForWorker.associateBy { it.name }
    val toolDefinitions = toolsForWorker.map { it.toToolDefinition() }

    // Resume execution
    val workerSession = WorkerSession(
        maxIterations = agentDef?.maxTurns ?: DEFAULT_MAX_ITERATIONS,
        parentJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job],
        transcriptStore = transcriptStore,
        agentId = agentId
    )

    agentService.activeWorkerCount.incrementAndGet()
    try {
        val result = withTimeout(WORKER_TIMEOUT_MS) {
            workerSession.executeFromContext(
                tools = toolMap,
                toolDefinitions = toolDefinitions,
                brain = agentService.brain,
                contextManager = contextManager,
                project = project
            )
        }

        transcriptStore.updateStatus(agentId, if (result.isError) "failed" else "completed",
            summary = result.summary, tokensUsed = result.tokensUsed)

        return ToolResult(
            content = "Resumed agent '$agentId' completed.\n\nResult: ${result.summary}\nAgent ID: $agentId (can resume again)",
            summary = "Resumed agent completed: ${result.summary.take(100)}",
            tokenEstimate = result.tokensUsed
        )
    } catch (e: Exception) {
        transcriptStore.updateStatus(agentId, "failed", summary = e.message)
        return errorResult("Resumed agent '$agentId' failed: ${e.message}")
    } finally {
        agentService.activeWorkerCount.decrementAndGet()
    }
}
```

- [ ] **Step 4: Add `executeFromContext` to WorkerSession**

This is a variant of `execute()` that skips the system prompt + task setup (since context is already loaded from transcript):

```kotlin
/**
 * Execute the ReAct loop from an already-populated ContextManager.
 * Used for resume — the context already contains the previous conversation.
 */
suspend fun executeFromContext(
    tools: Map<String, AgentTool>,
    toolDefinitions: List<ToolDefinition>,
    brain: LlmBrain,
    contextManager: ContextManager,
    project: Project
): WorkerResult {
    // Same ReAct loop as execute(), but skip the initial system prompt + task injection
    // Jump directly to the iteration loop
    // ... (reuse existing loop code)
}
```

The simplest implementation: extract the loop body from `execute()` into a private method `runReactLoop(tools, toolDefs, brain, contextManager, project)` and call it from both `execute()` and `executeFromContext()`.

- [ ] **Step 5: Add background execution path**

In `execute()`, after parameter parsing but before the normal execution, check `run_in_background`:

```kotlin
val runInBackground = params["run_in_background"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

if (runInBackground) {
    return executeBackground(agentId, description, prompt, subagentType, agentDef, project, agentService)
}
```

Add the method:

```kotlin
private fun executeBackground(
    agentId: String,
    description: String,
    prompt: String,
    subagentType: String,
    agentDef: AgentDefinitionRegistry.AgentDefinition?,
    project: Project,
    agentService: AgentService
): ToolResult {
    val sessionDir = agentService.currentSessionDir
    val transcriptStore = if (sessionDir != null) WorkerTranscriptStore(sessionDir) else null

    // Save initial metadata
    transcriptStore?.saveMetadata(WorkerTranscriptStore.WorkerMetadata(
        agentId = agentId,
        subagentType = subagentType,
        description = description
    ))

    // Launch in detached coroutine
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    val job = scope.launch {
        agentService.activeWorkerCount.incrementAndGet()
        try {
            // Build context, tools, prompt (same as foreground path)
            val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
            val contextManager = ContextManager(
                maxInputTokens = settings?.state?.maxInputTokens ?: AgentSettings.DEFAULTS.maxInputTokens
            )

            val toolsForWorker = resolveTools(agentDef, subagentType, agentService)
            val toolMap = toolsForWorker.associateBy { it.name }
            val toolDefinitions = toolsForWorker.map { it.toToolDefinition() }

            val systemPrompt = resolveSystemPrompt(agentDef, subagentType, agentService, project)

            val workerSession = WorkerSession(
                maxIterations = agentDef?.maxTurns ?: DEFAULT_MAX_ITERATIONS,
                transcriptStore = transcriptStore,
                agentId = agentId
            )

            val result = withTimeout(WORKER_TIMEOUT_MS) {
                workerSession.execute(
                    workerType = resolveWorkerType(subagentType),
                    systemPrompt = systemPrompt,
                    task = prompt,
                    tools = toolMap,
                    toolDefinitions = toolDefinitions,
                    brain = agentService.brain,
                    contextManager = contextManager,
                    project = project
                )
            }

            // Update metadata
            transcriptStore?.updateStatus(agentId, if (result.isError) "failed" else "completed",
                summary = result.summary, tokensUsed = result.tokensUsed)
            agentService.totalSessionTokens.addAndGet(result.tokensUsed.toLong())

            // Notify parent
            val bgWorker = agentService.backgroundWorkers[agentId]
            bgWorker?.status = if (result.isError) "failed" else "completed"
            agentService.onBackgroundWorkerCompleted?.invoke(
                agentId,
                "Background agent '$agentId' ($subagentType) ${if (result.isError) "failed" else "completed"}.\n" +
                    "Summary: ${result.summary}\nAgent ID: $agentId (can resume)",
                result.isError
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            transcriptStore?.updateStatus(agentId, "killed")
            val bgWorker = agentService.backgroundWorkers[agentId]
            bgWorker?.status = "killed"
        } catch (e: Exception) {
            transcriptStore?.updateStatus(agentId, "failed", summary = e.message)
            agentService.onBackgroundWorkerCompleted?.invoke(
                agentId, "Background agent '$agentId' failed: ${e.message}", true
            )
        } finally {
            agentService.activeWorkerCount.decrementAndGet()
            agentService.backgroundWorkers.remove(agentId)
        }
    }

    // Track the background worker
    agentService.backgroundWorkers[agentId] = AgentService.BackgroundWorker(
        agentId = agentId,
        job = job,
        subagentType = subagentType,
        description = description
    )

    return ToolResult(
        content = "Agent '$agentId' ($subagentType) launched in background.\n" +
            "Description: $description\n" +
            "You will be notified when it completes. Continue with other work.\n" +
            "To check status: the agent service tracks this worker.\n" +
            "To kill: delegate_task or agent service can cancel it.\n" +
            "Agent ID: $agentId",
        summary = "Background agent $agentId launched: $description",
        tokenEstimate = 50
    )
}
```

- [ ] **Step 6: Extract helper methods for tool/prompt resolution**

Refactor to avoid code duplication between foreground, background, and resume paths:

```kotlin
private fun resolveTools(agentDef: AgentDefinitionRegistry.AgentDefinition?, subagentType: String, agentService: AgentService): List<AgentTool> {
    // Same logic as existing tool selection in execute()
}

private fun resolveSystemPrompt(agentDef: AgentDefinitionRegistry.AgentDefinition?, subagentType: String, agentService: AgentService, project: Project): String {
    // Same logic as existing system prompt selection
}

private fun resolveWorkerType(subagentType: String): WorkerType {
    return BUILT_IN_AGENTS[subagentType]?.workerType ?: WorkerType.ORCHESTRATOR
}
```

- [ ] **Step 7: Include agentId in foreground results**

In the foreground success result, append the agent ID:

```kotlin
"Agent ID: $agentId (can be resumed with agent(resume='$agentId', prompt='...'))"
```

- [ ] **Step 8: Compile and test**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
./gradlew :agent:test --tests "*.SpawnAgentToolTest" -x verifyPlugin
```

- [ ] **Step 9: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerSession.kt
git commit -m "feat(agent): resume + background execution for agent tool

Resume: reload transcript from JSONL, reconstruct ContextManager, continue
execution. Background: launch in detached coroutine, return immediately with
agentId, notify parent on completion via callback. Transcripts persisted
for all executions (foreground and background)."
```

---

## Task 4: Wire Background Notifications into Parent Session

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt`

- [ ] **Step 1: Set `currentSessionDir` on AgentService during session creation**

In `ConversationSession.create()`, after creating the session:

```kotlin
try {
    agentService.currentSessionDir = store.sessionDirectory
} catch (_: Exception) {}
```

Where `store.sessionDirectory` is the directory where messages.jsonl is stored.

- [ ] **Step 2: Wire background worker completion notification**

In `AgentController`, after session creation (in `executeTask()` or `wireSessionCallbacks()`):

```kotlin
agentService.onBackgroundWorkerCompleted = { agentId, resultMessage, isError ->
    // Inject notification into the chat UI
    javax.swing.SwingUtilities.invokeLater {
        if (isError) {
            dashboard.appendError("Background agent $agentId: $resultMessage")
        } else {
            dashboard.appendStatus(resultMessage, RichStreamingPanel.StatusType.SUCCESS)
        }
        // Also inject into the conversation context so the LLM knows about it
        session?.contextManager?.addMessage(
            com.workflow.orchestrator.agent.api.dto.ChatMessage(
                role = "system",
                content = "<background_agent_completed agent_id=\"$agentId\">\n$resultMessage\n</background_agent_completed>"
            )
        )
    }
}
```

- [ ] **Step 3: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt
git commit -m "feat(agent): wire background worker completion notifications

Parent session gets notified when background agents complete. Notification
injected into both the UI (status message) and the LLM context (system
message with background_agent_completed tag)."
```

---

## Task 5: Kill Mechanism + Documentation

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt`
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Add kill support via the agent tool itself**

The LLM can kill a background worker by calling agent with a special pattern. Add to the execute() method, right after the resume check:

```kotlin
// Check for kill request
val killId = params["kill"]?.jsonPrimitive?.contentOrNull
if (killId != null) {
    val killed = agentService.killWorker(killId)
    return if (killed) {
        ToolResult("Agent '$killId' has been killed.", "Killed agent $killId", 20)
    } else {
        errorResult("Agent '$killId' not found or not running. Active: ${agentService.listBackgroundWorkers().joinToString { it.agentId }}")
    }
}
```

Add `kill` parameter:
```kotlin
"kill" to ParameterProperty(
    type = "string",
    description = "Agent ID to kill. Cancels a running background agent."
)
```

- [ ] **Step 2: Update CLAUDE.md**

Add/update the agent tool documentation:

```markdown
## Agent Tool (Subagent Management)

The `agent` tool spawns, resumes, and manages subagent workers:

**Spawn:** `agent(description="...", prompt="...", subagent_type="coder")`
**Background:** `agent(description="...", prompt="...", run_in_background=true)` — returns immediately with agentId
**Resume:** `agent(resume="agentId", prompt="continue with authorization module")`
**Kill:** `agent(kill="agentId")` — cancels a running background agent

**Transcript persistence:** All worker conversations are saved to `{sessionDir}/subagents/agent-{id}.jsonl`. Resume reconstructs the full conversation context from the transcript.

**Background notifications:** When a background agent completes, the parent is notified via a system message injected into the conversation context.

**Built-in types:** general-purpose, explorer, coder, reviewer, tooler
**Custom types:** Any agent defined in `.workflow/agents/{name}.md`
```

- [ ] **Step 3: Run all tests**

```bash
./gradlew :agent:test --rerun --no-build-cache -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt agent/CLAUDE.md
git commit -m "feat(agent): kill mechanism + agent tool documentation

agent(kill='agentId') cancels running background workers via Job.cancel().
Updated CLAUDE.md with full agent tool lifecycle: spawn, background,
resume, kill."
```

---

## Verification

```bash
./gradlew :agent:test --rerun --no-daemon
./gradlew verifyPlugin
```

Manual verification in `runIde`:
1. **Foreground agent:** Call agent tool → verify it runs, returns result with agentId, transcript saved to disk
2. **Resume:** Call agent(resume=agentId, prompt="continue") → verify it picks up where it left off
3. **Background:** Call agent(run_in_background=true) → verify it returns immediately → verify notification appears when done
4. **Kill:** Start background agent → call agent(kill=agentId) → verify it stops
5. **Transcript persistence:** Check `{sessionDir}/subagents/` for .jsonl and .meta.json files
6. **Multiple background:** Launch 2 background agents → verify both tracked → verify both notify on completion
