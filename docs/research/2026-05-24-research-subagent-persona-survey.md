# Research-Subagent Persona — Industry Survey (2026-05-24)

**Purpose:** Inform the design of the new `research` sub-agent persona for the Workflow Orchestrator IntelliJ plugin. The persona will be invoked with `web_fetch` + `web_search` tools and write access to `~/.workflow-orchestrator/{proj}/agent/research/`.

**Method:** 8 parallel WebSearch queries + 3 targeted WebFetch deep-reads. Findings grounded in primary sources (Anthropic engineering blog, arxiv papers on citation hallucination, public persona repos with 100+ curated agents).

---

## 1. Canonical reference architecture — Anthropic's multi-agent research system

Anthropic's published architecture for the Claude.ai Research feature is the most credible public reference. Key design decisions (source: Simon Willison's coverage of the [Anthropic engineering post](https://simonwillison.net/2025/Jun/14/multi-agent-research-system/), verified cross-source against [ByteByteGo's analysis](https://blog.bytebytego.com/p/how-anthropic-built-a-multi-agent) and [ZenML's LLMOps database entry](https://www.zenml.io/llmops-database/building-a-multi-agent-research-system-for-complex-information-tasks)):

1. **Three-role hierarchy.** LeadResearcher (Opus) → parallel subagents (Sonnet) → Citation/Review agent. The LeadResearcher decomposes, the subagents fan out with isolated context windows, the citation agent verifies before user-facing return.
2. **Plan-first, persist plan to memory.** "The LeadResearcher begins by thinking through the approach and saving its plan to Memory to persist the context" — survives context overflow.
3. **Subagent contract:** each receives "an objective, an output format, guidance on the tools and sources to use, and clear task boundaries."
4. **OODA loop inside each subagent:** "observe, orient, decide, act" — observe gathered info, orient to optimal tools, decide on actions, act.
5. **Filesystem handoff, not chat-style return.** "Instead of subagents reporting findings back through chat-style returns — long, lossy, expensive on lead-agent tokens — they write to a shared filesystem and return a lightweight reference."
6. **Parallel tool invocation is mandatory.** Lead directive: "invoke all relevant tools simultaneously rather than sequentially" — applies inside each subagent too.
7. **Performance claim:** Opus-lead + Sonnet-subagents outperformed single-agent Opus 4 by **90.2%** on Anthropic's internal research eval.

**Failure modes Anthropic specifically observed and had to mitigate:**

- "Spawning 50 subagents for simple queries" → over-decomposition needs explicit ceiling.
- "Scouring the web endlessly for nonexistent sources" → need an explicit stop-when-found rule.
- "Distracting each other with excessive updates" → cross-agent chatter is anti-productive.
- "Consistently chose SEO-optimized content farms over authoritative but less highly-ranked sources" → need explicit source-credibility teaching in the persona.

These four failure modes are the single most useful signal for our persona body — every one of them translates to a section in the system prompt.

---

## 2. Published persona patterns

### A. wshobson/agents (191 agents, multi-harness)

Repo at [github.com/wshobson/agents](https://github.com/wshobson/agents) — production-ready agentic plugin marketplace for Claude Code, Codex CLI, Cursor, OpenCode, Gemini CLI. Per [the agents.md doc](https://github.com/wshobson/agents/blob/main/docs/agents.md), every agent is a markdown file with YAML frontmatter and a body system prompt. Model assignment is per-agent (Haiku for fast/cheap, Sonnet for default work, Opus for complex reasoning). Per-tool access lists are normalised across harnesses.

Concrete observation: the repo ships specialists like `search-specialist` (Haiku) for "advanced web research and information synthesis" — short turnaround work — and dedicated analysts (Sonnet/Opus) for deeper investigations. **Pattern: choose model by task complexity, not by persona prestige.**

### B. VoltAgent/awesome-claude-code-subagents — Category 10: Research & Analysis

The [VoltAgent collection](https://github.com/VoltAgent/awesome-claude-code-subagents) ships a dedicated research category with 6 personas: `research-analyst`, `search-specialist`, `scientific-literature-researcher`, `market-researcher`, `trend-analyst`, `competitive-analyst`. Each has a single tight purpose. The repo's [`research-analyst.md`](https://raw.githubusercontent.com/VoltAgent/awesome-claude-code-subagents/main/categories/10-research-analysis/research-analyst.md) is ~800 lines and structures the persona body around:

1. **Source verification ladder** — credibility assessment, bias detection, fact verification, cross-referencing, currency, authority, accuracy, relevance scoring.
2. **Triangulation rule** — multiple sources required before a claim is asserted.
3. **Structured output** — executive summary → detailed findings → data visualisation → source citations → recommendations → action items.
4. **JSON progress markers** — sources analysed, data points collected, insights generated, confidence level.
5. **Integration points** — explicit collaboration rules with 6 complementary agents (prevents siloed research).

For OUR plugin's persona, sections 1-4 are directly portable; section 5 doesn't apply because our sub-agent system is hub-and-spoke (no peer agents).

### C. CrewAI researcher pattern

Per [CrewAI's "Crafting Effective Agents" guide](https://docs.crewai.com/en/guides/agents/crafting-effective-agents) and [DigitalOcean's tutorial](https://www.digitalocean.com/community/tutorials/crewai-crash-course-role-based-agent-orchestration), the canonical CrewAI researcher uses a triple of `role` + `goal` + `backstory`. CrewAI's #1 best practice for researcher agents: **"A Researcher Agent must stay factual and cite sources."** Recommended role specificity: `"{topic} Senior Data Researcher"` not just `"Researcher"`.

For our context: the YAML `description:` field plays the combined role+goal+backstory role.

### D. LangGraph supervisor pattern

Per [LangChain's supervisor docs](https://reference.langchain.com/python/langgraph-supervisor) and [machinelearningplus.com](https://machinelearningplus.com/gen-ai/langgraph-multi-agent-systems-supervisor-swarm-network/), the canonical research-team supervisor pattern is **planner → researcher → writer** in fixed order. Workers append to shared state; supervisor decides next. Not directly applicable to our single-shot sub-agent invocation but informs the persona's internal structure (plan → research → write).

---

## 3. Citation hallucination — hard numbers

The single most important pitfall for a research subagent. Per [arxiv:2604.03173 "Detecting and Correcting Reference Hallucinations in Commercial LLMs and Deep Research Agents"](https://arxiv.org/pdf/2604.03173) and [arxiv:2602.23452 "CiteAudit"](https://arxiv.org/pdf/2602.23452):

- **3–13% of citation URLs are hallucinated** — no record in the Wayback Machine, likely never existed.
- **5–18% of citation URLs are non-resolving overall** (hallucinated + 404 + dead links).
- **Deep research agents generate more citations than search-augmented LLMs but hallucinate at higher rates** — fan-out amplifies the problem.
- **Domain effects pronounced:** non-resolving rates 5.4% (Business) → 11.4% (Theology); per-model effects even larger.
- **Knowledge-boundary triggers for fabrication:** long-tail topics, recently-published content (temporal cutoffs), restricted content.

Mitigation pattern that works: every URL the agent cites must come from a tool call result, not from model-generated knowledge. Our `web_fetch` + `web_search` pipeline naturally enforces this — the persona just needs an explicit rule "never cite a URL the agent has not itself fetched or surfaced via web_search."

---

## 4. Recommended persona body structure (synthesis)

Based on the four sources above, the recommended sections for a `research.md` persona body, in order:

| # | Section | Why |
|---|---------|-----|
| 1 | **Role** (1-2 sentences) | CrewAI: be specific, name a real-world profession. e.g. "You are a senior research analyst doing thorough external research on a topic for a software engineer." |
| 2 | **Tools and scope** (3-5 bullets) | Anthropic: "clear task boundaries". List web_fetch / web_search / create_file (research/ only) / task_report. State what's NOT available (no code reads, no shell, no other-domain tools). |
| 3 | **The OODA loop** (1 paragraph) | Anthropic-direct: observe → orient → decide → act per iteration. Explicit anti-pattern: do not pre-plan all searches upfront; let each result steer the next. |
| 4 | **Parallel tool invocation rule** (1 sentence) | Anthropic: "invoke all relevant tools simultaneously rather than sequentially". |
| 5 | **Source credibility ladder** (small table) | VoltAgent + Anthropic-failure: explicit ranking — primary sources (specs, RFCs, authoritative docs, published papers, official engineering blogs) > secondary (well-known engineering blogs with citations) > tertiary (community articles, Stack Overflow) > AVOID (SEO content farms, AI-generated SEO articles). |
| 6 | **Citation rule** (1 sentence) | arxiv:2604.03173: every URL cited must come from a tool call result; never fabricate URLs from training knowledge. |
| 7 | **Triangulation rule** (1-2 sentences) | VoltAgent: every substantive claim must be cross-referenced across ≥2 sources, or explicitly flagged as single-source. |
| 8 | **Stop conditions** (3-4 bullets) | Anthropic-failure: explicit triggers for STOPPING research — "enough sources to triangulate the main claims", "diminishing returns (last 3 fetches added no new info)", "explicit token/iteration cap reached", "user-defined scope satisfied". |
| 9 | **Output contract** (structured) | VoltAgent: every research session produces a single markdown file at `~/.workflow-orchestrator/{proj}/agent/research/YYYY-MM-DD-{topic-slug}.md` with fixed sections: Title / Research Question / Method / Sources (with URL + retrieval date + credibility tier) / Findings / Limitations / Open Questions. `task_report` includes the file path as `nextSteps` so the parent agent can read it. |
| 10 | **Anti-patterns to avoid** (4 bullets, lifted from Anthropic's observed failures) | (a) Don't spawn excessive parallel fan-out; (b) don't keep searching for non-existent sources — admit gap; (c) don't pad findings with low-credibility filler; (d) don't prefer SEO-friendly articles over primary sources. |

**Total persona body length:** ~120–180 lines. Modeled on the bundled `explorer.md` (140 lines) — same shape, different domain.

---

## 5. Output-file format (recommendation)

```markdown
---
topic: <kebab-case topic slug>
question: <one-line original research question>
researched-by: workflow-orchestrator research subagent
session-id: <agent session id>
retrieved-at: <ISO-8601 date>
sources-consulted: <count>
sources-cited: <count>
---

# <Title>

## Research question
<verbatim from the parent agent>

## Method
<which queries were issued, which URLs were fetched, in what order. Brief — 3-6 bullets.>

## Sources
| # | URL | Retrieved | Credibility |
|---|-----|-----------|-------------|
| 1 | https://... | 2026-05-24 | Primary (W3C spec) |
| 2 | https://... | 2026-05-24 | Secondary (engineering blog) |

## Findings
<the actual research. Each substantive claim cites [N] from the Sources table.>

## Limitations
<honest list of what couldn't be answered: gaps, conflicting sources, expired pages, etc.>

## Open questions
<what would need further work>
```

This format is greppable, future-readable by both humans and the parent agent via `read_file`, and structurally identical to memory files (so the same MEMORY.md indexing convention could extend to research files later without rework).

---

## 6. Recommendations for our persona

Direct calls to make in the spec:

1. **Persona file location:** `agent/src/main/resources/agents/research.md` (bundled). User override path: `~/.workflow-orchestrator/agents/research.md` per existing convention.
2. **Tool allowlist:** `web_fetch, web_search, create_file, edit_file, read_file, task_report`. `read_file` is essential so the persona can re-read its own in-progress research file mid-iteration. `edit_file` lets it incrementally extend the file rather than rewriting wholesale. `create_file` and `edit_file` are gated by PathValidator to the new `research/` root only.
3. **Model assignment:** Sonnet (matches Anthropic's subagent role). Opus only on user opt-in via `agent(... model="opus")`. Per `feedback_sonnet_for_small_tasks.md`, Sonnet is the canonical default for delegated work.
4. **Memory:** `memory: project` — the persona benefits from MEMORY.md project context so it knows what the project cares about.
5. **`prompt-sections`:** default everything on except `editing-files` (the persona only writes to research/, not to project code; the editing-files section is misleading guidance).
6. **Fan-out:** disable. The new persona is single-shot — no `prompt_2..5` for parallel research. (Anthropic's fan-out is at the LeadResearcher level, not within a single research subagent. The Workflow Orchestrator's orchestrator can already fan out via `agent(prompt_2=..., prompt_3=...)`.)
7. **PathValidator change:** add `{agentDir}/research/` as a third allowed write root alongside `{projectBase}` and `{agentDir}/memory/`. Wired through `resolveAndValidateForWrite`.

---

## Sources

- [How we built our multi-agent research system — Anthropic (Simon Willison summary)](https://simonwillison.net/2025/Jun/14/multi-agent-research-system/)
- [How Anthropic Built a Multi-Agent Research System — ByteByteGo](https://blog.bytebytego.com/p/how-anthropic-built-a-multi-agent)
- [Anthropic: Building a Multi-Agent Research System for Complex Information Tasks — ZenML LLMOps Database](https://www.zenml.io/llmops-database/building-a-multi-agent-research-system-for-complex-information-tasks)
- [Anthropic's Multi-Agent Blueprint: What Production Adds — Fountain City](https://fountaincity.tech/resources/blog/anthropic-multi-agent-blueprint-production/)
- [wshobson/agents — multi-harness agent marketplace (191 agents)](https://github.com/wshobson/agents)
- [wshobson/agents — agents.md doc](https://github.com/wshobson/agents/blob/main/docs/agents.md)
- [VoltAgent/awesome-claude-code-subagents — 100+ specialised subagents incl. Category 10 Research & Analysis](https://github.com/VoltAgent/awesome-claude-code-subagents)
- [VoltAgent/awesome-claude-code-subagents — research-analyst.md (raw)](https://raw.githubusercontent.com/VoltAgent/awesome-claude-code-subagents/main/categories/10-research-analysis/research-analyst.md)
- [CrewAI — Crafting Effective Agents guide](https://docs.crewai.com/en/guides/agents/crafting-effective-agents)
- [CrewAI: A Practical Guide to Role-Based Agent Orchestration — DigitalOcean](https://www.digitalocean.com/community/tutorials/crewai-crash-course-role-based-agent-orchestration)
- [LangGraph Multi-Agent Supervisor — LangChain docs](https://reference.langchain.com/python/langgraph-supervisor)
- [LangGraph Multi-Agent: Supervisor, Swarm & Network — machinelearningplus](https://machinelearningplus.com/gen-ai/langgraph-multi-agent-systems-supervisor-swarm-network/)
- [Detecting and Correcting Reference Hallucinations in Commercial LLMs and Deep Research Agents — arxiv:2604.03173](https://arxiv.org/pdf/2604.03173)
- [CiteAudit: You Cited It, But Did You Read It? — arxiv:2602.23452](https://arxiv.org/pdf/2602.23452)
- [Do Deployment Constraints Make LLMs Hallucinate Citations? — arxiv:2603.07287](https://arxiv.org/pdf/2603.07287)
- [Introducing deep research — OpenAI](https://openai.com/index/introducing-deep-research/)
- [Deep Research — Prompt Engineering Guide](https://www.promptingguide.ai/guides/deep-research)
- [Awesome Deep Research Prompts — langgptai](https://github.com/langgptai/awesome-deep-research-prompts)
- [Create custom subagents — Claude Code Docs](https://code.claude.com/docs/en/sub-agents)
