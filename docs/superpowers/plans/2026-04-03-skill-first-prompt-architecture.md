# Skill-First Prompt Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the agent's system prompt so the LLM reliably auto-invokes skills instead of following inline workflow instructions.

**Architecture:** Three-pronged change — (1) move skill rules from context zone to primacy zone, (2) strip inline workflow details from four prompt sections, (3) strengthen built-in skill descriptions with trigger phrases.

**Tech Stack:** Kotlin, IntelliJ Platform SDK

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt` | Modify | Relocate skill section, strip inline workflows |
| `agent/src/main/resources/skills/writing-plans/SKILL.md` | Modify | Add trigger phrases to description |
| `agent/src/main/resources/skills/systematic-debugging/SKILL.md` | Modify | Add trigger phrases to description |
| `agent/src/main/resources/skills/tdd/SKILL.md` | Modify | Add trigger phrases to description |
| `agent/src/main/resources/skills/subagent-driven/SKILL.md` | Modify | Add trigger phrases to description |
| `agent/CLAUDE.md` | Modify | Update System Prompt Structure section |

No new files. No test changes needed.

---

### Task 1: Move skill injection from context zone to primacy zone

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:58-62` (primacy zone)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:91-93` (remove from context zone)

- [ ] **Step 1: Add skill rules to primacy zone**

In `buildSingleAgentPrompt()`, after `sections.add(TOOL_POLICY)` at line 61, add the new skill rules section:

```kotlin
        // === PRIMACY ZONE (highest attention) ===
        sections.add(CORE_IDENTITY)
        sections.add(PERSISTENCE_AND_COMPLETION)
        sections.add(TOOL_POLICY)

        // Skill rules — primacy zone so LLM sees them before any context data
        if (!skillDescriptions.isNullOrBlank()) {
            sections.add("<skill_rules>\nYou have access to skills — structured workflows that produce better results than ad-hoc approaches.\n\n$skillDescriptions\n\nTo use a skill, call Skill(skill=\"name\"). Users can also type /skill-name in chat.\n\nRULE: When a skill matches the task, load it BEFORE starting work.\nDo NOT attempt planning, debugging, brainstorming, or TDD workflows without the corresponding skill.\nThe skill contains the detailed workflow — the system prompt only tells you WHEN to use it, not HOW.\n</skill_rules>")
        }
```

- [ ] **Step 2: Remove skill injection from context zone**

Remove lines 91-93 (the old `<available_skills>` block):

```kotlin
        // DELETE THIS BLOCK:
        if (!skillDescriptions.isNullOrBlank()) {
            sections.add("<available_skills>\n$skillDescriptions\n\nTo use a skill, call Skill(skill=\"name\"). Users can also type /skill-name in chat.\n\nIMPORTANT: Before starting work, check if an available skill matches the task. Skills contain battle-tested workflows — always prefer them over ad-hoc approaches. In particular:\n- For planning multi-step tasks → Skill(skill=\"writing-plans\")\n- For brainstorming new features/architecture → Skill(skill=\"brainstorm\")\n- For executing an approved plan → Skill(skill=\"subagent-driven\")\n- For TDD workflow → Skill(skill=\"tdd\")\n- For debugging → Skill(skill=\"systematic-debugging\")\n</available_skills>")
        }
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "refactor(agent): move skill rules from context zone to primacy zone"
```

---

### Task 2: Strip inline workflow details from PLANNING_RULES

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:270-308`

- [ ] **Step 1: Replace PLANNING_RULES constant**

Replace lines 270-308 with:

