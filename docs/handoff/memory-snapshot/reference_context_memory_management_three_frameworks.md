# Context & Memory Management: Anthropic SDK vs Google ADK vs AutoGen

## Deep source-code analysis from cloned repos (2026-03-31)

---

## 1. ANTHROPIC SDK (anthropic-sdk-python)

### 1A. Server-Side Context Management (beta API)

The SDK exposes a `context_management` parameter on `beta.messages.create()` with three server-side edit types:

**`clear_tool_uses_20250919`** - Clears old tool use/result pairs from context:
```python
BetaClearToolUses20250919EditParam:
  type: "clear_tool_uses_20250919"
  trigger: InputTokensTrigger(type="input_tokens", value=30000)  # or ToolUsesTrigger
  keep: ToolUsesKeep(type="tool_uses", value=3)  # retain N recent tool uses
  clear_at_least: InputTokensClearAtLeast(type="input_tokens", value=5000)
  exclude_tools: ["web_search"]  # tools whose uses are never cleared
  clear_tool_inputs: bool | list[str]  # clear inputs for specific tools
```

**`clear_thinking_20251015`** - Clears thinking/reasoning blocks from context (for extended thinking models).

**`compact_20260112`** - Server-side automatic compaction:
```python
BetaCompact20260112EditParam:
  type: "compact_20260112"
  trigger: InputTokensTrigger(value=150000)  # default 150K tokens
  instructions: str | None  # custom summarization instructions
  pause_after_compaction: bool  # return compaction block to user before continuing
```

The response includes `context_management.applied_edits` showing which edits were applied.

### 1B. Client-Side Compaction (Tool Runner)

`_beta_runner.py` implements `CompactionControl` in the tool runner loop:

```python
CompactionControl:
  enabled: Required[bool]
  context_token_threshold: int  # default 100,000
  model: str  # defaults to same model as runner
  summary_prompt: str  # detailed continuation summary prompt
```

**How it works:**
1. After each tool loop iteration, `_check_and_compact()` checks if `input_tokens + cache_creation + cache_read + output_tokens >= threshold`
2. When triggered, sends all messages + summary prompt to the model
3. Replaces entire message history with a single user message containing the summary
4. The default prompt asks for: Task Overview, Current State, Important Discoveries, Next Steps, Context to Preserve
5. Summary is wrapped in `<summary></summary>` tags
6. After compaction, tool call generation is skipped for that iteration

**Key detail:** Before summarizing, tool_use blocks are stripped from the last assistant message to avoid API errors (tool_use requires tool_result).

### 1C. Memory Tool (beta)

`BetaAbstractMemoryTool` / `BetaLocalFilesystemMemoryTool` - A file-system-based persistent memory:

- **Type:** `memory_20250818` (server-side builtin tool type, not a custom function tool)
- **Operations:** view, create, str_replace, insert, delete, rename
- **Storage:** Files under `./memory/memories/` directory
- **Path validation:** Symlink escape prevention, all paths must start with `/memories`
- **Atomic writes:** Uses `O_CREAT | O_EXCL | O_WRONLY` + `fsync` + `os.replace`
- **Subclassable:** `BetaAbstractMemoryTool` is abstract, allowing custom backends (database, cloud, encrypted, etc.)

### 1D. Token Counting

`beta.messages.count_tokens()` returns `BetaMessageTokensCount`:
```python
class BetaMessageTokensCount:
  input_tokens: int
  context_management: BetaCountTokensContextManagementResponse | None
```

### 1E. Prompt Caching

No SDK-level caching utilities. Caching is done via `cache_control` parameters on message blocks:
```python
{"cache_control": {"type": "ephemeral"}}
```
Applied to tools, system prompts, or message content blocks. The API handles caching server-side.

### 1F. Multi-Turn Management

The SDK itself is stateless - it's a thin HTTP client. Multi-turn conversation management is the caller's responsibility. The `tool_runner` pattern (`run_tools()` / `tool_runner()`) manages the loop:
- `append_messages()` adds to conversation history
- `set_messages_params()` replaces params
- `max_iterations` controls loop limit
- `_should_stop()` checks iteration count

