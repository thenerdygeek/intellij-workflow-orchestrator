# Design: LLM-Triggered Plan Mode (`enable_plan_mode` Tool)

**Date:** 2026-03-29
**Status:** Approved for implementation

## Problem

Plan mode is currently user-only. The LLM receives soft guidance via `PLANNING_RULES` ("for complex tasks... call `create_plan` first") but can ignore it. The hard enforcement (`FORCED_PLANNING_RULES`) only activates when the user clicks the Plan button **before** session creation — and it cannot be enabled mid-session because the system prompt is immutable after creation.

## Solution

A new `enable_plan_mode` tool that the LLM calls when it decides a task requires thorough planning. This injects `FORCED_PLANNING_RULES` as a system message mid-loop (same pattern as `LoopGuard` nudges and `BudgetEnforcer` warnings) and syncs the UI so the Plan button highlights — identical to the user having pressed it manually.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Direction | One-way (enable only) | Once the LLM decides planning is needed, that discipline should stick. User can still toggle off via the button. |
| Injection method | System message, not anchor | Session-scoped and compressible. If context gets compressed, the LLM already has a plan by then. |
| Tool category | Core (always active) | Lightweight control tool — no reason to gate behind `request_tools`. |
| Worker access | ORCHESTRATOR only | Subagents must not toggle the parent's plan mode. |
| Controller state | Also sets `planModeEnabled` | So the boolean persists if the user sends another message in the same session. |

## Architecture

```
LLM calls enable_plan_mode(reason="...")
  -> EnablePlanModeTool.execute()
    -> contextManager.addSystemMessage(FORCED_PLANNING_RULES)    [mid-loop injection]
    -> agentService.onPlanModeEnabled?.invoke(true)               [callback to controller]
      -> AgentController sets planModeEnabled = true
      -> dashboard.setPlanMode(true)                              [Kotlin -> JS]
        -> callJs("setPlanMode(true)")
          -> jcef-bridge.ts: setPlanMode(true)
            -> chatStore.setInputMode('plan')
              -> PlanChip renders with accent color               [UI highlights]
```

## Tool Definition

```
name: enable_plan_mode
description: Switch to plan mode when the task requires thorough planning.
             Call this BEFORE making changes when you realize a task involves
             3+ files, architectural decisions, or complex multi-step work.
parameters:
  reason: string (required) — why plan mode is needed
```

**Return value:**
```
ToolResult("Plan mode enabled. You MUST call create_plan before any write operations. Reason: $reason")
```

## Implementation Plan

### 1. `AgentService.kt` — Add callback + context accessor

- Add `@Volatile var onPlanModeEnabled: ((Boolean) -> Unit)? = null`
- Add `@Volatile var currentContextManager: ContextManager? = null` (tools need this to inject the system message)

### 2. `EnablePlanModeTool.kt` — New tool (~60 lines)

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EnablePlanModeTool.kt`

Core category, ORCHESTRATOR-only.

`execute()` flow:
1. Get `AgentService.getInstance(project)`
2. Inject `FORCED_PLANNING_RULES` as a system message via `agentService.currentContextManager`
3. Fire `agentService.onPlanModeEnabled?.invoke(true)` callback
4. Return ToolResult with confirmation + reason

### 3. Tool Registration — Register in core category

Add `EnablePlanModeTool()` alongside `create_plan`, `think`, etc. in `AgentService.registerTools()`.

### 4. `AgentController.kt` — Wire callback

```kotlin
agentService.onPlanModeEnabled = { enabled ->
    planModeEnabled = enabled
    dashboard.setPlanMode(enabled)
}
```

### 5. `AgentCefPanel.kt` — New `setPlanMode()` method

```kotlin
fun setPlanMode(enabled: Boolean) {
    callJs("setPlanMode(${if (enabled) "true" else "false"})")
}
```

### 6. `jcef-bridge.ts` — New bridge function

```typescript
setPlanMode(enabled: boolean) {
    stores?.getChatStore().setInputMode(enabled ? 'plan' : 'agent');
}
```

No changes needed in `chatStore.ts` or `InputBar.tsx` — `setInputMode` and `PlanChip` active state already exist.

### 7. `PromptAssembler.kt` — Mention tool in PLANNING_RULES

Update the soft `PLANNING_RULES` to tell the LLM about the tool:
```
- If you realize mid-task that thorough planning is needed, call enable_plan_mode
  with your reasoning before calling create_plan.
```

### 8. `ConversationSession.kt` — Expose contextManager to AgentService

Set `agentService.currentContextManager = contextManager` during session creation so the tool can inject the system message.

## Files Touched (8)

| File | Change |
|------|--------|
| `agent/tools/builtin/EnablePlanModeTool.kt` | **New** — ~60 lines |
| `agent/AgentService.kt` | Add 2 `@Volatile` properties |
| `agent/AgentService.kt` (registration site) | Register new tool in core category |
| `agent/runtime/ConversationSession.kt` | Expose `contextManager` to `AgentService` |
| `agent/ui/AgentController.kt` | Wire `onPlanModeEnabled` callback |
| `agent/ui/AgentCefPanel.kt` | Add `setPlanMode()` method |
| `agent/webview/src/bridge/jcef-bridge.ts` | Add `setPlanMode` bridge function |
| `agent/orchestrator/PromptAssembler.kt` | Mention tool in `PLANNING_RULES` |

## Edge Cases

- **LLM calls it twice:** Idempotent — system message re-injected (harmless, ContextManager can deduplicate), UI stays highlighted.
- **User already enabled plan mode:** Tool is a no-op in effect — FORCED_PLANNING_RULES is already in the system prompt, callback sets same boolean.
- **Context compression drops the injected message:** By that point the LLM should already have a plan. The original `PLANNING_RULES` in the system prompt (which survives compression) still provides soft guidance.
- **Subagent tries to call it:** Tool restricted to `WorkerType.ORCHESTRATOR`, returns error for other worker types.
