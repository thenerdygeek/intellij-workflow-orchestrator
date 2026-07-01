# Context Window Management: 7 Open-Source Agentic AI Tools Compared

Research date: 2026-03-27

## 1. Cline (VS Code Extension)

**Source:** `src/core/prompts/contextManagement.ts`, `src/core/context/context-management/ContextManager.ts`

### Budget Thresholds
- Single threshold: `maxAllowedSize = Math.max(contextWindow - 40_000, contextWindow * 0.8)`
- No multi-tier budget system (no OK/COMPRESS/NUDGE/TERMINATE levels)
- Performance degradation noted at 70-80% capacity

### Compression Strategy
- **LLM-powered summarization** via `summarizeTask()` — generates structured summary with 10 sections (Primary Request, Key Technical Concepts, Files and Code, Problem Solving, Pending Tasks, Task Evolution, Current Work, Next Step, Required Files, Focus Chain Status)
- **Duplicate detection** — replaces redundant `read_file` content with `[DUPLICATE FILE READ]` markers
- **Sliding window truncation** — removes 50% of conversation history (75% when switching to smaller model), preserving first user-assistant exchange and removing middle pairs

### Context Rotation / Session Handoff
- Yes: `new_task` tool creates structured continuation with `.clinerules` configurable thresholds (e.g., "exceed 50%")
- `continuationPrompt()` resumes from summary without clarifying questions
- `/compact`, `/newtask`, `/smol` commands for manual control

### When Context is Full
- Three fallback modes: auto-compact (if enabled), error prompting new task start, or truncate older messages with warning
- Graceful degradation, not hard fail

---

## 2. Aider

**Source:** `aider/history.py` (ChatSummary class), `aider/models.py`

### Budget Thresholds
- Single soft threshold: `max_chat_history_tokens` (model-dependent defaults: 1024 tokens if max_input < 32K, 2048 tokens otherwise)
- Separate budget for repo map: `--map-tokens` (default 1K, dynamically expanded)
- No multi-tier system

### Compression Strategy
- **Recursive LLM summarization using a "weak model"** — e.g., GPT-4o-mini summarizes while coding with Claude Opus
- `ChatSummary.summarize_real()` implements recursive chunking:
  1. If messages < token limit at depth 0, return unchanged
  2. If messages <= 4 items or depth > 3, return as-is (recursion guard)
  3. Split messages backward, keeping ~half token budget for tail
  4. Ensure split points end at assistant messages for coherence
  5. Head limited to model's max_input minus 512-byte safety buffer
  6. Recurse if summary + tail still exceeds limits
- Falls back through multiple models if primary summarizer fails
- Background thread for summarization

### Context Rotation / Session Handoff
- No explicit session handoff mechanism
- Full chat history kept on disk; in-context version is summarized
- Context partitioned into regions: system prompt, repo map (graph-ranked), chat history, active files

### When Context is Full
- Graceful: recursive summarization compresses progressively
- No hard fail; recursion guard at depth > 3

---

## 3. OpenHands (OpenDevin)

**Source:** `openhands-sdk/openhands/sdk/context/condenser/llm_summarizing_condenser.py`

### Budget Thresholds
- Single threshold: `max_size` parameter (default 120 events, event-count based not token-based)
- Target after condensation: `max_size // 2` (~60 events)
- `keep_first` parameter (default 4) preserves initial events

### Compression Strategy — Most Sophisticated
- **Pipeline architecture** with composable condensers:
  - `NoOpCondenser` — pass-through
  - `LLMSummarizingCondenser` — LLM-powered, uses cheaper model (`usage_id: "condenser"`)
  - `PipelineCondenser` — chains multiple condensers sequentially
  - `RollingCondenser` — threshold-based triggering base class
- Condensation process:
  1. Divide history into head (first N events), middle (to compress), tail (recent)
  2. LLM generates compressed representation of middle events
  3. `Condensation` event created with `forgotten_event_ids` + summary
  4. `View.from_events()` filters forgotten events, inserts summary
- Results: up to 2x reduction in per-turn API costs, linear cost scaling vs quadratic baseline

### Context Rotation / Session Handoff
- No explicit session handoff
- `CondensationRequest` event can be manually triggered when encountering context window errors
- Known bug: can enter infinite condensation loops if context consistently overflows after condensation

