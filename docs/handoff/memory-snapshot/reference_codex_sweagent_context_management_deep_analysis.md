# Deep Source Analysis: Codex CLI & SWE-agent Context Management

## Date: 2026-03-31
## Source: Cloned repos at `/tmp/research-codex` and `/tmp/research-sweagent`

---

# PART 1: CODEX CLI (Rust)

## Architecture Overview

Context management lives in `codex-rs/core/src/` across these files:
- `context_manager/` — ContextManager struct (history tracking, token estimation, rollback)
- `compact.rs` — Client-side (LLM-based) compaction
- `compact_remote.rs` — Server-side (OpenAI API) compaction
- `tasks/compact.rs` — CompactTask dispatcher
- `codex.rs` — Auto-compact threshold logic and turn loop
- `tasks/undo.rs` — Ghost snapshot undo/rollback
- `tasks/ghost_snapshot.rs` — Git-based snapshot creation

## 1. ContextManager Data Structure

```rust
// codex-rs/core/src/context_manager/history.rs
struct ContextManager {
    items: Vec<ResponseItem>,           // Oldest at index 0
    token_info: Option<TokenUsageInfo>, // Last API response token counts
    reference_context_item: Option<TurnContextItem>, // Baseline for diffing
}
```

**ResponseItem variants tracked:**
- Message (user/assistant/developer roles)
- FunctionCall, FunctionCallOutput
- CustomToolCall, CustomToolCallOutput
- ToolSearchCall, ToolSearchOutput
- Reasoning (encrypted content)
- Compaction (encrypted server-side compaction)
- LocalShellCall, WebSearchCall, ImageGenerationCall
- GhostSnapshot (git snapshots for undo)
- Other

## 2. Token Counting Implementation

**Approximate token counting — NO tokenizer, pure byte heuristic:**

```rust
// codex-rs/utils/string/src/truncate.rs
const APPROX_BYTES_PER_TOKEN: usize = 4;

pub fn approx_token_count(text: &str) -> usize {
    let len = text.len();
    len.saturating_add(APPROX_BYTES_PER_TOKEN.saturating_sub(1)) / APPROX_BYTES_PER_TOKEN
    // Ceiling division: bytes / 4, rounded up
}

pub fn approx_bytes_for_tokens(tokens: usize) -> usize {
    tokens.saturating_mul(APPROX_BYTES_PER_TOKEN) // tokens * 4
}
```

**Per-item estimation:**
```rust
fn estimate_response_item_model_visible_bytes(item: &ResponseItem) -> i64 {
    match item {
        GhostSnapshot => 0,  // Not sent to model
        Reasoning { encrypted_content: Some(content) } | Compaction { encrypted_content } => {
            // Base64 decode estimate: len * 3/4 - 650
            estimate_reasoning_length(content.len())
        }
        _ => {
            // Serialize to JSON, measure length
            let raw = serde_json::to_string(item).len();
            // Adjust for inline images (replace base64 payload with fixed estimate)
            raw - payload_bytes + replacement_bytes
        }
    }
}
```

**Image estimation:** `RESIZED_IMAGE_BYTES_ESTIMATE = 7373` bytes (~1844 tokens). For `detail: "original"`, decodes the image, calculates 32px patch grid, converts to byte estimate via LRU cache (size 32).

**Total token usage calculation:**
```rust
fn get_total_token_usage(&self, server_reasoning_included: bool) -> i64 {
    last_api_response_total_tokens
    + estimated_tokens_of_items_added_since_last_api_response
    + (if !server_reasoning_included { non_last_reasoning_items_tokens } else { 0 })
}
```

## 3. Auto-Compact Threshold Calculation

**Core threshold formula:**
```rust
// codex-rs/protocol/src/openai_models.rs
impl ModelInfo {
    pub fn auto_compact_token_limit(&self) -> Option<i64> {
        let context_limit = self.context_window
            .map(|cw| (cw * 9) / 10);  // 90% of context window
        let config_limit = self.auto_compact_token_limit; // User override
        match (context_limit, config_limit) {
            (Some(cl), Some(ul)) => Some(min(ul, cl)), // Clamp to 90% max
            (Some(cl), None) => Some(cl),
            (None, Some(ul)) => Some(ul),
            (None, None) => None // No compaction
        }
    }
}
```

**Default parameters:**
- `effective_context_window_percent`: 95 (for usable input space)
- Auto-compact threshold: 90% of context_window
- Fallback model: context_window = 272,000 tokens

