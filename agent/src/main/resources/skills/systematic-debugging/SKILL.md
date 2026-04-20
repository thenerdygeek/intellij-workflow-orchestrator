---
name: systematic-debugging
description: Structured root-cause investigation that must be loaded before attempting any fix. Use this whenever you encounter any bug, test failure, build failure, runtime error, or unexpected behavior — trigger phrases include "failing", "broken", "NPE", "exception", "not working", "error", "bug", "wrong output", "unexpected", "why does this", "crash", "null pointer", as well as test failures, build failures, stack traces, CI failures, and flaky tests. You must always load this skill before proposing or attempting fixes, even if the fix seems obvious, because guessing at solutions without investigation leads to wasted iterations and incomplete fixes. For example, if the user says "Tests are failing", "This returns wrong results", or "Build broke after my change", load this skill immediately before doing anything else. It walks you through a structured investigate-hypothesize-verify workflow that uses IDE diagnostics, call hierarchy analysis, dataflow tracing, and git blame to systematically identify the actual root cause rather than treating symptoms.
user-invocable: true
preferred-tools: [diagnostics, search_code, read_file, run_command, find_references, find_definition, think, runtime_exec, call_hierarchy, coverage]
---

# Systematic Debugging

## Overview

Random fixes waste time and create new bugs. Quick patches mask underlying issues.

**Core principle:** ALWAYS find root cause before attempting fixes. Symptom fixes are failure.

## When to Use

Use for ANY technical issue:
- Test failures
- Bugs in production
- Unexpected behavior
- Performance problems
- Build/compilation failures
- Integration issues (Jira, Bamboo, SonarQube, Bitbucket API errors)

**Use this ESPECIALLY when:**
- Under time pressure (emergencies make guessing tempting)
- "Just one quick fix" seems obvious
- You've already tried multiple fixes
- Previous fix didn't work

## The Iron Law

```
NO FIXES WITHOUT ROOT CAUSE INVESTIGATION FIRST
```

If you haven't completed Phase 1, you cannot propose fixes.

## The Four Phases

Complete each phase before proceeding to the next.

### Phase 1: Root Cause Investigation

**BEFORE attempting ANY fix:**

1. **Read Error Messages Carefully**
   - Use `runtime_exec(action="get_test_results")` to get structured test failures (assertion messages, stack traces per test)
   - Use `runtime_exec(action="get_run_output")` to check application logs for errors (filter with `ERROR|WARN|Exception`)
   - Use `diagnostics` tool to get IDE-level error analysis (PSI errors, unresolved references, compiler warnings)
   - Don't skip past errors — read stack traces completely
   - Note line numbers, file paths, error codes

2. **Reproduce Consistently**
   - Use `runtime_exec(action="get_running_processes")` to check if the application is already running
   - Use `runtime_exec(action="run_tests")` to run the failing test in isolation (returns structured results)
   - Use `runtime_exec(action="compile_module")` to verify compilation errors
   - Can you trigger it reliably? What are the exact steps?

3. **Check Recent Changes**
   - Use `run_command("git status")` to see uncommitted changes
   - Use `run_command("git blame <failing-file>")` on the failing file to see who changed what and when
   - What changed that could cause this?

4. **Use `think` Tool to Reason**
   - Before investigating, call `think` to organize your approach:
     "What are the possible causes? What evidence would distinguish them?"
   - After gathering evidence, call `think` again:
     "Based on what I found, the root cause is likely X because Y"

5. **Trace Data Flow**
   - Use `find_definition` to trace where a function/class is defined
   - Use `find_references` to see all callers of the failing code
   - Use `search_code` to find related patterns across the codebase
   - Where does the bad value originate? Keep tracing up until you find the source.

6. **For Build/CI Failures**
   - Use `bamboo_builds(action="get_build_log")` and `bamboo_builds(action="get_test_results")` for structured failure information before investigating code
   - For PR build failures, use `bitbucket_repo(action="get_build_statuses")` to check the latest CI results
   - CI logs often reveal the root cause faster than local investigation

7. **Gather Evidence in Multi-Component Systems**
   When the system has multiple layers (API → Service → Repository → Database):
   - Use `read_file` to examine each layer
   - Use `find_references` to trace the call chain
   - Add diagnostic logging at component boundaries if needed
   - Run once to gather evidence showing WHERE it breaks

### Phase 1.5: Bisection (When Phase 1 Is Inconclusive)

If Phase 1 didn't reveal a clear root cause, narrow the suspect area using bisection before escalating to the debugger.

**Code bisection** — binary search the suspect code region:
1. Identify the entry point and exit point of the buggy flow
2. Set a strategic breakpoint at the midpoint (or use `think` to reason about it)
3. Is the state correct at the midpoint? If yes → bug is in the second half. If no → first half.
4. Repeat until the suspect region is small enough to read

**Change bisection** — find the commit that introduced the bug:
1. `run_command("git log --oneline -20")` — find recent commits
2. Pick the midpoint commit, read the code at that ref via `run_command("git show <ref>:<path>")`
3. Is the bug present? Binary search through the commit history.
4. Once found: `run_command("git show <hash>")` — read the exact change

**Coverage-guided narrowing** — when you have a failing test:
1. `coverage(action="run_with_coverage", test_class="FailingTest")` — see which lines the failing test covers
2. Compare with a passing test's coverage — lines uniquely covered by the failing test are prime suspects

### Escalation: Do You Need the Debugger?

After Phase 1 (and optionally 1.5), decide your next approach:

**Stay with static analysis (default path) when:**
- Error message + stack trace clearly points to the bug
- The bug is in logic you can read and reason about
- A log statement or assertion would confirm the hypothesis
- The fix is obvious from code reading

**Escalate to interactive debugging when:**
- The bug depends on runtime state you can't determine from code reading alone
- You need to inspect what value a variable actually holds at a specific execution point
- The call chain is too complex to trace statically (e.g., Spring proxies, AOP, dynamic dispatch)
- You suspect a race condition, timing issue, or ordering problem
- Previous static analysis (Phase 1) didn't reveal the root cause
- You need to verify what a method actually returns vs. what you expect

**Match the bug type to the right debugging pattern:**

| Bug Type | Best Interactive Pattern |
|----------|------------------------|
| NPE / ClassCastException | Pattern 4: Exception Breakpoint |
| Only reproduces on staging/Docker | Pattern 5: Remote Debugging |
| Deadlock, hang, race condition | Pattern 6: Thread Dump |
| Field has wrong value, unknown writer | Pattern 7: Field Watchpoint |
| Need to see runtime state at one point | Pattern 1: Strategic Breakpoint |
| Need to trace data flow across methods | Pattern 2: Observation Breakpoints |

**If escalating:** activate the `interactive-debugging` skill, then return here for Phase 2 after.

Most bugs (>80%) are solvable with static analysis. Reserve the debugger for the cases where
you genuinely need to observe runtime state.

### Phase 2: Pattern Analysis

**Find the pattern before fixing:**

1. **Find Working Examples**
   - Use `search_code` to find similar working code in the same codebase
   - What works that's similar to what's broken?

2. **Compare Against References**
   - Use `read_file` to read the reference implementation completely
   - Don't skim — read every line
   - Understand the pattern fully before applying

3. **Identify Differences**
   - Use `think` to list every difference between working and broken code
   - Don't assume "that can't matter"

4. **Understand Dependencies**
   - Use `find_definition` and `type_hierarchy` to understand class relationships
   - What other components does this need?
   - What Spring beans, configurations, or injections are involved?

### Phase 3: Hypothesis and Testing

**Scientific method:**

1. **Form Single Hypothesis**
   - Call `think` with: "I think X is the root cause because Y"
   - Be specific, not vague

2. **Test Minimally**
   - Make the SMALLEST possible change to test hypothesis
   - One variable at a time
   - Don't fix multiple things at once

3. **Verify Before Continuing**
   - Use `runtime_exec(action="run_tests")` to verify the specific test
   - Use `runtime_exec(action="compile_module")` to verify compilation
   - Use `diagnostics` to check for new issues introduced
   - Did it work? Yes → Phase 4. No → form NEW hypothesis

4. **When You Don't Know**
   - Say "I don't understand X"
   - Use `agent` to spawn an explorer subagent to investigate a specific aspect while you continue
   - Check SonarQube for related issues: use `sonar(action="issues")` filtered to the affected file

### Phase 4: Implementation

**Fix the root cause, not the symptom:**

1. **Create Failing Test Case**
   - Write the simplest reproduction test
   - Use `runtime_exec(action="run_tests")` to verify it fails
   - MUST have before fixing

2. **Implement Single Fix**
   - Address the root cause identified in Phase 1
   - ONE change at a time
   - No "while I'm here" improvements
   - No bundled refactoring

3. **Verify Fix**
   - Use `runtime_exec(action="run_tests")` — test passes now?
   - Use `runtime_exec(action="compile_module")` — no compilation errors?
   - Use `diagnostics` — no new issues introduced?
   - Use `sonar(action="issues")` on the file — no new code smells?

4. **If Fix Doesn't Work**
   - STOP
   - Count: How many fixes have you tried?
   - If < 3: Return to Phase 1, re-analyze with new information
   - If ≥ 3: STOP — this is likely an architectural problem. Explain to the user what you've tried and ask for guidance.

5. **If 3+ Fixes Failed: Question Architecture**
   Pattern indicating architectural problem:
   - Each fix reveals new shared state/coupling/problem in different place
   - Fixes require massive refactoring to implement
   - Each fix creates new symptoms elsewhere

   **Use `think` to analyze:** "Three fixes failed. The pattern I'm seeing is X. This suggests the architecture needs Y rather than another point fix."

   **Discuss with the user before attempting more fixes.**

6. **Record the Fix**
   After resolving a complex bug, use `archival_memory_insert` to record the root cause and fix approach for future sessions.

## Project-Type Diagnostic Flows

Before diving into generic debugging, check if the bug matches a project-specific pattern:

**Spring Boot failures:**
1. Check `spring(action="context")` for the bean graph — is the expected bean registered?
2. Check `endpoints(action="list", framework="Spring")` (or `spring(action="endpoints")` on IntelliJ Community) — is the endpoint mapped correctly?
3. For startup failures: search logs for `BeanCreationException`, `UnsatisfiedDependencyException`
4. For auto-config issues: check `spring(action="boot_autoconfig")` for matched/unmatched conditions

**Integration test / Testcontainers failures:**
1. Check if the failure is in container startup or test logic — these are very different bugs
2. For container startup failures: check container logs via `run_command` before investigating code
3. For flaky tests: run the test 5x in isolation (`runtime_exec(action="run_tests")`) to confirm flakiness
4. For flaky tests: check for shared mutable state, timing dependencies, or port conflicts

**Automation suite / E2E test failures:**
1. Check if it's a single test or a pattern across tests — pattern suggests shared state or environment
2. For timing-sensitive failures: use log breakpoints with timestamps (Pattern 2 in interactive-debugging)
3. For tests depending on external services: check service availability before investigating test logic

**ClassLoader / infrastructure failures:**
1. Exception breakpoint on `ClassNotFoundException` or `NoClassDefFoundError`
2. Use `debug_inspect(action="evaluate", expression="Thread.currentThread().getContextClassLoader()")` to inspect the ClassLoader hierarchy
3. For Gradle plugin bugs: run `./gradlew myTask -Dorg.gradle.debug=true` and attach via remote debugging

## Hypothesis Ranking Heuristics

When forming hypotheses in Phase 1 step 4, use this priority order (research-backed — most common causes first):

1. **Null / missing data** — an input, config value, or dependency is null or absent
2. **Wrong configuration** — property file, environment variable, Spring profile, or feature flag
3. **Recent change** — regression from the most recently modified code (`run_command("git blame <failing-file>")` the failing area)
4. **State mutation** — shared mutable state modified by another thread or call path
5. **API contract violation** — caller passing wrong types, wrong order, or missing required fields
6. **Race condition** — timing-dependent behavior that's inconsistent across runs
7. **Environment difference** — works locally but fails in CI/staging (different JDK, OS, network)

Check the most likely cause first. Don't investigate rare causes until common ones are ruled out.

## Red Flags — STOP and Follow Process

If you catch yourself thinking:
- "Quick fix for now, investigate later"
- "Just try changing X and see if it works"
- "Add multiple changes, run tests"
- "Skip the test, I'll manually verify"
- "It's probably X, let me fix that"
- "I don't fully understand but this might work"

**ALL of these mean: STOP. Return to Phase 1.**

## Tool Usage Quick Reference

| Phase | Primary Tools | Purpose |
|-------|--------------|---------|
| 1. Root Cause | `runtime_exec(action="get_test_results")`, `runtime_exec(action="get_run_output")`, `diagnostics`, `run_command("git blame <file>")`, `find_references`, `search_code`, `think` | Understand WHAT and WHY |
| 1.5 Bisection | `run_command("git log --oneline -20")`, `run_command("git show <hash>")`, `coverage(action="run_with_coverage")`, `think` | Narrow suspect area |
| 2. Pattern | `search_code`, `read_file`, `find_definition`, `type_hierarchy`, `think` | Find working patterns |
| 3. Hypothesis | `think`, `runtime_exec(action="run_tests")`, `runtime_exec(action="compile_module")`, `diagnostics` | Test theory minimally |
| 4. Implementation | `edit_file`, `runtime_exec(action="run_tests")`, `runtime_exec(action="compile_module")`, `diagnostics`, `sonar(action="issues")` | Fix and verify |
| Spring-specific | `spring(action="context/boot_autoconfig")`, `endpoints(action="list")`, `diagnostics` | Framework diagnostics |

## Defense-in-Depth

After finding and fixing a bug, add validation at EVERY layer data passes through:

1. **Entry Point** — Validate input at the public API boundary
2. **Business Logic** — Ensure data makes sense for the operation
3. **Framework Guards** — Use Spring's `@Valid`, `@NotNull`, `requireNotNull()`
4. **Logging** — Add diagnostic logging at the point where the bug occurred

Don't stop at one validation point. Make the bug structurally impossible.

## Root Cause Tracing

When a bug manifests deep in the call stack:

1. **Observe the symptom** — what error, where?
2. **Find immediate cause** — use `find_definition` to read the failing code
3. **Ask: what called this?** — use `find_references` to trace callers
4. **Keep tracing up** — follow the call chain until you find the source
5. **Fix at source** — not where the error appears

**NEVER fix just where the error appears.** Trace back to the original trigger.
