---
name: subagent-driven
description: Execute approved implementation plans by dispatching parallel subagents — one fresh agent per task — with two-stage review gates for spec compliance and code quality. You must load this skill whenever a plan with 3 or more tasks has been approved by the user, whether they clicked the approve button on a plan card or said something like "execute the plan", "start implementing", or "run the plan". Do not attempt to execute multi-task plans manually by working through tasks sequentially yourself — this skill handles parallel dispatch, file ownership coordination between agents, progress tracking with plan step updates, and quality review gates that catch issues before integration. For plans with only 1-2 tasks, you can execute directly with update_plan_step instead. This skill gives you a complete dispatch-review-integrate workflow that maximizes throughput while preventing file conflicts between concurrent subagents.
user-invocable: true
preferred-tools: [agent, read_file, search_code, think, diagnostics, run_command]
---

# Subagent-Driven Development

Execute plan by dispatching fresh subagent per task, with two-stage review after each: spec compliance review first, then code quality review.

**Why subagents:** You delegate tasks to specialized agents with isolated context. By precisely crafting their instructions and context, you ensure they stay focused and succeed at their task. They should never inherit your session's context or history — you construct exactly what they need. This also preserves your own context for coordination work.

**Core principle:** Fresh subagent per task + two-stage review (spec then quality) = high quality, fast iteration

## When to Use

- Have an approved implementation plan (from plan mode: `enable_plan_mode` + `plan_mode_respond`)
- Tasks are mostly independent
- Want to stay in this session (not switch to a separate session)

## The Process

```
1. Read plan, extract all tasks with full text and context
2. For each task:
   a. Dispatch implementer subagent (coder type)
   b. If implementer asks questions → answer, re-dispatch
   c. Implementer implements, tests, commits, self-reviews
   d. Dispatch spec reviewer subagent (reviewer type)
   e. If spec issues found → implementer fixes → reviewer re-reviews
   f. Dispatch code quality reviewer subagent (reviewer type)
   g. If quality issues found → implementer fixes → reviewer re-reviews
   h. Update progress via task_update calls as subagent completes each task
3. After all tasks: dispatch final reviewer for entire implementation
```

## Subagent Selection: Scopes vs Agent Types

There are two ways to configure a subagent:

**Option 1: `scope`** — generic roles with automatic tool sets:

| Role | scope | Tools |
|------|-------|-------|
| **Implementer** | `implement` | Full write access (edit, create, run, test) |
| **Spec reviewer** | `review` | Read-only + diagnostics/inspections |
| **Code quality reviewer** | `review` | Read-only + diagnostics/inspections |
| **Explorer** | `research` | Read-only (supports up to 5 parallel prompts) |

**Option 2: `agent_type`** — specialist personas with curated prompts and tool sets. Prefer these when the task matches a specialist's domain:

| agent_type | Use For |
|-----------|---------|
| `code-reviewer` | Comprehensive code review (PR diffs, commit ranges, branch comparisons) |
| `architect-reviewer` | Architecture review (module boundaries, dependencies, layering) |
| `test-automator` | Writing test suites (JUnit 5, MockK, slice tests, integration tests, TDD) |
| `spring-boot-engineer` | Spring Boot features (endpoints, services, JPA, security, migrations) |
| `refactoring-specialist` | Safe refactoring with tests before/after and rollback on failure |
| `devops-engineer` | CI/CD, Docker, deployment scripts, build system |
| `security-auditor` | OWASP Top 10, dependency vulnerabilities, auth review |
| `performance-engineer` | Profiling, bottleneck identification, query optimization |

When `agent_type` is set, `scope` is ignored — the agent type provides its own curated system prompt and tool set.

**Decision rule:** If a bundled agent type matches the task, use it. The specialist prompt gives better results than a generic scope. Fall back to `scope` for tasks that don't fit any specialist.

## Implementer Prompt Template

When dispatching an implementer subagent. Use `scope="implement"` for general tasks, or a specialist `agent_type` if the task matches one (e.g., `agent_type="spring-boot-engineer"` for Spring features, `agent_type="test-automator"` for writing tests):

```
agent(scope="implement", description="Implement Task N: [name]", prompt="""
You are implementing Task N: [task name]

## Task Description

[FULL TEXT of task from plan — paste it here, don't make subagent read the plan]

## Context

[Where this fits, dependencies, architectural context]

## Before You Begin

If you have questions about the requirements, approach, dependencies, or anything unclear — ask them now. Raise concerns before starting work.

## Your Job

Once you're clear on requirements:
1. Implement exactly what the task specifies
2. Write tests following TDD (failing test → minimal code → verify)
3. Run tests: `./gradlew :module:test`
4. Verify implementation with `diagnostics`
5. Commit your work
6. Self-review: completeness, quality, YAGNI, testing
7. Report back

## Self-Review Before Reporting

- Did I fully implement everything in the spec?
- Are names clear and accurate?
- Did I avoid overbuilding (YAGNI)?
- Do tests verify behavior (not mock behavior)?
- Did I follow existing patterns in the codebase?

If you find issues during self-review, fix them before reporting.

## Report Format

- **Status:** DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT
- What you implemented
- What you tested and test results
- Files changed
- Self-review findings
- Any issues or concerns

Use DONE_WITH_CONCERNS if you have doubts. Use BLOCKED if you cannot complete.
Use NEEDS_CONTEXT if you need information that wasn't provided.
Never silently produce work you're unsure about.
""")
```

