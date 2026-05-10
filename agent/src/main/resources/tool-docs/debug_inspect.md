# `debug_inspect` — extended notes

## Why this is a meta-tool (same rationale as `debug_step`)

Nine distinct debugger inspection and mutation operations are bundled into one schema
entry because the schema budget is the largest single constraint on agent quality. Each
top-level tool definition costs ~150–300 tokens in the system prompt. Nine siblings
would cost ~2000+ tokens every iteration, even when the LLM is not debugging.

The action-enum pattern amortizes this: one tool, one enum param, nine verbs in the
description. The LLM picks the verb in the same call where it picks the tool.

## The state-tag protocol (`[SUSPENDED]` vs `[ANY]`)

The tool description tags each action with `[SUSPENDED]` (must be paused) or `[ANY]`
(works regardless). This maps to two session-resolution helpers:

- `requireSuspendedSession` — used by evaluate, get_stack_frames, get_variables,
  set_value, memory_view, force_return, drop_frame. Returns `Failed` for a running session.
- `requireSession` — used by thread_dump and hotswap. Accepts running or paused.

Both helpers delegate to `IdeStateProbe.debugState()` so agent-started and user-started
sessions are unified (see "Debug session unification" in `agent/CLAUDE.md`).

## Session resolution: why `IdeStateProbe` not our registry alone

When the user clicks the gutter Debug button, IntelliJ creates an `XDebugSession`
directly in the platform. Our agent's own session registry (`AgentDebugController`)
doesn't see those sessions. `IdeStateProbe.debugState()` consults both the agent
registry (so agent-started sessions keep their assigned id and `activeSessionId`
semantics) and the platform's `XDebuggerManager.currentSession`. This means the LLM can
inspect a session the user started, without first needing to claim or register it.

## Evaluate: the 10-second cap and why it matters

`withTimeoutOrNull(10_000L)` wraps every evaluate call. This prevents the agent loop
from hanging indefinitely on expressions that:
- Call blocking I/O (e.g., `socket.read()`)
- Acquire a lock held by another thread
- Enter an infinite loop (e.g., `while(true) { }`)

The cap is visible in the tool result: `'Expression evaluation timed out after 10
seconds.'` The LLM should not retry with the same expression — it should either simplify
the expression or use `get_variables` / `get_stack_frames` to inspect state indirectly.

## Spill wiring: which actions produce large output

Three actions auto-spill via `spillOrFormat` when output exceeds 30K characters:

| Action | Spills? | Reason |
|--------|---------|--------|
| evaluate | Never | Output is a single value — always small |
| get_stack_frames | No | Bounded by max_frames cap (50) |
| get_variables | Yes | Deep object trees can be very large |
| set_value | No | Confirmation message is always small |
| thread_dump | Yes | All threads × all frames can be large |
| memory_view | Yes | Per-instance detail on large object graphs |
| hotswap | No | Status enum + one-liner explanation |
| force_return | No | Confirmation message is always small |
| drop_frame | No | Confirmation message is always small |

When output is spilled, the LLM receives a preview (head 20 + tail 10 lines) and a
file path. It can use `read_file` to retrieve the full content or `search_code` to
search it.

## set_value: XValueModifier vs evaluate fallback

`set_value` uses a two-path strategy:

1. **Primary: XValueModifier** — finds the `XValue` for the named variable in the
   frame's children and calls `modifier.setValue(expression, callback)` from EDT (as
   the API requires). This is the correct IntelliJ API and works for all standard
   local variables.

2. **Fallback: evaluate-with-assignment** — if the variable has no `XValue` in the
   frame children, or the `XValue` has no modifier (computed properties, watch
   expressions), falls back to evaluating `"variableName = newValue"` as an expression.
   This works in Java (assignment is an expression) but is unreliable for Kotlin `val`
   locals and `final` fields.

The result always includes which method was used:
- `Method: XValueModifier (direct)` — preferred path
- `Method: evaluate fallback (assignment expression)` — fallback path

The LLM should treat fallback results with less confidence — the variable may not have
actually changed if the expression form failed silently.

## hotswap: structural changes fail silently then loudly

JVM HotSwap (JDWP `redefineClasses`) only supports **method-body changes**. Adding or
removing methods, changing field signatures, altering class hierarchy, or adding/removing
annotations all cause the redefine to fail.

`HotSwapStatusListener.onFailure()` reports `status = "failure"` — the tool message
says "Check for structural changes." Structural limitations are a JVM constraint, not an
IntelliJ one. There is no workaround short of a full session restart.

