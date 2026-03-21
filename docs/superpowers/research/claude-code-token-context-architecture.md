# Claude Code Token & Context Management Architecture

Research date: 2026-03-21
Sources: Official Anthropic docs, Claude Code GitHub repo, community analysis

---

## 1. Token Display: What Does Claude Code Show?

### Two Types of Token Counts
Claude Code tracks **both cumulative and current** token usage:

- **Cumulative totals** (`total_input_tokens`, `total_output_tokens`): Sum of ALL tokens across the entire session. These can exceed the context window size since they represent total API consumption.
- **Current usage** (`current_usage`): Token counts from the **most recent API call only**. This reflects the actual context state.

### The `/cost` Command Shows:
```
Total cost:            $0.55
Total duration (API):  6m 19.7s
Total duration (wall): 6h 33m 10.2s
Total code changes:    0 lines added, 0 lines removed
```
This is cumulative session cost, not per-turn.

### The `/context` Command Shows:
Breaks down exactly how many tokens each component consumes in the current context window.

### Status Line Data (JSON piped to statusline script):
```json
{
  "context_window": {
    "total_input_tokens": 15234,      // cumulative across session
    "total_output_tokens": 4521,      // cumulative across session
    "context_window_size": 200000,    // max capacity
    "used_percentage": 8,             // current context fill %
    "remaining_percentage": 92,       // current context remaining %
    "current_usage": {
      "input_tokens": 8500,           // current API call input
      "output_tokens": 1200,          // current API call output
      "cache_creation_input_tokens": 5000,
      "cache_read_input_tokens": 2000
    }
  }
}
```

### Key Insight: `used_percentage` Calculation
Calculated from **input tokens only**: `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`. Does NOT include `output_tokens`.

---

## 2. Context Window Management

### The Fundamental Architecture: Stateless API + Full History

**The Claude API is stateless.** Claude Code sends the FULL conversation history with every API call. Each user message triggers a new API call that includes:
1. System prompt (CLAUDE.md, auto-memory, skills descriptions)
2. Tool definitions (all registered tools)
3. Complete conversation history (all user/assistant/tool messages)
4. The new user message

This means **context grows linearly** with each turn. Previous turns are preserved completely.

### Context Window Budget Breakdown (200K window)

Based on community analysis of actual sessions:
- **System prompt**: ~2.7K tokens (1.3%)
- **System tools (built-in)**: ~16.8K tokens (8.4%)
- **Custom/MCP tools**: Variable, can be 0-100K+
- **Memory files (CLAUDE.md etc)**: ~7.4K tokens (3.7%)
- **Skills descriptions**: ~1.0K tokens (0.5%)
- **Auto-compact buffer**: ~33K tokens (16.5%, hardcoded)
- **Usable context for conversation**: ~140-167K tokens

### Progressive Token Accumulation
Each turn consists of:
- **Input phase**: All previous conversation history + current user message
- **Output phase**: Generated response that becomes part of future input

This is why "longer sessions drain usage faster" - the conversation history gets reprocessed every turn.

### Context Awareness (Sonnet 4.5+, Sonnet 4.6, Haiku 4.5)
These models receive explicit token budget information:
```xml
<budget:token_budget>1000000</budget:token_budget>
```
After each tool call, they receive updates:
```xml
<system_warning>Token usage: 35000/1000000; 965000 remaining</system_warning>
```

---

## 3. Auto-Compaction: How It Works

### Server-Side Compaction API (Beta)
As of 2026, Anthropic offers server-side compaction via the Messages API:

**API Parameter:**
```json
{
  "context_management": {
    "edits": [
      {
        "type": "compact_20260112",
        "trigger": {"type": "input_tokens", "value": 150000},
        "pause_after_compaction": false,
        "instructions": null
      }
    ]
  }
}
```
Requires beta header: `compact-2026-01-12`

**Parameters:**
| Parameter | Default | Description |
|-----------|---------|-------------|
| `trigger` | 150,000 tokens | When to trigger. Must be >= 50,000 |
| `pause_after_compaction` | false | Pause to let client inject content |
| `instructions` | null | Custom summarization prompt (replaces default) |

### Compaction Process (4 Steps):
1. **Detect**: Input tokens exceed trigger threshold
2. **Summarize**: Generate summary of current conversation
3. **Create compaction block**: Summary wrapped in `<summary>` tags
4. **Continue**: Response proceeds with compacted context

### Default Summarization Prompt:
```
You have written a partial transcript for the initial task above. Please write
a summary of the transcript. The purpose of this summary is to provide continuity
so you can continue to make progress towards solving the task in a future context,
where the raw history above may not be accessible and will be replaced with this
summary. Write down anything that would be helpful, including the state, next steps,
learnings etc. You must wrap your summary in a <summary></summary> block.
```

### Claude Code's Internal Compaction Behavior:
- **Trigger**: When context reaches ~83.5% of window (after accounting for 16.5% buffer)
- **Buffer size**: ~33K tokens (reduced from 45K in early 2026)
- **Buffer purpose**: Working space for summarization + completion buffer + response generation
- **ENV override**: `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` (1-100) controls when compaction fires
- **Priority order**: Clears older tool outputs first, then summarizes conversation
- **Preserved**: User requests, key code snippets, recent context
- **Lost**: Detailed instructions from early in conversation, old tool results

### Historical Threshold Evolution:
- **Original**: ~95% capacity (or ~5% remaining)
- **Mid-2025**: Pushed down to ~90%
- **2026**: Further reduced to ~64-75% usage to preserve more working memory
- **Current**: ~83.5% (accounting for 16.5% buffer)

