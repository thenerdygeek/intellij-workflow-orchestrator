# Agent Memory Systems — Deep Source Analysis

**Date:** 2026-04-04
**Purpose:** Source-code-level analysis of Letta (MemGPT), Codex CLI, and Goose memory systems to inform porting decisions for our IntelliJ plugin agent module.

## Executive Summary

Three systems analyzed at source-code level:

| System | Architecture | Agent Self-Edits | Storage | Search | Complexity |
|--------|-------------|-----------------|---------|--------|-----------|
| **Letta** | 3-tier (core/archival/recall) | Yes — 6+ tools | PostgreSQL + pgvector | Semantic + FTS + hybrid RRF | High (5K+ LOC) |
| **Codex** | 2-phase pipeline (extract→consolidate) | Semi — consolidation agent | SQLite + files | Usage-based selection | Very High (3K+ LOC) |
| **Goose** | 2-scope file-based (global/local) | Yes — 4 tools | Plain text files | Category + tag filtering | Low (668 LOC) |

**Recommendation:** Port Letta's 3-tier pattern (matches our existing spec) with Goose's file-based simplicity (no DB dependency) and Codex's usage tracking/decay (smart pruning).

---

## 1. Letta (MemGPT) — Source Analysis

### Core Memory (Always-in-Prompt)

**Key files:**
- `letta/schemas/memory.py` (884 lines) — Memory class, Block structure, rendering
- `letta/schemas/block.py` — Block definition (label, value, limit, read_only, metadata, tags)

**Structure:** Named blocks with character limits
- Default block limit: 100,000 chars
- Persona block: 20,000 chars
- Human block: 20,000 chars
- Blocks rendered as XML in system prompt before every LLM call

**Rendering:** Three strategies based on model type:
1. Standard: `<memory_blocks><label><value>...</value></label></memory_blocks>`
2. Line-numbered (Anthropic): Adds `1→`, `2→` line numbers for precise editing
3. Git-enabled: `<self>` section from persona, external blocks as file tree

**Key insight:** Core memory is injected via `Memory.compile()` before EVERY LLM call. The system prompt is rebuilt each turn.

### Archival Memory (Long-Term)

**Key files:**
- `letta/orm/passage.py` — ArchivalPassage ORM
- `letta/services/passage_manager.py` — Insert/search
- `letta/services/agent_manager.py` (lines 2534-2670) — Search implementation

**Storage:** PostgreSQL with pgvector (4096-dim embeddings)
- Passages stored with text, embedding, tags, metadata, timestamps
- Junction table `passage_tag` for efficient tag queries
- Soft-delete support (is_deleted flag)

**Search:** Hybrid Reciprocal Rank Fusion (RRF)
- Combines vector similarity + full-text search
- Filter by tags (any/all mode), date range
- Returns content + relevance scores (rrf_score, vector_rank, fts_rank)

### Recall Memory (Conversation History)

**Storage:** PostgreSQL message table
**Search:** Hybrid (embedding + FTS) with role and date filters
- Post-filters: removes tool messages, prevents recursive search nesting
- Returns role, content, timestamp per result

### Memory Tools

**Tool executor:** `letta/services/tool_executor/core_tool_executor.py` (1000+ lines)

| Tool | Function | Notes |
|------|----------|-------|
| `core_memory_append` | Append to named block | Respects read_only, newline-separated |
| `core_memory_replace` | Find-and-replace in block | Exact match, rejects if 0 or >1 occurrences |
| `memory_replace` | V2 replace with line numbers | Rejects line-number prefixes from content |
| `memory_insert` | Insert at specific line | -1 = append to end |
| `memory_apply_patch` | Unified diff on blocks | Supports add/delete/update/rename blocks |
| `memory_rethink` | Rewrite entire block | Used in sleeptime consolidation |
| `archival_memory_insert` | Store to archival | With tags, triggers prompt rebuild |
| `archival_memory_search` | Search archival | Hybrid RRF, tag filters, date filters |
| `conversation_search` | Search past messages | Role/date filters, hybrid search |

### Persistence

- PostgreSQL with SQLAlchemy 2.0 async ORM
- Alembic migrations for schema evolution
- Optimistic locking via version field on blocks
- Block history table for audit trail

---

## 2. Codex CLI — Source Analysis

### Two-Phase Pipeline

**Key files:** `codex-rs/core/src/memories/` (3,310 lines total)

