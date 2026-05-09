# `new_task` — extended notes

## Why this exists

The agent loop has a finite context window. Every turn — the system prompt,
the conversation history, every file the LLM has read, every tool result —
all of it has to fit. When the input-token count crosses 85% of the model's
limit, `ContextManager` runs a 3-stage compaction pipeline: first it dedupes
file reads (replacing older copies with placeholders), then it truncates the
middle of the conversation, then it asks an LLM to summarize whatever's left.

That pipeline keeps long sessions alive, but it has a failure mode: summary
chaining decay. Each compaction's summary becomes the input to the next
compaction's summary, and after several rounds the conversation degrades to
"summary of summaries of summaries." The LLM is now reasoning against a
fuzzy recollection of its own earlier decisions — file paths it once knew
have collapsed into placeholders, prior tool calls are gone, the original
task spec exists only as a re-paraphrased fragment of a fragment.

`new_task` is the LLM's escape hatch. Instead of letting compaction grind
the context into noise, the LLM voluntarily declares "this session has
gotten too long, here's a clean handoff brief, restart." A fresh session
begins with the brief as its first user message, no other history, full
context budget intact. Cline ships this for the same reason
(`src/core/prompts/system-prompt/tools/new_task.ts`); we ported it
faithfully.

## How it differs from `task_create`

This is the single biggest source of LLM confusion with this tool. The
names sound similar — both contain "task" — and the Cline-inherited
description ("Request to create a new task with preloaded context...")
reinforces it.

The actual semantics are *opposite*:

| Aspect | `task_create` | `new_task` |
|---|---|---|
| Scope | Within the current session | Ends the current session |
| Persistence | Adds a Kanban card to `tasks.json` | No card; just a handoff brief |
| Context impact | Zero (just appends a TaskStore entry) | Erases everything |
| User-visible | Yes (task panel updates) | Yes (new session in History) |
| Reversible | Yes (mark DONE / delete) | No (previous session is COMPLETED) |
| Side effect class | AGENT_CONTROL (in-session bookkeeping) | AGENT_CONTROL (loop exit + restart) |

If the LLM calls `new_task` when it meant `task_create`, it nukes the
session. There is no undo. The only recovery is another handoff with a
better brief.

## How it differs from `agent` (subagent dispatch)

Both end *something* and start *something else*. The difference is who
keeps running:

- `agent` spawns a worker, the orchestrator **stays alive** and waits for
  the worker's `task_report`. After the worker completes, the orchestrator
  continues with the report appended to its context. This is the right
  tool for "delegate a sub-task and continue from there."
- `new_task` ends the orchestrator itself. There is no return path — the
  fresh session is the new orchestrator. This is the right tool for "this
  whole conversation has gotten too long, restart."

A useful heuristic: if the work after the call would naturally be done
*by the same agent that just called the tool*, use `new_task`. If the
work would naturally be done *by a different agent reporting back*, use
`agent`.

## How it differs from `attempt_completion`

Both exit the current loop:

- `attempt_completion` hands the result to the user. The session ends.
  No further LLM work happens unless the user types a new message.
- `new_task` hands a handoff brief to a fresh agent. Another LLM session
  starts immediately, picks up where the brief leaves off, and continues
  autonomously.

If the work is *done*, use `attempt_completion` — `new_task` would just
spin up an agent that re-discovers the answer is already there and burns
the user's tokens.

## What survives the handoff and what doesn't

