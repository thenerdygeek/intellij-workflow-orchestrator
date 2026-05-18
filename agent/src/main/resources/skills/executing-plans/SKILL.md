---
name: executing-plans
description: Execute an approved implementation plan in the current session, sequentially, with verification gates after each task and queued-bug discipline. Load this skill when the user clicks "2. Direct Execution" on the writing-plans Execution Handoff, when the plan has only 1-2 tasks where subagent dispatch overhead isn't worth it, or when consecutive tasks share heavy file state that would force subagent-driven to serialize anyway. You the orchestrator implement each task yourself, run diagnostics + compile + tests after every task, commit before moving on, queue unrelated bugs instead of inline-fixing them, triage those queued bugs after the plan completes, and call attempt_completion exactly once at the end. For plans with 3+ mostly-independent tasks, switch to subagent-driven instead.
---

# Executing Plans

## Overview

Load plan, review critically, execute every task in order with verification gates between each, then triage queued bugs and complete.

**Announce at start:** "I'm using the executing-plans skill to implement this plan."

**Note:** For plans with 3+ mostly-independent tasks, prefer `subagent-driven` — it dispatches fresh subagents per task with two-stage review gates and parallel safety. Use `executing-plans` for small plans, heavy file overlap, live-visibility requirements, or when the user explicitly picked "Direct Execution" at the writing-plans handoff.

## The Process

### Step 1: Load and Review Plan

1. Read the plan file
2. Call `task_list` to confirm the task IDs the plan card created
3. Review critically — questions, ambiguities, gaps
4. If concerns: raise them via `ask_followup_question` before starting
5. If no concerns: proceed

### Step 2: Execute Tasks

For each task in plan order:

1. **Mark in_progress** — `task_update(taskId="t-N", status="in_progress")`. Flip BEFORE you start; the `PlanProgressWidget` shows the spinner immediately.
2. **Follow plan steps exactly** — the plan has bite-sized steps for a reason; don't paraphrase or skip.
3. **Verification gates** — all must pass before marking completed:

   | Gate | Tool call | Pass condition |
   |---|---|---|
   | Diagnostics clean | `diagnostics` on every file you touched | Zero errors |
   | Module compiles | `java_runtime_exec(action="compile_module", module="<mod>")` (Java/Kotlin) or `python_runtime_exec(action="compile_module")` (Python). Fall back to `run_command "./gradlew :module:compileKotlin"` only when neither dedicated action applies. | Exit 0, no compile errors in output |
   | New tests pass | `java_runtime_exec(action="run_tests", ...)`, `python_runtime_exec(action="run_tests", ...)`, or `runtime_exec(action="run_tests")` | All green. **`NO_TESTS_FOUND` is a failure**, not a pass — the runner found no tests; investigate (wrong module path? test class not registered? wrong source set?) and re-run. |
   | No regression in adjacent code | The module's existing test suite | All green |

   If any gate fails, fix forward before continuing. Do not queue verification failures for later — later tasks may build on the broken state.

4. **Commit** — `run_command "git add <paths> && git commit -m '...'"`. Tests + implementation in the same commit. Follow the repo's commit style (run `git log -n 10 --oneline` once at plan start to mirror conventions). **No `Co-Authored-By` trailer** per the user's standing preference.
5. **Mark completed** — `task_update(taskId="t-N", status="completed")`.

### Step 3: Queue Unrelated Bugs (during execution)

If during a task you hit a test failure or defect **unrelated** to the current task — pre-existing flake, broken adjacent module, something your verification sweep surfaced but your edit did not cause:

- **Do NOT inline-fix.** Inline fixes scope-creep the current task, obscure what changed in the commit, and risk breaking unrelated work without a separate verification pass.
- Queue via `task_create(subject="BUG: <one-line>", description="<failure trace, file:line, what's broken, why you believe it's unrelated to the current task>")`. The new task lands at status `pending`.
- Continue with the current task.

Queued BUG: tasks are triaged in Step 4.

### Step 4: Complete Plan

When every original plan task is `completed`:

1. **Final regression sweep** — run the module's full test suite (`java_runtime_exec(action="run_tests", module="<mod>")` or equivalent for Python/other). All green.

2. **Triage queued BUG: tasks.** Read them with `task_list` and classify each into one of two buckets:

   **Related bugs** — caused by, surfaced by, or living in code adjacent to the plan's work. Fix these inline now, one at a time:
   - Implement the fix
   - Run the verification gates from Step 2.3
   - Commit
   - `task_update(taskId="<bug-id>", status="completed")`

   **Unrelated bugs** — pre-existing defects, unrelated modules, flakes you saw during the regression sweep that your plan-work did not touch. Do NOT fix these autonomously. Present them to the user with code references and reproduction scenarios so they can decide:

   ````
   ask_followup_question(
     question="Plan complete. While executing, I found N bugs unrelated to this plan:

     1. <Bug summary> — `path/to/File.kt:LINE`
        Code:
        ```kotlin
        <minimal relevant snippet, 3-8 lines>
        ```
        Example scenario: <concrete reproduction in 1-2 sentences — what input/state triggers the bug, what the wrong behavior is>

     2. <next bug, same shape>

     Which (if any) should I fix before closing the session?",
     options=["Fix all", "Fix specific ones (I'll list)", "Skip — leave as pending tasks"]
   )
   ````

   - If the user picks **Fix all** or **specific ones**: fix each inline, verification gates per fix, commit per fix, `task_update(..., status="completed")` per fix.
   - If the user picks **Skip**: leave the BUG: tasks at `pending` so the user can pick them up later.

3. **Summarize** what shipped — files touched, tests added, commits created. Separate the summary into: plan-work, plan-related bug fixes, unrelated bugs deferred.

4. **Call `attempt_completion(result="<summary>")`** exactly once. This ends the session.

## When to Stop and Ask for Help

**STOP executing immediately when:**

- Blocker (missing dependency, won't compile, instruction unclear)
- Plan has critical gaps preventing the next step
- You don't understand an instruction
- Verification fails more than twice on the same task — the plan or its assumption may be wrong
- You realize mid-plan that `subagent-driven` would have been the right choice (3+ independent tasks emerging, parallel work clearly feasible) — surface the mismatch and ask the user to confirm a switch; don't silently pivot

**Ask for clarification via `ask_followup_question` rather than guessing.**

## When to Revisit Earlier Steps

**Return to Step 1 when:**

- User updates the plan based on your feedback — call `enable_plan_mode` + `plan_mode_respond` for a clean re-presentation
- The fundamental approach needs rethinking

**Don't force through blockers** — stop and ask.

## Remember

- Review the plan critically before starting
- Follow plan steps exactly — they're bite-sized for a reason
- Don't skip verification gates; `NO_TESTS_FOUND` is a failure, not a pass
- Queue unrelated bugs with a `BUG:` prefix; **never** inline-fix them mid-task
- After all plan tasks complete: fix plan-related bugs inline; present unrelated bugs to the user with code + reproduction scenarios and let them decide
- `attempt_completion` is per-plan, called exactly once at the very end — not per-task
- Never start implementation on a protected branch (main/master) without explicit user consent

## Integration

- **`writing-plans`** — creates the plan this skill executes
- **`subagent-driven`** — alternative for plans with 3+ mostly-independent tasks (parallel dispatch + two-stage review gates)
