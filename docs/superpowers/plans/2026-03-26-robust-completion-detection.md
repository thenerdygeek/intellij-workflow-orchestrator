# Robust Completion Detection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the "no tool calls = done" completion pattern with an explicit `attempt_completion` tool gated by plan-awareness, post-compression detection, and escalation caps.

**Architecture:** New `AttemptCompletionTool` requires the LLM to actively declare completion. `CompletionGatekeeper` runs 4 gates (post-compression, plan, self-correction, loop-guard) before accepting. Responses without any tool calls trigger a nudge then implicit gating. Force-accept after 5 blocked attempts prevents infinite loops.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, kotlinx.serialization, JUnit 5 + MockK

**Spec:** `docs/superpowers/specs/2026-03-26-robust-completion-detection-design.md`

---

### Task 1: Add `isCompletion` Flag to ToolResult

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt:27-37`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/` (any test creating ToolResult)

- [ ] **Step 1: Read current ToolResult**

Read `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt` lines 27-37. Current:

```kotlin
data class ToolResult(
    val content: String,
    val summary: String,
    val tokenEstimate: Int,
    val artifacts: List<String> = emptyList(),
    val isError: Boolean = false
)
```

- [ ] **Step 2: Add `isCompletion` flag and `verifyCommand`**

```kotlin
data class ToolResult(
    val content: String,
    val summary: String,
    val tokenEstimate: Int,
    val artifacts: List<String> = emptyList(),
    val isError: Boolean = false,
    val isCompletion: Boolean = false,
    val verifyCommand: String? = null
)
```

Both default to false/null so existing callers are unaffected.

