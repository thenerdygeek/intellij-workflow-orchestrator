# Sub-Agent Control Gaps — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close 3 sub-agent control gaps: status visibility, manual rollback, and background agent checkpointing — aligned with Claude Code's patterns.

**Architecture:** Three independent features. Each adds a new capability without modifying existing sub-agent execution flow. Status tool exposes existing infrastructure. Rollback adds a parameter to the `agent` tool. Background checkpoints extend existing foreground checkpoint logic.

**Tech Stack:** Kotlin, IntelliJ Platform LocalHistory API, kotlinx.coroutines, kotlinx.serialization

**Research backing:** `docs/research/2026-03-24-enterprise-grade-gap-analysis.md`, Claude Code sub-agent docs, Codex CLI subagent docs

---

## File Structure

| File | Responsibility |
|------|---------------|
| Create: `agent/tools/builtin/ListAgentsTool.kt` | Tool for LLM to check background agent status |
| Create: `agent/test/.../tools/builtin/ListAgentsToolTest.kt` | Tests for status tool |
| Modify: `agent/tools/builtin/SpawnAgentTool.kt` | Add `rollback` parameter for manual undo |
| Modify: `agent/runtime/AgentRollbackManager.kt` | Add `rollbackAgent(agentId)` method |
| Modify: `agent/AgentService.kt` | Store checkpoint IDs per agent, expose to rollback |
| Modify: `agent/test/.../tools/builtin/SpawnAgentToolTest.kt` | Tests for rollback parameter |

---

### Task 1: ListAgentsTool — Background Agent Status Visibility

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ListAgentsTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/ListAgentsToolTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
@Test fun `returns empty when no background agents`() {
    val tool = ListAgentsTool(agentService)
    val result = runBlocking { tool.execute(JsonObject(emptyMap()), project) }
    assertTrue(result.content.contains("No background agents"))
}