**Phase 1: Raw Memory Extraction** (`phase1.rs`, `job.rs`)
- Processes up to 8 threads concurrently (CONCURRENCY_LIMIT=8)
- Per-thread: load rollout items → filter/truncate to 70% context window → call model → extract StageOneOutput
- Output: raw_memory (markdown), rollout_summary (1-5 lines), rollout_slug
- Secrets redacted post-extraction via regex patterns (API keys, AWS keys, bearer tokens, generic secrets)

**Phase 2: Global Consolidation** (`phase2.rs`)
- Single global lock via database job leasing
- Spawns sub-agent with restricted config (no network, workspace-write only to codex_home)
- Input: selected Stage1Outputs (based on usage decay)
- Output: MEMORY.md (handbook), memory_summary.md (navigation index), rollout_summaries/

### Usage Tracking & Decay

**Schema:** `usage_count INTEGER`, `last_usage INTEGER` on stage1_outputs table

**Selection algorithm:**
- Retention cutoff: `now - max_unused_days`
- Recency metric: `COALESCE(last_usage, source_updated_at)`
- Priority: `usage_count DESC, last_usage DESC, source_updated_at DESC`
- Pruning: batch delete unused rows not selected for phase2

**Key insight:** Memories that aren't cited by the consolidation agent decay and eventually get pruned. Natural selection for useful knowledge.

### Database Schema

```sql
-- stage1_outputs: per-thread extracted memories
thread_id TEXT PRIMARY KEY, source_updated_at INTEGER, raw_memory TEXT,
rollout_summary TEXT, generated_at INTEGER, rollout_slug TEXT,
usage_count INTEGER, last_usage INTEGER, selected_for_phase2 INTEGER

-- jobs: lease-based coordination
kind TEXT, job_key TEXT, status TEXT, worker_id TEXT, ownership_token TEXT,
started_at INTEGER, finished_at INTEGER, lease_until INTEGER,
retry_at INTEGER, retry_remaining INTEGER, last_error TEXT,
input_watermark INTEGER, last_success_watermark INTEGER
```

### File Artifacts

```
~/.codex/memories/
├── raw_memories.md         (merged phase1 outputs for phase2 input)
├── rollout_summaries/      (per-thread .md summaries)
├── MEMORY.md               (consolidated handbook — phase2 output)
├── memory_summary.md       (always-loaded navigation index)
└── skills/                 (reusable procedures)
```

---

## 3. Goose — Source Analysis

### Simple File-Based Memory

**Key file:** `crates/goose-mcp/src/memory/mod.rs` (668 lines)

**Storage:** Plain text files in category directories
- Global: `~/.config/goose/memory/{category}.txt`
- Local: `.goose/memory/{category}.txt` (project-specific)
- Entries separated by double newlines
- Tags as `# tag1 tag2` prefix lines

### Tool Signatures

| Tool | Parameters | Notes |
|------|-----------|-------|
| `remember_memory` | category, data, tags[], is_global | Append to category file |
| `retrieve_memories` | category ("*" for all), is_global | Returns HashMap<tags, entries> |
| `remove_memory_category` | category ("*" for all), is_global | Delete category file(s) |
| `remove_specific_memory` | category, memory_content, is_global | Substring match deletion |

### System Prompt Injection

At startup (`MemoryServer::new()`):
1. Load all global memories
2. Format as: `**Here are the user's currently saved memories:**` + category headers + bullet entries
3. Inject via MCP server instructions

**Key insight:** Only global memories pre-injected. Local memories accessed via tool calls on-demand.

---

## Porting Recommendation

### What to take from each:

**From Letta (primary pattern):**
- 3-tier architecture (core/archival/recall)
- Memory tools as first-class agent tools
- Core memory blocks with labels, limits, read_only
- Core memory compiled into system prompt before each LLM call
- Archival insert with tags
- Conversation search with role/date filters

**From Goose (storage simplicity):**
- File-based persistence (no PostgreSQL dependency)
- JSON files instead of vector DB
- Global vs project scoping
- Plain-text category organization
- Startup injection of core memory

**From Codex (smart pruning):**
- Usage tracking (usage_count, last_usage)
- Decay-based retention (max_unused_days)
- Secrets redaction from memories
- Consolidation summaries (memory_summary.md always loaded)

### What NOT to port:
- Letta's PostgreSQL/pgvector requirement (too heavy for IDE plugin)
- Letta's embedding-based search (requires embedding model — use keyword/tag search instead)
- Codex's 2-phase consolidation pipeline (requires background LLM calls — defer to v2)
- Letta's block versioning/optimistic locking (overkill for file-based storage)
- Letta's multiple rendering strategies (just use our XML format)
