# Plan Persistence Patterns in Production AI Agents

**Date:** 2026-03-20
**Purpose:** Research how production AI agents maintain plan state during long-running tasks, survive context compression, and persist execution progress.

---

## 1. Google Jules (Async Coding Agent)

### Architecture
Jules is an asynchronous, autonomous coding agent powered by Gemini 2.5 Pro. Announced December 2024, beta May 2025, GA August 2025, CLI tools October 2025.

### How Plans Work
- Jules analyzes the codebase and prompt, then generates a **structured plan** showing which files to modify, which functions to touch, and which tests to create.
- The plan is presented for **human review before execution**. Users can edit or approve it.
- Plans are **mutable during execution** -- users can modify, pause, or adjust while Jules works.

### Plan Persistence
- Each task runs in a **temporary cloud VM**. The VM is destroyed after task completion.
- Plans exist **within the task lifecycle only** -- they do not persist across tasks.
- The plan is tied to a single execution context (clone repo -> plan -> execute -> PR).
- **No cross-task plan persistence.** Each new task starts clean.

### Step Tracking
- An **activity feed** shows live logs of what Jules is doing (installing deps, modifying files, running tests).
- Each change is explained in natural language and linked back to the plan step that caused it.
- The plan serves as an **active guide**, not a static document.

### Key Insight
Jules sidesteps the plan persistence problem by keeping tasks short-lived and atomic. The plan lives for the duration of one task execution, and the VM is destroyed afterward. There is no need to survive context compression because each task fits within a single context window.

---

## 2. Google Antigravity (Agent-First IDE)

### Architecture
Announced November 2025. An AI-powered IDE built around autonomous agents powered by Gemini 3.1 Pro and Gemini 3 Flash. The Agent Manager is the product's core -- you supervise agents, not write code.

### Plan Management
Two execution modes:
- **Plan Mode:** Generates a detailed Plan Artifact before acting. Ideal for complex tasks.
- **Fast Mode:** Executes instantly. Ideal for quick fixes.

### Artifact Types
Plans are one of several artifact types:
1. **Task Lists** -- structured plan generated before coding
2. **Implementation Plans** -- technical details on code revisions needed
3. **Walkthroughs** -- summary of changes and how to test them (post-execution)
4. **Screenshots / Browser Recordings** -- UI state capture
5. **Code Diffs** -- reviewable change sets

### Plan Persistence Across Sessions: Knowledge Items (KIs)
This is the key innovation:
- **Knowledge Items (KIs)** are Antigravity's cross-session persistence mechanism.
- Unlike conversation history (session-bound), KIs are **distilled facts that persist indefinitely**.
- At the end of each conversation, a separate **Knowledge Subagent** analyzes the conversation and extracts key information into KIs.
- Each KI has: `metadata.json` (summary, timestamps, references) + `artifacts/` directory (related files, documentation, analysis).
- KIs are stored in the Knowledge directory and **automatically loaded when starting new conversations**.
- Antigravity checks KI summaries at the beginning of every session to avoid redundant work.

### How Plans Survive
- The PLANNING > EXECUTION > VERIFICATION workflow generates artifacts at each phase.
- These artifacts **carry context forward** across sessions.
- Over time, Antigravity builds a **rich knowledge base** about the project.
- The Knowledge Subagent ensures important plan decisions, architectural choices, and progress are extracted and persisted.

### Key Insight
Antigravity solves plan persistence through a **structured knowledge extraction pipeline**. Plans don't survive as raw conversation -- they are distilled into Knowledge Items by a dedicated subagent, then injected into future sessions. This is a "write-once, read-many" pattern for plan state.

---

## 3. Claude Code

### TodoWrite (Legacy, In-Memory)
- A built-in tool that creates and manages task checklists rendered in the terminal UI.
- **In-memory only** -- tasks exist only in conversation memory, no filesystem persistence.
- Task fields: `id`, `content`, `status` (pending/in_progress/completed), `priority`.
- Real-time terminal rendering with visual indicators as Claude works.
- **Session-isolated** -- each session has its own task list, discarded when session ends.
- System prompt instructs: "Use TodoWrite to break down and track work progress."
- Only used for non-trivial requests; simple tasks skip it entirely.

