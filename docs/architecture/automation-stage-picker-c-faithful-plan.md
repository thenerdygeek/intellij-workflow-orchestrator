# Phase H — C-faithful Stage Selection + Configurable Defaults

**Branch:** `fix/automation-handover-quality-tabs`
**Predecessors:** `automation-stage-picker-plan.md` (the C-simple version that just shipped as v0.84.4-alpha)
**Why now:** The C-simple implementation has three known limitations the user has explicitly asked to fix:

1. **No persisted user default.** Default click recomputes "first stage of latest build" on every click — there's no per-suite saved preference.
2. **Faulty fallback on API failure.** When `getLatestBuild` fails to return stages, the code silently substitutes `null` → runs all stages. Same wrong-data class as Phase D.
3. **C-simple semantics.** Only first-stage-forward selections are expressible. Users who want non-contiguous subsets (e.g., "Build + Deploy, skip Test") can't.

Phase H fixes all three.

## End-state architecture

- **`BambooApiClient.queueBuildWithStageSelection`** uses Bamboo's Struts action endpoint (`/build/admin/ajax/runChainAction.action` or whatever the live probe confirms). Form-encoded body with `stages_<name>=true` per selected stage + `bamboo.variable.<k>=<v>` per variable. Header `X-Atlassian-Token: no-check`. Auth: existing Bearer PAT (per memory `project_bamboo_write_path_lessons` — confirmed working for DC 10.2.14 write paths).
- **No fallback to C-simple.** Single path. If the action endpoint fails (auth, URL change, server-side rejection), surface the error to the user — do not silently substitute.
- **Per-suite default stages** persisted in `AutomationSettingsService`. Settings UI for editing; "Save as default for this suite" checkbox in the customize dialog.
- **Default-click logic:** if a saved default exists for the suite → use it; if not → open the customize dialog (no API-driven heuristic, no first-stage-of-latest-build).
- **API failure path:** never silently runs all stages. If we can't determine stages and have no saved default → show the dialog with an empty list + a "couldn't load stages from Bamboo: <reason>" banner. User explicitly picks or cancels.
- **Stale-stage filter on read:** when reading saved defaults, intersect against the current plan's actual stage list. If the intersection is empty (stages renamed/deleted), treat as "no default configured" + show one-time notification "Saved default stages no longer match plan; please reconfigure."

## End-state UX

```
[Trigger ▼]   ← split-button
   │
   ├─ Default click:
   │     ┌─ saved default exists ──→ enqueue with saved set
   │     └─ no saved default     ──→ open customize dialog
   │
   └─ Dropdown:
         ├─ Trigger Customized…   → open customize dialog (regardless of saved default)
         └─ Trigger All Stages    → enqueue with stages = null (run everything; explicit escape hatch)

Customize dialog (CUSTOM_STAGES mode):
  - Stage checkboxes (loaded from plan, NOT from latest build)
  - Pre-checks: saved default if any; else first stage; else nothing
  - Banner if stages couldn't be loaded: "Couldn't load stages from Bamboo: <error>. Refresh, or cancel."
  - "Save as default for this suite" checkbox at the bottom
  - OK / Cancel buttons
```

## Phasing

### H1 — Switch `queueBuildWithStageSelection` to action endpoint (C-faithful)