## 4. Compaction Trigger Points (Three Locations)

### 4a. Pre-Sampling Compaction (before each turn)
```rust
// codex.rs: run_pre_sampling_compact()
async fn run_pre_sampling_compact(sess, turn_context) {
    // 1. Check for model switch requiring compact
    maybe_run_previous_model_inline_compact(sess, turn_context, total_tokens);

    // 2. Check threshold
    let auto_compact_limit = model_info.auto_compact_token_limit().unwrap_or(i64::MAX);
    if total_usage_tokens >= auto_compact_limit {
        run_auto_compact(sess, turn_context, DoNotInject);
    }
}
```

### 4b. Mid-Turn Compaction (after each sampling request in loop)
```rust
// Inside the turn loop, after each model response:
let token_limit_reached = total_usage_tokens >= auto_compact_limit;
if token_limit_reached && needs_follow_up {
    run_auto_compact(sess, turn_context, BeforeLastUserMessage);
    continue; // Re-enter loop with compacted history
}
```

### 4c. Model Switch Compaction
```rust
// When switching to a smaller context window model:
let should_run = total_usage_tokens > new_auto_compact_limit
    && previous_model != current_model
    && old_context_window > new_context_window;
```

### 4d. Manual `/compact` Command
User can trigger CompactTask directly, dispatched as a SessionTask.

## 5. Two Compaction Strategies

### Strategy A: Local (LLM-based) Compaction
Used for non-OpenAI providers.

**Algorithm:**
1. Take full history, append compaction prompt as user input
2. Send to model for summarization
3. If ContextWindowExceeded: remove oldest history item, retry
4. Extract last assistant message as summary
5. Build new history: `[user_messages (last 20K tokens)] + [summary]`
6. Ghost snapshots preserved (carried over)

**Compaction prompt** (`templates/compact/prompt.md`):
> "You are performing a CONTEXT CHECKPOINT COMPACTION. Create a handoff summary for another LLM that will resume the task."

**Summary prefix** (`templates/compact/summary_prefix.md`):
> "Another language model started to solve this problem and produced a summary of its thinking process..."

**User message budget in compacted history: `COMPACT_USER_MESSAGE_MAX_TOKENS = 20,000`**

```rust
fn build_compacted_history_with_limit(history, user_messages, summary, max_tokens) {
    // Select user messages from most recent, working backward
    let mut remaining = max_tokens;  // 20,000
    for message in user_messages.iter().rev() {
        let tokens = approx_token_count(message);
        if tokens <= remaining {
            selected.push(message);
            remaining -= tokens;
        } else {
            selected.push(truncate(message, remaining));
            break;
        }
    }
    // Append selected user messages + summary as final item
}
```

### Strategy B: Remote (Server-side) Compaction
Used for OpenAI providers. Delegates to `compact_conversation_history` API.

**Pre-processing:**
1. Trim function call outputs from end if over context window
2. Preserve ghost snapshots separately
3. Send full prompt (with tools, instructions) to API
4. Post-process: filter compacted history

**Post-processing filter (`should_keep_compacted_history_item`):**
- DROP: `developer` role messages (stale instructions)
- DROP: `user` messages that aren't real user messages or hook prompts
- KEEP: `assistant` messages, `Compaction` items, real user messages

## 6. Initial Context Injection After Compaction

Two modes controlled by `InitialContextInjection`:
- **`DoNotInject`**: Used for pre-turn/manual compaction. Clears `reference_context_item`, next regular turn fully reinjects.
- **`BeforeLastUserMessage`**: Used for mid-turn compaction. Inserts fresh context (permissions, env, personality) before the last real user message.

**Insertion priority:**
1. Before last real user message (preferred)
2. Before last summary message (fallback)
3. Before last Compaction item (for remote compaction)
4. Append (if none found)

## 7. Thread Rollback / Undo System

### Ghost Snapshots
- Created as `GhostSnapshot { ghost_commit }` in ResponseItem
- Backed by actual git commits (ghost commits)
- Created via `codex_git_utils::create_ghost_commit_with_report`
- Warning if snapshot takes > 240 seconds
- Large untracked files/dirs can be excluded

### Undo
- Finds last `GhostSnapshot` in history
- Restores via `restore_ghost_commit_with_options`
- Removes the snapshot from history items
- Preserves `reference_context_item`

### History Rollback (`drop_last_n_user_turns`)
- Drops last N user turns from history
- Trims contextual developer/user messages above the cut point
- Clears `reference_context_item` if trimming mixed bundles
- Never crosses first instruction turn boundary (preserves session prefix)

