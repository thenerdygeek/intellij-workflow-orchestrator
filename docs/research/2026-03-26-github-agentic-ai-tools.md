# GitHub Agentic AI Tools & Frameworks Research

**Date:** 2026-03-26
**Purpose:** Survey of top-starred agentic AI tools and frameworks on GitHub for Phase 3 architecture decisions.

## Summary Table

| # | Tool/Framework | Stars | Language | Category | Architecture | Production Ready | License |
|---|---|---|---|---|---|---|---|
| 1 | **Claude Code** | 82.8k | TS/Shell/Python | Coding Agent | Agentic coding CLI, ReAct | Yes | Proprietary |
| 2 | **OpenHands** | 69.8k | Python/TS | Coding Agent | Autonomous SWE, SDK | Yes | MIT |
| 3 | **MetaGPT** | 66.2k | Python | Multi-Agent | SOP-driven roles | Experimental | MIT |
| 4 | **AutoGen (Microsoft)** | 56.2k | Python/.NET | Agent Framework | Multi-agent conversations | Yes | MIT |
| 5 | **Mem0** | 51.1k | Python/TS | Memory | Universal memory layer | Yes | Apache-2.0 |
| 6 | **CrewAI** | 47.2k | Python | Agent Framework | Role-based multi-agent | Yes | MIT |
| 7 | **Aider** | 42.4k | Python | Coding Agent | Pair programming | Yes | Apache-2.0 |
| 8 | **ChatDev** | 31.9k | Python/Vue | Multi-Agent | Zero-code multi-agent | Experimental | Open Source |
| 9 | **LangGraph** | 27.5k | Python/TS | Agent Framework | Graph-based orchestration | Yes (enterprise) | MIT |
| 10 | **Smolagents (HuggingFace)** | 26.3k | Python | Agent Framework | Code-based agents | Yes | Apache-2.0 |
| 11 | **MCP Python SDK** | 22.3k | Python | Tool Use | Standard tool protocol | Yes | MIT |
| 12 | **Mastra** | 22.3k | TypeScript | Agent Framework | Graph workflows + agents | Yes | Apache-2.0/EE |
| 13 | **Letta (MemGPT)** | 21.7k | Python | Memory | Stateful agents + memory | Yes | Apache-2.0 |
| 14 | **OpenAI Agents SDK** | 20.3k | Python/TS | Agent Framework | Multi-agent handoffs | Yes | MIT |
| 15 | **SWE-agent** | 18.8k | Python | Coding Agent | SWE benchmark | Research | MIT |
| 16 | **CAMEL** | 16.5k | Python | Multi-Agent | Scaling laws of agents | Research | Apache-2.0 |
| 17 | **Pydantic AI** | 15.8k | Python | Agent Framework | Type-safe agents | Yes | MIT |
| 18 | **MCP TypeScript SDK** | 12.0k | TypeScript | Tool Use | Standard tool protocol | Yes | MIT |
| 19 | **MCP Spec** | 7.6k | TypeScript | Tool Use | Protocol specification | Yes | MIT |
| 20 | **Anthropic Agent SDK** | 5.8k | Python/TS | Agent SDK | ReAct loop + tools | Yes | MIT |
| 21 | **AgentOps** | 5.4k | Python | Evaluation | Agent observability | Yes | MIT |
| 22 | **AG2 (AutoGen fork)** | 4.3k | Python | Agent Framework | Open-source AgentOS | Yes | Apache-2.0 |
| 23 | **Braintrust AutoEvals** | 0.8k | Python/TS | Evaluation | Eval methods library | Yes | Open Source |
| -- | **LangSmith** | N/A (SaaS) | SaaS | Evaluation | Tracing + evals | Yes (commercial) | Proprietary |
| -- | **Devin** | N/A (closed) | N/A | Coding Agent | Multi-model swarm | Yes (commercial) | Proprietary |
| -- | **Cursor** | N/A (closed) | N/A | Coding Agent | AI code editor | Yes (commercial) | Proprietary |
| -- | **Windsurf** | N/A (closed) | N/A | Coding Agent | Agentic IDE | Yes (commercial) | Proprietary |
| -- | **Temporal** | N/A (infra) | Go/TS/Python | Orchestration | Durable execution | Yes (enterprise) | MIT |

