# Skill-First Prompt Architecture — Design Spec

## Problem

The agent's LLM never auto-invokes skills. Skills are correctly registered, the `Skill` tool works, skill descriptions are injected into the system prompt, but the LLM always follows inline workflow instructions instead of loading the richer skill content.

**Example:** User says "plan this refactor." The LLM calls `enable_plan_mode` + `create_plan` using inline `PLANNING_RULES` instructions, never loading the `writing-plans` skill that contains detailed plan format, TDD steps, self-review checklist, and file structure sections.

**Impact:** Skill content (battle-tested workflows with detailed steps) is dead weight — shipped with the plugin, discoverable, loadable, but never loaded.

## Root Cause

Three prompt architecture flaws work together to make skills unreachable:

### 1. Position: Skills in Low-Attention Zone

`<available_skills>` is injected at `PromptAssembler.kt:91` in the **context zone** (middle of prompt). Stanford's "Lost in the Middle" research (cited in PromptAssembler's own comments) shows this is the lowest-attention position. The LLM processes primacy and recency zones first and treats context-zone content as reference metadata.

### 2. Redundancy: Inline Rules Make Skills Optional

`PLANNING_RULES` (38 lines), `FORCED_PLANNING_RULES` (16 lines), `DELEGATION_RULES` (20 lines), and `FEW_SHOT_EXAMPLES` (78 lines) provide complete standalone workflows. The LLM can plan, delegate, debug, and brainstorm using only inline instructions — it never needs to call `Skill()` because it already has enough information.

