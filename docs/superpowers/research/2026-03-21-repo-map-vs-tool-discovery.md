# Repo Map vs Tool-Based Discovery in AI Coding Agents

**Date:** 2026-03-21
**Status:** Research complete
**Question:** Should an AI coding agent pre-load a "repo map" (compact project structure summary) into context at session start, or discover the project structure on-demand via tools?

---

## 1. How Major Tools Handle It

### Aider — The Repo Map Pioneer

Aider pioneered the repo map concept and it remains central to their architecture.

**How it works:**
1. Tree-sitter parses all source files into ASTs
2. Extracts `Tag` objects (definitions + references) with: filename, line number, identifier name, kind (def/ref)
3. Builds a NetworkX directed graph where files are nodes, edges connect files with cross-references
4. Runs **PageRank** with personalization weighting (files in chat get 100x weight vs 1x default)
5. Selects top-ranked symbols that fit within the token budget
6. Formats as hierarchical tree: `filename > class > method(signature)`

**What's included:** Function/method signatures, class definitions, type annotations — NOT implementations. Just enough to understand APIs and relationships.

**Token budget:** Default 1,024 tokens (`--map-tokens`). When no files are in chat, expands to `1024 * 8 = 8,192` tokens. Controllable by user.

**Evolution:**
- v1: ctags-based (simple symbol extraction, no ranking)
- v2: tree-sitter + PageRank (current) — richer signatures, dependency-aware ranking
- Fallback: For languages without tree-sitter reference queries, uses Pygments lexer to extract identifiers

**Caching:** `diskcache.Cache` on disk (`.aider.tags.cache.v{N}/`), keyed by file mtime. Avoids re-parsing unchanged files.

**Effectiveness:** Aider does not publish ablation benchmarks (repo-map-on vs repo-map-off). The claimed benefit is qualitative: the LLM can identify which files to request without blind searching. The PageRank scoring ensures the most-connected (most important) symbols appear first.

**Key insight:** The map is *personalized per query* — PageRank weights shift based on which files are in the chat and which identifiers the user mentions. It's not a static dump.

### Claude Code — Tools Only, No Repo Map

Claude Code does **not** inject any repo map or project structure summary into context.

**What's injected at session start:**
- Git status (branch, recent commits, modified files)
- CLAUDE.md files (hierarchical: `~/.claude/CLAUDE.md` > project root > subdirectories)
- Memory files from previous sessions
- IDE selection context (if launched from editor)

**Discovery mechanism:** Entirely tool-based:
- `Glob` — file pattern matching (returns paths, not content)
- `Grep` — ripgrep-based content search
- `Read` — file content retrieval
- `Task` subagent — delegated deep exploration

**Philosophy:** "Read and understand existing code before suggesting modifications." Iterative, tool-driven exploration over static context injection.

**CLAUDE.md as pseudo-repo-map:** CLAUDE.md serves a similar *purpose* (orient the agent) but is human-authored, semantic, and small. It describes architecture, conventions, and build commands — not a mechanical listing of symbols. Typically 500-2000 tokens.

### Cursor — Semantic Index, RAG Retrieval

Cursor does **not** pre-load a repo map into the prompt. Instead:

1. **Indexing:** On project open, tree-sitter parses files into AST chunks (functions, classes). Each chunk is embedded into a vector (using an AI model) and stored in Turbopuffer (vector DB).
2. **Query time:** User's prompt is embedded, nearest-neighbor search retrieves semantically relevant chunks.
3. **Context assembly:** Retrieved chunks are injected into the prompt alongside the user's query.

**Key difference from repo map:** Cursor's context is *query-specific* — different questions surface different code. A repo map is *session-static* (same summary regardless of task).

**Team optimization:** 92% codebase similarity across team members enables shared index reuse via Merkle tree hashing.

### Windsurf (Codeium) — RAG + Dynamic Context Layering

Similar to Cursor but with additional layers:

1. **Static context:** Full codebase indexed (including unopened files), M-Query retrieval (improved over basic cosine similarity)
2. **Dynamic context:** Tracks user actions in real-time, assembles prompt from: rules, memories, open files, codebase retrieval, recent actions
3. **Codemaps:** AI-annotated structured maps generated on-demand (not at session start). Two views: structured text with line-number links, and visual diagrams. Referenced in agent prompts via `@{codemap}` syntax.

**Codemaps vs repo maps:** Codemaps are task-specific, generated per-request, and include AI annotations explaining code paths. More expensive but more targeted than Aider's repo map.

### Devin — Background Indexing + DeepWiki

Devin indexes codebases in the background and provides multiple understanding tools:

- **DeepWiki:** Analyzes project structure, source code, config files, docs. Generates dependency graphs and architectural diagrams.
- **Codemaps:** (Acquired Windsurf technology) Shared understanding between human and AI.
- **Devin Search:** Natural language queries against indexed codebase with code citations.

**Approach:** Heavy pre-computation (indexing happens before tasks), but context is *retrieved* per-task, not dumped into prompt.

### SWE-Agent — Pure Tool-Based Discovery

SWE-Agent provides **no repo map**. The agent orients itself entirely through tools:

- `find_file` — locate files by name
- `search_file` — search within a file
- `search_dir` — directory-wide content search (max 50 hits)
- `filemap` — print file contents, skipping lengthy function bodies
- `open` / `scroll_up` / `scroll_down` — file viewer (100 lines per turn)
- Bash — general shell access

**Design philosophy:** Custom tools with constrained output (e.g., max 50 search hits) to prevent overwhelming context. No pre-loaded project understanding.

**Mini-SWE-Agent:** An extreme minimalist variant — only bash, no custom tools at all. Scores >74% on SWE-bench Verified, demonstrating that tool-based discovery alone can be highly effective.

### Google Antigravity — Progressive Disclosure via Skills

Antigravity explicitly chose **NOT** to pre-load project context:

> "Loading every rule or tool into the agent's context leads to higher costs and confusion."

**Solution: Skills** — specialized knowledge packages that sit dormant until needed. Only loaded when the user's request matches the skill's description. This is lazy-loading of context, not pre-loading.

### Augment Code — Context Engine (RAG)

Augment's approach is the most sophisticated RAG system:

- Indexes entire codebase in real-time (including commit history, cross-repo dependencies, architectural patterns)
- **Prompt enhancer:** Turns short requests into context-rich queries, automatically referencing relevant modules
- Smart curation: retrieves only what matters, compresses context
- Available as MCP server for Claude Code, Cursor, etc.

**Key claim:** "A weaker model with great context can outperform a stronger model with poor context."

---

## 2. Taxonomy of Approaches

| Approach | Examples | When Context Is Loaded | Token Cost | Freshness |
|---|---|---|---|---|
| **Static repo map** | Aider | Session start | 1-8K fixed | Stale (generated once) |
| **Human-authored summary** | Claude Code (CLAUDE.md) | Session start | 0.5-2K fixed | Stale (manual updates) |
| **Tool-based discovery** | Claude Code, SWE-Agent | On-demand | Variable (0 to many K) | Always fresh |
| **RAG retrieval** | Cursor, Windsurf, Augment | Per-query | Variable | Near-real-time |
| **On-demand generation** | Windsurf Codemaps | User-triggered | High (LLM-generated) | Fresh per generation |
| **Progressive disclosure** | Google Antigravity | Skill-triggered | Zero until needed | Fresh |

---

## 3. Analysis: Key Questions

### Does a repo map improve task success rate?

**No published ablation data exists.** Aider does not publish with-map vs without-map benchmarks. The claimed benefit is qualitative: fewer wasted tool calls because the LLM knows which files to request.