- [ ] **Step 3: Build to verify no breakage**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL (all existing ToolResult usage is positional or named with defaults)

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt
git commit -m "feat(agent): add isCompletion flag to ToolResult for completion signaling"
```

---

### Task 2: Create CompletionGatekeeper

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/CompletionGatekeeper.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/CompletionGatekeeperTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/CompletionGatekeeperTest.kt
package com.workflow.orchestrator.agent.runtime

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CompletionGatekeeperTest {

    private lateinit var gatekeeper: CompletionGatekeeper
    private val selfCorrectionGate = mockk<SelfCorrectionGate>()
    private val loopGuard = mockk<LoopGuard>()

    @BeforeEach
    fun setup() {
        every { selfCorrectionGate.checkCompletionReadiness() } returns null
        every { loopGuard.beforeCompletion() } returns null
    }

    private fun createGatekeeper(
        planManager: PlanManager? = null,
        iterationsSinceCompression: Int = Int.MAX_VALUE,
        postCompressionAttempted: Boolean = false
    ): CompletionGatekeeper {
        return CompletionGatekeeper(
            planManager = planManager,
            selfCorrectionGate = selfCorrectionGate,
            loopGuard = loopGuard,
            iterationsSinceCompression = { iterationsSinceCompression },
            postCompressionCompletionAttempted = { postCompressionAttempted },
            onPostCompressionAttempted = {}
        )
    }

    @Test
    fun `all gates pass when no plan and no compression`() {
        val gk = createGatekeeper()
        assertNull(gk.checkCompletion())
    }

    @Test
    fun `post-compression gate blocks on recent compression`() {
        val gk = createGatekeeper(iterationsSinceCompression = 1)
        val result = gk.checkCompletion()
        assertNotNull(result)
        assertTrue(result!!.contains("compressed recently"))
    }

    @Test
    fun `post-compression gate passes after already attempted`() {
        val gk = createGatekeeper(iterationsSinceCompression = 1, postCompressionAttempted = true)
        assertNull(gk.checkCompletion())
    }

    @Test
    fun `post-compression gate passes when compression is old`() {
        val gk = createGatekeeper(iterationsSinceCompression = 5)
        assertNull(gk.checkCompletion())
    }

    @Test
    fun `plan gate blocks when incomplete steps exist`() {
        val planManager = mockk<PlanManager>()
        val plan = mockk<AgentPlan>()
        val step1 = mockk<PlanStep>()
        val step2 = mockk<PlanStep>()
        every { planManager.currentPlan } returns plan
        every { plan.steps } returns listOf(step1, step2)
        every { step1.status } returns PlanStepStatus.COMPLETED
        every { step2.status } returns PlanStepStatus.PENDING
        every { step2.title } returns "Write tests"

        val gk = createGatekeeper(planManager = planManager)
        val result = gk.checkCompletion()
        assertNotNull(result)
        assertTrue(result!!.contains("1 incomplete steps"))
        assertTrue(result.contains("Write tests"))
    }

    @Test
    fun `plan gate passes when all steps completed or skipped`() {
        val planManager = mockk<PlanManager>()
        val plan = mockk<AgentPlan>()
        val step1 = mockk<PlanStep>()
        val step2 = mockk<PlanStep>()
        every { planManager.currentPlan } returns plan
        every { plan.steps } returns listOf(step1, step2)
        every { step1.status } returns PlanStepStatus.COMPLETED
        every { step2.status } returns PlanStepStatus.SKIPPED

        val gk = createGatekeeper(planManager = planManager)
        assertNull(gk.checkCompletion())
    }

    @Test
    fun `plan gate passes when no plan`() {
        val gk = createGatekeeper(planManager = null)
        assertNull(gk.checkCompletion())
    }

    @Test
    fun `force accepts after MAX_TOTAL_COMPLETION_ATTEMPTS`() {
        val planManager = mockk<PlanManager>()
        val plan = mockk<AgentPlan>()
        val step = mockk<PlanStep>()
        every { planManager.currentPlan } returns plan
        every { plan.steps } returns listOf(step)
        every { step.status } returns PlanStepStatus.PENDING
        every { step.title } returns "Pending step"

        val gk = createGatekeeper(planManager = planManager)
        // First 5 attempts should block
        for (i in 1..5) {
            assertNotNull(gk.checkCompletion(), "Attempt $i should block")
        }
        // 6th attempt should force-accept
        assertNull(gk.checkCompletion())
    }

    @Test
    fun `plan gate escalates message after 3 blocks without progress`() {
        val planManager = mockk<PlanManager>()
        val plan = mockk<AgentPlan>()
        val step = mockk<PlanStep>()
        every { planManager.currentPlan } returns plan
        every { plan.steps } returns listOf(step)
        every { step.status } returns PlanStepStatus.PENDING
        every { step.title } returns "Pending step"

        val gk = createGatekeeper(planManager = planManager)
        gk.checkCompletion() // block 1
        gk.checkCompletion() // block 2
        val result = gk.checkCompletion() // block 3 — escalated
        assertNotNull(result)
        assertTrue(result!!.contains("update_plan_step"))
    }

    @Test
    fun `selfCorrectionGate block propagates`() {
        val msg = com.workflow.orchestrator.agent.api.dto.ChatMessage(role = "user", content = "Unverified edits")
        every { selfCorrectionGate.checkCompletionReadiness() } returns msg

        val gk = createGatekeeper()
        val result = gk.checkCompletion()
        assertNotNull(result)
        assertTrue(result!!.contains("Unverified edits"))
    }

    @Test
    fun `loopGuard block propagates`() {
        val msg = com.workflow.orchestrator.agent.api.dto.ChatMessage(role = "user", content = "Run diagnostics")
        every { loopGuard.beforeCompletion() } returns msg

        val gk = createGatekeeper()
        val result = gk.checkCompletion()
        assertNotNull(result)
        assertTrue(result!!.contains("Run diagnostics"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*.CompletionGatekeeperTest" --rerun`
Expected: FAIL — class not found

- [ ] **Step 3: Write the implementation**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/CompletionGatekeeper.kt
package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger

/**
 * Orchestrates completion gates before accepting task completion.
 * Gates run in order — first block wins. Force-accepts after MAX_TOTAL_COMPLETION_ATTEMPTS
 * to prevent infinite blocking loops.
 *
 * Gate order: PostCompression → Plan → SelfCorrection → LoopGuard
 */
