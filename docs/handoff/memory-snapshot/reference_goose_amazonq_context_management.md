# Goose & Amazon Q CLI: Context Management Deep Source Analysis

## GOOSE (Block) - Context Management Architecture

### 1. Two-Tier Compression System

Goose uses a two-tier approach: **tool-pair summarization** (incremental, background) + **full conversation compaction** (triggered at threshold).

#### Tier 1: Tool-Pair Summarization (Incremental)
- **File**: `crates/goose/src/context_mgmt/mod.rs`
- Runs in background via `tokio::spawn` during every reply loop iteration
- Uses a cheap/fast model (`provider.complete_fast()`) to summarize individual tool call/response pairs
- **Batch size**: `TOOLCALL_SUMMARIZATION_BATCH_SIZE = 10` (always processes oldest 10 tool pairs)
- **Cutoff formula**: `compute_tool_call_cutoff(context_limit, threshold)` = `(3 * effective_limit / 20_000).clamp(10, 500)`
  - For 128K context @ 0.8 threshold: cutoff = 15
  - For 200K context @ 0.8 threshold: cutoff = 24
  - For 1M context @ 0.8 threshold: cutoff = 120
- **Trigger condition**: `eligible_tool_count > cutoff + BATCH_SIZE` (where eligible = total - protect_last_n)
- **Protection**: Current turn's tool calls are never summarized (`protect_last_n = current_turn_tool_count`)
- **Summary prompt**: "Your task is to summarize a tool call & response pair to save tokens. Reply with a single message that describes what happened."
- Controlled by `GOOSE_TOOL_PAIR_SUMMARIZATION` config (default: true)
- Summarized messages get `MessageMetadata::agent_only()` and replace the original tool pair

#### Tier 2: Full Conversation Compaction
- **Threshold check**: `check_if_compaction_needed()` compares `current_tokens / context_limit` against threshold
- **Default threshold**: `DEFAULT_COMPACTION_THRESHOLD = 0.8` (80% of context window)
- **Token counting**: Prefers `session.total_tokens` (server-reported); falls back to `tiktoken_rs::CoreBPE` estimation with DashMap cache (max 10K entries)
- **Disabling**: Set threshold to 0.0 or >= 1.0
- **Two trigger points**:
  1. **Pre-reply**: Before entering reply loop, checks threshold. If exceeded, compacts first, then proceeds.
  2. **Mid-loop recovery**: On `ProviderError::ContextLengthExceeded`, compacts and retries (max 2 attempts)

### 2. Compaction Algorithm (`do_compact`)

**Progressive tool response removal** - if summarization itself exceeds context:
```
removal_percentages = [0, 10, 20, 50, 100]
```
For each percentage, removes that % of tool response messages from the **middle** (middle-out pattern), then retries summarization. If 100% removal still fails, returns error.

**Middle-out removal strategy** (`filter_tool_responses`):
- Identifies all tool response message indices
- Removes from the middle outward alternating left/right
- Preserves first and last tool responses longest

### 3. Compaction Prompt Template
**File**: `crates/goose/src/prompts/compaction.md`

Key design: Uses `<analysis>` tags for reasoning. Sections:
1. User Intent
2. Technical Concepts
3. Files + Code (full code, change justifications)
4. Errors + Fixes
5. Problem Solving
6. User Messages (truncate long tool args/results)
7. Pending Tasks
8. Current Work
9. Next Step (only if directly continues user instruction)

Instruction: "This summary will only be read by you so it is ok to make it much longer than a normal summary you would show to a human"

### 4. Post-Compaction Message Structure

After compaction:
1. Original messages get `metadata.with_agent_invisible()` (visible to user UI, invisible to model)
2. Summary message gets `MessageMetadata::agent_only()` (role: User)
3. Continuation message (role: Assistant, agent_only) with one of:
   - `CONVERSATION_CONTINUATION_TEXT` (if most recent user msg preserved)
   - `TOOL_LOOP_CONTINUATION_TEXT` (if in mid-tool-loop)
   - `MANUAL_COMPACT_CONTINUATION_TEXT` (if user triggered /summarize)
4. Preserved user message (the most recent text-only user message, re-added as fresh message)

