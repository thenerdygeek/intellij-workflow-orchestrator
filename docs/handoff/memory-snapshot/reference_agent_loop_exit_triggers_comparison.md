# Agent Loop Exit Triggers Comparison (9 Tools)

Research date: 2026-03-30. Source-code-level analysis of how agentic coding tools handle non-completion exit triggers.

## Quick Reference Table

| Tool | Context Exhaustion | Max Iterations | User Cancel | API Error Retries | Empty/Malformed Response | Text-Only (No Tools) |
|---|---|---|---|---|---|---|
| **Claude Code** | Auto-compact at ~83.5% (configurable via `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`, 1-100). Server-side compaction. | `maxTurns` cap (tool-use turns only). `maxBudgetUsd` spend cap. | Ctrl+C. State preserved in project memory. | Not publicly documented (closed source). | Not publicly documented. | **Natural loop termination** -- text-only = loop ends, awaits next user input. |
| **Cline** | Sliding window: truncates 50% of history (75% for smaller models). Threshold: `max(contextWindow - 40_000, contextWindow * 0.8)`. Auto-condense optional. `new_task` handoff. | No hard iteration cap. Auto-approve limits configurable. | Human-in-the-loop by default. Approval gates. | 3 retries, exponential backoff: 2s, 4s, 8s. | Auto-retry up to 3x with exponential backoff (2s, 4s, 8s). | Loop exits via `attempt_completion` tool call. Text-only treated as assistant message (continues conversation). |
| **Aider** | Auto-summarization via background `summarize_worker()`. Warns if `input_tokens >= max_input_tokens`. On `ContextWindowExceededError`: exits loop, shows token usage. | `max_reflections = 3` (error-fixing loops only). No general iteration cap. | Double Ctrl+C within 2 seconds to exit. Single Ctrl+C = soft interrupt. | Exponential backoff: starts 0.125s, doubles each retry, cap at `RETRY_TIMEOUT = 60s`. `request_timeout = 600s`. | Logs warning: "Empty response received from LLM." `num_malformed_responses++`, triggers reflection prompt. | Text-only = valid response. Returns to user input. No nudging. |
| **OpenHands** | `CondensationRequestAction` when `enable_history_truncation=true`. Otherwise `LLMContextWindowExceedError` halts. Multiple detection patterns. | `max_iterations = 500` (default). Measured from last user interaction. | `AgentState.STOPPED`. Pending actions get `ErrorObservation`. State saved via `save_state()`. | 8 retries (default). Backoff: min 15s, max 120s, multiplier 2.0. On exhaustion: `ERROR` state with `AGENT_RATE_LIMITED_STOPPED_MESSAGE`. | `LLMNoActionError`, `LLMMalformedActionError` -- emits `ErrorObservation`, pauses stepping. `FunctionCallValidationError` handled similarly. | **StuckDetector**: 3+ consecutive text messages = "agent monologue" pattern detected. Agent halted. |
| **SWE-agent** | `ContextWindowExceededError` -- exit status `exit_context`. Hard fail. | No general iteration cap. Cost limits instead: per-instance + total. 5+ command timeouts = `exit_command_timeout`. Total execution time limit. | `EXIT_FORFEIT_TOKEN` sentinel. | `RetryError` -- exit status `exit_api`. Delegated to litellm retry layer. | `FormatError` triggers requery loop: `max_requeries = 3`. After 3 failures: autosubmission + `exit_format`. | Treated as valid. Agent continues or submits based on exit token presence. |
| **Codex CLI** | Server-side compaction via `compact_threshold` (e.g., 200K tokens). Encrypted compaction items in stream. Known issue: compaction loops. | No documented iteration cap. | Standard terminal interrupt. | Retry for rate limits and connection errors. Specifics not public. | Known issue: model returns text-only with `end_turn` instead of tool calls. No automatic retry -- gateway treats as success. | **Natural termination** -- text-only = loop ends. Known issue with premature text-only exits. |
| **Continue.dev** | `pruneRawPromptFromTop()` trims oldest messages. `contextLength` property with fallback. | **No documented iteration cap**. Known issue: agent loops indefinitely. | Manual stop only. | `withExponentialBackoff()` -- 5 retries, 0.5 multiplier. | No documented handling. | No documented handling. Tool loop continues as long as tool calls present. |
| **Goose** | Compaction at `DEFAULT_COMPACTION_THRESHOLD` (~85% of context limit). 2 compaction attempts max, then hard exit: "Context limit still exceeded after compaction." | `DEFAULT_MAX_TURNS = 1000`. | `cancel_token` check in loop. Abort tasks, persist state. | Credits exhausted: shows `top_up_url`, breaks. Network errors: breaks with retry message. No exponential backoff in retry.rs. | Malformed tool call parse error: "response may have been truncated" + `exit_chat = true`. | Text-only (0 tool requests): **continues loop** -- appends to conversation, keeps going. Does NOT exit. |
| **Amazon Q CLI** | `ContextWindowOverflow`: progressive compaction (truncate large messages -> exclude recent messages -> `CompactHistoryFailure`). `TokenWarningLevel::Critical` at 600K chars. | No iteration counter. State-machine driven (`ChatState` enum). | Ctrl+C -> `ChatError::Interrupted` with queued tools preserved. | Throttling: displays error, prompts retry. Model overload: `RetryModelOverload` state. Monthly limit: returns to prompt. | State machine handles via transition to error states. | Returns to user prompt state. Interactive loop awaits next input. |

