# Unified LLM-Controlled Delegation Architecture — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the disconnected system-controlled orchestrator with a single `delegate_task` tool that lets the LLM spawn scoped workers, unifying planning and execution under LLM control.

**Architecture:** New `DelegateTaskTool` spawns `WorkerSession` instances with fresh context and scoped tools. Budget escalation becomes a nudge (not a hard stop). Context compression gains LLM-powered summarization for tool results. System-driven `executePlan()`/`createPlan()` removed entirely.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform 2025.1+, kotlinx.coroutines, kotlinx.serialization, Sourcegraph LLM API (150K input, 4K output tokens)

**Spec:** `docs/superpowers/specs/2026-03-20-unified-delegation-architecture.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `agent/src/main/kotlin/.../tools/builtin/DelegateTaskTool.kt` | The `delegate_task` tool — validates params, spawns WorkerSession, tracks resources, returns structured result |
| `agent/src/test/kotlin/.../tools/builtin/DelegateTaskToolTest.kt` | Tests for delegation: validation, resource limits, rollback, result formatting |
| `agent/src/test/kotlin/.../context/ContextManagerCompressionTest.kt` | Tests for LLM-powered compression |

### Modified Files
| File | Change Summary |
|------|---------------|
| `agent/.../runtime/BudgetEnforcer.kt` | Replace ESCALATE with NUDGE/STRONG_NUDGE/TERMINATE |
| `agent/.../runtime/SingleAgentSession.kt` | Replace escalation handler with nudge injection + explicit LLM compression |
| `agent/.../context/ContextManager.kt` | Add `compressWithLlm()` suspend function, change brain type |
| `agent/.../orchestrator/AgentOrchestrator.kt` | Remove executePlan/createPlan/requestPlan/runWorker, remove PlanReady/EscalateToOrchestrated handling |
| `agent/.../AgentService.kt` | Add worker tracking fields, register DelegateTaskTool |
| `agent/.../runtime/AgentEventLog.kt` | Add worker telemetry event types |
| `agent/.../runtime/WorkerSession.kt` | Add Job cancellation support |
| `agent/.../tools/ToolCategoryRegistry.kt` | Add delegate_task to core category |
| `agent/.../tools/DynamicToolSelector.kt` | Add delegate_task to ALWAYS_INCLUDE |
| `agent/.../orchestrator/PromptAssembler.kt` | Add delegation rules to system prompt |
| `agent/.../orchestrator/OrchestratorPrompts.kt` | Remove ORCHESTRATOR_SYSTEM_PROMPT, update worker prompts |
| `agent/.../tools/builtin/CreatePlanTool.kt` | Update approval response to mention delegate_task |
| `agent/.../settings/AgentSettings.kt` | Add maxSessionTokens field |
| `agent/.../ui/AgentController.kt` | Remove PlanReady handling, add delegation progress |

### Deleted Files
| File | Reason |
|------|--------|
| `agent/.../runtime/AgentMode.kt` | No longer needed — always single agent with optional delegation |

---

## Task 1: BudgetEnforcer — Replace ESCALATE with Nudge Thresholds

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/BudgetEnforcer.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/BudgetEnforcerTest.kt`

- [ ] **Step 1: Update BudgetEnforcerTest with new status values**

Open `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/BudgetEnforcerTest.kt` and add/update tests:

```kotlin
@Test
fun `check returns NUDGE between 60 and 75 percent`() {
    // Set context to 65% of effective budget
    val cm = mockContextManager(tokens = 97_500) // 65% of 150K
    val enforcer = BudgetEnforcer(cm, effectiveBudget = 150_000)
    assertEquals(BudgetEnforcer.BudgetStatus.NUDGE, enforcer.check())
}

@Test
fun `check returns STRONG_NUDGE between 75 and 90 percent`() {
    val cm = mockContextManager(tokens = 120_000) // 80% of 150K
    val enforcer = BudgetEnforcer(cm, effectiveBudget = 150_000)
    assertEquals(BudgetEnforcer.BudgetStatus.STRONG_NUDGE, enforcer.check())
}

@Test
fun `check returns TERMINATE above 90 percent`() {
    val cm = mockContextManager(tokens = 140_000) // 93% of 150K
    val enforcer = BudgetEnforcer(cm, effectiveBudget = 150_000)
    assertEquals(BudgetEnforcer.BudgetStatus.TERMINATE, enforcer.check())
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.BudgetEnforcerTest" --rerun --no-build-cache
```
Expected: FAIL — `NUDGE`, `STRONG_NUDGE`, `TERMINATE` don't exist yet.

- [ ] **Step 3: Implement new budget statuses**

In `BudgetEnforcer.kt`, replace the enum and thresholds:

