# Anthropic Official Prompt Engineering & Agentic AI Guidance

> Comprehensive research compiled from Anthropic's official documentation, engineering blog, and research publications.
> Last updated: 2026-03-26

---

## Table of Contents

1. [Source URLs](#source-urls)
2. [Core Prompt Engineering Principles](#core-prompt-engineering-principles)
3. [Tool Use Best Practices](#tool-use-best-practices)
4. [Building Effective Agents](#building-effective-agents)
5. [Context Engineering for AI Agents](#context-engineering-for-ai-agents)
6. [Agent Harnesses for Long-Running Tasks](#agent-harnesses-for-long-running-tasks)
7. [Multi-Agent Research Systems](#multi-agent-research-systems)
8. [Agent Skills & Progressive Disclosure](#agent-skills--progressive-disclosure)
9. [Claude Agent SDK Patterns](#claude-agent-sdk-patterns)
10. [Agentic System Prompts](#agentic-system-prompts)
11. [Anti-Patterns to Avoid](#anti-patterns-to-avoid)
12. [Key Takeaways for Our Plugin](#key-takeaways-for-our-plugin)

---

## Source URLs

| Resource | URL |
|---|---|
| Prompt Engineering Overview | https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/overview |
| Prompting Best Practices (Claude 4) | https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/claude-4-best-practices |
| Building Effective Agents | https://www.anthropic.com/research/building-effective-agents |
| Context Engineering for AI Agents | https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents |
| Effective Harnesses for Long-Running Agents | https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents |
| Multi-Agent Research System | https://www.anthropic.com/engineering/multi-agent-research-system |
| Agent Skills | https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills |
| Claude Agent SDK | https://www.anthropic.com/engineering/building-agents-with-the-claude-agent-sdk |
| Tool Use Implementation | https://platform.claude.com/docs/en/agents-and-tools/tool-use/implement-tool-use |
| Skill Authoring Best Practices | https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices |

---

## Core Prompt Engineering Principles

Source: [Prompting Best Practices](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/claude-4-best-practices)

### 1. Be Clear and Direct

- Claude responds to explicit instructions. If you want "above and beyond" behavior, explicitly request it.
- **Golden rule:** Show your prompt to a colleague with minimal context. If they'd be confused, Claude will be too.
- Be specific about desired output format and constraints.
- Provide instructions as sequential steps using numbered lists or bullet points when order matters.

**Example:**
```
# Less effective:
Create an analytics dashboard

# More effective:
Create an analytics dashboard. Include as many relevant features and interactions
as possible. Go beyond the basics to create a fully-featured implementation.
```

### 2. Add Context / Motivation

Explaining WHY an instruction matters helps Claude generalize beyond the specific rule.

```
# Less effective:
NEVER use ellipses

# More effective:
Your response will be read aloud by a text-to-speech engine, so never use ellipses
since the text-to-speech engine will not know how to pronounce them.
```

### 3. Use Examples Effectively (Few-Shot Prompting)

- 3-5 examples for best results
- Make examples **relevant** (mirror actual use case), **diverse** (cover edge cases), and **structured** (wrap in `<example>` tags)
- You can ask Claude to evaluate your examples for relevance and diversity

### 4. Structure Prompts with XML Tags

- Use consistent, descriptive tag names: `<instructions>`, `<context>`, `<input>`
- Nest tags for natural hierarchies: `<documents>` containing `<document index="n">`
- XML tags reduce misinterpretation when mixing instructions, context, examples, and variable inputs

### 5. Give Claude a Role

Set a role in the system prompt to focus behavior and tone:
```python
system="You are a helpful coding assistant specializing in Python."
```

### 6. Long Context Prompting (20k+ tokens)

- **Put longform data at the top**, above query/instructions/examples (up to 30% quality improvement)
- Structure documents with XML tags: `<document index="1"><source>...</source><document_content>...</document_content></document>`
- **Ground responses in quotes**: Ask Claude to quote relevant parts before carrying out its task

### 7. Output and Formatting Control

- Tell Claude what to do **instead of** what not to do
- Use XML format indicators: `<smoothly_flowing_prose_paragraphs>`
- Match your prompt style to desired output style (reducing markdown in your prompt reduces markdown in output)
- Claude 4.6 is more concise by default; may skip summaries after tool calls. If you want visibility: "After completing a task that involves tool use, provide a quick summary of the work you've done."

### 8. Thinking and Reasoning

- **Prefer general instructions over prescriptive steps**: "think thoroughly" often produces better reasoning than hand-written step-by-step plans
- **Multishot examples work with thinking**: Use `<thinking>` tags in few-shot examples
- **Ask Claude to self-check**: "Before you finish, verify your answer against [test criteria]"
- **Adaptive thinking** (Claude 4.6): Use `thinking: {type: "adaptive"}` — Claude dynamically decides when and how much to think. In internal evaluations, adaptive thinking reliably drives better performance than extended thinking.
- For overthinking: "Choose an approach and commit to it. Avoid revisiting decisions unless you encounter new information that directly contradicts your reasoning."

---

## Tool Use Best Practices

Source: [Tool Use Implementation](https://platform.claude.com/docs/en/agents-and-tools/tool-use/implement-tool-use)

### Tool Descriptions (Most Important Factor)

**Minimum 3-4 sentences per tool**, more for complex tools. Explain:
- What the tool does
- When it should be used (and when it shouldn't)
- What each parameter means and how it affects behavior
- Important caveats and limitations
- What information the tool does/doesn't return

```json
// GOOD:
{
  "name": "get_stock_price",
  "description": "Retrieves the current stock price for a given ticker symbol. The ticker symbol must be a valid symbol for a publicly traded company on a major US stock exchange like NYSE or NASDAQ. The tool will return the latest trade price in USD. It should be used when the user asks about the current or most recent price of a specific stock. It will not provide any other information about the stock or company.",
  "input_schema": { ... }
}

// BAD:
{
  "name": "get_stock_price",
  "description": "Gets the stock price for a ticker.",
  "input_schema": { ... }
}
```

### Tool Organization

- **Consolidate related operations** into fewer tools with an `action` parameter (reduces selection ambiguity)
- **Use meaningful namespacing**: prefix with service/resource (`github_list_prs`, `slack_send_message`)
- **Provide input_examples** for tools with nested objects, optional parameters, or format-sensitive inputs

### Tool Response Design

- Return **semantic, stable identifiers** (slugs, UUIDs) not opaque internal references
- Include **only fields Claude needs** to reason about its next step
- Avoid bloated responses that waste context

### Parallel Tool Calling

Claude excels at parallel tool execution. Boost to ~100% with:
```xml
<use_parallel_tool_calls>
If you intend to call multiple tools and there are no dependencies between the calls,
make all of the independent tool calls in parallel. Never use placeholders or guess
missing parameters in tool calls.
</use_parallel_tool_calls>
```

### Error Handling

- Return errors with `is_error: true` and clear error messages
- Claude can recover from tool errors and try alternative approaches
- Provide specific error messages that help Claude fix issues

### Tool Result Formatting

- Tool results MUST come before text in content arrays
- Content can be string, text blocks, or mixed content (text + images + documents)

### Agent-Computer Interface (ACI) Design

Think of tool design with the rigor applied to human-computer interfaces:
- **Clarity**: Usage patterns obvious from descriptions and parameters
- **Parameter naming**: Descriptive names, clear boundaries
- **Poka-yoke design**: Make errors harder (e.g., require absolute filepaths instead of relative)
- **Testing**: Run diverse inputs to identify usage mistakes

> "Anthropic's SWE-bench agent required more optimization effort on tools than overall prompts."

---

## Building Effective Agents

Source: [Building Effective Agents](https://www.anthropic.com/research/building-effective-agents)

### Core Philosophy

> "Success in the LLM space isn't about building the most sophisticated system. It's about building the right system for your needs."

Start with the simplest solution. Only increase complexity when measurably justified.

### Agents vs. Workflows

| Concept | Definition | When to Use |
|---|---|---|
| **Workflows** | LLMs + tools orchestrated through predefined code paths | Predictable, well-defined tasks requiring consistency |
| **Agents** | LLMs dynamically direct their own processes and tool usage | Open-ended problems where step counts can't be predetermined |

### Workflow Patterns

#### 1. Prompt Chaining
Sequential LLM calls where each processes previous output. Add programmatic "gates" to validate intermediate results.
- **Use case**: Fixed subtask sequences (generate then translate, outline then write)

#### 2. Routing
Classify inputs and direct to specialized handlers. Enables separation of concerns.
- **Use case**: Different query types handled differently (simple -> Haiku, complex -> Sonnet/Opus)

#### 3. Parallelization
Run LLM instances simultaneously. Two variants:
- **Sectioning**: Independent subtasks in parallel (guardrails, evaluations)
- **Voting**: Same task multiple times for confidence (code review, content moderation)

#### 4. Orchestrator-Workers
Central LLM dynamically breaks tasks into subtasks, delegates to workers, synthesizes results. Unlike parallelization, subtasks are determined at runtime.
- **Use case**: Complex tasks (multi-file code changes, multi-source search)

#### 5. Evaluator-Optimizer
One LLM generates, another evaluates and provides feedback iteratively.
- **Use case**: Literary translation, complex research requiring multiple search rounds

### Agent Pattern

Agents operate through loops of LLM usage with environmental feedback:
1. Begin with user command or interactive discussion
2. Plan and operate independently after task clarification
3. Obtain "ground truth" from environment at each step (tool results, code execution)
4. Pause for human feedback at checkpoints or blockers
5. Include stopping conditions (iteration limits) for control

**Use case**: Open-ended problems, trusted environments, tasks with clear success metrics (verifiable through tests, measurable through resolutions).

### Three Implementation Principles

1. **Simplicity**: Uncomplicated agent design architecture
2. **Transparency**: Explicitly show planning steps
3. **Tool documentation and testing**: Carefully craft the ACI

---

## Context Engineering for AI Agents

Source: [Effective Context Engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)

### Core Definition

Context engineering = the strategic curation of tokens available to LLMs during inference. Unlike prompt engineering (crafting instructions), context engineering manages the **entire context state** (system instructions, tools, MCP, external data, message history) across **multiple inference turns**.

**Fundamental principle**: Find the smallest set of high-signal tokens that maximize desired outcomes.

### The Attention Budget Problem

- Research on "needle-in-a-haystack" reveals **context rot** — as token count increases, retrieval accuracy declines
- Transformer architecture's n-squared pairwise token relationships create diminishing returns
- Treat context as a finite resource with diminishing returns, similar to human working memory

### System Prompt Design

- Calibrate at the **"right altitude"** — specific enough to guide, flexible enough for heuristics
- Structure with XML tags or markdown headers: `<background_information>`, `<instructions>`, `## Tool guidance`
- Start minimal, add clarity based on identified failure modes

### Tool Design for Context Efficiency

Effective tools:
- Demonstrate clear, unambiguous purpose
- Have minimal functional overlap
- Return token-efficient information
- Encourage efficient agent behaviors

> "Bloated toolsets with ambiguous decision points undermine agent performance."

### Runtime Context Retrieval

#### Just-In-Time Approach
- Maintain lightweight identifiers (file paths, URLs, query references)
- Dynamically load context using tools
- Metadata provides behavioral signals (folder hierarchies, naming conventions, timestamps)
- Agents incrementally discover context through exploration

#### Hybrid Strategy (Recommended)
- Retrieve some data upfront for speed
- Enable autonomous exploration for additional discovery
- Example: Claude Code drops CLAUDE.md files into context initially, while `glob` and `grep` enable just-in-time retrieval

### Long-Horizon Solutions

#### Compaction
- Summarize conversations nearing context limits, reinitiate with compressed summaries
- Preserve: architectural decisions, unresolved bugs, implementation details
- Discard: redundant outputs, verbose tool results
- Principle: maximize recall first (capture all relevant info), then improve precision

#### Structured Note-Taking (Agentic Memory)
- Agents write notes persisted outside context windows, retrieving later
- Enables multi-hour coherence across dozens of tool calls
- File-based systems for storing and consulting information across sessions

#### Sub-Agent Architectures
- Specialized sub-agents handle focused tasks with clean context windows
- Main agent coordinates high-level plans
- Subagents return condensed summaries (1,000-2,000 tokens from tens of thousands explored)
- Achieves separation of concerns

### Five Key Principles

1. **Minimal viable context**: Strip to essentials; minimal doesn't mean short
2. **Progressive disclosure**: Let agents incrementally discover relevant information
3. **Metadata leverage**: Use file structures, naming conventions, timestamps as signals
4. **Token efficiency**: Tools should return information with minimal overhead
5. **Autonomy scaling**: Smarter models require less prescriptive engineering

---

## Agent Harnesses for Long-Running Tasks

Source: [Effective Harnesses for Long-Running Agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)

### Two-Part Architecture

#### 1. Initializer Agent (First Session)
- Creates init script (executable startup for dev servers)
- Establishes progress tracking (`claude-progress.txt`)
- Sets up git repository with documented file additions
- Generates comprehensive feature specs in JSON (structured format > markdown for models)

#### 2. Coding Agent (Subsequent Sessions)
- Reads progress docs and git history to understand context
- Implements one feature at a time (not entire solutions)
- Commits changes with descriptive messages
- Updates progress docs before session conclusion
- Tests before marking features complete

### Session Startup Sequence

1. Directory verification (`pwd`)
2. Progress file and git log review
3. Feature list examination to identify next priority
4. Dev server launch via init.sh
5. Basic functionality smoke test
6. New feature implementation

### Key Patterns

- **JSON for feature tracking** (models handle structured formats more reliably than markdown)
- **Incremental progress** — one feature per session, git enables reverting broken code
- **End-to-end verification** via browser automation (not just unit tests or curl)
- **Explicit testing directives** — without them, agents mark features complete prematurely

---

## Multi-Agent Research Systems

Source: [Multi-Agent Research System](https://www.anthropic.com/engineering/multi-agent-research-system)

### Architecture

**Orchestrator-worker pattern**: Lead agent (Opus) coordinates, spawns specialized subagents (Sonnet) working in parallel.

1. **Planning Phase**: Lead agent saves plan to persistent memory
2. **Parallel Exploration**: Subagents independently search with separate context windows
3. **Iterative Evaluation**: Interleaved thinking after tool results to assess quality
4. **Synthesis**: Lead decides if more research needed
5. **Citation**: Dedicated CitationAgent processes documents for attribution

**Result**: 90.2% improvement over single-agent Claude Opus 4 on internal research evals.

### Scaling Effort to Complexity

- **Simple fact-finding**: 1 agent, 3-10 tool calls
- **Comparisons**: 2-4 subagents, 10-15 calls each
- **Complex research**: 10+ subagents with clearly divided responsibilities

### Prompt Engineering Lessons

1. **Think like your agents**: Simulate step-by-step behavior to reveal failure modes
2. **Effective delegation**: Detailed task descriptions prevent duplication. Vague instructions like "research semiconductor shortage" cause subagents to duplicate work
3. **Tool selection heuristics**: Examine all tools first, match to intent, prefer specialized over generic
4. **Tool description quality**: Poor descriptions send agents down entirely wrong paths
5. **Agent-driven improvement**: Claude 4 models can diagnose failures and suggest prompt improvements (40% completion time reduction)
6. **Broad-to-narrow search**: Agents default to overly specific queries. Prompt for short, broad queries followed by progressive narrowing
7. **Extended thinking for planning**: Lead agents think to plan approach, assess tool fit, determine complexity, define subagent roles
8. **Parallel tool calling**: Reduced research time by up to 90% for complex queries

### Context Management

- Agents summarize completed work phases
- Essential information stored in external memory before proceeding
- Fresh subagents spawned with clean contexts + continuity through handoffs
- Subagent outputs can bypass lead agent via artifact systems (reduces token overhead)

### Evaluation

- Start small: ~20 queries showing real usage patterns
- LLM-as-judge with rubric: factual accuracy, citation accuracy, completeness, source quality, tool efficiency
- Human testing catches: hallucinated answers, system failures, subtle source selection biases

### Production Reliability

- **State management**: Support resuming from error points, not full restarts
- **Debugging**: Full production tracing monitoring decision patterns (not conversation contents)
- **Deployment**: Rainbow deployments gradually shifting traffic, maintaining both versions
- **Combine** Claude's adaptability with deterministic safeguards (retry logic, regular checkpoints)

---

## Agent Skills & Progressive Disclosure

Source: [Agent Skills](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills), [Skill Authoring Best Practices](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices)

### What Are Agent Skills?

Organized directories of instructions, scripts, and resources that agents discover and load dynamically. Like "putting together an onboarding guide for a new hire."

### Progressive Disclosure (3 Levels)

1. **Level 1**: Skill name + description loaded into system prompt at startup
2. **Level 2**: Full SKILL.md content loaded when Claude determines relevance
3. **Level 3+**: Additional bundled files loaded contextually as needed

### Skill Authoring Principles

#### Conciseness
- Context window is a shared resource
- Default assumption: Claude is already very smart
- Challenge each piece of information: "Does Claude really need this explanation?"
- Keep SKILL.md under 500 lines

#### Degrees of Freedom
- **High freedom** (text instructions): Multiple approaches valid, decisions depend on context
- **Medium freedom** (pseudocode/parameterized scripts): Preferred pattern exists, some variation OK
- **Low freedom** (specific scripts): Operations fragile, consistency critical, specific sequence required

#### Description Writing
- Write in **third person** (injected into system prompt)
- Include BOTH what the skill does AND when to use it
- Be specific, include key terms for discovery

#### Structure
```
skill-name/
├── SKILL.md              # Main instructions (loaded when triggered)
├── reference.md          # API reference (loaded as needed)
├── examples.md           # Usage examples (loaded as needed)
└── scripts/
    ├── validate.py       # Utility script (executed, not loaded)
    └── process.py        # Processing script
```

#### Key Rules
- Keep references **one level deep** from SKILL.md (avoid nesting)
- Structure longer reference files with table of contents
- Use forward slashes in paths (not backslashes)
- Provide a default approach, don't offer too many options
- Test with all models you plan to use (Haiku needs more detail, Opus needs less)
- Use evaluation-driven development: identify gaps first, then build skills

#### Feedback Loops
- Run validator -> fix errors -> repeat
- Create verifiable intermediate outputs (plan -> validate plan -> execute -> verify)
- Make validation scripts verbose with specific error messages

#### Iterative Development
1. Complete a task without a skill (observe what context you provide)
2. Identify reusable patterns
3. Ask Claude to create a skill
4. Review for conciseness
5. Test with fresh instances
6. Iterate based on observation

---

## Claude Agent SDK Patterns

Source: [Building Agents with Claude Agent SDK](https://www.anthropic.com/engineering/building-agents-with-the-claude-agent-sdk)

### The Agent Loop (4 Stages)

1. **Gather Context** — Retrieve relevant information
2. **Take Action** — Execute tasks using available tools
3. **Verify Work** — Validate own outputs
4. **Iterate** — Refine results based on feedback

### Context Management Strategies

| Strategy | Description | When to Use |
|---|---|---|
| **Agentic Search** | bash commands (grep, tail) to navigate files | Transparency, accuracy matter |
| **Semantic Search** | Vector-based retrieval | Performance > accuracy |
| **Subagents** | Parallel processing with isolated context | Large datasets, parallel work |
| **Compaction** | Automatic summarization | Extended operations |

### Action Execution Methods

| Method | Description |
|---|---|
| Custom Tools | Defined API calls for specific operations |
| Bash & Scripts | General-purpose computer access |
| Code Generation | Python/TypeScript for complex operations |
| MCP | Standardized integrations (Slack, GitHub) |

### Verification Approaches

1. **Rule-Based**: Linting, format validation, explicit error conditions (most robust)
2. **Visual**: Screenshots/renders for UI tasks via Playwright MCP
3. **LLM-as-Judge**: Secondary model for fuzzy criteria (less robust, higher latency)

### Evaluation Framework

- Missing information -> Restructure search APIs
- Repeated failures -> Add formal rule validation
- Can't self-correct -> Provide alternative tools
- Inconsistent performance -> Build representative test sets

---

## Agentic System Prompts

Source: [Prompting Best Practices — Agentic Systems](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/claude-4-best-practices)

### Context Awareness and Multi-Window Workflows

Claude 4.6/4.5 can track remaining context window. For agent harnesses with compaction:

```
Your context window will be automatically compacted as it approaches its limit,
allowing you to continue working indefinitely from where you left off. Therefore,
do not stop tasks early due to token budget concerns. As you approach your token
budget limit, save your current progress and state to memory before the context
window refreshes. Always be as persistent and autonomous as possible and complete
tasks fully.
```

### Multi-Context Window Best Practices

1. **Use different prompts for first vs subsequent windows**: First window sets up framework (tests, scripts), future windows iterate on todo-list
2. **Write tests in structured format**: JSON > markdown for test tracking. "It is unacceptable to remove or edit tests"
3. **Create setup scripts** (`init.sh`): Gracefully start servers, run tests, linters
4. **Starting fresh vs compacting**: Claude's latest models are effective at discovering state from filesystem. Consider fresh windows over compaction.
5. **Provide verification tools**: Playwright MCP, computer use for testing UIs
6. **Encourage complete context usage**: "Spend your entire output context working on the task"

### State Management

- **Structured formats** (JSON) for state data (test results, task status)
- **Unstructured text** for progress notes
- **Git for state tracking**: Log of what's been done + checkpoints that can be restored
- **Emphasize incremental progress**: Track progress, focus on incremental work

### Balancing Autonomy and Safety

```
Consider the reversibility and potential impact of your actions. Take local,
reversible actions (editing files, running tests) freely, but for hard-to-reverse
actions affecting shared systems, ask the user before proceeding.

Examples warranting confirmation:
- Destructive: deleting files/branches, dropping tables, rm -rf
- Hard to reverse: git push --force, git reset --hard
- Visible to others: pushing code, commenting on PRs, sending messages
```

### Proactive vs Conservative Action

**Proactive (default to action):**
```xml
<default_to_action>
By default, implement changes rather than only suggesting them. If the user's intent
is unclear, infer the most useful likely action and proceed, using tools to discover
any missing details instead of guessing.
</default_to_action>
```

**Conservative (wait for instructions):**
```xml
<do_not_act_before_instructions>
Do not jump into implementation unless clearly instructed to make changes. Default to
providing information, doing research, and providing recommendations rather than
taking action.
</do_not_act_before_instructions>
```

### Subagent Orchestration

Claude 4.6 has strong native subagent orchestration — may overuse it. Guide:
```
Use subagents when tasks can run in parallel, require isolated context, or involve
independent workstreams. For simple tasks, sequential operations, single-file edits,
or tasks where you need to maintain context across steps, work directly.
```

### Research and Information Gathering

```
Search for this information in a structured way. As you gather data, develop several
competing hypotheses. Track your confidence levels. Regularly self-critique your
approach. Update a hypothesis tree or research notes file. Break down this complex
research task systematically.
```

### Minimizing Hallucinations

```xml
<investigate_before_answering>
Never speculate about code you have not opened. If the user references a specific file,
you MUST read the file before answering. Make sure to investigate and read relevant files
BEFORE answering questions about the codebase. Never make any claims about code before
investigating unless you are certain of the correct answer.
</investigate_before_answering>
```

### Minimizing Overengineering

```
Avoid over-engineering. Only make changes that are directly requested or clearly
necessary. Keep solutions simple and focused:
- Scope: Don't add features beyond what was asked
- Documentation: Don't add docstrings to code you didn't change
- Defensive coding: Don't add error handling for impossible scenarios
- Abstractions: Don't create helpers for one-time operations
```

---

## Anti-Patterns to Avoid

### Prompt Design Anti-Patterns

| Anti-Pattern | Better Approach |
|---|---|
| Telling what NOT to do | Tell what TO do instead |
| Vague instructions ("make it good") | Specific criteria and examples |
| Over-prompting for tool use | Claude 4.6 triggers tools appropriately without aggressive language |
| "CRITICAL: You MUST use this tool when..." | "Use this tool when..." (newer models respond to normal language) |
| Blanket defaults ("Default to using [tool]") | Targeted: "Use [tool] when it would enhance understanding" |
| Prescriptive step-by-step plans | General instructions ("think thoroughly") — Claude's reasoning exceeds hand-written plans |
| Prefilled assistant responses | Direct instructions in system prompt (deprecated in Claude 4.6) |

### Tool Design Anti-Patterns

| Anti-Pattern | Better Approach |
|---|---|
| Vague 1-sentence tool descriptions | 3-4+ sentence descriptions with when/what/caveats |
| One tool per action | Consolidate related ops with action parameter |
| Returning all data from tool | Return only high-signal fields Claude needs |
| Opaque internal references | Semantic, stable identifiers |
| Bloated toolsets with overlapping tools | Minimal viable set with clear distinctions |
| Relative file paths in tools | Absolute paths (poka-yoke design) |

### Agent Architecture Anti-Patterns

| Anti-Pattern | Better Approach |
|---|---|
| Starting with complex multi-agent systems | Start simple, add complexity only when justified |
| Heavy framework abstraction | Direct LLM API usage; understand underlying mechanisms |
| Vague delegation to subagents | Detailed task descriptions preventing duplication |
| Overly specific search queries | Broad-to-narrow search strategy |
| Loading all context upfront | Progressive disclosure / just-in-time retrieval |
| Full restart on errors | Resume from error points with checkpoints |
| Pre-loading entire datasets | Lightweight identifiers + dynamic tool loading |

### Context Management Anti-Patterns

| Anti-Pattern | Better Approach |
|---|---|
| Stuffing everything into context | Minimal viable context; strip to essentials |
| Deeply nested file references | Keep references one level deep |
| Time-sensitive information in prompts | Use "old patterns" sections |
| Inconsistent terminology | Choose one term and use consistently |
| No compaction strategy | Summarize nearing limits, preserve key decisions |

---

## Key Takeaways for Our Plugin

### For System Prompt Design

1. **Structure with XML tags**: `<instructions>`, `<tools>`, `<context>`, `<rules>`
2. **Right altitude**: Specific enough to guide, flexible enough for heuristics
3. **Progressive disclosure**: Load only what's needed, when needed
4. **Context awareness**: Tell the agent about compaction so it doesn't stop early
5. **Motivate rules**: Explain WHY, not just WHAT
6. **Role setting**: One sentence in system prompt focuses behavior

### For Tool Design

1. **3-4 sentence descriptions minimum** with when/what/caveats
2. **Consolidate related operations** with action parameters
3. **Namespace tools** by service (`jira_get_ticket`, `bamboo_get_build`)
4. **Return only high-signal data** — minimal token overhead
5. **Absolute paths** and stable identifiers
6. **Input examples** for complex tools

### For Agent Loop Design

1. **Four-stage loop**: Gather Context -> Take Action -> Verify Work -> Iterate
2. **Adaptive thinking** for multi-step tool use and long-horizon loops
3. **Parallel tool calling** whenever dependencies allow
4. **Structured state tracking**: JSON for status data, text for progress notes, git for checkpoints
5. **Verification at every step**: Rule-based > visual > LLM-as-judge
6. **Incremental progress**: One thing at a time, commit often

### For Context Management

1. **Hybrid retrieval**: Some data upfront (CLAUDE.md pattern), exploration tools for the rest
2. **Compaction with recall-first**: Capture everything relevant, then trim
3. **Subagents for isolation**: Clean context windows for focused tasks
4. **Agentic memory**: File-based notes persisted outside context windows
5. **Token budget awareness**: Treat context as finite resource with diminishing returns

### For Multi-Agent Coordination

1. **Orchestrator-worker pattern**: Lead agent (Opus) + specialized subagents (Sonnet)
2. **Clear delegation**: Objectives, expected output formats, tool guidance, task boundaries
3. **Scale effort to complexity**: 1 agent for simple, 2-4 for comparisons, 10+ for complex
4. **Broad-to-narrow search**: Start broad, progressively narrow
5. **Parallel subagent spawning**: Dramatic speedup (up to 90% time reduction)

### For Production Reliability

1. **Deterministic safeguards**: Retry logic, checkpoints alongside Claude's adaptability
2. **Full tracing**: Monitor decision patterns without exposing conversation contents
3. **Rainbow deployments**: Gradual traffic shifting between versions
4. **Resume from errors**: Don't force full restarts
5. **Evaluation-driven development**: Build evals before features, start with ~20 representative queries
