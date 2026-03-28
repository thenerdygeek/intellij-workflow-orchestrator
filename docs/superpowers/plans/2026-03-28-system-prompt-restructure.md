# System Prompt Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the agent system prompt to exploit U-shaped attention, eliminate contradictions, and improve sub-agent delegation via positive framing and single-source-of-truth design.

**Architecture:** Rewrite PromptAssembler's static content strings and section ordering. Rewrite SpawnAgentTool descriptions. Slim sections, remove redundancy, add bookend. No execution logic changes — prompt text only.

**Tech Stack:** Kotlin string constants, JUnit 5 tests

**Spec:** `docs/superpowers/specs/2026-03-28-system-prompt-restructure-design.md`

---

### Task 1: Rewrite SpawnAgentTool Descriptions

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt:51-57` (BUILT_IN_AGENTS)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt:63-85` (description)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt:97-103` (subagent_type param)

This is the highest-impact change — tool descriptions are the #1 lever for routing (Google ADK, BiasBusters).

- [ ] **Step 1: Replace BUILT_IN_AGENTS descriptions with routing triggers**

Replace lines 51-57:
```kotlin
val BUILT_IN_AGENTS = mapOf(
    "general-purpose" to BuiltInAgent(
        WorkerType.ORCHESTRATOR,
        "Full-capability agent for complex multi-step tasks that span research, implementation, and verification."
    ),
    "explorer" to BuiltInAgent(
        WorkerType.ANALYZER,
        "PREFERRED for codebase research. Runs parallel PSI-powered searches — faster than manual " +
            "search_code/read_file. Use proactively for: 'how does X work', 'where is Y', 'find all Z', " +
            "cross-module flows, inheritance chains, call graphs. Specify thoroughness: quick/medium/very thorough."
    ),
    "coder" to BuiltInAgent(
        WorkerType.CODER,
        "Use for implementation tasks touching 3+ files or self-contained plan steps. " +
            "Has full edit/test/diagnostics capabilities in isolated context."
    ),
    "reviewer" to BuiltInAgent(
        WorkerType.REVIEWER,
        "Always use after completing multi-file edits to verify quality before reporting task complete. " +
            "Read-only analysis with code review expertise."
    ),
    "tooler" to BuiltInAgent(
        WorkerType.TOOLER,
        "Use for Jira, Bamboo, SonarQube, Bitbucket tasks that don't need code context. " +
            "Handles ticket queries, build status, PR management, quality checks."
    )
)
```

- [ ] **Step 2: Replace tool description with positive framing**

Replace the `override val description` (lines 63-85):
```kotlin
override val description =
    "Spawn a sub-agent for autonomous task execution. " +
        "Each agent runs in its own context with scoped tools.\n\n" +
        "Use the agent tool for:\n" +
        "- Research: agent(subagent_type=\"explorer\") — faster than manual search, " +
        "runs parallel PSI-powered searches, protects your context from verbose results. " +
        "Use for open-ended questions, cross-module flows, or tasks needing 3+ searches.\n" +
        "- Implementation: agent(subagent_type=\"coder\") — for multi-file edits (3+ files) " +
        "or self-contained plan steps.\n" +
        "- Review: agent(subagent_type=\"reviewer\") — use after multi-file changes " +
        "before reporting complete.\n" +
        "- Enterprise tools: agent(subagent_type=\"tooler\") — Jira/Bamboo/Sonar/Bitbucket " +
        "tasks that don't need code context.\n" +
        "- Complex tasks: agent(subagent_type=\"general-purpose\") — full tool access.\n\n" +
        "Use direct tools (read_file, search_code) when you know the exact file or symbol name.\n\n" +
        "Lifecycle: resume=agentId to continue, run_in_background=true for non-blocking, " +
        "kill=agentId to cancel."
```

- [ ] **Step 3: Update subagent_type parameter description**

Replace lines 97-103:
```kotlin
"subagent_type" to ParameterProperty(
    type = "string",
    description = "Which agent type to use. See available_agents in your context for descriptions. " +
        "Built-in: general-purpose, explorer, coder, reviewer, tooler. " +
        "Also accepts custom agent names from .workflow/agents/."
),
```

- [ ] **Step 4: Compile and verify**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt
git commit -m "feat: rewrite SpawnAgentTool descriptions with positive framing and routing triggers"
```

