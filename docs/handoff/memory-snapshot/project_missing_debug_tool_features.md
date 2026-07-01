---
name: Missing debug tool features (partially done)
description: Remaining debug tool gaps — smart_step_into, dependent breakpoints, step filters. force_step_into/over, set_value, pass_count, suspend_policy are DONE.
type: project
---

## DONE (implemented 2026-04-06)

- `force_step_into` in debug_step — `session.forceStepInto()`, bypasses step filters
- `force_step_over` in debug_step — `session.stepOver(true)`, ignores breakpoints in called methods
- `set_value` in debug_inspect — uses evaluate API with assignment expression, verifies by read-back
- `pass_count` on debug_breakpoints add_breakpoint — condition counter workaround
- `suspend_policy` on debug_breakpoints add_breakpoint — all/thread/none via `SuspendPolicy`

## TODO (remaining)

- `smart_step_into(session_id?)` — Needs `XSmartStepIntoHandler.computeSmartStepVariants()` + target selection. More complex than other step actions. Medium effort.
- `depends_on` on `add_breakpoint` — Dependent breakpoints: "break here only after hitting there". Needs breakpoint dependency tracking. Medium effort.
- Step filter configuration — auto-skip `org.springframework.cglib.*`, `com.sun.proxy.*`, `jdk.internal.reflect.*`. Could be an action on debug_step or a persistent setting. Low effort.
