# Background Process Management — Design

**Date:** 2026-04-23
**Status:** Approved (design); implementation plan TBD
**Module:** `:agent`
**Branch:** `feature/telemetry-and-logging` (work will likely split to a new branch)

## Problem

`run_command` currently blocks the LLM's ReAct loop in a monitor loop until the process exits, times out, or goes idle (15s / 60s build). For use cases where the LLM needs the command to *start* but not *finish* before it continues — e.g., issuing a `curl` that will hit a breakpoint the LLM then wants to inspect via debug tools, or launching a long-running build while doing other work — this blocking behavior deadlocks the workflow.

Today's `[IDLE]` escape returns a `process_id` and lets the LLM use `send_stdin` / `kill_process` / `ask_user_input`, but there is **no read-only lifecycle tool** to poll, attach, or retrieve output of a running process. The LLM has no way to come back to a detached process later.

There is also a deeper issue with the current IDLE behavior: every `run_command` can *silently morph* into a detached process at 15s, regardless of whether the LLM wanted that. Returning `[IDLE]` unlocks the LLM even in cases where it wanted the command to finish. This design removes the implicit detach entirely — detach becomes an explicit launch-time choice (`background: true`), and foreground runs get a new idle policy (`on_idle: notify | wait`) that preserves the blocking contract while still surfacing idle as information.

## Goals

1. Let any tool support "run in background" via a stable contract, not a bespoke path.
2. Give the LLM **one management surface** (`background_process`) for status, output, attach, send stdin, and kill — regardless of which tool launched the process.
3. Guarantee that background processes are **session-scoped**: only visible/controllable from the session that spawned them; always killed on session transitions.
4. Never fire-and-forget. Every completion is delivered to the owning session, even if the LLM already called `attempt_completion`.
5. Prevent runaway auto-resume loops with hard guardrails.
6. Remove the implicit IDLE-detach: a process enters the background pool **only** on explicit launch-time request (`background: true`). Foreground runs stay foreground, with idle surfaced as information via `on_idle: notify | wait`.
7. Upgrade idle reporting from a binary "idle / not idle" signal to a classified one (`LikelyPasswordPrompt`, `LikelyStdinPrompt`, `GenericIdle`), so the LLM can reason about *why* a process looks stuck.

## Non-goals

- Wiring tools other than `run_command` into the background contract (v1 only implements the `run_command` path; the interface is designed for future use).
- Cross-session process sharing.
- Background processes that survive IDE restart.
- Surviving background processes across session transitions (user navigates away → process is killed by design).

---

## Architecture

### Components

```
AgentController
  │
  ├─ session transition hooks (_showSession, newChat, _deleteSession)
  │       → BackgroundPool.killAll(leavingSessionId)  (with confirmation dialog)
  │
AgentService
  │
  ├─ autoResumeForBackgroundCompletion(sessionId, completion)
  │       → fires on BackgroundCompletionEvent when loop is idle
  │       → guarded by cooldown, cap, budget, user setting
  │
  └─ newRunInvocation(...)         (unchanged, for run-configs/tests/coverage)

BackgroundPool  (new — @Service(Service.Level.PROJECT))
  │
  ├─ ConcurrentHashMap<SessionId, SessionPool>
  │
  ├─ SessionPool
  │     ├─ ConcurrentHashMap<BgId, BackgroundHandle>
  │     ├─ pendingCompletions: ConcurrentLinkedQueue<BackgroundCompletionEvent>
  │     ├─ autoWakeCount: AtomicInteger
  │     ├─ lastAutoWakeAt: AtomicLong
  │     └─ mutex: kotlinx.coroutines.sync.Mutex
  │
  └─ on handle exit:
        1. append to pool.pendingCompletions
        2. persist to pending_completions.json (atomic)
        3. if loop active → steering-queue injection (per-iteration drain)
        4. if loop idle → AgentService.autoResumeForBackgroundCompletion()

BackgroundHandle  (new interface)
  │
  ├─ val bgId: String
  ├─ val kind: String                          // "run_command" | future kinds
  ├─ val label: String                         // command text, or logical description
  ├─ val sessionId: String
  ├─ val startedAt: Long
  │
  ├─ fun state(): BackgroundState              // RUNNING | EXITED | KILLED | TIMED_OUT
  ├─ fun exitCode(): Int?
  ├─ fun runtimeMs(): Long
  ├─ fun outputBytes(): Long
  ├─ fun readOutput(sinceOffset: Long = 0, tailLines: Int? = null): OutputChunk
  ├─ fun sendStdin(input: String): Boolean     // optional; default throws UNSUPPORTED_FOR_KIND
  ├─ suspend fun attach(timeoutMs: Long): AttachResult // re-enters the monitor loop pattern
  ├─ fun kill(): Boolean                       // graceful two-phase kill (idempotent)
  └─ fun onComplete(callback: (event: BackgroundCompletionEvent) -> Unit)

BackgroundCapable  (new marker interface — tools implement this)
  │
  └─ suspend fun launchBackground(
         sessionId: String,
         params: JsonObject,
         project: Project,
     ): String?   // returns the bgId, or null on pre-spawn rejection

  // v1 implementer: RunCommandTool
  // Future implementers: SpringTool, JavaRuntimeExecTool, BambooTool, etc.
```

### Key invariants

- `BackgroundPool` is a **project-level service**. It is NOT parented to `SessionDisposableHolder`, because `SessionDisposableHolder.resetSession()` fires on stop/attempt_completion — events that should *not* kill backgrounds.
- `BackgroundPool` is parented to the project `Disposable`, so IDE close / project close cascades kill all pools.
- `SessionPool` is keyed by `sessionId`. Only that session's `background_process` tool can see it.
- `BgId` format: `bg_<8-hex-chars>` (e.g., `bg_a1b2c3d4`). Generated via `UUID.randomUUID().toString().substring(0, 8)`.
- `BackgroundHandle` wraps the native handle (v1: `ProcessRegistry.ManagedProcess`). Future kinds may wrap `XDebugSession`, `RunContentDescriptor`, HTTP handles, etc. The contract is handle-agnostic.

---

## `run_command` changes

Add two parameters: `background` (the launch-mode flag) and `on_idle` (the foreground idle policy).

```jsonc
{
  "background": {
    "type": "boolean",
    "description": "When true, the command starts in the background and returns immediately with a bgId. Use background_process to monitor, read output, attach, or kill. When false (default), the command runs synchronously in the monitor loop.",
    "default": false
  },
  "on_idle": {
    "type": "string",
    "enum": ["wait", "notify"],
    "default": "notify",
    "description": "Foreground-only (ignored when background=true). Controls what happens when the process produces no output for idle_timeout seconds. 'notify' emits an inline idle signal (with classification) and keeps waiting. 'wait' ignores idle entirely and blocks until exit or total timeout."
  }
}
```

### Foreground vs. background — the one-param rule

The LLM picks launch mode with **one flag**: `background`. Everything else follows:

| Launch | On idle threshold | Enters `BackgroundPool`? | `bgId` issued? |
|---|---|---|---|
| `background: true` | N/A — no monitor loop | Yes, at launch | Yes, returned in tool result |
| `background: false, on_idle: notify` *(default)* | Inline stream note with classification; tool keeps waiting | No — stays in `ProcessRegistry` only | No |
| `background: false, on_idle: wait` | Ignored entirely | No | No |

**Hard invariant:** a process enters `BackgroundPool` **only via `background: true` at launch**. Foreground runs never auto-detach. (A future v1.1 user-driven mid-tool "Detach" button can promote a foreground `notify` run into the pool at the user's request; that is a deferred UX affordance, not an LLM-facing policy.)

Total timeout (`timeout` param, default 120s / max 600s) remains the safety net for foreground runs under both idle policies — a genuinely hung process is still killed by total timeout, not by idle detection.

### Behavior when `background: true`

1. Pre-spawn checks run exactly as today: shell resolution, `DefaultCommandFilter`, env filter, `PathValidator`, working-dir validation. **Pre-spawn errors fail synchronously** with an error `ToolResult` and no `bgId` is returned.
2. Spawn via existing `GeneralCommandLine` path.
3. Register in `ProcessRegistry` (so existing `kill_process` / `send_stdin` continue to work).
4. Wrap the `ManagedProcess` in a `RunCommandBackgroundHandle` and register in `BackgroundPool.forSession(sessionId)`.
5. Capture a **500ms initial-output grace period** — reader thread runs, up to 500ms passes (or the first chunk arrives + 200ms settling) — so the LLM sees whether the process errored immediately vs. genuinely backgrounded.
6. Return:
   ```
   Started in background: bg_a1b2c3d4 (state: RUNNING)
   Command: curl -sS http://localhost:8080/users/42
   Initial output (first 500ms):
     <tail of captured chunks, or "(no output yet)">

   Use background_process to check status, read output, attach, or kill.
   On completion you will automatically receive a system message.
   ```
7. Return token estimate scaled to the initial output; `ToolResult.isError = false`.

Timeout semantics: when `background: true`, the `timeout` param is interpreted as the **background process's total wall-clock lifetime cap**, defaulting to `null` (unbounded within session lifetime). If set, pool fires a `TIMED_OUT` completion when exceeded.

### Behavior when `background: false`

Same spawn path, shell resolution, safety filters, env handling, reader thread, and monitor loop as today. The only changes are in the idle branch:

- **Idle classification always runs** on the first idle-threshold crossing via `PromptHeuristics.classify(tail)` (see "Idle classification" below). Cheap, no LLM calls in v1.
- **`on_idle: notify`** — the classified signal is emitted via the stream callback as an inline note ("⏳ Process idle for 15s — LIKELY_STDIN_PROMPT: '? Project name:'. Still waiting."). Monitor loop resets the idle-check clock and keeps polling. The process is **not** registered in `BackgroundPool`, no `bgId`, no detach. The final `ToolResult` returned when the process exits (or total timeout fires) is unchanged from today's shape.
- **`on_idle: wait`** — idle threshold is ignored entirely; no classification runs, no inline note emitted. Tool blocks until exit or total timeout.

The legacy "automatic `[IDLE]` detach after 15s" behavior is **removed**. A process now detaches only if the LLM explicitly asked with `background: true` at launch.

### Approval gate

`run_command` is hardcoded to `ALWAYS_PER_INVOCATION`. That stays. Launching in background is still a write op and still requires per-invocation approval. Approving a background launch only approves *the launch* — subsequent `background_process` reads are read-only (no approval), but `background_process` action = kill / send_stdin / attach inherit the write-tool gate (see "Approval mapping" below).

### Idle classification (v1: regex; v1.1: Haiku tier 2)

When the idle threshold fires in a foreground `on_idle: notify` run, the tail output is classified. Goal: tell the LLM *why* the process looks idle, not just that it is.

**v1 — tier 1 only (regex + heuristics, free, <1ms):**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/PromptHeuristics.kt
object PromptHeuristics {
    fun classify(tail: String): IdleClassification
}

sealed class IdleClassification {
    data class LikelyPasswordPrompt(val promptText: String) : IdleClassification()
    data class LikelyStdinPrompt(val promptText: String) : IdleClassification()
    data object GenericIdle : IdleClassification()
}
```

- **`LikelyPasswordPrompt`** — existing `ShellResolver.isLikelyPasswordPrompt` detector absorbed into tier 1. Message redirects LLM to `ask_user_input`, not `send_stdin`.
- **`LikelyStdinPrompt`** — tail ends with prompt-shaped characters (`?`, `:`, `>`, `»`, `[y/N]`, `(Y/n)`) with no trailing newline, OR matches a known framework prompt regex library (vite/npm/create-next-app/pip confirm/etc.). Message includes the prompt text verbatim and suggests `send_stdin`.
- **`GenericIdle`** — everything else. Message is honestly labeled: "The process may be waiting for stdin, running slowly, or stuck. Cause unknown."

**v1 message format (example under `notify` with `LikelyStdinPrompt`):**

```
⏳ Process idle for 15s — LIKELY_STDIN_PROMPT (source: regex)
Prompt: "? Project name: › vite-project"
Still waiting — set on_idle: wait to suppress this signal, or call
kill_process if the prompt is unexpected.
```

**v1.1 — tier 2 (Haiku LLM classifier), deferred:**

When tier 1 returns LOW confidence (`GenericIdle` or a weak-signal `LikelyStdinPrompt`) **and** `idle_timeout ≥ 5s` **and** the user has not disabled it, send the last ~40 lines to a cheap model with a tight prompt asking `waiting_for_stdin | running_slow | stuck | unknown` plus a confidence score and reason. Replace the tier 1 label with the LLM's verdict. Cache by tail-output hash. Hard timeout 3s; on timeout, fall back to tier 1 label. Not in v1.

**Existing infrastructure to reuse — do not reinvent.**

The plugin already has two working precedents for small-LLM-calls-during-agent-work that tier 2 should match structurally:

| Reference | What it does | What tier 2 reuses |
|---|---|---|
| `core/src/main/kotlin/.../ai/ModelCache.kt::pickCheapestModel()` | Picks Haiku > Sonnet > anything from the Sourcegraph `/api/llm/models` response. Handles Haiku unavailability by falling back to Sonnet. | Model selection — no new setting for "which model to use." |
| `agent/src/main/kotlin/.../observability/HaikuPhraseGenerator.kt` | Generates funny working-indicator phrases every 30s during agent execution. Async, non-blocking, cancellable, graceful on failure. | Pattern template — tier 2 classifier copies this shape almost exactly (different prompt, same async/cancel/fallback plumbing). |
| `AgentController.kt:1169` — Haiku-generated session title | Async title generation once per session. Non-blocking; falls back to default title on failure. | Second precedent confirming small-LLM calls are an established pattern in this module. |

**Implication:** `ModelFallbackManager`'s exclusion of Haiku from the main agent fallback chain (Opus thinking → Opus → Sonnet thinking → Sonnet) is deliberate — Haiku is wrong for tool-use reasoning. But `ModelCache.pickCheapestModel()` is a separate, existing path specifically for *lightweight tasks*. Tier 2 idle classification is a lightweight task. It goes through `pickCheapestModel()`, not `ModelFallbackManager`. No new "Haiku exception" is carved; the pattern is already there and in use.

**Settings — v1 adds none.** Classification is always on via tier 1. v1.1 adds **four** settings (not five — `idleClassifierModel` is dropped because `ModelCache.pickCheapestModel()` is authoritative):

- `idleClassifierMode: String = "smart"` — `regex` / `smart` / `always_llm` / `off`
- `idleClassifierCacheEnabled: Boolean = true`
- `idleClassifierTimeoutMs: Long = 3_000`
- `idleClassifierSkipBelowIdleSeconds: Int = 5`

---

## New tool: `background_process`

### Schema

```jsonc
{
  "name": "background_process",
  "description": "Manage background processes spawned in this session. With no args, lists all background processes. With an id and no action, returns status. With an action, performs the operation. Background processes are session-scoped — killed on session transitions.",
  "parameters": {
    "id": {
      "type": "string",
      "description": "Background process ID (e.g., bg_a1b2c3d4). Omit to list all."
    },
    "action": {
      "type": "string",
      "enum": ["status", "output", "attach", "send_stdin", "kill"],
      "description": "Operation to perform. If omitted and id is provided, defaults to status."
    },
    // action-specific params below are applied only when the action matches
    "tail_lines": { "type": "integer", "description": "[output] Return last N lines." },
    "since_offset": { "type": "integer", "description": "[output] Return bytes after this offset." },
    "grep_pattern": { "type": "string", "description": "[output] Filter output lines matching this regex." },
    "output_file": { "type": "boolean", "description": "[output] When true, writes full output to disk and returns a preview + path." },
    "input": { "type": "string", "description": "[send_stdin] Text to send. Include \\n for Enter." },
    "timeout_seconds": { "type": "integer", "description": "[attach] Max seconds to wait in monitor loop. Default: 600, Max: 600." }
  },
  "required": []
}
```

### Action behavior

| Args | Behavior |
|---|---|
| `{}` | **List.** Returns table: `bgId | kind | label | state | runtime | output_bytes | exit_code?`. Empty list returns "No background processes in this session." |
| `{id}` | Shortcut for `{id, action: status}`. |
| `{id, action: status}` | State, runtime, exit code if done, output size, last 5 lines, started/exited timestamps. |
| `{id, action: output, ...}` | Reads collected output. Obeys spill + grep contract shared with other high-volume tools (`OUTPUT_FILTERABLE_TOOLS`, `ToolOutputSpiller`). |
| `{id, action: attach, timeout_seconds?}` | Re-enters the blocking monitor loop used by sync `run_command`. Exits on process exit, idle (from `AgentSettings`), or timeout. Returns same result shape as sync `run_command`. **Cap: 600s**, override via param. |
| `{id, action: send_stdin, input}` | Delegates to handle. If handle reports `UNSUPPORTED_FOR_KIND`, returns error. Password prompt detection still runs (reuses `ShellResolver.isLikelyPasswordPrompt`). |
| `{id, action: kill}` | Graceful two-phase kill via handle (SIGTERM → 5s → SIGKILL + PID-tree cleanup). **Only entry point for kill in v1** — the standalone `kill_process` tool is retired in this release. Consumes pending completion for this bgId (no auto-wake fires after explicit kill). |

### Approval mapping

| Action | Approval policy |
|---|---|
| (list, status, output) | No approval — read-only, parallelizable |
| `send_stdin`, `kill` | `ALWAYS_PER_INVOCATION` — same as direct `send_stdin` / `kill_process` today |
| `attach` | `ALLOW_FOR_SESSION` — user can grant once per session (blocking behavior is not destructive) |

### `send_stdin` standalone tool — ID namespace unification

The existing `send_stdin` tool currently accepts `process_id`. It will be extended to accept a `bgId` in the same namespace. No new schema field — the `process_id` parameter resolves against both `ProcessRegistry` (the live `Process` record for any running process, including background ones) and `BackgroundPool` (session-scoped handle lookup). One tool, one ID space.

`send_stdin` is now the only *standalone* tool in the process-management area — `kill_process` is removed in this release (see "Removed tools" below). Kill is exclusively via `background_process(action=kill)`.

Note: post-v1, `run_command` no longer auto-emits `[IDLE]` messages with `process_id` on foreground runs (that behavior is removed). The `process_id` namespace is effectively `bgId`-only in the new flow — the parameter name is kept for backward compatibility with `SendStdinTool`'s own post-stdin IDLE messages, which continue to use `process_id` when *they* go idle after sending input to a background process's stdin. Those IDLE messages' "Options:" guidance is updated to suggest `background_process(action=kill)` instead of the retired `kill_process` tool.

## Removed tools

- **`kill_process` standalone tool** — retired in this release. Kill functionality is consolidated into `background_process(id, action=kill)` so there is exactly one tool (and one schema entry in the LLM's tool list) for destructive process termination. Rationale: with `bgId` being the single canonical ID for any managed process, a separate kill tool became redundant — one primitive, one entry point.

### Full removal sweep (concrete task list)

Every `kill_process` / `KillProcessTool` reference currently in the repo, grouped by file type. All must be addressed in the implementation commit — leaving any reference behind means the LLM sees conflicting guidance or the build breaks on snapshot diffs.

**Production source (`agent/src/main/kotlin/.../`):**

| File | Change |
|---|---|
| `tools/builtin/KillProcessTool.kt` | **Delete entire file.** |
| `AgentService.kt` (line ~723) | Remove `safeRegisterDeferred("Utilities") { KillProcessTool() }`. (`kill_process` is currently a deferred tool — see "Tool registration tier" below for the core-vs-deferred treatment of the new tools.) |
| `tools/builtin/ProcessToolHelpers.kt` (line ~51) | `buildIdleContent` "Options:" footer: replace `kill_process(process_id="$processId")` with `background_process(id="$processId", action="kill")`. |
| `tools/builtin/RunCommandTool.kt` (line ~38) | Tool description text: remove the phrase "use send_stdin, ask_user_input, or kill_process to interact with it" (also rewrite for the new `background: true` / `on_idle` behavior described elsewhere in this spec). |
| `tools/builtin/RunCommandTool.kt` (line ~471) | Idle-result footer `appendLine("- kill_process(process_id=...)` is removed — the whole `buildIdleResult` branch is being replaced by `on_idle: notify/wait` anyway (no more auto-IDLE from `run_command`). |
| `tools/builtin/SendStdinTool.kt` (line ~86) | Rate-limit error message: replace "Kill the process with kill_process and rerun…" with "Kill the process with `background_process(action=kill)` and rerun…" |
| `prompt/SystemPrompt.kt` (line ~259) | Plan mode write-tools list: remove `kill_process`. |
| `prompt/SystemPrompt.kt` (line ~377) | Capabilities hint text: replace "use kill_process to terminate" with "use `background_process(action=kill)` to terminate". |
| `prompt/SystemPrompt.kt` (line ~424) | Tool hints table "Stop a running configuration gracefully" row: replace trailing `kill_process or run_command` with `background_process(action=kill) or run_command`. |
| `loop/AgentLoop.kt` (line ~477) | `WRITE_TOOLS` set: remove `"kill_process"`. (Kill is still a write op; it's now exercised via `background_process`, so the set-membership moves accordingly if a meta-tool action-level check is needed, otherwise the `background_process` tool itself is added to `WRITE_TOOLS` via the approval-gate wiring.) |

**Resources — persona configs (`agent/src/main/resources/agents/`):**

| File | Change |
|---|---|
| `devops-engineer.md` | Remove `kill_process` from `deferred-tools:` list; add `send_stdin` only if the persona needs it (it already lists `send_stdin`, so nothing to add — just remove `kill_process`). Add `background_process` to the persona's tool allowlist (since it's a new core tool, it's implicitly available; persona YAMLs only list deferred tools they want access to, so no line is needed unless we want to restrict). Verify by regeneration. |
| `general-purpose.md` | Same pattern: remove `kill_process` from `deferred-tools:`. Keep `send_stdin` — and note its promotion from deferred to core means this line becomes redundant (see "Tool registration tier" below); remove `send_stdin` from the deferred-tools list once it's core. |
| `performance-engineer.md` | Same: remove `kill_process`; remove `send_stdin` once promoted to core. |

**Tests (`agent/src/test/kotlin/.../`):**

| File | Change |
|---|---|
| `tools/builtin/KillProcessToolTest.kt` | **Delete entire file.** |
| `tools/builtin/SpawnAgentToolTest.kt` (lines ~83, ~1177) | Tool set assertions: remove `"kill_process"` from the expected sets; add `"background_process"` where appropriate. |
| `tools/subagent/ParallelSubagentIntegrationTest.kt` (line ~76) | Same: remove `"kill_process"`, add `"background_process"`. |
| `loop/AgentServiceToolFilterTest.kt` (line ~204) | WRITE_TOOLS filter expectations: remove `"kill_process"`, add `"background_process"` (if action-level approval happens at the tool level) or adjust per the implementation's approval-gate wiring. |
| `loop/AgentLoopTest.kt` (line ~441) | Same as above. |

**Snapshot files — `agent/src/test/resources/prompt-snapshots/`:** regenerate all 7 snapshots. The affected content per snapshot file:
- Line ~60 — plan mode blocked-tools list (remove `kill_process`).
- Line ~127–135 — capabilities hint paragraph ("use kill_process to terminate" → "use `background_process(action=kill)` to terminate").
- Line ~152–162 — tool hints table "Stop a running configuration gracefully" row.

Files: `null-context.txt`, `intellij-ultimate.txt`, `intellij-community.txt`, `pycharm-professional.txt`, `pycharm-community.txt`, `webstorm.txt`, `intellij-ultimate-mixed.txt`.

**Snapshot files — `agent/src/test/resources/subagent-prompt-snapshots/`:** regenerate all 5 snapshots (same two line regions per file).

Files: `code-reviewer-intellij-ultimate.txt`, `spring-boot-engineer-intellij-ultimate.txt`, `python-engineer-pycharm-professional.txt`, `test-automator-null-context.txt`, `architect-reviewer-intellij-community.txt`.

Regeneration command (per `agent/CLAUDE.md`):

```bash
./gradlew :agent:test --tests "*SNAPSHOT*generate all golden*"
./gradlew :agent:test --tests "*SubagentSystemPromptSnapshotTest*generate all golden*"
./gradlew :agent:test --tests "*SNAPSHOT*"   # verify all pass
```

**Docs (`agent/CLAUDE.md`):**

| Line | Change |
|---|---|
| ~115 (Utilities deferred tools table row) | Remove `kill_process`; remove `send_stdin` from Utilities row (promoted to core — moved into the Core Tools table above); add `background_process` to the Core Tools table. |
| ~400 (`WRITE_TOOLS` list in "Tool Execution" section) | Remove `kill_process`; add `background_process`. |
| ~448 ("Blocked in plan mode" list) | Same: remove `kill_process`, add `background_process`. |

The Core Tools table at line ~85 needs two new rows: `background_process` and `send_stdin` (with the latter's entry removed from the Utilities deferred row).

### Persisted conversation histories

Old persisted conversation histories that reference `kill_process` by name are not rewritten. When the LLM re-enters such a session and looks at the transcript, the current tool schema will not include `kill_process`. The LLM will either adapt to `background_process(action=kill)` (expected) or report the tool doesn't exist (acceptable). No automatic migration of historical messages.

## Tool registration tier

**The background management tools go into the core tier, not deferred.** Rationale: when an auto-wake resumes a session with a `[BACKGROUND COMPLETION — AUTO-RESUMED]` synthetic message, the LLM must be able to manage the completed (or still-running) background immediately — inspecting output, attaching, killing, sending stdin — without a `tool_search` round-trip to discover the tools first. Deferred tools require the LLM to notice they exist, query for them, and wait for activation; that's an unacceptable extra iteration on a time-sensitive completion event.

### Tier assignments

| Tool | Today | v1 | Registration call |
|---|---|---|---|
| `background_process` | — (new) | **Core** | `registerCore { BackgroundProcessTool(...) }` in `AgentService.registerAllTools()`, near the existing core tool registrations (alongside `run_command`, `send_stdin`). |
| `send_stdin` | Deferred (Utilities) | **Core (promoted)** | Move from `safeRegisterDeferred("Utilities") { SendStdinTool() }` to `registerCore { SendStdinTool() }`. |
| `kill_process` | Deferred (Utilities) | **Removed entirely** | Deleted (see "Removed tools" above). |
| `ask_user_input` | Deferred (Utilities) | Deferred (unchanged) | Remains in `safeRegisterDeferred("Utilities")`. Not tied to the background workflow — may still be needed for credential prompts but unrelated to `bgId` lifecycle. |

### Visibility in the system prompt

Core tools are listed in the Capabilities section of the system prompt. After this change:
- `background_process` appears alongside `run_command` in the "Shell + process management" tool block.
- `send_stdin` moves from the deferred-tools catalog (section 6b) into the core capabilities section.
- The Utilities deferred category retains only `ask_user_input` + `project_context` + `current_time` (`kill_process` and `send_stdin` have both left).

### Approval gate wiring

`background_process` is a write tool **when the action is `kill`, `send_stdin`, or `attach`** and a read tool when the action is `status`, `output`, or the no-args list. We already handle per-action approval for other meta-tools; the pattern extends:
- `AgentLoop.WRITE_TOOLS` gains `"background_process"` — the approval gate is triggered for the tool name, and the per-action check happens inside `background_process.execute()` against the new table:

```kotlin
private val WRITE_ACTIONS = setOf("kill", "send_stdin", "attach")
// read actions (status, output, no-args list) skip approval even though
// the tool name is in WRITE_TOOLS
```

This mirrors how `debug_breakpoints` / `debug_step` / `debug_inspect` already split read vs. write at the action level.

`send_stdin` standalone tool remains in `WRITE_TOOLS` (unchanged — it's always a write op).

---

## Session scoping and kill triggers

### Kill cascade table

| Event | Kills pool? | Rationale |
|---|---|---|
| User opens different session via history | **yes** | Leaving this session; not "active" anymore. |
| New chat (reset to fresh session) | **yes** | Old session is being left. |
| Session deleted (from history context menu) | **yes** | Session no longer exists. |
| Project closed (single-project-close event) | **yes** | Project disposal; session infra gone. |
| IDE close | **yes** | Child processes of IDE JVM; will die anyway. Best-effort graceful kill. |
| Plugin disabled / uninstalled | **yes** | Project dispose cascades. |
| Agent tool window closed / minimized / hidden behind another tab | **no** | Window visibility ≠ session activity. |
| Session cancel (stop button) | **no** | Loop stopped, session still active. Pending completions still deliver. |
| `attempt_completion` fires | **no** | Loop ended, session still active. Auto-wake handles completions. |
| Loop hits max iterations / errors / context overflow | **no** | Session still active. |
| Chat cleared but session retained | **no** | Session identity unchanged. |

### Confirmation dialog

On `new chat`, `switch session`, `delete session` — if `BackgroundPool.forSession(leavingId).size() > 0`:

```
Leaving this session will kill 2 running background processes:
  • bg_a1b2c3d4 (run_command: "curl -X POST http://localhost:8080/...")
  • bg_e5f6g7h8 (run_command: "npm run dev")

Continue?                                       [Cancel] [Leave and kill]

☐ Don't ask again for this project
```

"Don't ask again" stored in `AgentSettings.suppressBackgroundKillConfirmation: Boolean` (default false).

IDE close / project close skip the dialog (best-effort, non-interactive).

### Implementation

- `AgentController._showSession(id)`:
  1. If leaving session has pool entries → show dialog (unless suppressed) → on confirm, `BackgroundPool.killAll(leavingId)`.
  2. Proceed with existing session-load flow.
- `AgentController.newChat()` — same pattern, check current session's pool before reset.
- `AgentController._deleteSession(id)` — dialog only if deleting session has pool entries; dialog text adapted ("Deleting this session will kill..."). Kill *before* directory removal.
- `ProjectManagerListener.projectClosing` — silent `BackgroundPool.killAllForProject(project)`.
- Application `Disposer` at shutdown — silent killAll across all projects.

---

## Completion loopback

Every handle exit produces a `BackgroundCompletionEvent`:

```kotlin
data class BackgroundCompletionEvent(
    val bgId: String,
    val kind: String,
    val label: String,            // command text
    val sessionId: String,
    val exitCode: Int,            // -1 if killed
    val state: BackgroundState,   // EXITED | KILLED | TIMED_OUT
    val runtimeMs: Long,
    val tailContent: String,      // last 20 lines (ANSI-stripped)
    val spillPath: String?,       // full log path if >1MB
    val occurredAt: Long,
)
```

### Delivery logic

On exit:

1. Pool appends to `SessionPool.pendingCompletions` queue **and** persists to `sessions/{sessionId}/background/pending_completions.json` atomically.
2. Pool calls `AgentService.notifyBackgroundCompletion(sessionId, event)`.
3. `AgentService` checks: is the loop for this session currently running?
   - **Yes, loop active** → event goes to the steering-message queue (`AgentLoop.steeringQueue`). Drained at the next iteration boundary. **One system message per event** (per user decision #3). Format:
     ```
     [BACKGROUND COMPLETION]
     Process bg_a1b2c3d4 (run_command: "curl -sS http://localhost:8080/users/42")
     State: EXITED, exit code: 0, runtime: 4.2s
     Output (tail 20 lines):
       <tail>
     <spill path line if spilled>
     ```
   - **No, loop idle** → `autoResumeForBackgroundCompletion(sessionId, event)` — see auto-wake below.
4. Once the LLM's next iteration consumes the event, pool removes it from `pendingCompletions.json`. Persistence is just for crash safety, not primary delivery.

### Persistence shape

```json
// sessions/{sessionId}/background/pending_completions.json
[
  {
    "bgId": "bg_a1b2c3d4",
    "kind": "run_command",
    "label": "curl -sS http://localhost:8080/users/42",
    "exitCode": 0,
    "state": "EXITED",
    "runtimeMs": 4231,
    "tailContent": "...",
    "spillPath": null,
    "occurredAt": 1745395200000
  }
]
```

---

## Auto-wake

Triggered when a completion event arrives for a session whose loop is not currently iterating.

### Flow

1. `AgentService.autoResumeForBackgroundCompletion(sessionId, event)` entered.
2. **Guard checks** (short-circuit to "persist only" if any fail):
   - `AgentSettings.autoWakeOnBackgroundCompletion == true` (default true).
   - `sessionPool.autoWakeCount.get() < MAX_AUTO_WAKES` (default 10).
   - `now - sessionPool.lastAutoWakeAt.get() > COOLDOWN_MS` (default 5000ms).
   - `lastRunIterationsUsed < 180` (of 200 max) — don't auto-wake if the prior run nearly exhausted budget.
   - No currently-running auto-wake for this session (mutex).
3. Acquire `SessionLock` (`.lock` file via `java.nio.channels.FileLock`). If already held (user actively typing in this session?), abort auto-wake — completion stays persisted, delivered when the user next interacts.
4. Build the synthetic user message:
   ```
   [BACKGROUND COMPLETION — AUTO-RESUMED]
   Your previous turn ended, but a background process just completed:

   Process bg_a1b2c3d4 (run_command: "curl -sS http://localhost:8080/users/42")
   State: EXITED, exit code: 0, runtime: 4.2s
   Output (tail 20 lines):
     <tail>
   <spill path line if spilled>

   Decide whether this needs action. If it completes the original task or
   requires no follow-up, call attempt_completion. Otherwise continue working.
   ```
5. Post via existing `initiateTaskLoop(newUserContent)` path — same as `[TASK RESUMPTION]`. Loop starts fresh iteration budget.
6. Increment `autoWakeCount`, update `lastAutoWakeAt`.
7. On successful loop end (attempt_completion), auto-wake cycle closes naturally. Cooldown applies to any *next* completion event.

### UI

- Chat transcript shows the synthetic message styled as a system message (not user-bubble styled), labeled "auto-resumed from background completion".
- Toast on auto-wake: "Background {bgId} completed — session resumed."
- **Top-bar indicator** — single source of truth for background visibility. See "UI: Top-bar background indicator" below.

### When guards fail

If any auto-wake guard fails, the completion **stays in `pending_completions.json`** and is delivered via `[TASK RESUMPTION]`'s existing preamble path on the next manual user interaction. User is never silently dropped.

---

## UI: Top-bar background indicator

A visibility-only chip in the agent chat top bar. Tells the user at a glance what's running in background for the current session, without asking the LLM.

### Placement

In the existing `AgentCefPanel` top-bar region, alongside the session-title chip row and model picker. Not in the composition bar.

### Visual

- **When 0 background processes for current session:** chip is **hidden entirely**. No empty badge.
- **When ≥1:** shows `⚙ {N} background ▾`. Integer count, singular label ("background", not "backgrounds"), trailing chevron to signal expandability.
- **Color:** neutral by default (matches model/skills chips). Amber if any process is `TIMED_OUT` or exited with an undelivered error — surfaces trouble without requiring the dropdown to be opened.

### Dropdown (on click)

Small popover anchored to the chip. Closes on outside-click or `Escape`. **Read-only in v1** — no actions, just information (matches the scope the user explicitly asked for: "show which 3 tools, thats it").

Per row:
- State dot (green RUNNING / grey EXITED-pending-delivery / amber TIMED_OUT/error)
- `bgId`
- Command text, truncated at ~60 chars with ellipsis; full command in tooltip
- State label + runtime (e.g., "RUNNING · 3m 22s")

Per-row actions (kill / view output / attach) are deferred to v1.1 — the user keeps LLM-driven management via `background_process` as the only action surface in v1.

### Data wiring

- `chatStore.backgroundProcesses: BackgroundProcessSnapshot[]` (new field in `agent/webview/src/stores/chatStore.ts`).
- Hydration on session load: new Kotlin bridge `_loadBackgroundSnapshot(sessionId)` returns pool state as JSON; React renders. Matches existing `_loadSessionState` pattern.
- Live updates: `BackgroundPool` emits `WorkflowEvent.BackgroundChanged(sessionId, snapshot)` via `EventBus` on launch / state change / exit / kill. `AgentController` forwards as a `background-update` bridge event to the webview, same push pattern as existing `task-update`.
- Runtime display: computed client-side from `startedAt` (epoch ms sent from Kotlin). 1s `setInterval` re-renders only the runtime text field. Timer stops when dropdown is closed and no RUNNING processes remain.

### Session-ownership rule

The indicator reflects **only the currently-loaded session's pool**. Switching sessions shows the new session's pool (empty for fresh sessions; the leaving session's processes were just killed per the session-transition rule + confirmation dialog). No cross-session aggregate view in v1.

### Accessibility

- `Tab` focuses the chip; `Enter` / `Space` toggles the dropdown; `Escape` closes.
- `aria-label="N background processes running, click to view"` on the chip.
- State dot colors use IDE theme tokens via `bridge.colors` — work in light and dark themes.

### Component layout

- New React component: `agent/webview/src/components/chat/BackgroundIndicator.tsx`
- Consumes `chatStore.backgroundProcesses`
- Styling: existing chip-row CSS classes; popover uses shadcn `Popover` (already bundled)

---

## Data layout

```
~/.workflow-orchestrator/{proj}/agent/sessions/{sessionId}/background/
├── registry.json                # [{bgId, kind, label, pid, startedAt, state, exitCode?}]
├── pending_completions.json     # queue of completions not yet delivered to the loop
├── {bgId}.stdout.log            # spill file (only created once >1MB in-memory)
├── {bgId}.stderr.log            # if separate_stderr
└── {bgId}.completion.json       # written on exit (audit trail; retained for 7 days)
```

All writes: atomic write-then-rename (`.tmp` + `Files.move(ATOMIC_MOVE)`), per-session `kotlinx.coroutines.sync.Mutex`. Matches existing conversation/task persistence pattern.

---

## System prompt changes

New subsection appended to Capabilities (Section 5):

```markdown
## Background Processes

When `run_command` is called with `background: true`, the command starts in
the background and returns immediately with a `bgId`. The LLM's loop
continues without waiting.

To manage background processes, use the `background_process` tool:
- `background_process()` — list all background processes in this session
- `background_process(id="bg_xxx")` — status of one process
- `background_process(id="bg_xxx", action="output", tail_lines=50)` — read output
- `background_process(id="bg_xxx", action="attach")` — wait for the process to exit (re-enters the normal monitor loop)
- `background_process(id="bg_xxx", action="send_stdin", input="yes\n")` — write to the process's stdin
- `background_process(id="bg_xxx", action="kill")` — graceful termination; **only kill entry point** (no separate kill_process tool)

Background processes are session-scoped. They are automatically killed when
the user starts a new chat, switches to a different session, deletes this
session, or closes the IDE. When a background process exits, you
automatically receive a system message describing the outcome — either at
the next iteration (if the loop is still active) or as a new resumed turn
(if the loop had ended).

Typical use cases:
- Trigger an HTTP endpoint that will hit a debugger breakpoint you plan to
  inspect, so the loop isn't blocked on the request
- Start a long-running build, test, or dev server and do other work
  meanwhile
- Fire a command whose completion you'll want to react to later, not
  immediately
```

Snapshot tests will need regeneration (see `agent/src/test/resources/prompt-snapshots/`).

---

## AgentSettings additions

```kotlin
// AgentSettings.State
var concurrentBackgroundProcessesPerSession: Int = 5
var backgroundOutputSpillThresholdBytes: Long = 1_048_576   // 1 MB
var autoWakeOnBackgroundCompletion: Boolean = true
var autoWakeMaxPerSession: Int = 10
var autoWakeCooldownMs: Long = 5_000
var suppressBackgroundKillConfirmation: Boolean = false
```

Settings UI: new section in `AgentAdvancedConfigurable` or new `AgentProcessToolsConfigurable` subsection titled "Background processes".

---

## Error taxonomy

| Code | When |
|---|---|
| `NO_SUCH_ID_IN_SESSION` | `background_process(id=...)` where id isn't in this session's pool. Do not leak whether it exists in another session. |
| `MAX_CONCURRENT_REACHED` | `run_command(background: true)` when session already has 5 running. Surface as a rejected launch with suggestion to kill an existing one. |
| `UNSUPPORTED_FOR_KIND` | `send_stdin` on a handle whose kind doesn't support stdin (future tools). |
| `ATTACH_TIMEOUT` | `action: attach` elapsed without exit/idle. Returns `[ATTACH TIMEOUT] bg_xxx still RUNNING — call again or inspect via status/output.` |
| `PASSWORD_PROMPT_DETECTED` | `send_stdin` when `ShellResolver.isLikelyPasswordPrompt` matches. Redirect to `ask_user_input`. |
| `SESSION_LOCK_HELD` | Auto-wake couldn't acquire session lock. Completion stays persisted. |

---

## Threading

- **Spawn path** — runs on `Dispatchers.IO` inside the tool's `execute()`, exactly as today.
- **Reader thread** — daemon thread per process, same pattern as today. Outputs to bounded in-memory ring buffer; overflow spills to disk.
- **Exit monitor** — single supervisor coroutine per `SessionPool`, polls handles every 500ms, fires completion events. Scoped to project `CoroutineScope + SupervisorJob`.
- **Completion delivery** — posts to `AgentLoop.steeringQueue` on `Dispatchers.Default`; loop drains on its own thread at iteration boundaries.
- **Auto-wake** — launched from supervisor coroutine on `Dispatchers.IO` after acquiring session mutex.
- **UI updates** (badge, toast, confirmation dialog) — `invokeLater` / `Dispatchers.EDT`, as always for Swing.

No `runBlocking` in any Swing path. `CancellationException` always re-thrown (structured concurrency).

---

## Implementation scope v1

**In scope:**
- `BackgroundPool`, `SessionPool`, `BackgroundHandle`, `BackgroundCapable` interface.
- `RunCommandBackgroundHandle` wrapping `ManagedProcess`.
- `run_command` gets `background` and `on_idle` params. Legacy auto-IDLE-detach behavior is removed.
- New `PromptHeuristics` helper (`tools/process/PromptHeuristics.kt`) with tier 1 regex classification. Absorbs the existing `ShellResolver.isLikelyPasswordPrompt` logic.
- Inline idle-signal emission via stream callback under `on_idle: notify`.
- New `background_process` meta-tool with 5 actions (`status`, `output`, `attach`, `send_stdin`, `kill`), registered as a **core tool** (always sent to LLM). See "Tool registration tier" below.
- `send_stdin` **promoted from deferred to core** (post-v1 it is only used on background processes, and auto-waked sessions need it available without a `tool_search` round-trip).
- **Retire the standalone `kill_process` tool entirely** — not hidden, not deprecated, deleted. See "Removed tools > Full removal sweep (concrete task list)" for the exhaustive inventory of files that need edits (production source, persona YAMLs, 12 snapshot files, 5 test files, CLAUDE.md docs). Kill functionality is consolidated into `background_process(action=kill)`.
- Update `AgentLoop.WRITE_TOOLS` set: remove `"kill_process"`, add `"background_process"` (with per-action read/write split inside `BackgroundProcessTool.execute()` so read actions skip the approval gate — same pattern as `debug_breakpoints` / `debug_step` / `debug_inspect`).
- **Top-bar `BackgroundIndicator` React component** + `_loadBackgroundSnapshot` bridge + `WorkflowEvent.BackgroundChanged` event + `background-update` JCEF push. Read-only dropdown; no inline actions in v1.
- Session-transition kill hooks + confirmation dialog.
- Persistence (`registry.json`, `pending_completions.json`, spill logs).
- Completion loopback: steering-queue delivery + auto-wake with guardrails.
- AgentSettings additions + UI (no new idle-classifier settings in v1 — tier 1 is always on).
- System prompt update + snapshot regeneration.

**Out of scope (deferred to v1.1 or later):**
- **Tier 2 Haiku idle classifier** — LLM-based classification when tier 1 returns LOW confidence. Reuses `ModelCache.pickCheapestModel()` (established cheap-model-picker used by `HaikuPhraseGenerator` and session-title generation) for model selection. Adds four settings: `idleClassifierMode`, `idleClassifierCacheEnabled`, `idleClassifierTimeoutMs`, `idleClassifierSkipBelowIdleSeconds`. Gated escalation from regex → LLM → fallback.
- **Mid-tool user-driven Detach button** — UI affordance to promote a foreground `notify` run into `BackgroundPool` while it's still blocked in the monitor loop. Issues a fresh `bgId` at the moment of user click. Not an LLM-facing policy.
- Wiring any tool other than `run_command` into `BackgroundCapable` (interface is designed for them; nothing else implements it yet).
- Background process migration across IDE restarts.
- Multi-session coordination (e.g., a sub-agent inheriting its parent's bgIds — sub-agents run in isolated contexts and don't share the parent's pool in v1).
- PTY-based spawning via pty4j for interactive commands — separate design; would replace pipe-based spawn for cases where the child's `isatty()` check suppresses prompts.

---

## Testing strategy

| Test | What it verifies |
|---|---|
| `BackgroundPoolSessionScopeTest` | A process registered in session A cannot be listed / read / killed from session B's pool. |
| `BackgroundPoolKillOnTransitionTest` | `killAll` fires on `_showSession`, `newChat`, `_deleteSession`, project close, IDE close; does NOT fire on stop, attempt_completion, window minimize. |
| `RunCommandBackgroundParamTest` | `background: true` returns bgId + initial output within 500ms + state=RUNNING. |
| `RunCommandOnIdleNotifyTest` | Foreground run with `on_idle: notify` emits inline stream note on idle, keeps monitor loop running, does NOT register in `BackgroundPool`, does NOT return early. Tool returns normally on eventual process exit. |
| `RunCommandOnIdleWaitTest` | Foreground run with `on_idle: wait` suppresses idle notifications entirely; tool blocks until exit or total timeout. No classification runs. |
| `NoAutoIdleDetachTest` | Regression lock-in: foreground `run_command` never auto-registers in `BackgroundPool`. The only paths into the pool are `background: true` (and, in v1.1, the user Detach button). |
| `PromptHeuristicsClassifyTest` | `PromptHeuristics.classify()` returns `LikelyPasswordPrompt` for password patterns (absorbs existing `isLikelyPasswordPrompt` cases), `LikelyStdinPrompt` for prompt-shaped tails and known framework prompts, `GenericIdle` otherwise. |
| `KillProcessToolRetirementTest` | Registry does not contain a `kill_process` tool. `background_process(action=kill)` remains the sole kill path. `ProcessToolHelpers.buildIdleContent` output does not reference `kill_process`. The string "kill_process" appears nowhere in `SystemPrompt.kt`, `RunCommandTool.kt`, `SendStdinTool.kt`, `ProcessToolHelpers.kt`, `AgentLoop.kt`, or any persona YAML under `agent/src/main/resources/agents/`. |
| `BackgroundProcessCoreTierTest` | `ToolRegistry.coreTools` contains `"background_process"` and `"send_stdin"`. `ToolRegistry.deferredTools` does NOT contain either. `AgentService.registerAllTools()` invokes `registerCore { BackgroundProcessTool(...) }` and `registerCore { SendStdinTool() }` (not the deferred variants). |
| `BackgroundProcessPerActionApprovalTest` | Read actions (`status`, `output`, no-args list) on `background_process` do not trigger the approval gate. Write actions (`kill`, `send_stdin`, `attach`) do. Matches the `debug_breakpoints`-style per-action split. |
| `BackgroundProcessListTest` | `background_process()` with no args lists current session's processes. |
| `BackgroundProcessAttachTest` | Attach re-enters monitor loop, exits on process exit/idle/timeout, returns sync-run-command-shaped result. |
| `BackgroundCompletionSteeringTest` | Event appearing while loop active → delivered as one system message per event at next iteration boundary. |
| `AutoWakeGuardTest` | Auto-wake triggers under normal conditions; skipped when setting disabled, cap reached, cooldown active, lock held, iteration budget near-exhausted. |
| `AutoWakePersistenceTest` | Guard failure → event remains in `pending_completions.json`; delivered on next manual resume. |
| `SendStdinUnifiedNamespaceTest` | Standalone `send_stdin` tool resolves both legacy IDLE process_ids and bgIds. |
| `ConfirmationDialogTest` | Dialog suppressed via setting; dialog text includes bgIds; cancel aborts transition. |
| `OutputSpillTest` | Output crosses 1MB → spills to disk → `action: output` returns preview + spill path. |
| System prompt snapshot regeneration | `./gradlew :agent:test --tests "*SNAPSHOT*generate all golden*"` after prompt change. |
| `BackgroundIndicatorVisibilityTest` | Webview test: chip hidden when 0 processes, renders `⚙ N background ▾` when ≥1, dropdown shows correct bgIds/commands/states on click, closes on outside-click and Escape. |
| `BackgroundIndicatorLiveUpdateTest` | `WorkflowEvent.BackgroundChanged` push updates the chip count + dropdown rows without a full reload. Runtime text ticks while RUNNING, freezes on EXITED. |

Existing `RunInvocationLeakTest` style pattern for: `BackgroundHandleLeakTest` — verifies every handle registration pairs with dispose/kill on all exit paths.

---

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| LLM spams `run_command(background: true)` and exhausts system | `concurrentBackgroundProcessesPerSession = 5` hard cap, returned as `MAX_CONCURRENT_REACHED` error. |
| Auto-wake loops (completion triggers action that spawns new background that completes that triggers auto-wake…) | `autoWakeMaxPerSession = 10` hard cap + `autoWakeCooldownMs = 5000` + loop detector already catches identical tool calls. |
| User leaves a long build in background and walks away — gets killed on navigation | Confirmation dialog ("2 background processes will be killed. Continue?") with don't-ask-again toggle. |
| Process balloons memory via unbounded output | Spill to disk at 1MB; in-memory retains ring buffer (last ~100KB) for fast preview access. |
| Session-lock contention when auto-wake fires mid-user-type | Auto-wake abandons on lock held; completion stays persisted; delivered on next manual interaction. |
| IDE crash with processes running | Spawn as child of IDE JVM — dies with OS cleanup of JVM. No orphans. On restart, the session's old `registry.json` is stale; `BackgroundPool.initialize()` scans it and writes `KILLED_BY_IDE_SHUTDOWN` completions. On next user interaction they're delivered via `[TASK RESUMPTION]` preamble. |
| Approval fatigue (per-invocation approval on every `background_process` read action) | Reads are no-approval. Only kill / send_stdin / attach require approval. |
| Foreground commands that genuinely hang now burn up to total `timeout` (120s–600s) instead of auto-returning at 15s | Correct tradeoff: the 15s auto-detach was returning wrong data ("idle" ≠ "done"). Total-timeout cap still kills hung processes; LLM can also choose `on_idle: wait` for known-slow commands or `background: true` for known-detach cases. |
| Tier 1 prompt classification is heuristic — we cannot actually detect "blocked on stdin" | Classifications are honestly labeled with source ("regex"); confidence tiers are explicit. `GenericIdle` says "cause unknown" rather than guessing. v1.1 Haiku tier escalates LOW-confidence cases to an LLM for a reasoned verdict. |
| Children spawned under pipes may suppress their prompts via `isatty()` check | Known limitation acknowledged in spec. Future pty4j work would address it. Not a blocker for v1 — affected tools still expose *something* on stdout eventually (exit code, error, or flushed buffer at exit), and `send_stdin` still works even without visible prompts. |

---

## Future extensions (out of scope v1)

- **v1.1 — Haiku tier 2 idle classifier.** LLM-based classification triggered only when tier 1 returns LOW confidence. Reuses existing infrastructure: `ModelCache.pickCheapestModel()` for model selection (no new model-override setting) and the `HaikuPhraseGenerator`-style async/cancel/fallback pattern for the call itself. Gated by `idleClassifierSkipBelowIdleSeconds` (default 5s) so snappy dev loops don't pay the latency. Cached by tail-output hash. Fails gracefully to tier 1 on LLM timeout or unavailability. Adds four settings: `idleClassifierMode` (`regex` / `smart` / `always_llm` / `off`), `idleClassifierCacheEnabled`, `idleClassifierTimeoutMs`, `idleClassifierSkipBelowIdleSeconds`.
- **v1.1 — Mid-tool user Detach button.** UI affordance: while a foreground `run_command` with `on_idle: notify` is running and has emitted an idle note, show a Detach button next to the inline note. Click → `RunCommandTool` honors a steering signal on its next monitor poll, promotes the process into `BackgroundPool` with a fresh `bgId`, returns `[IDLE]` with the bgId. Gives users an escape hatch for genuinely stuck commands without killing them.
- **v1.1 — Top-bar indicator inline actions.** Per-row kill / view-output / attach buttons in the top-bar dropdown, matching the `background_process` tool's action surface. v1 ships read-only; v1.1 unlocks direct user control for users who prefer not to route every kill through the LLM.
- Wire additional tools into `BackgroundCapable`: long-running `build`, `run_tests` debug launches, Bamboo `trigger_build`, dev-server configs in `runtime_exec.run_config`.
- A "background task" UI panel separate from chat, aggregating bg status across sessions (with session switcher).
- Cross-session "park and resume" (explicit user action: "keep this background alive across session switch"). Requires decoupling from per-session kill-on-transition rule, so it's a significant design change. Not in v1.
- **PTY-based spawning via pty4j** for interactive commands — would replace pipe-based spawn in cases where the child suppresses prompts under `isatty() == false`. Separate design doc (Windows ConPTY vs. winpty, terminal dimensions, ANSI sequence handling).

---

## Approvals

- [x] Tool architecture (meta-tool with actions + separate launcher) — user confirmed
- [x] Session scoping (kill on transitions only; survive stop/attempt_completion) — user confirmed
- [x] Auto-wake (yes; persisted; guardrails) — user confirmed
- [x] Per-completion delivery (not batched) — user confirmed
- [x] All 9 open proposals accepted — user confirmed
- [x] Idle-detach is no longer implicit — foreground runs default to `on_idle: notify`; `background: true` is the single flag for detach — user confirmed
- [x] Idle classification tiered: v1 ships tier 1 (regex via `PromptHeuristics`); v1.1 adds tier 2 (Haiku LLM escalation on LOW confidence) — user confirmed

Ready for implementation plan (`writing-plans` skill).
