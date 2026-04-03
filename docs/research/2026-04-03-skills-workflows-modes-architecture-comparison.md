# Skills, Workflows, and Modes Architecture Comparison

## Research Date: 2026-04-03

Comprehensive analysis of how 10 enterprise-grade agentic AI coding tools handle structured workflows, skills, and modes -- focusing on architecture: how they make the LLM reliably invoke skills/workflows without programmatic pre-injection.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [The Agent Skills Open Standard](#the-agent-skills-open-standard)
3. [Tool-by-Tool Analysis](#tool-by-tool-analysis)
4. [Architectural Comparison Matrix](#architectural-comparison-matrix)
5. [Key Architectural Patterns](#key-architectural-patterns)
6. [Reliability Analysis](#reliability-analysis)
7. [Implications for Our Plugin](#implications-for-our-plugin)

---

## Executive Summary

The industry has converged on a surprisingly consistent architecture for skill/workflow systems:

**The dominant pattern is: metadata-in-prompt, content-on-demand, LLM-driven selection.**

Every tool researched uses some variant of this three-tier approach:
1. **Discovery tier**: Skill names + descriptions injected into system prompt or tool schema (~100 tokens per skill)
2. **Selection tier**: LLM reads descriptions and decides which skill matches the user's intent (no algorithmic routing)
3. **Activation tier**: Full skill instructions loaded into context only when selected (progressive disclosure)

The critical finding: **No tool uses programmatic/algorithmic routing to select skills.** Every single one relies on the LLM reading descriptions and making a judgment call. The differences are in how they make this reliable:

| Approach | Tools Using It | Reliability |
|----------|---------------|-------------|
| Prompt-only enforcement | Cline (original), Aider | Low -- model can ignore |
| Prompt + programmatic tool restrictions | Roo Code, Cline (strict mode) | Medium-high |
| Tool-based activation (skill = tool call) | Claude Code, Gemini CLI, Cursor | High |
| Hook-based mandatory injection | Superpowers (Claude Code plugin) | Highest |
| Orchestrator agent routing | Roo Code, GitHub Copilot | Medium -- depends on orchestrator quality |

---

## The Agent Skills Open Standard

Before analyzing individual tools, it is essential to understand that the industry has converged on a **shared open standard** called Agent Skills (agentskills.io), originally created by Anthropic in late 2025 and now adopted by 26+ platforms including Claude Code, OpenAI Codex, Gemini CLI, GitHub Copilot, Cursor, Windsurf, and OpenHands.

### SKILL.md Specification

```
skill-name/
+-- SKILL.md          # Required: YAML frontmatter + markdown instructions
+-- scripts/          # Optional: executable code
+-- references/       # Optional: documentation
+-- assets/           # Optional: templates, resources
```

**Required frontmatter fields:**
- `name`: 1-64 chars, lowercase + hyphens only, must match directory name
- `description`: 1-1024 chars, describes what the skill does AND when to use it

**Optional fields:**
- `license`: License name or reference
- `compatibility`: Max 500 chars, environment requirements
- `metadata`: Arbitrary key-value pairs
- `allowed-tools`: Space-delimited list of pre-approved tools (experimental)

### Three-Tier Progressive Disclosure

1. **Metadata** (~100 tokens): Name + description loaded at startup for ALL skills
2. **Instructions** (<5000 tokens recommended): Full SKILL.md body loaded on activation
3. **Resources** (as needed): scripts/, references/, assets/ loaded only when required

### Why This Matters

The standard defines HOW skills are discovered (directory scanning), HOW they are presented to the LLM (name+description in prompt), and HOW they are activated (progressive loading). What it does NOT define is the selection algorithm -- that is left to each tool's implementation, and universally implemented as "the LLM decides."

---

## Tool-by-Tool Analysis

### 1. Claude Code (Anthropic)

**Architecture: Tool-based skill invocation with LLM-driven selection**

#### How Skills Are Defined
- SKILL.md files following the Agent Skills standard
- Located in `.claude/skills/` (project), `~/.claude/skills/` (user), or plugin directories
- YAML frontmatter with `name`, `description`, optional `when_to_use`, `disable-model-invocation`, `mode`

#### How the LLM Knows About Them
- The `Skill` tool appears in the tools array (NOT the system prompt)
- Its description dynamically includes an `<available_skills>` section listing all skill names and descriptions
- This list is rebuilt for every API request based on currently loaded skills

#### How the LLM Selects Skills
- **Pure LLM reasoning** -- no algorithmic routing, no embeddings, no classifiers
- Claude reads the `<available_skills>` list, matches user intent against descriptions
- Invokes `Skill` tool with `{"command": "skill-name"}`
- The `description` field is the primary selection signal; `when_to_use` is appended if present

#### Enforcement Mechanism
- **Tool-based**: Skill invocation is a tool call, which means it is tracked, logged, and auditable
- **Two-message injection**: Visible message ("The 'X' skill is loading") + hidden isMeta message (full SKILL.md)
- **Context modifier**: Skills can pre-approve tools, override model selection, scoped to skill execution
- **No programmatic gate**: Nothing prevents Claude from skipping skill invocation

#### Reliability
- High for explicit `/skill-name` invocation (user-triggered)
- Medium for automatic invocation (depends on description quality)
- `disable-model-invocation: true` prevents automatic selection, requiring explicit user invocation

---

### 1b. Superpowers Plugin (for Claude Code)

**Architecture: Hook-based mandatory injection -- the most aggressive approach**

#### How It Works
- Uses Claude Code's `SessionStart` hook to inject the `using-superpowers` meta-skill BEFORE the agent's first response
- The hook runs synchronously (`async: false`) to block agent response until injection completes
- Fires on `startup`, `clear`, and `compact` events (NOT `--resume` to prevent duplication)

#### What Gets Injected
- The complete `using-superpowers` SKILL.md (4,000+ lines) containing:
  - **The 1% Rule**: "If there is even a 1% chance a skill applies, the agent MUST invoke it"
  - Complete listings of all available skills with trigger descriptions
  - **Red Flag Tables**: Common rationalizations for skipping skills, with counter-arguments
  - **Decision Tree**: Mermaid flowcharts for skill selection logic
  - Priority hierarchy: User's explicit instructions > Superpowers skills > Default system prompt

#### hooks.json Format
```json
{
  "hooks": [
    {
      "name": "SessionStart",
      "async": false,
      "events": ["startup", "clear", "compact"]
    }
  ]
}
```

#### Hook Output Format
```json
{
  "hookSpecificOutput": {
    "additionalContext": "[using-superpowers skill content]"
  }
}
```

#### How the LLM Selects Skills
- Before-response requirement: "This check must happen before any response, including clarifying questions"
- Self-reinforcing language: Contains explicit instructions about always checking for relevant skills
- Lists every available skill with trigger descriptions for pattern matching
- Provides decision tree flowchart for structured selection

#### Enforcement Mechanism
- **Behavioral, not technical**: No programmatic gate prevents non-compliance
- Relies on prompt engineering + alignment training (RLHF/constitutional AI) to follow explicit, repeated instructions
- The injected content is first-in-context (seen before any user message), leveraging primacy bias
- Red Flag Tables explicitly address and counter common skip rationalizations

#### Reliability
- **Highest observed** among all tools -- the exhaustive prompt engineering is remarkably effective
- But still purely prompt-based: a sufficiently adversarial prompt or context overflow could bypass it
- Known issue: Subagents miss the injected context (GitHub issue #237)

---

### 2. Cursor

**Architecture: Four-tier rules + subagent delegation with LLM-driven routing**

#### How Workflows/Skills/Modes Are Defined

**Rules** (`.cursor/rules/*.mdc`):
- MDC (Markdown with metadata) format with YAML frontmatter
- Four rule types by evaluation priority:
  1. **Always**: `alwaysApply: true` -- included in every conversation
  2. **Auto Attached**: `globs: "*.tsx"` -- included when matching files are open
  3. **Agent Requested**: Clear `description` field -- AI decides if relevant
  4. **Manual/Local**: No metadata -- only included via explicit `@ruleName`

**Custom Agents** (`.cursor/agents/*.md` or `~/.cursor/agents/`):
- Markdown files with YAML frontmatter
- Fields: `name`, `description` (critical for auto-delegation), `model`, `readonly`, `is_background`

**Skills** (SKILL.md following Agent Skills standard):
- Adopted in Cursor 2.4 (late 2025)
- Invoked via slash commands or automatically when relevant

#### How the LLM Knows About Them
- Always rules: injected at start of every conversation
- Auto Attached: injected based on open file glob matching (programmatic pre-filtering)
- Agent Requested: description shown to LLM, which decides relevance
- Custom agents: descriptions visible to main agent for delegation decisions

#### How the LLM Selects
- **Rules**: Programmatic for Always and Auto Attached; LLM reasoning for Agent Requested
- **Agents**: Main agent reads subagent descriptions and decides delegation. Description quality is critical -- "If it's vague, the agent either delegates everything or delegates nothing"
- **Skills**: LLM-driven based on description matching

#### Enforcement Mechanism
- **Hybrid**: Programmatic glob matching for Auto Attached + LLM reasoning for Agent Requested
- Tool restrictions via `readonly` and `tools` fields on custom agents
- No enforcement preventing the agent from ignoring an Agent Requested rule

#### Reliability
- Always rules: 100% (programmatic injection)
- Auto Attached: 100% (programmatic glob matching)
- Agent Requested: Medium (depends on description quality and LLM reasoning)
- Custom agent delegation: Medium-high (description specificity is key)

---

### 3. Windsurf/Codeium Cascade

**Architecture: Planner+executor with rules/memories/skills/workflows as separate concerns**

#### How They Are Defined

**Rules** (`.windsurfrules` global, project-level rules files):
- Fire before every Cascade response
- Short, specific rules outperform long ones
- "Treat them like compiler constraints"

**Memories** (auto-generated):
- Cascade autonomously generates memories to persist context across sessions
- Encode decisions and discoveries (vs. rules which encode standards)

**Skills** (`.windsurf/skills/<name>/SKILL.md` or `~/.codeium/windsurf/skills/`):
- Agent Skills standard format (adopted Jan 12, 2026)
- Progressive disclosure: only name+description initially; full content on activation
- Also supports `.agents/skills/` and `.claude/skills/` cross-agent paths

**Workflows** (`.windsurf/workflows/*.md`):
- Markdown files with title, description, and sequential steps
- **Manual-only** -- "Cascade will never invoke a workflow automatically"
- Invoked via `/[workflow-name]` slash commands
- Limited to 12,000 characters each

#### How the LLM Knows About Them
- Rules: loaded before every response (always-on injection)
- Memories: loaded based on relevance to current context
- Skills: name+description in prompt; full content via progressive disclosure
- Workflows: NOT auto-discovered; requires explicit `/slash-command`

#### How the LLM Selects
- Rules: always applied (no selection needed)
- Skills: LLM matches request to skill descriptions, then auto-invokes
- Workflows: no auto-selection -- manual only
- Memories: automatic relevance matching (algorithm not disclosed)

#### Planner-Executor Architecture
- "A specialized planning agent continuously refines the long-term plan while your selected model focuses on short-term actions"
- Planning happens in background, automatically updated on new information
- SWE-1.5 model specifically trained for software engineering workflows

#### Enforcement
- Rules: always loaded (programmatic)
- Skills: **acknowledged as unreliable**: "Automatic skill invocation is unreliable in Windsurf, and you should always use explicit @skill-name invocation when you need guaranteed skill loading"
- Workflows: enforced by manual-only design (no reliability issue since user controls it)

#### Reliability
- Rules: High (always injected)
- Skill auto-selection: **Low** (officially unreliable)
- Workflows: N/A (manual only)

---

### 4. Cline

**Architecture: Plan/Act mode toggle with system prompt modification**

#### How Modes Are Defined
- Two built-in modes: **Plan** (read-only analysis) and **Act** (read/write execution)
- Deep Planning: multi-phase exploration before implementation
- Custom instructions via `.clinerules` files

#### How the LLM Knows About Them
- System prompt dynamically generated based on active mode
- Mode instructions embedded in system prompt: "PLAN MODE AND ACT MODE" explicitly described
- Tool availability differs by mode

#### How Mode Selection Works
- **Manual toggle**: User switches between Plan and Act in the UI
- **YOLO mode**: Automatic escalation from plan to act when agent determines execution should proceed
- No automatic mode detection based on user intent

#### Enforcement
- **Dual-layer (with caveats)**:
  - Prompt-level: System prompt instructs model to avoid destructive operations in Plan mode
  - Programmatic: `strictPlanModeEnabled` restricts tool availability; `ToolExecutor` enforces based on `TaskConfig`
  - Specialized tools: `plan_mode_respond` and `act_mode_respond` for mode-specific structured responses
- **Known weakness**: Without `strictPlanModeEnabled`, tool restrictions rely solely on system prompt instructions, which "models frequently ignore, especially under certain conditions like after switching between Plan and Act modes multiple times, during long conversations approaching context limits, with certain models (particularly Gemini variants and o3-mini)"

#### Reliability
- With strict mode: High (programmatic tool restriction)
- Without strict mode: Low (prompt-only, models frequently violate)
- Separate models per mode: High (different API configurations)

---

### 5. Roo Code (formerly Roo Cline)

**Architecture: The most sophisticated mode system -- orchestrator-driven with programmatic tool restrictions**

#### How Modes Are Defined
- **YAML or JSON** in `.roomodes` (project) or global config
- Five built-in modes: Code, Architect, Ask, Debug, Orchestrator
- Fully customizable: slug, name, description, roleDefinition, groups, customInstructions, whenToUse

#### Mode Definition Schema
```yaml
customModes:
  - slug: custom-mode-id
    name: Display Name
    description: Brief summary
    roleDefinition: >-
      Detailed description of mode's identity and expertise
    groups:
      - read
      - - edit
        - fileRegex: \.(js|ts)$
    customInstructions: Specific behavioral rules
    whenToUse: When this mode is appropriate
```

#### System Prompt Assembly
Constructed dynamically per mode in this order:
1. **Role Definition** -- mode identity at top
2. **Tool Descriptions** -- available tools based on mode's `groups`
3. **Tool Use Guidelines** -- usage rules
4. **Capabilities** -- environmental abilities
5. **Operational Rules** -- file handling, interaction guidelines
6. **System Information** -- OS, shell, custom instructions

Mode-specific rules loaded from `.roo/rules-{slug}/` directories.

#### The Orchestrator Pattern
- Built-in **Orchestrator mode** breaks tasks into subtasks delegated to specialized modes
- Uses `new_task` tool to spawn subtasks with specific `mode` parameter
- Uses `switch_mode` tool (always-available) for mode transitions
- `whenToUse` field guides orchestrator's mode selection decisions
- Each subtask runs in **complete isolation** with its own conversation history
- Only the `attempt_completion` result summary returns to parent

#### switch_mode Tool
```
Parameters:
  mode_slug (required): Target mode slug
  reason (optional): Context for the transition
```
Validation -> preparation -> user approval -> activation -> tool adjustment -> continuation.

#### Enforcement
- **Programmatic**: `getToolsForMode()` controls exactly which tools are available per mode
- **File restrictions**: `fileRegex` patterns enforce which files can be edited; `FileRestrictionError` thrown on violation
- **Groups**: `read`, `edit`, `command`, `mcp` -- each mode gets specific groups
- **Sticky models**: Each mode remembers last-used AI model, enabling different model preferences per mode

#### Reliability
- Tool restrictions: **Very high** (programmatic enforcement, not prompt-dependent)
- Orchestrator mode selection: **Medium** (LLM-driven based on `whenToUse` descriptions)
- File restrictions: **Very high** (regex-enforced at execution time)

---

### 6. Aider

**Architecture: Simple manual mode switching with two-LLM architect/editor pipeline**

#### How Modes Are Defined
- Four built-in modes: Code, Ask, Architect, Help
- No custom mode creation
- Conventions via `CONVENTIONS.md` file (loaded as read-only context)
- Configuration via `.aider.conf.yml`

#### How the LLM Knows About Them
- Mode affects which system prompt and edit format is used
- Each mode has a distinct prompt prefix (">", "ask>", "architect>")
- Architect mode uses different edit formats (`editor-diff`, `editor-whole`)

#### Two-LLM Pipeline (Architect Mode)
1. **Architect model**: Receives the problem, proposes solution in natural language
2. **Editor model**: Receives architect's proposal, translates into specific file edits
- The editor uses streamlined prompts focused on editing, not problem-solving
- Different LLMs can be used for each role (e.g., o1-preview + DeepSeek)

#### Mode Selection
- **Entirely manual**: `/code`, `/ask`, `/architect`, `/help` commands
- `--chat-mode <mode>` flag at launch
- `/chat-mode <mode>` for persistent switching
- No automatic mode detection

#### Enforcement
- **Functional**: Ask mode prevents file modifications (enforced by system)
- **Structural**: Architect mode enforces two-request pipeline (not bypassable)
- **Lint integration**: Auto-lints after every edit, auto-fixes errors

#### Reliability
- Mode enforcement: **High** (structural, not prompt-dependent)
- Convention following: **Medium** (prompt-based, dependent on CONVENTIONS.md clarity)

---

### 7. OpenHands

**Architecture: Event-sourced agents with keyword-triggered skills and sub-agent delegation**

#### How Skills Are Defined
- Agent Skills standard (SKILL.md) + OpenHands extensions
- Three activation types:
  1. **Always-loaded** (`trigger=None`): In system prompt from conversation start
  2. **Keyword-triggered** (`trigger=KeywordTrigger(keywords=[...])`): Activated on keyword match
  3. **Progressive disclosure**: Listed in `<available_skills>`, read on demand

#### OpenHands Frontmatter Extension
```yaml
---
name: skill-identifier
description: What this skill does
triggers:
  - keyword1
  - keyword2
license: MIT
---
```

#### How the LLM Knows About Them
- Always-loaded: in system prompt directly
- Keyword-triggered: injected as `<EXTRA_INFO>` blocks when keywords match
- Agent Skills: listed in `<available_skills>` with name, description, location path

#### How Selection Works
- **Keyword matching** (programmatic): When user input contains trigger keywords, skill content auto-injected
- **LLM reasoning** (for Agent Skills): LLM reads descriptions in `<available_skills>` and reads full content on demand
- **Sub-agent delegation**: `DelegateTool` for spawning specialized sub-agents

#### Injection Format
```xml
<EXTRA_INFO>
The following information has been included based on a keyword match for "encrypt".
[Skill content]
</EXTRA_INFO>
```

#### Enforcement
- Keyword triggers: **Programmatic** (no LLM decision needed)
- Agent Skills activation: **LLM-driven** (reads descriptions)
- Multiple matching skills: all activate simultaneously, content concatenates
- Name precedence: explicitly defined skills override public skills

#### Reliability
- Keyword-triggered: **Very high** (programmatic, deterministic)
- Agent Skills auto-discovery: **Medium** (LLM-driven)
- Always-loaded: **100%** (always in prompt)

---

### 8. Amazon Q Developer

**Architecture: Rules-based context injection with custom agent profiles**

#### How Rules Are Defined
- Markdown files in `.amazonq/rules/` directory
- **Free-form natural language** -- no required frontmatter or metadata schema
- Priority levels specified within rule content
- Can be organized in sub-directories

#### How Custom Agents Are Defined
- JSON configuration files in `~/.aws/amazonq/cli-agents/` (global) or `.amazonq/cli-agents/` (local)
- Fields: name, description, prompt (system prompt), model, tools configuration
- Tool permissions: which paths can be written, which MCP tools available
- Context includes: file globs, startup commands, trigger-point commands

#### How the LLM Knows About Them
- Rules: automatically scanned and loaded into context on first interaction
- Dynamic update: "if you modify existing rules or add new ones during a session, Amazon Q Developer detects these changes"
- Custom agents: selected before conversation starts (not auto-switched)

#### How Selection Works
- **Rules**: All applicable rules loaded; prioritized by specified priority levels
- **Custom agents**: Manual selection only. "You cannot switch custom agents within an existing chat session"
- Precedence: Local agents > Global agents > Built-in default
- No automatic agent routing based on user intent

#### Enforcement
- Rules: **Always loaded** (programmatic injection)
- Custom agents: **Manual selection** (no auto-switching)
- Tool permissions: **Programmatic** (path restrictions, tool whitelists)
- Toggle capability: Users can disable individual rules per chat session via UI

#### Reliability
- Rule application: **High** (always loaded, priority-based)
- Agent selection: N/A (manual only)
- Tool restrictions: **High** (programmatic enforcement)

---

### 9. GitHub Copilot

**Architecture: Multi-layer customization with agents, skills, instructions, and handoffs**

#### How They Are Defined

**Instructions** (`.github/copilot-instructions.md`):
- Always-on background guidance for the entire project
- Architecture notes, coding standards, build/test/deploy

**Scoped Instructions** (`.github/instructions/*.instructions.md`):
- `applyTo` glob patterns for file-specific guidance

**Custom Agents** (`.github/agents/*.agent.md`):
- YAML frontmatter + markdown body
- Fields: `name`, `description` (required), `target`, `tools`, `model`, `disable-model-invocation`, `user-invocable`
- Tool specification via aliases: `read`, `edit`, `execute`, `search`, `agent`, `web`, `todo`

**Skills** (`.github/skills/<name>/SKILL.md` or `~/.copilot/skills/`):
- Agent Skills standard format
- Progressive loading: name+description first, full content when relevant

**Handoffs**:
- Agent-to-agent transitions with pre-filled context
- After one agent completes, handoff buttons appear for next agent
- Enables sequential workflows: Plan -> Implement -> Review

#### How the LLM Knows About Them
- Instructions: always injected (copilot-instructions.md)
- Scoped instructions: auto-attached based on applyTo globs
- Skills: progressive loading (metadata first)
- Agents: descriptions available for delegation decisions

#### How Selection Works
- **Instructions**: Always applied (programmatic)
- **Skills**: LLM-driven auto-discovery based on descriptions
- **Agents**: Manual selection OR LLM-driven delegation via `agent` tool
- **Handoffs**: Semi-automatic -- buttons appear after agent completion, user clicks to transition
- `disable-model-invocation: true`: Prevents automatic agent selection

#### Enforcement
- Tool restrictions: **Programmatic** (tools field limits available tools per agent)
- `disable-model-invocation`: **Programmatic** (prevents auto-invocation)
- Handoffs: **User-controlled** (buttons, not automatic)

#### Reliability
- Instructions: **Very high** (always injected)
- Skill auto-selection: **Medium** (LLM-driven)
- Agent auto-delegation: **Medium** (depends on description quality)
- Handoffs: **High** (user-controlled transitions)

---

### 10. Gemini CLI / Google Jules

**Architecture: Tool-based skill activation with user approval gate**

#### How Skills Are Defined
- Agent Skills standard (SKILL.md)
- Discovery paths:
  1. `.gemini/skills/` or `.agents/skills/` (workspace)
  2. `~/.gemini/skills/` or `~/.agents/skills/` (user)
  3. Extension bundles (community)
- `.agents/skills/` takes precedence over `.gemini/skills/` at same tier

#### Context Files (GEMINI.md)
- Project-specific instructions loaded hierarchically
- Scanned from CWD upward to project root (.git boundary)
- Also scans subdirectories below CWD
- Respects .gitignore and .geminiignore

#### How the LLM Knows About Them
- At session start: "name and description of all enabled skills" injected into system prompt
- Lightweight registry consuming minimal tokens

#### How Selection Works
- **LLM-driven**: Gemini evaluates request against skill descriptions
- **Tool-based activation**: LLM calls `activate_skill` tool to request activation
- **User approval required**: User sees skill name, purpose, and directory path; must approve
- After approval: full SKILL.md body + folder structure added to conversation history

#### Enforcement
- `activate_skill` tool: **Tracked and auditable** (tool call with user confirmation)
- **User approval gate**: Programmatic -- skill cannot activate without user consent
- File access: skill gains access to its directory only after activation

#### Reliability
- Skill selection: **Medium** (LLM-driven)
- Activation: **Very high** (tool call + user approval gate)
- Context instructions: **High** (GEMINI.md always loaded from hierarchy)

---

## Architectural Comparison Matrix

### Definition Format

| Tool | Format | Location | Custom Creation |
|------|--------|----------|-----------------|
| Claude Code | SKILL.md (Agent Skills) | `.claude/skills/` | Yes |
| Superpowers | SKILL.md + hooks.json | Plugin directory | Yes (skill authoring skill) |
| Cursor | MDC rules + .md agents + SKILL.md | `.cursor/rules/`, `.cursor/agents/` | Yes |
| Windsurf | Rules + SKILL.md + Workflow.md | `.windsurfrules`, `.windsurf/skills/`, `.windsurf/workflows/` | Yes |
| Cline | System prompt modes + .clinerules | Built-in + config files | Limited (Plan/Act only) |
| Roo Code | YAML/JSON modes + .roomodes | `.roomodes`, `.roo/rules-{slug}/` | Yes (full custom modes) |
| Aider | Built-in modes + CONVENTIONS.md | `.aider.conf.yml` | No custom modes |
| OpenHands | SKILL.md + Python Skill objects | `.openhands/`, `skills/` | Yes |
| Amazon Q | Markdown rules + JSON agents | `.amazonq/rules/`, `.amazonq/cli-agents/` | Yes |
| GitHub Copilot | .agent.md + SKILL.md + instructions.md | `.github/agents/`, `.github/skills/` | Yes |
| Gemini CLI | SKILL.md + GEMINI.md | `.gemini/skills/`, `~/.gemini/skills/` | Yes |

### Selection Mechanism

| Tool | Auto-Selection Method | Programmatic Enforcement | Manual Override |
|------|----------------------|-------------------------|-----------------|
| Claude Code | LLM reads skill descriptions in tool schema | Tool-based (Skill tool call) | `/skill-name` slash commands |
| Superpowers | LLM forced by hook-injected "1% rule" | None (prompt-only) | `/skill-name` |
| Cursor (rules) | Programmatic (Always/AutoAttached) + LLM (AgentRequested) | Glob matching for AutoAttached | `@ruleName` mention |
| Cursor (agents) | LLM reads agent descriptions for delegation | `readonly`, `tools` fields | `@agent-name` |
| Windsurf (skills) | LLM matches descriptions | None (acknowledged unreliable) | `@skill-name` |
| Windsurf (workflows) | None -- manual only | N/A | `/workflow-name` |
| Cline | Manual mode toggle | `strictPlanModeEnabled` restricts tools | UI toggle |
| Roo Code | Orchestrator reads `whenToUse` + `switch_mode` tool | `groups` restrict tools; `fileRegex` restricts files | UI mode selector |
| Aider | None -- manual only | Mode-specific system prompts | `/code`, `/architect`, `/ask` |
| OpenHands | Keyword matching (programmatic) + LLM reasoning | KeywordTrigger deterministic | Explicit invocation |
| Amazon Q | Rules always loaded; agents manual | Tool permissions per agent | Agent selection at session start |
| GitHub Copilot | LLM-driven skill discovery + agent delegation | `disable-model-invocation`, tool restrictions | Explicit agent selection |
| Gemini CLI | LLM-driven + `activate_skill` tool | User approval gate required | `/skills` commands |

### Enforcement Depth

| Tool | Prompt-Only | Programmatic Tool Restriction | Programmatic File Restriction | User Approval Gate |
|------|-------------|------------------------------|------------------------------|-------------------|
| Claude Code | Selection only | No | No | No |
| Superpowers | Yes (all enforcement) | No | No | No |
| Cursor | Agent Requested rules | Subagent tools/readonly | No | No |
| Windsurf | Skills, Rules | No | No | No |
| Cline (strict) | Plan mode instructions | strictPlanModeEnabled | No | No |
| Roo Code | Orchestrator routing | groups, tools per mode | fileRegex patterns | switch_mode approval |
| Aider | Convention following | Mode-specific constraints | No | No |
| OpenHands | Agent Skills selection | Keyword triggers | No | No |
| Amazon Q | Rule priority | Tool permissions per agent | Path restrictions | No |
| GitHub Copilot | Skill/agent selection | tools field per agent | No | No |
| Gemini CLI | Skill selection | activate_skill tool | No | User approval for activation |

---

## Key Architectural Patterns

### Pattern 1: Progressive Disclosure (Universal)

Every tool that supports skills uses the same three-tier loading:
1. Metadata in prompt (~100 tokens/skill) -- always visible
2. Full instructions loaded on activation (~5000 tokens)
3. Resources loaded on demand (unbounded)

This is the only scalable approach: with 50+ skills, injecting all content would consume the entire context window.

### Pattern 2: Description-Driven LLM Selection (Universal)

No tool uses algorithmic routing (embeddings, classifiers, regex matching) for skill selection. Every single tool relies on the LLM reading natural language descriptions and making a judgment call. This means **description quality is the #1 reliability factor**.

Good: "Extract and process PDF files. Use when working with PDF documents, forms, or document extraction."
Bad: "Helps with PDFs."

### Pattern 3: Tool-Based Activation (Emerging Standard)

The most reliable auto-selection happens when skill activation is modeled as a tool call:
- **Claude Code**: `Skill` tool with `command` parameter
- **Gemini CLI**: `activate_skill` tool with approval gate
- **Cursor**: Subagent delegation via built-in agent tool
- **Roo Code**: `switch_mode` and `new_task` tools

Tool calls are: auditable, logged, deterministic in structure, and can have approval gates.

### Pattern 4: Prompt-Based Behavioral Enforcement (Superpowers)

Superpowers represents the extreme of prompt engineering:
- 4,000+ line meta-skill injected at session start
- "1% rule" -- must check skills if even 1% chance of relevance
- Red Flag Tables countering skip rationalizations
- Decision tree flowcharts for selection logic
- First-in-context positioning for primacy bias

This works surprisingly well due to modern LLM instruction following, but has no programmatic safety net.

### Pattern 5: Programmatic Tool Restriction (Roo Code, Cline Strict)

The most robust enforcement doesn't try to control WHICH skill is selected -- it controls WHAT the agent can do within each mode:
- Roo Code: `groups` control tool availability; `fileRegex` controls editable files
- Cline: `strictPlanModeEnabled` removes destructive tools in Plan mode
- Cursor: `readonly` and `tools` fields on custom agents

This is defense-in-depth: even if the LLM selects the wrong mode, it cannot perform unauthorized actions.

### Pattern 6: Orchestrator Agent (Roo Code, GitHub Copilot)

A dedicated orchestrator mode/agent that:
1. Receives the user's request
2. Breaks it into subtasks
3. Routes each subtask to a specialized mode/agent
4. Collects results
5. Synthesizes final response

Roo Code's Orchestrator mode is the most mature implementation, using `whenToUse` fields to guide routing and `new_task` for isolated subtask execution.

### Pattern 7: Separation of Concerns (Windsurf, Cursor)

Windsurf uniquely separates four concerns:
- **Rules**: How you work (always applied, compiler-like constraints)
- **Memories**: What you know (persistent cross-session facts)
- **Skills**: How to do specific tasks (procedural, on-demand)
- **Workflows**: Step-by-step processes (manual-only, slash commands)

This prevents overloading any single mechanism and gives each concern the appropriate activation pattern.

---

## Reliability Analysis

### What Makes Auto-Selection Reliable

Based on this research, the factors that increase reliability (in order of impact):

1. **Programmatic tool restrictions** (Roo Code, Cline strict) -- even wrong selection is safe
2. **Tool-based activation with approval gates** (Gemini CLI) -- human in the loop
3. **First-in-context injection** (Superpowers) -- leverages primacy bias
4. **Programmatic pre-filtering** (Cursor AutoAttached, OpenHands keywords) -- reduces selection space
5. **High-quality descriptions** (all tools) -- the universal factor
6. **Explicit "when to use" guidance** (Roo Code whenToUse, Claude Code when_to_use)
7. **Self-reinforcing instructions** (Superpowers Red Flag Tables) -- addresses skip rationalizations

### What Makes Auto-Selection Unreliable

1. **Long context** -- skill descriptions pushed far from attention window
2. **Many competing skills** -- LLM has difficulty distinguishing similar descriptions
3. **Vague descriptions** -- "Helps with X" vs "Use when Y happens"
4. **Model quality** -- weaker models ignore instructions more often (Gemini variants, o3-mini cited)
5. **Context overflow** -- near-limit conversations degrade instruction following
6. **Mode switching** -- repeated transitions reduce compliance (Cline finding)

### Industry Consensus on Reliability

**No tool claims 100% reliable auto-selection.** The closest are:
- Programmatic mechanisms (Cursor AutoAttached, OpenHands keywords): deterministic but limited
- Superpowers: very high but not guaranteed
- Windsurf officially acknowledges unreliability and recommends explicit invocation

The industry has accepted that LLM-driven skill selection is a best-effort mechanism and compensates with:
- Manual override mechanisms (slash commands, @mentions)
- Programmatic enforcement at the tool/file level (defense in depth)
- Approval gates for safety-critical operations

---

## Implications for Our Plugin

### Recommended Architecture

Based on this research, the optimal architecture for our agent module would combine patterns:

1. **Agent Skills standard for definition** -- maximum portability and ecosystem compatibility
2. **Progressive disclosure for context efficiency** -- metadata first, content on activation
3. **Tool-based activation** -- model `activate_skill` as a tool call for auditability
4. **Programmatic tool/file restrictions per mode** -- defense in depth (Roo Code pattern)
5. **Description-driven LLM selection** -- invest in description quality, not algorithmic routing
6. **Orchestrator pattern for complex workflows** -- dedicated routing agent for multi-step tasks
7. **Manual override via slash commands** -- always allow explicit invocation
8. **First-in-context injection for critical behaviors** -- Superpowers pattern for foundational skills

### What NOT to Build

- Embedding-based skill routing (no tool does this; LLM reasoning is sufficient)
- Complex algorithmic matching (keywords work for deterministic cases; LLM handles the rest)
- Automatic mode switching without approval (every tool that tried this had reliability issues)

---

## Sources

### Claude Code / Superpowers
- [Superpowers GitHub Repository](https://github.com/obra/superpowers)
- [Inside Claude Code Skills: Structure, prompts, invocation](https://mikhail.io/2025/10/claude-code-skills/)
- [Claude Agent Skills: A First Principles Deep Dive](https://leehanchung.github.io/blogs/2025/10/26/claude-skills-deep-dive/)
- [Claude Code System Prompts - Skill Invocation](https://github.com/Piebald-AI/claude-code-system-prompts/blob/main/system-prompts/system-prompt-tool-usage-skill-invocation.md)
- [Superpowers DeepWiki - Claude Code Integration](https://deepwiki.com/obra/superpowers/5.1-claude-code:-skill-tool-and-hooks)
- [SessionStart hook double injection issue](https://github.com/obra/superpowers/issues/648)
- [Subagents missing using-superpowers context](https://github.com/obra/superpowers/issues/237)

### Cursor
- [Best practices for coding with agents](https://cursor.com/blog/agent-best-practices)
- [Cursor 2.4: Subagents, Skills, and Image Generation](https://cursor.com/changelog/2-4)
- [Cursor Rules Deep Dive](https://forum.cursor.com/t/a-deep-dive-into-cursor-rules-0-45/60721)
- [Cursor Subagents Complete Guide](https://medium.com/@codeandbird/cursor-subagents-complete-guide-5853e8d39176)

### Windsurf
- [Cascade Documentation](https://docs.windsurf.com/windsurf/cascade/cascade)
- [Cascade Skills](https://docs.windsurf.com/windsurf/cascade/skills)
- [Cascade Workflows](https://docs.windsurf.com/windsurf/cascade/workflows)
- [Cascade Memories](https://docs.windsurf.com/windsurf/cascade/memories)

### Cline
- [Plan and Act Modes (DeepWiki)](https://deepwiki.com/cline/cline/3.4-plan-and-act-modes)
- [Deep Planning](https://docs.cline.bot/features/deep-planning)
- [System Prompt Advanced](https://cline.bot/blog/system-prompt-advanced)

### Roo Code
- [Customizing Modes](https://docs.roocode.com/features/custom-modes)
- [Using Modes](https://docs.roocode.com/basic-usage/using-modes)
- [switch_mode Tool](https://docs.roocode.com/advanced-usage/available-tools/switch-mode)
- [Boomerang Tasks](https://docs.roocode.com/features/boomerang-tasks)
- [Prompt Structure](https://docs.roocode.com/advanced-usage/prompt-structure)

### Aider
- [Chat Modes](https://aider.chat/docs/usage/modes.html)
- [Separating code reasoning and editing](https://aider.chat/2024/09/26/architect.html)
- [Coding Conventions](https://aider.chat/docs/usage/conventions.html)

### OpenHands
- [Agent Skills & Context](https://docs.openhands.dev/sdk/guides/skill)
- [Sub-Agent Delegation](https://docs.openhands.dev/sdk/guides/agent-delegation)
- [OpenHands SDK Paper](https://arxiv.org/html/2511.03690v1)

### Amazon Q Developer
- [Mastering Amazon Q Developer with Rules](https://aws.amazon.com/blogs/devops/mastering-amazon-q-developer-with-rules/)
- [Creating Project Rules](https://docs.aws.amazon.com/amazonq/latest/qdeveloper-ug/context-project-rules.html)
- [Custom Agents](https://docs.aws.amazon.com/amazonq/latest/qdeveloper-ug/command-line-custom-agents.html)

### GitHub Copilot
- [Custom Agents Configuration Reference](https://docs.github.com/en/copilot/reference/custom-agents-configuration)
- [About Agent Skills](https://docs.github.com/en/copilot/concepts/agents/about-agent-skills)
- [Custom Instructions in VS Code](https://code.visualstudio.com/docs/copilot/customization/custom-instructions)
- [Copilot Instructions vs Prompts vs Agents vs Skills](https://dev.to/pwd9000/github-copilot-instructions-vs-prompts-vs-custom-agents-vs-skills-vs-x-vs-why-339l)

### Gemini CLI / Jules
- [Agent Skills (Gemini CLI)](https://geminicli.com/docs/cli/skills/)
- [Creating Agent Skills Codelab](https://codelabs.developers.google.com/gemini-cli/how-to-create-agent-skills-for-gemini-cli)
- [Beyond Prompt Engineering: Agent Skills in Gemini CLI](https://medium.com/google-cloud/beyond-prompt-engineering-using-agent-skills-in-gemini-cli-04d9af3cda21)
- [Jules Extension for Gemini CLI](https://developers.googleblog.com/en/introducing-the-jules-extension-for-gemini-cli/)

### Agent Skills Standard
- [Agent Skills Specification](https://agentskills.io/specification)
