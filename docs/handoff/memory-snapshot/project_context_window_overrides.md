---
name: project_context_window_overrides
description: Context-window divergence fix (selector 132K vs bar 96K) + per-model/global overrides — PR
metadata: 
  node_type: memory
  type: project
  originSessionId: 03af3cf4-6e28-40e6-8385-0ef0d97fc3e2
---

**MERGED 2026-06-10: context-window unification + user overrides.** **PR #52 squash-merged to `main` @ `0b562eef8`** (all 5 CI checks green incl. Tests). Pre-release **v0.86.0-ctxwin.1** built for in-IDE smoke. CI runs root `./gradlew detekt` (all modules), NOT `:agent:detekt` — a new test passed `:agent:detekt` locally but failed root detekt (ArgumentListWrapping/NoMultipleSpaces); always run `./gradlew detekt` before pushing.

**Bug:** model selector showed 132K but the top context bar showed 96K for the SAME model. Root cause = model-identity mismatch: selector keyed each row's window on its own `m.id`; the bar keyed on `currentBrainModelId` (the auto-picked *running* model — a thinking variant ~96K). Both read the same `ContextWindow.maxInputTokens` from `ModelCatalogService.getContextWindow` — NOT two data sources, NOT a missing cache. (The earlier 2026-06 fix had keyed the bar on `currentBrainModelId` to dodge a blank-`sourcegraphChatModel`→90K bug; this work re-keys display to the selection but keeps that protection via `selectedModelId()`'s `?: getCurrentBrainModelId()` fallback.)

**Fix — new `agent/model/EffectiveContextWindow.kt` resolver** both surfaces read through. Narrow dep `windowLookup: (String)->ContextWindow?` (wraps `sharedCatalogHolder.peek()?.getContextWindow(id)`) so it's mock-free + no `:core` change. Precedence: per-model > global > catalog > fallback(90K). **TWO-KEY design (critical):** DISPLAY (selector rows, bar via `setContextUsageProvider`, TopBar `onTokenUpdate`) keys on the SELECTED model (`AgentController.selectedModelId()`/`displayMaxInputTokens()`); RUNTIME (`ContextManager.effectiveMaxInputTokens()` → compaction + sub-agent `contextBudget`) stays keyed on `currentBrainModelId`. Re-keying compaction to the selection would break it under L2 tier-escalation — caught in spec review.

**Overrides:** `AgentSettings.State.maxTokenGlobalOverride: Int` (0=none) + `maxTokenPerModelOverrideJson: String` (JSON; snapshot filters ≤0) → `maxTokenOverridesSnapshot(): MaxTokenOverrides`. Settings UI: "Context Window" group in `AgentAdvancedConfigurable` (custom `JBTable`+`MaxTokenOverrideTableModel`, global `bindIntText`, soft-warn when override>catalog, manual isModified/apply/reset; apply→`AgentControllerRegistry.getController().notifyContextWindowOverridesChanged()`→`loadModelList(force)`+`dashboard.refreshContextUsage()`). Absolute-replace (may exceed catalog), layered per-model>global. No new API calls (reuses 1h shared catalog cache).

**Process:** brainstorm→spec (Sonnet-reviewed: two-key)→plan (Sonnet-reviewed: narrow dep, `sharedCatalogHolder.peek()`, reified JSON, sentinel placement)→subagent-driven impl (8 tasks TDD, two-stage review each)→final holistic review (I-1 ≤0-guard, I-2 resolver-routing, M-1 doc fixed). Spec: `docs/superpowers/specs/2026-06-09-context-window-overrides-design.md`. Plan: `docs/superpowers/plans/2026-06-09-...` (plans dir is gitignored; force-added).

**⚠ Still open:** **in-IDE smoke NOT done** — the wiring is pinned by source-text contract tests (AgentService/AgentController not unit-instantiable), NOT behaviorally; the actual UI fix + the Swing settings table/apply-refresh are runtime-unverified. Smoke checklist is in the v0.86.0-ctxwin.1 release notes + PR #52 body. Two `@get:JvmName` getters added (AgentService `effectiveContextWindowInternal`, AgentControllerRegistry `controllerRef`) to dodge property/`getX()` clashes. Related: [[project_token_context_optimization]], [[project_subagent_contextbar_bugfixes]] (prior 93K→132K bar fix).
