# Context-Window Overrides + Unified Window Resolution — Design

**Date:** 2026-06-09
**Status:** Revised after Sonnet spec review (pending user spec review)
**Modules:** `:agent` (resolver, settings, consumers), reuses `:core` `ModelCatalogService`

## Problem

The model selector and the top context-usage bar disagree about a model's context window
(selector **132K**, bar **96K** at 100%). Both ultimately read `ContextWindow.maxInputTokens` from
the same `ModelCatalogService.getContextWindow(modelRef, tier="enterprise")` (a non-suspend in-memory
cache read; `core/.../ai/ModelCatalogService.kt:108`, DTO `core/.../ai/dto/ModelCatalogDtos.kt:50`).
So the cause is **not** two data sources or a missing cache — it is a **model-identity mismatch**:

- **Selector** (`AgentController.loadModelList`, ~`:1968`) keys each row on that row's own `m.id`
  (from `/.api/llm/models`). The user observing **132K for the selected row proves this lookup
  succeeds** — i.e. `m.id` and the catalog's `modelRef` DO match for the selected model.
- **Context bar** (`getContextUsage` provider, `AgentController.kt:853-858` → `ContextManager`
  `.effectiveMaxInputTokens()`) keys on `currentBrainModelId` — the model the brain last built
  (`AgentService.kt:1764`). That resolves to a *different* model (an auto-picked thinking variant,
  ~96K) or is null before the first message (→ ctor fallback). Different id → different number.

The picker's "active" highlight (`activeModel = sourcegraphChatModel ?: best?.id`,
`AgentController.kt:2023`) and the bar's `currentBrainModelId` are **two independent notions of "the
current model"** that drift apart. There is also no way to override a model's window.

## Goals

1. **One unified resolution path** every window read goes through, so selector and bar can never show
   different windows for the same model again.
2. **User override** of a model's max input tokens — per-model and/or a global "all models" value.
3. **No extra API calls** — reuse the existing 1-hour shared catalog cache.

## Non-goals (YAGNI)

- Overriding `maxUserInputTokens` (per-message cap) or `maxOutputTokens`. Override targets
  `maxInputTokens` only.
- Persisting the catalog to disk beyond the in-memory 1-hour TTL. No `tier` setting (stays `enterprise`).

## Decisions (from brainstorming + review)

| Question | Decision |
|---|---|
| Override vs catalog | **Absolute replace** — may be higher OR lower; soft-warn in settings when above catalog. |
| Per-model vs global | **Layered**: per-model > global > catalog > fallback. |
| Override target | `maxInputTokens` only. |
| **Display** "current model" (bar + picker highlight + TopBar) | The **selected model** (`AgentSettings.sourcegraphChatModel`). Guarantees selector/bar agree. |
| **Compaction / runtime budget** "current model" | The **running model** (`currentBrainModelId`). MUST track reality (see Two-Key below). |
| Architecture placement | New resolver in `:agent` wrapping `:core` catalog cache + reading `AgentSettings` overrides. |

## Two-key architecture (critical — from review Blocker 2)

There are **two distinct questions**, and conflating them breaks compaction:

- **Display budget** — "what window should the UI show for the model the user picked?" → key on the
  **selected model**. Drives: usage bar (`getContextUsage`), TopBar progress (`onTokenUpdate`),
  picker rows. This is purely cosmetic alignment and is what makes selector == bar.
- **Runtime budget** — "what is the real budget of the model the brain is actually calling?" → key on
  **`currentBrainModelId`**. Drives: `shouldCompact()`, `utilizationPercent()`, the L3/L4 token
  targets, and the sub-agent `contextBudget`. This MUST be the running model because L2 tier
  escalation (Opus-thinking → Opus → Sonnet…) and model fallback change `currentBrainModelId`
  mid-session **without** changing `sourcegraphChatModel`. Keying compaction off the selection would
  compute against the wrong budget and compact too late / never.

