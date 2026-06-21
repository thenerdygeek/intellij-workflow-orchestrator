# Background Tool Execution — Design Spec (v3, post-review + re-verified)

**Date:** 2026-06-21
**Module:** `:agent` (plus one documentation bullet in `:core` `ToolPromptBuilder.FORMAT_INSTRUCTIONS`)
**Branch:** `worktree-feature+background-tool-execution` (worktree, fresh from `origin/main` @ `cf3d22873`)
**Status:** Design v3 — revised after adversarial review + direct re-verification of the two hardest claims (sub-agent cancellation teardown; the drain/thread-boundary). All findings checked against code. Awaiting user review before plan.

---

## 1. Motivation

The ReAct loop executes every tool **inline and blocking**: `AgentLoop.executeToolCalls` runs a tool
inside a `withTimeoutOrNull(timeout)` `coroutineScope` and waits for the result before the next LLM
turn. Only `run_command background=true`, `background_process`, and sub-agents detach today.

We want **any work tool runnable in the background**, via two triggers:

1. **Agent-initiated** — the LLM adds a loop-level `run_in_background` attribute to a tool call and
   keeps working while it runs.
2. **User-initiated** — the user clicks "Move to background" on a running tool card; the agent is
   told and continues; the result is delivered asynchronously when it lands.

