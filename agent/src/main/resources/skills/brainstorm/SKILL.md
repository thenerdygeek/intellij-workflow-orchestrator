---
name: brainstorm
description: Turn ideas into fully formed designs through structured collaborative dialogue before any code is written. Use when the user wants to build something new, add a feature, design architecture, or plan a significant change — this includes any request like "brainstorm", "design", "how should I", "let's plan", "I want to add", "I want to build", "new feature", "architecture for", or "approach for". You should also use this proactively before any non-trivial implementation, because understanding what to build prevents wasted work and rework. For example, if the user says "Add a caching layer" or "Build a notification system" or "How should we structure the API?", load this skill first before writing any code. Do not skip this for features, components, or architectural changes even if you think you already know the answer. This skill walks you through a structured exploration workflow that captures requirements, surfaces constraints, evaluates alternatives, and produces a clear design decision before implementation begins.
user-invocable: true
preferred-tools: [think, ask_followup_question, enable_plan_mode, plan_mode_respond, core_memory_append, archival_memory_insert, read_file, search_code, find_definition, find_references, file_structure, agent]
---

# Brainstorming — Ideas Into Designs

Turn vague ideas into concrete, implementable designs through structured dialogue. This skill uses existing tools — `ask_followup_question` for structured choices (wizard mode), `think` for reasoning, `agent` for codebase research, `enable_plan_mode` + `plan_mode_respond` for the final plan output, `core_memory_append`/`archival_memory_insert` for cross-session persistence.

## Why This Matters

Code written without understanding requirements gets rewritten. The 20 minutes spent brainstorming saves days of rework. Every non-trivial feature benefits from this process.

## The Flow

```
User's idea
  → Understand context (read code, check patterns)
  → Ask clarifying questions (one at a time, use ask_followup_question wizard for structured choices)
  → Think through approaches (use think tool)
  → Propose 2-3 approaches (present trade-offs)
  → User picks direction
  → Present design section by section
  → User approves or revises
  → Switch to plan mode and present implementation plan (enable_plan_mode → plan_mode_respond)
  → Save key decisions to memory (use core_memory_append or archival_memory_insert)
```

## Step 1: Understand the Context

Before asking the user anything, gather context silently. Spend **2-4 tool calls** understanding the landscape — don't map the entire codebase, focus on the area the user's idea touches.

1. **Check @mentions first** — if the user included @file or @folder mentions, those are already in context. Don't re-read them.

2. **Read the codebase** — use `read_file`, `search_code`, `file_structure`, `find_definition` to understand existing patterns, architecture, and conventions. If the idea touches multiple areas, spawn an `explorer` subagent with `thoroughness: "quick"`.

3. **Check existing work** — search for related code, similar features, or previous attempts. The user may not know what already exists.

4. **Assess scope** — if the idea describes multiple independent subsystems, flag this immediately. Don't brainstorm a monolith. Ask the user which idea to start with, or suggest a priority order.

5. **Check memory** — if previous brainstorm decisions exist in memory for this topic, summarize them and ask if the user wants to continue, revise, or start fresh.

Only after you have context should you start asking questions.

## Step 2: Ask Clarifying Questions

Ask questions **one at a time**. For questions with clear options, use `ask_followup_question` in wizard mode (pass `questions` JSON param) to render interactive choices. For open-ended questions, use simple mode (pass `question` string param).

### When to use wizard mode (`questions` param):
- Choosing between approaches (A vs B vs C)
- Selecting features to include/exclude
- Confirming constraints (performance, compatibility, scope)
- Picking technologies or patterns

### When to use simple mode (`question` param):
- Understanding the "why" behind the request
- Exploring edge cases the user hasn't considered
- Clarifying ambiguous requirements
- Open-ended questions with no predefined options

### Question guidelines:
- **One question per message** — don't overwhelm
- **Prefer structured choices** — easier to answer than open-ended
- **Include "Other" option** — let users break out of predefined choices
- **Max 5-7 questions total** — respect the user's time
- **Skip obvious questions** — if the codebase tells you the answer, don't ask

### Example wizard question:
```
ask_followup_question(questions='[{"id":"approach","question":"How should the caching layer work?","type":"single","options":[{"id":"inmemory","label":"In-Memory (ConcurrentHashMap)","description":"Fast, simple, lost on restart"},{"id":"caffeine","label":"Caffeine Cache","description":"TTL, max-size, eviction policies"},{"id":"redis","label":"External Redis","description":"Shared across instances, persistent"},{"id":"other","label":"Something else","description":"I will explain in chat"}]}]')
```

### Example simple question:
```
ask_followup_question(question="What problem are you trying to solve with caching? Is it API latency, repeated computations, or something else?")
```

## Step 3: Think Through Approaches

After gathering requirements, use the `think` tool to reason through the design space:

- What are the 2-3 viable approaches?
- What are the trade-offs (complexity, performance, maintainability)?
- Which approach fits the existing codebase patterns?
- What are the risks and unknowns?
- What's the simplest thing that could work?

