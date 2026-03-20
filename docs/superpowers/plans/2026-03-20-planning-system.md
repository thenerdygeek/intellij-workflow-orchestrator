# Antigravity-Style Planning System — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Antigravity-style planning system where the LLM creates an interactive implementation plan for complex tasks before executing, with per-step user comments and approve/revise flow.

**Architecture:** Two new tools (`create_plan`, `update_plan_step`) that the LLM calls to produce and update a plan. The plan renders as an interactive HTML card in JCEF with comment inputs, Approve/Revise buttons, and live status updates. A Plan/Execute mode toggle in the toolbar forces planning. System prompt updated to tell the LLM when to plan.

**Tech Stack:** Kotlin (tools, session handling), HTML/CSS/JS (JCEF plan card), JBCefJSQuery (approve/revise/comment bridges)

---

## File Structure

### New Files (4)

| File | Responsibility |
|------|---------------|
| `agent/tools/builtin/CreatePlanTool.kt` | Tool the LLM calls to produce a structured plan. Returns a special "PLAN_AWAITING_APPROVAL" result that pauses the ReAct loop. |
| `agent/tools/builtin/UpdatePlanStepTool.kt` | Tool the LLM calls during execution to mark a plan step as running/done/failed. |
| `agent/runtime/PlanManager.kt` | Manages plan state: stores the current plan, handles approval/revision, provides the plan to the session. |
| `agent/tools/builtin/CreatePlanToolTest.kt` | Tests for plan creation and serialization. |

### Modified Files (6)

| File | Change |
|------|--------|
| `agent/resources/webview/agent-chat.html` | Add `renderPlan()`, `updatePlanStep()`, comment input handling, Approve/Revise buttons, live status CSS |
| `agent/ui/AgentCefPanel.kt` | Add `renderPlan()` and `updatePlanStep()` JS bridge methods + JBCefJSQuery for approve/revise/comment callbacks |
| `agent/ui/AgentDashboardPanel.kt` | Add Plan/Execute mode toggle button + delegation for plan rendering |
| `agent/ui/AgentController.kt` | Wire mode toggle, handle plan approval/revision flow, pass PlanManager to orchestrator |
| `agent/orchestrator/PromptAssembler.kt` | Add planning instructions to RULES, add plan-mode system prompt variant |
| `agent/AgentService.kt` | Register CreatePlanTool and UpdatePlanStepTool |

---

## Task 1: PlanManager + CreatePlanTool + UpdatePlanStepTool

The backend: the plan data model, the tools the LLM calls, and the manager that coordinates approval.

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/PlanManager.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreatePlanTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/UpdatePlanStepTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreatePlanToolTest.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

### PlanManager Design

```kotlin
package com.workflow.orchestrator.agent.runtime

import java.util.concurrent.CompletableFuture
import kotlinx.serialization.Serializable

@Serializable
data class PlanStep(
    val id: String,
    val title: String,
    val description: String = "",
    val files: List<String> = emptyList(),
    val action: String = "code", // "read", "edit", "create", "delete", "verify", "code"
    var status: String = "pending", // "pending", "running", "done", "failed"
    var userComment: String? = null
)

@Serializable
data class AgentPlan(
    val goal: String,
    val approach: String = "",
    val steps: List<PlanStep>,
    val testing: String = "",
    var approved: Boolean = false
)

/**
 * Manages the active plan for the current session.
 * When CreatePlanTool is called, the plan is stored here and
 * the ReAct loop pauses waiting for user approval.
 */
class PlanManager {
    var currentPlan: AgentPlan? = null
        private set

    /** Future that blocks the ReAct loop until the user approves or revises. */
    private var approvalFuture: CompletableFuture<PlanApprovalResult>? = null

    /** Callback to render the plan in the UI. */
    var onPlanCreated: ((AgentPlan) -> Unit)? = null
    /** Callback to update a step's status in the UI. */
    var onStepUpdated: ((String, String) -> Unit)? = null

    /**
     * Called by CreatePlanTool. Stores the plan, notifies UI, and
     * returns a future that resolves when the user approves or revises.
     */
    fun submitPlan(plan: AgentPlan): CompletableFuture<PlanApprovalResult> {
        currentPlan = plan
        approvalFuture = CompletableFuture()
        onPlanCreated?.invoke(plan)
        return approvalFuture!!
    }

    /** Called by UI when user clicks Approve. */
    fun approvePlan() {
        currentPlan?.approved = true
        approvalFuture?.complete(PlanApprovalResult.Approved)
    }

    /** Called by UI when user clicks Revise with comments. */
    fun revisePlan(comments: Map<String, String>) {
        // Apply comments to steps
        currentPlan?.steps?.forEach { step ->
            comments[step.id]?.let { step.userComment = it }
        }
        approvalFuture?.complete(PlanApprovalResult.Revised(comments))
    }

    /** Called by UpdatePlanStepTool during execution. */
    fun updateStepStatus(stepId: String, status: String) {
        currentPlan?.steps?.find { it.id == stepId }?.status = status
        onStepUpdated?.invoke(stepId, status)
    }

    fun hasPlan(): Boolean = currentPlan != null
    fun isPlanApproved(): Boolean = currentPlan?.approved == true

    fun clear() {
        currentPlan = null
        approvalFuture = null
    }
}

sealed class PlanApprovalResult {
    object Approved : PlanApprovalResult()
    data class Revised(val comments: Map<String, String>) : PlanApprovalResult()
}
```

