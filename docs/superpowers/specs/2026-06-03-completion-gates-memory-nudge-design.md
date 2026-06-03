# Completion Gates + Proactive Memory Update Nudge

**Date:** 2026-06-03
**Module:** `:agent`
**Status:** Design approved, pending spec review

## Problem

When the orchestrator LLM calls `attempt_completion`, the agent loop's `Completion`
branch (`AgentLoop.kt:2226-2305`) hard-codes a single optional gate: if
`feedbackEnabled`, it defers the exit, stores `pendingCompletion`, injects a nudge
asking the model to call the `feedback` tool, and continues the loop. A second site
(`AgentLoop.kt:2425-2432`) watches for the `feedback` tool to clear `awaitingFeedback`
and return the parked completion.

We want a **second** post-completion behavior: when a new setting is enabled, nudge the
agent — *before* the feedback nudge — to consider whether anything it learned this
session is worth saving to its file-based memory. Observed behavior today: unless
explicitly asked, the agent rarely updates memory.

Bolting a second `awaitingMemory` flag onto the existing logic would double an
already-messy interleaving and make a third future gate worse. This design replaces the
ad-hoc flags with a small, ordered **completion-gate chain** so feedback and memory
become two interchangeable gates and future post-completion steps are cheap to add.

## Goals

- Add a `proactiveMemoryUpdatesEnabled` agent setting (default off, opt-in) with a
  Settings UI checkbox.
- When enabled, inject a memory-review nudge after `attempt_completion` and **before**
  the feedback nudge.
- Replace `awaitingFeedback` / `pendingCompletion` loop fields with an extensible
  `CompletionGate` abstraction + a pure, dependency-free runner.
- Preserve current feedback behavior exactly when memory is off.

## Non-goals

- No new LLM tool. Memory updates use the existing `create_file` / `edit_file` tools.
- No external (`.agent-hooks.json`) user-scriptable completion hooks — these gates must
  inject into the loop and *wait*, which the fire-and-forget hook system cannot do.
- No change to sub-agent completion (`task_report`); gates are orchestrator-only.

## Decisions (locked)

| Decision | Choice | Rationale |
|---|---|---|
| Memory-gate satisfaction signal | Re-issue `attempt_completion` (no new tool) | The agent edits memory if it wants, then re-completes to proceed. Matches how the feedback gate's re-completion bypass already works today. |
| Setting name | `proactiveMemoryUpdatesEnabled` | camelCase, reads alongside `agentFeedbackEnabled`. |
| Extensibility surface | Internal code-level `CompletionGate` registry | Self-contained, unit-testable, no user config surface. |
| Memory-dir path source | Injected into `AgentLoop` by `AgentService` | Mirrors existing `memoryIndex` / `memoryIndexPath` flow; keeps the gate pure. |
| Memory nudge content | Also instructs updating the `MEMORY.md` index | `MemoryIndex.onMemoryFileCreated` auto-syncs the index only on `create_file`, not on `edit_file` of an existing memory file. |
| `pendingCompletion` capture point | First `attempt_completion` | Memory writes during the gate land under `~/.workflow-orchestrator/.../memory/`; freezing the result keeps those writes out of the user-facing `filesModified` / line-diff stats. |

## Architecture

### The abstraction

```kotlin
// agent/loop/completion/CompletionGate.kt
interface CompletionGate {
    /** Stable id, e.g. "memory", "feedback". */
    val id: String
    /** Message injected when this gate is armed. */
    fun nudge(): String
    /** True if invoking [toolName] satisfies this gate. Memory → always false. */
    fun isSatisfiedByTool(toolName: String): Boolean
}
```

Two signals drive the chain, with one universal composition rule:

- **`ToolUsed(name)`** satisfies a gate only if `isSatisfiedByTool(name)` is true.
- **`CompletionReattempted`** (the agent calls `attempt_completion` again) satisfies
  *whatever gate is currently armed* — a universal "done with this step, advance" bypass.

