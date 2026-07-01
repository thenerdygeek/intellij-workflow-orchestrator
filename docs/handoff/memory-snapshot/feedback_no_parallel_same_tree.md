---
name: no-parallel-same-tree
description: "Don't dispatch parallel implementer subagents against the same working tree, even if their file lists are disjoint"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 54e94acb-4300-423e-8060-b79e0e9e2ab4
---

Don't dispatch parallel implementer subagents against the same working tree, even if their file lists appear disjoint.

**Why:** 2026-05-17, after writing the main/sub-agent unification plan, I dispatched 4 parallel Sonnet implementers (F1, F2, F3, F4) to fix code-review findings. File lists looked disjoint but two of the four (F1, F4) were rejected mid-flight. Root cause: every implementer in the same working tree sees the *other agents' uncommitted edits* via `git status`/`git diff` and runs gradle against a mixed compile surface. Builds and tests see correlated state and the implementers get confused. Only F2 (the one touching purely the webview, an independent build surface) committed cleanly. F1/F3/F4 left modified files + untracked tests on disk without commits.

**How to apply:**
- Default to **sequential dispatch** when implementer subagents touch the same module / build surface. Even "disjoint file lists" share the gradle build, test runner, and git index.
- For genuine parallelism, use `isolation: worktree` on each Agent call — each implementer gets its own working copy. Worktree creation cost is worth it.
- Parallel research/explore agents are still fine — they're read-only and don't contend on the working tree.
- Webview vs Kotlin module CAN often run in parallel safely because they have separate build surfaces (Vite vs Gradle) — but only if no Kotlin file is shared.

Related: [[feedback_foreground_agents]] (no run_in_background for implementation agents) — that one is about visibility/control; this one is about working-tree contention. Both push toward "serial in-foreground implementer dispatch by default".
