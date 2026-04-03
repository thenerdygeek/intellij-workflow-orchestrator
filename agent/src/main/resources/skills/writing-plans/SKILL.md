---
name: writing-plans
description: >
  Create structured implementation plans with bite-sized tasks, TDD steps, and file-level guidance.
  Triggers on: "plan this", "create a plan", "write a plan", multi-file tasks, new features, refactoring,
  cross-module changes. Always load after enable_plan_mode.
preferred-tools: [read_file, search_code, file_structure, find_definition, find_references, type_hierarchy, diagnostics, run_command, think, create_plan, update_plan_step, agent]
---

# Writing Plans

## Overview

Write comprehensive implementation plans assuming the engineer has zero context for our codebase and questionable taste. Document everything they need to know: which files to touch for each task, code, testing, docs they might need to check, how to test it. Give them the whole plan as bite-sized tasks. DRY. YAGNI. TDD. Frequent commits.

Assume they are a skilled developer, but know almost nothing about our toolset or problem domain. Assume they don't know good test design very well.

**Announce at start:** "I'm using the writing-plans skill to create the implementation plan."

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

## Plan Structure

Use `create_plan` with two parameters:
1. `title` — short display title for the plan card
2. `markdown` — full plan as a markdown document

**Every plan MUST use this structure:**

```markdown
> **Execution:** Use Skill(skill="subagent-driven") to execute this plan — fresh subagent per task with two-stage review (spec compliance + code quality).

## Goal
[One sentence describing what this builds]

## Architecture
[2-3 sentences about approach and key technologies]

### Task N: [Component Name]

**Files:**
- Create: `exact/path/to/File.kt`
- Modify: `exact/path/to/Existing.kt`
- Test: `exact/path/to/FileTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `specific behavior description`() {
    val result = function(input)
    assertEquals(expected, result)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module:test --tests "...Test.specific behavior description"`
Expected: FAIL with "function not defined"

- [ ] **Step 3: Write minimal implementation**

```kotlin
fun function(input: Type): ReturnType {
    return expected
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module:test --tests "...Test.specific behavior description"`
Expected: PASS

- [ ] **Step 5: Commit**

## Testing
[How to verify the full implementation works end-to-end]
```

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
- Exact commands with expected output
- DRY, YAGNI, TDD, frequent commits
- Kotlin/JVM conventions (JUnit 5, MockK, suspend funs, IntelliJ APIs)

## Self-Review

After writing the complete plan, look at the spec with fresh eyes and check the plan against it. This is a checklist you run yourself — not a subagent dispatch.

**1. Spec coverage:** Skim each section/requirement in the spec. Can you point to a task that implements it? List any gaps.

**2. Placeholder scan:** Search your plan for red flags — any of the patterns from the "No Placeholders" section above. Fix them.

**3. Type consistency:** Do the types, method signatures, and property names you used in later tasks match what you defined in earlier tasks? A function called `clearLayers()` in Task 3 but `clearFullLayers()` in Task 7 is a bug.

If you find issues, fix them inline. No need to re-review — just fix and move on. If you find a spec requirement with no task, add the task.

## Execution Handoff

After calling `create_plan`, once the user approves, offer execution choice:

**"Plan approved. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task with two-stage review (spec compliance + code quality). Use: `Skill(skill="subagent-driven")`

**2. Direct Execution** — I execute tasks in this session step by step, tracking progress with `update_plan_step`.

**Which approach?"**
