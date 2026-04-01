# Plan Mode Mechanical Enforcement — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mechanically enforce plan mode by removing write tools from the LLM's available tool set (Claude Code style), auto-transitioning to execute mode on plan approval, and visually reflecting mode state in the UI.

**Architecture:** Plan mode filtering happens in `SingleAgentSession.execute()` — before each LLM call, if `planModeActive` is true, write tools are removed from `activeToolDefs`/`activeTools`. The `AgentService.planModeActive` volatile boolean is the single source of truth, set by `EnablePlanModeTool`, the UI toggle, and cleared by `PlanManager.approvePlan()`. An execution-time guard in `executeSingleToolRaw()` acts as a belt-and-suspenders safety net.

**Tech Stack:** Kotlin (agent runtime), TypeScript/React (webview UI)

**Research basis:** Claude Code (tools removed from schema), Cline (execution-time guard + `strictPlanMode`), Roo Code (declarative blocked tool set)

---

### Task 1: Define PLAN_MODE_BLOCKED_TOOLS constant in SingleAgentSession

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt:143-149`

- [ ] **Step 1: Write the test for plan mode tool filtering**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/PlanModeToolFilterTest.kt
package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PlanModeToolFilterTest {

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS contains all source mutation tools`() {
        val blocked = SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS
        // Source code mutation tools
        assertTrue("edit_file" in blocked)
        assertTrue("create_file" in blocked)
        assertTrue("format_code" in blocked)
        assertTrue("optimize_imports" in blocked)
        assertTrue("refactor_rename" in blocked)
        assertTrue("rollback_changes" in blocked)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does NOT contain read or analysis tools`() {
        val blocked = SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS
        assertFalse("read_file" in blocked)
        assertFalse("search_code" in blocked)
        assertFalse("diagnostics" in blocked)
        assertFalse("run_command" in blocked)
        assertFalse("think" in blocked)
        assertFalse("create_plan" in blocked)
        assertFalse("enable_plan_mode" in blocked)
        assertFalse("attempt_completion" in blocked)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does NOT contain runtime or debug tools`() {
        val blocked = SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS
        assertFalse("runtime" in blocked)
        assertFalse("debug" in blocked)
    }

    @Test
    fun `filterToolsForPlanMode removes blocked tools`() {
        val allTools = mapOf(
            "read_file" to mockTool("read_file"),
            "edit_file" to mockTool("edit_file"),
            "create_file" to mockTool("create_file"),
            "search_code" to mockTool("search_code"),
            "run_command" to mockTool("run_command"),
            "create_plan" to mockTool("create_plan"),
            "think" to mockTool("think"),
            "format_code" to mockTool("format_code")
        )
        val filtered = SingleAgentSession.filterToolsForPlanMode(allTools)
        assertEquals(5, filtered.size)
        assertTrue("read_file" in filtered)
        assertTrue("search_code" in filtered)
        assertTrue("run_command" in filtered)
        assertTrue("create_plan" in filtered)
        assertTrue("think" in filtered)
        assertFalse("edit_file" in filtered)
        assertFalse("create_file" in filtered)
        assertFalse("format_code" in filtered)
    }

    private fun mockTool(name: String): com.workflow.orchestrator.agent.tools.AgentTool {
        return object : com.workflow.orchestrator.agent.tools.AgentTool {
            override val name = name
            override val description = "mock"
            override val parameters = com.workflow.orchestrator.agent.api.dto.FunctionParameters()
            override suspend fun execute(
                params: kotlinx.serialization.json.JsonObject,
                project: com.intellij.openapi.project.Project
            ) = com.workflow.orchestrator.agent.tools.ToolResult("ok", "ok", 10)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.PlanModeToolFilterTest" --rerun`
Expected: FAIL — `PLAN_MODE_BLOCKED_TOOLS` and `filterToolsForPlanMode` don't exist yet.

- [ ] **Step 3: Add PLAN_MODE_BLOCKED_TOOLS and filterToolsForPlanMode**

In `SingleAgentSession.kt`, add after the `READ_ONLY_TOOLS` set (around line 149):

```kotlin
/** Tools blocked during plan mode — source code mutation tools only.
 *  The LLM will not see these in the tool schema during plan mode (Claude Code style).
 *  Read, run, debug, runtime, analysis, memory, and planning tools remain available. */
val PLAN_MODE_BLOCKED_TOOLS = setOf(
    "edit_file", "create_file",
    "format_code", "optimize_imports",
    "refactor_rename", "rollback_changes"
)

/** Filter tools for plan mode: remove blocked tools from the available set. */
fun filterToolsForPlanMode(tools: Map<String, AgentTool>): Map<String, AgentTool> {
    return tools.filterKeys { it !in PLAN_MODE_BLOCKED_TOOLS }
}

/** Filter tool definitions for plan mode: remove blocked tools from the schema. */
fun filterToolDefsForPlanMode(toolDefs: List<ToolDefinition>): List<ToolDefinition> {
    return toolDefs.filter { it.function.name !in PLAN_MODE_BLOCKED_TOOLS }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*.PlanModeToolFilterTest" --rerun`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/PlanModeToolFilterTest.kt
git commit -m "feat(agent): add PLAN_MODE_BLOCKED_TOOLS constant and filter functions"
```

---

### Task 2: Add planModeActive to AgentService as single source of truth

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:129`

- [ ] **Step 1: Write the test**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/PlanModeStateTest.kt
package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PlanModeStateTest {

    @Test
    fun `planModeActive defaults to false`() {
        // AgentService.planModeActive is a static-like volatile boolean
        // Default is false — agent starts in execute mode
        assertFalse(AgentService.planModeActive.get())
    }

    @Test
    fun `planModeActive can be toggled`() {
        AgentService.planModeActive.set(true)
        assertTrue(AgentService.planModeActive.get())
        AgentService.planModeActive.set(false)
        assertFalse(AgentService.planModeActive.get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.PlanModeStateTest" --rerun`
Expected: FAIL — `planModeActive` doesn't exist.

- [ ] **Step 3: Add planModeActive to AgentService**

In `AgentService.kt`, add a companion-level atomic boolean (so SingleAgentSession can read it without a project reference):

```kotlin
companion object {
    /** Whether plan mode is currently active — single source of truth.
     *  Read by SingleAgentSession to filter tools before LLM calls.
     *  Set by EnablePlanModeTool, AgentController (UI toggle), and PlanManager (on approval). */
    val planModeActive = java.util.concurrent.atomic.AtomicBoolean(false)

    // ... existing getInstance() etc.
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*.PlanModeStateTest" --rerun`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/PlanModeStateTest.kt
git commit -m "feat(agent): add AgentService.planModeActive atomic boolean"
```

---

### Task 3: Filter tools in SingleAgentSession main loop based on plan mode

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt:474`

- [ ] **Step 1: Add plan mode tool filtering before each LLM call**

In `SingleAgentSession.execute()`, at line 474 where `toolDefsForCall` is computed, replace:

```kotlin
val toolDefsForCall = if (forceTextOnly) null else if (activeTools.isNotEmpty()) activeToolDefs else null
```

with:

```kotlin
// Plan mode: remove source mutation tools from schema so LLM can't see them
val planMode = AgentService.planModeActive.get()
val effectiveToolDefs = if (planMode) filterToolDefsForPlanMode(activeToolDefs) else activeToolDefs
val effectiveTools = if (planMode) filterToolsForPlanMode(activeTools) else activeTools
val toolDefsForCall = if (forceTextOnly) null else if (effectiveTools.isNotEmpty()) effectiveToolDefs else null
```

Also update the LLM call at line 477-480 to pass `effectiveToolDefs` and `effectiveTools` to `callLlmWithRetry` (for context-exceeded tool reduction):

```kotlin
val result = callLlmWithRetry(
    brain, messages, toolDefsForCall, maxOutputTokens,
    onStreamChunk, effectiveToolDefs, effectiveTools, eventLog, onDebugLog, iteration
)
```

- [ ] **Step 2: Add import for AgentService**

Add at top of `SingleAgentSession.kt`:

```kotlin
import com.workflow.orchestrator.agent.AgentService
```

- [ ] **Step 3: Run existing tests to verify no regressions**

Run: `./gradlew :agent:test --rerun`
Expected: PASS (planModeActive defaults to false, so no behavioral change)

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "feat(agent): filter tools from LLM schema when plan mode active"
```

---

### Task 4: Add execution-time guard in executeSingleToolRaw (belt-and-suspenders)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt:1331-1341`

- [ ] **Step 1: Write the test**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/PlanModeExecutionGuardTest.kt
package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.AgentService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach

class PlanModeExecutionGuardTest {

    @AfterEach
    fun cleanup() {
        AgentService.planModeActive.set(false)
    }

    @Test
    fun `isPlanModeBlocked returns true for edit_file when plan mode active`() {
        AgentService.planModeActive.set(true)
        assertTrue(SingleAgentSession.isPlanModeBlocked("edit_file"))
    }

    @Test
    fun `isPlanModeBlocked returns false for edit_file when plan mode inactive`() {
        AgentService.planModeActive.set(false)
        assertFalse(SingleAgentSession.isPlanModeBlocked("edit_file"))
    }

    @Test
    fun `isPlanModeBlocked returns false for read_file regardless of plan mode`() {
        AgentService.planModeActive.set(true)
        assertFalse(SingleAgentSession.isPlanModeBlocked("read_file"))
    }

    @Test
    fun `isPlanModeBlocked returns false for run_command in plan mode`() {
        AgentService.planModeActive.set(true)
        assertFalse(SingleAgentSession.isPlanModeBlocked("run_command"))
    }

    @Test
    fun `isPlanModeBlocked returns false for runtime and debug in plan mode`() {
        AgentService.planModeActive.set(true)
        assertFalse(SingleAgentSession.isPlanModeBlocked("runtime"))
        assertFalse(SingleAgentSession.isPlanModeBlocked("debug"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.PlanModeExecutionGuardTest" --rerun`
Expected: FAIL — `isPlanModeBlocked` doesn't exist.

- [ ] **Step 3: Add isPlanModeBlocked and the guard in executeSingleToolRaw**

Add to the `companion object` in `SingleAgentSession`:

```kotlin
/** Check if a tool is blocked by plan mode (execution-time safety net). */
fun isPlanModeBlocked(toolName: String): Boolean {
    return AgentService.planModeActive.get() && toolName in PLAN_MODE_BLOCKED_TOOLS
}
```

In `executeSingleToolRaw()`, after the tool-not-found check (line 1341) and before the approval gate check (line 1344), add:

```kotlin
// Plan mode execution guard — belt-and-suspenders safety net.
// Tools should already be filtered from the schema, but if one slips through
// (e.g. cached tool call from before mode switch), block it here.
if (isPlanModeBlocked(toolName)) {
    val msg = "Tool '$toolName' is blocked in plan mode. Create and get your plan approved first, then plan mode will deactivate and you can use write tools."
    eventLog?.log(AgentEventType.TOOL_FAILED, "$toolName: blocked by plan mode")
    return Triple(toolCall, ToolResult(
        content = msg,
        summary = "Blocked: $toolName (plan mode)",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    ), 0L)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*.PlanModeExecutionGuardTest" --rerun`
Expected: PASS

- [ ] **Step 5: Run all agent tests for regression check**

Run: `./gradlew :agent:test --rerun`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/PlanModeExecutionGuardTest.kt
git commit -m "feat(agent): add execution-time guard for plan mode blocked tools"
```

---

### Task 5: Wire EnablePlanModeTool to set planModeActive

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EnablePlanModeTool.kt:61-64`

- [ ] **Step 1: Update EnablePlanModeTool to set the atomic boolean**

After line 61 (the `addSystemMessage` call), add:

```kotlin
// Set the mechanical enforcement flag — SingleAgentSession will filter tools on next iteration
AgentService.planModeActive.set(true)
```

The existing `agentService.onPlanModeEnabled?.invoke(true)` at line 64 stays — it handles the UI callback.

- [ ] **Step 2: Run existing tests**

Run: `./gradlew :agent:test --rerun`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EnablePlanModeTool.kt
git commit -m "feat(agent): EnablePlanModeTool sets planModeActive for mechanical enforcement"
```

---

### Task 6: Wire AgentController UI toggle to set planModeActive

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt:89-101`

- [ ] **Step 1: Update the onTogglePlanMode callback**

Replace the existing `onTogglePlanMode` block (lines 89-101):

```kotlin
onTogglePlanMode = { enabled ->
    planModeEnabled = enabled
    AgentService.planModeActive.set(enabled)
    if (enabled) {
        // Inject planning constraints into any active session context
        try {
            AgentService.getInstance(project).currentContextBridge
                ?.addSystemMessage(PromptAssembler.FORCED_PLANNING_RULES)
        } catch (_: Exception) {}
        dashboard.setPlanMode(true)
    } else {
        dashboard.setPlanMode(false)
    }
},
```

- [ ] **Step 2: Also update the second onTogglePlanMode callback (popup mode)**

There is a duplicate callback around line 349-357. Apply the same change — add `AgentService.planModeActive.set(enabled)` after `planModeEnabled = enabled`.

- [ ] **Step 3: Update the onPlanModeEnabled callback wiring**

At line 1010, update the `onPlanModeEnabled` callback to also set the atomic boolean:

```kotlin
agentSvc?.onPlanModeEnabled = { enabled ->
    planModeEnabled = enabled
    AgentService.planModeActive.set(enabled)
    SwingUtilities.invokeLater { dashboard.setPlanMode(enabled) }
}
```

- [ ] **Step 4: Run existing tests**

Run: `./gradlew :agent:test --rerun`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): UI plan toggle sets planModeActive for mechanical enforcement"
```

---

### Task 7: Auto-exit plan mode on plan approval

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/PlanManager.kt:123-135`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt:228-230`

- [ ] **Step 1: Write the test**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/PlanModeTransitionTest.kt
package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.AgentService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach

class PlanModeTransitionTest {

    @AfterEach
    fun cleanup() {
        AgentService.planModeActive.set(false)
    }

    @Test
    fun `approvePlan clears planModeActive`() {
        AgentService.planModeActive.set(true)
        val pm = PlanManager()
        val plan = AgentPlan(goal = "test", steps = listOf(PlanStep(id = "1", title = "step 1")))
        pm.submitPlan(plan)
        pm.approvePlan()
        assertFalse(AgentService.planModeActive.get(), "planModeActive should be false after approval")
    }

    @Test
    fun `auto-approval via timeout clears planModeActive`() {
        AgentService.planModeActive.set(true)
        val pm = PlanManager()
        val plan = AgentPlan(goal = "test", steps = listOf(PlanStep(id = "1", title = "step 1")))
        // Use a very short timeout to trigger auto-approval
        kotlinx.coroutines.runBlocking {
            pm.submitPlanAndWait(plan, timeoutMs = 50)
        }
        assertFalse(AgentService.planModeActive.get(), "planModeActive should be false after auto-approval")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.PlanModeTransitionTest" --rerun`
Expected: FAIL — `approvePlan` doesn't clear `planModeActive`.

- [ ] **Step 3: Add planModeActive.set(false) to PlanManager.approvePlan()**

In `PlanManager.kt`, in the `approvePlan()` method (line 123), add after `currentPlan?.approved = true`:

```kotlin
// Auto-exit plan mode: tools are restored on next LLM call
AgentService.planModeActive.set(false)
```

Also in `submitPlanAndWait()`, in the timeout catch block (line 112), add after `currentPlan?.approved = true`:

```kotlin
AgentService.planModeActive.set(false)
```

Add the import at the top of PlanManager.kt:

```kotlin
import com.workflow.orchestrator.agent.AgentService
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*.PlanModeTransitionTest" --rerun`
Expected: PASS

- [ ] **Step 5: Wire plan approval to UI state in AgentController**

In `AgentController.kt`, in the plan callbacks (line 228-230), update the `onApprove` to also exit plan mode visually:

```kotlin
onApprove = {
    session?.planManager?.approvePlan()
    // Auto-exit plan mode: unclick the Plan button
    planModeEnabled = false
    SwingUtilities.invokeLater { dashboard.setPlanMode(false) }
},
```

- [ ] **Step 6: Inject transition system message**

In `PlanManager.approvePlan()`, after setting `planModeActive.set(false)`, the `CreatePlanTool` already returns "Plan approved. Execute step by step..." — so the LLM already gets the transition message. No additional injection needed.

- [ ] **Step 7: Run all agent tests**

Run: `./gradlew :agent:test --rerun`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/PlanManager.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/PlanModeTransitionTest.kt
git commit -m "feat(agent): auto-exit plan mode on plan approval, restore tools + unclick button"
```

---

### Task 8: Update FORCED_PLANNING_RULES to reflect mechanical enforcement

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:290-302`

- [ ] **Step 1: Update the prompt text**

Replace `FORCED_PLANNING_RULES` (lines 290-302):

```kotlin
val FORCED_PLANNING_RULES = """
    <planning mode="required">
    - Plan mode is ACTIVE. Source code mutation tools (edit_file, create_file, format_code,
      optimize_imports, refactor_rename) are NOT available until you create a plan and the user approves it.
    - First, analyze the task by reading relevant files using read_file, search_code, file_structure,
      diagnostics, run_command (for tests/builds), runtime, and debug tools.
    - Then produce a comprehensive implementation plan using create_plan with:
      1. `markdown` — full plan as a rich markdown document (## Goal, ## Steps, ### Step N, code blocks)
      2. `steps` — JSON array for status tracking
      3. `title` — short display title
    - Once the user approves the plan, plan mode will automatically deactivate and all tools
      will become available. Then execute step by step, calling update_plan_step to track progress.
    - If the user requests revision, incorporate their feedback and call create_plan again.
    </planning>
""".trimIndent()
```

- [ ] **Step 2: Run existing tests**

Run: `./gradlew :agent:test --rerun`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "feat(agent): update FORCED_PLANNING_RULES to reflect mechanical tool enforcement"
```

---

### Task 9: Reset planModeActive on new session / clear

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` (newChat method)

- [ ] **Step 1: Find and update the newChat/session cleanup code**

In `AgentController`, find the `newChat()` method or session cleanup code. Add:

```kotlin
AgentService.planModeActive.set(false)
planModeEnabled = false
dashboard.setPlanMode(false)
```

This ensures plan mode doesn't leak across sessions.

- [ ] **Step 2: Also reset in cancelTask() if applicable**

If `cancelTask()` cleans up session state, add the same reset there.

- [ ] **Step 3: Run all agent tests**

Run: `./gradlew :agent:test --rerun`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "fix(agent): reset planModeActive on new session and cancel"
```

---

### Task 10: Meta-tool write action filtering (jira/bamboo/bitbucket/git)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`

Meta-tools like `jira`, `bamboo`, `bitbucket`, `git` have mixed read/write actions. The meta-tool itself stays available in plan mode, but write actions should be blocked at execution time.

- [ ] **Step 1: Define blocked meta-tool actions**

Add to the `companion object` in `SingleAgentSession`:

```kotlin
/** Meta-tool actions blocked during plan mode. The meta-tool itself stays
 *  available (so read actions work), but write actions are blocked. */
val PLAN_MODE_BLOCKED_ACTIONS = setOf(
    // jira write actions
    "transition", "comment", "log_work", "start_work",
    // bamboo write actions
    "trigger_build", "stop_build", "cancel_build", "rerun_failed", "trigger_stage",
    // bitbucket write actions
    "create_pr", "approve_pr", "merge_pr", "decline_pr", "add_comment",
    "add_reviewer", "remove_reviewer", "update_pr",
    // git write actions
    "shelve"
)
```

- [ ] **Step 2: Add action-level check in executeSingleToolRaw**

In `executeSingleToolRaw()`, after the plan mode guard for full tools, add a check for meta-tool actions:

```kotlin
// Plan mode: block write actions within meta-tools (jira, bamboo, bitbucket, git)
if (AgentService.planModeActive.get() && toolName in setOf("jira", "bamboo", "bitbucket", "git")) {
    try {
        val params = json.parseToJsonElement(toolCall.function.arguments)
        val action = (params as? kotlinx.serialization.json.JsonObject)?.get("action")
            ?.jsonPrimitive?.content
        if (action != null && action in PLAN_MODE_BLOCKED_ACTIONS) {
            val msg = "Action '$action' on '$toolName' is blocked in plan mode. Get your plan approved first."
            eventLog?.log(AgentEventType.TOOL_FAILED, "$toolName.$action: blocked by plan mode")
            return Triple(toolCall, ToolResult(
                content = msg,
                summary = "Blocked: $toolName.$action (plan mode)",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            ), 0L)
        }
    } catch (_: Exception) { /* parsing failed, allow through */ }
}
```

- [ ] **Step 3: Write test for meta-tool action blocking**

```kotlin
// Add to PlanModeExecutionGuardTest.kt
@Test
fun `PLAN_MODE_BLOCKED_ACTIONS contains jira write actions`() {
    val blocked = SingleAgentSession.PLAN_MODE_BLOCKED_ACTIONS
    assertTrue("transition" in blocked)
    assertTrue("comment" in blocked)
    assertTrue("log_work" in blocked)
}

@Test
fun `PLAN_MODE_BLOCKED_ACTIONS does not contain jira read actions`() {
    val blocked = SingleAgentSession.PLAN_MODE_BLOCKED_ACTIONS
    assertFalse("get_ticket" in blocked)
    assertFalse("search_issues" in blocked)
    assertFalse("get_sprints" in blocked)
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --rerun`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/PlanModeExecutionGuardTest.kt
git commit -m "feat(agent): block meta-tool write actions during plan mode"
```

---

### Task 11: Build, verify, and integration test

**Files:**
- No new files

- [ ] **Step 1: Run full test suite**

Run: `./gradlew :agent:test --rerun`
Expected: All tests pass

- [ ] **Step 2: Build the plugin**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify plugin compatibility**

Run: `./gradlew verifyPlugin`
Expected: No API compatibility issues

- [ ] **Step 4: Manual integration test checklist**

Launch sandbox IDE with `./gradlew runIde` and verify:
1. Click Plan button → button highlights with accent color
2. Send a task → LLM does NOT see edit_file/create_file in available tools
3. LLM can still use read_file, search_code, run_command, runtime, debug
4. LLM creates plan with create_plan → plan card appears
5. Click Approve → Plan button automatically unclicks
6. LLM can now see and use edit_file, create_file again
7. LLM calls enable_plan_mode → Plan button highlights automatically
8. New Chat → Plan button resets to inactive

- [ ] **Step 5: Commit any fixes from integration testing**

```bash
git add -A
git commit -m "fix(agent): integration test fixes for plan mode enforcement"
```

---

### Task 12: Update documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Update root CLAUDE.md**

Add to the "Threading" or appropriate section:

```markdown
## Plan Mode

Mechanical enforcement (Claude Code style): when plan mode is active, source code mutation tools
(edit_file, create_file, format_code, optimize_imports, refactor_rename, rollback_changes) are
removed from the LLM's tool schema. Read, run, debug, runtime, analysis, and planning tools
remain available. Plan mode auto-deactivates when the user approves the plan.

Activation: UI Plan button toggle, or LLM calls `enable_plan_mode` tool.
Deactivation: User approves plan (auto), or user unclicks Plan button (manual).
State: `AgentService.planModeActive` (AtomicBoolean) — single source of truth.
```

- [ ] **Step 2: Update agent/CLAUDE.md**

Add a "## Plan Mode Enforcement" section after "## Tool Execution":

```markdown
## Plan Mode Enforcement

Two-layer enforcement (Claude Code style + Cline safety net):

1. **Schema filtering** — `SingleAgentSession.execute()` removes `PLAN_MODE_BLOCKED_TOOLS` from
   `activeToolDefs`/`activeTools` before each LLM call. The LLM never sees blocked tools.
2. **Execution guard** — `executeSingleToolRaw()` checks `isPlanModeBlocked()` as a safety net
   for cached tool calls from before mode switch.
3. **Meta-tool action filtering** — Write actions within `jira`/`bamboo`/`bitbucket`/`git`
   meta-tools are blocked at execution time via `PLAN_MODE_BLOCKED_ACTIONS`.

**Blocked tools:** edit_file, create_file, format_code, optimize_imports, refactor_rename, rollback_changes
**Always available:** read_file, search_code, run_command, runtime, debug, think, create_plan, agent, etc.

**Transition:** `PlanManager.approvePlan()` → `AgentService.planModeActive.set(false)` → tools restored on next LLM call → `dashboard.setPlanMode(false)` unclicks UI button.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md agent/CLAUDE.md
git commit -m "docs: document plan mode mechanical enforcement"
```
