# Unified LLM-Controlled Delegation Architecture

## Problem

The agent module has two disconnected planning/execution systems that don't communicate:

1. **Antigravity-style `create_plan` tool** — LLM plans, user approves, but the same LLM executes ALL steps itself in one ReAct loop. Context bloats on complex tasks.

2. **System-controlled wave executor** — `BudgetEnforcer.ESCALATE` hard-stops the session at 60% budget, system makes a SEPARATE LLM call to create a `TaskGraph`, then system-driven `executePlan()` spawns `WorkerSession` per task. The LLM loses agency, partial work is thrown away, and there are two incompatible plan formats.

If the LLM creates a plan via `create_plan` and hits budget exhaustion mid-execution, the system creates a *new* plan from scratch — discarding the existing one.

## Solution

Replace the system-controlled orchestrator with a single `delegate_task` tool that lets the LLM spawn scoped workers. The LLM becomes the orchestrator — it decides what to handle directly and what to delegate.

### Design Principles

1. **LLM decides** — the LLM controls when to plan, when to delegate, when to act directly (matches Claude Code, OpenAI Agents SDK, Devin)
2. **Fresh context per worker** — each worker gets its own `ContextManager`, preventing context pollution and enabling work that wouldn't fit in the parent's context
3. **Tool scoping** — workers get role-specific tools via existing `allowedWorkers` mechanism on each `AgentTool`, enforcing least-privilege
4. **No nesting** — workers cannot spawn workers (matches Claude Code's constraint)
5. **Sequential execution** — one worker at a time, no parallel delegation (avoids file conflict race conditions). The worker slot limit of 5 is forward-compatible for future parallel support.
6. **Budget nudge, not takeover** — at high utilization, the system nudges the LLM to delegate rather than hard-stopping the session

---

## Architecture

### New Tool: `delegate_task`

```kotlin
class DelegateTaskTool : AgentTool {
    name = "delegate_task"
    parameters:
      task: String       // REQUIRED, min 50 chars. What the worker should do.
      worker_type: String // REQUIRED. "coder" | "analyzer" | "reviewer" | "tooler"
      context: String    // REQUIRED, must contain at least one file path.
                         // Relevant context the worker needs (it can't see parent history).
                         // Should include: task context, relevant file paths, what parent
                         // already changed, active Jira ticket if relevant.
}
```

**Execution flow:**
1. Validate parameters (task length >= 50, context contains file path, worker_type valid)
2. Check active worker count < 5 (hard limit via `AtomicInteger`, forward-compatible for future parallel execution)
3. Check cumulative session tokens < configurable limit (default 500K, stored in `AgentSettings`)
4. Increment active worker count
5. Create `LocalHistory` checkpoint via `AgentRollbackManager` (for rollback on failure)
6. Create fresh `ContextManager` (150K budget)
7. Get worker-specific system prompt from `OrchestratorPrompts.getSystemPrompt(workerType)`
8. Filter tools by `workerType` via `ToolRegistry.getToolsForWorker()` (uses existing `allowedWorkers` mechanism on each `AgentTool`). The tools `delegate_task`, `create_plan`, `update_plan_step`, and `request_tools` must NOT have worker types in their `allowedWorkers` sets, which naturally excludes them.
9. Execute `WorkerSession` (max 10 iterations, 5-minute timeout via `withTimeout(300_000L)`)
10. On success: build structured `ToolResult` from worker output + artifacts list
11. On failure/timeout: revert files via `LocalHistory` checkpoint, return error result
12. Decrement active worker count (in `finally` block — ensures decrement even on exception/cancellation)
13. Log delegation telemetry (task, worker_type, tokens, duration, status)

**How file tracking works:**
Modified files are tracked via `WorkerResult.artifacts`, which is populated by tools that mutate files (e.g., `EditFileTool` adds the edited file path to `toolResult.artifacts`). Additionally, the worker system prompt instructs: "In your final response, report: status (complete/partial/failed), files you modified, and any issues encountered." `DelegateTaskTool` constructs the structured result by combining:
- `artifacts` list → `filesModified` (reliable, tool-reported)
- Worker's final text → parsed for status and issues (best-effort)
- Token usage from `WorkerResult.tokensUsed`

**Worker result format returned to parent LLM:**
```
Worker completed successfully.
Status: complete
Files modified: [src/main/kotlin/.../UserService.kt] (from tool artifacts)
Issues: none reported
Summary: Added @Valid annotation to createUser() parameter and wrote 3 test cases.
Tokens used: 12,450

Note: The above files were modified by the worker. If you need to reference
these files, re-read them as your cached version may be stale.
```

The "stale file" note is embedded in the tool result itself — no separate synthetic message injection needed. The parent LLM sees this as part of the normal `delegate_task` tool response.

**Retry limit:** Max 2 delegation attempts per task. Tracked via plan step ID when available (from `update_plan_step`), otherwise by `worker_type + sorted file paths from context` as the dedup key. This avoids brittle exact-string matching on task descriptions that the LLM may rephrase. On third attempt, returns error: "This task has failed twice. Handle it directly or skip it."

### Worker Types

Tool assignments per worker type are determined by the existing `allowedWorkers` property on each `AgentTool`. The table below documents the *expected* tool availability — the actual implementation uses `ToolRegistry.getToolsForWorker(workerType)` which reads `allowedWorkers`.

| Type | Expected Tools | System Prompt Focus |
|------|-------|-------------------|
| CODER | read, edit, search, run_command, diagnostics, format, optimize_imports | "You edit code. Complete the task and return a summary of changes." |
| ANALYZER | read, search, file_structure, find_definition, find_references, type_hierarchy, call_hierarchy | "You analyze code read-only. Return findings, do NOT edit files." |
| REVIEWER | read, search, git_status, git_blame, diagnostics | "You review code changes. Report issues, quality concerns, suggestions." |
| TOOLER | read, search, jira_*, bamboo_*, sonar_*, bitbucket_* | "You interact with enterprise tools. Complete the integration task." |

Workers cannot call: `delegate_task`, `create_plan`, `update_plan_step`, `request_tools` — enforced by not including worker types in those tools' `allowedWorkers` sets.

### Planning Unification

**Before (two systems):**
- `create_plan` tool → LLM plans → LLM executes everything itself
- `BudgetEnforcer.ESCALATE` → system `createPlan()` → system `executePlan()` with wave executor

**After (one system):**
- `create_plan` tool → LLM plans → user approves → LLM executes simple steps directly, delegates complex steps via `delegate_task`
- Budget ESCALATE → nudge message → LLM stays in control, starts delegating

The `create_plan` approval response changes from:
```
"Plan approved. Execute the plan step by step."
```
To:
```
"Plan approved. Execute the plan step by step. For simple steps (1-2 files),
handle them directly. For complex steps (3+ files, multi-step edits), use
delegate_task to spawn a focused worker. Update step status with update_plan_step
as you progress."
```

### Budget Escalation → Nudge

**Current behavior:** ESCALATE at 60% → hard stop → system takes over.

**New behavior:** Three-phase system, LLM stays in control.

| Threshold | Status | Action |
|-----------|--------|--------|
| 40% used | COMPRESS | Trigger context compression (existing behavior) |
| 60% used | NUDGE | Inject: "Context at 60%. Prefer delegate_task for remaining multi-file work." |
| 75% used | STRONG_NUDGE | Inject: "WARNING: 75% context used. You MUST use delegate_task for any task touching 2+ files. Single-file edits are still allowed directly." |
| 90% used | TERMINATE | Hard stop. Return: "Context budget exhausted at 90%. Please start a new conversation for remaining work." Prevents degraded output from near-limit context. |

`SingleAgentResult.EscalateToOrchestrated` is removed. `BudgetEnforcer.BudgetStatus` gains `NUDGE`, `STRONG_NUDGE`, and `TERMINATE` replacing the old `ESCALATE`.

### LLM-Powered Context Compression

**Current:** Truncation-based summarizer (first 500 chars). Loses critical detail.

**New:** Smart LLM-powered compression when dropping messages that contain tool results.

**Trigger logic:**
```
When compression is needed:
  1. Collect messages to drop (oldest first, skip system messages)
  2. Check if any dropped messages have role="tool" (contain file contents, search results, etc.)
  3. If YES → LLM summarization:
     - Send dropped messages to brain.chat() with prompt:
       "Summarize the following conversation segment. Preserve: file paths,
        line numbers, code changes made, errors encountered, key decisions.
        Be concise (max 500 tokens)."
     - Use the summary as the anchored summary
  4. If NO (only user/assistant text) → truncation summarizer (existing behavior, cheaper)
```

**Implementation — avoiding the suspend cascade:**
The existing `ContextManager.addMessage()` auto-triggers `compress()` when tokens exceed T_max. Making `compress()` a `suspend fun` would cascade through `addMessage()`, `addToolResult()`, `addAssistantMessage()`, and every caller — a massive refactor.

Instead, split compression into two paths:
- **`compress()` (non-suspend, existing):** Stays as-is. Called by `addMessage()` auto-trigger. Uses truncation summarizer only. No API call, no suspend needed.
- **`compressWithLlm(brain: LlmBrain)` (new, suspend):** Called explicitly by `SingleAgentSession` when `BudgetEnforcer` signals COMPRESS. Uses LLM summarization for tool results, truncation for plain text. This is the smart path.

The `brain` parameter on `ContextManager` changes from `@Deprecated brain: Any? = null` to `brain: LlmBrain? = null` (type change, deprecation removed). The auto-trigger in `addMessage()` continues to use truncation as a safety net. The explicit call from the session uses LLM when available.

**Cost:** One extra API call per LLM-powered compression. Summarization input is the dropped messages (variable, typically 30-50K tokens), output ~500 tokens. Fires roughly every 15-20 ReAct iterations when tool results are being dropped. This is a worthwhile tradeoff — preserves file paths, changes made, and errors that truncation would lose.

**Note on output tokens:** The Sourcegraph API has a 4K max output token limit per response. This applies to worker iterations as well. Each worker iteration produces at most 4K output (typically 1-2 tool calls). With 10 iterations, a worker has up to 40K cumulative output. This is sufficient for most coding tasks but should be documented as a known constraint.

### System Prompt Changes

Added to `PromptAssembler.buildSingleAgentPrompt()`:

```
<delegation>
You have access to delegate_task to spawn focused workers for specific sub-tasks.
Each worker gets a fresh context window with scoped tools — they won't see your
conversation history, so provide clear context in the task description.

Guidelines:
- Simple tasks (1-2 files, quick fix): handle yourself
- Moderate to complex tasks (3+ files, multi-step edits): delegate to a coder worker
- Analysis tasks (understand codebase, find references across modules): delegate to an analyzer worker
- Review tasks (check quality after changes): delegate to a reviewer worker
- Enterprise tool tasks (Jira, Bamboo, Sonar operations): delegate to a tooler worker
- When you create a plan and a step is non-trivial, delegate it
- Always provide the worker with: what to do, which files, and any relevant context
  from your conversation (the worker cannot see your history)
- If a delegated task fails twice, handle it yourself or skip it
</delegation>
```

Worker system prompts get one addition: "You are a focused worker agent. Complete your assigned task and return a clear summary of what you did, which files you modified, and any issues encountered. Report your status as: complete, partial, or failed."

### Resource Limits

| Resource | Limit | Enforcement |
|----------|-------|-------------|
| Active workers | 5 max (sequential for now, forward-compatible for future parallel) | `AtomicInteger` on `AgentService`, checked before spawn, decremented in `finally` |
| Worker iterations | 10 per worker | `WorkerSession.maxIterations` (existing) |
| Worker wall-clock | 5 minutes | `withTimeout(300_000L)` wrapping `WorkerSession.execute()` |
| Session tokens | Configurable, default 500K cumulative | `AtomicLong` on `AgentService`, configurable via `AgentSettings.maxSessionTokens` |
| Delegation retries | 2 per task | Tracked by plan step ID or worker_type + file paths |
| Budget termination | 90% context used | Hard stop, returns error to user |

### Delegation Telemetry

Every delegation is logged to the existing `AgentEventLog`:

```kotlin
AgentEventType.WORKER_SPAWNED     // task, worker_type, tools count
AgentEventType.WORKER_COMPLETED   // tokens_used, iterations, duration_ms, files_modified
AgentEventType.WORKER_FAILED      // error, tokens_used, iterations, duration_ms
AgentEventType.WORKER_TIMED_OUT   // partial_result, tokens_used
AgentEventType.WORKER_ROLLED_BACK // files reverted
```

### UI Visibility

When the LLM calls `delegate_task`, the chat UI shows:
- A tool badge: `[DELEGATE]` with an indeterminate progress indicator
- Status text: "Worker (coder): Implementing OAuth2 client..."
- On completion: collapsible result section showing the structured worker output
- The user can cancel the parent session (which propagates cancellation to the running worker via coroutine Job)

Individual worker cancellation is not supported in this version — the user cancels the entire session.

---

## Files Changed

### New Files

| File | Purpose |
|------|---------|
| `tools/builtin/DelegateTaskTool.kt` | The `delegate_task` tool implementation |

### Modified Files

| File | Change |
|------|--------|
| `runtime/SingleAgentSession.kt` | Replace ESCALATE handler with nudge injection at 60%, 75%, and hard stop at 90%. Remove `EscalateToOrchestrated` return. Call `contextManager.compressWithLlm(brain)` explicitly on COMPRESS signal. |
| `orchestrator/AgentOrchestrator.kt` | Remove `createPlan()`, `requestPlan()`, `executePlan()`, `runWorker()`, `parsePlanToTaskGraph()`. Remove `EscalateToOrchestrated` match arm. Simplify to single-agent-only. |
| `orchestrator/PromptAssembler.kt` | Add `<delegation>` section to system prompt. Update plan-mode prompt to mention `delegate_task`. |
| `context/ContextManager.kt` | Change `brain` param type from `@Deprecated Any?` to `LlmBrain?`. Add `suspend fun compressWithLlm(brain: LlmBrain)` for smart LLM-powered compression. Existing `compress()` stays non-suspend (truncation only). |
| `runtime/BudgetEnforcer.kt` | Replace `ESCALATE` with `NUDGE`, `STRONG_NUDGE`, `TERMINATE`. Thresholds: 40% COMPRESS, 60% NUDGE, 75% STRONG_NUDGE, 90% TERMINATE. |
| `AgentService.kt` | Add `activeWorkerCount: AtomicInteger`, `totalSessionTokens: AtomicLong`, `delegationAttempts: ConcurrentHashMap`. Register `DelegateTaskTool`. |
| `tools/ToolCategoryRegistry.kt` | Add `delegate_task` to core category. |
| `tools/DynamicToolSelector.kt` | Add `delegate_task` to `ALWAYS_INCLUDE` (never disabled, like `request_tools`). |
| `runtime/WorkerSession.kt` | Accept coroutine `Job` for cancellation. Check `job.isActive` between iterations and before tool execution. |
| `ui/AgentController.kt` | Remove `PlanReady` result handling and `executePlan` flow. Add delegate_task progress rendering. |
| `runtime/AgentEventLog.kt` | Add `WORKER_SPAWNED`, `WORKER_COMPLETED`, `WORKER_FAILED`, `WORKER_TIMED_OUT`, `WORKER_ROLLED_BACK` event types. |
| `orchestrator/OrchestratorPrompts.kt` | Remove `ORCHESTRATOR_SYSTEM_PROMPT` constant only. `getSystemPrompt(WorkerType)` remains — it returns worker prompts (CODER/ANALYZER/REVIEWER/TOOLER). Add "focused worker" instruction to each worker prompt. |
| `settings/AgentSettings.kt` | Add `maxSessionTokens: Int = 500_000` field for configurable cumulative session budget. |

### Removed Code

| What | Why |
|------|-----|
| `AgentOrchestrator.executePlan()` | System wave executor replaced by LLM calling `delegate_task` |
| `AgentOrchestrator.createPlan()` | System LLM call replaced by LLM using `create_plan` tool |
| `AgentOrchestrator.requestPlan()` | Plan mode handled by prompt rules + `create_plan` tool |
| `AgentOrchestrator.runWorker()` | Logic moved into `DelegateTaskTool.execute()` |
| `SingleAgentResult.EscalateToOrchestrated` | Replaced by nudge injection |
| `AgentResult.PlanReady` | Plan approval handled by `create_plan` tool |
| `OrchestratorPrompts.ORCHESTRATOR_SYSTEM_PROMPT` | Main LLM is the orchestrator now. Only this constant is removed — `getSystemPrompt()` and worker prompts remain. |
| `AgentMode` enum | No longer needed — always single agent with optional delegation. Note: verify `AgentMode` is not persisted in any serialized state (settings XML, checkpoints) before removal. If it is, deprecate rather than delete. |

### Kept As-Is

| What | Why |
|------|-----|
| `WorkerSession` | Used by `DelegateTaskTool` — only change is cancellation support |
| `WorkerType` enum | Used for tool scoping and worker system prompts |
| `TaskGraph`, `AgentTask` | Data structure retained for potential future use |
| `create_plan`, `update_plan_step` tools | Antigravity planning UX unchanged |
| `PlanManager`, `AgentPlan`, `PlanStep` | Plan UI unchanged |
| `CheckpointStore` | Available for crash recovery |
| Worker system prompts (CODER/ANALYZER/REVIEWER/TOOLER) | Used by `DelegateTaskTool` |

---

## Behavioral Summary

### Three Execution Paths (Unified)

**Path 1 — Simple task (no planning, no delegation):**
```
User: "Fix the NPE in UserService"
→ LLM reads file, edits, done. Single ReAct loop.
```

**Path 2 — Complex task (plan + delegation):**
```
User: "Refactor auth module to OAuth2"
→ LLM calls create_plan (5 steps) → user approves
→ Step 1 (rename): LLM handles directly
→ Step 2 (implement OAuth2 client): LLM calls delegate_task(coder, ...)
→ Step 3 (update callers): LLM calls delegate_task(coder, ...)
→ Step 4 (add tests): LLM calls delegate_task(coder, ...)
→ Step 5 (update config): LLM handles directly
→ LLM calls update_plan_step for each, summarizes result
```

**Path 3 — Budget pressure (nudge → delegation):**
```
User: "Fix all the Sonar issues" (large task)
→ LLM starts fixing issues one by one
→ At 60%: nudge "Consider delegating remaining work"
→ LLM starts using delegate_task for remaining fixes
→ At 75%: strong nudge "MUST delegate 2+ file tasks"
→ LLM delegates everything except trivial single-file fixes
→ At 90%: hard stop "Context exhausted, start new conversation"
```

### Edge Case Handling

| Edge Case | Behavior |
|-----------|----------|
| Worker fails mid-edit | `LocalHistory` checkpoint reverts files. Parent gets error with details. |
| Worker times out (5 min) | Coroutine cancelled via Job, files reverted, parent gets timeout error. |
| Worker succeeds | Parent gets structured result with file list + stale-file warning in tool result. |
| Same task fails twice | Third attempt blocked. Parent told: "Handle directly or skip." |
| 5 worker slots full | Sequential execution prevents this. Safety check returns error if triggered. |
| Session tokens > limit | `delegate_task` returns error: "Session token limit reached." Limit configurable in settings. |
| Parent cancelled by user | Running worker's coroutine Job cancelled via propagation. |
| Worker edits file parent also edited | Tool result includes "re-read these files" note. |
| LLM ignores all nudges | Hard stop at 90% prevents degraded output from near-limit context. |
| `AgentMode` serialized in state | Check before removing. If persisted, deprecate instead of delete. |