---

### Task 2: Restructure PromptAssembler Section Order and Assembly

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:32-154` (buildSingleAgentPrompt method)

- [ ] **Step 1: Rewrite buildSingleAgentPrompt with new section order**

Replace the entire `buildSingleAgentPrompt` method body (lines 47-154) with:
```kotlin
val sections = mutableListOf<String>()

// === PRIMACY ZONE (highest attention) ===
// 1. Core Identity + directives
sections.add(CORE_IDENTITY)

// 2. Persistence + completion (merged from COMPLETION_RULES)
sections.add(PERSISTENCE_AND_COMPLETION)

// 3. Tool policy (merged from EFFICIENCY, THINKING, MENTION rules)
sections.add(TOOL_POLICY)

// === CONTEXT ZONE (lower attention — reference data OK here) ===
// 4. Project Context
if (projectName != null || frameworkInfo != null) {
    val ctx = buildProjectContext(projectName, projectPath, frameworkInfo)
    sections.add("<project_context>\n$ctx\n</project_context>")
}

// 5. Repository Context
if (!repoContext.isNullOrBlank()) {
    sections.add("<project_repositories>\n$repoContext\n</project_repositories>")
}

// 6. Repo Map
if (!repoMapContext.isNullOrBlank()) {
    sections.add("<repo_map>\n$repoMapContext\n</repo_map>")
}

// 7. Core Memory
if (!coreMemoryContext.isNullOrBlank()) {
    sections.add("<core_memory>\n$coreMemoryContext\n</core_memory>")
}

// 8. Agent Memory (legacy)
if (!memoryContext.isNullOrBlank()) {
    sections.add("<agent_memory>\n$memoryContext\n</agent_memory>")
}

// 9. Guardrails
if (!guardrailsContext.isNullOrBlank()) {
    sections.add(guardrailsContext)
}

// 10. Available Agents — ALWAYS inject built-in + custom
val builtInDescs = com.workflow.orchestrator.agent.tools.builtin.SpawnAgentTool.BUILT_IN_AGENTS.entries
    .joinToString("\n") { "- ${it.key}: ${it.value.description}" }
val allAgentDescs = if (!agentDescriptions.isNullOrBlank()) {
    "$builtInDescs\n\nCustom agents:\n$agentDescriptions"
} else {
    builtInDescs
}
sections.add("<available_agents>\n$allAgentDescs\n\nTo delegate, call agent(subagent_type=\"name\", prompt=\"...\").\n</available_agents>")

// 11. Available Skills
if (!skillDescriptions.isNullOrBlank()) {
    sections.add("<available_skills>\n$skillDescriptions\n\nTo activate a skill, call activate_skill(name). Users can also type /skill-name in chat.\n</available_skills>")
}

// Previous Step Results (orchestrated mode only)
if (!previousStepResults.isNullOrEmpty()) {
    val prev = previousStepResults.joinToString("\n\n") { "- $it" }
    sections.add("<previous_results>\nContext from previous steps:\n$prev\n</previous_results>")
}

// === RECENCY ZONE (highest recall) ===
// 12. Planning
sections.add(if (planMode) FORCED_PLANNING_RULES else PLANNING_RULES)

// 13. Delegation mechanics
sections.add(DELEGATION_RULES)

// 14. Memory (shortened)
sections.add(MEMORY_RULES)

// 15. Context management (shortened)
sections.add(CONTEXT_MANAGEMENT_RULES)

// 16. Rendering (compact, conditional)
if (hasJcefUi) {
    sections.add(RENDERING_RULES_COMPACT)
}

// 17. Few-shot examples (high recency attention)
sections.add(FEW_SHOT_EXAMPLES)

// 18. Rules (slimmed)
sections.add(RULES)

// 19. Integration-specific rules (conditional)
val activeToolNames = if (activeTools.isNotEmpty()) {
    activeTools.map { it.name }.toSet()
} else null
val integrationRules = buildIntegrationRules(activeToolNames)
if (integrationRules.isNotBlank()) {
    sections.add(integrationRules)
}