### 5. Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `GOOSE_AUTO_COMPACT_THRESHOLD` | 0.8 | Ratio of context usage that triggers auto-compaction |
| `GOOSE_TOOL_CALL_CUTOFF` | Computed | Number of tool calls to keep before summarizing older ones |
| `GOOSE_TOOL_PAIR_SUMMARIZATION` | true | Enable/disable background tool pair summarization |
| `GOOSE_MAX_TURNS` | 1000 | Max turns without user input |
| `GOOSE_SUBAGENT_MAX_TURNS` | 25 | Max turns for subagent |
| `GOOSE_CONTEXT_LIMIT` | Model default / 128K | Override context window size |
| `GOOSE_MOIM_MESSAGE_TEXT` | unset | Persistent text injected every turn |
| `GOOSE_MOIM_MESSAGE_FILE` | unset | File contents injected every turn (max 64KB) |

### 6. Subagent Context Isolation
- **File**: `crates/goose/src/agents/platform_extensions/summon.rs`
- Subagents run via `run_subagent_task()` with completely isolated conversations
- Each subagent gets its own `SessionConfig` with independent context window
- No context sharing between parent and subagent beyond the task prompt
- Results returned as single text message to parent

### 7. Memory/Persistence (.goosehints)
- **Files**: `crates/goose/src/hints/load_hints.rs`, `import_files.rs`
- `.goosehints` files provide persistent instructions loaded at session start
- `GOOSE_MOIM_MESSAGE_TEXT` / `GOOSE_MOIM_MESSAGE_FILE` inject text into working memory every turn
- These are injected via the "moim" (memory of important messages) system before each provider call

### 8. Summarize Tool (not compaction)
- **File**: `crates/goose/src/agents/platform_extensions/summarize.rs`
- A separate tool the agent can call to summarize files/directories
- Loads files deterministically (respects .gitignore, max 100KB per file, 1MB total)
- Sends to LLM with a question for focused analysis
- Not related to context compaction - it's a research/analysis tool

### 9. Failure Modes
- **Compaction itself exceeds context**: Progressive tool removal (0/10/20/50/100%)
- **Compaction fails after removal**: Error message, session ends
- **Mid-loop compaction fails**: Error message + "Please try again or create a new session"
- **Max 2 compaction attempts** in reply_internal loop before giving up
- **Provider manages own context**: `provider.manages_own_context()` skips tool pair summarization

---

## AMAZON Q CLI - Context Management Architecture

### TWO SEPARATE SYSTEMS

Amazon Q has **two distinct context management implementations**:
1. **chat-cli** (older, `crates/chat-cli/`) - Full compaction with progressive strategies
2. **agent** (newer, `crates/agent/`) - Simpler, treats ContextWindowOverflow as terminal error

### System 1: chat-cli Context Management

#### 1. Token Counting (Heuristic)
- **File**: `crates/chat-cli/src/cli/chat/token_counter.rs`
- **Simple heuristic**: `count_tokens(content) = (content.len() / 4 + 5) / 10 * 10`
- `TOKEN_TO_CHAR_RATIO = 4` (1 token ~= 4 characters)
- Rounds up to nearest 10 to avoid false precision
- Counts chars recursively through JSON structures for tool results
- Tracks `ConversationSize` with three categories: `context_messages`, `user_messages`, `assistant_messages`

#### 2. TokenWarningLevel System
- **File**: `crates/chat-cli/src/cli/chat/conversation.rs` (line 1072)
- Only two levels: `None` and `Critical`
- `Critical` when `total_chars >= max_chars` (where max_chars = context_window_tokens * TOKEN_TO_CHAR_RATIO)
- Displays warning: "This conversation is getting lengthy. Please use /compact to summarize."
- Checked after each response (`display_char_warnings()`)

#### 3. ContextWindowOverflow Handling (3-Step Progressive Strategy)
- **File**: `crates/chat-cli/src/cli/chat/mod.rs` (line 991)
- Triggered when `ConverseStreamErrorKind::ContextWindowOverflow` received from API

**Step 1 - Auto-compact with defaults:**
```rust
ChatState::CompactHistory {
    truncate_large_messages: history.len() <= 2,
    max_message_length: if history.len() <= 2 { 25_000 } else { default },
    messages_to_exclude: 0,
}
```