The bypass rule is not a special case: re-issuing `attempt_completion` instead of calling
`feedback` already exits the loop today (`AgentLoop.kt:2271` else-branch). The memory gate
simply has no satisfying tool, so re-completion is its *only* path forward.

### The runner

```kotlin
// agent/loop/completion/CompletionGateChain.kt
class CompletionGateChain(private val gates: List<CompletionGate>) {
    private var pending: LoopResult.Completed? = null
    private var cursor = 0      // gate currently armed / next to arm
    private var armed = false

    sealed interface Outcome {
        data class Arm(val nudge: String) : Outcome      // inject nudge, continue loop
        data class Finish(val result: LoopResult.Completed) : Outcome  // return it
        data object Ignore : Outcome                      // not gate-relevant
    }

    /** Called from the Completion branch on every attempt_completion. */
    fun onCompletionAttempt(fresh: LoopResult.Completed): Outcome

    /** Called from the Standard/Error branch on every tool result. */
    fun onToolUsed(toolName: String): Outcome
}
```

`onCompletionAttempt(fresh)`:
1. If not started: `pending = fresh`, `cursor = 0`, `armed = false`.
2. Else if `armed`: the armed gate is satisfied by re-completion → `cursor++`, `armed = false`.
3. Advance `cursor` past gates (here there are none to skip — list is pre-filtered).
4. If `cursor < gates.size`: `armed = true`; return `Arm(gates[cursor].nudge())`.
5. Else: return `Finish(pending!!)`.

`onToolUsed(name)`:
1. If `!armed`: return `Ignore`.
2. If `gates[cursor].isSatisfiedByTool(name)`: `cursor++`, `armed = false`; then if a next
   gate exists `armed = true` + return `Arm(next.nudge())`, else return `Finish(pending!!)`.
3. Else: return `Ignore`.

The list passed to the runner is **already filtered and ordered** by `AgentLoop`, so the
runner has zero settings / IntelliJ dependency — a pure state machine. An empty list →
first `attempt_completion` → `Finish` immediately (the current no-gates fast path, unified).

### The Completion branch (rewritten)

```kotlin
is ToolResultType.Completion -> {
    // ...metrics / logging unchanged...
    when (val outcome = completionGates.onCompletionAttempt(buildCompletion())) {
        is Arm -> {
            contextManager.collapseLastCompletionToolPair()
            messageStateHandler?.collapseLastCompletionToolPair()
            contextManager.addNudgeMessage(outcome.nudge)
            if (drainSteeringIntoContextOnExit()) userInputReceivedInToolCall = true
            // loop continues
        }
        is Finish -> {
            contextManager.collapseLastCompletionToolPair()
            messageStateHandler?.collapseLastCompletionToolPair()
            if (drainSteeringIntoContextOnExit()) { userInputReceivedInToolCall = true; return null }
            return outcome.result
        }
        Ignore -> {} // not reachable from onCompletionAttempt
    }
}
```

The tool-result site (`AgentLoop.kt:2425`) generalizes:

```kotlin
is ToolResultType.Standard, is ToolResultType.Error -> {
    when (val o = completionGates.onToolUsed(toolName)) {
        is Finish -> return o.result               // last gate satisfied → parked completion
        is Arm -> contextManager.addNudgeMessage(o.nudge)  // future: a gate after a tool-gate
        Ignore -> {}
    }
}
```

`awaitingFeedback` and `pendingCompletion` loop fields (`AgentLoop.kt:524-530`) are
**deleted** — that state moves into the runner.

### The two gates

- **`MemoryReviewGate`** (`id = "memory"`, first in chain):
  `isSatisfiedByTool` always returns false. Constructed with the absolute memory-dir path.
  `nudge()` instructs the agent to: review what *this session* taught it against the memory
  protocol (user prefs, feedback, project state, references — and the "what NOT to save"
  list so it does not log code/architecture/git history); if worth saving, use
  `create_file` / `edit_file` into the memory dir **and update the `MEMORY.md` index line**;
  then call `attempt_completion` again. If nothing is worth saving, just call
  `attempt_completion` again.