**Files:**
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
  - Replace the current `?executeAllStages=false&stage=<first>` body for the non-null branch:
    ```kotlin
    // selectedStages != null && nonEmpty
    val url = "$baseUrl/build/admin/ajax/runChainAction.action"
    val formFields = buildMap {
        put("planKey", chainKey)
        selectedStages.forEach { put("stages_$it", "true") }
        variables.forEach { (k, v) -> put("bamboo.variable.$k", v) }
    }
    val headers = mapOf("X-Atlassian-Token" to "no-check")
    val raw = postFormWithHeaders(httpClient, url, formFields, headers)
    ```
    (If `postFormWithHeaders` doesn't exist, add to `core/HttpFormPost.kt`.)
  - The action endpoint returns HTML/302; **don't try to parse it as JSON**. After the POST:
    - Treat 200/302 as success.
    - Call `getRunningAndQueuedBuilds(chainKey)` to find the just-queued build (the entry with the highest buildNumber + matching variables; or simply the first non-terminal entry — usually unambiguous immediately after a queue).
    - Synthesize a `BambooQueueResponse` from that build's `buildResultKey` + `buildNumber`.
  - For `selectedStages == null`: keep the current REST `?executeAllStages=true` path (legitimate "run all" escape hatch via the documented REST API; no need to use the action endpoint when running everything).
  - **Verify the URL with the live probe before merging.** Run `tools/atlassian-probe/probe_bamboo.py` against the user's Bamboo if a probe action exists for this; otherwise document the URL choice in the commit message and let the user verify on first manual test. **Do not silently fall back to C-simple if the URL is wrong** — the failure must be loud.

### H2 — Fix the API-failure fallback in `onEnqueueFirstStage`

**Files:**
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt`
  - Rename `onEnqueueFirstStage` → `onTriggerDefault`.
  - Replace body:
    ```kotlin
    private fun onTriggerDefault() {
        if (currentSuitePlanKey.isBlank()) return
        scope.launch {
            val saved = automationSettings.getSuiteDefaultStages(currentSuitePlanKey)
            if (saved != null && saved.isNotEmpty()) {
                invokeLater { enqueueWith(saved) }
                return@launch
            }
            // No saved default → open the customize dialog. Do NOT fall back to "first stage"
            // or "run all" — both would substitute wrong data for missing config.
            invokeLater { onTriggerCustomized() }
        }
    }
    ```
  - The `bambooService.getLatestBuild(currentSuitePlanKey)` call goes away from this default path — stages are now resolved either from saved settings or from the plan-stage list inside the dialog.

### H3 — Per-suite `defaultStages` in `AutomationSettingsService`

**Files:**
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/AutomationSettingsService.kt`
  - Existing suite config (find the `data class SuiteConfig` or equivalent and the persisted state class).
  - Add `defaultStages: Set<String>? = null` to the suite config.
  - Public API:
    - `fun getSuiteDefaultStages(suitePlanKey: String): Set<String>?` — returns the stale-stage-filtered set (see H7).
    - `fun setSuiteDefaultStages(suitePlanKey: String, stages: Set<String>?)` — persist.
  - Backward-compat: missing field on rehydration → null (= "no default", per H2 falls through to dialog).

### H4 — Settings UI for default stages

**Files:**
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/settings/AutomationConfigurable.kt`
  - In the per-suite config row, add a "Default stages:" multi-select widget (a small list of checkboxes or a text-field-with-completion).
  - Stages list source: call `bambooService.getLatestBuild(planKey).stages` once when the row is rendered (or lazily on focus). Cache for the session. If the API fails, render the field as disabled with a hint "Could not load stages — verify plan key."
  - Save → `AutomationSettingsService.setSuiteDefaultStages`.

### H5 — "Save as default" checkbox in customize dialog

**Files:**
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/ManualStageDialog.kt`
  - In `CUSTOM_STAGES` mode, add a `JBCheckBox("Save as default for this suite")` at the bottom of the dialog. Pre-checked if the current selection equals the saved default; unchecked otherwise.
  - On OK: if the checkbox is checked, the dialog returns both the selected stages AND a flag "save as default". The caller (`AutomationPanel.onTriggerCustomized`) calls `automationSettings.setSuiteDefaultStages(suitePlanKey, selectedStages)` before enqueueing.

### H6 — Customize dialog reads saved default + plan stages

**Files:**
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/ManualStageDialog.kt`
  - Stages list source: **plan stages**, not "latest build" stages. Use whatever Bamboo API returns the static plan stage list. If only `getLatestBuild` is available, that's a known issue but acceptable for now (most plans have at least one build).
  - Pre-check logic: saved default if any; else first stage; else nothing.
  - **Failure handling (issue #2 fix):** if stages can't be loaded, show the dialog with an empty list and a banner: `Couldn't load stages from Bamboo: <reason>. Refresh, or cancel.` OK is disabled. The user cannot pick "all" by accident.

### H7 — Stale-stage filter on read

**Files:**
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/AutomationSettingsService.kt`
  - In `getSuiteDefaultStages(suitePlanKey)`:
    1. Read raw saved set from settings.
    2. Fetch current plan stages (cached if recently fetched).
    3. Intersect saved set with current stages.
    4. If intersection is empty AND raw set was non-empty → emit a one-time notification "Saved default stages for $suitePlanKey no longer match the plan; reconfigure in settings." Return null.
    5. Otherwise return the intersection.

### H8 — Tests

**Files:**
- `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClientStageSelectionTest.kt` — extend
  - MockWebServer assertions:
    - URL is the action endpoint (not `/rest/api/latest/queue/...`).
    - Form body contains `stages_<name>=true` for each selected stage.
    - Header `X-Atlassian-Token: no-check` present.
    - For `null` stages: REST `?executeAllStages=true` path used (legitimate escape hatch).
    - For empty set: validation error returned.
    - After successful action POST: `getRunningAndQueuedBuilds` is called and `BambooQueueResponse` populated correctly.
- `automation/src/test/kotlin/com/workflow/orchestrator/automation/service/AutomationSettingsServiceDefaultStagesTest.kt` — new
  - `setSuiteDefaultStages` + `getSuiteDefaultStages` round-trip.
  - Stale-stage filter: saved `{Build, Deploy}`, current plan `{Build, Test}` → returns `{Build}`.
  - Stale-stage filter: saved `{X, Y}`, current plan `{A, B}` → returns null + notification.
- `automation/src/test/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanelTriggerDefaultTest.kt` — new (or extend existing)
  - Saved default exists → `enqueueWith(saved)` called.
  - No saved default → `onTriggerCustomized` (dialog) opened.
  - **Never** auto-runs all stages on API failure.

## Acceptance

1. `./gradlew :core:test :bamboo:test :automation:test :agent:test` — all green individually.
2. `./gradlew verifyPlugin` — passes.
3. Manual: configure default stages for a suite via settings → click Trigger → only those stages run.
4. Manual: clear saved default → click Trigger → customize dialog opens.
5. Manual: open dialog, pick stages, check "Save as default" + OK → settings show the saved set; next default click uses it without dialog.
6. Manual: rename a stage in Bamboo whose name is in saved default → click Trigger → dialog opens with notification "Saved default stages no longer match plan."
7. Manual: pick non-contiguous stages (e.g., 1st and 3rd, skip 2nd) → trigger → Bamboo runs only those two (verifies C-faithful).
8. Manual: temporarily misconfigure plan key → click Trigger → dialog opens with "couldn't load stages" banner; OK is disabled.

## Out of scope

- Probing the action endpoint URL programmatically. If the chosen URL is wrong on a particular Bamboo version, the error is surfaced to the user and we adjust in a follow-up.
- Migrating run-config / agent-tool entry points to use saved defaults. Those have their own per-config / per-tool-call inputs; per-suite defaults don't apply.

## Anti-deviation notes for the implementer

The previous Sonnet agent implementing the C-simple version explicitly diverged from "C-faithful" in its KDoc. **Do not repeat that.** The user has confirmed C-faithful is the chosen direction. Do not silently fall back to `?executeAllStages=false&stage=<first>` if you are uncertain about the action-endpoint URL. Either:
1. Implement the action-endpoint POST as specified, and accept that the URL may need correction once the user tests against their live Bamboo, OR
2. Stop and report the uncertainty in your final summary so the user can probe.

Either is acceptable. **What is not acceptable: silently choosing the C-simple URL.**