**Step 2 - If compaction itself overflows:**
- If `history_len <= 2` and not yet truncating: Enable truncation with `max_message_length = 25_000`
- If `history_len > 2` and `messages_to_exclude < 1`: Exclude 1 most recent message pair
- If already excluding but not truncating: Add truncation
- If already both: `ChatError::CompactHistoryFailure` (terminal)

**Step 3 - CompactHistoryFailure (terminal):**
```
"Your conversation is too large to continue."
Suggestions: /compact, /usage, /clear
```

Can be disabled with: `q settings chat.disableAutoCompaction true`

#### 4. Compaction Request Construction
- **File**: `crates/chat-cli/src/cli/chat/conversation.rs` (line 630)
- Sends **entire conversation history** to model as a summarization request
- Strategy application:
  - `messages_to_exclude`: Drains N most recent history entries before sending
  - `truncate_large_messages`: Truncates each user message to `max_message_length` (default: MAX_USER_MESSAGE_SIZE)
- Sends only a `DUMMY_TOOL_NAME` tool spec to prevent model from using tools during summarization
- If previous summary exists, includes it in the prompt with `CONTEXT_ENTRY_START_HEADER` markers

#### 5. Summary Prompt Structure
Default prompt (same in chat-cli and agent):
```
[SYSTEM NOTE: This is an automated summarization request, not from the user]

FORMAT REQUIREMENTS: Create a structured, concise summary in bullet-point format.
DO NOT respond conversationally. DO NOT address the user directly.

Your task is to create a structured summary document containing:
1) A bullet-point list of key topics/questions covered
2) Bullet points for all significant tools executed and their results
3) Bullet points for any code or technical information shared
4) A section of key insights gained
5) REQUIRED: the ID of the currently loaded todo list, if any

FORMAT THE SUMMARY IN THIRD PERSON, NOT AS A DIRECT RESPONSE.
```
Sections: CONVERSATION SUMMARY, TOOLS EXECUTED, TODO ID

Custom prompts can be passed via `/compact <prompt>` - placed as "IMPORTANT CUSTOM INSTRUCTION" above the format requirements.

#### 6. Post-Compaction State
- **File**: `crates/chat-cli/src/cli/chat/conversation.rs` (line 732)
- `replace_history_with_summary()`: Drains history (keeping `messages_to_exclude` most recent), stores summary in `latest_summary`
- Summary persists across future compactions (included in next compaction prompt)
- If `next_user_message` is set (auto-compaction after overflow), **retries the original request** after compaction

#### 7. Context Manager (Sticky Context Files)
- **File**: `crates/chat-cli/src/cli/chat/context.rs`
- `ContextManager` loads files from agent config (`file://` resources) or `/context add` commands
- Supports glob patterns, ~ expansion
- Files limited to 75% of context window: `calc_max_context_files_size = context_window_tokens * 3 / 4`
- Excess files dropped via `drop_matched_context_files()` when total exceeds limit
- Context files injected as fake user/assistant message pairs at conversation start

### System 2: Agent Module Context Management

#### 1. CompactStrategy (Message Truncation)
- **File**: `crates/agent/src/agent/compact.rs`
- `DEFAULT_MAX_MESSAGE_LEN = 25_000` characters
- `truncate_large_messages`: When true, truncates each content block equally
- Algorithm:
  1. Calculate `total_len` across all text/JSON content blocks in a message
  2. If `total_len > max_message_length`: `max_bytes = max_message_length / total_items`
  3. Truncate each block to `max_bytes`, append "...truncated due to length"
  4. JSON values converted to text strings for truncation
- Images are never truncated

#### 2. CompactingState
```rust
struct CompactingState {
    last_user_message: Option<Message>,  // Message that caused overflow
    strategy: CompactStrategy,
    conversation: ConversationState,     // Being summarized
}
```
If `last_user_message` is `Some`, indicates auto-compaction was triggered.

#### 3. Summary Prompt (`create_summary_prompt`)
- Same structured format as chat-cli
- Includes previous summary if available (via `CONTEXT_ENTRY_START_HEADER` markers)
- States: "This summary contains ALL relevant information from our previous conversation"

