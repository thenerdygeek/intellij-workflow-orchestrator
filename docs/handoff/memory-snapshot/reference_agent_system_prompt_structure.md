# Agent System Prompt Structure & Ordering — Research Findings

Researched: 2026-03-28
Sources: Anthropic, OpenAI, Google, Microsoft, DeepLearning.AI, LangChain, academic papers

---

## EXECUTIVE SUMMARY

There is strong consensus across all major providers on system prompt structure for tool-using agents. The key findings:

1. **Section order matters** — all providers recommend a specific section hierarchy
2. **Tool position matters** — empirical research proves positional bias in tool selection
3. **Instructions at the end** outperform instructions at the beginning (Anthropic: up to 30% improvement)
4. **Tool descriptions deserve as much engineering as the prompt itself** (Anthropic)
5. **Explicit tool selection guidance** in the system prompt dramatically reduces ambiguity (OpenAI, Anthropic)
6. **Progressive disclosure** beats dumping everything upfront (Anthropic Agent Skills)

---

## 1. ANTHROPIC — CONTEXT ENGINEERING FOR AGENTS

Sources:
- https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents
- https://www.anthropic.com/engineering/writing-tools-for-agents
- https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills
- https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/claude-4-best-practices
- https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/long-context-tips

### System Prompt Structure Guidance

**Ordering rule for long context:** Place longform data/documents at the TOP of the prompt, above queries and instructions. Queries and instructions at the END improve response quality by up to 30% in tests, especially with complex, multi-document inputs.

**Rationale:** There is an inverse relationship between recall performance and the distance of relevant content from the end of the prompt. Instructions at the end ensures Claude's recall of them is as high as possible.

### Tool Description Engineering

**Core principle:** "Think about how much effort goes into human-computer interfaces (HCI), and plan to invest just as much effort in creating good agent-computer interfaces (ACI)."

**Actionable rules:**
1. Tool definitions deserve as much prompt engineering attention as overall prompts
2. A good tool definition includes: example usage, edge cases, input format requirements, clear boundaries from other tools
3. Input parameters should be unambiguously named (e.g., `user_id` not `user`)
4. Think of describing the tool to a new hire — make implicit context explicit (specialized query formats, niche terminology, resource relationships)
5. Bloated tool sets that cover too much functionality or create ambiguous decision points are the most common failure mode
6. **If a human engineer can't definitively say which tool should be used in a given situation, an agent can't be expected to do better**

### Progressive Disclosure (Agent Skills Pattern)

**Core design principle:** Like a well-organized manual — table of contents first, then specific chapters, then detailed appendix. Load information only as needed.

This is directly applicable to structuring tool descriptions: give a summary/index of capabilities first, detailed tool specs only when relevant.

### Claude 4 Specific

- Adaptive thinking for agentic workloads (multi-step tool use, complex coding, long-horizon loops)
- After receiving tool results: "carefully reflect on quality and determine optimal next steps before proceeding"
- Parallel tool execution: prompt with "invoke all relevant tools simultaneously rather than sequentially" for ~100% parallel success rate
- If using context compaction, inform Claude in the system prompt so it doesn't stop tasks early due to token budget concerns
- Providing motivation behind instructions (WHY something matters) helps Claude deliver more targeted responses

---

## 2. OPENAI — GPT-4.1 & O3/O4-MINI FUNCTION CALLING GUIDES

Sources:
- https://cookbook.openai.com/examples/gpt4-1_prompting_guide
- https://developers.openai.com/cookbook/examples/o-series/o3o4-mini_prompting_guide
- https://developers.openai.com/api/docs/guides/function-calling

### GPT-4.1 Recommended Section Order (EXPLICIT)

OpenAI explicitly recommends this section ordering for agentic system prompts:

```
1. # Role & Objective          — Who the model is, what its main goal is
2. # Instructions              — Steps, rules, actions to follow
3.   [Sub-sections as needed]  — Tool-specific guidance, decision boundaries
4. # Reasoning Steps           — Step-by-step thinking process
5. # Output Format             — How the answer should be structured
6. # Context                   — Background information for the task
```

