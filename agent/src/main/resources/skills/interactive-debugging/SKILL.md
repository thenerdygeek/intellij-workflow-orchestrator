---
name: interactive-debugging
description: Use when you need to set breakpoints, step through code, inspect runtime variables, or evaluate expressions in a live debug session. Escalation from systematic-debugging when static analysis is insufficient.
user-invocable: false
preferred-tools: [add_breakpoint, remove_breakpoint, list_breakpoints, start_debug_session, get_debug_state, debug_step_over, debug_step_into, debug_step_out, debug_resume, debug_stop, debug_pause, debug_run_to_cursor, evaluate_expression, get_stack_frames, get_variables, get_run_configurations, create_run_config, get_test_results, get_run_output, think]
---

# Interactive Debugging

## Overview

You have full access to IntelliJ's debugger. You can set breakpoints, launch debug sessions,
step through code, inspect variables, and evaluate expressions — all programmatically.

**This is powerful but expensive.** Each step+inspect cycle costs 2 iterations of your budget.
A typical debug session uses 6-12 iterations. Plan carefully.

## When to Use

You should already be here via systematic-debugging's escalation decision. You need runtime
state observation that code reading alone cannot provide.

## The Three Patterns

### Pattern 1: Strategic Breakpoint (Most Common, Most Efficient)

Set a breakpoint at the suspicious location, run, inspect state. No stepping needed.

1. Use `think` to identify the exact line where you need to see runtime state
2. `add_breakpoint` at that line (use `condition` to filter if in a loop/high-traffic path)
3. `start_debug_session` with `wait_for_pause` (or `get_debug_state` to poll)
4. `get_variables` to inspect local state
5. `evaluate_expression` for computed values (e.g., method return values, collection sizes)
6. You now have the information. `debug_stop` and return to systematic-debugging Phase 2.

**Cost: 4-6 iterations.**

### Pattern 2: Observation Breakpoints (Zero-Pause Debugging)

Use log breakpoints to observe values at multiple points without stopping execution.
Best for understanding data flow across methods.

1. `add_breakpoint` at point A with `log_expression: "Point A: user=${user}, valid=${isValid}"`
2. `add_breakpoint` at point B with `log_expression: "Point B: result=${result}"`
3. `add_breakpoint` at point C with `log_expression: "Point C: saved=${savedEntity}"`
4. `start_debug_session` to run with log breakpoints active
5. Trigger the code path (via test or manually)
6. `get_run_output` with `filter="Point [ABC]"` to see the logged values
7. Remove breakpoints. Analyze the flow.

**Cost: 5-7 iterations.** But gives you the full data flow picture.

### Pattern 3: Step-Through (Last Resort)

Single-step through code. Use only when Patterns 1-2 didn't give you enough information.

1. Set breakpoint at the START of the suspicious region
2. `start_debug_session` with `wait_for_pause`
3. `debug_step_over` — examine each line's effect on variables
4. At method calls of interest: `debug_step_into` to follow the call
5. When deep enough: `debug_step_out` to return to caller
6. When you understand the issue: `debug_stop`

**Cost: 8-15+ iterations.** Use `debug_run_to_cursor` to skip uninteresting sections.

## Budget Rules

- **Set a budget before starting**: decide how many iterations you'll spend on debugging
- **10 debug iterations max** without finding the issue = STOP
- If 10 iterations didn't help: `debug_stop`, summarize observations, ask the user
- **Prefer conditional breakpoints** over stepping through loops
- **Prefer `evaluate_expression`** over stepping to a variable assignment
- **Prefer `debug_run_to_cursor`** over repeated `debug_step_over`

## Session Lifecycle

ALWAYS follow this lifecycle:

1. Check `get_run_configurations` — does a suitable config exist?
2. If not: `create_run_config` for the specific test/class you need
3. Set breakpoints BEFORE launching
4. `start_debug_session`
5. Inspect/step as needed
6. **ALWAYS `debug_stop` when done** — never leave a debug session running
7. **ALWAYS `remove_breakpoint`** for temporary breakpoints — never leave breakpoint litter

## Conditional Breakpoint Tips

Instead of stepping through a loop of 1000 items:
```
add_breakpoint(file="OrderService.kt", line=45, condition="order.total > 10000")
```

Instead of pausing on every request:
```
add_breakpoint(file="UserController.kt", line=20, condition="request.getHeader('X-Debug') != null")
```

## Common Pitfalls

- **Don't step through framework code.** Spring proxies, Hibernate internals, reflection — use
  `debug_step_out` or `debug_run_to_cursor` to skip past them.
- **Don't debug without a hypothesis.** "Let me just step through and see" wastes iterations.
  Have a specific question: "What value does X have at line Y?"
- **Don't forget to stop.** An abandoned debug session holds resources and can affect IDE performance.
- **Don't evaluate side-effecting expressions carelessly.** `userRepository.delete(user)` in
  `evaluate_expression` WILL execute against the database.

## Tool Quick Reference

| Phase | Tools | Purpose |
|-------|-------|---------|
| Setup | `get_run_configurations`, `create_run_config` | Ensure config exists |
| Breakpoints | `add_breakpoint`, `remove_breakpoint`, `list_breakpoints` | Set observation points |
| Launch | `start_debug_session` | Begin debug session |
| Navigate | `debug_step_over/into/out`, `debug_run_to_cursor`, `debug_resume` | Move through code |
| Inspect | `get_debug_state`, `get_variables`, `get_stack_frames`, `evaluate_expression` | Observe state |
| Cleanup | `debug_stop`, `remove_breakpoint` | Always clean up |
