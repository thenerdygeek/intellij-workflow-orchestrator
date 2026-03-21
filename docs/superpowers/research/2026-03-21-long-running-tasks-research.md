# Long-Running Task Management in Agentic AI Coding Tools

Research date: 2026-03-21
Tools analyzed: Aider (Python), OpenCode (Go/TypeScript), Claude Code (CLI)

---

## 1. Iteration Limits and Timeouts

### Aider
- **Max reflections**: Hardcoded `max_reflections = 3` in `base_coder.py:101`. When the LLM produces an error (lint fail, test fail), aider will auto-retry ("reflect") up to 3 times before stopping with a warning: `"Only 3 reflections allowed, stopping."`
- **No iteration limit on the main loop**: The outer `run()` loop runs indefinitely until the user sends EOF or interrupts. There is no configurable max-iterations-per-task.
- **No time limit**: No timeout on individual tasks or the entire session.
- **No budget warning**: No "80% of budget" indicator. The tool simply stops after max reflections.

### OpenCode
- **Configurable `steps` per agent**: In `config.ts:743`, each agent has an optional `steps` field (formerly `maxSteps`): "Maximum number of agentic iterations before forcing text-only response." When `step >= maxSteps`, the agent is forced to stop tool use (`prompt.ts:563-564`).
- **Default is `Infinity`**: If not configured, agents run with no iteration limit.
- **No time limit**: No hard timeout on tasks.
- **Doom loop detection**: `DOOM_LOOP_THRESHOLD = 3` in `processor.ts:21`. If the last 3 tool calls are identical (same tool, same input), OpenCode triggers a `"doom_loop"` permission prompt asking the user whether to continue. This prevents infinite retry spirals.