### CreatePlanTool Design

```kotlin
class CreatePlanTool(private val planManager: PlanManager) : AgentTool {
    override val name = "create_plan"
    override val description = "Create an implementation plan before making changes. Use for complex tasks involving 3+ files, refactoring, or new features. The plan will be shown to the user for review before execution begins."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "goal" to ParameterProperty(type = "string", description = "What the task aims to achieve"),
            "approach" to ParameterProperty(type = "string", description = "High-level strategy for solving the problem"),
            "steps" to ParameterProperty(type = "string", description = "JSON array of steps: [{\"id\":\"1\",\"title\":\"...\",\"description\":\"...\",\"files\":[\"...\"],\"action\":\"read|edit|create|verify\"}]"),
            "testing" to ParameterProperty(type = "string", description = "How to verify the implementation works")
        ),
        required = listOf("goal", "steps")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val goal = params["goal"]?.jsonPrimitive?.content ?: return error("goal required")
        val approach = params["approach"]?.jsonPrimitive?.content ?: ""
        val stepsJson = params["steps"]?.jsonPrimitive?.content ?: return error("steps required")
        val testing = params["testing"]?.jsonPrimitive?.content ?: ""

        val steps = try {
            Json.decodeFromString<List<PlanStep>>(stepsJson)
        } catch (e: Exception) {
            return ToolResult("Error: invalid steps JSON: ${e.message}", "Error", 5, isError = true)
        }

        val plan = AgentPlan(goal = goal, approach = approach, steps = steps, testing = testing)

        // Submit plan and BLOCK until user approves or revises
        val result = planManager.submitPlan(plan).get() // Blocks the coroutine's thread

        return when (result) {
            is PlanApprovalResult.Approved -> {
                ToolResult(
                    content = "Plan approved by user. Proceed with execution step by step. Update each step's status using update_plan_step as you complete them.",
                    summary = "Plan approved (${steps.size} steps)",
                    tokenEstimate = 20
                )
            }
            is PlanApprovalResult.Revised -> {
                val comments = result.comments.entries.joinToString("\n") { "Step ${it.key}: ${it.value}" }
                ToolResult(
                    content = "User requested revisions to the plan:\n$comments\n\nPlease revise the plan by calling create_plan again with the updated steps.",
                    summary = "Plan revision requested",
                    tokenEstimate = TokenEstimator.estimate(comments),
                    isError = true // Signal to LLM to revise
                )
            }
        }
    }
}
```

**Note:** `CompletableFuture.get()` blocks the thread. Since `execute()` runs on `Dispatchers.IO`, this is acceptable — it blocks one IO thread, not the EDT. The UI remains responsive.

### UpdatePlanStepTool Design

