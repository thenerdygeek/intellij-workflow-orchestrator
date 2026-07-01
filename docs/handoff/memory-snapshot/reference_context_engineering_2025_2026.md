# Context Engineering & Management for AI Agents (2025-2026 Research)

Research compiled: 2026-03-31

---

## 1. CORE CONCEPT: Context Engineering vs Prompt Engineering

**Source:** [Anthropic - Effective Context Engineering for AI Agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) (Sep 2025)

Context engineering = the discipline of curating ALL tokens (system prompts, tools, retrieved docs, message history, tool outputs) to maximize desired model behavior. Unlike prompt engineering (finding the right words), CE answers: "What configuration of context is most likely to generate our model's desired behavior?"

**Key principle:** Find the smallest set of high-signal tokens that maximize likelihood of desired outcome.

**Industry adoption:** Per LangChain's 2025 State of Agent Engineering, 57% of organizations have AI agents in production, 32% cite quality as top barrier, most failures traced to poor context management (not LLM capabilities).

---

## 2. CONTEXT ROT (Attention Degradation)

**Source:** [Chroma Research - Context Rot](https://www.trychroma.com/research/context-rot) (2025)

Tested 18 frontier models. Key findings:
- Performance follows U-shaped curve: models attend strongly to beginning/end, poorly to middle
- U-shaped pattern only persists when context < 50% full; beyond that, models favor recent tokens
- Context rot is architectural (transformer quadratic attention), not a training gap
- At 100K tokens = 10 billion pairwise relationships; softmax means each weight shrinks
- Even single distractors reduce performance vs baseline
- Models perform BETTER on shuffled haystacks than logically structured ones
- Claude models: lowest hallucination rates; GPT models: highest

**Impact:** 65% of enterprise AI failures in 2025 attributed to context drift/memory loss during multi-step reasoning. At 95% per-step reliability over 20 steps, combined success = 36%.

---

## 3. ANTHROPIC'S CONTEXT ENGINEERING FRAMEWORK

### 3.1 System Prompt Design
- **Right Altitude Principle:** Balance specificity with flexibility
- **XML/Markdown structure:** Use tags like `<background_information>`, `<instructions>` to delineate sections
- **Minimal Information Approach:** Start minimal, iteratively add based on failure modes

### 3.2 Tool Design
- **Token-Efficient Returns:** Tools must conserve tokens
- **Minimal Viable Toolset:** No overlapping functionality; if humans can't choose, agents can't either
- **Clear Input Parameters:** Descriptive, unambiguous

### 3.3 Context Retrieval Patterns
- **Just-In-Time (JIT) Retrieval:** Maintain lightweight identifiers (paths, URLs), load data at runtime via tools
- **Progressive Disclosure:** Agents discover context incrementally (file sizes indicate complexity, timestamps proxy relevance)
- **Hybrid Strategy:** Pre-loaded data (CLAUDE.md) + runtime exploration tools (glob, grep)

### 3.4 Long-Horizon Techniques
1. **Compaction:** Summarize approaching limits; preserve architectural decisions, discard redundant tool outputs
2. **Tool Result Clearing:** Remove raw tool results deep in history (lightest-touch compaction)
3. **Structured Note-Taking:** Agent writes persistent notes outside context window, pulls back when needed
4. **Sub-Agent Architectures:** Specialized sub-agents with clean context; return condensed summaries (1-2K tokens)

---

## 4. ANTHROPIC'S COMPACTION API (Production)

**Source:** [Claude API - Compaction Docs](https://platform.claude.com/docs/en/build-with-claude/compaction) (Jan 2026)

### API Details
- Beta header: `compact-2026-01-12`
- Strategy type: `compact_20260112`
- Trigger threshold: default 150,000 tokens, minimum 50,000
- `pause_after_compaction`: boolean, allows adding content after summary
- `instructions`: custom summarization prompt (replaces default entirely)
- Supported models: Claude Opus 4.6, Claude Sonnet 4.6

### Default Summarization Prompt
"Write a summary of the transcript. The purpose is to provide continuity so you can continue to make progress towards solving the task in a future context, where the raw history may not be accessible. Write down state, next steps, learnings etc."

### Additional Strategy: clear_tool_uses_20250919
- Clears oldest tool results chronologically
- Replaces with placeholder text
- Useful for agentic workflows with heavy tool use

### Claude Code Specifics
- Buffer reduced to ~33K tokens (16.5%) as of early 2026
- Compaction triggers at ~167K tokens
- `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` env var controls trigger percentage (1-100)
- Since v2.0.64: instant compaction, no waiting
- Key: Put persistent rules in CLAUDE.md, not conversation history

---

## 5. OPENAI'S COMPACTION

**Source:** [OpenAI API - Compaction Guide](https://developers.openai.com/api/docs/guides/compaction)

### Two Approaches
1. **Server-Side:** Enable via `context_management.compact_threshold` in `/responses`; automatic
2. **Standalone Endpoint:** `POST /responses/compact`; explicit control, stateless, ZDR-friendly

### Key Details
- Compaction item is opaque, encrypted, not human-readable
- Carries forward key state using fewer tokens
- Can drop items before most recent compaction item for latency optimization
- Match model versions between compaction and response calls

### Codex-Specific
- First-class compaction enables multi-hour reasoning
- AGENTS.md for persistent context (now Linux Foundation standard)
- Supports skills and path-scoped rules

---

## 6. MICROSOFT AGENT FRAMEWORK COMPACTION

**Source:** [Microsoft Learn - Compaction](https://learn.microsoft.com/en-us/agent-framework/agents/conversations/compaction) (Mar 2026)

### Five Strategy Types
| Strategy | Aggressiveness | Requires LLM | Best For |
|---|---|---|---|
| ToolResultCompaction | Low | No | Reclaiming verbose tool output |
| SelectiveToolCall | Low-Medium | No | Removing tool history |
| Summarization | Medium | Yes | Preserving conversational context |
| SlidingWindow | High | No | Hard turn/group limits |
| Truncation | High | No | Emergency token backstop |

### Pipeline Pattern (Recommended)
Compose strategies sequentially, gentlest first:
1. Collapse old tool results (gentle)
2. Summarize older conversation (moderate)
3. Keep last N turns (aggressive)
4. Drop oldest groups (emergency backstop)

### Key Architecture
- **MessageGroup:** Atomic units (System, User, AssistantText, ToolCall, Summary)
- **CompactionTrigger:** Delegates (TokensExceed, MessagesExceed, TurnsExceed, GroupsExceed)
- **Trigger vs Target:** Trigger = when to start; Target = when to stop
- Uses cheaper model for summarization (e.g., gpt-4o-mini)

---

## 7. MANUS AI: Production Context Engineering Lessons

**Source:** [Manus Blog - Context Engineering Lessons](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus) (Jul 2025)

### KV-Cache as #1 Production Metric
- Cached tokens: $0.30/MTok vs uncached: $3/MTok (10x difference)
- ~100:1 input-to-output token ratios
- **Rules:** Stable prefixes (no timestamps), append-only context, deterministic JSON serialization, explicit cache breakpoints, session routing across vLLM workers

### Tool Management via Logits Masking
- Never dynamically remove tools (breaks KV-cache + confuses model)
- Instead: mask token logits during decoding
- Consistent prefixes: `browser_*`, `shell_*` for constraint enforcement

### File System as Memory
- Filesystem = unlimited, persistent, directly-operable context
- Compression always reversible (drop content if URL/path persists)

### Todo.md Pattern
- Agent creates/updates task files during execution
- "Recites objectives into end of context"
- Pushes plans into recent attention span (combats lost-in-the-middle)
- Average task requires ~50 tool calls

### Error Preservation
- Keep wrong turns in context (don't hide errors)
- Models observe failures and shift priors away from similar mistakes

### Few-Shot Drift Mitigation
- Uniform action-observation pairs cause pattern mimicry
- Introduce structured variation in serialization templates
- Controlled randomness breaks repetitive patterns

---

## 8. ANTHROPIC LONG-RUNNING AGENT HARNESSES

### 8.1 Two-Agent Harness
**Source:** [Anthropic - Effective Harnesses for Long-Running Agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents) (Nov 2025)

- **Initializer Agent:** Creates init.sh, progress file, 200+ feature list (all marked "failing")
- **Coding Agent:** Works on single features incrementally, clean state for merging
- Feature list in JSON (not Markdown) to prevent inappropriate overwrites
- Git commits document each increment across context windows

### 8.2 Three-Agent GAN-Inspired Harness
**Source:** [Anthropic - Harness Design for Long-Running Apps](https://www.anthropic.com/engineering/harness-design-long-running-apps) (2026)

- **Planner → Generator → Evaluator**
- Context resets > compaction: eliminates "context anxiety" (premature task conclusion)
- Claude Sonnet 4.5 required resets; Opus 4.5 eliminated the need
- Evaluator calibrated with few-shot examples for score consistency
- Results: Solo run (20min, $9, broken) vs Harness (6hrs, $200, fully functional)

---

## 9. GOOGLE ADK FOUR-LAYER CONTEXT SYSTEM

**Source:** [Google Developers Blog - Multi-Agent Framework](https://developers.googleblog.com/architecting-efficient-context-aware-multi-agent-framework-for-production/)

### Four Layers
1. **Working Context:** Ephemeral prompt for single invocation
2. **Session:** Durable event log (structured Events, not raw text)
3. **Memory:** Long-lived searchable knowledge across sessions (InMemory or VertexAI Memory Bank)
4. **Artifacts:** Named, versioned binary/text objects via ArtifactService

### Key Features
- Context compaction via async LLM-based summarization
- Deterministic rule-based filtering at session layer
- Prefix caching via `static_instruction` primitives
- Narrative casting: prior assistant messages reframed during agent transfer
- Recovery from failure + rewind to any conversation point (2025-2026 improvements)
- Topic-based memory (ACL 2025 accepted research)

---

## 10. CONTEXT COMPRESSION RESEARCH

### 10.1 ACON Framework
**Source:** [arXiv 2510.00615 - ACON](https://arxiv.org/html/2510.00615v1)

- Gradient-free optimization of compression guidelines via failure analysis
- Dual compression: History (>4096 tokens) + Observation (>1024 tokens)
- Results: 26-54% peak token reduction while maintaining baseline accuracy
- Distillable to smaller models (Qwen-14B, Phi-4) preserving 95% of teacher accuracy
- Observation compression more cost-effective than history compression (KV-cache breaking)

### 10.2 ACE (Agentic Context Engineering)
**Source:** [arXiv 2510.04618](https://arxiv.org/abs/2510.04618) (ICLR 2026)

- Treats contexts as "evolving playbooks" via generation, reflection, curation
- Prevents brevity bias and context collapse
- Results: +10.6% on agent benchmarks, +8.6% on finance benchmarks
- Matched top production agents on AppWorld using smaller open-source models

---

## 11. PROMPT CACHING FOR AGENTS

**Source:** [arXiv 2601.06007 - Don't Break the Cache](https://arxiv.org/html/2601.06007v1)

### Findings
- 45-80% cost reduction across all tested models
- 13-31% time-to-first-token improvement
- Full-context caching can paradoxically DEGRADE latency
- System-prompt-only caching most reliable strategy
- GPT-5.2 highest savings: 79-81%

### Architecture Pattern
Divide context into two zones:
1. **Stable prefixes:** System instructions, agent identity, long-lived summaries
2. **Variable suffixes:** Latest user turn, new tool outputs, incremental updates

---

## 12. MEMORY FRAMEWORKS COMPARISON (2026)

### Mem0
- Framework-agnostic SDK (works with LangChain, CrewAI, AutoGen, custom loops)
- Passive extraction: add() → system decides what to store
- Hybrid storage: vector + key-value + graph databases
- Retrieval considers relevance, importance, recency
- Research: 26% accuracy boost for LLMs

### Letta (formerly MemGPT)
- Agent runtime with OS-inspired memory hierarchy (RAM = main context, disk = external)
- Agent self-edits memory via function calls
- Tiered: core memory, recall memory, archival memory
- Benchmark: 74.0% on LoCoMo (vs Mem0's 68.5% for graph variant)

### Zep
- Shared memory for teams/multi-agent
- Managed infrastructure

### Mastra 1.0 (2026)
- "Observational Memory" using Observer + Reflector background agents
- Compresses conversations into date-stamped observations

---

## 13. CONVERSATION MANAGEMENT STRATEGIES

### Sliding Window
- Keep last k interactions
- Works for bounded conversations
- Some systems: multiple windows with different retention policies

### Summarization
- Hierarchical: recent verbatim, older compressed
- Cost: LLM call per summarization step
- **Anchored Iterative Summarization:** Factory's eval across 36K messages shows merging into persistent state > full-reconstruction

### RAG-Based
- Historical turns embedded in vector store
- Decouples total history from context window
- Production trend: combining RAG + long context strategically

### Hybrid Hierarchical Memory (Best Practice)
- Short-term: Recent turns verbatim
- Medium-term: Compressed session summaries
- Long-term: Facts, preferences, experiences in external store
- Allocate more budget to short-term, include relevant long-term

---

## 14. TOKEN BUDGET MANAGEMENT

### Quadratic Cost Growth
- LLMs charge for every input token in every turn
- Multi-turn agents face compounding costs

### Cost Optimization Levers
1. Prompt caching: ~90% input cost reduction, ~75% latency reduction
2. Multi-tier routing: classify query complexity, route to appropriate model
3. Compaction: reduce carried history
4. Tool output truncation/summarization
5. Session boundaries: fresh sessions for new tasks

### Production Constraints (2026)
- Multi-agent systems: 10-30 second latency often unacceptable for user-facing
- Tension between latency and accuracy is primary engineering constraint
- Token budget as resource: Nvidia CEO proposed token budgets as employee compensation

---

## 15. AGENTS.md / CLAUDE.md STANDARD

**Source:** [OpenAI Codex AGENTS.md](https://developers.openai.com/codex/guides/agents-md)

- Open standard stewarded by Agentic AI Foundation (Linux Foundation)
- Supported by: OpenAI Codex, Amp, Jules (Google), Cursor, Factory, Claude Code
- Standard Markdown format with project structure, conventions, testing instructions
- Precedence: AGENTS.override.md > AGENTS.md > fallback names
- Path-scoped rules (e.g., `*.ts` for TypeScript files only)
- Always loaded into context as persistent configuration

---

## 16. ACADEMIC PAPERS (2025-2026)

| Paper | Key Contribution |
|---|---|
| ACON (2510.00615) | Gradient-free compression guideline optimization, 26-54% token reduction |
| ACE (2510.04618, ICLR 2026) | Evolving context playbooks, +10.6% on agent benchmarks |
| Everything is Context (2512.05470) | Unix-like file system abstraction for context artifacts |
| Meta Context Engineering (2601.21557) | Bi-level framework co-evolving CE skills and artifacts |
| Don't Break the Cache (2601.06007) | Prompt caching evaluation, 45-80% cost savings |
| On Impact of AGENTS.md (2601.20404) | Empirical study of AGENTS.md on coding agent efficiency |
| Codified Context (2602.20478) | Infrastructure for agents in complex codebases |
| Structured CE for File-Native Systems (2602.05447) | 9,649 experiments, 11 models, 4 formats |
| CE: Prompts to Corporate Multi-Agent (2603.09619) | CE as standalone discipline |
| Memory in Age of AI Agents (2512.13564) | Survey: forms (token/parametric/latent), functions (factual/experiential/working) |

---

## 17. KEY ANTI-PATTERNS

1. **Dumping everything into context** — "right information, not most information"
2. **Dynamic tool removal** — breaks KV-cache; use logits masking instead
3. **Timestamps in system prompts** — invalidates cache from that token forward
4. **Ignoring context pollution** even with larger windows
5. **Pre-processing all data upfront** for simple retrieval tasks
6. **Exhaustive edge case lists** instead of canonical few-shot examples
7. **Single long-running session** without session boundaries
8. **Hiding errors from context** — models learn from observed failures
9. **Relying on conversation history** for persistent rules (use config files)
10. **Full-context caching** — can paradoxically increase latency

---

## 18. RECOMMENDED ARCHITECTURE FOR PRODUCTION AGENTS

Based on synthesis of all sources:

### Context Budget Allocation
1. **System prompt + tools** (stable prefix, cache-friendly): ~15-20%
2. **Retrieved context** (JIT, relevant docs/code): ~20-30%
3. **Conversation history** (compacted): ~30-40%
4. **Working space** (current task + output): ~20-25%

### Compaction Pipeline (ordered by aggressiveness)
1. Tool result clearing/collapsing (no LLM needed)
2. LLM-based summarization of older turns (cheap model)
3. Sliding window on conversation groups
4. Truncation as emergency backstop

### Memory Tiers
1. **In-context:** Current session, recent turns verbatim
2. **External short-term:** Session state files, todo.md, progress tracking
3. **External long-term:** Persistent notes, learned patterns, project knowledge
4. **Cross-session:** CLAUDE.md/AGENTS.md, guardrails, core memory

### Session Management
- Context resets > compaction for long-running work
- Structured handoff artifacts between sessions
- Initializer + coding agent pattern for multi-session tasks
- Sub-agents for exploration (return condensed summaries)