Results deliver through the existing `UnifiedMessageQueue` (PR #61) as a `BACKGROUND` message that
**auto-wakes** the session when idle (modeled on the monitor framework).

## 2. Locked Decisions

| # | Decision | Choice |
|---|----------|--------|
| D1 | Concurrency model | **Full parallel work** — the loop keeps calling the LLM and firing other tools while background tools run. |
| D2 | Eligibility | **All work tools; exclude control-flow** (`attempt_completion`, `plan_mode_respond`, `ask_followup_question`, `enable_plan_mode`/`disable_plan_mode`, `new_task`). |
| D3 | Completion behavior | Background tools behave like monitor/async sources. An **in-flight** job never blocks `attempt_completion`. A result already **queued** at the completion boundary defers exit by re-injecting and continuing **one turn** (existing `BACKGROUND` `defersCompletion=true`). A result that lands while the **same** session is idle **auto-wakes** it; one that lands while a **different** session is foreground is durable-queued and replayed when that session resumes. See §3.3. |
| D4 | Agent trigger | **Loop-level reserved tag** `run_in_background`, read+stripped from the decoded params (no schema/DTO change). |
| D5 | Concurrency cap | **5, configurable.** Beyond the cap → inline fallback (never an error). |
| D6 | Feature gate | **On by default + kill switch** setting. OFF → attribute and button ignored; everything inline. |
| D7 | Sub-agent tool (`agent`) | **Eligible.** Backgrounding wraps the tool at its `execute()` boundary; a per-tool Stop cancels the tool's job → its sub-agents via structured concurrency. **Verified:** `executeParallel` runs children in `supervisorScope { mapIndexed { async { runner.run() } } }` (`SpawnAgentTool.kt:925`) and each `runner.run` wraps the inner loop in a descendant `coroutineScope` (`SubagentRunner.kt:461`); `supervisorScope` isolates only *sibling* failure, so parent-job cancellation tears the whole tree down. Sub-agents' own per-worker controls remain the inline affordance. A sub-agent's *internal* loop does **not** honor `run_in_background` in v1 (parsed-and-stripped there). |
| D8 | Session/chat switch | **Survive on session-switch, cancel on new-chat.** Viewing another session lets A's job run on and deliver to A's durable queue (replayed on resume). `resetForNewChat()`/new chat cancels all of A's background jobs; `dispose()` cancels everything. |

## 3. Architecture

### 3.1 Core model — "launch in a session scope, await inline, detach = stop awaiting"

A coroutine cannot be re-parented after it starts, but it can be **launched in the right scope from
the start**. Every eligible tool is launched as a job in a **per-session job set** owned by a
`BackgroundToolExecutor` whose scope is a child of `AgentService.cs` (a `SupervisorJob`, so one
failing tool cannot cancel siblings or the session). The job is **not** a structured child of the
loop turn. Normally the loop just `await`s it, so non-background behavior is identical to today. The
triggers reduce to *whether the loop keeps awaiting*:

- **Agent-initiated:** launch, **do not await** — synthesize a "started in background" result and proceed.
- **User-initiated:** the loop is awaiting via `select { handle.deferred.onAwait ‖ handle.detachSignal.onAwait }`.
  The button completes `detachSignal`; the loop stops awaiting, synthesizes "moved to background," and
  proceeds. The job survives because it lives in the executor scope.

### 3.2 Reconciliation with AgentService's single-active-task model (B3 fix)

`AgentService` is `@Service(PROJECT)` and runs **one** `activeTask` at a time (`AtomicReference<ActiveTask?>`,
`AgentService.kt:110`); starting a new task cancels the previous loop's job under `activeTaskMutex`
(`:2491`). The `BackgroundToolExecutor` is therefore a **single AgentService-owned object** that
indexes jobs **by sessionId** (`Map<sessionId, MutableSet<BackgroundToolHandle>>`), not a per-session
instance. Because background jobs are children of `cs` (not the loop job), a normal loop end or a
new task does **not** cancel them — which is the intent. Explicit lifecycle hooks (D8):

| Event | Site | Action on background jobs |
|-------|------|---------------------------|
| Loop ends naturally (`attempt_completion`/idle) | loop exit | **survive**; deliver on completion |
| New **task**, same session | `executeTask` (`:2491`) | **survive** (still belong to that session) |
| Switch to view another session | (foreground change) | **survive**; deliver to A's durable queue |
| **New chat** / `resetForNewChat()` | `:3643` | `executor.cancelAllForSession(sid)` for the reset session |
| Global Stop | `cancelCurrentTask()` (`:3579`) | cancel the foreground session's background jobs |
| Per-tool Stop | bridge → `ToolStopCoordinator` | cancel that one handle's job |
| Project close | `AgentService.dispose()` | `executor.dispose()` cancels all |

### 3.3 Delivery and auto-wake (B4 fix — honest, conditional)

On completion the handler enqueues via the existing `AgentService.enqueueToSession(sessionId, msg)`
(`:501`), which auto-wakes **only** when `activeLoopForSession(sessionId) == null` **and** the
`IdleSessionWaker` agrees the target is wake-eligible (`activeSessionId = { activeTask.get()?.sessionId }`,
`:186`; it defers with `DEFER_ACTIVE_SESSION` when a *different* session is the active task). Concretely:

- **Same session, now idle** (the primary scenario: background → keep working in A → complete → idle):
  `activeTask` is null after loop exit (`:2609`) → wake fires → agent resumes and reacts. ✅
- **A different session is foreground:** A's result is **durable-queued** (`BackgroundQueuePolicy.durable=true`)
  and replayed when A is next resumed; the async event card surfaces it meanwhile. No data loss; the
  wake is deferred, not unconditional.

## 4. Components (all in `:agent`; **no `:core` change**)

| Component | Responsibility |
|-----------|----------------|
| **`BackgroundToolExecutor`** | AgentService-owned. Scope = child of `cs` + `SupervisorJob`. `start(handle)`, `detach(toolCallId)`, `cancelAllForSession(sid)`, `cancelOne(toolCallId)`, `dispose()`. Indexes handles by sessionId. On job completion: post-process (pure) → `enqueueToSession` → emit async card → unregister. Enforces the cap. **Catches all throwables inside the job and completes the `Deferred` with an error `ToolResult`** so the awaiting `select`'s `onAwait` never rethrows (single error site). |
| **`BackgroundToolHandle`** | `toolCallId`, `sessionId`, `toolName`, `params`, `tool`, `Deferred<ToolResult>`, `startedAt`, `detachSignal: CompletableDeferred<Unit>`. |
| **`BackgroundToolRegistry`** | Thread-safe (`ConcurrentHashMap`/snapshot reads) index of live handles for env_details, UI, and cap checks. `ToolCancellationRegistry` (keyed by `toolCallId`) stays the cancel authority. |
| **`run_in_background` handling** | The reserved name is injected into the `paramNames` set at every provider site so the parser keeps the tag; the loop reads `params["run_in_background"]` (a **string** — BrainRouter serializes XML params as strings — parse `"true"` defensively) and **removes the key** before the tool runs. **No `ToolCall`/DTO field.** |
| **`BackgroundEligibility`** | `toolName !in CONTROL_FLOW_DENYLIST && tool.isBackgroundable && settings.allowToolsRunInBackground`. Mirrors the plan-mode `WRITE_TOOLS` set pattern. |
| **UI** | Per-tool-category "Move to background" affordance + a background card state (§8). |
| **Settings** | `allowToolsRunInBackground: Boolean = true`; `maxBackgroundedToolsPerSession: Int = 5` (named to NOT collide with the existing `concurrentBackgroundProcessesPerSession`, which caps OS processes — a different concept). |

## 5. Data Flow

### 5.1 Agent-initiated
1. Parser keeps `<run_in_background>` because the reserved name is in `paramNames`; the value reaches
   `params` via `FunctionCall.arguments`.
2. In `executeToolCalls`, **after** the approval gate, plan-mode guard, and PRE_TOOL_USE hook (all
   still run inline, unchanged), read+strip `run_in_background`. Then:
   - kill switch OFF, or `!isBackgroundable` → run inline; append a note so the agent learns.
   - at cap → run inline; append "(background slots full, ran inline)".
   - else → `executor.start(handle)`, register in `BackgroundToolRegistry` + `ToolCancellationRegistry`,
     synthesize: `Started '<tool>' in background (id=<toolCallId>). It will deliver its result when done — continue with other work.`
3. The synthetic result is inserted as a normal tool result; the loop proceeds.

### 5.2 User-initiated
1. Tool runs via the executor; the loop awaits via `select { deferred.onAwait ‖ detachSignal.onAwait }`.
2. "Move to background" → bridge `_moveToolToBackground(toolCallId)` → `executor.detach(toolCallId)`
   completes `detachSignal`.
3. `select` resolves to detach → loop synthesizes `User moved '<tool>' (id=<toolCallId>) to the background; still running. Continuing.`
   The job survives.

### 5.3 Completion delivery (both paths) — the thread boundary (verified)

The background completion handler runs on an **executor coroutine**, NOT the loop thread, so it does
**only** off-thread-safe work:

1. **Post-process** the `ToolResult` via the extracted **pure** helper (grep/spill/truncate/token
   re-estimate, §11), producing the framed body — which carries the tool name + `toolCallId` so the
   agent correlates it with the call it backgrounded.
2. `enqueueToSession(sessionId, QueuedMessage(kind=BACKGROUND, body=framedResult, coalesceKey=toolCallId,
   meta=card))` — `enqueue` is `synchronized` (`UnifiedMessageQueue.kt:20`), safe off-thread; it rides the
   existing async-card `meta` channel (#61) so the UI card flips to SUCCESS/FAILURE.
3. Unregister from both registries.

**Delivery reuses the existing drain path unchanged — no net-new plumbing.** Verified: the queue drain
injects framed async text via `contextManager.addUserMessage(withEnvDetails(combined))` (`AgentLoop.kt:943-952`)
on the loop thread — exactly how DELEGATION/MONITOR/background-completion already deliver. A backgrounded
tool's result therefore arrives as a framed **user** message; the original tool call already received its
synthetic tool-result (§5.1/§5.2), so API tool_use/tool_result pairing stays intact.

The handler does **NOT** call `contextManager.addToolResult`, `addToApiConversationHistory`/`addToClineMessages`,
POST_TOOL_USE, `modifiedFiles`/`countDiffChanges`, or `onToolCall` (verified non-thread-safe mid-iteration at
`AgentLoop.kt:2186/2213/2269/2275/2298/2303/2307`). Consistent with every other async source, these **do
not run for backgrounded tools** (§14). For the real background use cases (run_command, build/test, grep,
web_fetch, agent) this is lossless — they emit no `ToolResult.diff` and POST_TOOL_USE is observation-only.
UI completion state is carried by the async card, not `onToolCall`. **One owner of loop state: the loop thread.**

## 6. The `run_in_background` plumbing (B1 + B2 fix) — `:core` untouched

`AssistantMessageParser.parse(text, toolNames, paramNames)` drops any tag whose name is not in
`paramNames`; that set is **not static** — it is `ToolRegistry.allParamNames()` (derived from each
tool's `parameters.properties.keys`, `ToolRegistry.kt:265`). So:

- **Augment the set at every provider site** with the reserved name:
  `registry.allParamNames() + "run_in_background"` at `AgentService.kt:734`, `:1901`, `:2464`, **and**
  the sub-agent path (`SubagentRunner` `subagentRegistry.allParamNames()`). Augmenting the sub-agent
  set too means the tag parses (not leaked as literal text) even though the sub-agent loop ignores it (D7).
- **Read + strip in the loop:** `params["run_in_background"]?.let { parse "true" }`; then drop the key
  from the `JsonObject` before `tool.execute(params)` so no tool sees an unexpected param.
- A tiny parser/registry unit test: the reserved name parses for a tool that does not declare it.

There is **no** `ToolCall.runInBackground` field and **no** `:core` DTO change — the flag lives only in
the transient `params` map.

## 7. Agent self-awareness — `env_details`

`EnvironmentDetailsBuilder.build(... sessionId ...)` (`:31`) already renders running processes and
active monitors. Add a **"Background tasks in progress"** block (after Active Monitors) listing
`id · tool · elapsed` from a **snapshot** read of `BackgroundToolRegistry` for `sessionId` (mirror
`MonitorPool.list(sessionId)`'s snapshot pattern). Omitted when empty. Lets the agent reason about
pending parallel work and decide whether to keep going or wind down.

## 8. UI (I-fix — per-category placement)

`STOP_SUPPRESSED_TOOLS = {run_command, background_process, ask_user_input, agent}` (`ToolCallChain.tsx:32`)
do **not** render the universal Stop button — so "beside Stop" has no anchor for `run_command`/`agent`,
two prime background candidates. Placement by category:

- **Generic tool cards** (have the universal Stop button): add "Move to background" beside it.
- **`run_command` / `background_process`**: add the affordance to their terminal/process card controls.
  Note the overlap — `run_command background=true` already spawns detached; the new mechanism is the
  *universal* detach and both may coexist. Document so users aren't confused.
- **`agent` (sub-agent) card**: add the affordance to the sub-agent card; it backgrounds the whole
  tool execution (D7). The card's existing per-worker Kill remains for the inline case.

Visibility of the affordance is gated by a Kotlin-pushed `backgroundable` flag (denylist + kill switch).
On detach the card shows "running in background ⏱ <elapsed>" and keeps its cancel control. On completion:
an async event card SUCCESS/FAILURE with id **`bg-<toolCallId>-<occurredAt>`** (aligns with the existing
`bg-{id}-{occurredAt}` card scheme; the queue's `coalesceKey=toolCallId` still guarantees single delivery).

## 9. Concurrency, limits, edge cases & the cancel truth table

- **Cap (D5):** beyond `maxBackgroundedToolsPerSession`, the flag is ignored → inline with a note.
- **Non-backgroundable / kill switch OFF:** ignored + noted / pure inline.
- **Cancel truth table** for the inline `await`/`select` (B-fix; the load-bearing correctness property,
  since detached jobs are no longer structured children):

  | Outcome of the await/select | Job? | Result to loop |
  |---|---|---|
  | `deferred.onAwait` completes normally | done | the `ToolResult` |
  | `detachSignal` fires | **leave running** | synth "moved to background" |
  | throws, cause chain has `UserStopCancellationException` (per-tool Stop, #62) | already cancelling | `stoppedByUser` result; loop continues |
  | throws other `CancellationException` (genuine loop cancel) | **cancel the job** (not detached) | rethrow — propagate |

  Only an explicit detach lets a job outlive its await; an un-detached inline tool still dies with the
  loop, preserving today's structured-concurrency semantics.
- **Tools that catch cancellation and return a partial result** (notably `agent`/`SpawnAgentTool`, which
  returns the completed children on cancel) complete the `Deferred` **normally** with that partial result
  rather than throwing. So per-tool Stop on a backgrounded `agent` delivers its partial aggregate — not the
  generic stopped-by-user message — matching today's inline behavior. The truth-table "cancel the job" row
  applies to tools that propagate the `CancellationException`.
- **`select` never rethrows tool exceptions:** the executor catches throwables inside the job and
  completes the `Deferred` with an error `ToolResult` (§4) — exactly one error-reporting site.
- **defersCompletion (D3):** a result already queued at the `attempt_completion` boundary defers exit
  one turn (`drainSteeringIntoContextOnExit`, `AgentLoop.kt:2687`). We keep `BACKGROUND.defersCompletion=true`
  (do not regress existing background-process behavior); the one-turn re-evaluation is intended, not a bug.
- **Per-tool Stop on a backgrounded tool:** unchanged — `ToolStopCoordinator` (process-kill then
  coroutine-cancel). For a backgrounded `agent`, cancelling its job cancels its sub-agents.
- **Race: completion at the instant of "move to background":** if `deferred` and `detachSignal` are both
  ready, `select` picks one deterministically; if completion wins, deliver normally and ignore the late
  detach (the handle is already unregistered). If detach wins, the completion handler still fires and
  delivers — `coalesceKey=toolCallId` prevents double injection.
- **Result enqueued after session disposal:** `enqueueToSession` persists durably; on dispose the scope
  is cancelled first, so no new completion fires post-dispose. A completion racing dispose is cancelled.
- **IDE restart:** in-flight jobs are in-memory and do not survive (same as `run_command background=true` today);
  already-queued-undrained results persist. Documented.

## 10. Scope ownership & lifecycle

`BackgroundToolExecutor` is owned by `AgentService`, scope a child of the injected `cs` with its own
`SupervisorJob`. Wiring per §3.2: `cancelAllForSession` into `resetForNewChat()` (`:3643`) and the
foreground path of `cancelCurrentTask()` (`:3579`); `dispose()` into `AgentService.dispose()`.

## 11. The refactor — extract a **pure** post-processing helper

Extract `AgentLoop.kt:2111–~2159` (grep via `ToolOutputConfig.applyGrep`, spill via `outputSpiller.spill`,
`truncateOutput`, token re-estimate via `estimateTokens`) into a pure helper:

```kotlin
private suspend fun processToolOutput(
    toolName: String, toolResult: ToolResult, params: JsonObject, tool: AgentTool,
): ProcessedToolResult   // (content, tokenEstimate, wasProcessed)
```

Both the inline path and the executor completion handler call it. **The helper is pure (output only);
it does NOT touch loop state.** The ~8 side effects that follow it in the inline path
(`addToolResult`, history/clineMessages, POST_TOOL_USE, `modifiedFiles`/`countDiffChanges`, `onToolCall`,
`when(type)` dispatch) stay on the loop thread and run **only for inline tools**. For backgrounded tools
they are intentionally **not** run (§5.3, §14) — delivery is the framed user message via the existing
drain, and UI completion is the async card. The extraction itself is contained; the thread-safety win
comes from *not* duplicating those side effects off-thread, not from re-running them at drain time.

### 11.1 ThreadLocal / context propagation (must-fix)

The loop sets **three** ThreadLocals immediately before `executeOnce()` (`AgentLoop.kt:2015–2017`):
`BackgroundProcessTool.currentSessionId`, `RunCommandTool.currentToolCallId`, `RunCommandTool.currentSessionId`
— gated on `toolName in STREAMING_TOOLS = {run_command, sonar, java_runtime_exec, python_runtime_exec,
runtime_exec, coverage}` (`:770`). The companion `streamCallback` is a static field (process-global) and
is fine. Once execution moves onto the executor's dispatcher thread, ThreadLocals set on the loop thread
are invisible. **Fix:** perform set→execute→clear **inside** the launched job for every eligible tool, or
migrate to a `ThreadContextElement` (aligns with the open `agent:F-28` migration). Also installed around
`executeOnce()`: `AgentLoopAttachmentScope.runWithStore(attachmentStore)` (`:2042`, a CoroutineContext
element) and the `WRITE_TOOLS` pre-edit checkpoint capture (`:2018`) — both must be installed inside the
job for backgrounded tools. **Test:** assert a tool body observes the correct `toolCallId`/`sessionId`
when run through the executor.

## 12. Settings

- **State** (`AgentSettings.State`, `:13`): `var allowToolsRunInBackground by property(true)`;
  `var maxBackgroundedToolsPerSession by property(5)` — distinct name + comment vs the existing
  `concurrentBackgroundProcessesPerSession` (`:66`, caps OS processes).
- **UI** (`AgentAdvancedConfigurable`): a "Background Tool Execution" group — checkbox + `intTextField(1..50)`,
  each with a `.comment(...)` clarifying the difference from the process cap.

## 13. Testing

**Pure / unit (no `BasePlatformTestCase` — indexing-timeout-on-2nd-test trap):**
- Reserved name parses + strips for a tool that doesn't declare it; `"true"`/`"false"`/absent.
- `isBackgroundable` (denylist + tool flag + kill switch); cap → inline-fallback.
- The pure `processToolOutput` helper (grep/spill/truncate/estimate parity).

**Coroutine (`runTest`):**
- launch → complete enqueues a framed `BACKGROUND` message with `coalesceKey`; no loop-state mutation.
- launch → detach → synth moved-to-background; job still completes + delivers.
- agent-initiated → synth started; does not await.
- cancel truth table: detach survives; non-detach loop-cancel kills the un-detached job (no leak);
  user-stop yields stopped result; tool exception → single error `ToolResult` (no `onAwait` rethrow).
- `cancelAllForSession` (new chat) cancels A's jobs; session-switch leaves them running.
- ThreadLocal/context: tool body observes correct `toolCallId`/`sessionId` via the executor (§11.1).
- backgrounded `agent` tool: cancelling the handle's job tears down its sub-agents (assert via a fake
  runner that records cancellation); a partial result is delivered on stop (D7).
- delivery: a backgrounded result reaches the conversation as a framed `addUserMessage` (not `addToolResult`),
  and POST_TOOL_USE / diff accounting do **not** fire for it (§5.3, §14).

**In-IDE smoke (deferred, like #62):** real `run_command` backgrounded by button; agent-initiated
background grep while the loop keeps working; background completion auto-wakes after `attempt_completion`
(same session); backgrounded `agent` tool.

## 14. Out of scope (YAGNI)

- A sub-agent's **internal** loop honoring `run_in_background` (parsed-and-stripped only in v1).
- For backgrounded tools: POST_TOOL_USE hooks and the in-conversation file-modified/diff accounting do
  not fire (consistent with DELEGATION/MONITOR async delivery). The tool's actual disk changes still
  occur; only the conversation-side summary/hook is skipped. The agent must not background a tool whose
  result a subsequent inline step immediately depends on — it is told what is pending via env_details (§7).
  (Lossless for the real candidates — run_command/build/test/grep/web_fetch/agent emit no `diff`.)
- Durable in-flight jobs across IDE restart (queued results remain durable).
- Backgrounding control-flow/interactive tools (D2).
- A dedicated `wait_for_background` tool (auto-wake/defersCompletion make it unnecessary).
- Cross-session auto-wake into a *foreground* different session (durable-queue + replay-on-resume instead).
- Priority scheduling among background tools beyond existing queue ordering.

## 15. Integration seams (verified against code)

| Seam | Location |
|------|----------|
| Tool-call parser | `core/.../core/ai/AssistantMessageParser.kt` — `parse(text, toolNames, paramNames)`; whitelist-drop is real. `ToolCall(id,type,function)` / `FunctionCall(name, arguments: String)` — `core/.../core/ai/dto/ToolCallModels.kt:34-45`. |
| `allParamNames()` + injection sites | `agent/.../tools/ToolRegistry.kt:265`; `AgentService.kt:734`, `:1901`, `:2464`; sub-agent `SubagentRunner`. |
| Inline exec + ThreadLocals + context | `AgentLoop.kt` — `executeToolCalls` (1838+); ThreadLocals (2015–2017); `STREAMING_TOOLS` (770); `runWithStore` (2042); post-processing (2111–2159); side effects (2186/2213/2269/2275/2298/2303/2307); type dispatch (2330); exit-drain `drainSteeringIntoContextOnExit` (2687). |
| Cancel infra (#62) | `agent/.../tools/cancel/ToolCancellationRegistry.kt`, `ToolStopCoordinator.kt`; `UserStopCancellationException`; `ProcessRegistry`. |
| Sub-agent (`agent`) | `agent/.../tools/builtin/SpawnAgentTool.kt` — `executeParallel` `supervisorScope`/`async` (`:925`), `executeSingle` (`:797`); `agent/.../tools/subagent/SubagentRunner.kt` — descendant `coroutineScope`/`abortableRunJob` (`:461`); `SubagentRunnerCancellationTest`. |
| Queue (#61) + drain | `agent/.../loop/queue/UnifiedMessageQueue.kt` (`enqueue` synchronized, `:20`); `QueueSourceKind.BACKGROUND`; `BackgroundQueuePolicy` (`durable/autoWakesIdle=true, defersCompletion=true, priority=50`); drain → `addUserMessage(withEnvDetails(...))` (`AgentLoop.kt:943-952`). |
| Enqueue + wake | `AgentService.enqueueToSession` (`:501`); `activeLoopForSession`; `IdleSessionWaker` (`:172/:186`, `DEFER_ACTIVE_SESSION`). |
| Single-active-task / lifecycle | `AgentService.kt` — `ActiveTask`/`activeTask` (`:109/110`); `executeTask`/install (`:2491`); null-on-exit (`:2609`); `cancelCurrentTask` (`:3579`); `resetForNewChat` (`:3643`); `@Service(PROJECT)`, `cs`, `Disposable`. |
| env_details | `agent/.../prompt/EnvironmentDetailsBuilder.kt` — `build(...)` (`:31`), running-processes/monitors. |
| UI | `agent/webview/src/components/agent/ToolCallChain.tsx` (`STOP_SUPPRESSED_TOOLS:32`, Stop at `:383`); bridge `_killToolCall` in `agent/.../ui/AgentCefPanel.kt`; async-card presenter (#61). |
| Settings | `agent/.../settings/AgentSettings.kt` (`:13`; existing `concurrentBackgroundProcessesPerSession:66`); `AgentAdvancedConfigurable`. |

## 16. Service-architecture compliance

`:agent` depends only on `:core`. The **only** `:core` edit is one documentation bullet in
`ToolPromptBuilder.FORMAT_INSTRUCTIONS` (the shared tool-call-format preamble) describing the
`run_in_background` attribute — sub-agents see it too but parse-and-strip it (they don't honor it in v1),
which is harmless. No `:core` *behavior* change: the reserved-tag plumbing is agent-side (`paramNames`
augmentation at the provider boundary + read/strip in the loop), and `ToolCall`/`AssistantMessageParser`
are untouched. The executor, registry, eligibility, UI, and settings live in `:agent`. Delivery reuses the
existing `:core`-typed `UnifiedMessageQueue` and `AgentService.enqueueToSession`. No new cross-module
client methods.