```kotlin
companion object {
    private val LOG = Logger.getInstance(BudgetEnforcer::class.java)
    private const val COMPRESSION_RATIO = 0.40
    private const val NUDGE_RATIO = 0.60
    private const val STRONG_NUDGE_RATIO = 0.75
    private const val TERMINATE_RATIO = 0.90
}

private val compressionThreshold = (effectiveBudget * COMPRESSION_RATIO).toInt()
private val nudgeThreshold = (effectiveBudget * NUDGE_RATIO).toInt()
private val strongNudgeThreshold = (effectiveBudget * STRONG_NUDGE_RATIO).toInt()
private val terminateThreshold = (effectiveBudget * TERMINATE_RATIO).toInt()

fun check(): BudgetStatus {
    val used = contextManager.currentTokens
    return when {
        used < compressionThreshold -> BudgetStatus.OK
        used < nudgeThreshold -> {
            LOG.info("BudgetEnforcer: compression zone ($used/$effectiveBudget, ${utilizationPercent()}%)")
            BudgetStatus.COMPRESS
        }
        used < strongNudgeThreshold -> {
            LOG.info("BudgetEnforcer: nudge zone ($used/$effectiveBudget, ${utilizationPercent()}%)")
            BudgetStatus.NUDGE
        }
        used < terminateThreshold -> {
            LOG.warn("BudgetEnforcer: strong nudge zone ($used/$effectiveBudget, ${utilizationPercent()}%)")
            BudgetStatus.STRONG_NUDGE
        }
        else -> {
            LOG.warn("BudgetEnforcer: termination zone ($used/$effectiveBudget, ${utilizationPercent()}%)")
            BudgetStatus.TERMINATE
        }
    }
}

enum class BudgetStatus {
    OK,
    COMPRESS,
    NUDGE,
    STRONG_NUDGE,
    TERMINATE
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.BudgetEnforcerTest" --rerun --no-build-cache
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/BudgetEnforcer.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/BudgetEnforcerTest.kt
git commit -m "refactor(agent): replace ESCALATE with NUDGE/STRONG_NUDGE/TERMINATE budget statuses"
```

---

## Task 2: SingleAgentSession — Nudge Injection + Remove Escalation

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt:24-46,170-198`

- [ ] **Step 1: Remove `EscalateToOrchestrated` from `SingleAgentResult`**

In `SingleAgentSession.kt` lines 42-46, delete:
```kotlin
    data class EscalateToOrchestrated(
        val reason: String,
        val partialContext: String,
        val tokensUsed: Int
    ) : SingleAgentResult()
```

- [ ] **Step 2: Replace ESCALATE handler with nudge injection**

In `SingleAgentSession.kt` lines 170-198, replace the entire `when (budgetEnforcer.check())` block:

```kotlin
// Budget check before each LLM call
val budgetStatus = budgetEnforcer.check()
when (budgetStatus) {
    BudgetEnforcer.BudgetStatus.TERMINATE -> {
        LOG.warn("SingleAgentSession: budget exhausted at iteration $iteration (${budgetEnforcer.utilizationPercent()}%)")
        eventLog?.log(AgentEventType.SESSION_FAILED, "Budget terminated at ${budgetEnforcer.utilizationPercent()}%")
        return SingleAgentResult.Failed(
            error = "Context budget exhausted at ${budgetEnforcer.utilizationPercent()}%. Please start a new conversation for remaining work.",
            tokensUsed = totalTokensUsed
        )
    }
    BudgetEnforcer.BudgetStatus.STRONG_NUDGE -> {
        LOG.warn("SingleAgentSession: strong nudge at iteration $iteration (${budgetEnforcer.utilizationPercent()}%)")
        // Note: addMessage() auto-triggers compression if tokens exceed tMax,
        // so no explicit compress() call needed (avoids double-compression)
        contextManager.addMessage(ChatMessage(
            role = "system",
            content = "WARNING: You have used ${budgetEnforcer.utilizationPercent()}% of your context budget. You MUST use delegate_task for any remaining task touching 2+ files. Single-file edits are still allowed directly."
        ))
    }
    BudgetEnforcer.BudgetStatus.NUDGE -> {
        LOG.info("SingleAgentSession: nudge at iteration $iteration (${budgetEnforcer.utilizationPercent()}%)")
        contextManager.addMessage(ChatMessage(
            role = "system",
            content = "Context at ${budgetEnforcer.utilizationPercent()}%. Prefer delegate_task for remaining multi-file work — each worker gets a fresh context window."
        ))
    }
    BudgetEnforcer.BudgetStatus.COMPRESS -> {
        LOG.info("SingleAgentSession: triggering LLM compression at iteration $iteration")
        eventLog?.log(AgentEventType.COMPRESSION_TRIGGERED, "At iteration $iteration, ${budgetEnforcer.utilizationPercent()}% used")
        val tokensBefore = contextManager.currentTokens
        val messagesBefore = contextManager.messageCount
        // Use LLM-powered compression when brain is available (Task 3 adds compressWithLlm)
        contextManager.compressWithLlm(brain)
        sessionTrace?.compressionTriggered("budget_enforcer", tokensBefore, contextManager.currentTokens, messagesBefore - contextManager.messageCount)
    }
    BudgetEnforcer.BudgetStatus.OK -> { /* proceed */ }
}
```

- [ ] **Step 3: Remove `buildContextSummary` method if only used by escalation**

Search for `buildContextSummary` usages. If it's only called from the removed ESCALATE handler, delete the method.

- [ ] **Step 4: Verify compilation**

```bash
./gradlew :agent:compileKotlin --no-build-cache
```
Expected: may fail if AgentOrchestrator still references EscalateToOrchestrated — that's fixed in Task 5.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "refactor(agent): replace budget escalation with nudge injection in SingleAgentSession"
```

---

## Task 3: LLM-Powered Context Compression

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt:20-37,114-158`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerCompressionTest.kt`

- [ ] **Step 1: Write tests for LLM-powered compression**