```kotlin
class UpdatePlanStepTool(private val planManager: PlanManager) : AgentTool {
    override val name = "update_plan_step"
    override val description = "Update the status of a plan step during execution. Call this as you start and complete each step."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "step_id" to ParameterProperty(type = "string", description = "The step ID from the plan"),
            "status" to ParameterProperty(type = "string", description = "New status: 'running', 'done', or 'failed'")
        ),
        required = listOf("step_id", "status")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val stepId = params["step_id"]?.jsonPrimitive?.content ?: return error("step_id required")
        val status = params["status"]?.jsonPrimitive?.content ?: return error("status required")

        if (status !in setOf("running", "done", "failed")) {
            return ToolResult("Error: status must be 'running', 'done', or 'failed'", "Error", 5, isError = true)
        }

        planManager.updateStepStatus(stepId, status)
        return ToolResult("Step $stepId marked as $status", "Step $stepId: $status", 5)
    }
}
```

### Register tools in AgentService

Both tools need the PlanManager instance. Since PlanManager is per-session, create it in ConversationSession and pass it when creating tools.

**Simpler approach:** Create PlanManager as a field on AgentController (like rollbackManager) and pass it to the tools via a factory method.

Actually simplest: make PlanManager a singleton per-session, accessible via ConversationSession:

In `ConversationSession.kt`, add:
```kotlin
val planManager: PlanManager = PlanManager()
```

In `AgentService.kt`, the tools need PlanManager at registration time. But PlanManager is per-session. Solution: register the tools with a lazy reference:

Actually the cleanest approach: CreatePlanTool and UpdatePlanStepTool take PlanManager as constructor param. Register them when the session is created, not at AgentService startup.

But ToolRegistry is built once in AgentService... We need the tools to be able to find the current PlanManager.

**Pragmatic solution:** Use a project-level holder:

```kotlin
// In AgentService.kt, add:
var currentPlanManager: PlanManager? = null
```

Tools look it up:
```kotlin
class CreatePlanTool : AgentTool {
    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val planManager = AgentService.getInstance(project).currentPlanManager
            ?: return ToolResult("Error: no active session", "Error", 5, isError = true)
        // ...
    }
}
```

Controller sets it when session starts:
```kotlin
agentService.currentPlanManager = session!!.planManager
```

- [ ] **Step 1:** Create `PlanManager.kt` with AgentPlan, PlanStep data classes, submit/approve/revise/update methods
- [ ] **Step 2:** Create `CreatePlanTool.kt` — calls planManager.submitPlan(), blocks until approval
- [ ] **Step 3:** Create `UpdatePlanStepTool.kt` — calls planManager.updateStepStatus()
- [ ] **Step 4:** Add `planManager` field to ConversationSession
- [ ] **Step 5:** Add `currentPlanManager` to AgentService, set it in AgentController
- [ ] **Step 6:** Register both tools in AgentService.toolRegistry
- [ ] **Step 7:** Create `CreatePlanToolTest.kt` — test plan serialization, step status updates
- [ ] **Step 8:** Verify: `./gradlew :agent:compileKotlin && ./gradlew :agent:test --rerun --no-build-cache`
- [ ] **Step 9:** Commit: `feat(agent): create_plan + update_plan_step tools with PlanManager`

---

## Task 2: JCEF Interactive Plan Card

The frontend: render the plan as an interactive HTML card with comments, buttons, live updates.

**File:** `agent/src/main/resources/webview/agent-chat.html`

### New JS Functions

```javascript
/**
 * Render an interactive implementation plan card.
 * Called from Kotlin when CreatePlanTool fires.
 */
function renderPlan(planJson) {
  endStream(); hideEmpty();
  const plan = JSON.parse(planJson);
  // Build interactive HTML with goal, approach, steps, testing, comments, buttons
  // Each step has: status indicator, title, description, files, comment input
  // Footer: [Approve & Execute] [Revise with Comments]
}

/**
 * Update a single step's status indicator.
 * Called during execution as agent completes each step.
 */
function updatePlanStep(stepId, status) {
  // Find step element by data-step-id, update icon and color
}

/**
 * Collect all user comments from the plan card.
 * Called when user clicks "Revise with Comments".
 */
function collectPlanComments() {
  // Iterate over all comment inputs, return {stepId: comment} map
}
```

