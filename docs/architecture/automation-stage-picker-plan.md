# Automation Trigger Unification + Stage Picker — Implementation Plan

**Branch:** `fix/automation-handover-quality-tabs`
**Predecessor:** `automation-chainkey-unification-plan.md` (chain-key foundation)

## Problem

Two distinct issues in the trigger flow:

1. **Wrong default** — `BambooApiClient.queueBuild` defaults `executeAllStages = true`, so plugin-triggered builds run every stage in the plan. Bamboo's "Run Customized" UI default is to run only the first stage; the plugin disagrees by default.
2. **Leaky queue abstraction** — Automation tab exposes "Trigger Now" + "Queue Run" as two buttons; the user has to decide whether to queue or trigger. The queue should be an internal optimization, not a user choice.

## End-state architecture

- **Single `BambooApiClient.queueBuildWithStageSelection(chainKey, variables, selectedStages: Set<String>?)`** is the only Bamboo trigger primitive in the codebase.
  - `selectedStages = null` → run all stages (legacy escape hatch; explicit, not a default)
  - `selectedStages = setOf("Build")` → run only that stage
  - `selectedStages = emptySet()` → rejected with error
  - Uses Bamboo's action endpoint (`/build/admin/ajax/runChainAction.action` or equivalent — verify exact URL during impl) with form-encoded `stages_<name>=true` per selected stage. Includes `X-Atlassian-Token: no-check`.
  - Response is HTML/redirect → follow-up `GET /rest/api/latest/result/{chainKey}?includeAllStates=true&max-results=5` to extract the queued `buildResultKey` + `buildNumber`. Reuse existing `getRunningAndQueuedBuilds` helper.
- **`BambooService.triggerBuild(chainKey, variables, stages: Set<String>? = null)`** — single service method. The old no-stages overload deleted (no shim).
- **`ManualStageDialog`** extended with `TriggerMode.CUSTOM_STAGES` mode: list of stages as JBCheckBox rows; first stage pre-checked; OK button disabled if zero selected. Shared by Automation tab and Build tab.
- **Automation tab** — single split-button replaces "Trigger Now" + "Queue Run":
  - Default click → enqueue with `stages = setOf(firstStageName)` (matches Bamboo UI default)
  - Dropdown → "Trigger Customized…" opens the dialog → enqueue with user-selected stages
  - Dropdown → "Trigger All Stages" → enqueue with `stages = null` (legacy escape hatch)
  - All paths route through `QueueService.enqueue`; queue handles fast-path internally
- **`QueueService.enqueue` fast-path** — when queue empty for this suite AND `bambooService.getRunningAndQueuedBuilds(suiteChainKey)` is empty, call `doTrigger(entry)` synchronously instead of waiting for next poll tick. Otherwise existing behavior.
- **`QueueEntry`** gains `stages: Set<String>?` field. Nullable for backward-compat with persisted entries from `TagHistoryService` (old entries default to "first stage only" on rehydration).
- **`BambooBuildRunState`** (IntelliJ run config) — run-config settings UI gains a stage-list field; saved with config; replayed at trigger time without dialog.
- **`BambooBuildsTool.trigger_build`** (agent tool) — schema extended with optional `stages: array of strings`. LLM picks stages programmatically.
- **Architectural invariant** — extend `BambooServiceShapeInvariantTest`:
  - `triggerBuild` has exactly three params: `chainKey: String`, `variables: Map<String, String>`, `stages: Set<String>?`.
  - No method on `BambooService` takes a raw `executeAllStages: Boolean` parameter.

## Phase breakdown

### Phase F1 — `BambooApiClient.queueBuildWithStageSelection`

**Files:**
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
  - Add `suspend fun queueBuildWithStageSelection(chainKey: String, variables: Map<String, String>, selectedStages: Set<String>?): ApiResult<BambooQueueResponse>`.
  - For `selectedStages == null`: call existing `queueBuild` with `executeAllStages=true` (legacy path; preserved).
  - For `selectedStages != null`: form POST to action endpoint with `stages_<name>=true` per stage + variables. Header: `X-Atlassian-Token: no-check`. Reuse `HttpFormPost`.
  - After successful POST: call `getRunningAndQueuedBuilds(chainKey)` to find the just-queued build; extract `buildResultKey` + `buildNumber`; synthesize `BambooQueueResponse`.
  - **Probe first**: implementer must hit a real Bamboo instance (or stub locally) to verify the exact action URL and form-field shape. Document what they probed in the plan doc / commit message. Per project memory `feedback_audit_research_after_version`, do not deep-research the URL until probe confirms the server version's actual endpoint.
- Delete `queueBuild`'s old `stageName` + `executeAllStages` parameters once `queueBuildWithStageSelection` covers all callers (in Phase F2).

### Phase F2 — `BambooService.triggerBuild` signature

**Files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/services/BambooService.kt`
  - `triggerBuild(chainKey: String, variables: Map<String, String> = emptyMap(), stages: Set<String>? = null): ToolResult<BuildTriggerData>`
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt`
  - Body delegates to `api.queueBuildWithStageSelection(chainKey, variables, stages)`.
  - Drop `triggerStage` if it's now redundant (covered by `triggerBuild` with `stages = setOf(stageName)`); else keep with KDoc clarifying when to use which.

### Phase F3 — `ManualStageDialog` extension