class CompletionGatekeeper(
    private val planManager: PlanManager?,
    private val selfCorrectionGate: SelfCorrectionGate,
    private val loopGuard: LoopGuard,
    private val iterationsSinceCompression: () -> Int,
    private val postCompressionCompletionAttempted: () -> Boolean,
    private val onPostCompressionAttempted: () -> Unit
) {
    companion object {
        private val LOG = Logger.getInstance(CompletionGatekeeper::class.java)
        private const val MAX_PLAN_BLOCKS_WITHOUT_PROGRESS = 3
        const val MAX_TOTAL_COMPLETION_ATTEMPTS = 5
    }

    private var planGateBlockCount = 0
    private var lastPlanIncompleteCount = Int.MAX_VALUE
    private var totalCompletionAttempts = 0

    /**
     * Check all completion gates. Returns a block message (String) if completion
     * should be denied, or null if all gates pass.
     */
    fun checkCompletion(): String? {
        totalCompletionAttempts++

        if (totalCompletionAttempts > MAX_TOTAL_COMPLETION_ATTEMPTS) {
            LOG.warn("CompletionGatekeeper: force-accepting after $totalCompletionAttempts attempts")
            return null
        }

        checkPostCompression()?.let { return it }
        checkPlanCompletion()?.let { return it }
        checkSelfCorrection()?.let { return it }
        checkLoopGuard()?.let { return it }
        return null
    }

    private fun checkPostCompression(): String? {
        if (iterationsSinceCompression() > 2) return null
        if (postCompressionCompletionAttempted()) return null

        onPostCompressionAttempted()
        return "COMPLETION BLOCKED: Context was compressed recently. You may have lost " +
            "track of the task. Review the [CONTEXT COMPRESSED] summary above and the " +
            "active plan (if any). If there is remaining work, continue. " +
            "If truly done, call attempt_completion again."
    }

    private fun checkPlanCompletion(): String? {
        val plan = planManager?.currentPlan ?: return null
        val incomplete = plan.steps.filter {
            it.status != PlanStepStatus.COMPLETED && it.status != PlanStepStatus.SKIPPED
        }
        if (incomplete.isEmpty()) return null

        if (incomplete.size == lastPlanIncompleteCount) {
            planGateBlockCount++
        } else {
            planGateBlockCount = 0
        }
        lastPlanIncompleteCount = incomplete.size

        if (planGateBlockCount >= MAX_PLAN_BLOCKS_WITHOUT_PROGRESS) {
            return "COMPLETION BLOCKED (${planGateBlockCount}x): ${incomplete.size} plan steps " +
                "still incomplete with no progress. To proceed, call update_plan_step for each:\n" +
                incomplete.joinToString("\n") {
                    "- update_plan_step(step=\"${it.title}\", status=\"skipped\", comment=\"Not needed\")"
                } +
                "\n\nOr continue working on them."
        }

        return "COMPLETION BLOCKED: Your plan has ${incomplete.size} incomplete steps:\n" +
            incomplete.mapIndexed { i, step ->
                "${i + 1}. [${step.status}] ${step.title}"
            }.joinToString("\n") +
            "\n\nContinue working on the next incomplete step. " +
            "If a step is no longer needed, call update_plan_step to mark it as skipped."
    }

    private fun checkSelfCorrection(): String? {
        return selfCorrectionGate.checkCompletionReadiness()?.content
    }

    private fun checkLoopGuard(): String? {
        return loopGuard.beforeCompletion()?.content
    }
}
```

**Note:** The method returns `String?` not `ChatMessage?`. This is simpler — the caller wraps it in a ChatMessage or ToolResult as needed.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*.CompletionGatekeeperTest" --rerun`
Expected: PASS

You will need to adjust test imports based on actual class locations for `PlanManager`, `AgentPlan`, `PlanStep`, `PlanStepStatus`. Find them:
```bash
grep -rn "class PlanManager" --include="*.kt" agent/src/main/
grep -rn "class AgentPlan" --include="*.kt" agent/src/main/
grep -rn "enum class PlanStepStatus" --include="*.kt" agent/src/main/
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/CompletionGatekeeper.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/CompletionGatekeeperTest.kt
git commit -m "feat(agent): add CompletionGatekeeper with plan-aware and post-compression gates"
```