Create `ContextManagerCompressionTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContextManagerCompressionTest {

    private fun mockBrain(summaryResponse: String): LlmBrain = mockk {
        coEvery { chat(any(), any(), any(), any()) } returns ApiResult.Success(
            ChatCompletionResponse(
                id = "test",
                model = "test",
                choices = listOf(Choice(index = 0, message = AssistantMessage(content = summaryResponse), finishReason = "stop")),
                usage = Usage(promptTokens = 100, completionTokens = 50, totalTokens = 150)
            )
        )
        every { estimateTokens(any()) } answers { firstArg<String>().length / 4 }
        every { modelId } returns "test-model"
    }

    @Test
    fun `compressWithLlm uses LLM when tool results are present`() = runTest {
        val brain = mockBrain("Summary: User asked to fix NPE. Agent read UserService.kt and edited line 45.")
        val cm = ContextManager(maxInputTokens = 1000, tMaxRatio = 0.30, tRetainedRatio = 0.10)

        // Add messages that exceed tMax, including a tool result
        cm.addMessage(ChatMessage(role = "system", content = "You are a coding agent."))
        cm.addMessage(ChatMessage(role = "user", content = "Fix the NPE in UserService"))
        cm.addMessage(ChatMessage(role = "assistant", content = "I'll read the file."))
        cm.addMessage(ChatMessage(role = "tool", content = "package com.example...(long file content)...", toolCallId = "tc1"))
        cm.addMessage(ChatMessage(role = "assistant", content = "Found the issue at line 45."))

        cm.compressWithLlm(brain)

        // Verify LLM was called for summarization
        coVerify(exactly = 1) { brain.chat(any(), isNull(), any(), any()) }
    }

    @Test
    fun `compressWithLlm skips LLM when no tool results in dropped messages`() = runTest {
        val brain = mockBrain("Should not be called")
        val cm = ContextManager(maxInputTokens = 500, tMaxRatio = 0.30, tRetainedRatio = 0.10)

        cm.addMessage(ChatMessage(role = "system", content = "You are a coding agent."))
        cm.addMessage(ChatMessage(role = "user", content = "Hello"))
        cm.addMessage(ChatMessage(role = "assistant", content = "Hi there!"))
        cm.addMessage(ChatMessage(role = "user", content = "How are you?"))

        cm.compressWithLlm(brain)

        // LLM should NOT be called — no tool results to summarize
        coVerify(exactly = 0) { brain.chat(any(), any(), any(), any()) }
    }

    @Test
    fun `compressWithLlm falls back to truncation on LLM error`() = runTest {
        val brain = mockk<LlmBrain> {
            coEvery { chat(any(), any(), any(), any()) } returns ApiResult.Error("API error", 500)
            every { estimateTokens(any()) } answers { firstArg<String>().length / 4 }
            every { modelId } returns "test-model"
        }
        val cm = ContextManager(maxInputTokens = 1000, tMaxRatio = 0.30, tRetainedRatio = 0.10)

        cm.addMessage(ChatMessage(role = "system", content = "System"))
        cm.addMessage(ChatMessage(role = "user", content = "Fix it"))
        cm.addMessage(ChatMessage(role = "tool", content = "Long tool result...", toolCallId = "tc1"))
        cm.addMessage(ChatMessage(role = "assistant", content = "Done"))

        // Should not throw — falls back to truncation
        cm.compressWithLlm(brain)

        assertTrue(cm.currentTokens < 1000)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.context.ContextManagerCompressionTest" --rerun --no-build-cache
```
Expected: FAIL — `compressWithLlm` doesn't exist yet.

- [ ] **Step 3: Implement `compressWithLlm` in ContextManager**

In `ContextManager.kt`, change the `brain` parameter type and add the new method:

Change line 22-23 from:
```kotlin
@Deprecated("LLM summarization removed — brain was never passed in production. Kept for backward compat.")
private val brain: Any? = null,
```
To:
```kotlin
private val brain: LlmBrain? = null,
```

Add import at top:
```kotlin
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.core.model.ApiResult
```

Add after the existing `compress()` method (after line 147):

