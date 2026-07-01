---
name: runblocking-ban-pre-commit-hook
description: block-runblocking.sh (a Claude Code PreToolUse hook, NOT a git pre-commit hook) bans kotlinx.coroutines.runBlocking in production sources; use runBlockingCancellable instead
metadata: 
  node_type: memory
  type: project
  originSessionId: 330592c7-3278-4236-ae70-4e097278b4d5
---

⚠ CORRECTION 2026-06-07: this is a **Claude Code `PreToolUse` hook** (`.claude/hooks/block-runblocking.sh`, wired in `.claude/settings.json`) that rejects Edit/Write tool calls adding `kotlinx.coroutines.runBlocking { ... }` to production sources during agentic sessions. It is **NOT a git pre-commit hook** — `core.hooksPath` → `.git/hooks` contains only `.sample` files, so a human running `git commit` directly is NOT blocked. Don't describe it as a git/pre-commit guard in user-facing docs (caught while writing CONTRIBUTING.md for Phase 1; verified `git check-ignore`/`core.hooksPath`). The rule itself (ban plain `runBlocking` in production) still stands.

**Why:** Phase 4 service+threading refactor banned plain `runBlocking` to enforce proper cancellation propagation. Plain `runBlocking` swallows cancellation; if the IDE wants to cancel the enclosing context (project close, indicator cancel), the work keeps running.

**How to apply:** Use `com.intellij.openapi.progress.runBlockingCancellable { ... }` as the sanctioned replacement. It's API-compatible (suspend-bridge wrapper) but routes cancellation through IntelliJ's `ProgressManager` / coroutine cancellation contract. Test code can still use plain `runTest` / `runBlocking` since the hook scopes to `main/` sources.

Discovered during Checkpoint System v2 Task 12 (2026-05-18) when a subagent's first attempt to add `kotlinx.coroutines.runBlocking { ... }` inside `AgentService.revertToUserMessage` was rejected by the hook. The replacement `runBlockingCancellable` landed cleanly in commit `754e79288`.