### Three Key Reminders for Agentic Prompts

GPT-4.1's guide says ALL agent prompts should include:

1. **Persistence reminder** — "You are an agent. Keep going until the task is fully complete. Do not end prematurely."
2. **Tool-calling reminder** — "Use your tools. Do not guess or hallucinate answers. If a tool can answer it, call the tool."
3. **Planning reminder (optional)** — "Plan and reflect on each tool call in text before making it."

### Tool Selection Boundaries

- If multiple tools can fulfill similar roles, **explicitly define tool usage boundaries in the developer prompt**
- Instruct which tool is preferred and when — reduces ambiguity, improves accuracy, avoids tool overuse/underuse
- Include examples and edge cases, especially to fix recurring failures
- When mixing tools, **define decision boundaries and be explicit about when to use one tool over another**

### O-Series (Reasoning Models) Caveat

- Do NOT ask reasoning models to "plan more" before tool calls — they reason internally, and prompting for more reasoning can HURT performance
- Explicitly outline the ORDER for accomplishing tasks, as models can make ordering mistakes
- Up to ~100 tools and ~20 args per tool is "in-distribution" for reliable performance

---

## 3. GOOGLE — GEMINI 3 PROMPTING GUIDE

Sources:
- https://docs.cloud.google.com/vertex-ai/generative-ai/docs/start/gemini-3-prompting-guide
- https://ai.google.dev/gemini-api/docs/prompting-strategies
- https://docs.cloud.google.com/vertex-ai/generative-ai/docs/learn/prompts/system-instructions

### Structure Rules

1. Use clear delimiters: XML-style tags (`<context>`, `<task>`) or Markdown headings — pick ONE format, use consistently
2. **Place core request and most critical restrictions as the FINAL LINE of your instruction**
3. **Place negative constraints at the end** (what NOT to do)
4. For large datasets: put specific instructions/questions at the END, after the data context
5. Anchor reasoning to provided data with phrases like "Based on the information above..."

### Agent-Specific Guidance

- Complex agents benefit from prompts that enforce: persistence, risk assessment, proactive planning
- For deep agentic workflows, control the trade-off between computational cost and task accuracy
- Consider dimensions: logical decomposition (constraint/operation analysis) and problem diagnosis (depth of cause analysis)
- Explicitly reinforce current date in system instructions for time-sensitive queries

### System Instruction Design

- System instructions are processed BEFORE prompts
- Use them for: persona, contextual information, formatting instructions
- Gemini 3 follows complex instructions with minimal prompt tuning and reliably calls tools

---

## 4. MICROSOFT — AUTOGEN / SEMANTIC KERNEL / AGENT FRAMEWORK

Sources:
- https://learn.microsoft.com/en-us/agent-framework/overview/
- https://devblogs.microsoft.com/semantic-kernel/microsofts-agentic-ai-frameworks-autogen-and-semantic-kernel/

### Key Architectural Pattern

Microsoft's Agent Framework converges AutoGen + Semantic Kernel:
- Agent creation includes: temperature, instructions, and tool registration as co-equal configuration
- Common runtime with shared abstractions for agent orchestration
- Graph-based workflows for explicit multi-agent orchestration

### Prompt Implications

- Instructions are a first-class parameter alongside tools — they are designed to be composed together
- Session-based state management means the system prompt should be stateless (session handles state)
- The framework handles tool registration separately from instructions, suggesting they should be conceptually separate in your prompt design

---

## 5. DEEPLEARNING.AI — AGENTIC AI COURSES

Sources:
- https://learn.deeplearning.ai/courses/agentic-ai/information
- https://www.deeplearning.ai/short-courses/chatgpt-prompt-engineering-for-developers/

### Four Agent Design Patterns (Andrew Ng)

1. **Reflection** — AI critiques its own work and iterates
2. **Tool Use** — Connect to databases, APIs, external services
3. **Planning** — Break complex tasks into executable steps
4. **Multi-agent** — Multiple specialized agents collaborating

### Prompt Implications from Course Content

- Chain-of-thought, multi-step reasoning should be built into the system prompt structure
- Classifying user queries first (routing) before executing is a recommended pattern
- Safety evaluation of queries should happen as a structured step

