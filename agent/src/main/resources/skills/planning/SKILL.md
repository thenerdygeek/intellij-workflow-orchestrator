---
name: planning
description: >
  Auto-loaded when plan mode activates. Guides structured planning methodology:
  analysis-first investigation, goal decomposition, step granularity, testing
  strategy, and TDD integration. Follow this workflow for every plan.
disable-model-invocation: false
user-invocable: true
preferred-tools: [read_file, search_code, file_structure, diagnostics, find_definition, find_references, type_hierarchy, call_hierarchy, run_command, think, create_plan, update_plan_step, agent]
---

# Structured Planning

## Overview

Good plans prevent wasted work. Bad plans create more problems than they solve.

**Core principle:** Investigate BEFORE planning. Plan BEFORE coding. Every plan is a contract with the user.

**Violating the letter of this process is violating the spirit of planning.**

## The Iron Law

```
NO PLAN WITHOUT INVESTIGATION FIRST
NO CODE WITHOUT AN APPROVED PLAN
```

If you haven't read the relevant code, you cannot write a plan.
If the user hasn't approved the plan, you cannot write code.

## When This Skill Applies

- Complex tasks involving 3+ files
- Architectural changes or refactoring
- New features or modules
- Cross-module changes
- Database migrations
- API changes affecting multiple consumers
- Anything the user explicitly asks to plan

## Phase 1: Investigation (MANDATORY)

**Before writing ANY plan, gather evidence:**

1. **Read the relevant code**
   - Use `read_file` on all files that will be modified
   - Use `search_code` to find related patterns and usages
   - Use `find_references` to understand impact radius
   - Use `file_structure` to map the module layout

2. **Understand the architecture**
   - Use `type_hierarchy` and `call_hierarchy` for dependency chains
   - Use `diagnostics` to check current code health
   - Read tests to understand expected behavior

3. **Run existing tests/builds**
   - Use `run_command` to run relevant test suites
   - Verify the baseline — what passes now?
   - Note any existing failures (don't plan to fix unrelated issues)

4. **Check git context**
   - Recent changes to files you'll modify
   - Active branches that might conflict

5. **Record findings**
   - Use `think` to organize what you've learned
   - Note: constraints, patterns, dependencies, risks

**Skip none of these steps.** "I already know the codebase" is not investigation.

## Phase 2: Plan Design

**Structure every plan with these sections:**

### Goal (1-2 sentences)
What does this accomplish? What problem does it solve?
Be specific — "improve performance" is not a goal. "Reduce API response time from 2s to 200ms by adding Redis cache" is.

### Approach (2-4 sentences)
High-level strategy. Why this approach over alternatives?
Mention key trade-offs and what you considered and rejected.

### Steps (### heading per step)
Each step should be:
- **Atomic** — completable in 2-5 minutes of agent work
- **Testable** — you can verify it worked before moving on
- **Ordered** — dependencies flow top-down
- **Specific** — includes exact file paths, function names, code patterns

**Good step granularity:**
```markdown
### 1. Create SkillTool.kt
- Path: `agent/src/main/kotlin/.../tools/builtin/SkillTool.kt`
- Implements: `AgentTool` interface
- Parameters: `skill` (required), `args` (optional)
- Returns: full skill content in ToolResult
- Key logic: lookup → preprocess → activate anchor → return content
```

**Bad step granularity:**
```markdown
### 1. Implement the tool
Create the new tool and make it work.
```

### Testing
- What tests to write (file paths, test names)
- What to verify after each step
- Integration test strategy
- How to verify the full change works end-to-end

### Risks (if applicable)
- What could go wrong?
- What's the rollback strategy?
- Are there backward compatibility concerns?

## Phase 3: Plan Review

After calling `create_plan`:

1. **Wait for user approval** — do NOT proceed without it
2. If the user requests changes:
   - Re-investigate if new requirements emerged
   - Call `create_plan` again with revised content
   - Don't just patch — rewrite the affected sections
3. If the user approves:
   - Plan mode auto-deactivates
   - Execute step by step, calling `update_plan_step` to track progress

## Phase 4: Execution

Once the plan is approved:

1. **Follow the plan exactly** — don't "improve" as you go
2. **One step at a time** — mark `running`, execute, mark `done`
3. **Test after each step** — run relevant tests before moving on
4. **If a step fails:**
   - Don't skip it
   - Don't rewrite the plan silently
   - Report to the user: what failed and why
   - Get approval for the revised approach

## Red Flags — STOP and Investigate More

If you catch yourself:
- Writing a plan without reading the code → STOP. Read first.
- Guessing file paths or function names → STOP. Verify with search.
- Writing vague steps ("implement the feature") → STOP. Be specific.
- Planning more than 15 steps → STOP. Decompose into smaller plans.
- Planning to modify files you haven't read → STOP. Read them.
- Skipping the Testing section → STOP. Every plan needs tests.
- Proposing an approach without considering alternatives → STOP. Think harder.

## Common Rationalizations

| Excuse | Reality |
|--------|---------|
| "I know this codebase" | Your knowledge may be stale. Read the files. |
| "It's a simple change" | Simple changes in complex codebases have hidden dependencies. |
| "Testing section not needed" | If you can't describe how to test it, you don't understand it. |
| "This step is obvious, no detail needed" | Obvious to you now. Not obvious to the agent executing it later. |
| "One big step is fine" | Big steps hide complexity. Break them down. |
| "I'll figure out the approach as I code" | That's not planning. That's improvising. |

## Delegation During Planning

For large plans, consider using sub-agents for investigation:
- `agent(subagent_type="explorer", prompt="Analyze the auth module...")` for read-only research
- Multiple agents can investigate different subsystems in parallel
- Synthesize their findings before writing the plan

## Quick Reference

| Phase | Key Activities | Gate |
|-------|---------------|------|
| **1. Investigate** | Read code, search, run tests, understand | Evidence gathered |
| **2. Plan** | Goal, approach, steps, testing, risks | Plan written |
| **3. Review** | User approval, revisions | Plan approved |
| **4. Execute** | Step by step, test each, track progress | All steps done |
