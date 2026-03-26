# Enterprise-Grade Agentic AI Tools & Platforms Research

**Date:** 2026-03-26
**Purpose:** Comprehensive research on production agentic AI systems for long-running tasks with minimal supervision and high accuracy.

---

## Table of Contents

1. [Anthropic Claude Agent SDK](#1-anthropic-claude-agent-sdk)
2. [OpenAI Agents SDK](#2-openai-agents-sdk)
3. [LangGraph (LangChain)](#3-langgraph-langchain)
4. [CrewAI](#4-crewai)
5. [Microsoft AutoGen / AG2](#5-microsoft-autogen--ag2)
6. [Amazon Bedrock Agents](#6-amazon-bedrock-agents)
7. [Google Vertex AI Agent Builder / ADK](#7-google-vertex-ai-agent-builder--adk)
8. [Temporal AI](#8-temporal-ai)
9. [Letta (MemGPT)](#9-letta-memgpt)
10. [Mastra](#10-mastra)
11. [Comparative Analysis](#11-comparative-analysis)
12. [Key Takeaways for Plugin Integration](#12-key-takeaways-for-plugin-integration)

---

## 1. Anthropic Claude Agent SDK

**Source:** [Official Docs](https://platform.claude.com/docs/en/agent-sdk/overview) | [GitHub (Python)](https://github.com/anthropics/claude-agent-sdk-python) | [Agent Loop Docs](https://platform.claude.com/docs/en/agent-sdk/agent-loop)

> Formerly the Claude Code SDK. Provides the same tools, agent loop, and context management that power Claude Code, programmable in Python and TypeScript.

### Architecture

The Claude Agent SDK implements a **ReAct-style agentic loop** that runs inside a bundled Claude Code CLI binary, communicating with your application over stdin/stdout using NDJSON. The loop does not run in your application process.

**Loop mechanics:**
1. **Receive prompt** - Claude receives prompt + system prompt + tool definitions + conversation history. SDK yields a `SystemMessage` with subtype `"init"`.
2. **Evaluate and respond** - Claude evaluates current state, responds with text and/or tool call requests. SDK yields `AssistantMessage`.
3. **Execute tools** - SDK runs each requested tool and collects results. Hooks can intercept/modify/block tool calls.
4. **Repeat** - Steps 2-3 repeat. Each full cycle is one "turn."
5. **Return result** - Final `AssistantMessage` (no tool calls) followed by `ResultMessage` with cost, usage, session ID.

**Message types:** `SystemMessage`, `AssistantMessage`, `UserMessage`, `StreamEvent`, `ResultMessage`.

### Long-Running Task Support

- **Session persistence:** Capture `session_id` from `ResultMessage` to resume later. Full context from previous turns is restored.
- **Session forking:** Branch into different approaches without modifying the original session.
- **Automatic compaction:** When context window approaches its limit, the SDK automatically summarizes older history while keeping recent exchanges and key decisions intact. Emits `SystemMessage` with subtype `"compact_boundary"`.
- **Compaction customization:** CLAUDE.md sections tell the compactor what to preserve. `PreCompact` hook archives full transcripts before summarizing. Manual compaction via `/compact` command.
- **Budget/turn limits:** `max_turns` (tool-use round trips), `max_budget_usd` (cost threshold). `ResultMessage` includes subtypes: `success`, `error_max_turns`, `error_max_budget_usd`, `error_during_execution`.

### Multi-Agent

- **Subagents:** Spawn specialized agents via the `Agent` tool. Each subagent starts with a fresh conversation (no parent's turns), only its final response returns to parent as a tool result.
- **Agent definitions:** Configurable with `description`, `prompt`, and `tools` per subagent.
- **Context efficiency:** Subagents keep the main agent's context lean since only the summary result returns, not the full transcript.

### Human-in-the-Loop

- **Permission modes:** `"default"` (tools trigger approval callback), `"acceptEdits"` (auto-approve file edits), `"plan"` (no execution, plan only), `"bypassPermissions"` (isolated environments only).
- **Tool permissions:** `allowed_tools` (auto-approve), `disallowed_tools` (block), scoped rules like `"Bash(npm:*)"`.
- **AskUserQuestion tool:** Built-in tool for asking clarifying questions with multiple choice options.

### Memory

- **Session-based context:** Full conversation history accumulates across turns within a session.
- **CLAUDE.md files:** Project instructions loaded via `settingSources: ["project"]`, re-injected on every request (survives compaction).
- **Skills:** Specialized capabilities defined in `.claude/skills/SKILL.md` - short summaries loaded at session start, full content only when invoked.
- **Automatic compaction:** Summarizes older history when approaching context limit.

### Tool Use

Built-in tools that require no implementation:

| Category | Tools |
|----------|-------|
| File operations | `Read`, `Edit`, `Write` |
| Search | `Glob`, `Grep` |
| Execution | `Bash` |
| Web | `WebSearch`, `WebFetch` |
| Discovery | `ToolSearch` (dynamic tool loading) |
| Orchestration | `Agent`, `Skill`, `AskUserQuestion`, `TodoWrite` |

- **MCP integration:** Connect external systems via Model Context Protocol (databases, browsers, APIs).
- **Custom tools:** Define custom tool handlers.
- **Parallel execution:** Read-only tools run concurrently; state-modifying tools run sequentially.

### Evaluation & Observability

- **Hooks system:** Callbacks at key points (`PreToolUse`, `PostToolUse`, `Stop`, `SessionStart`, `SessionEnd`, `UserPromptSubmit`, `SubagentStart`, `SubagentStop`, `PreCompact`). Run in application process, don't consume context.
- **ResultMessage:** Includes `total_cost_usd`, `usage`, `num_turns`, `session_id`, `stop_reason`.
- **Effort levels:** `"low"`, `"medium"`, `"high"`, `"max"` - trade latency/cost for reasoning depth.

### Guardrails

- **Tool permission system:** Allow/deny/scope tool access at the configuration level.
- **Hooks as guardrails:** `PreToolUse` hooks can validate inputs and block dangerous commands. `PostToolUse` hooks audit outputs.
- **Permission modes:** Structural safety through `"plan"` mode (no execution) and `"default"` mode (approval required).

### Production Deployment

- **Authentication:** API key, Amazon Bedrock, Google Vertex AI, Microsoft Azure AI Foundry.
- **Streaming:** `StreamEvent` messages with `include_partial_messages` for real-time text and tool call updates.
- **Cost controls:** Budget limits, turn limits, effort levels.
- **Error handling:** `ResultMessage` subtypes distinguish success from various failure modes.

---

## 2. OpenAI Agents SDK

**Source:** [Official Docs](https://openai.github.io/openai-agents-python/) | [GitHub](https://github.com/openai/openai-agents-python)

> Lightweight, powerful framework for multi-agent workflows. Successor to the experimental Swarm SDK. 19k+ GitHub stars.

### Architecture

The SDK provides **five core primitives:** Agents, Handoffs, Guardrails, Sessions, and Tracing. The architecture follows a **ReAct-style agent loop** with automatic tool invocation and result processing.

- **Agents:** LLMs configured with instructions, tools, guardrails, and handoffs.
- **Provider-agnostic:** Supports OpenAI Responses and Chat Completions APIs, plus 100+ other LLMs.
- **Automatic schema generation:** Any Python function becomes a tool via Pydantic validation.

### Long-Running Task Support

- **Sessions:** Persistent memory layer maintaining working context across agent interactions. Multiple backend implementations: SQLAlchemy, SQLite, Redis, encrypted variants.
- **Automatic conversation history:** Sessions manage conversation history across agent runs without manual state tracking.
- No explicit checkpointing/durability system documented (relies on session backends for persistence).

### Multi-Agent

- **Handoffs:** Core delegation mechanism where agents pass tasks to specialized peers. Handoffs are represented as tools to the LLM (e.g., `transfer_to_refund_agent`).
- **Agents as tools:** Agents can be composed as reusable building blocks within larger workflows.
- **Manager-style orchestration:** Different coordination approaches supported depending on use case.

### Human-in-the-Loop

- **Built-in mechanisms** for human involvement embedded throughout execution lifecycle.
- **Blocking guardrails:** Can run before agent starts (`run_in_parallel=False`), preventing token consumption and tool execution if validation fails.

### Memory

- **Session-based:** SQLAlchemy, SQLite, Redis, encrypted variants.
- **Automatic conversation history management** across agent runs.
- No documented long-term/episodic memory beyond session storage.

### Tool Use

- **Function tools:** Automatic schema generation from Python functions with Pydantic validation.
- **MCP tools:** Model Context Protocol servers as callable tools.
- **Hosted tools:** Built-in tools provided by OpenAI (web search, file operations).
- **Agents as tools:** Other agents callable as tools for composition.

### Evaluation & Observability

- **Built-in tracing:** Comprehensive record of events during agent runs - LLM generations, tool calls, handoffs, guardrails, custom events.
- **Traces dashboard:** Debug, visualize, and monitor workflows during development and production.
- **Integration:** Connects with OpenAI's evaluation and fine-tuning infrastructure.

### Guardrails

- **Input guardrails:** Validate agent inputs before execution.
- **Output guardrails:** Validate agent outputs after execution.
- **Tool guardrails:** Run on every custom function-tool invocation.
- **Parallel execution:** Run safety checks in parallel with agent execution, fail fast when checks don't pass.
- **Blocking mode:** `run_in_parallel=False` runs guardrail before agent starts, preventing token consumption if tripped.

### Production Deployment

- **Realtime agents:** Voice interaction support via `gpt-realtime-1.5`.
- **Provider agnostic:** Works beyond OpenAI ecosystem.
- **Lightweight:** Minimal abstraction, clean design.

---

## 3. LangGraph (LangChain)

**Source:** [Official Docs](https://docs.langchain.com/oss/python/langgraph/overview) | [GitHub](https://github.com/langchain-ai/langgraph) | [LangGraph Platform](https://www.langchain.com/langgraph)

> Low-level orchestration framework and runtime for building, managing, and deploying long-running, stateful agents. Inspired by Pregel and Apache Beam.

### Architecture

LangGraph implements a **graph-based state machine** where:

- **Nodes** are functions (Python/JS) that execute logic.
- **Edges** represent state transitions between nodes.
- **State** is explicitly managed and passed through the graph.
- Deterministic execution engine for AI reasoning workflows.

Replaces the original LangChain chains-and-agents model with directed graphs for explicit control. Interfaces draw from NetworkX.

### Long-Running Task Support

- **Checkpointing:** Built-in persistence layer saves graph state as checkpoints at every step of execution, organized into threads.
- **Database backends:** SQLite (local/experimentation), PostgreSQL (production/distributed), custom implementations.
- **Interrupt and resume:** When a workflow pauses at an interrupt, state is saved. Resuming via `Command` sends human's decision back to the `interrupt()` function. Graph picks up exactly where it left off.
- **Fault tolerance:** Durable execution - agents persist through failures and resume from where they left off.
- **Time travel debugging:** Navigate to any previous checkpoint to inspect or replay state.

### Multi-Agent

- **Explicit graph routing:** Define exactly how agents coordinate via edges and conditional logic.
- **Agent hierarchies:** Compose graphs within graphs for complex multi-agent systems.
- **Human-in-the-loop handoffs:** Structured approval points in the graph.

### Human-in-the-Loop

- **Interrupt mechanism:** `interrupt()` function pauses execution at any node.
- **State inspection:** Inspect and modify agent state at any point.
- **Approval gates:** Humans approve or correct before the next step.
- **Thread-based resume:** `thread_id` loads saved state for resuming after interrupt.

### Memory

- **Short-term working memory:** MessagesState for ongoing reasoning within a session.
- **Long-term memory:** Persistent memory across sessions via checkpointer backends.
- **State management:** Explicit state objects passed through graph nodes.

### Tool Use

- Tools defined as functions called within graph nodes.
- Integration with LangChain tool ecosystem.
- Can operate independently or integrate with LangChain components.

### Evaluation & Observability

- **LangSmith integration:** Visualization tools that trace execution paths, capture state transitions, and provide detailed runtime metrics.
- **Time travel debugging:** Navigate execution history.
- **State transition logging:** Full audit trail of graph execution.

### Guardrails

- Input/output validation at graph node boundaries.
- Conditional edges for safety checks.
- Human approval gates as structural guardrails.

### Production Deployment

- **LangGraph Platform:** Scalable infrastructure for stateful, long-running workflows.
- **LangGraph Cloud:** Managed deployment service.
- **Streaming:** Native token-by-token streaming via Server-Sent Events (SSE).
- **Enterprise users:** Klarna, Uber, J.P. Morgan.

---

## 4. CrewAI

**Source:** [Official Docs](https://docs.crewai.com/) | [GitHub](https://github.com/crewAIInc/crewAI) | [crewai.com](https://crewai.com/)

> Open-source multi-agent orchestration framework. Organizes AI agents into collaborative teams with defined roles, hierarchies, and workflows. 44.6k+ GitHub stars.

### Architecture

CrewAI uses an **agent-team model** (metaphor: a crew of specialists):

- **Agents:** Defined by `role`, `goal`, and `backstory`. Autonomous units with specific expertise.
- **Tasks:** Units of work assigned to agents with expected outputs.
- **Crews:** Collections of agents working together on tasks.
- **Processes:** Sequential, hierarchical, or hybrid execution patterns.

### Long-Running Task Support

- **Execution timeouts:** `max_execution_time` per agent.
- **Retry mechanisms:** `max_retry_limit` (default: 2).
- **Context window management:** `respect_context_window` auto-summarizes when conversations exceed token limits.
- No explicit checkpointing/durability system documented.

### Multi-Agent

- **Role-based agents:** Each agent has distinct role, goal, and backstory.
- **Task delegation:** `allow_delegation=True` enables agents to delegate tasks to others in the crew.
- **Sequential process:** Tasks executed one after another in order.
- **Hierarchical process:** Manager agent coordinates planning and execution through delegation and validation.
- **CrewAI Studio:** Visual workflow design for building crews.

### Human-in-the-Loop

- **Human input on tasks:** Tasks can be configured to require human feedback.
- Not as deeply integrated as LangGraph's interrupt/resume pattern.

### Memory

- **Short-term memory:** Conversation context within a task.
- **Long-term memory:** Persists learnings across executions.
- **External vector stores:** Pinecone, Weaviate for enterprise-scale horizontal memory.
- **Knowledge sources:** Domain-specific knowledge bases integrated into agent configurations.

### Tool Use

- **CrewAI Toolkit:** Built-in tools (SerperDevTool, WikipediaTools, etc.).
- **LangChain integration:** Compatible with LangChain tools.
- **CrewAI Studio:** Pre-built integrations (Gmail, Teams, Notion, HubSpot, Salesforce, Slack).
- **Code execution:** `allow_code_execution` with "safe" (Docker) or "unsafe" (direct) modes.
- **Caching:** Tool results cached by default for optimization.

### Evaluation & Observability

- **Step callbacks:** Monitor agent interactions at each step.
- **Reasoning mode:** `reasoning=True` enables agents to reflect and create plans before executing.
- No dedicated observability platform (unlike LangSmith for LangGraph).

### Guardrails

- **Rate limiting:** `max_rpm` per agent.
- **Execution timeouts and retries.**
- **Context window management.**
- Black-box orchestration provides less fine-grained control than graph-based approaches.

### Production Deployment

- **CrewAI Enterprise:** Managed platform.
- **CrewAI Studio:** Visual editor for non-developers.
- **Rapid prototyping:** Fastest time-to-first-agent among frameworks.
- **Limitation:** Less control over internal orchestration compared to LangGraph.

---

## 5. Microsoft AutoGen / AG2

**Source:** [AutoGen Docs](https://microsoft.github.io/autogen/stable/) | [GitHub](https://github.com/microsoft/autogen) | [AG2 Docs](https://docs.ag2.ai/)

> Originally Microsoft's project, now independent open-source "AgentOS." The v0.4 rewrite (AG2) features event-driven core, async-first execution, and pluggable orchestration.

### Architecture

AG2 centers on **conversable agents** that interact through structured dialogue:

- **Group Chat:** Multiple agents share a common thread of messages via pub/sub topics.
- **Group Chat Manager:** Orchestrates participation, maintains speaker order (sequential turn-taking - one agent at a time).
- **Event-driven core:** `SingleThreadedAgentRuntime` with `TypeSubscription` for topic management.
- **Message types:** `GroupChatMessage`, `RequestToSpeak`, approval messages.

**Speaker selection:**
- LLM-based dynamic selection analyzing conversation history and available participants.
- Rule-based fallbacks for simple cases (e.g., editor always follows writer).
- Previous speaker tracking to prevent consecutive same-agent turns.

### Long-Running Task Support

- No explicit checkpointing or durability system documented.
- State maintained in memory during conversation.
- **Termination conditions:** Manager monitors messages for approval keywords.
- **Limitation:** Not production-ready for long-running tasks.

### Multi-Agent

- **Group Chat:** Primary coordination pattern - multiple agents in shared conversation with manager-controlled turn-taking.
- **Nested group chats:** Hierarchical decomposition with each participant being a recursive group chat.
- **Conversational swarms:** Agents collaborate through structured dialogue.
- **Specialized roles:** WriterAgent, EditorAgent, IllustratorAgent, UserAgent patterns.

### Human-in-the-Loop

- **UserAgent:** Human input and final approval interface.
- **Termination conditions:** Editor approval stops the conversation.
- **UserProxyAgent:** (v0.2) Allows human participation in multi-agent conversations.

### Memory

- **Chat history:** Complete message record per agent.
- **Role definitions:** System messages describing persona and responsibilities.
- **Topic subscriptions:** Dual subscriptions for broadcasts and direct requests.
- No long-term memory system.

### Tool Use

- **Code execution:** Primary strength - agents generate and execute code.
- **Tool integration:** DALL-E, web browsers, file systems.
- **Function calling:** Standard LLM function calling patterns.

### Evaluation & Observability

- No dedicated observability platform.
- Basic logging of conversation flows.
- **Limitation:** Significant gap compared to LangSmith or OpenAI tracing.

### Guardrails

- No built-in guardrail system.
- No code sandboxing for execution.
- **Limitation:** Major gap for production deployments.

### Production Deployment

- **Production readiness:** LOW. Lacks observability, security features, and code sandboxing.
- **Best for:** Research and experimentation with multi-agent conversation patterns.
- **Microsoft Agent Framework:** Microsoft has released a separate, production-focused Agent Framework (different from AutoGen).

---

## 6. Amazon Bedrock Agents

**Source:** [AWS Docs](https://docs.aws.amazon.com/bedrock/latest/userguide/agents-how.html) | [Prescriptive Guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/agentic-ai-frameworks/bedrock-agents.html)

> Fully managed service for building AI agents on AWS. No orchestration code needed - select a foundation model, write natural language instructions, and configure action groups.

### Architecture

Bedrock Agents follows a **managed orchestration loop** with three phases:

1. **Pre-processing:** Contextualizes, categorizes, validates user input (optional).
2. **Orchestration (iterative):**
   - Agent interprets input with FM, generates rationale.
   - Predicts which action group to invoke or knowledge base to query.
   - Sends parameters to Lambda function or returns control to application.
   - Generates observation from results, augments base prompt.
   - Repeats until final response ready.
3. **Post-processing:** Formats final response (disabled by default).

### Long-Running Task Support

- **Session management:** Conversation history preserved across multiple `InvokeAgent` requests.
- **Return of control:** Agent returns parameters to application, application handles action and returns results to continue orchestration. Enables external long-running operations.
- No explicit checkpointing beyond session-level conversation history.

### Multi-Agent

- **Built-in multi-agent supervision.**
- **Lambda-based workflows** for complex coordination.
- Agents can invoke other agents' action groups.

### Human-in-the-Loop

- **Return of control:** Agent pauses, returns to application with parameters. Application performs action (potentially involving human), returns result.
- **Pre-processing validation:** Input validation before orchestration begins.

### Memory

- **Conversation history:** Preserved in sessions, continually augments orchestration prompt.
- **Knowledge bases:** Domain-specific information queried during orchestration.
- No self-editing or episodic memory.

### Tool Use

- **Action groups:** Define APIs and operations the agent can perform.
  - **OpenAPI schema:** For API operations.
  - **Function detail schema:** For parameter elicitation.
- **Lambda functions:** Handle action group invocations.
- **Knowledge base queries:** RAG-style information retrieval.

### Evaluation & Observability

- **Trace feature:** Step-by-step reasoning tracking including rationale, actions, full prompts, FM outputs, API responses, knowledge base queries, observations.
- **CloudWatch integration** (standard AWS monitoring).

### Guardrails

- **Bedrock Guardrails:** Content filters, denied topics, sensitive information filters, word filters, image content filters.
- **Pre-processing validation:** Input checked before orchestration.
- **IAM, VPC, HIPAA compliance:** Strongest enterprise security among frameworks.

### Production Deployment

- **Fully managed:** No infrastructure to manage.
- **AWS ecosystem integration:** IAM, VPC, CloudWatch, Lambda.
- **Turnkey deployment.**
- **Limitation:** Limited flexibility compared to code-first frameworks. Steep AWS learning curve.

---

## 7. Google Vertex AI Agent Builder / ADK

**Source:** [ADK Docs](https://google.github.io/adk-docs/) | [Cloud Docs](https://docs.cloud.google.com/agent-builder/agent-development-kit/overview) | [Google Blog](https://developers.googleblog.com/en/agent-development-kit-easy-to-build-multi-agent-applications/)

> Open-source, model-agnostic, deployment-agnostic framework. ADK 2.0 Alpha adds graph-based workflow orchestration. Optimized for Gemini and Google ecosystem.

### Architecture

ADK separates agent types into distinct categories:

- **Workflow Agents:** Deterministic orchestration
  - `SequentialAgent` - step-by-step ordered execution
  - `LoopAgent` - iterative refinement cycles
  - `ParallelAgent` - concurrent independent tasks
- **LLM Agents:** Dynamic, adaptive behavior via LLM-driven routing.
- **Custom Agents:** Developer-defined specialized implementations.
- **ADK 2.0 Alpha:** Graph-based workflows for more complex orchestration.

### Long-Running Task Support

- **Sessions:** Persistent conversation state with rewind capabilities.
- **State management:** Contextual data persistence across interactions.
- **Context caching and compression** for efficient long-running operations.
- **Agent Engine Runtime:** Managed service for long-running agents.

### Multi-Agent

- **Hierarchical composition:** Multiple specialized agents in a hierarchy.
- **Supervisor agents:** Coordinate sub-agents.
- **Agents as tools:** Agents can be called as tools by other agents.
- **Graph-based multi-agent** (ADK 2.0 Alpha).

### Human-in-the-Loop

- **Action confirmations:** Human approval before execution.
- **Callbacks:** Various interaction patterns between agents and human operators.

### Memory

- **Sessions:** Persistent conversation state.
- **State management:** Contextual data across interactions.
- **Context caching:** Efficient reuse of previous context.
- **Short and long-term retention patterns.**

### Tool Use

- **Pre-built tools:** Search, Code Execution.
- **Custom function tools.**
- **OpenAPI tools:** From API specifications.
- **MCP tools:** Model Context Protocol integration.
- **Agents as tools.**

### Evaluation & Observability

- **Built-in evaluation:** Assesses both final response quality and step-by-step execution trajectory against predefined test cases.
- **Vertex AI integration:** Full Google Cloud monitoring.

### Guardrails

- **Security and safety patterns:** Built-in best practices for trustworthy agents.
- **Deterministic guardrails:** Via workflow agents (SequentialAgent enforces order).
- **Orchestration controls** for predictable behavior.

### Production Deployment

- **Vertex AI Agent Engine Runtime:** Fully managed Google Cloud service.
- **Cloud Run:** Containerized deployment.
- **GKE (Kubernetes):** Scalable deployment.
- **Docker:** Custom infrastructure.
- **Multi-language:** Python, Java, and more coming.
- **Local CLI:** Development and testing.

---

## 8. Temporal AI

**Source:** [Temporal for AI](https://temporal.io/solutions/ai) | [Temporal Blog](https://temporal.io/blog/of-course-you-can-build-dynamic-ai-agents-with-temporal) | [OpenAI Integration](https://temporal.io/blog/announcing-openai-agents-sdk-integration)

> Not an agent framework itself, but a **durable execution platform** that provides the missing infrastructure layer for production agent systems. $5B valuation, $300M Series D.

### Architecture

Temporal provides durable execution for agent workflows:

- **Workflows:** Deterministic orchestration layer that defines application structure. Must be deterministic for Temporal's replay mechanism.
- **Activities:** Where actual work happens - calling LLMs, invoking tools, making API requests. Can be non-deterministic.
- **Each agent becomes a Temporal Workflow** with unique ID, guaranteeing only one active agent process per user session.

### Long-Running Task Support

**This is Temporal's core value proposition.**

- **Durable execution:** Guarantees all processes run to completion despite failures.
- **Checkpointing:** Automatic state snapshots. Replit Agent uses Temporal for application checkpoints.
- **Resumability:** Workflows survive process crashes, outages, and other failures.
- **Long timescales:** Built for workflows lasting hours, days, or months.
- **State persistence:** Maintains full state across entire lifecycle.
- **Temporal Nexus (GA):** Cross-namespace workflow coordination.

### Multi-Agent

- **Multi-agentic architecture:** Each agent as a workflow, coordinating via signals and queries.
- **Parallel task execution** at scale.
- **Cross-namespace coordination** via Temporal Nexus.

### Human-in-the-Loop

- **Workflow Updates:** Agents pause and wait for user approval before continuing.
- **Signal-based:** External systems send signals to pause/resume workflows.
- Integrates naturally with approval patterns.

### Memory

- **Workflow state:** Full state maintained automatically by Temporal runtime.
- **Event history:** Complete audit trail of all workflow events.
- No AI-specific memory (short-term, long-term, episodic) - relies on the agent framework for that.

### Tool Use

- **Activities as tools:** Each tool call is a Temporal Activity with automatic retry policies.
- **Activity timeouts:** Configurable per-tool.
- Not an agent framework - integrates with frameworks like OpenAI Agents SDK.

### Evaluation & Observability

- **Temporal Cloud:** Full workflow visibility.
- **Event history:** Complete record of all workflow events.
- **Multi-Region Replication (GA):** 99.99% SLA.

### Guardrails

- **Deterministic workflows:** Structural guarantee of execution order.
- **Activity retries:** Automatic retry until valid data.
- **Timeouts:** Per-activity and per-workflow.
- Not AI-specific guardrails (input/output validation handled by agent framework).

### Production Deployment

- **Temporal Cloud:** Managed service, 99.99% SLA with Multi-Region Replication.
- **Self-hosted:** Open-source server.
- **Multi-cloud:** AWS, Google Cloud.
- **SDKs:** Go, Java, Python, TypeScript, PHP, .NET (beta), Ruby (pre-release).
- **OpenAI Agents SDK integration:** Official partnership for durable agent execution.
- **Enterprise adoption:** Replit, Netflix, Stripe, Datadog.

---

## 9. Letta (MemGPT)

**Source:** [Letta Docs](https://docs.letta.com/) | [GitHub](https://github.com/letta-ai/letta) | [Research Paper](https://docs.letta.com/concepts/letta/)

> Platform for building stateful agents with advanced memory. Formerly MemGPT. Implements an "LLM-as-an-Operating-System" paradigm where the model manages its own memory.

### Architecture

Letta implements the **MemGPT architecture** - a self-managing memory system:

- **LLM-as-Operating-System paradigm:** The model manages its own memory, context, and reasoning loops, much like a traditional OS manages RAM and disk.
- **Self-editing memory:** Key innovation - agents actively manage their own memory using tools, deciding what to remember and retrieve.
- **Agent loop:** Agent reasons, calls memory functions, updates memory, and continues reasoning.

### Long-Running Task Support

- **Stateful by design:** Agents maintain state across interactions indefinitely.
- **Memory persistence:** All memory tiers persist across sessions.
- **Heartbeats:** Mechanism for long-running agent execution.
- **Identity persistence:** Agents maintain identity across sessions via self-managed memory.

### Multi-Agent

- **Multi-agent collaboration** supported.
- **Agents can communicate** and share memory stores.
- Primarily focused on single-agent statefulness rather than multi-agent orchestration.

### Human-in-the-Loop

- **`send_message` tool:** Agent's primary interface for communicating with users.
- Not a primary focus - Letta is more about autonomous stateful execution.

### Memory

**This is Letta's core value proposition - the most sophisticated memory system of any framework.**

- **Core Memory (In-Context):** Fixed-size working memory embedded in system instructions, always visible to the agent. Writeable only via MemGPT function calls. Stores key facts about user and agent persona.
- **Archival Memory:** Vector DB table for long-running memories and external data. Unlimited size. Agent decides what to persist via `archival_memory_insert` and `archival_memory_search`.
- **Recall Memory:** Table logging all conversational history. Searchable via `conversation_search`.

**Memory tools (base agent type `memgpt_agent`):**
- `send_message` - communicate with user
- `core_memory_append` - add to core memory
- `core_memory_replace` - edit core memory
- `conversation_search` - search conversation history
- `archival_memory_insert` - persist to archival storage
- `archival_memory_search` - retrieve from archival storage

### Tool Use

- **Memory tools as first-class:** Memory operations are tools the agent calls.
- **Custom tools:** Additional tools can be defined.
- **Code SDK:** Rich support for agent skills and local tool execution (Bash, Grep, etc.).

### Evaluation & Observability

- **Memory benchmarking:** Letta has published agent memory benchmarks.
- No dedicated observability platform.

### Guardrails

- **Memory structure:** Fixed core memory blocks provide structural constraints.
- No dedicated input/output guardrail system.

### Production Deployment

- **Letta Server:** Self-hosted deployment.
- **Letta Cloud:** Managed service.
- **Model-agnostic:** Works with various LLM providers.
- **REST API:** For integration with external systems.

---

## 10. Mastra

**Source:** [Official Docs](https://mastra.ai/docs) | [GitHub](https://github.com/mastra-ai/mastra) | [mastra.ai](https://mastra.ai/)

> TypeScript-first AI agent framework from the team behind Gatsby. Y Combinator W25, $13M funding, 22.3k+ GitHub stars, 300k+ weekly npm downloads. Launched January 2026.

### Architecture

Mastra follows a **TypeScript-native agent loop**:

- **Agents:** LLMs paired with tools that reason about goals, decide tool use, retain memory, and iterate until final answer or stop condition.
- **Workflows:** Separate concept for predetermined, multi-step processes with explicit control flow (loops, branching, approvals).
- **Type-safe:** Full TypeScript type safety throughout.
- Agents can be called within workflow steps.

### Long-Running Task Support

- **AgentFSFilesystem:** Database-persistent file storage (Turso/SQLite) that survives across sessions.
- **Workflow persistence:** Workflows support complex control flow with durable state.
- **Lower token costs** for long-running Observational Memory (recent optimization).

### Multi-Agent

- **Workflows with multiple agents:** Chain agents in workflow steps.
- **Tool-based composition:** Agents can call other agents as tools.
- Not primarily focused on multi-agent orchestration.

### Human-in-the-Loop

- **Workflow approvals:** Pause execution and wait for human approval.
- **Branching workflows:** Different paths based on human decisions.

### Memory

- **Short-term memory:** Conversation context within threads.
- **Long-term memory:** Persistent across sessions.
- **Storage backends:** libSQL, Postgres.
- **Observational Memory:** Lower token cost memory for long-running agents.
- **AgentFSFilesystem:** Persistent file storage across sessions.

### Tool Use

- **MCP Support:** Connect agents to pre-built tools (Google Sheets, GitHub, databases).
- **Custom tools:** Define TypeScript functions as tools.
- **Type-safe tool definitions** with full TypeScript support.

### Evaluation & Observability

- **Observability pipeline:** Recently upgraded.
- **Type-safe server route inference.**
- No dedicated tracing dashboard (unlike LangSmith or OpenAI Tracing).

### Guardrails

- **TypeScript type safety** as compile-time guardrails.
- **Workflow validation** for predetermined processes.
- No dedicated runtime guardrail system.

### Production Deployment

- **Streaming:** `.stream()` returns real-time token delivery with separate promises for tool calls, results, and usage statistics.
- **npm package:** Standard TypeScript deployment.
- **Vercel/serverless:** Natural fit for TypeScript ecosystem.
- **Recent and growing rapidly** - youngest framework in this comparison.

---

## 11. Comparative Analysis

### Production Readiness Matrix

| Platform | Prod Ready | Durability | Multi-Agent | HitL | Memory | Guardrails | Observability |
|----------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **Claude Agent SDK** | HIGH | Sessions + compaction | Subagents | Permission modes | Session + CLAUDE.md | Hooks + permissions | Hooks + cost tracking |
| **OpenAI Agents SDK** | HIGH | Sessions (SQL/Redis) | Handoffs | Blocking guardrails | Sessions | Input/output/tool | Built-in tracing |
| **LangGraph** | HIGH | Checkpoints (PG/SQLite) | Graph composition | Interrupt/resume | State + checkpoints | Node-level | LangSmith |
| **CrewAI** | HIGH | Limited | Crews + delegation | Task-level | Short/long-term | Rate limiting | Step callbacks |
| **AutoGen/AG2** | LOW | None | Group chat | UserAgent | Chat history only | None | Basic logging |
| **Bedrock Agents** | HIGH | Sessions | Multi-agent supervision | Return of control | KB + conversation | Content filters + IAM | Traces + CloudWatch |
| **Google ADK** | HIGH | Sessions + state | Hierarchy + graph | Action confirmations | Sessions + caching | Workflow controls | Built-in eval |
| **Temporal** | HIGHEST | Core feature (durable) | Workflow coordination | Signals/updates | Workflow state | Timeouts + retries | Full event history |
| **Letta (MemGPT)** | MEDIUM | Memory persistence | Limited | send_message | Core/archival/recall | Memory structure | Benchmarks |
| **Mastra** | MEDIUM | Workflow + FS persist | Workflow-based | Workflow approvals | Short/long + FS | Type safety | Pipeline |

### Architecture Pattern Comparison

| Platform | Primary Pattern | Loop Style |
|----------|----------------|------------|
| Claude Agent SDK | ReAct loop (CLI binary) | While-loop with tool calls |
| OpenAI Agents SDK | ReAct with handoffs | While-loop with tool calls |
| LangGraph | State machine (graph) | Node-to-node transitions |
| CrewAI | Agent-team (crew) | Task-by-task execution |
| AutoGen/AG2 | Conversational agents | Group chat turns |
| Bedrock Agents | Managed orchestration | Iterative reasoning loop |
| Google ADK | Workflow + LLM agents | Sequential/parallel/loop |
| Temporal | Durable workflows | Workflow + activities |
| Letta (MemGPT) | Self-managing memory | Memory-augmented loop |
| Mastra | TypeScript agent loop | Tool iteration loop |

### Best Framework For Each Use Case

| Use Case | Best Choice | Runner-Up |
|----------|-------------|-----------|
| **Complex stateful workflows** | LangGraph | Temporal + any framework |
| **Fast prototyping** | CrewAI | Mastra |
| **Code-first TypeScript** | Mastra | OpenAI Agents SDK |
| **Long-running durable tasks** | Temporal (infra) + framework | LangGraph |
| **Memory-intensive agents** | Letta (MemGPT) | LangGraph |
| **Enterprise AWS** | Bedrock Agents | Temporal Cloud |
| **Enterprise Google Cloud** | Google ADK | LangGraph Platform |
| **Multi-agent research** | AutoGen/AG2 | CrewAI |
| **Anthropic Claude integration** | Claude Agent SDK | LangGraph |
| **OpenAI integration** | OpenAI Agents SDK | LangGraph |
| **Maximum production safety** | Temporal + LangGraph | Bedrock Agents |

---

## 12. Key Takeaways for Plugin Integration

### Patterns That Matter Most for Our Use Case

Given our IntelliJ plugin's agentic AI architecture (single agent ReAct loop, Cody Enterprise backend, long-running tasks):

1. **Agent Loop Pattern:** All major frameworks converge on the same core pattern - a while loop calling LLM, executing tools, feeding results back. Our existing ReAct loop design aligns with industry consensus.

2. **Checkpointing is Critical:** LangGraph and Temporal demonstrate that production agents need durable state. For our plugin, this means:
   - Save agent state between tool calls
   - Resume from last checkpoint on IDE restart or error
   - Consider thread-based session management (LangGraph pattern)

3. **Memory Architecture (Letta Pattern):** The three-tier memory model (core/archival/recall) maps well to our needs:
   - **Core memory** = Current task context (always in prompt)
   - **Archival memory** = Project knowledge, past decisions (vector search)
   - **Recall memory** = Conversation history (searchable)

4. **Subagent Pattern (Claude SDK):** Each subagent gets a fresh context with only its final result returning to parent. This keeps the main agent's context lean - directly applicable to our tool architecture.

5. **Guardrails as Hooks:** The Claude Agent SDK and OpenAI Agents SDK both implement guardrails as pre/post hooks on tool execution. This maps to our approval gate architecture.

6. **Human-in-the-Loop:** LangGraph's interrupt/resume pattern is the gold standard. Our approval gates should:
   - Pause execution at tool boundaries
   - Persist state before pausing
   - Resume exactly where left off with human input

7. **Streaming is Table Stakes:** Every production framework supports real-time token streaming. Our JCEF-based chat UI must handle streaming from the start.

8. **Temporal for Durability:** If our agent tasks become truly long-running (minutes+), wrapping the agent loop in a Temporal-like durable execution pattern provides crash recovery and guaranteed completion.

### Architecture Recommendations

Based on this research, our plugin's agentic architecture should incorporate:

- **ReAct loop** with tool-call/result cycles (industry consensus)
- **Session-based persistence** with checkpoint-and-resume (LangGraph pattern)
- **Hierarchical memory** - in-context core + searchable archival (Letta pattern)
- **Hook-based guardrails** at PreToolUse/PostToolUse boundaries (Claude SDK pattern)
- **Interrupt/resume** for human approval gates (LangGraph pattern)
- **Subagent delegation** for isolated subtasks (Claude SDK pattern)
- **Cost tracking** per session with budget limits (Claude SDK pattern)
- **Automatic compaction** when context window fills (Claude SDK pattern)

---

*Research conducted 2026-03-26. Sources verified against official documentation.*
