---
name: attempt_completion tool redesign plan
description: Full planning document for redesigning the attempt_completion tool with a kind enum (done/review/heads_up), per-kind UI, and typed persistence. Ready for execution.
type: project
originSessionId: 1904c786-89f7-4bef-b852-1b988d6795af
---
Plan location: `docs/plans/2026-04-18-attempt-completion-redesign.md` (in `feature/tooling-architecture-enhancements` worktree).

**Why:** Two problems. (1) live bug — `chatStore.addCompletionSummary` JSON-encodes `{result, verifyCommand}` into `msg.text` but `ChatView` passes `msg.text` raw to `CompletionCard`, so the verify command never renders and the user sees JSON in the summary. Persistence also drops `verifyCommand` entirely. (2) one-size-fits-all green card gives users no information scent and no LLM-side classification discipline.

**How to apply:** When executing this, do NOT patch the `verifyCommand` bug in isolation. The redesign IS the fix. Landing them together is a locked decision — the JSON-in-text hack gets deleted, not fixed, and a typed `completionData` side-car on `UiMessage` replaces it.

**Locked design decisions (12 total, summarised):** Single tool with `kind` enum (`done` / `review` / `heads_up`) + `result` + optional `verify_how` + `discovery` (required on `heads_up`). `partial` and `blocked` dropped. `task_report` untouched. One `CompletionCard` with kind discriminator (not three components). Typed side-car persistence (same pattern as `toolCallData` / `approvalData`). Two-layer LLM teaching (description + system prompt); `<thinking>` layer dropped because Sourcegraph hides thinking output. No per-kind interactive affordances in v1. `attempt_completion` is exclusively the loop-exit signal; stuck-mid-task → `ask_followup_question`.

**5 phases** in plan, all independently verifiable: Kotlin contract → Kotlin controller/bridge → React rewrite → system prompt + snapshots → observability (kind telemetry via SessionMetrics). Plan includes full file inventory, test matrix, migration story (backward compat via fallback render, no script), and appendix documenting rejected options.

**Status:** Ready for execution on the existing `feature/tooling-architecture-enhancements` branch (the active worktree). NO new branch and NO worktree switch — user explicitly pinned all work to this worktree. All design forks resolved in the 2026-04-18 discussion.
