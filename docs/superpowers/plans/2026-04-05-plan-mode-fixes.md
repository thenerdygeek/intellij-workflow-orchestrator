# Plan Mode Fixes — Editor Port, Persistence, Dead Button Wiring

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the plan editor from main branch, add plan persistence with compaction-surviving path anchor, and fix 3 dead button callbacks.

**Architecture:** Port the JCEF-based AgentPlanEditor from main (adapting `AgentPlan` → `PlanParser.PlanJson`), save plan to `{sessionDir}/plan.md` on generation, store path in ContextManager for compaction survival, wire 3 dead callbacks in AgentController.

**Tech Stack:** Kotlin, JCEF (JBCefBrowser, JBCefJSQuery), IntelliJ FileEditor API, kotlinx.serialization

---

### Task 1: Update AgentPlanVirtualFile to hold structured data

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanVirtualFile.kt`

- [ ] **Step 1: Update to hold PlanParser.PlanJson instead of raw String**

```kotlin
package com.workflow.orchestrator.agent.ui.plan

import com.intellij.testFramework.LightVirtualFile
import com.workflow.orchestrator.agent.loop.PlanParser

/**
 * Virtual file backing the plan editor tab. Holds structured plan data.
 */
class AgentPlanVirtualFile(
    val plan: PlanParser.PlanJson,
    val sessionId: String
) : LightVirtualFile("Implementation Plan", AgentPlanFileType, "") {
    var currentPlan: PlanParser.PlanJson = plan
    override fun isWritable() = false
}
```

- [ ] **Step 2: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanVirtualFile.kt
git commit -m "refactor(plan): update AgentPlanVirtualFile to hold PlanParser.PlanJson"
```

---

### Task 2: Port AgentPlanEditor from main branch

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanEditor.kt`

Port the full JCEF implementation from main at `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanEditor.kt`.

Adaptations needed:
- Replace `AgentPlan` with `PlanParser.PlanJson` (use `PlanParser`'s kotlinx.serialization)
- Replace `AgentService.getInstance(project).currentPlanManager?.approvePlan()` with callback: `var onApprove: (() -> Unit)? = null`
- Replace `AgentService.getInstance(project).currentPlanManager?.revisePlan(comments)` / `revisePlanWithContext(revisions, markdown)` with callback: `var onRevise: ((String) -> Unit)? = null`
- Keep `fileClickQuery` with project-scoped path validation (same as main)
- Keep `commentCountQuery` with `onCommentCountChanged` callback (same as main)
- Keep theme sync with `AgentColors` (same as main)
- Use `PlanParser`'s `Json` instance for serialization

- [ ] **Step 1: Write the ported AgentPlanEditor.kt**

Replace the stub with the full JCEF implementation. Key changes from main:

```kotlin
package com.workflow.orchestrator.agent.ui.plan

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import com.workflow.orchestrator.agent.ui.AgentColors
import com.workflow.orchestrator.agent.ui.CefResourceSchemeHandler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Plan editor — JCEF-based full-screen plan viewer with inline commenting.
 *
 * Ported from main branch. Adaptations:
 * - Uses PlanParser.PlanJson instead of AgentPlan
 * - Uses callbacks instead of AgentService.currentPlanManager direct access
 */
class AgentPlanEditor(
    private val project: Project,
    private val planFile: AgentPlanVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val json = Json { encodeDefaults = true }
    private val browser = JBCefBrowser()
    private val approveQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val reviseQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val fileClickQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val commentCountQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    /** Callback when user clicks Approve in the plan editor. */
    var onApprove: (() -> Unit)? = null

    /** Callback when user clicks Revise in the plan editor. Receives JSON payload. */
    var onRevise: ((String) -> Unit)? = null

    /** Callback to update comment count on the chat panel's plan summary card. */
    var onCommentCountChanged: ((Int) -> Unit)? = null

    init {
        // ... (port 4 query handlers from main, adapting approve/revise to use callbacks)
        // ... (register CefResourceSchemeHandler, load plan-editor.html)
        // ... (add CefLoadHandlerAdapter to inject plan data on load)
    }

    private fun injectPlanData(cefBrowser: CefBrowser?) {
        // Serialize PlanParser.PlanJson (not AgentPlan)
        // Inject theme vars + JS bridges + renderPlan() call
        // (port from main, replacing json.encodeToString(planFile.currentPlan))
    }

    fun updatePlanStep(stepId: String, status: String) {
        // Port from main — executeJavaScript("updatePlanStep(...)")
    }

    fun triggerRevise() {
        // Port from main — executeJavaScript("triggerReviseFromHost()")
    }

    // ... FileEditor interface methods (port component/dispose from main)
}
```

The actual implementation should be a faithful port of main's `AgentPlanEditor.kt` with the 2 adaptations listed above. Read the full source from main at:
`/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanEditor.kt`

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanEditor.kt
git commit -m "feat(plan): port JCEF plan editor from main branch"
```