// 20. Communication
sections.add(COMMUNICATION)

// 21. Bookend — restate critical directives (Anthropic-recommended)
sections.add(BOOKEND)

return sections.joinToString("\n\n")
```

- [ ] **Step 2: Compile and verify**

Run: `./gradlew :agent:compileKotlin`
Expected: FAIL (new constants not yet defined). This is expected — Task 3 defines them.

---

### Task 3: Rewrite All Static Content Strings

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt` (companion object)

- [ ] **Step 1: Add PERSISTENCE_AND_COMPLETION constant**

Add after CORE_IDENTITY:
```kotlin
private const val PERSISTENCE_AND_COMPLETION = """
<persistence>
Keep working until the user's task is fully resolved. Do not stop early or yield partial results.
When you have fully completed ALL parts of the request, call attempt_completion with a summary.
Do not end your response without either calling a tool or calling attempt_completion.
Do NOT call attempt_completion when completing individual plan steps — use update_plan_step for that.
</persistence>
"""
```

- [ ] **Step 2: Add TOOL_POLICY constant**

```kotlin
val TOOL_POLICY = """
    <tool_policy>
    - Make independent tool calls in parallel. Never guess — use tools to discover information.
    - For targeted lookups (known file/class/method): use read_file, search_code, find_definition directly.
    - Use the think tool before complex multi-step reasoning. Skip for simple actions.
    - @ mentioned content is already in context — do not re-read mentioned files.
    - Use git_* tools for ALL git operations. NEVER use run_command for git.
    - ALWAYS fill the 'description' parameter on tools that have it — the user sees it in the approval dialog.
    </tool_policy>
""".trimIndent()
```

- [ ] **Step 3: Replace DELEGATION_RULES with slimmed version**

```kotlin
val DELEGATION_RULES = """
    <delegation>
    Sub-agents run in isolated contexts — provide detailed prompts with file paths and context.

    Decision: Am I confident I'll find what I need in 1-2 tool calls?
    - YES → direct tools. NO → agent tool with the appropriate subagent_type.

    For parallel independent tasks, launch multiple agents in one response.
    Use run_in_background=true for tasks that don't block your next step.
    Resume a completed agent: agent(resume="agentId", prompt="continue with...")
    </delegation>
""".trimIndent()
```

- [ ] **Step 4: Replace MEMORY_RULES with shortened version**

```kotlin
val MEMORY_RULES = """
    <memory>
    Three-tier memory: Core Memory (always in prompt, self-editable), Archival (searchable, unlimited), Conversation Recall (past sessions).

    Save memories for: build quirks, API workarounds, project conventions, user preferences.
    When the user corrects you, IMMEDIATELY save a memory so you never repeat the mistake.
    Do not save information already in code or obvious from reading the codebase.
    </memory>
""".trimIndent()
```

- [ ] **Step 5: Replace CONTEXT_MANAGEMENT_RULES with shortened version**

```kotlin
val CONTEXT_MANAGEMENT_RULES = """
    <context_management>
    Your conversation may be compressed during long tasks. After compression:
    - Use <agent_facts> as your source of truth for what you've done
    - Re-read files if tool results show "[Tool result pruned]"
    - File paths in summaries are reliable; line numbers may be stale
    </context_management>
""".trimIndent()
```

- [ ] **Step 6: Add RENDERING_RULES_COMPACT (top 4 formats only)**

