# Multi-Agent Sub-Agent Spawning & LLM-Driven Delegation Research

Research date: 2026-03-28

---

## 1. LangGraph -- Supervisor Sub-Agent Pattern

### Architecture
LangGraph provides a `create_supervisor` function that creates a hierarchical multi-agent system. A central supervisor agent orchestrates specialized worker agents by controlling communication flow and task delegation.

### How the Supervisor Decides Which Agent to Call
- The supervisor is an LLM with **handoff tools** -- one tool per worker agent
- By default, tools are auto-generated via `create_handoff_tool`, named `transfer_to_<agent_name>`
- The supervisor's **system prompt** tells it which agent handles what: e.g., `"You are a team supervisor managing a research expert and a math expert. For current events, use research_agent. For math problems, use math_agent."`
- The LLM evaluates current context + task requirements, then calls the appropriate handoff tool
- Handoff tools pass the **full message history** to the sub-agent by default

### Key API
```python
workflow = create_supervisor(
    agents=[agent1, agent2],
    model=model,
    prompt="supervisor instructions",
    output_mode="full_history",        # or "last_message"
    add_handoff_messages=True,         # include handoff invocations
    handoff_tool_prefix="transfer_to", # customize prefix
    tools=[custom_tools],              # override default handoff tools
)
```

### Three Architectures Supported
1. **Supervisor Architecture** -- structured, central coordinator
2. **Swarm Architecture** -- decentralized, agents hand off to each other
3. **Collaborative Architecture** -- blends supervisor + swarm

### Production Features (v1.1, Dec 2025)
- Model retry middleware with configurable exponential backoff
- Content moderation middleware
- Streaming, memory persistence, human-in-the-loop

Sources:
- https://github.com/langchain-ai/langgraph-supervisor-py
- https://pypi.org/project/langgraph-supervisor/

---

## 2. CrewAI -- Task Delegation Pattern

### Architecture
CrewAI implements a **Hierarchical Process** with a manager-worker pattern. A manager agent coordinates task execution through delegation.

### How the LLM Decides Which Agent Handles What
- The manager agent is an LLM with a system prompt saying: "You are a manager. Here are your workers and their capabilities. Delegate to achieve the goal."
- **Other agents are converted into tools** that the manager can invoke
- Two auto-generated delegation tools:
  - `Delegate work to coworker(task: str, context: str, coworker: str)` -- assign tasks
  - `Ask question to coworker(question: str, context: str, coworker: str)` -- gather info
- Manager considers "each agent's capabilities and available tools" when assigning

### Configuration
```python
project_crew = Crew(
    tasks=[...],
    agents=[researcher, writer],
    manager_llm="gpt-4o",           # auto-create manager
    # OR manager_agent=custom_mgr,  # custom manager
    process=Process.hierarchical,
    planning=True,
)
```

### Key Design Rules
- Manager agent must NOT be in the `agents` list -- operates outside the worker pool
- Tasks do NOT require explicit agent assignment -- manager dynamically assigns based on roles, goals, capabilities
- `allow_delegation=True` must be set (now disabled by default)
- Manager validates results before proceeding
- Best practice: only specialist "worker" agents should have tools (search, DB, etc); keep manager focused on orchestration
- Manager needs strong instruction-following: GPT-4o or Claude 3.5 Sonnet for complex delegation; GPT-4o-mini for simple crews

Sources:
- https://docs.crewai.com/en/learn/hierarchical-process
- https://docs.crewai.com/en/concepts/collaboration
- https://deepwiki.com/crewAIInc/crewAI/2.4-process-types

---

## 3. AutoGen (Microsoft) -- Agent Communication & Delegation

### Strategic Context
Microsoft has placed AutoGen into **maintenance mode** (bug fixes only), shifting to **Microsoft Agent Framework** targeting 1.0 GA by end of Q1 2026. AutoGen's patterns are now unified under the "Workflow" abstraction.

### Communication Model
Based on the **Actor Model of concurrency**: each agent is an independent "actor" that encapsulates its own state and behavior. They interact exclusively through **asynchronous message passing**.

