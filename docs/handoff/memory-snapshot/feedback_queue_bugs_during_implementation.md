---
name: Queue bugs found during implementation
description: When implementing a planned task and you discover unrelated bugs or issues, queue them in the plan doc rather than derailing into immediate fixes; address after the main task is verified complete
type: feedback
originSessionId: 6fd6cf79-1d31-4108-9843-ded6b467423c
---
When implementing a planned task and you discover bugs or issues outside that
task's scope, append them to a queue in the plan doc rather than fixing inline.
Address the queue only after the main task is verified complete.

**Why:** Keeps focus on the current scoped work, prevents scope creep, and
avoids piling up half-finished implementations across unrelated concerns.
Stated by the user 2026-05-11 in the context of Phase 7 implementation
(`docs/architecture/phase7-handover-context-plan.md` § "Discovered-during-implementation queue"),
but the principle is general — applies to every multi-task plan.

**How to apply:**
- During implementation, append discovered out-of-scope issues to the plan
  doc's queue section. Capture: which task uncovered it, file:line, severity,
  brief description, current state.
- Do NOT silently fix unrelated issues mid-task. Exception: a true blocker
  that prevents the current task from being completable — in that case, flag
  explicitly and ask the user before widening scope.
- After the current task is verified complete (tests pass, exit criteria met,
  diff reviewed), work the queue in priority order. Each queue item becomes
  its own task with its own completion bar.
- Treat the queue as a deliverable, not a "nice to have": when the main task
  is done, the queued bugs are what's next. Do not merge the main task and
  forget the queue exists.
- For plans without a dedicated queue section, create one. Same shape:
  a table with task-of-discovery / file:line / severity / description / status.
