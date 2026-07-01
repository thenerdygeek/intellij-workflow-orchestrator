---
name: Tool count reduction needed post-rewrite
description: After agent rewrite is complete, reduce 70 tools to ~15 core + deferred loading (Claude Code ToolSearch pattern). Also remove duplicate meta-tool registrations.
type: project
---

After the Cline-faithful agent rewrite is complete, come back and fix the tool architecture:

1. **Remove duplicate meta-tool registrations** — GitTool + GitStatusTool etc. both registered. Pick one, remove the other.
2. **Reduce to ~15 core tools sent per call** — match Cline's tool count. Core: read_file, edit_file, create_file, run_command, search_code, glob_files, think, attempt_completion, ask_questions, agent, revert_file, git_status, git_diff, semantic_diagnostics, find_definition
3. **Deferred loading for remaining ~55 tools** — Claude Code's ToolSearch pattern. Add a `tool_search` meta-tool that loads schemas on demand for IDE/integration/debug/PSI tools.
4. **Per-tool timeout** — wrap tool.execute() in withTimeout. PSI tools especially can hang.
5. **Tool output spilling to disk** — Codex CLI pattern for large outputs.

**Why:** 70 tools = ~10K tokens of schemas per API call = 7% of context budget wasted. Cline works with ~15 tools (~3K tokens). This directly impacts LLM performance.

**When:** After all phases of the Cline port are done (prompt, context, checkpoint, plan mode, skills).
