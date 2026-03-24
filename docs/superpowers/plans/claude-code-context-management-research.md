# Claude Code Context Management & Auto-Compaction Research

> Research completed 2026-03-22. Sources: official docs (code.claude.com), GitHub repo (anthropics/claude-code), CHANGELOG.md, environment variables reference.

---

## 1. Auto-Compaction: When and How It Triggers

### Trigger Threshold
- **Default: ~95% of context window capacity** (`CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` env var)
- Configurable via `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` (1-100). Setting to `50` compacts at 50% capacity.
- Values above the default threshold have no effect.
- Applies to **both main conversations and subagents**.

### Virtual Window Override
- `CLAUDE_CODE_AUTO_COMPACT_WINDOW` lets you set a virtual context capacity in tokens.
- Example: On a 1M model, set to `500000` to treat the window as 500K for compaction purposes.
- The value is capped at the model's actual context window.
- `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` is applied as a percentage of this virtual value.
- This decouples the compaction threshold from the status line's `used_percentage` (which always uses full window).

### When It Happens
- **Yes, it happens mid-loop** (during tool call iterations for a single message), not just between user messages.
- Auto-compaction triggers whenever the context approaches the threshold, regardless of where in the agentic loop Claude is.
- Manual compaction via `/compact` command or `/compact <focus instructions>`.
- "Summarize from here" via `/rewind` menu compresses only from a selected message forward.

### Circuit Breaker (v2.1.77)
- Auto-compaction has a **circuit breaker: stops retrying after 3 consecutive failures** to prevent infinite loops.

---

## 2. What Gets Compressed vs. Preserved

### Compression Strategy (from official docs)
1. **Clears older tool outputs first** (tool results from earlier in conversation)
2. **Then summarizes the conversation if needed**
3. **User requests and key code snippets are preserved**
4. **Detailed instructions from early in the conversation may be lost**