---

### Task 3: Add plan persistence to ContextManager

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt`

Add plan path storage alongside the existing `activeSkillContent` pattern. The plan path survives compaction via re-injection as a system-level anchor.

- [ ] **Step 1: Add plan path field and methods**

Add after the `activeSkillContent` field (around line 74):

```kotlin
/**
 * Path to the saved plan file on disk.
 * Set when plan_mode_respond generates a plan — saved immediately, not on approval.
 * Survives compaction: re-injected as a pointer so the LLM can re-read the plan.
 */
private var activePlanPath: String? = null

fun setActivePlanPath(path: String) {
    activePlanPath = path
}

fun getActivePlanPath(): String? = activePlanPath

fun clearActivePlanPath() {
    activePlanPath = null
}
```

- [ ] **Step 2: Add plan path re-injection after compaction**

Add a `reInjectActivePlan()` method after `reInjectActiveSkill()` (around line 777):

```kotlin
/**
 * Re-inject the active plan path into the conversation after compaction.
 * Lightweight pointer — the LLM uses read_file to access the full plan.
 */
internal fun reInjectActivePlan() {
    val path = activePlanPath ?: return
    val recentMessages = messages.takeLast(10)
    val alreadyPresent = recentMessages.any { msg ->
        msg.content?.contains("[Active Plan]") == true
    }
    if (!alreadyPresent) {
        messages.add(ChatMessage(
            role = "assistant",
            content = "[Active Plan] You are working from an implementation plan saved at: $path\n" +
                "Use read_file to review the plan steps if needed."
        ))
    }
}
```

- [ ] **Step 3: Call reInjectActivePlan after compaction**

In the `compact()` method (around line 507), add after `reInjectActiveSkill()`:

```kotlin
reInjectActivePlan()
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt
git commit -m "feat(plan): add plan path persistence with compaction survival"
```

---

### Task 4: Save plan to disk on generation

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

When `onPlanResponse` fires, save the plan markdown to `{sessionDir}/plan.md` and store the path in ContextManager.

- [ ] **Step 1: Pass sessionDir to AgentController**

In `AgentService.kt`, the `sessionDebugDir` is already computed (line 519-522). Pass it to AgentController's `executeTask` call so the controller knows where to save. Check if there's already a `sessionDir` or `sessionId` accessible in AgentController. If not, add it as a field set during task execution.

Grep for how `sessionDebugDir` flows to `AgentController`:
```bash
grep -n "sessionDebugDir\|sessionDir\|sessionId" agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
```

The session directory path is `ProjectIdentifier.agentDir(basePath) + "/sessions/$sid"`. Store this on the controller when a task starts.

- [ ] **Step 2: Save plan on generation in onPlanResponse**

In `AgentController.onPlanResponse()` (line 1012), after parsing the plan JSON, save to disk:

```kotlin
private fun onPlanResponse(planText: String, needsMoreExploration: Boolean) {
    invokeLater {
        val planJson = PlanParser.parseToJson(planText)
        dashboard.renderPlan(planJson)

        // Save plan to disk and store path for compaction survival
        currentSessionDir?.let { dir ->
            try {
                val planFile = java.io.File(dir, "plan.md")
                planFile.parentFile?.mkdirs()
                planFile.writeText(planText, Charsets.UTF_8)
                // Store path in ContextManager so it survives compaction
                currentContextManager?.setActivePlanPath(planFile.absolutePath)
            } catch (e: Exception) {
                LOG.warn("Failed to save plan to disk: ${e.message}")
            }
        }

        if (!needsMoreExploration) {
            loopWaitingForInput = true
            dashboard.setBusy(false)
            dashboard.focusInput()
        }
    }
}
```

- [ ] **Step 3: Verify the ContextManager reference is accessible**

AgentController needs access to the current ContextManager. Check if it's already stored (grep for `contextManager` in AgentController). If not, pass it through when starting the loop.

- [ ] **Step 4: Clear plan path on new chat**

In the `newChat()` method, clear the plan path:

```kotlin
currentContextManager?.clearActivePlanPath()
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(plan): save plan to disk on generation, path survives compaction"
```

---

### Task 5: Wire plan editor callbacks in AgentController

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

Wire the two dead plan editor callbacks: `focusPlanEditor` and `revisePlanFromEditor`.

- [ ] **Step 1: Wire focusPlanEditor callback**

In the `wireCallbacks()` method (or wherever dashboard callbacks are set up), add:

```kotlin
dashboard.setCefFocusPlanEditorCallback {
    openPlanInEditor()
}
```

- [ ] **Step 2: Implement openPlanInEditor()**

Add a method to open the plan editor tab:

```kotlin
private var currentPlanJson: PlanParser.PlanJson? = null

