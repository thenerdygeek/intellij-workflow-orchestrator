# OpenAI Official Prompt Engineering & Agentic AI Best Practices

> Research compiled 2026-03-26 from OpenAI's official documentation, cookbooks, and blog posts.

---

## Table of Contents

1. [Prompt Engineering Fundamentals](#1-prompt-engineering-fundamentals)
2. [GPT-4.1 Prompting Guide](#2-gpt-41-prompting-guide)
3. [GPT-5 Prompting Guide](#3-gpt-5-prompting-guide)
4. [GPT-5.4 Prompt Guidance](#4-gpt-54-prompt-guidance)
5. [Function Calling / Tool Use](#5-function-calling--tool-use)
6. [Structured Outputs](#6-structured-outputs)
7. [Building Agents — Practical Guide](#7-building-agents--practical-guide)
8. [Agents SDK Architecture](#8-agents-sdk-architecture)
9. [Long-Running Agents: Skills, Shell, Compaction](#9-long-running-agents-skills-shell-compaction)
10. [Governed Agents & Guardrails](#10-governed-agents--guardrails)
11. [Cross-Cutting Anti-Patterns](#11-cross-cutting-anti-patterns)
12. [Key Takeaways for Our Agent](#12-key-takeaways-for-our-agent)

---

## 1. Prompt Engineering Fundamentals

**Source:** [Prompt Engineering Guide](https://developers.openai.com/api/docs/guides/prompt-engineering)

### Message Roles & Authority Hierarchy

The OpenAI model spec establishes a chain of command:
- `developer` messages receive higher priority than `user` messages
- Developer messages provide system rules and business logic
- User messages provide inputs and configuration
- Use the `instructions` parameter for high-level behavioral guidance (tone, goals, correct response examples)

### Recommended System Prompt Structure

Organize developer messages into distinct sections using Markdown headers and XML tags:

```
1. Identity — purpose, style, goals
2. Instructions — rules, dos/don'ts, function calling guidance
3. Examples — input-output pairs
4. Context — proprietary data, reference materials
```

### Structural Formatting

- **Markdown**: Recommended starting point; use H1-H4+ hierarchy, inline backticks, and lists
- **XML tags**: Perform well; enable precise wrapping, metadata, and nesting for complex structures
- **JSON**: Highly structured but verbose; avoid for large document collections

### Reusable Prompt Templates

Create dashboard prompts with placeholders like `{{customer_name}}` to decouple prompt development from code. Substitute variables at runtime.

### Model Selection Considerations

- **Reasoning models**: Excel at complex tasks and multi-step planning but slower/costlier
- **GPT models**: Fast, efficient, require explicit task instructions
- Larger models handle complex domains; smaller ones prioritize speed and cost

### Critical Warnings

- **Output Parsing Risk**: Never assume text output is at `output[0].content[0].text`. Always check the full response structure.
- **Version Pinning**: Pin production applications to specific model snapshots (e.g., `gpt-4.1-2025-04-14`) for consistency.

---

## 2. GPT-4.1 Prompting Guide

**Source:** [GPT-4.1 Prompting Guide (Cookbook)](https://developers.openai.com/cookbook/examples/gpt4-1_prompting_guide)

### Agentic System Prompt — Three Critical Components

Every agentic prompt for GPT-4.1 MUST include:

1. **Persistence**: "Keep going until the user's query is completely resolved, before yielding back."
2. **Tool-Calling**: "If uncertain about file content or codebase structure, use your tools to read files — do NOT guess or make up answers."
3. **Planning (Optional)**: "Plan extensively before each function call, and reflect on outcomes rather than chaining only tool calls."

### Tool Definition Best Practices

- Use the `tools` parameter in API requests rather than injecting descriptions into prompts manually
- Employ clear tool names indicating purpose
- Provide detailed descriptions in the `description` field
- Add parameter-level descriptions for clarity
- Create an `# Examples` section in system prompts for complex tools (not in field descriptions)
- This approach yields ~2% improvement in task success rates

### Induced Planning & Chain-of-Thought

GPT-4.1 is NOT a reasoning model but responds well to explicit planning prompts. "Thinking out loud" can increase pass rates by ~4% on complex tasks.

Multi-step reasoning framework:
1. **Query Analysis**: Break down the query; clarify ambiguities using context
2. **Context Analysis**: Optimize for recall; rate document relevance (high/medium/low/none)
3. **Synthesis**: Summarize which documents matter most and why

### Long Context Optimization

- Effective performance extends to the full 1M token context window
- Complex multi-hop reasoning across entire contexts may degrade performance
- **Place instructions at both the beginning AND end** of long context passages
- If using only one location, place ABOVE the context rather than below

### Document Formatting for Multi-Document Inputs

Performance ranking:
1. **XML with metadata**: `<doc id='1' title='Title'>Content</doc>` (best)
2. **Pipe-delimited**: `ID: 1 | TITLE: Title | CONTENT: Content`
3. **JSON**: (poorest performance — avoid for large document sets)

### Instruction Following — Workflow for Prompt Development

1. Create an "Instructions" or "Response Rules" section with bullet-point guidance
2. Add subsections for specific behaviors (e.g., `# Sample Phrases`)
3. Include ordered workflow steps if needed
4. Debug iteratively:
   - Check for conflicting or underspecified instructions
   - Later instructions override earlier ones
   - Add examples demonstrating desired behavior
   - Avoid ALL-CAPS or bribes unless necessary

### Recommended Prompt Structure (GPT-4.1)

```
# Role and Objective
# Instructions
## Sub-categories for detailed instructions
# Reasoning Steps
# Output Format
# Examples
# Context
# Final instructions and step-by-step prompting
```

### Key Behavioral Note

GPT-4.1 follows instructions **more literally** than predecessors:
- Less inference of implicit intent
- Higher steerability through precise, unambiguous language
- A single clarifying sentence can redirect behavior effectively

---

## 3. GPT-5 Prompting Guide

**Source:** [GPT-5 Prompting Guide (Cookbook)](https://developers.openai.com/cookbook/examples/gpt-5/gpt-5_prompting_guide)

### Controlling Agentic Eagerness

**For reduced autonomy:**
- Lower `reasoning_effort`
- Define clear exploration criteria
- Set fixed tool call budgets (e.g., max 2 calls)

**For increased autonomy:**
- Raise `reasoning_effort`
- Encourage persistence: "keep going until resolved"
- Provide escape hatches for proceeding under uncertainty

### Tool Preambles Pattern

"Always begin by rephrasing the user's goal in a friendly, clear, and concise manner, before calling any tools" followed by structured step-by-step planning and progress narration. Improves user experience on monitored agentic tasks.

### Instruction Clarity

GPT-5's surgical precision means **contradictory instructions damage reasoning**. Resolve conflicts by establishing clear hierarchies and exception clauses (e.g., "Do not do lookup in emergency case").

### Coding Task Optimization

- Prefer readable, maintainable solutions with clear names
- Comments where needed
- Straightforward control flow rather than code golf
- Balance verbose code outputs with concise status updates

### Self-Reflection for Complex Tasks

Have the model "think of a rubric until confident," then "think deeply about every aspect" using a 5-7 category rubric before building. Useful for zero-to-one creative tasks.

### Responses API: Context Reuse

Reusing reasoning context with `previous_response_id`:
- +4.3% Tau-Bench performance increase
- Lower costs
- Eliminates plan reconstruction between tool calls

### Planning Prompt Template

"Decompose the user's query into all required sub-requests, and confirm that each is completed. Do not stop after completing only part of the request."

### Anti-Patterns

- **Conflicting Instructions**: Avoid "Never schedule without consent" clashing with "auto-assign without contacting." Use clear exception handlers.
- **Over-Prompting for Over-Reasoning**: Instructions like "Be THOROUGH when gathering information" cause excessive tool calls. Soften: "Bias towards not asking for help if you can find the answer yourself."
- **Unresolved Ambiguity**: Failing to disambiguate tool instructions impairs long-horizon task completion.

### Meta-Prompting

Use GPT-5 itself to refine prompts: "What specific phrases could be added to or deleted from this prompt to elicit the desired behavior?"

---

## 4. GPT-5.4 Prompt Guidance

**Source:** [GPT-5.4 Prompt Guidance](https://developers.openai.com/api/docs/guides/prompt-guidance)

### Core Strengths

GPT-5.4 excels at:
- Long-running task performance
- Stronger control over style and behavior
- More disciplined execution across complex workflows
- Agentic workflow robustness
- Evidence-rich synthesis in multi-tool scenarios
- Batched parallel tool calling

### Output Contracts & Compactness

Define explicit output requirements:
- "Return exactly the sections requested, in the requested order"
- Avoid repeating user requests
- Lock verbosity through both the `verbosity` parameter and prompt-level constraints

### Default Follow-Through Policy

- Proceed without asking when tasks are reversible and low-risk
- Only request permission for irreversible actions, external side effects, or missing sensitive information
- State clearly what actions you're taking and what remains optional

### Persistent Tool Discipline

"Use tools whenever they materially improve correctness, completeness, or grounding. Do not stop early when another tool call is likely to materially improve correctness or completeness."

**Dependency-aware execution:**
- Check prerequisites before taking actions
- Don't skip prerequisite steps just because the end goal seems obvious
- If a task depends on prior output, resolve that dependency first

**Parallelism guidance:**
- Parallelize independent retrieval/lookup steps to reduce latency
- Sequence steps with prerequisite dependencies
- Pause to synthesize parallel results before proceeding

### Completeness Contracts

Treat tasks as incomplete until all requested items are covered:
- Maintain internal checklists
- Track processed items
- Confirm coverage before finalizing
- Mark blocked items explicitly with what's missing

**Empty-result recovery:**
When lookups return empty/partial results, don't immediately conclude nothing exists. Try fallback strategies: alternate query wording, broader filters, prerequisite lookups, or alternate sources before reporting failure.

### Pre-Execution Verification

Before finalizing, check:
1. **Correctness**: Does output satisfy all requirements?
2. **Grounding**: Are claims backed by context?
3. **Formatting**: Does it match the requested schema?
4. **Safety**: Are there external side effects?

For irreversible actions, use a lightweight frame:
- **Pre-flight**: summarize the intended action in 1-2 lines
- **Execute** via tool
- **Post-flight**: confirm outcome and validation performed

### Missing Context Gating

"If required context is missing, do NOT guess."
- Prefer lookup tools when retrievable
- Ask clarifying questions only when unavoidable
- If proceeding anyway, label assumptions explicitly and choose reversible actions

### Citation & Grounding Rules

- Only cite sources retrieved in the current workflow
- Never fabricate citations, URLs, IDs, or quote spans
- Attach citations to specific claims, not just at the end
- Base claims only on provided context or tool outputs
- State conflicts explicitly with attribution

### Three-Pass Research Mode

1. **Plan**: list 3-6 sub-questions to answer
2. **Retrieve**: search each sub-question and follow 1-2 second-order leads
3. **Synthesize**: resolve contradictions and write the final answer with citations

Stop only when additional searching is unlikely to change conclusions.

### Coding & Terminal Agent Guidance

- "Persist until the task is fully handled end-to-end within the current turn whenever feasible"
- Don't stop at analysis or partial fixes; carry through implementation, verification, and explanation
- Assume the user wants code changes unless they explicitly ask for analysis/brainstorming
- If blocked, attempt resolution yourself rather than deferring to the user

**Intermediary Updates:**
- Send updates roughly every 30 seconds
- 1-2 sentence updates explaining progress
- Before file edits, state what you're changing
- Avoid meta-commentary openers like "Got it" or "Done"

**Terminal Hygiene:**
- Use specialized patch/edit tools directly rather than bash workarounds
- After changes, run lightweight verification (ls, tests, builds) before declaring done

### Reasoning Effort Tuning

Treat reasoning as a **last-mile knob**, not the primary quality lever.

**Recommended defaults:**
- `none`: Fast, cost/latency-sensitive tasks
- `low`: Latency-sensitive tasks where small thinking yields meaningful gains
- `medium`/`high`: Tasks truly requiring stronger reasoning
- `xhigh`: Avoid as default; only for long, agentic, reasoning-heavy tasks

**Before increasing reasoning effort, first add:**
- `<completeness_contract>`
- `<verification_loop>`
- `<tool_persistence_rules>`

### Context Preservation: Compaction

For very long sessions, use the `/responses/compact` endpoint after major milestones. GPT-5.4 remains more coherent when compaction is used strategically.

### Phase Parameter for Long-Running Agents

Use the `phase` field to distinguish working commentary from final answers. Preserve `phase` when replaying prior assistant items — missing `phase` causes preambles to be misinterpreted as final answers.

---

## 5. Function Calling / Tool Use

**Source:** [Function Calling Guide](https://developers.openai.com/api/docs/guides/function-calling)

### Defining Tools

Tools use a consistent schema:
```json
{
  "type": "function",
  "name": "tool_name",
  "description": "Clear description of what this tool does",
  "parameters": {
    "type": "object",
    "properties": { ... },
    "required": [ ... ]
  },
  "strict": true  // optional
}
```

### Best Practices for Tool Descriptions

1. **Clarity and Detail**: Write clear, detailed function names, parameter descriptions, and instructions with explicit purpose statements and format specifications
2. **System Prompt Guidance**: Use the system prompt to describe when (and when not) to use each function
3. **Examples and Edge Cases**: Include examples to address recurring failures (note: examples may impact reasoning model performance)
4. **Deferred Tools**: Put detailed guidance in the function description; keep namespace description concise

### Parameter Schema Best Practices

- **Avoid Invalid States**: Use enums and object structures to prevent impossible parameter combinations
- **The Intern Test**: "Can an intern/human correctly use the function given nothing but what you gave the model?"
- **Leverage Code**: Don't make the model fill arguments you already know; combine functions called sequentially
- **Reduce Cognitive Load**: Keep initially available functions under 20 to maintain accuracy

### Namespace Grouping

Group related tools by domain (e.g., `crm`, `billing`) to help models choose between similar tools serving different systems.

### Strict Mode (Structured Outputs for Functions)

Enable strict mode to ensure function calls reliably adhere to the function schema:
- `additionalProperties` must be `false` for each object
- All fields in `properties` must be marked `required`
- Optional fields use `null` as an additional type option

### Parallel Function Calling

The model can call multiple functions in one turn by default. Disable with `parallel_tool_calls: false` to enforce zero or one tool call per turn.

### Tool Choice Options

- `"auto"` (default): Zero or more functions
- `"required"`: One or more functions
- Specific function: Force one particular tool
- `"allowed_tools"`: Restrict to a subset while maintaining prompt caching benefits

### Error Handling

- Return results as strings (JSON, error codes, or plain text)
- For images/files, pass object arrays instead
- For functions with no return value, indicate success/failure as a string
- Always handle zero, one, or multiple function calls — assume there are several

### Token Optimization

Functions count as input tokens (injected into system message). Reduce token usage by:
- Limiting initially loaded functions
- Shortening descriptions
- Using tool search for deferred loading of infrequently used tools

---

## 6. Structured Outputs

**Source:** [Structured Outputs Guide](https://developers.openai.com/api/docs/guides/structured-outputs)

### When to Use

**Via `response_format`:**
- Structuring model output for user display
- Data extraction tasks
- Generating consistent UI-compatible responses

**Via function calling:**
- Connecting models to tools, functions, or databases
- Building AI assistants with system integrations
- Enabling model-driven application functionality

### Implementation

```json
// Chat Completions API
response_format: {
  type: "json_schema",
  json_schema: { "strict": true, "schema": { ... } }
}

// Responses API
text: { format: { type: "json_schema", "strict": true, "schema": { ... } } }
```

### Schema Constraints

- Must be valid JSON Schema
- Supports objects, arrays, strings, numbers, booleans, enums
- `additionalProperties: false` enforces strict field validation
- All required fields must be explicitly declared

### Best Practices

1. **Define clear schemas**: Explicitly list all required fields and their types
2. **Use enums for choices**: Restrict values to predefined options rather than free text
3. **Leverage descriptions**: Add property descriptions to guide model behavior
4. **Use SDK helpers**: Pydantic (Python) or Zod (JS) to keep schemas and types in sync
5. **Handle refusals**: Check the `refusal` field for safety-based refusals
6. **Keep schemas focused**: Avoid overly complex nested structures

### Structured Outputs vs JSON Mode

| Feature | Structured Outputs | JSON Mode |
|---------|-------------------|-----------|
| Valid JSON | Yes | Yes |
| Schema adherence | Yes | No |
| Explicit refusals | Yes | No |

### Limitations

- Requires newer model support (gpt-4o-mini+)
- Schema complexity impacts performance
- Cannot guarantee content quality — only format

---

## 7. Building Agents — Practical Guide

**Source:** [Building Agents Track](https://developers.openai.com/tracks/building-agents), [Practical Guide to Building AI Agents](https://openai.com/business/guides-and-resources/a-practical-guide-to-building-ai-agents/)

### Definition

An agent is "an AI system that has instructions (what it should do), guardrails (what it should not do), and access to tools (what it can do) to take action on the user's behalf."

Agents differ from chatbots through their capacity to **execute actions**, not merely answer questions.

### Model Selection Strategy

- **Reasoning models** (o1, o3): Excel at complex planning, math, multi-tool workflows. Trade latency/cost for reliability.
- **Non-reasoning models** (gpt-5.4, gpt-5.4-mini): Suit chatlike experiences requiring fast responses.
- **Recommended progression**: Start with flagship multi-purpose models with minimal reasoning_effort, then optimize.
- **Latency-reliability tradeoff**: Pair a fast model for user interaction with a powerful model for delegated tasks.

### Tool Architecture — Priority Hierarchy

1. **Built-in tools first** (when available) — execute server-side automatically
2. **Function calling** (for custom logic) — requires client-side execution loop

Built-in tools available:
| Tool | Use Case |
|------|----------|
| Web Search | Recent information retrieval |
| File Search | Internal knowledge access (RAG) |
| Code Interpreter | Data manipulation, analysis |
| Computer Use | API-less service automation |
| Image Generation | Visual content creation |
| MCP | Protocol-based hosted server integration |

### Function Calling Execution Loop

1. Define functions
2. Model selects and calls with parameters
3. Execute on client
4. Return results to conversation context
5. Model generates next response

### Orchestration Patterns

#### Single Agent Foundation

Establish:
- Clear instructions
- Appropriate tool set
- Guardrail implementation
- Context/session management

#### Multi-Agent Networks

Deploy multiple agents when you have:
- Separate, non-overlapping tasks
- Complex/lengthy instructions per task
- Task-specific tool sets

**Implementation**: Routing agent -> task-specific agents with handoffs, sharing memory keyed by `conversation_id`.

**Advantages of separation:**
- Separation of concerns (research vs. drafting vs. QA)
- Parallelism for faster execution
- Focused evaluation per agent scope

### Guardrail Implementation

**Input guardrails:**
- Simple: include constraints in system prompt ("don't answer unrelated questions")
- Complex: multi-step filtering depending on risk tolerance

**Output guardrails:**
- Use structured outputs to constrain responses to JSON schemas
- Validate against business rules (pricing, compliance)
- Essential when agent output integrates into application logic

### Design Principles

1. **Composition over monolith**: Multiple focused agents outperform mega-prompts for complex scenarios
2. **Tool-first thinking**: Leverage built-in tools before custom functions
3. **Progressive complexity**: Start simple, add multi-agent networks only when justified
4. **Observable systems**: Use tracing for rapid iteration
5. **Risk-appropriate safeguards**: Guardrail sophistication should match deployment context

---

## 8. Agents SDK Architecture

**Source:** [OpenAI Agents SDK](https://openai.github.io/openai-agents-python), [Agents SDK Guide](https://developers.openai.com/api/docs/guides/agents-sdk)

### Core Primitives

| Primitive | Function |
|-----------|----------|
| **Agent** | LLM + instructions + tools |
| **Handoff** | Route to another agent |
| **Guardrail** | Input/output filtering/validation |
| **Session** | Persistent memory for maintaining context |

### Agent Configuration

- Name and instruction parameters define agent behavior
- Instructions provide context for LLM decision-making
- Agents can be equipped with multiple tools and capabilities

### Tool Types

1. **Function tools**: Turn any Python function into a tool with automatic schema generation
2. **MCP server integration**: Built-in Model Context Protocol support
3. **Hosted tools**: Access to external services
4. **Agents as tools**: Delegation patterns where agents act as callable tools

### Multi-Agent Orchestration Patterns

1. **Handoffs**: Direct agent-to-agent delegation for specific tasks
2. **Manager-style orchestration**: Centralized coordination of multiple agents

### Guardrails

"Enable validation of agent inputs and outputs. Run input validation and safety checks in parallel with agent execution, and fail fast when checks do not pass."

### State Management (Sessions)

Provides "persistent memory layer for maintaining working context within an agent loop." Backends: SQLAlchemy, SQLite, Redis, encrypted variants.

### Runner (Agent Loop)

The Runner "handles tool invocation, sends results back to the LLM, and continues until the task is complete." Built-in streaming support for real-time output.

### Observability (Tracing)

Provides "visualizing, debugging, and monitoring workflows" with integration for evaluation and fine-tuning. Groups operations under a single trace ID linking LLM calls, tool executions, handoffs, and guardrail checks.

### Design Principles

1. Balance sufficient features with learning simplicity
2. "Works great out of the box, but you can customize exactly what happens"

---

## 9. Long-Running Agents: Skills, Shell, Compaction

**Source:** [Shell + Skills + Compaction: Tips for long-running agents](https://developers.openai.com/blog/skills-shell-tips)

### Skills Pattern

Skills function as **versioned procedure bundles** with a `SKILL.md` manifest. The model accesses skill metadata (name, description, path) to determine invocation, then reads full workflows from the manifest.

**Key patterns for skill effectiveness:**

1. **Routing descriptions should include decision logic** — specify when to use AND when NOT to use the skill, with concrete inputs and success criteria
2. **Negative examples reduce misfires** — explicit "Don't call this skill when..." cases help routing, especially with overlapping skills
3. **Embed templates and examples inside skills** — "Templates and worked examples inside skills have two advantages: they're available exactly when needed (when the skill is invoked). They don't inflate tokens for unrelated queries."
4. **Explicit invocation for deterministic workflows** — direct the model with "Use the `<skill name>` skill" when prioritizing reliability over discovery

### Compaction

Design for continuity from the start. Use compaction as a **default primitive** rather than emergency fallback. This "reduces restart behavior and keeps multi-step jobs coherent as the thread grows."

### Build Patterns

- **Pattern A**: Install dependencies -> fetch data -> write artifact
- **Pattern B**: Skills encode workflow steps; shell executes them; agents follow skills deterministically
- **Pattern C**: Skills serve as enterprise workflow carriers, embedding procedures that bridge tool reasoning without inflating system prompts

---

## 10. Governed Agents & Guardrails

**Source:** [Agentic Governance Cookbook](https://developers.openai.com/cookbook/examples/partners/agentic_governance_guide/agentic_governance_cookbook)

### Core Philosophy

Governance **accelerates delivery** rather than impeding it. When guardrails are transparent and automated, teams build with confidence.

### Multi-Agent Architecture for Governance

- **Triage agents** receive all queries and determine routing
- **Specialist agents** handle domain-specific tasks
- **Handoff descriptions** define when each specialist should engage
- Users experience seamless conversations while experts handle specific domains

### Two-Level Guardrail Approach

**Built-in Agent Guardrails (Per-Agent):**
Defined via the Agents SDK using `InputGuardrail` or `OutputGuardrail`. Best for domain-specific validation.

**Centralized Guardrails (Organization-Wide):**
Universal policies applicable to all calls:
- PII detection (credit cards, SSN, email, medical)
- Moderation (sexual, hate, harassment, violence)
- Jailbreak detection
- Off-topic prompt detection
- Prompt injection detection
- Keyword filtering
- Secret key detection
- Hallucination detection

### Policy Stages

1. **Pre-flight**: Universal validation before any processing
2. **Input**: Blocks bad inputs before LLM calls
3. **Output**: Redacts sensitive data from responses

### Policy as Code

Package governance as an installable library for:
- Consistency across projects
- Easy updates via package manager
- Full audit trail via Git history
- Single compliance reference

### Observability for Governed Agents

The `trace()` context manager groups operations under a single ID, linking LLM calls, tool executions, handoffs, and guardrail checks. For ZDR (zero data retention) environments, use custom trace processors to export spans to internal systems.

---

## 11. Cross-Cutting Anti-Patterns

Compiled from all sources above.

### Prompt Design Anti-Patterns

| Anti-Pattern | Why It's Bad | Fix |
|-------------|-------------|-----|
| Contradictory instructions | Damages reasoning, especially in GPT-5+ | Establish clear hierarchies and exception clauses |
| Over-constraining ("always call a tool first") | No escape clause leads to stuck agents | Add "unless you lack information" |
| ALL-CAPS and bribes in prompts | Unreliable steering mechanism | Use clear, structured instructions instead |
| Repetition without variation control | Model repeats sample phrases verbatim | Instruct to vary language |
| Unspecified output format | Model adds excessive explanation/formatting | Define explicit output contracts |
| JSON for large document collections | Worst performance of all formats | Use XML or pipe-delimited instead |
| Instructions only at end of long context | Worse instruction following | Place at both beginning AND end |

### Tool Design Anti-Patterns

| Anti-Pattern | Why It's Bad | Fix |
|-------------|-------------|-----|
| Manual tool descriptions in prompt text | Less effective than API `tools` parameter | Use structured `tools` parameter |
| More than 20 initially loaded functions | Accuracy degrades | Use deferred tool loading / tool search |
| Complex examples in field descriptions | Wrong place, inflates tokens | Put examples in system prompt `# Examples` section |
| Making model fill known arguments | Wastes tokens and introduces errors | Pre-fill known values in code |
| No error feedback from tool results | Model can't recover from failures | Always return success/failure strings |

### Agent Architecture Anti-Patterns

| Anti-Pattern | Why It's Bad | Fix |
|-------------|-------------|-----|
| Monolithic mega-prompt agents | Unwieldy, hard to update, poor performance | Multiple focused agents with handoffs |
| Governance as launch-time inspection | Blocks delivery, inconsistent | Governance as infrastructure from day one |
| No tracing/observability | Can't debug or optimize | Enable tracing by default |
| Compaction as emergency fallback | Restart behavior, incoherent long sessions | Use compaction as default primitive |
| Stopping at partial completion | Poor user experience | Completeness contracts with checklists |
| Guessing when context is missing | Hallucination risk | "If required context is missing, do NOT guess" |
| Immediately concluding nothing exists on empty results | Misses recoverable information | Try fallback strategies first |

---

## 12. Key Takeaways for Our Agent

### System Prompt Architecture

Based on all sources, the optimal system prompt structure is:

```
# Identity & Role
[Who the agent is, its purpose, personality]

# Instructions
## Core Behavioral Rules
- Persistence: "Keep going until fully resolved"
- Tool discipline: "Use tools rather than guessing"
- Planning: "Plan before each action, reflect on outcomes"

## Tool Usage Rules
[When to use each tool, when NOT to use them]
[Negative examples for routing clarity]

## Output Format
[Explicit output contracts]
[Formatting requirements]

# Reasoning Steps
[Multi-step workflow if applicable]

# Examples
[Input-output pairs for complex behaviors]

# Context
[Dynamic context injected at runtime]

# Reminders
[Repeat critical rules — instructions at both start and end]
```

### Tool Description Patterns

1. Use the structured `tools` API parameter, not manual prompt injection
2. Clear names indicating purpose
3. Detailed `description` field with when-to-use and when-NOT-to-use
4. Parameter-level descriptions
5. Keep initially available tools under 20; defer the rest
6. Enable strict mode for reliable schema adherence
7. Group related tools by namespace/domain

### Agent Loop Design

1. **ReAct pattern**: Plan -> Act (tool call) -> Observe (tool result) -> Repeat
2. Maintain completeness checklists
3. Pre-flight verification before irreversible actions
4. Post-flight confirmation after execution
5. Fallback strategies on empty results
6. Compaction at milestones for long sessions
7. Tracing for all operations

### Guardrail Architecture

1. Per-agent guardrails for domain-specific validation
2. Centralized guardrails for organization-wide policies (PII, moderation, injection)
3. Three stages: pre-flight, input, output
4. Package as versioned code

### Reasoning Effort as Last-Mile Knob

Stronger prompts and verification loops should be the primary quality lever:
1. Add completeness contracts
2. Add verification loops
3. Add tool persistence rules
4. THEN increase reasoning effort if still needed

---

## Sources

### Official Documentation
- [Prompt Engineering Guide](https://developers.openai.com/api/docs/guides/prompt-engineering)
- [Function Calling Guide](https://developers.openai.com/api/docs/guides/function-calling)
- [Structured Outputs Guide](https://developers.openai.com/api/docs/guides/structured-outputs)
- [GPT-5.4 Prompt Guidance](https://developers.openai.com/api/docs/guides/prompt-guidance)
- [Agents SDK Overview](https://developers.openai.com/api/docs/guides/agents-sdk)
- [Building Agents Track](https://developers.openai.com/tracks/building-agents)

### Cookbooks & Guides
- [GPT-4.1 Prompting Guide](https://developers.openai.com/cookbook/examples/gpt4-1_prompting_guide)
- [GPT-5 Prompting Guide](https://developers.openai.com/cookbook/examples/gpt-5/gpt-5_prompting_guide)
- [Agents Cookbook Topic](https://developers.openai.com/cookbook/topic/agents)
- [Agentic Governance Cookbook](https://developers.openai.com/cookbook/examples/partners/agentic_governance_guide/agentic_governance_cookbook)
- [Context Engineering for Personalization](https://developers.openai.com/cookbook/examples/agents_sdk/context_personalization)

### Blog Posts & Announcements
- [OpenAI for Developers in 2025](https://developers.openai.com/blog/openai-for-developers-2025/)
- [Shell + Skills + Compaction Tips](https://developers.openai.com/blog/skills-shell-tips)
- [New Tools for Building Agents](https://openai.com/index/new-tools-for-building-agents/)
- [Introducing AgentKit](https://openai.com/index/introducing-agentkit/)
- [Using Skills to Accelerate OSS Maintenance](https://developers.openai.com/blog/skills-agents-sdk)
- [A Practical Guide to Building AI Agents](https://openai.com/business/guides-and-resources/a-practical-guide-to-building-ai-agents/)
- [A Practical Guide to Building with GPT-5](https://openai.com/business/guides-and-resources/a-practical-guide-to-building-with-ai/)

### SDK Repositories
- [OpenAI Agents Python SDK](https://github.com/openai/openai-agents-python)
- [Agents SDK Python Docs](https://openai.github.io/openai-agents-python)
- [Agents SDK JS Docs](https://openai.github.io/openai-agents-js)
