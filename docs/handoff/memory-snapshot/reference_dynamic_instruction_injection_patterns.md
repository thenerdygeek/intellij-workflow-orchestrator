# Dynamic Instruction Injection Patterns (6 tools, source code analysis)

**Full doc:** `docs/research/2026-04-03-dynamic-instruction-injection-patterns.md`

All 6 tools inject rules into the **system prompt** (beginning). Key differences:

- **Cursor:** System prompt end section. Glob-scoped rules. Closed-source compaction.
- **Cline:** Last section of system prompt (after RULES, before OBJECTIVE). Conditional via YAML frontmatter `paths:`. Reads .cursorrules/.windsurfrules for compat. Skills loaded via `use_skill` tool.
- **Codex CLI:** AGENTS.md injected as **user-role messages** with XML `<INSTRUCTIONS>` tags (NOT system). Re-injected each turn. Explicitly excluded from memory/compaction via `is_memory_excluded_contextual_user_fragment()`. Directory-scoped. Skills via `<skill>` XML tags.
- **Windsurf:** System prompt + auto-generated "memories" for persistence. 4-layer pipeline. Closed source.
- **Continue.dev:** Rules appended to system message via `getSystemMessageWithRules()`. Supports AGENTS.md/CLAUDE.md. Glob + regex + directory scoping. `@rules` context provider for manual injection.
- **Aider:** **Dual injection** -- system prompt (beginning) AND `system_reminder` at END of context (after conversation). Reminder is either `system` role or stuffed into last `user` message. Read-only files as user message pairs. ChatChunks structural separation makes system/reminder/repo/files compression-proof (never summarized).

**Key patterns:** (1) System prompt injection (all), (2) User message with XML tags (Codex), (3) Dual/recency reinforcement (Aider), (4) Dynamic skill loading (Cline, Codex), (5) Conditional activation (Cline, Continue, Codex, Cursor).

**Best compression-proof:** Codex (explicit exclusion + re-injection) > Aider (structural separation) > Continue (rebuild each turn).