---

### Task 3: Create AttemptCompletionTool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/AttemptCompletionTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/AttemptCompletionToolTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/AttemptCompletionToolTest.kt
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.CompletionGatekeeper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AttemptCompletionToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val gatekeeper = mockk<CompletionGatekeeper>()

    @Test
    fun `returns completion result when all gates pass`() = runTest {
        every { gatekeeper.checkCompletion() } returns null

        val tool = AttemptCompletionTool(gatekeeper)
        val params = JsonObject(mapOf(
            "result" to JsonPrimitive("Fixed the bug in UserService.kt")
        ))
        val result = tool.execute(params, project)

        assertTrue(result.isCompletion)
        assertFalse(result.isError)
        assertTrue(result.content.contains("Fixed the bug"))
    }

    @Test
    fun `returns error when gate blocks`() = runTest {
        every { gatekeeper.checkCompletion() } returns "COMPLETION BLOCKED: 3 plan steps incomplete"

        val tool = AttemptCompletionTool(gatekeeper)
        val params = JsonObject(mapOf(
            "result" to JsonPrimitive("Done")
        ))
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertFalse(result.isCompletion)
        assertTrue(result.content.contains("COMPLETION BLOCKED"))
    }

    @Test
    fun `returns error when result param missing`() = runTest {
        val tool = AttemptCompletionTool(gatekeeper)
        val params = JsonObject(emptyMap())
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter"))
    }

    @Test
    fun `includes verify command in result`() = runTest {
        every { gatekeeper.checkCompletion() } returns null

        val tool = AttemptCompletionTool(gatekeeper)
        val params = JsonObject(mapOf(
            "result" to JsonPrimitive("Added tests"),
            "command" to JsonPrimitive("./gradlew test")
        ))
        val result = tool.execute(params, project)

        assertTrue(result.isCompletion)
        assertEquals("./gradlew test", result.verifyCommand)
    }

    @Test
    fun `tool name is attempt_completion`() {
        val tool = AttemptCompletionTool(mockk())
        assertEquals("attempt_completion", tool.name)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*.AttemptCompletionToolTest" --rerun`
Expected: FAIL — class not found

- [ ] **Step 3: Write the implementation**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/AttemptCompletionTool.kt
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.CompletionGatekeeper
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Explicit completion tool — the LLM must call this to declare task completion.
 * Gates in CompletionGatekeeper may block the attempt and return a continuation prompt.
 *
 * Not available to WorkerSession (subagents use "no tool calls = done").
 */
class AttemptCompletionTool(
    private val gatekeeper: CompletionGatekeeper
) : AgentTool {

    override val name = "attempt_completion"

    override val description = "Declare that you have finished the user's request. Call this " +
        "ONLY when the entire task is fully resolved — not when completing individual plan " +
        "steps (use update_plan_step for that). Your completion may be blocked if there is " +
        "unfinished work."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "result" to ParameterProperty(
                type = "string",
                description = "Summary of what was accomplished"
            ),
            "command" to ParameterProperty(
                type = "string",
                description = "Optional command for the user to verify the result (e.g., 'npm test')"
            )
        ),
        required = listOf("result")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val result = params["result"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: result",
                summary = "attempt_completion failed: missing result",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val command = params["command"]?.jsonPrimitive?.content

        val block = gatekeeper.checkCompletion()
        if (block != null) {
            return ToolResult(
                content = block,
                summary = "Completion blocked by gate",
                tokenEstimate = block.length / 4,
                isError = true
            )
        }

        return ToolResult(
            content = result,
            summary = "Task completed: ${result.take(200)}",
            tokenEstimate = result.length / 4,
            isCompletion = true,
            verifyCommand = command
        )
    }
}
```

**Key:** `allowedWorkers = setOf(WorkerType.ORCHESTRATOR)` excludes it from subagent workers (ANALYZER, CODER, REVIEWER, TOOLER).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*.AttemptCompletionToolTest" --rerun`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/AttemptCompletionTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/AttemptCompletionToolTest.kt
git commit -m "feat(agent): add AttemptCompletionTool with gatekeeper integration"
```

---

### Task 4: Register Tool and Update Protected Lists

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` — register tool
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt` — add to PROTECTED_TOOLS

- [ ] **Step 1: Read current files**

Read `AgentService.kt` to find where tools are registered (around lines 129-341).
Read `ContextManager.kt` to find the `PROTECTED_TOOLS` set (around line 86-90).

- [ ] **Step 2: Register AttemptCompletionTool in AgentService**

In the `toolRegistry` lazy block, after the planning tools (create_plan, update_plan_step), add:

```kotlin
// After the planning tools registration:
// register(AttemptCompletionTool(...)) — registered later in executeTask after gatekeeper is created
```

**Problem:** AttemptCompletionTool needs a `CompletionGatekeeper` reference, which is created per-session in `AgentOrchestrator`. We can't register it in the static `toolRegistry`.

**Solution:** Register it dynamically in `AgentOrchestrator.executeTask()` after creating the gatekeeper. Add it to the `allTools` map before passing to `SingleAgentSession`.

So in `AgentService.kt`, no change to the static registry. Instead, in `AgentOrchestrator.kt` (handled in Task 6).

- [ ] **Step 3: Add to PROTECTED_TOOLS in ContextManager**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt`, line 86-90:

```kotlin
// Before:
val PROTECTED_TOOLS = setOf(
    "agent", "delegate_task", "create_plan", "update_plan_step",
    "save_memory", "activate_skill", "ask_questions"
)

// After:
val PROTECTED_TOOLS = setOf(
    "agent", "delegate_task", "create_plan", "update_plan_step",
    "save_memory", "activate_skill", "ask_questions", "attempt_completion"
)
```

- [ ] **Step 4: Add `attempt_completion` to always-on tools list**

Search for where `agent`, `delegate_task`, and `request_tools` are added after `removeAll(disabledTools)`:

```bash
grep -n "removeAll\|disabledTools\|cannot be disabled" --include="*.kt" agent/src/main/kotlin/com/workflow/orchestrator/agent/
```

Add `attempt_completion` to the same "cannot be disabled" list so it survives tool preference filtering.

- [ ] **Step 5: Build to verify**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt
# Plus any other files touched for always-on tool registration
git commit -m "feat(agent): add attempt_completion to protected tools and always-on list"
```

---

### Task 5: Add System Prompt Instruction

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt`

- [ ] **Step 1: Read PromptAssembler.kt**

Read the file fully to understand how prompt sections are assembled. Find where other rule constants (PLANNING_RULES, DELEGATION_RULES, etc.) are defined.

- [ ] **Step 2: Add COMPLETION_RULES constant**

Add a new constant alongside the other rule constants:

```kotlin
private const val COMPLETION_RULES = """
## Completion

When you have fully completed ALL parts of the user's request, call the attempt_completion tool with a summary of what you accomplished.
Do not end your response without either calling a tool or calling attempt_completion.
If you stop without calling any tool, you will be asked to continue.
Do NOT call attempt_completion when completing individual plan steps — use update_plan_step for that.
"""
```

- [ ] **Step 3: Add to prompt assembly**

In `buildSingleAgentPrompt()`, add `COMPLETION_RULES` to the assembled sections. Place it after `PLANNING_RULES` (since it references plan steps):

```kotlin
// After: PLANNING_RULES
sections.add(COMPLETION_RULES)
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "feat(agent): add completion rules to system prompt for attempt_completion"
```

---

### Task 6: Integrate into SingleAgentSession and AgentOrchestrator

