# Ralph Loop Patterns Integration — Design Spec

**Date:** 2026-03-26
**Module:** `:agent`
**Branch:** `feature/phase-3-agentic-ai-foundation`

## Overview

Integrate 5 patterns from the Ralph Loop / Ralph Wiggum technique into our ReAct-based agent to improve reliability, error recovery, and long-session resilience. This is not adopting the full Ralph Loop (autonomous batch processing) — it's borrowing specific structural patterns that complement our interactive agent.

**Research:** `docs/research/ralph-loop-analysis.md`

## Motivation

Our agent has prompt-based suggestions for best practices (read before edit, run diagnostics after changes, etc.) but the LLM can ignore them. Ralph Loop's insight is that structural enforcement beats educational prompting. We already have the infrastructure (`LoopGuard`, `FactsStore`, `PlanManager`, `ContextManager` anchors) — these patterns extend it.

## Pattern 1: Plan Recovery Bug Fix (DONE)

**Problem:** `ConversationSession.load()` returns `RecoveredSession.plan` but never wires it into `PlanManager`. After session recovery, `PlanManager.currentPlan` is null — `update_plan_step` and `checkDeviation` silently fail.

**Changes:**

### PlanManager.restorePlan()
New method that sets `currentPlan` and triggers `onPlanAnchorUpdate` to rebuild the context anchor.

```kotlin
// PlanManager.kt
fun restorePlan(plan: AgentPlan) {
    currentPlan = plan
    onPlanAnchorUpdate?.invoke(plan)
}
```

### ConversationSession.load()
After replaying messages, load `plan.json` from disk and restore into `PlanManager`:

```kotlin
val restoredPlan = PlanPersistence.load(loaded.store.sessionDirectory)
if (restoredPlan != null) {
    loaded.planManager.sessionDir = loaded.store.sessionDirectory
    loaded.planManager.restorePlan(restoredPlan)
}
```

### AgentController.resumeSession()
Wire callbacks on the loaded session (was missing) and set PlanManager/QuestionManager on AgentService:

```kotlin
wireSessionCallbacks(loaded)
agentService.currentPlanManager = loaded.planManager
agentService.currentQuestionManager = loaded.questionManager
```

**Status:** Implemented.

## Pattern 2: Learned Guardrails (`GuardrailStore`)

**Problem:** Guardrails are static prompt rules assembled from templates. Lessons from agent failures die with the session. The agent has `save_memory` but rarely records failure patterns automatically.

### New Component: `GuardrailStore`

**File:** `agent/src/main/kotlin/.../context/GuardrailStore.kt`
**Persistence:** `{projectBasePath}/.workflow/agent/guardrails.md`

Markdown file of learned constraints, human-readable and editable. Example:

```markdown
# Agent Guardrails

- Avoid calling `edit_file` on `build.gradle.kts` without reading it first — syntax is sensitive to whitespace
- Tool `run_command` with `./gradlew test` often times out in this project — use `./gradlew :module:test` instead
- When editing Kotlin files with `@Serializable`, always check imports — missing kotlinx.serialization imports cause silent failures
```

### Recording Constraints (Two Paths)

**Automatic — from LoopGuard/circuit breaker:**
- Doom loop (3x same tool+args) → record: "Avoid calling {tool} with {pattern} — causes loops"
- Circuit breaker fires → record: "Tool {name} frequently fails — consider alternatives"
- Same edit rejected 2+ times → record: "File {path} needs careful re-reading before edits"

Implementation: `LoopGuard` gets a `guardrailStore: GuardrailStore?` reference. When doom loop or circuit breaker fires, call `guardrailStore.record(constraint)`.

**Manual — from LLM:**
Extend `save_memory` with a `type` parameter (default `"memory"`, new option `"guardrail"`). When `type=guardrail`, content is appended to `guardrails.md` instead of the memory directory. This avoids adding a new tool and keeps the tool count stable.

### Loading Constraints

