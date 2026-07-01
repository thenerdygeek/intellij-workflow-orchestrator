---
name: subagent-model-no-haiku
description: "User prefers sonnet/opus (never haiku) for dispatched subagents, even mechanical ones"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 958eb394-c2cf-4b41-84e5-485ee506bc52
---

When dispatching subagents — implementers/reviewers in subagent-driven development, or any Agent tool call — use **sonnet or opus, never haiku**, even for mechanical transcription tasks where the cost-optimization would suggest haiku.

**Why:** the user values output quality/reliability for delegated work over token cost. They interrupted a haiku dispatch (2026-06-20) and said "why use haiku agents? use sonnet or opus."

**How to apply:** default dispatched implementers and reviewers to **sonnet**; use **opus** for the hardest tasks (tricky integration like a coroutine-cancellation funnel, the final whole-branch review). The superpowers `subagent-driven-development` "Model Selection" section recommends the cheapest tier for mechanical tasks — the user overrides that; sonnet is the floor. Always set `model` explicitly on every Agent dispatch (an omitted model inherits the session model).
