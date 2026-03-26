# Prompt Engineering & Context Engineering for Agentic AI — Synthesis

> Consolidated from 55+ sources across Anthropic, OpenAI, Google, Microsoft, LangChain, academic papers, and production systems.
> Compiled: 2026-03-26

---

## The Big Picture

**Prompt engineering is dead. Context engineering is what matters for agents.**

The shift (Karpathy, mid-2025): For agents, it's not about phrasing a single instruction well — it's about managing the *entire information environment* across multiple inference turns. The LLM is the CPU; the context window is RAM. Your job is the OS.

---

## Top 20 Actionable Principles

### System Prompt Design

1. **Structure with clear sections** — Use XML tags or Markdown headers: `<background>`, `<instructions>`, `<tools>`, `<output>`. Format as bulleted lists, not prose. Up to 40% performance variation from formatting alone.

2. **U-shaped attention** — Place critical instructions at beginning AND end. LLMs have weak attention in the middle (30%+ accuracy drop for mid-positioned info). Never bury important rules in the middle.

3. **Explain WHY, not just WHAT** — "Never use ellipses because the TTS engine can't pronounce them" works better than "NEVER use ellipses". Models generalize from motivation, not just rules.

4. **Right altitude** — Specific enough to guide, flexible enough for heuristics. Avoid both "make it good" (too vague) and 50-step hardcoded procedures (too brittle). "Think thoroughly" often outperforms hand-written step-by-step plans.

5. **Few-shot examples** — 3-5 diverse, high-quality examples wrapped in `<example>` tags. "For an LLM, examples are the 'pictures' worth a thousand words." Put examples in system prompt, not in tool descriptions.

6. **Never include dynamic values in prefix** — Timestamps, session IDs, or variable tool definitions at the start break KV-cache (10x cost difference: $0.30 vs $3.00/MTok cached vs uncached).

### Tool Design (The Most Impactful Factor)

7. **3-4+ sentence descriptions minimum** — Include: what it does, when to use it, when NOT to use it, parameter meanings, caveats. "Anthropic's SWE-bench agent required more optimization effort on tools than overall prompts."

8. **The Intern Test** — "Can an intern correctly use this function given nothing but what you gave the model?" If not, the description is insufficient.

9. **Keep tools under 30 (ideally under 20)** — Beyond 30, models struggle with overlapping descriptions. Dynamic tool selection improved performance 44%. Smaller sets: 18% less powerful but 77% faster.

10. **Consolidate related operations** — Use action parameters instead of separate tools (e.g., one `jira` tool with `action: "get_ticket" | "transition" | "comment"` vs three separate tools). Reduces selection ambiguity.

11. **Return only high-signal data** — Tool results are the primary token budget killer. Filter at ingestion time, not during compression. Return summaries, not raw dumps.

### Agent Loop Design

12. **ReAct + Reflexion is the proven combination** — ReAct for reasoning+acting, Reflexion for self-improvement from test feedback. ReAct+CoT achieved 35.1% on HotpotQA vs 29.4% for CoT alone.

13. **Three mandatory agentic components** (OpenAI GPT-4.1 guide):
    - **Persistence**: "Keep going until fully resolved"
    - **Tool discipline**: "Use tools rather than guessing"
    - **Planning**: "Plan before each action, reflect on outcomes"

14. **Parallel tool calling** — Up to 90% time reduction. Always include: "If you intend to call multiple tools with no dependencies, make all independent calls in parallel."

15. **Completeness contracts** — "Treat tasks as incomplete until all requested items are covered. Maintain internal checklists. Track processed items. Confirm coverage before finalizing."

### Context Management