At session start, `PromptAssembler` reads `guardrails.md` and injects as `<guardrails>` section in the system prompt.

Additionally, inject as a compression-proof `guardrailsAnchor` in `ContextManager` (new anchor, same pattern as `planAnchor` / `factsAnchor`).

### Token Budget

Cap at ~2000 tokens. When exceeded, remove oldest entries (FIFO). File format is line-based markdown list — easy to count and trim.

### User Editability

The markdown file is in the project's `.workflow/` directory. Users can manually add, edit, or remove entries. This mirrors Ralph's "signs" system — the operator tunes agent behavior by adding corrective instructions.

## Pattern 3: Backpressure Gates

**Problem:** After the agent edits code, it *might* run diagnostics — the system prompt suggests it, `LoopGuard.beforeCompletion()` nudges before finishing. But there's no structured verify-after-edit cycle. The agent can edit multiple files without checking any.

### New Component: `BackpressureGate`

**File:** `agent/src/main/kotlin/.../runtime/BackpressureGate.kt`

Lightweight interceptor that tracks edits and injects verification nudges.

### Trigger Rules

| Tool | Gate | Verification |
|------|------|-------------|
| `edit_file` (success) | After every N edits (configurable, default 3) or on plan step completion | `diagnostics` on edited files |
| `run_command` / `run_tests` (failure) | On failure | Inject structured error context with parsed errors |
| `update_plan_step(status=done)` | On step completion | Run `diagnostics` + `run_tests` if test files were touched |

### Integration Point: SingleAgentSession

After tool execution (around the `editedFiles` tracking block), `BackpressureGate` accumulates edits:

1. **Threshold hit (N edits):** Inject system message: `"Backpressure gate: you've edited {N} files ({list}). Run diagnostics on these files before continuing."`
2. **LLM ignores nudge:** If next action is NOT a verification tool (`diagnostics`, `run_tests`, `run_inspections`), inject stronger: `"REQUIRED: Run diagnostics before making more changes."`
3. **Test/build failure:** Parse error output, inject as `<backpressure_error>` tagged message with structured feedback. Record to `GuardrailStore` on 2nd+ occurrence.

### Not a Hard Block

Strong nudge system, not a gate that breaks flow. The LLM can technically proceed, but injected messages make ignoring verification very difficult.

### Configuration

`AgentSettings.state.backpressureEditThreshold` — default 3, configurable. Can be set to 0 to disable.

## Pattern 4: Pre-Edit Search Enforcement

**Problem:** System prompt says "read before edit" but nothing enforces it. The agent can call `edit_file` on a file it never read, leading to wrong `old_string` matches and blind edits.

### Extension to LoopGuard

New method `checkPreEditRead(filePath: String): String?`:
- If `filePath` is in `readFiles` set → return null (proceed)
- If not → return error message blocking the edit

### Integration Point: SingleAgentSession

Before executing `edit_file`, check the gate:

```kotlin
if (toolName == "edit_file") {
    val filePath = extractFilePath(args)
    val warning = loopGuard.checkPreEditRead(filePath)
    if (warning != null) {
        // Return error to LLM — don't execute the edit
        toolResult = ToolResult(isError = true, content = warning, summary = "Edit blocked: file not read")
    }
}
```

### This is a Hard Gate

The edit returns an error. The LLM reads the file, retries. This prevents the #1 cause of failed edits.

### Edge Cases

- **After compression:** `readFiles` is cleared (`clearAllFileReads()`). Correct — compressed content may be stale, re-read required.
- **After edit:** File's read tracking already cleared (existing behavior). Each edit cycle: read → edit → read → edit.
- **`write_file` (new file):** Exempt — nothing to read. Only enforced on `edit_file`.
- **Subagent files:** Not tracked in parent's `LoopGuard`. Parent shouldn't edit files it hasn't personally read.
- **Always on:** No configuration toggle. There is no valid reason to edit a file without reading it.