## Detailed Analysis by Category

### 1. Budget/Context Exhaustion

**Three distinct strategies emerge:**

**A. Server-side compaction (Claude Code, Codex CLI)**
Both Anthropic and OpenAI implement compaction server-side. Claude Code triggers at ~83.5% by default (configurable 1-100% via `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`). Codex CLI uses `compact_threshold` (e.g., 200K tokens). Both produce opaque compressed representations. Codex CLI has a known issue where compaction can enter infinite loops.

**B. Client-side summarization/truncation (Cline, Aider, OpenHands, Goose, Amazon Q)**
- **Cline**: Most sophisticated -- formula-based threshold `max(contextWindow - 40_000, contextWindow * 0.8)`, sliding window removes 50% of history, preserves first message pair, deduplicates file reads, offers `new_task` handoff at 50% usage.
- **Aider**: Background `summarize_worker()` thread condenses old messages using a weak model. On `ContextWindowExceededError`, shows token breakdown and reduction suggestions.
- **OpenHands**: Two-tier -- if `enable_history_truncation` is true, fires `CondensationRequestAction` (LLM-based + heuristic condensers). Otherwise hard fails.
- **Goose**: 2-attempt compaction with hard exit on failure. Telemetry emitted on failure.
- **Amazon Q**: Progressive 3-step strategy: truncate large messages, exclude recent messages, then `CompactHistoryFailure`. Critical warning at 600K characters.

**C. Hard fail (SWE-agent)**
No compression -- just exits with `exit_context` status. Research-focused tool prioritizes clean eval results over recovery.

**Novel approach**: Cline's `new_task` handoff is unique -- proactively creates a new session with essential context before degradation occurs, rather than reactively compressing.

### 2. Max Iterations

| Tool | Default Limit | What Counts | On Hit |
|---|---|---|---|
| Claude Code | Configurable `maxTurns` | Tool-use turns only | Model-driven stop signal |
| Cline | None | N/A | No safety net (relies on `attempt_completion`) |
| Aider | `max_reflections = 3` | Error-fixing cycles only | Warning + stop reflection |
| OpenHands | **500** | All agent steps (from last user interaction) | `AgentState.ERROR` |
| SWE-agent | None (cost-based) | N/A | Cost/time limits instead |
| Codex CLI | None documented | N/A | Relies on model to stop |
| Continue.dev | **None** | N/A | Known infinite loop issue |
| Goose | **1000** | All turns | "Reached maximum number of actions" message |
| Amazon Q | None (state-machine) | N/A | State transitions control flow |

