---
name: claude-agent-sdk-python analysis
description: Reference doc for Anthropic's official Python SDK for Claude Code — thin CLI wrapper, not an agent framework. Useful for comparing against our :agent module.
type: reference
originSessionId: 1904c786-89f7-4bef-b852-1b988d6795af
---
Full analysis at `docs/research/2026-04-18-claude-agent-sdk-python-analysis.md` (in `feature/tooling-architecture-enhancements` worktree, ~400 lines).

**One-line summary:** `claude-agent-sdk-python` is a remote-control dataclass for the bundled Claude Code CLI — all agent logic (loop, tools, context, sub-agents, compaction) runs inside the CLI subprocess; the SDK only spawns it, builds argv from `ClaudeAgentOptions`, and speaks a line-delimited JSON control protocol on stdio. Its signature novel feature is in-process SDK MCP servers (@tool decorator + `create_sdk_mcp_server`) that route tool calls back through the same pipe instead of subprocess MCP.

**Why the doc matters for our work:** architectural inverse of our `:agent` module (we re-implement every layer they delegate). Useful isolated patterns to borrow: control-protocol wire design (§10), ergonomic flat `ClaudeAgentOptions` dataclass (§5), pluggable `Transport` ABC (§12), `rewind_files(user_message_id)` UX over raw checkpoint ids (§13), `exclude_dynamic_sections` for cross-user prompt-cache hits (§5). Do NOT imitate the "delegate loop to subprocess" model — we'd lose all IntelliJ integration and we're locked to Sourcegraph Cody anyway.

**Version analysed:** v0.1.62, bundles CLI 2.1.113. Doc includes full §13 side-by-side table comparing to our `:agent` module.
