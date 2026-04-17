---
name: writing-plans
description: Create structured implementation plans with bite-sized tasks, clear acceptance criteria, and file-level guidance that the user can review and approve before any implementation begins. You must always load this skill after calling enable_plan_mode — trigger phrases include "plan this", "create a plan", "write a plan", and "how should I implement", and you should also use it proactively whenever the task touches 2 or more files, crosses module boundaries, adds new features or API endpoints, involves refactoring or cross-module changes, or requires any non-trivial multi-step work. Do not use this for single-file fixes or simple questions that can be answered directly. For example, if the user says "Refactor the notification system" or "Add a new API endpoint with service and tests", call enable_plan_mode first and then load this skill. It walks you through a structured research-then-plan workflow where you first investigate the codebase to understand the scope and dependencies, then produce a concrete and actionable task breakdown with specific files, acceptance criteria, and implementation order that the user can review, comment on, and approve before you write any code.
user-invocable: true
preferred-tools: [read_file, search_code, file_structure, find_definition, find_references, type_hierarchy, diagnostics, run_command, think, enable_plan_mode, plan_mode_respond, agent]
---

# Writing Plans

## Overview

Write comprehensive implementation plans assuming the engineer has zero context for our codebase and questionable taste. Document everything they need to know: which files to touch for each task, code, testing, docs they might need to check, how to test it. Give them the whole plan as bite-sized tasks. DRY. YAGNI. TDD. Frequent commits.

Assume they are a skilled developer, but know almost nothing about our toolset or problem domain. Assume they don't know good test design very well.

**Announce at start:** "I'm using the writing-plans skill to create the implementation plan."

Note: `file_structure` and `type_hierarchy` are deferred tools — activate with `tool_search(query="file structure")` or `tool_search(query="type hierarchy")` before use.

## How the Plan UI Works

Your plan flows through a specific UI pipeline. Understanding this ensures your plan renders correctly:

1. **You call `plan_mode_respond(response=plan_markdown)`** — a full markdown plan for the document viewer (with code blocks, tables, etc.)
2. **Plan summary card** renders in chat — shows the plan summary, approve/revise buttons, and comment count
3. **Plan editor** opens as a full tab when user clicks "View Plan" — the user sees the full markdown with line numbers and can **add inline comments on specific lines**
4. **Approval** switches to act mode. You then use the task_create / task_update / task_list / task_get tools to track execution progress — these flow through the same PlanProgressWidget as a task checklist.
5. **Revision** sends the user's line-level comments back to you. You revise and call `plan_mode_respond` again.

**Execution tracking after approval:** Once in act mode, create tasks via `task_create` (one call per task, outcome-focused subjects) as you scope work. Update status via `task_update` as work progresses (pending → in_progress → completed). Mark deleted for anything superseded. The user sees these in the progress widget in real time.

## Scope Check

If the spec covers multiple independent subsystems, it should have been broken into sub-project specs during brainstorming. If it wasn't, suggest breaking this into separate plans — one per subsystem. Each plan should produce working, testable software on its own.

## File Structure

Before defining tasks, map out which files will be created or modified and what each one is responsible for. This is where decomposition decisions get locked in.

- Design units with clear boundaries and well-defined interfaces. Each file should have one clear responsibility.
- You reason best about code you can hold in context at once, and your edits are more reliable when files are focused. Prefer smaller, focused files over large ones that do too much.
- Files that change together should live together. Split by responsibility, not by technical layer.
- In existing codebases, follow established patterns. If the codebase uses large files, don't unilaterally restructure - but if a file you're modifying has grown unwieldy, including a split in the plan is reasonable.

This structure informs the task decomposition. Each task should produce self-contained changes that make sense independently.

## Bite-Sized Task Granularity

**Each step is one action (2-5 minutes):**
- "Write the failing test" - step
- "Run it to make sure it fails" - step
- "Implement the minimal code to make the test pass" - step
- "Run the tests and make sure they pass" - step
- "Commit" - step

## Plan Markdown Format

Use `### Task N: Title` headers for steps. Each header becomes a section in the plan document viewer with its own comment target. The `steps` parameter should list these task titles for the progress card.

**Every plan MUST use this structure:**

