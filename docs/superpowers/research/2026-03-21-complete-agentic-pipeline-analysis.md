# Complete Agentic Pipeline Analysis — What We're Doing Wrong

Research date: 2026-03-21
Sources: Claude Code official docs + internal analysis, Aider source code, OpenCode source code, trace data from user testing

---

## The 3 User-Reported Issues — Root Causes

### Issue 1: Token display showing 369.9K / 190K
**Root cause:** We display `totalTokensUsed` (cumulative sum of ALL API calls' prompt+completion tokens across all iterations) against `maxInputTokens` (per-call context window limit). These are completely different metrics. The actual context window per call was only ~22K, not 369K.

### Issue 2: ReadAction threading error
**Root cause:** `DynamicToolSelector.detectProjectTools()` calls `JavaPsiFacade.findClass()` without `ReadAction` wrapper. Called from coroutine on `Dispatchers.IO`.

### Issue 3: Multi-turn broken + agent spirals to 17 iterations
**Root causes (multiple):**
- Tool set changes per message (24→62→24→30) but `reservedTokens` is fixed from session creation
- `reconcileWithActualTokens(promptTokens - reservedTokens)` produces wrong values with stale `reservedTokens`
- Old tool results never pruned — context fills with stale data
- No graceful degradation at high iteration counts (no "wrap up" nudge)
- No doom loop detection (3 identical tool calls should trigger a break)

---

## How Production Tools Compare to Our Architecture

### Agent Loop

| Aspect | Claude Code | Aider | OpenCode | Our Agent |
|--------|-------------|-------|----------|-----------|
| Loop type | while(tool_calls), no hard limit | Text parsing + max 3 reflections | AI SDK inner loop + custom outer loop | while(iterations < 50) |
| Termination | stop_reason=end_turn OR context full | 3 reflections max | stop_reason + maxSteps + overflow | Max 50 iterations OR budget |
| Graceful degradation | Model receives `<system_warning>Token usage: X/Y remaining</system_warning>` | Hard stop at 3 | "CRITICAL — MAX STEPS REACHED. Tools disabled. Summarize." | NUDGE/STRONG_NUDGE messages but no tool disabling |
| Doom loop detection | Unknown | N/A (no tool calls) | 3 identical calls → user approval | LoopGuard exists but unclear effectiveness |

### Context Management

| Aspect | Claude Code | Aider | OpenCode | Our Agent |
|--------|-------------|-------|----------|-----------|
| What's sent per API call | Full history + system + tools | Chunks: system+examples+files+summarized_history+current | Full history since last compaction | Full history (messages list) |
| System prompt | ~40K tokens, prompt-cached | Dynamic, small (~2-4K) | Dynamic per agent | ~2-4K, re-sent every call |
| Tool definitions | ~17K, prompt-cached + Tool Search for MCP | **None** (text-based edits) | All agent tools | 24-62 tools, NOT cached, changes per message |
| Prompt caching | Yes — 80% cost reduction | Yes (Anthropic cache breakpoints) | Unknown | **Not available** (Sourcegraph API) |
| Context buffer | 33K reserved (16.5%) | N/A | 20K reserved | Our T_max at 70% = 30% buffer |

### Compression

| Aspect | Claude Code | Aider | OpenCode | Our Agent |
|--------|-------------|-------|----------|-----------|
| Trigger | ~83.5% fill (167K/200K) | After each edit cycle | input_limit - 20K | 70% of effective budget |
| Phase 1 | Prune old tool outputs | N/A | Prune beyond 40K protected | **Missing** |
| Phase 2 | LLM summarization | Cheap model in background thread | Structured LLM summary | LLM summarization |
| History budget | Unbounded until compaction | **8K tokens max** (1/16th of context) | Unbounded until compaction | Unbounded until compression |
| On overflow | Compact + continue | Warn user, stop | Compact + **replay failed request** | **Fail immediately** |

### Token Tracking

| Aspect | Claude Code | Aider | OpenCode | Our Agent |
|--------|-------------|-------|----------|-----------|
| Source of truth | API `usage.input_tokens` | API `completion.usage`, fallback litellm | API response only | Heuristic + reconciliation with stale reservation |
| What UI shows | Context % (current fill) | Per-message cost USD | Cost USD | **Cumulative API total vs per-call limit** ❌ |
| Context awareness | Model receives budget tags | No model awareness | No model awareness | Efficiency rules in prompt (not per-iteration) |

### Tool Results

| Aspect | Claude Code | Aider | OpenCode | Our Agent |
|--------|-------------|-------|----------|-----------|
| Per-result cap | ~25K tokens (file read) | N/A | 2000 lines / 50KB | 500 token compressed |
| Old result pruning | Cleared on compaction | N/A | Beyond 40K, replaced with "[cleared]" | **Never pruned** ❌ |
| Full output saved | Unknown | N/A | Yes — to disk, agent told path | No |

---

## Priority Fixes — Ordered by Impact

### Tier 1: Critical (Must fix before next release)

**1. Token Display Fix** — Show context window fill, not cumulative API total
- File: `SingleAgentSession.kt:124,326`, `AgentController.kt:608`
- Fix: Pass `contextManager.totalTokens` to progress callback

**2. ReadAction Fix** — Wrap PSI calls in ReadAction
- File: `DynamicToolSelector.kt:189-200`
- Fix: `ReadAction.compute<Set<String>, Exception> { ... }`

**3. Token Reconciliation Fix** — Use API promptTokens directly
- File: `ContextManager.kt:301`
- Fix: `totalTokens = actualPromptTokens` (API already includes tool defs)

### Tier 2: High (Fix agent effectiveness)

**4. Tool Result Pruning** — Clear old tool results before compression
- Add Phase 1 to compression: replace tool results older than N iterations with summaries
- Protect last 3 iterations (like OpenCode's 40K window)

**5. Stabilize Tool Set Per Session** — Don't re-select tools per message
- Keep the tool set from session creation, only ADD new tools when keywords match
- Recalculate `reservedTokens` when tools are added

**6. Graceful Degradation at High Iterations** — "Wrap up" mechanism
- At 80% of max iterations (iteration 40/50): inject "You're running low on iterations. Wrap up your current work and provide a summary."
- At max iterations: inject "CRITICAL — MAX ITERATIONS. Tools disabled. Summarize what you've done." (like OpenCode)

**7. Context Overflow Retry** — Compress and replay instead of failing
- When API returns context_exceeded: compress, then retry the same request
- Like OpenCode's approach

### Tier 3: Medium (Polish and efficiency)

**8. Context Budget Awareness** — Tell the model its budget
- Inject `<system_warning>Context: X/Y tokens used, Z remaining</system_warning>` in the system message per iteration
- Like Claude Code's approach — the model self-regulates

**9. Doom Loop Detection** — 3 identical tool calls = break
- Simple: track last 3 tool calls, if name+args match → inject "You've called the same tool 3 times. Try a different approach."
- Like OpenCode's approach

**10. Improve Compression Thresholds**
- Increase from 70% to ~80% (keep 20% buffer instead of 30%)
- Add Phase 1: prune old tool outputs before LLM summarization
- Consider background pre-emptive summarization (like Aider)

---

## Architecture Decisions to Make

### Should we switch to text-based edits (like Aider)?
**No.** Tool calling gives us precise file operations, undo support, and approval gates. Text parsing is fragile. But we should learn from Aider's aggressive history summarization.

### Should we use server-side compaction API?
**Not yet.** Sourcegraph's API is OpenAI-compatible, not Anthropic-native. The server-side compaction API (`compact-2026-01-12`) is Anthropic-specific. We'd need to implement client-side compaction (which we already do).

### Should we implement prompt caching?
**Can't.** Sourcegraph's proxy doesn't expose Anthropic's cache breakpoint headers. This is a Sourcegraph limitation. Our tool definitions (~4-12K tokens) are re-sent fully on every call. This is a cost we have to accept.

### Should we implement Tool Search (lazy tool loading)?
**Yes, eventually.** When we have 60+ tools, sending all definitions wastes ~12K tokens per call. A Tool Search mechanism (like Claude Code's) would send only a lightweight search tool, then load specific tools on demand. This is a bigger refactor for later.
