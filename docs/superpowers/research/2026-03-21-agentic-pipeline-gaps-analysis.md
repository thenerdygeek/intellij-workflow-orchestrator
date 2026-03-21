# Agentic Pipeline Gaps Analysis — What We're Doing Wrong

Research date: 2026-03-21
Sources: Claude Code docs, Aider source code, OpenCode source code, trace data analysis

## Executive Summary

Our agent has **5 critical architectural gaps** compared to production tools (Claude Code, Aider, OpenCode). These explain the user's 3 reported issues (inflated tokens, ReadAction error, broken multi-turn). The gaps are not just bugs — they're fundamental patterns we're missing.

---

## Gap 1: Token Display Shows Wrong Metric (CRITICAL)

### What we do:
`SingleAgentSession.totalTokensUsed` sums `usage.totalTokens` (prompt + completion) across ALL API calls. UI shows this cumulative sum vs 190K limit.

### What production tools do:
- **Claude Code**: Shows `used_percentage` from the CURRENT context size (input tokens only). `/cost` shows cumulative cost separately.
- **Aider**: Shows per-message cost in USD and cumulative session cost. Never shows raw token count vs limit.
- **OpenCode**: Tracks cost in USD via `Decimal.js`. Context usage is internal, not displayed as raw tokens.

### The fix:
Display `contextManager.totalTokens` (current context window fill) for the budget widget. Track cumulative API tokens separately for cost/telemetry.

---

## Gap 2: Tool Definition Token Overhead Not Managed (HIGH)

### What we do:
Send ALL selected tools (24-62) on every API call. Tool definitions are ~4-12K tokens. `reservedTokens` calculated once at session creation, never updated.

### What production tools do:
- **Claude Code**: When MCP tools exceed **10% of context**, auto-defers them and uses a lightweight "Tool Search" tool (~500 tokens). Tools discovered on-demand. **85% reduction** in overhead.
- **Aider**: Does NOT use tool/function calling API at all. Edits returned as structured text. **Zero tool definition overhead**.
- **OpenCode**: Uses standard tool calling but has batch tool (25 parallel), sub-agent delegation for heavy work, and tool output truncation.

### The fix:
1. Stabilize tool set within a session (don't re-select per message, only add new ones)
2. Recalculate `reservedTokens` when tool set changes
3. Consider lazy tool loading for 60+ tool sets (only send tools relevant to current turn)

---

## Gap 3: Context Compression Strategy Is Wrong (HIGH)

### What we do:
- Two-threshold: T_max at 70%, T_retained at 40%
- Compression triggers when context exceeds 70% of budget
- LLM-powered summarization or truncation
- No distinction between tool results and conversation messages

### What production tools do:
- **Claude Code**:
  - Buffer: ~33K tokens (16.5% of 200K) — never fill beyond ~83%
  - **TWO-PHASE**: Clear old tool outputs FIRST, then full conversation summarization
  - Server-side compaction API available (`compact-2026-01-12`)
  - Threshold evolved from 95% → 64-75% (keeping more working memory)

- **Aider**:
  - Pre-emptive summarization in BACKGROUND THREAD before context is full
  - Threshold: `max_input_tokens / 16` (e.g., 12K for 190K window)
  - Uses cheap/weak model for summarization
  - Recursive split-and-summarize (head/tail at 50%, depth 3)

- **OpenCode**:
  - Phase 1: PRUNE OLD TOOL OUTPUTS (replace with "[cleared]" beyond 40K protected tokens)
  - Phase 2: Full LLM summarization with structured template
  - Triggered at `input_limit - 20K buffer`
  - On `ContextOverflowError`: compact then REPLAY the failed request

### The fix:
1. **Add tool output pruning as Phase 1** — clear old tool results BEFORE summarizing conversation
2. **Increase buffer** — never fill past 80%, not 70%
3. **Background pre-emptive summarization** — start summarizing early, don't wait for threshold
4. **On API context overflow**: compress and RETRY the request (we currently fail)

---

## Gap 4: No Token Reconciliation from API Response (CRITICAL)

### What we do:
`reconcileWithActualTokens(promptTokens - reservedTokens)` — but `reservedTokens` is stale when tool set changes. The formula produces wrong values.

### What production tools do:
- **Claude Code**: Uses API `usage.input_tokens` directly. Prompt caching means `cache_creation_input_tokens + cache_read_input_tokens + input_tokens` gives the real count.
- **Aider**: Prefers `completion.usage` from API, falls back to `litellm.token_counter()` with proper tokenizers.
- **OpenCode**: API response only. Never relies on client-side heuristics for budget decisions.

### The fix:
Use `promptTokens` from the API response AS the authoritative context size. Don't subtract `reservedTokens` — the API already includes tool definitions in `promptTokens`. Our `reconcileWithActualTokens` should simply be:
```kotlin
totalTokens = actualPromptTokens  // The API knows the real count
```

---

## Gap 5: No Tool Result Pruning (MEDIUM)

### What we do:
Tool results are compressed via `ToolResultCompressor.compress()` with a 500 token cap per result. But OLD results from previous iterations are never pruned — they stay in the conversation forever.

### What production tools do:
- **Claude Code**: Clears old tool outputs as Phase 1 of compaction
- **OpenCode**: Replaces tool results beyond 40K protected tokens with `[Old tool result content cleared]`
- **Aider**: Doesn't use tool calling, so no tool result bloat

### The fix:
Before each API call, prune tool results from iterations older than N (e.g., keep only last 3 iterations' results). Replace pruned results with a one-line summary.

---

## Gap 6: ReadAction Threading (BUG, not architectural)

`DynamicToolSelector.detectProjectTools()` calls PSI without `ReadAction`. Simple fix.

---

## Comparison Matrix

| Aspect | Our Agent | Claude Code | Aider | OpenCode |
|--------|-----------|-------------|-------|----------|
| Token display | Cumulative API total ❌ | Current context % ✓ | Per-msg cost USD ✓ | Cost USD ✓ |
| Tool definitions | 24-62 tools, all sent ❌ | Lazy "Tool Search" when >10% ✓ | No tool calling ✓ | Standard + batch ✓ |
| Compression trigger | 70% of budget | ~75-83% with 33K buffer | Pre-emptive at 1/16th | On overflow with 20K buffer |
| Compression strategy | LLM summarize all | Phase 1: prune tool results, Phase 2: summarize | Background thread, cheap model | Phase 1: prune, Phase 2: structured LLM |
| Token reconciliation | Heuristic with stale reservation ❌ | API response authoritative ✓ | API response + fallback ✓ | API response only ✓ |
| Tool result pruning | Never pruned ❌ | Cleared on compaction ✓ | N/A (no tool results) | Cleared beyond 40K ✓ |
| Context overflow retry | Fail immediately ❌ | Compact + retry ✓ | Diagnose + warn ✓ | Compact + replay ✓ |
| Multi-turn stability | Tool set changes per msg ❌ | Stable within session ✓ | Stable ✓ | Stable ✓ |

---

## Priority Fix Order

1. **Token display** — show `contextManager.totalTokens` not cumulative (1 line fix)
2. **ReadAction** — wrap PSI in `ReadAction.compute{}` (1 line fix)
3. **Token reconciliation** — use `promptTokens` directly, don't subtract stale reservation
4. **Tool result pruning** — add Phase 1 pruning before compression
5. **Stabilize tool set** — don't re-select tools per message, only expand
6. **Context overflow retry** — compress and retry on API error instead of failing
7. **Increase compression buffer** — use 80% not 70% as threshold