## 8. Output Truncation

Applied when recording items into ContextManager:

```rust
fn process_item(&self, item, policy: TruncationPolicy) -> ResponseItem {
    // Only truncate FunctionCallOutput and CustomToolCallOutput
    // Policy multiplied by 1.2 for serialization overhead
    let policy_with_budget = policy * 1.2;
    truncate_function_output_payload(output, policy_with_budget)
}
```

**TruncationPolicy:**
- `Bytes(n)`: Byte budget
- `Tokens(n)`: Token budget (converted to bytes via * 4)

**Default:** 10,000 bytes per tool output.

**Middle truncation:** Splits budget 50/50 between prefix and suffix, inserts marker: `...N tokens truncated...` or `...N chars truncated...`

## 9. History Normalization (before sending to model)

Three invariants enforced by `normalize_history`:
1. Every function/tool call has a corresponding output entry (synthesizes missing)
2. Every output has a corresponding call entry (removes orphans)
3. Images stripped when model doesn't support them

## 10. Context Updates Between Turns

`build_settings_update_items` diffs against `reference_context_item`:
- Environment context (cwd, shell)
- Model switch instructions
- Permission updates (sandbox, approval)
- Collaboration mode changes
- Realtime mode toggle
- Personality changes

Only emits developer/user messages when things actually changed.

---

# PART 2: SWE-AGENT (Python)

## Architecture Overview

All history processing in single file: `sweagent/agent/history_processors.py`

## 1. Data Structures

```python
# sweagent/types.py
class _HistoryItem(TypedDict):
    role: str  # "user", "assistant", "tool", "system"
    content: str | list[dict[str, Any]]
    message_type: Literal["thought", "action", "observation"]

class HistoryItem(_HistoryItem, total=False):
    agent: str
    is_demo: bool
    thought: str
    action: str | None
    tool_calls: list[dict[str, str]] | None
    tags: list[str]  # Used by processors for marking
    thinking_blocks: list[dict[str, Any]] | None

History = list[HistoryItem]
```

## 2. Processor Composition System

```python
# In DefaultAgentConfig:
history_processors: list[HistoryProcessor] = [DefaultHistoryProcessor()]

# Applied as a chain (pipeline):
@property
def messages(self) -> list[dict[str, Any]]:
    filtered_history = [e for e in self.history if e["agent"] == self.name]
    messages = filtered_history
    for processor in self.history_processors:
        messages = processor(messages)
    return messages
```

**Type union (discriminated by `type` field):**
```python
HistoryProcessor = Annotated[
    DefaultHistoryProcessor
    | LastNObservations
    | ClosedWindowHistoryProcessor
    | TagToolCallObservations
    | CacheControlHistoryProcessor
    | RemoveRegex
    | ImageParsingHistoryProcessor,
    Field(discriminator="type"),
]
```

All processors are Pydantic BaseModels with `model_config = ConfigDict(extra="forbid")`.

## 3. DefaultHistoryProcessor

```python
class DefaultHistoryProcessor(BaseModel):
    type: Literal["default"] = "default"
    def __call__(self, history: History) -> History:
        return history  # No-op passthrough
```

## 4. LastNObservations — THE Core Processor

**Purpose:** Elide all but the last N observations (tool outputs). The original paper's primary context management mechanism.

**Parameters:**
```python
class LastNObservations(BaseModel):
    n: int                           # Number of observations to keep (must be > 0)
    polling: int = 1                 # Steps between updates (for cache friendliness)
    always_remove_output_for_tags: set[str] = {"remove_output"}
    always_keep_output_for_tags: set[str] = {"keep_output"}
    type: Literal["last_n_observations"] = "last_n_observations"
```

**Algorithm (`_get_omit_indices`):**
```python
def _get_omit_indices(self, history: History) -> list[int]:
    # 1. Find all observation indices (excluding demos)
    observation_indices = [
        idx for idx, entry in enumerate(history)
        if entry.get("message_type") == "observation" and not entry.get("is_demo", False)
    ]

    # 2. Calculate cutoff with polling quantization
    # This rounds DOWN to nearest polling boundary, then subtracts n
    last_removed_idx = max(0,
        (len(observation_indices) // self.polling) * self.polling - self.n
    )

    # 3. NEVER remove first observation (instance template)
    return observation_indices[1:last_removed_idx]
```

