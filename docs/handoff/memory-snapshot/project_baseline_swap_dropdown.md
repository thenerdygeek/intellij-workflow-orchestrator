---
name: TODO — automation baseline build picker UI
description: AutomationPanel should show which build the baseline came from + dropdown to swap baseline among the parseable builds; currently auto-selects opaquely
type: project
originSessionId: 274de309-f044-421c-a300-450bfa0a16f8
---
# TODO — Automation baseline build picker UI

## What's missing

The plugin's automation panel auto-selects a baseline build via `TagBuilderService.scoreAndRankRuns` (`automation/.../service/TagBuilderService.kt`) but never tells the user:

1. **Which build was selected.** The build number, result key, and date should be visible somewhere on the AutomationPanel.
2. **What the alternatives were.** Other parseable recent builds should be in a dropdown so the user can manually swap baseline. The user said: "give option for those 10 builds as a dropdown to swap with that dockerTagsAsJson."

## Why this matters

When the auto-selected baseline is wrong (flaky stage penalized a good build, dev-tag run sneaks in, partial deploy), the user has no recourse. They can't see *why* this baseline was picked, can't compare against alternatives, can't override.

## Where the data already exists

`TagBuilderService.scoreAndRankRuns` already returns `Pair<List<BaselineRun>, BaselineDiagnostics>` — the full ranked list. `loadBaselineWithDiagnostics` returns the top one and discards the rest. The dropdown just needs to surface the rest of the ranked list.

## Bundle hint

This is the same Bamboo UI surface as audit PR 7 (plan-variable secrets + stage runnability + rerun-failed-jobs polish). Worth landing in the same PR or as PR 7.5 right after.

## Adjacent — the underlying scoring is also being challenged

The user's concern about the scoring algorithm (`-20 per failed stage` mis-ranks when "always some failure" is the team reality) ties into this — they want to see the alternatives partly because the auto-pick is sometimes wrong. Two fixes likely ship together:

1. Algorithm change: prioritize all-release builds (tier 1), rank tier 2 by lowest `failedTestCount` (probe is currently testing this two-tier scoring side-by-side with the plugin's current scoring).
2. UI dropdown so users can override even when the algorithm picks correctly.

Probe has the two-tier algorithm wired in for empirical validation — see `tools/atlassian-probe/probe_bamboo.py` `mirror_baseline_detection` + `_two_tier_key`. Once the user runs it on real data, that informs whether to ship the algorithm change or just the UI.

**Why:** User flagged 2026-05-08 while validating the probe — discovered the plugin auto-picks opaquely with no override. Wants both the picked-from indicator and a swap dropdown.

**How to apply:** Bundle into PR 7 or schedule as PR 7.5 right after. Don't ship the algorithm change without the UI — even a "perfect" algorithm picks wrong sometimes, so the override is essential regardless.
