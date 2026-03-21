# Claude Code Skills & Subagents — Official Reference

Research date: 2026-03-21
Source: https://code.claude.com/docs/en/skills and https://code.claude.com/docs/en/sub-agents

## Skills System

### SKILL.md Frontmatter Fields

| Field | Required | Description |
|-------|----------|-------------|
| `name` | No | Display name, becomes `/slash-command`. Lowercase, hyphens, max 64 chars. |
| `description` | Recommended | What skill does + when to use it. LLM uses this to decide auto-invocation. |
| `argument-hint` | No | Hint shown during autocomplete. E.g., `[issue-number]` |
| `disable-model-invocation` | No | `true` = only user can invoke. Description NOT loaded into LLM context. Default: `false`. |
| `user-invocable` | No | `false` = hidden from `/` menu. Only LLM can invoke. Default: `true`. |
| `allowed-tools` | No | Tools the LLM can use WITHOUT per-use approval when skill is active. |
| `model` | No | Override model when skill is active. |
| `effort` | No | Override effort level. Options: low, medium, high, max. |
| `context` | No | `fork` = run in isolated subagent context. |
| `agent` | No | Which subagent type when `context: fork`. Built-in: Explore, Plan, general-purpose, or custom. |
| `hooks` | No | Lifecycle hooks scoped to this skill. |

### Invocation Control

| Frontmatter | User can invoke | Claude can invoke | Context loading |
|---|---|---|---|
| (default) | Yes | Yes | Description always in context, full content on invoke |
| `disable-model-invocation: true` | Yes | No | Description NOT in context |
| `user-invocable: false` | No | Yes | Description always in context |

Key note from docs: "The `user-invocable` field only controls menu visibility, not Skill tool access."

### String Substitutions

| Variable | Description |
|----------|-------------|
| `$ARGUMENTS` | All args passed when invoking |
| `$ARGUMENTS[N]` / `$N` | Positional arg by 0-based index |
| `${CLAUDE_SESSION_ID}` | Current session ID |
| `${CLAUDE_SKILL_DIR}` | Directory containing SKILL.md |

### Dynamic Context Injection

`` !`command` `` syntax runs shell commands BEFORE skill content is sent to LLM. Output replaces placeholder.

### Skill Scopes (Priority Order)

1. Enterprise (managed settings)
2. Personal (`~/.claude/skills/`)
3. Project (`.claude/skills/`)
4. Plugin (`<plugin>/skills/`, namespaced as `plugin-name:skill-name`)

### Description Budget

2% of context window, fallback 16,000 chars. Override: `SLASH_COMMAND_TOOL_CHAR_BUDGET` env var.

## Subagents System

### Subagent Definition (Markdown + YAML frontmatter)

Location priority:
1. `--agents` CLI flag (session only, highest)
2. `.claude/agents/` (project)
3. `~/.claude/agents/` (user)
4. Plugin's `agents/` directory (lowest)

### Subagent Frontmatter Fields

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Unique identifier |
| `description` | Yes | When Claude should delegate |
| `tools` | No | Allowlist of tools. Inherits all if omitted. |
| `disallowedTools` | No | Denylist. Removed from inherited/specified. |
| `model` | No | `sonnet`, `opus`, `haiku`, full ID, or `inherit` |
| `permissionMode` | No | `default`, `acceptEdits`, `dontAsk`, `bypassPermissions`, `plan` |
| `maxTurns` | No | Max agentic turns |
| `skills` | No | Skills preloaded into subagent context at startup |
| `mcpServers` | No | MCP servers for this subagent |
| `hooks` | No | Lifecycle hooks scoped to this subagent |
| `memory` | No | Persistent memory: `user`, `project`, `local` |
| `background` | No | `true` = always run in background |
| `effort` | No | Override effort level |
| `isolation` | No | `worktree` = isolated git worktree |

### Built-in Subagents

| Agent | Model | Tools | Purpose |
|-------|-------|-------|---------|
| Explore | Haiku | Read-only | File discovery, code search |
| Plan | Inherits | Read-only | Codebase research for planning |
| General-purpose | Inherits | All | Complex multi-step tasks |

### Key Subagent Rules

- Subagents CANNOT spawn other subagents
- Subagents don't inherit skills from parent — must be listed in `skills` field
- Subagent markdown body = system prompt (NOT the full Claude Code system prompt)
- `disallowedTools` applied first, then `tools` resolved against remaining
- Background subagents: pre-approve permissions upfront, auto-deny anything not pre-approved

### Skills ↔ Subagents Relationship

| Approach | System prompt | Task | Also loads |
|----------|--------------|------|------------|
| Skill with `context: fork` | From agent type (Explore, Plan, etc.) | SKILL.md content | CLAUDE.md |
| Subagent with `skills` field | Subagent's markdown body | Claude's delegation message | Preloaded skills + CLAUDE.md |

"This is the inverse of running a skill in a subagent. With `skills` in a subagent, the subagent controls the system prompt and loads skill content. With `context: fork` in a skill, the skill content is injected into the agent you specify."

### Persistent Memory for Subagents

| Scope | Location |
|-------|----------|
| `user` | `~/.claude/agent-memory/<name>/` |
| `project` | `.claude/agent-memory/<name>/` |
| `local` | `.claude/agent-memory-local/<name>/` |

When enabled: system prompt includes instructions + first 200 lines of MEMORY.md. Read/Write/Edit tools auto-enabled.