This is the core integration task. It modifies the completion path in `SingleAgentSession` and wires up the `CompletionGatekeeper` + `AttemptCompletionTool` from `AgentOrchestrator`.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt`

- [ ] **Step 1: Read SingleAgentSession.kt completion path (lines 685-790)**

Understand the current flow completely.

- [ ] **Step 2: Add state variables to SingleAgentSession**

At the top of the `execute()` function (or as class properties), add:

```kotlin
var consecutiveNoToolResponses = 0
var iterationsSinceCompression = Int.MAX_VALUE
var postCompressionCompletionAttempted = false
```

- [ ] **Step 3: Hook compression tracking**

Find all compression events in `execute()` (search for `compress`, `compressWithLlm`). After each, add:

```kotlin
iterationsSinceCompression = 0
postCompressionCompletionAttempted = false
```

At the top of each iteration (inside the `for` loop), add:

```kotlin
iterationsSinceCompression++
```

- [ ] **Step 4: Add confused-response heuristic**

Add a helper function:

```kotlin
private fun isConfusedResponse(content: String): Boolean {
    val trimmed = content.trim()
    return trimmed.length < 100 || trimmed.count { it == '?' } >= 2
}
```

- [ ] **Step 5: Replace the completion path (lines 685-790)**

The current code at line 685 is:
```kotlin
if (toolCalls.isNullOrEmpty()) {
    // ... existing completion logic
}
```

Replace the entire block with the new flow. The logic:

```kotlin
if (toolCalls.isNullOrEmpty()) {
    // Existing: malformed tool call retry (finishReason == "tool_calls")
    // Keep lines 689-720 as-is

    // Existing: unfulfilled intent detection
    // Keep lines 724-738 as-is

    // NEW: Confused response detection
    val content = message.content ?: ""
    if (isConfusedResponse(content)) {
        contextManager.addMessage(ChatMessage(
            role = "user",
            content = "What specifically are you unsure about? Describe the problem and I'll help."
        ))
        consecutiveNoToolResponses = 0
        return null // continue loop
    }

    // NEW: No tool calls nudge / implicit completion
    consecutiveNoToolResponses++

    if (consecutiveNoToolResponses == 1) {
        // First time: nudge to use attempt_completion
        contextManager.addMessage(ChatMessage(
            role = "user",
            content = "You responded without calling any tools. If you've completed the task, " +
                "call attempt_completion with a summary. If you have more work to do, " +
                "make your next tool call now."
        ))
        return null // continue loop
    }

    // Second consecutive: run gatekeeper as implicit completion
    val gateBlock = completionGatekeeper?.checkCompletion()
    if (gateBlock != null) {
        contextManager.addMessage(ChatMessage(role = "user", content = gateBlock))
        return null // continue loop
    }

    // All gates passed — accept implicit completion
    // ... existing completion finalization (OutputValidator, scorecard, etc.)
    // Keep lines 754-789 (the return SingleAgentResult.Completed block)
}
```

- [ ] **Step 6: Handle attempt_completion in tool execution**

In the tool execution section (after line 790), add a check for `isCompletion` in tool results:

```kotlin
// After executing each tool call and getting the ToolResult:
if (tr.isCompletion) {
    // attempt_completion succeeded — exit loop
    val sanitizedContent = if (OutputValidator.validate(tr.content).isNotEmpty()) {
        CredentialRedactor.redact(tr.content)
    } else tr.content

    LOG.info("SingleAgentSession: attempt_completion accepted after $iteration iterations")
    eventLog?.log(AgentEventType.SESSION_COMPLETED, "Completed via attempt_completion after $iteration iterations")
    return SingleAgentResult.Completed(
        content = sanitizedContent,
        summary = tr.summary,
        tokensUsed = totalTokensUsed,
        artifacts = allArtifacts,
        scorecard = buildScorecard(sessionId, "completed", selfCorrectionGate, System.currentTimeMillis() - sessionStartMs, project)
    )
}
```

- [ ] **Step 7: Handle attempt_completion mixed with other tools**

Before executing tool calls, check if `attempt_completion` is in the batch alongside others:

```kotlin
val hasAttemptCompletion = toolCalls.any { it.function.name == "attempt_completion" }
val hasOtherTools = toolCalls.any { it.function.name != "attempt_completion" }

