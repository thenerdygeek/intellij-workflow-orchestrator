---
name: Agent branch strategy and release plan
description: feature/phase-3-agentic-ai-foundation is a long-running branch until main reaches v1.0 stable, then merged as v1.1.0
type: project
---

The `feature/phase-3-agentic-ai-foundation` branch is long-running.

**Why:** Main is heading to v1.0 stable (currently v0.19.x). The agentic AI module will NOT be merged until v1.0 is released and stable. This branch will be worked on in parallel until the agent is production-ready.

**How to apply:**
- Periodically rebase this branch onto main to pick up architecture changes
- If main changes module structure, settings patterns, or core APIs — adapt agent module accordingly
- Never merge agent branch to main before v1.0 is tagged
- After v1.0 is stable, merge agent branch as v1.1.0 (new feature, no breaking changes)
- Worktree at `.worktrees/phase-3-agentic-ai/`

**Release versioning:**
- Main: v0.19.x → v0.20.x → ... → v1.0.0 (stable)
- Agent merge: v1.1.0 (first feature release post-stable)
- Agent module is additive — no breaking changes to existing features

**Remaining work on agent branch:**
- Plan display in proper markdown format
- Approval dialogs for all cases (edits, commands, plans, destructive actions)
- Resume from checkpoint after IDE restart
- MCP tool registration and discovery
- Interactive terminal with streaming output
- Concurrent edit handling and merge UI
- Proper IntelliJ Ultimate Spring context integration (beans, endpoints, @Transactional, etc.)
- Enterprise-grade UI/UX using IntelliJ's best components
- Architecture diagram (after first usable draft)