---

## 2. GOOGLE ADK (adk-python)

### 2A. Session & State Architecture

**Session** stores all events + state:
```python
class Session:
  id: str
  app_name: str
  user_id: str
  state: dict[str, Any]  # key-value state
  events: list[Event]     # full event history
  last_update_time: float
```

**State** has delta tracking with three scopes:
```python
class State:
  APP_PREFIX = "app:"    # shared across all sessions of an app
  USER_PREFIX = "user:"  # shared across user's sessions
  TEMP_PREFIX = "temp:"  # ephemeral, not persisted
```
- `_value` = committed state, `_delta` = pending changes
- `has_delta()` checks for uncommitted changes

**Session Services:** InMemorySessionService, DatabaseSessionService (SQLite), VertexAiSessionService (cloud).

### 2B. Event Compaction (Two Strategies)

**Strategy 1: Token Threshold Compaction**
```python
EventsCompactionConfig:
  token_threshold: int       # e.g., 100000 tokens
  event_retention_size: int  # keep N recent events uncompacted
  summarizer: BaseEventsSummarizer
```

How it works:
1. `CompactionRequestProcessor` runs BEFORE content building
2. Checks `_latest_prompt_token_count()` - first tries actual `usage_metadata.prompt_token_count` from recent events, falls back to character-based estimate (`total_chars // 4`)
3. Selects events to compact: everything after last compaction, minus `event_retention_size` retained events
4. **Rolling summary seed:** If previous compaction exists, includes its summary as first event so next summary supersedes it
5. **Safety:** `_truncate_events_before_pending_function_call()` never compacts events with unanswered function calls
6. **Split safety:** `_safe_token_compaction_split_index()` prevents orphaning function responses from their calls
7. LLM summarizer generates summary, stored as `EventCompaction` with `start_timestamp` + `end_timestamp` + `compacted_content`
8. Summary event appended to session via session service

**Strategy 2: Sliding Window Compaction**
```python
EventsCompactionConfig:
  compaction_interval: int   # N new invocations before compacting
  overlap_size: int          # overlap invocations for context continuity
```

How it works:
- Counts new invocation IDs since last compaction
- When `>= compaction_interval`, selects range including `overlap_size` preceding invocations
- Creates overlapping summaries for context continuity

**Content Processing (`contents.py`):**
- `_process_compaction_events()` handles overlapping compaction ranges (subsumed compactions are removed)
- `_get_contents()` applies rewind filtering, branch filtering, compaction, transcription aggregation
- `_get_current_turn_contents()` for `include_contents='none'` mode (only current turn)
- `_present_other_agent_message()` reformats cross-agent messages as user context with `[agent_name] said:` prefix
- Invisible parts (thoughts without function calls) are filtered out

### 2C. Memory Service

**BaseMemoryService** abstract interface:
```python
add_session_to_memory(session)        # full session ingestion
add_events_to_memory(events, ...)     # incremental delta
add_memory(memories, ...)             # direct memory writes
search_memory(app_name, user_id, query) -> SearchMemoryResponse
```

**InMemoryMemoryService** - keyword matching (prototyping only):
- Events stored per `{app_name}/{user_id}/{session_id}`
- Search: word-level intersection between query and event text

**VertexAiRagMemoryService** - production RAG-based:
- Writes session events to temp files, uploads to Vertex AI RAG corpus
- Search via `rag.retrieval_query()` with `similarity_top_k` and `vector_distance_threshold`

**VertexAiMemoryBankService** - managed memory bank (Google Cloud).

**Context integration:** `Context.search_memory(query)` accessible from agent tools/callbacks. Results injected into LLM instructions via `instructions.py` processor.

### 2D. Context Caching

`ContextCacheRequestProcessor`:
- Finds latest `CacheMetadata` from session events
- Tracks `invocations_used` count across invocations
- Passes cache info to model-specific managers (e.g., `GeminiContextCacheManager`)
- Separate from compaction - caches stable prefix of conversation

