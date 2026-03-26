# Agent Completion Decision Research

**Date:** 2026-03-26
**Purpose:** How do enterprise/open-source AI coding agents decide when the agent loop should STOP vs CONTINUE?

## The Core Problem

An LLM responds without tool calls. Is the task actually done, or did the LLM stop prematurely (e.g., after context compression, hitting output limits, or losing track of the plan)?

---

## 1. Claude Code (Anthropic)

**Source:** Closed-source CLI, but Agent SDK is documented. Community reverse-engineering at [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code).

### Stop Condition
The agent loop has **one exit condition**: `stop_reason != "tool_use"`. The loop runs until the model stops calling tools. When Claude produces a response with no tool calls, the loop ends and the final `AssistantMessage` + `ResultMessage` are yielded.

From the [Agent SDK docs](https://platform.claude.com/docs/en/agent-sdk/agent-loop):
> "Claude continues calling tools and processing results until it produces a response with no tool calls."

### Stop Reasons (from [API docs](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons))
- `end_turn` -- Claude finished naturally (most common)
- `tool_use` -- Claude wants to call a tool (loop continues)
- `max_tokens` -- Hit output token limit (truncated)
- `pause_turn` -- Server-side tool loop hit iteration limit
- `refusal` -- Safety refusal
- `model_context_window_exceeded` -- Context full
- `stop_sequence` -- Custom stop sequence matched

### Plan-Awareness
**None explicit.** The system prompt instructs Claude to work through gather/act/verify phases, but there is no programmatic plan-tracking or completion verification in the harness itself. The loop trusts the model's decision to stop.

### Post-Compaction Behavior
Auto-compaction fires at ~95% context capacity. It summarizes older history, keeping recent exchanges. A `SystemMessage` with subtype `"compact_boundary"` is emitted. **No special continuation logic after compaction** -- the loop simply continues with the compacted context. Persistent instructions should be in CLAUDE.md (re-injected every request) to survive compaction.

### Empty Response Handling
The API docs explicitly warn about **empty responses with `end_turn`**: Claude sometimes returns 2-3 tokens with no content. The recommended fix is to add a continuation prompt (`"Please continue"`) in a NEW user message -- not to retry.

### Unfulfilled Intent Detection
**None.** No programmatic detection.

### Safety Limits
- `max_turns` -- Caps tool-use round trips
- `max_budget_usd` -- Cost cap
- `Stop` hook -- Fires when agent finishes, can validate results
- `PreCompact` hook -- Runs before compaction for archiving

### Key Insight
Claude Code relies **entirely on the model's judgment** for completion. The harness is deliberately simple. Anthropic's approach to preventing premature completion is through **better prompting** (system prompt instructs thorough work) and **context preservation** (CLAUDE.md survives compaction) rather than programmatic plan-checking.

---

## 2. Codex CLI (OpenAI)

**Source:** [github.com/openai/codex](https://github.com/openai/codex) (Rust, codex-rs)

### Stop Condition
Same fundamental pattern: the loop runs until the model produces an assistant message **without tool calls**. From the [Codex blog post](https://openai.com/index/unrolling-the-codex-agent-loop/):
> "The process repeats until the model stops emitting tool calls and instead produces a message for the user."

In the Rust source (`agent/status.rs`), agent statuses are:
- `Running` -- loop continues
- `Completed` -- task finished (final)
- `Interrupted` -- NOT final (can resume)
- `Errored` -- final
- `Shutdown` -- final
- `PendingInit` -- not started

The `is_final()` function returns true for anything except `PendingInit`, `Running`, and `Interrupted`.

### System Prompt Instruction
From `codex-rs/core/prompt.md`:
> "Please keep going until the query is completely resolved, before ending your turn and yielding back to the user. Only terminate your turn when you are sure that the problem is solved."

### Known Premature Completion Bug
[Issue #5264](https://github.com/openai/codex/issues/5264) -- "Tendency to stop the agent on output while it has a plan to move on." The agent announces 7 tasks, completes 2, then stops. Occurs 30-50% of the time. Labeled both "bug" and "model-behavior."

[Issue #14414](https://github.com/openai/codex/issues/14414) -- Agent says it will do something and stops, requires "continue" nudge. Users discovered a workaround by adding an "Autonomy Policy" to AGENTS.md.

### Plan-Awareness
**None in the harness.** The system prompt tells the model to keep going, but there is no programmatic verification that the plan was completed.

### Post-Compaction Behavior
No documented compaction mechanism in the same style as Claude Code.

### Unfulfilled Intent Detection
**None.** OpenAI acknowledged the premature stopping as a model behavior issue, not a harness issue.

### Key Insight
Codex CLI has the **exact same vulnerability** as Claude Code -- if the model decides to stop (even prematurely), the harness accepts it. The only defense is prompt engineering in AGENTS.md.

---

## 3. Cline (VS Code Extension)

**Source:** [github.com/cline/cline](https://github.com/cline/cline) (TypeScript)

### Stop Condition
Cline uses a **fundamentally different approach**: an explicit `attempt_completion` tool. The recursive loop (`recursivelyMakeClineRequests`) continues until the LLM calls the `attempt_completion` tool.

From [DeepWiki analysis](https://deepwiki.com/cline/cline/3.1-system-prompt):
> "Controller.initTask() creates a Task and starts a loop that continues until the LLM issues an `attempt_completion` tool block."

### How It Works
1. System prompt assembly
2. LLM invocation via `ApiHandler`
3. Response parsing (text + tool-use blocks)
4. Tool dispatch via `ToolExecutor`
5. Human approval (Task.ask() suspends for confirmation)
6. Loop continues with tool results appended
7. **Only terminates when `attempt_completion` tool is called**

### Plan-Awareness
Cline has **Plan Mode** vs **Act Mode** (toggled with Tab). In Plan Mode, the agent explores without modifications. However, there is no programmatic tracking of plan steps against completion.

### Additional Stop Conditions
- User cancellation (`taskState.abort = true`)
- Consecutive mistake limit (`ask("mistake_limit_reached")`)
- Context window exceeded (truncation/summarization attempted)

### Unfulfilled Intent Detection
**None programmatic.** But the `attempt_completion` design means the model must **actively declare completion** rather than passively stopping. This is a significant architectural advantage -- a response without tool calls does NOT end the loop. Only the explicit completion tool does.

### Key Insight
Cline's `attempt_completion` tool is the most robust completion mechanism among the agents studied. The model cannot accidentally stop -- it must explicitly call the completion tool. However, the model can still call `attempt_completion` prematurely without actually verifying work.

---

## 4. Cursor Agent

**Source:** Closed-source, but documented at [cursor.com](https://cursor.com/docs/agent/overview)

### Stop Condition
Cursor's agent mode is "Composer with a loop -- it executes until a stop condition." The agent relies on the LLM deciding when the workflow is complete (same as Claude Code/Codex).

### Hook-Based Continuation
Cursor supports a `stop` hook in `.cursor/hooks.json` that receives:
- `status`: "completed" | "aborted" | "error"
- `loop_count`: iteration counter

The hook can issue a `followup_message` to continue the loop. Example pattern:
```
if input.status !== 'completed' or input.loop_count >= MAX_ITERATIONS: stop
```

### Plan-Awareness
Cursor supports a scratchpad file (`.cursor/scratchpad.md`) where agents track progress. The loop can continue **"until a DONE marker appears in the scratchpad"** or iteration limits are reached. This is external-file-based plan tracking, not semantic understanding.

### Unfulfilled Intent Detection
**None built-in**, but the hook system allows users to implement custom detection.

### Key Insight
Cursor's hook system is the most extensible approach -- users can implement their own completion verification. But out of the box, it relies on the model's judgment like Claude Code and Codex.

---

## 5. Aider

**Source:** [github.com/Aider-AI/aider](https://github.com/Aider-AI/aider) (Python)

### Stop Condition
Aider uses a **reflection loop** rather than a tool-calling loop. From `base_coder.py`:

```python
while message:
    self.reflected_message = None
    list(self.send_message(message))

    if not self.reflected_message:
        break  # No corrections needed = done

    if self.num_reflections >= self.max_reflections:
        return  # Hard limit (default: 3)

    self.num_reflections += 1
    message = self.reflected_message
```

### How Completion Works
1. Send message to LLM, get response with code edits
2. Apply edits, run linting, run tests
3. If lint/test failures occur, set `reflected_message` with error details
4. Loop continues with the error as the next prompt
5. **Stop when `reflected_message` is None** (no corrections needed)
6. **Hard stop at `max_reflections = 3`** (prevents infinite fix loops)

### Plan-Awareness
**None.** Aider is edit-focused, not plan-focused. It processes one request at a time with error correction.

### Post-Compaction Behavior
Not applicable -- Aider doesn't maintain long conversations.

### Unfulfilled Intent Detection
**Implicit via linting/testing.** If edits cause lint or test failures, the loop automatically continues. This is verification-driven completion rather than intent-driven.

### Key Insight
Aider's approach is fundamentally different -- completion is determined by **verification success** (no lint errors, no test failures), not by the model declaring it's done. This is the most grounded approach but only works for edit-focused tasks.

---

## 6. SWE-agent (Princeton)

**Source:** [github.com/SWE-agent/SWE-agent](https://github.com/SWE-agent/SWE-agent) (Python)

### Stop Condition
From `sweagent/agent/agents.py`:

```python
while not step_output.done:
    step_output = self.step()
    self.save_trajectory()
```

The `done` flag is set in several scenarios:

1. **Explicit submission**: Agent runs the `submit` command
2. **User exit**: User inputs "exit"
3. **Error autosubmission**: Various errors trigger `attempt_autosubmission_after_error()`
4. **Context window exceeded**: `ContextWindowExceededError` triggers autosubmission
5. **Cost limit exceeded**: `CostLimitExceededError` triggers autosubmission
6. **Execution timeouts**: 5+ consecutive timeouts raise exception
7. **Total execution time**: Exceeds `total_execution_timeout`
8. **Format errors**: After `max_requeries` failed attempts (default: 3)

### Plan-Awareness
**None programmatic.** The agent follows the ReAct pattern (Thought -> Action -> Observation) but has no plan tracker.

### RetryAgent Variant
SWE-agent includes a `RetryAgent` that wraps the loop with retry logic:
```python
if step_output.done:
    self._rloop.on_submit(...)
    if self._rloop.retry():
        self._next_attempt()
        step_output.done = False  # Reset and try again!
```

### Key Insight
SWE-agent has the most **graceful degradation** -- when things go wrong (context overflow, cost limits, timeouts), it attempts to autosubmit the best patch so far rather than just failing. The `submit` command (an explicit tool) is the normal completion mechanism, similar to Cline's `attempt_completion`.

---

## 7. OpenHands (formerly OpenDevin)

**Source:** [github.com/All-Hands-AI/OpenHands](https://github.com/All-Hands-AI/OpenHands) (Python)

### Stop Condition
Multiple mechanisms in `openhands/controller/agent_controller.py`:

1. **AgentFinishAction**: Agent explicitly finishes, transitions to `AgentState.FINISHED`
2. **AgentRejectAction**: Agent rejects task, transitions to `AgentState.REJECTED`
3. **Iteration limit**: `iteration_flag.current_value` exceeds maximum
4. **Budget limit**: `budget_flag` monitors accumulated USD cost
5. **User stop**: `AgentState.STOPPED` via user intervention
6. **Error**: `AgentState.ERROR` from various failures

### StuckDetector (Unique Feature)

Located at `openhands/controller/stuck.py`. This is the **most sophisticated completion/continuation analysis** among all agents studied. It detects 5 patterns:

1. **Repeating Action-Observation Cycles** (4+ identical pairs)
2. **Repeating Action-Error Cycles** (3+ identical error patterns)
3. **Agent Monologue** (3+ consecutive messages without user input)
4. **Alternating Patterns** (6+ A-B-A-B cycles)
5. **Context Window Error Loop** (10+ `AgentCondensationObservation` events)

Detection is **semantic** -- it compares event content, not object identity. It ignores PIDs in command observations and compares only first 3 lines of file-editing code.

### Recovery Mechanism
When stuck is detected, the system offers:
- Restart before the loop
- Restart with last user message
- Stop completely

Recent updates replaced hard errors (`RuntimeError`) with graceful state transitions, allowing new messages to be processed.

### Plan-Awareness
**None explicit**, but the StuckDetector provides a form of **negative plan-awareness** -- it detects when the agent is NOT making progress rather than checking if it completed the plan.

### Key Insight
OpenHands' StuckDetector is the gold standard for detecting **when an agent should NOT stop** (infinite loops). However, it does not address the opposite problem: detecting when an agent stops prematurely.

---

## 8. AutoGen (Microsoft)

**Source:** [github.com/microsoft/autogen](https://github.com/microsoft/autogen) (Python)

### Stop Condition
AutoGen 0.4 has the most **flexible and composable** termination system with 11 built-in conditions:

1. **MaxMessageTermination** -- After N messages
2. **TextMentionTermination** -- When specific text appears (e.g., "TERMINATE")
3. **TokenUsageTermination** -- When token limits reached
4. **TimeoutTermination** -- After N seconds
5. **HandoffTermination** -- When handoff requested
6. **SourceMatchTermination** -- After specific agent responds
7. **ExternalTermination** -- Programmatic external control
8. **StopMessageTermination** -- When StopMessage produced
9. **TextMessageTermination** -- When any TextMessage produced
10. **FunctionCallTermination** -- On specific function execution
11. **FunctionalTermination** -- Custom evaluation function

### Composability
Conditions combine with AND (`&`) and OR (`|`):
```python
termination = MaxMessageTermination(10) | TextMentionTermination("TERMINATE")
```

### The Classic Pattern (AutoGen 0.2)
```python
is_termination_msg=lambda x: x.get("content", "").rstrip().endswith("TERMINATE")
```
Combined with `max_consecutive_auto_reply` for hard limits.

### Plan-Awareness
**None built-in.** Termination is condition-based, not plan-based.

### Unfulfilled Intent Detection
**None built-in**, but `FunctionalTermination` allows custom logic that could implement it.

### Key Insight
AutoGen's strength is **composability** -- you can build complex termination logic by combining conditions. But the framework itself has no awareness of task completion quality.

---

## 9. LangChain/LangGraph ReAct Agents

**Source:** [github.com/langchain-ai/langgraph](https://github.com/langchain-ai/langgraph) (Python)

### Stop Condition
From `libs/prebuilt/langgraph/prebuilt/chat_agent_executor.py`:

```python
def should_continue(state: StateSchema) -> str | list[Send]:
    messages = _get_state_value(state, "messages")
    last_message = messages[-1]
    if not isinstance(last_message, AIMessage) or not last_message.tool_calls:
        return END
    else:
        return "tools"  # continue to tool execution
```

**The classic ReAct pattern**: if `last_message.tool_calls` is empty/falsy, the loop ends.

### Additional Stop: Remaining Steps
```python
def _are_more_steps_needed():
    remaining_steps = _get_state_value(state, "remaining_steps", None)
    if remaining_steps is not None:
        if remaining_steps < 1 and all_tools_return_direct:
            return True
        elif remaining_steps < 2 and has_tool_calls:
            return True
```
Returns "Sorry, need more steps to process this request" when exhausted.

### Recursion Limit
LangGraph enforces a `recursion_limit` (default: 25) that throws `GraphRecursionError` when exceeded.

### Plan-Awareness
**None.** Pure ReAct -- reason, act, observe, repeat until no more tools.

### Unfulfilled Intent Detection
**None.** The framework trusts the model's decision to stop calling tools.

### Key Insight
LangGraph is the purest implementation of the "no tool calls = done" pattern. No bells and whistles, no plan tracking, no stuck detection. The simplicity is intentional -- LangGraph provides the graph primitives and expects users to add custom nodes for verification.

---

## 10. Devin (Cognition)

**Source:** Closed-source. Limited public information.

### Stop Condition
From public documentation and blog posts:
- Devin breaks tasks into step-by-step plans
- It continues in an iterative loop until all tests pass
- After 10+ ACUs (Autonomous Compute Units), performance may degrade
- Supports "dynamic re-planning" (v3.0) when hitting roadblocks

### Plan-Awareness
**Yes, inferred.** Devin appears to maintain internal plans and can re-plan when stuck. However, no technical details are public about how this works.

### Premature Completion
From [Devin's agents101](https://devin.ai/agents101):
> "If you find yourself thinking 'it's ignoring my instructions' or 'this thing is going in circles', you should be ok discontinuing that conversation."

This suggests Devin does not have robust premature completion detection -- it relies on human judgment.

### Key Insight
Devin is likely the most plan-aware agent given its "dynamic re-planning" capability, but without public source code, we cannot verify how it actually detects task completion.

---

## Comparative Summary

| Agent | Stop Condition | Plan-Aware? | Post-Compaction Logic? | Stuck Detection? | Nudge/Retry? | Explicit Completion Tool? |
|-------|---------------|-------------|----------------------|-----------------|-------------|--------------------------|
| **Claude Code** | `stop_reason != "tool_use"` | No | No special logic | No | No | No |
| **Codex CLI** | No tool calls in response | No | N/A | No | No (prompt workaround) | No |
| **Cline** | `attempt_completion` tool | Plan/Act modes | Context truncation | Mistake limit | No | **YES** |
| **Cursor** | Model decides + hooks | Scratchpad-based | N/A | No | Hook-based followup | No |
| **Aider** | `reflected_message == None` | No | N/A | No | Reflection loop (max 3) | No |
| **SWE-agent** | `submit` command | No | N/A | No | RetryAgent variant | **YES** (`submit`) |
| **OpenHands** | `AgentFinishAction` | No | Condensation | **YES (5 patterns)** | Recovery options | **YES** |
| **AutoGen** | 11 composable conditions | No | N/A | No | No | TextMention pattern |
| **LangGraph** | `tool_calls` empty | No | N/A | No | No | No |
| **Devin** | Unknown (closed) | Likely yes | Unknown | Unknown | Dynamic re-planning | Unknown |

---

## Key Patterns Identified

### Pattern 1: "No Tool Calls = Done" (Most Common)
**Used by:** Claude Code, Codex CLI, LangGraph, Cursor (default)

The simplest pattern. When the LLM response has no tool calls, the task is considered complete. **Vulnerability:** The model can stop prematurely after context compaction, hitting output limits, or losing track of multi-step plans.

### Pattern 2: Explicit Completion Tool (Most Robust)
**Used by:** Cline (`attempt_completion`), SWE-agent (`submit`), OpenHands (`AgentFinishAction`)

The model must actively call a completion tool. A response without tool calls is NOT treated as completion -- it would be an error or the loop would continue expecting more. **Advantage:** Prevents accidental completion. **Vulnerability:** The model can still call the completion tool prematurely.

### Pattern 3: Verification-Driven Completion (Most Grounded)
**Used by:** Aider (lint/test reflection loop)

Completion is determined by automated verification (tests pass, no lint errors). The model's declaration of completion is irrelevant -- only observable outcomes matter. **Limitation:** Only works for tasks with clear verification criteria.

### Pattern 4: External Condition Composition (Most Flexible)
**Used by:** AutoGen (11 conditions), Cursor (hooks)

Termination is configured externally with composable conditions. **Advantage:** Maximum flexibility. **Limitation:** Requires upfront configuration per task type.

### Pattern 5: Stuck Detection (Unique to OpenHands)
**Used by:** OpenHands (StuckDetector)

Detects when the agent is in an unproductive loop rather than checking if it's done. **Advantage:** Catches infinite loops. **Gap:** Does not detect premature stopping.

---

## What's Missing Everywhere

**No agent in production has "unfulfilled intent detection."** Specifically, none of them:

1. Parse the original user request into a plan/checklist
2. Track which steps have been completed
3. Before accepting completion, verify that all original requirements are met
4. Detect when a post-compaction response fails to continue interrupted work

The closest approaches are:
- **Cline's `attempt_completion`**: Forces explicit declaration but doesn't verify against original intent
- **Aider's reflection loop**: Verifies edits via lint/test but not task intent
- **OpenHands' StuckDetector**: Detects non-progress but not premature stopping
- **Anthropic's recommended pattern**: Feature lists with `passes: false` as external completion guards

---

## Recommendations for Our Implementation

Based on this research, a robust agent completion system should combine multiple patterns:

1. **Explicit completion tool** (Pattern 2): Require the model to call a `task_complete` tool rather than just stopping. A response without tool calls should trigger a continuation nudge, not acceptance.

2. **Plan-aware completion gate** (Novel): Before accepting `task_complete`, check if there's an active plan with incomplete steps. If yes, nudge the model to continue.

3. **Post-compaction continuation** (Novel): After context compaction, if the model's first response has no tool calls, inject a system message: "Context was just compacted. Review the task summary above and continue working on any incomplete items."

4. **Verification hooks** (Pattern 3): When possible, verify completion via observable outcomes (tests, builds, lint).

5. **Stuck detection** (Pattern 5): Implement OpenHands-style pattern detection to prevent infinite loops.

6. **Hard limits** (Pattern 4): Always have max_turns, max_cost, and timeout as safety nets.

---

## Sources

- [Claude Code Agent Loop - Agent SDK docs](https://platform.claude.com/docs/en/agent-sdk/agent-loop)
- [Claude Code - How it works](https://code.claude.com/docs/en/how-claude-code-works)
- [Claude API - Handling stop reasons](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons)
- [Codex CLI - GitHub](https://github.com/openai/codex)
- [Codex CLI - Premature stopping issue #5264](https://github.com/openai/codex/issues/5264)
- [Codex CLI - Agent stops issue #14414](https://github.com/openai/codex/issues/14414)
- [Cline - GitHub](https://github.com/cline/cline)
- [Cline - DeepWiki analysis](https://deepwiki.com/cline/cline)
- [Cursor - Agent best practices](https://cursor.com/blog/agent-best-practices)
- [Cursor - Plan mode docs](https://cursor.com/docs/agent/plan-mode)
- [Aider - GitHub](https://github.com/Aider-AI/aider) (base_coder.py)
- [SWE-agent - GitHub](https://github.com/SWE-agent/SWE-agent) (sweagent/agent/agents.py)
- [OpenHands - GitHub](https://github.com/All-Hands-AI/OpenHands)
- [OpenHands - Stuck Detector docs](https://docs.openhands.dev/sdk/guides/agent-stuck-detector)
- [OpenHands - Stuck Detector source](https://github.com/All-Hands-AI/OpenHands/blob/main/openhands/controller/stuck.py)
- [AutoGen - Termination conditions](https://microsoft.github.io/autogen/stable//user-guide/agentchat-user-guide/tutorial/termination.html)
- [LangGraph - GitHub](https://github.com/langchain-ai/langgraph) (prebuilt/chat_agent_executor.py)
- [Devin - Agents 101](https://devin.ai/agents101)
- [Anthropic - Effective harnesses for long-running agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- [learn-claude-code - Agent loop analysis](https://github.com/shareAI-lab/learn-claude-code/blob/main/docs/en/s01-the-agent-loop.md)
