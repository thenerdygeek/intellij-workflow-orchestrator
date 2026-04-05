---
name: interactive-debugging
description: Live debugger control for setting breakpoints, stepping through code, inspecting variables, and evaluating expressions in a running JVM debug session. This skill is the escalation path from systematic-debugging — use it when static analysis alone cannot identify the root cause and you need to observe runtime state. Trigger phrases include "step through", "set a breakpoint", "watch this variable", "evaluate at runtime", as well as situations involving thread deadlocks, race conditions, or values that only make sense at runtime. Important — do not load this directly for initial bug investigation. Always start with systematic-debugging first and only escalate to interactive-debugging when you have a specific hypothesis to verify at runtime. This skill provides a structured protocol for strategic breakpoint placement, efficient stepping strategy, systematic variable inspection, and proper debug session lifecycle management so you do not waste time stepping blindly through unrelated code.
user-invocable: true
preferred-tools: [debug_breakpoints, debug_step, debug_inspect, runtime_exec, runtime_config, create_run_config, think]
---

# Interactive Debugging

## Overview

You have full access to IntelliJ's debugger via three meta-tools:
- **`debug_breakpoints`** — add/remove breakpoints, start/attach debug sessions
- **`debug_step`** — stepping, pausing, resuming, stopping, run-to-cursor
- **`debug_inspect`** — evaluate expressions, inspect variables, stack frames, thread dump

All three are **deferred tools** — activate them first with `tool_search(query="debug")`.

**This is powerful but expensive.** Each step+inspect cycle costs 2 iterations of your budget.
A typical debug session uses 6-12 iterations. Plan carefully.

## When to Use

You should already be here via systematic-debugging's escalation decision. You need runtime
state observation that code reading alone cannot provide.

## The Three Patterns

### Pattern 1: Strategic Breakpoint (Most Common, Most Efficient)

Set a breakpoint at the suspicious location, run, inspect state. No stepping needed.

1. Use `think` to identify the exact line where you need to see runtime state
2. `debug_breakpoints(action="add_breakpoint", file="Foo.kt", line=42, condition="...")` — use `condition` to filter if in a loop/high-traffic path
3. `debug_breakpoints(action="start_session", config_name="FooTest")` — or poll with `debug_step(action="get_state")`
4. `debug_inspect(action="get_variables")` to inspect local state
5. `debug_inspect(action="evaluate", expression="myVar.size()")` for computed values
6. You now have the information. `debug_step(action="stop")` and return to systematic-debugging Phase 2.

**Cost: 4-6 iterations.**

### Pattern 2: Observation Breakpoints (Zero-Pause Debugging)

Use log breakpoints to observe values at multiple points without stopping execution.
Best for understanding data flow across methods.

1. `debug_breakpoints(action="add_breakpoint", file="Service.kt", line=10, log_expression="Point A: user=${user}, valid=${isValid}")`
2. `debug_breakpoints(action="add_breakpoint", file="Service.kt", line=25, log_expression="Point B: result=${result}")`
3. `debug_breakpoints(action="add_breakpoint", file="Service.kt", line=40, log_expression="Point C: saved=${savedEntity}")`
4. `debug_breakpoints(action="start_session", config_name="ServiceTest")` to run with log breakpoints active
5. Trigger the code path (via test or manually)
6. `runtime_exec(action="get_run_output", filter="Point [ABC]")` to see the logged values
7. Remove breakpoints. Analyze the flow.

**Cost: 5-7 iterations.** But gives you the full data flow picture.

### Pattern 3: Step-Through (Last Resort)

Single-step through code. Use only when Patterns 1-2 didn't give you enough information.

1. Set breakpoint at the START of the suspicious region
2. `debug_breakpoints(action="start_session", config_name="...")` to launch in debug mode
3. `debug_step(action="step_over")` — examine each line's effect on variables
4. At method calls of interest: `debug_step(action="step_into")` to follow the call
5. When deep enough: `debug_step(action="step_out")` to return to caller
6. When you understand the issue: `debug_step(action="stop")`

