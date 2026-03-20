# Subagent Spawning Patterns in Production Agentic AI Tools

**Date:** 2026-03-20
**Purpose:** Research how production agentic AI tools handle multi-agent orchestration and subagent spawning, to inform Phase 3 architecture decisions.

---

## Summary Matrix

| Tool | Who Decides | Spawning Mechanism | Context Isolation | Tool Scoping | Communication |
|---|---|---|---|---|---|
| Claude Code | LLM (via tool) | Tool call (`Task`/`Agent` tool) | Fresh context per subagent | Yes - YAML frontmatter `tools` field | Final message returned to parent |
| Anthropic Agent SDK | LLM (via tool) | `Task` tool / programmatic agents param | Fresh context per subagent | Yes - per-agent tool lists | Tool response back to orchestrator |
| OpenAI Agents SDK | LLM (via tool) | Handoff = tool call (`transfer_to_X`) | Shared conversation or fresh (configurable) | Yes - per-agent tools list | Handoff returns control; agent-as-tool returns result |
| Google ADK | LLM or System (workflow agents) | `sub_agents` param / `AgentTool` | Shared session state (whiteboard) | Yes - per-agent tool lists | Shared state object or tool response |
| Devin | LLM (orchestrator Devin) | Child session creation API | Fully isolated VM per child | Same tools (full Devin) per child | Structured output schemas returned to parent |
| Cursor | System (event/schedule trigger) | Cloud sandbox spin-up | Fully isolated cloud sandbox | Configured per automation (MCP + creds) | Agent completes task, pushes branch/PR |
| Amazon Q Developer | System (plan-then-execute) | Internal step decomposition | Single agent, no subagent spawning | Single tool set | Sequential step results feed next step |
| Aider | System (hardcoded 2-step) | Second LLM call (editor model) | Shared - editor sees architect output | No separate tools (both use edit format) | Architect output piped as editor input |

---

## 1. Claude Code

### How the Agent/Task Tool Works
Claude Code's subagent system is **LLM-controlled via tool calls**. When Claude encounters a task that matches a subagent's description, it invokes that subagent through the `Task` tool (also called the `Agent` tool). The LLM decides when to delegate based on the subagent's `description` field. Users can also explicitly request a subagent by name.

### Subagent Definition
Subagents are defined as **markdown files with YAML frontmatter**:
- Project-level: `.claude/agents/*.md`
- User-level: `~/.claude/agents/*.md`

The frontmatter includes:
- `description` - When to invoke (LLM matches against this)
- `tools` - Allowed tools list (e.g., `Read, Grep, Glob, Bash(git:*)`)
- `permissionMode` - Security boundary

### Context Isolation
Each subagent runs in its **own fresh context window** with its own system prompt. Intermediate tool calls and results stay inside the subagent; only the final message returns to the parent. This is a major advantage: a research subagent can explore dozens of files without that content accumulating in the main conversation.

### Tool Scoping
Highly configurable:
- Omit `tools` field to inherit all tools
- Specify exact tools: `tools: Read, Write, Edit, Grep, Glob`
- Bash with patterns: `tools: Bash(git:*), Bash(npm:*), Read`
- Read-only subagents possible by excluding write tools

### Constraints
- Subagents **cannot spawn other subagents** (no nesting)
- Up to 10 simultaneous subagents (2026)
- Multiple subagents can run concurrently for parallelism

### Key Insight
The spawning decision is made by the LLM, but the tool schema constrains WHAT a subagent can do. A Planner subagent literally cannot write files because write tools are absent from its schema -- not because a runtime check blocks it.

