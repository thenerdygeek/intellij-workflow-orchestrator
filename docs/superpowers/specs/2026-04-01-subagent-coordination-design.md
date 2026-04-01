# Subagent Coordination: File Ownership, Bidirectional Messaging & Timeout Removal

**Date:** 2026-04-01
**Status:** Approved
**Scope:** `:agent` module — `SpawnAgentTool`, `WorkerSession`, `SingleAgentSession`, `AgentService`, `EditFileTool`, `CreateFileTool`, `ReadFileTool`

## Problem

Three gaps prevent the subagent architecture from being enterprise-grade:

1. **No file conflict prevention** — Two background workers can edit the same file simultaneously. Worker B's reads become stale after Worker A edits, and B's subsequent edits can overwrite A's work silently.
2. **No parent↔child communication** — Workers are fully isolated silos. The parent can't redirect a running worker; a worker can't alert the parent about critical findings mid-execution.
3. **Overly harsh timeout** — A hard 5-minute `withTimeout()` kills workers abruptly. No enterprise coding agent (Claude Code, Codex CLI, Cursor, Cline) uses wall-clock timeouts on sub-agents. Iteration limits are the correct bound.

## Design

### 1. FileOwnershipRegistry

A per-session registry that tracks which worker owns (is actively editing) each file. Write operations (`edit_file`, `create_file`) must acquire ownership before proceeding. Read operations (`read_file`) warn if the file is owned by another worker.

#### Data Model

```kotlin
package com.workflow.orchestrator.agent.runtime

import java.util.concurrent.ConcurrentHashMap

data class OwnershipRecord(
    val agentId: String,
    val workerType: WorkerType,
    val claimedAt: Long = System.currentTimeMillis()
)

enum class ClaimResult {
    GRANTED,
    DENIED
}

data class ClaimResponse(
    val result: ClaimResult,
    val ownerAgentId: String? = null  // non-null when DENIED
)

class FileOwnershipRegistry {
    private val fileOwners = ConcurrentHashMap<String, OwnershipRecord>()

    fun claim(filePath: String, agentId: String, workerType: WorkerType): ClaimResponse
    fun release(filePath: String, agentId: String): Boolean
    fun releaseAll(agentId: String): Int  // returns count released
    fun getOwner(filePath: String): OwnershipRecord?
    fun listOwnedFiles(agentId: String): List<String>
    fun isOwnedByOther(filePath: String, agentId: String): Boolean
}
```

#### Claim Semantics

- `claim()` is **idempotent** — claiming a file you already own returns GRANTED.
- `claim()` uses canonical paths (`File(path).canonicalPath`) to prevent path aliasing.
- Ownership is **per-worker, not per-file-region** — whole-file granularity.
- The orchestrator (agentId = null or "orchestrator") is exempt from ownership checks — it can always edit.

#### Integration Points

**`EditFileTool.execute()`:**
```
Before edit:
  1. Get WorkerContext from coroutineContext
  2. If workerContext.agentId != null:
     a. registry.claim(canonicalPath, agentId, workerType)
     b. If DENIED → return errorResult("File '$path' is locked by agent '$ownerAgentId'.
        Wait for it to complete or ask the orchestrator to coordinate.")
     c. If DENIED → also send FILE_CONFLICT message to orchestrator via messageBus
  3. Proceed with edit
```

**`CreateFileTool.execute()`:**
```
Same pattern as EditFileTool — claim before creating.
```

**`ReadFileTool.execute()`:**
```
Before read:
  1. Get WorkerContext from coroutineContext
  2. If workerContext.agentId != null:
     a. Check registry.isOwnedByOther(canonicalPath, agentId)
     b. If true → prepend warning header to result:
        "⚠ This file is being actively edited by agent '$ownerAgentId'. Contents may change."
  3. Proceed with read (never blocked)
```

**Worker lifecycle:**
```
SpawnAgentTool: on worker completion/failure → registry.releaseAll(agentId)
SpawnAgentTool: on timeout (if kept) → registry.releaseAll(agentId)
AgentService.killWorker(): → registry.releaseAll(agentId)
```

### 2. WorkerMessageBus

A per-session message bus enabling bidirectional communication between the parent orchestrator and worker sessions. Uses Kotlin `Channel` for non-blocking, bounded message delivery.

#### Data Model

