# Agentic AI Courses & Educational Resources (2025-2026)

> Research compiled: 2026-03-26
> Purpose: Identify top educational resources for building production agentic AI systems

## Table of Contents

1. [Anthropic Official Guides & Courses](#1-anthropic-official-guides--courses)
2. [DeepLearning.AI Courses (Andrew Ng)](#2-deeplearningai-courses-andrew-ng)
3. [LangChain / LangGraph Courses](#3-langchain--langgraph-courses)
4. [OpenAI Agents SDK & Cookbooks](#4-openai-agents-sdk--cookbooks)
5. [Multi-Agent Framework Courses (CrewAI, AutoGen)](#5-multi-agent-framework-courses-crewai-autogen)
6. [Google Agent Development Kit (ADK)](#6-google-agent-development-kit-adk)
7. [Hugging Face Agent Courses](#7-hugging-face-agent-courses)
8. [University Courses](#8-university-courses)
9. [MCP (Model Context Protocol) Courses](#9-mcp-model-context-protocol-courses)
10. [Industry Conference Workshops](#10-industry-conference-workshops)
11. [Books](#11-books)
12. [Summary Matrix](#12-summary-matrix)
13. [Key Takeaways for Our Plugin](#13-key-takeaways-for-our-plugin)

---

## 1. Anthropic Official Guides & Courses

### Building Effective AI Agents
- **Provider:** Anthropic
- **URL:** https://www.anthropic.com/research/building-effective-agents
- **Also at:** https://resources.anthropic.com/building-effective-ai-agents
- **Format:** Guide / Research paper
- **Key Content:**
  - Architectural distinction between **workflows** (predefined code paths) and **agents** (LLM dynamically directs its own processes)
  - Most successful implementations use simple, composable patterns — not complex frameworks
  - Augmented LLMs as the basic building block: retrieval, tools, memory
  - Models actively generate search queries, select tools, determine what to retain
- **Coverage:** Tool use, planning, memory, evaluation patterns
- **Key Takeaway:** Keep it simple. Composable patterns beat complex frameworks. The LLM IS the orchestrator.

### Agent Capabilities API
- **Provider:** Anthropic
- **URL:** https://www.anthropic.com/news/agent-capabilities-api
- **Format:** Documentation / API guide
- **Key Content:**
  - Code execution tool
  - MCP connector for external system integration
  - Files API for cross-session file access
  - Prompt caching for up to 1 hour
  - Works with Claude Opus 4 and Sonnet 4
- **Coverage:** Tool use, production infrastructure
- **Key Takeaway:** Four production building blocks for agent systems: code execution, MCP, files, caching.

### Agent Skills (Open Standard)
- **Provider:** Anthropic
- **URL:** https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills
- **Format:** Engineering blog / Open standard
- **Key Content:**
  - Organized folders of instructions, scripts, and resources
  - Agents discover and load skills dynamically
  - Transform general-purpose agents into specialized ones
  - Composable resource packaging
- **Coverage:** Tool use, planning, modular agent design
- **Key Takeaway:** Skills as composable, discoverable instruction packages — relevant to our plugin's agent tool architecture.

### Building Effective Agents — Workflow Patterns (Detailed)
- **Provider:** Anthropic
- **URL:** https://www.anthropic.com/research/building-effective-agents
- **Five Workflow Patterns:**
  1. **Prompt Chaining** — task decomposed into sequential steps, with programmatic checks between
  2. **Routing** — classify input, direct to specialized handler
  3. **Parallelization** — LLMs work simultaneously; two variants: sectioning (independent subtasks) and voting (same task, diverse outputs)
  4. **Orchestrator-Workers** — central LLM breaks down tasks, delegates to workers, synthesizes results
  5. **Evaluator-Optimizer** — iterative refinement with clear evaluation criteria
- **Key Takeaway:** Start simple (prompt chaining), increase complexity only when needed. Our ReAct loop is most like Orchestrator-Workers.

### Anthropic Academy (Skilljar) — Free Courses
- **Provider:** Anthropic
- **URL:** https://anthropic.skilljar.com/
- **Format:** Self-paced, free, with completion certificates
- **Available Courses:**
  - **Introduction to Agent Skills** — Build, configure, and share reusable Skills in Claude Code; create individual Skills, distribute via plugins, deploy organization-wide
  - **Introduction to Subagents** — Use and create sub-agents in Claude Code; manage context, delegate tasks, build specialized workflows. Covers when delegation is worth it
  - **Introduction to Model Context Protocol** — Build MCP servers and clients from scratch; master tools, resources, and prompts primitives
  - **MCP: Advanced Topics** — Sampling, notifications, file system access, transport mechanisms for production MCP servers
  - **Building with the Claude API** — Technical training for integrating Claude into applications
  - **Claude Code in Action** — Hands-on training for development workflows
- **Coverage:** Tool use, sub-agents, MCP, production patterns
- **Key Takeaway:** Free, official Anthropic training. The subagents and agent skills courses are directly relevant to our delegate_task architecture.

---

## 2. DeepLearning.AI Courses (Andrew Ng)

### Agentic AI (Flagship Course)
- **Provider:** DeepLearning.AI (Andrew Ng)
- **URL:** https://www.deeplearning.ai/courses/agentic-ai/
- **Format:** Self-paced online course (7+ hours, 5 modules)
- **Cost:** Free
- **Key Content:**
  - Four agentic design patterns:
    1. **Reflection** — agent examines its own output and improves it
    2. **Tool Use** — LLM decides which functions to call
    3. **Planning** — LLM breaks tasks into sub-tasks
    4. **Multi-Agent Collaboration** — multiple specialized agents for complex tasks
  - Vendor-neutral, raw Python (no framework abstraction)
  - Production deployment and evaluation
  - Robust testing frameworks and systematic error analysis
- **Coverage:** Tool use, planning, multi-agent, evaluation, reflection
- **Key Takeaway:** The definitive free course. Vendor-neutral approach means concepts transfer directly. The four patterns (reflection, tool use, planning, multi-agent) map well to our agent architecture.

### Agent Memory: Building Memory-Aware Agents
- **Provider:** DeepLearning.AI
- **URL:** https://www.deeplearning.ai/courses/ (check for latest listing)
- **Format:** Short course
- **Key Content:**
  - Memory architectures for agents
  - Building agents that maintain context across interactions
- **Coverage:** Memory, context management
- **Key Takeaway:** Memory is a critical gap in many agent implementations — this fills it.

### Agent Skills with Anthropic
- **Provider:** DeepLearning.AI + Anthropic
- **URL:** https://www.deeplearning.ai/short-courses/agent-skills-with-anthropic/
- **Instructor:** Elie Schoppik (Anthropic)
- **Key Content:** Equip agents with expert on-demand knowledge; enable reliable coding, research, and data analysis
- **Coverage:** Tool use, skills architecture, agent specialization

### Building Code Agents with Hugging Face smolagents
- **Provider:** DeepLearning.AI + Hugging Face
- **URL:** https://learn.deeplearning.ai/courses/building-code-agents-with-hugging-face-smolagents/
- **Instructors:** Thomas Wolf (HF co-founder), Aymeric Roucher
- **Key Content:** Build code agents that write and execute code
- **Coverage:** Tool use, code generation, agent execution

### Building Coding Agents with Tool Execution
- **Provider:** DeepLearning.AI + E2B
- **URL:** https://www.deeplearning.ai/courses/
- **Key Content:** Create agents writing and executing code safely in sandboxed environments
- **Coverage:** Tool use, sandboxed execution, safety

### Building and Evaluating Data Agents
- **Provider:** DeepLearning.AI + Snowflake
- **Key Content:** Build multi-agent systems connecting to data sources; agent evaluation
- **Coverage:** Multi-agent, evaluation, data integration

### Nvidia's NeMo Agent Toolkit: Making Agents Reliable
- **Provider:** DeepLearning.AI + Nvidia
- **Key Content:** Deploy agents using observability, evaluation, and deployment tools
- **Coverage:** Production deployment, observability, evaluation

### A2A: The Agent2Agent Protocol
- **Provider:** DeepLearning.AI + Google Cloud + IBM Research
- **Key Content:** Connect agents using an open protocol standardizing agent-to-agent communication
- **Coverage:** Multi-agent, protocols, interoperability

### Governing AI Agents
- **Provider:** DeepLearning.AI + Databricks
- **Key Content:** Integrate data governance into agent workflows for safe data handling
- **Coverage:** Governance, safety, production deployment

### Semantic Caching for AI Agents
- **Provider:** DeepLearning.AI + Redis
- **Key Content:** Speed up agents by reusing responses based on meaning rather than exact text
- **Coverage:** Performance optimization, caching, production patterns

### MCP: Build Rich-Context AI Apps with Anthropic
- **Provider:** DeepLearning.AI + Anthropic
- **URL:** https://www.deeplearning.ai/short-courses/mcp-build-rich-context-ai-apps-with-anthropic/
- **Key Content:** Build rich-context AI applications using Model Context Protocol
- **Coverage:** MCP, tool use, context management

### Additional Short Courses (Agent-Related)
- **Multi AI Agent Systems with crewAI** — taught by Joao Moura (crewAI founder)
- **Design, Develop, and Deploy Multi-Agent Systems with CrewAI** — production multi-agent with tools, memory, scaling
- **AI Agents in LangGraph** — practical LangGraph implementation
- **Functions, Tools and Agents with LangChain**
- **Building Live Voice Agents with Google's ADK** — real-time voice agents
- Available at: https://www.deeplearning.ai/courses/

---

## 3. LangChain / LangGraph Courses

### LangChain Academy: Introduction to LangGraph
- **Provider:** LangChain (Official)
- **URL:** https://academy.langchain.com/courses/intro-to-langgraph
- **Format:** Self-paced online course
- **Key Content:**
  - LangGraph fundamentals for agent orchestration
  - Adding precision and control to agentic workflows
  - Graph-based workflow modeling
- **Coverage:** Tool use, planning, workflow orchestration
- **Key Takeaway:** Official source — best for understanding LangGraph's graph-based agent patterns.

### Agentic AI with LangChain and LangGraph (Coursera/IBM)
- **Provider:** IBM via Coursera
- **URL:** https://www.coursera.org/learn/agentic-ai-with-langchain-and-langgraph
- **Format:** 3-week hands-on course
- **Key Content:**
  - Building agentic AI systems
  - Self-improving agents using Reflection, Reflexion, and ReAct architectures
  - Agentic RAG systems
- **Coverage:** Tool use, planning, reflection, RAG
- **Key Takeaway:** Covers ReAct (our chosen pattern), Reflection, and Reflexion — directly applicable.

### Agentic AI Engineer with LangChain and LangGraph (Udacity)
- **Provider:** Udacity
- **URL:** https://www.udacity.com/course/agentic-ai-engineer-with-langchain-and-langgraph--nd901
- **Format:** Nanodegree program
- **Key Content:**
  - Turn LLM applications into autonomous agents
  - Multi-tool planning
  - Self-critique loops
  - Multi-agent collaboration
- **Coverage:** Tool use, planning, multi-agent, evaluation
- **Key Takeaway:** Comprehensive nanodegree covering the full agent engineering lifecycle.

### The Complete LangChain, LangGraph, & LangSmith Course (Udemy, 2026)
- **Provider:** Udemy
- **URL:** https://www.udemy.com/course/the-complete-langchain-langgraph-langsmith-course/
- **Format:** Video course (re-recorded 2026, LangChain v1.2+)
- **Key Content:**
  - Full LangChain ecosystem (LangChain + LangGraph + LangSmith)
  - Observability and tracing with LangSmith
  - Production deployment
- **Coverage:** Tool use, planning, observability, evaluation
- **Key Takeaway:** Includes LangSmith for tracing/observability — critical for production agent systems.

---

## 4. OpenAI Agents SDK & Cookbooks

### OpenAI Agents SDK Documentation
- **Provider:** OpenAI
- **URL:** https://developers.openai.com/api/docs/guides/agents-sdk
- **Python SDK:** https://openai.github.io/openai-agents-python/
- **Format:** Official documentation
- **Key Content:**
  - Three core primitives: **Agents** (LLMs + instructions + tools), **Handoffs** (agent-to-agent delegation), **Guardrails** (input/output validation)
  - Production-ready upgrade of Swarm
  - Provider-agnostic (supports non-OpenAI models)
  - Open-source Python and TypeScript SDKs
- **Coverage:** Tool use, multi-agent (handoffs), guardrails
- **Key Takeaway:** Minimal abstraction philosophy. Three primitives (agents, handoffs, guardrails) is a clean mental model.

### OpenAI Agents Cookbook
- **Provider:** OpenAI
- **URL:** https://developers.openai.com/cookbook/topic/agents
- **Format:** Code examples and tutorials
- **Key Examples (2025-2026):**
  - **Context Engineering for Personalization** — state management with long-term memory (Jan 2026)
  - **Build a coding agent with GPT 5.1** (Nov 2025)
  - **Building Consistent Workflows with Codex CLI & Agents SDK** (Sep 2025)
  - **Deep Research API with Agents SDK** — multi-step research agents
- **Coverage:** Tool use, memory, planning, production patterns
- **Key Takeaway:** The context engineering cookbook entry is directly relevant to our agent's context management. Deep Research API shows multi-step agent patterns.

---

## 5. Multi-Agent Framework Courses (CrewAI, AutoGen)

### Multi AI Agent Systems with crewAI (DeepLearning.AI/Coursera)
- **Provider:** DeepLearning.AI via Coursera
- **URL:** https://www.coursera.org/projects/multi-ai-agent-systems-with-crewai
- **Instructor:** João Moura (crewAI founder/CEO)
- **Format:** 2-hour guided project
- **Key Content:**
  - Role-based agent orchestration (inspired by real-world team structures)
  - Agents with clearly defined roles, goals, and responsibilities
  - Task delegation and collaboration patterns
- **Coverage:** Multi-agent, planning, tool use
- **Key Takeaway:** Role-based paradigm is intuitive. Good mental model even if not using CrewAI directly.

### Agentic AI with LangGraph, CrewAI, AutoGen and BeeAI (Coursera)
- **Provider:** Coursera
- **URL:** https://www.coursera.org/learn/agentic-ai-with-langgraph-crewai-autogen-and-beeai
- **Format:** Online course
- **Key Content:**
  - Building intelligent, autonomous multi-agent systems
  - Covers four frameworks: LangGraph, CrewAI, BeeAI, AG2 (formerly AutoGen)
  - Framework comparison and selection criteria
- **Coverage:** Multi-agent, tool use, planning, framework comparison
- **Key Takeaway:** Best single course for understanding multiple frameworks and when to use each.

### AI Engineer Agentic Track (Udemy)
- **Provider:** Udemy
- **URL:** https://www.udemy.com/course/the-complete-agentic-ai-engineering-course/
- **Format:** Intensive course
- **Key Content:**
  - 8 real-world projects
  - Covers OpenAI Agents SDK, CrewAI, LangGraph, AutoGen, and MCP
  - 6-week program
- **Coverage:** Tool use, multi-agent, MCP, production deployment
- **Key Takeaway:** Hands-on projects across multiple frameworks — good for practical experience.

### Framework Comparison Resources
- **DataCamp Tutorial:** https://www.datacamp.com/tutorial/crewai-vs-langgraph-vs-autogen
- **Key Differences:**
  - **CrewAI** — Role-based model (organizational metaphor)
  - **LangGraph** — Graph-based workflow (precise control)
  - **AutoGen/AG2** — Conversational collaboration (agents chat)

---

## 6. Google Agent Development Kit (ADK)

### Google ADK Documentation
- **Provider:** Google
- **URL:** https://google.github.io/adk-docs/
- **GitHub:** https://github.com/google/adk-python
- **Format:** Official documentation + open-source SDK
- **Key Content:**
  - Flexible, modular framework for developing and deploying AI agents
  - Optimized for Gemini but model-agnostic and deployment-agnostic
  - ADK Python 2.0 Alpha with graph-based workflows (2025)
  - Compatible with other frameworks
- **Coverage:** Tool use, planning, multi-agent, graph workflows
- **Key Takeaway:** Google's answer to LangGraph — graph-based agent workflows with Gemini optimization.

### Develop Agents with ADK (Google Skills)
- **Provider:** Google
- **URL:** https://www.skills.google/paths/3545
- **Format:** Learning path
- **Key Content:**
  - Environment setup and agent logic configuration
  - Adding tools and memory
  - Model Context Protocol (MCP) integration
  - Planning, reasoning, and task execution
- **Coverage:** Tool use, memory, MCP, planning

### Building AI Agents with ADK: The Foundation (Codelabs)
- **Provider:** Google
- **URL:** https://codelabs.developers.google.com/devsite/codelabs/build-agents-with-adk-foundation
- **Format:** Hands-on codelab
- **Key Content:**
  - Setting up development environment on Google Cloud
  - Defining core logic for conversational agents
  - Part of Google's Production-Ready AI program
- **Coverage:** Tool use, production deployment

---

## 7. Hugging Face Agent Courses

### Hugging Face AI Agents Course (Free)
- **Provider:** Hugging Face
- **URL:** https://huggingface.co/learn/agents-course/en/unit0/introduction
- **GitHub:** https://github.com/huggingface/agents-course
- **Format:** Free, self-paced
- **Key Content:**
  - Introduction to smolagents library (lightweight agent framework)
  - Three agent types: code agents, tool calling agents, retrieval agents
  - Agents that search data, execute code, interact with web pages
  - Introduction to agentic frameworks (comparison unit)
- **Coverage:** Tool use, code execution, retrieval, framework comparison
- **Key Takeaway:** Lightweight, code-first approach to agents. Good contrast to heavier frameworks.

### Hugging Face MCP Course (Free)
- **Provider:** Hugging Face + Anthropic
- **URL:** https://huggingface.co/learn/mcp-course/en/unit0/introduction
- **Format:** Free, self-paced
- **Key Content:** Beginner to informed in understanding, using, and building MCP applications
- **Coverage:** MCP, tool use

---

## 8. University Courses

### UC Berkeley: CS294/194-196 — Agentic AI
- **Provider:** UC Berkeley (RDI)
- **URL:** https://rdi.berkeley.edu/agentic-ai/f25
- **Format:** Graduate seminar (Fall 2025)
- **Key Content:**
  - Cutting-edge research on agentic AI systems
  - Part of Berkeley's Responsible Decentralized Intelligence initiative
- **Coverage:** Research-grade agentic AI theory and practice
- **Key Takeaway:** Academic rigor on agent architectures — good for understanding theoretical foundations.

### UC Berkeley: CS294/194-280 — Advanced Large Language Model Agents
- **Provider:** UC Berkeley (RDI)
- **URL:** https://rdi.berkeley.edu/adv-llm-agents/sp25
- **Format:** Graduate seminar (Spring 2025)
- **Key Content:**
  - Advanced LLM agent techniques
  - Research-focused
- **Coverage:** Advanced agent architectures, planning, tool use
- **Key Takeaway:** Research frontier — covers techniques not yet in industry courses.

### Stanford: CS329A — Self-Improving AI Agents
- **Provider:** Stanford
- **URL:** https://cs329a.stanford.edu/
- **Also:** https://online.stanford.edu/courses/cs329a-self-improving-ai-agents
- **Format:** Graduate course (Autumn 2025, Mon/Fri 4:30-5:50 PM)
- **Key Content:**
  - Techniques for AI agents that continuously improve themselves
  - Self-improvement loops
  - Agent evaluation
- **Coverage:** Reflection, self-improvement, evaluation
- **Key Takeaway:** Self-improvement is the frontier — agents that get better with use. Relevant to our agent's learning capabilities.

### Stanford: Agentic AI in Action (Continuing Studies)
- **Provider:** Stanford Continuing Studies
- **URL:** https://online.stanford.edu/courses/csp-xtech40-agentic-ai-action-concepts-real-world-impact
- **Format:** Professional development course
- **Key Content:**
  - Tool calling, API integration, multiagent orchestration
  - Reliability, governance, and ethics
  - Both business and technical audiences
- **Coverage:** Tool use, multi-agent, governance, production reliability
- **Key Takeaway:** Production-focused with governance angle — relevant for enterprise deployment.

### CMU: 15-482 — Autonomous Agents
- **Provider:** Carnegie Mellon University
- **URL:** http://www.cs.cmu.edu/~15482/
- **Format:** Undergraduate/Graduate course (Fall 2025)
- **Key Content:**
  - Architectures for intelligent agents
  - Autonomous behaviors
  - Perception and execution
  - Complete, integrated AI-based autonomous agents
- **Coverage:** Agent architectures, planning, perception, execution
- **Key Takeaway:** Broader agent fundamentals beyond just LLMs — perception, execution, and integration.

---

## 9. MCP (Model Context Protocol) Courses

### Anthropic: Introduction to MCP (Skilljar)
- **URL:** https://anthropic.skilljar.com/introduction-to-model-context-protocol
- **Format:** Free, self-paced with certificate
- **Key Content:** Build MCP servers and clients from scratch using Python; master tools, resources, and prompts primitives

### Anthropic: MCP Advanced Topics (Skilljar)
- **URL:** https://anthropic.skilljar.com/model-context-protocol-advanced-topics
- **Format:** Free, self-paced with certificate
- **Key Content:** Sampling, notifications, file system access, transport mechanisms for production MCP servers

### Introduction to MCP (Coursera)
- **URL:** https://www.coursera.org/learn/introduction-to-model-context-protocol
- **Format:** Coursera course
- **Key Content:** From MCP core architecture to building functional MCP servers and clients for real-world systems

### DeepLearning.AI: MCP with Anthropic
- **URL:** https://www.deeplearning.ai/short-courses/mcp-build-rich-context-ai-apps-with-anthropic/
- **Format:** Short course
- **Key Content:** Build rich-context AI apps using MCP

### Hugging Face: MCP Course
- **URL:** https://huggingface.co/learn/mcp-course/en/unit0/introduction
- **Format:** Free, self-paced
- **Key Content:** Beginner to informed in MCP understanding and building

### MCP Specification
- **URL:** https://modelcontextprotocol.io/specification/2025-11-25
- **Format:** Technical specification
- **Key Takeaway:** The canonical reference. Our plugin already uses MCP-compatible patterns with agent tools.

---

## 10. Industry Conference Workshops

### ICML 2025: Collaborative and Federated Agentic Workflows (CFAgentic)
- **URL:** https://cfagentic.github.io/
- **Date:** July 19, 2025, Vancouver Convention Center
- **Key Topics:**
  - Federated Reinforcement Learning
  - Self-Discovered Reasoning Environments
  - The Importance of Exploration for Test-Time Scaling
  - Federated AI with Flower: scaling to production
- **Coverage:** Multi-agent, federated learning, production scaling

### ICML 2025: Workshop on Computer Use Agents
- **URL:** https://icml.cc/virtual/2025/workshop/39960
- **Key Topics:**
  - Dynamic task planning and modular coordination
  - Multi-agent systems and agent interfaces
  - Safety guardrails and benchmarking tools
  - Human-agent interaction
  - Applications across healthcare and scientific research
- **Coverage:** Tool use, planning, multi-agent, safety, evaluation

### ICML 2025: Multi-Agent Systems in the Era of Foundation Models
- **URL:** https://icml.cc/virtual/2025/workshop/39955
- **Key Topics:** Opportunities, challenges, and futures of multi-agent systems with foundation models
- **Coverage:** Multi-agent, research frontier

### NeurIPS 2025: AI Assistants in the Wild
- **URL:** https://neurips.cc/virtual/2025/128671
- **Key Topics:**
  - Building generative agents that are efficient and responsive
  - Personal memory accumulation, recall, and adaptation
  - Edge device deployment for agentic systems
- **Coverage:** Memory, deployment, production agents

### ICLR 2026: Agents in the Wild — Safety, Security, and Beyond
- **URL:** https://openreview.net/pdf?id=etVUhp2igM
- **Key Topics:** Safety and security of deployed agent systems
- **Coverage:** Safety, guardrails, security

---

## 11. Books

### Designing Multi-Agent Systems — Victor Dibia
- **Key Content:** 6 orchestration patterns, 4 UX principles, builds a complete agent library from scratch, evaluation with trajectories and LLM judges, two full case studies
- **Coverage:** Multi-agent, evaluation, UX, orchestration patterns
- **Key Takeaway:** Most comprehensive book on multi-agent architecture. The orchestration patterns and evaluation approaches are directly applicable.

### AI Agents in Action (2nd Edition) — Micheal Lanham (Manning)
- **URL:** https://www.manning.com/books/ai-agents-in-action-second-edition
- **Key Content:** LLM-powered autonomy, Model Context Protocol (MCP), multi-agent collaboration, deployment
- **Coverage:** Tool use, MCP, multi-agent, production deployment
- **Key Takeaway:** Updated for MCP era — covers the full lifecycle from design to deployment.

### Agentic AI Engineering — Yi Zhou
- **Key Content:** The definitive field guide to building production-grade, enterprise-grade, and regulatory-grade AI systems; Agentic Stack, Maturity Ladder, 19 engineering practice areas
- **Coverage:** Production deployment, enterprise patterns, governance
- **Key Takeaway:** Enterprise focus with maturity model — useful for understanding where our plugin fits in organizational adoption.

### Building Agentic AI Systems — Wrick Talukdar & Anjanava Biswas
- **Key Content:** Control loops and decision architecture for production systems
- **Coverage:** Planning, control loops, production patterns
- **Key Takeaway:** Focuses on what matters most — the control loop. Directly relevant to our ReAct implementation.

### Building Applications with AI Agents
- **Key Content:** Implements scenarios in LangGraph, LangChain, and AutoGen
- **Coverage:** Tool use, multi-agent, framework comparison

### Designing Machine Learning Systems — Chip Huyen
- **Key Content:** Data pipelines, model development, testing, deployment, monitoring; iteration and feedback loops
- **Coverage:** Production systems, evaluation, monitoring
- **Key Takeaway:** Not agent-specific but essential for production ML system patterns that apply to agent deployment.

---

## 12. Summary Matrix

| Resource | Provider | Free? | Tool Use | Planning | Multi-Agent | Memory | Evaluation | Production |
|----------|----------|-------|----------|----------|-------------|--------|------------|------------|
| Building Effective Agents | Anthropic | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| Anthropic Academy (6 courses) | Anthropic | Yes | Yes | No | Yes | No | No | Yes |
| Agent Skills (DLAI) | Anthropic+DLAI | Yes | Yes | Yes | No | No | No | Yes |
| Agentic AI (Andrew Ng) | DeepLearning.AI | Yes | Yes | Yes | Yes | No | Yes | Yes |
| Agent Memory | DeepLearning.AI | Yes | No | No | No | Yes | No | No |
| NeMo Agent Toolkit | DLAI+Nvidia | Yes | No | No | No | No | Yes | Yes |
| Governing AI Agents | DLAI+Databricks | Yes | No | No | No | No | No | Yes |
| A2A Protocol | DLAI+Google+IBM | Yes | No | No | Yes | No | No | Yes |
| LangGraph Intro | LangChain Academy | Yes | Yes | Yes | No | No | No | No |
| LangChain+LangGraph (IBM) | Coursera | Audit | Yes | Yes | No | No | No | Yes |
| Agentic AI Engineer | Udacity | Paid | Yes | Yes | Yes | No | No | Yes |
| OpenAI Agents SDK | OpenAI | Yes | Yes | No | Yes | No | No | Yes |
| OpenAI Cookbook | OpenAI | Yes | Yes | Yes | No | Yes | No | Yes |
| Multi-Agent crewAI | Coursera | Audit | Yes | Yes | Yes | Yes | No | No |
| Multi-Framework Course | Coursera | Audit | Yes | Yes | Yes | No | No | No |
| Google ADK Docs | Google | Yes | Yes | Yes | Yes | Yes | No | Yes |
| Google ADK Skills Path | Google | Yes | Yes | Yes | No | Yes | No | No |
| HF Agents Course | Hugging Face | Yes | Yes | No | No | No | No | No |
| HF MCP Course | Hugging Face | Yes | Yes | No | No | No | No | No |
| Berkeley Agentic AI | UC Berkeley | Audit | Yes | Yes | Yes | Yes | Yes | No |
| Berkeley Adv LLM Agents | UC Berkeley | Audit | Yes | Yes | Yes | Yes | Yes | No |
| Stanford CS329A | Stanford | No | Yes | Yes | No | No | Yes | No |
| Stanford Agentic AI | Stanford | Paid | Yes | No | Yes | No | No | Yes |
| CMU Autonomous Agents | CMU | No | Yes | Yes | No | No | No | No |
| MCP courses (5 options) | Various | Yes | Yes | No | No | No | No | Yes |
| Designing Multi-Agent Sys. | Book | Paid | Yes | Yes | Yes | No | Yes | No |
| AI Agents in Action 2e | Book | Paid | Yes | Yes | Yes | No | No | Yes |
| Agentic AI Engineering | Book | Paid | Yes | Yes | Yes | No | Yes | Yes |

---

## 13. Key Takeaways for Our Plugin's Agent Architecture

### Validated Design Decisions

1. **ReAct single-agent loop is correct.** Anthropic's own guidance says simple composable patterns beat complex frameworks. The LLM IS the orchestrator. Our single-agent ReAct approach with 98 tools aligns with the most successful production implementations.

2. **Tool-use architecture is industry standard.** Every major course and framework centers on tool use as the primary agent capability. Our ToolResult<T> pattern with typed data and human-readable summaries maps directly to how Anthropic, OpenAI, and Google design tool interfaces.

3. **Approval gates match industry patterns.** OpenAI's three primitives (Agents, Handoffs, Guardrails) validate our approval gate architecture for dangerous operations. Multiple courses emphasize guardrails as essential for production.

### Patterns to Adopt

4. **Reflection pattern** (Andrew Ng, Coursera/IBM). Our agent could examine its own output before presenting to users. Low-cost improvement: have the agent review its plan before execution.

5. **Context engineering** is the new frontier (OpenAI cookbook, Jan 2026). State management and long-term memory differentiate production agents from demos. Our plan persistence is a good start; consider semantic caching (Redis course).

6. **Agent Skills / composable instructions** (Anthropic). Our agent's tool descriptions are effectively skills. Could evolve to dynamic skill loading based on task context.

7. **Evaluation infrastructure** (Nvidia NeMo, Stanford CS329A, Victor Dibia's book). Multiple sources emphasize systematic testing with trajectory evaluation and LLM-as-judge. We need agent evaluation beyond unit tests.

### Future Considerations

8. **Self-improvement** (Stanford CS329A) — agents that get better with use. Consider for v2.

9. **A2A Protocol** (Google/IBM) — agent-to-agent communication standard. May be relevant if we add multi-agent capabilities.

10. **MCP as the standard** — 5+ courses now teach MCP. Our tool architecture should ensure MCP compatibility for interoperability.

### Recommended Learning Path for Team

1. **Start:** Anthropic "Building Effective Agents" guide (free, 30 min read)
2. **Foundation:** Andrew Ng "Agentic AI" course (free, 7+ hours)
3. **Hands-on:** Anthropic Academy courses on Skills + Subagents (free)
4. **Deep dive:** OpenAI Cookbook — Context Engineering entry (free)
5. **Production:** Nvidia NeMo Agent Toolkit course (free, observability/evaluation)
6. **Reference:** "Designing Multi-Agent Systems" by Victor Dibia (book)
