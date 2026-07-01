---
name: OpenHands context management complete blueprint
description: Source-code-level blueprint of OpenHands' event-sourced context management — every class, field, method, prompt, config, integration point. Reference architecture for our agent redesign.
type: reference
---

# OpenHands Context Management — Complete Blueprint

Research date: 2026-03-31. Source: `/tmp/research-openhands` (cloned from github.com/All-Hands-AI/OpenHands)

## Architecture Overview (3 Layers)

```
Layer 1: Event History (State.history: list[Event]) — append-only, never modified
Layer 2: View (View.from_events()) — filtered projection, inserts summaries
Layer 3: ConversationMemory — converts View events into LLM Messages
```

## Core Data Flow

```
Controller._step()
  → agent.step(state)
    → condenser.condensed_history(state)
      → state.view [cached by len(history)]
        → View.from_events(history) — scans CondensationActions, builds forgotten set, filters, inserts summaries
      → condenser.condense(view)
        → Condensation(action) — short-circuit, return to controller
        → View(events) — proceed to LLM
    → conversation_memory.process_events(events) → list[Message] → LLM
```

## Event System

### Event IDs
- Monotonic, gapless integers starting at 0
- Assigned under threading lock in EventStream.add_event()
- EventStore uses 25-event cache pages for sequential reads

### CondensationAction (the core mechanism)
Two modes for specifying forgotten events:
1. `forgotten_event_ids: list[int]` — explicit list
2. `forgotten_events_start_id + forgotten_events_end_id` — contiguous range (inclusive)

Optional: `summary: str` + `summary_offset: int` (both or neither)

### CondensationRequestAction
No fields. Signal from controller that context window was exceeded.

### AgentCondensationObservation
Content = summary text. Inserted into View at summary_offset position.

## View.from_events() Algorithm

1. Scan all events for CondensationActions → build forgotten_event_ids set (includes action's own ID)
2. Also forget CondensationRequestAction IDs
3. Filter: kept_events = events NOT in forgotten set
4. Find LAST CondensationAction with summary+offset → insert AgentCondensationObservation at offset
5. Scan reverse for unhandled CondensationRequestAction (one after last CondensationAction)
6. Return View(events, unhandled_condensation_request, forgotten_event_ids)

Cache: State.view cached by len(history) — recomputed only on append.

## 10 Condensers

| Condenser | Type Key | Rolling? | LLM? | Summary? | Trigger |
|---|---|---|---|---|---|
| NoOp | noop | No | No | No | Never |
| ObservationMasking | observation_masking | No | No | Masks | Always (positional cutoff) |
| BrowserOutput | browser_output_masking | No | No | URL stub | Always (count from end) |
| RecentEvents | recent | No | No | No | Always (keep first+last) |
| AmortizedForgetting | amortized | Yes | No | No | size > max OR request |
| LLMSummarizing | llm | Yes | Yes | Free-text | size > max OR request |
| LLMAttention | llm_attention | Yes | Yes (structured) | No | size > max OR request |
| StructuredSummary | structured | Yes | Yes (tool call) | 18-field typed | size > max OR request |
| ConversationWindow | conversation_window | Yes | No | No | request ONLY (reactive) |
| Pipeline | pipeline | No | Depends | Depends | Chains sequentially |

## Production Pipeline (Hosted Product)

```python
CondenserPipeline([
    ConversationWindowCondenser(),                    # Handle explicit requests
    BrowserOutputCondenser(attention_window=2),       # Mask old screenshots
    LLMSummarizingCondenser(keep_first=4, max_size=240),  # Summarize at 240 events
])
```

## LLM Summarization Prompt (Verbatim)

Tracks: USER_CONTEXT, TASK_TRACKING (with task IDs), COMPLETED, PENDING, CURRENT_STATE, CODE_STATE, TESTS, CHANGES, DEPS, VERSION_CONTROL_STATUS.

Includes `<PREVIOUS SUMMARY>` from last condensation + `<EVENT id=N>` blocks for forgotten events.

## StructuredSummary — 18 Fields

user_context, completed_tasks, pending_tasks, current_state, files_modified, function_changes, data_structures, tests_written, tests_passing, failing_tests, error_messages, branch_created, branch_name, commits_made, pr_created, pr_status, dependencies, other_relevant_context.

Uses forced tool_choice: `create_state_summary`.

## ConversationMemory — Events to Messages

- SystemMessageAction → system message
- MessageAction → user/assistant based on source
- Tool actions → held in pending_tool_call_action_messages until all observations arrive
- Tool observations → stored in tool_call_id_to_message, paired with pending actions
- Filter: removes orphaned tool calls/responses
- 2 prompt cache breakpoints: end of system message + last user/tool message
- Initial user message NOT re-inserted if its ID is in forgotten_event_ids

## Stuck Detector — 5 Scenarios

1. 4 identical action+observation pairs
2. 3 identical actions with error observations
3. 3 identical agent messages (monologue)
4. A-B-A-B-A-B alternating pattern (6 pairs)
5. **10+ consecutive AgentCondensationObservation** (condensation loop)

## Key Integration Points

- ContextWindowExceededError → controller adds CondensationRequestAction → triggers re-step
- CondensationAction added to event stream → should_step()=True → immediate re-step
- Condenser gets separate LLM instance via llm_registry.get_llm('condenser', config)
- Prompt caching disabled for all LLM condensers
- State history NOT pickled — rebuilt from EventStream on restore
- Condensation events survive restart (persisted in EventStream)

## Defaults

- max_size: 100 events (240 in production pipeline)
- keep_first: 1 (4 in production)
- max_event_length: 10,000 chars
- max_iterations: 500
- enable_history_truncation: True
- enable_condensation_request: False (agent can't proactively request)
- enable_stuck_detection: True
