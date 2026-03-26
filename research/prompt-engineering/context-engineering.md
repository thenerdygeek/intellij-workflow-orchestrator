# Context Engineering for Agentic AI Systems

> Research compiled 2026-03-26. Covers the emerging discipline of optimizing what goes into an LLM's context window for agentic AI, beyond traditional prompt engineering.

---

## Table of Contents

1. [Definition and Origins](#1-definition-and-origins)
2. [System Prompt Structure and Design](#2-system-prompt-structure-and-design)
3. [Token Budget Management](#3-token-budget-management)
4. [Compression and Summarization](#4-compression-and-summarization)
5. [Memory Systems for Agents](#5-memory-systems-for-agents)
6. [Tool Result Management](#6-tool-result-management)
7. [The Lost-in-the-Middle Problem](#7-the-lost-in-the-middle-problem)
8. [Prompt Caching and KV-Cache Optimization](#8-prompt-caching-and-kv-cache-optimization)
9. [Production Patterns from Leading Systems](#9-production-patterns-from-leading-systems)
10. [Implementation Checklist](#10-implementation-checklist)

---

## 1. Definition and Origins

### Karpathy's Definition (June 2025)

Andrej Karpathy coined the distinction between prompt engineering and context engineering:

> "+1 for 'context engineering' over 'prompt engineering'. People associate prompts with short task descriptions you'd give an LLM in your day-to-day use. When in every industrial-strength LLM app, context engineering is the delicate art and science of filling the context window with just the right information for the next step."

**The CPU/RAM Metaphor:** "The LLM is like the CPU, and its context window is like RAM. It is the model's working memory." The engineer's job is akin to an operating system: load working memory with just the right code and data for the task.

By February 2026, Karpathy declared prompt engineering dead. Gartner published a formal definition, enterprise teams posted job listings, and the ODSC community treated context engineering as the discipline that makes production AI work.

**Source:** [Karpathy on X](https://x.com/karpathy/status/1937902205765607626)

### The Core Distinction

| Prompt Engineering | Context Engineering |
|---|---|
| "Maybe if I phrase it this way..." | "What inputs does this system need? How do I get and structure them?" |
| Trial-and-error wording | Systematic design |
| Static instructions | Dynamic, evolving context across turns |
| Single-turn focus | Multi-turn agent lifecycle |

### Anthropic's Definition

> "The set of strategies for curating and maintaining the optimal set of tokens (information) during LLM inference, including all the other information that may land there outside of the prompts."

The engineering challenge: optimize the utility of tokens against the inherent constraints of LLMs to consistently achieve a desired outcome.

**Source:** [Anthropic Engineering Blog](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)

### Five Essential Components of Context

1. **Instructional Context** -- Role definitions, task guidance, few-shot examples
2. **Knowledge Context** -- Domain facts via retrieval or external sources
3. **Tools Context** -- API call results, database queries, code execution output
4. **State/Memory Context** -- Conversation history, summaries, prior decisions
5. **Environmental Context** -- Current user info, system state, constraints

**Source:** [Addy Osmani's Substack](https://addyo.substack.com/p/context-engineering-bringing-engineering)

---

## 2. System Prompt Structure and Design

### The "Right Altitude" Approach (Anthropic)

Avoid two extremes:
- **Too specific:** Hardcoded complex logic creates brittleness and maintenance burden
- **Too vague:** High-level guidance without concrete signals fails to guide behavior

**Optimal:** Specific enough to guide behavior effectively, yet flexible enough to provide strong heuristics.

### Structural Recommendations

**Organization:** Use distinct sections with clear delineation:
```
<background_information>
[Role, domain context, current state]
</background_information>

<instructions>
[Core behavioral rules and task guidance]
</instructions>

## Tool guidance
[When and how to use each tool]

## Output description
[Expected format and constraints]
```

**Format Impact:** Research shows LLMs are highly sensitive to formatting:
- GPT-3.5-turbo performance varies by up to **40%** depending on prompt template format
- Bulleted lists improve instruction following vs. prose paragraphs
- Up to **76 accuracy points** difference across formatting changes in few-shot settings

**Source:** [arxiv.org/html/2411.10541v1](https://arxiv.org/html/2411.10541v1)

### Ordering and Placement Rules

1. **Begin with ASK section** at top for quick goal comprehension
2. **Format each section as Markdown bullet points** -- easier to skim, better instruction following
3. **Include retrieved context AFTER instructions** -- model primes on task first, then grounds with context
4. **Place critical constraints at beginning AND end** -- exploits U-shaped attention curve
5. **Never compress system prompts** -- they're the most stable, highest-value tokens

### The Isolation Template (Preventing "Context Soup")

Strict separation prevents cross-contamination between reasoning domains:

```
# SYSTEM (immutable operational rules)
[Role, constraints, behavioral guidelines]

# TASK (task-specific context)
[Current objective, relevant background]

# MEMORY (retrieved episodic knowledge)
[Relevant past interactions, decisions]

# TOOLS (execution artifacts)
[Available tools, recent results]

# USER INPUT (untrusted, sanitized)
[Current user query]
```

**Source:** [Sundeep Teki - Agentic Context Engineering](https://www.sundeepteki.org/blog/agentic-context-engineering)

### Iteration Strategy

1. Start with minimal prompt on the best available model
2. Evaluate performance against task requirements
3. Add clear instructions addressing observed failure modes
4. Repeat until sufficient performance reached
5. Note: "minimal does not necessarily mean short" -- include sufficient information for adherence

**Source:** [Anthropic Engineering Blog](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)

---

## 3. Token Budget Management

### The Attention Budget Problem

Every token in context competes for the model's attention. Research confirms:
- Performance starts degrading at **60-70%** of available context capacity
- Beyond **100,000 tokens**, models favor repeating past actions over generating novel solutions
- Agents remain sharp for ~20-30 minutes before reasoning degrades -- correlating with context window saturation
- **65% of enterprise AI failures** in 2025 attributed to context drift or memory loss, NOT raw context exhaustion

**Key insight:** Context drift kills agents before context limits do.

### Token Budget Allocation Strategy

| Content Type | Budget Priority | Compression Ratio |
|---|---|---|
| System prompt | Highest -- never compress | 1:1 (preserve fully) |
| Recent messages (5-7 turns) | High -- preserve fully | 1:1 |
| Current task state | High | 1:1 |
| Old conversation turns | Medium | 3:1 to 5:1 |
| Tool outputs/observations | Low | 10:1 to 20:1 |
| Stale tool results | Lowest | Replace with references |

### Compression Trigger

**Trigger compaction when context utilization exceeds 70% of available budget.** Performance degrades beyond 30,000 tokens even in large-window models.

### The 100:1 Ratio (Manus)

Production agents operate with a **100:1 input-to-output token ratio**, creating highly skewed prefilling-to-decoding dynamics unlike chatbots. This means input token optimization is overwhelmingly more impactful than output optimization.

**Source:** [Manus Blog](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)

### Multi-Step Reliability Math

95% per-step reliability over 20 steps = only **36% combined success rate**. This means every token of noise that reduces per-step reliability compounds catastrophically over long agent runs.

---

## 4. Compression and Summarization

### Technique Comparison

| Technique | Token Reduction | Quality | Cost | Best For |
|---|---|---|---|---|
| Sliding window | N/A (discard) | Poor continuity | Free | Brief sessions |
| Rolling LLM summarization | ~80% | Detail drift risk | High (7%+ of costs) | Moderate sessions |
| Anchored iterative summarization | ~70% | Best accuracy (4.04 vs 3.74) | Medium | Long sessions |
| ACON (failure-driven optimization) | 26-54% peak reduction | 95%+ accuracy | Medium | Production agents |
| Embedding-based compression | 80-90% | Precision loss risk | Low at inference | Storage-heavy systems |
| Provider-native compaction | ~60% | Good | API-managed | Anthropic users |
| Observation masking | ~50%+ | Matched or exceeded summarization | Free | All agent types |

### Anchored Iterative Summarization (Best Practice)

The leading production pattern. Instead of re-summarizing from scratch:

1. Identify only newly-evicted message spans
2. Summarize that span alone
3. Merge into persistent anchor state structured around:
   - **Intent** -- what the agent is trying to accomplish
   - **Changes made** -- what's been done so far
   - **Decisions taken** -- key choices and rationale
   - **Next steps** -- what remains

Factory reported **superior accuracy** (4.04 vs competitors' 3.74-3.43) for preserving technical details like file paths across compression cycles.

**Source:** [Zylos Research](https://zylos.ai/research/2026-02-28-ai-agent-context-compression-strategies)

### ACON Framework (Gradient-Free Optimization)

Treats compression as an optimization problem:
1. **Paired trajectory analysis** -- find cases where full context succeeded but compressed failed
2. **Failure analysis** -- identify what information loss caused failures
3. **Guideline updates** -- iteratively refine compression prompts
4. **Distillation** -- transfer to smaller models (95%+ accuracy preservation)

Results: **26-54% reduction in peak token usage**. Compatible with any API-accessible model.

### Observation Masking (Surprisingly Effective)

JetBrains research finding: **Simple observation masking matched or exceeded LLM summarization in 4 of 5 test configurations.**

- Replace older environment observations with placeholders
- Preserve action and reasoning history in full
- Qwen3-Coder 480B: **52% cheaper** while improving solve rates by 2.6%
- LLM summarization caused **13-15% trajectory elongation** (agents ran longer unnecessarily)

**Recommendation:** Start with masking; add summarization selectively.

**Source:** [JetBrains Research Blog](https://blog.jetbrains.com/research/2025/12/efficient-context-management/)

### Compression vs. Summarization Safety

- **Compression** keeps original phrasing but removes redundancy -- safer for precision
- **Summarization** creates new sentences -- risks hallucination in the summary itself
- For production agents, prefer compression over summarization when possible

### Context Drift Detection

**Drift symptoms:**
- Re-execution of completed work
- Goal statement rewording across turns
- Technical detail corruption (variable names, error codes)
- System prompt instructions "forgotten"

**Detection:** Distributed tracing with conversation trajectory visualization identifies exact turn where drift begins.

### Production Compression Flow

```
Incoming message --> [Context budget check]
  <70% --> append normally
  >70% --> [identify evictable span] --> [summarize span only] -->
           [merge into anchor state] --> [append anchor + recent] --> LLM call
```

---

## 5. Memory Systems for Agents

### Four-Layer Memory Architecture

| Layer | Scope | Implementation | Persistence |
|---|---|---|---|
| **Working Context** | Current step | In-context tokens | Ephemeral |
| **Session Memory** | Current task | Structured state doc | Session-scoped |
| **Domain Memory** | Cross-session | Vector store + KG | Persistent |
| **Artifacts** | All time | File system | Permanent |

**Source:** [Nate's Newsletter synthesis of Google/Anthropic/Manus research](https://natesnewsletter.substack.com/p/i-read-everything-google-anthropic)

### Cognitive Science Mapping

| Human Memory | Agent Equivalent | Implementation |
|---|---|---|
| Working memory | Context window | Current tokens in LLM input |
| Short-term memory | Recent conversation | Sliding window of recent turns |
| Episodic memory | Interaction logs | Vector-searchable past sessions |
| Semantic memory | Knowledge base | RAG over documentation/facts |
| Procedural memory | Learned strategies | Accumulated context playbooks |

### AgeMem: Unified Memory Management (Jan 2026)

Key innovation: Let the LLM itself decide **when, what, and how** to manage memory, rather than using heuristic rules.

**Problem with existing approaches:** Separate STM/LTM systems with hardcoded rules limit adaptability. AgeMem enables end-to-end optimization of memory decisions.

**Source:** [arxiv.org/abs/2601.01885](https://arxiv.org/abs/2601.01885)

### A-MEM: Zettelkasten-Inspired Memory

Each memory unit is enriched with:
- LLM-generated keywords and tags
- Contextual descriptions
- Dynamically constructed links to semantically related memories

This creates a structured, interconnected knowledge graph that grows organically.

### Structured Note-Taking Pattern (Proven Effective)

Agent maintains external files (e.g., `NOTES.md`, `todo.md`) tracking progress:

```markdown
## Current Objective
[What we're trying to accomplish]

## Decisions Made
- [Decision 1]: [Rationale]
- [Decision 2]: [Rationale]

## Progress
- [x] Step 1 completed
- [ ] Step 2 in progress
- [ ] Step 3 pending

## Key Findings
- [Finding that affects future decisions]
```

**Evidence:** Claude playing Pokemon maintained precise tallies across thousands of steps ("for the last 1,234 steps I've been training my Pokemon in Route 1, Pikachu has gained 8 levels toward target of 10") using only external note-taking.

**Anthropic reports up to 54% improvement**, particularly for tool analysis and multi-step decision-making when agents use external scratchpads.

### File System as Extended Memory (Manus Pattern)

Rather than vector stores, use the filesystem directly:
- **Unlimited in size, persistent by nature, directly operable by the agent**
- Full tool results stored on disk; agents retrieve via `grep` and `glob`
- Compression remains restorable -- URLs preserve web content, file paths preserve documents
- Fewer than 20 atomic functions needed (Bash, filesystem, code execution)

**Source:** [Manus Blog](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)

### Memory Retrieval Trigger Design

The hardest problem: ensuring external memory gets accessed when needed.
- Passive libraries don't work -- agents must actively recognize when to retrieve
- Implement explicit "memory check" steps in the agent loop
- Use structured state documents that the agent updates and reads each turn

---

## 6. Tool Result Management

### The Core Problem

**Tool output verbosity is the primary token budget killer.** In production agents averaging 50 tool calls per task, unmanaged tool results can consume the entire context window.

### Retention Strategy

| Recency | Treatment |
|---|---|
| Last 2 user turns | Never touch -- maintain immediate context |
| Recent tool outputs (~40k tokens) | Keep intact |
| Older tool results | Compact to summaries/references |
| Stale results | Replace with file paths or one-line summaries |

**Rule of thumb:** Keep most recent ~40k tokens of tool outputs intact. Only prune if at least ~20k tokens would be removed. Systems scan backward from recent turns while skipping the last 2 user turns.

### Tool Result Clearing (Lightweight Compaction)

Once a tool has been called deep in message history, the agent doesn't need raw results repeatedly. Replace with:
- A one-line summary of what was found
- A file path where full results are stored
- A compact reference that can be re-fetched if needed

This is the **safest, lowest-risk form of context pruning**.

### Tool Design Principles

1. **Minimal viable set** -- bloated tool sets cause ambiguous decision points
2. **Self-contained and robust** -- each tool clearly defined with unambiguous parameters
3. **No functional overlap** -- if a human can't say which tool applies, the agent won't either
4. **Filter at ingestion** -- truncate verbose tool responses BEFORE they enter context, not during compression
5. **Consistent naming** -- use prefixes (e.g., `browser_`, `shell_`, `file_`) for stateless constraint

### Dynamic Tool Loading (Manus Approach)

Rather than removing tools (which breaks KV-cache), use **logit masking with state machines**:
- **Auto mode:** Model may call functions
- **Required mode:** Must call a function (unconstrained which)
- **Specified mode:** Must select from a specific subset

This constrains without invalidating cache.

### Tool Count Limits

Research findings on tool count impact:
- Beyond **30 tools**, DeepSeek-v3 struggles with overlapping descriptions
- Llama 3.1 8b fails with **46 tools** but succeeds with **19**
- Dynamic tool selection using LLM reasoning improved performance by **44%**
- Smaller tool sets: **18% less power**, **77% faster**

**Source:** [dbreunig.com](https://www.dbreunig.com/2025/06/26/how-to-fix-your-context.html)

---

## 7. The Lost-in-the-Middle Problem

### The Problem

LLMs exhibit U-shaped attention: strong at the beginning and end of context, weak in the middle. Liu et al. (2024) measured a **30%+ accuracy drop** on multi-document QA when the answer moved from position 1 to position 10 in a 20-document context.

### Root Cause

Rotary Position Embedding (RoPE) introduces long-term decay that prioritizes tokens at beginning and end. As context density increases, models retain semantic comprehension but **lose spatial awareness**.

### Mitigation Strategies

#### 1. Strategic Document Ordering
Place highest-ranked documents at beginning AND end of context. Keep only **3-5 most relevant documents** in the prompt.

#### 2. Two-Stage Retrieval + Reranking
- Broad recall (20-100 candidates) with vector similarity
- Cross-encoder reranking: **15-30% improvement** over embedding-only retrieval
- BERT-based rerankers that jointly encode query-document pairs

#### 3. Hybrid Search
Combine semantic (dense) + keyword (BM25/sparse) search to capture both conceptual similarity and exact matches.

#### 4. Context Compression
**Most broadly effective mitigation** -- shorter context eliminates the middle region entirely.

#### 5. Multi-Scale Positional Encoding (Ms-PoE)
Different position index scaling for different attention heads: **20-40% improvement** in middle-position accuracy.

#### 6. Attention Calibration
Direct intervention in attention distribution to reflect relevance rather than position.

#### 7. Explicit Section Labeling
Tell the model: "The first section is primary and the second section is optional." This simple instruction eliminates many lost-in-the-middle cases.

#### 8. Todo List Recitation (Manus)
Create and update `todo.md` files during tasks, pushing objectives into recent context. This combats the problem across ~50 average tool calls per task by ensuring goals remain in the high-attention end-of-context zone.

**Sources:**
- [Maxim AI](https://www.getmaxim.ai/articles/solving-the-lost-in-the-middle-problem-advanced-rag-techniques-for-long-context-llms/)
- [arxiv.org/abs/2511.13900](https://arxiv.org/abs/2511.13900)
- [Towards AI](https://pub.towardsai.net/why-language-models-are-lost-in-the-middle-629b20d86152)

---

## 8. Prompt Caching and KV-Cache Optimization

### Cost Impact

Cached tokens cost **10x less** than uncached:
- Claude Sonnet cached: **$0.30/MTok**
- Claude Sonnet uncached: **$3.00/MTok**

Production agents achieve **45-80% reduction** in API costs with proper caching:
- GPT-5.2: 79.6% savings
- Claude Sonnet 4.5: 78.5% savings

**Source:** [arxiv.org/html/2601.06007v1](https://arxiv.org/html/2601.06007v1)

### What Breaks Cache

Any single-token change at the prompt start prevents ALL caching:
- **Timestamps** in system prompts
- **UUIDs or session IDs** in early prompt positions
- **Variable tool definitions** that change between requests
- **Non-deterministic JSON serialization** (key ordering)

### KV-Cache Optimization Rules (Manus)

1. **Stable prefixes** -- avoid timestamps or dynamic content in system prompts
2. **Append-only contexts** -- never modify previous actions/observations
3. **Deterministic serialization** -- ensure identical JSON key ordering
4. **Explicit cache breakpoints** -- manually insert when frameworks lack auto-caching
5. **Session routing** -- use session IDs for consistency across distributed workers

### Caching Architecture

```
[SYSTEM PROMPT - 10k+ tokens, CACHED]         <-- Stable, highest cache value
[TOOL DEFINITIONS - stable set, CACHED]        <-- Fixed between requests
[CONVERSATION HISTORY - append-only]           <-- Prefix remains cached
[CURRENT TURN - dynamic]                       <-- Not cached
```

### Practical Recommendations

1. **Prioritize system-prompt-only caching** for consistent cost and latency benefits
2. **Keep large, stable system prompts (10,000+ tokens) at beginning**
3. **Place dynamic values only at prompt end** to preserve cacheable prefix
4. **Avoid dynamic function definitions** -- use static APIs with code-based extensibility
5. **Monitor provider-specific thresholds**: minimum tokens range 1,024-4,096; TTL 5 minutes to 24 hours
6. **Latency improvement**: 13-31% TTFT improvement with optimal strategies

### Anti-Pattern: Full-Context Caching

Full-context caching sometimes **caused latency regression** (up to 8.8% worse). System-prompt-only caching proved most reliable.

**Source:** [arxiv.org/html/2601.06007v1](https://arxiv.org/html/2601.06007v1)

---

## 9. Production Patterns from Leading Systems

### Anthropic's Claude Code

**Context management approach:**
- Pre-loads critical data (CLAUDE.md files) for speed
- Enables autonomous exploration via tools for just-in-time discovery
- Uses progressive disclosure -- agents incrementally discover relevant context
- Sub-agents handle focused tasks with clean context windows, returning condensed summaries (1,000-2,000 tokens) from extensive exploration (tens of thousands of tokens)
- Auto-compaction at ~83.5% context utilization
- Structured note-taking for long-horizon coherence

**JIT Retrieval Pattern:**
- Store lightweight identifiers (file paths, URLs, query references) in context
- Dynamically load data at runtime via tools (SQL, grep, glob)
- Use `head` and `tail` for large datasets
- Never load full data objects into context

### Manus

**Architecture decisions:**
- In-context learning over fine-tuning (ship improvements in hours, not weeks)
- 100:1 input-to-output token ratio
- Average 50 tool calls per task
- Fewer than 20 atomic functions (Bash, filesystem, code execution)
- File system as extended memory instead of vector stores
- Refactored architecture 5 times since March 2025

**Error preservation:** Keep error traces visible in context so models implicitly update beliefs and avoid repeating mistakes.

**Pattern-breaking:** Structured variation in serialization templates prevents models from "few-shotting themselves into a rut" during batch operations.

### JetBrains Research Findings

- Observation masking outperformed LLM summarization in most configurations
- Both approaches cut costs by **50%+** vs unmanaged agents
- Summarization caused **13-15% trajectory elongation** (agents running unnecessarily long)
- Optimal observation masking window: **10 turns**
- Different agent scaffolds require different window sizes

### ACE Framework (Zhang et al., 2025)

Three-role system for evolving context:
1. **Generator** -- produces reasoning trajectories
2. **Reflector** -- critiques across iterations (typically 5 rounds)
3. **Curator** -- synthesizes lessons into compact entries

Results: +10.6% on agent benchmarks, +8.6% on finance tasks, 86.9% latency reduction, 75.1% cost reduction.

**Critical insight:** Separating reflection from curation is essential. Combined roles produce superficial analysis.

**Source:** [arxiv.org/abs/2510.04618](https://arxiv.org/abs/2510.04618)

### Multi-Agent Context Isolation

Anthropic's multi-agent research system outperformed single-agent Claude Opus by **90.2%** on breadth-first queries through task decomposition with isolated contexts.

**Pattern:**
- Main agent coordinates high-level planning
- Specialized sub-agents handle focused tasks with clean context windows
- Sub-agents return condensed summaries (1,000-2,000 tokens)
- Clear separation: detailed search context isolated, lead agent synthesizes

---

## 10. Implementation Checklist

### For Our Plugin Agent

Based on all research, these are the priority implementation items:

#### System Prompt Design
- [ ] Structure with clear XML/Markdown sections (background, instructions, tools, output)
- [ ] Place critical instructions at beginning AND end of system prompt
- [ ] Use bullet points over prose paragraphs
- [ ] Include few-shot examples for complex tool usage
- [ ] Never include timestamps or dynamic values in system prompt prefix
- [ ] Design for KV-cache stability (deterministic serialization, append-only)

#### Token Budget Management
- [ ] Set compression trigger at 70% context utilization
- [ ] Implement anchored iterative summarization (not full re-summarization)
- [ ] Track token usage per category (system, history, tools, memory)
- [ ] Monitor for context drift signals (re-reads, goal rewording, detail corruption)

#### Tool Result Handling
- [ ] Filter tool response verbosity at ingestion time
- [ ] Keep last ~40k tokens of tool outputs intact
- [ ] Replace stale tool results with file paths / one-line summaries
- [ ] Limit tool set to under 30 (ideally under 20)
- [ ] Use consistent naming prefixes for tool categories

#### Memory Architecture
- [ ] Implement working context (current turn tokens)
- [ ] Implement session memory (structured state document updated each turn)
- [ ] Implement domain memory (persistent knowledge across sessions)
- [ ] Use file system as extended memory (not vector store for initial impl)
- [ ] Agent maintains `todo.md` or equivalent for objective recitation

#### Lost-in-the-Middle Mitigation
- [ ] Place critical info at beginning and end of context
- [ ] Use explicit section labels with priority indicators
- [ ] Keep context as short as possible (compression is the best mitigation)
- [ ] Implement todo list recitation pattern for long tasks

#### Caching Optimization
- [ ] Stable system prompt prefix (10k+ tokens, no dynamic content)
- [ ] Append-only conversation history
- [ ] Deterministic JSON serialization for all context components
- [ ] Static tool definitions (use logit masking for dynamic selection)

#### Compression Pipeline
```
Incoming message --> [Token count check]
  < 70% budget --> append normally
  >= 70% budget --> [identify evictable spans]
                    --> [observation masking on old tool results]
                    --> [anchored summarization of old conversation]
                    --> [merge into session state anchor]
                    --> [append anchor + recent turns]
                    --> LLM call
```

---

## Key Metrics to Target

| Metric | Target | Source |
|---|---|---|
| KV-cache hit rate | >80% | Manus (primary production metric) |
| Context utilization at trigger | 70% | Zylos Research |
| Cost reduction via caching | 45-80% | arxiv prompt caching study |
| Tool result compression ratio | 10:1 to 20:1 | Zylos Research |
| Observation masking window | 10 turns | JetBrains Research |
| Sub-agent summary size | 1-2k tokens | Anthropic |
| Max tools in active set | <30 (ideally <20) | dbreunig.com |

---

## Sources

### Primary References

- [Anthropic - Effective Context Engineering for AI Agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Manus - Context Engineering for AI Agents: Lessons from Building Manus](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)
- [JetBrains Research - Cutting Through the Noise: Smarter Context Management](https://blog.jetbrains.com/research/2025/12/efficient-context-management/)
- [Zylos Research - AI Agent Context Compression Strategies](https://zylos.ai/research/2026-02-28-ai-agent-context-compression-strategies)
- [arxiv - Don't Break the Cache: Prompt Caching for Long-Horizon Agentic Tasks](https://arxiv.org/html/2601.06007v1)
- [arxiv - Agentic Context Engineering: Evolving Contexts (ACE)](https://arxiv.org/abs/2510.04618)
- [arxiv - Agentic Memory: Unified LTM and STM Management](https://arxiv.org/abs/2601.01885)

### Frameworks and Guides

- [Prompting Guide - Context Engineering Guide](https://www.promptingguide.ai/guides/context-engineering-guide)
- [LlamaIndex - Context Engineering Techniques](https://www.llamaindex.ai/blog/context-engineering-what-it-is-and-techniques-to-consider)
- [Addy Osmani - Context Engineering: Bringing Engineering Discipline to Prompts](https://addyo.substack.com/p/context-engineering-bringing-engineering)
- [Sundeep Teki - Agentic Context Engineering: The Complete Guide](https://www.sundeepteki.org/blog/agentic-context-engineering)
- [dbreunig - How to Fix Your Context](https://www.dbreunig.com/2025/06/26/how-to-fix-your-context.html)

### Analysis and Commentary

- [Karpathy on X - Context Engineering Definition](https://x.com/karpathy/status/1937902205765607626)
- [GitHub - Context Engineering Handbook (davidkimai)](https://github.com/davidkimai/Context-Engineering)
- [Nate's Newsletter - Synthesis of Google/Anthropic/Manus Research](https://natesnewsletter.substack.com/p/i-read-everything-google-anthropic)
- [Lance Martin - Context Engineering in Manus Analysis](https://rlancemartin.github.io/2025/10/15/manus/)
- [arxiv - Lost in the Middle: Mitigations Study](https://arxiv.org/abs/2511.13900)
- [Maxim AI - Solving Lost-in-the-Middle with RAG](https://www.getmaxim.ai/articles/solving-the-lost-in-the-middle-problem-advanced-rag-techniques-for-long-context-llms/)

### Research Papers

- [arxiv - What Works for Lost-in-the-Middle in LLMs](https://arxiv.org/html/2511.13900v1)
- [arxiv - Does Prompt Formatting Have Any Impact on LLM Performance](https://arxiv.org/html/2411.10541v1)
- [arxiv - Choosing How to Remember: Adaptive Memory Structures](https://arxiv.org/html/2602.14038)
- [OpenReview - ACE: Agentic Context Engineering](https://openreview.net/forum?id=eC4ygDs02R)
