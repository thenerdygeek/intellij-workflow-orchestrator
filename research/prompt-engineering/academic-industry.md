# Prompt Engineering for Agentic AI: Academic & Industry Research

> Research compiled March 2026. Focus: techniques proven to improve agentic AI performance,
> particularly for coding agents and tool-using systems.

---

## Table of Contents

1. [Chain-of-Thought (CoT) Prompting](#1-chain-of-thought-cot-prompting)
2. [ReAct: Reasoning + Acting](#2-react-reasoning--acting)
3. [Tree of Thoughts (ToT)](#3-tree-of-thoughts-tot)
4. [Self-Consistency](#4-self-consistency)
5. [Reflexion](#5-reflexion)
6. [LATS: Language Agent Tree Search](#6-lats-language-agent-tree-search)
7. [ART: Automatic Reasoning and Tool-use](#7-art-automatic-reasoning-and-tool-use)
8. [PAL: Program-Aided Language Models](#8-pal-program-aided-language-models)
9. [Context Engineering (Evolution of Prompt Engineering)](#9-context-engineering-evolution-of-prompt-engineering)
10. [Industry Guidance: Anthropic](#10-industry-guidance-anthropic)
11. [Industry Guidance: OpenAI](#11-industry-guidance-openai)
12. [Industry Guidance: Microsoft](#12-industry-guidance-microsoft)
13. [Industry Guidance: Google](#13-industry-guidance-google)
14. [Industry Guidance: LangChain/LangGraph](#14-industry-guidance-langchainlanggraph)
15. [DeepLearning.AI: Andrew Ng's Agentic AI Patterns](#15-deeplearningai-andrew-ngs-agentic-ai-patterns)
16. [Agent-Specific Prompt Engineering Patterns](#16-agent-specific-prompt-engineering-patterns)
17. [Technique Comparison Matrix](#17-technique-comparison-matrix)
18. [Recommendations for Our Agent](#18-recommendations-for-our-agent)

---

## 1. Chain-of-Thought (CoT) Prompting

**Source:** Wei et al. (2022), "Chain-of-Thought Prompting Elicits Reasoning in Large Language Models"
**URL:** https://www.promptingguide.ai/techniques/cot

### How It Works

CoT prompting enables LLMs to decompose complex problems into intermediate reasoning steps before producing a final answer. Rather than jumping directly to output, the model generates a step-by-step reasoning trace.

### Variants

| Variant | Description | When to Use |
|---------|-------------|-------------|
| **Few-Shot CoT** | Provide 1-5 examples with explicit reasoning chains | Complex tasks with available exemplars |
| **Zero-Shot CoT** | Append "Let's think step by step" to the prompt | Quick reasoning boost without examples |
| **Auto-CoT** (Zhang et al. 2022) | Automatically generate diverse reasoning chains via clustering | Scaling CoT across many task types |

### Evidence of Effectiveness

- Zero-Shot CoT (Kojima et al. 2022): Adding "Let's think step by step" corrects arithmetic errors (e.g., from 11 to correct answer of 10 in apple-counting task)
- CoT is an "emergent ability" -- only works reliably in sufficiently large models (>100B parameters)
- Even a single demonstration can be effective
- Diversity in examples improves performance even when individual chains contain errors

### Relevance to Coding Agents

- **Planning phase**: CoT helps agents decompose a coding task into sub-steps before executing
- **Debugging**: Step-by-step reasoning traces help identify where logic goes wrong
- **Tool selection**: Reasoning about which tool to use before calling it

### Limitations

- Manual crafting of diverse examples is labor-intensive (Auto-CoT addresses this)
- Can be verbose, consuming context window tokens
- Not grounded in external facts -- prone to hallucination in knowledge-intensive tasks

---

## 2. ReAct: Reasoning + Acting

**Source:** Yao et al. (2022), "ReAct: Synergizing Reasoning and Acting in Language Models" (ICLR 2023)
**URLs:**
- https://arxiv.org/abs/2210.03629
- https://research.google/blog/react-synergizing-reasoning-and-acting-in-language-models/
- https://www.promptingguide.ai/techniques/react

### How It Works

ReAct interleaves verbal reasoning traces with task-specific actions in a loop:

```
Thought: [Agent's reasoning about what to do next]
Action: [Tool call or API interaction]
Observation: [Result from environment]
Thought: [Updated reasoning based on observation]
...
```

The key insight: reasoning traces help the model plan and update strategies, while actions ground reasoning in real-world feedback. This bidirectional synergy is what makes ReAct uniquely suited to agentic systems.

### Benchmarks (Specific Numbers)

| Task | Method | Score |
|------|--------|-------|
| HotpotQA (6-shot) | Standard | 28.7% |
| HotpotQA (6-shot) | Chain-of-Thought | 29.4% |
| HotpotQA (6-shot) | ReAct | 27.4% |
| HotpotQA (6-shot) | **ReAct + CoT** | **35.1%** |
| Fever (3-shot) | ReAct | 60.9% |
| Fever (3-shot) | **ReAct + CoT** | **64.6%** |
| ALFWorld (2-shot) | Act-only | 45% |
| ALFWorld (2-shot) | **ReAct** | **71%** |
| WebShop (1-shot) | Act-only | 30.1% |
| WebShop (1-shot) | **ReAct** | **40%** |

Critical finding: **ReAct + CoT combined is the best approach overall**, leveraging both internal knowledge and externally obtained information.

### Implementation Best Practices

1. **Task-dependent trace density:**
   - Reasoning-heavy tasks: alternate frequent reasoning traces with actions
   - Decision-heavy tasks: sparse reasoning at critical decision points only

2. **Useful reasoning types in traces:**
   - Task decomposition and action planning
   - Commonsense knowledge injection
   - Observation extraction and filtering
   - Progress tracking and plan adjustment
   - Exception handling through plan revision

3. **Few-shot exemplars**: Use human-written thought-action-observation trajectories as examples

4. **Error recovery**: Include examples of how to handle failed tool calls or unexpected results

5. **Human-in-the-loop**: Reasoning traces can be edited by humans to correct agent behavior in real-time

### Relevance to Coding Agents

ReAct is the **foundational pattern for tool-using coding agents**:
- Thought: "I need to find the file that defines the UserService interface"
- Action: Search[UserService interface definition]
- Observation: Found in core/services/UserService.kt
- Thought: "Now I need to read this file to understand the existing methods"
- Action: Read[core/services/UserService.kt]

### Scaling

- Larger models (PaLM-540B) benefit from prompting alone
- Smaller models (PaLM-8B/62B) benefit from fine-tuning on successful ReAct trajectories
- This reduces annotation overhead significantly

---

## 3. Tree of Thoughts (ToT)

**Source:** Yao et al. (2023), "Tree of Thoughts: Deliberate Problem Solving with Large Language Models"
**URL:** https://www.promptingguide.ai/techniques/tot

### How It Works

ToT extends CoT by maintaining a tree of reasoning paths rather than a single chain:

1. **Thought Generation**: LLM generates multiple candidate intermediate steps
2. **Self-Evaluation**: LLM assesses each branch's progress ("sure/maybe/impossible")
3. **Systematic Search**: BFS, DFS, or beam search explores promising paths with backtracking

### Key Parameters

- **Breadth (b)**: Number of candidates maintained at each step (typically b=5)
- **Evaluation**: Values sampled 3 times per candidate for robustness
- **Search strategy**: BFS for shallow, wide problems; DFS for deep, narrow problems

### Variants

| Variant | Author | Key Difference |
|---------|--------|----------------|
| **ToT (original)** | Yao et al. 2023 | Generic search (DFS/BFS/beam search) |
| **ToT with RL Controller** | Long 2023 | RL-trained controller learns optimal backtracking |
| **Single-Prompt ToT** | Hulbert 2023 | Simulates multiple expert perspectives in one prompt |

### When to Use

- Mathematical reasoning puzzles
- Tasks requiring deliberate exploration and backtracking
- Problems with multiple valid solution paths
- Strategic planning where wrong initial choices are costly

### Relevance to Coding Agents

- **Architecture decisions**: Explore multiple design approaches before committing
- **Bug diagnosis**: Systematically evaluate multiple hypotheses for a bug's root cause
- **Refactoring strategies**: Evaluate different refactoring approaches and their trade-offs

### Limitations

- Computationally expensive (multiple LLM calls per step)
- Best for problems with clear evaluation criteria
- Overkill for straightforward tasks

---

## 4. Self-Consistency

**Source:** Wang et al. (2022), "Self-Consistency Improves Chain of Thought Reasoning in Language Models"
**URL:** https://www.promptingguide.ai/techniques/consistency

### How It Works

1. Generate multiple diverse reasoning chains using few-shot CoT (high temperature)
2. Collect varied outputs from the same prompt
3. Aggregate via majority vote to select the most frequent answer

### Evidence of Effectiveness

- Consistently boosts performance on arithmetic and commonsense reasoning
- Example: On age-calculation problems, 2 out of 3 paths gave correct answer (67) vs. 1 incorrect (35); majority vote yields correct result
- Most effective when combined with CoT

### When to Use

- Tasks with a single correct answer (not open-ended generation)
- When reliability matters more than latency
- Arithmetic, logical deduction, commonsense reasoning

### Relevance to Coding Agents

- **Code review**: Generate multiple reviews and pick consistent findings
- **Test generation**: Sample multiple test approaches, keep the most common patterns
- **Error diagnosis**: Multiple diagnostic attempts, majority-vote the root cause

### Trade-offs

- Requires multiple LLM calls (3-5x typical)
- Higher latency and cost
- Not useful for creative/open-ended tasks

---

## 5. Reflexion

**Source:** Shinn et al. (2023), "Reflexion: Language Agents with Verbal Reinforcement Learning"
**URL:** https://www.promptingguide.ai/techniques/reflexion

### How It Works

Reflexion is a self-improvement loop with three components:

1. **Actor**: Generates actions using CoT or ReAct patterns
2. **Evaluator**: Scores the actor's output (task-specific reward function)
3. **Self-Reflection**: Produces verbal feedback stored in long-term memory

The loop: Define task -> Generate trajectory -> Evaluate -> Reflect -> Generate improved trajectory

### Benchmarks

| Task | Method | Result |
|------|--------|--------|
| AlfWorld (sequential decision-making) | ReAct + Reflexion | **130/134 tasks completed** |
| HotPotQA | Reflexion + CoT | Consistently exceeded standard CoT |
| HumanEval (Python code gen) | Reflexion | **State-of-the-art** pass@1 |
| MBPP (Python code gen) | Reflexion | **State-of-the-art** pass@1 |

### Relevance to Coding Agents

Reflexion is **directly applicable to coding agents** because:
- Test-driven feedback provides a natural evaluation signal
- Failed test runs produce concrete error messages for self-reflection
- Memory of past failures prevents repeating the same mistakes
- No fine-tuning required -- works through in-context learning

### Implementation Pattern for Coding

```
1. Agent writes code
2. Tests run, some fail
3. Agent reflects: "The function fails on edge case X because I didn't handle null input"
4. Reflection stored in memory
5. Agent rewrites code with reflection context
6. Tests run again
```

### When to Use

- Iterative improvement tasks (code generation, writing)
- Tasks with clear evaluation criteria (test suites)
- When you need learning across attempts without fine-tuning

---

## 6. LATS: Language Agent Tree Search

**Source:** Zhou et al. (2023), "Language Agent Tree Search Unifies Reasoning, Acting, and Planning" (ICML 2024)
**URL:** https://arxiv.org/abs/2310.04406

### How It Works

LATS unifies reasoning, acting, and planning by integrating Monte Carlo Tree Search (MCTS) with LLM-powered value functions and self-reflections:

1. **Selection**: Use UCB1 to select promising nodes in the search tree
2. **Expansion**: Generate multiple candidate actions at each node
3. **Evaluation**: LLM-powered value function scores node quality
4. **Backpropagation**: Update value estimates up the tree
5. **Self-Reflection on failure**: When a terminal node fails, generate verbal reflection stored in memory

### Benchmarks

| Task | Method | Score |
|------|--------|-------|
| **HumanEval (coding)** | GPT-4 + LATS | **94.4% pass@1** (state-of-the-art) |
| Programming tasks | LATS | Outperforms ReAct, Reflexion, and other search methods |
| Interactive QA | LATS | Competitive with specialized approaches |
| Web navigation | LATS | Strong performance across domains |

### Why LATS Matters for Coding Agents

- **94.4% on HumanEval** is the highest prompting-only result reported
- Combines the best of ReAct (reasoning + acting), Reflexion (self-improvement), and ToT (tree search)
- Failed trajectories and reflections are stored and used in subsequent iterations
- External environment feedback (test results) guides the search

### Limitations

- High computational cost (many LLM calls per problem)
- Practical mainly for high-stakes, complex problems where cost is justified
- Requires well-defined evaluation criteria

---

## 7. ART: Automatic Reasoning and Tool-use

**Source:** Paranjape et al. (2023), "ART: Automatic multi-step Reasoning and Tool-use for Large Language Models"
**URL:** https://www.promptingguide.ai/techniques/art

### How It Works

1. **Task library**: Maintains a library of multi-step reasoning + tool-use demonstrations
2. **Automatic selection**: Given a new task, selects relevant demonstrations automatically
3. **Interleaved execution**: Pauses generation for tool calls, incorporates results, continues

### Evidence of Effectiveness

- "Substantially improves over few-shot prompting and automatic CoT on unseen tasks" (BigBench, MMLU benchmarks)
- "Exceeds performance of hand-crafted CoT prompts when human feedback is incorporated"
- Zero-shot generalization to novel tasks

### Relevance to Coding Agents

- Task library approach maps to agent skill libraries
- Extensible: humans can add new tools and reasoning patterns
- Reduces need for per-task prompt engineering

---

## 8. PAL: Program-Aided Language Models

**Source:** Gao et al. (2023), "PAL: Program-aided Language Models"
**URL:** https://www.promptingguide.ai/techniques/pal

### How It Works

Instead of reasoning in natural language, PAL generates executable code as intermediate steps:

1. LLM reads natural language problem
2. Generates Python code that solves it
3. Python interpreter executes the code
4. Result is the program output

### Relevance to Coding Agents

PAL is foundational to coding agent design:
- Validates reasoning through execution (not just text generation)
- Reduces hallucination -- programs either run correctly or produce errors
- Natural fit for any task involving computation, data manipulation, or API interaction

---

## 9. Context Engineering (Evolution of Prompt Engineering)

**Sources:**
- https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents
- https://neo4j.com/blog/agentic-ai/context-engineering-vs-prompt-engineering/
- https://www.firecrawl.dev/blog/context-engineering

### The Shift: Prompt Engineering -> Context Engineering

By mid-2025, the industry consensus shifted: for agentic AI, **context engineering** (managing the entire information environment) matters more than prompt engineering (crafting individual instructions).

Key statistic: LangChain's 2025 State of Agent Engineering report found **57% of organizations have AI agents in production**, yet **32% cite quality as the top barrier**, with most failures traced to poor context management, not LLM capabilities.

### Core Principle

> "Find the smallest set of high-signal tokens that maximize the likelihood of your desired outcome."
> -- Anthropic, "Effective Context Engineering for AI Agents"

### Key Techniques

#### System Prompt Optimization
- Strike the "Goldilocks zone" between overly-specific hardcoded logic and vague guidance
- Organize into distinct sections using XML tags or Markdown headers
- Be "specific enough to guide behavior effectively, yet flexible enough to provide strong heuristics"

#### Tool Design for Efficiency
- Minimize tool set overlap and ambiguity
- Ensure tools are self-contained, error-robust, and unambiguous
- Return token-efficient information (not everything available)
- Encourage efficient agent behaviors

#### Few-Shot Prompting
- Curate diverse, canonical examples rather than exhaustive edge cases
- "For an LLM, examples are the 'pictures' worth a thousand words"

#### Just-In-Time Context Retrieval
- Maintain lightweight identifiers (file paths, URLs, queries)
- Dynamically load data at runtime using tools
- Avoid pre-loading everything into context

### Long-Horizon Context Management

#### Compaction
- Summarize conversations approaching context limits
- Preserve architectural decisions and implementation details
- Discard redundant outputs (tool result clearing is the "lightest touch")

#### Structured Note-Taking
- Agents write persistent notes outside the context window
- Retrieved later for continuity across sessions
- Example: Claude Code maintains to-do lists; Claude playing Pokemon tracks game state across thousands of steps

#### Sub-Agent Architectures
- Specialized agents handle focused tasks with clean contexts
- Main agent coordinates; sub-agents return condensed summaries (1,000-2,000 tokens)
- Achieves "clear separation of concerns"

### Context Rot

Model accuracy decreases as context tokens increase due to transformer architectural constraints (n-squared pairwise attention relationships). This makes context curation critical -- more tokens does not mean better performance.

---

## 10. Industry Guidance: Anthropic

**Sources:**
- https://platform.claude.com/docs/en/docs/build-with-claude/prompt-engineering/claude-4-best-practices
- https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents
- https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents

### System Prompt Best Practices

1. **Be clear and direct**: "Show your prompt to a colleague with minimal context. If they'd be confused, Claude will be too."
2. **Add context/motivation**: Explaining *why* helps the model generalize better than just stating rules
3. **Use examples**: 3-5 well-crafted examples in `<example>` tags dramatically improve accuracy
4. **Structure with XML tags**: `<instructions>`, `<context>`, `<input>` -- reduces misinterpretation
5. **Give a role**: Even a single sentence focuses behavior and tone

### Tool Use Patterns (Claude 4.x)

**Explicit action direction**:
```
Less effective: "Can you suggest some changes to improve this function?"
More effective: "Change this function to improve its performance."
```

**Proactive action prompt**:
```xml
<default_to_action>
By default, implement changes rather than only suggesting them. If the user's intent
is unclear, infer the most useful likely action and proceed, using tools to discover
any missing details instead of guessing.
</default_to_action>
```

**Parallel tool calling optimization**:
```
If you intend to call multiple tools and there are no dependencies between the tool
calls, make all of the independent tool calls in parallel. Never use placeholders
or guess missing parameters in tool calls.
```

### Agentic System Patterns

**Long-horizon state tracking**:
- Use structured formats (JSON) for state data (test results, task status)
- Use unstructured text for progress notes
- Use git for state tracking across sessions
- Emphasize incremental progress

**Multi-context window workflows** (critical for long-running agents):
1. Use first context window to set up framework (write tests, create setup scripts)
2. Have model write tests in structured format (e.g., `tests.json`)
3. Set up quality-of-life tools (init.sh for reproducible startup)
4. Starting fresh can be better than compaction -- models are effective at discovering state from filesystem
5. Provide verification tools (Playwright, computer use) for testing without human feedback

**Context management prompt**:
```
Your context window will be automatically compacted as it approaches its limit, allowing
you to continue working indefinitely. Do not stop tasks early due to token budget concerns.
Save your current progress and state to memory before the context window refreshes.
```

**Balancing autonomy and safety**:
```
Consider the reversibility and potential impact of your actions. Take local, reversible
actions freely, but for actions that are hard to reverse, affect shared systems, or
could be destructive, ask the user before proceeding.
```

### Thinking and Reasoning

- Adaptive thinking (`thinking: {type: "adaptive"}`) is the recommended approach for Claude 4.6
- "Prefer general instructions over prescriptive steps" -- "think thoroughly" often produces better reasoning than hand-written step-by-step plans
- Self-checking: "Before you finish, verify your answer against [test criteria]" -- catches errors reliably for coding and math
- Multishot examples can include `<thinking>` tags to demonstrate reasoning patterns

### Harness for Long-Running Agents

Two-agent system architecture:
1. **Initializer Agent** (first run): Sets up environment, creates feature registry (JSON), init scripts
2. **Coding Agent** (subsequent sessions): Makes incremental progress per session

Session bootstrap protocol:
1. Verify working directory
2. Review git logs and progress files
3. Identify next priority from registry
4. Execute basic integration test before new work
5. Implement single feature incrementally

Key insight: JSON for structured state (feature registries, test results) because "the model is less likely to inappropriately change or overwrite JSON files compared to Markdown."

---

## 11. Industry Guidance: OpenAI

**Sources:**
- https://developers.openai.com/cookbook/examples/gpt-5/gpt-5_prompting_guide
- https://platform.openai.com/docs/guides/prompt-engineering
- https://cookbook.openai.com/examples/o-series/o3o4-mini_prompting_guide

### Agentic Workflow Best Practices (GPT-5 Guide)

**Controlling eagerness**:
- Lower `reasoning_effort` to reduce exploration and latency
- Set explicit context-gathering criteria and search depth limits
- Provide early-stop conditions (e.g., "stop as soon as you can act")
- Create escape hatches for proceeding under uncertainty

**Persistence and autonomy**:
- "Keep going until the user's query is completely resolved"
- Reduce clarifying questions by documenting reasonable assumptions upfront

**Planning patterns**:
- Require explicit decomposition of user requests into subtasks
- For minimal reasoning: "plan extensively before each function call"
- One tool call at a time with reflection on outcomes (improves reasoning quality)
- Tool budgets: Set fixed call limits for rapid workflows

**Self-reflection for zero-to-one tasks**:
- Develop internal excellence criteria (5-7 categories)
- Evaluate outputs against those standards before finalizing

### Tool Calling Best Practices

1. **Use system prompt** to describe when and when not to use each function
2. **Few-shot examples** for tool usage patterns
3. **Strict mode** (`strict: true`) for reliable schema adherence
4. **Disambiguate** between similar tools with explicit preference instructions
5. **Preambles**: "Before you call a tool, explain why you are calling it"
6. **TODO tool**: Track workflow and progress in structured format
7. Up to **~100 tools** with **~20 arguments per tool** is in-distribution

### Benchmark Evidence

- Tau-Bench Retail: Passing `previous_response_id` (context reuse) improved from 73.9% to **78.2%**
- SWE-Bench: Use `apply_patch` tool for file edits (matches training distribution)
- Low global verbosity with high verbosity for code outputs balances conciseness with readability

### Minimizing Overengineering

```
Avoid over-engineering. Only make changes that are directly requested or clearly necessary.
- Don't add features, refactor code, or make "improvements" beyond what was asked
- Don't add error handling for scenarios that can't happen
- Don't create abstractions for one-time operations
```

---

## 12. Industry Guidance: Microsoft

**Source:** https://learn.microsoft.com/en-us/azure/foundry/openai/concepts/prompt-engineering

### Core Techniques

| Technique | Description | Evidence |
|-----------|-------------|----------|
| **Few-shot learning** | Example user/assistant pairs in messages array | Transforms vague classification into precise labeling |
| **Clear instructions first** | Place task description before context/examples | Higher quality outputs (though GPT-4+ shows less sensitivity to order) |
| **Repeat instructions at end** | Counter recency bias with instruction repetition | Mitigates tendency to weight end-of-prompt info more heavily |
| **Prime the output** | Include prefix words to guide format | "Here's a bulleted list:\n- " produces bulleted output |
| **Clear syntax** | Separators (---), uppercase headers, structured formatting | Cleaner parsing, enables stopping conditions |
| **Break tasks down** | Decompose into extract-then-generate steps | More accurate fact extraction and query generation |
| **Use affordances** | Stop generation for tool calls, paste results back | Grounded responses with real search results |
| **Chain of thought** | "Take a step-by-step approach" | Reduces inaccuracy, makes assessment easier |
| **Specify output structure** | Request citations, structured formats | "Asking for citations makes it so the model must make two errors" |

### Key Insights for Agents

- **Grounding data**: "The closer you can get your source material to the final form of the answer you want, the less work the model needs to do, which means there's less opportunity for error"
- **Affordance pattern**: Stop generation at tool call, execute tool, paste results back into prompt -- this is essentially the ReAct pattern formalized
- **Output structure as guardrail**: Requiring inline citations reduces hallucination more than end-of-document citations
- **Space efficiency**: Tables are more token-efficient than JSON for structured data

### System Message Design (Azure AI)

Microsoft recommends system messages that:
1. Define assistant's role and boundaries
2. Set tone and communication style
3. Specify output formats (e.g., JSON)
4. Add safety and quality constraints

---

## 13. Industry Guidance: Google

**Sources:**
- Google Prompt Engineering Whitepaper (Lee Boonstra, September 2024)
- https://mfmfazrin.medium.com/prompt-engineering-whitepaper-by-google-eddb3b4842bc
- https://www.kaggle.com/whitepaper-prompt-engineering

### Four-Part Prompt Structure

1. **Role**: Define the persona the AI should assume
2. **Context**: Provide background information or goals
3. **Instruction**: Explain the task
4. **Output Format**: Specify response structure

### Techniques Covered

| Technique | Google's Recommendation |
|-----------|------------------------|
| Zero-Shot | Most basic; "provides only a description of the task without any examples" |
| Few-Shot | "Three to five high-quality, diverse examples" for complex tasks |
| Chain of Thought | Adding "Let's think step by step" can solve otherwise-failed problems |
| Self-Consistency | Run same prompt multiple times with high temperature, majority vote |
| Tree of Thoughts | Explore "multiple reasoning paths simultaneously" |
| Step-Back Prompting | Ask a "more general, high-level question" first to activate background knowledge |
| ReAct | Operate "in a thought-action loop" with external tools |
| Automatic Prompt Engineering (APE) | Use LLMs to generate "numerous prompt candidates," evaluate and select best |

### OPRO: Optimization by Prompting (Google DeepMind)

- LLMs act as self-optimizers for their own prompts
- Meta-prompt includes past attempts with performance scores
- LLM suggests new prompts; best ones become part of next meta-prompt iteration
- Automated discovery of effective prompts without human engineering

### Model Configuration Guidance

- Temperature, Top-K, and Top-P interact with prompt design
- Lower temperature for deterministic/factual tasks
- Higher temperature for creative/diverse outputs
- Suggested starting values provided for reliability vs. creativity trade-offs

---

## 14. Industry Guidance: LangChain/LangGraph

**Sources:**
- https://docs.langchain.com/oss/python/langgraph/workflows-agents
- https://blog.langchain.com/langchain-langgraph-1dot0/
- https://github.com/langchain-ai/deepagents

### Agent Patterns in LangGraph 1.0 (November 2025)

**ReAct Loop** (primary pattern):
```
Reasoning -> Action (tool call) -> Observation (tool result) -> Reasoning -> ...
```

**Supervisor Pattern** (multi-agent):
- Supervisor agent routes tasks to specialized workers
- Workers return results to supervisor for synthesis
- Used for complex tasks requiring different expertise

**Deep Agents** (langchain-ai/deepagents):
- Planning tool for task decomposition
- Filesystem backend for state persistence
- Ability to spawn sub-agents
- Designed for complex, long-horizon tasks

### Production Features

- **Durable state persistence**: Resume execution across sessions
- **Human-in-the-loop**: Pause for review at any point
- **Time-travel debugging**: Replay and inspect past states
- **Checkpointing**: Save and restore agent state
- **Structured outputs**: `with_structured_output` for typed tool results

### Tool Calling Patterns

- `bind_tools` to attach tool schemas to models
- `with_structured_output` for ensuring typed responses
- Any chat model supporting structured outputs and tool calling can be used
- Tool calling built on top of the model's native function calling capability

---

## 15. DeepLearning.AI: Andrew Ng's Agentic AI Patterns

**Source:** https://www.deeplearning.ai/courses/agentic-ai/

### Four Key Design Patterns

1. **Reflection**: Agent examines its own output and figures out how to improve it
   - Self-critique loop before finalizing output
   - No external tools required

2. **Tool Use**: LLM decides which functions to call
   - Agent selects from available tools based on task needs
   - Function calling with structured schemas

3. **Planning**: LLM breaks down task into sub-tasks
   - Hierarchical decomposition
   - Dynamic re-planning based on results

4. **Multi-Agent Collaboration**: Multiple specialized agents
   - Each agent has a focused role
   - Agents communicate and coordinate

### Key Insight

> "The single biggest predictor of whether someone can build effectively is whether they
> know how to drive a disciplined process for evals and error analysis."
> -- Andrew Ng

### Implementation Approach

- Vendor-neutral, using raw Python (no framework dependency)
- Core concepts transferable to any agentic framework
- Emphasis on understanding fundamentals over framework-specific patterns

---

## 16. Agent-Specific Prompt Engineering Patterns

**Sources:**
- https://www.prompthub.us/blog/prompt-engineering-for-ai-agents
- https://www.dsstream.com/post/prompt-secrets-ai-agents-and-code

### System Prompt Structure for Agents

| Section | Purpose | Example |
|---------|---------|---------|
| **Role Definition** | Define agent identity and expertise | "You are a senior Kotlin developer..." |
| **Environment Context** | OS, working directory, available tools | "Operating on macOS, cwd: /project" |
| **Tool Documentation** | Name, description, parameters, usage | XML-structured tool definitions |
| **Behavioral Rules** | When to act vs. ask, safety constraints | "Implement changes rather than suggesting" |
| **Output Format** | Response structure requirements | "Use tool call format: <tool_name>..." |
| **Planning Instructions** | How to approach tasks | "Think HOLISTICALLY before acting" |

### Mode Differentiation (from Cline)

- **PLAN MODE**: Gather context, brainstorm approach, analyze dependencies
- **ACT MODE**: Execute predetermined strategy, make changes, verify results

This prevents premature or misguided actions by separating thinking from doing.

### Tool Use Patterns for Agents

1. **Single-tool-per-message constraint**: One tool call per response, confirm before proceeding
2. **Dependency-aware sequencing**: "Create the file before running a command that would execute it"
3. **Comprehensive toolset organization**: Group related tools, provide decision trees
4. **Modular documentation**: Consistent format for every tool (name, description, params, examples, guidance)

### Planning Patterns

1. **Holistic pre-execution analysis**: Consider entire project context before any action
2. **Step-by-step confirmation**: Verify each step's success before proceeding
3. **TODO/rubric tracking**: Maintain structured progress tracking

### Error Handling

1. **Think-before-act**: Chain-of-thought reasoning before every tool call
2. **Confirmation requirements**: Approval before risky operations
3. **Environmental constraints**: Explicitly state limitations and boundaries

---

## 17. Technique Comparison Matrix

### For Agentic Coding Tasks

| Technique | Reasoning Quality | Tool Integration | Self-Improvement | Compute Cost | Best For |
|-----------|------------------|-----------------|-----------------|-------------|----------|
| **CoT** | Good | None | None | Low (1x) | Planning, decomposition |
| **ReAct** | Good | Excellent | None | Medium (1x + tools) | Tool-using agents (PRIMARY) |
| **Self-Consistency** | Excellent | None | None | High (3-5x) | Critical decisions |
| **ToT** | Excellent | None | None | Very High | Complex multi-path problems |
| **Reflexion** | Good | Good | Excellent | High (iterative) | Code generation, test-driven tasks |
| **LATS** | Excellent | Excellent | Excellent | Very High | High-stakes coding (94.4% HumanEval) |
| **ART** | Good | Excellent | None | Medium | Zero-shot tool use |
| **PAL** | N/A | Excellent | None | Low | Computation, data tasks |

### When to Apply Each Technique

| Scenario | Recommended Technique(s) |
|----------|--------------------------|
| Single tool call decision | ReAct |
| Multi-step coding task | ReAct + Reflexion |
| Critical architecture decision | ToT or Self-Consistency |
| Bug diagnosis | ReAct + CoT |
| Code generation from spec | Reflexion (with test feedback) |
| High-stakes code change | LATS (if compute budget allows) |
| Quick factual lookup | ReAct (minimal traces) |
| Complex refactoring | ReAct + planning phase |

---

## 18. Recommendations for Our Agent

Based on this research, here are the specific techniques most applicable to our IntelliJ plugin's agentic AI system:

### Primary Pattern: ReAct Loop

Our agent should use the ReAct pattern as its core execution loop:
```
System: You are a workflow automation agent for IntelliJ IDEA...
Thought: I need to [reasoning about next step]
Action: [tool_name](parameters)
Observation: [tool result]
Thought: [updated reasoning]
...
```

### Context Engineering Priorities

1. **System prompt structure**: Use XML tags to separate instructions, tool docs, and context
2. **Just-in-time context**: Load project state, file contents, and API data on demand via tools
3. **Compaction strategy**: Preserve architectural decisions while discarding verbose tool outputs
4. **Structured state**: Use JSON for task tracking, test results, and feature registries

### Self-Improvement via Reflexion

For code generation and modification tasks:
1. Generate code/changes
2. Run verification (tests, linting, compilation)
3. Self-reflect on failures
4. Iterate with reflection in context

### Tool Design Principles

1. Self-contained tools with clear, unambiguous descriptions
2. Token-efficient return values (summaries, not raw data)
3. Strict schemas for reliable invocation
4. ~20-100 tools is the sweet spot for current models

### Prompt Design Rules

1. **Be explicit about action vs. suggestion**: "Implement the change" not "Can you suggest..."
2. **Provide reasoning motivation**: Explain *why* rules exist, not just the rules
3. **Few-shot examples**: 3-5 canonical examples for complex tool use patterns
4. **Adaptive thinking**: Let the model decide when to reason deeply vs. act quickly
5. **Safety guardrails**: Confirm before irreversible actions (destructive git ops, deployments)
6. **Incremental progress**: One feature/task per iteration, verify before moving on

### Key Metrics to Track

- Tool call accuracy (correct tool selected)
- Task completion rate (end-to-end success)
- Token efficiency (context utilization)
- Self-correction rate (errors caught by reflection)
- User intervention rate (how often human override needed)

---

## Sources

### Academic Papers
- [Chain-of-Thought Prompting (Wei et al. 2022)](https://www.promptingguide.ai/techniques/cot)
- [ReAct: Synergizing Reasoning and Acting (Yao et al. 2022)](https://arxiv.org/abs/2210.03629)
- [ReAct - Google Research Blog](https://research.google/blog/react-synergizing-reasoning-and-acting-in-language-models/)
- [Tree of Thoughts (Yao et al. 2023)](https://www.promptingguide.ai/techniques/tot)
- [Self-Consistency (Wang et al. 2022)](https://www.promptingguide.ai/techniques/consistency)
- [Reflexion (Shinn et al. 2023)](https://www.promptingguide.ai/techniques/reflexion)
- [LATS (Zhou et al. 2023, ICML 2024)](https://arxiv.org/abs/2310.04406)
- [ART: Automatic Reasoning and Tool-use](https://www.promptingguide.ai/techniques/art)
- [PAL: Program-Aided Language Models](https://www.promptingguide.ai/techniques/pal)

### Industry Resources
- [Anthropic: Effective Context Engineering for AI Agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Anthropic: Effective Harnesses for Long-Running Agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- [Anthropic: Claude 4.x Prompting Best Practices](https://platform.claude.com/docs/en/docs/build-with-claude/prompt-engineering/claude-4-best-practices)
- [OpenAI: GPT-5 Prompting Guide](https://developers.openai.com/cookbook/examples/gpt-5/gpt-5_prompting_guide)
- [OpenAI: Prompt Engineering Guide](https://platform.openai.com/docs/guides/prompt-engineering)
- [OpenAI: o3/o4-mini Function Calling Guide](https://developers.openai.com/cookbook/examples/o-series/o3o4-mini_prompting_guide)
- [Microsoft: Prompt Engineering Techniques (Azure AI Foundry)](https://learn.microsoft.com/en-us/azure/foundry/openai/concepts/prompt-engineering)
- [Microsoft: System Message Design](https://learn.microsoft.com/en-us/azure/ai-services/openai/concepts/advanced-prompt-engineering)
- [Google: Prompt Engineering Whitepaper (Lee Boonstra, 2024)](https://www.kaggle.com/whitepaper-prompt-engineering)
- [Google: Prompt Engineering Whitepaper Summary](https://mfmfazrin.medium.com/prompt-engineering-whitepaper-by-google-eddb3b4842bc)
- [LangChain/LangGraph Workflows and Agents](https://docs.langchain.com/oss/python/langgraph/workflows-agents)
- [LangChain/LangGraph 1.0 Announcement](https://blog.langchain.com/langchain-langgraph-1dot0/)
- [LangChain Deep Agents](https://github.com/langchain-ai/deepagents)
- [DAIR.AI Prompt Engineering Guide](https://www.promptingguide.ai/)

### Courses and Educational
- [DeepLearning.AI: Agentic AI with Andrew Ng](https://www.deeplearning.ai/courses/agentic-ai/)
- [Anthropic: Interactive Prompt Engineering Tutorial](https://github.com/anthropics/prompt-eng-interactive-tutorial)

### Context Engineering
- [Neo4j: Context Engineering vs Prompt Engineering](https://neo4j.com/blog/agentic-ai/context-engineering-vs-prompt-engineering/)
- [Firecrawl: Context Engineering for AI Agents](https://www.firecrawl.dev/blog/context-engineering)
- [SDG Group: Evolution of Prompt Engineering to Context Design (2026)](https://www.sdggroup.com/en/insights/blog/the-evolution-of-prompt-engineering-to-context-design-in-2026)

### Agent Prompt Engineering
- [PromptHub: Prompt Engineering for AI Agents](https://www.prompthub.us/blog/prompt-engineering-for-ai-agents)
- [DS Stream: Prompt Secrets for AI Agents and Code](https://www.dsstream.com/post/prompt-secrets-ai-agents-and-code)
- [Comet: Chain-of-Thought Prompting for LLM Agents](https://www.comet.com/site/blog/chain-of-thought-prompting/)