if (hasAttemptCompletion && hasOtherTools) {
    // Discard attempt_completion, execute only other tools
    val filteredCalls = toolCalls.filter { it.function.name != "attempt_completion" }
    // Add a message about the discarded call
    contextManager.addMessage(ChatMessage(
        role = "user",
        content = "You called attempt_completion alongside other tools. Complete your tool calls first, " +
            "then call attempt_completion separately when you are truly done."
    ))
    // Process filteredCalls instead of toolCalls
    // ... continue with existing tool execution using filteredCalls
}
```

- [ ] **Step 8: Reset counter on tool calls**

After successful tool execution (when tools were called), reset:

```kotlin
consecutiveNoToolResponses = 0
```

- [ ] **Step 9: Add budget TERMINATE override**

In the TERMINATE handler (around lines 306-369), before the existing rotation/failure logic, add:

```kotlin
BudgetEnforcer.BudgetStatus.TERMINATE -> {
    // If the LLM was trying to complete, accept it despite gates
    if (consecutiveNoToolResponses > 0) {
        val lastContent = contextManager.getMessages().lastOrNull { it.role == "assistant" }?.content ?: ""
        return SingleAgentResult.Completed(
            content = lastContent,
            summary = "Completed (budget exhausted, gates bypassed)",
            tokensUsed = totalTokensUsed,
            artifacts = allArtifacts,
            scorecard = buildScorecard(sessionId, "completed_forced", selfCorrectionGate, System.currentTimeMillis() - sessionStartMs, project)
        )
    }
    // ... existing TERMINATE logic (rotation or fail)
}
```

- [ ] **Step 10: Wire CompletionGatekeeper from AgentOrchestrator**

In `AgentOrchestrator.executeTask()` (around lines 262-290), create the gatekeeper and tool:

```kotlin
// After planManager and other session components are available:
val completionGatekeeper = CompletionGatekeeper(
    planManager = planManager,
    selfCorrectionGate = selfCorrectionGate,
    loopGuard = loopGuard,
    iterationsSinceCompression = { singleAgentSession.iterationsSinceCompression },
    postCompressionCompletionAttempted = { singleAgentSession.postCompressionCompletionAttempted },
    onPostCompressionAttempted = { singleAgentSession.postCompressionCompletionAttempted = true }
)

// Create and register the tool
val attemptCompletionTool = AttemptCompletionTool(completionGatekeeper)
val allToolsWithCompletion = allTools.toMutableMap()
allToolsWithCompletion[attemptCompletionTool.name] = attemptCompletionTool
val allToolDefsWithCompletion = allToolDefs + attemptCompletionTool.toToolDefinition()

// Pass to SingleAgentSession
singleAgentSession.completionGatekeeper = completionGatekeeper
val result = singleAgentSession.execute(
    tools = allToolsWithCompletion,
    toolDefinitions = allToolDefsWithCompletion,
    ...
)
```

This requires `SingleAgentSession` to have a `completionGatekeeper` property (var, nullable, set before execute).

- [ ] **Step 11: Build and test**

Run: `./gradlew :agent:compileKotlin`
Run: `./gradlew :agent:test --rerun`
Expected: BUILD SUCCESSFUL, existing tests pass

- [ ] **Step 12: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt
git commit -m "feat(agent): integrate CompletionGatekeeper into agent loop with nudge and budget override"
```

---

### Task 7: Add Completion Metrics to AgentMetrics

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentMetrics.kt`

- [ ] **Step 1: Read AgentMetrics.kt**

Read the full file to understand the existing structure.

- [ ] **Step 2: Add completion counters**

Add to the `AgentMetrics` class:

```kotlin
var completionAttemptCount = 0          // Total attempt_completion calls
var completionGateBlocks = mutableMapOf<String, Int>()  // gate name → block count
var forcedCompletionCount = 0           // Budget override or max attempts
var nudgeCount = 0                      // "No tool calls" nudges injected
```

Add to the `SessionSnapshot` data class:

```kotlin
val completionAttemptCount: Int = 0,
val completionGateBlocks: Map<String, Int> = emptyMap(),
val forcedCompletionCount: Int = 0,
val nudgeCount: Int = 0
```

Update `snapshot()` to include the new fields.

- [ ] **Step 3: Wire metrics from SingleAgentSession**

In SingleAgentSession, after each gate block / nudge / forced completion, increment the appropriate counter:

```kotlin
metrics.nudgeCount++  // when nudge injected
metrics.completionAttemptCount++  // when attempt_completion called
metrics.completionGateBlocks["plan"] = (metrics.completionGateBlocks["plan"] ?: 0) + 1  // when specific gate blocks
metrics.forcedCompletionCount++  // when force-accepted
```

- [ ] **Step 4: Build and test**

Run: `./gradlew :agent:compileKotlin :agent:test --rerun`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentMetrics.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "feat(agent): add completion detection metrics to AgentMetrics"
```