### Swarm Pattern (Source Code Analyzed)
```python
class SwarmGroupChatManager(BaseGroupChatManager):
    """Selects next speaker based on handoff message only."""

    async def select_speaker(self, thread):
        # Looks for the LAST HandoffMessage in the thread
        for message in reversed(thread):
            if isinstance(message, HandoffMessage):
                self._current_speaker = message.target
                return [self._current_speaker]
        return self._current_speaker  # stay with current if no handoff
```
- First participant is initial speaker
- Next speaker determined by `HandoffMessage.target`
- If no handoff message, current speaker continues
- Agents produce `HandoffMessage` via tool calling (same as OpenAI pattern)

### SelectorGroupChat -- LLM-Based Speaker Selection
The LLM selects the next speaker using agent descriptions and conversation history.

**Default selector prompt:**
```python
selector_prompt = """Select an agent to perform task.

{roles}

Current conversation context:
{history}

Read the above conversation, then select an agent from {participants} to perform the next task.
Make sure the planner agent has assigned tasks before other agents start working.
Only select one agent.
"""
```
Where:
- `{roles}` = `"<name> : <description>"` (newline-separated)
- `{participants}` = `["<name1>", "<name2>", ...]`
- `{history}` = `"<name> : <message content>"` (double-newline separated)

**Critical quote:** "Agents' `name` and `description` attributes are used by the model to determine the next speaker, so it is recommended to provide meaningful names and descriptions."

### Five Orchestration Patterns
1. **Sequential** -- step-by-step workflows
2. **Concurrent** -- agents work in parallel
3. **Group chat** -- agents brainstorm collaboratively
4. **Handoff** -- responsibility moves between agents as context evolves
5. **Magentic** -- manager agent builds/refines a dynamic task ledger

### A2A (Agent-to-Agent Protocol)
Introduced by Google in 2025 with Microsoft support. Agents expose "Agent Cards" (JSON metadata) advertising capabilities, accepted task formats, and communication protocols. Enables cross-runtime, cross-cloud agent coordination.

Sources:
- https://microsoft.github.io/autogen/dev//user-guide/agentchat-user-guide/selector-group-chat.html
- AutoGen Swarm source code (GitHub)
- https://learn.microsoft.com/en-us/agent-framework/overview/

---

## 4. Anthropic's Agent Patterns -- Official Guidance

### Key Distinction: Workflows vs. Agents
- **Workflows**: "systems where LLMs and tools are orchestrated through predefined code paths"
- **Agents**: "systems where LLMs dynamically direct their own processes and tool usage, maintaining control over how they accomplish tasks"
- Recommendation: "find the simplest solution possible, and only increasing complexity when needed"

### Orchestrator-Workers Pattern
- Central coordinator breaks down complex tasks dynamically
- **When to use:** "complex tasks where you can't predict the subtasks needed (in coding, for example, the number of files that need to be changed...depend on the task)"
- Key difference from parallelization: "subtasks aren't pre-defined, but determined by the orchestrator based on the specific input"

### Multi-Agent Research System (Anthropic's Own Implementation)
Architecture:
- **Lead Researcher**: analyzes user queries, develops strategy, spawns subagents
- **Subagents**: specialized instances with own isolated context windows

**Task specification for sub-agents (critical quote):**
> "Each subagent needs an objective, an output format, guidance on the tools and sources to use, and clear task boundaries -- without detailed task descriptions, agents duplicate work, leave gaps, or fail to find necessary information."

**Scaling rules embedded in prompts:**
- Simple fact-finding: 1 agent, 3-10 tool calls
- Direct comparisons: 2-4 subagents, 10-15 calls each
- Complex research: 10+ subagents with clearly divided responsibilities

**Parallelization:**
- "(1) the lead agent spins up 3-5 subagents in parallel rather than serially; (2) the subagents use 3+ tools in parallel"
- "cut research time by up to 90% for complex queries"

**Context isolation:**
- Each subagent has "separate context windows"
- Provides "reduction of path dependency and enables thorough, independent investigations"
- Only curated results return to parent (natural compression)

### Tool Design Guidance
- "It is therefore crucial to design toolsets and their documentation clearly and thoughtfully"
- Clear parameter names reducing ambiguity
- Example usage and edge cases in documentation
- "Poka-yoke your tools. Change the arguments so that it is harder to make mistakes."
- SWE-bench example: requiring absolute filepaths instead of relative eliminated model errors

### Long-Running Agent Harnesses
- Two-agent system: Initializer Agent (first session) + Coding Agent (subsequent sessions)
- Feature list in JSON (not Markdown): "the model is less likely to inappropriately change or overwrite JSON files compared to Markdown files"
- Git-based recovery: agents can "use git to revert bad code changes and recover working states"
- Structured progress tracking prevents premature completion

