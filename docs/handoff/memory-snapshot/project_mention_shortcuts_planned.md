---
name: Planned mention shortcuts
description: Future context triggers to add — prioritized list of @, #, and special mention types for the chat input
type: project
---

## Implemented (2026-03-25)
- **@** — Files, folders, symbols (contenteditable inline chips)
- **#** — Jira tickets with async validation (yellow → green/red chips)
- **/** — Skills autocomplete
- **+** button — Picker for all 5 types (File, Folder, Symbol, Ticket, Skill)

## Priority 1 — Next batch (saves 1-2 LLM iterations each)
- **@selection** — Current editor selection. Use `Editor.getSelectionModel()`. Most common "explain this" / "fix this" pattern.
- **@errors** — Current compiler errors/diagnostics. Use `CompilerManager` or diagnostics tool output.
- **@git** / **@changes** — Current `git diff` + `git status`. Most common code review context.
- **@terminal** — Last terminal/run output. Use `ProcessHandler` output.

## Priority 2 — Convenience
- **@branch** — Full diff of current branch vs base. PR-level context.
- **@test** — Latest test results (pass/fail/output).
- **@pr:456** — PR diff + comments from Bitbucket.
- **@sonar** — Current SonarQube issues for the project.
- **@build** — Latest Bamboo build status + log excerpt.
- **#sprint** — All tickets in current sprint as summary.

## Priority 3 — Creative / unique
- **@clipboard** — System clipboard content (paste stack traces from Slack).
- **@history** — Summary of last N conversation turns.
- **@url:https://...** — Fetch and inject web page content.
- **@memory** — Agent's cross-session memory.
- **@sketch** — Quick drawing canvas, inject as image.

## How to apply
Each trigger follows the same pattern: detect in RichInput → show dropdown or auto-resolve → create inline chip → Kotlin bridge fetches data → inject as `mentionAnchor` in LLM context. For Priority 1, the Kotlin side already has the APIs (Editor, CompilerManager, git tools) — just needs bridge wiring.