### Plan Card HTML Structure

```html
<div class="plan-card" id="plan-active">
  <div class="plan-header">
    <span class="plan-icon">📋</span>
    <span class="plan-title">Implementation Plan</span>
  </div>

  <div class="plan-section">
    <div class="plan-section-title">Goal</div>
    <div class="plan-section-content">{goal}</div>
  </div>

  <div class="plan-section">
    <div class="plan-section-title">Approach</div>
    <div class="plan-section-content">{approach}</div>
    <div class="plan-comment-toggle" onclick="toggleComment(this)">💬 Add comment</div>
    <input class="plan-comment-input" data-section="approach" placeholder="Your feedback..." style="display:none"/>
  </div>

  <div class="plan-steps">
    <div class="plan-section-title">Steps</div>
    {steps.map(step => `
      <div class="plan-step" data-step-id="${step.id}">
        <span class="plan-step-status">{statusIcon}</span>
        <div class="plan-step-content">
          <div class="plan-step-title">${step.id}. ${step.title}</div>
          <div class="plan-step-desc">${step.description}</div>
          <div class="plan-step-files">${step.files.join(', ')}</div>
        </div>
        <div class="plan-comment-toggle" onclick="toggleComment(this)">💬</div>
        <input class="plan-comment-input" data-step="${step.id}" placeholder="Feedback on this step..." style="display:none"/>
      </div>
    `)}
  </div>

  <div class="plan-section">
    <div class="plan-section-title">Testing</div>
    <div class="plan-section-content">{testing}</div>
  </div>

  <div class="plan-actions">
    <button class="plan-btn approve" onclick="approvePlan()">✓ Approve & Execute</button>
    <button class="plan-btn revise" onclick="revisePlan()">✏ Revise with Comments</button>
  </div>
</div>
```

### CSS for Plan Card

```css
.plan-card { background: var(--tool-bg); border: 1px solid var(--border); border-radius: 10px; margin: 12px 0; overflow: hidden; }
.plan-header { display: flex; align-items: center; gap: 8px; padding: 12px 16px; border-bottom: 1px solid var(--border); font-size: 14px; font-weight: 600; }
.plan-section { padding: 10px 16px; }
.plan-section-title { font-size: 11px; font-weight: 600; color: var(--fg-muted); text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 4px; }
.plan-section-content { font-size: 13px; line-height: 1.5; }
.plan-steps { padding: 0 16px 10px; }
.plan-step { display: flex; align-items: flex-start; gap: 10px; padding: 8px 0; border-bottom: 1px solid rgba(255,255,255,0.05); }
.plan-step-status { font-size: 16px; flex-shrink: 0; width: 24px; text-align: center; }
.plan-step-title { font-size: 13px; font-weight: 500; }
.plan-step-desc { font-size: 12px; color: var(--fg-secondary); margin-top: 2px; }
.plan-step-files { font-size: 11px; color: var(--fg-muted); font-family: var(--font-mono); margin-top: 2px; }
.plan-comment-toggle { font-size: 12px; cursor: pointer; color: var(--fg-muted); padding: 2px 4px; }
.plan-comment-toggle:hover { color: var(--link); }
.plan-comment-input { width: 100%; padding: 6px 10px; margin-top: 4px; background: var(--code-bg); border: 1px solid var(--border); border-radius: 6px; color: var(--fg); font-size: 12px; outline: none; }
.plan-comment-input:focus { border-color: var(--link); }
.plan-actions { display: flex; gap: 10px; padding: 12px 16px; border-top: 1px solid var(--border); }
.plan-btn { padding: 8px 18px; border-radius: 8px; font-size: 13px; font-weight: 500; cursor: pointer; border: none; }
.plan-btn.approve { background: var(--success); color: white; }
.plan-btn.approve:hover { opacity: 0.9; }
.plan-btn.revise { background: transparent; border: 1px solid var(--border); color: var(--fg-secondary); }
.plan-btn.revise:hover { border-color: var(--link); color: var(--fg); }
/* Step status icons */
.plan-step[data-status="pending"] .plan-step-status::before { content: "○"; color: var(--fg-muted); }
.plan-step[data-status="running"] .plan-step-status::before { content: "◉"; color: var(--link); }
.plan-step[data-status="done"] .plan-step-status::before { content: "✓"; color: var(--success); }
.plan-step[data-status="failed"] .plan-step-status::before { content: "✗"; color: var(--error); }
```

