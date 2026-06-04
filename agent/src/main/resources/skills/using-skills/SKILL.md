---
name: using-skills
description: Meta-skill that teaches how to find and use skills. Auto-injected into the system prompt — do not call use_skill for this skill.
auto-inject: true
user-invocable: false
---

# Using Skills

Skills provide specialized instructions for specific tasks. Bundled skills ship with the plugin; project skills live in `.agent-skills/`; personal skills in `~/.workflow-orchestrator/skills/`.

## The Rule

**Invoke relevant skills BEFORE any response or action — even before clarifying questions or exploring the codebase.** If there's even a 1% chance a skill applies, call `use_skill` to check first; if the loaded skill turns out wrong for the situation, you don't have to follow it. Do NOT rationalize skipping the check — "it's just a simple question", "I'll do this one thing first", "I already know how", "the skill is overkill", "let me just fix this quickly" all mean you're about to skip a skill you should load. Action = task; check first.

## Skill Priority

When multiple skills apply: **process skills first** (systematic-debugging, brainstorm, writing-plans — they determine HOW to approach), then **implementation skills** (tdd, git-workflow, interactive-debugging), then **delegation** (subagent-driven). E.g. "Fix this bug" → systematic-debugging then tdd for a regression test; "Add a feature" → brainstorm → writing-plans → tdd or subagent-driven.

## Skill Types

**Rigid** (tdd, systematic-debugging): follow exactly — don't adapt away the discipline. **Flexible** (brainstorm, create-skill, git-workflow): adapt principles to context, scaling depth to complexity. The skill itself tells you which.

## When to Load

Match the task to a skill using the trigger keywords in each skill's description (in the Available Skills list), and load it BEFORE starting work. User instructions say WHAT to do, not HOW — "Add X" / "Fix Y" does not mean skip the skill workflow; the user wants the outcome, and the skill ensures quality on the way there.

## How to Invoke

Call `use_skill(skill_name="exact-name")`; the content loads into your context — follow it directly and do NOT re-invoke for the same task. If the skill lists preferred-tools, prioritize them (but you're not restricted to them).