**Key insight**: Only OpenHands (500) and Goose (1000) have hard default iteration caps. Most tools rely on the model to self-terminate, which is fragile. SWE-agent uses cost limits instead of iteration counts -- arguably more practical for production use.

### 3. User Cancellation

**State preservation varies dramatically:**

- **Best**: OpenHands -- `save_state()` on every state change, pending actions get `ErrorObservation`, full event stream preserved
- **Good**: Goose -- `cancel_token` checked in loop, tool pair summarization aborted cleanly, extension state persisted
- **Good**: Amazon Q -- `ChatError::Interrupted` preserves queued tools, conversation history maintained
- **Adequate**: Aider -- requires double Ctrl+C within 2 seconds (prevents accidental cancellation), state preserved in memory
- **Minimal**: Claude Code, Codex CLI, Continue.dev -- basic interrupt handling, project memory persisted but in-flight work lost

**Novel**: Aider's double-Ctrl+C is a clever UX pattern that prevents accidental cancellation during long operations.

### 4. LLM API Errors

| Tool | Max Retries | Backoff Strategy | Initial Delay | Max Delay | On Exhaustion |
|---|---|---|---|---|---|
| Aider | Until 60s total | Exponential 2x | 0.125s | 60s | Stop + show error |
| OpenHands | 8 | Exponential 2x | 15s | 120s | `ERROR` state + `RATE_LIMITED` message |
| Cline | 3 | Exponential 2x | 2s | 8s | Show error to user |
| Continue.dev | 5 | Exponential 0.5x | Not documented | Not documented | Error propagated |
| Goose | Configurable | Sequential (no backoff!) | N/A | Timeout-based | `MaxAttemptsReached` + halt |
| SWE-agent | Via litellm | Delegated | Delegated | Delegated | `exit_api` status |
| Claude Code | Not public | Not public | Not public | Not public | Not public |
| Codex CLI | Not public | Not public | Not public | Not public | Not public |
| Amazon Q | Interactive | User-prompted | N/A | N/A | State transition |

**Key insight**: OpenHands is most resilient with 8 retries and up to 120s max wait. Cline is most aggressive with only 3 retries. Goose notably has NO backoff -- retries are sequential, which is unusual and potentially problematic for rate limits. SWE-agent delegates entirely to litellm, which is clean separation of concerns.

**Novel**: Amazon Q's interactive retry for model overload -- in interactive mode, prompts user to retry; in non-interactive mode, suggests alternate model. This is the only tool with model-switching as a recovery strategy.

### 5. Empty/Malformed LLM Responses

**Three recovery strategies:**

**A. Retry with backoff (Cline)**
3 automatic retries with exponential backoff (2s, 4s, 8s). Simple and effective for transient issues.

**B. Reflection/requery (Aider, SWE-agent)**
- **Aider**: Increments `num_malformed_responses`, sets `reflected_message = str(err)` to feed error back to LLM for self-correction. Up to `max_reflections` attempts.
- **SWE-agent**: `max_requeries = 3`. On `FormatError`, `BashIncorrectSyntaxError`, `BlockedActionError`: re-prompts LLM with error. On exhaustion: autosubmission + `exit_format`.

**C. Hard exit (Goose)**
Malformed tool call parse error immediately sets `exit_chat = true` with message "response may have been truncated." No retry.

**D. No handling (Codex CLI)**
Known issue -- model returns `end_turn` with text-only, gateway treats as success. No retry triggered.

**Novel**: SWE-agent's sentinel token approach (`RETRY_WITH_OUTPUT_TOKEN`, `RETRY_WITHOUT_OUTPUT_TOKEN`) allows the model itself to request retries, giving the LLM agency over its own error recovery.

