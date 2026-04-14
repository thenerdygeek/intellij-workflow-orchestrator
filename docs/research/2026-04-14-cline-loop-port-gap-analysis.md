# Cline ReAct Loop Port — Gap Analysis

**Date**: 2026-04-14
**Cline version**: Latest main (cloned 2026-04-14)
**Our file**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`
**Cline file**: `src/core/task/index.ts` (main loop), `src/core/task/ToolExecutor.ts` (tool execution)

## Architecture Comparison

| Aspect | Cline | Ours |
|--------|-------|------|
| Loop style | Two-level: outer `initiateTaskLoop()` while-loop + inner recursive `recursivelyMakeClineRequests()` | Single `while` loop with iteration counter |
| Iteration limit | **None** — runs until completion, cancel, or context exhaustion | Hard cap at `maxIterations=200` |
| Streaming | Async generator `ApiStream` + `StreamChunkCoordinator` | Callback-based `chatStream` with `onChunk` lambda |
| Tool execution | `ToolExecutor.executeTool()` called from `presentAssistantMessage()` | Inline `executeToolCalls()` within the loop body |
| State mutations | `stateMutex` (Mutex) guarded | `AtomicBoolean` for cancel, local variables for counters |
| Result type | `boolean` (didEndLoop) | `sealed class LoopResult` (Completed, Failed, Cancelled, SessionHandoff) |

## Correctly Ported Features

These are faithfully ported and working correctly:

- **Text-only nudge**: TEXT_ONLY_NUDGE matches `formatResponse.noToolsUsed()`
- **Empty response distinction**: Empty (provider error) vs text-only (model mistake) — separate counters
- **Loop detection**: Soft warn at 3, hard at 5 identical calls (via `LoopDetector`)
- **Approval gate**: APPROVED/DENIED/ALLOWED_FOR_SESSION matches Cline's 3-choice approval
- **Plan mode enforcement**: Write tools blocked, read tools allowed
- **Hooks**: PRE_TOOL_USE (cancellable) + POST_TOOL_USE (observation-only)
- **Checkpoints**: After write operations, named checkpoints created
- **Streaming persistence**: `partial: true` flag on streaming chunks, flipped on completion
- **Abort stream**: Synthetic assistant turn with interrupt marker on cancel/error
- **API message persistence**: Two-file JSON (api_conversation_history + ui_messages)
- **Task progress**: Extracted from tool call arguments, markdown checklist
- **Context compaction**: Triggered on threshold (our 4-stage pipeline is MORE sophisticated than Cline's)
- **Token tracking**: Cumulative input/output tokens, per-call updates

## Gaps Found

### GAP 1 — CRITICAL: Loop Detection Hard Limit Terminates Instead of Asking User

**Cline behavior** (`ToolExecutor.ts:580-598`):
Hard escalation (5 identical calls) sets `consecutiveMistakeCount = maxConsecutiveMistakes`.
This redirects to the mistake-limit path on the next iteration, which **asks the user** for feedback.
The user can provide guidance and the task continues.

**Our behavior** (`AgentLoop.kt:926-934`):
```kotlin
LoopStatus.HARD_LIMIT -> {
    reportToolError(call, startTime, LOOP_HARD_FAILURE)
    return makeFailed("Loop detected: '$toolName' called ${loopDetector.currentCount} times...", iteration)
}
```
Hard limit **immediately returns `makeFailed()`** — no user intervention possible.

**Impact**: User loses all session work when the LLM gets stuck in a loop. In Cline, the user can steer the model out of the loop. This is especially bad for long sessions with many edits.

**Fix**: On HARD_LIMIT, if `userInputChannel != null`, inject an error message and wait for user input (like the consecutiveMistakes path). Only `makeFailed` if no user input channel (sub-agent).

---

### GAP 2 — HIGH: Empty Response Max Retries Hard-Fails Instead of Asking User

**Cline behavior** (`index.ts:3267-3310`):
After 3 auto-retries (with 2s/4s/8s exponential delays), Cline **asks the user** to retry or cancel via `ask("api_req_failed")`. User can click "Retry" to reset the counter and try again.

**Our behavior** (`AgentLoop.kt:854-858`):
```kotlin
if (consecutiveEmpties >= MAX_CONSECUTIVE_EMPTIES) {
    return makeFailed("Provider returned $MAX_CONSECUTIVE_EMPTIES consecutive empty responses...", iteration)
}
```
Hard fails immediately after 3 empties. No delay between retries. No user choice.

**Impact**: Transient provider issues that would recover with one more retry terminate the session.

**Fix**: (a) Add exponential delay between empty retries (2s, 4s, 8s). (b) After max retries, if `userInputChannel != null`, ask user instead of hard-failing.

---

### GAP 3 — HIGH: No Delay Between Empty Response Retries

**Cline behavior**: Exponential backoff between empty retries: `delay = 2000 * 2^(attempt-1)` → 2s, 4s, 8s.

**Our behavior**: Immediate retry — inject `EMPTY_RESPONSE_ERROR` message and loop back to LLM call with zero delay.

**Impact**: Rapid-fire API calls during provider outages. Wastes API budget and may trigger rate limiting.

**Fix**: Add `delay(INITIAL_RETRY_DELAY_MS * (1L shl (consecutiveEmpties - 1)))` before continuing after empty response.

---

### GAP 4 — MEDIUM: Tool Parameter Errors Don't Increment Mistake Counter

**Cline behavior** (`WriteToFileToolHandler.ts`, `ReadFileToolHandler.ts`, etc.):
Each tool handler increments `consecutiveMistakeCount` when required parameters are missing/invalid (52 increment locations across all handlers). Each handler resets to 0 on success (26 reset locations).

**Our behavior**:
`consecutiveMistakes` is a local variable in `run()`. Tool handlers have no access to it. Tool parameter errors are reported via `reportToolError()` as tool results, but don't affect the mistake counter.

**Impact**: If the LLM repeatedly calls tools with bad parameters, in Cline it escalates to user feedback after 3 mistakes. In ours, it loops indefinitely (or until `maxIterations`). Our loop detection partially mitigates this (catches identical calls) but doesn't catch the case where the model tries different-but-still-wrong parameters.

**Fix consideration**: Pass a mistake counter callback to tools, or check tool results for errors in the main loop and increment. Alternatively, accept this as a design decision since our `maxIterations` guard prevents infinite loops.

---

### GAP 5 — MEDIUM: User Feedback Doesn't Reset All Error State

**Cline behavior** (`index.ts:2432-2437`):
When user provides feedback after mistake limit, Cline resets ALL error counters:
```typescript
this.taskState.consecutiveMistakeCount = 0
this.taskState.autoRetryAttempts = 0
this.taskState.consecutiveIdenticalToolCount = 0
this.taskState.lastToolName = ""
this.taskState.lastToolParams = ""
```

**Our behavior** (`AgentLoop.kt:835-839`):
Only resets `consecutiveMistakes = 0`. Does NOT reset `consecutiveEmpties`, `apiRetryCount`, or `loopDetector`.

**Impact**: After user provides feedback, the loop detection state is still active. If the model happens to call the same tool again (even correctly), it could immediately trigger the soft/hard warning again.

**Fix**: After receiving user feedback, also reset `consecutiveEmpties = 0` and `loopDetector.reset()`.

---

### GAP 6 — MEDIUM: No Resume After Cancel

**Cline behavior** (`index.ts:1535-1612`):
After cancellation, Cline presents a "Resume" button (`ask("resume_task")`). User can click it to resume from the exact cancellation point via `resumeTaskFromHistory()`, which reloads the conversation history and continues.

**Our behavior** (`AgentLoop.kt:865-868`):
Returns `LoopResult.Cancelled`. `AgentController.cancelTask()` clears all state. No resume option.

**Impact**: User must start a new session after cancellation. All in-progress work context is lost.

**Fix consideration**: This is partially mitigated by our session persistence — the user can resume a session from the history view. The gap is that there's no immediate "Resume" button in the cancel flow. Lower priority if session resume works reliably.

---

### GAP 7 — LOW: API Error Max Retries Hard-Fails Instead of Asking User

**Cline behavior** (`index.ts:2100-2178`):
After 3 auto-retries with exponential delays, asks user via `ask("api_req_failed")`. User can click "Retry" (resets counter) or cancel.

**Our behavior** (`AgentLoop.kt:720-723`):
After 5 retries (3 for timeout), returns `makeFailed()` immediately.

**Impact**: Minor — our 5-retry budget is more generous than Cline's 3, and our L1-fallback/L1-recycle/L2-escalation recovery layers don't exist in Cline. We're MORE resilient on the retry side but less on the user-choice side.

**Fix consideration**: After all retries + recovery layers exhausted, if `userInputChannel != null`, ask user before failing.

---

### GAP 8 — LOW: Cline's `autoRetryAttempts` Is Unified, Ours Are Separate

**Cline**: ONE `autoRetryAttempts` counter shared across API errors, streaming errors, and empty responses. All use the same exponential backoff formula.

**Ours**: THREE separate counters: `apiRetryCount` (API errors), `consecutiveEmpties` (empty responses), `consecutiveMistakes` (text-only). Each has its own budget and behavior.

**Impact**: In Cline, an API error followed by an empty response counts as 2 total retries against a budget of 3. In ours, each starts fresh. This means ours is actually MORE resilient (each error category gets its full budget), but less "conservative" about total retries.

**Fix**: None needed — separate counters is arguably better design. Document as intentional divergence.

---

### GAP 9 — LOW: No `doubleCheckCompletion` Pattern

**Cline**: `doubleCheckCompletionEnabled` setting requires the model to call `attempt_completion` twice — once to trigger re-verification, once to actually complete.

**Ours**: `CompletionGatekeeper` with 3 gates (Plan, SelfCorrection, LoopGuard). More sophisticated but different mechanism. Force-accepts after 5 blocked attempts.

**Impact**: None — our gatekeeper is MORE thorough. Not a gap, but a different design.

---

## Features We Have That Cline Doesn't

| Feature | Description |
|---------|-------------|
| **3-tier API recovery** | L1-Fallback (model chain), L1-Recycle (same-model fresh socket), L2-Tier Escalation |
| **Steering messages** | Mid-turn user message injection (from Claude Code, not Cline) |
| **4-stage compaction pipeline** | SmartPruner → ObservationMasking → ConversationWindow → LLMSummarizing |
| **maxIterations guard** | Hard cap at 200 iterations (Cline has none) |
| **Parallel read tools** | Read-only tools execute concurrently via coroutineScope+async |
| **CompletionGatekeeper** | 3-gate completion verification (Plan + SelfCorrection + LoopGuard) |
| **Context overflow counter** | Up to 2 auto-retries (Cline allows only 1) |
| **Finish reason: length** | Explicit truncated response handling with continuation prompt |
| **Session handoff** | `new_task` returns `LoopResult.SessionHandoff` for structured context transfer |
| **Compaction on timeout** | Optional compact-and-retry when timeout retries exhausted |

## Priority Fix Order (Loop Gaps)

1. **GAP 1** (CRITICAL) — Loop detection hard limit → ask user instead of failing
2. **GAP 2 + 3** (HIGH) — Empty response: add delays + ask user after max
3. **GAP 5** (MEDIUM) — Reset all error state on user feedback
4. **GAP 4** (MEDIUM) — Consider tool error → mistake counter integration
5. **GAP 7** (LOW) — API error exhaustion → ask user
6. **GAP 6** (LOW) — Resume after cancel (partial mitigation via session history)

---

# Part 2: Tool Calling & Tool Parsing Gap Analysis

## Architecture Comparison

| Aspect | Cline | Ours |
|--------|-------|------|
| Tool call formats | Dual: native `tool_calls` + XML fallback, per-provider switching | Dual: native `StreamToolCallDelta` + XML fallback, native-first with auto-fallback |
| XML parser | `parseAssistantMessageV2()` — character-by-character, pre-computed tag maps | `AssistantMessageParser.parse()` — faithful Kotlin port, same algorithm |
| Streaming assembly | `ToolCallProcessor` (stateful generator, yields per-chunk) | `ToolCallBuilder` map (accumulate-at-end) |
| Tool result format | Provider-specific: `{ role: "tool" }` for OpenAI, `tool_result` block for Anthropic | Single format: `ChatMessage(role="tool")` → sanitized to `ChatMessage(role="user", content="TOOL RESULT:\n...")` |
| Tool dispatch | `ToolExecutorCoordinator` with `IToolHandler` interface, full `TaskConfig` | `Map<String, AgentTool>` with `ToolRegistry`, `execute(params, project)` |
| Partial block UI | `handlePartialBlock()` shows tool params streaming in | No partial block handling — tools shown after stream completes |
| Loop detection timing | AFTER tool execution (tool executes, then check) | BEFORE tool execution (tool blocked on hard limit) |
| Error handling | Handler-level `consecutiveMistakeCount` increments (52 locations) | Loop-level only — tools report errors but don't affect mistake counter |

## Correctly Ported Tool Features

These are faithfully ported and working correctly:

- **XML parser**: `AssistantMessageParser` is a direct port of `parseAssistantMessageV2()` with same algorithm (index-based scanning, code-carrying param `lastIndexOf`, partial tracking, `stripPartialTag`)
- **Native tool call assembly**: Both use index-keyed accumulators for streaming deltas
- **Dual-mode fallback**: Native-first, XML if native yields no tool calls
- **Tool approval gate**: APPROVED/DENIED/ALLOWED_FOR_SESSION matches Cline's 3-way approval
- **Plan mode blocking**: Write tools blocked in plan mode with error reporting
- **Tool error reporting**: Unknown tools, malformed JSON, execution exceptions → all reported as tool results
- **Loop detection**: Same soft (3) / hard (5) thresholds with identical signature comparison
- **Pre/Post tool hooks**: PRE_TOOL_USE (cancellable) + POST_TOOL_USE (observation-only)
- **Task progress extraction**: `task_progress` param extracted from every tool call, updates UI
- **Tool name/param resolution**: Dynamic providers (`toolNameProvider`, `paramNameProvider`) refreshed per iteration

## Tool Calling Gaps Found

### TOOL-GAP 1 — HIGH: Tool Results Lose tool_call_id Correlation

**Cline behavior** (`openai-format.ts:104-134`):
Tool results sent as `{ role: "tool", tool_call_id: "call_xxx", content: "..." }` messages, preserving the correlation between the assistant's `tool_calls[].id` and the result. The API uses this to match which result belongs to which tool call.

**Our behavior** (`SourcegraphChatClient.kt:177-183`):
```kotlin
"tool" -> {
    val toolContent = "TOOL RESULT:\n${msg.content ?: ""}"
    converted.add(ChatMessage(role = "user", content = toolContent))
}
```
ALL tool results converted to user role with plain text prefix. The `toolCallId` is **silently dropped** during sanitization. The model never sees the correlation.

**Impact**: For single tool calls, this is fine — the model infers the result belongs to its last tool call. For **parallel/multiple tool calls** in one response, the model can't tell which result belongs to which call. This causes confusion when 2+ tools are called simultaneously.

**Why this exists**: Comment says `"tool" role may not pass through the proxy`. This was defensive coding for Sourcegraph's API. If Sourcegraph now supports native `tool` role, we should preserve it.

**Fix**: Check if Sourcegraph API supports `tool` role. If yes, keep `role = "tool"` with `toolCallId` intact for native tool calls. Only fall back to user-role conversion for XML-based tool calls.

---

### TOOL-GAP 2 — MEDIUM: No Partial Tool Block UI During Streaming

**Cline behavior** (`ToolExecutor.ts:520-531`):
During streaming, partial tool blocks get UI updates via `handlePartialBlock()`. Users see tool parameters appearing in real-time (e.g., "editing file: /src/main.kt" appears as the path parameter streams in). Users can reject a tool BEFORE it's fully formed.

**Our behavior** (`AgentLoop.kt:501-559`):
Only `TextContent` blocks are shown during streaming. Tool calls are invisible until the stream completes, then they appear all at once. Approval gate runs after full assembly.

**Impact**: Lower UX quality — users can't see what the model is planning to do while it's thinking. They see text streaming, then suddenly a tool approval card appears. In Cline, users see the tool forming and can reject early, saving time.

**Fix consideration**: This requires significant architecture changes (streaming tool param display in JCEF, partial approval UI). Medium priority — functional correctness is not affected.

---

### TOOL-GAP 3 — MEDIUM: Loop Detection Runs Before Execution (Cline Runs After)

**Cline behavior** (`ToolExecutor.ts:580-598`):
Loop detection runs AFTER tool execution and AFTER pushing the tool result. The 5th identical call DOES execute — then the state is updated to trigger user feedback on the next iteration.

```typescript
toolResult = await this.coordinator.execute(config, block)  // ← Tool executes
this.pushToolResult(toolResult, block)  // ← Result pushed
const loopCheck = checkRepeatedToolCall(...)  // ← THEN check
if (loopCheck.hardEscalation) {
    this.taskState.consecutiveMistakeCount = max  // ← Redirect to user
}
```

**Our behavior** (`AgentLoop.kt:924-944`):
Loop detection runs BEFORE tool execution. On hard limit, the tool is **blocked from executing** and `makeFailed()` is returned immediately.

```kotlin
when (loopDetector.recordToolCall(toolName, call.function.arguments)) {
    LoopStatus.HARD_LIMIT -> {
        reportToolError(call, startTime, LOOP_HARD_FAILURE)
        return makeFailed(...)  // ← Tool NEVER executes
    }
}
// ... tool.execute(params, project)  // ← Only reached if OK or SOFT_WARNING
```

**Impact**: Two differences:
1. Cline lets the 5th call execute (might produce useful side effects); ours blocks it
2. Cline redirects to user feedback; ours hard-fails (already covered in Loop GAP 1)

**Fix**: Move loop detection to AFTER tool execution (but keep the hard-limit → user feedback fix from GAP 1).

---

### TOOL-GAP 4 — MEDIUM: No `attempt_completion` Parameter Canonicalization

**Cline behavior** (`ToolExecutor.ts:34-41`):
```typescript
function canonicalizeAttemptCompletionParams(block: ToolUse): boolean {
    if (block.name === "attempt_completion" && !block.params?.result && typeof block.params?.response === "string") {
        block.params.result = block.params.response  // ← Auto-fix
        return true
    }
    return false
}
```
If model uses `response` instead of `result` param for `attempt_completion`, Cline silently fixes it.

**Our behavior**: No canonicalization. Missing `result` param would be treated as a tool execution error.

**Impact**: Minor — models rarely confuse these, but when they do, Cline recovers gracefully while ours reports an error and the model retries.

**Fix**: Add param canonicalization in `AttemptCompletionTool.execute()`.

---

### TOOL-GAP 5 — LOW: No PostToolUse Hook Context Modification

**Cline behavior** (`ToolExecutor.ts:416-447`):
PostToolUse hooks can return `contextModification: string`, which is injected into the conversation as `<hook_context>` XML. This allows hooks to add workspace rules, lint results, or test feedback after tool execution.

**Our behavior** (`AgentLoop.kt:1151-1170`):
PostToolUse hooks are observation-only. No context modification support.

**Impact**: Reduces extensibility — users can't inject automated feedback after tool calls (e.g., "linter found 3 issues after your edit").

**Fix**: Add `contextModification` field to `HookResult`, inject as user message if present.

---

### TOOL-GAP 6 — LOW: No Tool Rejection Mid-Stream

**Cline behavior** (`index.ts:2969-2975`):
`didRejectTool` flag interrupts streaming. All remaining tools in the response are skipped with rejection messages. `"[Response interrupted by user feedback]"` appended.

**Our behavior**: No mid-stream rejection. Approval gate runs after stream completes. Each tool is individually approved/denied, but the stream is never interrupted.

**Impact**: In Cline, rejecting one tool stops all subsequent tools immediately (fast). In ours, the full response streams, then each tool is individually processed (slower but more deliberate).

**Fix consideration**: Low priority — our approach is valid and arguably better for review workflows.

---

### TOOL-GAP 7 — LOW: Concatenated JSON Recovery (We're Better)

**Cline**: No explicit handling for concatenated JSON in tool call arguments.

**Ours** (`SourcegraphChatClient.kt:425-447`): Explicit detection and splitting of `}{}` patterns from Sourcegraph API merging parallel tool calls. Recovers first tool call.

**This is NOT a gap — we're better here.**

---

### TOOL-GAP 8 — LOW: Streaming Drop Recovery (We're Better)

**Cline**: No explicit handling for `finish_reason=tool_calls` with empty tool call deltas.

**Ours** (`SourcegraphChatClient.kt:408-417`): Detects this Sourcegraph-specific bug and falls back to non-streaming endpoint to recover tool calls.

**This is NOT a gap — we're better here.**

---

## Tool Features We Have That Cline Doesn't

| Feature | Description |
|---------|-------------|
| **3-tier tool registry** | Core (always) + deferred (tool_search) + active (loaded mid-session) |
| **Streaming drop recovery** | Fallback to non-streaming when tool deltas missing |
| **Concatenated JSON splitting** | Recovers from Sourcegraph API merging parallel calls |
| **Dynamic tool definitions** | `toolDefinitionProvider` refreshes tool schemas per iteration |
| **Tool token accounting** | `TokenEstimator.estimateToolDefinitions()` tracks tools in context budget |
| **Risk assessment** | `CommandSafetyAnalyzer` classifies run_command risk level |
| **Write checkpoints** | Named checkpoints after write tool operations |

## Priority Fix Order (Tool Calling Gaps)

1. **TOOL-GAP 1** (HIGH) — Preserve tool_call_id for native tool calls
2. **TOOL-GAP 3** (MEDIUM) — Move loop detection to after execution
3. **TOOL-GAP 2** (MEDIUM) — Partial tool block UI during streaming (large effort)
4. **TOOL-GAP 4** (MEDIUM) — attempt_completion param canonicalization
5. **TOOL-GAP 5** (LOW) — Hook context modification support
6. **TOOL-GAP 6** (LOW) — Mid-stream tool rejection

---

# Combined Priority List (All Gaps)

## Critical
1. **GAP 1** — Loop detection hard limit terminates instead of asking user

## High
2. **GAP 2+3** — Empty response: add delays + ask user after max retries
3. **TOOL-GAP 1** — Tool results lose tool_call_id correlation in sanitization

## Medium
4. **GAP 5** — User feedback doesn't reset all error state
5. **GAP 4** — Tool parameter errors don't increment mistake counter
6. **TOOL-GAP 3** — Loop detection runs before execution (Cline runs after)
7. **TOOL-GAP 4** — No attempt_completion parameter canonicalization
8. **TOOL-GAP 2** — No partial tool block UI during streaming (large effort)

## Low
9. **GAP 7** — API error exhaustion hard-fails instead of asking user
10. **GAP 6** — No immediate resume after cancel
11. **TOOL-GAP 5** — No hook context modification
12. **TOOL-GAP 6** — No mid-stream tool rejection
