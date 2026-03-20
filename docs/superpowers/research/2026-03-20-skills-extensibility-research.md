# Skills & Extensibility Research: How Agentic AI Tools Enable User-Defined Skills

**Date:** 2026-03-20
**Purpose:** Research how enterprise-grade agentic AI tools implement user-extensible "skills" or "plugins" that let users add custom capabilities to the agent.
**Status:** Complete

---

## Table of Contents

1. [Claude Code — Skills & Subagents](#1-claude-code--skills--subagents)
2. [Cursor — Rules & Custom Commands](#2-cursor--rules--custom-commands)
3. [Devin — Playbooks & Skills](#3-devin--playbooks--skills)
4. [Google Antigravity — Workflows & Skills](#4-google-antigravity--workflows--skills)
5. [OpenAI Agents SDK — Agent Composition](#5-openai-agents-sdk--agent-composition)
6. [MCP — Universal Tool Extension](#6-mcp--universal-tool-extension)
7. [Windsurf — Rules & Workflows](#7-windsurf--rules--workflows)
8. [Cross-Cutting Design Patterns](#8-cross-cutting-design-patterns)
9. [Implications for Our IntelliJ Plugin](#9-implications-for-our-intellij-plugin)

---

## 1. Claude Code — Skills & Subagents

Claude Code has the most mature and well-documented skill system. It follows the open [Agent Skills](https://agentskills.io) standard (released Dec 2025), which OpenAI also adopted for Codex CLI and ChatGPT.

### 1.1 Skills (SKILL.md)

**Format:** Markdown file with YAML frontmatter.

**File Structure:**
```
my-skill/
  SKILL.md           # Main instructions (required)
  template.md        # Template for Claude to fill in (optional)
  examples/
    sample.md        # Example output (optional)
  scripts/
    validate.sh      # Script Claude can execute (optional)
```

**YAML Frontmatter Fields (all optional, description recommended):**

| Field | Description |
|-------|-------------|
| `name` | Display name, becomes `/slash-command`. Lowercase, hyphens, max 64 chars. |
| `description` | What the skill does. Claude uses this for auto-discovery. |
| `argument-hint` | Autocomplete hint, e.g. `[issue-number]`. |
| `disable-model-invocation` | `true` = only user can invoke (no auto-trigger). For side-effect workflows like `/deploy`. |
| `user-invocable` | `false` = only Claude can invoke. For background knowledge, not meaningful as user command. |
| `allowed-tools` | Tools Claude can use without permission when skill is active (e.g., `Read, Grep, Glob`). |
| `model` | Override model when skill is active. |
| `context` | `fork` = run in isolated subagent context. |
| `agent` | Which subagent type when `context: fork` (e.g., `Explore`, `Plan`, custom). |
| `hooks` | Lifecycle hooks scoped to this skill. |

**Discovery Mechanism:**
- Skill descriptions are always loaded into context (budget: 2% of context window, fallback 16K chars).
- Full skill content loads only when invoked.
- Discovery is description-based: Claude reads descriptions and decides relevance.
- Auto-discovery from nested `.claude/skills/` directories (monorepo support).

**Invocation Modes:**
1. **User invokes:** Type `/skill-name` or `/skill-name args`.
2. **Claude auto-invokes:** When conversation matches skill description.
3. **Combined (default):** Both user and Claude can invoke.

**String Substitutions:** `$ARGUMENTS`, `$ARGUMENTS[N]`, `$N`, `${CLAUDE_SESSION_ID}`, `${CLAUDE_SKILL_DIR}`.

**Dynamic Context Injection:** `` !`command` `` syntax runs shell commands before skill prompt is sent to Claude. Output replaces the placeholder.

**Skill Scoping:**

| Level | Path | Applies To |
|-------|------|------------|
| Enterprise | Managed settings | All org users |
| Personal | `~/.claude/skills/<name>/SKILL.md` | All your projects |
| Project | `.claude/skills/<name>/SKILL.md` | This project only |
| Plugin | `<plugin>/skills/<name>/SKILL.md` | Where plugin enabled |

Priority: enterprise > personal > project.

### 1.2 Subagents (.claude/agents/)

Subagents are **distinct from skills**. While skills are instructions/prompts, subagents are isolated execution environments with their own context window.

**Key Differences:**

| Aspect | Skill | Subagent |
|--------|-------|----------|
| Context | Runs inline (or fork with `context: fork`) | Always isolated context window |
| System prompt | Inherits Claude Code system prompt | Custom system prompt (markdown body) |
| Format | `SKILL.md` in skills directory | Markdown in `agents/` directory |
| Spawning | N/A | Cannot spawn other subagents (no nesting) |
| Memory | No persistence | Optional persistent memory across sessions |

**Subagent Frontmatter Fields:**

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Unique identifier |
| `description` | Yes | When to delegate |
| `tools` | No | Allowlist of tools (inherits all if omitted) |
| `disallowedTools` | No | Denylist (removed from inherited set) |
| `model` | No | `sonnet`, `opus`, `haiku`, full ID, or `inherit` |
| `permissionMode` | No | `default`, `acceptEdits`, `dontAsk`, `bypassPermissions`, `plan` |
| `maxTurns` | No | Max agentic turns |
| `skills` | No | Skills to preload into subagent context |
| `mcpServers` | No | MCP servers scoped to this subagent |
| `hooks` | No | Lifecycle hooks |
| `memory` | No | Persistent memory: `user`, `project`, or `local` |
| `background` | No | `true` = run as background task |
| `effort` | No | `low`, `medium`, `high`, `max` |
| `isolation` | No | `worktree` = isolated git worktree |

**Context Isolation:** Each subagent gets its own fresh 200K-token context. Intermediate tool calls stay inside; only final message returns to parent. This is the primary value proposition — keeping verbose output out of main conversation.

**Built-in Subagents:** Explore (Haiku, read-only), Plan (inherited model, read-only), General-purpose (inherited model, all tools).

**Invocation:** Automatic (Claude decides), @-mention (`@"code-reviewer (agent)"`), or session-wide (`claude --agent code-reviewer`).

**Resumability:** Subagents can be resumed with full conversation history using `SendMessage` with the agent's ID.

### 1.3 Key Insight: Skills + Subagents Compose

Skills and subagents work together bidirectionally:
- **Skill with `context: fork`:** Skill content becomes the task, agent type provides execution environment.
- **Subagent with `skills` field:** Subagent controls system prompt, loads skill content as reference.

---

## 2. Cursor — Rules & Custom Commands

### 2.1 Rules System (.cursor/rules/)

**Format:** Markdown (`.md`) or MDC (`.mdc`) files with optional YAML frontmatter.

**Frontmatter Fields:**
- `description` — What the rule is about (used for auto-activation).
- `globs` — File path patterns (e.g., `src/components/**/*.tsx`).
- `alwaysApply` — Boolean, force-apply to every session.

**Four Activation Modes:**

| Mode | Trigger |
|------|---------|
| Always Apply | `alwaysApply: true` — every chat session |
| Apply Intelligently | Agent evaluates description for relevance |
| Apply to Specific Files | When file matches glob patterns |
| Apply Manually | User @-mentions the rule |

**AGENTS.md:** Plain markdown file (no metadata) placed in project root or subdirectories. Nested AGENTS.md files apply hierarchically — more specific directories take precedence.

**Precedence:** Team Rules > Project Rules > User Rules.

### 2.2 Limitations

- Cursor rules are **context injection only** — they modify the agent's behavior but cannot define new tools or restrict tool access.
- No concept of isolated execution or subagents.
- No tool restrictions or model override per rule.
- Cannot define custom slash commands (rules are automatic or @-mention only).

### 2.3 Key Insight

Cursor's system is the simplest: markdown files that inject instructions into context. No tool definitions, no execution isolation, no composition. Pure prompt engineering.

---

## 3. Devin — Playbooks & Skills

Devin has two distinct extensibility systems: **Playbooks** (web app) and **Skills** (Devin for Terminal).

### 3.1 Playbooks (Web App)

**Format:** `.devin.md` files or web-app-created documents.

**Structure:**
```markdown
## Overview
Task outcome and high-level goal

## Procedure
Step-by-step imperative instructions

## Specifications (optional)
Postconditions defining success criteria

## Advice and Pointers (optional)
Guidance correcting AI assumptions

## Forbidden Actions (optional)
What the AI must NOT do

## What's Needed From User (optional)
Required inputs outside AI scope
```

**Activation:** Direct creation in web app, macros (`!macro-name`), or drag-and-drop `.devin.md` files.

**Key Characteristics:**
- Playbooks are **custom system prompts for repeated tasks**.
- They are NOT tool definitions — they are structured instruction sets.
- Enterprise-level sharing across organization.
- Full version history with rollback.
- Combined with Knowledge (persistent tips/docs auto-recalled across sessions).

### 3.2 Skills (Devin for Terminal)

**Format:** `SKILL.md` with YAML frontmatter (follows Agent Skills standard like Claude Code).

**Directory Locations:**
- Project: `.devin/skills/<name>/SKILL.md`
- Global: `~/.config/devin/skills/<name>/SKILL.md`

**Frontmatter Fields:**
- `name` — Skill identifier
- `description` — Purpose summary
- `allowed-tools` — Array restricting tool access (e.g., `read`, `grep`, `glob`, `exec`)
- `triggers` — `user` (slash-command) or `model` (agent-autonomous), both enabled by default

**Invocation:** `/skill-name` in chat, or agent auto-invokes when contextually relevant.

### 3.3 Multi-Agent Orchestration

As of March 2026, Devin can orchestrate multiple Devins in parallel — a main Devin session acts as coordinator, delegating to managed Devins each running in isolated virtual machines.

### 3.4 Key Insight

Devin separates structured workflow instructions (Playbooks) from tool-restricted capabilities (Skills). The Playbooks system is richer for process documentation, while Skills follow the emerging Agent Skills standard.

---

## 4. Google Antigravity — Workflows & Skills

Released late 2025, Antigravity is an "Agent-First" IDE platform built on VS Code.

### 4.1 Workflows

- **Saved prompts** triggered on demand with `/command-name`.
- Analogous to Claude Code slash commands.
- Can instruct the agent to perform multi-step tasks.
- Parameterized and reusable; the AI can compose/modify them.

### 4.2 Skills

- **Specialized knowledge packages** that sit dormant until needed.
- Loaded into context only when request matches skill description.
- Progressive disclosure: only name/description discovered initially, full content on activation.
- Same discovery pattern as Claude Code.

### 4.3 Rules

- System-level behavior guidance (analogous to Cursor rules).
- Applied automatically to every interaction.
- "Rules are more like system instructions whereas Workflows are more like saved prompts."

### 4.4 Key Insight

Antigravity adopts the three-tier model that's emerging as standard: Rules (always-on), Skills (auto-discovered), Workflows/Commands (user-triggered).

---

## 5. OpenAI Agents SDK — Agent Composition

### 5.1 Agent Definition

```python
agent = Agent(
    name="Assistant",
    instructions="You are a helpful assistant",
    tools=[my_function_tool],
    handoffs=[other_agent],
)
```

### 5.2 Custom Tools

Three types:
1. **Function tools:** Any Python function with type annotations becomes a tool. Schema auto-generated via Pydantic.
2. **MCP server tools:** Built-in MCP integration, same interface as function tools.
3. **Hosted tools:** OpenAI-hosted capabilities (code interpreter, file search, etc.).

### 5.3 Handoffs (Agent-to-Agent Delegation)

Handoffs are **represented as tools to the LLM**. An agent with `handoffs=[refund_agent]` gets a tool called `transfer_to_refund_agent`.

**Handoff configuration:**
```python
handoff(
    agent=destination_agent,
    tool_name_override="escalate",
    tool_description_override="Escalate to specialist",
    on_handoff=callback_function,
    input_type=EscalationData,  # Pydantic model for metadata
    input_filter=filter_history,
    is_enabled=True,  # or callable for runtime control
)
```

**Input types** allow model-generated metadata (reason, priority, summary) to accompany handoffs.

**Input filters** transform conversation history for the receiving agent.

### 5.4 Key Insight

OpenAI's model is **code-first, not file-first**. Skills are Python functions and classes, not markdown files. The handoff pattern (agents as tools) is elegant — any agent can be a tool for another agent. This is the most flexible composition model but requires programming to extend.

---

## 6. MCP — Universal Tool Extension

### 6.1 Protocol Overview

MCP (Model Context Protocol) is the emerging standard for adding tools to any LLM agent. Anthropic donated it to the Linux Foundation's Agentic AI Foundation (AAIF) in Dec 2025, co-founded by Anthropic, Block, and OpenAI.

### 6.2 Tool Definition Schema

```json
{
  "name": "get_weather",
  "title": "Weather Information Provider",
  "description": "Get current weather for a location",
  "inputSchema": {
    "type": "object",
    "properties": {
      "location": {
        "type": "string",
        "description": "City name or zip code"
      }
    },
    "required": ["location"]
  },
  "outputSchema": { ... },
  "annotations": { ... }
}
```

### 6.3 Protocol Flow

1. **Discovery:** Client sends `tools/list`, server responds with tool definitions.
2. **Invocation:** Client sends `tools/call` with tool name + arguments.
3. **Dynamic updates:** Server sends `notifications/tools/list_changed` when tools change.

### 6.4 Transport Types

- **stdio** — Process launched locally, communicates over stdin/stdout.
- **HTTP/SSE** — Remote server with HTTP transport.
- **WebSocket** — Persistent connection for bidirectional communication.

### 6.5 Integration with Claude Code

Claude Code supports MCP servers in:
- `.mcp.json` — Project-level MCP server configuration.
- Subagent `mcpServers` field — Scoped MCP servers per subagent.
- Inline subagent definitions — MCP servers connected only when subagent runs.

### 6.6 Known Concerns (2026)

- MCP tool descriptions consume 40-50% of context window before agents do any work.
- Authentication friction for remote MCP servers.
- Token consumption overhead is a growing concern.

### 6.7 Key Insight

MCP is the standard for **tool-level extensibility** (add new capabilities). Skills/Agents are for **prompt-level extensibility** (add new instructions/workflows). They are complementary, not competing. MCP adds tools; Skills tell the agent when and how to use them.

---

## 7. Windsurf — Rules & Workflows

### 7.1 System

- **Rules:** Project-level behavior configuration (similar to Cursor rules).
- **Workflows:** Declarative, AI-assisted automation units.
- **Memories:** Persistent context across sessions.
- **MCP Servers:** Tool extensibility (same as Claude Code).
- **Agent Skills:** Added January 2026 (follows Agent Skills standard).

### 7.2 Key Developments

- Wave 13 (Dec 2025): Parallel multi-agent sessions, git worktree support.
- January 2026: Agent Skills adoption.
- Workflows can be parameterized and reused.

### 7.3 Key Insight

Windsurf converged on the same model as everyone else: Rules + Skills + MCP. The industry is standardizing.

---

## 8. Cross-Cutting Design Patterns

### 8.1 Skill Discovery Pattern (Universal)

All systems follow the same three-phase model:

```
1. DISCOVERY  — Load name + description only (lightweight)
2. ACTIVATION — Load full instructions when task matches description
3. EXECUTION  — Follow instructions, optionally with tool restrictions
```

This is a **progressive disclosure** pattern that manages context budget.

### 8.2 Skill Format Convergence

The industry has converged on **SKILL.md with YAML frontmatter**:

| System | Format | Standard |
|--------|--------|----------|
| Claude Code | SKILL.md + YAML frontmatter | Agent Skills (originator) |
| Devin Terminal | SKILL.md + YAML frontmatter | Agent Skills |
| Windsurf | SKILL.md + YAML frontmatter | Agent Skills |
| Google Antigravity | Similar markdown-based | Proprietary but compatible |
| Cursor | .mdc + YAML frontmatter | Proprietary (rules only, not skills) |
| OpenAI Agents SDK | Python code | Code-first (different paradigm) |

### 8.3 Invocation Taxonomy

| Mode | Who Decides | Example |
|------|-------------|---------|
| **User-triggered** | User types `/command` | Deploy, commit, send message |
| **Auto-triggered** | LLM matches description to context | Style guides, API conventions |
| **Background** | Neither — always loaded | System rules, coding standards |
| **Disabled** | Blocked from auto-invoke | Dangerous operations |

### 8.4 Scoping Levels

Every system supports at least three scopes:

| Level | Persistence | Sharing |
|-------|-------------|---------|
| Organization/Enterprise | Managed settings | All org members |
| User/Personal | User home directory | All user's projects |
| Project | Project directory (VCS) | Team via version control |
| Session | CLI flags/runtime | Current session only |

### 8.5 Composition Patterns

**Pattern 1: Skill as Prompt Injection (Cursor, Antigravity Rules)**
- Simplest: inject markdown into system prompt.
- No isolation, no tool restriction.
- Good for: coding standards, style guides.

**Pattern 2: Skill as Guided Task (Claude Code Skills, Devin Playbooks)**
- Markdown instructions with optional tool restrictions.
- Can run inline or in forked context.
- Good for: repeatable workflows, code generation templates.

**Pattern 3: Agent as Tool (OpenAI Handoffs, Claude Code Subagents)**
- Agents delegate to other agents via tool calls.
- Full context isolation, custom model, custom tools.
- Good for: complex multi-step workflows, parallel research.

**Pattern 4: External Tool via Protocol (MCP)**
- External process provides tool definitions and execution.
- Language-agnostic, dynamically discovered.
- Good for: integrating external services, databases, APIs.

### 8.6 The Emerging Three-Tier Architecture

```
Tier 1: RULES (always active)
  - Coding standards, conventions, constraints
  - Applied to every interaction
  - No user invocation needed

Tier 2: SKILLS (auto-discovered, on-demand)
  - Specialized capabilities with instructions
  - Loaded when context matches description
  - Can be user-invoked with /command

Tier 3: TOOLS (MCP servers, function tools)
  - Concrete capabilities (API calls, DB queries, etc.)
  - Defined by schema, invoked by agent
  - Protocol-based extensibility
```

---

## 9. Implications for Our IntelliJ Plugin

### 9.1 Current State

We have:
- 50 tools in a hybrid tool selection system (categories + semantic matching).
- A `delegate_task` tool for spawning sub-tasks.
- Tool descriptions and categories for discovery.
- A Kotlin-based plugin architecture with 6 modules.

### 9.2 Recommendation: Three-Tier Skill System

Based on the industry convergence, we should implement:

#### Tier 1: Project Rules (Always Active)

**Format:** Markdown files in `.workflow/rules/` or project settings.
**Purpose:** Project-specific conventions the agent always follows.
**Examples:**
- "Always run `mvn verify` before suggesting PR creation"
- "Use `feature/PROJ-{ticketId}-{description}` branch naming"
- "Never modify files in `src/main/resources/legacy/`"

**Implementation:** Load rule content into system prompt. Simple string injection.

#### Tier 2: Skills (On-Demand Capabilities)

**Format:** SKILL.md with YAML frontmatter — follow the Agent Skills standard.

**Location:** `.workflow/skills/<name>/SKILL.md` (project), `~/.workflow-orchestrator/skills/<name>/SKILL.md` (user).

**Frontmatter Fields to Support:**
```yaml
---
name: deploy-staging
description: Deploy current branch to staging environment
disable-model-invocation: true
allowed-tools: [run_bamboo_build, check_build_status, get_docker_tags]
---
```

**How Skills Interact with Existing Tools:**
- `allowed-tools` maps to our existing 50 tools by name.
- Skills provide **instructions** for using tools in specific workflows.
- The agent loads skill descriptions during tool selection phase.
- Skills can reference project-specific scripts/templates.

**Discovery:** Same progressive disclosure — load descriptions at start, full content on match.

#### Tier 3: MCP-Compatible Tool Extension

**Not recommended for v1.** Our 50 built-in tools cover the workflow. MCP adds significant context overhead (40-50% of window). Consider for v2 if users need custom integrations (e.g., Slack, custom CI systems).

### 9.3 Skills vs. Kotlin Plugins vs. MCP — Decision Matrix

| Approach | Effort to Extend | Runtime Cost | User Skill Required | Best For |
|----------|-------------------|--------------|---------------------|----------|
| **SKILL.md files** | Low (write markdown) | Low (text injection) | None (anyone can write) | Workflow instructions, conventions |
| **MCP Servers** | Medium (write server) | High (process + context) | Developer | External tool integration |
| **Kotlin plugins** | High (write + compile) | Zero (native) | Kotlin developer | Core capabilities |

**Recommendation:** SKILL.md for user-extensible skills. This is the industry standard, requires no programming, and integrates naturally with our existing tool system. Skills tell the agent **what to do**, our tools provide **how to do it**.

### 9.4 Concrete Integration Architecture

```
User writes SKILL.md
       |
       v
[Skill Registry] -- scans .workflow/skills/ at startup
       |
       |-- Stores: name, description (lightweight)
       |
       v
[Tool Selection Phase] -- includes skill descriptions
       |
       |-- Agent matches user request to skill
       |
       v
[Skill Activation] -- loads full SKILL.md content
       |
       |-- Restricts available tools to allowed-tools
       |-- Injects instructions into agent context
       |
       v
[Agent Executes] -- uses restricted tool set per skill instructions
```

### 9.5 Subagent Integration

Our `delegate_task` tool already spawns sub-tasks. To add subagent support:

1. **Subagent definitions** in `.workflow/agents/<name>.md` — YAML frontmatter + system prompt.
2. **Tool restrictions** — subagent `tools` field maps to our tool categories or specific tool names.
3. **Context isolation** — each delegated task already runs with its own context via `delegate_task`.
4. **No nesting** — delegated tasks cannot delegate further (matches industry pattern).

### 9.6 Skill Examples for Our Domain

**Example 1: Hotfix Workflow**
```yaml
---
name: hotfix
description: Create and deploy a hotfix for production issues
disable-model-invocation: true
allowed-tools: [create_branch, transition_jira_ticket, run_bamboo_build, create_pull_request]
---

## Hotfix Workflow

1. Get the production issue ticket from user
2. Create branch from `master` with pattern `hotfix/PROJ-{ticketId}-{description}`
3. Transition Jira ticket to "In Progress"
4. Guide user through the fix
5. Run Bamboo build to verify
6. Create PR targeting `master`
7. Add hotfix label to Jira ticket
```

**Example 2: Code Review Prep**
```yaml
---
name: review-prep
description: Prepare code for review - run quality checks and fix issues
allowed-tools: [run_sonar_analysis, get_coverage_report, fix_with_cody, run_maven_build]
---

## Pre-Review Checklist

1. Run SonarQube analysis on changed files
2. Check coverage meets 100% new code threshold
3. Fix any blocker/critical Sonar issues with Cody
4. Verify Maven build passes
5. Generate summary of changes for PR description
```

**Example 3: Sprint Standup Context (Auto-triggered)**
```yaml
---
name: standup-context
description: Provide sprint context when user asks about their current work, tickets, or standup
user-invocable: false
---

When providing standup context:
1. Show current sprint tickets assigned to user
2. Highlight any tickets in "In Progress" state
3. Show recent build results for active branches
4. Note any SonarQube quality gate failures
5. List any pending PRs awaiting review
```

---

## Sources

### Claude Code
- [Extend Claude with skills](https://code.claude.com/docs/en/skills)
- [Create custom subagents](https://code.claude.com/docs/en/sub-agents)
- [Claude Agent Skills: A First Principles Deep Dive](https://leehanchung.github.io/blogs/2025/10/26/claude-skills-deep-dive/)
- [Inside Claude Code Skills: Structure, prompts, invocation](https://mikhail.io/2025/10/claude-code-skills/)
- [Anthropic Skills GitHub Repository](https://github.com/anthropics/skills)
- [Equipping agents for the real world with Agent Skills](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills)
- [Claude Code Agent Skills 2.0: From Custom Instructions to Programmable Agents](https://medium.com/@richardhightower/claude-code-agent-skills-2-0-from-custom-instructions-to-programmable-agents-ab6e4563c176)

### Cursor
- [Cursor Rules Documentation](https://cursor.com/docs/context/rules)
- [Awesome CursorRules](https://github.com/PatrickJS/awesome-cursorrules)

### Devin
- [Creating Playbooks](https://docs.devin.ai/product-guides/creating-playbooks)
- [Skills Overview - Devin for Terminal](https://cli.devin.ai/docs/extensibility/skills/overview)
- [Devin 2.0 - Cognition](https://cognition.ai/blog/devin-2)

### Google Antigravity
- [Build with Google Antigravity](https://developers.googleblog.com/build-with-google-antigravity-our-new-agentic-development-platform/)
- [Google Antigravity AI IDE 2026](https://www.baytechconsulting.com/blog/google-antigravity-ai-ide-2026)
- [Getting Started with Google Antigravity](https://codelabs.developers.google.com/getting-started-google-antigravity)

### OpenAI Agents SDK
- [OpenAI Agents SDK Documentation](https://openai.github.io/openai-agents-python/)
- [Handoffs Documentation](https://openai.github.io/openai-agents-python/handoffs/)
- [OpenAI Agents SDK GitHub](https://github.com/openai/openai-agents-python)
- [New tools for building agents - OpenAI](https://openai.com/index/new-tools-for-building-agents/)

### MCP (Model Context Protocol)
- [MCP Tools Specification (2025-11-25)](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- [The 2026 MCP Roadmap](http://blog.modelcontextprotocol.io/posts/2026-mcp-roadmap/)
- [MCP Servers GitHub](https://github.com/modelcontextprotocol/servers)
- [MCP - Wikipedia](https://en.wikipedia.org/wiki/Model_Context_Protocol)

### Windsurf
- [Windsurf Rules & Workflows](https://www.paulmduvall.com/using-windsurf-rules-workflows-and-memories/)
- [Windsurf Documentation](https://docs.windsurf.com/)

### Design Patterns
- [Agentic Design Patterns: The 2026 Guide](https://www.sitepoint.com/the-definitive-guide-to-agentic-design-patterns-in-2026/)
- [Spring AI Agentic Patterns: Agent Skills](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/)
- [Google Cloud: Choose a design pattern for your agentic AI system](https://docs.google.com/architecture/choose-design-pattern-agentic-ai-system)