```markdown
Implementation plan for [feature name].

### Task 1: [Component Name]

**Files:**
- Create: `exact/path/to/File.kt`
- Modify: `exact/path/to/Existing.kt`
- Test: `exact/path/to/FileTest.kt`

**Steps:**
1. Write the failing test
2. Run test — verify it fails
3. Write minimal implementation
4. Run test — verify it passes
5. Commit

**Test code:**
[actual test code block]

**Implementation code:**
[actual implementation code block]

### Task 2: [Next Component]
...

### Testing
[How to verify the full implementation works end-to-end]
```

**Key format rules:**
- Text BEFORE the first `### Task` header becomes the plan summary (shown in the plan card)
- Each `### Task N:` header becomes a step the user can comment on individually
- Keep task titles short and descriptive — they appear in the progress checklist
- Put detailed code blocks and commands INSIDE the task (below the header) — they become the step's description

## Presenting the Plan

Call `plan_mode_respond` with `response` (markdown):

```
plan_mode_respond(
  response="Implementation plan for user management API.\n\n### Task 1: Create DTOs\n**Files:**\n- Create: `src/dto/UserRequest.kt`\n...\n\n### Task 2: Write controller test\n...\n\n### Task 3: Implement UserController\n..."
)
```

After approval, create tasks via task_create (one call per outcome-sized work item). Tasks are separate from the plan document; the plan describes strategy, tasks track execution.

## Handling User Comments

After presenting the plan, the user may:

1. **Approve** — you receive an approval message with the task checklist. Switch to implementation.
2. **Add comments** — the user opens the plan in the editor tab and adds inline comments on specific lines. You receive a revision message like:
   ```
   I have comments on your plan. Please revise it:
   - Line 15 (### Task 2: Write controller test): "Add a test for 400 validation error too"
   - Line 32 (```kotlin): "Use @WebMvcTest not @SpringBootTest here"
   Please revise the plan and present the updated version using plan_mode_respond.
   ```
3. **Type a chat message** — freeform feedback while still in plan mode.

When you receive comments, revise the plan and call `plan_mode_respond` again. The plan card updates with the new version.

## No Placeholders

Every step must contain the actual content an engineer needs. These are **plan failures** — never write them:
- "TBD", "TODO", "implement later", "fill in details"
- "Add appropriate error handling" / "add validation" / "handle edge cases"
- "Write tests for the above" (without actual test code)
- "Similar to Task N" (repeat the code — the engineer may be reading tasks out of order)
- Steps that describe what to do without showing how (code blocks required for code steps)
- References to types, functions, or methods not defined in any task

## Remember
- Exact file paths always
- Complete code in every step — if a step changes code, show the code
- Exact tool calls with expected output (use `runtime_exec(action="run_tests")` not `./gradlew`)
- DRY, YAGNI, TDD, frequent commits
- Kotlin/JVM conventions (JUnit 5, MockK, suspend funs, IntelliJ APIs)

## Self-Review

After writing the complete plan, look at the spec with fresh eyes and check the plan against it. This is a checklist you run yourself — not a subagent dispatch.

**1. Spec coverage:** Skim each section/requirement in the spec. Can you point to a task that implements it? List any gaps.

**2. Placeholder scan:** Search your plan for red flags — any of the patterns from the "No Placeholders" section above. Fix them.

**3. Type consistency:** Do the types, method signatures, and property names you used in later tasks match what you defined in earlier tasks? A function called `clearLayers()` in Task 3 but `clearFullLayers()` in Task 7 is a bug.

**4. Task coverage:** Count your `### Task` headers. Does each represent a distinct, outcome-sized work item that can be tracked independently?

If you find issues, fix them inline. No need to re-review — just fix and move on. If you find a spec requirement with no task, add the task.

## Execution Handoff

After the user approves the plan, the session switches back to act mode. You receive an approval message containing the task checklist. Use `ask_followup_question` to offer the execution choice:

```
ask_followup_question(
  question="Plan approved. Two execution options:\n\n1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task with two-stage review (spec compliance + code quality)\n\n2. **Direct Execution** — I execute tasks in this session step by step\n\nWhich approach?",
  options=["1. Subagent-Driven (recommended)", "2. Direct Execution"]
)
```

- If **Subagent-Driven**: load `use_skill(skill_name="subagent-driven")`
- If **Direct Execution**: implement tasks sequentially, creating and updating tasks via task_create and task_update

During execution (either approach), use task_create and task_update to track progress. Tasks appear in the progress widget with spinner/check icons reflecting status.