```kotlin
package com.workflow.orchestrator.agent.runtime

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

enum class MessageType {
    INSTRUCTION,      // parent → child: "focus on X", "skip Y"
    FINDING,          // child → parent: "discovered breaking change in X"
    STATUS_UPDATE,    // child → parent: "completed 3/5 files", "blocked on tests"
    FILE_CONFLICT     // system-generated: "edit denied, file locked by agent-xyz"
}

data class WorkerMessage(
    val from: String,                        // agentId or "orchestrator"
    val to: String,                          // agentId or "orchestrator"
    val type: MessageType,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

class WorkerMessageBus {
    companion object {
        const val ORCHESTRATOR_ID = "orchestrator"
        private const val INBOX_CAPACITY = 20
    }

    private val inboxes = ConcurrentHashMap<String, Channel<WorkerMessage>>()

    fun createInbox(agentId: String): Channel<WorkerMessage>
    fun closeInbox(agentId: String)
    fun send(message: WorkerMessage): Boolean   // false if inbox doesn't exist
    fun drain(agentId: String): List<WorkerMessage>  // non-blocking, returns all pending
    fun hasPending(agentId: String): Boolean
    fun close()  // close all inboxes (session end)
}
```

#### Channel Configuration

```kotlin
Channel<WorkerMessage>(
    capacity = INBOX_CAPACITY,           // 20 messages
    onBufferOverflow = BufferOverflow.DROP_OLDEST  // bounded, drops oldest if full
)
```

#### Inbox Lifecycle

| Event | Action |
|-------|--------|
| Session start | `createInbox("orchestrator")` |
| Worker spawned | `createInbox(agentId)` |
| Worker completed/failed | `closeInbox(agentId)`, log remaining messages |
| Worker killed | `closeInbox(agentId)` |
| Session end | `bus.close()` |

#### Message Consumption

**Worker side — `WorkerSession.runReactLoop()`**, at the top of each iteration before `brain.chat()`:

```kotlin
// Drain pending messages from parent
val workerCtx = coroutineContext[WorkerContext]
val pending = workerCtx?.messageBus?.drain(agentId) ?: emptyList()
if (pending.isNotEmpty()) {
    val formatted = pending.joinToString("\n") { msg ->
        "<parent_message type=\"${msg.type.name.lowercase()}\" timestamp=\"${msg.timestamp}\">\n" +
        "${msg.content}\n" +
        "</parent_message>"
    }
    bridge.addSystemMessage(formatted)
}
```

**Parent side — `SingleAgentSession.execute()`**, at the top of each iteration before `brain.chat()`:

```kotlin
val pending = messageBus.drain(WorkerMessageBus.ORCHESTRATOR_ID)
if (pending.isNotEmpty()) {
    val formatted = pending.joinToString("\n") { msg ->
        "<worker_message from=\"${msg.from}\" type=\"${msg.type.name.lowercase()}\">\n" +
        "${msg.content}\n" +
        "</worker_message>"
    }
    bridge.addSystemMessage(formatted)
}
```

#### XML Format

Messages are injected as system messages with XML tags so the LLM can distinguish them from tool results:

```xml
<!-- Parent → Child -->
<parent_message type="instruction" timestamp="1711929600000">
Focus on the service layer only. Skip test files for now.
</parent_message>

<!-- Child → Parent -->
<worker_message from="agent-abc123" type="finding">
Discovered that AuthService.kt has a circular dependency with UserService.kt.
This affects the refactoring approach.
</worker_message>

<!-- System-generated on file conflict -->
<worker_message from="agent-def456" type="file_conflict">
Edit denied for AuthService.kt — file is locked by agent-abc123.
Consider coordinating file assignments or waiting for agent-abc123 to complete.
</worker_message>
```

### 3. LLM Tools for Messaging

#### 3a. `send_message_to_parent` (new tool)

Available to all WorkerTypes. Workers use this to send findings/status to the orchestrator mid-execution.

```kotlin
class SendMessageToParentTool : AgentTool {
    override val name = "send_message_to_parent"
    override val description = "Send a message to the parent orchestrator. " +
        "Use for critical findings, status updates, or when you need coordination. " +
        "The parent will see your message at its next iteration."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "type" to ParameterProperty(
                type = "string",
                enum = listOf("finding", "status_update"),
                description = "Message type: 'finding' for discoveries that affect the task, " +
                    "'status_update' for progress reports"
            ),
            "content" to ParameterProperty(
                type = "string",
                description = "The message content. Be concise and actionable."
            )
        ),
        required = listOf("type", "content")
    )

    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR, WorkerType.ANALYZER, WorkerType.CODER,
        WorkerType.REVIEWER, WorkerType.TOOLER
    )
}
```

**Execution:** Reads `WorkerContext` from coroutine context, calls `messageBus.send(WorkerMessage(..., to = "orchestrator"))`. Returns a confirmation: `"Message sent to orchestrator. Continue with your task."`

