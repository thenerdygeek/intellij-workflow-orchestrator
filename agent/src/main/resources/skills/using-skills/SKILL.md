---
name: using-skills
description: Meta-skill that teaches how to find and use skills. Auto-injected into the system prompt — do not call use_skill for this skill.
auto-inject: true
user-invocable: false
---

# Using Skills

Skills provide specialized instructions for specific tasks. They are loaded dynamically — bundled skills ship with the plugin, project skills live in `.agent-skills/`, and personal skills in `~/.workflow-orchestrator/skills/`.

## The Rule

**Invoke relevant skills BEFORE any response or action.** Even a 1% chance a skill might apply means you should call `use_skill` to check. If the loaded skill turns out to be wrong for the situation, you don't need to follow it — but you MUST check first.

## Red Flags — STOP and check for skills

These thoughts mean you're rationalizing skipping a skill:

| Thought | Reality |
|---------|---------|
| "This is just a simple question" | Questions are tasks. Check for skills. |
| "I need more context first" | Skill check comes BEFORE clarifying questions. |
| "Let me explore the codebase first" | Skills tell you HOW to explore. Check first. |
| "I can check git/files quickly" | Files lack conversation context. Check for skills. |
| "Let me gather information first" | Skills tell you HOW to gather information. |
| "This doesn't need a formal skill" | If a skill exists for it, use it. |
| "I remember this skill" | Skills evolve. Read the current version via use_skill. |
| "This doesn't count as a task" | Action = task. Check for skills. |
| "The skill is overkill" | Simple things become complex. Use it. |
| "I'll just do this one thing first" | Check BEFORE doing anything. |
| "This feels productive" | Undisciplined action wastes time. Skills prevent this. |
| "I know how to do this" | Knowing the concept ≠ following the process. Invoke it. |
| "Let me just fix this quickly" | systematic-debugging exists for a reason. Load it. |
| "I can write tests after" | tdd enforces test-FIRST. Load it. |
| "I can plan this in my head" | writing-plans ensures nothing is missed. Load it. |

## Skill Priority

When multiple skills could apply, use this order:

1. **Process skills first** (systematic-debugging, brainstorm, writing-plans) — these determine HOW to approach the task
2. **Implementation skills second** (tdd, git-workflow, interactive-debugging) — these guide execution
3. **Delegation skills last** (subagent-driven) — these orchestrate multi-step work

Examples:
- "Fix this bug" → systematic-debugging first, then tdd for regression test
- "Add a new feature" → brainstorm first, then writing-plans, then tdd or subagent-driven
- "Refactor the notification system" → writing-plans first (it's multi-file), then subagent-driven
- "What changed on this branch?" → git-workflow
- "Create a skill for our deploy process" → create-skill

## Skill Types

**Rigid** (tdd, systematic-debugging): Follow exactly. Don't adapt away the discipline. These exist because skipping steps leads to bugs and wasted time.

**Flexible** (brainstorm, create-skill, git-workflow): Adapt principles to context. Scale depth to complexity.

The skill's instructions tell you which type it is.

## Skill-Driven Workflow Triggers

When the task matches one of these patterns, load the skill BEFORE starting work:

| Trigger | Skill to Load |
|---------|--------------|
| Bug, test failure, error, "not working", exception, NPE | systematic-debugging |
| New feature, new endpoint, adding functionality | tdd |
| Multi-step work, 2+ files, cross-module, refactoring | writing-plans (switch to plan mode first) |
| Approved plan with 3+ tasks | subagent-driven |
| Git operations, branches, diffs, blame, history | git-workflow |
| "Brainstorm", "design", "how should I approach" | brainstorm |
| "Create a skill", "turn this into a workflow" | create-skill |
| Runtime debugging, breakpoints, "step through" | interactive-debugging (via systematic-debugging escalation) |

## User Instructions

User instructions say WHAT to do, not HOW. "Add X" or "Fix Y" doesn't mean skip the skill workflow. The user wants the outcome — the skill ensures quality on the way there.

## How to Invoke

1. Read the user's request and scan the available skills list
2. Call `use_skill(skill_name="exact-name")` — the skill content loads into your context
3. Follow the returned instructions directly — do NOT call use_skill again for the same task
4. If the skill has a preferred-tools list, prioritize those tools (but you're not restricted to them)