- [ ] **Step 1:** Add plan card CSS to agent-chat.html
- [ ] **Step 2:** Add `renderPlan(planJson)` JS function
- [ ] **Step 3:** Add `updatePlanStep(stepId, status)` JS function
- [ ] **Step 4:** Add `toggleComment()`, `approvePlan()`, `revisePlan()`, `collectPlanComments()` JS functions
- [ ] **Step 5:** Test: inject a test plan via browser console, verify rendering and interactivity
- [ ] **Step 6:** Commit: `feat(agent): JCEF interactive plan card with comments and live status`

---

## Task 3: Wire JCEF Plan Card to Kotlin

Connect the JS plan card actions (approve/revise/comment) back to Kotlin via JBCefJSQuery bridges.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

### AgentCefPanel additions

```kotlin
// New public methods
fun renderPlan(planJson: String) {
    callJs("renderPlan(${jsonStr(planJson)})")
}

fun updatePlanStep(stepId: String, status: String) {
    callJs("updatePlanStep(${jsonStr(stepId)}, ${jsonStr(status)})")
}

// New callbacks
var onPlanApproved: (() -> Unit)? = null
var onPlanRevised: ((String) -> Unit)? = null  // JSON string of comments

// New JBCefJSQuery bridges in createBrowser()
val approveQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { _ -> onPlanApproved?.invoke(); JBCefJSQuery.Response("ok") }
}
val reviseQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { commentsJson -> onPlanRevised?.invoke(commentsJson); JBCefJSQuery.Response("ok") }
}

// Inject bridges on page load
js("window._approvePlan = function() { ${approveQuery.inject("'approve'")} }")
js("window._revisePlan = function(comments) { ${reviseQuery.inject("comments")} }")
```

### AgentDashboardPanel additions

```kotlin
fun renderPlan(planJson: String) {
    cefPanel?.renderPlan(planJson) // fallback: appendStatus with plan summary
}

fun updatePlanStep(stepId: String, status: String) {
    cefPanel?.updatePlanStep(stepId, status)
}
```

### AgentController wiring

Wire PlanManager callbacks to dashboard:

```kotlin
// When session is created:
session!!.planManager.onPlanCreated = { plan ->
    val json = Json.encodeToString(plan)
    dashboard.renderPlan(json)
}
session!!.planManager.onStepUpdated = { stepId, status ->
    dashboard.updatePlanStep(stepId, status)
}

// Wire JCEF callbacks:
dashboard.setCefPlanCallbacks(
    onApprove = { session?.planManager?.approvePlan() },
    onRevise = { commentsJson ->
        val comments = Json.decodeFromString<Map<String, String>>(commentsJson)
        session?.planManager?.revisePlan(comments)
    }
)
```

- [ ] **Step 1:** Add `renderPlan()` and `updatePlanStep()` to AgentCefPanel with JS bridges
- [ ] **Step 2:** Add JBCefJSQuery bridges for approve/revise callbacks
- [ ] **Step 3:** Add delegation methods to AgentDashboardPanel
- [ ] **Step 4:** Wire PlanManager callbacks in AgentController
- [ ] **Step 5:** Wire JCEF approve/revise callbacks to PlanManager
- [ ] **Step 6:** Test: end-to-end — LLM calls create_plan → card appears → click Approve → LLM continues
- [ ] **Step 7:** Commit: `feat(agent): wire JCEF plan card to PlanManager via JBCefJSQuery`

---

## Task 4: Plan/Execute Mode Toggle + System Prompt Update

