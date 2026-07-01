# OpenHands Stuck Detection & Iterative Self-Improvement Loop Patterns

Source: `github.com/All-Hands-AI/OpenHands` (cloned 2026-04-03, latest main)

**NOTE**: All V0 code is marked "Legacy V0 - deprecated since 1.0.0, scheduled for removal April 1, 2026". V1 uses the Software Agent SDK (`github.com/OpenHands/software-agent-sdk`). The stuck detection patterns analyzed here are still the production code.

---

## 1. Stuck Detection (StuckDetector) - The Industry-Leading Implementation

**File**: `openhands/controller/stuck.py`

OpenHands has **5 distinct stuck detection scenarios**, checked in order on every `_step()`:

### Scenario 1: Repeating Action-Observation (4 repetitions)
Detects 4 identical action-observation pairs in sequence. Uses `_eq_no_pid()` for comparison that:
- For `IPythonRunCellAction`: ignores thought, compares first 3 lines of code (handles edit_file_by_replace variations)
- For `CmdOutputObservation`: ignores command_id (PID), compares command + exit_code
- Default: exact equality

```python
# Minimum 4 identical (action, observation) pairs
actions_equal = all(self._eq_no_pid(last_actions[0], action) for action in last_actions)
observations_equal = all(self._eq_no_pid(last_observations[0], obs) for obs in last_observations)
```

### Scenario 2: Repeating Action-Error (3 repetitions)
Same action 3 times producing:
- `ErrorObservation` (any error), OR
- `IPythonRunCellObservation` with specific `SyntaxError` patterns:
  - "unterminated string literal" (checks consistent line numbers)
  - "invalid syntax. Perhaps you forgot a comma?"
  - "incomplete input"

### Scenario 3: Monologue (3 repetitions)
Agent sends 3 identical `MessageAction(source=AGENT)` with NO observations between them. The agent is talking to itself in a loop without taking any actions.

### Scenario 4: Alternating Pattern (6 steps)
Detects an A-B-A-B-A-B oscillation pattern:
```
(action_1, obs_1), (action_2, obs_2), (action_1, obs_1), (action_2, obs_2), (action_1, obs_1), (action_2, obs_2)
```
This catches the agent alternating between two approaches without making progress.

### Scenario 5: Context Window Error Loop (10 condensation events)
Detects 10+ consecutive `AgentCondensationObservation` events with no other events between them - meaning condensation keeps failing and re-triggering.

### Interactive vs Headless Mode
- **Headless (default)**: Checks entire history
- **Interactive**: Only checks history AFTER the last user message (resets on each user input)

### StuckAnalysis Data
```python
@dataclass
class StuckAnalysis:
    loop_type: str           # 'repeating_action_observation', 'repeating_action_error', 'monologue', 'repeating_action_observation_pattern', 'context_window_error'
    loop_repeat_times: int   # How many repeats detected
    loop_start_idx: int      # Index in filtered history where loop began
```

---

## 2. Loop Recovery Mechanism (3 options)

**File**: `openhands/controller/agent_controller.py` (lines 613-1389)

When stuck is detected, `AgentStuckInLoopError` is raised, which sets agent to `ERROR` state. In CLI mode, 3 recovery options are presented:

### Option 1: Restart from Before Loop (`_perform_loop_recovery`)
- Truncates `state.history` to `stuck_analysis.loop_start_idx`
- Updates `state.end_id`
- Sets state to `AWAITING_USER_INPUT`
- User can then give new instructions

### Option 2: Restart with Last User Message (`_restart_with_last_user_message`)
- Truncates history to before loop
- Finds last `MessageAction(source=USER)` in history
- Re-injects that message as a new action
- Sets state to `RUNNING` (auto-continues)

### Option 3: Stop Agent
- Sets state to `STOPPED`

Key: Recovery is **memory truncation** (not rollback of file changes). The loop is removed from context, but any file modifications persist.

---

## 3. LLM-Level Retry (RetryMixin)

**File**: `openhands/llm/retry_mixin.py`

Uses `tenacity` library with:
- Exponential backoff (configurable min/max/multiplier)
- `stop_after_attempt(num_retries)` + `stop_if_should_exit()`
- **Temperature escalation**: On `LLMNoResponseError` (Gemini models), automatically bumps temperature from 0 to 1.0 for next attempt

---

## 4. Error-as-Feedback Pattern (NOT Terminal)