Replace the 140-line RENDERING_RULES with a ~60-line compact version keeping only ```flow, ```mermaid, ```chart, ```table schemas. Add a note about activate_skill('rich-rendering') for the full reference. Remove: ```timeline, ```progress, ```image, ```visualization, ```output, code annotations, LaTeX.

- [ ] **Step 7: Replace FEW_SHOT_EXAMPLES with expanded version**

Keep existing 5 examples (open-ended-exploration, targeted-lookup, edit-with-verification, error-recovery, when-to-plan) and add 3 new:
- `multi-file-implementation` — coder delegation
- `parallel-research` — explorer + tooler in parallel
- `review-before-complete` — reviewer before attempt_completion

- [ ] **Step 8: Replace RULES with slimmed 10-rule version**

Remove the 10+ niche tool tips (type_inference, structural_search, dataflow_analysis, debugging). Keep 10 universal rules. Merge error recovery (2 sentences from OrchestratorPrompts).

- [ ] **Step 9: Add COMMUNICATION constant**

Extract from old RULES:
```kotlin
val COMMUNICATION = """
    <communication>
    Include brief text alongside tool calls to keep the user informed.
    After exploration, include a 1-2 sentence status update. Before edits, explain the root cause.
    Keep status updates to 1-2 sentences between tool call batches.
    </communication>
""".trimIndent()
```

- [ ] **Step 10: Add BOOKEND constant**

```kotlin
private const val BOOKEND = """
<final_reminders>
Remember: Use tools to discover information — never guess. Verify your work before claiming done. Keep going until fully resolved.
</final_reminders>
"""
```

- [ ] **Step 11: Remove deleted constants**

Delete: `EFFICIENCY_RULES`, `THINKING_RULES`, `MENTION_RULES`, old `RENDERING_RULES` (replaced by compact), `critical_reminders` block from inside RULES.

- [ ] **Step 12: Remove tool_activation section from assembly**

The `<tool_activation>` inline section (line 54) becomes part of TOOL_POLICY. Remove the standalone injection.

- [ ] **Step 13: Move niche tool tips to integration_rules**

Add new conditional blocks in `buildIntegrationRules()`:
- PSI_TOOL_RULES (type_inference, structural_search, dataflow_analysis, read_write_access, test_finder, module_dependency_graph) — included when PSI tools active
- DEBUG_TOOL_RULES (exception_breakpoint, field_watchpoint, thread_dump, memory_view, hotswap, method_breakpoint) — included when debug tools active

- [ ] **Step 14: Compile**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 15: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "refactor: restructure system prompt — new section order, slimmed content, bookend pattern"
```

---

### Task 4: Update OrchestratorPrompts

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/OrchestratorPrompts.kt`

- [ ] **Step 1: Remove ERROR_RECOVERY_RULES constant**

The error recovery guidance is now merged into RULES (2 sentences). Delete the `ERROR_RECOVERY_RULES` val and its reference in PromptAssembler (already removed in Task 2).

- [ ] **Step 2: Compile**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/OrchestratorPrompts.kt
git commit -m "refactor: merge ERROR_RECOVERY_RULES into RULES section"
```

---

### Task 5: Update Tests

**Files:**
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssemblerTest.kt`

- [ ] **Step 1: Update section assertions**

Changes needed:
- `prompt has critical reminders at the end` → Replace with `prompt has bookend at the end`. Assert `<final_reminders>` exists and appears after `<rules>`.
- `prompt contains few-shot examples` → Add assertions for new examples: `multi-file-implementation`, `parallel-research`, `review-before-complete`.
- `tool activation note is present` → Remove or update (tool_activation replaced by tool_policy).
- Add new test: `prompt always contains available_agents` — assert `<available_agents>` present even without custom agents.
- Add new test: `prompt contains tool_policy` — assert `<tool_policy>` present.
- Add new test: `prompt contains persistence section` — assert `<persistence>` present.
- Remove/update: `rules contain multi-repo guidance` — multi-repo rule is now in conditional integration_rules, not always present.

- [ ] **Step 2: Run all tests**

Run: `./gradlew :agent:test --tests "*PromptAssemblerTest*"`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 3: Run full agent test suite**

Run: `./gradlew :agent:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssemblerTest.kt
git commit -m "test: update PromptAssemblerTest for restructured prompt sections"
```

---

### Task 6: Final Verification and Build

**Files:** None (verification only)

- [ ] **Step 1: Full compile**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Full test suite**

Run: `./gradlew :agent:test`
Expected: All ~470 tests pass

- [ ] **Step 3: Build plugin**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL, ZIP generated

- [ ] **Step 4: Update agent module CLAUDE.md**

Update the prompt-related documentation in `agent/CLAUDE.md` to reflect the new section order and removed sections.

- [ ] **Step 5: Final commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs: update agent CLAUDE.md for restructured prompt"
```