Sources:
- https://www.anthropic.com/research/building-effective-agents
- https://www.anthropic.com/engineering/multi-agent-research-system
- https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents

---

## 5. OpenAI Agents SDK -- Handoff Mechanism

### Core Concept
**Handoffs are represented as tools to the LLM.** If there's a handoff to "Refund Agent", the tool is `transfer_to_refund_agent`.

### RECOMMENDED_PROMPT_PREFIX (Exact Text)
```
# System context
You are part of a multi-agent system called the Agents SDK, designed to make agent
coordination and execution easy. Agents uses two primary abstraction: **Agents** and
**Handoffs**. An agent encompasses instructions and tools and can hand off a
conversation to another agent when appropriate.
Handoffs are achieved by calling a handoff function, generally named
`transfer_to_<agent_name>`. Transfers between agents are handled seamlessly in the background;
do not mention or draw attention to these transfers in your conversation with the user.
```

### What Makes the LLM Correctly Choose to Hand Off
1. **Tool naming convention**: `transfer_to_<agent_name>` is self-documenting
2. **handoff_description**: agents have a `handoff_description` attribute appended to tool description, hinting when to use
3. **System prompt prefix**: tells the LLM it's part of a multi-agent system with handoff capability
4. **Recommended guidance**: "To make sure that LLMs understand handoffs properly, we recommend including information about handoffs in your agents"

### Advanced Features
- `input_type`: schema for handoff tool-call arguments (e.g., reason, priority)
- `input_filter`: control what history the next agent sees
- `nest_handoff_history`: collapses prior transcript into `<CONVERSATION HISTORY>` block
- `is_enabled`: dynamically enable/disable handoffs at runtime
- `on_handoff`: callback when handoff invoked (for logging, data fetching)

### OpenAI Cookbook -- Routines & Handoffs Pattern
Handoff functions return Agent objects instead of strings:
```python
def transfer_to_refunds():
    return refund_agent

# Execution logic:
if type(result) is Agent:  # if agent transfer, update current agent
    current_agent = result
    result = f"Transferred to {current_agent.name}. Adopt persona immediately."
```

**Key insight:** "The model is smart enough to know to call this function when it makes sense to make a handoff!" -- routines contain conditional logic "much like a state machine or branching in code"

Sources:
- https://openai.github.io/openai-agents-python/handoffs/
- OpenAI Agents SDK source (handoff_prompt.py)
- https://developers.openai.com/cookbook/examples/orchestrating_agents

---

## 6. Google ADK -- Multi-Agent Patterns

### Eight Core Patterns
1. **Sequential Pipeline** -- "Agent A finishes a task and hands the baton directly to Agent B"
2. **Coordinator/Dispatcher** -- "A central, intelligent agent acts as a dispatcher. It analyzes the user's intent and routes the request to a specialist agent best suited for the job"
3. **Parallel Fan-Out/Gather** -- "If you have tasks that don't depend on each other, why run them one by one?"
4. **Hierarchical Decomposition** -- parent treats sub-agents as `AgentTool`, delegates parts of tasks
5. **Generator and Critic** -- one generates, one validates with conditional looping
6. **Iterative Refinement** -- cyclical improvement until quality threshold
7. **Human-in-the-Loop** -- pause for authorization on high-stakes decisions
8. **Composite** -- combine patterns for complex systems

### How LLM Decides Which Sub-Agent
- **AutoFlow mechanism**: "takes care of the rest, transferring execution based on the descriptions you provide for the children"
- **Critical quote**: "When using routing, the description field of your sub-agents is effectively your API documentation for the LLM. Be precise."
- State management via `session.state` as shared whiteboard with `output_key`

### Race Condition Warning
For parallel agents: "To prevent race conditions, make sure each agent writes its data to a unique key."

Sources:
- https://developers.googleblog.com/developers-guide-to-multi-agent-patterns-in-adk/
- https://google.github.io/adk-docs/agents/multi-agents/

---

## 7. Common Anti-Patterns -- Why LLMs Fail to Delegate

### A. Tool Overload
- "A model with 20 tools tends to make worse decisions than one with 5"
- Berkeley Function Calling Leaderboard: accuracy drops as tool count increases
- **Fix**: Keep prompts to 5-10 most relevant tools, not 50

