# Agent Observability & Debugging Guide

How to debug abnormal agent behavior: premature completion, stuck loops, context loss, tool failures, and budget exhaustion.

## Setup

### 1. Enable the UI Debug Panel

Settings > Tools > Workflow Orchestrator > Agent > **Show Debug Log** (checkbox)

This shows a real-time event stream at the bottom of the agent chat panel. Events appear as they happen — LLM calls, tool executions, compressions, errors. Up to 200 entries, color-coded by level.

### 2. Know Where Files Live

All agent data is under `~/.workflow-orchestrator/{ProjectName-hash}/`:

```
~/.workflow-orchestrator/IntelijPlugin-a3f8b2/
├── agent/
│   ├── sessions/{sessionId}/
│   │   ├── messages.jsonl          ← full conversation replay
│   │   ├── metadata.json           ← session info (project, model, timestamps)
│   │   ├── checkpoint.json         ← last iteration state
│   │   ├── plan.json               ← active plan (if any)
│   │   ├── traces/
│   │   │   └── trace.jsonl         ← rich per-iteration trace
│   │   ├── api-debug/
│   │   │   ├── call-001-request.txt  ← full LLM request (messages + tools)
│   │   │   ├── call-001-response.txt ← full LLM response (content + tool calls)
│   │   │   └── call-001-error.txt    ← HTTP errors
│   │   ├── tool-outputs/
│   │   │   └── {toolCallId}.txt    ← full tool output (before pruning)
│   │   └── subagents/
│   │       └── agent-{id}.jsonl    ← subagent conversation transcripts
│   ├── metrics/
│   │   └── scorecard-{sessionId}.json  ← end-of-session quality report
│   ├── core-memory.json
│   ├── guardrails.md
│   └── archival/store.json
└── logs/
    └── agent-YYYY-MM-DD.jsonl      ← daily audit log (all sessions)
```

Your project identifier is `{dirName}-{first6OfSHA256(absolutePath)}`. To find it:

```bash
# Find your project's storage directory
ls ~/.workflow-orchestrator/ | grep "IntelijPlugin"
```

### 3. Find the Failing Session

```bash
# List recent sessions (most recent first)
ls -lt ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/

# Or search by date in the daily log
grep "session_start" ~/.workflow-orchestrator/YOUR-PROJECT/logs/agent-2026-03-26.jsonl
```

Each session directory is named by its session ID (a UUID prefix).

---

## Debugging Workflows

### "The agent stopped mid-task" (Premature Completion)

This is the most common issue. The orchestrator returned `Completed` when work remained.

**Step 1: Check the trace for the completion iteration**

```bash
# Find the last iteration and its finish_reason
grep "iteration_completed" ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/traces/trace.jsonl | tail -5
```

Look at `finishReason` and `toolsCalled`:
- `finishReason: "stop"` + `toolsCalled: []` = LLM chose to stop (no tool calls)
- `finishReason: "length"` = output token limit hit (response truncated)
- `finishReason: "tool_calls"` + no valid tools = malformed tool call

**Step 2: Check if compression happened right before**

```bash
grep -E "compression|iteration_completed" ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/traces/trace.jsonl | tail -10
```

If you see `compression` immediately followed by `iteration_completed` with empty `toolsCalled`, the LLM lost context during compression and summarized instead of continuing.

**Step 3: Read the final LLM response**

```bash
# Find the last api-debug response file
ls -t ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/api-debug/ | head -2
```

Read the last `call-NNN-response.txt`. Check:
- Did the LLM produce a summary/recap instead of tool calls?
- Did it say "I've completed the changes" without actually finishing?
- Was the content truncated (look for cut-off mid-sentence)?

**Step 4: Check plan progress**

```bash
cat ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/plan.json | python3 -m json.tool
```

If there's an active plan with incomplete steps but the session completed, the orchestrator lacks a plan-awareness completion check (known gap — see "Known Gaps" below).

---

### "The agent is stuck in a loop"

**Step 1: Check for doom loop detection**

```bash
grep "doom_loop\|LOOP_DETECTED" ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/traces/trace.jsonl
```

The LoopGuard detects 3 identical consecutive tool calls and injects a warning.

**Step 2: Check tool error patterns**

```bash
# From the daily log — tool calls with errors
grep "\"event\":\"tool_call\"" ~/.workflow-orchestrator/YOUR-PROJECT/logs/agent-TODAY.jsonl | grep "\"status\":\"error\""
```

Circuit breaker trips after 5 consecutive failures on the same tool.

**Step 3: Read the conversation flow**

```bash
# Count messages by role
cat ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/messages.jsonl | python3 -c "
import sys, json
roles = {}
for line in sys.stdin:
    msg = json.loads(line)
    r = msg.get('role', 'unknown')
    roles[r] = roles.get(r, 0) + 1
print(roles)
"
```

A healthy session has roughly equal user/assistant/tool messages. If you see many consecutive `user` messages (system injections), the agent is being nudged repeatedly.

---

### "The agent hit the budget limit"