In the common case (no escalation) selected == running, so both show the same number. They diverge
only during fallback — and the existing "fallback active" model badge already signals that to the
user. **The override (a per-model value) is applied in BOTH keys** — display uses the override for
the selected model, compaction uses the override for the running model.

## Architecture

### Component: `EffectiveContextWindow` (`:agent`, `agent/.../model/EffectiveContextWindow.kt`)

One small, synchronous, injected, unit-testable decider. Both keys call the SAME function with a
different `modelId` argument.

```kotlin
class EffectiveContextWindow(
    private val catalog: () -> ModelCatalogService?,        // shared cached catalog (SharedCatalogHolder)
    private val overrides: () -> MaxTokenOverrides,         // snapshot from AgentSettings
    private val fallback: Int = FALLBACK_MAX_INPUT_TOKENS,  // 90_000 (existing constant)
) {
    /** Effective max INPUT tokens for [modelId]: per-model override > global override > catalog > fallback. */
    fun maxInputTokens(modelId: String?): Int {
        val ov = overrides()
        if (!modelId.isNullOrBlank()) ov.perModel[modelId]?.let { return it }   // (1) per-model wins
        ov.global?.let { return it }                                            // (2) global override
        val catVal = modelId?.let { catalog()?.getContextWindow(it)?.maxInputTokens }
        return catVal ?: fallback                                               // (3) catalog else (4) fallback
    }

    /** Real catalog window ignoring overrides — for the settings "real: 132K" column + warn. null if uncached. */
    fun catalogMaxInputTokens(modelId: String): Int? =
        catalog()?.getContextWindow(modelId)?.maxInputTokens
}

data class MaxTokenOverrides(val global: Int?, val perModel: Map<String, Int>)
```

`getContextWindow` is a non-suspend cache read (null on miss) → the resolver is synchronous and safe
from `loadModelList` and the usage provider. Constructed in `AgentService` (owns the shared catalog +
settings), exposed to `AgentController`, injected into the orchestrator `ContextManager`.

### Data model: `AgentSettings.State` (review Finding 4/10)

`State` extends `BaseState`; a bare `MutableMap` is NOT change-tracked by `BaseState.isModified()` and
needs special handling. To avoid platform-version annotation fragility, persist overrides as scalars:

```kotlin
// 0 = "no global override" (0 is not a valid window anyway → avoids nullable bindIntText).
var maxTokenGlobalOverride: Int = 0
// JSON-encoded {modelId: tokens}. A plain String IS change-tracked by BaseState (by string()).
var maxTokenPerModelOverrideJson: String = "{}"
```

`AgentSettings.maxTokenOverridesSnapshot(): MaxTokenOverrides` decodes the JSON + maps `global == 0`
to `null`. Round-trip is trivial and `isModified()` works because both fields are scalars.

### Consumer rewiring

**Display key = selected model** (`AgentController.selectedModelId()` = `sourcegraphChatModel`,
non-blank after first-open seeding — see below):

- **Selector** — `loadModelList` row builder (~`:1968`): row window =
  `effectiveContextWindow.maxInputTokens(m.id)`. (per-message `maxUserInputTokens` unchanged.)
- **Usage bar** — the `getContextUsage` provider (`AgentController.kt:853-858`): `max =
  effectiveContextWindow.maxInputTokens(selectedModelId())`. (No longer `cm.effectiveMaxInputTokens()`.)
- **TopBar progress** — `onTokenUpdate` (`AgentController.kt:2849-2854`): same display key as the bar.
- **`AgentCefPanel.kt:729`** hardcoded `(0 to 132_000)` pre-wire fallback: replace literal with the
  shared `FALLBACK_MAX_INPUT_TOKENS` (cosmetic, only used before the provider is wired).

**Runtime key = currentBrainModelId** (UNCHANGED keying; routed through the resolver so overrides apply):