### B. "Bag of Agents" Anti-Pattern
- Flat topology with no hierarchy or gatekeeper
- Agents "descend into circular logic or hallucination loops, where they echo and validate each other's mistakes"
- **Fix**: "arranging agents into functional planes transforms a noisy 'bag of agents' into a closed-loop system that suppresses error amplification"

### C. Error Multiplication (17x Error Trap)
- Without deliberate architectural planning, errors multiply rather than cancel
- **Fix**: topology-focused design with closed-loop feedback mechanisms

### D. Poorly Designed Tool Descriptions
- "LLMs struggle with tools that lack clear best practices or documented failure modes"
- "Don't just throw your raw REST API at the LLM. Create 'business task' oriented tools"
- Tool descriptions are "load-bearing infrastructure"
- **Fix**: Include purpose, parameter meanings with constraints, return values, when NOT to use, common errors

### E. Uncontrolled Recursion
- "Don't rely on the model to know not to spawn sub-agents of sub-agents"
- **Fix**: "Enforce it structurally -- pass a baseTools list (without the spawn tool) to sub-agents"
```kotlin
private val baseTools = scheduler.tools + githubDocs.tools  // No spawn tool
private val tools = baseTools + listOf(SpawnAgentTool(...)) // Parent only
```

### F. Runaway Tool Loops
- "Add a max-iterations limit -- without it, a model that gets stuck will keep calling tools until you run out of tokens or budget"

### G. Silent Sub-Agent Failure
- "A sub-agent that returns an empty or nonsensical result is indistinguishable from a valid response at the string boundary"
- **Fix**: "Use a structured return type that separates success from error"

### H. Sub-Agent Cannot Clarify
- "The sub-agent has no way to ask for clarification. If the task is ambiguous, it will either hallucinate a plausible answer or return an empty result"
- **Fix**: Provide detailed task specifications upfront

### I. Nested/Sequential Call Failures
- NESTFUL benchmark: "GPT-4o achieved a full sequence match accuracy of just 28%" for chained API calls
- Error rates compound at each step

### J. The Coding "Rabbit Hole"
- Agents pursuing feature creep with "incredible confidence" rather than MVP-focused delivery

### K. Lack of World Models
- Without state prediction, agents can't foresee consequences (e.g., DELETE before SELECT)

Sources:
- https://dev.to/terzioglub/why-llm-agents-break-when-you-give-them-tools-and-what-to-do-about-it-f5
- https://towardsdatascience.com/why-your-multi-agent-system-is-failing-escaping-the-17x-error-trap-of-the-bag-of-agents/
- https://dev.to/zrcic/sub-agent-architectures-patterns-trade-offs-and-a-kotlin-implementation-13dh
- https://glaforge.dev/talks/2025/12/02/ai-agentic-patterns-and-anti-patterns/

---

## 8. Best Practices for Prompt Engineering That Makes LLMs Correctly Delegate

### A. Tool Description Quality Is Everything
- "Think of the LLM as a developer on your team; the better you document the tool, the easier it will be to use correctly"
- Include: purpose (one sentence), parameter meanings with constraints, return values with examples, when NOT to use, common errors
- "Poka-yoke your tools" -- make it harder to make mistakes (e.g., absolute paths only)

### B. Agent/Tool Descriptions ARE the Routing API
- "The description field of your sub-agents is effectively your API documentation for the LLM. Be precise." (Google ADK)
- OpenAI: `handoff_description` attribute hints when to pick a handoff
- AutoGen: "agents' name and description attributes are used by the model to determine the next speaker"

### C. Explicit Task Decomposition in Prompts
- Anthropic: "Each subagent needs an objective, an output format, guidance on tools and sources, and clear task boundaries"
- Include effort budgets: simple=1 agent/3-10 calls, complex=10+ agents
- Google ADK: "Start with a sequential chain, debug it, and then add complexity"

### D. ReAct-Style Reasoning
- Interleaving thought and action improves accuracy from ~60% to ~85%
- Model should "explain its reasoning between calls"

### E. Tell the LLM It's Part of a Multi-Agent System
- OpenAI: "You are part of a multi-agent system..." (RECOMMENDED_PROMPT_PREFIX)
- "Transfers between agents are handled seamlessly in the background; do not mention or draw attention to these transfers"