The user-facing mode switch and the prompt engineering that makes the LLM plan when appropriate.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt`

### Mode Toggle in Toolbar

```kotlin
// In AgentDashboardPanel
val modeToggle = JToggleButton("Plan").apply {
    icon = AllIcons.Actions.ListFiles
    toolTipText = "Toggle Plan mode — forces the agent to create a plan before making changes"
    putClientProperty("JButton.buttonType", "roundRect")
}
var isPlanMode: Boolean = false
    get() = modeToggle.isSelected
```

Add to toolbar between newChatButton and toolsButton.

### System Prompt Update

In `PromptAssembler.kt`, add planning instructions to the RULES:

```kotlin
val PLANNING_RULES = """
    <planning>
    - For complex tasks involving 3+ files, refactoring, new features, or architectural changes:
      call create_plan first with a structured plan before making any changes.
    - For simple tasks (questions, single-file fixes, running commands, checking status):
      act directly without creating a plan.
    - When executing an approved plan, call update_plan_step to mark each step as
      'running' when you start it and 'done' when you complete it.
    - If the user adds comments to the plan and requests revision, incorporate their
      feedback and call create_plan again with the updated plan.
    </planning>
""".trimIndent()

val FORCED_PLANNING_RULES = """
    <planning mode="required">
    - You MUST call create_plan before making any changes or executing any tools.
    - Analyze the task, read relevant files, then produce a comprehensive plan.
    - Do NOT edit files, run commands, or make changes until the plan is approved.
    - You may use read_file, search_code, file_structure, and find_definition to research.
    - After the plan is approved, execute step by step using update_plan_step to track progress.
    </planning>
""".trimIndent()
```

Add a `planMode: Boolean = false` parameter to `buildSingleAgentPrompt()`:

```kotlin
fun buildSingleAgentPrompt(
    projectName: String? = null,
    projectPath: String? = null,
    frameworkInfo: String? = null,
    previousStepResults: List<String>? = null,
    repoMapContext: String? = null,
    planMode: Boolean = false  // NEW
): String {
    // ... existing sections ...

    // Add planning rules
    val planningSection = if (planMode) FORCED_PLANNING_RULES else PLANNING_RULES
    sections.add(planningSection)

    // ... existing rules ...
}
```

### Controller wiring

In `AgentController.executeTask()`, pass planMode to session creation:

```kotlin
if (session == null) {
    session = ConversationSession.create(project, agentService, planMode = dashboard.isPlanMode)
}
```

In `ConversationSession.create()`, pass planMode to PromptAssembler:

```kotlin
val systemPrompt = promptAssembler.buildSingleAgentPrompt(
    projectName = project.name,
    projectPath = project.basePath,
    repoMapContext = repoMap.ifBlank { null },
    planMode = planMode
)
```

- [ ] **Step 1:** Add Plan/Execute toggle button to AgentDashboardPanel toolbar
- [ ] **Step 2:** Add PLANNING_RULES and FORCED_PLANNING_RULES to PromptAssembler
- [ ] **Step 3:** Add `planMode` parameter to `buildSingleAgentPrompt()`
- [ ] **Step 4:** Pass planMode through ConversationSession.create() → PromptAssembler
- [ ] **Step 5:** Wire dashboard.isPlanMode in AgentController
- [ ] **Step 6:** Test: toggle Plan mode → send message → LLM calls create_plan → approve → executes
- [ ] **Step 7:** Test: in Execute mode → simple question → LLM responds directly (no plan)
- [ ] **Step 8:** Commit: `feat(agent): Plan/Execute mode toggle + system prompt planning instructions`

---

## Verification

```bash
./gradlew :agent:compileKotlin          # All compiles
./gradlew :agent:test --rerun --no-build-cache   # All tests pass
```

Manual verification in `runIde`:
1. Execute mode + simple question → direct answer (no plan) ✓
2. Execute mode + complex task → LLM creates plan (agent decides) ✓
3. Plan mode + any task → LLM forced to create plan ✓
4. Plan card renders with goal, steps, comment inputs ✓
5. Add comment to step → click Revise → LLM regenerates plan ✓
6. Click Approve → agent executes step by step → step status updates live ✓
