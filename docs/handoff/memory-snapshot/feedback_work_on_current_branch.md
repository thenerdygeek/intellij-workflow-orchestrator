---
name: Work on the current branch by default
description: Don't create worktrees or branch off main without explicit user direction; when user is on a feature branch they want work to land on that branch
type: feedback
originSessionId: 88aafcf4-63bd-43d4-9582-0bfc674a3146
---
**Rule:** If the user is on a feature branch (e.g. `feature/telemetry-and-logging`), default to working on that branch. Do not create a new worktree or cut a branch off `main` unless the user explicitly asks for one.

**Why:** On 2026-04-23, user on `feature/telemetry-and-logging` asked me to execute a plan. I reflexively spun up a worktree branched from `origin/main` — but `main` was 219 commits behind `feature/telemetry-and-logging` (upcoming merge candidate). Work ended up stacked on a stale base and had to be rebased + merged back. User frustration: "without even me asking, you created a worktree." Lesson: follow existing branch context.

**How to apply:**
- When kicking off multi-task execution, inspect `git branch --show-current` and `git log --oneline -5` first. If the branch is clearly a live feature/work branch (not `main`/`master`), work directly on it.
- Only propose a worktree when (a) the user explicitly asks, (b) the task needs isolation from in-flight local changes, or (c) there's a strong reason like parallel agent coordination — and ASK before creating.
- When cutting a new branch, default base = **current branch**, not `main`. If the task truly needs a fresh cut from `main`, state that and confirm.
- If in doubt: ask. "Should I work on this branch or cut a new one from main?" is a one-sentence question worth asking.
