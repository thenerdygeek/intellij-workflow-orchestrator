# Context Management, Retention & Compression in Enterprise Agentic AI Tools

**Research Date:** 2026-03-31
**Method:** Source-code analysis of 13 cloned repositories + web research across 20+ publications
**Purpose:** Inform context management design for the Workflow Orchestrator Agent (`:agent` module)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [The Context Problem in Agentic Systems](#2-the-context-problem)
3. [Tool-by-Tool Deep Analysis](#3-tool-by-tool-deep-analysis)
   - 3.1 Cline
   - 3.2 Aider
   - 3.3 OpenHands
   - 3.4 Codex CLI
   - 3.5 SWE-agent
   - 3.6 Goose
   - 3.7 Amazon Q CLI
   - 3.8 LangGraph
   - 3.9 CrewAI
   - 3.10 Anthropic SDK / Claude Code
   - 3.11 Google ADK
   - 3.12 AutoGen
4. [Comparative Analysis](#4-comparative-analysis)
5. [Architectural Patterns](#5-architectural-patterns)
6. [Academic Research & Industry Publications](#6-academic-research)
7. [Anti-Patterns](#7-anti-patterns)
8. [Recommendations for Our Agent](#8-recommendations)

---

## 1. Executive Summary

After analyzing 13 open-source agentic AI tools at the source-code level and reviewing 20+ academic papers and industry publications, the following key findings emerge:

**The industry has converged on a 4-stage compaction pipeline** (gentlest first):
1. Tool result clearing/collapsing (no LLM needed)
2. LLM-based summarization of older turns (cheap model)
3. Sliding window on conversation groups
4. Truncation as emergency backstop

**No tool implements multi-tier context budgets** (OK/COMPRESS/NUDGE/TERMINATE levels). This is our differentiation opportunity.

**Two compression camps**: LLM-powered (Cline, Aider, OpenHands, Codex, Goose, Google ADK) vs. heuristic-only (SWE-agent, Continue.dev, AutoGen core). The LLM camp consistently outperforms.

**Context resets > compaction** for long-running work (Anthropic's finding). Compaction causes "context anxiety" where models prematurely conclude tasks.

**KV-cache hit rate is the #1 production metric** (Manus AI). Stable prefixes, append-only context, and deterministic serialization are critical.

**65% of enterprise AI failures** in 2025 were attributed to context drift/memory loss, not context exhaustion.

---

## 2. The Context Problem in Agentic Systems

### 2.1 Context Rot (Chroma Research, 2025)

Tested across 18 frontier models:
- Performance follows U-shaped curve: models attend strongly to beginning/end, poorly to middle
- U-shape only persists when context < 50% full; beyond that, recency bias dominates
- At 100K tokens = 10 billion pairwise attention relationships; softmax dilutes each weight
- Even single distractors reduce performance vs baseline
- This is **architectural** (transformer quadratic attention), not fixable by training

### 2.2 Compounding Failure Rate

At 95% per-step reliability over 20 tool calls, combined success = 36%. Context degradation makes this worse because each step has slightly less reliable context than the previous one.

### 2.3 Quadratic Cost Growth

LLMs charge for every input token in every turn. A 50-turn conversation at 100K tokens/turn = 5M input tokens billed. Multi-turn agents face compounding costs that context management directly mitigates.

---

## 3. Tool-by-Tool Deep Analysis

### 3.1 Cline (VS Code Extension)

**Repos analyzed:** `github.com/cline/cline`
**Key files:** `ContextManager.ts`, `contextManagement.ts`, `SummarizeTaskHandler.ts`, `context-window-utils.ts`

#### Architecture: Six Mechanisms Working Together

1. **Standard Truncation** (old path, non-next-gen models) — programmatic sliding window
2. **Auto-Condense** (new path, next-gen models) — LLM summarization via `summarize_task` tool
3. **File Read Deduplication** — replaces duplicate file content with notices
4. **Content Size Limits** — 400KB per content block
5. **`/smol` and `/newtask` commands** — user-initiated compaction and session handoff
6. **File Read Cache** — per-task deduplication at tool handler level

#### Context Window Thresholds

| Context Window | maxAllowedSize (buffer) |
|---|---|
| 64K (DeepSeek) | contextWindow - 27,000 = 37K |
| 128K (most models) | contextWindow - 30,000 = 98K |
| 200K (Claude) | contextWindow - 40,000 = 160K |
| Other | max(contextWindow - 40,000, contextWindow * 0.8) |

#### Standard Truncation Algorithm

1. After each API request, check if `totalTokens >= maxAllowedSize`
2. Determine aggressiveness: `keep = totalTokens/2 > maxAllowedSize ? "quarter" : "half"`
3. Before truncating, attempt file read optimization first. If dedup saves >= 30%, skip truncation
4. Always keeps first user-assistant pair (indices 0 and 1)
5. Messages NOT deleted — `conversationHistoryDeletedRange` is a virtual window over full history
6. Truncated range persisted for checkpoint rollback support

#### Auto-Condense (LLM Summarization)

**Trigger:** 75% of context window (lower than truncation threshold to give LLM time for good summary)

**Process:**
1. Check `totalTokens >= min(floor(contextWindow * 0.75), maxAllowedSize)`
2. Try file read optimization first (30% savings threshold)
3. If needed, append `summarizeTask` prompt to user message
4. LLM responds with `summarize_task` tool call containing 10-section structured summary:
   - Primary Request, Key Technical Concepts, Files + Code, Problem Solving, Pending Tasks, Task Evolution, Current Work, Next Step, Required Files, Most Recent User Message
5. Auto-reads up to 8 files (max 100K chars) from "Required Files" section
6. Wraps with continuation prompt: "This session is being continued from a previous conversation..."

#### File Read Deduplication

Scans all user messages for `read_file` results, `write_to_file`/`replace_in_file` results, and `<file_content>` mentions. For files with multiple reads, replaces ALL BUT THE LAST with:
```
[NOTE] This file read has been removed to save space in the context window.
```
Optimizations are timestamped for checkpoint support — can revert by timestamp via binary search.

#### Session Handoff (`/newtask`)

Creates entirely new task instance with clean conversation history. LLM generates a structured summary passed to `new_task` tool. User confirms before new task creation. This is the **only proactive session handoff** among all tools analyzed.

---

### 3.2 Aider

**Repos analyzed:** `github.com/Aider-AI/aider`
**Key files:** `history.py`, `chat_chunks.py`, `repomap.py`, `base_coder.py`

#### Architecture: 8-Region Context Partitioning

```
ChatChunks:
  system      → System prompt (role, instructions, edit format)
  examples    → Few-shot example conversations
  done        → Completed conversation history (summarized)
  repo        → Repository map (PageRank-ranked code structure)
  readonly    → Read-only reference files
  chat_files  → Active editable files (full content)
  cur         → Current turn messages
  reminder    → System reminder at end (recency bias exploitation)
```

Assembly order intentional: system prompt at top (most attended), repo map in middle, active files near end close to current turn, reminder at very end.

**Cache control headers** at 3 breakpoints for Anthropic prompt caching.

#### Recursive Summarization Algorithm (ChatSummary)

**Model strategy:** Weak model first (e.g., GPT-4o-mini), main model as fallback.

**Algorithm (`summarize_real()`):**
1. Base case: If total tokens <= `max_tokens` at depth 0, return unchanged
2. Recursion guard: If `len(messages) <= 4` OR `depth > 3`, summarize everything via `summarize_all()`
3. Binary split: Walk backward, accumulating tokens until reaching `max_tokens // 2`. Split so head ends with assistant message
4. Head truncation: Cap at `model.max_input_tokens - 512` before sending to summarizer
5. Recursive check: If `summary_tokens + tail_tokens < max_tokens`, done. Otherwise recurse with `depth + 1`

**History budget formula:** `max_chat_history_tokens = min(max(max_input_tokens / 16, 1024), 8192)`
- 200K model: 8192 tokens
- 32K model: 2048 tokens
- 4K model: 1024 tokens

**Background thread:** Summarization runs asynchronously between user turns. Joined (blocking) only when assembling next request. Optimistic concurrency — if messages changed while summarizing, stale summary is discarded.

#### PageRank-Based Repo Map

Token budget: `max_input_tokens / 8`, clamped [1024, 4096]. When no files in chat, gets 8x multiplier.

**Algorithm:**
1. Tree-sitter extracts definitions and references from all files
2. Builds NetworkX MultiDiGraph: nodes = files, edges = referencer → definer
3. Weight multipliers: 10x for user-referenced symbols, 10x for meaningful identifiers (8+ chars), 0.1x for private (`_prefix`), 50x for chat files
4. PageRank with personalization boost for chat files
5. Binary search finds how many top-ranked tags fit within budget (15% tolerance)

**5 caching layers:** Tags (SQLite), tree render (memory), TreeContext (memory), map result (memory), refresh modes (auto/always/files/manual).

---

### 3.3 OpenHands

**Repos analyzed:** `github.com/All-Hands-AI/OpenHands`
**Key files:** `condenser.py`, `view.py`, `conversation_memory.py`, `stuck.py`, `condenser_config.py`

#### Architecture: 3-Layer Context Management

1. **Event History** (`State.history: list[Event]`) — raw, append-only event stream
2. **View** (`View.from_events()`) — filtered projection removing forgotten events, inserting summaries
3. **ConversationMemory** — converts View events into LLM-compatible Messages with truncation

#### 10 Condenser Types (Composable Pipeline)

| Condenser | LLM? | Strategy |
|---|---|---|
| **NoOpCondenser** | No | Passthrough |
| **ObservationMaskingCondenser** | No | Replace old observations with `<MASKED>` (keeps event count) |
| **BrowserOutputCondenser** | No | Replace old screenshots/a11y trees with URL stubs |
| **RecentEventsCondenser** | No | Keep first N + last M events (simple sliding window) |
| **AmortizedForgettingCondenser** | No | Drop oldest events, no summary |
| **LLMSummarizingCondenser** | Yes | Drop oldest, generate text summary via LLM |
| **LLMAttentionCondenser** | Yes | LLM picks most important events to keep (structured output) |
| **StructuredSummaryCondenser** | Yes | LLM generates 17-field Pydantic `StateSummary` via tool call |
| **ConversationWindowCondenser** | No | **DEFAULT** — keep essentials + recent half, reactive only |
| **CondenserPipeline** | Mixed | Chains multiple condensers sequentially |

#### StructuredSummaryCondenser Fields (17 fields)

```python
class StateSummary(BaseModel):
    user_context: str
    completed_tasks: str
    pending_tasks: str
    current_state: str
    files_modified: str
    function_changes: str
    data_structures: str
    tests_written: str
    tests_passing: str
    failing_tests: str
    error_messages: str
    branch_created: str
    branch_name: str
    commits_made: str
    pr_created: str
    pr_status: str
    dependencies: str
    other_relevant_context: str
```

#### Condensation Trigger Mechanism

**Two paths:**
1. **Proactive:** RollingCondensers check `len(view) > max_size` every agent step
2. **Reactive:** On `ContextWindowExceededError`, controller adds `CondensationRequestAction` to event stream

**Default (ConversationWindowCondenser):** Only fires reactively — never proactively condenses.
**Production default max_size:** 240 events (bumped from 120 in Jan 2026).

#### Summary Chain

Previous summary is included as `<PREVIOUS SUMMARY>` in next condensation prompt, creating a rolling compression chain. Each condensation builds on the last.

#### Stuck Detector (Context-Aware)

5 loop scenarios detected:
1. 4 identical action+observation pairs
2. 3 identical actions producing errors
3. 3 identical agent messages (monologue)
4. A-B-A-B-A-B alternating pattern over 6 pairs
5. **10+ consecutive condensation events** (condensation loop — the infinite loop bug)

---

### 3.4 Codex CLI (OpenAI)

**Repos analyzed:** `github.com/openai/codex`
**Key files:** `compact.rs`, `compact_remote.rs`, `history.rs`, `updates.rs`, `truncate.rs`

#### Token Counting

Purely heuristic: `bytes / 4` with ceiling division. `APPROX_BYTES_PER_TOKEN = 4`. No tokenizer. Image tokens estimated at 7,373 bytes (~1,844 tokens) with LRU cache.

#### Auto-Compact Threshold

`min(config_override, context_window * 90%)`. Default `effective_context_window_percent = 95`.

#### Three Trigger Points

1. **Pre-sampling:** Before each turn
2. **Mid-turn:** After model response when `needs_follow_up && token_limit_reached`
3. **Model-switch:** When switching to smaller context window

#### Dual Compaction Strategy

- **Local (non-OpenAI providers):** LLM summarization using "handoff summary" prompt
- **Remote (OpenAI):** Server-side `compact_conversation_history` API returning encrypted opaque blob

Both preserve:
- Ghost snapshots (git rollback references)
- User messages (last 20K tokens)
- Fresh context injection (permissions, environment, personality)

#### Output Middle-Truncation

50/50 prefix/suffix split, default 10K bytes per tool output. 1.2x budget multiplier for serialization overhead.

#### `InitialContextInjection` Enum

Controls whether fresh system context is re-injected after compaction:
- `DoNotInject` for pre-turn/manual compaction
- `BeforeLastUserMessage` for mid-turn compaction

---

### 3.5 SWE-agent

**Repos analyzed:** `github.com/SWE-agent/SWE-agent`
**Key files:** `history_processors.py`, `agents.py`

#### Architecture: 7 Composable Heuristic Processors (No LLM)

Processors chain via `for processor in self.history_processors: messages = processor(messages)`. Each is a Pydantic BaseModel.

| Processor | Purpose |
|---|---|
| **LastNObservations** | Keep last N observations, replace older with line-count stub |
| **TagToolCallObservations** | Tag specific tool calls for selective preservation |
| **ClosedWindowHistoryProcessor** | Replace outdated file windows with stubs |
| **CacheControlHistoryProcessor** | Add Claude prompt cache markers to last N messages |
| **RemoveRegex** | Strip patterns (default: `<diff>.*</diff>`) with keep_last |
| **ImageParsingHistoryProcessor** | Convert base64 images to multimodal format |

#### LastNObservations Polling Parameter

`last_removed_idx = max(0, (len(obs) // polling) * polling - n)`

Quantizes update frequency — trades slightly more context for better prompt cache hit rates. This is a **unique optimization** not found in any other tool.

#### No LLM Summarization

All context management is pure heuristics. Design philosophy: research-focused tool prioritizes clean eval results and deterministic behavior over adaptive compression.

---

### 3.6 Goose (Block)

**Repos analyzed:** `github.com/block/goose`
**Key files:** `context_mgmt/mod.rs`, `agents/agent.rs`, `prompts/compaction.md`

#### Architecture: Two-Tier Compression

**Tier 1 — Tool-Pair Summarization (Incremental, Background):**
- Runs via `tokio::spawn` during every reply loop iteration
- Uses cheap model (`complete_fast()`) to summarize individual tool call/response pairs
- Batch size: 10 (always processes oldest 10 pairs)
- Cutoff formula: `(3 * effective_limit / 20_000).clamp(10, 500)`
  - 128K context: cutoff = 15
  - 200K context: cutoff = 24
  - 1M context: cutoff = 120
- Current turn's tool calls never summarized

**Tier 2 — Full Conversation Compaction:**
- Default threshold: 80% of context (`GOOSE_AUTO_COMPACT_THRESHOLD=0.8`)
- Token counting: `tiktoken_rs::CoreBPE` with DashMap cache (10K entries), prefers server-reported tokens
- Two trigger points: pre-reply (proactive) and mid-loop on `ContextLengthExceeded` (reactive, max 2 attempts)

#### Progressive Fallback (Middle-Out Pattern)

When compaction itself exceeds context:
```
removal_percentages = [0, 10, 20, 50, 100]
```
For each percentage, removes that % of tool response messages from the **middle** of the conversation outward (alternating left/right). Preserves first and last tool responses longest.

#### Compaction Prompt (9 Sections)

Uses `<analysis>` tags for reasoning:
1. User Intent
2. Technical Concepts
3. Files + Code (full code, change justifications)
4. Errors + Fixes
5. Problem Solving
6. User Messages
7. Pending Tasks
8. Current Work
9. Next Step

Key instruction: "This summary will only be read by you so it is ok to make it much longer than a normal summary you would show to a human"

#### Post-Compaction Message Structure

1. Original messages: `metadata.with_agent_invisible()` (visible to UI, invisible to model)
2. Summary: `MessageMetadata::agent_only()` (role: User)
3. Continuation text: (role: Assistant, agent_only)
4. Most recent user message: re-injected as fresh message

#### Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `GOOSE_AUTO_COMPACT_THRESHOLD` | 0.8 | Compaction trigger ratio |
| `GOOSE_TOOL_PAIR_SUMMARIZATION` | true | Background tool summarization |
| `GOOSE_MAX_TURNS` | 1000 | Max turns without user input |
| `GOOSE_CONTEXT_LIMIT` | Model default | Override context window |
| `GOOSE_MOIM_MESSAGE_TEXT` | unset | Persistent text injected every turn |
| `GOOSE_MOIM_MESSAGE_FILE` | unset | File contents injected every turn (max 64KB) |

---

### 3.7 Amazon Q CLI

**Repos analyzed:** `github.com/aws/amazon-q-developer-cli`
**Key files:** `token_counter.rs`, `conversation.rs`, `compact.rs`, `agent/mod.rs`

#### Split Personality: Two Systems

**chat-cli module (older, full-featured):**
- Token counting: `chars / 4`, rounded to nearest 10
- 3-step progressive strategy on `ContextWindowOverflow`:
  1. Auto-compact with defaults (if <=2 messages, enable 25K truncation)
  2. Exclude 1 most recent message pair + add truncation
  3. `CompactHistoryFailure` (terminal — suggests `/compact`, `/usage`, `/clear`)
- Summary chaining: previous summary included in next compaction prompt
- Context files limited to 75% of context window

**agent module (newer):**
- `ContextWindowOverflow` is **terminal** — sets `ActiveState::Errored` and stops
- No auto-compaction in agent loop (infrastructure built but not wired)
- 500-message hard history cap
- `CompactStrategy` provides message truncation: `DEFAULT_MAX_MESSAGE_LEN = 25_000`

---

### 3.8 LangGraph

**Repos analyzed:** `github.com/langchain-ai/langgraph`
**Key files:** `message.py`, `channels/base.py`, `checkpoint/base/__init__.py`, `store/base/__init__.py`

#### Philosophy: Infrastructure, Not Context Management

LangGraph deliberately has **NO built-in context window management**. It provides:

- **`add_messages` reducer:** ID-based dedup, `RemoveMessage` for explicit deletion, `REMOVE_ALL_MESSAGES` sentinel
- **Checkpoint system:** Full state persistence with thread_id, versioning, pruning strategies (keep_latest, delete)
- **Store system:** Cross-thread key-value + vector search with TTL, namespace hierarchy, embedding support

Context management (trim_messages, token counting) is delegated to `langchain-core`.

#### Channel System for State Flow

Each state key becomes a channel. `BinaryOperatorAggregate` applies reducers (e.g., `add_messages`, `operator.add`). `Overwrite` type bypasses reducers. Channels have monotonically increasing version strings.

---

### 3.9 CrewAI

**Repos analyzed:** `github.com/crewAIInc/crewAI`
**Key files:** `unified_memory.py`, `encoding_flow.py`, `recall_flow.py`, `memory_scope.py`

#### Unified Memory with Composite Scoring

Three-factor weighted score:
```python
composite = (
    0.5 * semantic_similarity +    # Vector similarity
    0.3 * exponential_time_decay + # 30-day half-life
    0.2 * importance               # Explicit importance
)
```

#### 5-Step Encoding Pipeline

1. **Batch Embed** — single embedder call for all items
2. **Intra-batch Dedup** — cosine similarity >= 0.98 dropped
3. **Parallel Find Similar** — concurrent storage searches (8 threads)
4. **Parallel Analyze** — LLM classifies into 4 groups (0-2 LLM calls each)
5. **Execute Plans** — apply consolidation (dedup, update, insert)

#### Adaptive-Depth Recall

Confidence-based routing:
- Confidence >= 0.8 → return immediately ("synthesize")
- Confidence < 0.5 + budget > 0 → "explore_deeper" (recursive LLM exploration)
- Complex query + confidence < 0.7 → "explore_deeper"

#### No Token Budget Management

No context window tracking, no trimming, no compression. Memory stores full content. The LLM-based consolidation on save is for deduplication, not compression.

---

### 3.10 Anthropic SDK / Claude Code

**Repos analyzed:** `github.com/anthropics/anthropic-sdk-python`
**Key source:** Claude API documentation, Anthropic engineering blog posts

#### Server-Side Context Management (Beta API)

Three strategy types:
1. **`clear_tool_uses_20250919`** — clears oldest tool results chronologically with configurable keep/exclude/trigger
2. **`clear_thinking_20251015`** — clears reasoning blocks
3. **`compact_20260112`** — server-side LLM compaction, default 150K token trigger (min 50K), custom instructions support

#### Client-Side CompactionControl

In the tool runner: checks `input + output tokens >= threshold` (default 100K), sends summary prompt to LLM, replaces entire history with single summary message. Strips `tool_use` blocks before summarizing.

#### Memory Tool (`memory_20250818`)

Builtin server-side tool with filesystem backend. Operations: view/create/str_replace/insert/delete/rename. Abstract base class enables custom backends.

#### Claude Code Specifics

- Auto-compact at ~83.5% (configurable via `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`, 1-100)
- Buffer reduced to ~33K tokens (16.5%) as of early 2026
- Instant compaction since v2.0.64
- Key rule: persistent instructions in CLAUDE.md, not conversation history

---

### 3.11 Google ADK

**Repos analyzed:** `github.com/google/adk-python`
**Key source:** ADK documentation, Google Developers Blog

#### Four-Layer Context System

1. **Working Context** — ephemeral prompt for single invocation
2. **Session** — durable event log (structured Events)
3. **Memory** — long-lived searchable knowledge across sessions
4. **Artifacts** — named, versioned binary/text objects

#### Event-Sourced Compaction

Events are never deleted. Compaction summaries are additional events with `start_timestamp`/`end_timestamp` ranges. Overlapping ranges handled via subsumption detection.

**Two compaction modes:**
1. **Token threshold:** Triggered by `usage_metadata.prompt_token_count` (falls back to `chars // 4`)
2. **Sliding window:** Triggered by invocation count, with configurable overlap

**Rolling summaries:** Previous compaction summary seeded as first event for next summarization. Pending function calls never compacted. Safe split index prevents orphaning function responses.

#### Branch-Based Multi-Agent Segregation

Dot-delimited branches (`agent_1.agent_2`): child sees parent events, peers isolated. Cross-agent messages reformatted as `[agent_name] said:` user context.

#### Memory Service

Abstract `BaseMemoryService` with implementations: `InMemoryMemoryService` (keyword matching), `VertexAiRagMemoryService` (production RAG), `VertexAiMemoryBankService` (managed).

---

### 3.12 AutoGen (Microsoft)

**Repos analyzed:** `github.com/microsoft/autogen`
**Key source:** AutoGen documentation

#### 4 Pluggable ChatCompletionContext Strategies

| Strategy | Method | LLM? |
|---|---|---|
| **UnboundedChatCompletionContext** | All messages | No |
| **BufferedChatCompletionContext** | Last N messages | No |
| **HeadAndTailChatCompletionContext** | First N + last M with "Skipped X messages" placeholder | No |
| **TokenLimitedChatCompletionContext** | Remove from middle using `model_client.count_tokens()` | No |

None use LLM-based summarization in core. The `Memory.update_context()` interface allows injection of memory entries as SystemMessages.

#### Experimental MemoryController

Learn-from-failure: iteratively trains on tasks, extracts insights, topic-based retrieval with LLM validation.

#### SocietyOfMindAgent

Runs inner team, then summarizes result with separate LLM call. Inner team reset after each call (no state leakage).

---

## 4. Comparative Analysis

### 4.1 Context Compression Strategies

| Tool | LLM Compress | Heuristic Compress | Token Counting | Default Threshold |
|---|---|---|---|---|
| **Cline** | Yes (summarize_task) | Yes (sliding window) | API-reported | 75% (auto-condense), ~80% (truncation) |
| **Aider** | Yes (weak model) | No | tiktoken | min(max(ctx/16, 1024), 8192) tokens |
| **OpenHands** | Yes (10 condenser types) | Yes (masking, sliding) | Event count | 240 events |
| **Codex CLI** | Yes (local) + server-side | No | bytes/4 heuristic | 90% of context |
| **SWE-agent** | No | Yes (7 processors) | N/A (event-based) | Last N observations |
| **Goose** | Yes (two-tier) | Yes (tool response removal) | tiktoken_rs | 80% of context |
| **Amazon Q** | Yes (chat-cli only) | Yes (message truncation) | chars/4 | On overflow (reactive) |
| **LangGraph** | No (delegates) | No (delegates) | No built-in | N/A |
| **CrewAI** | Yes (consolidation) | No | No built-in | N/A |
| **Anthropic** | Yes (server-side) | Yes (tool clearing) | Server-side | 150K tokens |
| **Google ADK** | Yes (event summarization) | Yes (sliding window) | Server + chars/4 | Token threshold |
| **AutoGen** | No | Yes (4 strategies) | model_client.count_tokens() | Configurable |

### 4.2 What Gets Preserved (Priority Rules)

| Tool | Always Kept | Preferentially Kept | Dropped First |
|---|---|---|---|
| **Cline** | First user-assistant pair | Most recent messages, last file reads | Middle conversation, duplicate file reads |
| **Aider** | System prompt, examples | Recent history (tail), active files | Older history (head), repo map adjusts |
| **OpenHands** | First N events (keep_first=1) | Recent events (tail half) | Middle events, old observations |
| **Codex CLI** | Ghost snapshots, last 20K user tokens | Recent messages | Old tool outputs |
| **SWE-agent** | First observation (instance template) | Last N observations, tagged outputs | Old observations, diff content |
| **Goose** | Most recent user message | First/last tool responses | Middle tool responses (middle-out) |
| **Google ADK** | Pending function calls | Recent events, rolling summary seed | Old events (with summary) |

### 4.3 Session Handoff Capabilities

| Tool | Mechanism | Proactive? |
|---|---|---|
| **Cline** | `/newtask` tool — creates new task with structured context | Yes (configurable threshold) |
| **Codex CLI** | Multi-context-window compaction (GPT-5.1) | Server-managed |
| **Anthropic** | Harness pattern (Planner → Generator → Evaluator) | Architecture-level |
| **All others** | None — compress in place | No |

### 4.4 Summarization Prompt Comparison

| Tool | Sections | Key Differentiator |
|---|---|---|
| **Cline** | 10 sections (most structured) | Required Files for auto-reload, verbatim quotes |
| **Aider** | 1 section (simplest) | First-person perspective, function names mandatory |
| **OpenHands** | 10 tracking categories (LLM) or 17 Pydantic fields (Structured) | Typed structured output via tool call |
| **Goose** | 9 sections with `<analysis>` tags | "Longer than normal summary" instruction |
| **Amazon Q** | 5 sections (bullet-point) | Third person, TODO ID preservation |
| **Anthropic** | Default: state + next steps + learnings | Customizable via instructions parameter |
| **Google ADK** | Rolling with seed summary | Previous summary fed as first event |

---

## 5. Architectural Patterns

### 5.1 The Compaction Pipeline Pattern (Industry Standard)

Adopted by Anthropic, OpenAI, Microsoft, Google, Goose:

```
Stage 1: Tool Result Clearing (no LLM)
  ↓ Still over threshold?
Stage 2: LLM Summarization (cheap model)
  ↓ Still over threshold?
Stage 3: Sliding Window (drop oldest groups)
  ↓ Still over threshold?
Stage 4: Truncation (emergency, destructive)
```

**Microsoft's implementation** (most formalized):
- **MessageGroup** as atomic unit (System, User, AssistantText, ToolCall, Summary)
- **CompactionTrigger** delegates (TokensExceed, MessagesExceed, TurnsExceed, GroupsExceed)
- **Trigger vs Target**: trigger = when to start; target = when to stop
- Uses cheaper model for summarization

### 5.2 Weak/Cheap Model Summarization

| Tool | Summarization Model | Main Model |
|---|---|---|
| Aider | GPT-4o-mini / Claude Haiku | GPT-4o / Claude Opus |
| Goose | `complete_fast()` | `complete()` |
| Microsoft | gpt-4o-mini (recommended) | Any |
| OpenHands | Configurable `llm_config` with separate `usage_id: "condenser"` | Main model |
| Cline | Same model | Same model |

**Key insight:** Using the same model for summarization wastes expensive tokens. Aider's approach (weak model first, main as fallback) is optimal.

### 5.3 KV-Cache Optimization (Manus AI Pattern)

Production rules for maximizing prompt cache hits:
1. **Stable prefixes:** No timestamps, no dynamic tool removal
2. **Append-only context:** Never reorder messages
3. **Deterministic serialization:** Same content = same bytes
4. **Explicit cache breakpoints:** Aider uses 3 breakpoints; SWE-agent's `CacheControlHistoryProcessor` adds `cache_control` to last N messages
5. **Logits masking over tool removal:** Never remove tools dynamically (breaks cache); mask at decode time instead
6. **Session routing:** Route same conversation to same inference server/worker

### 5.4 File System as Persistent Memory

Pattern from Manus AI and Letta:
- **Filesystem = unlimited, persistent, directly-operable context**
- Compression always reversible (drop content if URL/path persists)
- **Todo.md pattern:** Agent creates/updates task files → "recites objectives into end of context" → pushes plans into recent attention span

### 5.5 Event-Sourced Context (Google ADK, OpenHands)

Events are never deleted. Compaction produces additional "summary" events with timestamp ranges. Benefits:
- Full audit trail
- Rollback to any point
- Multiple views of same history
- Subsumption detection for overlapping summaries

### 5.6 Context Resets > Compaction (Anthropic)

For long-running work, full context reset + structured handoff artifacts is more reliable than progressive compaction. Compaction causes "context anxiety" where models prematurely conclude tasks.

**Anthropic's harness patterns:**
- **Two-agent:** Initializer (creates progress file + feature list) → Coding Agent (works incrementally)
- **Three-agent (GAN-inspired):** Planner → Generator → Evaluator (calibrated with few-shot examples)
- Feature list in JSON, not Markdown, to prevent inappropriate overwrites

---

## 6. Academic Research & Industry Publications (2025-2026)

### 6.1 Key Papers

| Paper | Key Contribution | Result |
|---|---|---|
| **ACON** (arXiv 2510.00615) | Gradient-free compression guideline optimization | 26-54% token reduction, baseline accuracy maintained |
| **ACE** (arXiv 2510.04618, ICLR 2026) | Evolving context playbooks via generation/reflection/curation | +10.6% on agent benchmarks |
| **Don't Break the Cache** (arXiv 2601.06007) | Prompt caching evaluation across models | 45-80% cost savings, system-prompt-only caching most reliable |
| **Memory in Age of AI Agents** (arXiv 2512.13564) | Taxonomy: forms (token/parametric/latent), functions (factual/experiential/working) | Survey framework |
| **Structured CE for File-Native Systems** (arXiv 2602.05447) | 9,649 experiments, 11 models, 4 formats | Empirical CE guidelines |

### 6.2 Key Industry Publications

**Anthropic — "Effective Context Engineering for AI Agents" (Sep 2025):**
- Context engineering = curating ALL tokens to maximize desired behavior
- JIT retrieval > pre-loading
- Progressive disclosure: agents discover context incrementally
- Sub-agent architectures for context isolation

**Anthropic — Agentic Coding Trends Report (2026):**
- 65% of enterprise AI failures attributed to context drift/memory loss
- Context resets > compaction for reliability

**Manus AI — Production Lessons (Jul 2025):**
- KV-cache hit rate is #1 metric
- Error preservation: keep wrong turns in context (models learn from failures)
- Few-shot drift: uniform action-observation pairs cause pattern mimicry → introduce structured variation

### 6.3 AGENTS.md Standard

Open standard (Linux Foundation, Agentic AI Foundation):
- Supported by: OpenAI Codex, Amp, Jules (Google), Cursor, Factory, Claude Code
- Persistent context loaded at session start
- Path-scoped rules (e.g., `*.ts` for TypeScript files)
- Precedence: AGENTS.override.md > AGENTS.md > fallback names

---

## 7. Anti-Patterns

Based on cross-cutting analysis of all tools:

1. **Dumping everything into context** — "right information, not most information" (Anthropic)
2. **Dynamic tool removal** — breaks KV-cache; use logits masking instead (Manus)
3. **Timestamps in system prompts** — invalidates cache from that token forward
4. **Same model for summarization** — wastes expensive tokens; use cheap model (Aider pattern)
5. **Reactive-only compaction** — proactive threshold checks prevent API errors (Goose, Codex)
6. **No summary chaining** — each compaction should include previous summary (Amazon Q, Google ADK)
7. **Hiding errors from context** — models learn from observed failures (Manus)
8. **Single long-running session** — context resets with structured handoff > progressive compression (Anthropic)
9. **Full-context caching** — can paradoxically increase latency; system-prompt-only is more reliable
10. **Token counting by event count** — less precise than token/byte estimation (OpenHands limitation)
11. **No stuck detection** — compaction can loop infinitely without detection (OpenHands bug #8630, Codex #13946)
12. **Compaction prompt too short** — Goose's 9-section prompt with "make it longer" instruction outperforms terse prompts

---

## 8. Recommendations for Our Agent

Based on synthesis of all findings, here is the recommended context management architecture for the Workflow Orchestrator Agent:

### 8.1 Multi-Tier Context Budget (Our Differentiator)

No tool implements this. We should be the first:

```
TIER 1 — OK (< 60% context):
  Normal operation, no action needed

TIER 2 — COMPRESS (60-75% context):
  Tool result clearing (collapse old tool outputs to summaries)
  File read deduplication (Cline pattern)
  Background tool-pair summarization (Goose Tier 1 pattern)

TIER 3 — NUDGE (75-85% context):
  LLM summarization of older conversation (cheap model)
  Rolling summary chain (Google ADK pattern)
  Notify agent via system message: "Context approaching limit"

TIER 4 — CRITICAL (85-95% context):
  Aggressive sliding window
  Progressive tool response removal (Goose middle-out pattern)
  Consider session handoff (Cline /newtask pattern)

TIER 5 — TERMINATE (> 95% context):
  Emergency truncation
  State dump to persistent storage
  Force session handoff with structured summary
```

### 8.2 Compaction Pipeline

```kotlin
interface ContextCompactor {
    suspend fun compact(history: List<ConversationMessage>, budget: TokenBudget): CompactionResult
}

// Pipeline stages (ordered by aggressiveness):
class ToolResultClearer : ContextCompactor        // Stage 1: No LLM
class FileReadDeduplicator : ContextCompactor      // Stage 2: No LLM
class ToolPairSummarizer : ContextCompactor        // Stage 3: Cheap LLM
class ConversationSummarizer : ContextCompactor    // Stage 4: Cheap LLM
class SlidingWindowCompactor : ContextCompactor    // Stage 5: No LLM
class EmergencyTruncator : ContextCompactor        // Stage 6: No LLM
```

### 8.3 Summarization Strategy

- **Primary model:** Use a cheap/fast model (not the main agent model)
- **Summary prompt:** 9-section structured prompt (Goose-inspired) with:
  1. User Intent / Task Goal
  2. Technical Context (project, branch, services)
  3. Files Modified + Code Changes
  4. Errors Encountered + Resolutions
  5. Tool Execution History (condensed)
  6. Problem-Solving Decisions
  7. Pending Tasks / Blocklist
  8. Current State
  9. Next Steps
- **Summary chaining:** Include previous summary in next compaction (Google ADK pattern)
- **Structured output:** Consider Pydantic-like typed summary (OpenHands StructuredSummaryCondenser)

### 8.4 Token Counting

- **Primary:** Server-reported `usage.input_tokens` from API response
- **Fallback:** `bytes / 4` heuristic (Codex CLI pattern) — simple, fast, ~80% accurate
- **Cache:** DashMap/ConcurrentHashMap with bounded size (Goose pattern)

### 8.5 Prompt Caching

- **Stable prefix:** System prompt + tool definitions + CLAUDE.md content
- **Cache breakpoints:** After system prompt, after tool defs, after project context
- **Never invalidate:** No timestamps in system prompt, no dynamic tool removal
- **Logits masking:** If tools need to be hidden, mask at decode time (not removal)

### 8.6 Persistent Context (Outside Window)

- **Session files:** `~/.workflow-orchestrator/{project}/agent/sessions/{id}/`
  - `context-summary.json` — structured summary (typed fields)
  - `progress.md` — task progress file (Todo.md pattern from Manus)
  - `decisions.md` — architectural decisions made
- **Core memory:** `agent/core-memory.json` — cross-session knowledge
- **CLAUDE.md / AGENTS.md:** Project-level persistent instructions

### 8.7 Stuck Detection

Implement OpenHands-style pattern matching:
- 3+ consecutive identical actions → stuck
- 3+ text-only responses → monologue
- 10+ consecutive compaction events → compaction loop
- A-B-A-B alternating pattern → oscillation

### 8.8 Session Handoff

When context reaches TIER 4 or above:
1. Generate structured summary (all 9 sections)
2. Write to session file
3. Offer user: "Context is running low. Continue with compressed context or start fresh session?"
4. If fresh session: pre-load summary + re-read critical files (Cline pattern, max 8 files, 100K chars)

### 8.9 Configuration

```kotlin
data class ContextManagementConfig(
    val autoCompactThreshold: Double = 0.75,        // TIER 3 trigger
    val criticalThreshold: Double = 0.85,           // TIER 4 trigger
    val emergencyThreshold: Double = 0.95,          // TIER 5 trigger
    val toolPairSummarizationEnabled: Boolean = true,
    val maxToolPairsBeforeSummarize: Int = 15,
    val summarizationModel: String = "cheap",       // Use cheap model
    val summaryChaining: Boolean = true,
    val fileReadDeduplication: Boolean = true,
    val stuckDetectionEnabled: Boolean = true,
    val maxConsecutiveCompactions: Int = 3,          // Stuck detection
    val sessionHandoffEnabled: Boolean = true,
    val maxMessageLength: Int = 25_000,             // Per-message truncation
    val maxToolOutputLength: Int = 10_000,          // Per-tool-output truncation
)
```

---

## Source Repositories Analyzed

| Tool | Repository | Language | Clone Depth |
|---|---|---|---|
| Cline | `github.com/cline/cline` | TypeScript | Full context management |
| Aider | `github.com/Aider-AI/aider` | Python | ChatSummary, RepoMap, ChatChunks |
| OpenHands | `github.com/All-Hands-AI/OpenHands` | Python | All condensers, View, Memory |
| Codex CLI | `github.com/openai/codex` | Rust | compact.rs, history.rs, truncate.rs |
| SWE-agent | `github.com/SWE-agent/SWE-agent` | Python | history_processors.py |
| Goose | `github.com/block/goose` | Rust | context_mgmt, agent, prompts |
| Amazon Q | `github.com/aws/amazon-q-developer-cli` | Rust | chat-cli, agent modules |
| LangGraph | `github.com/langchain-ai/langgraph` | Python | channels, checkpoint, store |
| CrewAI | `github.com/crewAIInc/crewAI` | Python | unified_memory, encoding/recall flows |
| Anthropic SDK | `github.com/anthropics/anthropic-sdk-python` | Python | CompactionControl, memory tool |
| Google ADK | `github.com/google/adk-python` | Python | compaction, memory, session |
| AutoGen | `github.com/microsoft/autogen` | Python | ChatCompletionContext, Memory |

## Web Research Sources

- Anthropic Engineering Blog: Context Engineering (Sep 2025), Harness Design (Nov 2025, 2026)
- Claude API: Compaction Documentation (Jan 2026)
- OpenAI: Compaction Guide, Codex AGENTS.md
- Microsoft: Agent Framework Compaction (Mar 2026)
- Manus AI: Production Context Engineering Lessons (Jul 2025)
- Google Developers Blog: Multi-Agent Framework
- Chroma Research: Context Rot (2025)
- Martin Fowler: Context Engineering for Coding Agents
- Academic Papers: ACON, ACE (ICLR 2026), Don't Break the Cache, Memory in Age of AI Agents, + 6 more
- Anthropic 2026 Agentic Coding Trends Report
