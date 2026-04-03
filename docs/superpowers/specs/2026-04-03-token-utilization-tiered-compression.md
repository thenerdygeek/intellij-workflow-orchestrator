# Token-Utilization Tiered Context Compression

**Date:** 2026-04-03
**Status:** Approved
**Module:** `:agent` — Context Management / Condenser Pipeline

## Problem

The current `ObservationMaskingCondenser` uses a fixed 30-event attention window that runs unconditionally every iteration. This replaces ALL observations older than 30 events with ~100-char placeholders regardless of actual context pressure. As a result:

1. Context utilization never exceeds ~70K tokens even with a 190K budget
2. The LLM loses access to earlier file contents, search results, and command output that could inform decisions
3. The LLM summarizer (stage 4) almost never fires because stages 1-2 prevent context from growing
4. The `ConversationWindowCondenser` only fires reactively on API errors, never proactively

## Industry Research

Analysis of 13 enterprise agentic tools (Claude Code, OpenHands, Codex CLI, Cline, Goose, Aider, Amazon Q, SWE-agent, etc.) shows convergence on:

- **Token-based triggers** (not event-count): Cline 75%, Goose 80%, Claude Code 83.5%, Codex CLI 90%
- **Progressive compression**: cheapest stages first, expensive (LLM) last
- **Proactive compression**: trigger before API errors, not after

OpenHands uses event-count-based masking (attention_window=100) with binary FULL/MASKED. No tool implements tiered observation compression (FULL/COMPRESSED/METADATA) — this is a differentiation opportunity.

## Design

### Token-Utilization Tiers

Replace the fixed event window with a 4-tier system gated by `tokenUtilization` (0.0-1.0):

```
Tier 0 (< 60%):  No masking. All observations kept at FULL fidelity.
Tier 1 (60-75%): ObservationMasking activates with 3 content tiers.
Tier 2 (75-85%): ConversationWindow activates proactively.
Tier 3 (85%+):   LLMSummarizing activates for structured summary.
```

### ObservationMaskingCondenser Changes

**Trigger:** `tokenUtilization >= observationMaskingThreshold` (default 0.60)

**Three content tiers** based on token distance from current position:

| Tier | Condition | Strategy |
|------|-----------|----------|
| FULL | Within `innerWindowTokens` (40K) from tail | Keep complete content unchanged |
| COMPRESSED | Within `outerWindowTokens` (60K) from tail | Keep first 20 + last 5 lines, add `[... N lines compressed ...]` |
| METADATA | Beyond outer window | Tool name + 100-char preview + recovery hint (current behavior) |

**Token distance calculation:** Approximate by accumulating estimated token counts from the tail backwards. Each event's token estimate = `content.length / 4` (standard heuristic). Events within the inner window are FULL, between inner and outer are COMPRESSED, beyond outer are METADATA.

**Key behaviors preserved:**
- `CondensationObservation` instances are NEVER masked (contain critical summaries)
- Non-observation events (actions) are never masked
- Always returns `CondenserView`, never `Condensation`

### ConversationWindowCondenser Changes

**Trigger:** `tokenUtilization >= conversationWindowThreshold` (default 0.75) OR `unhandledCondensationRequest`

Currently only fires on `unhandledCondensationRequest` (API context overflow error). Adding proactive triggering prevents wasted API calls.

Algorithm unchanged — keep essential events + ~50% of tail + NEVER_FORGET types.

### LLMSummarizingCondenser Changes

**Trigger threshold raised:** `tokenThreshold` from 0.75 to 0.85

This ensures cheaper stages (masking at 0.60, window at 0.75) handle compression first. The LLM summarizer becomes a last resort before termination (0.97).

### ContextManagementConfig Changes

```kotlin
data class ContextManagementConfig(
    // Stage 2: Observation masking (tiered, token-based)
    val observationMaskingThreshold: Double = 0.60,
    val observationMaskingInnerWindowTokens: Int = 40_000,
    val observationMaskingOuterWindowTokens: Int = 60_000,

    // Stage 1: Smart pruner (unchanged)
    val smartPrunerEnabled: Boolean = true,

    // Stage 3: Conversation window (proactive)
    val conversationWindowThreshold: Double = 0.75,

    // Stage 4: LLM summarization
    val llmSummarizingMaxSize: Int = 150,
    val llmSummarizingKeepFirst: Int = 4,
    val llmSummarizingTokenThreshold: Double = 0.85,  // raised from 0.75
    val llmSummarizingMaxEventLength: Int = 10_000,

    // Rotation & safety
    val rotationThreshold: Double = 0.97,
    val condensationLoopThreshold: Int = 10,
    val useCheaperModelForSummarization: Boolean = true
)
```