### Tasks API (v2.1.16+, Persistent)
- Introduced in 2025, replaces TodoWrite for persistent workflows.
- Tasks persist in **`~/.claude/tasks/`** as JSONL files.
- `CLAUDE_CODE_TASK_LIST_ID` environment variable determines which file to use.
- Supports **dependency tracking** via `addBlockedBy` parameter.
- **Cross-session:** broadcasts updates across sessions.
- Task chaining: marking Task 1 complete makes Task 2 available.

### Session Storage
- Sessions stored at `~/.claude/projects/<encoded-cwd>/*.jsonl`.
- JSONL format -- each message is one line, appended incrementally.
- Messages written to disk immediately (no buffering until session end).
- Known issue: large sessions (3.8 GB+) can cause memory explosion.

### Context Compaction
- **Automatic:** Triggers at ~95% context window capacity (25% remaining).
- **Manual:** `/compact` command with optional custom prompt to direct summarization focus.
- Process: summarizes entire conversation history -> starts new session with summary preloaded.
- **What survives:** High-level decisions, recent code changes, general trajectory.
- **What is lost:** Verbatim earlier exchanges, nuanced reasoning chains, specific phrasing.
- Customizable via CLAUDE.md: "When compacting, always preserve the full list of modified files."

### How Plans Survive Compression
- TodoWrite tasks are **in-memory and vulnerable to compression**. After compaction, the todo state may be summarized away.
- Tasks API tasks persist on disk and can be re-read, but the agent must know to look for them.
- The primary survival mechanism is **CLAUDE.md instructions** that tell the compaction process what to preserve.
- No formal "anchored context" mechanism -- plan preservation depends on the quality of the compression summary.

### Key Insight
Claude Code's plan persistence has two tiers: in-memory TodoWrite (fragile, lost on compaction) and on-disk Tasks API (durable, but requires the agent to re-read). The gap is that neither tier provides automatic "always in context" plan awareness after compression. CLAUDE.md customization is the main lever for ensuring critical information survives.

---

## 4. Anthropic Multi-Agent Researcher Pattern

### The Problem
Large research tasks easily exceed the 200K token context limit.

### The Solution: External Memory for Plans
- The **LeadResearcher** begins by thinking through the approach and **saving its plan to Memory**.
- This is critical because if the context window exceeds 200K tokens, it will be truncated.
- By saving the plan externally, the agent can **retrieve it from memory** rather than losing it when tokens run out.
- Agents summarize completed work phases and store essential information in external memory before proceeding.
- Fresh subagents can be spawned with clean contexts while maintaining continuity through careful handoffs.

### Key Insight
Anthropic's own production system treats plan persistence as a **first-class concern**. The plan is explicitly saved to an external memory store, not left in the conversation context. This is the canonical pattern for surviving context truncation.

---

## 5. General Patterns in Agentic AI

### 5.1 The Scratchpad Pattern
- A designated area of the prompt context that accumulates thoughts and observations for the current task.
- **In-context scratchpad:** Lives in the conversation, vulnerable to compression.
- **File-based scratchpad:** Agent writes plan/state to a file via tool call, reads it back when needed. Survives compression because it exists outside the context window.
- Management is critical: dumping raw error logs into the scratchpad confuses the model. Effective orchestration summarizes observations before feeding them back.

### 5.2 Checkpoint-Based State Persistence (LangGraph)
- Every node in the execution graph saves state automatically.
- Creates a complete audit trail: `agent.get_state_history(config)`.
- Supports time-travel debugging: `agent.update_state(checkpoint, new_values)`.
- **Durable execution:** Process saves progress at key points, can pause and resume exactly where it left off.

### 5.3 Factory.ai Anchored Iterative Summarization
The most sophisticated compression-survival pattern found:
- Maintains a **structured, persistent summary** with explicit sections: session intent, file modifications, decisions made, next steps.
- When compression triggers, only the newly-truncated span is summarized and **merged** with the existing summary (not regenerated from scratch).
- **Structure forces preservation:** dedicated sections cannot silently drop information. Each section acts as a checklist.
- Scored 3.70 overall vs. 3.44 (Anthropic) and 3.35 (OpenAI) in evaluation across 36,000+ production messages.
- **Context awareness gap:** Factory 4.01 vs. Anthropic 3.56 -- the 0.45 point advantage comes from anchored iterative merging preventing detail drift across compression cycles.