**YAGNI ruthlessly.** Remove features the user didn't ask for. Don't design for hypothetical future requirements. The right amount of complexity is the minimum needed for the current goal.

## Step 4: Propose Approaches

Present 2-3 approaches with clear trade-offs. Lead with your recommendation and explain why.

Format:
```
## Approach A: [Name] ← Recommended
[2-3 sentences on what it does]
✓ Pros: [list]
✗ Cons: [list]

## Approach B: [Name]
[2-3 sentences]
✓ Pros: [list]
✗ Cons: [list]
```

If one approach is clearly superior, say so. Don't present false balance.

If the user rejects all approaches, ask them to describe what they have in mind. They may have a specific solution they want validated, not brainstormed.

After presenting, use `ask_followup_question` wizard to let the user pick:
```
ask_followup_question(questions='[{"id":"approach_choice","question":"Which approach do you prefer?","type":"single","options":[{"id":"a","label":"Approach A: [Name]","description":"Recommended — [one-line reason]"},{"id":"b","label":"Approach B: [Name]","description":"[one-line trade-off]"},{"id":"c","label":"Approach C: [Name]","description":"[one-line trade-off]"},{"id":"discuss","label":"Lets discuss more","description":"I have questions or a different idea"}]}]')
```

## Step 5: Present the Design

Once the user picks a direction, present the full design. Scale each section to its complexity — a few sentences if straightforward, more detail if nuanced.

Sections to cover (skip if not relevant):
1. **Architecture** — how components fit together
2. **Key files** — which files to create/modify and what each does
3. **Data flow** — how data moves through the system
4. **Edge cases** — what could go wrong and how to handle it
5. **Testing strategy** — what to test and how
6. **Impact** — what existing code is affected

Present all sections together, then ask: "Does this design look right, or should I adjust anything?" Only break into section-by-section review if the design is highly complex (architecture change affecting 10+ files).

### Design principles:
- **Follow existing patterns** — don't invent new architecture unless the existing one is broken
- **Small, focused units** — each file has one clear responsibility
- **Clear interfaces** — can you understand what a unit does without reading its internals?
- **DRY, YAGNI, TDD** — no duplication, no speculation, tests first

## Step 6: Create the Plan

Once the user approves the design, switch to plan mode and present the implementation plan. The plan renders in the chat with the progress checklist UI.

**If currently in act mode:**
1. Call `enable_plan_mode(reason="Creating implementation plan for [feature name]")`
2. Then call `plan_mode_respond` with the plan and checklist

**If already in plan mode:**
1. Call `plan_mode_respond` directly

The `plan_mode_respond` call should include:
- `response` — the full plan as markdown (architecture, tasks, file paths, test steps)
- `task_progress` — a markdown checklist summarizing the tasks

```
plan_mode_respond(
  response="## Implementation Plan\n\n### Task 1: Create FooService interface\n...",
  task_progress="- [ ] Create FooService interface in core/services/\n- [ ] Implement FooServiceImpl in module/service/\n- [ ] Write failing tests for FooService\n- [ ] Implement and verify tests pass\n- [ ] Update module CLAUDE.md"
)
```

The plan should:
- Break work into tasks of 2-5 minutes each
- Specify exact file paths for each task
- Include test steps (write test → verify fails → implement → verify passes)
- Order tasks by dependency (foundations first)

After the user approves the plan, the session switches back to act mode for implementation.

## Step 7: Save Key Decisions

After the brainstorming session, use `archival_memory_insert` to persist:
- The chosen approach and why alternatives were rejected
- Key constraints or requirements that emerged
- Architecture decisions that should inform future work

This ensures future sessions in this project have the context of what was decided and why.

## Anti-Patterns to Avoid

| Don't | Why | Do instead |
|-------|-----|-----------|
| Ask 10 questions at once | Overwhelming, user gives shallow answers | One question at a time |
| Skip context gathering | You'll suggest patterns that don't fit the codebase | Read code first, ask second |
| Present only one approach | User can't make an informed choice | Always show 2-3 with trade-offs |
| Design for hypothetical futures | Speculative complexity wastes time | Build for current requirements |
| Write code during brainstorming | Premature implementation before design approval | Finish design, then plan, then implement |
| Ignore existing patterns | New patterns create inconsistency | Follow what the codebase already does |
| Over-design simple things | A config change doesn't need architecture diagrams | Scale depth to complexity |

## When to Skip Brainstorming

Not everything needs full brainstorming. Skip directly to implementation for:
- Bug fixes with clear root cause
- Typo/rename changes
- Adding a single test
- Configuration changes
- Direct user instruction: "change X to Y"
- When the user explicitly says they don't need brainstorming ("just do it", "I already have a plan") — acknowledge their direction and proceed to plan mode or implementation

Use brainstorming for:
- New features
- Architecture changes
- Refactoring decisions
- "How should I approach X?"
- Anything touching 3+ files
