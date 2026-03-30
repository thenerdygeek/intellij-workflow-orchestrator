# Agent Plan Mode Bug Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three confirmed bugs: plan step completion not showing in UI, plan mode UI button not injecting constraints into active sessions, and LLM using `cat` to create files instead of `edit_file`.

**Architecture:** Two independent layers — TypeScript webview (step status rendering) and Kotlin backend (plan mode wiring + prompt policy). The TypeScript fix is entirely in the webview build; the Kotlin fixes are in `AgentController` and `PromptAssembler`. No new files needed — all changes are targeted edits to existing files.

**Tech Stack:** Kotlin 2.1.10, React 19 + TypeScript + Zustand (webview), Gradle + IntelliJ Platform Plugin v2

---

## File Map

| File | Change |
|------|--------|
| `agent/webview/src/bridge/types.ts` | Add `'done'` to `PlanStepStatus` union |
| `agent/webview/src/components/agent/PlanProgressWidget.tsx` | Map `'done'` → `'completed'` in status mapping |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` | Inject `FORCED_PLANNING_RULES` + call `setPlanMode(true)` on enable |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt` | Add `edit_file` file-creation rule to `TOOL_POLICY` |
| `gradle.properties` | Bump `pluginVersion` to `0.39.0` |

---

### Task 1: Fix plan step status mismatch (`"done"` → `"completed"`)

**Root cause:** Kotlin's `UpdatePlanStepTool` sends `"done"` but the React `PlanStepStatus` type only includes `'completed'` (not `'done'`). `PlanProgressWidget` falls through to `step.status as 'pending' | 'completed'` which passes the raw string `"done"` to the `tool-ui` Plan component — which never renders it as checked.

**Fix strategy:** Extend the TypeScript type to include `'done'` and map it to `'completed'` at the widget boundary. No Kotlin changes — the LLM prompt explicitly instructs `"done"`, and persisted `plan.json` files use `"done"`.

**Files:**
- Modify: `agent/webview/src/bridge/types.ts:46`
- Modify: `agent/webview/src/components/agent/PlanProgressWidget.tsx:15-18`

- [ ] **Step 1: Update `PlanStepStatus` to include `'done'`**

In `agent/webview/src/bridge/types.ts`, change line 46:

```typescript
// Before:
export type PlanStepStatus = 'pending' | 'running' | 'completed' | 'failed' | 'skipped';

// After:
export type PlanStepStatus = 'pending' | 'running' | 'completed' | 'done' | 'failed' | 'skipped';
```

- [ ] **Step 2: Map `'done'` to `'completed'` in `PlanProgressWidget`**

In `agent/webview/src/components/agent/PlanProgressWidget.tsx`, change lines 15-18:

```typescript
// Before:
status: step.status === 'failed' ? 'cancelled' as const
  : step.status === 'skipped' ? 'cancelled' as const
  : step.status === 'running' ? 'in_progress' as const
  : step.status as 'pending' | 'completed',

// After:
status: step.status === 'failed' ? 'cancelled' as const
  : step.status === 'skipped' ? 'cancelled' as const
  : step.status === 'running' ? 'in_progress' as const
  : (step.status === 'done' || step.status === 'completed') ? 'completed' as const
  : 'pending' as const,
```

- [ ] **Step 3: Build the webview to confirm no TypeScript errors**

```bash
cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/webview
npm run build
```

Expected: clean build with no TypeScript errors. The output lands in `agent/src/main/resources/webview/dist/`.

- [ ] **Step 4: Commit**

```bash
cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin
git add agent/webview/src/bridge/types.ts agent/webview/src/components/agent/PlanProgressWidget.tsx agent/src/main/resources/webview/dist/
git commit -m "fix(agent): map 'done' step status to 'completed' in PlanProgressWidget

Kotlin UpdatePlanStepTool sends 'done' (matching LLM prompt instructions)
but React PlanStepStatus only knew 'completed'. Steps were falling through
to raw string 'done' which the tool-ui Plan component can't render as checked.

Add 'done' to PlanStepStatus union and map both 'done' and 'completed' to
'completed' at the widget boundary. Kotlin and persisted plan.json unchanged."
```

---

### Task 2: Fix plan mode UI button — inject constraints into active session

**Root cause:** `AgentController.onTogglePlanMode` (line 82-85) only sets `planModeEnabled = true` locally but:
1. Does NOT inject `PromptAssembler.FORCED_PLANNING_RULES` into the currently-active session's `ContextManager` — so the LLM never sees planning constraints when plan mode is toggled mid-session.
2. Does NOT call `dashboard.setPlanMode(true)` when enabling — only calls it when disabling.

The LLM-triggered path (`enable_plan_mode` tool) works correctly because `EnablePlanModeTool` directly calls `agentService.currentContextManager?.addSystemMessage(...)`. The UI button path needs the same injection.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt:82-86`

- [ ] **Step 5: Inject planning rules and sync UI on plan mode enable**

In `AgentController.kt`, find the `onTogglePlanMode` callback (around line 82) and replace it:

```kotlin
// Before:
onTogglePlanMode = { enabled ->
    planModeEnabled = enabled
    // Sync UI when user toggles (LLM-triggered path goes via onPlanModeEnabled below)
    if (!enabled) dashboard.setPlanMode(false)
},

