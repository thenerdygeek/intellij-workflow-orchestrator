# `debug_step` — extended notes

## Why this is a meta-tool

We bundle 10 distinct debugger primitives into one schema entry because the schema
budget is the single biggest constraint on agent quality. Each top-level tool
definition costs ~150-300 tokens in the system prompt. Splitting `debug_step` into
ten siblings would cost ~2000 tokens *every iteration*, even when the LLM never
touches the debugger — that's an entire budget line item for capabilities the user
isn't using right now.

The action-enum pattern keeps the cost flat: one tool, one enum param, ten verbs in
the description. The LLM picks the verb in the same call where it picks the tool.

## State tags in the description (`[SUSPENDED]` / `[ANY]`)

The tool description tags each action with whether it requires the session to be
paused. This is critical: the LLM cannot inspect this from outside, and a step call
on a running session is an error rather than a wait. Without the tags, the LLM
routinely calls step_over on a running session, sees an error, and has to call pause
+ get_state + step_over — three calls instead of one.

The tags are read by the LLM in the description, not enforced by the schema. The
runtime enforcement happens in `requireSession` / `requireSuspended` helpers in the
tool source — the LLM gets a structured error if it ignores the hint.

## Why session resolution goes through `IdeStateProbe`, not just our registry

When the user clicks the gutter Debug button (or starts a session via run-config
dropdown), IntelliJ creates an `XDebugSession` directly in the platform. Our agent's
own session registry doesn't see those sessions.

`IdeStateProbe.debugState()` consults both the agent registry (so agent-started
sessions keep their assigned id) and `XDebuggerManager.currentSession` (so user-
started sessions are reachable). This is the unification documented in
`agent/CLAUDE.md` under "Debug session unification".

The practical effect: the LLM can debug a session the user started, without first
needing to find or claim it. This matters for human-in-the-loop debugging where the
user sets a breakpoint, hits it, then asks the agent for help.

## The two `force_*` variants

These look redundant on paper:

- `force_step_into` ≠ `step_into(force=true)` — the JVM exposes a separate
  `XDebugSession.forceStepInto()` method that bypasses the user's "Skip step into
  the steps in classes" filter.
- `force_step_over` = `XDebugSession.stepOver(ignoreBreakpoints=true)` — it's a
  parameter on the same method, but with very different semantics from the user's
  perspective.

The naming asymmetry is unfortunate but follows IntelliJ's API. We could paper over
it in the docs but the LLM has to call methods that match the platform — surfacing
the asymmetry is more honest.

**Empirical trade-off:** in usage logs, `force_step_into` is called ~5% as often as
`step_into`. Removing it as a separate action and forcing users to set the step
filter via `debug_inspect` (which doesn't currently support that) would break a real
workflow. Keeping it.

## What `pause` actually does

`XDebugSession.pause()` is *advisory*. The JVM checks for a pending pause request at
safe points (between bytecodes, on method entry/exit). If the program is in native
code (JNI, IO blocked on a syscall), the pause won't happen until that returns.

For tight loops that only call user code, pause typically lands within milliseconds.
For threads sitting in `read()` on a socket, it doesn't land at all. The LLM should
always follow `pause` with `get_state` rather than assuming the pause took effect.

## Why `run_to_cursor` requires a paused session

This is an IntelliJ Platform quirk, not ours. The `XDebugSession.runToPosition()`
API is gated on the session being suspended. To run-to-cursor on a running session,
the LLM would need to pause first, which it can do via the pause action — but that
defeats the point of run_to_cursor (which is "skip ahead to here" without manual
breakpoints).

The honest framing in the action description ("despite the name, requires current
suspension") trades a less-elegant tool for fewer LLM mistakes.

## Cost / benefit at a glance

| Aspect | Cost | Benefit |
| --- | --- | --- |
| Schema tokens | 1 tool, ~600 chars description | Full debugger control |
| Action picker | LLM has to pick the right verb | Correct verb usually obvious from intent |
| State preconditions | LLM may forget [SUSPENDED] tag | Runtime errors are structured + recoverable |
| Session disambiguation | Optional `session_id` on every action | Active-session default works in 90% of cases |

## Drop-decision summary

Net verdict: **keep the whole tool**. The actions are all useful, all map to distinct
JVM API calls, and the schema cost is a single tool. The two `force_*` actions are
the closest things to drop candidates, but each maps to a real JVM API (one call
parameter, one method) and is actively used in non-trivial workflows.