**Cost: 8-15+ iterations.** Use `debug_step(action="run_to_cursor", file="Foo.kt", line=80)` to skip uninteresting sections.

### Pattern 4: Exception Breakpoint (Catch at Throw Point)

Don't guess where an exception happens — let the debugger catch it the moment it's thrown.

1. `debug_breakpoints(action="exception_breakpoint", exception_class="java.lang.NullPointerException", caught=true)`
2. `debug_breakpoints(action="start_session", config_name="...")` — run normally
3. When the exception fires, the session pauses at the exact throw point
4. `debug_inspect(action="get_variables")` — see what's null and why
5. `debug_inspect(action="get_stack_frames")` — see the full call chain that led here
6. `debug_step(action="stop")`

**Cost: 3-4 iterations.** Best for NPE, ClassCastException, IllegalArgumentException — any exception where you know the type but not the location.

Common exception breakpoints:
- `java.lang.NullPointerException` — the most common bug type
- `java.lang.ClassCastException` — wrong type at runtime
- `java.lang.IllegalStateException` — invalid state transitions
- `org.springframework.beans.factory.BeanCreationException` — Spring startup failures
- `org.springframework.beans.factory.NoSuchBeanDefinitionException` — missing Spring beans

### Pattern 5: Remote Debugging (Docker, K8s, Staging)

Attach to a remote JVM running elsewhere — Docker container, Kubernetes pod, staging server.

**Prerequisite:** The target JVM must be running with JDWP agent:
```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```
(Java 8 uses `address=5005`, Java 11+ uses `address=*:5005`)

1. Ensure the debug port is accessible (Docker: `-p 5005:5005`, K8s: `kubectl port-forward pod/mypod 5005:5005`)
2. `debug_breakpoints(action="attach_to_process", port=5005, host="localhost")` — or use the remote host
3. Set breakpoints on the classes you need to inspect
4. Trigger the code path (API call, test, etc.)
5. Inspect state as in Pattern 1
6. `debug_step(action="stop")` when done

**Cost: 5-8 iterations.** Essential for bugs that only reproduce in non-local environments.

For Testcontainers: the container assigns random ports. Add `JAVA_TOOL_OPTIONS=-agentlib:jdwp=...` to the container env, then use `container.getMappedPort(5005)` to find the host port.

### Pattern 6: Thread Dump Analysis (Deadlocks, Race Conditions, Hangs)

When the application hangs or behaves inconsistently under concurrency.

1. `debug_breakpoints(action="start_session", config_name="...")` — launch the app
2. Trigger the concurrent scenario (parallel requests, multiple threads)
3. `debug_step(action="pause")` — freeze all threads
4. `debug_inspect(action="thread_dump", include_stacks=true)` — see all threads, their states, and stack traces
5. Look for: BLOCKED threads (deadlock), threads waiting on the same lock, threads stuck in infinite loops
6. If a specific thread is suspicious: `debug_inspect(action="get_stack_frames", thread_name="http-nio-8080-exec-3")`
7. `debug_step(action="stop")`

**Cost: 3-5 iterations.** The thread dump alone often reveals the problem.

### Pattern 7: Field Watchpoint (Who Mutated This?)

When a field has an unexpected value but you don't know which code path set it.

1. `debug_breakpoints(action="field_watchpoint", class_name="UserSession", field_name="token", watch_write=true)`
2. `debug_breakpoints(action="start_session", config_name="...")` — run normally
3. The session pauses every time `token` is written to
4. `debug_inspect(action="get_stack_frames")` — see who wrote it
5. `debug_inspect(action="get_variables")` — see what value was written
6. Remove the watchpoint and `debug_step(action="stop")`

**Cost: 4-5 iterations.** Also supports `watch_read=true` to catch who reads a field.

## Advanced Breakpoint Types

Beyond line breakpoints, the debugger supports:

| Type | Action | When to Use |
|------|--------|-------------|
| **Line** | `add_breakpoint(file, line)` | Default — pause at a specific line |
| **Method** | `method_breakpoint(class_name, method_name)` | Break on entry/exit without knowing exact line. **Warning: 5-10x slower** than line breakpoints. |
| **Exception** | `exception_breakpoint(exception_class)` | Catch exceptions at throw point. Set `caught=true` and/or `uncaught=true`. |
| **Field watchpoint** | `field_watchpoint(class_name, field_name)` | Break when field is read/written. Set `watch_read` and/or `watch_write`. |
| **Log breakpoint** | `add_breakpoint(file, line, log_expression="...")` | Print values without pausing. Zero runtime cost. |
| **Temporary** | `add_breakpoint(file, line, temporary=true)` | Auto-removes after first hit. No cleanup needed. |
| **Conditional** | `add_breakpoint(file, line, condition="...")` | Pause only when condition is true. |
| **Pass count** | `add_breakpoint(file, line, pass_count=100)` | Break on every Nth hit. Useful for loops and high-traffic code. |
| **Thread-only suspend** | `add_breakpoint(file, line, suspend_policy="thread")` | Pause only the hitting thread — other threads keep running. Essential for concurrent debugging. |

## Advanced Inspection

Beyond variables and evaluate, the debugger supports:

| Action | What It Does | When to Use |
|--------|-------------|-------------|
| `set_value(variable_name, new_value)` | Modify a variable's value at runtime | Test hypotheses without restarting — change a value and resume |
| `thread_dump` | All threads with states and stacks | Deadlocks, hangs, race conditions |
| `memory_view(class_name)` | Count live instances of a class | Memory leaks, unexpected object retention |
| `hotswap(compile_first=true)` | Reload changed classes without restart | Iterative fix-and-test during debug session |
| `force_return(return_value="...", return_type="...")` | Exit method early with a specific value | Skip error handling, test alternate paths |
| `drop_frame(frame_index=0)` | Rewind execution to frame start | Re-run a method with different data after `set_value` |

## Spring Boot Debugging

Spring uses CGLIB proxies for `@Transactional`, `@Cacheable`, etc. When stepping into a proxied method, you'll land in generated proxy code.

**Skip proxies:** Use `debug_step(action="force_step_into")` to bypass step filters and enter the actual method body directly through CGLIB proxies. Alternatively, `debug_step(action="step_out")` then `debug_step(action="step_into")` again, or `debug_step(action="run_to_cursor")` to jump past the proxy.

**`force_step_into` vs `step_into`:** Regular `step_into` respects step filters (skips framework internals). `force_step_into` ignores all filters — use it when you need to enter Spring proxy code, reflection calls, or library methods. `force_step_over` ignores breakpoints in called methods — use when stepping over a method that has breakpoints you don't want to trigger.

**Spring startup failures:** Set exception breakpoints on:
- `BeanCreationException` — failed to create a bean
- `NoSuchBeanDefinitionException` — missing dependency
- `UnsatisfiedDependencyException` — circular or unresolvable dependency

**Spring Security debugging:** Set method breakpoint on the specific filter class (e.g., `JwtAuthenticationFilter`) or use log breakpoints on `FilterChainProxy.doFilter()` to trace the filter chain without stopping.

**Use the `spring` meta-tool first:** Before setting breakpoints, use `spring(action="context")` to understand the bean graph and `spring(action="endpoints")` to find the handler method for a given URL.

## Budget Rules

- **Set a budget before starting**: decide how many iterations you'll spend on debugging
- **10 debug iterations max** without finding the issue = STOP
- If 10 iterations didn't help: `debug_step(action="stop")`, summarize observations, ask the user
- **Prefer conditional breakpoints** over stepping through loops
- **Prefer `debug_inspect(action="evaluate")`** over stepping to a variable assignment
- **Prefer `debug_step(action="run_to_cursor")`** over repeated step_over calls

## Session Failures

If `start_session` fails: check `runtime_exec(action="compile_module")` first (compilation errors prevent debugging), verify `runtime_config(action="get_run_configurations")` has a valid config, create one with `create_run_config` if missing.

## Session Lifecycle

ALWAYS follow this lifecycle:

1. Check `runtime_config(action="get_run_configurations")` — does a suitable config exist?
2. If not: `create_run_config(name="...", type="junit", test_class="...")` for the specific test/class
3. Set breakpoints BEFORE launching
4. `debug_breakpoints(action="start_session", config_name="...")`
5. Inspect/step as needed
6. **ALWAYS `debug_step(action="stop")` when done** — never leave a debug session running
7. **ALWAYS `debug_breakpoints(action="remove_breakpoint")`** for temporary breakpoints — never leave breakpoint litter

## Conditional Breakpoint Tips

Instead of stepping through a loop of 1000 items:
```
debug_breakpoints(action="add_breakpoint", file="OrderService.kt", line=45, condition="order.total > 10000")
```

Instead of pausing on every request:
```
debug_breakpoints(action="add_breakpoint", file="UserController.kt", line=20, condition="request.getHeader('X-Debug') != null")
```

## Common Pitfalls

- **Don't step through framework code.** Spring proxies, Hibernate internals, reflection — use
  `debug_step(action="step_out")` or `debug_step(action="run_to_cursor")` to skip past them.
  If you DO need to enter framework code, use `debug_step(action="force_step_into")`.
- **Don't debug without a hypothesis.** "Let me just step through and see" wastes iterations.
  Have a specific question: "What value does X have at line Y?"
- **Don't forget to stop.** An abandoned debug session holds resources and can affect IDE performance.
- **Don't evaluate side-effecting expressions carelessly.** `userRepository.delete(user)` in
  `debug_inspect(action="evaluate")` WILL execute against the database.

## Pattern Selection Guide

| Scenario | Best Pattern | Why |
|----------|-------------|-----|
| Know the suspicious line | Pattern 1: Strategic Breakpoint | Direct, 4-6 iterations |
| Need to trace data flow | Pattern 2: Observation Breakpoints | Zero-pause, full picture |
| No idea where the bug is | Pattern 3: Step-Through | Exhaustive but expensive |
| Exception with unknown origin | Pattern 4: Exception Breakpoint | Catches at throw point, 3-4 iterations |
| Bug only on staging/Docker | Pattern 5: Remote Debugging | Attach to remote JVM |
| Deadlock, hang, race condition | Pattern 6: Thread Dump | Reveals thread state instantly |
| Field has wrong value, unknown writer | Pattern 7: Field Watchpoint | Catches every mutation |

## Tool Quick Reference

| Phase | Tools | Purpose |
|-------|-------|---------|
| Setup | `runtime_config(action="get_run_configurations")`, `create_run_config` | Ensure config exists |
| Breakpoints | `debug_breakpoints(action="add/remove/list_breakpoints")` | Line breakpoints (supports `condition`, `log_expression`, `temporary`, `pass_count`, `suspend_policy`) |
| Advanced Breakpoints | `debug_breakpoints(action="exception_breakpoint/method_breakpoint/field_watchpoint")` | Exception, method, field |
| Launch (local) | `debug_breakpoints(action="start_session")` | Debug local run config |
| Launch (remote) | `debug_breakpoints(action="attach_to_process")` | Attach to remote JVM |
| Navigate | `debug_step(action="step_over/step_into/step_out/run_to_cursor/resume/pause")` | Move through code |
| Force Navigate | `debug_step(action="force_step_into/force_step_over")` | Bypass step filters (proxies, reflection) / ignore breakpoints in called methods |
| Inspect | `debug_inspect(action="get_variables/get_stack_frames/evaluate")` | Observe state |
| Modify | `debug_inspect(action="set_value", variable_name="x", new_value="42")` | Change variable at runtime to test hypotheses |
| Concurrency | `debug_inspect(action="thread_dump")` | Thread states and stacks |
| Memory | `debug_inspect(action="memory_view")` | Instance counts, leak detection |
| Hotfix | `debug_inspect(action="hotswap")` | Reload changed classes live |
| Advanced | `debug_inspect(action="force_return/drop_frame")` | Alter execution flow |
| Cleanup | `debug_step(action="stop")`, `debug_breakpoints(action="remove_breakpoint")` | Always clean up |

After resolving the issue, use `archival_memory_insert` to record the debugging approach and root cause for future reference.