16. **Compress at 70% utilization, not at exhaustion** — Performance degrades beyond 60-70% capacity. Use anchored iterative summarization (update existing state doc, don't re-summarize from scratch). 4.04 accuracy vs 3.43-3.74 for alternatives.

17. **Observation masking beats LLM summarization** — JetBrains research: simple masking of old tool outputs matched or exceeded summarization in 4/5 configs, at 52% lower cost. Summarization caused 13-15% trajectory elongation.

18. **File system as extended memory** — Manus uses <20 atomic functions with filesystem storage. Agents retrieve via grep/glob. Simpler, more debuggable than vector stores.

19. **Todo list recitation** — Writing/updating `todo.md` pushes objectives into the high-attention end-of-context zone. Combats lost-in-the-middle across 50+ tool calls. Anthropic reports 54% improvement for multi-step decisions.

20. **Sub-agent isolation** — Clean context windows per subtask, returning 1-2K token summaries from tens of thousands explored. Anthropic's multi-agent system outperformed single-agent by 90.2%.

---

## System Prompt Template (Synthesis of All Sources)

```
# Identity & Role
[One-sentence role definition focusing behavior and tone]

# Core Behavioral Rules
- Persistence: Keep going until fully resolved before yielding back
- Tool discipline: Use tools to discover information — never guess or make up answers
- Planning: Plan before each action, reflect on outcomes
- Verification: Verify work before claiming completion

# Instructions
## Task Guidance
[Domain-specific instructions at the "right altitude"]

## Tool Usage Rules
[When to use each tool group, when NOT to use them]
[Parallel calling guidance]

## Safety & Autonomy
[Reversible actions: proceed freely]
[Irreversible actions: confirm first]

## Output Format
[Explicit output contracts]

# Examples
<example>
[3-5 diverse, high-quality input/output pairs]
</example>

# Context
[Dynamic context injected at runtime — CLAUDE.md, project state, etc.]

# Reminders
[Repeat 2-3 most critical rules from Instructions section]
```

### Key Ordering Rules:
1. Role/identity FIRST (primes all subsequent behavior)
2. Behavioral rules BEFORE domain instructions
3. Long-form data/context ABOVE queries (up to 30% quality improvement)
4. Critical constraints at beginning AND end (U-shaped attention)
5. Dynamic/variable content at END only (preserves KV-cache)

---

## Technique Selection Guide

| Scenario | Technique | Why |
|---|---|---|
| Standard tool-using task | ReAct | Foundation pattern, proven in production |
| Code generation with tests | ReAct + Reflexion | Test feedback drives self-improvement |
| Complex architecture decision | Tree of Thoughts | Explore multiple approaches before committing |
| Bug diagnosis | ReAct + CoT | Step-by-step reasoning with tool grounding |
| Critical code change | LATS (if budget allows) | 94.4% on HumanEval (SOTA for prompting-only) |
| Multi-file refactoring | Orchestrator-workers | Parallel subagents with isolated context |
| Quick factual lookup | ReAct (minimal traces) | Low overhead, high accuracy |

---

## Anti-Patterns to Avoid

| Anti-Pattern | Evidence | Fix |
|---|---|---|
| ALL-CAPS MUST NEVER | Unreliable; newer models respond to normal language | Explain reasoning instead |
| Prescriptive step-by-step plans | "Think thoroughly" outperforms hand-written plans (Anthropic) | General instructions + adaptive thinking |
| >30 tools always active | Accuracy degrades, 77% slower (dbreunig) | Dynamic tool selection, keep <20 active |
| Stuffing everything into context | 65% of failures from context drift, not exhaustion | Minimal viable context |
| Compaction as emergency fallback | Causes restart behavior (OpenAI) | Design for compaction from the start |
| LLM summarization for compression | 13-15% trajectory elongation (JetBrains) | Start with observation masking |
| JSON for large document collections | Poorest performance of all formats (OpenAI) | XML with metadata or pipe-delimited |
| Guessing when context is missing | Hallucination risk | "If required context is missing, do NOT guess" |
| Stopping at partial completion | Poor UX, incomplete results | Completeness contracts with checklists |
| Tool descriptions under 2 sentences | Single most impactful failure mode (Anthropic) | 3-4+ sentences with when/what/caveats |

---

## Key Metrics to Target

| Metric | Target | Source |
|---|---|---|
| KV-cache hit rate | >80% | Manus |
| Compression trigger | 70% context utilization | Zylos Research |
| Cost reduction via caching | 45-80% | arxiv prompt caching study |
| Tool result compression ratio | 10:1 to 20:1 | Zylos Research |
| Observation masking window | 10 turns | JetBrains Research |
| Sub-agent summary size | 1-2K tokens | Anthropic |
| Max active tools | <30 (ideally <20) | Multiple sources |
| Per-step reliability needed | >99% (95% over 20 steps = only 36% success) | Context engineering research |

---

## Courses & Resources for Deeper Study

### Essential Reading (Start Here)
1. **Anthropic: Building Effective Agents** — https://www.anthropic.com/research/building-effective-agents
2. **Anthropic: Context Engineering for AI Agents** — https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents
3. **Anthropic: Claude 4 Prompting Best Practices** — https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/claude-4-best-practices
4. **OpenAI: GPT-5 Prompting Guide** — https://developers.openai.com/cookbook/examples/gpt-5/gpt-5_prompting_guide
5. **Manus: Context Engineering Lessons** — https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus

### Courses
6. **DeepLearning.AI: Agentic AI (Andrew Ng)** — https://www.deeplearning.ai/courses/agentic-ai/ — Four patterns: Reflection, Tool Use, Planning, Multi-Agent
7. **Anthropic: Interactive Prompt Engineering Tutorial** — https://github.com/anthropics/prompt-eng-interactive-tutorial

### Academic Papers
8. **ReAct** (Yao et al. 2022) — https://arxiv.org/abs/2210.03629 — Foundation pattern for tool-using agents
9. **Reflexion** (Shinn et al. 2023) — Self-improvement via verbal reinforcement learning
10. **LATS** (Zhou et al. 2023) — 94.4% HumanEval via tree search + ReAct + Reflexion

### Comprehensive Guides
11. **DAIR.AI Prompt Engineering Guide** — https://www.promptingguide.ai/ — Covers all techniques with examples
12. **Google Prompt Engineering Whitepaper** — https://www.kaggle.com/whitepaper-prompt-engineering

### Production Systems Analysis
13. **Anthropic: Multi-Agent Research System** — https://www.anthropic.com/engineering/multi-agent-research-system
14. **Anthropic: Agent Skills** — https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills
15. **Anthropic: Effective Harnesses for Long-Running Agents** — https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents
16. **OpenAI: Skills, Shell, Compaction Tips** — https://developers.openai.com/blog/skills-shell-tips
17. **JetBrains Research: Context Management** — https://blog.jetbrains.com/research/2025/12/efficient-context-management/
18. **dbreunig: How System Prompts Define Agent Behavior** — https://www.dbreunig.com/2026/02/10/system-prompts-define-the-agent-as-much-as-the-model.html

### Context Engineering Deep Dives
19. **Zylos Research: AI Agent Context Compression** — https://zylos.ai/research/2026-02-28-ai-agent-context-compression-strategies
20. **arxiv: Don't Break the Cache** — https://arxiv.org/html/2601.06007v1 — Prompt caching optimization

---

## What This Means for Our Agent

### Already Doing Well
- ReAct loop pattern (SingleAgentSession)
- Sub-agent isolation (WorkerSession, SpawnAgentTool)
- Parallel tool calling (read-only tools via coroutineScope+async)
- Compression with anchored summarization (ContextManager)
- Facts store as compression-proof memory
- File-based memory system (AgentMemoryStore)
- Tool result pruning (ToolOutputStore)
- Progressive disclosure for skills
- Doom loop detection (LoopGuard)

### Gaps to Address
1. **Tool descriptions** — Audit all 98 tools for 3-4+ sentence descriptions with when/what/caveats/NOT patterns
2. **System prompt structure** — Restructure with U-shaped critical info placement, explicit section labels
3. **Observation masking** — Consider as Phase 1 compression (before LLM summarization)
4. **Dynamic tool selection** — Currently keyword-based; could benefit from LLM-driven selection
5. **Completeness contracts** — Add explicit "verify all items covered" patterns to system prompt
6. **Todo recitation** — Plan anchor exists but could be more actively recited at end of context
7. **KV-cache optimization** — Ensure system prompt prefix is stable, append-only conversation history
8. **Context drift detection** — Monitor for re-reads, goal rewording, detail corruption