**File**: `openhands/controller/agent_controller.py` (lines 916-929)

When the agent produces a malformed response, it is NOT terminal:
```python
except (LLMMalformedActionError, LLMNoActionError, LLMResponseError,
        FunctionCallValidationError, FunctionCallNotExistsError) as e:
    self.event_stream.add_event(ErrorObservation(content=str(e)), EventSource.AGENT)
    return  # Returns to main loop, agent sees the error as an observation next step
```

This means the LLM sees its own error in the next turn and can self-correct. This is the primary "self-improvement" mechanism within a session.

---

## 5. Context Window Auto-Condensation

**File**: `openhands/controller/agent_controller.py` (lines 930-957)

On `ContextWindowExceededError`:
- If `enable_history_truncation` is True: emits `CondensationRequestAction` which triggers the condenser pipeline
- If False: raises `LLMContextWindowExceedError` (terminal)

The agent also has a `request_condensation` tool it can call proactively.

---

## 6. Auto-Continue Response (Headless Mode)

**File**: `openhands/core/main.py` (lines 322-335)

When the agent asks for user input in headless mode:
```python
def auto_continue_response(state, ...):
    return ('Please continue on whatever approach you think is suitable.\n'
            'If you think you have solved the task, please finish the interaction.\n'
            'IMPORTANT: YOU SHOULD NEVER ASK FOR HUMAN HELP.\n')
```

The resolver uses a variant (`codeact_user_response`):
```python
'Please continue working on the task on whatever approach you think is suitable.\n'
'If you think you have solved the task, please first send your answer to user...\n'
```

---

## 7. Session Continuation / Resume

**File**: `openhands/controller/state/state.py`

- `save_to_session()` / `restore_from_session()` persists full state to FileStore
- `RESUMABLE_STATES = [RUNNING, PAUSED, AWAITING_USER_INPUT]`
- On resume, agent receives: "Let's get back on track. If you experienced errors before, do NOT resume your task. Ask me about it."

---

## 8. Delegation for Specialization (NOT Review)

**File**: `openhands/controller/agent_controller.py` (lines 735+)

OpenHands uses `AgentDelegateAction` to delegate subtasks to specialized agents (e.g., `BrowsingAgent` for web tasks). This is NOT a review/judge pattern - it's task specialization.

- Delegate gets its own `AgentController` with shared metrics
- Delegate results flow back as `AgentDelegateObservation`
- Parent controller checks if delegate is stuck too (`_is_stuck` checks delegate recursively)

---

## 9. Self-Verification via Prompt Engineering (NOT Programmatic)

**File**: `openhands/agenthub/codeact_agent/prompts/system_prompt.j2`

The TROUBLESHOOTING section instructs the agent to self-review:
```
If you've made repeated attempts to solve a problem but tests still fail:
1. Step back and reflect on 5-7 different possible sources of the problem
2. Assess the likelihood of each possible cause
3. Methodically address the most likely causes
4. Document your reasoning process
```

The PROBLEM_SOLVING_WORKFLOW includes:
```
5. VERIFICATION: Test your implementation thoroughly, including edge cases.
```

The `think` tool enables structured reasoning without side effects.

---

## 10. What OpenHands Does NOT Have

1. **No outer retry/review loop**: No mechanism to re-run the entire agent after completion
2. **No judge/scorer agent**: No separate LLM evaluating the agent's work
3. **No "run until tests pass" programmatic loop**: Test verification is prompt-instructed, not enforced
4. **No automatic rollback on stuck**: Memory is truncated but file changes persist
5. **No model fallback**: Does not switch to a different model on failure (only temperature adjustment)
6. **SWE-bench evaluation harness**: Moved to separate repository, not in the main codebase

---

## Key Architectural Insights for Our Agent

1. **5-scenario stuck detection is the gold standard** - most agents have 0-1 scenarios
2. **Error-as-feedback (not terminal)** is critical - send malformed response errors back to LLM as observations
3. **Memory truncation for loop recovery** is elegant - remove the stuck context, not the file changes
4. **Interactive vs headless mode matters** - reset stuck detection after each user message
5. **Temperature escalation on empty response** is a proven technique for Gemini/empty responses
6. **Alternating pattern detection (A-B-A-B)** catches a failure mode most agents miss
7. **Context window error loops** (condensation keeps failing) is a unique scenario worth protecting against
8. **Self-verification is purely prompt-driven** - no programmatic verification before `finish`