### When Context is Full
- Automatic condensation triggered during each agent step
- Can fail into infinite loops (documented bug #8630)

---

## 4. SWE-agent

**Source:** `sweagent/agent/history_processors.py`

### Budget Thresholds
- No token-based thresholds — uses **event-count and pattern-based** processing
- `LastNObservations` keeps last N observations (configurable)
- `polling` parameter staggers removals for cache efficiency

### Compression Strategy — Heuristic, No LLM
- **Multiple composable history processors** (no LLM summarization):
  - `LastNObservations` — keeps last N observations, replaces older with "Old environment output: (X lines omitted)". Never removes first observation (instance template)
  - `ClosedWindowHistoryProcessor` — tracks file windows, replaces outdated ones with "Outdated window with N lines omitted..."
  - `TagToolCallObservations` — tags specific tool calls for selective preservation via `always_keep_output_for_tags` / `always_remove_output_for_tags`
  - `RemoveRegex` — strips content matching regex patterns, `keep_last` exempts recent entries
  - `CacheControlHistoryProcessor` — adds Claude prompt cache markers to last N messages
  - `ImageParsingHistoryProcessor` — converts base64 images to multimodal format
- Original SWE-agent paper: observations before last 5 collapsed to single line; past error messages except first omitted

### Context Rotation / Session Handoff
- No session handoff mechanism
- Designed for single-shot issue resolution, not long-running sessions

### When Context is Full
- Hard truncation via history processors
- No graceful degradation beyond what processors provide

---

## 5. Continue.dev

**Source:** `core/llm/index.ts`

### Budget Thresholds
- Single threshold: `contextLength` config parameter (model-specific)
- `maxTokens` defaults to 1/4 of context length
- No multi-tier system; no user-visible context warnings (feature request closed as "not planned")

### Compression Strategy — Simple Truncation Only
- **`pruneRawPromptFromTop()`** — removes tokens from beginning of prompts when exceeding context
- **`compileChatMessages()`** — compiles and trims message history to fit constraints
- No LLM-powered summarization
- No sliding window with overlap
- Token counting via `countTokens(text, model)` utility
- Known issues: token counting inaccurate for Anthropic models (uses OpenAI formula)

### Context Rotation / Session Handoff
- No explicit session handoff
- No compaction command

### When Context is Full
- Prunes from top (oldest messages removed first)
- Graceful degradation but with information loss (no summarization)

---

## 6. Codex CLI (OpenAI)

**Source:** `codex-rs/core/` (Rust), with API via `/responses/compact` endpoint

### Budget Thresholds
- Token-based threshold: `model_auto_compact_token_limit` (~220K tokens hardcoded for current models)
- 95% `effective_context_window_percent` safety margin
- Default limits vary by model: 180K-244K tokens
- No multi-tier system (community requesting: off/on/manual/smart-auto modes)

### Compression Strategy — Unique Encrypted Compaction
- **Server-side compaction** via OpenAI API:
  1. When token count crosses `compact_threshold`, server runs compaction
  2. Returns `type: "contextCompaction"` item with `encrypted_content` (opaque, not human-readable)
  3. Encrypted blob carries forward "key prior state and reasoning using fewer tokens"
  4. Client appends compaction item to next request
- **Manual `/compact` command** also available
- Compaction rebuilds: initial context + recent user messages (~20K tokens) + summary
- Retry logic with exponential backoff

### Context Rotation / Session Handoff
- `thread/rollback` API can drop last N turns
- GPT-5.1-Codex-Max: "first model natively trained to operate across multiple context windows through compaction"
- Compacted context preserved across session resumes

### When Context is Full
- Auto-compaction triggered; session continues with compressed context
- Known bugs: compaction can overwrite concurrent history writes and loop indefinitely (#13946)
- Warns users that "multiple compactions can cause the model to be less accurate"

---

## 7. Goose (Block)

**Source:** `crates/goose/src/agents/truncate.rs`, `crates/goose/src/agents/agent.rs` (Rust)

### Budget Thresholds — Most Configurable
- Auto-compact threshold: **80% of context window** (default), configurable via `GOOSE_AUTO_COMPACT_THRESHOLD`
- Can be disabled by setting threshold to `0.0`
- Role-specific limits: `GOOSE_LEAD_CONTEXT_LIMIT`, `GOOSE_WORKER_CONTEXT_LIMIT`, `GOOSE_PLANNER_CONTEXT_LIMIT`
- Max turns: `GOOSE_MAX_TURNS` (default 1000)
- Tool call optimization cutoff: `GOOSE_TOOL_CALL_CUTOFF` (default 10 calls)

### Compression Strategy — Two-Tiered
- **Tier 1: Auto-compaction** — LLM summarizes older conversation parts at 80% threshold
- **Tier 2: Context strategies** (backup if still over limit after compaction):
  - `summarize` — condenses while preserving key points (Desktop + CLI)
  - `truncate` — removes oldest messages (CLI only)
  - `clear` — starts fresh while keeping session active (CLI only)
  - `prompt` — asks user to choose (CLI interactive default)
- **Tool call optimization** — summarizes older tool outputs in background while keeping recent calls full detail (>10 calls)
- Manual: `/summarize` CLI command or "Compact now" button in Desktop
- Config: `GOOSE_CONTEXT_STRATEGY` environment variable

### Context Rotation / Session Handoff
- No explicit session handoff
- `.goosehints` files persist project context across sessions
- Memory extension stores cross-session information
- Subagents execute in isolated contexts

### When Context is Full
- Two-tier graceful degradation: auto-compact first, then context strategy fallback
- Visual indicators: green (normal), orange (warning at 80%), red (exceeded)
- Headless mode (`goose run`) auto-summarizes; interactive mode prompts user

---

## Comparative Summary Table

| Feature | Cline | Aider | OpenHands | SWE-agent | Continue | Codex CLI | Goose |
|---------|-------|-------|-----------|-----------|----------|-----------|-------|
| **Multi-tier budgets** | No (single) | No (single) | No (single) | No | No | No | Closest (2-tier) |
| **LLM compression** | Yes | Yes (weak model) | Yes (cheap model) | No | No | Yes (server-side) | Yes |
| **Simple truncation** | Yes (fallback) | No | No | Yes (primary) | Yes (primary) | No | Yes (fallback) |
| **Threshold type** | Token % | Token count | Event count | Event count | Token count | Token count | Token % |
| **Default threshold** | 80% | 1-2K tokens | 120 events | Last 5 obs | contextLength | ~220K tokens | 80% |
| **Agent warned** | Via progress bar | No | Via CondensationRequest | No | No | Via item/started | Via color indicator |
| **Session handoff** | Yes (new_task) | No | No | No | No | Yes (multi-window) | No |
| **Pipeline/composable** | No | No | Yes | Yes | No | No | Yes (2-tier) |
| **Failure mode** | Graceful | Graceful | Can loop | Hard truncate | Graceful prune | Can loop | Graceful 2-tier |
| **Configurable** | Limited | Yes (flags) | Yes (params) | Yes (config) | Basic | Limited (hardcoded) | Most (env vars) |

## Key Architectural Insights for Our Plugin

1. **No tool implements multi-tier budgets (OK/COMPRESS/NUDGE/TERMINATE)** — this is an opportunity for differentiation
2. **Two compression camps**: LLM-powered (Cline, Aider, OpenHands, Codex, Goose) vs. heuristic/truncation (SWE-agent, Continue)
3. **Weak/cheap model for summarization** is the winning pattern (Aider, OpenHands) — avoids consuming expensive main model tokens
4. **Composable pipeline** (OpenHands, SWE-agent) is more flexible than monolithic compaction
5. **Session handoff** is rare (only Cline and Codex) — most tools just compress in place
6. **Tool output optimization** (Goose's tool call cutoff, SWE-agent's observation collapsing) is a separate concern from conversation summarization
7. **Event-count thresholds** (OpenHands, SWE-agent) are simpler but less precise than token-count thresholds
8. **Infinite loop risk** with auto-condensation is a real bug (OpenHands #8630, Codex #13946)
9. **Encrypted compaction** (Codex) is unique but non-portable — tied to OpenAI's API
10. **Goose's environment-variable-driven config** is the most user-friendly for threshold tuning