Unlike other tools (e.g., `read_file` where there's no alternative to get file contents), the `Skill` tool fills a knowledge gap, not a capability gap. The LLM only calls tools it *needs*. It doesn't need skills because the system prompt already provides the workflow.

### 3. Description Quality: Vague Triggers

Most skill descriptions are generic statements like "Use when implementing any feature or bugfix." Cursor's documentation confirms: "If it's vague, the agent either delegates everything or delegates nothing." Only the `brainstorm` skill has concrete trigger phrases ("Triggers on: 'brainstorm', 'design', 'how should I'...").

## Industry Research

Research across 10 enterprise agentic tools (Claude Code, Cursor, Windsurf, Cline, Roo Code, Aider, OpenHands, Amazon Q, GitHub Copilot, Gemini CLI) reveals:

**Every tool uses prompt-based LLM selection.** No tool uses algorithmic routing (embeddings, classifiers, keyword matching) as the primary skill selection mechanism. The difference between tools where auto-invocation works and where it doesn't is prompt architecture:

| Tool | Approach | Key Design Choice |
|---|---|---|
| Superpowers (Claude Code) | 4000+ line meta-skill injected FIRST in context | Primacy positioning + exhaustive anti-skip engineering |
| Cursor | Agent descriptions with `description` field | "If it's vague, the agent either delegates everything or delegates nothing" |
| Roo Code | `whenToUse` field per mode + dedicated Orchestrator | Orchestrator's PRIMARY job is reading descriptions and delegating |
| Windsurf | Skills with descriptions, LLM-selected | **Officially acknowledges auto-invocation is unreliable**, recommends explicit @mention |
| Cline | Plan/Act modes, prompt enforcement | "Models frequently ignore" prompt-only enforcement; added `strictPlanModeEnabled` programmatic fallback |

**Convergence point:** The Agent Skills open standard (agentskills.io), adopted by 26+ platforms, uses progressive three-tier disclosure: metadata in prompt → full content on demand → supporting resources on request. This is exactly our architecture — the issue is prompt positioning and inline redundancy, not the skill mechanism.

## Solution: Three-Pronged Prompt Restructure

### Prong 1: Move Skill Rules to Primacy Zone

Move `<available_skills>` from context zone (line 91, between repo map and previous results) to primacy zone (after `TOOL_POLICY`, before any context data). This is the highest-attention position — the LLM reads it before project context, repo map, or memory.

**Current prompt order:**
```
PRIMACY:  CORE_IDENTITY → PERSISTENCE → TOOL_POLICY
CONTEXT:  project_context → repo_map → memory → agents → ★skills★ → previous_results
RECENCY:  PLANNING_RULES → DELEGATION → MEMORY → RENDERING → EXAMPLES → RULES → COMMUNICATION → BOOKEND
```

**New prompt order:**
```
PRIMACY:  CORE_IDENTITY → PERSISTENCE → TOOL_POLICY → ★SKILL_RULES★
CONTEXT:  project_context → repo_map → memory → agents → previous_results
RECENCY:  PLANNING_RULES → DELEGATION → MEMORY → RENDERING → EXAMPLES → RULES → COMMUNICATION → BOOKEND
```

The skill section becomes a behavioral rule (primacy zone), not reference metadata (context zone).

**New section content:**

```kotlin
// Built from skillDescriptions parameter + static rules
val SKILL_RULES_TEMPLATE = """
<skill_rules>
You have access to skills — structured workflows that produce better results than ad-hoc approaches.

{skillDescriptions}

To use a skill, call Skill(skill="name"). Users can also type /skill-name in chat.

RULE: When a skill matches the task, load it BEFORE starting work.
Do NOT attempt planning, debugging, brainstorming, or TDD workflows without the corresponding skill.
The skill contains the detailed workflow — the system prompt only tells you WHEN to use it, not HOW.
</skill_rules>
"""
```

### Prong 2: Strip Inline Workflow Details

Remove "how" details from four sections, keeping only "when" triggers. The skill becomes the single source of truth for workflow behavior.

#### 2a. `PLANNING_RULES` — 38 lines → ~15 lines

**Remove:** Steps 3-5 (how to create plans, what to include in markdown, how to execute after approval). These are fully covered by the `writing-plans` and `subagent-driven` skills.

**Keep:** The decision tree (when to plan vs act directly). The LLM needs this BEFORE loading any skill — it determines WHETHER to load the skill.

**New content:**
```kotlin
val PLANNING_RULES = """
<planning>
DECISION — run this before every task:

1. If the user explicitly asks to "create a plan", "write a plan", or "plan this":
   → enable_plan_mode, then Skill(skill="writing-plans"). No exceptions.

2. Otherwise, INVESTIGATE SCOPE first — use agent(subagent_type="explorer") or read key files.
   Then decide:

   PLAN (enable_plan_mode + Skill(skill="writing-plans")) when ANY of these are true:
   - Task touches 2+ files or crosses module boundaries
   - Task adds a new feature, API endpoint, service method, or UI component
   - Task involves refactoring, renaming, or moving code
   - Task requires interface/contract changes with multiple implementations
   - Task involves wiring through multiple layers (API → service → tool → UI)
   - You're unsure about the scope — when in doubt, plan

   ACT DIRECTLY (no plan) only when ALL of these are true:
   - Task is a single-file fix, one-line change, or question
   - You're confident about the exact change after reading the file
   - No cross-file dependencies are affected

3. After the user approves a plan:
   - For multi-task plans: Skill(skill="subagent-driven")
   - For simple 1-2 task plans: execute directly with update_plan_step
</planning>
""".trimIndent()
```

**What was removed:** Steps 3 (how to create plan — call create_plan with title + markdown, steps auto-extracted from ### headings), step 4 detail about subagent dispatch, step 5 about revision. All of this is in the skills.

#### 2b. `FORCED_PLANNING_RULES` — 16 lines → 5 lines

**New content:**
```kotlin
val FORCED_PLANNING_RULES = """
<planning mode="required">
Plan mode is ACTIVE. Write tools are NOT available until you create a plan and the user approves it.
Call Skill(skill="writing-plans") to activate the planning workflow, then follow its instructions.
Once the user approves, plan mode deactivates and all tools become available.
</planning>
""".trimIndent()
```

**What was removed:** Inline instructions about how to analyze the task, what to include in create_plan (title, markdown, ## Goal, ## Steps with ### per step, ## Testing), and how to execute after approval. All covered by the skills.

#### 2c. `DELEGATION_RULES` — 20 lines → ~12 lines

**Remove:** The "Plan execution" paragraph that duplicates `subagent-driven` skill content.

**New content:**
```kotlin
val DELEGATION_RULES = """
<delegation>
Sub-agents run in isolated contexts — provide detailed prompts with file paths and context.

Decision: Am I confident I'll find what I need in 1-2 tool calls?
- YES → direct tools. NO → agent tool with the appropriate subagent_type.

For parallel independent tasks, launch multiple agents in one response.
Use run_in_background=true for tasks that don't block your next step.
Use name="label" to make agents addressable for resume/send.

Specialist agents: Use bundled specialists (code-reviewer, architect-reviewer, test-automator,
spring-boot-engineer, refactoring-specialist, security-auditor, performance-engineer, devops-engineer)
via agent(subagent_type="name") for domain-specific tasks. See <available_agents> for full list.
</delegation>
""".trimIndent()
```

**What was removed:** "Plan execution: After a plan is approved, use Skill(skill="subagent-driven") to execute it. This dispatches a fresh subagent per task with two-stage review (spec compliance + code quality)." — this is now only in `PLANNING_RULES` step 3 (as a trigger) and in the `subagent-driven` skill (as the full workflow).

#### 2d. `FEW_SHOT_EXAMPLES` — Tighten skill examples

The current `skill-activation` example shows skills as one "good approach" among others. Change to make skill loading the **required** approach, not a suggestion.

**Current** (lines 500-508):
```
<example name="skill-activation">
User: "The UserService tests are failing with NPE"
Good approach: Skill(skill="systematic-debugging") — this activates a structured debugging workflow...
Bad approach: Jumping straight to guessing the fix without investigating the root cause.

User: "I want to add a caching layer to the API"
Good approach: Skill(skill="brainstorm") first to explore requirements and design, then Skill(skill="writing-plans")...
Bad approach: Immediately editing files without understanding requirements or planning.
</example>
```

**New:**
```
<example name="skill-matching">
User: "The UserService tests are failing with NPE"
→ Skill(skill="systematic-debugging"). Always load this for bugs, test failures, or unexpected behavior.

User: "I want to add a caching layer to the API"
→ Skill(skill="brainstorm"). Always load this for new features, architecture, or design discussions.

User: "Refactor the notification system to use events"
→ enable_plan_mode + Skill(skill="writing-plans"). Multi-file changes need a plan.

User: "Add a null check in processOrder()"
→ Act directly. Single-file, single-line fix — no skill needed.
</example>
```

Also remove three examples that duplicate planning/skill content:
- `when-to-plan` (lines 469-476) — covered by `skill-matching` and `PLANNING_RULES` decision tree
- `multi-file-implementation` (lines 478-483) — covered by `skill-matching`
- `skill-activation` (lines 500-508) — replaced by `skill-matching`

Keep these examples unchanged: `open-ended-exploration`, `targeted-lookup`, `edit-with-verification`, `error-recovery`, `parallel-research`, `review-before-complete`.

### Prong 3: Strengthen Built-in Skill Descriptions

Following the `brainstorm` skill's pattern (which includes concrete trigger phrases), update all skill descriptions to include explicit trigger signals.

| Skill | Current Description | New Description |
|---|---|---|
| `writing-plans` | "Use when you have a spec or requirements for a multi-step task, before touching code" | "Create structured implementation plans with bite-sized tasks, TDD steps, and file-level guidance. Triggers on: 'plan this', 'create a plan', 'write a plan', multi-file tasks, new features, refactoring, cross-module changes. Always load after enable_plan_mode." |
| `systematic-debugging` | "Use when encountering any bug, test failure, build failure, or unexpected behavior — before proposing fixes. Enforces root-cause investigation with IDE-level diagnostics." | (Already good — add triggers) "Use when encountering any bug, test failure, build failure, or unexpected behavior — before proposing fixes. Enforces root-cause investigation with IDE-level diagnostics. Triggers on: 'failing', 'broken', 'NPE', 'exception', 'not working', 'error', test failures, build failures, stack traces." |
| `tdd` | "Use when implementing any feature or bugfix, before writing implementation code" | "Red-green-refactor TDD workflow. Write the test first, watch it fail, write minimal code to pass. Triggers on: 'TDD', 'test first', 'test-driven', 'write tests for', or when the user explicitly requests test-first development." |
| `subagent-driven` | "Use when executing implementation plans with independent tasks in the current session" | "Execute approved plans by dispatching a fresh subagent per task with two-stage review (spec compliance + code quality). Triggers on: plan approved with 3+ tasks, 'execute the plan', 'start implementing'. Always load after plan approval for multi-task plans." |
| `git-workflow` | "Enterprise git workflow best practices. Use when working with branches, comparing changes, reviewing commits, investigating ticket changes, or any git-related task." | (Already good — no change needed) |
| `brainstorm` | (Already includes trigger phrases) | (No change needed) |
| `create-skill` | (Has `disable-model-invocation: true`) | (No change — user-only skill) |
| `interactive-debugging` | (Has `user-invocable: false`) | (No change — LLM-only escalation skill) |

### Description Budget Consideration

Longer descriptions consume more of the 2% context budget (max 16K chars) in `buildDescriptionIndex()`. The new descriptions are ~2x longer but still well within budget:

- 8 auto-discoverable skills × ~200 chars average = ~1600 chars
- Budget at 190K input tokens: min(190000 * 0.02 * 4, 16000) = 15200 chars
- Plenty of headroom even with longer descriptions

---

## File Changes

### 1. `PromptAssembler.kt` — Restructure prompt sections

**Move skill injection to primacy zone:**
- Remove the `<available_skills>` block from context zone (line 91-93)
- Add new `SKILL_RULES` section after `TOOL_POLICY` in primacy zone (line ~63)
- The section is conditionally included (same as before — only when skillDescriptions is non-empty)

**Strip inline workflows:**
- Replace `PLANNING_RULES` constant (lines 269-307) with slim decision dispatcher
- Replace `FORCED_PLANNING_RULES` constant (lines 309-325) with skill-invocation-only version
- Replace `DELEGATION_RULES` constant (lines 327-346) — remove plan execution paragraph
- Update `FEW_SHOT_EXAMPLES` constant (lines 432-509) — tighten skill examples, remove redundant planning/delegation examples

### 2. Built-in SKILL.md files — Update description fields

- `agent/src/main/resources/skills/writing-plans/SKILL.md` — new description with trigger phrases
- `agent/src/main/resources/skills/systematic-debugging/SKILL.md` — add trigger phrases
- `agent/src/main/resources/skills/tdd/SKILL.md` — new description with trigger phrases
- `agent/src/main/resources/skills/subagent-driven/SKILL.md` — new description with trigger phrases

No changes to: `brainstorm` (already good), `git-workflow` (already good), `create-skill` (user-only), `interactive-debugging` (LLM-only escalation).

### 3. `agent/CLAUDE.md` — Update System Prompt Structure section

Update the section ordering documentation to reflect the new position of `<skill_rules>` in primacy zone.

---

## What Does NOT Change

- `SkillRegistry.kt` — no code changes
- `SkillManager.kt` — no code changes
- `SkillTool.kt` — no code changes
- `SingleAgentSession.kt` — no code changes
- `EventSourcedContextBridge.kt` — no code changes (skill anchor mechanism unchanged)
- `AgentController.kt` — no code changes
- `ConversationSession.kt` — no code changes
- Skill content (SKILL.md bodies) — only frontmatter descriptions change
- Skill frontmatter fields other than `description` — unchanged
- `buildDescriptionIndex()` budget logic — unchanged

---

## Testing Strategy

### Verification

1. **Build compiles:** `./gradlew :agent:compileKotlin`
2. **Tests pass:** `./gradlew :agent:test` — no test changes needed since tests don't assert on prompt content
3. **Plugin verifies:** `./gradlew verifyPlugin`

### Manual Testing

1. Start a new agent session
2. Say "The UserService tests are failing with NPE" → expect `Skill(skill="systematic-debugging")` call
3. Say "I want to add a caching layer" → expect `Skill(skill="brainstorm")` call
4. Say "Plan the refactoring of the auth module" → expect `enable_plan_mode` + `Skill(skill="writing-plans")`
5. After plan approval with 3+ tasks → expect `Skill(skill="subagent-driven")`

### Regression Checks

- Inline decision logic (when to plan vs act directly) still works
- Plan mode enforcement (blocked tools) still works
- Skill anchor persistence through compression still works
- Skill loading/preprocessing pipeline still works

---

## Token Impact

| Section | Before (tokens) | After (tokens) | Delta |
|---|---|---|---|
| `PLANNING_RULES` | ~600 | ~250 | -350 |
| `FORCED_PLANNING_RULES` | ~250 | ~80 | -170 |
| `DELEGATION_RULES` | ~200 | ~150 | -50 |
| `FEW_SHOT_EXAMPLES` | ~800 | ~650 | -150 |
| `<available_skills>` (context zone) | ~200 | 0 | -200 |
| `<skill_rules>` (primacy zone, new) | 0 | ~250 | +250 |
| **Net system prompt** | | | **~-670 tokens** |

Smaller prompt AND skills actually load. The detailed workflow content (~1-5K tokens per skill) loads on demand via `Skill` tool only when needed, rather than being summarized inline every turn.

---

## Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| LLM still doesn't load skills after changes | Low — industry evidence shows primacy positioning + no-alternative works | Fall back to programmatic pre-injection (Option 2) if needed |
| LLM loads skills for trivial tasks | Low — decision tree still present in PLANNING_RULES | "ACT DIRECTLY" criteria unchanged |
| Skill loading adds latency (extra tool call) | Negligible — skill content is local (resource/file read), not API call | Already measured at ~5ms for load+preprocess |
| Existing tests break | Very low — no tests assert on prompt string content | Run full test suite |

## Out of Scope

- Programmatic pre-injection / intent classification — not needed if prompt architecture works
- New skills — existing built-in skills are sufficient
- Eager skill discovery (UI) — separate spec exists
- Skill tool consolidation — separate spec exists
- Hot-reload of skills — future work