---

## 4. Tool Definition Token Overhead

### The Problem
MCP tool definitions are included in EVERY API call. Real-world overhead:
- **Built-in tools**: ~16.8K tokens (8.4% of 200K window)
- **GitHub MCP server (91 tools)**: ~46K tokens
- **Five-server setup (58 tools)**: ~55K tokens
- **Heavy setup (multiple servers)**: 100K+ tokens before conversation starts
- **Anthropic internal**: 134K tokens consumed by tool definitions alone

### Tool Search: The Solution
When MCP tool descriptions exceed **10% of context window** (i.e., 20K tokens for 200K window):
1. Tools are automatically **deferred** (marked `defer_loading: true`)
2. A **Tool Search tool** (~500 tokens) is injected instead
3. Claude discovers tools on-demand via keyword search (3-5 tools per query, ~3K tokens per search)
4. **Result**: 85% reduction in token overhead

### Configuring Tool Search:
- Auto threshold: `ENABLE_TOOL_SEARCH=auto:<N>` (e.g., `auto:5` = trigger at 5%)
- Default: `auto:10` (10% of context window)
- Deferred tools only enter context when actually used

### Tool Selection Accuracy Improvement:
Fewer tools in context = better tool selection:
- Opus 4: 49% -> 74%
- Opus 4.5: 79.5% -> 88.1%

---

## 5. Multi-Turn Behavior

### Each Turn = New API Call with Full History
1. User types message
2. Claude Code assembles: system prompt + tools + full history + new message
3. Sends to Claude API
4. Claude responds (may include tool_use blocks)
5. If tool_use: execute tool, append tool_result, send ANOTHER API call
6. Repeat steps 4-5 until Claude responds with text only (end_turn)
7. Response appended to conversation history

### Agentic Loop Within a Single User Turn:
A single user prompt can trigger MANY API calls:
- "Fix the failing tests" might cause 10+ sequential API calls
- Each call includes growing conversation (previous tool calls + results)
- Tool results are appended to history, growing context rapidly

### Session Continuity:
- Sessions saved as `.jsonl` files (complete conversation transcript)
- `--continue` restores full history
- `--resume` lets you pick a session
- `--fork-session` creates new session with history up to that point
- **Each session starts with fresh context** (no cross-session memory except CLAUDE.md/auto-memory)

### Extended Thinking Optimization:
- Thinking tokens are generated during output
- **Previous thinking blocks are automatically stripped** from future turns
- You don't pay for old thinking in context
- Exception: thinking blocks MUST be preserved during active tool_use cycles

---

## 6. API Response Token Tracking

### What the API Returns:
```json
{
  "usage": {
    "input_tokens": 8500,
    "output_tokens": 1200,
    "cache_creation_input_tokens": 5000,
    "cache_read_input_tokens": 2000
  }
}
```

### Long Context Pricing Trigger:
If `input_tokens + cache_creation_input_tokens + cache_read_input_tokens > 200,000`, the entire request is billed at the **long-context price** (higher rate).

### Prompt Caching:
Claude Code uses prompt caching automatically:
- System prompt and tool definitions get cached (same across turns)
- Cache read tokens are much cheaper than regular input tokens
- Reduces cost for repeated context (system prompt, tools, early conversation)

### Cost Tracking:
- Average: $6/developer/day
- 90th percentile: <$12/day
- Monthly average with Sonnet 4.6: ~$100-200/developer
- Background token usage (summarization, `/cost` checks): ~$0.04/session

---

## 7. Context Optimization Strategies Used by Claude Code

### 1. Prompt Caching
Repeated content (system prompt, tools, early turns) is cached. Subsequent API calls read from cache at reduced cost.

### 2. Tool Result Clearing
`clear_tool_uses_20250919` strategy: clears old tool results when context grows. Once Claude has processed a tool result deep in history, the raw result is redundant.

### 3. Thinking Block Stripping
Previous thinking blocks are automatically removed from context on subsequent turns.

### 4. Deferred Tool Loading
Tool Search replaces bulk tool definitions with on-demand discovery.

### 5. Subagent Isolation
Subagents get their own fresh context window. They return condensed summaries (1-2K tokens) instead of full exploration traces.

### 6. Skills as Lazy-Loaded Context
Skill descriptions load at session start, but full content only loads when invoked.

### 7. CLAUDE.md Instead of Conversation Instructions
Persistent rules go in CLAUDE.md (reloaded each turn from file) rather than early conversation messages (which get lost during compaction).

---

## 8. Key Takeaways for Our Plugin

### What Claude Code Gets Right:
1. **Tracks both cumulative AND current-turn tokens** separately
2. **Uses API response `usage` field** for actual token counts (not estimates)
3. **Aggressive tool definition management** - defers tools when they exceed 10% of context
4. **Server-side compaction** - offloads summarization to the API itself
5. **Layered context clearing** - tool results first, then full compaction
6. **Prompt caching** - system prompt + tools cached across turns
7. **Thinking block stripping** - automatic, no manual management needed
8. **Subagent architecture** - keeps main context clean

### What This Means for Our Agent Architecture:
1. **We MUST send full conversation history each turn** (API is stateless)
2. **Tool definitions are expensive** - we should implement lazy loading
3. **Compaction should be server-side** using `compact_20260112` API
4. **Track context fill percentage** from API response, not estimates
5. **Clear tool results** from old turns before full compaction
6. **Use prompt caching** by keeping system prompt/tools stable across turns
7. **Subagents for heavy operations** to avoid bloating main context
8. **The ~33K buffer** is real - don't try to fill context to 100%