**Step 1: Check budget progression in the trace**

```bash
grep "iteration_started" ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/traces/trace.jsonl | \
  python3 -c "import sys,json; [print(f'Iter {json.loads(l)[\"iteration\"]}: {json.loads(l)[\"budgetPercent\"]}%') for l in sys.stdin]"
```

Budget thresholds:
| % | Status | What happens |
|---|--------|-------------|
| < 80% | OK | Normal operation |
| 80-88% | COMPRESS | Auto-compression triggered |
| 88-93% | NUDGE | "Consider delegating" message injected |
| 93-97% | STRONG_NUDGE | "Conclude immediately" message injected |
| >= 97% | TERMINATE | Session ends (context rotation if plan exists, else failure) |

**Step 2: Check compression events**

```bash
grep "compression" ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/traces/trace.jsonl
```

Fields: `tokensBefore`, `tokensAfter`, `messagesDropped`, `compressionTrigger`. If compression removes too many messages, the agent loses context.

---

### "Tool calls are failing"

**Step 1: Check tool metrics in the scorecard**

```bash
cat ~/.workflow-orchestrator/YOUR-PROJECT/agent/metrics/scorecard-SESSION_ID.json | python3 -m json.tool
```

The `qualitySignals` section shows:
- `doomLoopTriggers` — identical tool call repetitions
- `circuitBreakerTrips` — tools hitting 5+ consecutive errors
- `filesEditedCount` / `filesVerifiedCount` / `filesExhaustedCount` — edit-verify success rate

**Step 2: Check individual tool failures**

```bash
grep "tool_executed" ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/traces/trace.jsonl | \
  python3 -c "import sys,json; [print(f'{json.loads(l)[\"toolName\"]}: {\"ERROR\" if json.loads(l).get(\"isError\") else \"ok\"} ({json.loads(l).get(\"durationMs\",0)}ms)') for l in sys.stdin]"
```

**Step 3: Check the full tool output**

Tool outputs are saved to disk before any pruning:

```bash
ls ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/tool-outputs/
# Then read the specific tool call output
cat ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/tool-outputs/TOOL_CALL_ID.txt
```

---

### "The LLM response doesn't make sense"

**Step 1: Read the exact request sent to the LLM**

```bash
cat ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/api-debug/call-NNN-request.txt
```

This shows:
- The model used
- Total message count and body length
- Every message with role, content preview (first 1000 + last 1000 chars), and tool calls
- Tool definitions sent

**Step 2: Read the exact response**

```bash
cat ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/api-debug/call-NNN-response.txt
```

This shows:
- Token usage (prompt/completion/total)
- `FinishReason` — why the LLM stopped
- Full content and tool calls

**Step 3: Check context truncation**

Compare the request's message count across iterations. A sudden drop means compression happened. Check if important context was lost:

```bash
grep "http_request" ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/traces/trace.jsonl | \
  python3 -c "import sys,json; [print(f'Call: {json.loads(l).get(\"messageCount\",0)} msgs, {json.loads(l).get(\"httpBodyLength\",0)} chars') for l in sys.stdin]"
```

---

### "The agent keeps trying to complete but gets blocked"

**Step 1: Check completion metrics in the scorecard**

```bash
cat ~/.workflow-orchestrator/YOUR-PROJECT/agent/metrics/scorecard-SESSION_ID.json | python3 -m json.tool | grep -A5 completion
```

Fields: `completionAttemptCount`, `completionGateBlocks` (map of gate→count), `forcedCompletionCount`, `nudgeCount`.

**Step 2: Check gate blocks in the trace**

```bash
grep "attempt_completion" ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/traces/trace.jsonl
```

Tool calls with `isError=true` and content containing "COMPLETION BLOCKED" indicate which gate blocked.

**Step 3: Check if force-accept was triggered**

If `forcedCompletionCount > 0`, the agent was force-accepted after 5 blocked attempts. Check what was actually incomplete — the plan may have aspirational steps that should have been skipped.

---

### "I need to replay a full session"

The `messages.jsonl` file is a complete, append-only conversation transcript:

```bash
# Pretty-print all messages
cat ~/.workflow-orchestrator/YOUR-PROJECT/agent/sessions/SESSION_ID/messages.jsonl | \
  python3 -c "
import sys, json
for i, line in enumerate(sys.stdin):
    msg = json.loads(line)
    role = msg.get('role', '?')
    content = (msg.get('content') or '')[:200]
    tools = msg.get('toolCalls', [])
    tool_str = f' [{len(tools)} tool calls]' if tools else ''
    print(f'[{i}] {role}{tool_str}: {content}')
"
```

---

## Quick Reference: Event Types

### Daily Log Events (`logs/agent-YYYY-MM-DD.jsonl`)

