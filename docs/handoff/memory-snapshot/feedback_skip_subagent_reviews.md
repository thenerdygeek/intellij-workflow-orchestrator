---
name: Skip per-task reviews; run a code review at phase boundaries
description: In subagent-driven development, skip per-task spec/quality reviewer subagents — but dispatch a code-quality reviewer at natural phase boundaries (groups of related tasks)
type: feedback
originSessionId: 8aa9367e-e66a-44eb-9fe0-eb2afe6ba171
---
When running `superpowers:subagent-driven-development`:

1. **Per task:** dispatch implementer only. Accept its report, move to the next task. Do NOT dispatch the spec-compliance or code-quality reviewer subagents per task.
2. **Per phase:** at natural phase boundaries (e.g. a coherent feature chunk made up of several tasks, before manual smoke-testing or before merging), DO dispatch a code-quality reviewer over the diff for that phase.

**Why:** Per-task reviews wasted ~80K tokens for marginal value (clickable-file-paths plan, 2026-04-23). Phase-level reviews catch structural issues across related changes without paying that cost on every commit. User refined this on 2026-04-29 active-ticket-multi-repo-chip plan.

**How to apply:**
- Identify phase boundaries from the plan structure (usually feature chunks; e.g. "Tasks 1–7a multi-repo chip" then "Tasks 9–11 activate-only checkbox" were two phases). When in doubt, ask.
- Per-task: implementer → inline sanity check (read the diff, eyeball it) → next task. If implementer reports DONE_WITH_CONCERNS or BLOCKED, handle normally.
- At end of phase: dispatch a code-quality reviewer over the cumulative diff for that phase. Do NOT dispatch a separate spec reviewer — fold spec-vs-impl checks into the same reviewer prompt.
- If a fold-forward warning would help (e.g. known gotcha, mock-wiring hint) bake it into the next implementer's prompt rather than spinning up a per-task reviewer.