### Claude Code
- **No publicly documented iteration limit**: Claude Code runs the agent loop until the task completes or context exhausts.
- **Possible 612-second timeout**: Users report a systematic crash at exactly 10 minutes 12 seconds (GitHub issue #22293), suggesting a hardcoded 600s task timeout + 12s grace period.
- **Configurable timeout requested**: Issue #15446 requests configurable timeouts but no resolution yet.
- **No budget percentage indicator**: No "80% of iteration budget" warning.

### Key Findings for Our Plugin
- Aider's 3-reflection limit is simple but effective for preventing runaway loops.
- OpenCode's configurable `steps` per agent is the most flexible approach.
- OpenCode's doom loop detection (3 identical tool calls) is a clever safety net.
- None of the tools have hard time limits or budget percentage indicators.

---

## 2. Progress Feedback During Long Tasks

### Aider
- **Waiting spinner**: `WaitingSpinner("Waiting for " + model_name)` shown while waiting for LLM response (`base_coder.py:1440-1441`).
- **Live streaming**: When `stream=True`, responses are streamed token-by-token via `live_incremental_response()` using a markdown stream renderer (`base_coder.py:1955-1959`).
- **Cost report after each message**: Shows `"Cost: $X.XX message, $Y.YY session"` plus token counts after each LLM interaction.
- **No step counter**: No "step 3/10" or similar progress indicator.
- **No intermediate results**: Only the final edit is shown; no partial file changes.

### OpenCode
- **Real-time streaming**: Full stream processing in `processor.ts:56` iterates over `stream.fullStream` events. Text deltas are emitted as `text-delta` events and persisted via `Session.updatePartDelta()` for live UI updates.
- **Tool call visibility**: Each tool call is visible in real-time: `tool-input-start` -> `tool-call` (running) -> `tool-result` (completed).
- **Context usage indicator**: Desktop/web UI shows a `ProgressCircle` with token count, percentage usage, and cost (`session-context-usage.tsx:73-97`). The TUI sidebar shows cost and context percentage.
- **Step tracking**: `start-step` and `finish-step` events are emitted with usage data per step.
- **Session status**: `SessionStatus.set(sessionID, { type: "busy" })` on start, `{ type: "retry", attempt, message, next }` during retries, `{ type: "idle" }` when done.

### Claude Code
- **Token-by-token streaming**: Responses stream in real-time in the terminal.
- **"Crunched for X time"**: Shows elapsed processing time.
- **`/cost` command**: Shows total cost, API duration, wall duration, and code changes for the session.
- **Status line**: Configurable to display context window usage continuously.
- **Tool calls visible**: Each tool call (file read, edit, bash) is shown as it happens.
- **No step counter**: No "step 3 of N" indicator.

### Key Findings for Our Plugin
- OpenCode's approach is the most complete: streaming + tool visibility + context % + cost.
- The context usage percentage indicator (ProgressCircle) is essential for long tasks.
- Tool call visibility (start/running/completed per tool) gives users confidence the agent is working.

---

## 3. Cancellation and Interruption

### Aider
- **Ctrl+C to cancel**: `KeyboardInterrupt` handler in `base_coder.py:986-1000`.
- **Single Ctrl+C**: Prints "^C again to exit" and records the interrupt time. Sets a 2-second threshold for double-tap.
- **Double Ctrl+C within 2 seconds**: Exits the program entirely.
- **Partial work preserved**: When interrupted mid-response, the partial response is kept: `self.cur_messages += [dict(role="user", content="^C KeyboardInterrupt")]` followed by `dict(role="assistant", content="I see that you interrupted my previous reply.")` (`base_coder.py:1575-1582`). The LLM sees the interruption context in the next turn.
- **No message queuing**: Cannot send follow-up messages while the agent is running.

### OpenCode
- **AbortSignal propagation**: Every operation takes an `abort: AbortSignal` parameter (`llm.ts:38`, `processor.ts:31`). This propagates to the LLM stream via `abortSignal: input.abort` (`llm.ts:251`).
- **Abort checked per event**: `input.abort.throwIfAborted()` at `processor.ts:57` checks abort status on every stream event.
- **Partial work preserved**: When aborted, incomplete tool calls are marked as errors: `status: "error", error: "Tool execution aborted"` (`processor.ts:404-415`). File snapshots are still finalized.
- **Retry on abort**: The retry sleep is cancellable via the abort signal (`retry.ts:11-25`).

### Claude Code
- **Esc to cancel**: Single Esc stops current generation.
- **Double Esc**: Opens the rewind/checkpoint menu.
- **Partial work kept**: Cancelled responses retain partial text and file changes.
- **Known issue**: ESC partially works but queue auto-restart can immediately start the next queued prompt (GitHub issue #33949).
- **No message queuing**: Cannot queue messages while agent is running in standard mode.

### Key Findings for Our Plugin
- OpenCode's AbortSignal pattern is the cleanest for cancellation in an async/coroutine environment.
- Checking abort status on every stream event is the right granularity.
- Preserving partial work (not rolling back) on cancel is the standard behavior across all tools.
- Aider's "Ctrl+C once = soft cancel, twice = hard exit" is a good UX pattern.

---

## 4. Checkpointing and Recovery

### Aider
- **Git-based undo**: Aider auto-commits each edit to git. The `/undo` command reverts the last aider commit (`commands.py:553`).
- **No mid-task checkpoints**: Checkpoints are only at git commit boundaries, not during a single LLM turn.
- **No resume from failure**: If a task fails mid-way, the user must re-run it. Successful edits are already committed.

### OpenCode
- **Snapshot system**: A dedicated `Snapshot` namespace (`snapshot/index.ts`) maintains a separate git repository (in `~/.opencode/data/snapshot/<project-id>`) for tracking file state.
- **Per-step snapshots**: `Snapshot.track()` is called at `start-step` event (`processor.ts:235`) and `Snapshot.patch()` at `finish-step` (`processor.ts:257`). Every LLM step gets a before/after snapshot.
- **Restore capability**: `Snapshot.restore(hash)` checks out files from the snapshot git repo. `Snapshot.revert(patches)` reverts specific patches.
- **Patch tracking**: Each step records a `patch` part with `hash` and `files` list, enabling granular restore.
- **Auto-cleanup**: Snapshots are pruned after 7 days via `git gc --prune=7.days`, run hourly.
- **Independent of user's git**: Uses a completely separate git repository, so it works even in non-git projects and does not pollute the user's git history.

### Claude Code
- **Automatic checkpoints**: Every user prompt creates a checkpoint. Checkpoints persist across sessions for 30 days.
- **Rewind menu** (Esc+Esc or `/rewind`): Shows scrollable list of prompts. Options: restore code+conversation, restore conversation only, restore code only, or summarize from that point.
- **Summarize from checkpoint**: Compresses conversation from a selected point forward into a summary, freeing context space while keeping earlier context intact.
- **Limitation**: Only tracks direct file edits via Claude's tools. Bash command changes (rm, mv, cp) are NOT tracked.
- **Not version control**: Designed for session-level "local undo", complementary to git.

### Key Findings for Our Plugin
- OpenCode's per-step snapshot with a separate git repo is the most robust approach.
- Claude Code's "summarize from checkpoint" is a unique feature that combines checkpointing with context management.
- For our IntelliJ plugin, we should use IntelliJ's LocalHistory API as an equivalent to the snapshot system.
- The key insight: checkpoint BEFORE each tool execution, not after.

---

## 5. Context Exhaustion During Long Tasks

### Aider
- **Detection**: Catches `ContextWindowExceededError` from the API (`base_coder.py:1464-1466`). Also detects `FinishReasonLength` for output limit hits.
- **Error reporting**: `show_exhausted_error()` shows detailed token breakdown: input tokens, output tokens, total tokens, and whether each exceeds the model's limits (`base_coder.py:1628-1665`).
- **Chat history summarization**: `ChatSummary` class (`history.py:7`) with configurable `max_tokens` (default: 1/16th of context, capped at 8192). When history exceeds this, it's recursively summarized. Splits messages, summarizes the head, keeps the tail.
- **No auto-recovery**: After context exhaustion, the error is shown and the turn ends. User must manually `/clear` or `/drop` files.
- **Multi-response for output limits**: If `supports_assistant_prefill`, aider can chain multiple responses to bypass output token limits (`base_coder.py:1492-1510`).

### OpenCode
- **Auto-compaction on overflow**: `SessionCompaction.isOverflow()` checks if total tokens >= usable context (`compaction.ts:33-49`). Triggers automatically.
- **Two-phase compaction**:
  1. **Pruning** (`compaction.ts:59-100`): Goes backwards through tool calls, protecting the last 40K tokens of tool output. Erases output of older tool calls that exceed the threshold (`PRUNE_MINIMUM = 20K`, `PRUNE_PROTECT = 40K`). Protected tools (e.g., "skill") are never pruned.
  2. **Summarization** (`compaction.ts:102-297`): Creates a structured summary using a "compaction" agent with a template (Goal, Instructions, Discoveries, Accomplished, Relevant files). The summary replaces older messages.
- **Overflow recovery with replay**: If the conversation overflows even during compaction, OpenCode tries to find the last user message, compact everything before it, and replay that message (`compaction.ts:114-130`).
- **Graceful degradation**: If compaction itself exceeds the context limit, it sets a `ContextOverflowError` and stops (`compaction.ts:227-236`).
- **Buffer**: `COMPACTION_BUFFER = 20,000` tokens reserved for the compaction process itself.
- **Plugin hooks**: `experimental.session.compacting` allows plugins to inject custom context or replace the compaction prompt.

### Claude Code
- **Auto-compaction at ~83.5%**: When usage hits approximately 83.5% of context window, auto-compaction triggers.
- **Configurable threshold**: `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` environment variable (1-100).
- **Strategy**: Clears older tool outputs first, then summarizes conversation. Preserves requests and key code snippets.
- **`/compact` command**: Manual compaction with optional focus instructions: `/compact Focus on code samples and API usage`.
- **Targeted summarize via `/rewind`**: Summarize from a specific checkpoint forward, keeping early context intact.
- **Context buffer**: Reserved ~33K-45K tokens (16.5-22.5% of 200K) for summarization overhead.
- **Known limitation**: Detailed instructions from early in the conversation may be lost during compaction.

### Key Findings for Our Plugin
- OpenCode's two-phase approach (prune tool outputs first, then summarize) is the most sophisticated.
- The structured summary template (Goal/Instructions/Discoveries/Accomplished/Files) is excellent for continuity.
- Reserving a buffer for the compaction process itself is critical (otherwise compaction fails).
- The "spiral problem" (compression drops useful context, causing re-fetch, which fills context again) is mitigated by protecting recent tool outputs (PRUNE_PROTECT) and using structured summaries.
- Plugin hooks for customizing compaction are valuable for enterprise use cases.

---

## 6. Token Cost Management

### Aider
- **Per-message cost tracking**: `message_cost` and `total_cost` in `base_coder.py:113,384`. Shows after each interaction: `"Cost: $X.XX message, $Y.YY session"`.
- **`/tokens` command**: Shows current token usage.
- **No spending limit**: No configurable budget per task or session.
- **Cost calculation**: Uses litellm's built-in cost calculator when available, falls back to manual calculation from `input_cost_per_token` model metadata.
- **Model metadata**: Token limits and costs can be configured via `.aider.model.metadata.json`.

### OpenCode
- **Per-step cost tracking**: Cost is calculated per `finish-step` event and accumulated on the assistant message (`processor.ts:252`).
- **Detailed cost formula**: Accounts for input, output, cache read, cache write, reasoning tokens, and special pricing for >200K context (`session/index.ts:849-862`).
- **UI display**: Both TUI sidebar and desktop app show session cost formatted as currency.
- **No spending limit**: No configurable budget. Cost is display-only.
- **Free usage detection**: Detects `FreeUsageLimitError` in retry logic (`retry.ts:66-67`).

### Claude Code
- **`/cost` command**: Shows total cost, API duration, wall duration, code changes.
- **Average cost**: ~$6/developer/day, below $12 for 90% of users. ~$100-200/developer/month with Sonnet.
- **Workspace spend limits**: Admins can set workspace-level spend limits in the Console.
- **Rate limit recommendations**: Published TPM/RPM recommendations per team size.
- **Agent teams**: ~7x more tokens than standard sessions. Recommendations to keep teams small.
- **Status line**: Can display cost continuously via configurable status line.
- **Background token usage**: ~$0.04/session for background processes (summarization, status checks).

### Key Findings for Our Plugin
- Per-message/per-step cost display is standard across all tools.
- None of the open-source tools implement per-task spending limits (only Claude Code offers workspace-level limits via Console).
- For our plugin, displaying cost per agent turn and cumulative session cost in the chat UI is sufficient.
- A configurable "warn at $X" threshold would be a differentiator.

---

## 7. Streaming and Partial Results

### Aider
- **Token-by-token streaming**: Uses `stream=True` on LLM calls. `show_send_output_stream()` (`base_coder.py:1900`) iterates over completion chunks and calls `live_incremental_response()` for live markdown rendering.
- **Partial content accumulation**: `partial_response_content` is built up incrementally (`base_coder.py:1956`).
- **Waiting spinner**: Shown while waiting for first token, stopped once content arrives (`base_coder.py:1837-1838`).
- **No tool call streaming**: Tool results are not streamed; only LLM text output is.

### OpenCode
- **Full event stream**: `processor.ts` processes a comprehensive event stream: `start`, `reasoning-start/delta/end`, `tool-input-start/delta/end`, `tool-call`, `tool-result`, `tool-error`, `text-start/delta/end`, `start-step/finish-step`.
- **Delta-based updates**: Uses `Session.updatePartDelta()` for incremental text and reasoning updates, enabling efficient real-time UI rendering.
- **Tool call visibility**: Tool calls are shown from input start through execution to result, with timing data.
- **Patch tracking**: File diffs are captured at step boundaries as `patch` parts.

### Claude Code
- **Full streaming**: Responses stream token-by-token in the terminal.
- **Tool call visibility**: Each tool invocation is shown with its result.
- **Extended thinking visible**: When using extended thinking models, the thinking process is partially visible.
- **SSE-based**: Uses Server-Sent Events. Known issue: no client-side timeout for dead connections.

### Key Findings for Our Plugin
- OpenCode's event-driven architecture is the gold standard: every phase of execution (reasoning, tool input, tool execution, text output) has distinct start/delta/end events.
- Delta-based updates (not full replacements) are essential for performant UI updates.
- Our IntelliJ plugin should emit similar events through the EventBus for the chat UI to render.

---

## 8. Parallel Work

### Aider
- **No parallel execution**: Single-threaded, one request at a time.
- **No background agents**: No sub-agent or team capability.

### OpenCode
- **No built-in parallel agents**: Single-session model. However, the architecture supports multiple sessions.
- **ACP (Agent Communication Protocol)**: OpenCode has an `acp/agent.ts` module suggesting early work on agent-to-agent communication.

### Claude Code
- **Sub-agents**: Can spawn separate Claude instances for specific tasks. Each has its own context window.
- **Background agents**: Sub-agents can run in background (non-blocking). Results surface when complete. Since v2.0.64, background agents can send messages to wake up the main agent.
- **Agent teams**: Experimental feature (`CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1`). Multiple Claude instances coordinated by a lead agent.
- **File conflict avoidance**: Parallel work requires agents to touch different files. No built-in merge conflict resolution.
- **Cost implications**: Agent teams use ~7x more tokens than standard sessions.

### Key Findings for Our Plugin
- Parallel agent execution is a Claude Code differentiator, not yet common in open-source tools.
- For our plugin, sub-agent delegation (e.g., "research in background while I continue working") is the most practical parallel pattern.
- File boundary enforcement is critical: parallel agents must not modify the same files.

---

## Summary: Design Patterns for Our IntelliJ Plugin

### Must-Have
1. **Configurable step limit per agent** (OpenCode pattern: `steps` field)
2. **Doom loop detection** (OpenCode: 3 identical tool calls triggers user prompt)
3. **AbortSignal/CancellationToken propagation** (OpenCode: abort checked on every stream event)
4. **Auto-compaction with structured summary** (OpenCode: two-phase prune + summarize)
5. **Per-step cost tracking and display** (all tools do this)
6. **Streaming with tool call visibility** (OpenCode: full event stream)
7. **Snapshot/checkpoint before each tool execution** (OpenCode: per-step snapshots via separate git)

### Should-Have
8. **Context usage percentage in UI** (OpenCode: ProgressCircle with %)
9. **Retry with exponential backoff** (OpenCode: respects Retry-After headers)
10. **Partial work preservation on cancel** (all tools: keep partial results, mark interrupted tools as errors)

### Nice-to-Have
11. **Targeted summarization from checkpoint** (Claude Code: summarize from a specific point forward)
12. **Configurable compaction instructions** (Claude Code: `/compact Focus on...`)
13. **Spending limit warning** (none of the tools do this well; opportunity for differentiation)
14. **Background sub-agent execution** (Claude Code: background agents)

---

## Source Code References

### Aider
- Reflection limit: `/tmp/research-agents/aider/aider/coders/base_coder.py:100-101`
- Keyboard interrupt: `/tmp/research-agents/aider/aider/coders/base_coder.py:986-1000`
- Context exhaustion: `/tmp/research-agents/aider/aider/coders/base_coder.py:1454-1466, 1628-1665`
- Chat summarization: `/tmp/research-agents/aider/aider/history.py:7-123`
- Streaming: `/tmp/research-agents/aider/aider/coders/base_coder.py:1900-1981`
- Cost tracking: `/tmp/research-agents/aider/aider/coders/base_coder.py:1994-2065`
- Undo: `/tmp/research-agents/aider/aider/commands.py:553-655`

### OpenCode
- Step limit config: `/tmp/research-agents/opencode/packages/opencode/src/config/config.ts:740-788`
- Step enforcement: `/tmp/research-agents/opencode/packages/opencode/src/session/prompt.ts:563-564`
- Doom loop: `/tmp/research-agents/opencode/packages/opencode/src/session/processor.ts:21, 152-177`
- Compaction: `/tmp/research-agents/opencode/packages/opencode/src/session/compaction.ts`
- Snapshots: `/tmp/research-agents/opencode/packages/opencode/src/snapshot/index.ts`
- Streaming processor: `/tmp/research-agents/opencode/packages/opencode/src/session/processor.ts:46-430`
- Retry logic: `/tmp/research-agents/opencode/packages/opencode/src/session/retry.ts`
- Context usage UI: `/tmp/research-agents/opencode/packages/app/src/components/session-context-usage.tsx`
- LLM streaming: `/tmp/research-agents/opencode/packages/opencode/src/session/llm.ts`

### Claude Code
- Checkpointing docs: https://code.claude.com/docs/en/checkpointing
- Cost management docs: https://code.claude.com/docs/en/costs
- Long-running agent harness: https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents
