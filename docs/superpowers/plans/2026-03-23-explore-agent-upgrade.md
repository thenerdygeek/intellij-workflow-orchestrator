# Explore Agent Upgrade — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the built-in `explorer` subagent type to match Claude Code's Explore agent design — with PSI-powered advantages — by rewriting its system prompt, restricting its tools to read-only, adding thoroughness calibration, and teaching the orchestrator when/how to use it.

**Architecture:** Replace the generic `ANALYZER_SYSTEM_PROMPT` with a purpose-built `EXPLORER_SYSTEM_PROMPT` that emphasizes speed, thoroughness calibration (quick/medium/very thorough), PSI-first search strategies, structured output format, and explicit "FIND don't ANALYZE" guardrails. Restrict the `ANALYZER` worker type to strictly read-only tools by removing it from 18 debug/config tool allowedWorkers sets. Add exploration heuristics to the orchestrator's delegation rules so the LLM knows when to delegate vs search directly.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, JUnit 5 + MockK

**Research:** `docs/superpowers/research/2026-03-23-explore-agent-research.md`

---

## File Structure

| File | Change | Responsibility |
|------|--------|---------------|
| `agent/.../orchestrator/OrchestratorPrompts.kt` | Modify | Replace ANALYZER_SYSTEM_PROMPT with EXPLORER_SYSTEM_PROMPT |
| `agent/.../orchestrator/PromptAssembler.kt` | Modify | Add exploration heuristics to DELEGATION_RULES |
| `agent/.../tools/builtin/SpawnAgentTool.kt` | Modify | Update explorer description with thoroughness guidance |
| 15 debug tool files in `agent/.../tools/debug/` | Modify | Remove WorkerType.ANALYZER from allowedWorkers |
| 3 config tool files in `agent/.../tools/config/` | Modify | Remove WorkerType.ANALYZER from allowedWorkers |
| `agent/.../runtime/ApprovalGate.kt` | No change | No change needed — read-only tools are already NONE risk |
| `agent/.../tools/ToolCategoryRegistry.kt` | No change | Categories are for DynamicToolSelector, not per-worker filtering |
| `agent/src/test/.../OrchestratorPromptsTest.kt` | Create | Tests for new explorer prompt content and structure |
| `agent/src/test/.../ExplorerToolFilteringTest.kt` | Create | Tests verifying explorer has no debug/config/write tools |
| `agent/CLAUDE.md` | Modify | Document explorer upgrade in module docs |

---

### Task 1: Rewrite Explorer System Prompt

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/OrchestratorPrompts.kt:11-38`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/orchestrator/OrchestratorPromptsTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/orchestrator/OrchestratorPromptsTest.kt
package com.workflow.orchestrator.agent.orchestrator

import com.workflow.orchestrator.agent.runtime.WorkerType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class OrchestratorPromptsTest {

    @Test
    fun `explorer prompt contains thoroughness calibration`() {
        val prompt = OrchestratorPrompts.getSystemPrompt(WorkerType.ANALYZER)
        assertTrue(prompt.contains("quick"), "Should mention quick thoroughness")
        assertTrue(prompt.contains("medium"), "Should mention medium thoroughness")
        assertTrue(prompt.contains("very thorough"), "Should mention very thorough thoroughness")
    }

    @Test
    fun `explorer prompt contains PSI tool guidance`() {
        val prompt = OrchestratorPrompts.getSystemPrompt(WorkerType.ANALYZER)
        assertTrue(prompt.contains("find_definition"), "Should guide PSI usage")
        assertTrue(prompt.contains("find_references"), "Should guide PSI usage")
        assertTrue(prompt.contains("type_hierarchy"), "Should guide PSI usage")
        assertTrue(prompt.contains("spring_endpoints"), "Should guide Spring tools")
    }

    @Test
    fun `explorer prompt enforces read-only role`() {
        val prompt = OrchestratorPrompts.getSystemPrompt(WorkerType.ANALYZER)
        assertTrue(prompt.contains("read-only"), "Should state read-only")
        assertTrue(prompt.contains("FIND"), "Should have FIND emphasis")
        assertFalse(prompt.contains("edit_file"), "Should not mention edit_file")
        assertFalse(prompt.contains("run_command"), "Should not mention run_command")
    }

    @Test
    fun `explorer prompt has structured output format`() {
        val prompt = OrchestratorPrompts.getSystemPrompt(WorkerType.ANALYZER)
        assertTrue(prompt.contains("files_found") || prompt.contains("Files Found"),
            "Should have files found section")
        assertTrue(prompt.contains("key_findings") || prompt.contains("Key Findings"),
            "Should have key findings section")
    }

    @Test
    fun `explorer prompt stays under token budget`() {
        val prompt = OrchestratorPrompts.getSystemPrompt(WorkerType.ANALYZER)
        // ~4 chars per token, budget is 1500 tokens = ~6000 chars
        assertTrue(prompt.length < 6000,
            "Explorer prompt should be under ~1500 tokens (${prompt.length} chars)")
    }

    @Test
    fun `all worker types return non-blank prompts`() {
        for (type in WorkerType.entries) {
            val prompt = OrchestratorPrompts.getSystemPrompt(type)
            assertTrue(prompt.isNotBlank(), "Prompt for $type should not be blank")
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.orchestrator.OrchestratorPromptsTest" --rerun --no-build-cache`
Expected: FAIL — current ANALYZER_SYSTEM_PROMPT doesn't contain thoroughness, PSI guidance, or structured output format.

- [ ] **Step 3: Replace ANALYZER_SYSTEM_PROMPT with EXPLORER_SYSTEM_PROMPT**

In `OrchestratorPrompts.kt`, replace lines 11-38 (the entire `ANALYZER_SYSTEM_PROMPT` val) with:

```kotlin
    val ANALYZER_SYSTEM_PROMPT = """
        You are a fast, read-only codebase explorer for IntelliJ IDEA projects.
        You FIND code and REPORT findings. You do NOT analyze, fix, suggest improvements, or solve problems.

        <role>
        - Search for files, classes, methods, and patterns in the codebase
        - Navigate code structure using PSI-powered tools (semantically accurate, not regex)
        - Map dependencies, relationships, and architecture
        - Return structured findings with exact file paths and line numbers
        - You are strictly read-only — you cannot and should not modify any file
        </role>

        <thoroughness>
        The parent agent specifies a thoroughness level in your task. Calibrate your search depth:
        - **quick** (1-3 tool calls): Targeted lookup. You roughly know what you're looking for. One search + one read. Return immediately.
        - **medium** (3-6 tool calls): Search multiple locations, follow 1-2 references. Default for most queries.
        - **very thorough** (6-10 tool calls): Exhaustive search across packages, naming conventions, inheritance trees. Check unusual locations.
        If no thoroughness is specified, default to medium.
        </thoroughness>

        <search_strategy>
        Use PSI tools FIRST — they are semantically accurate (no false positives):
        1. **find_definition** — locate where a class/method/field is defined
        2. **find_references** — find all usages of a symbol (exact, not regex)
        3. **find_implementations** — find all implementations of an interface/abstract class
        4. **type_hierarchy** — map inheritance tree
        5. **call_hierarchy** — trace callers/callees of a method
        6. **file_structure** — get class outline (methods, fields, annotations)
        7. **spring_endpoints** — find all REST endpoints with types and paths
        8. **spring_bean_graph** — map Spring dependency injection graph
        9. **spring_context** — list all Spring components/services/repositories

        Fall back to text search when PSI isn't applicable:
        - **search_code** with output_mode="files" — discover files containing a pattern
        - **glob_files** — find files by name/path pattern
        - **read_file** — read file contents (use offset+limit for large files)

        Use VCS tools for history questions:
        - **git_blame** — who changed this and when
        - **git_file_history** — how a file evolved
        - **git_log** — recent commits matching a pattern
        </search_strategy>

        <output_format>
        Structure ALL responses as:

        **Files Found:**
        - `path/to/File.kt:45` — Brief description of what's here
        - `path/to/Other.kt:12-30` — Brief description

        **Structure:**
        - Key relationships (implements, extends, calls, depends on)
        - Spring wiring if applicable (beans, injection, endpoints)

        **Key Findings:**
        - Concise bullet points answering the parent's question
        - Include specific line numbers for anything notable
        </output_format>

        <rules>
        - NEVER attempt to edit, write, or run commands — you are read-only
        - NEVER analyze code quality, suggest improvements, or solve problems — only FIND and REPORT
        - STOP searching as soon as you have enough information to answer the question
        - For "quick" thoroughness, return after 1-3 tool calls maximum
        - Always include file paths with line numbers in your response
        - Prefer PSI tools over regex search — they are faster and more accurate
        </rules>
    """.trimIndent()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.orchestrator.OrchestratorPromptsTest" --rerun --no-build-cache`
Expected: PASS — all 6 tests green.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/OrchestratorPrompts.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/orchestrator/OrchestratorPromptsTest.kt
git commit -m "feat(agent): rewrite explorer system prompt with thoroughness calibration and PSI-first strategy"
```

---

### Task 2: Restrict Explorer to Read-Only Tools

The `ANALYZER` worker type currently has access to 14 debug/config tools that are NOT read-only and an explorer should never use. Remove `WorkerType.ANALYZER` from their `allowedWorkers` sets. Keep ANALYZER in the 4 read-only debug inspection tools (ListBreakpoints, GetDebugState, GetStackFrames, GetVariables).

**Files:**
- Modify: 11 files in `agent/src/main/kotlin/.../tools/debug/` (action tools only — keep read-only ones)
- Modify: 3 files in `agent/src/main/kotlin/.../tools/config/`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/ExplorerToolFilteringTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/ExplorerToolFilteringTest.kt
package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.debug.AgentDebugController
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Verifies that the explorer (ANALYZER) worker type only has access to
 * read-only tools — no debug actions, config mutations, or file edits.
 *
 * Many debug tools require an AgentDebugController constructor arg.
 * We use MockK to provide it since we only check allowedWorkers (no execution).
 */
class ExplorerToolFilteringTest {

    private val mockDebugController = mockk<AgentDebugController>(relaxed = true)

    /**
     * Instantiate a tool class, handling both no-arg and AgentDebugController constructors.
     */
    private fun instantiateTool(className: String): AgentTool {
        val clazz = Class.forName(className)
        return try {
            // Try no-arg constructor first
            clazz.getDeclaredConstructor().newInstance() as AgentTool
        } catch (_: NoSuchMethodException) {
            // Try AgentDebugController constructor
            clazz.getDeclaredConstructor(AgentDebugController::class.java)
                .newInstance(mockDebugController) as AgentTool
        }
    }

    @Test
    fun `explorer does not have debug action tools`() {
        val debugActionClasses = listOf(
            "com.workflow.orchestrator.agent.tools.debug.AddBreakpointTool",
            "com.workflow.orchestrator.agent.tools.debug.RemoveBreakpointTool",
            "com.workflow.orchestrator.agent.tools.debug.StartDebugSessionTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugStepOverTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugStepIntoTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugStepOutTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugResumeTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugPauseTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugRunToCursorTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugStopTool",
            "com.workflow.orchestrator.agent.tools.debug.EvaluateExpressionTool"
        )
        for (className in debugActionClasses) {
            val tool = instantiateTool(className)
            assertFalse(
                WorkerType.ANALYZER in tool.allowedWorkers,
                "Explorer (ANALYZER) should NOT have access to ${tool.name}"
            )
        }
    }

    @Test
    fun `explorer does not have config mutation tools`() {
        val configToolClasses = listOf(
            "com.workflow.orchestrator.agent.tools.config.CreateRunConfigTool",
            "com.workflow.orchestrator.agent.tools.config.ModifyRunConfigTool",
            "com.workflow.orchestrator.agent.tools.config.DeleteRunConfigTool"
        )
        for (className in configToolClasses) {
            val tool = instantiateTool(className)
            assertFalse(
                WorkerType.ANALYZER in tool.allowedWorkers,
                "Explorer (ANALYZER) should NOT have access to ${tool.name}"
            )
        }
    }

    @Test
    fun `explorer retains read-only debug inspection tools`() {
        val readOnlyDebugClasses = listOf(
            "com.workflow.orchestrator.agent.tools.debug.ListBreakpointsTool",
            "com.workflow.orchestrator.agent.tools.debug.GetDebugStateTool",
            "com.workflow.orchestrator.agent.tools.debug.GetStackFramesTool",
            "com.workflow.orchestrator.agent.tools.debug.GetVariablesTool"
        )
        for (className in readOnlyDebugClasses) {
            val tool = instantiateTool(className)
            assertTrue(
                WorkerType.ANALYZER in tool.allowedWorkers,
                "Explorer (ANALYZER) SHOULD have access to ${tool.name} (read-only)"
            )
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.ExplorerToolFilteringTest" --rerun --no-build-cache`
Expected: FAIL — `explorer does not have debug action tools` fails because ANALYZER is still in allowedWorkers.

- [ ] **Step 3: Remove ANALYZER from debug action tools (11 files)**

In each of these 11 files, change the `allowedWorkers` line to remove `WorkerType.ANALYZER`:

**`AddBreakpointTool.kt:63`** — change:
```kotlin
override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER)
```
to:
```kotlin
override val allowedWorkers = setOf(WorkerType.CODER)
```

**`RemoveBreakpointTool.kt:43`** — same pattern: `setOf(WorkerType.CODER, WorkerType.ANALYZER)` → `setOf(WorkerType.CODER)`

**`StartDebugSessionTool.kt:53`** — same: `setOf(WorkerType.CODER, WorkerType.ANALYZER)` → `setOf(WorkerType.CODER)`

**`DebugStepOverTool.kt:31`** — same: `setOf(WorkerType.CODER, WorkerType.ANALYZER)` → `setOf(WorkerType.CODER)`

**`DebugStepIntoTool.kt:31`** — same: `setOf(WorkerType.CODER, WorkerType.ANALYZER)` → `setOf(WorkerType.CODER)`

**`DebugStepOutTool.kt:31`** — same: `setOf(WorkerType.CODER, WorkerType.ANALYZER)` → `setOf(WorkerType.CODER)`

**`DebugResumeTool.kt:31`** — same: `setOf(WorkerType.CODER, WorkerType.ANALYZER)` → `setOf(WorkerType.CODER)`

**`DebugPauseTool.kt:31`** — same: `setOf(WorkerType.CODER, WorkerType.ANALYZER)` → `setOf(WorkerType.CODER)`

**`DebugRunToCursorTool.kt:48`** — same: `setOf(WorkerType.CODER, WorkerType.ANALYZER)` → `setOf(WorkerType.CODER)`

**`DebugStopTool.kt:30`** — same: `setOf(WorkerType.CODER, WorkerType.ANALYZER)` → `setOf(WorkerType.CODER)`

**`EvaluateExpressionTool.kt:41`** — change:
```kotlin
override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)
```
to:
```kotlin
override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER)
```

- [ ] **Step 4: Remove ANALYZER from config mutation tools (3 files)**

**`CreateRunConfigTool.kt:89`** — change:
```kotlin
override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER)
```
to:
```kotlin
override val allowedWorkers = setOf(WorkerType.CODER)
```

**`ModifyRunConfigTool.kt:58`** — same: `setOf(WorkerType.CODER, WorkerType.ANALYZER)` → `setOf(WorkerType.CODER)`

**`DeleteRunConfigTool.kt:33`** — same: `setOf(WorkerType.CODER, WorkerType.ANALYZER)` → `setOf(WorkerType.CODER)`

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.ExplorerToolFilteringTest" --rerun --no-build-cache`
Expected: PASS — all 3 tests green.

- [ ] **Step 6: Run full agent test suite**