- **`FeedbackGate`** (`id = "feedback"`, second in chain):
  `isSatisfiedByTool(n) = n == "feedback"`. `nudge()` is the verbatim existing feedback
  text (`AgentLoop.kt:2251-2256`). Behaviorally identical to today when memory is off.

Combined flow (both enabled):
`attempt_completion` → memory nudge → (agent edits memory, optional) → `attempt_completion`
→ feedback nudge → `feedback` tool → return parked completion.

## Wiring

- **Setting:** `var proactiveMemoryUpdatesEnabled by property(false)` in
  `AgentSettings.State` (`agent/settings/AgentSettings.kt`), default off.
- **UI:** new "Memory" group checkbox in `AgentAdvancedConfigurable`, next to the existing
  feedback toggle, `bindSelected(agentSettings.state::proactiveMemoryUpdatesEnabled)` with a
  `.comment(...)` explaining the behavior.
- **`AgentLoop` constructor:** add `proactiveMemoryUpdatesEnabled: Boolean = false` and a
  memory-dir path/provider alongside `feedbackEnabled`. Build the gate list:
  ```kotlin
  val gates = buildList {
      if (proactiveMemoryUpdatesEnabled) add(MemoryReviewGate(memoryDirPath))
      if (feedbackEnabled) add(FeedbackGate())
  }
  val completionGates = CompletionGateChain(gates)
  ```
  `buildList` order = chain order = memory before feedback.
- **`AgentService`:** read `proactiveMemoryUpdatesEnabled` from settings and pass it (plus
  the memory-dir path it already computes for `MemoryIndex.load`) at **every** `AgentLoop`
  construction site where `feedbackEnabled` is currently passed: `executeTask`,
  `resumeSession`, **and the delegated wrappers** (`startDelegatedSession` /
  `resumeDelegatedSession`) — same parity discipline as `SessionUiCallbacks`.

## Scope / boundaries

- **Orchestrator-only by construction.** Sub-agents complete via `task_report` (not the
  `Completion` branch) and default to `memory: none`, so they never build these gates.
- **No schema-token cost.** No tool is registered for the memory gate.

## Threading

No new threading concerns. The runner is invoked synchronously inside the existing
`AgentLoop.run()` coroutine at the same points the current feedback logic runs. Nudges go
through `contextManager.addNudgeMessage` exactly as today.

## Testing

- **`CompletionGateChainTest`** (pure, no IntelliJ mocks):
  - empty chain → first `attempt_completion` finishes immediately
  - memory-only → re-completion advances to finish
  - feedback-only → parity with today (feedback tool finishes; re-completion bypass finishes)
  - both → memory nudge, then feedback nudge, in order
  - re-completion bypass on each gate
  - out-of-order tool calls while armed → `Ignore` (tool executes normally)
- **`AgentLoopCompletionGateTest`**:
  - memory nudge fires before feedback nudge
  - feedback exit path unregressed after deletion of `awaitingFeedback` / `pendingCompletion`
    (mirrors the existing feedback test)
- **Docs:** update `agent/CLAUDE.md` with a "Completion Gates" subsection and the new
  `proactiveMemoryUpdatesEnabled` setting in the same commit.

## Files touched

| File | Change |
|---|---|
| `agent/loop/completion/CompletionGate.kt` | NEW — interface + `MemoryReviewGate` + `FeedbackGate` |
| `agent/loop/completion/CompletionGateChain.kt` | NEW — pure runner |
| `agent/loop/AgentLoop.kt` | Rewrite `Completion` branch + tool-result site; delete feedback fields; add ctor params; build chain |
| `agent/settings/AgentSettings.kt` | Add `proactiveMemoryUpdatesEnabled` |
| `agent/settings/AgentAdvancedConfigurable.kt` | Add "Memory" checkbox |
| `agent/AgentService.kt` | Read setting + pass flag/path at all `AgentLoop` sites |
| `agent/CLAUDE.md` | Document completion gates + setting |
| `agent/.../CompletionGateChainTest.kt` | NEW |
| `agent/.../AgentLoopCompletionGateTest.kt` | NEW |

## Open questions

None — all design forks resolved.
