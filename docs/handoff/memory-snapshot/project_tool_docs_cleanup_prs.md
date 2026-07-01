---
name: Tool-docs cleanup PRs — 10 schema-reshape candidates
description: IMPORTANT pending work — 10 LLM-facing tool-schema reshape candidates from the 2026-05-11 tool-docs handoff §1. Each needs accept/defer/reject sign-off — NEVER batch-execute. Ordered by safety (#10/#8 trivial → #1/#3/#9 require ergonomics calls).
type: project
originSessionId: 325cdbf3-80cd-4fcf-8046-a49640f54aa5
---
# Tool-docs cleanup PRs — 10 candidates pending sign-off

**STATUS: TODO** (as of 2026-05-11). Surfaced by the Phase 5 swarm's per-tool `documentation()` audits; consolidated in `docs/handoff/2026-05-11-tool-docs-initiative.md` §1.

## Critical convention — DO NOT batch-execute

Every candidate below RESHAPES the LLM-facing tool schema. Per handoff conventions, each one needs an **explicit accept/defer/reject sign-off from the user** before execution. Walking through them one-by-one is the locked-in pattern, not a swarm dispatch.

## The 10 candidates (ranked by handoff §1, highest to lowest value)

### 1. 3-way merge: `format_code` + `optimize_imports` + `refactor_rename` → `refactor(kind:format|imports|rename)`
- **Win:** 3 schema slots → 1. ~80% shared scaffolding (DumbService → ReadAction → EDT WriteCommandAction → no-op detect → ToolResult); first two differ by a single dispatch line.
- **Catch:** `refactor_rename` is semantically heavier (target symbol + new name), so the `kind` enum may feel awkward — could regress LLM ergonomics.

### 2. Drop `task_list` entirely
- **Win:** -1 core tool; -1 hook-exempt special case.
- **Why drop:** `EnvironmentDetailsBuilder.appendTasks` already injects `id + status + subject` into every user turn.
- **Migration path:** 3-line patch to `appendTasks` adding `owner` + `blockedBy` to that render, then drop the tool.

### 3. Merge `send_stdin` ↔ `background_process(action=send_stdin)`
- **Win:** -1 core tool; one stdin path instead of two semantically equivalent ones.
- **Catch:** Standalone `send_stdin` has password-prompt detection (`ShellResolver.isLikelyPasswordPrompt`) + per-process rate limiting (`AgentSettings.maxStdinPerProcess`); the `background_process` action lacks both. Migration must FIRST absorb those guards into `BackgroundPool` and unify `ProcessRegistry`/`BackgroundPool` id namespaces.

### 4. Merge `db_list_databases` → `db_schema` level-0
- **Win:** -1 schema slot; one tool for "tell me what's there at any level" (databases → schemas → tables → columns).
- **NOT mergeable with:** `db_list_profiles` — pure config read, no network IO, fundamentally different from runtime discovery. Don't let scope creep.

### 5. Drop 3 `bitbucket_pr` actions
- `get_blocker_comment_count` — superseded by `check_merge_status` (already surfaces blocker counts).
- `get_required_builds` — rare repo-level config, set once and never queried.
- `update_pr_title` — rare housekeeping; titles set at `create_pr` time, almost never changed.
- **Win:** 19 actions → 16.

### 6. Drop 2 `jira` actions + fold 2 more
- Drop `get_worklogs` (time-log readback rarely LLM's job).
- Drop `get_board_issues` (dominated by `search_tickets` JQL).
- Fold `get_dev_branches` + `get_linked_prs` → `get_ticket(include_dev_status=true)`.
- **Win:** 17 actions → 13.
- **Catch:** Folding shifts cost — `get_ticket` becomes heavier when `include_dev_status=true`. Confirm Jira's dev-status endpoint isn't slow enough to make this awkward.

### 7. Drop `spring.scheduled_tasks` + `spring.event_listeners`
- **Win:** 15 actions → 13.
- **Why drop:** Zero-param convenience wrappers around `annotated_methods(annotation=@Scheduled)` / `annotated_methods(annotation=@EventListener)`.
- **Catch:** Wrappers exist *because* the LLM tends to forget `annotated_methods` exists. Dropping may regress discoverability.

### 8. Move `bamboo_plans.get_build_variables` → `bamboo_builds`
- **Win:** Better mental model; no count change.
- **Why move:** Operates on `result_key` (a build identifier), semantically belongs with build observation, not plan config.
- **Trivial relocation, low risk.**

### 9. Collapse `build`'s pip/Poetry/uv triples
- `python_list(manager:pip|poetry|uv)`, `python_outdated(manager)`, `python_lock_status(manager)`.
- **Win:** 26 actions → 20.
- **Catch:** Multi-manager projects need to specify `manager` every call; current explicit-action form documents intent more clearly. Argument-vs-action is a real LLM ergonomics call.

### 10. Extract shared `InspectionWalker` from `run_inspections` + `list_quickfixes`
- **Win:** ~60 LOC of duplicated scaffolding (`profile.getInspectionTools → LocalInspectionToolWrapper → HighlightDisplayKey.find → profile.isToolEnabled → buildVisitor → PsiRecursiveElementWalkingVisitor`) deduplicated.
- **No LLM schema change** — source-only DRY refactor. **Safest of the 10.**

## Recommended ordering for the walk-through

1. **#10** (no LLM impact, pure refactor) — warm-up.
2. **#8** (trivial relocation) — warm-up.
3. **#2** (drop `task_list`) — migration path is concrete (3-line patch).
4. **#5** / **#6** drops — case-by-case.
5. **#4** (db merge) — small, but check `db_list_profiles` is not swept up.
6. **#7** (spring wrappers) — only if discoverability concern is mitigated.
7. **#1**, **#3**, **#9** — bigger ergonomics calls, save for last when momentum is established.

## Why this matters

**Why:** The Phase 5 swarm intentionally documented EVERY tool — including ones that the swarm itself flagged as redundant or borderline. Verdicts captured in `documentation()` calls are the audit trail; these 10 are the highest-confidence consolidation candidates. The user explicitly stated this is "really good points" (2026-05-11) and asked for it to be saved as important cleanup work.

**How to apply:**
- When the user asks "what's next on tool-docs?" — surface this list (in addition to Phase 6 verification swarm).
- When walking through any candidate, present the win + catch, then ask for explicit accept/defer/reject before any code change.
- Never batch — schema reshapes need individual sign-off.
- Update the STATUS line at the top of this file as each one ships or is rejected.
