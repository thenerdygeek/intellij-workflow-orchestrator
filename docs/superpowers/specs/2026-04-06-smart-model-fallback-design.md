# Smart Model Fallback & API Call Progress

**Date:** 2026-04-06
**Module:** `:agent`
**Status:** Approved

## Problem

When the Sourcegraph LLM API returns `NETWORK_ERROR` or `TIMEOUT` during the agent loop:

1. **No model fallback:** The loop retries the same model 3 times with exponential backoff, then fails. Large context payloads (100K+ tokens) are more prone to server-side timeouts than small ones. A smaller/faster model might succeed where the primary fails.

2. **No progress visibility:** After retries exhaust and the user types "continue", a new loop launches with zero feedback — no status message during brain creation or the API call. The 120s read timeout means the user sees nothing for up to 2 minutes before the next error. They perceive this as "nothing happened."

## Design

### Feature 1: Smart Model Fallback

Opt-in feature (`AgentSettings.enableModelFallback = false` by default). When enabled, the agent loop falls back to cheaper models on network errors and escalates back to the primary model after a cooldown period.

#### Fallback Chain

Resolved at session start from `ModelCache` based on available models. Priority order:

1. Opus thinking (primary — whatever `ModelCache.pickBest()` returns)
2. Opus non-thinking
3. Sonnet thinking
4. Sonnet non-thinking

No Haiku. Chain ends at Sonnet non-thinking. If a tier isn't available on the Sourcegraph instance, it's skipped.

#### State Machine

```
PRIMARY ──(network error)──► FALLBACK_1 ──(network error)──► FALLBACK_2 ──► ...
    ▲                            │
    │                            │ (3 successful iterations)
    │                            ▼
    └───────(try escalation)─── ESCALATING
                                 │
                          ┌──────┴──────┐
                     (succeeds)    (fails)
                          │             │
                          ▼             ▼
                      PRIMARY      FALLBACK_N
                                   (wait 6 iterations)
                                        │
                                        ▼
                                   ESCALATING (retry)
```

#### Escalation Logic

- After first fallback: try escalating back to primary after **3** successful iterations
- If escalation fails (network error on first call with primary model): stay on current fallback for **6** iterations before trying again
- Extended threshold (6) persists until a successful escalation resets it back to 3
- "Successful iteration" = an API call that returns a valid response (not necessarily a good tool call)

#### `ModelFallbackManager`

New class in `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackManager.kt`.

```kotlin
class ModelFallbackManager(
    private val fallbackChain: List<String>,  // model IDs in priority order
    private val initialEscalationThreshold: Int = 3,
    private val extendedEscalationThreshold: Int = 6
) {
    // Current position in fallback chain (0 = primary)
    private var currentIndex: Int = 0
    // Iterations since last model switch
    private var iterationsSinceSwitch: Int = 0
    // Whether the last escalation attempt failed
    private var lastEscalationFailed: Boolean = false

    /** Current model ID. */
    fun getCurrentModelId(): String

    /** Whether we're on the primary (index 0) model. */
    fun isPrimary(): Boolean

    /**
     * Called on NETWORK_ERROR/TIMEOUT during retry.
     * Returns the next fallback model ID, or null if chain is exhausted.
     * Advances currentIndex by 1.
     */
    fun onNetworkError(): String?

    /**
     * Called after each successful API call.
     * Returns an escalation model ID if it's time to try the primary,
     * or null if we should stay on the current model.
     */
    fun onIterationSuccess(): String?

    /**
     * Called when an escalation attempt fails (network error on primary).
     * Reverts to the previous fallback model and sets extended threshold.
     */
    fun onEscalationFailed(): String

    /** Reset to primary model (e.g., new session). */
    fun reset()
}
```

#### Building the Fallback Chain

In `AgentService.executeTask()`, after `ModelCache` resolves available models:

```kotlin
val fallbackChain = if (agentSettings.state.enableModelFallback) {
    ModelCache.buildFallbackChain(models)  // new method
} else null

val fallbackManager = fallbackChain?.let {
    ModelFallbackManager(it)
}
```

`ModelCache.buildFallbackChain()` filters and orders models by tier:
- Matches model names/IDs against known patterns (e.g., contains "opus" + "thinking", contains "opus", contains "sonnet" + "thinking", contains "sonnet")
- Skips tiers with no matching model
- Returns ordered list of model IDs

#### AgentLoop Integration

New constructor parameters:

```kotlin
class AgentLoop(
    // ... existing params ...
    private val fallbackManager: ModelFallbackManager? = null,
    private val brainFactory: (suspend (modelId: String) -> LlmBrain)? = null,
    private val onModelSwitch: ((fromModel: String, toModel: String, reason: String) -> Unit)? = null,
)
```

**In the retry block** (after `NETWORK_ERROR`/`TIMEOUT`, before `delay`):

```kotlin
if (fallbackManager != null && brainFactory != null) {
    val fallbackModel = fallbackManager.onNetworkError()
    if (fallbackModel != null) {
        val oldModel = brain.modelId
        brain = brainFactory(fallbackModel)
        onModelSwitch?.invoke(oldModel, fallbackModel, "Network error — falling back")
    }
}
```

