---
name: Bamboo write-path lessons (probe-validated 2026-05-08)
description: Three Bamboo write-path constraints discovered during probe validation; all three must flow into PR 7 (Bamboo polish)
type: project
originSessionId: 274de309-f044-421c-a300-450bfa0a16f8
---
# Bamboo write-path lessons (2026-05-08)

While validating the audit's P0 finding (`triggerBuild` JSON body silently dropped), the probe surfaced three additional constraints the plugin currently violates or doesn't account for. All three must be in PR 7's scope.

## 1. `X-Atlassian-Token: no-check` is required on every mutating REST POST

Plugin currently doesn't set it. Bamboo's queue endpoint can return 403 BEFORE checking BUILD permission if XSRF protection rejects the request. The audit flagged this missing for `Rerun Failed Jobs` (Struts admin action); the probe confirms the same applies to `/rest/api/latest/queue/{plan}`. Apply uniformly across `queueBuild`, `cancelBuild`, `rerunFailedJobs`, `stopBuild` — every mutation, not just Struts actions.

## 2. BUILD permission ≠ BUILD_READ permission

Read perms (BUILD_READ — fetch plans, results, variables) and trigger perms (BUILD — POST to /queue) are separate Bamboo perm grades. A PAT with read can browse the plugin's automation panel cleanly, then 403 the moment the user clicks Trigger Now. **The plugin currently shows no signal in advance.** PR 7 should pre-flight via `/rest/api/latest/plan/{key}?expand=actions` (which lists allowed actions for the current user) and grey the Trigger button with a tooltip when BUILD isn't granted. Same for cancel/rerun.

## 3. Form-encoded body works on Bamboo DC 10.2.14

The audit's P0 fix shape (`Content-Type: application/x-www-form-urlencoded` with `bamboo.variable.<name>=<value>` pairs) is empirically confirmed on the user's Bamboo instance via probe write-test. Atlassian docs accept both query-params and form-encoded body; the form body is the correct choice for `dockerTagsAsJson`-shaped payloads (avoids URL-length limits on large JSON values).

**Why:** Three concrete bugs surfaced during the 2026-05-08 probe validation of audit P0 finding #1. The probe's `_post_form` already implements the correct shape — PR 2 lifts that into `BambooApiClient.queueBuild`, PR 7 adds the surrounding permission-aware UI + the same header on every other write call.

**How to apply:** Don't ship PR 2 (queueBuild fix) without also adding `X-Atlassian-Token: no-check` to that same call. PR 7 then handles uniform application across the rest of `BambooApiClient`'s write surface plus the BUILD-permission preflight UI.

## 4. (2026-05-10) The Struts action endpoint `/build/admin/ajax/runChainAction.action` 404'd in production

Commit `c3a38117a` (Phase H "C-faithful" stage selection) switched `queueBuildWithStageSelection` from the documented REST queue endpoint to the Struts action endpoint with `stages_<name>=true` per stage, intending to support non-contiguous subset selection. The docstring acknowledged "the user should verify on first manual test" — verification failed: the user's Bamboo returns 404 for that path. Reverted in `5cc57ef0c` (v0.84.10) back to the REST queue endpoint.

Bamboo 10.0+ also officially deprecated Struts DMI (the `!method.action` URL form) per the 10.0 EAP release notes — `runChainAction.action` is not coming back as a stable REST-compatible path.

**Why:** This re-validates the original audit memo's warning ("undocumented, version-specific, requires session cookie on some DC versions"). Cost of the regression: two interim "fix" releases (v0.84.7 pack(), v0.84.8 bounded scrollPane) that were chasing a UI symptom while the actual broken path was masked by a separate dialog bug — once the dialog was fixed in v0.84.9, the trigger 404 surfaced immediately.

**How to apply:** Before switching ANY Bamboo write path to an undocumented Struts action endpoint, run `probe_bamboo.py --probe-stage-bound` (added 2026-05-10) against the user's actual Bamboo and confirm semantics empirically. Never ship an "audit memo says this should work" change to a write path without a probe verdict.

## 5. (2026-05-10) `?stage=X&executeAllStages=false` is an UPPER BOUND, not a "run only X" filter (probe-verified)

The `stage` query param on `/queue/{plan}` runs every plan stage from the start UP TO AND INCLUDING `X`, then stops. Sources:
- Atlassian REST docs literal: *"name of the stage that should be executed even if manual stage. Execution will follow to the next manual stage after this or end of plan if no subsequent manual stage."*
- Steffen Opel's accepted answer (community.atlassian.com/.../Rest-Api-to-start-a-stage-for-build/...): *"`?stage=Second&executeAllStages=false` runs up to and including the second stage only."*
- Empirically confirmed via `probe_bamboo.py --probe-stage-bound` against the user's Bamboo on 2026-05-10: with stage 2 chosen as the bound, stage 1 ran and stage 2 was queued for the agent; stages 3+ were not queued.

The 2026-05-07 audit memo got this WRONG (it claimed `?stage=` "just queues a manual stage execution against the most recent build") — that memo wrote the C-simple revert into v0.84.10 with `selectedStages.first()` as the param, which made Bamboo run only the first checked stage. User reported "I select two stages, only one runs". Fixed in `e581ba7b9` (v0.84.11) by passing `selectedStages.last()` (last plan-order stage checked) as the upper bound.

**How to apply:** When wiring stage selection to Bamboo's REST queue endpoint, pass the LAST plan-order stage in the user's selection as `?stage=`. The set must iterate in plan order — production callers build it from the dialog's checkbox list (LinkedHashSet), so `.last()` works. Also: Bamboo's design fundamentally cannot skip manual stages mid-chain, so any UI promising "select arbitrary subset" is inherently dishonest at the wire layer; the bound semantic is the closest honest approximation.
