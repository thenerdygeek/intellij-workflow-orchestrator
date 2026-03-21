# Agentic AI Tools Architecture Research
## Aider & OpenCode — Token Management, Context, and Tool Efficiency

Research date: 2026-03-21
Sources: aider (github.com/Aider-AI/aider), opencode (github.com/anomalyco/opencode)

---

## 1. AIDER (Python)

### 1.1 Token Management

**Tracking approach: Dual — API response preferred, heuristic fallback**

Aider uses `completion.usage` from the API response when available, and falls back to local token counting via `litellm.token_counter()` when the API doesn't return usage data.

Key code in `aider/coders/base_coder.py:1994-2019`:
```python
def calculate_and_show_tokens_and_cost(self, messages, completion=None):
    if completion and hasattr(completion, "usage") and completion.usage is not None:
        prompt_tokens = completion.usage.prompt_tokens
        completion_tokens = completion.usage.completion_tokens
        cache_hit_tokens = getattr(completion.usage, "prompt_cache_hit_tokens", 0) or getattr(
            completion.usage, "cache_read_input_tokens", 0
        )
        cache_write_tokens = getattr(completion.usage, "cache_creation_input_tokens", 0)
    else:
        prompt_tokens = self.main_model.token_count(messages)
        completion_tokens = self.main_model.token_count(self.partial_response_content)
```

**Local token counting** (`aider/models.py:635-655`):
- For message lists: uses `litellm.token_counter(model=self.name, messages=messages)` which handles model-specific tokenizers
- For strings: uses the model's tokenizer directly (`len(self.tokenizer(msgs))`)
- No custom chars-per-token heuristic — relies on proper tiktoken/sentencepiece tokenizers

**What's displayed to users:**
- Per-message: tokens sent, cache write, cache hit, tokens received
- Per-message and cumulative session cost in USD
- `/tokens` command shows full context window breakdown by category (system, examples, repo map, chat files, history, current) with per-item cost and remaining budget

**Cost calculation** (`base_coder.py:2070-2100`):
- First tries `litellm.completion_cost()` for non-streaming
- Falls back to manual calculation from `input_cost_per_token`, `output_cost_per_token`
- Special handling for Anthropic cache pricing (1.25x write, 0.10x read)
- Special handling for DeepSeek cache pricing

### 1.2 Multi-Turn Conversation & Context Compression

**History architecture:**
- `done_messages`: completed conversation turns (user + assistant pairs)
- `cur_messages`: the current turn being processed
- After each turn, `cur_messages` get appended to `done_messages`

**Summarization trigger** (`base_coder.py:1002-1034`):
- Uses `ChatSummary` class from `aider/history.py`
- `max_chat_history_tokens` = `min(max(max_input_tokens / 16, 1024), 8192)`
  - For a 200K model: ~8192 tokens history budget
  - For a 128K model: ~8192 tokens
  - For a 32K model: ~2048 tokens
- Summarization is triggered after each message turn when `done_messages` exceed this limit
- **Runs in a background thread** so it doesn't block the user

**Summarization algorithm** (`aider/history.py:27-123`):
1. If messages fit within budget, return as-is
2. Split messages into head (older) and tail (newer), keeping the tail at ~50% of budget
3. Summarize the head by sending it to a weak model with a specific prompt
4. Recursively summarize if the result still doesn't fit (up to depth 3)
5. The summary prompt asks the model to write as the user in first person: "I asked you..."
6. Summary is prefixed with "I spoke to you previously about a number of things."

**Key insight:** Summarization uses the **weak model** (cheaper/faster) first, falling back to the main model. This keeps summarization cheap.

### 1.3 Context Window Management

**Message assembly** (`base_coder.py:1260-1331`, `chat_chunks.py`):

Messages are assembled in this exact order via `ChatChunks`:
1. **system** — main system prompt + edit format instructions
2. **examples** — few-shot examples for the edit format
3. **readonly_files** — read-only file contents
4. **repo** — repository map (tree-sitter based code structure)
5. **done** — summarized conversation history
6. **chat_files** — editable file contents
7. **cur** — current user message
8. **reminder** — system reminder (re-injected near end for recency bias)