```kotlin
        val PLANNING_RULES = """
            <planning>
            DECISION PROCESS — run this before every task:

            1. If the user explicitly asks to "create a plan", "write a plan", or "plan this":
               → enable_plan_mode, then Skill(skill="writing-plans"). No exceptions.

            2. Otherwise, INVESTIGATE SCOPE first — use agent(subagent_type="explorer") to research
               the codebase areas involved, or read key files/structure directly for smaller scopes.
               Then decide based on what you find:

               PLAN (enable_plan_mode + Skill(skill="writing-plans")) when ANY of these are true:
               - Task touches 2+ files or crosses module boundaries
               - Task adds a new feature, API endpoint, service method, or UI component
               - Task involves refactoring, renaming, or moving code
               - Task requires changes to interfaces/contracts that have multiple implementations
               - Task involves wiring something through multiple layers (API → service → tool → UI)
               - You're unsure about the scope — when in doubt, plan

               ACT DIRECTLY (no plan) only when ALL of these are true:
               - Task is a single-file fix, one-line change, or question
               - You're confident about the exact change needed after reading the file
               - No cross-file dependencies are affected

            3. After the user approves a plan:
               - For multi-task plans: Skill(skill="subagent-driven")
               - For simple 1-2 task plans: execute directly with update_plan_step
            </planning>
        """.trimIndent()
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "refactor(agent): strip inline workflow details from PLANNING_RULES, keep decision tree only"
```

---

### Task 3: Strip inline workflow details from FORCED_PLANNING_RULES

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:310-326`

- [ ] **Step 1: Replace FORCED_PLANNING_RULES constant**

Replace lines 310-326 with:

```kotlin
        val FORCED_PLANNING_RULES = """
            <planning mode="required">
            Plan mode is ACTIVE. Write tools are NOT available until you create a plan and the user approves it.
            Call Skill(skill="writing-plans") to activate the planning workflow, then follow its instructions.
            Once the user approves, plan mode deactivates and all tools become available.
            </planning>
        """.trimIndent()
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "refactor(agent): strip inline workflow details from FORCED_PLANNING_RULES"
```

---

### Task 4: Strip plan execution paragraph from DELEGATION_RULES

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:328-347`

- [ ] **Step 1: Replace DELEGATION_RULES constant**

Replace lines 328-347 with:

```kotlin
        val DELEGATION_RULES = """
            <delegation>
            Sub-agents run in isolated contexts — provide detailed prompts with file paths and context.

            Decision: Am I confident I'll find what I need in 1-2 tool calls?
            - YES → direct tools. NO → agent tool with the appropriate subagent_type.

            For parallel independent tasks, launch multiple agents in one response.
            Use run_in_background=true for tasks that don't block your next step.
            Use name="label" to make agents addressable for resume/send.
            Resume a completed agent: agent(resume="agentId", prompt="continue with...")

            Specialist agents: Use bundled specialists (code-reviewer, architect-reviewer, test-automator,
            spring-boot-engineer, refactoring-specialist, security-auditor, performance-engineer, devops-engineer)
            via agent(subagent_type="name") for domain-specific tasks. See <available_agents> for full list.
            </delegation>
        """.trimIndent()
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "refactor(agent): remove plan execution paragraph from DELEGATION_RULES"
```

---

### Task 5: Replace redundant few-shot examples with skill-matching example

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:433-511`

- [ ] **Step 1: Replace FEW_SHOT_EXAMPLES constant**

Replace lines 433-511 with:

```kotlin
        val FEW_SHOT_EXAMPLES = """
            <examples>
            These examples show the expected approach for common task types.

            <example name="open-ended-exploration">
            User: "How does the authentication flow work?"
            Good approach: Use agent(subagent_type="explorer", prompt="How does the authentication flow work? Trace the flow from login entry point through middleware to session creation. Thoroughness: medium") — the explorer will search across files, follow references, and return a summary without bloating your context.
            Bad approach: Manually searching one file at a time with search_code, reading every result — wastes your context window and takes longer than delegating.
            </example>

            <example name="targeted-lookup">
            User: "What does the processOrder method do?"
            Good approach: Call find_definition("processOrder") directly — you know the exact method name, so a single tool call finds it.
            Bad approach: Spawning an explorer for a single known method lookup.
            </example>

            <example name="edit-with-verification">
            User: "Fix the null pointer in UserService.findById"
            Good approach:
            1. read_file to understand the current code
            2. edit_file to add the null check
            3. diagnostics to verify no compilation errors
            4. runtime(action="run_tests") on the affected test class to confirm the fix
            Then report what you changed and the test results.
            Bad approach: Edit the file and immediately say "Done! The fix has been applied." without running diagnostics or tests.
            </example>

            <example name="error-recovery">
            User: "Run the integration tests"
            If runtime(action="run_tests") fails with a compilation error:
            1. Read the error carefully — identify the file and line
            2. read_file on the failing file
            3. edit_file to fix the compilation issue
            4. Run tests again
            Do NOT retry the same failing command without fixing the underlying issue first.
            </example>

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

            <example name="parallel-research">
            User: "Understand the auth system and check if there are related Sonar issues"
            Good approach: Launch two agents in parallel:
              agent(subagent_type="explorer", prompt="Trace the authentication flow from login through token validation. Find all auth-related classes and their relationships. Thoroughness: medium")
              agent(subagent_type="tooler", prompt="Search SonarQube for issues in auth-related files. Check quality gate status and any security vulnerabilities tagged as auth.", run_in_background=true)
            Bad approach: Manually searching auth code, then switching to Sonar queries sequentially.
            </example>

            <example name="review-before-complete">
            User: "Refactor the auth module to use JWT tokens"
            After implementing all changes, before calling attempt_completion:
              agent(subagent_type="reviewer", prompt="Review changes in src/main/kotlin/auth/. Verify: JWT dependency added, token generation correct, existing tests updated, no security issues.")
            Bad approach: Declaring "Done!" without verifying multi-file changes.
            </example>
            </examples>
        """.trimIndent()
