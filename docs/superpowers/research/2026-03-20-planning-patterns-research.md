# Planning Patterns in Agentic AI Systems

**Date:** 2026-03-20
**Purpose:** Research how Claude Code, Amp (Amplified/Antigravity), Devin, OpenAI Codex, and other agentic systems handle task planning vs direct execution. Concrete architectural details for informing Phase 3 agentic AI design.

---

## Table of Contents

1. [Claude Code Planning Architecture](#1-claude-code-planning-architecture)
2. [Amp (ampcode.com) Planning Architecture](#2-amp-ampcode-planning-architecture)
3. [Devin AI Planning Architecture](#3-devin-ai-planning-architecture)
4. [OpenAI Codex Planning Architecture](#4-openai-codex-planning-architecture)
5. [Google Antigravity Planning Architecture](#5-google-antigravity-planning-architecture)
6. [The Planning Pattern Taxonomy](#6-the-planning-pattern-taxonomy)
7. [Extended Thinking as Implicit Planning](#7-extended-thinking-as-implicit-planning)
8. [Token Cost Implications](#8-token-cost-implications)
9. [Comparison Matrix](#9-comparison-matrix)
10. [Implications for Our Plugin](#10-implications-for-our-plugin)

---

## 1. Claude Code Planning Architecture

### Core Agent Loop

Claude Code uses a **single-threaded master loop** — the simplest possible agent architecture:

```
User input → messages[] → LLM → response
  ↓ (if stop_reason == "tool_use")
  Execute tool → append result → loop back to LLM
  ↓ (if stop_reason == "end_turn")
  Return text to user
```

The philosophy: "A simple, single-threaded master loop combined with disciplined tools and planning delivers controllable autonomy." No separate planner model, no multi-agent orchestration at the core level.

### Plan Mode — Explicit Planning

**Mechanism:** Plan mode is activated by pressing **Shift+Tab twice** or using `/plan`. It is NOT a separate API call — it is the same Claude model but with a modified system prompt that makes it **read-only**. Claude cannot edit files, run commands, or modify anything while in plan mode.

**How it works:**
1. User activates plan mode (Shift+Tab x2 or `/plan`)
2. System prompt is modified to add read-only constraints
3. Claude reads files, analyzes code, asks clarifying questions
4. Claude produces a markdown plan saved to `~/.claude/plans/<random-name>.md`
5. Plan persists across sessions, survives `/clear` and context compaction
6. User reviews plan, optionally modifies it
7. User exits plan mode (Shift+Tab) — Claude executes against the plan

**Key detail:** Plan mode is also a tool the agent can enter itself. The agent can decide "this is complex, I should plan first" and enter plan mode programmatically, which has the same effect as the user pressing Shift+Tab.

**Visibility:** Yes — plan is a markdown file the user can read and edit before execution.

**User modifiable:** Yes — it's just a markdown file in `~/.claude/plans/`.

### TodoWrite — Inline Task Tracking

**Mechanism:** The `TodoWrite` tool creates structured JSON task lists with IDs, content, status tracking, and priority levels. These render as interactive checklists in the terminal UI.

**System prompt instruction:** "You have access to the TodoWrite tools to help you manage and plan tasks. Use these tools VERY frequently to ensure that you are tracking your tasks and giving the user visibility into your progress."

**Reminder injection:** After tool uses, the system injects current TODO list state as system messages to prevent the model from losing track of objectives. The TodoWrite reminder system uses ~98 tokens per injection.

**Key insight:** TodoWrite is NOT a separate planning step — it is an inline tracking mechanism. The agent creates and updates todos AS it works. This is closer to "progressive planning" than "plan-then-execute."

### How Claude Code Decides: Simple vs Complex

There is no explicit complexity classifier. The decision emerges from:

1. **User instruction:** "think" / "think hard" / "think harder" / "ultrathink" triggers progressively larger thinking budgets
2. **System prompt guidance:** The prompt encourages TodoWrite for "non-trivial tasks"
3. **Plan mode is user-initiated** (Shift+Tab) — the USER decides when to plan, not the model
4. **The model CAN enter plan mode itself** if it judges the task requires it
5. **Adaptive thinking** lets the model calibrate reasoning per step automatically

### Sub-Agent Architecture

For complex tasks, Claude Code can spawn sub-agents:
- **Task tool:** Spawns a sub-agent with its own fresh context window
- **Strict depth limits:** Sub-agents cannot recursively spawn more agents
- **Parallel execution:** Multiple sub-agents can run simultaneously
- **Built-in agent types:** Plan, Explore, Task — each with specialized system prompts

### Sources
- [Understanding Claude Code Plan Mode (lord.technology)](https://lord.technology/2025/07/03/understanding-claude-code-plan-mode-and-the-architecture-of-intent.html)
- [What Actually Is Claude Code's Plan Mode? (Armin Ronacher)](https://lucumr.pocoo.org/2025/12/17/what-is-plan-mode/)
- [Claude Code Agent Architecture (ZenML)](https://www.zenml.io/llmops-database/claude-code-agent-architecture-single-threaded-master-loop-for-autonomous-coding)
- [Claude Code System Prompts (GitHub)](https://github.com/Piebald-AI/claude-code-system-prompts)
- [Plan Mode Token Bug (GitHub Issue #6586)](https://github.com/anthropics/claude-code/issues/6586)
- [TodoWrite Undocumented Behavior (GitHub Issue #6968)](https://github.com/anthropics/claude-code/issues/6968)
- [Claude Code Plan Mode (claudefast)](https://claudefa.st/blog/guide/mechanics/planning-modes)
- [Claude Code Best Practices (simonwillison.net)](https://simonwillison.net/2025/Apr/19/claude-code-best-practices/)

---

## 2. Amp (ampcode.com) Planning Architecture

### Core Approach: No Separate Plan Mode

Amp does NOT have a dedicated plan mode. Planning is done by **asking the agent to plan** in natural language.

**Mechanism:** The user says "plan how to implement X before writing any code." Amp reasons about the task, reads files, and produces a plan. Then the user says "execute the plan." There is no special UI, no plan file, no mode toggle.

**Recommended workflow for complex tasks:**
1. Ask Amp to plan and commit the plan to a temporary file
2. Start new threads for each sub-task, referencing the plan file
3. Each thread picks off one piece of the plan

### Sub-Agent Architecture

Amp's key architectural innovation is **sub-agents via the Task tool:**
- Main agent spawns sub-agents for specific tasks
- Each sub-agent has its own **fresh context window** (only a fraction of main agent's tokens used)
- Multiple sub-agents can run in parallel
- Sub-agents have full tool access (file editing, terminal commands)

### Oracle Model — Second Opinion

Amp has access to a **"second opinion" model** (currently GPT-5.4) called the **oracle**:
- Used for complex reasoning or analysis tasks
- The main agent autonomously decides when to ask the oracle
- This is NOT planning — it is a verification/reasoning step

### How Amp Decides: Simple vs Complex

No explicit classifier. The recommendation is:
- Small changes: just do them directly
- Large tasks: break into smaller sub-tasks, one per thread
- Very complex: ask for a plan first, then execute piece by piece

**Visibility:** No dedicated plan UI. Plans are just text in the conversation or files.
**User modifiable:** Only by editing the plan file or giving new instructions.

### Sources
- [Amp Code AI Review 2026 (Second Talent)](https://www.secondtalent.com/resources/amp-ai-review/)
- [Amp Owner's Manual](https://ampcode.com/manual)
- [Agents for the Agent (Amp Blog)](https://ampcode.com/notes/agents-for-the-agent)
- [Amp CLI Prompts and Tools (GitHub)](https://github.com/ben-vargas/ai-amp-cli)
- [How I use Amp after 4 months (Medium)](https://medium.com/@steph.jarmak/how-i-use-amp-after-4-months-and-6000-threads-b4058204e9de)

---

## 3. Devin AI Planning Architecture

### Compound AI System — Specialized Models

Devin is NOT a single model. It uses a **swarm of specialized models:**

| Component | Role | Model Type |
|-----------|------|------------|
| **Planner** | Analyzes request, breaks into steps, creates roadmap | High-reasoning model (GPT-6 class) |
| **Coder** | Writes code based on plan steps | Code-specialized model |
| **Critic** | Reviews code for security/logic errors before execution | Adversarial review model |
| **Browser** | Scrapes and synthesizes documentation from the web | Web navigation agent |

### Planning Mechanism

**Separate dedicated planning step:**
1. User gives task in natural language
2. **Planner model** analyzes the request and breaks it into a step-by-step plan
3. Plan is shown to the user for review/modification
4. User approves or modifies the plan
5. **Coder model** executes each step
6. **Critic model** reviews each output
7. If blocked → **dynamic re-planning** (Planner re-engages without human intervention)

**Key insight:** Devin's planning IS a separate API call — a separate model specialized for planning. This is fundamentally different from Claude Code where the same model plans and executes.

**Visibility:** Yes — plan is shown to the user before execution begins.
**User modifiable:** Yes — user can modify the plan before letting Devin work autonomously.

### Sources
- [Devin 2.0 (Cognition)](https://cognition.ai/blog/devin-2)
- [Devin AI Complete Guide (Digital Applied)](https://www.digitalapplied.com/blog/devin-ai-autonomous-coding-complete-guide)
- [Devin 2.0 Explained (Analytics Vidhya)](https://www.analyticsvidhya.com/blog/2025/04/devin-2-0/)

---

## 4. OpenAI Codex Planning Architecture

### Native Plan Mode

Codex includes a **native plan mode** in the app, CLI, and IDE extension.

**Mechanism:**
1. Codex receives a task
2. It breaks the task into a clear, reviewable sequence of steps
3. Plan is presented to the user for review
4. Execution proceeds step by step with verification at each checkpoint

### PLANS.md — Persistent Plan Files

Codex treats plans as **first-class artifacts:**
- **Ephemeral plans:** Lightweight, for small changes (not persisted)
- **Execution plans:** For complex work — include progress logs and decision logs
- **Checked into the repository** alongside code
- Three categories: active plans, completed plans, known technical debt
- Agents start with a small, stable entry point (progressive disclosure)

### AGENTS.md — Configuration Files

Codex reads `AGENTS.md` files before doing any work:
- Global guidance + project-specific overrides
- Provides operating parameters and custom instructions
- Ensures consistent expectations across tasks

### Multi-Step Execution Pattern

GPT-5.3-Codex follows: **plan -> implement -> validate -> repair**

The planning architecture emphasizes structured context (repo metadata, file tree, diffs, command outputs) and enforces a disciplined "done when" routine for reliable long-horizon task execution.

**Visibility:** Yes — plans are reviewable artifacts.
**User modifiable:** Yes — plans are markdown files that can be edited.

### Sources
- [Introducing Codex (OpenAI)](https://openai.com/index/introducing-codex/)
- [Using PLANS.md for Multi-Hour Problem Solving (OpenAI Cookbook)](https://cookbook.openai.com/articles/codex_exec_plans)
- [Custom Instructions with AGENTS.md (OpenAI)](https://developers.openai.com/codex/guides/agents-md)
- [Run Long Horizon Tasks with Codex (OpenAI Cookbook)](https://developers.openai.com/cookbook/examples/codex/long_horizon_tasks)

---

## 5. Google Antigravity Planning Architecture

### Agent-First Philosophy

Antigravity (launched November 2025) is built on an **"Agent-First" philosophy** where the AI is an autonomous actor capable of planning, executing, validating, and iterating.

### Mission Control Interface

- **Unified inbox** where all agent communications flow
- Developer acts as **"Architect" or "Mission Controller"** — issues directives, not code
- **Multiple agents** can be assigned to separate workspaces for parallel development
- Tasks span frontend, backend, database, and testing simultaneously

### Planning Pattern

Built on Gemini 3 model family. The developer issues high-level directives (e.g., "Refactor the login flow to support OAuth") and the agent breaks this down internally. Limited public details on the exact planning mechanism, but the architecture emphasizes agent autonomy with human oversight.

### Sources
- [Google Antigravity IDE (Sigosoft)](https://sigosoft.com/blog/google-antigravity-ide/)
- [Antigravity Agent Manager Guide (AI Fire)](https://www.aifire.co/p/mastering-the-antigravity-agent-manager-2026-guide-part-1)
- [Antigravity Agent-First Architecture (VentureBeat)](https://venturebeat.com/orchestration/google-antigravity-introduces-agent-first-architecture-for-asynchronous)

---

## 6. The Planning Pattern Taxonomy

### ReAct (Reasoning and Acting) — Interleaved

```
Loop:
  1. Reason: Analyze current state, decide next action
  2. Act: Execute one tool call
  3. Observe: Process result
  4. Repeat until done
```

**Planning:** No upfront plan. Planning happens incrementally — one step at a time.
**Token cost:** HIGH — the entire conversation history is re-sent to the LLM at each step. Redundant tokens accumulate.
**Strength:** Can adapt to unexpected results at every step.
**Weakness:** Expensive, can lose track of the overall goal, prone to "wandering."

**Who uses this:** Claude Code (default mode), Amp, most coding agents.

### ReWOO (Reasoning WithOut Observation) — Plan-First

```
1. Planner: Generate complete plan with tool calls and placeholders
2. Worker: Execute all tools in sequence, filling in placeholders
3. Solver: Integrate results and produce final answer
```

**Planning:** Complete upfront plan generated in ONE LLM call.
**Token cost:** LOW — 5-10x fewer tokens than ReAct. One planning call, then execution without LLM re-invocation.
**Strength:** Extremely efficient. Great for well-defined tasks.
**Weakness:** Cannot adapt mid-execution. If step 3 depends on an unexpected result from step 2, the plan breaks.

**Who uses this:** Research implementations. No major coding agent uses pure ReWOO.

### Plan-and-Execute (Hybrid)

```
1. Plan: Generate high-level plan
2. Execute: For each step, use ReAct to handle the details
3. Re-plan: If execution deviates, re-plan remaining steps
```

**Planning:** Upfront plan with dynamic re-planning.
**Token cost:** MEDIUM — one planning call + per-step ReAct (but scoped to individual steps).
**Strength:** Balances efficiency with adaptability.
**Weakness:** More complex orchestration logic.

**Who uses this:** Devin (planner + coder), OpenAI Codex (plan mode), Claude Code (plan mode + execution).

### Key Comparison

| Pattern | Planning Cost | Execution Cost | Adaptability | Complexity |
|---------|--------------|----------------|--------------|------------|
| ReAct | None (incremental) | Very High | Excellent | Low |
| ReWOO | One LLM call | Minimal (no LLM) | None | Medium |
| Plan-and-Execute | One LLM call | Medium | Good (re-plan) | High |

### Sources
- [ReAct vs Plan-and-Execute (DEV Community)](https://dev.to/jamesli/react-vs-plan-and-execute-a-practical-comparison-of-llm-agent-patterns-4gh9)
- [Navigating Modern LLM Agent Architectures (Wollen Labs)](https://www.wollenlabs.com/blog-posts/navigating-modern-llm-agent-architectures-multi-agents-plan-and-execute-rewoo-tree-of-thoughts-and-react)
- [ReWOO Agent Pattern (agent-patterns docs)](https://agent-patterns.readthedocs.io/en/stable/patterns/rewoo.html)
- [Agentic AI Design Patterns (Capabl)](https://capabl.in/blog/agentic-ai-design-patterns-react-rewoo-codeact-and-beyond)
- [What is ReWOO? (IBM)](https://www.ibm.com/think/topics/rewoo)
- [What is AI Agent Planning? (IBM)](https://www.ibm.com/think/topics/ai-agent-planning)
- [Agentic AI Planning Pattern (Analytics Vidhya)](https://www.analyticsvidhya.com/blog/2024/11/agentic-ai-planning-pattern/)

---

## 7. Extended Thinking as Implicit Planning

### Three Distinct Mechanisms

Anthropic provides THREE mechanisms for "thinking," each serving a different purpose:

#### 1. Extended Thinking (Thinking Blocks)

**What it is:** Before generating any visible response, Claude performs internal chain-of-thought reasoning. This is the "planning step" that happens BEFORE the first tool call or text output.

**API parameter:** `thinking: { type: "enabled", budget_tokens: N }` or `thinking: { type: "adaptive" }`

**Planning role:** This IS implicit planning. Claude analyzes the problem, considers alternatives, and formulates an approach before committing to action. For coding tasks, this is where Claude decides "I need to read these 3 files, understand the architecture, then modify file X."

**Cost:** Thinking tokens are billed at the same rate as output tokens. Budget can range from 1,024 to 128,000+ tokens.

**Trigger words in Claude Code:** "think" < "think hard" < "think harder" < "ultrathink" — each maps to increasing budget.

#### 2. Adaptive Thinking (Interleaved Thinking)

**What it is:** Claude dynamically determines how much to think at each step, including BETWEEN tool calls. This is the most important mode for agentic workflows.

**API parameter:** `thinking: { type: "adaptive" }`

**Planning role:** This enables RE-planning. After getting tool results, Claude thinks again before the next action. This is the agentic equivalent of Plan-and-Execute — plan a bit, execute, think about results, plan the next step.

**Key advantage:** "Adaptive thinking automatically enables interleaved thinking, making it especially effective for agentic workflows."

**Cost:** Variable — Claude calibrates reasoning per step. Less thinking for simple steps, more for complex ones.

#### 3. The "Think" Tool

**What it is:** A tool Claude can call to pause and reason about information gathered so far. It creates a designated scratchpad space for structured reasoning.

**When to use:** Long chains of tool calls, policy-heavy environments, sequential decisions where mistakes are costly.

**Planning role:** This is MID-EXECUTION planning. After gathering data through tool calls, Claude can call the think tool to consolidate understanding before proceeding.

**Cost:** Minimal — just the tokens in the think tool's input/output.

### Which Replaces a Separate Planning Call?

**For our plugin's agentic system, the answer is: Adaptive Thinking replaces a separate planning call for most cases.**

- Simple tasks: Extended thinking (pre-response) handles it
- Complex multi-step tasks: Adaptive thinking (interleaved) handles plan-execute-replan
- Very complex tasks with policy constraints: Think tool provides structured reasoning points

A separate planning API call (like Devin's dedicated planner model) is only needed if:
1. The plan needs to be shown to the user for approval before ANY execution
2. The plan needs to persist across sessions
3. The planning model is different from the execution model

### Sourcegraph Cody API and Extended Thinking

Sourcegraph's Cody API supports extended thinking through standard Anthropic API parameters. The agentic chat mode uses a sub-agent architecture where Deep Search spawns a dedicated sub-agent for finding relevant files, which saves tokens in the main agent's context window. However, specific `budget_tokens` configuration for Cody Enterprise's API is not publicly documented — it likely follows standard Anthropic API parameters since Cody Enterprise uses Claude models directly.

### Sources
- [Adaptive Thinking (Anthropic Docs)](https://platform.claude.com/docs/en/build-with-claude/adaptive-thinking)
- [Extended Thinking Tips (Anthropic Docs)](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/extended-thinking-tips)
- [Building with Extended Thinking (Anthropic Docs)](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)
- [The "Think" Tool (Anthropic Engineering Blog)](https://www.anthropic.com/engineering/claude-think-tool)
- [Claude Opus 4.6 Announcement (Anthropic)](https://www.anthropic.com/news/claude-opus-4-6)
- [Claude Code Extended Thinking Mode (CallSphere)](https://callsphere.tech/blog/claude-code-extended-thinking-mode)

---

## 8. Token Cost Implications

### Planning Approaches Compared by Token Cost

| Approach | Planning Tokens | Execution Overhead | Total for 10-Step Task |
|----------|----------------|-------------------|----------------------|
| No planning (pure ReAct) | 0 | ~5K per step (history re-sent) | ~50K |
| Extended thinking (implicit) | 4K-32K (one-time) | ~5K per step | 54K-82K |
| Plan mode (explicit markdown) | 10K-20K (one-time) | ~3K per step (focused) | 40K-50K |
| TodoWrite (progressive) | ~100 tokens per update | ~5K per step | ~51K |
| ReWOO (full upfront plan) | 5K-15K (one-time) | ~0 (no LLM) | 5K-15K |
| Separate planner model (Devin) | 5K-15K (planner) | ~3K per step (coder) | 35K-45K |

### Claude Code Specific Costs

- **Plan mode halves token consumption** for review/analysis sessions (38K -> 18K reported)
- **Agent teams use ~7x more tokens** because each teammate has its own context window
- **TodoWrite reminder injection:** ~98 tokens per tool call (small but adds up)
- **Adaptive thinking:** Variable cost, but generally more efficient than fixed extended thinking because it only thinks deeply when needed

### Cost Optimization Strategies

1. **Use plan mode for complex tasks** — it forces focused file reading, reducing context
2. **Sub-agents for isolation** — fresh context windows avoid bloated histories
3. **Adaptive thinking over fixed budget** — let the model decide how much to think
4. **Progressive planning (TodoWrite)** — cheapest approach, but least structured

---

## 9. Comparison Matrix

| Feature | Claude Code | Amp | Devin | OpenAI Codex | Antigravity |
|---------|------------|-----|-------|--------------|-------------|
| **Planning mechanism** | Plan mode + TodoWrite + thinking | Natural language + Task tool | Dedicated planner model | Native plan mode + PLANS.md | Agent-first directives |
| **Separate planning call?** | No (same model, different prompt) | No | Yes (separate model) | Optional (plan mode) | Unknown |
| **Plan visible to user?** | Yes (markdown file) | Yes (conversation text) | Yes (step-by-step UI) | Yes (PLANS.md file) | Yes (Mission Control) |
| **Plan editable by user?** | Yes (edit file) | Yes (new instructions) | Yes (before execution) | Yes (edit file) | Unknown |
| **Dynamic re-planning?** | Yes (adaptive thinking) | Yes (new threads) | Yes (automatic) | Yes | Yes |
| **Sub-agents?** | Yes (Task/Plan/Explore) | Yes (Task tool + oracle) | Yes (Planner/Coder/Critic/Browser) | Limited | Yes (multi-agent workspaces) |
| **Simple/complex classifier?** | No (user-triggered or model intuition) | No (user decides) | Implicit (planner always runs) | Optional | Unknown |
| **Pattern** | ReAct + optional Plan-and-Execute | ReAct + optional planning | Plan-and-Execute (always) | Plan-and-Execute (optional) | Agent-first (always plans) |
| **Planning token cost** | Low-Medium | Low | Medium (separate model) | Low-Medium | Unknown |

---

## 10. Implications for Our Plugin

### Key Takeaways for Phase 3 Agentic AI

#### 1. Planning Should Be Adaptive, Not Mandatory

Every major tool except Devin lets the agent decide whether to plan. Forcing a planning step on simple tasks wastes tokens and time. Our approach should:
- **Simple tasks** (fix this Sonar issue, generate commit message): Direct execution with extended thinking
- **Medium tasks** (review PR, analyze coverage gaps): Adaptive thinking with TodoWrite-style progress tracking
- **Complex tasks** (refactor module, fix CVE chain): Explicit plan shown to user before execution

#### 2. Extended Thinking IS the Planning Step for Most Cases

We do NOT need a separate planning API call. Anthropic's adaptive thinking already provides:
- Pre-response planning (extended thinking)
- Mid-execution re-planning (interleaved thinking)
- Structured reasoning checkpoints (think tool)

The only case for a separate planning call is when the user MUST approve a plan before any execution begins (destructive operations, large refactors).

#### 3. Plans Should Be Persistent Artifacts

Both Claude Code and OpenAI Codex persist plans as files. For our plugin:
- Store plans in project-local storage (not just conversation context)
- Plans survive IDE restart
- Plans can be referenced by sub-agents
- Plans provide audit trail for what the agent did and why

#### 4. Sub-Agent Pattern is Essential for Token Efficiency

Every tool uses sub-agents for complex tasks. Fresh context windows are critical:
- Main orchestrator holds the plan and tracks progress
- Workers get only the context they need for their specific step
- This prevents context window bloat on long-running tasks

#### 5. The Think Tool is Underutilized

Most tools don't expose the think tool concept, but Anthropic's research shows it significantly improves decision quality in multi-step tool chains. Our plugin should:
- Include a think tool in the agent's toolset
- Use it before destructive operations (file edits, API calls)
- Use it after gathering diagnostic data (Sonar results, build logs)

#### 6. User Control Points

The pattern across all tools:
- User can ASK for a plan (explicit)
- User can REVIEW the plan before execution
- User can MODIFY the plan
- User can APPROVE execution
- Agent can re-plan autonomously during execution

Our plugin should implement all five control points, with the default being "plan for complex tasks, execute directly for simple ones."