```kotlin
/**
 * Compress with LLM-powered summarization for tool results.
 * Called explicitly by SingleAgentSession when BudgetEnforcer signals COMPRESS.
 * Falls back to truncation if LLM call fails.
 *
 * @param llmBrain The LLM to use for summarization
 */
suspend fun compressWithLlm(llmBrain: LlmBrain) {
    if (messages.size <= 2) return

    var tokensToRemove = totalTokens - tRetained
    val messagesToDrop = mutableListOf<ChatMessage>()
    val indicesToRemove = mutableListOf<Int>()

    for (i in messages.indices) {
        if (tokensToRemove <= 0) break
        val msg = messages[i]
        if (msg.role == "system") continue
        val msgTokens = TokenEstimator.estimate(listOf(msg))
        messagesToDrop.add(msg)
        indicesToRemove.add(i)
        tokensToRemove -= msgTokens
    }

    if (messagesToDrop.isEmpty()) return

    // Check if any dropped messages contain tool results (high-information content)
    val hasToolResults = messagesToDrop.any { it.role == "tool" }

    val summary = if (hasToolResults) {
        // LLM-powered summarization for tool results
        try {
            val content = messagesToDrop.mapNotNull { it.content }.joinToString("\n---\n")
            val summaryMessages = listOf(
                ChatMessage(role = "user", content = """
                    Summarize the following conversation segment. Preserve: file paths,
                    line numbers, code changes made, errors encountered, key decisions.
                    Be concise (max 500 tokens).

                    ---
                    $content
                """.trimIndent())
            )
            when (val result = llmBrain.chat(messages = summaryMessages, tools = null, maxTokens = 600)) {
                is ApiResult.Success -> {
                    result.data.choices.firstOrNull()?.message?.content
                        ?: summarizer(messagesToDrop)
                }
                is ApiResult.Error -> {
                    // Fall back to truncation on API error
                    summarizer(messagesToDrop)
                }
            }
        } catch (_: Exception) {
            summarizer(messagesToDrop)
        }
    } else {
        // Plain text — use cheap truncation
        summarizer(messagesToDrop)
    }

    anchoredSummaries.add(summary)
    indicesToRemove.reversed().forEach { messages.removeAt(it) }
    totalTokens = TokenEstimator.estimate(getMessages())
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.context.ContextManagerCompressionTest" --rerun --no-build-cache
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ContextManagerCompressionTest.kt
git commit -m "feat(agent): LLM-powered context compression for tool results"
```

---

## Task 4: Worker Telemetry Events + Settings

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentEventLog.kt:13-20`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt:13-23`

- [ ] **Step 1: Add worker event types to AgentEventType**

In `AgentEventLog.kt` lines 13-20, add to the enum:

```kotlin
enum class AgentEventType {
    SESSION_STARTED, SESSION_COMPLETED, SESSION_FAILED,
    TOOL_CALLED, TOOL_SUCCEEDED, TOOL_FAILED,
    EDIT_APPLIED, EDIT_REJECTED_SYNTAX,
    COMPRESSION_TRIGGERED, ESCALATION_TRIGGERED,
    LOOP_DETECTED, APPROVAL_REQUESTED, APPROVAL_GRANTED, APPROVAL_DENIED,
    RATE_LIMITED_RETRY, CONTEXT_EXCEEDED_RETRY, SNAPSHOT_CREATED,
    // Worker delegation events
    WORKER_SPAWNED, WORKER_COMPLETED, WORKER_FAILED, WORKER_TIMED_OUT, WORKER_ROLLED_BACK
}
```

- [ ] **Step 2: Add maxSessionTokens to AgentSettings**

In `AgentSettings.kt`, add to the State class:

```kotlin
var maxSessionTokens by property(500_000)
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew :agent:compileKotlin --no-build-cache
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentEventLog.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt
git commit -m "feat(agent): add worker telemetry events and maxSessionTokens setting"
```

---

## Task 5: AgentOrchestrator — Remove System Orchestration

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt:25-46,234-239,247-300,306-455`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt:406-408`
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentMode.kt`

- [ ] **Step 1: Remove PlanReady from AgentResult**

In `AgentOrchestrator.kt` line 27, delete:
```kotlin
data class PlanReady(val plan: TaskGraph, val description: String) : AgentResult()
```

- [ ] **Step 2: Remove EscalateToOrchestrated handling in executeTask()**

In `AgentOrchestrator.kt` lines 234-239, replace:
```kotlin
is SingleAgentResult.EscalateToOrchestrated -> {
    LOG.info("AgentOrchestrator: auto-escalating to orchestrated mode: ${result.reason}")
    onProgress(AgentProgress("Task too complex for single pass. Creating plan...", WorkerType.ORCHESTRATOR))
    createPlan(taskDescription, onProgress)
}
```
With nothing — the `when` should now only handle `Completed` and `Failed`.

- [ ] **Step 3: Remove createPlan(), requestPlan(), executePlan(), runWorker(), parsePlanToTaskGraph()**

Delete these methods entirely:
- `requestPlan()` (lines 247-254)
- `runWorker()` (lines 260-269)
- `createPlan()` (lines 275-300)
- `executePlan()` (lines 306-391)
- `parsePlanToTaskGraph()` (lines 405-455)

Remove any now-unused imports (TaskGraph, TaskStatus, CheckpointStore, AgentCheckpoint, OrchestratorPrompts, EventBus, WorkflowEvent, etc.).

- [ ] **Step 4: Remove PlanReady handling from AgentController**

In `AgentController.kt` lines 406-408, delete:
```kotlin
is AgentResult.PlanReady -> {
    dashboard.showOrchestrationPlan(result.plan.getAllTasks())
}
```

- [ ] **Step 5: Verify AgentMode is not serialized, then delete**

First verify AgentMode is not in any serialized state:
```bash
grep -r "AgentMode" agent/src/ --include="*.kt" --include="*.xml"
```
Expected: Only `AgentMode.kt` itself. If found elsewhere in serialized state (XML, checkpoint), deprecate instead of delete.

Then delete:
```bash
rm agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentMode.kt
```

Remove any imports of `AgentMode` in other files.

- [ ] **Step 6: Verify compilation**

```bash
./gradlew :agent:compileKotlin --no-build-cache
```
Expected: PASS

- [ ] **Step 7: Run full test suite**

```bash
./gradlew :agent:test --rerun --no-build-cache
```
Expected: PASS (fix any tests that referenced PlanReady or ESCALATE)

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt \
       agent/src/test/kotlin/
git commit -m "refactor(agent): remove system-controlled orchestration (executePlan, createPlan, PlanReady, EscalateToOrchestrated)"
```