**Worker config** (stricter for subagents):
```kotlin
val WORKER = ContextManagementConfig(
    observationMaskingThreshold = 0.50,
    observationMaskingInnerWindowTokens = 20_000,
    observationMaskingOuterWindowTokens = 30_000,
    conversationWindowThreshold = 0.65,
    llmSummarizingMaxSize = 50,
    llmSummarizingKeepFirst = 2,
    llmSummarizingTokenThreshold = 0.75,
    condensationLoopThreshold = 5
)
```

### Removed Fields

- `observationMaskingWindow: Int` — replaced by token-based thresholds

### CondenserFactory Changes

Pass new config fields to condensers:

```kotlin
// Stage 2: ObservationMasking (token-gated, tiered)
condensers.add(ObservationMaskingCondenser(
    threshold = config.observationMaskingThreshold,
    innerWindowTokens = config.observationMaskingInnerWindowTokens,
    outerWindowTokens = config.observationMaskingOuterWindowTokens
))

// Stage 3: ConversationWindow (proactive)
condensers.add(ConversationWindowCondenser(
    threshold = config.conversationWindowThreshold
))
```

### Pipeline Flow (Unchanged Order)

```
SmartPruner (always, zero-loss) → returns CondenserView
    ↓
ObservationMasking (≥60% utilization, 3 tiers) → returns CondenserView
    ↓
ConversationWindow (≥75% utilization OR API error) → returns Condensation (short-circuits)
    ↓
LLMSummarizing (≥85% utilization OR 150+ events OR API error) → returns Condensation
```

## Expected Behavior Change

**Before (current):**
- Context stays ~50-70K regardless of budget
- Observations masked after 30 events unconditionally
- LLM loses context from early file reads/searches

**After:**
- Context grows naturally up to ~60% of budget (~114K for 190K window)
- At 60%, observations progressively compress (FULL → COMPRESSED → METADATA)
- At 75%, conversation window trims proactively
- At 85%, LLM summarization kicks in
- Context utilization should typically range 60-80% instead of 30-40%

## Files Changed

| File | Change |
|------|--------|
| `ContextManagementConfig.kt` | Replace `observationMaskingWindow` with token-based fields, add `conversationWindowThreshold`, raise LLM threshold |
| `ObservationMaskingCondenser.kt` | Token-gated trigger, 3-tier masking with token distance |
| `ConversationWindowCondenser.kt` | Add `threshold` parameter, proactive trigger |
| `LLMSummarizingCondenser.kt` | Default threshold 0.85 (constructor default only) |
| `CondenserFactory.kt` | Pass new config fields to condensers |
| `agent/CLAUDE.md` | Update Context Management section |
| Tests | Update all condenser tests for new behavior |

## Testing Strategy

1. **ObservationMaskingCondenser tests:**
   - Below threshold (0.59): no masking occurs
   - At threshold (0.60): masking activates
   - Verify FULL tier (content within innerWindow preserved)
   - Verify COMPRESSED tier (first 20 + last 5 lines kept)
   - Verify METADATA tier (100-char preview + recovery hint)
   - CondensationObservation never masked regardless of tier

2. **ConversationWindowCondenser tests:**
   - Below threshold (0.74): no condensation
   - At threshold (0.75): condensation triggers
   - Still triggers on `unhandledCondensationRequest` regardless of utilization
   - NEVER_FORGET types preserved

3. **Integration tests:**
   - Pipeline with token utilization at 0.55: only SmartPruner runs
   - Pipeline at 0.65: SmartPruner + ObservationMasking
   - Pipeline at 0.80: SmartPruner + ObservationMasking + ConversationWindow (short-circuits)
   - Pipeline at 0.90: LLMSummarizing would fire but ConversationWindow short-circuits first