| Event | When | Key Fields |
|-------|------|-----------|
| `session_start` | Session begins | task description, tool count |
| `iteration` | Each ReAct loop pass | promptTokens, completionTokens, finishReason, toolsCalled |
| `tool_call` | Each tool execution | tool name, status (success/error/skipped), duration, args |
| `compression` | Context compressed | trigger, tokens before/after |
| `retry` | Rate limit or context overflow | reason, iteration |
| `malformed_tool_call` | Unparseable tool JSON | tool name, raw args |
| `session_end` | Session completes/fails | iterations, total tokens, duration, error (if failed) |

### Trace Events (`traces/trace.jsonl`)

| Event | When | Key Fields |
|-------|------|-----------|
| `session_started` | Session begins | task, toolCount, effectiveBudget |
| `iteration_started` | Before each LLM call | iteration, budgetUsedTokens, budgetPercent |
| `http_request` | LLM API call sent | messageCount, httpBodyLength, toolCount, maxOutputTokens |
| `http_response` | LLM API response received | promptTokens, completionTokens, finishReason, durationMs |
| `iteration_completed` | After processing response | toolsCalled, finishReason |
| `tool_executed` | Each tool result | toolName, durationMs, isError, resultTokens |
| `compression` | Context reduction | compressionTrigger, tokensBefore, tokensAfter, messagesDropped |
| `conversation_dump` | On failure/debug | dumpReason, messageSummaries (role, length, preview, tokenEstimate) |
| `rate_limit_retry` | 429 retry | retryAttempt, backoff duration |
| `context_exceeded_retry` | Context overflow | toolCountBefore, tokensBefore |
| `session_completed` | Success terminal | totalTokens, iteration, artifacts |
| `session_failed` | Failure terminal | error, totalTokens, iteration |
| `session_metrics` | End of session | full AgentMetrics JSON snapshot |

### API Debug Files (`api-debug/call-NNN-*.txt`)

| File | Contents |
|------|----------|
| `call-NNN-request.txt` | Model, message count, body length, all messages (role + content preview + tool calls), tool definitions |
| `call-NNN-response.txt` | Token usage, FinishReason, content, tool calls with args |
| `call-NNN-error.txt` | HTTP status code, error body |

---

## Known Gaps vs Enterprise Agentic Tools

### 1. No Plan-Aware Completion Check

**Problem:** If the LLM responds without tool calls, the orchestrator accepts it as completion — even if an active plan has incomplete steps.

**What Claude Code does:** Checks plan progress before accepting completion. If steps remain, injects "you still have steps X, Y, Z remaining."

**How to detect:** Check `plan.json` for incomplete steps when the session ended as `completed`.

### 2. No Post-Compression Continuation Signal

**Problem:** After context compression, the LLM may respond with a vague summary ("I've completed the changes") instead of continuing work. The `TOOL_INTENT_PATTERNS` check only catches explicit phrases like "let me check" — not passive summarization.

**What to look for:** A `compression` trace event immediately followed by `session_completed` with the final message being a summary, not a tool call.

### Addressed Gaps (v0.34.0-agent-beta.50+)

1. **Plan-aware completion** — `CompletionGatekeeper` checks plan progress before accepting. Blocks with specific step list if incomplete.
2. **Post-compression continuation** — Blocks completion for 2 iterations after compression, forcing re-orientation.
3. **Explicit completion tool** — `attempt_completion` replaces passive "no tool calls = done". LLM must actively declare completion.

---

### 3. finish_reason Default When Missing

**Problem:** `SourcegraphChatClient` defaults `finishReason` to `"stop"` at line 298. If the streaming connection drops the final chunk, the response looks like a clean stop but is actually incomplete.

**What to look for:** A `http_response` trace entry where `promptTokens` and `completionTokens` are both 0 or null but `finishReason` is `"stop"`.

---

## Cheat Sheet

```bash
# Set your project dir
PROJ=~/.workflow-orchestrator/YOUR-PROJECT-HASH

# List sessions (most recent first)
ls -lt $PROJ/agent/sessions/

# Quick session summary
SID=SESSION_ID
grep "iteration_completed" $PROJ/agent/sessions/$SID/traces/trace.jsonl | wc -l  # iteration count
grep "session_" $PROJ/agent/sessions/$SID/traces/trace.jsonl | tail -1           # how it ended

# Budget progression
grep "iteration_started" $PROJ/agent/sessions/$SID/traces/trace.jsonl | python3 -c "import sys,json;[print(f'Iter {json.loads(l)[\"iteration\"]}: {json.loads(l)[\"budgetPercent\"]}%') for l in sys.stdin]"

# Compression events
grep "compression" $PROJ/agent/sessions/$SID/traces/trace.jsonl

# Tool errors
grep "tool_executed" $PROJ/agent/sessions/$SID/traces/trace.jsonl | python3 -c "import sys,json;[print(f'{json.loads(l)[\"toolName\"]}: ERROR — {json.loads(l).get(\"error\",\"\")}') for l in sys.stdin if json.loads(l).get('isError')]"

# Last LLM response
ls -t $PROJ/agent/sessions/$SID/api-debug/*response* | head -1 | xargs cat

# Scorecard
cat $PROJ/agent/metrics/scorecard-$SID.json | python3 -m json.tool
```
