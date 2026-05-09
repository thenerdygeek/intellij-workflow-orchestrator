# `use_skill` — extended notes

## Why this exists

The agent loop is a stateless ReAct cycle — each turn the LLM gets the same system
prompt plus the conversation history, decides on a tool, runs it, and the cycle
repeats. There is no native way for the LLM to say "for the rest of this task,
follow this multi-step methodology" except by re-stating the methodology in every
response, which (a) wastes tokens, and (b) decays as the conversation truncates.

`use_skill` solves that. A skill is a Markdown file with YAML frontmatter — TDD,
systematic-debugging, git-workflow, etc. — that codifies a procedure. When the LLM
activates one, the body is pinned to Section 6 of the system prompt and re-injected
on every rebuild, including after `ContextManager` compaction. The procedure
survives the conversation getting shorter.

## Discovery vs activation

These are two distinct moments in the skill lifecycle:

- **Discovery** happens at session start (and lazily on every `use_skill` call).
  `InstructionLoader.discoverSkills()` walks three roots:
  1. Bundled (classpath `/skills/`)
  2. User (`~/.workflow-orchestrator/skills/`)
  3. Project (`{basePath}/.workflow/skills/`)

  Project overrides user; user overrides bundled, by skill name. Each skill's YAML
  frontmatter `description` field is harvested into a one-line listing rendered in
  Section 6 of the system prompt — this is what the LLM scans when deciding
  whether a skill applies to the current task.

- **Activation** is `use_skill(skill_name="...")`. Discovery names ≠ activation
  cost. Listing all available skills is cheap (one line each); activation reads
  the full SKILL.md plus optional language variant and injects it.

## Override precedence and the "user said no" exit

A custom project skill at `.workflow/skills/tdd/SKILL.md` shadows the bundled
`tdd` skill. This is intentional — teams override the canonical procedure with
their conventions. The override is name-keyed, not content-merged: the bundled
content is invisible once a project skill of the same name exists.

There is no opt-out flag for activation in the current implementation. If a user
wants to disable a skill mid-session, they must remove it via UI or filesystem
and the next `use_skill` discovery cycle will reflect the change. Discovery is
lazy — re-runs on every `use_skill` call — so disabling takes effect immediately.

## The compaction-survival contract

`ToolResult.skillActivation(skillName, skillContent)` is the load-bearing piece.
Plain tool results are part of the conversation history and may be truncated by
ContextManager Stage 2. Skill activations route through a different field
(`activatedSkillContent` on the result + `activeSkillContent` on the
ContextManager) and are re-rendered into Section 6 on every prompt rebuild. They
are not in the conversation; they live in the system prompt.

This is *the* reason `use_skill` exists as a tool instead of being a `read_file`
on `SKILL.md`. A `read_file` result is conversation history and gets truncated.
Activation is system-prompt state and gets re-rendered.

## IDE-aware variants

`InstructionLoader.getSkillContent()` accepts an optional `IdeContext`. Five
bundled skills (`tdd`, `interactive-debugging`, `systematic-debugging`,
`subagent-driven`, `writing-plans`) ship a `SKILL.java.md` and/or
`SKILL.python.md` alongside the base. When the IDE matches:

- IntelliJ IDEA Ultimate / Community → `SKILL.java.md` is appended
- PyCharm Professional / Community → `SKILL.python.md` is appended
- Other IDEs (WebStorm, etc.) → base only

Variant content is **appended** (not merged), so the base contains the
language-agnostic procedure and the variant contains language-specific examples
(JUnit + MockK for Java; pytest + fixtures for Python).

## Substitution tax: chat path vs LLM path

Skills support `$ARGUMENTS`, `$1`-`$N`, and `${CLAUDE_SKILL_DIR}` substitutions.
These are **chat-path features** — when a user types `/tdd write a test for
foo()`, the chat slash-command parser fills `$ARGUMENTS = "write a test for
foo()"` before injection.

`use_skill` (the LLM-callable tool) does not currently expose an `arguments`
parameter, so when the LLM activates a skill, those substitutions fire with
empty values. Custom skills designed around positional args will get
`$1`-substituted to empty strings.

Practical implication for skill authors: write skills whose default behaviour
makes sense without args. Use args as enrichment, not as required input. The
substitution scaffolding is forward-compatible — when an `arguments` parameter
is added to the tool schema, existing skills will automatically benefit.

## Why orchestrator-only

`allowedWorkers = setOf(WorkerType.ORCHESTRATOR)` is deliberate. Sub-agents are
short-lived task workers spawned by `agent` (SpawnAgentTool). Their procedure
context is set up at config load time via the persona's `skills:` YAML field;
runtime activation by a sub-agent would create a procedural Mexican standoff
with the parent ("does the parent know this skill is now active for this
worker?").

The cleaner contract: parent decides procedure → spawns sub-agent with skills
preloaded → sub-agent runs the procedure. If a sub-agent discovers it needs a
different skill mid-task, the canonical answer is `task_report` to the parent,
which can re-spawn with the right config.

## What replaces this if it goes away

Three fallbacks, in declining order of fidelity:

1. **Slash-command-only skills.** User types `/systematic-debugging` to
   activate; LLM cannot auto-pick. Loses the "match user request to skill
   description" capability — the SKILLS section becomes documentation rather
   than a callable menu.
2. **Skill-as-file-read.** LLM reads `SKILL.md` via `read_file`. Procedure
   present until ContextManager truncates the read. Death by compaction; long
   sessions silently lose their procedures.
3. **Skill-as-priors.** LLM reinvents TDD, debugging, etc. from training. Works
   for canonical procedures, fails for project-specific custom skills (which
   are the whole point of the user-extensibility surface).

The verdict — STRONG keep — reflects that the first two erase the central
feature (compaction-survival across long autonomous tasks) and the third erases
the second central feature (user-extensibility).