### 5.4 Multi-Layer Memory Taxonomy
Production agents typically implement 3+ memory layers:

| Layer | Scope | Persistence | Example |
|-------|-------|-------------|---------|
| Working Memory | Current task | In-context (volatile) | TodoWrite, scratchpad |
| Session Memory | Current session | Disk (JSONL) | Claude Code session files |
| Episodic Memory | Cross-session | Database/files | Antigravity Knowledge Items |
| Semantic Memory | Permanent | Indexed store | Project conventions, user preferences |

### 5.5 Agentic Context Engineering (ACE)
- Treats contexts as **evolving playbooks** that accumulate, refine, and organize strategies.
- Modular process: generation -> reflection -> curation.
- Prevents collapse with structured, incremental updates.
- Scales with long-context models.

---

## 6. Comparison Matrix

| Capability | Jules | Antigravity | Claude Code (TodoWrite) | Claude Code (Tasks API) | Anthropic Multi-Agent |
|---|---|---|---|---|---|
| Plan storage | In-memory (task VM) | Artifacts + KIs on disk | In-memory only | JSONL on disk | External memory store |
| Survives compression | N/A (short tasks) | Yes (KI extraction) | No | Yes (on disk, must re-read) | Yes (explicit save) |
| Cross-session | No | Yes (KIs) | No | Yes | Yes |
| Auto-awareness after compression | N/A | Yes (KIs auto-loaded) | No | Partial (must re-read) | Yes (retrieval-based) |
| UI visibility | Activity feed + plan view | Agent Manager dashboard | Terminal checklist | Terminal checklist | N/A (infrastructure) |
| Human-editable | Yes (before/during) | Yes (plan review) | No | No | No |
| Dependency tracking | Implicit (sequential) | Task list ordering | No | Yes (addBlockedBy) | Implicit (handoffs) |

---

## 7. Recommendations for Phase 3 Agentic AI

Based on this research, the following patterns are most relevant for our plugin's agentic AI architecture:

### 7.1 File-Based Plan Persistence (High Priority)
**Pattern:** Agent writes its execution plan to a file at the start of a task, re-reads it after any context compression event.
- Inspired by: Anthropic Multi-Agent Researcher, file-based scratchpad pattern.
- Implementation: Agent saves plan as structured JSON/YAML to a known path (e.g., `.workflow/agent-plan.json`).
- The plan file acts as ground truth that survives any compression.

### 7.2 Anchored Iterative Summarization (High Priority)
**Pattern:** When compressing context, maintain a structured summary with explicit sections that are merged incrementally rather than regenerated.
- Inspired by: Factory.ai's approach.
- Implementation: Define fixed sections (intent, progress, decisions, next steps, file modifications) that the compression routine must populate.
- Prevents silent information loss during compression cycles.

### 7.3 Knowledge Item Extraction (Medium Priority)
**Pattern:** At the end of each agent session, extract and persist key decisions, patterns, and outcomes.
- Inspired by: Antigravity's Knowledge Subagent.
- Implementation: Post-session analysis that distills conversation into structured facts.
- Enables cross-session continuity without carrying full conversation history.

### 7.4 Two-Tier Plan State (Medium Priority)
**Pattern:** Maintain plan state in both working memory (fast access) and persistent storage (compression-safe).
- Inspired by: Claude Code's TodoWrite + Tasks API split.
- Implementation: In-context plan for active execution + on-disk plan for recovery.
- Agent checks for on-disk plan at session start and after compression events.

### 7.5 Plan-as-Artifact (Lower Priority)
**Pattern:** Treat the execution plan as a first-class artifact with its own lifecycle (created, reviewed, executing, completed).
- Inspired by: Antigravity's artifact system, Jules' plan review flow.
- Implementation: Plan displayed in separate UI panel, human-reviewable and editable.

---

## Sources

