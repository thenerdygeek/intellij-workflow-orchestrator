---
name: Don't reintroduce Handover-side AI Pre-Review
description: The Handover tab's PreReview panel was deliberately deleted in commit 988c261c — PR-tab AI Review covers the use case. Future sessions: don't rebuild it.
type: feedback
originSessionId: 29c37ac8-5af7-4aed-88c6-2e3a452e6b2b
---
The Handover tab does NOT host AI code review. `PreReviewService` and
`PreReviewPanel` were deliberately deleted in commit `988c261c`
(`fix/automation-handover-quality-tabs`, 2026-05-07).

**Why:** The actual developer workflow is `PR → Bamboo build → unique
docker tag → automation suites → Handover` (per memory
`project_workflow_sequence`). The Handover tab is the LAST surface in
the chain. By the time the user is on it, the PR has already existed
long enough for Bamboo to build, produce a docker tag, and for
automation suites to run on that tag. There is therefore no "before
code review" moment reachable from this surface.

**Where AI code review lives:** The PR tab — `pullrequest/ui/PrDetailPanel`
hosts an `aiReviewToggle` that opens `AiReviewTabPanel`, backed by
`PrReviewSessionRegistry` and `core.prreview.PrReviewFindingsStore`.
That surface is agentic (uses `ai_review.add_finding` and
`bitbucket_review.list_comments` tools), persists findings, and lets
the user push them as inline Bitbucket comments.

**How to apply:** If a "review without ever opening a PR" workflow
re-emerges, extend the PR-tab `AiReviewTabPanel` to accept a "pre-PR"
mode (branch-vs-target diff with no PR id). Do NOT reintroduce a
parallel handover-side panel — that was the audit-flagged anti-pattern
this branch fixed.

**Reference:** `docs/research/2026-05-07-handover-wireup-plan.md`
"Phase 4 — AI Pre-Review — SKIPPED" section has the comparison table
and full rationale.