**Files:**
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/ManualStageDialog.kt`
  - Add `TriggerMode.CUSTOM_STAGES`.
  - When mode is `CUSTOM_STAGES`: dialog fetches plan stages via `bambooService.getPlanStages(chainKey)` (add this if missing — should map from existing `BuildResultData.stages`). Renders one `JBCheckBox` per stage, first pre-checked, in stage execution order.
  - OK button disabled when no checkboxes are checked.
  - Caller passes `chainKey`; dialog's `onOk` returns `Set<String>` of selected stage names.
  - Variables editor (existing) works the same way regardless of mode.

### Phase F4 — Automation tab split-button

**Files:**
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt`
  - Delete the two `JButton`s ("Queue Run" + "Trigger Now").
  - Add a single split-button using `JBPopupFactory.createActionGroupPopup` or a custom `JPanel` with `JButton` + `JPopupMenu`.
  - Default click handler: build `QueueEntry` with `stages = setOf(firstStageName)` (look up from current suite's stage list via `bambooService.getPlanStages` cached at suite-select time); `queueService.enqueue(entry)`.
  - Popup actions:
    - "Trigger Customized…" — open `ManualStageDialog(CUSTOM_STAGES)`; on OK, `queueService.enqueue(entry with stages = userSelection)`.
    - "Trigger All Stages" — `queueService.enqueue(entry with stages = null)`.
  - Delete `onTriggerNow` (the direct-bypass path); delete `onQueueRun` (replaced by the unified split-button).

### Phase F5 — `QueueService.enqueue` fast-path

**Files:**
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt`
  - In `enqueue`, after persisting the entry to `_stateFlow`, check:
    1. Was queue empty for this suite *before* this entry? (i.e., `_stateFlow.value.filter { it.suitePlanKey == entry.suitePlanKey }.size == 1`)
    2. Is Bamboo idle for this suite? (`bambooService.getRunningAndQueuedBuilds(suiteChainKey).isEmpty()`)
  - If both true: call `doTrigger(entry)` synchronously inside the existing `mutex.withLock`. Skip `startPollingIfNeeded` for this trigger (the entry's status will reach `QUEUED_ON_BAMBOO` immediately).
  - If either false: existing behavior (record + start poller).
  - `QueueEntry` gains `stages: Set<String>? = null`. `TagHistoryService.saveQueueEntry` / `loadQueueEntry` updated for the new field with backward-compat default (null on rehydration of old entries).
  - `doTrigger(entry)` reads `entry.stages` and passes to `bambooService.triggerBuild(chainKey, vars, entry.stages)`.

### Phase F6 — Build tab integration

**Files:**
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt` (and any caller of `ManualStageDialog`)
  - Existing "Trigger Build" button now opens dialog in `CUSTOM_STAGES` mode by default (instead of `FULL_BUILD`).
  - `FULL_BUILD` mode either deleted (if no other caller) or kept with a KDoc note "legacy escape hatch — most callers should use `CUSTOM_STAGES`".

### Phase F7 — Run-config field

**Files:**
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildRunState.kt`
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildConfigurationType.kt` (or wherever the SettingsEditor lives)
  - Add `stages: Set<String>?` field to the run-config persistent state. Backwards-compat: missing field reads as null (which means "all stages" for legacy configs, OR change default to "first stage" — recommend "all stages" to avoid silently changing behavior of existing user run configs; document the choice in commit message).
  - SettingsEditor UI: a panel listing stages with checkboxes, populated when the user picks a plan key. "All stages" master checkbox at the top.
  - At trigger time (`BambooBuildRunState.startProcess` or equivalent): pass `stages` from settings to `bambooService.triggerBuild(...)`.

### Phase F8 — Agent tool

**Files:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsTool.kt` (`trigger_build` action)
  - Add optional `stages: array of strings` to the action's parameter schema. Document semantics in the inline help.
  - Translate to `Set<String>?` and pass to `bambooService.triggerBuild`.
  - Update tool docs.

### Phase F9 — Tests + invariants

**Files:**
- `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClientStageSelectionTest.kt` — new file
  - MockWebServer-based: assert URL is the action endpoint, body has `stages_<name>=true` per stage, header `X-Atlassian-Token: no-check` present.
  - Assert follow-up GET fetches the queued build's resultKey.
- `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/ui/ManualStageDialogTest.kt` — extend
  - Test `CUSTOM_STAGES` mode: first stage pre-checked, OK disabled when none checked.
- `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/QueueServiceTest.kt` — extend
  - Test: empty queue + idle Bamboo → fast-path triggers synchronously.
  - Test: empty queue + Bamboo busy → enters polling path.
  - Test: non-empty queue → enters polling path regardless of Bamboo state.
- `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceShapeInvariantTest.kt` — extend
  - Assert `triggerBuild` has exactly three params: `chainKey`, `variables`, `stages`.
  - Assert no method on `BambooService` takes a `Boolean` named `executeAllStages` or similar.

## Acceptance

1. `./gradlew :core:test :bamboo:test :automation:test :agent:test :pullrequest:test` — green.
2. `./gradlew verifyPlugin` — passes.
3. Manual: open Automation tab, click Trigger → only first stage runs in Bamboo.
4. Manual: open Automation tab, click Trigger ▼ → "Trigger Customized…" → uncheck first stage, check third stage → only third stage runs.
5. Manual: open Automation tab with a build already running for this suite → click Trigger → entry shows "queued, position #2" without firing immediately.
6. Manual: agent tool `bamboo_builds.trigger_build` with `stages: ["Test"]` → only Test stage runs.

## Out of scope

- Migrating other Bamboo plans not yet covered (only the trigger paths listed above).
- Multi-stage parallel selection beyond what the action endpoint natively supports.
- Per-stage variable overrides (single variables map applies to all selected stages, same as today).