**Repo map budget** (`aider/models.py:767-774`):
- Default: 1024 tokens
- Scales with model: `max_input_tokens / 8`, clamped to [1024, 4096]
- When no files in chat: multiplied by `map_mul_no_files` (default 8x)
- Binary search to fit repo map into token budget

**Token budget check** (`base_coder.py:1396-1409`):
- Before sending, checks if `input_tokens >= max_input_tokens`
- If exceeded, warns user and suggests `/drop` or `/clear`
- Does NOT automatically truncate — relies on user action

**Cache control** (`chat_chunks.py:28-55`):
- Adds Anthropic `cache_control: ephemeral` headers to stable message boundaries
- Cache points: end of examples/system, end of repo map, end of chat files
- Background cache warming thread pings every ~5 minutes with `max_tokens=1`

### 1.4 Tool Call Efficiency

**Aider does NOT use the standard tool/function calling API for most edit formats.** Instead:
- Edit instructions are returned as structured text in the assistant's response (search/replace blocks, whole file, unified diff, etc.)
- The `editblock_func_coder.py` is the exception — it uses OpenAI function calling
- No tool definitions are sent to the API in the standard flow
- This means zero overhead from tool definitions in the context

**No tool result truncation:** Aider doesn't send tool results back. The model outputs edits, Aider applies them, and the next turn starts fresh.

### 1.5 API Integration

- Uses `litellm` as the abstraction layer for all providers
- Catches `ContextWindowExceededError` specifically and shows exhaustion diagnostics
- Retry logic with exponential backoff for transient errors
- Tracks `prompt_tokens`, `completion_tokens`, `cache_hit_tokens`, `cache_write_tokens` from API response
- Falls back to local counting only when API doesn't provide usage data

---

## 2. OPENCODE (TypeScript)

### 2.1 Token Management

**Tracking approach: API response only, with heuristic estimation for pruning**

OpenCode uses the `usage` object from the Vercel AI SDK's `streamText` response. Token counts come directly from the API.

Key code in `packages/opencode/src/session/index.ts:791-867`:
```typescript
export const getUsage = fn(..., (input) => {
    const inputTokens = safe(input.usage.inputTokens ?? 0)
    const outputTokens = safe(input.usage.outputTokens ?? 0)
    const reasoningTokens = safe(input.usage.reasoningTokens ?? 0)
    const cacheReadInputTokens = safe(input.usage.cachedInputTokens ?? 0)
    const cacheWriteInputTokens = safe(
        (input.metadata?.["anthropic"]?.["cacheCreationInputTokens"] ?? 0)
    )
    ...
})
```

**Heuristic estimation** (`packages/opencode/src/util/token.ts`):
```typescript
export namespace Token {
    const CHARS_PER_TOKEN = 4
    export function estimate(input: string) {
        return Math.max(0, Math.round((input || "").length / CHARS_PER_TOKEN))
    }
}
```
This is used ONLY for the pruning heuristic (estimating old tool output sizes), not for API calls or budget decisions.

**Cost calculation:**
- Uses `Decimal.js` for precise cost math (avoids floating-point errors)
- Provider-aware: handles Anthropic's different token counting (inputTokens excludes cached)
- Supports tiered pricing for >200K context
- Charges reasoning tokens at output token rate

### 2.2 Multi-Turn Conversation & Context Compression

**History architecture:**
- All messages persisted to SQLite database
- Each session has a linear message chain (user -> assistant -> user -> ...)
- Messages have `parts` (text, tool calls, tool results, reasoning, compaction markers, patches)

**Compaction (context compression):**

Two-phase approach:

**Phase 1: Tool Output Pruning** (`packages/opencode/src/session/compaction.ts:59-99`):
- Scans messages backwards
- Protects the last 2 turns and the last 40,000 tokens of tool results
- Beyond that threshold, replaces old tool outputs with `"[Old tool result content cleared]"`
- Minimum 20,000 tokens must be prunable before acting
- Protected tools: `skill` (never pruned)
- Pruning is triggered before summarization

**Phase 2: Full Compaction** (`compaction.ts:102-297`):
- Triggered when `tokens.total >= model.limit.input - reserved_buffer`
- Reserved buffer: `min(20_000, maxOutputTokens)`
- Sends ALL conversation history to the model with a structured summarization prompt
- Summary template: Goal, Instructions, Discoveries, Accomplished, Relevant files
- Creates a "compaction" message that acts as a new conversation start
- Everything before the compaction marker is discarded from future API calls

**Overflow compaction trigger** (`processor.ts:283-288`):
```typescript
if (!input.assistantMessage.summary &&
    (await SessionCompaction.isOverflow({ tokens: usage.tokens, model: input.model }))) {
    needsCompaction = true
}
```
- Uses ACTUAL token counts from the API response (not estimates)
- Also triggered when the API returns a `ContextOverflowError`

**`filterCompacted` function** (`message-v2.ts:882-898`):
- Reads messages in reverse (newest first) from SQLite
- Stops at the first compaction marker with a completed summary
- Only messages AFTER the compaction point are included in API calls

### 2.3 Tool Call Efficiency

