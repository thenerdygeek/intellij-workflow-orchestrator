---
name: project-runide-behavioral-test-plan
description: Behavioral/visual QA test plan for runIde smoke (285 scenarios) + verified suspected-bug list
metadata: 
  node_type: memory
  type: project
  originSessionId: 0ed317b6-6756-4010-8ff1-8be639bc09fc
---

**Behavioral & visual QA test plan built 2026-06-25** on `feature/plugin-split`, at
`docs/qa/behavioral-test-plan/` (README.md + 8 `sections/*.md`). **285 scenarios, ~1,166 tickable
checks.** It is the *observable-UI* counterpart to the *log-oracle* catalogs
([[project_runide_smoke_campaign]]'s `RUNIDE-TEST-SCENARIOS.md` / `.superpowers/runide-catalog/` /
`WINDOWS-RUNIDE-CHECKLIST.md`): tells a tester what to click + what "correct" looks like (number
updates, scroll stick/release, expand, per-tool info, resume-and-continue), not just what greps in
`idea.log`. Authored by 8 parallel read-only subagents (source-grounded, `file:line` cited), then
fact-check + usability review rounds (per [[feedback_multi_round_review_plugin_split]]). Read-only
constraint baked in; §7 = write-op STOP-point cheat sheet. **STILL UNCOMMITTED** (new files on the
branch). Whole plan authored on the license-blocked Mac → every scenario is UNRUN; must be executed
on Windows Ultimate.

**README §4 = 14 suspected bugs found statically, fact-checked: 12 CONFIRMED, S12 REFUTED, S14
corrected, S13 by-design.** Highest-value confirmed candidates to verify/fix (cited in §4):
- **S1** `imageRefs` "N images attached" badge plumbed through bridge→store→ChatView but NO component
  reads `tc.imageRefs` (stale/dropped).
- **S2** `create_file`/`delete_file` absent from `ToolCallChain.tsx` `CATEGORY_MAP` → grey TOOL badge
  + plain text instead of WRITE badge + `DiffHtml` diff (map lists `write_file`/`Write`; no
  `WriteFileTool` exists).
- **S3** `UsageIndicator` (below-input token bar) not mounted in live app — only `HarnessApp.tsx`.
- **S5** `SessionStatsChips` shows only tokens+cost; `SessionInfo.iterations/durationMs` stored but
  never surfaced.
- **S6** per-session scroll position not restored (`MessageList` has no Virtuoso restore).
- **S7** history cards render no run-status badge (`HistoryItem` has no status field).
- **S9** context-menu Delete has NO confirm (inline + bulk delete both do).
- **S10** `SessionContextMenu` raw clientX/clientY, no viewport clamping (edge-clip).
- **S11** startup interrupted-session = notification balloon (not in-chat banner), no auto-resume,
  10-min recency gate.
Two P0 read-only safety holes were fixed in the plan during review: **HND-05** (steps told tester to
click Copyright "Fix All" = rewrites source files, NO dialog) and **PRT-13** (PR Approve fires
immediately, NO dialog) — both now LABEL/READ-ONLY only. Real Automation status-bar widget exists
(`AutomationStatusBarWidgetFactory`, root `plugin.xml:356-357`, "Workflow Automation Queue").
See [[project_plugin_split_open_source_backbone]].