However, indirect evidence suggests it helps:
- Aider's PageRank-weighted map surfaces the most-connected symbols first, which are statistically most likely to be relevant
- Mini-SWE-Agent (tools only, no map) scores >74% on SWE-bench — proving tools alone are viable
- The trend across tools (Claude Code, SWE-Agent, Antigravity) is away from static maps and toward tool-based or RAG-based discovery

**Conclusion:** Repo maps likely help for *unfamiliar* codebases where the agent has no CLAUDE.md or prior context. For well-documented projects with good CLAUDE.md, the benefit is marginal.

### Token cost: 1500 tokens of map vs 2-3 tool calls?

**Rough comparison:**

| Approach | Input tokens | Output tokens | Total |
|---|---|---|---|
| Repo map (1K default) | 1,024 per turn (cumulative) | 0 | 1,024 * N turns |
| Glob + Read | ~100 (call) + ~500 (result) | ~50 | ~650 per lookup |
| Grep + Read | ~100 (call) + ~300 (result) | ~50 | ~450 per lookup |

A 1K repo map costs 1,024 tokens **every turn** (it's in the system prompt). Over a 20-turn conversation, that's 20,480 tokens spent on the map. Two targeted tool calls might cost ~1,300 tokens total and return *exactly* what's needed.

**However:** Tool calls add latency (round trips) and can fail (wrong glob pattern, grep misses). The map provides immediate orientation without round trips.

**Conclusion:** For short conversations (1-5 turns), the repo map is cheaper. For long conversations (10+ turns), tool-based discovery amortizes better. The pi-coding-agent author achieved <1,000 tokens for system prompt + tools, vs 13-18K for competing agents.

### Does staleness matter?

**For repo maps:** Generated at session start from current files. Stale if files change mid-session (unlikely in most workflows). Aider uses file mtime caching, so the map is current at session start.

**For CLAUDE.md:** Manually maintained. Can become stale if architecture evolves and nobody updates it. But it captures *intent* and *conventions* that code analysis can't infer.

**For RAG:** Near-real-time (Augment indexes on every save). Freshness is a major advantage.

**Conclusion:** Staleness is a minor concern for repo maps (sessions are short). It's a bigger concern for CLAUDE.md (requires discipline to maintain).

### Does a repo map scale to 500+ file projects?

Aider's approach scales through PageRank ranking — only the top-ranked symbols fit within the token budget, so a 500-file project still produces a 1K token map. The *quality* of the map depends on whether the ranking surfaces relevant symbols.

**Risk:** In large projects with many loosely-coupled modules, PageRank may surface "hub" files (utils, constants) rather than domain-relevant files. Aider mitigates this with personalization (weighting files in chat).

**Conclusion:** Scales mechanically, but quality degrades in large, loosely-coupled projects. RAG retrieval handles scale better because it's query-specific.

---

## 4. Recommendations for Our Plugin's Agent

### Context: IntelliJ Plugin with IDE APIs

Our agent runs inside IntelliJ, giving us access to:
- **PSI (Program Structure Interface):** Full AST with type resolution, richer than tree-sitter
- **IntelliJ indexes:** Already built and maintained by the IDE, no extra computation
- **Project model:** Module structure, dependencies, source roots
- **VCS integration:** Changed files, branches, commit history

### Recommended Approach: Hybrid (CLAUDE.md + Lazy Tool Discovery)

**Do NOT pre-load a repo map.** Instead:

1. **CLAUDE.md equivalent (already have it):** Our plugin settings and project description serve as the human-authored orientation. Inject at session start. ~500-1000 tokens.

2. **Targeted tools for discovery:**
   - `list_files(path, pattern)` — glob-based file search using IntelliJ's VFS
   - `search_code(query, scope)` — content search using IntelliJ's index
   - `get_file_structure(path)` — PSI-based: classes, methods, fields for a single file
   - `find_usages(symbol)` — IntelliJ's reference search (far richer than grep)
   - `get_project_structure()` — module list with source roots (lightweight, ~200 tokens)

3. **Optional lazy repo map:** If the agent's first action is always "understand the project," generate a compact summary on first tool call, cache it, and include in subsequent turns. But only if benchmarking shows it reduces tool call count.

4. **Skip RAG for now:** RAG requires vector DB infrastructure. IntelliJ's built-in search is already semantic-aware (understands types, inheritance, references). Use it directly as tools.

### What NOT to do:

- **Don't dump full repo map into every prompt.** The trend across all major tools is away from this.
- **Don't build tree-sitter parsing.** We have PSI, which is strictly superior for JVM languages.
- **Don't ignore the problem.** Pure tool-based discovery without any orientation context wastes the first 3-5 tool calls on basic discovery. A lightweight `get_project_structure()` tool (module list + key entry points) gives the agent a starting point.

---

## 5. Summary Table

| Tool | Pre-loaded context? | Discovery mechanism | Repo map? |
|---|---|---|---|
| **Aider** | Yes (1-8K tokens) | Repo map + chat files | Yes (tree-sitter + PageRank) |
| **Claude Code** | CLAUDE.md only (~1K) | Glob, Grep, Read tools | No |
| **Cursor** | No | RAG vector search | No |
| **Windsurf** | No (RAG at query time) | M-Query retrieval + Codemaps | On-demand only |
| **Devin** | Background index | DeepWiki + Search + Codemaps | Generated, not injected |
| **SWE-Agent** | No | find_file, search_dir, bash | No |
| **Antigravity** | No | Progressive Skills | No |
| **Augment** | No (RAG at query time) | Context Engine + prompt enhancer | No |

**Industry consensus:** The trend is decisively **away from static repo maps** and toward **tool-based discovery** or **query-specific RAG retrieval**. Aider is the only major tool still using a pre-loaded repo map, and it's the oldest approach.

---

## Sources

- [Aider: Building a better repo map with tree-sitter](https://aider.chat/2023/10/22/repomap.html)
- [Aider: Repository map documentation](https://aider.chat/docs/repomap.html)
- [Aider: Repository Mapping System (DeepWiki)](https://deepwiki.com/Aider-AI/aider/4.1-repository-mapping)
- [Aider: Improving GPT-4's codebase understanding with ctags](https://aider.chat/docs/ctags.html)
- [Claude Code: Using CLAUDE.md files](https://claude.com/blog/using-claude-md-files)
- [Claude Code system prompts (leaked)](https://github.com/Piebald-AI/claude-code-system-prompts)
- [How Cursor Indexes Codebases Fast](https://read.engineerscodex.com/p/how-cursor-indexes-codebases-fast)
- [Cursor: Codebase Indexing docs](https://docs.cursor.com/context/codebase-indexing)
- [Windsurf: Context Awareness Overview](https://docs.windsurf.com/context-awareness/overview)
- [Windsurf Codemaps](https://cognition.ai/blog/codemaps)
- [Devin 2.0](https://cognition.ai/blog/devin-2)
- [SWE-agent GitHub](https://github.com/SWE-agent/SWE-agent)
- [SWE-agent tools documentation](https://swe-agent.com/latest/config/tools/)
- [Mini-SWE-Agent](https://github.com/SWE-agent/mini-swe-agent)
- [Google Antigravity developer blog](https://developers.googleblog.com/en/build-with-google-antigravity-our-new-agentic-development-platform/)
- [Augment Code Context Engine](https://www.augmentcode.com/context-engine)
- [How Augment Code Solved the Large Codebase Problem](https://blog.codacy.com/ai-giants-how-augment-code-solved-the-large-codebase-problem)
- [Building an opinionated minimal coding agent (pi-coding-agent)](https://mariozechner.at/posts/2025-11-30-pi-coding-agent/)
- [How System Prompts Define Agent Behavior](https://www.dbreunig.com/2026/02/10/system-prompts-define-the-agent-as-much-as-the-model.html)