**Sources:**
- [Create custom subagents - Claude Code Docs](https://code.claude.com/docs/en/sub-agents)
- [Subagents in the SDK - Claude API Docs](https://platform.claude.com/docs/en/agent-sdk/subagents)
- [Task Tool vs. Subagents: How Agents Work in Claude Code](https://www.ibuildwith.ai/blog/task-tool-vs-subagents-how-agents-work-in-claude-code/)
- [Claude Code Sub-Agents: Parallel vs Sequential Patterns](https://claudefa.st/blog/guide/agents/sub-agent-best-practices)

---

## 2. Anthropic Agent SDK (Claude Agent SDK)

### Multi-Agent Architecture
The Agent SDK (renamed from Claude Code SDK) uses an **orchestrator-worker pattern**. The orchestrator is responsible for global planning, delegation, and state. Its tool permissions are narrow -- mostly "read and route." Subagents are defined with clear inputs/outputs and a single goal.

### Handoff/Delegation Mechanism
Delegation happens through the **`Task` tool** -- the same mechanism as Claude Code. The `agents` parameter in programmatic usage defines specialized subagents. The LLM decides when to delegate based on each agent's description.

### Anthropic's Own Multi-Agent Research System
Anthropic's internal research system (documented in their engineering blog) uses this pattern:
- A **lead agent** analyzes queries, develops strategy, spawns subagents
- **Subagents** operate in parallel, act as intelligent filters
- Each subagent iteratively uses search tools, returns results to lead agent
- Achieves **90.2% performance improvement** over single-agent systems
- Early versions had problems: spawning 50 subagents for simple queries, endless web scouring
- **Token economics**: Agents use 4x more tokens than chat; multi-agent uses 15x more

### Context & Tool Scoping
Each subagent gets its own context window. Tool lists are configurable per agent. The SDK supports MCP (Model Context Protocol) and A2A (Agent-to-Agent) protocol for inter-agent communication.

**Sources:**
- [How we built our multi-agent research system - Anthropic Engineering](https://www.anthropic.com/engineering/multi-agent-research-system)
- [Building agents with the Claude Agent SDK](https://www.anthropic.com/engineering/building-agents-with-the-claude-agent-sdk)
- [Subagents in the SDK - Claude Docs](https://docs.anthropic.com/en/docs/claude-code/sdk/subagents)

---

## 3. OpenAI Agents SDK

### Two Delegation Patterns

**Pattern 1: Handoffs (transfer control)**
Handoffs are represented as **tools to the LLM**. If there's a handoff to "Refund Agent", the tool is called `transfer_to_refund_agent`. The LLM decides when to hand off. When a handoff occurs, control transfers completely -- the new agent takes over the conversation.

Configuration:
- `handoffs` param on each agent (list of Agent or Handoff objects)
- `tool_name_override` to customize tool name
- `on_handoff` callback for side effects (e.g., data fetching)
- `input_filter` to control what context transfers

**Pattern 2: Agent-as-Tool (keep control)**
One agent (central planner) calls other agents **as if they were tools**. Sub-agents don't take over the conversation. The main agent invokes them for specific subtasks and incorporates their results. Single thread of control stays with the orchestrator.

### Context Isolation
- **Handoffs**: Shared conversation context (filtered by `input_filter`)
- **Agent-as-Tool**: Sub-agent gets scoped input, returns result to parent

### Tool Scoping
Each agent has its own `tools` list, `handoffs` list, and `guardrails`. Guardrails provide input/output validation.

### Key Insight
OpenAI provides TWO patterns because they serve different needs. Handoffs are for routing (user needs different expertise). Agent-as-Tool is for decomposition (break task into subtasks). The LLM decides in both cases.

**Sources:**
- [Handoffs - OpenAI Agents SDK](https://openai.github.io/openai-agents-python/handoffs/)
- [Agent orchestration - OpenAI Agents SDK](https://openai.github.io/openai-agents-python/multi_agent/)
- [GitHub - openai/openai-agents-python](https://github.com/openai/openai-agents-python)

---

## 4. Google ADK (Agent Development Kit) / Jules

### ADK Multi-Agent Architecture
ADK uses a **tree structure** via `sub_agents` parameter. Each agent can have only one parent (enforced with ValueError). Two delegation modes:

**LLM-Driven Delegation (default)**
The LLM considers the query, current agent's description, and descriptions of related agents (parent/sub) to decide routing. No explicit tool call needed -- the framework handles it.

**Explicit Invocation via AgentTool**
Parent agents use `AgentTool` to assign tasks to subagents. Results return via tool responses or shared state.

### Workflow Agents (System-Controlled)
ADK provides **Sequential**, **Parallel**, and **Loop** workflow agents. These execute sub-agents in an automated flow without LLM decision-making. This is a system-controlled pattern for deterministic pipelines.

### Context / Communication
**Shared Session State** acts as a whiteboard. Any agent can write results to a common state object; other agents in the hierarchy can read it. This is NOT fresh-context-per-agent -- it's a shared mutable state.

### Jules (Google's Coding Agent)
Jules is **asynchronous and single-agent**. It clones repos into Google Cloud VMs, generates step-by-step plans, and executes them sequentially. No evidence of multi-agent spawning within Jules itself. Jules Tools CLI allows external orchestration (spin up tasks, inspect progress).

**Sources:**
- [Multi-agent systems - Agent Development Kit (ADK)](https://google.github.io/adk-docs/agents/multi-agents/)
- [Developer's guide to multi-agent patterns in ADK](https://developers.googleblog.com/developers-guide-to-multi-agent-patterns-in-adk/)
- [Jules - An Autonomous Coding Agent](https://jules.google)

---

## 5. Devin

### Orchestration Model
Devin uses **LLM-controlled orchestration**. A primary Devin session acts as coordinator, spawning "managed Devins" (child sessions) that work in parallel.

### Spawning Mechanism
Child sessions are created via **session management API**. The orchestrator Devin can:
- Create child sessions with structured output schemas and playbooks
- Search/filter past sessions by tags, playbook, origin, or time range
- Analyze past sessions with full search across shell, file, browser, git, and MCP activity
- Manage knowledge (create/update/delete notes) and playbooks

### Context Isolation
**Fully isolated**. Each managed Devin gets its own **virtual machine** with its own IDE, shell, browser, and git environment. This is the strongest isolation model of any tool researched.

### Tool Scoping
Each child Devin is a **full Devin** -- same tool set as the parent. No tool restriction mechanism documented.

### Communication
- Structured output schemas define what child sessions return
- Parent/child sessions grouped in sidebar UI
- Parent monitors progress, resolves conflicts, compiles results

### Key Insight
Devin's approach is "clone the entire agent" rather than "create a lightweight specialist." This is resource-intensive but eliminates the problem of a subagent needing a tool it doesn't have.

**Sources:**
- [Recent Updates - Devin Docs](https://docs.devin.ai/release-notes/overview)
- [Cognition | Devin 2.0](https://cognition.ai/blog/devin-2)
- [Agent-Native Development: A Deep Dive into Devin 2.0's Technical Design](https://medium.com/@takafumi.endo/agent-native-development-a-deep-dive-into-devin-2-0s-technical-design-3451587d23c0)

---

## 6. Cursor

### Background Agents (User-Triggered)
Background agents are triggered by the user pressing **Ctrl+E** or from the background agent panel (Ctrl+Shift+B). They run in an **isolated Ubuntu-based VM**, clone the repo from GitHub, work on a separate branch, and push for handoff.

### Cursor Automations (System-Triggered, March 2026)
A fundamentally different model: **always-on agents triggered by external events**:
- GitHub PR opened/updated
- Slack message
- Linear issue created
- PagerDuty incident
- Custom webhooks
- Scheduled (cron-like)

### Spawning Mechanism
Each automation spins up an **isolated cloud sandbox** with:
- Codebase access (cloned repo)
- Configured MCP servers
- Provided credentials
- Memory tool (learns from past runs)

### Context Isolation
Fully isolated. Each automation gets its own sandbox. No shared state between automations.

### Tool Scoping
Configured per automation via MCP servers, model selection, and credentials. Each automation can have different tools and access levels.

### Key Insight
Cursor is the only tool that separates **user-triggered** (background agents) from **event-triggered** (automations). The LLM doesn't decide to spawn agents -- the system does, based on external events. This is a fundamentally different paradigm from Claude Code or OpenAI.

**Sources:**
- [Cursor Background Agents](https://docs.cursor.com/en/background-agent)
- [Cursor Automations Blog](https://cursor.com/blog/automations)
- [Cursor Automations: Always-On AI Agents](https://www.adwaitx.com/cursor-automations-ai-coding-agents/)

---

## 7. Amazon Q Developer Agent

### Architecture
Amazon Q Developer uses a **single-agent, plan-then-execute** model. There is no evidence of multi-agent spawning or subagent delegation.

### Task Decomposition
The agent:
1. Analyzes existing codebase
2. Maps out step-by-step implementation plan spanning multiple files
3. Presents plan for user approval
4. Executes all required code changes and tests

### Execution Model
The agent autonomously reads/writes files, generates code diffs, runs shell commands, incorporates feedback, and provides real-time updates. It uses MCP for external tool integration.

### Key Insight
Amazon Q takes the "single powerful agent" approach rather than multi-agent. The plan is generated by the LLM, approved by the human, then executed sequentially by the same agent. This is simpler but doesn't benefit from parallel execution.

**Sources:**
- [Amazon Q Developer - Build](https://aws.amazon.com/q/developer/build/)
- [Amazon Q Developer Features](https://aws.amazon.com/q/developer/features/)
- [Streamline Development with New Amazon Q Developer Agents](https://aws.amazon.com/blogs/devops/streamline-development-with-new-amazon-q-developer-agents/)

---

## 8. Aider

### Architect/Editor Two-LLM Model
Aider uses a **system-controlled, hardcoded two-step delegation**:

1. **Architect LLM** (e.g., o1, Claude) receives the coding problem and proposes a solution in natural language
2. **Editor LLM** (e.g., GPT-4o, Sonnet, DeepSeek) receives the architect's output and translates it into specific file edit instructions

### Who Decides
The **system decides** -- not the LLM. The two-step flow is hardcoded. The architect never "chooses" to delegate; it always happens.

### Context
**Shared** -- the editor sees the architect's full output plus the relevant file contents. No isolation; the editor needs the architect's reasoning to produce correct edits.

### Tool Scoping
No separate tools. Both models operate within aider's edit format system. The architect writes prose; the editor writes structured edits (diff format, whole file, etc.).

### Communication
Direct pipeline: architect output becomes editor input. No intermediate state or message bus.

### Key Insight
Aider's model is the simplest of all -- it's not really "multi-agent" but "multi-model." The separation exploits the fact that different models have different strengths: o1 is great at reasoning but poor at precise edits; GPT-4o is great at structured output. This achieved SOTA (85%) on aider's code editing benchmark.

**Sources:**
- [Separating code reasoning and editing - aider blog](https://aider.chat/2024/09/26/architect.html)
- [Chat modes - aider docs](https://aider.chat/docs/usage/modes.html)
- [Edit formats - aider docs](https://aider.chat/docs/more/edit-formats.html)

---

## Cross-Cutting Patterns Identified

### Pattern 1: LLM-as-Router (Claude Code, OpenAI, Google ADK)
The LLM reads subagent descriptions and decides which to invoke. This is the dominant pattern. The routing decision is a tool call, making it auditable and debuggable.

**Pros:** Flexible, handles novel situations, no hardcoded routing rules
**Cons:** LLM can make poor routing decisions (Anthropic's 50-subagent problem), costs more tokens

### Pattern 2: System-as-Router (Cursor Automations, Amazon Q, Aider)
The system decides when agents run, based on events, schedules, or hardcoded pipelines. The LLM has no say in the delegation decision.

**Pros:** Deterministic, predictable costs, no routing errors
**Cons:** Cannot adapt to novel situations, requires pre-configuration

### Pattern 3: Clone-the-Agent (Devin)
Instead of creating lightweight specialists, clone the full agent with all tools into an isolated environment. The orchestrator manages N full-capability agents.

**Pros:** No tool scoping mistakes, subagents can handle any unexpected need
**Cons:** Resource-intensive, harder to constrain behavior

### Pattern 4: Multi-Model Pipeline (Aider)
Different LLMs for different phases of the same task. Not multi-agent but multi-model. System-controlled sequencing.

**Pros:** Exploits model strengths, simple architecture
**Cons:** No parallelism, fixed pipeline

---

## Implications for Our Phase 3 Architecture

### What to adopt:
1. **LLM-controlled spawning via tool calls** (Pattern 1) -- This is the industry standard. The LLM calls a `Task` tool to spawn a subagent. This is what Claude Code, OpenAI, and Google ADK all do.

2. **Fresh context per subagent with tool scoping** -- Claude Code's model of isolated context windows with per-subagent tool lists is the most mature. Prevents context pollution and enforces least-privilege.

3. **Structured output schemas for subagent results** -- Devin's approach of defining what child sessions return ensures the orchestrator gets predictable data back.

4. **No subagent nesting** -- Claude Code explicitly prevents this. Keep it simple: one orchestrator, N workers, no recursion.

### What to avoid:
1. **Shared mutable state** (Google ADK's whiteboard) -- Introduces coupling and race conditions. Prefer message-passing.

2. **Unrestricted subagent spawning** -- Anthropic learned this the hard way (50 subagents for simple queries). Need explicit limits.

3. **Full-clone model** (Devin) -- Too resource-intensive for an IDE plugin. Our subagents should be lightweight with scoped tools.

### Architecture recommendation:
- Orchestrator agent with broad read access + `spawnWorker` tool
- Worker agents with task-specific tool sets (e.g., "code-analysis" worker gets PSI tools, "sonar-fix" worker gets Cody + file edit tools)
- Fresh Cody context per worker (new chat session)
- Results returned as structured JSON, summarized by orchestrator
- Max 3-5 concurrent workers (IDE resource constraint)
- System-level guardrails: max tokens per worker, timeout per worker, no worker-spawns-worker