---

## 1. Agent Frameworks

### 1.1 AutoGen / AG2 (Microsoft)

- **GitHub:** https://github.com/microsoft/autogen
- **Stars:** 56.2k
- **Language:** Python (also .NET support)
- **License:** MIT
- **What it does:** Programming framework for building multi-agent AI applications. Agents can operate autonomously or collaborate with humans. Originally from Microsoft Research, now also forked as AG2 (https://github.com/ag2ai/ag2, 4.3k stars, Apache-2.0).
- **Architecture patterns:**
  - Multi-agent conversation: "conversable agents" that talk in structured dialogues
  - Group chat orchestration, swarms, one-on-one exchanges
  - Agents debate, collaborate, and solve through dialogue
- **Key features:**
  - Flexible agent roles and conversation patterns
  - Human-in-the-loop support
  - Code execution in sandboxed environments
  - Tool use via function calling
  - Nested agent conversations
  - Multi-model support
- **Production readiness:** Widely adopted in enterprise. Microsoft-backed. Weekly office hours, active Discord. 3,780 commits, 470 open issues.
- **Enterprise adoption:** Used by Microsoft internal teams, research institutions, enterprises building conversational AI workflows.

### 1.2 CrewAI

- **GitHub:** https://github.com/crewAIInc/crewAI
- **Stars:** 47.2k
- **Language:** Python
- **License:** MIT
- **What it does:** Framework for orchestrating role-playing, autonomous AI agents. Fosters collaborative intelligence where agents work together on complex tasks.
- **Architecture patterns:**
  - Role-based multi-agent: agents have roles, goals, backstories
  - Crews (autonomous agent teams) + Flows (event-driven workflows)
  - Sequential and parallel task execution
- **Key features:**
  - Agent roles with defined goals and tool assignments
  - Task delegation between agents
  - Memory (short-term, long-term, entity memory)
  - Tool integration (custom tools, LangChain tools)
  - Process types: sequential, hierarchical
  - Built-in RAG support
  - Human-in-the-loop via task callbacks
- **Production readiness:** Very mature. 2,129 commits. Largest ecosystem of any AI agent framework. Independent of LangChain (built from scratch).
- **Enterprise adoption:** CrewAI Enterprise platform available. Widely adopted for business automation workflows.

### 1.3 LangGraph

- **GitHub:** https://github.com/langchain-ai/langgraph
- **Stars:** 27.5k
- **Language:** Python (also TypeScript via langgraphjs)
- **License:** MIT
- **What it does:** Low-level orchestration framework for building stateful, long-running agents as directed graphs with nodes, edges, and explicit state transitions.
- **Architecture patterns:**
  - Graph-based orchestration: DAG with explicit state machines
  - ReAct loops as graph cycles
  - Plan-and-execute via subgraphs
  - Multi-agent via supervisor/worker graph patterns
- **Key features:**
  - Durable execution with checkpointing
  - Human-in-the-loop (interrupt/resume at any node)
  - Streaming (token-level and node-level)
  - Memory (short-term via state, long-term via store)
  - Time-travel debugging
  - Subgraph composition
  - LangGraph Platform (commercial) for deployment
- **Production readiness:** Enterprise-grade. Trusted by Klarna, Replit, Elastic. 6,653 commits, 288 contributors. Latest release: v0.4.19 (Mar 2026).
- **Enterprise adoption:** LangGraph Cloud for managed deployment. LangSmith integration for observability. Most mature graph-based agent framework.

### 1.4 Smolagents (HuggingFace)

- **GitHub:** https://github.com/huggingface/smolagents
- **Stars:** 26.3k
- **Language:** Python
- **License:** Apache-2.0
- **What it does:** Minimalist library for building agents that think and act in code. Core logic in ~1,000 lines. Agents write Python snippets rather than JSON tool calls.
- **Architecture patterns:**
  - Code-based agent actions (CodeAgent)
  - Also supports traditional ToolCallingAgent
  - ReAct-style reasoning loops
- **Key features:**
  - Code execution in sandboxes (E2B, Docker, WebAssembly, Modal)
  - Multi-modal: vision, audio, video inputs
  - LiteLLM integration for 100+ LLM providers
  - HuggingFace Hub sharing for agents and tools
  - CLI with interactive setup
  - Minimal abstraction, easy to understand/modify
- **Production readiness:** Active development. 1,032 commits, 34 releases. Latest v1.24.0 (Jan 2026).
- **Enterprise adoption:** Backed by HuggingFace. Good for teams wanting minimal framework overhead.

### 1.5 Mastra

- **GitHub:** https://github.com/mastra-ai/mastra
- **Stars:** 22.3k
- **Language:** TypeScript (99.3%)
- **License:** Apache-2.0 (core) / Enterprise License (EE features)
- **What it does:** TypeScript framework for building AI-powered applications and agents. From the team behind Gatsby. Y Combinator W25 ($13M funding).
- **Architecture patterns:**
  - Graph-based workflow orchestration with control flow syntax
  - Autonomous agents using LLMs and tools
  - Human-in-the-loop with state persistence
- **Key features:**
  - Model routing across 40+ providers
  - MCP server support
  - RAG and context management
  - React/Next.js/Node.js integration
  - Built-in evaluation and observability
  - Conversation history management
  - 300k+ weekly npm downloads
- **Production readiness:** Very active. 13,758 commits, 75 releases. Latest: @mastra/core@1.14.0 (Mar 2026).
- **Enterprise adoption:** Leading TypeScript agent framework. Enterprise license for advanced features.

### 1.6 OpenAI Agents SDK

- **GitHub:** https://github.com/openai/openai-agents-python (also TS: openai/openai-agents-js)
- **Stars:** 20.3k
- **Language:** Python (also TypeScript)
- **License:** MIT
- **What it does:** Lightweight framework for building multi-agent workflows. Provider-agnostic (supports 100+ LLMs beyond OpenAI).
- **Architecture patterns:**
  - Agent handoffs: agents delegate to specialized agents
  - Multi-agent orchestration via handoff chains
  - Guardrails for input/output validation
- **Key features:**
  - Tool calling with automatic schema generation
  - Agent handoffs (transfer control between agents)
  - Guardrails (input validators, output validators)
  - Tracing and observability built-in
  - Streaming support
  - Context management
  - Provider-agnostic (not locked to OpenAI)
- **Production readiness:** Active. 76 releases, latest v0.13.1 (Mar 25, 2026). 1,282 commits.
- **Enterprise adoption:** OpenAI-backed. Growing ecosystem. Used for building on OpenAI platform but works with other providers.

### 1.7 Pydantic AI

- **GitHub:** https://github.com/pydantic/pydantic-ai
- **Stars:** 15.8k
- **Language:** Python (99.7%)
- **License:** MIT
- **What it does:** Type-safe AI agent framework leveraging Pydantic for validation. Production-grade with structured outputs.
- **Architecture patterns:**
  - Type-safe agent definitions with validated inputs/outputs
  - Dependency injection for agent behavior customization
  - Graph support for complex workflows
- **Key features:**
  - Model-agnostic (OpenAI, Anthropic, Gemini, 20+ providers)
  - Structured output validation via Pydantic models
  - Thinking/reasoning support
  - Web search built-in
  - MCP integration
  - Durable execution across transient failures
  - Human-in-the-loop tool approval
  - Pydantic Logfire integration for observability
  - IDE auto-completion via type hints
- **Production readiness:** Very active. 221 releases, latest v1.72.0 (Mar 26, 2026). 1,829 commits, 410 contributors, used by 3,700+ projects.
- **Enterprise adoption:** From the Pydantic team (ubiquitous in Python ecosystem). Rising fast as the type-safe choice.

### 1.8 Anthropic Agent SDK

- **GitHub:** https://github.com/anthropics/claude-agent-sdk-python (also TypeScript: claude-agent-sdk-typescript)
- **Stars:** 5.8k (newer, released 2026)
- **Language:** Python (3.10+), also TypeScript
- **License:** MIT
- **What it does:** SDK for building agentic applications with Claude Code. Exposes the Claude Code agent loop, built-in tools, and context management as a library.
- **Architecture patterns:**
  - ReAct agent loop (same as Claude Code)
  - Tool use via MCP protocol
  - Custom tools as in-process MCP servers
  - Hooks for intercepting agent behavior
- **Key features:**
  - Two APIs: `query()` (simple) and `ClaudeSDKClient` (advanced bidirectional)
  - Built-in tools: file read/write, bash, edit
  - Custom tool definition via `@tool` decorator
  - Hooks system (pre/post tool use)
  - Tool permissions (allow/deny/custom logic)
  - Streaming responses
  - Bundled Claude Code CLI (no separate install)
  - Cross-platform (macOS, Linux, Windows)
- **Production readiness:** Active development. 389 commits. Newer SDK but backed by Anthropic.
- **Enterprise adoption:** Infrastructure behind Claude Code. Growing adoption for custom agent apps.

---

## 2. Orchestration

### 2.1 Temporal + AI

- **GitHub:** https://github.com/temporalio/temporal
- **Website:** https://temporal.io
- **Language:** Go (server), Python/TypeScript/Java/Go (SDKs)
- **License:** MIT
- **What it does:** Durable execution platform that provides reliable workflow orchestration. Increasingly used as the backbone for production AI agent systems.
- **Architecture patterns:**
  - Durable execution: workflows survive crashes, restarts, deployments
  - Deterministic workflow code + non-deterministic activities
  - Long-running stateful workflows (unlimited duration)
  - Event-driven orchestration with signals and queries
- **Key features for AI agents:**
  - Auto-save for agent state and memory
  - Automatic retries for LLM API calls (activities)
  - Human-in-the-loop via signals (pause/resume workflows)
  - Multi-agent orchestration via child workflows
  - Temporal Nexus for cross-namespace workflow connections (GA)
  - Time-travel debugging and full observability
- **Production readiness:** Enterprise-grade. $5B valuation ($300M Series D from a16z). Used at Netflix, Stripe, Snap, Datadog.
- **Relevance to our plugin:** Strong pattern match for durable agent execution. LLM decisions happen in Activities (non-deterministic), while the overall agent loop runs as a Workflow (deterministic orchestration). However, Temporal is a server-side platform, not directly usable in an IntelliJ plugin.

---

## 3. Tool Use Libraries

### 3.1 Model Context Protocol (MCP)

- **Spec repo:** https://github.com/modelcontextprotocol/modelcontextprotocol (7.6k stars)
- **Python SDK:** https://github.com/modelcontextprotocol/python-sdk (22.3k stars)
- **TypeScript SDK:** https://github.com/modelcontextprotocol/typescript-sdk (12.0k stars)
- **Language:** TypeScript (spec), Python/TypeScript (SDKs)
- **License:** MIT
- **What it does:** Open standard introduced by Anthropic (Nov 2024) for connecting AI systems to external tools, data sources, and systems. Donated to the Agentic AI Foundation (Linux Foundation) in Dec 2025, co-founded by Anthropic, Block, and OpenAI.
- **Architecture patterns:**
  - Client-server protocol: host applications connect to MCP servers
  - Resources (read-only data), Tools (actions with side effects), Prompts (templates)
  - Transport: stdio, SSE, Streamable HTTP
  - OAuth authentication support
- **Key features:**
  - Standardized tool calling across all LLM providers
  - FastMCP framework for rapid server development (Python)
  - Framework middleware packages (Express, Hono, Node.js)
  - Structured output with Pydantic/TypedDict/dataclass support
  - Context management with logging, progress, request context
  - Multi-runtime: Node.js, Bun, Deno (TS), any Python runtime
- **Production readiness:** Industry standard. Adopted by OpenAI, Google DeepMind, Microsoft, and all major AI providers. 348 contributors on spec repo. Latest spec: 2025-11-25.
- **Enterprise adoption:** De facto standard for AI tool integration. Every major agent framework now supports MCP.
- **Relevance to our plugin:** Our agent module already uses MCP for tool exposure. The protocol is the clear winner for tool standardization.

---

## 4. Coding Agents

### 4.1 Claude Code

- **GitHub:** https://github.com/anthropics/claude-code
- **Stars:** 82.8k
- **Language:** Shell (47%), Python (29.3%), TypeScript (17.7%)
- **License:** Proprietary (Anthropic)
- **What it does:** Agentic coding tool that lives in the terminal. Understands codebases and helps code faster through natural language commands. Handles routine tasks, explains complex code, manages git workflows.
- **Architecture patterns:**
  - ReAct loop with tool use
  - Context engineering (CLAUDE.md files, codebase indexing)
  - Auto-compaction at ~83.5% context usage
  - Sub-agent delegation via `delegate_task` tool
- **Key features:**
  - File read/write/edit, bash execution
  - Git workflow management
  - IDE integration (VS Code, JetBrains)
  - GitHub Actions integration (claude-code-action)
  - Agent SDK for programmatic use
  - Streaming responses
  - 200K/1M context window support
- **Production readiness:** GA since May 2025. 566 commits, 51 contributors, 6.9k forks. Very active (5k+ open issues reflecting massive user base).
- **Enterprise adoption:** Widely used across industry. Powers the Anthropic Agent SDK.

### 4.2 OpenHands (formerly OpenDevin)

- **GitHub:** https://github.com/OpenHands/OpenHands
- **Stars:** 69.8k
- **Language:** Python (72.9%), TypeScript (25.2%)
- **License:** MIT (enterprise directory under separate license)
- **What it does:** Open platform for AI-driven development. Provides composable SDK, CLI, local GUI, and cloud platform for running coding agents.
- **Architecture patterns:**
  - Software Agent SDK: composable Python library for defining agents
  - Sandboxed execution environments
  - Multiple deployment targets (local to cloud)
- **Key features:**
  - Software Agent SDK for building custom agents
  - CLI compatible with Claude, GPT, and other LLMs
  - Local GUI (REST API + React SPA)
  - Cloud platform with free tier (Minimax model)
  - Slack, Jira, Linear integrations
  - Enterprise self-hosted Kubernetes deployment
  - SWEBench score: 77.6
- **Production readiness:** Very active. 250+ contributors. v1.5.0 (Mar 2026). Leading open-source coding agent.
- **Enterprise adoption:** Enterprise tier with dedicated support. MLSys 2026 poster on SDK architecture.

### 4.3 Aider

- **GitHub:** https://github.com/Aider-AI/aider
- **Stars:** 42.4k
- **Language:** Python (80%)
- **License:** Apache-2.0
- **What it does:** AI pair programming in the terminal. Collaborates with LLMs to start new projects or enhance existing codebases.
- **Architecture patterns:**
  - Codebase mapping (repo-map) for context
  - Edit-apply cycle with git integration
  - Multi-file editing with diff-based changes
- **Key features:**
  - Works with Claude, DeepSeek, OpenAI, and 100+ LLMs
  - Supports 100+ programming languages
  - Automatic git commits with sensible messages
  - Images, web pages, and voice input support
  - IDE integration available
  - 5.7M+ pip installations
- **Production readiness:** Very mature. 13,119 commits, 93 releases. v0.86.0 (Aug 2025).
- **Enterprise adoption:** Widely used by individual developers and small teams. Terminal-first approach.

### 4.4 SWE-agent

- **GitHub:** https://github.com/SWE-agent/SWE-agent
- **Stars:** 18.8k
- **Language:** Python (94.8%)
- **License:** MIT
- **What it does:** Takes a GitHub issue and tries to automatically fix it using your LLM of choice. Also used for offensive cybersecurity and competitive coding. From Princeton/Stanford researchers.
- **Architecture patterns:**
  - Agent-Computer Interface (ACI) for LLM-friendly tool design
  - ReAct-style reasoning with specialized commands
  - Trajectory-based execution
- **Key features:**
  - Automatic GitHub issue resolution
  - Multiple LLM support
  - Cybersecurity and competitive coding modes
  - mini-swe-agent: 100-line version scoring >74% on SWE-bench verified
  - NeurIPS 2024 paper
- **Production readiness:** Research-oriented but practical. 2,158 commits, 26 releases. v1.1.0 (May 2025).
- **Enterprise adoption:** Primarily research/academic. mini-swe-agent recommended for production use.

### 4.5 Devin (Cognition)

- **Website:** https://devin.ai
- **Stars:** N/A (closed source)
- **License:** Proprietary
- **What it does:** AI software engineer. Compound AI system using specialized model swarm (planner, coder, critic) for autonomous software development.
- **Architecture patterns:**
  - Multi-model swarm: Planner (high-reasoning) + Coder (code-specialized) + Critic (adversarial reviewer)
  - Persistent context across sessions
  - Learn-from-failure adaptation
- **Key features:**
  - Codebase indexing with architecture wiki generation
  - Devin Search: agentic code exploration with cited answers
  - Legacy code migration (COBOL/Fortran to Rust/Go/Python)
  - PR creation with review response capability
  - Multi-modal: UI mockups, Figma, video recordings
  - Desktop testing via computer use
  - Devin Review: AI-powered PR analysis
- **Production readiness:** Commercial product. Pricing: $20/month + $2.25/ACU. Devin 2.0 released.
- **Enterprise adoption:** Growing enterprise adoption for code migration and automated development.

### 4.6 Cursor

- **Website:** https://cursor.com
- **Stars:** N/A (closed source, VS Code fork)
- **License:** Proprietary
- **What it does:** AI code editor built as a VS Code fork with deep AI integration. Leading commercial AI IDE.
- **Key features:** Tab autocomplete, inline edit, agent mode, multi-file editing, codebase-aware context.
- **Enterprise adoption:** Widely adopted. Y Combinator backed (Anysphere).

### 4.7 Windsurf (formerly Codeium)

- **Website:** https://windsurf.com
- **Stars:** N/A (closed source)
- **License:** Proprietary
- **What it does:** Agentic IDE with Cascade (multi-step planning assistant), Tab/Supercomplete, and app preview/deploy capabilities. Ranked #1 in LogRocket AI Dev Tool Power Rankings (Feb 2026).
- **Key features:** Cascade agentic assistant, Supercomplete, app previews/deploys, plugins for VS Code/JetBrains/Vim.
- **Enterprise adoption:** Strong adoption, especially among beginners. Free tier available.

---

## 5. Multi-Agent Systems

### 5.1 MetaGPT

- **GitHub:** https://github.com/FoundationAgents/MetaGPT
- **Stars:** 66.2k
- **Language:** Python (97.5%)
- **License:** MIT
- **What it does:** "The First AI Software Company." Multi-agent framework that assigns different roles to GPTs (product manager, architect, project manager, engineer) following Standard Operating Procedures (SOPs).
- **Architecture patterns:**
  - SOP-driven multi-agent: agents follow defined procedures
  - Role-based specialization with defined workflows
  - Full software company simulation (PRD -> Design -> Tasks -> Code)
- **Key features:**
  - One-line requirement to full software output
  - Agents: PM, architect, project manager, engineer
  - Competitive analysis generation
  - Data structures and API design
  - MGX (MetaGPT X): world's first AI agent development team
  - AFlow: automated agentic workflow generation (ICLR 2025 oral)
- **Production readiness:** Research-oriented with commercial product (MGX). 6,367 commits, 112 contributors, 8.3k forks.
- **Enterprise adoption:** MGX product launched. #1 Product of Week/Day on ProductHunt (Mar 2025).

### 5.2 ChatDev

- **GitHub:** https://github.com/OpenBMB/ChatDev
- **Stars:** 31.9k
- **Language:** Python (backend/FastAPI), Vue 3 (frontend)
- **License:** Open Source
- **What it does:** ChatDev 2.0 (DevAll) is a zero-code multi-agent platform for developing everything. Enables customized multi-agent systems through configuration.
- **Architecture patterns:**
  - Chat-based agent communication
  - Software development lifecycle simulation
  - Zero-code multi-agent orchestration (2.0)
- **Key features:**
  - Web console and Python SDK interfaces
  - YAML-based workflow templates
  - Data visualization, 3D generation, game development
  - Deep research workflows
  - Multi-agent task orchestration without code
- **Production readiness:** Active. ChatDev 2.0 released Jan 2026. 163 commits.
- **Enterprise adoption:** Research/experimental. Useful for rapid prototyping.

### 5.3 CAMEL

- **GitHub:** https://github.com/camel-ai/camel
- **Stars:** 16.5k
- **Language:** Python
- **License:** Apache-2.0
- **What it does:** "Finding the Scaling Laws of Agents." Open-source community focused on multi-agent systems for data generation, task automation, and world simulation.
- **Architecture patterns:**
  - Role-playing agent communication
  - Multi-agent task decomposition
  - Agent scaling research
- **Key features:**
  - Data generation via agent interaction
  - Task automation pipelines
  - World simulation capabilities
  - Multi-agent collaboration patterns
  - Research-first approach
- **Production readiness:** Active research project. 2,159 commits, 233 PRs, 219 issues.
- **Enterprise adoption:** Primarily academic/research.

---

## 6. Memory & RAG

### 6.1 Mem0

- **GitHub:** https://github.com/mem0ai/mem0
- **Stars:** 51.1k
- **Language:** Python (64.7%), TypeScript (24.5%)
- **License:** Apache-2.0
- **What it does:** Universal memory layer for AI agents. Enables personalized AI interactions through intelligent memory management.
- **Architecture patterns:**
  - Multi-level memory: User, Session, Agent state
  - Semantic search for memory retrieval
  - Memory graph with relationship tracking
- **Key features:**
  - Cross-platform SDKs (Python and JavaScript/TypeScript)
  - Integration with LangGraph, CrewAI, and other frameworks
  - Both hosted platform and self-hosted options
  - +26% accuracy improvement over competing solutions
  - 186M+ API calls in Q3 (exponential growth)
  - Selected as exclusive memory provider for AWS Agent SDK
- **Production readiness:** Very mature. $24M Series A raised. Largest community in agent memory space.
- **Enterprise adoption:** AWS partnership. Wide framework integration. Production-proven at scale.

### 6.2 Letta (formerly MemGPT)

- **GitHub:** https://github.com/letta-ai/letta
- **Stars:** 21.7k
- **Language:** Python (99.4%)
- **License:** Apache-2.0
- **What it does:** Platform for building stateful agents with advanced memory that can learn and self-improve over time. Evolved from the MemGPT research paper on teaching LLMs memory management.
- **Architecture patterns:**
  - Virtual context management (MemGPT paper)
  - Stateful agent architecture with persistent memory
  - Skills and subagent composition
  - Agent File (.af) open format for serializing agents
- **Key features:**
  - Letta Code CLI for local agent execution
  - Letta API for application integration
  - Pre-built skills and subagents
  - Continual learning capabilities
  - Model-agnostic (recommends Opus 4.5, GPT-5.2)
  - Python and TypeScript SDKs
  - Agent loop inspired by ReAct, MemGPT, and Claude Code
- **Production readiness:** Active development. Platform approach with API server.
- **Enterprise adoption:** Growing. Agent File format for portability across frameworks.

---

## 7. Evaluation & Observability

### 7.1 LangSmith

- **Website:** https://www.langchain.com/langsmith
- **Stars:** N/A (SaaS product, not open-source)
- **License:** Proprietary (commercial)
- **What it does:** End-to-end AI agent and LLM observability platform. Provides tracing, debugging, evaluation, and deployment tools.
- **Architecture patterns:**
  - Trace trees: root run + child runs for each step
  - Annotation queues for human evaluation
  - Experiment comparison framework
- **Key features:**
  - Step-by-step trace visualization
  - Multiple evaluator types: human, heuristic, LLM-as-judge, pairwise
  - Custom evaluators in Python/TypeScript
  - Side-by-side experiment comparison
  - Insights Agent: auto-analyzes traces for patterns and failures
  - Self-hosted option available
  - Deep LangGraph integration
- **Production readiness:** Mature commercial product. Self-hosted option for enterprises.
- **Enterprise adoption:** Default observability for LangChain/LangGraph ecosystem. Used by enterprises at scale.

### 7.2 Braintrust

- **GitHub:** https://github.com/braintrustdata/autoevals (841 stars - eval library)
- **Website:** https://www.braintrust.dev
- **Language:** Python, TypeScript
- **License:** Open Source (AutoEvals library) / Commercial (platform)
- **What it does:** AI observability platform for building quality AI products. AutoEvals library provides LLM-as-judge, heuristic, and statistical evaluation methods.
- **Key features:**
  - AutoEvals: LLM-as-judge, RAG evals, heuristic checks
  - Factuality, moderation, security evaluations
  - Context precision, faithfulness, answer relevancy (RAG)
  - Embedding-based similarity scoring
  - Sync and async evaluation APIs
  - Custom evaluator creation
  - Works with OpenAI, Claude, any provider
- **Production readiness:** Well-funded. $80M raised at $800M valuation (Feb 2026). Starter plan at $0/month.
- **Enterprise adoption:** Strong enterprise adoption. Leading managed eval platform.

### 7.3 AgentOps

- **GitHub:** https://github.com/AgentOps-AI/agentops
- **Stars:** 5.4k
- **Language:** Python
- **License:** MIT
- **What it does:** Observability and DevTool platform for AI agents. Session replay analytics, LLM cost tracking, and monitoring from prototype to production.
- **Architecture patterns:**
  - Decorator-based observability (@session, @agent, @operation, @task, @workflow)
  - Session replay with step-by-step execution graphs
  - Framework-agnostic integration layer
- **Key features:**
  - Session replay analytics
  - LLM cost tracking across providers
  - Native integrations: CrewAI, AG2/AutoGen, Agno, LangGraph, OpenAI Agents SDK
  - Self-hosting capability
  - Async/await and generator function support
  - Google ADK integration
- **Production readiness:** Active. 810 commits, 88 open issues. Growing integration ecosystem.
- **Enterprise adoption:** On-premises deployment option. Integrated with most major agent frameworks.

---

## Key Takeaways for Phase 3

### Architecture Patterns Emerging as Standards

1. **ReAct loop** remains the dominant single-agent pattern (Claude Code, Anthropic Agent SDK, Smolagents, Letta)
2. **Graph-based orchestration** is the standard for complex workflows (LangGraph, Mastra, Pydantic AI)
3. **MCP** is the universal tool protocol -- every framework supports it
4. **Human-in-the-loop** is table stakes -- all production frameworks support interrupt/resume
5. **Streaming** is expected everywhere -- token-level and event-level
6. **Durable execution** is increasingly important for production agents (LangGraph checkpointing, Temporal workflows)

### Language Ecosystem

- **Python** dominates overwhelmingly (8/10 top frameworks)
- **TypeScript** has Mastra (22.3k) as the clear leader, plus MCP SDK and OpenAI Agents JS
- **Kotlin/JVM** has no major agent framework (opportunity for our plugin, or challenge for ecosystem integration)

### Relevance to Our Plugin Architecture

| Our Decision | Industry Alignment |
|---|---|
| Single ReAct agent loop | Matches Claude Code, Anthropic SDK, Smolagents |
| MCP for tool exposure | Industry standard -- correct choice |
| ToolResult<T> return types | Similar to structured outputs in Pydantic AI, OpenAI SDK |
| Streaming via JCEF | Aligns with streaming as universal expectation |
| Human-in-the-loop approval gates | Required by all production frameworks |
| Checkpointing/persistence | Aligns with LangGraph, Temporal, Letta patterns |
| Context engineering (CLAUDE.md) | Matches Claude Code's approach |

### Frameworks to Watch

1. **Anthropic Agent SDK** -- Direct relevance as we use Claude/Sourcegraph. SDK exposes the exact agent loop we implement.
2. **MCP ecosystem** -- Already integrated. Continue tracking MCP spec evolution.
3. **Pydantic AI** -- Type-safe approach maps well to our Kotlin type-safe service layer.
4. **Letta** -- Agent File format and memory management patterns applicable to our stateful agent.
5. **Mem0** -- Memory layer patterns for potential agent memory persistence.

### Notable Trends (Mar 2026)

- **Agent memory** is a rapidly growing category (Mem0 at 51k stars, Letta at 21.7k)
- **Coding agents** are the highest-starred category overall (Claude Code 82.8k, OpenHands 69.8k)
- **Evaluation/observability** remains fragmented -- no single dominant open-source solution
- **Enterprise readiness** increasingly means: durable execution + human-in-the-loop + observability
- **Multi-agent** is cooling down as an architecture pattern; single-agent-with-tools and graph-based approaches are preferred for production systems
- **Agent memory and context engineering** are the new frontier -- managing what the agent knows is more impactful than how many agents you run