#### 3b. `agent` tool — new `send` parameter

Extend `SpawnAgentTool` with a `send` operation for parent → child messaging.

```kotlin
// New parameters added to SpawnAgentTool.parameters
"send" to ParameterProperty(
    type = "string",
    description = "Agent ID to send a message to. Use with 'message' parameter."
),
"message" to ParameterProperty(
    type = "string",
    description = "Message content to send to a running agent."
)
```

**Execution path in `SpawnAgentTool.execute()`:**

```kotlin
// --- 0b. Send check (before spawn/resume) ---
val sendTo = params["send"]?.jsonPrimitive?.contentOrNull
val message = params["message"]?.jsonPrimitive?.contentOrNull
if (sendTo != null && message != null) {
    val bus = agentService.workerMessageBus ?: return errorResult("No message bus available")
    val sent = bus.send(WorkerMessage(
        from = WorkerMessageBus.ORCHESTRATOR_ID,
        to = sendTo,
        type = MessageType.INSTRUCTION,
        content = message
    ))
    return if (sent) {
        ToolResult("Message sent to agent '$sendTo'. It will receive this at its next iteration.",
            "Sent instruction to $sendTo", 30)
    } else {
        errorResult("Agent '$sendTo' not found or inbox closed. Active: ${agentService.listBackgroundWorkers().joinToString { it.agentId }}")
    }
}
```

### 4. WorkerContext (Coroutine Context Element)

A coroutine context element that carries worker identity and shared infrastructure to all tools executing within a worker's scope.

```kotlin
package com.workflow.orchestrator.agent.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class WorkerContext(
    val agentId: String?,                       // null for orchestrator
    val workerType: WorkerType,
    val messageBus: WorkerMessageBus?,
    val fileOwnership: FileOwnershipRegistry?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkerContext>

    val isOrchestrator: Boolean get() = agentId == null
}
```

**Set in `WorkerSession.execute()`:**

```kotlin
suspend fun execute(...): WorkerResult {
    return withContext(WorkerContext(
        agentId = agentId,
        workerType = workerType,
        messageBus = messageBus,
        fileOwnership = fileOwnership
    )) {
        // ... existing execute body
    }
}
```

**Read in tools:**

```kotlin
// In EditFileTool.execute()
val workerCtx = coroutineContext[WorkerContext]
val myAgentId = workerCtx?.agentId
val registry = workerCtx?.fileOwnership

if (myAgentId != null && registry != null) {
    val claim = registry.claim(canonicalPath, myAgentId, workerCtx.workerType)
    if (claim.result == ClaimResult.DENIED) {
        // Send conflict notification
        workerCtx.messageBus?.send(WorkerMessage(
            from = myAgentId,
            to = WorkerMessageBus.ORCHESTRATOR_ID,
            type = MessageType.FILE_CONFLICT,
            content = "Edit denied for '$path' — locked by agent '${claim.ownerAgentId}'",
            metadata = mapOf("file" to canonicalPath, "owner" to (claim.ownerAgentId ?: ""))
        ))
        return errorResult("File '$path' is locked by agent '${claim.ownerAgentId}'. " +
            "Wait for it to complete or ask the orchestrator to coordinate.")
    }
}
```

**For SingleAgentSession (orchestrator):** Set a `WorkerContext` with `agentId = null` so tools know they're running in orchestrator context and skip ownership checks.

### 5. Timeout Removal

#### What Changes

| Location | Before | After |
|----------|--------|-------|
| `SpawnAgentTool.WORKER_TIMEOUT_MS` | `300_000L` (5 min) | **Removed** |
| `SpawnAgentTool.execute()` line 301 | `withTimeout(WORKER_TIMEOUT_MS) { workerSession.execute(...) }` | `workerSession.execute(...)` (direct call) |
| `SpawnAgentTool.executeResume()` line 477 | `withTimeout(WORKER_TIMEOUT_MS) { ... }` | Direct call |
| `SpawnAgentTool.executeBackground()` line 569 | `withTimeout(WORKER_TIMEOUT_MS) { ... }` | Direct call |
| `TimeoutCancellationException` catch blocks | 3 catch blocks with rollback + transcript update | **Removed** |
| `SpawnAgentTool` KDoc | "5-minute timeout" | Updated to explain iteration-based bounds |
| `AgentEventType.WORKER_TIMED_OUT` | Used in event log | **Kept** (may be useful for future configurable timeouts) |

#### What Stays