---

## Task 6: WorkerSession — Add Cancellation Support

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerSession.kt:36-42,57-66,76-77,121-122`

- [ ] **Step 1: Add Job parameter and cancellation checks**

Update `WorkerSession` constructor and `execute()`:

```kotlin
class WorkerSession(
    private val maxIterations: Int = 10,
    private val parentJob: kotlinx.coroutines.Job? = null
) {
```

In the `execute()` method, add cancellation check at the start of each iteration (line 76) and before each tool execution (line 121):

```kotlin
// At start of iteration loop (line 76):
for (iteration in 1..maxIterations) {
    // Check cancellation from parent
    if (parentJob?.isActive == false) {
        LOG.info("WorkerSession: cancelled by parent at iteration $iteration")
        return WorkerResult(
            content = "Worker cancelled by parent session.",
            summary = "Cancelled at iteration $iteration",
            tokensUsed = totalTokensUsed,
            artifacts = allArtifacts,
            isError = true
        )
    }
    LOG.info("WorkerSession: iteration $iteration/$maxIterations")
```

```kotlin
// Before tool execution (inside the for (toolCall in toolCalls) loop, line 121):
if (parentJob?.isActive == false) {
    LOG.info("WorkerSession: cancelled before tool '$toolName' execution")
    return WorkerResult("Cancelled", "Cancelled", totalTokensUsed, allArtifacts, isError = true)
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :agent:compileKotlin --no-build-cache
```
Expected: PASS

- [ ] **Step 3: Run tests**

```bash
./gradlew :agent:test --rerun --no-build-cache
```
Expected: PASS (existing tests don't pass parentJob, so default null → no cancellation checks)

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/WorkerSession.kt
git commit -m "feat(agent): add parent Job cancellation support to WorkerSession"
```

---

## Task 7: DelegateTaskTool — Core Implementation

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/DelegateTaskTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/DelegateTaskToolTest.kt`

- [ ] **Step 1: Write tests for DelegateTaskTool**

Create `DelegateTaskToolTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.settings.AgentSettings
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DelegateTaskToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = DelegateTaskTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("delegate_task", tool.name)
        assertTrue(tool.parameters.required.containsAll(listOf("task", "worker_type", "context")))
        // delegate_task should NOT be allowed for workers (no nesting)
        assertFalse(tool.allowedWorkers.isEmpty()) // allowed for main agent types
    }

    @Test
    fun `returns error when task is too short`() = runTest {
        val params = buildJsonObject {
            put("task", "fix it")
            put("worker_type", "coder")
            put("context", "File: UserService.kt")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("at least 50 characters"))
    }

    @Test
    fun `returns error when context has no file path`() = runTest {
        val params = buildJsonObject {
            put("task", "A".repeat(60))
            put("worker_type", "coder")
            put("context", "Just some text without any file reference")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("file path"))
    }

    @Test
    fun `returns error for invalid worker type`() = runTest {
        val params = buildJsonObject {
            put("task", "A".repeat(60))
            put("worker_type", "invalid_type")
            put("context", "File: src/main/kotlin/UserService.kt")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Invalid worker_type"))
    }

    @Test
    fun `returns error when worker limit reached`() = runTest {
        val agentService = mockk<AgentService>(relaxed = true)
        every { agentService.activeWorkerCount } returns AtomicInteger(5)
        mockkStatic(AgentService::class)
        every { AgentService.getInstance(project) } returns agentService

        val params = buildJsonObject {
            put("task", "A".repeat(60))
            put("worker_type", "coder")
            put("context", "File: src/main/kotlin/UserService.kt")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Maximum"))

        unmockkStatic(AgentService::class)
    }

    @Test
    fun `returns error when session token limit exceeded`() = runTest {
        val agentService = mockk<AgentService>(relaxed = true)
        every { agentService.activeWorkerCount } returns AtomicInteger(0)
        every { agentService.totalSessionTokens } returns AtomicLong(600_000)
        mockkStatic(AgentService::class)
        every { AgentService.getInstance(project) } returns agentService

        val settings = mockk<AgentSettings>(relaxed = true)
        every { settings.state } returns AgentSettings.State().apply { maxSessionTokens = 500_000 }
        mockkStatic(AgentSettings::class)
        every { AgentSettings.getInstance(project) } returns settings

        val params = buildJsonObject {
            put("task", "A".repeat(60))
            put("worker_type", "coder")
            put("context", "File: src/main/kotlin/UserService.kt")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("token limit"))

        unmockkStatic(AgentService::class, AgentSettings::class)
    }

    @Test
    fun `returns error when required params missing`() = runTest {
        val params = buildJsonObject {
            put("task", "A".repeat(60))
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
    }

    @Test
    fun `description mentions worker types`() {
        val desc = tool.description
        assertTrue(desc.contains("coder"))
        assertTrue(desc.contains("analyzer"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.DelegateTaskToolTest" --rerun --no-build-cache
```
Expected: FAIL — `DelegateTaskTool` doesn't exist.

- [ ] **Step 3: Implement DelegateTaskTool**

Create `DelegateTaskTool.kt`:

```kotlin
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LLM-controlled delegation tool. Spawns a scoped WorkerSession to execute
 * a focused sub-task with fresh context and role-specific tools.
 *
 * Workers cannot call delegate_task (no nesting) — enforced via allowedWorkers.
 */
class DelegateTaskTool : AgentTool {
    companion object {
        private val LOG = Logger.getInstance(DelegateTaskTool::class.java)
        private const val MAX_WORKERS = 5
        private const val WORKER_TIMEOUT_MS = 300_000L // 5 minutes
        private const val MIN_TASK_LENGTH = 50
        private val FILE_PATH_PATTERN = Regex("""[\w/\\]+\.\w{1,10}""")
        private val VALID_WORKER_TYPES = setOf("coder", "analyzer", "reviewer", "tooler")
    }

    override val name = "delegate_task"
    override val description = "Spawn a focused worker to handle a sub-task. The worker gets fresh context and scoped tools. Use for complex steps (3+ files), analysis tasks, or when your context is growing large. Worker types: coder (edits code), analyzer (read-only analysis), reviewer (code review), tooler (Jira/Bamboo/Sonar/Bitbucket)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "task" to ParameterProperty(
                type = "string",
                description = "Detailed description of what the worker should do (min 50 chars). Include specific files, line numbers, and expected outcome."
            ),
            "worker_type" to ParameterProperty(
                type = "string",
                description = "Worker specialization: 'coder' (read+edit), 'analyzer' (read-only), 'reviewer' (review changes), 'tooler' (enterprise tools)"
            ),
            "context" to ParameterProperty(
                type = "string",
                description = "Relevant context the worker needs. MUST include file paths. The worker cannot see your conversation history — provide everything it needs here."
            )
        ),
        required = listOf("task", "worker_type", "context")
    )
    // Only ORCHESTRATOR can use this — workers (CODER, ANALYZER, REVIEWER, TOOLER) cannot.
    // ToolRegistry.getToolsForWorker(CODER) won't include this tool, preventing nesting.
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // --- Parameter Validation ---
        val task = params["task"]?.jsonPrimitive?.content
            ?: return errorResult("'task' parameter required")
        val workerTypeStr = params["worker_type"]?.jsonPrimitive?.content
            ?: return errorResult("'worker_type' parameter required")
        val context = params["context"]?.jsonPrimitive?.content
            ?: return errorResult("'context' parameter required")

        if (task.length < MIN_TASK_LENGTH) {
            return errorResult("Task description must be at least $MIN_TASK_LENGTH characters. Provide a detailed description so the worker knows exactly what to do.")
        }
        if (workerTypeStr !in VALID_WORKER_TYPES) {
            return errorResult("Invalid worker_type '$workerTypeStr'. Must be one of: ${VALID_WORKER_TYPES.joinToString()}")
        }
        if (!FILE_PATH_PATTERN.containsMatchIn(context)) {
            return errorResult("Context must include at least one file path so the worker knows which files to work with.")
        }

        val workerType = when (workerTypeStr) {
            "coder" -> WorkerType.CODER
            "analyzer" -> WorkerType.ANALYZER
            "reviewer" -> WorkerType.REVIEWER
            "tooler" -> WorkerType.TOOLER
            else -> return errorResult("Invalid worker_type")
        }

        // --- Resource Checks ---
        val agentService = try {
            AgentService.getInstance(project)
        } catch (e: Exception) {
            return errorResult("Agent service not available: ${e.message}")
        }

        if (agentService.activeWorkerCount.get() >= MAX_WORKERS) {
            return errorResult("Maximum $MAX_WORKERS concurrent workers reached. Wait for current workers to complete.")
        }

        val maxSessionTokens = try {
            AgentSettings.getInstance(project).state.maxSessionTokens
        } catch (_: Exception) { 500_000 }

        if (agentService.totalSessionTokens.get() > maxSessionTokens) {
            return errorResult("Session token limit ($maxSessionTokens) exceeded. Complete remaining work yourself or finish up.")
        }

        // --- Retry Limit Check ---
        val retryKey = "$workerTypeStr:${FILE_PATH_PATTERN.findAll(context).map { it.value }.sorted().joinToString(",")}"
        val attempts = agentService.delegationAttempts.getOrDefault(retryKey, 0)
        if (attempts >= 2) {
            return errorResult("This task has failed twice (key: $retryKey). Handle it directly or skip it.")
        }

        // --- Spawn Worker ---
        agentService.activeWorkerCount.incrementAndGet()
        val eventLog = try {
            agentService.activeController?.let { ctrl ->
                // Access event log if available
                null // Will be wired when we have access to the session's event log
            }
        } catch (_: Exception) { null }

        try {
            LOG.info("DelegateTaskTool: spawning $workerType worker for: ${task.take(100)}")

            // Create rollback checkpoint
            val rollbackManager = AgentRollbackManager(project)
            val checkpointId = rollbackManager.createCheckpoint("Worker: $workerTypeStr — ${task.take(60)}")

            // Build worker context
            val maxTokens = try { AgentSettings.getInstance(project).state.maxInputTokens } catch (_: Exception) { 150_000 }
            val contextManager = ContextManager(maxInputTokens = maxTokens)
            val toolsList = agentService.toolRegistry.getToolsForWorker(workerType)
            val toolsMap = toolsList.associateBy { it.name }
            val toolDefs = agentService.toolRegistry.getToolDefinitionsForWorker(workerType)
            val systemPrompt = OrchestratorPrompts.getSystemPrompt(workerType)
            val parentJob = currentCoroutineContext().job
            val session = WorkerSession(parentJob = parentJob)

            // Combine task + context into worker's user message
            val workerTask = """
                $task

                <context>
                $context
                </context>
            """.trimIndent()

            val workerResult = try {
                withTimeout(WORKER_TIMEOUT_MS) {
                    session.execute(workerType, systemPrompt, workerTask, toolsMap, toolDefs, agentService.brain, contextManager, project)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                LOG.warn("DelegateTaskTool: worker timed out after ${WORKER_TIMEOUT_MS / 1000}s")
                // Revert on timeout
                rollbackManager.rollbackToCheckpoint(checkpointId)
                return ToolResult(
                    content = "Worker timed out after ${WORKER_TIMEOUT_MS / 1000} seconds. Files have been reverted. Consider breaking this into smaller sub-tasks.",
                    summary = "Worker timeout",
                    tokenEstimate = 20,
                    isError = true
                )
            }

            // Track tokens
            agentService.totalSessionTokens.addAndGet(workerResult.tokensUsed.toLong())

            // Handle result
            if (workerResult.isError) {
                LOG.warn("DelegateTaskTool: worker failed: ${workerResult.content.take(200)}")
                agentService.delegationAttempts.merge(retryKey, 1) { a, b -> a + b }
                rollbackManager.rollbackToCheckpoint(checkpointId)
                return ToolResult(
                    content = """
                        Worker ($workerTypeStr) failed. Files have been reverted.
                        Error: ${workerResult.content}

                        You can retry with delegate_task (max 2 attempts per task) or handle it yourself.
                    """.trimIndent(),
                    summary = "Worker failed: ${workerResult.summary}",
                    tokenEstimate = 20,
                    isError = true
                )
            }

            // Success
            val filesModified = workerResult.artifacts.distinct()
            val staleWarning = if (filesModified.isNotEmpty()) {
                "\n\nNote: The following files were modified by the worker and your cached versions may be stale. Re-read them if needed:\n${filesModified.joinToString("\n") { "  - $it" }}"
            } else ""

            return ToolResult(
                content = """
                    Worker ($workerTypeStr) completed successfully.
                    Files modified: ${if (filesModified.isEmpty()) "none" else filesModified.joinToString(", ")}
                    Summary: ${workerResult.summary}
                    Tokens used: ${workerResult.tokensUsed}$staleWarning
                """.trimIndent(),
                summary = "Worker ($workerTypeStr) done: ${workerResult.summary.take(100)}",
                tokenEstimate = 30,
                artifacts = workerResult.artifacts
            )

        } finally {
            agentService.activeWorkerCount.decrementAndGet()
        }
    }

    private fun errorResult(message: String) = ToolResult(
        content = "Error: $message",
        summary = "Delegation error",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.DelegateTaskToolTest" --rerun --no-build-cache
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/DelegateTaskTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/DelegateTaskToolTest.kt
git commit -m "feat(agent): delegate_task tool — LLM-controlled worker spawning"
```

---

## Task 8: Wire DelegateTaskTool Into the System

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`

- [ ] **Step 1: Add worker tracking fields to AgentService**

In `AgentService.kt`, add after the existing `pendingToolActivations` field:

```kotlin
val activeWorkerCount = java.util.concurrent.atomic.AtomicInteger(0)
val totalSessionTokens = java.util.concurrent.atomic.AtomicLong(0)
val delegationAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
```

- [ ] **Step 2: Register DelegateTaskTool in AgentService**

In the `toolRegistry` lazy initializer, add alongside the other tool registrations:

```kotlin
register(DelegateTaskTool())
```

Add import: `import com.workflow.orchestrator.agent.tools.builtin.DelegateTaskTool`

- [ ] **Step 3: Add delegate_task to ToolCategoryRegistry core category**

In `ToolCategoryRegistry.kt`, add `"delegate_task"` to the core category's tools list (line 28-33):

```kotlin
tools = listOf(
    "read_file", "edit_file", "search_code", "run_command",
    "diagnostics", "format_code", "optimize_imports",
    "file_structure", "find_definition", "find_references",
    "type_hierarchy", "call_hierarchy",
    "delegate_task"
)
```

- [ ] **Step 4: Add delegate_task to DynamicToolSelector ALWAYS_INCLUDE**

In `DynamicToolSelector.kt` lines 16-20, add `"delegate_task"`:

```kotlin
private val ALWAYS_INCLUDE = setOf(
    "read_file", "edit_file", "search_code", "run_command",
    "file_structure", "find_definition", "find_references", "type_hierarchy", "call_hierarchy",
    "diagnostics", "format_code", "optimize_imports",
    "delegate_task"
)
```

Also add `"delegate_task"` to `CORE_TOOL_NAMES` in `SingleAgentSession.kt` (line 77) so it survives context-exceeded tool reduction — delegation is most needed when context is exhausted:

```kotlin
val CORE_TOOL_NAMES = setOf("read_file", "edit_file", "search_code", "run_command", "diagnostics", "delegate_task")
```

Also add `"delegate_task"` to the post-`removeAll` block (like `request_tools`) so it can never be disabled:

After the existing `selectedNames.add("request_tools")` line, add:
```kotlin
// delegate_task is always available — it's the LLM's delegation escape hatch
selectedNames.add("delegate_task")
```

- [ ] **Step 5: Verify compilation and tests**

```bash
./gradlew :agent:compileKotlin --no-build-cache && ./gradlew :agent:test --rerun --no-build-cache
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt
git commit -m "feat(agent): wire delegate_task into tool registry, category registry, and dynamic selector"
```

---

## Task 9: Prompt Updates — Delegation Rules + Plan Approval

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/OrchestratorPrompts.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreatePlanTool.kt:98-103`

- [ ] **Step 1: Add delegation rules to PromptAssembler**

In `PromptAssembler.kt`, add a new constant after `PLANNING_RULES`:

```kotlin
val DELEGATION_RULES = """
    <delegation>
    You have access to delegate_task to spawn focused workers for specific sub-tasks.
    Each worker gets a fresh context window with scoped tools — they won't see your
    conversation history, so provide clear context in the task description.

    Guidelines:
    - Simple tasks (1-2 files, quick fix): handle yourself
    - Moderate to complex tasks (3+ files, multi-step edits): delegate to a coder worker
    - Analysis tasks (understand codebase, find references across modules): delegate to an analyzer worker
    - Review tasks (check quality after changes): delegate to a reviewer worker
    - Enterprise tool tasks (Jira, Bamboo, Sonar operations): delegate to a tooler worker
    - When you create a plan and a step is non-trivial, delegate it
    - Always provide the worker with: what to do, which files, and any relevant context
      from your conversation (the worker cannot see your history)
    - If a delegated task fails twice, handle it yourself or skip it
    </delegation>
""".trimIndent()
```

Include `DELEGATION_RULES` in `buildSingleAgentPrompt()` alongside the existing `PLANNING_RULES`.

- [ ] **Step 2: Update CreatePlanTool approval response**

In `CreatePlanTool.kt` lines 98-103, update the Approved response:

```kotlin
is PlanApprovalResult.Approved -> {
    ToolResult(
        content = "Plan approved by user. Execute the plan step by step. For simple steps (1-2 files), handle them directly. For complex steps (3+ files, multi-step edits), use delegate_task to spawn a focused worker. Update step status with update_plan_step as you progress ('running' when starting, 'done' when complete, 'failed' if it fails).",
        summary = "Plan approved (${steps.size} steps)",
        tokenEstimate = 40
    )
}
```

- [ ] **Step 3: Remove ORCHESTRATOR_SYSTEM_PROMPT, update worker prompts**

In `OrchestratorPrompts.kt`:
- Delete `ORCHESTRATOR_SYSTEM_PROMPT` (lines 11-55)
- Update `getSystemPrompt()` to remove the `ORCHESTRATOR` case or have it return a generic prompt
- Add to each worker prompt: "You are a focused worker agent. Complete your assigned task and return a clear summary of what you did, which files you modified, and any issues encountered. Report your status as: complete, partial, or failed."

- [ ] **Step 4: Verify compilation and tests**

```bash
./gradlew :agent:compileKotlin --no-build-cache && ./gradlew :agent:test --rerun --no-build-cache
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/OrchestratorPrompts.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreatePlanTool.kt
git commit -m "feat(agent): add delegation rules to prompts, update plan approval and worker prompts"
```

---

## Task 10: Final Integration — Verification and Cleanup

**Files:**
- All modified files

- [ ] **Step 1: Run full test suite**

```bash
./gradlew :agent:test --rerun --no-build-cache
```
Expected: ALL PASS

- [ ] **Step 2: Run plugin verification**

```bash
./gradlew verifyPlugin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run full compilation check**

```bash
./gradlew :agent:compileKotlin --no-build-cache
```
Expected: PASS

- [ ] **Step 4: Verify no references to removed code**

Search for stale references:
```bash
grep -r "EscalateToOrchestrated\|PlanReady\|executePlan\|requestPlan\|AgentMode\." agent/src/ --include="*.kt"
```
Expected: No matches (except possibly in test files that need cleanup)

- [ ] **Step 5: Final commit if any cleanup needed**

```bash
git add -A
git commit -m "chore(agent): cleanup stale references after delegation architecture unification"
```

---

## Implementation Order

```
Task 1: BudgetEnforcer (new statuses)           ← foundation, no dependencies
Task 3: LLM Compression (compressWithLlm)        ← independent
Task 2: SingleAgentSession (nudge injection)     ← depends on Tasks 1 AND 3 (uses compressWithLlm)
Task 4: Telemetry + Settings                     ← independent
Task 5: Remove System Orchestration              ← depends on Tasks 1-2
Task 6: WorkerSession (cancellation)             ← independent
Task 7: DelegateTaskTool (core)                  ← depends on Tasks 4, 6
Task 8: Wire into system                         ← depends on Task 7
Task 9: Prompt updates                           ← depends on Task 5
Task 10: Final verification                      ← depends on all
```

Parallelizable groups:
- **Group A (parallel):** Tasks 1, 3, 4, 6
- **Group B (after Tasks 1+3):** Task 2 (uses both BudgetEnforcer statuses and compressWithLlm)
- **Group C (after Task 2):** Task 5
- **Group D (after Tasks 4+6):** Tasks 7, 8
- **Group E (after C+D):** Task 9
- **Group F (final):** Task 10