#### 4. ContextWindowOverflow in Agent Module
- **CRITICAL DIFFERENCE**: In the agent module (`crates/agent/src/agent/mod.rs` line 866), `ContextWindowOverflow` is treated as a **terminal error**:
```rust
StreamErrorKind::ContextWindowOverflow => {
    self.set_active_state(ActiveState::Errored(err.clone().into())).await;
    self.agent_event_buf.push(AgentEvent::Stop(AgentStopReason::Error(err.into())));
}
```
- No auto-compaction in the agent loop! The agent simply stops.
- Only the chat-cli module has auto-compaction on overflow.

#### 5. Conversation Invariants (`enforce_conversation_invariants`)
- **Max history**: `MAX_CONVERSATION_STATE_HISTORY_LEN = 500` messages (minus 2 reserved for context)
- When trimming: Finds second-oldest user message without tool results as new start
- Invalid tool uses (referencing removed tools) get arguments replaced with dummy content
- Context messages (system prompt, resources, hooks, summary) prepended as fake user/assistant pairs

#### 6. Stream Timeout Handling
```rust
StreamTimeout: {
    // Add fake assistant message: "Response timed out"
    // Add fake user message: "try to split up the work into smaller steps"
    // Retry the request
}
```

---

## COMPARATIVE ANALYSIS

| Aspect | Goose | Amazon Q CLI (chat-cli) | Amazon Q (agent) |
|---|---|---|---|
| **Token counting** | tiktoken_rs (accurate) with DashMap cache | chars/4 heuristic, rounded to nearest 10 | Same as chat-cli |
| **Auto-compact trigger** | 80% of context (configurable) | On API overflow error | Not implemented (terminal error) |
| **Pre-emptive check** | Yes, before reply loop | No (reactive only) | No |
| **Incremental optimization** | Tool-pair summarization (background) | None | None |
| **Progressive fallback** | Remove 0/10/20/50/100% tool responses | Exclude messages -> truncate -> fail | Truncate only |
| **Max retry** | 2 compaction attempts in loop | 3 progressive strategies | 0 (immediate fail) |
| **Summary model** | `complete_fast()` (cheap model) | Same model as conversation | Same model |
| **Preserves user msg** | Most recent text-only user message | Via `messages_to_exclude` | N/A |
| **Summary persistence** | Via message metadata (agent_only) | `latest_summary` field | `ConversationSummary` struct |
| **Chain summarization** | No (new summary replaces old) | Yes (old summary included in new) | Yes (old summary included) |
| **History cap** | None (managed by compaction) | 500 messages hard limit | 500 messages hard limit |
| **Context file limit** | N/A (uses MCP extensions) | 75% of context window | 10KB per resource file |
| **Subagent isolation** | Full (separate session) | N/A | Hierarchical AgentId with parent tracking |
| **Disable auto-compact** | `GOOSE_AUTO_COMPACT_THRESHOLD=0` | `chat.disableAutoCompaction` setting | N/A |
| **Manual compact** | `/summarize` command | `/compact` command with args | N/A |

### Key Architectural Insights

1. **Goose is the most sophisticated** - two-tier (incremental tool summarization + full compaction), pre-emptive threshold checks, progressive fallback during compaction itself, and configurable via environment variables.

2. **Amazon Q has a split personality** - the older chat-cli module has full compaction with 3-step progressive strategy, but the newer agent module treats overflow as terminal. This suggests compaction hasn't been ported to the agent architecture yet.

3. **Neither uses multi-tier budgets** - Both use a single threshold (Goose) or reactive overflow (Amazon Q). No tool has graduated to budget-based allocation.

4. **Summary chaining** - Amazon Q explicitly includes previous summaries in new compaction requests. Goose does not chain summaries but its compaction prompt is much more detailed (9 sections vs 5).

5. **Token estimation gap** - Goose uses accurate tiktoken, Amazon Q uses chars/4. This affects when compaction triggers and how accurately remaining capacity is estimated.

6. **Goose's middle-out removal** is unique - when the compaction itself exceeds context, it removes tool responses starting from the middle of the conversation (preserving recent and oldest context). No other tool does this.

7. **Amazon Q's truncation-as-compaction** is a novel fallback - when summarization fails (even summarization overflows), it truncates individual messages to 25K chars with "...truncated" suffix. This is a destructive but guaranteed way to reduce context.