## Pattern 5: Context Rotation

**Problem:** At `TERMINATE` (97% budget), the agent dies with "Context budget exhausted." All reasoning, plan state, and working context is lost. The user must manually start over.

### Enhanced TERMINATE Path in SingleAgentSession

Instead of returning `Failed`, externalize state and create a structured handoff.

### New Result Type

```kotlin
data class ContextRotated(
    val summary: String,
    val rotationStatePath: String,
    val tokensUsed: Int
) : SingleAgentResult()
```

### Externalized State (`rotation-state.json`)

Saved to `{sessionDir}/rotation-state.json`:

```json
{
  "goal": "original user task",
  "plan": { "goal": "...", "steps": [...] },
  "guardrails": ["constraint 1", "constraint 2"],
  "accomplishments": "LLM compression summary of what was done",
  "remainingWork": "derived from incomplete plan steps",
  "modifiedFiles": ["file1.kt", "file2.kt"],
  "factsSnapshot": [
    { "type": "EDIT_MADE", "path": "src/Main.kt", "content": "..." }
  ]
}
```

### Flow

1. `BudgetEnforcer` returns `TERMINATE`
2. Instead of failing, generate summary using existing `compressWithLlm()` template
3. Write `rotation-state.json` to session directory
4. Return `ContextRotated` result

### AgentController Handling

On `ContextRotated`:
1. Show user: "Context full. Here's what was accomplished: {summary}"
2. Auto-start a new session
3. Load `rotation-state.json` into new session's system prompt as `<rotated_context>` section
4. Restore plan into `PlanManager`
5. New session continues with fresh context window

### Constraints

- **Only for parent sessions** — subagents (`WorkerSession`) return `Failed` as before
- **Only with active plan** — without structured state to hand off, fall back to `Failed` with improved message
- **Rotated context cap:** ~10K tokens for `<rotated_context>` injection, leaving maximum room in fresh session

## Implementation Order

1. **Learned Guardrails** — `GuardrailStore` + `guardrailsAnchor` + auto-recording in `LoopGuard`
2. **Pre-Edit Search Enforcement** — `LoopGuard.checkPreEditRead()` + hard gate in `SingleAgentSession`
3. **Backpressure Gates** — `BackpressureGate` + integration in `SingleAgentSession`
4. **Context Rotation** — `ContextRotated` result type + rotation state + `AgentController` handling

Guardrails first because backpressure gates record to it. Pre-edit enforcement is the simplest win. Context rotation is the most complex and depends on the others being in place.

## Files to Create

| File | Component |
|------|-----------|
| `agent/.../context/GuardrailStore.kt` | Guardrail persistence + loading + token budget |
| `agent/.../runtime/BackpressureGate.kt` | Edit tracking + verification nudge injection |
| `agent/.../runtime/RotationState.kt` | Serializable state for context rotation |

## Files to Modify

| File | Changes |
|------|---------|
| `LoopGuard.kt` | `checkPreEditRead()`, `guardrailStore` reference, auto-record on doom loop/circuit breaker |
| `SingleAgentSession.kt` | Pre-edit gate, backpressure integration, `ContextRotated` result, rotation flow |
| `ContextManager.kt` | New `guardrailsAnchor` slot |
| `PromptAssembler.kt` | Load guardrails.md, inject `<guardrails>` section |
| `AgentController.kt` | Handle `ContextRotated`, auto-start new session with rotated context |
| `AgentSettings.kt` | `backpressureEditThreshold` setting |

## Testing

Each pattern gets its own test class:
- `GuardrailStoreTest` — persistence, FIFO eviction, token cap, loading
- `LoopGuardPreEditTest` — read tracking, edit blocking, compression clearing
- `BackpressureGateTest` — edit counting, nudge injection, threshold config
- `ContextRotationTest` — state serialization, rotation flow, new session loading
- `PlanRecoveryTest` — plan restore on session load (extend existing `MultiTurnFlowTest`)