// After:
onTogglePlanMode = { enabled ->
    planModeEnabled = enabled
    if (enabled) {
        // Inject planning constraints into any active session context so the LLM
        // immediately sees planning mode rules — mirrors what EnablePlanModeTool does.
        try { AgentService.getInstance(project).currentContextManager
            ?.addSystemMessage(PromptAssembler.FORCED_PLANNING_RULES) } catch (_: Exception) {}
        dashboard.setPlanMode(true)
    } else {
        dashboard.setPlanMode(false)
    }
},
```

- [ ] **Step 6: Verify `PromptAssembler` is imported in `AgentController.kt`**

Check the imports at the top of `AgentController.kt`. If `PromptAssembler` is not already imported, add:

```kotlin
import com.workflow.orchestrator.agent.orchestrator.PromptAssembler
```

- [ ] **Step 7: Run agent tests to confirm no regression**

```bash
cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin
./gradlew :agent:test --tests "*.AgentControllerTest" 2>/dev/null || ./gradlew :agent:test
```

Expected: all tests pass. If `AgentControllerTest` doesn't exist, any test failures indicate regressions.

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "fix(agent): inject FORCED_PLANNING_RULES when user enables plan mode from UI

When user clicks the Plan button on an active session, the LLM was not
receiving planning constraints — planModeEnabled was set locally but
PromptAssembler.FORCED_PLANNING_RULES was never injected into ContextManager.

Now mirrors what EnablePlanModeTool does: injects the rules mid-loop via
agentService.currentContextManager.addSystemMessage(). Also calls
dashboard.setPlanMode(true) so the UI confirms the active state."
```

---

### Task 3: Add file creation guidance to `TOOL_POLICY`

**Root cause:** LLM creates new files using `run_command` with `cat > file.kt << 'EOF'...`, bypassing IntelliJ's VFS/Document API. IntelliJ only tracks file creation through VFS synchronously; shell-created files arrive via async file watcher and appear as "Unversioned" with no proper change event. The correct approach is `edit_file` with `old_string=""` which goes through the Document API.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:258-268`

- [ ] **Step 9: Add file creation rule to `TOOL_POLICY`**

In `PromptAssembler.kt`, find `val TOOL_POLICY` (around line 258) and add one rule before the closing `</tool_policy>` tag:

```kotlin
// Before (last two lines of TOOL_POLICY):
            - ALWAYS fill the 'description' parameter on tools that have it — the user sees it in the approval dialog.
            </tool_policy>

// After:
            - ALWAYS fill the 'description' parameter on tools that have it — the user sees it in the approval dialog.
            - To CREATE a new file: use edit_file with old_string="" and new_string=<full content>. NEVER use run_command with cat/echo/heredoc to write files — shell-created files bypass IntelliJ's VFS and appear as untracked.
            </tool_policy>
```

- [ ] **Step 10: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "fix(agent): instruct LLM to use edit_file for new files, not cat/heredoc

Files created via run_command (cat > file) bypass IntelliJ VFS, arriving
only through the async file watcher and appearing as unversioned. Using
edit_file with old_string=\"\" goes through the Document API directly,
giving IntelliJ full VCS change tracking."
```

---

### Task 4: Bump version, build plugin, and release

- [ ] **Step 11: Bump `pluginVersion` in `gradle.properties`**

In `gradle.properties`, change:

```properties
# Before:
pluginVersion = 0.38.1

# After:
pluginVersion = 0.39.0
```

- [ ] **Step 12: Run clean build**

```bash
cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin
./gradlew clean buildPlugin
```

Expected: `BUILD SUCCESSFUL`. ZIP created at `build/distributions/workflow-orchestrator-0.39.0.zip`.

- [ ] **Step 13: Commit the version bump**

```bash
git add gradle.properties
git commit -m "chore: bump version to 0.39.0"
```

- [ ] **Step 14: Create GitHub release with attached ZIP**

```bash
gh release create v0.39.0 \
  build/distributions/workflow-orchestrator-0.39.0.zip \
  --title "v0.39.0 — Plan mode bug fixes" \
  --notes "## Bug Fixes

- **Plan step progress**: Completed steps now show checkmark — fixed \`'done'\` status not mapping to \`'completed'\` in React UI
- **Plan mode button**: Clicking Plan in the UI now injects planning constraints into the active LLM session (previously only new sessions got the rules)
- **File creation**: LLM now instructed to use \`edit_file\` for new files instead of \`cat\` heredoc, so IntelliJ VFS tracking works correctly"
```

Expected output: URL of the new GitHub release.

---

## Self-Review

**Spec coverage:**
- ✅ Step progress not showing → Task 1 (types.ts + PlanProgressWidget)
- ✅ Plan mode button not wiring LLM → Task 2 (AgentController)
- ✅ File creation via cat → Task 3 (PromptAssembler TOOL_POLICY)
- ✅ Build + release → Task 4

**Placeholder scan:** None — all steps have exact file paths, exact code diffs, exact commands.

**Type consistency:** `PlanStepStatus` updated in `types.ts` (Step 1) is the single source of truth; `PlanProgressWidget` consumes it (Step 2). No other files reference `'done'` in a way that breaks.

**Known deferred issues** (need logs, not code fixes):
- "Using tools." stuck without tool calls — needs API debug log analysis
- `attempt_completion` called during plan execution — needs session log analysis