- `ContextManager.effectiveMaxInputTokens()` keeps `currentModelRef = { currentBrainModelId }` but
  routes the value through the injected `EffectiveContextWindow` so a per-model/global override is
  honored for the running model. `shouldCompact()`, token targets, and `AgentService.kt:2104`
  (`spawnAgentTool.contextBudget = ctx.effectiveMaxInputTokens()`) keep using this — correct under
  L2 escalation. `AgentLoop.kt:1384` (logging only) is unaffected.

**Sub-agents** (review Finding 8): `SubagentRunner.kt:253` builds `ContextManager(maxInputTokens =
contextBudget)` with no catalog/model ref — intentionally a fixed allocated budget from the parent's
**runtime** effective value. NOT wired to `EffectiveContextWindow`; this is deliberate and documented.

**Why selector == bar now:** both display reads call `effectiveContextWindow.maxInputTokens(id)` and
the bar's `selectedModelId()` is exactly the selected picker row → identical by construction.

### Selected-model seeding (review Finding 6)

`loadModelList` (`AgentController.kt:2014-2023`) already sets `sourcegraphChatModel = pickBest.id`
when blank, and runs on first agent-tab open. So `selectedModelId()` is non-blank when the bar reads.
Defensive: if still blank, `selectedModelId()` falls back to `currentBrainModelId`, then the resolver
fallback. (Verified this seeding path runs on tab open during implementation.)

### Settings UI (review Finding 5/10)

New "Context Window" group in `AgentAdvancedConfigurable` (AI Agent ▸ Advanced):

- **Global override:** `bindIntText` directly on `agentSettings.state::maxTokenGlobalOverride`
  (non-null Int; 0 = no override). Label: "Max input tokens for all models (0 = use model default)".
- **Per-model table:** Kotlin UI DSL `panel {}` cannot bind a table, so use a **custom `JBTable` +
  `AbstractTableModel`** hosted via `cell(...)`. Columns `Model | Real (catalog) | Override`. The
  Configurable holds a mutable `List<Row(modelId, override)>` as UI state: populated from
  `maxTokenPerModelOverrideJson` + catalog rows on `reset()`, written back (re-encoded to JSON) on
  `apply()`, and compared in `isModified()` (does NOT come free from `dialogPanel.isModified()`).
  Follow `project_intellij_configurable_dialog_panel_pattern` (Configurable holds the panel +
  delegates isModified/apply/reset).
- **Soft warning:** inline label when an override > that model's `catalogMaxInputTokens` (non-blocking).
- Catalog not warmed → Real column shows "—" + hint "Open the Agent tab to load models."

### Refresh / events (review Finding 3/9)

- Add explicit `fun getController(): AgentController? = controller` to `AgentControllerRegistry` (stable
  reflective target; the multimodal path relies on the Kotlin-generated getter today).
- Add public `fun notifyContextWindowOverridesChanged()` to `AgentController` → calls
  `loadModelList(force = true)` (re-emits `updateModelList` with new effective per-row windows) + fires
  the existing context-usage refresh event (`UsageIndicator.tsx:55-57`) so the bar updates at once
  (1-second poll is the backstop).
- On `apply()`, the Configurable reaches the controller reflectively via `AgentControllerRegistry`
  (`:core` must not depend on `:agent`) and calls `notifyContextWindowOverridesChanged()`, exactly
  mirroring `pushImageSettingsToWebview`.

## Data flow

```
First agent-tab open
  → AgentService warms SharedCatalogHolder (ONE /.api/modelconfig fetch, cached 1h)
  → loadModelList seeds AgentSettings.sourcegraphChatModel from auto-pick if blank
Display reads (selector rows, bar, TopBar):  effectiveContextWindow.maxInputTokens(selected-or-row id)
Runtime reads (compaction, sub-agent budget): effectiveContextWindow.maxInputTokens(currentBrainModelId)
  both = perModel[id] ?: global ?: catalog(id) ?: fallback
Settings apply → persist overrides → notifyContextWindowOverridesChanged() → loadModelList(force) + usage refresh
```