IntelliJ's HotSwapUI is invoked via the abstract API (`HotSwapUI.getInstance(project)`)
rather than via `HotSwapUIImpl` to avoid casting to an internal class — the implementation
class is not part of the stable API surface.

## force_return: type inference rules

When `return_type=auto` (the default), the tool infers the JDI mirror type from the
`return_value` string using `inferReturnType()`:

| Value string | Inferred type |
|---|---|
| null | null |
| `return_value == null` (omitted) | void |
| "true" or "false" | boolean |
| starts+ends with `"` | string |
| contains `.` | double |
| parseable as Long, in Int range | int |
| parseable as Long, outside Int range | long |
| anything else | string |

If auto-inference picks the wrong type, pass `return_type` explicitly. Mismatches
produce `InvalidTypeException` with a clear error message.

## drop_frame: what "rewind" actually means

`ThreadReference.popFrames(frame)` moves the program counter back to the start of the
method at `frame_index`. What it does **not** do:

- Reset local variables to their pre-method-entry values
- Undo any side effects the method already caused (file writes, DB inserts, outgoing
  HTTP calls, etc.)
- Restore static fields or heap objects the method has already mutated

This means drop_frame is safe only for methods whose body is purely computational (no
side effects). Using it on a method that already wrote to a database will leave the DB
in a half-written state that doesn't match the re-executed code path.

The tool result explicitly states: `"Note: Variable state is NOT reset. Side effects
are NOT undone."` — the LLM must not ignore this.

## Python session detection via reflection

Both `hotswap` and `drop_frame` reject Python debug sessions early. The detection uses
reflection to inspect the `XDebugProcess` class hierarchy:

```kotlin
private fun isPythonDebugSession(session: XDebugSession): Boolean =
    try {
        val processClass = session.debugProcess.javaClass
        processClass.simpleName.startsWith("Py") ||
            processClass.name.contains("pydevd", ignoreCase = true) ||
            processClass.name.startsWith("com.jetbrains.python") ||
            processClass.name.startsWith("com.intellij.python")
    } catch (_: Exception) { false }
```

This avoids a compile-time dependency on the Python plugin JARs (`:agent` module must
compile even when the Python plugin is absent). The pattern is the same used by the
platform's own Python-optional code paths.

## memory_view: canGetInstanceInfo availability

`VirtualMachine.instanceCounts()` requires the VM to advertise `canGetInstanceInfo=true`.
This capability is:

- **Available**: HotSpot JDK 8+ local processes, JDK 21 JBR (used by IntelliJ itself)
- **Unavailable**: Remote JDWP connections over a network (capability is not exposed
  remotely by default in OpenJDK), GraalVM native image mode, some non-OpenJDK JVMs

When the capability is missing the tool returns a structured error before hitting the
API, rather than crashing with a JDI `UnsupportedOperationException`.

## Relationship to `debug_step` and `debug_breakpoints`

The three debug tools form a complete workflow:

```
debug_breakpoints(add_breakpoint) → set a pause condition
debug_step(resume) → let execution proceed until breakpoint
debug_step(get_state) → confirm paused, get session_id
debug_inspect(get_variables) → read local state
debug_inspect(evaluate) → query specific values
debug_inspect(set_value) → mutate state for hypothesis test
debug_step(step_over) → advance one line
debug_inspect(evaluate) → verify state after advance
debug_inspect(hotswap) → after source edit, reload changed class
debug_step(resume) → continue to next breakpoint
```

None of the three tools encroaches on the others' domain:
- `debug_breakpoints` — set/remove/list breakpoints (not in `debug_inspect`)
- `debug_step` — session lifecycle (start, step, resume, stop) (not in `debug_inspect`)
- `debug_inspect` — inspection and mutation of live runtime state (not in either sibling)

## Drop-decision summary

Net verdict: **keep all 9 actions**. Brief note on the weaker candidates:

| Action | Strength | Drop case |
|--------|----------|-----------|
| evaluate | STRONG | None — most-used action |
| get_variables | STRONG | None |
| get_stack_frames | STRONG | None |
| set_value | STRONG | None — only mutation action for locals |
| thread_dump | STRONG | None — get_stack_frames doesn't cover all threads |
| hotswap | STRONG | None — critical for slow-starting applications |
| force_return | STRONG | None — unique hypothesis-testing primitive |
| memory_view | NORMAL | Remote VM limitation makes it unreliable; narrow use case |
| drop_frame | NORMAL | Narrow use case + state-not-reset footgun |

`memory_view` and `drop_frame` are the closest drop candidates, but both map to
distinct JVM APIs not available via any other surface, and both have real workflows
(heap leak detection and overstepped-debugger recovery). Keeping both.