```

**What changed:** Removed `when-to-plan`, `multi-file-implementation`, and `skill-activation` examples (they provided inline workflow details the LLM used to skip skills). Added `skill-matching` example that shows direct skill invocation as the required approach.

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "refactor(agent): replace redundant few-shot examples with skill-matching example"
```

---

### Task 6: Strengthen built-in skill descriptions with trigger phrases

**Files:**
- Modify: `agent/src/main/resources/skills/writing-plans/SKILL.md:3`
- Modify: `agent/src/main/resources/skills/systematic-debugging/SKILL.md:3`
- Modify: `agent/src/main/resources/skills/tdd/SKILL.md:3`
- Modify: `agent/src/main/resources/skills/subagent-driven/SKILL.md:3`

- [ ] **Step 1: Update writing-plans description**

In `agent/src/main/resources/skills/writing-plans/SKILL.md`, replace line 3:

Old:
```
description: Use when you have a spec or requirements for a multi-step task, before touching code
```

New:
```
description: >
  Create structured implementation plans with bite-sized tasks, TDD steps, and file-level guidance.
  Triggers on: "plan this", "create a plan", "write a plan", multi-file tasks, new features, refactoring,
  cross-module changes. Always load after enable_plan_mode.
```

- [ ] **Step 2: Update systematic-debugging description**

In `agent/src/main/resources/skills/systematic-debugging/SKILL.md`, replace line 3:

Old:
```
description: Use when encountering any bug, test failure, build failure, or unexpected behavior — before proposing fixes. Enforces root-cause investigation with IDE-level diagnostics.
```

New:
```
description: >
  Use when encountering any bug, test failure, build failure, or unexpected behavior — before
  proposing fixes. Enforces root-cause investigation with IDE-level diagnostics. Triggers on:
  "failing", "broken", "NPE", "exception", "not working", "error", test failures, build failures,
  stack traces. Always load before attempting any fix.
```

- [ ] **Step 3: Update tdd description**

In `agent/src/main/resources/skills/tdd/SKILL.md`, replace line 3:

Old:
```
description: Use when implementing any feature or bugfix, before writing implementation code
```

New:
```
description: >
  Red-green-refactor TDD workflow. Write the test first, watch it fail, write minimal code to pass.
  Triggers on: "TDD", "test first", "test-driven", "write tests for", or when the user explicitly
  requests test-first development.
```

- [ ] **Step 4: Update subagent-driven description**

In `agent/src/main/resources/skills/subagent-driven/SKILL.md`, replace line 3:

Old:
```
description: Use when executing implementation plans with independent tasks in the current session
```

New:
```
description: >
  Execute approved plans by dispatching a fresh subagent per task with two-stage review
  (spec compliance + code quality). Triggers on: plan approved with 3+ tasks, "execute the plan",
  "start implementing". Always load after plan approval for multi-task plans.
```

