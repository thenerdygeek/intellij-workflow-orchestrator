---
name: Sonnet for small or well-planned subagent tasks
description: Default subagent model selection — use Sonnet (not Opus) for tasks that are small, mechanical, or already fully planned in an implementation plan
type: feedback
originSessionId: 1d7b7580-3966-4def-8ab1-f7dfc50fc6ce
---
Default to Sonnet (`model: "sonnet"`) when dispatching Agent subagents for tasks that are:
- Small in scope (1–2 files, clear boundaries)
- Mechanical (plan spells out every step, no design judgment required)
- Already fully planned in an implementation plan / phase doc

Reserve Opus for design, architecture, ambiguous debugging, broad synthesis, or final review.

**Why:** Cost + speed. When the plan carries the reasoning load, the subagent is executing — not deciding — and Sonnet does that well.

**How to apply:** In Agent tool calls, set `model: "sonnet"` explicitly for implementer + reviewer subagents during plan execution (subagent-driven development). Only escalate to Opus if Sonnet returns BLOCKED or the task requires architectural judgment. Research/triage for well-scoped enumeration tasks also goes to Sonnet — Opus is for open-ended exploratory research.