- `maxIterations` (10 default) — the primary bound on worker execution
- `parentJob` cancellation — parent can still cancel workers
- Context exhaustion — worker's bridge fills up, forcing completion
- `agent(kill="...")` — user/orchestrator can kill any worker
- `AgentService.killWorker()` — programmatic kill

#### Existing Bounds Are Sufficient

A worker with 10 iterations, where each iteration is 1 LLM call (~5-15s) + tool execution (~1-30s), runs for roughly 1-7 minutes naturally. Context exhaustion (150K budget) provides an additional cap. These are the same bounds Claude Code, Codex CLI, and Cursor use.

### 6. AgentService Changes

New fields on `AgentService`:

```kotlin
/** Per-session file ownership registry. Created by ConversationSession, shared across all workers. */
@Volatile var fileOwnershipRegistry: FileOwnershipRegistry? = null

/** Per-session message bus. Created by ConversationSession, shared across all workers. */
@Volatile var workerMessageBus: WorkerMessageBus? = null
```

**Lifecycle:** Created by `ConversationSession` at session start. Destroyed at session end. Both are lightweight in-memory structures with no persistence requirement.

**`killWorker()` update:**

```kotlin
fun killWorker(agentId: String): Boolean {
    val worker = backgroundWorkers[agentId] ?: return false
    worker.job.cancel()
    worker.status = WorkerStatus.KILLED
    backgroundWorkers.remove(agentId)
    activeWorkerCount.decrementAndGet()
    fileOwnershipRegistry?.releaseAll(agentId)    // NEW
    workerMessageBus?.closeInbox(agentId)          // NEW
    return true
}
```

## Files to Create

| File | Description |
|------|-------------|
| `agent/runtime/FileOwnershipRegistry.kt` | File ownership tracking with claim/release |
| `agent/runtime/WorkerMessageBus.kt` | Channel-based per-worker inbox system |
| `agent/runtime/WorkerContext.kt` | Coroutine context element for worker identity |
| `agent/tools/builtin/SendMessageToParentTool.kt` | New tool for worker → parent messaging |

## Files to Modify

| File | Changes |
|------|---------|
| `agent/AgentService.kt` | Add `fileOwnershipRegistry`, `workerMessageBus` fields; update `killWorker()` |
| `agent/tools/builtin/SpawnAgentTool.kt` | Remove timeout, add `send`/`message` params, wire registry/bus into WorkerContext, releaseAll on completion |
| `agent/runtime/WorkerSession.kt` | Accept WorkerContext params, wrap execute in `withContext(WorkerContext(...))`, drain inbox at iteration top |
| `agent/runtime/SingleAgentSession.kt` | Drain orchestrator inbox at iteration top, set WorkerContext for orchestrator |
| `agent/runtime/ConversationSession.kt` | Create FileOwnershipRegistry + WorkerMessageBus at session start |
| `agent/tools/builtin/EditFileTool.kt` | Check file ownership before edit, claim on success |
| `agent/tools/builtin/CreateFileTool.kt` | Check file ownership before create, claim on success |
| `agent/tools/builtin/ReadFileTool.kt` | Warn if file owned by another worker |
| `agent/CLAUDE.md` | Document new coordination features |

## Testing Strategy

- **FileOwnershipRegistry:** Unit tests for claim/release/releaseAll/concurrent claims/idempotent claim/canonical path normalization
- **WorkerMessageBus:** Unit tests for create/close inbox, send/drain, capacity overflow (DROP_OLDEST), close-all
- **WorkerContext:** Unit test verifying coroutine context propagation through `withContext`
- **SendMessageToParentTool:** Unit test for execute with mocked bus
- **SpawnAgentTool send path:** Unit test for the new send/message parameter handling
- **EditFileTool ownership check:** Unit test for DENIED claim returning error + FILE_CONFLICT message
- **ReadFileTool warning:** Unit test for owned-by-other warning header
- **Integration:** Test full flow — spawn 2 workers, one claims file, second gets DENIED, parent receives FILE_CONFLICT message
- **Timeout removal:** Verify no `TimeoutCancellationException` paths remain, workers run to iteration limit

## Non-Goals

- **Inter-worker messaging** (worker A → worker B): Not needed. Hub-and-spoke via orchestrator is the industry standard. Workers communicate through the parent.
- **File-region locking** (line-level): Whole-file granularity is sufficient. Line-level locking adds complexity with minimal benefit for typical agent workloads.
- **Message persistence:** Messages are ephemeral (in-memory channels). Worker transcripts already capture the full conversation; messages are coordination signals, not permanent records.
- **Structured WorkerResult:** A valid improvement but orthogonal to coordination. Can be added separately.