Note: `brain` must become a `var` (currently effectively immutable since the loop doesn't reassign it).

**At the start of each iteration** (after successful API call processing, before the next API call):

```kotlin
if (fallbackManager != null && brainFactory != null && !fallbackManager.isPrimary()) {
    val escalationModel = fallbackManager.onIterationSuccess()
    if (escalationModel != null) {
        val oldModel = brain.modelId
        brain = brainFactory(escalationModel)
        onModelSwitch?.invoke(oldModel, escalationModel, "Escalating back")
        // If the NEXT API call fails, onEscalationFailed() handles it
    }
}
```

**Escalation failure handling** (in the retry block, when the current model IS the escalation target):

```kotlin
if (fallbackManager != null && fallbackManager.isPrimary()) {
    // We just tried to escalate and it failed
    val revertModel = fallbackManager.onEscalationFailed()
    brain = brainFactory(revertModel)
    onModelSwitch?.invoke(brain.modelId, revertModel, "Escalation failed — reverting")
}
```

### Feature 2: API Call Progress Indicator

New callback on `AgentLoop`:

```kotlin
private val onApiCallStart: ((modelId: String) -> Unit)? = null
```

Fires immediately before `brain.chatStream()` on each iteration. `AgentController` wires it to show a status message:

```kotlin
onApiCallStart = { modelId ->
    invokeLater {
        dashboard.appendStatus("Thinking ($modelId)...", StatusType.INFO)
    }
}
```

Combined with existing `onRetry` and new `onModelSwitch`, the user always sees what's happening:

1. "Thinking (opus-4-6-thinking)..."
2. "Network error — retrying (1/3) in 1s..."
3. "Thinking (opus-4-6-thinking)..." (retry)
4. "Network error — falling back to claude-sonnet-4-6..."
5. "Thinking (claude-sonnet-4-6)..."
6. (success — agent continues on Sonnet)
7. (after 3 iterations) "Escalating back to opus-4-6-thinking..."

### Feature 3: UI Model Chip Update

`onModelSwitch` callback in `AgentController` updates the model chip in the input bar:

```kotlin
onModelSwitch = { from, to, reason ->
    invokeLater {
        dashboard.appendStatus("$reason to $to", StatusType.WARNING)
        dashboard.updateModelChip(to)
    }
}
```

`dashboard.updateModelChip(modelId)` calls the JS bridge:
```kotlin
fun updateModelChip(modelId: String) {
    cefPanel?.callJs("updateModelChip(${jsonStr(modelId)})")
}
```

The React `InputBar` component reads the model chip from the store and displays it. When the model changes, the chip updates in real-time.

### Settings

New field in `AgentSettings`:

```kotlin
var enableModelFallback: Boolean = false
```

UI: Checkbox in **Settings > Tools > Workflow Orchestrator > AI & Advanced**:
> **Smart model fallback** — Automatically fall back to a cheaper model on network errors and escalate back when the connection stabilizes.

## Files Changed

| File | Change |
|------|--------|
| `agent/.../loop/ModelFallbackManager.kt` | **New** — fallback state machine |
| `agent/.../loop/AgentLoop.kt` | Add `fallbackManager`, `brainFactory`, `onModelSwitch`, `onApiCallStart` params; model switching in retry block and iteration start |
| `agent/.../AgentService.kt` | Build fallback chain from ModelCache; pass `brainFactory` lambda; wire callbacks |
| `agent/.../ui/AgentController.kt` | Wire `onModelSwitch` → dashboard status + model chip; wire `onApiCallStart` → status |
| `agent/.../ui/AgentDashboardPanel.kt` | Add `updateModelChip()` delegate |
| `agent/.../ui/AgentCefPanel.kt` | Add `updateModelChip()` JS call |
| `agent/webview/src/bridge/jcef-bridge.ts` | Add `updateModelChip` bridge function |
| `agent/webview/src/stores/chatStore.ts` | Add `currentModel` state + `updateModelChip` action |
| `agent/webview/src/components/input/InputBar.tsx` | Read `currentModel` from store, display in chip |
| `core/.../ai/ModelCache.kt` | Add `buildFallbackChain(models)` method |
| `core/.../ai/LlmBrain.kt` | Add `val modelId: String` to interface |
| `agent/.../settings/AgentSettings.kt` | Add `enableModelFallback` field |
| `agent/.../settings/AgentSettingsConfigurable.kt` | Add checkbox to AI & Advanced page |

## Testing

- `ModelFallbackManagerTest` — unit tests for state machine: fallback chain traversal, escalation after N iterations, escalation failure + extended threshold, chain exhaustion, reset
- `AgentLoopTest` — integration: verify model switch on network error, verify escalation after cooldown, verify `onModelSwitch` callback fires
- `ModelCacheTest` — `buildFallbackChain()` with various model lists (all tiers, missing tiers, empty)

## Not In Scope

- Haiku phrase timer pausing during retries (separate concern)
- Removing dead `sendMessageStream()` SSE code (separate cleanup)
- Context compaction on retry (orthogonal — condenser pipeline handles this)
- Configurable fallback chain in settings UI (chain is derived from available models)