private fun openPlanInEditor() {
    val plan = currentPlanJson ?: return
    val sid = currentSessionId ?: "unknown"
    val vf = AgentPlanVirtualFile(plan, sid)

    invokeLater {
        val editors = FileEditorManager.getInstance(project).openFile(vf, true)
        val planEditor = editors.filterIsInstance<AgentPlanEditor>().firstOrNull()
        planEditor?.onApprove = ::approvePlan
        planEditor?.onRevise = ::revisePlan
        planEditor?.onCommentCountChanged = { count ->
            dashboard.updatePlanCommentCount(count)
        }
    }
}
```

- [ ] **Step 3: Store currentPlanJson when plan is generated**

In `onPlanResponse`, after parsing:

```kotlin
currentPlanJson = PlanParser.parse(planText)?.let { steps ->
    PlanParser.PlanJson(summary = PlanParser.extractSummary(planText, steps), steps = steps)
}
```

Wait — `PlanParser.parseToJson()` returns a JSON string, but we need the `PlanJson` object. Either:
- Add a `parseToPlanJson()` method that returns the object, or
- Deserialize the JSON string back

The cleaner approach: add a method to PlanParser that returns the object directly.

Add to `PlanParser.kt`:

```kotlin
fun parseToPlanJson(planText: String): PlanJson {
    val steps = parse(planText)
    val summary = extractSummary(planText, steps)
    return PlanJson(summary = summary, steps = steps)
}
```

Then in `onPlanResponse`:
```kotlin
val planData = PlanParser.parseToPlanJson(planText)
currentPlanJson = planData
val planJson = Json.encodeToString(planData)
dashboard.renderPlan(planJson)
```

- [ ] **Step 4: Wire revisePlanFromEditor callback**

```kotlin
dashboard.setCefRevisePlanFromEditorCallback {
    // Trigger revision from the plan editor tab
    // The editor collects comments and calls onRevise callback
    val editors = FileEditorManager.getInstance(project).allEditors
    val planEditor = editors.filterIsInstance<AgentPlanEditor>().firstOrNull()
    planEditor?.triggerRevise()
}
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/PlanParser.kt
git commit -m "feat(plan): wire plan editor open, approve, revise callbacks"
```

---

### Task 6: Wire 3 dead button callbacks

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

Three callbacks exist in AgentCefPanel/AgentDashboardPanel but are never assigned in AgentController:
1. `toggleTool` — `setCefToolToggleCallback`
2. `deactivateSkill` — `onSkillDismissed` via `setCefSkillCallbacks`
3. `killSubAgent` — `setCefKillSubAgentCallback`

- [ ] **Step 1: Wire toggleTool**

Find where other callbacks are wired in AgentController (the `wireCallbacks()` method or init block). Add:

```kotlin
dashboard.setCefToolToggleCallback { toolName, enabled ->
    LOG.info("Tool toggle: $toolName = $enabled")
    // Toggle tool in the registry
    val registry = currentToolRegistry ?: return@setCefToolToggleCallback
    if (enabled) {
        registry.enable(toolName)
    } else {
        registry.disable(toolName)
    }
}
```

Check if `ToolRegistry` has `enable`/`disable` methods. If not, check what the intended behavior is. The callback at minimum should be wired (even as a log + TODO if registry doesn't support toggling yet).

- [ ] **Step 2: Wire deactivateSkill**

Find `setCefSkillCallbacks` in AgentDashboardPanel. Wire it:

```kotlin
dashboard.setCefSkillCallbacks(onDismiss = {
    LOG.info("Skill dismissed by user")
    currentContextManager?.clearActiveSkill()
})
```

Check the exact method signature on the dashboard. The skill dismiss should clear the active skill from ContextManager.

- [ ] **Step 3: Wire killSubAgent**

```kotlin
dashboard.setCefKillSubAgentCallback { agentId ->
    LOG.info("Kill subagent: $agentId")
    // Cancel the subagent's coroutine job
    // Check if SpawnAgentTool tracks running agents
}
```

Check `SpawnAgentTool` or `SubagentRunner` for a way to cancel running subagents by ID.

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "fix(ui): wire toggleTool, deactivateSkill, killSubAgent callbacks"
```