## Spec Reviewer Prompt Template

After implementer reports DONE, dispatch a spec compliance reviewer:

```
agent(scope="review", description="Review spec compliance for Task N", prompt="""
You are reviewing whether an implementation matches its specification.

## What Was Requested

[FULL TEXT of task requirements from plan]

## What Implementer Claims They Built

[From implementer's report]

## CRITICAL: Do Not Trust the Report

The implementer's report may be incomplete or optimistic. You MUST verify independently.

**DO NOT:** Take their word for what they implemented or trust claims about completeness.

**DO:** Read the actual code, compare to requirements line by line, check for missing pieces.

## Your Job

Read the implementation code and verify:

**Missing requirements:** Did they implement everything requested? Did they skip anything?

**Extra/unneeded work:** Did they build things that weren't requested? Over-engineer?

**Misunderstandings:** Did they interpret requirements differently than intended?

**Verify by reading code, not by trusting the report.**

Report:
- Pass: Spec compliant (everything matches after code inspection)
- Fail: Issues found (list specifically what's missing or extra, with file:line references)
""")
```

## Code Quality Reviewer Prompt Template

After spec compliance passes, dispatch a code quality reviewer. For thorough reviews, prefer `agent_type="code-reviewer"` over generic `scope="review"`:

```
agent(agent_type="code-reviewer", description="Code quality review for Task N", prompt="""
Review the implementation for code quality.

## What Was Implemented
[From implementer's report]

## Review Checklist

1. Does each file have one clear responsibility?
2. Are units decomposed so they can be understood and tested independently?
3. Are names clear and accurate?
4. Is error handling appropriate?
5. Are tests comprehensive and testing real behavior (not mocks)?
6. Does it follow existing codebase patterns?
7. Any security concerns (injection, credential leakage)?
8. Did this change create overly large files?

## Report Format

- **Strengths:** What was done well
- **Issues:** Critical / Important / Minor (with file:line references)
- **Assessment:** Approved | Needs Changes
""")
```

## Handling Implementer Status

**DONE:** Proceed to spec compliance review.

**DONE_WITH_CONCERNS:** Read the concerns before proceeding. If about correctness or scope, address before review. If observations ("this file is getting large"), note and proceed.

**NEEDS_CONTEXT:** Provide the missing context and re-dispatch.

**BLOCKED:** Assess the blocker:
1. Context problem → provide more context, re-dispatch
2. Needs more reasoning → re-dispatch with more thorough prompt
3. Task too large → break into smaller pieces
4. Plan itself is wrong → escalate to the user

**Never** ignore an escalation or force retry without changes.

## Review Loops

If a reviewer finds issues:
1. Dispatch a **new** implementer subagent with the fix instructions (sub-agents cannot be resumed — spawn a fresh one with the reviewer's findings in the prompt)
2. Dispatch reviewer again to re-review the fixes
3. Repeat until approved
4. Don't skip the re-review

**Spec compliance MUST pass before code quality review.** Wrong order wastes time.

## Example Workflow

```
"I'm using Subagent-Driven Development to execute this plan."

[Read plan, extract all 5 tasks with full text]

Task 1: Create SkillTool.kt

[Dispatch coder subagent with full task text + context]

Implementer: "Before I begin — should the tool return content or just a confirmation?"
Me: "Full content in ToolResult, matching Claude Code's design."

Implementer: DONE
  - Created SkillTool.kt with Skill tool
  - 8 tests, all passing
  - Self-review: clean

[Dispatch reviewer for spec compliance]
Spec reviewer: Pass — all requirements met

[Dispatch reviewer for code quality]
Code reviewer: Approved. Minor: FQN could use import.

[Dispatch new implementer to fix minor issue]
[Call task_update(taskId="t-1", status="completed") to mark Task 1 done]

Task 2: ...
```

## Red Flags — Never Do These

- Skip reviews (spec compliance OR code quality)
- Proceed with unfixed issues
- Dispatch multiple implementers in parallel (file conflicts — implement scope is sequential only)
- Make subagent read plan file (provide full text instead)
- Skip context (subagent needs to understand where task fits)
- Ignore subagent questions
- Start code quality review before spec compliance passes
- Move to next task while review has open issues
- Let implementer self-review replace actual review (both needed)

## Advantages

**vs. Direct execution:**
- Fresh context per task (no confusion from prior work)
- Parallel-safe (subagents don't interfere)
- Review checkpoints catch issues early
- Subagent can ask questions before AND during work

**Quality gates:**
- Self-review catches issues before handoff
- Spec compliance prevents over/under-building
- Code quality ensures implementation is well-built
- Review loops ensure fixes actually work