@Test fun `returns status of running agent`() {
    agentService.backgroundWorkers["abc123"] = BackgroundWorker(
        agentId = "abc123", job = Job(), subagentType = "coder",
        description = "Fix bug", startedAt = Instant.now(), status = "running"
    )
    val result = runBlocking { tool.execute(JsonObject(emptyMap()), project) }
    assertTrue(result.content.contains("abc123"))
    assertTrue(result.content.contains("running"))
    assertTrue(result.content.contains("coder"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.ListAgentsToolTest"`

- [ ] **Step 3: Implement ListAgentsTool**

```kotlin
class ListAgentsTool(private val agentService: AgentService) : AgentTool {
    override val name = "list_agents"
    override val description = "List all background agents with their status, type, and duration. " +
        "Use this to check if a background agent has completed or is still running."
    override val parameters = FunctionParameters(
        type = "object",
        properties = emptyMap(),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val workers = agentService.listBackgroundWorkers()
        if (workers.isEmpty()) {
            return ToolResult("No background agents running or completed.", "No agents", 10)
        }
        val lines = workers.map { w ->
            val elapsed = Duration.between(w.startedAt, Instant.now()).toSeconds()
            "- ${w.agentId} (${w.subagentType}): ${w.status} | ${w.description} | ${elapsed}s elapsed"
        }
        val content = "Background agents:\n${lines.joinToString("\n")}"
        return ToolResult(content, "${workers.size} agents listed", 20 + workers.size * 10)
    }
}
```

- [ ] **Step 4: Register in AgentService tool registry**

Find where tools are registered in `AgentService` and add:
```kotlin
toolRegistry.register(ListAgentsTool(this))
```

- [ ] **Step 5: Run tests, commit**

```
git commit -m "feat(agent): list_agents tool for background agent status visibility"
```

---

### Task 2: Manual Rollback via `agent(rollback='agentId')`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentRollbackManager.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

- [ ] **Step 1: Add checkpoint storage to AgentService**

In `AgentService.kt`, add a map to track checkpoint IDs per agent:
```kotlin
val agentCheckpoints = ConcurrentHashMap<String, String>()  // agentId -> checkpointId
```

- [ ] **Step 2: Store checkpoint ID when creating foreground agents**

In `SpawnAgentTool.kt`, after creating the checkpoint (around the existing `rollbackManager.createCheckpoint()` call), store it:
```kotlin
val checkpointId = rollbackManager.createCheckpoint(description)
agentService.agentCheckpoints[agentId] = checkpointId
```

- [ ] **Step 3: Add `rollback` parameter to SpawnAgentTool**

Add to the tool's parameters:
```kotlin
"rollback" to PropertyDefinition("string", "Agent ID whose changes should be rolled back. Reverts all file changes made by that agent.")
```

Add handling at the start of `execute()` (after kill handling):
```kotlin
val rollbackId = params["rollback"]?.jsonPrimitive?.contentOrNull
if (rollbackId != null) {
    val checkpointId = agentService.agentCheckpoints[rollbackId]
        ?: return errorResult("No checkpoint found for agent '$rollbackId'. Only foreground agents have rollback checkpoints.")
    val success = rollbackManager.rollbackToCheckpoint(checkpointId)
    return if (success) {
        agentService.agentCheckpoints.remove(rollbackId)
        ToolResult("Rolled back all file changes made by agent '$rollbackId'.", "Rolled back $rollbackId", 20)
    } else {
        errorResult("Failed to rollback agent '$rollbackId'. Checkpoint may have expired.")
    }
}
```

- [ ] **Step 4: Update tool description to mention rollback**

Add to the tool description:
```
Rollback: agent(rollback='agentId') to undo all file changes made by a specific agent.
```

- [ ] **Step 5: Write tests**

```kotlin
@Test fun `rollback reverts agent changes`() { ... }
@Test fun `rollback fails for unknown agent`() { ... }
@Test fun `rollback fails for background agent without checkpoint`() { ... }
```

- [ ] **Step 6: Run tests, commit**

```
git commit -m "feat(agent): manual rollback via agent(rollback='agentId')"
```

---

### Task 3: Background Agent Checkpoint Creation

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt`

- [ ] **Step 1: Find the background agent execution path**

In `SpawnAgentTool.kt`, find the block that handles `run_in_background=true` (around the `executeBackground` method or the background launch block).

- [ ] **Step 2: Add checkpoint creation before background agent starts**

Before launching the background coroutine, create a checkpoint:
```kotlin
// Create rollback checkpoint for background agent (same as foreground)
val bgCheckpointId = rollbackManager?.createCheckpoint("Background: $description")
if (bgCheckpointId != null) {
    agentService.agentCheckpoints[agentId] = bgCheckpointId
}
```

- [ ] **Step 3: Add rollback on background agent failure**

In the background agent's catch block (where failure is handled), add:
```kotlin
catch (e: Exception) {
    // Rollback on background agent failure
    val cpId = agentService.agentCheckpoints[agentId]
    if (cpId != null) {
        rollbackManager?.rollbackToCheckpoint(cpId)
        log.info("[Agent] Background agent $agentId failed, rolled back to checkpoint")
    }
    // ... existing error handling
}
```

- [ ] **Step 4: Add rollback on kill**

In the kill handler (AgentService.killWorker), after cancelling the job:
```kotlin
fun killWorker(agentId: String): Boolean {
    val worker = backgroundWorkers[agentId] ?: return false
    worker.job.cancel()
    worker.status = "killed"
    // Rollback file changes on kill
    val cpId = agentCheckpoints[agentId]
    if (cpId != null) {
        rollbackManager?.rollbackToCheckpoint(cpId)
        agentCheckpoints.remove(agentId)
    }
    backgroundWorkers.remove(agentId)
    activeWorkerCount.decrementAndGet()
    return true
}
```

- [ ] **Step 5: Write test**

```kotlin
@Test fun `killed background agent gets rolled back`() { ... }
```

- [ ] **Step 6: Run tests, commit**

```
git commit -m "feat(agent): background agents now have rollback checkpoints (auto-rollback on failure/kill)"
```

---

## Summary

| Task | What | Aligned With |
|------|------|-------------|
| 1 | `list_agents` tool — LLM can check background agent status | Claude Code `/tasks`, Cline per-subagent stats |
| 2 | `agent(rollback='agentId')` — LLM can manually undo agent changes | Claude Code `/rewind`, checkpointing |
| 3 | Background agent checkpoints — auto-rollback on failure/kill | Claude Code worktree isolation (delete worktree = discard) |

**Estimated effort:** 1-2 days for all 3 tasks.