### 6. Text-Only Responses (No Tool Calls)

**This is the most divergent category:**

| Tool | Behavior | Rationale |
|---|---|---|
| Claude Code | **Loop terminates** | Text = completion signal. Primary exit mechanism. |
| Codex CLI | **Loop terminates** | Same as Claude Code. Known issue with premature exits. |
| Cline | **Continues** (awaits `attempt_completion`) | Explicit completion tool required. Text is intermediate. |
| Aider | **Returns to user** | Text = valid response, no action needed. |
| OpenHands | **StuckDetector fires after 3+** | Monologue detection. 3+ consecutive text messages = stuck. |
| SWE-agent | **Continues** | Text may contain sentinel tokens for control flow. |
| Goose | **Continues loop** | Appends to conversation, keeps iterating. |
| Continue.dev | **No documented handling** | Loop depends on tool call presence. |
| Amazon Q | **Returns to prompt** | Interactive mode returns to user input. |

**Critical architectural split**:
- **Text = done** (Claude Code, Codex CLI): Simple, clean, but fragile. Model can prematurely exit by producing text.
- **Explicit completion tool** (Cline, SWE-agent): More robust but requires the model to use a specific tool to signal completion.
- **Stuck detection** (OpenHands): Hybrid -- allows some text but detects patterns. 3+ consecutive text messages triggers halt.
- **Keep going** (Goose): Most aggressive -- assumes text is intermediate thinking, keeps looping until max_turns or tool use resumes.

**Novel**: OpenHands' stuck detector is the most sophisticated approach. Rather than a binary text=done/text=continue decision, it uses pattern matching (4+ repeated action-observation cycles, 3+ error cycles, 6+ alternating patterns, 3+ monologue messages) to distinguish legitimate text responses from stuck loops.

## Cross-Cutting Insights

### 1. Maturity Spectrum
- **Most mature error handling**: OpenHands (stuck detection, state preservation, configurable retries, condensation)
- **Most minimal**: Continue.dev (no documented limits, known infinite loop issues)
- **Research-optimized**: SWE-agent (clean exit codes for eval, cost-based limits, sentinel tokens)

### 2. State Preservation Gap
Most tools lose in-flight work on exit. Only OpenHands (`save_state()` on every transition) and Goose (session manager persistence) maintain meaningful state across interruptions.

### 3. The Compaction Problem
Both Claude Code and Codex CLI have reported compaction loop issues where compaction itself consumes enough context to trigger re-compaction. This is an unsolved problem in server-side compaction approaches.

### 4. Missing Safety Nets
Continue.dev and Codex CLI have no documented iteration caps. Cline relies entirely on the model calling `attempt_completion`. These are production risks for autonomous/unattended operation.

### 5. Cost Controls
Only SWE-agent and Claude Code offer explicit cost/budget limits as exit triggers. For enterprise use, cost-based limits (SWE-agent's `per_instance_cost_limit`, Claude Code's `maxBudgetUsd`) are more practical than iteration counts.

## Architectural Recommendations for Plugin Agent

Based on this research, recommended exit trigger design:

1. **Context**: Client-side compaction with 2-attempt limit + hard exit (Goose pattern), plus proactive `new_task` handoff (Cline pattern)
2. **Iterations**: Hard cap (default 200-500), configurable, counting tool-use turns only
3. **Budget**: USD-based budget cap (Claude Code pattern) as primary production guard
4. **Cancellation**: Token-based cancellation with state persistence (Goose/OpenHands pattern)
5. **API errors**: 5-8 retries with exponential backoff, min 5s / max 120s (OpenHands parameters)
6. **Malformed responses**: Reflection-based recovery up to 3 attempts (Aider pattern)
7. **Text-only**: Stuck detection with pattern matching (OpenHands approach) rather than binary text=done
8. **Stuck loop**: Implement SWE-agent-style pattern detection (repeated action-observation cycles)
