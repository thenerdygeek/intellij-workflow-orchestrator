# Background Tool Execution — Design Spec

**Date:** 2026-06-21
**Module:** `:agent`
**Branch:** `worktree-feature+background-tool-execution` (worktree, fresh from `origin/main` @ `cf3d22873`)
**Status:** Design — awaiting user review before plan

---

## 1. Motivation

Today the agent's ReAct loop executes every tool **inline and blocking**: the LLM emits a tool
call, `AgentLoop.executeToolCalls` runs it inside a `withTimeoutOrNull(timeout)` `coroutineScope`,
and the loop waits for the result before the next LLM turn. Only three things run detached:
`run_command background=true`, `background_process`, and sub-agents (`agent`).

We want **any work tool to be runnable in the background**, via two triggers:

1. **Agent-initiated** — the LLM decides up front to run a tool in the background and keep working
   on other things while it runs.
2. **User-initiated** — for a long-running tool already executing, the user clicks a button on the
   running tool card to send it to the background. The agent is told the user did this, continues,
   and when the result lands it is delivered back into the conversation.

In both cases the result is delivered asynchronously through the existing `UnifiedMessageQueue`
(PR #61) as a `BACKGROUND` message, which **auto-wakes** the session if it has gone idle.

## 2. Locked Decisions

| # | Decision | Choice |
|---|----------|--------|
| D1 | Concurrency model | **Full parallel work** — the loop keeps calling the LLM and firing other tools while background tools run. |
| D2 | Eligibility | **All work tools; exclude control-flow.** A `run_in_background` flag is honored unless the tool is in a control-flow denylist or marks itself non-backgroundable. |
| D3 | Completion race | Background tools behave like **monitor/async sources**. The agent is **never blocked** from `attempt_completion`; a late result **auto-wakes** the session (existing `BACKGROUND` policy: `autoWakesIdle=true`). |
| D4 | Agent trigger | **Loop-level reserved attribute** `run_in_background`, recognized on *any* tool call, documented once in the system prompt. Zero per-tool schema bloat. |
| D5 | Concurrency cap | **5, configurable.** Beyond the cap → inline fallback (never an error). |
| D6 | Feature gate | **On by default + kill switch** setting. When OFF, the attribute and the button are ignored; everything runs inline. |

## 3. Architecture — "Launch in a session scope, await inline, detach = stop awaiting"

A coroutine cannot be moved between scopes after it starts, but it can be **launched in the right
scope from the beginning**. So every eligible tool is launched as a job in a **per-session
`SupervisorJob` scope** owned by the executor — *not* as a structured child of the loop turn.
Normally the loop simply `await`s that job to completion, so non-background behavior is identical to
today. The two triggers reduce to *whether the loop keeps awaiting*:

- **Agent-initiated:** launch the job, **do not await** — synthesize a "started in background"
  result immediately and proceed.
- **User-initiated:** the loop is awaiting via a `select { onJobComplete ‖ onDetachSignal }`. The
  button completes the detach signal; the loop stops awaiting, synthesizes "user moved this to
  background," and proceeds. The job keeps running because it was never a child of the loop turn.

When the job completes (either path), a completion handler post-processes the result identically to
the inline path and enqueues it as a `BACKGROUND` message → drained at the next iteration boundary,
auto-waking the session if idle.

```
                       ┌─────────────────────────── BackgroundToolExecutor (per session) ───────────┐
                       │  CoroutineScope(SupervisorJob + Dispatchers.IO), owned by AgentService       │
                       │  registry: Map<toolCallId, BackgroundToolHandle>                             │
executeToolCalls()     │                                                                              │
   │                   │   launch { executeOnce() }  ──►  Deferred<ToolResult>                        │
   │  eligible?        │            │                                                                 │
   ├── inline ─────────┼── await ───┤ (select over detach signal)                                     │
   │   (await job)     │            │                                                                 │
   ├── agent bg ───────┼── launch, NO await ──► synth "started in background"                          │
   │                   │            │                                                                 │
   │  user clicks      │            ▼                                                                 │
   │  "→ background" ──┼── detach signal ──► loop stops awaiting, synth "moved to background"          │
   │                   │            │                                                                 │
   │                   │   invokeOnCompletion ──► postProcess() ──► enqueue(BACKGROUND) ──► auto-wake  │
   └───────────────────┴────────────────────────────────────────────────────────────────────────────┘
```

## 4. Components (all new code in `:agent`; no `:core` change except the parser flag)

| Component | Responsibility |
|-----------|----------------|
| **`BackgroundToolExecutor`** | Per-session. Owns `CoroutineScope(SupervisorJob() + Dispatchers.IO)` as a child of `AgentService.cs`. `start(handle)`, `detach(toolCallId)`, `cancelAllForSession()`, `dispose()`. On job completion: post-process → enqueue → emit async card → unregister. Enforces the cap. |
| **`BackgroundToolHandle`** | `toolCallId`, `toolName`, `params`, `tool`, `Deferred<ToolResult>`, `startedAt: Long`, `detachSignal: CompletableDeferred<Unit>`. |
| **`BackgroundToolRegistry`** | Session-keyed index of live handles, queried by env_details, the UI, and cap enforcement. Distinct from `ToolCancellationRegistry` (which stays the cancel authority, keyed by `toolCallId`). |
| **`run_in_background` parser flag** | Reserved param name added to the global `paramNames` set so it parses on any tool, lifted onto `ToolCall.runInBackground` and **stripped** from the params map before the tool runs. |
| **`BackgroundEligibility`** | `fun isBackgroundable(toolName, tool): Boolean = toolName !in CONTROL_FLOW_DENYLIST && tool.isBackgroundable`. Mirrors the plan-mode `WRITE_TOOLS` pattern. |
| **UI: "Move to background" button + background card state** | Button beside the #62 Stop button on a running tool card; on detach the card shows "running in background ⏱ <elapsed>" and keeps Stop. |
| **Settings** | `allowToolsRunInBackground: Boolean = true` (kill switch); `maxConcurrentBackgroundToolRuns: Int = 5`. |

## 5. Data Flow

### 5.1 Agent-initiated
1. Parser lifts `run_in_background=true` onto `ToolCall.runInBackground`; strips the tag from params.
2. In `executeToolCalls`, after approval/plan-mode/PRE_TOOL_USE gates pass (these still run inline,
   before launch), check:
   - Kill switch OFF → ignore the flag, run inline.
   - `!isBackgroundable(toolName)` → run inline, append a note to the result so the agent learns.
   - At cap → run inline, append "(background slots full, ran inline)".
   - Otherwise → `executor.start(handle)`, register in `BackgroundToolRegistry` **and**
     `ToolCancellationRegistry`, and **synthesize**:
     `Started '<tool>' in background (id=<toolCallId>). It will deliver its result when done — continue with other work.`
3. The loop inserts the synthetic result into the conversation as a normal tool result and proceeds.

### 5.2 User-initiated
1. Tool runs inline; loop awaits via `select { handle.deferred.onAwait ‖ handle.detachSignal.onAwait }`.
2. User clicks "Move to background" → JCEF bridge `_moveToolToBackground(toolCallId)` →
   `executor.detach(toolCallId)` completes `detachSignal`.
3. `select` resolves to detach → loop synthesizes
   `User moved '<tool>' (id=<toolCallId>) to the background; it is still running. Continuing.`
   and proceeds. The job survives (it lives in the executor scope, not the loop turn).

### 5.3 Completion delivery (both paths converge)
1. `handle.deferred.invokeOnCompletion` fires.
2. Post-process via the **extracted helper** (grep filter, output spill, truncation, token
   re-estimate) — identical to the inline path.
3. `AgentService.enqueueToSession(sessionId, QueuedMessage(kind=BACKGROUND, body=framedResult,
   coalesceKey=toolCallId, ...))` — reuses the path `BackgroundCompletionCoordinator` already uses.
   `BackgroundQueuePolicy`: `durable=true, autoWakesIdle=true, resetsUserSilence=false,
   defersCompletion=true, priority=50`.
4. Emit a UI-only async event card (SUCCESS/FAILURE), deduped by `bg-<toolCallId>` (reuses #61's
   async-card path).
5. Unregister from both registries.

**Why this satisfies D3:** an *in-flight* background job has nothing in the queue, so it never
defers `attempt_completion`; the agent completes and goes idle. When the result lands,
`autoWakesIdle=true` re-arms the loop via the existing `IdleSessionWaker`. `defersCompletion=true`
only matters if a result is *already queued and undrained* at the instant the agent completes — in
which case draining it first is correct, not a contradiction.

## 6. Parser change (`:core`)

`AssistantMessageParser.parse(text, toolNames, paramNames)` drops any tag not present in
`paramNames` (the per-tool whitelist trap from `run_maven_goal`). Approach:

- Add `"run_in_background"` to the **global** `paramNames` set the loop passes in, so it parses for
  any tool as an ordinary param.
- In `executeToolCalls`, read `params["run_in_background"]` (arrives as a **string** —
  BrainRouter serializes XML params as string primitives, so parse `"true"`/`"false"` defensively),
  set `runInBackground`, and **remove the key from `params`** before the tool's `execute()` sees it.

This keeps the change to a single reserved name plus a lift-and-strip, with **no per-tool schema
edits** and one short paragraph in the system prompt documenting the attribute.

## 7. Agent self-awareness — `env_details`

`EnvironmentDetailsBuilder.build(... sessionId ...)` already renders running processes
(`appendRunningProcesses`). Add a **"Background tasks in progress"** block after the Active Monitors
section, listing `id · tool · elapsed` for each live handle from `BackgroundToolRegistry`. Omitted
entirely when empty (no token cost). This lets the agent reason about pending parallel work (e.g.
"the build is still running; I'll hold the deploy") and decide whether to keep going or wind down.

## 8. UI

- **`ToolCallChain.tsx`**: beside the existing Stop button (`killToolCall(tc.id)`), add a
  "Move to background" button shown only while a tool is running and (per a flag pushed from Kotlin)
  the tool is backgroundable and the kill switch is ON. Click → `moveToolToBackground(tc.id)` in the
  chat store → bridge.
- **`AgentCefPanel.kt`**: register a `_moveToolToBackground` `JBCefJSQuery` handler alongside
  `_killToolCall`, routing to `executor.detach(toolCallId)`.
- **Card states**: running → (on detach) "running in background ⏱ <elapsed>" with Stop retained →
  (on completion) async event card SUCCESS/FAILURE.

## 9. Concurrency, limits, and edge cases

- **Cap (D5):** beyond `maxConcurrentBackgroundToolRuns` (default 5), the flag is ignored and the
  tool runs inline with a noted reason. Never an error, never silently dropped.
- **Non-backgroundable (D2):** flag on a control-flow tool → ignored + noted.
- **Kill switch OFF (D6):** attribute and button both ignored; pure inline behavior.
- **Per-tool Stop on a backgrounded tool:** unchanged — routes through `ToolStopCoordinator`
  (process-kill then coroutine-cancel via `ToolCancellationRegistry`). The queued result reports
  "stopped by user."
- **Natural loop end** (`attempt_completion` / idle): background jobs **survive** and auto-wake on
  completion. This is the intended D3 flow.
- **Global Stop** (user stops the whole agent task): cancels the loop **and**
  `executor.cancelAllForSession(sessionId)` — "stop everything" stops background work too.
- **Session close / project close:** `AgentService.dispose()` → `executor.dispose()` cancels the
  SupervisorJob and all in-flight jobs.
- **Duplicate-result safety:** `coalesceKey=toolCallId` guarantees a result can never be injected
  twice.
- **Inline await cancelled (not via detach):** because every eligible tool launches in the executor
  scope (so the button can target any running tool), an inline tool is no longer a structured child
  of the loop turn and won't die automatically. **Invariant:** when an inline `await` throws
  `CancellationException` that is *not* the detach signal, the executor cancels that handle's job.
  Only an explicit detach lets a job outlive its await. This preserves today's behavior — an inline
  tool that was never backgrounded still dies with the loop. (User-stop / `UserStopCancellationException`
  is discriminated exactly as in #62.)
- **IDE restart:** in-flight background jobs are in-memory coroutines and do **not** survive a
  restart (same limitation as today's `run_command background=true`). Results already enqueued but
  undrained *are* durable (`BACKGROUND` source persists). Documented, not hidden.
- **Approval / plan-mode / PRE_TOOL_USE:** all run **inline before launch**, so a backgrounded tool
  is still gated exactly like an inline one. Plan mode already blocks `WRITE_TOOLS` regardless.

## 10. Scope ownership & lifecycle

`AgentService` is `@Service(Service.Level.PROJECT)` with an injected `cs: CoroutineScope` and is
`Disposable`. The `BackgroundToolExecutor` is owned by `AgentService`, its scope a child of `cs`
with its own `SupervisorJob` (one failing background tool must not cancel siblings or the session).
Background jobs are deliberately **not** children of the loop's task job, so a natural loop end does
not cancel them; they are cancelled only by global Stop, session close, or per-tool Stop.

## 11. The refactor — extract post-processing

`AgentLoop.executeToolCalls` currently post-processes a returned `ToolResult` inline (~lines
2111–2159: grep filter via `ToolOutputConfig.applyGrep`, spill via `outputSpiller.spill`,
`truncateOutput`, token re-estimate via `estimateTokens`). For the background path to produce
**identical** results, extract this into a single private helper, e.g.:

```kotlin
private suspend fun processToolOutput(
    toolName: String, toolResult: ToolResult, params: JsonObject, tool: AgentTool,
): ProcessedToolResult            // (content, tokenEstimate, wasProcessed)
```

Both the inline path and the executor's completion handler call it. This prevents the two paths from
drifting and is a small, contained improvement to code we're already touching.

### 11.1 Known integration risk — ThreadLocal context

The loop currently sets `BackgroundProcessTool.currentSessionId` and
`RunCommandTool.currentToolCallId` as **ThreadLocals immediately before `executeOnce()`**, relying
on the tool running on the same thread. Once execution moves into the executor's coroutine (a
different dispatcher thread), a ThreadLocal set on the loop thread is **not** visible to the tool
body. The set must therefore happen **inside the launched job** (set-then-execute-then-clear in the
`finally`), or be carried via a `kotlinx.coroutines.ThreadContextElement`. This is the same class of
issue as the open `agent:F-28 (ThreadLocal→CoroutineContext)` item and MUST be handled, or
`run_command` streaming / background-process session routing will silently break. The implementation
plan must include a test that asserts the tool body observes the correct `toolCallId` / `sessionId`
when run through the executor.

## 12. Settings

- **State** (`AgentSettings.State`, `SimplePersistentStateComponent`):
  - `var allowToolsRunInBackground by property(true)`
  - `var maxConcurrentBackgroundToolRuns by property(5)`
- **UI** (`AgentAdvancedConfigurable`): a "Background Tool Execution" group — a checkbox bound to the
  kill switch and an `intTextField(1..50)` bound to the cap, each with a `.comment(...)`.

## 13. Testing

**Pure / unit (no `BasePlatformTestCase` — avoids the indexing-timeout-on-2nd-test trap):**
- Parser lifts `run_in_background` and strips it for every tool; string `"true"`/`"false"` parsed.
- `isBackgroundable` predicate (denylist + tool flag).
- Cap → inline-fallback logic; kill-switch-OFF → inline.
- The extracted `processToolOutput` helper (grep/spill/truncate/estimate parity).

**Coroutine tests (`runTest`):**
- launch → complete delivers a framed `BACKGROUND` message to a fake queue with `coalesceKey`.
- launch → detach (signal) → loop synthesizes the moved-to-background result; job still completes
  and delivers.
- agent-initiated launch → synthesizes started-in-background and does not await.
- global Stop / dispose cancels in-flight jobs; per-tool Stop yields the stopped-by-user result.
- inline `await` cancelled (non-detach) cancels the underlying job (no leak); detach lets it survive.
- tool body observes the correct `toolCallId` / `sessionId` ThreadLocal when run via the executor
  (guards §11.1).

**In-IDE smoke (deferred, like #62):**
- Real `run_command` sent to background via the button; result auto-delivers.
- Agent-initiated background grep while the loop keeps working.
- Background completion auto-wakes the session after `attempt_completion`.

## 14. Out of scope (YAGNI)

- Durable in-flight background jobs across IDE restart (results already queued remain durable).
- Backgrounding control-flow / interactive tools (D2).
- A dedicated `wait_for_background` tool — the auto-wake model (D3) makes it unnecessary.
- Cross-session background tools — executor is per-session.
- Priorities / scheduling among background tools beyond the existing queue ordering.

## 15. Integration seams (verified)

| Seam | Location |
|------|----------|
| Tool-call parser | `core/.../core/ai/AssistantMessageParser.kt` — `parse(text, toolNames, paramNames)`; `ToolCall` = `core.ai.dto.ToolCall` |
| Inline exec + cancel registry | `agent/.../loop/AgentLoop.kt` — `executeToolCalls` (~1838+); exec+timeout (~2033–2107); cancel register/catch (~2048–2086) |
| Post-processing block to extract | `agent/.../loop/AgentLoop.kt` ~2111–2159 |
| Cancel infra (#62) | `agent/.../tools/cancel/ToolCancellationRegistry.kt`, `ToolStopCoordinator.kt`; `ProcessRegistry` |
| Message queue (#61) | `agent/.../loop/queue/UnifiedMessageQueue.kt` — `enqueue(QueuedMessage)`; `QueueSourceKind.BACKGROUND`; `BackgroundQueuePolicy` |
| Enqueue + wake path | `AgentService.enqueueToSession(...)`; `BackgroundCompletionCoordinator`; `IdleSessionWaker` |
| env_details | `agent/.../prompt/EnvironmentDetailsBuilder.kt` — `build(...)`, `appendRunningProcesses` |
| UI Stop button | `agent/webview/src/components/agent/ToolCallChain.tsx` (`killToolCall`); bridge `_killToolCall` in `agent/.../ui/AgentCefPanel.kt` |
| Lifecycle / scope | `agent/.../AgentService.kt` — `@Service(PROJECT)`, `cs: CoroutineScope`, `Disposable`, `activeTask` |
| Settings | `agent/.../settings/AgentSettings.kt` (State); `agent/.../settings/AgentAdvancedConfigurable.kt` |

## 16. Service-architecture compliance

`:agent` depends only on `:core`. The only `:core` touch is adding `"run_in_background"` to the
reserved param-name set and a `runInBackground: Boolean` field on `ToolCall` (`core.ai.dto`).
Everything else — executor, registry, eligibility, UI, settings — lives in `:agent`. No new
cross-module client methods are required; delivery reuses the existing `:core`-typed
`UnifiedMessageQueue` and `AgentService.enqueueToSession`.