---

### Task 8: Update Documentation

**Files:**
- Modify: `agent/CLAUDE.md`
- Modify: `docs/agent-observability-guide.md`

- [ ] **Step 1: Update agent/CLAUDE.md**

Add `attempt_completion` to the tools table in the "Planning" category row:

```
| Planning | create_plan, update_plan_step, ask_questions, save_memory, activate_skill, deactivate_skill, attempt_completion |
```

Add a section under "Key Components":

```markdown
- **CompletionGatekeeper** — Orchestrates 4 completion gates before accepting task completion: PostCompression (blocks if context was recently compressed), Plan (blocks if plan has incomplete steps, escalates after 3 blocks without progress), SelfCorrectionGate (existing, blocks if unverified edits), LoopGuard (existing, blocks if unverified files). Force-accepts after 5 total blocked attempts. `attempt_completion` tool delegates to this.
- **AttemptCompletionTool** (`attempt_completion`) — Explicit completion signal. LLM must call this to end the session. Responses without tool calls trigger a nudge then implicit gating. Not available to WorkerSession.
```

- [ ] **Step 2: Update observability guide**

In `docs/agent-observability-guide.md`, add a new debugging workflow section:

```markdown
### "The agent keeps trying to complete but gets blocked"

**Step 1: Check completion attempts in the scorecard**

The `completionAttemptCount`, `completionGateBlocks`, and `forcedCompletionCount` fields show how many times the agent tried to stop and which gates blocked it.

**Step 2: Check the trace for gate blocks**

The daily log and trace will show `attempt_completion` tool calls with error results containing "COMPLETION BLOCKED".

**Step 3: Check if force-accept was triggered**

If `forcedCompletionCount > 0` in the scorecard, the agent was forced to accept after 5 blocked attempts. Check what was actually incomplete.
```

- [ ] **Step 3: Commit**

```bash
git add agent/CLAUDE.md docs/agent-observability-guide.md
git commit -m "docs: update CLAUDE.md and observability guide for attempt_completion and CompletionGatekeeper"
```

---

### Task 9: Full Build and Test Verification

**Files:** None (verification only)

- [ ] **Step 1: Run all agent tests**

Run: `./gradlew :agent:clean :agent:test --rerun --no-build-cache`
Expected: All tests pass (except pre-existing E2E failures)

- [ ] **Step 2: Run core and cody tests**

Run: `./gradlew :core:test --rerun`
Expected: PASS

- [ ] **Step 3: Verify plugin builds**

Run: `./gradlew verifyPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Verify attempt_completion is in protected tools**

Run: `grep -n "attempt_completion" --include="*.kt" agent/src/main/`
Expected: Found in ContextManager PROTECTED_TOOLS, AttemptCompletionTool, PromptAssembler, and nowhere else unexpected.

- [ ] **Step 5: Verify attempt_completion excluded from workers**

Check that `allowedWorkers = setOf(WorkerType.ORCHESTRATOR)` is in AttemptCompletionTool:
Run: `grep -A2 "allowedWorkers" agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/AttemptCompletionTool.kt`
Expected: `setOf(WorkerType.ORCHESTRATOR)`

- [ ] **Step 6: Commit if any fixups needed**

```bash
git add -A
git commit -m "fix: address remaining issues from completion detection verification"
```