### 2E. Multi-Agent Context Sharing

- **Branch-based segregation:** `_is_event_belongs_to_branch()` uses dot-delimited branches (e.g., `agent_1.agent_2`)
- Child agents see parent's events, but peers are isolated
- Cross-agent messages reformatted as `[agent_name] said: ...` user context
- `InvocationContext` carries `branch`, `session`, `memory_service`, `events_compaction_config`
- `max_llm_calls` limit enforced via `_InvocationCostManager`

### 2F. Tool Result Management

- Function calls/responses are always kept visible (never filtered as "invisible")
- Async function responses are rearranged to pair with their calls
- Pending (unanswered) function calls are protected from compaction

---

## 3. AUTOGEN

### 3A. ChatCompletionContext (Model Context)

Abstract base with pluggable recall strategies:

**UnboundedChatCompletionContext** - stores all messages, returns all.

**BufferedChatCompletionContext** - sliding window:
```python
buffer_size: int  # keep last N messages
# Handles FunctionExecutionResultMessage at boundaries
```

**HeadAndTailChatCompletionContext** - keeps first N + last M messages:
```python
head_size: int  # keep first N messages
tail_size: int  # keep last M messages
# Inserts "Skipped {N} messages." placeholder between head and tail
# Handles function call/result boundary issues
```

**TokenLimitedChatCompletionContext** - token-aware pruning:
```python
model_client: ChatCompletionClient  # for count_tokens() / remaining_tokens()
token_limit: int | None             # explicit limit or use model's remaining
tool_schema: List[ToolSchema]       # counted in token budget
```
- Removes messages from the **middle** (`len//2`) when over limit
- This middle-out removal preserves both early context and recent messages

All contexts:
- Support `save_state()` / `load_state()` for serialization
- Handle `FunctionExecutionResultMessage` boundary cases (never start with orphaned result)
- Are `Component`-based for declarative configuration

### 3B. Memory Interface

```python
class Memory(ABC):
  async def update_context(model_context: ChatCompletionContext) -> UpdateContextResult
  async def query(query: str | MemoryContent) -> MemoryQueryResult
  async def add(content: MemoryContent) -> None
  async def clear() -> None
  async def close() -> None
```

**Key design:** `update_context()` mutates the model context directly. Memory is responsible for deciding what to inject.

**ListMemory** - chronological list, injects all as `SystemMessage`:
```python
# Format: "Relevant memory content (in chronological order):\n1. ...\n2. ..."
await model_context.add_message(SystemMessage(content=memory_context))
```

**MemoryContent** supports multiple types: text, JSON, markdown, image, binary.

### 3C. AssistantAgent Context Flow

```python
class AssistantAgent:
  _model_context: ChatCompletionContext  # pluggable context strategy
  _memory: List[Memory]                  # optional memory modules
```

On each `on_messages()`:
1. Query each memory module, add `MemoryQueryEvent` to inner messages
2. Add incoming messages to model context
3. Build LLM messages from context + system message
4. Execute tool calls in loop (up to `max_tool_iterations`)
5. State is maintained between calls (caller passes only NEW messages)

### 3D. Group Chat Context

`BaseGroupChatManager`:
- Maintains `_message_thread: List[BaseAgentEvent | BaseChatMessage]` - shared across all participants
- All agent responses (inner messages + chat message) appended to thread
- Thread passed to `select_speaker()` for next speaker selection
- Participants receive messages via pub/sub (topic-based)
- No built-in summarization or compression of group chat thread

**SocietyOfMindAgent** - inner team with summarization:
- Runs inner team, collects all inner messages
- Wraps in instruction + response prompt, sends to model for final response
- Previous context passed as `HandoffMessage` with `context=prev_content`
- Inner team is **reset** after each call
- Model context persists across outer calls

### 3E. Task-Centric Memory (Experimental)