No specific guidance on section ordering within system prompts was found in the course materials.

---

## 6. LANGCHAIN / LANGGRAPH

Sources:
- https://python.langchain.com/docs/tutorials/agents
- https://api.python.langchain.com/en/latest/agents/langchain.agents.react.agent.create_react_agent.html

### ReAct Agent Prompt Structure

LangChain's `create_react_agent` uses a specific prompt template structure:
1. System prompt (role + instructions)
2. Tools list (injected automatically by framework)
3. Agent scratchpad (intermediate reasoning steps)

### Key Patterns

- The off-the-shelf ReAct agent manages state as a list of messages and continues until no tool calls remain in output
- Multi-agent patterns: design patterns shown in notebooks, combine with fundamental patterns for best performance
- Tools are registered separately and injected into prompts by the framework — suggesting tool descriptions should be self-contained units that work regardless of where they're placed

---

## 7. EMPIRICAL RESEARCH — TOOL ORDERING AND POSITIONAL BIAS

### BiasBusters Paper (2025)
Source: https://arxiv.org/html/2510.00307v1

**"Uncovering and Mitigating Tool Selection Bias in Large Language Models"**

**Critical finding: LLMs prefer tools that appear EARLIER in the prompt (positional bias).**

**Methodology:**
- Execute each query 5 times with different cyclic rotations of tool ordering
- Ensures every tool appears at the top exactly once per query
- Total variation-based metrics to quantify selection imbalances
- Novel benchmark of clusters of tools with equivalent functionality

**Models tested:** GPT-3.5-turbo, GPT-4.1-mini, Claude 3.5 Sonnet, DeepSeek R1, Gemini 2.5 Flash, ToolLLaMA-2-7B, Qwen3 (1.7B to 235B)

**Key findings:**
1. Tool choices are far from uniform — in some clusters, ALL models concentrate heavily on a single API
2. Positional bias exists across all tested models
3. **Bias degrades user experience** by repeatedly selecting slow/unreliable services and inflates operational costs
4. Tool names have strong influence on selection (beyond just position)
5. Parameter schemas significantly affect usability

**Implication for your agent:** If you want tool X to be preferred, place it EARLIER in the tool list. But also invest in its name and description quality.

### Additional Positional Bias Research

Source: https://openreview.net/pdf/42b5c90351fef4c9a0ff4c90f48cc99040cb1b8f.pdf

This confirms the positional bias finding and introduces mitigation strategies including cyclic rotation and calibrated scoring.

---

## 8. SYNTHESIZED GUIDELINES — ACTIONABLE STRUCTURE

Based on all findings, here is the consensus optimal structure for an AI agent system prompt:

### Recommended Section Order

```
┌─────────────────────────────────────────────────┐
│ 1. ROLE & IDENTITY                              │  ← Who the agent is, persona
│    - Name, role, expertise domain                │
│    - Knowledge cutoff date                       │
├─────────────────────────────────────────────────┤
│ 2. LONG-FORM CONTEXT / DATA (if any)            │  ← Documents, reference data
│    - Placed high for long-context performance    │
│    - Wrapped in clear delimiters (<doc> tags)    │
├─────────────────────────────────────────────────┤
│ 3. TOOL CATALOG (ordered by priority)           │  ← Tool descriptions
│    - PREFERRED tools listed FIRST (positional    │
│      bias makes earlier tools more likely used)  │
│    - Each tool: description, examples, edge      │
│      cases, boundaries with other tools          │
│    - Self-contained (works without reading rest) │
├─────────────────────────────────────────────────┤
│ 4. TOOL SELECTION RULES                         │  ← Decision boundaries
│    - When to use tool A vs tool B                │
│    - Explicit preferred-tool guidance             │
│    - "If X, use Y. If Z, use W."                │
│    - Examples of correct tool routing            │
├─────────────────────────────────────────────────┤
│ 5. BEHAVIORAL INSTRUCTIONS                      │  ← How to behave
│    - Persistence: "Keep going until done"        │
│    - Tool-calling: "Use tools, don't guess"      │
│    - Planning: "Plan before acting" (optional)   │
│    - Parallel execution guidance                 │
│    - Error recovery patterns                     │
├─────────────────────────────────────────────────┤
│ 6. CONSTRAINTS & SAFETY                         │  ← What NOT to do
│    - Negative constraints at the END (Gemini)    │
│    - Safety boundaries, forbidden actions        │
│    - Scope limitations                           │
├─────────────────────────────────────────────────┤
│ 7. OUTPUT FORMAT                                │  ← How to respond
│    - Response structure, formatting rules         │
│    - When to use text vs tool calls              │
└─────────────────────────────────────────────────┘
```