### System Prompt
- **System prompt is always preserved** (it's part of the API request structure, not conversation history).
- CLAUDE.md content is loaded as part of system/context and survives compaction.
- This is why the docs say: "Put persistent rules in CLAUDE.md rather than relying on conversation history."

### Recent Messages
- Recent messages are kept in full — the compaction targets **older** content.
- The boundary between summarized and fresh content is dynamic based on what's consuming space.

### What Survives Compaction
- System prompt (always)
- CLAUDE.md instructions (always — loaded fresh each request)
- Recent conversation messages (preserved in full)
- Key code patterns and file states
- Key decisions made during the session

### What Gets Compressed/Lost
- Older tool results (file reads, command outputs) — cleared first
- Detailed instructions from early in conversation
- Verbose debugging output
- Exploration results from codebase searches

### Customizable Preservation
- Add a **"Compact Instructions"** section to CLAUDE.md:
  ```markdown
  # Compact instructions
  When you are using compact, please focus on test output and code changes
  ```
- Or run `/compact Focus on the API changes` to guide what's preserved.
- When using "Summarize from here" via `/rewind`, you can type optional instructions to guide what the summary focuses on.

---

## 3. Compression Approach

### LLM-Powered Summarization
- **Yes, uses LLM-powered summarization.** The compaction is done by Claude itself.
- The summary is an AI-generated compact representation of the conversation.
- The `PostCompact` hook receives a `compact_summary` field containing the generated summary.

### Summary Format
- Not publicly documented in detail, but from the PostCompact hook:
  ```json
  {
    "compact_summary": "Summary of the compacted conversation..."
  }
  ```
- The summary replaces the compacted messages in the conversation.
- For "Summarize from here": messages before the selected point stay intact; selected message and all subsequent messages get replaced with the summary.

### Fallback on Failure
- **Circuit breaker after 3 consecutive failures** (added v2.1.77).
- If compaction fails 3 times, it stops retrying to prevent infinite loops.
- Progress messages survive compaction correctly (fixed in v2.1.77).

### Is Claude Told Compression Happened?
- The compacted conversation includes the summary in place of the original messages.
- Claude sees the summary as part of its conversation history, so it implicitly knows the context was compressed.
- The transcript file records a `compact_boundary` event:
  ```json
  {
    "type": "system",
    "subtype": "compact_boundary",
    "compactMetadata": {
      "trigger": "auto",
      "preTokens": 167189
    }
  }
  ```

---

## 4. Token Management

### Context Window Sizes
| Model | Standard | Extended |
|-------|----------|----------|
| Opus 4.6 | 200K tokens | **1M tokens** |
| Sonnet 4.6 | 200K tokens | **1M tokens** |
| Haiku | 200K tokens | N/A |

### Extended Context Availability
- **Max, Team, Enterprise**: Opus 1M included automatically, Sonnet 1M requires extra usage.
- **Pro**: Both require extra usage.
- **API/pay-as-you-go**: Full access to both.
- Disable with `CLAUDE_CODE_DISABLE_1M_CONTEXT=1`.
- Use `[1m]` suffix: `/model opus[1m]` or `/model sonnet[1m]`.

### Compaction Threshold Calculation
- `used_percentage` = (`input_tokens` + `cache_creation_input_tokens` + `cache_read_input_tokens`) / `context_window_size`
- Does **not** include output tokens.
- Default compaction at ~95% of this.

### Prompt Caching
- Automatically uses **prompt caching** to reduce costs for repeated content (system prompts, CLAUDE.md).
- Configurable per-model: `DISABLE_PROMPT_CACHING`, `DISABLE_PROMPT_CACHING_OPUS`, etc.
- Cache tokens tracked: `cache_creation_input_tokens` and `cache_read_input_tokens`.

### Max Output Tokens
- `CLAUDE_CODE_MAX_OUTPUT_TOKENS` — increasing this **reduces the effective context window** available before auto-compaction triggers.

---

## 5. Tool Result Handling During Iterations

### Truncation Limits
| Tool | Limit Variable | Default | Behavior |
|------|---------------|---------|----------|
| **Bash** | `BASH_MAX_OUTPUT_LENGTH` | Not specified | Middle-truncated (keeps start and end, removes middle) |
| **File Read** | `CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS` | Not specified | Token-limited output |
| **MCP tools** | `MAX_MCP_OUTPUT_TOKENS` | 25,000 tokens | Warning displayed when exceeding 10,000 tokens |

### Key Design: Middle Truncation for Bash
- Bash output is **middle-truncated**: keeps the beginning and end, removes the middle.
- This preserves the command start (headers, setup) and end (results, exit codes).

### Recovery of Truncated Content
- Claude can re-read files or re-run commands if it needs content that was truncated.
- The agent is told when output was truncated.
- For file reads, Claude can use offset/limit parameters to read specific sections.

### During Compaction
- Older tool results are **cleared first** before conversation summarization.
- This is the first line of defense against context overflow.

---

## 6. Key Design Decisions & Architecture

### Why This Approach

1. **System prompt always preserved**: CLAUDE.md is loaded fresh each API call, never subject to compaction. This ensures persistent instructions survive any amount of context churn.

2. **Tool outputs cleared first**: These are typically the largest context consumers (file contents, command output) and are reproducible — Claude can re-read files.

3. **LLM self-summarization**: Claude summarizes its own conversation, which means it can identify what's semantically important vs. what's noise.

4. **Configurable focus**: The `/compact <instructions>` and CLAUDE.md "Compact Instructions" features let users control what survives, acknowledging that the system can't always know what matters most.

5. **Subagent isolation**: Subagents get their **own fresh context window**, completely separate from the main conversation. Only a summary returns. This is the primary architectural defense against context bloat in long sessions.

6. **Progressive degradation**: The system degrades gracefully:
   - First: clear old tool outputs
   - Then: summarize conversation
   - Fallback: circuit breaker after 3 failures
   - User option: `/clear` for complete reset

### Context Cost Awareness
- `/context` command shows what's consuming space with actionable suggestions.
- Status line can display `used_percentage` continuously.
- MCP servers add tool definitions to **every request**, so idle servers consume context.
- Skill descriptions consume context at session start; full content loads on-demand.

### Session Architecture
- Each session is independent with fresh context.
- Auto-memory (`MEMORY.md`) persists learnings across sessions (first 200 lines loaded).
- No conversation history from previous sessions is loaded.
- Session transcripts stored as `.jsonl` files.

---

## 7. Practical Implications for Our Plugin

### What We Can Learn
1. **95% threshold is aggressive** — most context is used before compaction kicks in. For our agent, we might want earlier compaction.
2. **Clear tool outputs first** — this is smart because tool results are reproducible (re-read files, re-run commands).
3. **CLAUDE.md as persistent context** — equivalent to our system prompt. Always survives compaction.
4. **Subagent pattern for isolation** — each subagent gets fresh context, returns only summary. This is exactly what we need for our agent's tool calls.
5. **Middle truncation** — preserving start and end of outputs is better than head-only truncation.
6. **Circuit breaker** — essential to prevent infinite compaction loops.
7. **User-controllable focus** — letting users specify what to preserve during compaction is a power-user feature we should consider.

### Key Env Vars Reference
| Variable | Purpose |
|----------|---------|
| `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` | Compaction trigger threshold (1-100, default ~95) |
| `CLAUDE_CODE_AUTO_COMPACT_WINDOW` | Virtual context window size for compaction calc |
| `CLAUDE_CODE_DISABLE_1M_CONTEXT` | Disable 1M context window |
| `BASH_MAX_OUTPUT_LENGTH` | Max chars in bash output before middle-truncation |
| `CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS` | Max tokens for file reads |
| `MAX_MCP_OUTPUT_TOKENS` | Max tokens for MCP tool responses (default 25K) |
| `CLAUDE_CODE_MAX_OUTPUT_TOKENS` | Max output tokens per request |
| `MAX_THINKING_TOKENS` | Extended thinking budget |
| `DISABLE_PROMPT_CACHING` | Disable prompt caching globally |
| `SLASH_COMMAND_TOOL_CHAR_BUDGET` | Skill metadata budget (2% of context window, fallback 16K chars) |

---

## 8. Hooks for Observability

### PreCompact Hook
- Fires before compaction
- Matcher: `manual` or `auto`
- Input includes `trigger` and `custom_instructions`
- **No decision control** — cannot block compaction

### PostCompact Hook
- Fires after compaction
- Matcher: `manual` or `auto`
- Input includes `trigger` and `compact_summary` (the generated summary)
- **No decision control** — observability only

### Token Estimation Fix (v2.1.75)
- Fixed token estimation over-counting for thinking and `tool_use` blocks.
- This was causing **premature context compaction**.

---

## Sources
- https://code.claude.com/docs/en/how-claude-code-works
- https://code.claude.com/docs/en/costs
- https://code.claude.com/docs/en/best-practices
- https://code.claude.com/docs/en/checkpointing
- https://code.claude.com/docs/en/model-config
- https://code.claude.com/docs/en/env-vars
- https://code.claude.com/docs/en/hooks
- https://code.claude.com/docs/en/sub-agents
- https://code.claude.com/docs/en/statusline
- https://github.com/anthropics/claude-code (CHANGELOG.md)