### F. Step-by-Step Execution
- "Proceed step-by-step, waiting for the user's message after each tool use before moving forward"
- Planning before execution: "Think HOLISTICALLY and COMPREHENSIVELY BEFORE creating an artifact"

### G. Context Engineering > Prompt Engineering
- "Most agent failures aren't model failures anymore -- they're context failures"
- LangChain's four strategies: write (persist), select (RAG), compress (summarize), isolate (separate contexts)
- Anthropic: JSON format for structured data because "the model is less likely to inappropriately change or overwrite JSON files compared to Markdown"

### H. Structural Enforcement Over Trust
- Don't trust the model to limit recursion -- enforce it in code
- Don't trust the model to stop looping -- add max iterations
- Don't trust the model to validate results -- add explicit validation step

### I. Testing
- "Create at least 1 test scenario per tool function, 5+ scenarios for complex teams, and test each scenario with 10+ variations of user phrasing"
- "'It looks like it works' is not a testing strategy"

---

## 9. Kotlin/JVM-Specific Sub-Agent Implementation (JetBrains Koog)

### Agent-as-Tool Pattern
```kotlin
AIAgentService
    .fromAgent(findAgent as GraphAIAgent<String, String>)
    .createAgentTool<String, String>(
        agentName = "agent_identifier",
        agentDescription = "when_to_invoke",
        inputDescription = "how_to_invoke"
    )
```

### Preventing Recursive Spawning (Structural)
```kotlin
private val baseTools: List<AgentTool> = scheduler.tools + githubDocs.tools // No spawn tool
private val tools: List<AgentTool> = baseTools + listOf(SpawnAgentTool(...))  // Parent only
```

### Sub-Agent Isolation
- Fresh conversation history (no parent history access)
- Restricted tool set only
- System prompt: "Be concise and return only the result"
- Sub-agent cannot ask for clarification

### Explicit Prompt Guidance
- "You also have an intelligent find micro agent at your disposal... Lean on it for any and all search operations. Do not use shell execution for find tasks."
- "Give preference to clustering similar searches in one call rather than doing multiple calls"

### Tool Registry for Sub-Agents (Least Privilege)
```kotlin
ToolRegistry {
    tool(ListDirectoryTool(JVMFileSystemProvider.ReadOnly))
    tool(ReadFileTool(JVMFileSystemProvider.ReadOnly))
    tool(RegexSearchTool(JVMFileSystemProvider.ReadOnly))
}
```

### Performance: ~10% cost reduction ($1.63 to $1.47 per instance), modest performance gain (56% to 58% success rate)

Sources:
- https://blog.jetbrains.com/ai/2026/01/building-ai-agents-in-kotlin-part-4-delegation-and-sub-agents/
- https://dev.to/zrcic/sub-agent-architectures-patterns-trade-offs-and-a-kotlin-implementation-13dh

---

## 10. Cross-Framework Comparison: How Delegation Decision Is Made

| Framework | Delegation Mechanism | How LLM Decides | Key Enabler |
|-----------|---------------------|-----------------|-------------|
| **LangGraph** | Handoff tools (`transfer_to_X`) | Supervisor prompt + tool descriptions | System prompt specifying agent capabilities |
| **CrewAI** | `Delegate work to coworker` tool | Manager LLM + agent role/goal/capability descriptions | Agents converted to tools automatically |
| **AutoGen Swarm** | `HandoffMessage` objects | Agent produces handoff via tool calling | Agent `handoffs` list defines valid targets |
| **AutoGen Selector** | LLM speaker selection | Prompt with `{roles}` and `{history}` | Agent `name` + `description` attributes |
| **OpenAI Agents SDK** | `transfer_to_<name>` tools | System prompt prefix + handoff_description | RECOMMENDED_PROMPT_PREFIX + tool naming |
| **Google ADK** | AutoFlow + AgentTool | Agent `description` field | "Description = API docs for the LLM" |
| **Anthropic** | Orchestrator spawns subagents | Lead agent prompt with scaling rules | Task specification: objective, format, tools, boundaries |
| **JetBrains Koog** | `createAgentTool()` | `agentDescription` = "when_to_invoke" | Explicit prompt guidance + tool isolation |

### Universal Pattern
Every framework converts sub-agents into **tools** that the LLM can call. The LLM decides based on:
1. **Tool/agent descriptions** (most important lever)
2. **System prompt** explaining the delegation model
3. **Conversation context** / current state
4. **Structural constraints** (which tools are available)