Survives (because it's encoded in the brief):
- Whatever the LLM writes into the 5-section context summary.

Does NOT survive:
- File reads. The new session has not read any files yet. If the brief
  says "we modified `Foo.kt` line 42," the new session will still need to
  `read_file` the current state.
- Conversation history. The new session sees only the handoff message.
  All prior turns are gone.
- Active skill. Skills survive `ContextManager` compaction (they're re-
  injected into the rebuilt system prompt every turn) but they do *not*
  survive the handoff — the new session has no concept of the previous
  session's `use_skill` activation. If the work was being done under
  `systematic-debugging`, the brief must say so and the new session
  must `use_skill` again.
- TaskStore items. Tasks created via `task_create` live in the previous
  session's `tasks.json`. They do not carry to the new session's task
  store. If the brief's "Pending Tasks" section lists them, the new
  session can recreate them via `task_create`, but they are prose in the
  brief, not structured cards in the new session's TaskStore.
- Session approvals. `sessionApprovalStore.clear()` runs as part of the
  handoff. Tools the user had granted "Allow for session" must be re-
  approved in the fresh session.
- Memory. The file-based MEMORY.md system survives because it's per-
  project not per-session — both sessions share the same memory dir.
  But the always-injected `MEMORY.md` index is fetched at session start,
  so any mid-session edits the previous session made to memory are
  visible to the new session only because they were persisted to disk.

## When to call `new_task` vs let compaction run

Compaction is cheaper. Most of the time, the right answer is to let
`ContextManager` handle it — Stage 1 dedup often recovers 30%+ of tokens
for free, Stage 2 truncation buys another window, and Stage 3 LLM
summarization is the last resort.

`new_task` is the right call when:
- Compaction has fired multiple times already, and the summaries are
  visibly degrading (file paths gone, decisions paraphrased into mush,
  the LLM is asking the same question it asked 50 turns ago).
- The early conversation is no longer relevant. A long arc of "explore,
  consider three approaches, abandon two, commit to one" — once the
  approach is committed, the exploration is dead weight. A fresh session
  with "we picked approach X, here's where we are with it" is cleaner.
- The LLM has caught itself in a doom loop that compaction's truncation
  isn't breaking — sometimes a clean restart is the way out.

`new_task` is the wrong call when:
- The session hasn't compacted yet. New_task wastes the unused budget.
- The work is actually done. Use `attempt_completion`.
- The user is still actively conversing with the LLM. The handoff
  surfaces as a new session in the UI, which is jarring mid-conversation
  unless the LLM has telegraphed it.

## What replaces this if it goes away

Three fallbacks, declining order of fidelity:

1. **Aggressive compaction.** Lower the 85% threshold to 75% or 70%, run
   Stage 3 LLM summarization more eagerly. Buys headroom in cases where
   the conversation is bloated but still has useful structure. Doesn't
   help when the structure itself has degraded — eventually you're just
   re-summarizing your own summaries faster.
2. **User-driven new chat.** The user clicks "New Chat" in the UI and
   manually copies relevant context into the new session. This is what
   the workflow degrades to without `new_task`. Costs the user attention
   and risks losing context the user doesn't realize matters (active
   skill, partial progress, sub-decisions made several turns ago).
3. **Live with degraded context.** The LLM keeps running with summary-of-
   summaries, gets things subtly wrong, the user notices, has to course-
   correct. The session technically survives but quality drops.

## The naming-collision risk

This tool fires rarely (compaction handles most cases) but when it fires
the cost of mis-firing is high (entire session destroyed). And the
naming overlap with `task_create` is exactly the LLM-confusion shape
that mistakes are most expensive in: the LLM thinks it's adding a TODO
and instead nukes the session.

A defensive prompt-engineering tweak — adding "NOT for adding a TODO
item — that is task_create. This tool ENDS the current session" to the
LLM-facing description — would probably reduce the mis-fire rate without
changing the tool's actual contract. Out of scope for the documentation
pass; flagged in the audit notes.

## Verdict reasoning

NORMAL keep. Cline ships it; the fail-mode it covers (summary-of-
summaries decay) is real; the implementation is small. The drop case is
real but weak: this codebase's `ContextManager` Stage 3 is more
aggressive than Cline's, so the niche is narrower here than it would be
in a stock Cline port. Until we have telemetry on how often LLMs
actually invoke `new_task` in practice (vs how often compaction
handles things), keep wins on inertia.

If telemetry shows `new_task` firing < ~1 per 100 long sessions, the
~100 lines of code + the schema slot + the naming-collision risk become
hard to justify, and the drop verdict gets stronger. If it fires more
than that, keep is correct.
