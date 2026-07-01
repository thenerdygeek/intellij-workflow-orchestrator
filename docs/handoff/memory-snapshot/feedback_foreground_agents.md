---
name: feedback_foreground_agents
description: User prefers foreground agents over background — wants to see progress and not be blocked from editing files
type: feedback
---

Run implementation subagents in foreground, not background.

**Why:** User wants to know when agents complete immediately and doesn't want to be blocked from editing files the agent might be touching. Background agents create uncertainty about what files are safe to edit.

**How to apply:** When dispatching implementation agents, use `run_in_background: false` (default). Only use background for truly independent research/exploration agents where the user doesn't need to wait.