`MemoryController` - learning from failures:
```python
class MemoryController:
  memory_bank: MemoryBank     # persistent storage with topic-based retrieval
  prompter: Prompter          # LLM-based task generalization and insight extraction
  grader: Grader              # response correctness checking
```

Features:
- `train_on_task()`: iteratively assigns task, learns insights from failures
- `add_memo(insight, task)`: generalizes task, extracts topics, stores indexed insight
- `retrieve_relevant_memos(task)`: generalizes query task, topic-based retrieval, LLM validation
- `consider_memo_storage(text)`: extracts advice from arbitrary text
- Memories formatted as: `## Important insights that may help solve tasks like this\n- insight1\n- insight2`

### 3F. State Management

- `save_state()` / `load_state()` on agents and teams
- `AssistantAgentState` includes model_context state
- `TeamState` includes all agent states + termination condition state
- Enables pause/resume across sessions

---

## COMPARISON MATRIX

| Feature | Anthropic SDK | Google ADK | AutoGen |
|---------|--------------|------------|---------|
| **Context window management** | Server-side API (clear_tool_uses, compact) + client-side CompactionControl | Token threshold + sliding window compaction with LLM summarization | 4 pluggable ChatCompletionContext strategies (unbounded, buffered, head+tail, token-limited) |
| **Compaction trigger** | Token count threshold (100K client, 150K server default) | Token threshold or invocation count | Token count or message count |
| **Compaction method** | LLM summary replacing all history | LLM summary as event, originals retained | Middle-out message removal (no summarization) |
| **Memory persistence** | File-system memory tool (builtin) | Session service (SQLite/Vertex AI) + Memory service (RAG) | ListMemory (in-process), MemoryBank (disk) |
| **Cross-turn memory** | Memory tool files + conversation history | Session events + memory search + state dict | ChatCompletionContext state + Memory modules |
| **Multi-agent context** | N/A (single agent SDK) | Branch-based event segregation, cross-agent reformatting | Group chat shared thread, SocietyOfMindAgent summarization |
| **Token counting** | `count_tokens()` API endpoint | `usage_metadata` from responses + char//4 estimate | `model_client.count_tokens()` + `remaining_tokens()` |
| **Tool result handling** | clear_tool_uses with keep/exclude | Protected from compaction if pending; rearranged for pairing | FunctionExecutionResultMessage boundary handling |
| **Prompt caching** | `cache_control: ephemeral` on blocks | ContextCacheRequestProcessor with Gemini cache manager | N/A |
| **State serialization** | N/A (stateless HTTP client) | Session + State with delta tracking | save_state/load_state on all components |
| **Safety during compaction** | Strip tool_use from last msg before summarizing | Never compact pending function calls; safe split index | Remove orphaned FunctionExecutionResultMessage at boundaries |

---

## KEY ARCHITECTURAL INSIGHTS

1. **Anthropic is moving context management server-side.** The `compact_20260112` edit type means the API itself handles compaction. The client-side CompactionControl in the tool runner is likely transitional.

2. **Google ADK has the most sophisticated compaction.** Rolling summaries (seed previous summary into next), overlapping sliding windows, branch-aware filtering, pending function call protection, and safe split indices. This is production-grade.

3. **AutoGen takes a pluggable, no-LLM approach to context windowing.** The `TokenLimitedChatCompletionContext` removes middle messages without summarization. The `HeadAndTailChatCompletionContext` preserves beginning and end. No built-in LLM-based summarization in the core context layer.

4. **Memory is orthogonal to context in all three.** All three separate "what the LLM sees this turn" (context) from "what persists across sessions" (memory). The injection point differs:
   - Anthropic: builtin tool type that the model invokes
   - Google ADK: injected into instructions via processor pipeline
   - AutoGen: `Memory.update_context()` adds SystemMessage to context

5. **Function call safety is universal.** All three handle the edge case of orphaned function results. ADK is most thorough (prevents compacting pending calls).

6. **Google ADK's event-sourced architecture is unique.** Events are never deleted - compaction summaries are additional events that override ranges. This enables audit trails and overlapping summaries.