Run: `./gradlew :agent:test --rerun --no-build-cache`
Expected: PASS — no existing tests should depend on explorer having debug tools.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/*.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/config/*.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/ExplorerToolFilteringTest.kt
git commit -m "feat(agent): restrict explorer to read-only tools — remove debug/config access from ANALYZER"
```

---

### Task 3: Update SpawnAgentTool Description with Thoroughness Guidance

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt:51-57,62-76`

- [ ] **Step 1: Update BUILT_IN_AGENTS explorer description**

In `SpawnAgentTool.kt:53`, change:
```kotlin
"explorer" to BuiltInAgent(WorkerType.ANALYZER, "Fast read-only codebase exploration"),
```
to:
```kotlin
"explorer" to BuiltInAgent(WorkerType.ANALYZER, "Fast read-only codebase exploration with PSI intelligence — specify thoroughness: quick/medium/very thorough"),
```

- [ ] **Step 2: Update tool description with explorer thoroughness guidance**

In `SpawnAgentTool.kt:62-76`, replace the `description` val:
```kotlin
    override val description =
        "Launch a subagent to handle a task autonomously. The subagent runs in its own context " +
            "with its own tools and returns results.\n\n" +
            "Available agent types:\n" +
            "- general-purpose: Full tool access, for complex multi-step tasks\n" +
            "- explorer: Read-only, fast codebase exploration. Specify thoroughness in prompt: " +
            "'quick' (1-3 calls, targeted lookup), 'medium' (3-6 calls, default), " +
            "'very thorough' (6-10 calls, exhaustive). Uses PSI tools for semantically accurate navigation. " +
            "Prefer explorer over direct Grep/Glob when: the search is open-ended, requires >3 queries, " +
            "or needs to follow references/inheritance/call chains.\n" +
            "- coder: Code editing and implementation\n" +
            "- reviewer: Code review and analysis (read-only)\n" +
            "- tooler: Integration tools (Jira, Bamboo, SonarQube, Bitbucket)\n" +
            "Or specify any custom agent defined in .workflow/agents/\n\n" +
            "If subagent_type is omitted, defaults to general-purpose.\n\n" +
            "When NOT to use explorer (use direct tools instead):\n" +
            "- Reading a specific known file → read_file\n" +
            "- Searching for a specific class/method → search_code or find_definition\n" +
            "- Searching within 1-3 known files → read_file\n\n" +
            "Lifecycle:\n" +
            "- Resume: agent(resume='agentId', prompt='continue with...') — continues a previous agent\n" +
            "- Background: agent(run_in_background=true, ...) — returns immediately, notifies on completion\n" +
            "- Kill: agent(kill='agentId') — cancels a running background agent"
```

- [ ] **Step 3: Run full agent tests to verify no regressions**

Run: `./gradlew :agent:test --rerun --no-build-cache`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt
git commit -m "feat(agent): update explorer description with thoroughness levels and usage heuristic"
```

---

### Task 4: Add Exploration Heuristic to Orchestrator Delegation Rules

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:192-229`

- [ ] **Step 1: Update DELEGATION_RULES with exploration heuristic**

In `PromptAssembler.kt`, replace the `DELEGATION_RULES` val (lines 192-229) with:

```kotlin
        val DELEGATION_RULES = """
            <delegation>
            You have access to the agent tool to spawn focused subagents for specific sub-tasks.
            Each subagent runs in its own context with scoped tools — they won't see your
            conversation history, so provide clear context in the prompt.

            Built-in agent types: general-purpose, explorer, coder, reviewer, tooler.
            Custom agents may also be available (see available_subagents section).

            When to delegate:
            - Simple tasks (1-2 files, quick fix): handle yourself
            - Moderate to complex tasks (3+ files, multi-step edits): use agent with subagent_type="coder"
            - Analysis tasks (understand codebase, find references): use agent with subagent_type="explorer"
            - Review tasks (check quality after changes): use agent with subagent_type="reviewer"
            - Enterprise tool tasks (Jira, Bamboo, Sonar): use agent with subagent_type="tooler"
            - When you create a plan and a step is non-trivial, delegate it via the agent tool
            - Always provide detailed prompts with file paths and context (subagent has no conversation history)

            Explorer subagent heuristic — use explorer when:
            - The search is open-ended (you don't know which files to look at)
            - The task requires more than 3 search queries
            - You need to follow references, inheritance chains, or call graphs
            - You want to protect your context from verbose search results
            - The user asks "how does X work" or "where is Y implemented"
            Do NOT use explorer when:
            - You know the exact file path → use read_file directly
            - You're searching for a specific class name → use search_code or find_definition
            - You need to search within 1-3 known files → use read_file directly
            When using explorer, specify thoroughness in the prompt:
            - "Thoroughness: quick" — targeted lookup, 1-3 tool calls
            - "Thoroughness: medium" — balanced search (default if omitted)
            - "Thoroughness: very thorough" — exhaustive multi-location search

            Background execution:
            - For independent tasks that don't block your next step, use run_in_background=true
            - You will be notified automatically when the background agent completes
            - Continue working on other tasks while it runs — do NOT wait or poll
            - Use background for: research, code review, test runs, long builds
            - Use foreground (default) for: tasks whose results you need before proceeding

            Resume:
            - Every agent returns an agentId in its result
            - To continue a completed agent's work: agent(resume="agentId", prompt="continue with...")
            - The resumed agent has its full previous context preserved
            - Use resume when: follow-up work on the same area, iterating on review feedback

            Kill:
            - To cancel a running background agent: agent(kill="agentId")
            - Use when: the task is no longer needed, or you want to redirect the agent

            If a delegated task fails, try a different approach or handle it yourself.
            </delegation>
        """.trimIndent()
```

- [ ] **Step 2: Run full agent tests**

Run: `./gradlew :agent:test --rerun --no-build-cache`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "feat(agent): add explorer heuristic to delegation rules — when/how to use thoroughness levels"
```

---

### Task 5: Update ApprovalGate — Remove Debug Tools from NONE Risk for Explorer

No changes needed to ApprovalGate.kt — the risk levels are tool-based, not worker-based. The explorer's tool set restriction (Task 2) handles this automatically. Read-only tools that the explorer HAS access to are already classified as NONE risk.

However, we should verify this is correct.

**Files:**
- Test (add to existing): `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/ExplorerToolFilteringTest.kt`

- [ ] **Step 1: Add approval gate verification test**

Add this test to `ExplorerToolFilteringTest.kt`:

```kotlin
    @Test
    fun `all explorer-accessible tools are NONE risk`() {
        // The explorer should never trigger an approval dialog.
        // Verify that the read-only tools the explorer uses are classified as NONE risk.
        val explorerReadOnlyTools = listOf(
            "read_file", "search_code", "glob_files", "file_structure",
            "find_definition", "find_references", "type_hierarchy",
            "call_hierarchy", "find_implementations",
            "git_status", "git_blame", "git_diff", "git_log",
            "git_branches", "git_show_file", "git_show_commit",
            "list_breakpoints", "get_debug_state", "get_stack_frames", "get_variables",
            "diagnostics", "think"
        )
        for (toolName in explorerReadOnlyTools) {
            val risk = com.workflow.orchestrator.agent.runtime.ApprovalGate.riskLevelFor(toolName)
            assertTrue(
                risk == com.workflow.orchestrator.agent.runtime.RiskLevel.NONE,
                "Explorer tool '$toolName' should be NONE risk, but was $risk"
            )
        }
    }
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.ExplorerToolFilteringTest" --rerun --no-build-cache`
Expected: PASS — all explorer tools are classified as NONE risk.

- [ ] **Step 3: Commit**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/ExplorerToolFilteringTest.kt
git commit -m "test(agent): verify all explorer tools are NONE risk — no approval dialogs during exploration"
```

---

### Task 6: Update Module Documentation

**Files:**
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Update agent CLAUDE.md**

Find the section that describes the `explorer` built-in type (inside the "Agent Tool (Subagent Management)" section) and update it.

Find the line:
```
**Built-in types:** general-purpose, explorer, coder, reviewer, tooler
```
and replace with:
```
**Built-in types:** general-purpose, explorer (PSI-powered, read-only, thoroughness: quick/medium/very thorough), coder, reviewer, tooler
```

Also find the `## Key Components` section's `SpawnAgentTool` entry and update:
```
- **SpawnAgentTool** (`agent`) — Primary tool for spawning subagents, matching Claude Code's Agent tool design. Only `description` and `prompt` required. `subagent_type` selects built-in (general-purpose/explorer/coder/reviewer/tooler) or custom agents from `.workflow/agents/`. Defaults to general-purpose. Explorer type uses PSI-first search strategy with thoroughness calibration (quick/medium/very thorough) and is restricted to read-only tools only (no debug, config, or edit tools).
```

- [ ] **Step 2: Commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs(agent): document explorer upgrade — PSI-first strategy, thoroughness levels, read-only restriction"
```

---

### Task 7: Final Verification

- [ ] **Step 1: Run all agent tests**

Run: `./gradlew :agent:test --rerun --no-build-cache`
Expected: PASS — all ~470+ tests green.

- [ ] **Step 2: Run compilation check**

Run: `./gradlew :agent:compileKotlin`
Expected: PASS — no compilation errors.

- [ ] **Step 3: Run plugin verification**

Run: `./gradlew verifyPlugin`
Expected: PASS — API compatibility check.

---

## Summary of Changes

| Change | Impact | Files |
|--------|--------|-------|
| New explorer system prompt | MAJOR — completely new behavior for explorer subagent | 1 |
| Tool restriction | MAJOR — 14 tools removed from explorer access (11 debug action + 3 config) | 14 |
| SpawnAgentTool description | MEDIUM — LLM guidance for when/how to use explorer | 1 |
| Delegation rules | MEDIUM — orchestrator knows when to delegate to explorer | 1 |
| Tests | NEW — 10 new test methods across 2 test files | 2 |
| Documentation | MINOR — updated module docs | 1 |
| **Total** | | **20 files modified/created** |

> **Out of scope (deliberate):** Renaming `WorkerType.ANALYZER` → `WorkerType.EXPLORER` (breaking change across 70+ files, deferred). Creating `ExplorerConfig.kt` for hard iteration limits per thoroughness level (prompt guidance is sufficient for now — revisit if LLM over-searches on "quick" tasks).