**Polling parameter explained:**
- With `polling=1`: every new step updates which observations are visible (breaks prompt cache)
- With `polling=5, n=5`: keeps between 5 and 10 observations, only updates every 5 steps
- Effect: fewer cache misses, slightly more context used

**Replacement for elided observations:**
```python
data["content"] = f"Old environment output: ({num_text_lines} lines omitted)"
if num_images > 0:
    data["content"] += f" ({num_images} images omitted)"
```

**Tag override system:**
- `always_remove_output_for_tags`: Force-elide even if within last N (default: `{"remove_output"}`)
- `always_keep_output_for_tags`: Force-keep even if outside last N (default: `{"keep_output"}`)
- Tags checked via set intersection: `tags & self.always_keep_output_for_tags`

**Typical config (SWE-agent 0.7):**
```yaml
history_processors:
  - type: last_n_observations
    n: 5
```

## 5. TagToolCallObservations

**Purpose:** Pre-processor that adds tags to history items for specific tool calls, enabling selective preservation by LastNObservations.

```python
class TagToolCallObservations(BaseModel):
    tags: set[str] = {"keep_output"}  # Tags to add
    function_names: set[str] = set()  # Tool names to match
    type: Literal["tag_tool_call_observations"] = "tag_tool_call_observations"
```

**Algorithm:**
```python
def __call__(self, history: History) -> History:
    for entry in history:
        if entry.get("message_type") == "action":
            function_calls = entry.get("tool_calls", [])
            function_names = {call["function"]["name"] for call in function_calls}
            if self.function_names & function_names:
                # Add tags to THIS action entry
                entry["tags"] = list(existing_tags | self.tags)
    return history
```

**Note:** This modifies action entries, not observations. Combined with LastNObservations, the observation following a tagged action gets preserved.

**Typical use:** Keep output of specific tools (e.g., `search` results) even when they fall outside the last N window:
```yaml
history_processors:
  - type: tag_tool_call_observations
    function_names: ["search_dir", "find_file"]
    tags: ["keep_output"]
  - type: last_n_observations
    n: 5
```

## 6. ClosedWindowHistoryProcessor

**Purpose:** For file-viewing tools, replaces outdated file windows with a line count summary. Only keeps the LAST window shown for each file.

```python
class ClosedWindowHistoryProcessor(BaseModel):
    _pattern = re.compile(r"^(\d+)\:.*?(\n|$)", re.MULTILINE)  # Numbered lines
    _file_pattern = re.compile(r"\[File:\s+(.*)\s+\(\d+\s+lines\ total\)\]")
```

**Algorithm (reverse scan):**
```python
def __call__(self, history):
    windows = set()  # Files whose latest window we've already seen
    for entry in reversed(history):
        if entry["role"] != "user" or entry.get("is_demo", False):
            new_history.append(entry)
            continue
        matches = self._pattern.finditer(entry["content"])  # Find numbered lines
        file_match = self._file_pattern.search(entry["content"])  # Find file header
        if file_match:
            file = file_match.group(1)
            if file in windows:
                # Replace numbered lines with summary
                data["content"] = (
                    content[:start]
                    + f"Outdated window with {len(matches)} lines omitted...\n"
                    + content[end:]
                )
            windows.add(file)
        new_history.append(data)
    return list(reversed(new_history))
```