**All tools sent every call:**
- OpenCode uses standard tool calling API (Vercel AI SDK's `streamText`)
- All registered tools are sent in every API call (filtered only by agent permissions)
- Tools include: read, write, edit, multiedit, bash, grep, glob, ls, lsp, webfetch, websearch, task, batch, etc.

**Tool output truncation** (`packages/opencode/src/tool/truncate.ts`):
- Max 2,000 lines OR 50KB per tool result
- Full output saved to a temp file on disk
- Truncated output includes a hint: "Use Grep/Read to view full content" or "Use Task tool to delegate"
- 7-day retention with hourly cleanup

**Batch tool** (`packages/opencode/src/tool/batch.ts`):
- Allows up to 25 tool calls in parallel in a single tool invocation
- Reduces multi-turn overhead for read-heavy operations

**Doom loop detection** (`processor.ts:153-177`):
- If the same tool is called 3 times with identical arguments, triggers a permission check
- Prevents the model from repeating failed operations

**Sub-agent delegation** (`packages/opencode/src/tool/task.ts`):
- `task` tool spawns a sub-agent in a child session
- Agent types: build, plan, explore, compaction, etc.
- Child sessions have their own context windows
- Result is summarized back to parent session

### 2.4 Context Window Management

**What's sent each turn:**
1. System messages (provider-specific prompt + environment info + skills)
2. Full message history since last compaction point
3. All tool definitions
4. Tool results inline (or `[Old tool result content cleared]` for pruned ones)

**Context budget calculation** (`compaction.ts:33-49`):
```typescript
const context = input.model.limit.context  // e.g., 200000
const reserved = config.compaction?.reserved ?? Math.min(COMPACTION_BUFFER, maxOutputTokens)
const usable = input.model.limit.input
    ? input.model.limit.input - reserved
    : context - maxOutputTokens
return count >= usable
```
- Uses `model.limit.input` (max input tokens) if available
- Falls back to `context - maxOutputTokens`
- Subtracts a reserve buffer (min of 20K or maxOutputTokens)
- Does NOT explicitly subtract tool definition tokens from the budget

**Model limits** come from `models.dev` (a centralized model database):
```typescript
limit: {
    context: 200000,  // total context window
    input: 180000,    // max input tokens (optional)
    output: 16384,    // max output tokens
}
```

**Max output tokens** (`transform.ts:908-909`):
```typescript
export function maxOutputTokens(model: Provider.Model): number {
    return Math.min(model.limit.output, OUTPUT_TOKEN_MAX) || OUTPUT_TOKEN_MAX
}
```
Default `OUTPUT_TOKEN_MAX = 32_000`

### 2.5 API Integration

**Retry logic** (`packages/opencode/src/session/retry.ts`):
- Exponential backoff: `2000ms * 2^(attempt-1)`
- Respects `retry-after-ms` and `retry-after` headers
- Max delay: 30s without headers, ~24 days with headers
- Context overflow errors are NOT retried — trigger compaction instead
- Rate limits and overloaded errors ARE retried

**Context overflow handling** (`processor.ts:360-366`):
```typescript
if (MessageV2.ContextOverflowError.isInstance(error)) {
    needsCompaction = true
    Bus.publish(Session.Event.Error, { sessionID, error })
}
```
- Catches the error, sets `needsCompaction = true`
- Loop returns "compact", outer loop creates a compaction message
- Compaction runs, then the original request is replayed

---

## 3. KEY ARCHITECTURAL DIFFERENCES

| Aspect | Aider | OpenCode |
|---|---|---|
| **Token counting** | `litellm.token_counter` (proper tokenizers) + API usage | API response only; `chars/4` heuristic only for pruning |
| **History storage** | In-memory lists | SQLite database |
| **Compression trigger** | Soft limit: `max_input_tokens/16` (1K-8K) | Hard limit: `input_limit - reserve_buffer` |
| **Compression strategy** | LLM summarization of old history | Two-phase: prune tool outputs first, then LLM summarization |
| **Compression timing** | Background thread, pre-emptive | Inline, reactive (after overflow detected) |
| **Summarizer model** | Weak/cheap model first | Same model or configured compaction model |
| **Tool calling** | Text-based edits (no tool API) | Standard tool calling API |
| **Tool output size** | N/A (no tool results) | Truncated to 2000 lines / 50KB, full saved to disk |
| **Context assembly** | Structured chunks with explicit ordering | Full message history since last compaction |
| **Repo awareness** | Tree-sitter repo map (token-budgeted) | No repo map; relies on grep/glob/read tools |
| **Cache optimization** | Anthropic cache control headers + warming | Via provider options (Anthropic SDK handles it) |
| **Sub-agent delegation** | No | Yes — `task` tool spawns child sessions |
| **Batch operations** | No | Yes — `batch` tool runs up to 25 tools in parallel |
| **Cost tracking** | Cumulative session cost, per-message cost | Per-step cost with Decimal.js precision |

## 4. LESSONS FOR OUR PLUGIN

### From Aider:
1. **Pre-emptive summarization** at a low threshold (1/16 of context) prevents context overflow entirely
2. **Using the weak model for summarization** keeps costs low
3. **Background thread summarization** means no user-visible latency
4. **Structured context chunks** with explicit ordering makes the budget predictable
5. **Cache warming** for Anthropic saves cost on repeated interactions
6. **No tool API overhead** — text-based edit instructions avoid sending tool definitions

### From OpenCode:
1. **Two-phase compression** (prune tool outputs first, then summarize) is more efficient than pure summarization
2. **API token counts drive compaction** — more reliable than heuristic estimates
3. **Tool output truncation to disk** with hints to use grep/read is elegant
4. **Sub-agent delegation** isolates context — child sessions have fresh context windows
5. **Batch tool** reduces multi-turn round-trips significantly
6. **Doom loop detection** (3 identical calls) prevents runaway tool usage
7. **`filterCompacted`** cleanly slices history at compaction boundaries
8. **SQLite persistence** means sessions survive process restarts

### Critical for our Cody integration:
- Cody Enterprise has 150K input / 4K output limits
- With 150K input, Aider's approach would set history budget at ~8K tokens and repo map at ~4K
- OpenCode's approach would trigger compaction at ~130K tokens (150K - 20K buffer)
- We should implement TWO thresholds: soft (for pre-emptive summarization) and hard (for emergency compaction)
- Tool output pruning should be our first line of defense since Cody tool results can be large
- The chars/4 heuristic is sufficient for pruning decisions but not for budget calculations