### Key Ordering Principles (with sources)

| Principle | Source | Evidence Level |
|---|---|---|
| Long data at top, instructions at bottom | Anthropic | Empirical (30% improvement) |
| Preferred tools listed first in tool catalog | BiasBusters paper | Empirical (multi-model) |
| Role/identity comes first | OpenAI GPT-4.1 guide | Prescriptive |
| Negative constraints at the end | Google Gemini 3 guide | Prescriptive |
| Tool decision boundaries in system prompt | OpenAI o3/o4-mini guide | Prescriptive |
| Persistence + tool-calling reminders required | OpenAI GPT-4.1 guide | Prescriptive |
| Tool descriptions = ACI, invest like HCI | Anthropic | Prescriptive |
| Progressive disclosure for large tool sets | Anthropic Agent Skills | Architectural |
| Don't ask reasoning models to plan more | OpenAI o-series guide | Empirical (hurts perf) |

### Does "Preferred Tool" Guidance Before vs After Tool List Matter?

**Direct answer based on research:**

1. **Positional bias (BiasBusters):** Tools listed earlier are selected more. So putting preferred tools first in the tool list itself is the most reliable mechanism.

2. **Instruction recall (Anthropic long-context):** Instructions placed AFTER content have up to 30% better recall. So tool selection rules placed AFTER the tool list would be better recalled.

3. **Recommended approach:** Put preferred tools FIRST in the tool list (exploits positional bias) AND put explicit tool selection rules AFTER the tool list (exploits instruction recall). This double-reinforcement approach uses both biases in your favor.

4. No direct empirical study was found that specifically compares "preferred tool guidance before tool list" vs "after tool list." But the two proven biases (positional for tools, recency for instructions) strongly suggest: **tools first, then selection rules** is optimal.

---

## 9. ANTI-PATTERNS (What NOT to Do)

| Anti-Pattern | Why It Fails | Source |
|---|---|---|
| Putting all business logic only in tool descriptions | Agent misses cross-tool coordination rules | Anthropic |
| Ambiguous tool boundaries ("use X or Y for this") | Agent can't decide, picks randomly/positionally | OpenAI, Anthropic |
| Asking reasoning models to "think harder" before tools | Actually hurts performance | OpenAI o-series |
| Dumping 50+ tools without grouping or hierarchy | Decision fatigue, poor selection | Anthropic |
| Putting instructions before long context data | Up to 30% worse recall | Anthropic |
| Not including examples in tool descriptions | Miss edge cases, format errors | OpenAI, Anthropic |
| Generic parameter names (user, id, data) | Ambiguity leads to wrong arguments | Anthropic |
| No persistence/tool-calling reminders | Agent stops early or hallucinates answers | OpenAI GPT-4.1 |

---

## 10. PROVIDER-SPECIFIC QUIRKS

| Provider | Quirk |
|---|---|
| **Anthropic Claude 4** | Responds to motivation ("why this matters"); parallel tool calling needs explicit prompt; adaptive thinking for agentic loops |
| **OpenAI o3/o4-mini** | System message auto-converts to developer message; DON'T prompt for extra reasoning; explicitly outline task ordering |
| **OpenAI GPT-4.1** | Needs explicit persistence reminder or stops early; benefits from planning reminder |
| **Google Gemini 3** | Place critical restrictions as final line; XML tags or Markdown headings (pick one, be consistent); reinforce current date |
| **All models** | Positional bias in tool selection is universal across all tested models |
