---
name: tool-feedback-fixes-shipped
description: "2026-05-21 — 8 commits on bugfix closing 7 tool-feedback items (A4 PSI smart-mode, A3 bamboo plan_key, E1 maven-goal log, B2 git-submodule hint, A1 db_query output_file, A2 coverage filter + DSL parity followup, B1 redesigned sonar legend). Verified-triage workflow + B1 hypothesis-refuted pivot are the load-bearing takeaways."
metadata: 
  node_type: memory
  type: project
  originSessionId: e882cad9-ed34-48ce-8083-91563472dafc
---

8 commits on `bugfix` (2026-05-21 session):

| Commit | Item | Files |
|--------|------|-------|
| `8ab3fe0a3` | A4 — PSI tools wait for smart mode | 3 src + 3 tests (+ 2 updated stale tests) |
| `0c37881eb` | A3 — Bamboo plan_key error → project_context hint | 1 src + 1 test |
| `046f89ed6` | E1 — run_maven_goal logs received param keys on blank-check | 1 src + 1 test |
| `fcf30ac77` | B2 — RunCommand emits git-submodule hint on "not a git repository" | 1 src + 1 test |
| `0e12d203c` | A1 — DbQueryTool reads `output_file`; AgentTool.spillOrFormat gains `forceSpill: Boolean = false` | 2 src + 1 test (+ SpillingWiringTest override) |
| `bbdd1d521` | A2 — CoverageTool 3-layer package filter (explicit > Maven groupId > Gradle group > src-tree) | 1 src + 1 test |
| `7457324ff` | A2 followup — `whenPresent` declared on `package_filter` (DSL parity guard) | 1 src line |
| `5b79081d8` | B1 (redesigned) — conditional empty-new-code legend in `:sonar` branch_quality_report | 1 src + 1 test |

**Why:** Closing agent-self-reported friction from the (now-deleted) `tools/feedback.md`. The user pasted ~14 reports; verify-first triage produced 4 confirmed bugs + 2 presentation fixes + 1 diagnostic + 3 misdiagnosed (deferred) + 1 cannot-locate (Windows `nul` — no agent-source culprit, external hook suspected).

**How to apply:**
- When an agent reports a tool bug, **verify before designing the fix**. The B1 case here is the canonical example: original hypothesis (Quality Gate=new-code vs Coverage=overall) was refuted by a verifier subagent — both sections actually use new-code period. The Coverage section already labels itself "Coverage (new code):". Real cause: gate-threshold (`new_coverage ≥ 80%` returns ERROR at 0.0) vs percentage metric (`new_line_coverage` returns 100% when there are 0 new lines, vacuously). Pivoted to a conditional legend explaining the divergence rather than the wrong [NEW CODE]/[OVERALL] split.
- The `forceSpill: Boolean = false` overload on `AgentTool.spillOrFormat` is a reusable primitive. Other tools wanting to honor `output_file=true` can adopt the same `forceSpill = outputFileRequested` pattern.
- The PSI tools' `inSmartMode(project).executeSynchronously()` already awaits smart mode — never re-introduce an eager `isDumb` guard. The fix at `FindDefinitionTool.kt`/`FindReferencesTool.kt`/`FindImplementationsTool.kt` is one deleted line each.

**Deferred / not in this bundle:**
- C1 — `edit_file` streaming-preview vs git-status confusion (system-prompt doc, not a bug)
- C2 — `search_code` on agent-data-dir paths (validator allows them; "Search path not found" means file missing on disk — better error split deferred)
- C3 — `bamboo grep_pattern` case-sensitivity (`containsMatchIn` is correct; needs system-prompt doc about `(?i)` flag)
- D1 — Windows `nul` file (no agent-source culprit; need Windows-side investigation; likely external hook script)

**Artifacts:**
- Spec: `docs/superpowers/specs/2026-05-21-tool-feedback-fixes-design.md` (committed iff user requests)
- Plan: `docs/superpowers/plans/2026-05-21-tool-feedback-fixes.md` (gitignored — `docs/superpowers/plans/` is in .gitignore)

Related: [[edit_file_streaming_preview_shipped]] (preceding 2026-05-21 bundle).