### Google Jules
- [Google's AI coding agent Jules is now out of beta | TechCrunch](https://techcrunch.com/2025/08/06/googles-ai-coding-agent-jules-is-now-out-of-beta/)
- [Jules - An Autonomous Coding Agent](https://jules.google)
- [Agentic AI Coding with Google Jules | KDnuggets](https://www.kdnuggets.com/agentic-ai-coding-with-google-jules)
- [Meet Jules Tools | Google Developers Blog](https://developers.googleblog.com/en/meet-jules-tools-a-command-line-companion-for-googles-async-coding-agent/)
- [Practical Agentic Coding with Google Jules | MLMastery](https://machinelearningmastery.com/practical-agentic-coding-with-google-jules/)

### Google Antigravity
- [Build with Google Antigravity | Google Developers Blog](https://developers.googleblog.com/build-with-google-antigravity-our-new-agentic-development-platform/)
- [Antigravity Architecture Deep Dive | SmartScope](https://smartscope.blog/en/generative-ai/google-gemini/antigravity-architecture-deep-dive/)
- [Context Management Strategies for Google Antigravity | Iceberg Lakehouse Blog](https://iceberglakehouse.com/posts/2026-03-context-google-antigravity/)
- [Google Antigravity Agent Manager Explained | Arjan KC](https://www.arjankc.com.np/blog/google-antigravity-agent-manager-explained/)
- [Google Antigravity Explained | SkyWork](https://skywork.ai/blog/google-antigravity-agentic-architecture-ai-workflow/)

### Claude Code
- [Todo Lists | Claude API Docs](https://platform.claude.com/docs/en/agent-sdk/todo-tracking)
- [Claude Code Task Management | ClaudeFast](https://claudefa.st/blog/guide/development/task-management)
- [Compaction | Claude API Docs](https://platform.claude.com/docs/en/build-with-claude/compaction)
- [Session Memory Compaction | Claude Developer Platform](https://platform.claude.com/cookbook/misc-session-memory-compaction)
- [Claude Code System Prompts | Piebald-AI](https://github.com/Piebald-AI/claude-code-system-prompts/blob/main/system-prompts/tool-description-todowrite.md)
- [Tasks API vs TodoWrite | DeepWiki](https://deepwiki.com/FlorianBruniaux/claude-code-ultimate-guide/8.1-session-management-commands)
- [How Claude Code Manages Local Storage | Milvus Blog](https://milvus.io/blog/why-claude-code-feels-so-stable-a-developers-deep-dive-into-its-local-storage-design.md)
- [TodoWrite Proactive Planning Behavior | GitHub Issue #6968](https://github.com/anthropics/claude-code/issues/6968)

### Anthropic Multi-Agent Research
- [How we built our multi-agent research system | Anthropic](https://www.anthropic.com/engineering/multi-agent-research-system)

### Context Engineering & Compression
- [Evaluating Context Compression for AI Agents | Factory.ai](https://factory.ai/news/evaluating-compression)
- [Compressing Context | Factory.ai](https://factory.ai/news/compressing-context)
- [Context Engineering for Agents | Lance Martin](https://rlancemartin.github.io/2025/06/23/context_engineering/)
- [Agentic Context Engineering | arXiv](https://arxiv.org/abs/2510.04618)

### Agent Memory Patterns
- [Practical Memory Patterns for Agent Workflows | AIS](https://www.ais.com/practical-memory-patterns-for-reliable-longer-horizon-agent-workflows/)
- [Agentic Memory Patterns & Context Engineering | Medium](https://medium.com/@chetankerhalkar/agentic-memory-patterns-context-engineering-the-real-os-of-ai-agents-614cf0cf98b3)
- [Memory in AI Agents: Taxonomies & Directions | arXiv](https://www.emergentmind.com/papers/2512.13564)
- [Checkpoint-Based State Replay with LangGraph | DEV Community](https://dev.to/sreeni5018/debugging-non-deterministic-llm-agents-implementing-checkpoint-based-state-replay-with-langgraph-5171)
- [Durable Execution | LangChain Docs](https://docs.langchain.com/oss/python/langgraph/durable-execution)
