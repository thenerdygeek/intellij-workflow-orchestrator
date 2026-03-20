# Sequential Thinking & Memory in Agentic AI Systems

**Date:** 2026-03-20
**Type:** Research (no code changes)
**Context:** Phase 3 Agentic AI foundation for IntelliJ plugin

---

## 1. Anthropic's MCP Servers

### 1.1 Sequential Thinking MCP Server

**What it is:** An official reference MCP server published by Anthropic at
`@modelcontextprotocol/server-sequential-thinking` in the
[modelcontextprotocol/servers](https://github.com/modelcontextprotocol/servers/tree/main/src/sequentialthinking)
monorepo. MIT licensed. TypeScript.

**How it works:** The server exposes a single tool called `sequentialthinking` with these parameters:

| Parameter | Type | Required | Purpose |
|---|---|---|---|
| `thought` | string | yes | The current thinking step |
| `nextThoughtNeeded` | boolean | yes | Whether more thinking is required |
| `thoughtNumber` | integer | yes | Position in sequence (1-indexed) |
| `totalThoughts` | integer | yes | Estimated total thoughts needed |
| `isRevision` | boolean | no | Indicates revising a previous thought |
| `revisesThought` | integer | no | Which thought number is being reconsidered |
| `branchFromThought` | integer | no | Branching point thought number |
| `branchId` | string | no | Branch identifier |
| `needsMoreThoughts` | boolean | no | If more thoughts needed despite apparent completion |

**Critical finding: The server does ZERO computation.** The `SequentialThinkingServer` class
in `lib.ts` is a pure data store. The `processThought()` method:

1. Pushes the thought to a `thoughtHistory` array
2. Tracks branch relationships in a `branches` record
3. Auto-adjusts `totalThoughts` if `thoughtNumber` exceeds it
4. Formats output with chalk for console display
5. Returns metadata (thought numbers, branch IDs, history length)

It performs no transformation, no reasoning, no analysis on the input. It is a **structured
scratchpad** that gives the LLM a tool to call for organizing its own reasoning.

**What problem it solves:** It forces the LLM to externalize its reasoning into discrete,
numbered, revisable steps rather than generating a single monolithic response. The tool-call
mechanism means the LLM must pause, structure a thought, then decide whether to continue.
This creates a natural checkpoint system.

**Status:** Official Anthropic reference implementation, but it's a *reference* server in the
MCP examples repo, not a built-in feature of any Anthropic product. Users must install and
configure it themselves.

### 1.2 Memory (Knowledge Graph) MCP Server

**What it is:** An official reference MCP server at
`@modelcontextprotocol/server-memory` in the same
[modelcontextprotocol/servers](https://github.com/modelcontextprotocol/servers/tree/main/src/memory)
monorepo. MIT licensed. TypeScript.

**How it works:** Exposes 9 tools for knowledge graph manipulation:

| Tool | Purpose |
|---|---|
| `create_entities` | Create new entities (nodes) |
| `create_relations` | Create directed relations between entities |
| `add_observations` | Add observation strings to existing entities |
| `delete_entities` | Remove entities and their relations |
| `delete_observations` | Remove specific observations |
| `delete_relations` | Remove specific relations |
| `read_graph` | Read entire knowledge graph |
| `search_nodes` | Case-insensitive search across names, types, observations |
| `open_nodes` | Retrieve specific entities by name + connected relations |

**Data model:**
- **Entities:** `{ name, entityType, observations[] }` - primary nodes
- **Relations:** `{ from, to, relationType }` - directed edges in active voice
- **Observations:** String facts attached to entities

**Persistence:** JSONL file (one JSON object per line). Default location is `memory.jsonl`
in the server directory. Configurable via `MEMORY_FILE_PATH` env var. Backward-compatible
migration from legacy `memory.json` format.

**What problem it solves:** Gives an LLM persistent memory across conversations. The LLM
can store entities (people, projects, preferences) with observations, create relationships
between them, and search/retrieve later. It's a simple but effective way to build up a
knowledge base over time.

**Status:** Official reference implementation. Not built into any Anthropic product.

### 1.3 Key Distinction

Both servers are **official Anthropic reference implementations** in the MCP specification
repo. They are NOT built into Claude, Claude Code, or any Anthropic product. They are
examples of what you can build with MCP, published to help the ecosystem.

---

## 2. Sequential Thinking: Deep Analysis

### 2.1 What Is Sequential Thinking in LLM Agent Context?

Sequential thinking refers to **externalizing the LLM's reasoning process into discrete,
tool-mediated steps** rather than generating reasoning inline in a single response. The key
mechanism is that the LLM must make a tool call for each thought step, creating natural
pause points where it can:

- Review what it has concluded so far
- Decide if a revision is needed
- Branch into alternative reasoning paths
- Determine if the current conclusion is sufficient

### 2.2 Is It Just Chain-of-Thought by Another Name?

**No, but the difference is subtle and the value is debatable.**

| Aspect | Chain-of-Thought (CoT) | Sequential Thinking MCP | Think Tool |
|---|---|---|---|
| Where reasoning happens | Inline in the response | Via tool calls to external server | Via tool call (no external server) |
| Who controls it | Prompt engineering | LLM decides to call tool | LLM decides to call tool |
| Visibility | Visible in response text | Visible as tool call/response pairs | Visible as tool call (to developer) |
| Revision support | No formal mechanism | Explicit revision/branch parameters | No formal mechanism |
| Persistence | None (response only) | Server stores history | None (appended to conversation log) |
| Overhead | None | Tool call round-trip per step | Tool call round-trip per step |
| Works without Claude | Yes (any LLM) | Yes (any MCP client) | Yes (any tool-using LLM) |

**The real difference:** CoT is text in a response. Sequential thinking MCP forces a
tool-call loop, which means the LLM must commit to a structured schema per step and
explicitly decide "am I done?" This can prevent premature conclusions, but it also
adds token overhead.

### 2.3 Anthropic's "Think" Tool: The Simpler Alternative

Anthropic published a [blog post](https://www.anthropic.com/engineering/claude-think-tool)
describing a much simpler approach: a `think` tool that is just:

```json
{
  "name": "think",
  "description": "Use the tool to think about something. It will not obtain new information or change the database, but just append the thought to the log.",
  "input_schema": {
    "type": "object",
    "properties": {
      "thought": { "type": "string", "description": "A thought to think about." }
    },
    "required": ["thought"]
  }
}
```

This is even simpler than the sequential thinking MCP. No history, no branching, no
revision tracking. Just "pause and think before acting."

**Measured performance improvements:**
- Airline domain (complex multi-step): **54% improvement** (0.570 vs 0.370 baseline)
- Retail domain (simpler): **3.7% improvement** (0.812 vs 0.783 baseline)
- SWE-Bench: **1.6% average improvement** (statistically significant, p < .001)

**Anthropic's recommendation:** Use the think tool for:
- Complex tool chains where output analysis matters
- Policy-heavy environments with detailed guidelines
- Sequential decisions where each step builds on previous ones
- Multi-step decisions where mistakes are costly

Skip it for:
- Non-sequential single/parallel tool calls
- Simple instruction-following without constraints

### 2.4 Extended Thinking vs Sequential Thinking vs Think Tool

| Feature | Extended Thinking | Think Tool | Sequential Thinking MCP |
|---|---|---|---|
| When it runs | Before response generation | During response generation | During response generation |
| Visibility | Summary only (thinking hidden) | Tool call visible to developer | Tool calls visible |
| Implementation | Native model feature | Simple tool definition | External MCP server |
| Overhead | Compute time (billed) | Token cost per call | Token cost + server |
| Best for | Deep reasoning, math, code | Multi-step tool chains | Transparent, revisable plans |
| Revision/branching | No | No | Yes (formal support) |

### 2.5 Does Claude Code Use Sequential Thinking?

**No.** Claude Code does NOT use the sequential thinking MCP server internally. Claude Code
uses:
- Extended thinking (native to the model)
- Its own built-in tool set (Read, Edit, Bash, Grep, etc.)
- Its own memory system (CLAUDE.md + auto memory)

Users CAN configure the sequential thinking MCP server as an external tool in Claude Code,
but it's not built in or recommended by default.

### 2.6 Do Other Tools Use Sequential Thinking?

- **Cursor:** No built-in sequential thinking. Uses rules files + codebase embeddings.
- **Devin:** No explicit sequential thinking tool. Uses internal planning + playbooks.
- **OpenAI Agents SDK:** Has "sequential problem decomposition" as a pattern, not a tool.
  Uses chain-of-thought reasoning built into the model.
- **Google Antigravity:** No explicit sequential thinking tool. Uses multi-agent
  orchestration with knowledge items.
- **Windsurf:** No explicit sequential thinking tool. Uses Cascade agent with memories.

**None of the major agentic IDE tools use a dedicated sequential thinking tool.** They all
rely on the LLM's native reasoning capabilities, sometimes augmented with extended thinking
or chain-of-thought prompting.

### 2.7 When Is Sequential Thinking Needed vs Overkill?

**Needed when:**
- The agent must create a multi-step plan that requires revision as new info emerges
- Transparency of reasoning is critical (auditing, debugging)
- The task involves branching decision trees (e.g., "if build fails, try X; if X fails, try Y")
- The LLM tends to "rush" to conclusions on complex tasks

**Overkill when:**
- The LLM already has extended thinking / native reasoning
- The task is straightforward tool usage (read file, edit file, run test)
- Token budget is tight (each thought step costs tokens)
- The agent already has a plan persistence mechanism

**Key insight:** For modern reasoning models (Claude with extended thinking, o3, Gemini 3
with thinking), a dedicated sequential thinking tool adds marginal value for most coding
tasks. The 2025 Wharton research found that CoT prompting provides only "modest average
improvements but increased variability" for non-reasoning models, and "marginal benefits
despite substantial time costs" for reasoning models.

---

## 3. Memory Systems: Deep Analysis

### 3.1 Claude Code's Memory System

Claude Code does NOT use the MCP memory server. It has its own built-in two-layer system:

**Layer 1: CLAUDE.md files (user-written)**
- Project: `./CLAUDE.md` or `./.claude/CLAUDE.md` (committed to git)
- User: `~/.claude/CLAUDE.md` (personal, all projects)
- Organization: `/Library/Application Support/ClaudeCode/CLAUDE.md` (IT-managed)
- Rules: `.claude/rules/*.md` (modular, path-scoped)
- Loaded at session start, consumed as context tokens
- Can import other files via `@path/to/file` syntax
- Recommendation: under 200 lines per file

**Layer 2: Auto memory (Claude-written)**
- Location: `~/.claude/projects/<project>/memory/`
- `MEMORY.md` index + topic files (debugging.md, patterns.md, etc.)
- First 200 lines of MEMORY.md loaded at session start
- Topic files loaded on-demand when Claude needs them
- Machine-local, never committed to git
- Claude decides what to remember (build commands, debugging insights, preferences)

**Loading order:** Managed policy -> Project CLAUDE.md -> User CLAUDE.md -> Auto MEMORY.md

**Key design choices:**
- Plain markdown files (human-readable, human-editable)
- File-based (no database, no knowledge graph)
- Hierarchical scoping (org > project > user > auto)
- Size-limited injection (200 lines for MEMORY.md)
- On-demand loading for detailed topic files

### 3.2 Cursor's Memory System

- **Rules:** `.cursor/rules/*.mdc` files (replaced deprecated `.cursorrules`)
- **Memories:** Introduced mid-2025, removed in v2.1.x. Rules are now the only built-in
  persistent memory.
- **Codebase indexing:** Vector embeddings computed locally, cosine similarity search for
  relevant code chunks. This is "memory" of the codebase, not user preferences.
- **MCP support:** Can use external MCP servers including the memory server.

**Status:** Cursor effectively has project rules + codebase embeddings. No auto-memory.

### 3.3 Devin's Memory System

- **Knowledge:** A collection of tips, instructions, and organizational context stored in
  Settings & Library. Devin automatically recalls relevant knowledge as needed.
- **Playbooks:** Custom system prompts for repeated tasks. Communicate instructions upfront
  to minimize back-and-forth.
- **Knowledge management:** Create, update, delete, organize into folders, review suggestions.
- **Session search:** Full search across past sessions (shell, file, browser, git, MCP activity).

**Key design:** Centralized knowledge base with automatic retrieval. Playbooks are
task-specific instruction templates. Session history is searchable.

### 3.4 Google Antigravity's Memory System

- **Knowledge Items:** Stored in `.gemini/antigravity/brain/` directory in project root.
- **Agent learning:** When an agent learns a preference or makes a decision, it records an
  artifact in the "brain" directory.
- **Shared knowledge base:** Agents both retrieve from and contribute to the knowledge base.
- **Knowledge Subagent:** Dedicated agent that manages the knowledge base.
- **Persistence:** Project-local, file-based.

**Key design:** "Learning as a core primitive." Agents actively contribute to a shared
knowledge base, not just consume it. This is the most aggressive approach to agent learning
among current tools.

### 3.5 Windsurf's Memory System

- **Memories:** Auto-generated by Cascade agent, stored in `~/.codeium/windsurf/memories/`.
  Workspace-scoped, not committed to repo.
- **Rules:** User-defined, stored in `.windsurf/rules/` or `AGENTS.md`.
- **Context assembly pipeline:** Rules -> Memories -> Open files -> Codebase retrieval ->
  Recent actions -> Final prompt.

**Key design:** Very similar to Claude Code's approach. Auto-generated memories + user rules.

### 3.6 OpenAI Agents SDK Memory

- **Short-term:** Session-based. Same `session_id` = agent remembers. New ID = fresh.
- **Structured state:** `RunContextWrapper` with typed state objects that persist across runs.
  Global vs session precedence. Supports belief updates, not just fact accumulation.
- **Long-term:** Expected to be added externally. SDK handles short-term cleanly but expects
  durable memory, retrieval, and personalization layers to be built by developers.

**Key design:** Typed, structured state (not free-form text). Developer builds the
persistence layer.

### 3.7 Comparison Matrix

| Tool | User Rules | Auto Memory | Knowledge Graph | Codebase Index | Session Persistence | Cross-Session Search |
|---|---|---|---|---|---|---|
| Claude Code | CLAUDE.md + rules/ | MEMORY.md + topics | No | No (uses tools) | No (fresh each time) | No |
| Cursor | .cursor/rules/*.mdc | Removed | No | Yes (embeddings) | No | No |
| Devin | Knowledge + Playbooks | Knowledge suggestions | No | Yes | Yes (session search) | Yes |
| Antigravity | .gemini rules | brain/ artifacts | No | Yes | Yes | Yes |
| Windsurf | .windsurf/rules/ | ~/.codeium/memories/ | No | Yes (embeddings) | No | No |
| MCP Memory Server | N/A | N/A | Yes (entities/relations) | No | Yes (JSONL) | Yes (search_nodes) |

**Notable:** No major tool uses a knowledge graph for memory. All use flat files
(markdown or JSON). The MCP memory server's knowledge graph approach is more sophisticated
than what any production tool actually uses.

---

## 4. Analysis for Our Use Case

### 4.1 Our Current State

Our IntelliJ plugin agent already has:

| Capability | Implementation | Status |
|---|---|---|
| Plan persistence | `plan.json` + anchored summary | Designed |
| Conversation persistence | JSONL file per session | Designed |
| Context compression | LLM-powered (two-threshold) | Designed |
| Session history | List of past sessions | Designed |
| Tool results | Structured JSON responses | Designed |
| Cody integration | JSON-RPC over stdio | Implemented |

Our context window: 150K+ input tokens (Cody Enterprise with Claude).

### 4.2 Do We Need Sequential Thinking?

**Recommendation: No dedicated sequential thinking tool. Use the "think" tool pattern instead.**

Rationale:

1. **The sequential thinking MCP server is a structured scratchpad.** It does no computation.
   Any value comes from forcing the LLM to structure its thoughts - but our plan.json already
   does this. When the agent creates a plan with steps, that IS sequential thinking.

2. **Our plan persistence already provides revision/branching.** The plan has phases, steps,
   and can be updated as execution progresses. This is functionally equivalent to the
   sequential thinking server's revision/branching, but integrated with our actual workflow.

3. **The "think" tool pattern is free and effective.** We can add a simple `think` tool to
   our agent's tool set:
   ```
   think(thought: String) -> "Thought recorded."
   ```
   This gives the LLM a way to pause and reason during complex tool chains without the
   overhead of an external server. Anthropic's data shows 54% improvement on complex tasks.

4. **Token budget matters.** With a 150K context window serving both conversation and code
   context, we can't afford to waste tokens on verbose sequential thinking steps for routine
   operations. The think tool is cheaper (one call vs many).

5. **No production agentic IDE uses dedicated sequential thinking.** Claude Code, Cursor,
   Devin, Antigravity, Windsurf - none of them use it. They all rely on the LLM's native
   reasoning plus good prompting.

**Action:** Add a simple `think` tool to the agent's tool set. Cost: trivial. Benefit:
gives the LLM a structured pause mechanism during complex multi-tool operations.

### 4.3 Do We Need More Memory Beyond What We Have?

**Recommendation: Our current design is sufficient. Consider one enhancement.**

What we have vs what others have:

| Feature | Us (designed) | Claude Code | Devin | Best Practice |
|---|---|---|---|---|
| Session context | JSONL conversation log | Fresh each session | Full session history | Session persistence |
| Cross-session memory | Session history list | MEMORY.md auto-memory | Knowledge base | Some form of learning |
| Plan persistence | plan.json + summary | N/A (no plans) | Playbooks | Plan files |
| User preferences | Plugin settings | CLAUDE.md + rules | Knowledge | Settings + rules |
| Codebase awareness | PSI + tool results | File tools | Codebase index | Index or tools |
| Context compression | LLM-powered | /compact command | Internal | Compression |

**What we're missing that would add value:**

1. **Cross-session learning (low priority).** After completing a task, the agent could save
   learnings like "this project's tests require Redis running" or "the Bamboo API returns
   XML not JSON for this endpoint." However, with 150K context and our existing plan
   persistence + session history, this is a nice-to-have, not a necessity. The user can
   configure preferences in plugin settings.

2. **Knowledge graph (not needed).** No production tool uses one. Flat files are simpler,
   human-readable, and sufficient. Our plan.json + JSONL already provides structured
   persistence without the complexity of entity/relation management.

**Action:** No changes needed for Phase 3 foundation. If we later find the agent repeating
mistakes or forgetting project-specific patterns across sessions, we can add a simple
auto-memory file (markdown, like Claude Code does) with minimal effort.

### 4.4 ROI Analysis

| Enhancement | Implementation Cost | Expected Benefit | ROI |
|---|---|---|---|
| Think tool | ~1 hour (add tool definition) | 1-54% improvement on complex tasks (Anthropic data) | **HIGH** |
| Sequential thinking MCP | ~1 week (server + integration) | Marginal over plan.json + think tool | **LOW** |
| Knowledge graph memory | ~2 weeks (entity model + persistence + retrieval) | Marginal over plan.json + session history | **LOW** |
| Auto-memory file | ~2 days (save/load markdown learnings) | Useful after many sessions, not critical for MVP | **MEDIUM** |
| Better plan persistence | Already designed | Core to agent functionality | **Already planned** |

### 4.5 Recommendations Summary

1. **Add a `think` tool** to the agent tool set. Trivial cost, proven benefit. This is the
   only "sequential thinking" mechanism we need.

2. **Keep plan.json as our primary structured reasoning mechanism.** It already provides
   plan creation, step tracking, revision, and persistence - which is what sequential
   thinking is trying to achieve.

3. **Do not implement the MCP memory server or a knowledge graph.** No production tool uses
   this approach. Our plan.json + JSONL conversation log + session history provide sufficient
   persistence.

4. **Defer auto-memory to post-MVP.** If users report the agent forgetting project patterns,
   add a simple markdown-based learning file. But our 150K context window + existing
   persistence should be sufficient initially.

5. **Do not implement the sequential thinking MCP server.** It's a structured scratchpad that
   adds no value beyond what our plan persistence + think tool already provide.

---

## Sources

### Anthropic Official
- [Sequential Thinking MCP Server - GitHub](https://github.com/modelcontextprotocol/servers/tree/main/src/sequentialthinking)
- [Sequential Thinking source code (index.ts)](https://github.com/modelcontextprotocol/servers/blob/main/src/sequentialthinking/index.ts)
- [Memory MCP Server - GitHub](https://github.com/modelcontextprotocol/servers/tree/main/src/memory)
- [The "think" tool: Enabling Claude to stop and think - Anthropic Engineering](https://www.anthropic.com/engineering/claude-think-tool)
- [How Claude remembers your project - Claude Code Docs](https://code.claude.com/docs/en/memory)
- [Memory tool - Claude API Docs](https://platform.claude.com/docs/en/agents-and-tools/tool-use/memory-tool)

### Analysis & Deep Dives
- [Sequential vs Extended Thinking in Claude AI - Arsturn](https://www.arsturn.com/blog/sequential-vs-extended-thinking-in-claude-whats-the-difference)
- [Mastering Complex Reasoning with Sequential Thinking MCP - Skywork](https://skywork.ai/skypage/en/An-AI-Engineer's-Deep-Dive-Mastering-Complex-Reasoning-with-the-sequential-thinking-MCP-Server-and-Claude-Code/1971471570609172480)
- [Anthropic's Sequential Thinking MCP - Trevor Lasn](https://www.trevorlasn.com/blog/anthropic-sequential-thinking-mcp)
- [Knowledge Graph Memory Server Deep Dive - Skywork](https://skywork.ai/skypage/en/anthropics-knowledge-graph-memory/1977576322715676672)
- [Decreasing Value of CoT in Prompting - Wharton](https://gail.wharton.upenn.edu/research-and-insights/tech-report-chain-of-thought/)

### Competitor Memory Systems
- [Cursor AI Memory Guide](https://www.blockchain-council.org/ai/cursor-ai-track-memory-across-conversations/)
- [Devin 2.0 - Cognition](https://cognition.ai/blog/devin-2)
- [Google Antigravity - Developers Blog](https://developers.googleblog.com/build-with-google-antigravity-our-new-agentic-development-platform/)
- [Cascade Memories - Windsurf Docs](https://docs.windsurf.com/windsurf/cascade/memories)
- [OpenAI Agents SDK - Context Personalization](https://developers.openai.com/cookbook/examples/agents_sdk/context_personalization)

### Memory Architecture
- [6 Best AI Agent Memory Frameworks 2026 - MLMastery](https://machinelearningmastery.com/the-6-best-ai-agent-memory-frameworks-you-should-try-in-2026/)
- [Agentic AI Scaling Requires New Memory Architecture - AI News](https://www.artificialintelligence-news.com/news/agentic-ai-scaling-requires-new-memory-architecture/)
- [Best Agentic IDEs 2026 - Builder.io](https://www.builder.io/blog/agentic-ide)
- [Claude Code Memory Explained - Substack](https://joseparreogarcia.substack.com/p/claude-code-memory-explained)
- [Claude Memory Guide 3-Layer Architecture - ShareUHack](https://www.shareuhack.com/en/posts/claude-memory-feature-guide-2026)
