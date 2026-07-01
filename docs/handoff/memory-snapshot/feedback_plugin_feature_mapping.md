---
name: Plugin is not Claude Code - don't map CC features onto it
description: When suggesting plugin features for users, use plugin-native surfaces only (Personas/Skills/hooks/memory/plan mode/sub-agents); do not suggest CLAUDE.md or other CC-specific features
type: feedback
originSessionId: 729fdfed-bfc2-49ec-abd0-edd3bda0ded5
---
The Workflow Orchestrator plugin is a Kotlin IntelliJ plugin with its own Cline-derived ReAct agent. It does NOT have a CLAUDE.md feature. CLAUDE.md belongs to Claude Code (the CLI tool running in this terminal session, not the plugin).

**Why:** Got flagged when I proposed a "Suggested CLAUDE.md Additions" section for the plugin's insights report, borrowing the idea from Claude Code's `/insights` sample. User corrected: "there is no claude.md addition or feature related to it so why suggest claude.md suggestion?" The confusion happens because the plugin's own CLAUDE.md files (like `IntelijPlugin/CLAUDE.md`) are read by Claude Code when I work on the plugin's codebase — they're not a feature *of* the plugin.

**How to apply:** When designing anything that surfaces "features to try" or "conventions to add" for the plugin's users, stick to plugin-native surfaces:
- Personas (`~/.workflow-orchestrator/agents/*.yaml`)
- Skills (`.agent-skills/` or `~/.workflow-orchestrator/skills/`)
- Hooks (`.agent-hooks.json` with TaskStart/PreToolUse/PostToolUse/etc)
- Core memory (`core-memory.json`, injected into system prompt — this is the closest analog to CLAUDE.md, but access is via the `core_memory_append`/`core_memory_replace` agent tools, not a file the user hand-edits)
- Archival memory (`archival/store.json`, tag-boosted keyword search)
- Plan mode (toggled by user; restricts to read-only tools)
- Sub-agents (via `spawn_agent` tool)

The broader lesson: when porting design ideas from other AI tools (Claude Code, Cursor, Cline), verify each referenced feature exists in the plugin's own architecture before including it as a recommendation or comparison.