**Key insight:** Only processes `user` role messages (observations come as user messages in SWE-agent's format). Scans in reverse so the FIRST occurrence seen (newest) is kept, all older are summarized.

## 7. CacheControlHistoryProcessor

**Purpose:** Add Anthropic-specific `cache_control: {"type": "ephemeral"}` markers for prompt caching.

```python
class CacheControlHistoryProcessor(BaseModel):
    last_n_messages: int = 2         # How many messages to cache-mark
    last_n_messages_offset: int = 0  # Skip N messages from end
    tagged_roles: list[str] = ["user", "tool"]  # Only mark these roles
```

**Algorithm:**
```python
def __call__(self, history: History) -> History:
    n_tagged = 0
    for i_entry, entry in enumerate(reversed(history)):
        _clear_cache_control(entry)  # Remove any existing markers
        if (n_tagged < self.last_n_messages
            and entry["role"] in self.tagged_roles
            and i_entry >= self.last_n_messages_offset):
            _set_cache_control(entry)
            n_tagged += 1
    return list(reversed(new_history))
```

**Cache control format:**
```python
# For user messages: add to content item
entry["content"] = [{"type": "text", "text": text, "cache_control": {"type": "ephemeral"}}]

# For tool messages (workaround for Claude bug):
entry["content"][0].pop("cache_control", None)  # Remove from content
entry["cache_control"] = {"type": "ephemeral"}   # Add to message level
```

**Configuration guidance:**
- `last_n_messages=2`: Standard multi-turn (cache 2 breakpoints)
- `last_n_messages=1`: When resampling/concurrent instances
- `last_n_messages<=0`: Remove all cache control markers

## 8. RemoveRegex

**Purpose:** Strip arbitrary content patterns from history.

```python
class RemoveRegex(BaseModel):
    remove: list[str] = ["<diff>.*</diff>"]  # Regex patterns
    keep_last: int = 0                        # Keep N most recent items unchanged
```

**Algorithm:**
```python
def __call__(self, history: History) -> History:
    for i_entry, entry in enumerate(reversed(history)):
        entry = copy.deepcopy(entry)  # Don't mutate original
        if i_entry < self.keep_last:
            new_history.append(entry)  # Skip recent items
        else:
            for pattern in self.remove:
                # re.DOTALL makes . match newlines
                content = re.sub(pattern, "", content, flags=re.DOTALL)
    return list(reversed(new_history))
```

**Key:** Default strips `<diff>.*</diff>` blocks. `keep_last` preserves the N most recent entries unchanged (useful for keeping the latest diff visible).

## 9. ImageParsingHistoryProcessor

**Purpose:** Convert embedded base64 images in markdown to multi-modal format.

```python
class ImageParsingHistoryProcessor(BaseModel):
    type: Literal["image_parsing"] = "image_parsing"
    allowed_mime_types: set[str] = {"image/png", "image/jpeg", "image/webp"}
    _pattern = re.compile(r"(!\[([^\]]*)\]\(data:)([^;]+);base64,([^)]+)(\))")
```

Only processes `user` and `tool` role messages. Converts `![alt](data:image/png;base64,...)` into `{"type": "image_url", "image_url": {"url": "data:..."}}` segments.

## 10. Configuration via YAML

All processors configured in agent YAML files using discriminated unions:

```yaml
agent:
  history_processors:
    # Pre-tag specific tool outputs for preservation
    - type: tag_tool_call_observations
      function_names: ["search_dir", "find_file"]
      tags: ["keep_output"]
    # Elide old observations
    - type: last_n_observations
      n: 5
      polling: 3
    # Strip diffs from older items
    - type: remove_regex
      remove: ["<diff>.*</diff>"]
      keep_last: 2
    # Add Claude cache markers
    - type: cache_control
      last_n_messages: 2
    # Parse base64 images
    - type: image_parsing
```

---

# COMPARATIVE ANALYSIS

## Key Differences

| Aspect | Codex CLI | SWE-agent |
|--------|-----------|-----------|
| **Language** | Rust | Python |
| **Strategy** | LLM summarization + server-side compaction | Heuristic observation elision |
| **Token counting** | Approximate (bytes/4 ceiling) | None (line counting for display) |
| **Trigger** | Token threshold (90% of context window) | Every step (processor pipeline) |
| **Summarization** | Yes (LLM generates handoff summary) | No (just omits/replaces content) |
| **Composability** | Single strategy (local or remote) | Pipeline of composable processors |
| **Cache awareness** | Prefix-based (trims from front) | Explicit cache_control processor + polling |
| **Rollback** | Git ghost commits + history rollback | None |
| **Output truncation** | Middle truncation with budget | Line omission markers |
| **Image handling** | Token estimation with caching | Base64 parsing to multimodal |

## Key Insights for Agent Implementation

1. **Codex's 90% threshold is battle-tested** — auto-compact at 90% of context window, never at 100%
2. **Approximate token counting (bytes/4) is sufficient** — no need for exact tokenizer
3. **User messages are preserved across compaction** — last 20K tokens of user messages kept
4. **SWE-agent's polling parameter is clever** — trades slightly more context for cache efficiency
5. **Tag-based selective preservation** — tool outputs can be marked for keeping (SWE-agent)
6. **Ghost snapshots for undo** — Codex creates actual git commits for filesystem rollback
7. **Context update diffing** — only send changed environment/permissions/personality between turns
8. **Pipeline composition** — SWE-agent's approach of chaining simple processors is very extensible
9. **RemoveRegex with keep_last** — simple pattern to strip verbose content from older history
10. **Mid-turn compaction** — Codex can compact during a multi-step tool loop without losing the user message