## Error handling / edge cases

- **Cache-miss / offline:** catalog lookup null → `fallback` (90K) unless an override is set (overrides
  are local → keep working offline). Settings Real column "—".
- **Invalid override (≤0):** treated as "no override" (global 0 = unset; per-model entries pruned/ignored).
- **Stale per-model entry** (model removed): harmless; pruned by the row "Clear" action.
- **Override above catalog:** honored; compaction uses it (running-model key); soft-warn only.
- **Display vs runtime divergence** (L2 escalation): bar shows selected model; compaction tracks the
  running model. Intended; signaled by the existing fallback badge.

## Testing

- `EffectiveContextWindowTest` (pure unit): precedence per-model > global > catalog > fallback;
  exceed-catalog returns override; null/blank id; cache-miss → fallback; global applies absent per-model.
- `AgentSettings` round-trip: `maxTokenPerModelOverrideJson` empty / one entry / cleared;
  `maxTokenGlobalOverride` 0-vs-set; `maxTokenOverridesSnapshot()` decoding.
- Wiring contract test (source-text/behavioral, in the `*WiringTest`/parity style): the bar + TopBar +
  selector route DISPLAY through `EffectiveContextWindow` keyed on the **selected** model; compaction
  + sub-agent budget route through it keyed on **currentBrainModelId**.
- `ContextManager` test: `effectiveMaxInputTokens()` honors an override for the running model.
- Webview vitest: picker row renders override-aware window; `UsageIndicator` matches the selected row;
  settings soft-warning renders when override > real.

## Affected files

- New: `agent/.../model/EffectiveContextWindow.kt` (+ `EffectiveContextWindowTest`).
- `agent/.../settings/AgentSettings.kt` — 2 fields + `maxTokenOverridesSnapshot()`.
- `agent/.../settings/AgentAdvancedConfigurable.kt` — global field + custom JBTable group.
- `agent/.../ui/AgentController.kt` — selector row window; `selectedModelId()`; bar + `onTokenUpdate`
  display keys; `notifyContextWindowOverridesChanged()`; construct/inject resolver.
- `agent/.../ui/AgentCefPanel.kt` — replace hardcoded 132_000 fallback with constant.
- `agent/.../ui/AgentControllerRegistry.kt` — explicit `getController()`.
- `agent/.../loop/ContextManager.kt` — route `effectiveMaxInputTokens()`/`maxInputTokensFor` through resolver (running-model key).
- `agent/.../AgentService.kt` — construct `EffectiveContextWindow`, inject into `ContextManager`, expose to controller.
- `agent/webview/.../UsageIndicator.tsx` / model-picker row — refresh hookup + tests.

## Review resolution log (Sonnet review 2026-06-09)

- **Blocker 1 (root cause / id format):** selected-row 132K proves `getContextWindow(selectedId)` works → not a format miss; the divergence is `currentBrainModelId` ≠ selected. Display re-keying fixes it regardless. A defensive log/test confirms id parity during impl.
- **Blocker 2 (compaction re-key unsafe):** RESOLVED via two-key design — compaction stays on `currentBrainModelId`; only display uses the selected model.
- **Blocker 3 (refresh method):** RESOLVED — explicit `notifyContextWindowOverridesChanged()` + `getController()`.
- **Finding 4 (map persistence):** RESOLVED — JSON-string scalar field, change-tracked by `BaseState`.
- **Finding 5 (table UI):** RESOLVED — custom `JBTable`, manual reset/apply/isModified noted.
- **Finding 7 (missed consumers):** RESOLVED — `onTokenUpdate`, `AgentCefPanel:729`, `spawnAgentTool` budget enumerated and keyed correctly.
- **Finding 8 (sub-agents):** RESOLVED — documented intentional fixed budget from the parent's runtime value.
- **Finding 10 (nullable bind / mixed apply):** RESOLVED — global override is non-null Int (0=unset), direct bind.