- [ ] **Step 5: Verify build compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL (SKILL.md files are resources, not compiled, but verify nothing breaks resource loading)

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/resources/skills/writing-plans/SKILL.md \
       agent/src/main/resources/skills/systematic-debugging/SKILL.md \
       agent/src/main/resources/skills/tdd/SKILL.md \
       agent/src/main/resources/skills/subagent-driven/SKILL.md
git commit -m "refactor(agent): strengthen skill descriptions with trigger phrases for reliable auto-invocation"
```

---

### Task 7: Update agent CLAUDE.md documentation

**Files:**
- Modify: `agent/CLAUDE.md` (System Prompt Structure section, around line 50-79)

- [ ] **Step 1: Update the System Prompt Structure section**

Replace the prompt structure listing to reflect the new ordering:

```markdown
## System Prompt Structure (`PromptAssembler`)

Assembled dynamically per turn. Section order follows primacy/recency attention patterns:

**Primacy zone** (highest compliance):
1. `CORE_IDENTITY` — role, capabilities, persona
2. `PERSISTENCE_AND_COMPLETION` — session durability, `attempt_completion` requirement
3. `TOOL_POLICY` — tool usage rules, read-before-edit, verification
4. `<skill_rules>` — **skill-first behavioral rule**: skill list with trigger descriptions + mandatory loading rule. Positioned in primacy zone so LLM sees skills before context data and can't bypass them with inline instructions.

**Context zone** (reference data, conditionally included):
5. `<project_context>` — name, path, framework
6. `<project_repositories>` — repo info
7. `<repo_map>` — file structure
8. `<core_memory>` — tier-1 memory (always if non-empty)
9. `<agent_memory>` — legacy markdown memory
10. Guardrails context
11. `<available_agents>` — **always injected**: built-in agents (general-purpose, explorer, coder, reviewer, tooler) + any custom agents from `.workflow/agents/`
12. `<previous_results>` — orchestration step context

**Recency zone** (highest recall):
13. `PLANNING_RULES` (or `FORCED_PLANNING_RULES` in plan mode) — decision tree only: when to plan vs act directly. No inline workflow details — defers to skills.
14. `DELEGATION_RULES` — when/how to spawn subagents
15. `MEMORY_RULES` — when to save to each memory tier
16. `CONTEXT_MANAGEMENT_RULES` — budget awareness
17. `RENDERING_RULES_COMPACT` — rich UI formatting (skipped in plain-text mode)
18. `FEW_SHOT_EXAMPLES` — concrete tool call examples including skill-matching patterns
19. `RULES` — general behavioral rules
20. `STEERING_RULES` — real-time user steering protocol
21. `<integration_rules>` — **conditional**: niche tips for Jira/Bamboo/Sonar/Bitbucket/PSI/Debug tools, only included when those tools are active
22. `COMMUNICATION` — response style guidelines
23. `BOOKEND` — closing reinforcement of identity + key constraints
```

Note: `<available_skills>` removed from context zone (item 11 in old list). `<skill_rules>` added to primacy zone (new item 4). `STEERING_RULES` added (item 20, was missing from old docs).

- [ ] **Step 2: Commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs(agent): update System Prompt Structure to reflect skill-first primacy zone placement"
```

---

### Task 8: Run full test suite and verify

**Files:**
- No file changes — verification only

- [ ] **Step 1: Run agent tests**

Run: `./gradlew :agent:test`
Expected: All tests pass. No tests assert on prompt string content.

- [ ] **Step 2: Run full build**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify plugin API compatibility**

Run: `./gradlew verifyPlugin`
Expected: No API compatibility issues

- [ ] **Step 4: Verify SkillRegistry still parses updated SKILL.md files**

Run: `./gradlew :agent:test --tests "*SkillRegistry*"`
Expected: All 23 SkillRegistry tests pass (they use test fixtures, not built-in skills, but confirms parsing logic still works)

- [ ] **Step 5: Commit (only if fixes were needed)**

Only if tests revealed issues that required fixes.