---

### Task 7: Add plan persistence and editor tests

**Files:**
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/SkillCompactionTest.kt` (add plan tests)
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/PlanCompactionTest.kt`

- [ ] **Step 1: Write plan path persistence tests**

```kotlin
package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PlanCompactionTest {

    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    @Test
    fun `setActivePlanPath stores path`() {
        contextManager.setActivePlanPath("/tmp/session/plan.md")
        assertEquals("/tmp/session/plan.md", contextManager.getActivePlanPath())
    }

    @Test
    fun `getActivePlanPath returns null when no plan`() {
        assertNull(contextManager.getActivePlanPath())
    }

    @Test
    fun `clearActivePlanPath removes stored path`() {
        contextManager.setActivePlanPath("/tmp/plan.md")
        contextManager.clearActivePlanPath()
        assertNull(contextManager.getActivePlanPath())
    }

    @Test
    fun `plan path survives compaction via re-injection`() {
        contextManager.setSystemPrompt("System prompt")
        contextManager.setActivePlanPath("/tmp/session/plan.md")

        for (i in 1..20) {
            contextManager.addUserMessage("User message $i")
            contextManager.addAssistantMessage(
                ChatMessage(role = "assistant", content = "Response $i")
            )
        }

        contextManager.truncateConversation(TruncationStrategy.HALF)
        contextManager.reInjectActivePlan()

        val messages = contextManager.getMessages()
        val hasPlanMessage = messages.any { msg ->
            msg.content?.contains("[Active Plan]") == true &&
            msg.content?.contains("/tmp/session/plan.md") == true
        }
        assertTrue(hasPlanMessage, "Plan path should be re-injected after compaction")
    }

    @Test
    fun `re-injection does not duplicate when plan already present`() {
        contextManager.setSystemPrompt("System prompt")
        contextManager.setActivePlanPath("/tmp/plan.md")
        contextManager.addUserMessage("Hello")

        contextManager.reInjectActivePlan()
        val countAfterFirst = contextManager.getMessages().count {
            it.content?.contains("[Active Plan]") == true
        }

        contextManager.reInjectActivePlan()
        val countAfterSecond = contextManager.getMessages().count {
            it.content?.contains("[Active Plan]") == true
        }

        assertEquals(countAfterFirst, countAfterSecond)
    }

    @Test
    fun `no re-injection when no active plan`() {
        contextManager.setSystemPrompt("System prompt")
        contextManager.addUserMessage("Hello")

        val sizeBefore = contextManager.getMessages().size
        contextManager.reInjectActivePlan()
        assertEquals(sizeBefore, contextManager.getMessages().size)
    }
}
```

- [ ] **Step 2: Run tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --tests "*PlanCompactionTest" -v`
Expected: All 5 tests PASS

- [ ] **Step 3: Commit**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/PlanCompactionTest.kt
git commit -m "test(plan): add plan path compaction survival tests"
```

---

### Task 8: Build and verify everything

**Files:** None (verification only)

- [ ] **Step 1: Run full agent test suite**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --rerun`
Expected: All tests pass (1168+ existing + 5 new plan tests)

- [ ] **Step 2: Verify plugin builds**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify no old API references remain**

```bash
cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite
grep -rn "planContent\|currentPlanContent" agent/src/main/kotlin/
```
Expected: No matches (old `AgentPlanVirtualFile` fields gone)
