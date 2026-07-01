---
name: Bitbucket tools audit result (2026-04-22)
description: Phase 0 audit of bitbucket_review + bitbucket_pr + PR-diff methods — verdicts, FIX severities, decision, and Phase 1+2 task deltas for the PR Review Workflow project
type: reference
originSessionId: 87f699de-c64e-4b0f-91f4-53890c45be4f
---
# Phase 0 Audit Result (2026-04-22)

## Decision

**PROCEED** per design spec §10 (`docs/superpowers/specs/2026-04-22-pr-review-workflow-design.md`) — broken-or-missing rate lands at 25.0%, exactly on the ≤25% threshold.

**Boundary note:** The PROCEED verdict rests on `merge_pr` being classified F-MED (loud API-error failure, not silent miscomment). Reclassification to F-HIGH would move the rate to 29.2% and flip the decision to RE-SCOPE. Plan 2 should sanity-check this during scoping.

## Verdict counts (n=24)

- OK: 0
- FIX: 9 (6 F-HIGH / 3 F-MED / 0 F-LOW)
- BROKEN: 0
- MISSING: 0
- UNTESTED: 15

## F-HIGH items (Phase 1 must-fix)

1. `bitbucket_review.add_inline_comment` — `fileType` hardcoded `"TO"` breaks REMOVED-line comments; `srcPath` missing for renames
2. `bitbucket_pr.get_my_prs` — missing `username.1` → returns all repo PRs instead of caller's PRs
3. `bitbucket_pr.get_reviewing_prs` — same as above
4. `PR-diff.getPullRequestChanges` — pagination missing + `srcPath` missing
5. `PR-diff.getPullRequestActivities` — pagination missing (silent truncation of activity stream)
6. `PR-diff.getPullRequestCommits` — pagination missing

## F-MED items (Phase 1 should-fix)

1. `bitbucket_review.set_reviewer_status` — missing `approved` field (WADL-undefined, DC-version-sensitive)
2. `bitbucket_pr.merge_pr` — tool description lists wrong strategy names (LLM emits invalid values 2/3 of time; loud failure)
3. `PR-diff.getPullRequestDiff` — no size cap; critical for Phase 4 LLM injection, also OOM risk in current PR-detail view

## Phase 2 new actions to build

6 new `bitbucket_review` actions per spec §6.3: `list_comments`, `get_comment`, `edit_comment`, `delete_comment`, `resolve_comment`, `reopen_comment`.

## Test coverage status

23/24 audited items have zero execute()-path tests (only `create_pr` has real coverage: happy / 4xx / 409 / live HTTP via MockWebServer). `create_pr` is also the only action with any live HTTP test in the codebase.

## Risks affecting current production users (beyond this project)

- `getPullRequestDiff` can OOM on large PRs (no size cap).
- Pagination truncation on activities / changes / commits silently returns partial data with no truncation marker or log warning visible to users.
- Schema-only test coverage across all 23 untested items means any regression is silent.

## Sandbox verification

Not performed during audit — subagent cannot extract Bitbucket access token from IntelliJ PasswordSafe. Static analysis only. Live verification is a user-driven follow-up; 5 specific curl-based checks are listed in the Sandbox section of the summary audit doc.

## Next step

Write Plan 2 (Phases 1–2: foundation + agent tools) incorporating the task deltas listed in the decision memo.

## References

- Summary audit: `docs/superpowers/audits/2026-04-22-bitbucket-tools-audit.md`
- Per-action details: `docs/superpowers/audits/2026-04-22-bitbucket-tools-audit-details.md`
- Decision memo: `docs/superpowers/audits/2026-04-22-decision-memo.md`
- Design spec: `docs/superpowers/specs/2026-04-22-pr-review-workflow-design.md`
