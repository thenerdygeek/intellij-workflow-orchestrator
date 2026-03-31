# Tool Consolidation & Token Budget Reduction Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce tool definition token cost from ~45K tokens (25% of 190K budget) to ~8K tokens by consolidating 183 individual tools into ~25 meta-tools (Phase 1), implementing deferred schema loading (Phase 2), and allowing tool set to shrink per message (Phase 3).

**Root cause:** 183 tool definitions × ~300 tokens/definition = ~54,900 tokens sent every API call. With Sourcegraph's 4K output cap, this leaves the LLM with very little budget for actual reasoning, causing degenerate behaviors (file-path responses, repetitive loops, premature stops).

**Research basis:** Cross-tool comparison of Claude Code (~30 tools, deferred MCP schemas), Cline (~17 tools, text-based sub-commands), Codex CLI (~12 tools), OpenHands (~15-20, text-based sub-commands), SWE-agent (~15, text-based YAML). None of the top-performing agents use 150+ individual tools. The winning pattern is meta-tools with sub-command routing.

**Target:** ~25 tools visible at any time, ~8K tokens in tool definitions.

**Tech Stack:** Kotlin, `AgentTool` interface, `ToolRegistry`, `DynamicToolSelector`, `PromptAssembler`

---

## Phase 1 — Consolidate Integration Tools into Meta-Tools

**Approach:** Create one dispatching meta-tool per integration domain. Each accepts an `action` enum parameter + domain-specific params. Individual tool files become private helpers or are inlined. The dispatcher routes `action → existing execute()` logic.

**Token saving:** 15 Jira tools × ~300 = 4,500 → 1 tool × ~800 = 800. Repeat for all domains.

### Task 1: Create `JiraTool` meta-tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentService.kt` (registration)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt` (group update)

Consolidates: `jira_get_ticket`, `jira_get_transitions`, `jira_transition`, `jira_comment`, `jira_get_comments`, `jira_log_work`, `jira_get_worklogs`, `jira_get_sprints`, `jira_get_linked_prs`, `jira_get_boards`, `jira_get_sprint_issues`, `jira_get_board_issues`, `jira_search_issues`, `jira_get_dev_branches`, `jira_start_work`

- [ ] **Step 1.1: Create JiraTool.kt with action enum dispatch**

```kotlin
class JiraTool : AgentTool {
    override val name = "jira"
    override val description = """
        Jira integration. Use action parameter to select operation.
        Actions: get_ticket, search_issues, get_sprint_issues, get_board_issues, get_boards,
        get_sprints, comment, get_comments, log_work, get_worklogs, transition, get_transitions,
        get_linked_prs, get_dev_branches, start_work
    """.trimIndent()
    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(type = "string",
                description = "Operation: get_ticket|search_issues|get_sprint_issues|get_board_issues|get_boards|get_sprints|comment|get_comments|log_work|get_worklogs|transition|get_transitions|get_linked_prs|get_dev_branches|start_work",
                enum = listOf("get_ticket","search_issues","get_sprint_issues","get_board_issues","get_boards","get_sprints","comment","get_comments","log_work","get_worklogs","transition","get_transitions","get_linked_prs","get_dev_branches","start_work")),
            "key"      to ParameterProperty(type = "string", description = "Jira issue key (e.g. PROJ-123)"),
            "query"    to ParameterProperty(type = "string", description = "JQL query or search text"),
            "text"     to ParameterProperty(type = "string", description = "Comment text or transition name"),
            "sprint_id" to ParameterProperty(type = "string", description = "Sprint ID"),
            "board_id"  to ParameterProperty(type = "string", description = "Board ID"),
            "time_spent" to ParameterProperty(type = "string", description = "Time spent (e.g. 1h 30m)"),
            "work_date"  to ParameterProperty(type = "string", description = "Work date YYYY-MM-DD"),
            "max_results" to ParameterProperty(type = "integer", description = "Max results to return")
        ),
        required = listOf("action")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val service = ServiceLookup.jira(project) ?: return ServiceLookup.notConfigured("Jira")
        return when (params["action"]?.jsonPrimitive?.content) {
            "get_ticket"       -> { /* delegate to existing JiraGetTicketTool logic */ }
            "search_issues"    -> { /* delegate to existing JiraSearchIssuesTool logic */ }
            // ... all 15 actions
            else -> ToolResult("Unknown action", "Error", 5, isError = true)
        }
    }
}
```

- [ ] **Step 1.2: Update JIRA_TOOL_NAMES in DynamicToolSelector to `setOf("jira")`**
- [ ] **Step 1.3: Remove individual jira_* registrations from AgentService, register JiraTool**
- [ ] **Step 1.4: Keep old individual tool classes but mark `@Deprecated` (don't delete yet — needed for backward compat until Phase 3)**

---

### Task 2: Create `BambooTool` meta-tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooTool.kt`

Consolidates 18 bamboo_* tools into one tool.

Actions: `build_status`, `get_build`, `trigger_build`, `get_build_log`, `get_test_results`, `stop_build`, `cancel_build`, `get_artifacts`, `recent_builds`, `get_plans`, `get_project_plans`, `search_plans`, `get_plan_branches`, `get_running_builds`, `get_build_variables`, `get_plan_variables`, `rerun_failed_jobs`, `trigger_stage`

- [ ] **Step 2.1: Create BambooTool.kt with action dispatch (same pattern as JiraTool)**
- [ ] **Step 2.2: Update BAMBOO_TOOL_NAMES in DynamicToolSelector to `setOf("bamboo")`**
- [ ] **Step 2.3: Remove individual bamboo_* registrations, register BambooTool**

---

### Task 3: Create `SonarTool` meta-tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarTool.kt`

Consolidates 9 sonar_* tools.

Actions: `issues`, `quality_gate`, `coverage`, `search_projects`, `analysis_tasks`, `branches`, `project_measures`, `source_lines`, `issues_paged`

- [ ] **Step 3.1: Create SonarTool.kt with action dispatch**
- [ ] **Step 3.2: Update SONAR_TOOL_NAMES to `setOf("sonar")`**
- [ ] **Step 3.3: Remove individual sonar_* registrations, register SonarTool**

---

### Task 4: Create `BitbucketTool` meta-tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketTool.kt`

Consolidates 26 bitbucket_* tools.

Actions: `create_pr`, `get_pr_commits`, `add_inline_comment`, `reply_to_comment`, `set_reviewer_status`, `get_file_content`, `add_reviewer`, `update_pr_title`, `get_branches`, `create_branch`, `search_users`, `get_my_prs`, `get_reviewing_prs`, `get_pr_detail`, `get_pr_activities`, `get_pr_changes`, `get_pr_diff`, `get_build_statuses`, `approve_pr`, `merge_pr`, `decline_pr`, `update_pr_description`, `add_pr_comment`, `check_merge_status`, `remove_reviewer`, `list_repos`

- [ ] **Step 4.1: Create BitbucketTool.kt with action dispatch**
- [ ] **Step 4.2: Update BITBUCKET_TOOL_NAMES to `setOf("bitbucket")`**
- [ ] **Step 4.3: Remove individual bitbucket_* registrations, register BitbucketTool**

---

### Task 5: Create `DebugTool` meta-tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/DebugTool.kt`

Consolidates 20 debug_* tools (breakpoints, stepping, inspection).

Actions: `add_breakpoint`, `method_breakpoint`, `exception_breakpoint`, `field_watchpoint`, `remove_breakpoint`, `list_breakpoints`, `start_session`, `get_state`, `step_over`, `step_into`, `step_out`, `resume`, `pause`, `run_to_cursor`, `stop`, `evaluate`, `get_stack_frames`, `get_variables`, `thread_dump`, `memory_view`, `hotswap`, `force_return`, `drop_frame`, `attach_to_process`

- [ ] **Step 5.1: Create DebugTool.kt with action dispatch**
- [ ] **Step 5.2: Update DEBUG_TOOL_NAMES to `setOf("debug")`**
- [ ] **Step 5.3: Remove individual debug tool registrations, register DebugTool**

---

### Task 6: Create `GitTool` meta-tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/vcs/GitTool.kt`

Consolidates 11 VCS tools.

Actions: `status`, `blame`, `diff`, `log`, `branches`, `show_file`, `show_commit`, `stash_list`, `merge_base`, `file_history`, `shelve_changelist`

- [ ] **Step 6.1: Create GitTool.kt with action dispatch**
- [ ] **Step 6.2: Update VCS_TOOL_NAMES to `setOf("git")`**
- [ ] **Step 6.3: Remove individual VCS registrations, register GitTool**

---

### Task 7: Create `SpringTool` meta-tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/SpringTool.kt`

Consolidates 14 Spring + 4 Spring Boot tools (18 total).

Actions: `context`, `endpoints`, `bean_graph`, `config`, `version_info`, `profiles`, `repositories`, `security_config`, `scheduled_tasks`, `event_listeners`, `boot_endpoints`, `boot_autoconfig`, `boot_config_properties`, `boot_actuator`

- [ ] **Step 7.1: Create SpringTool.kt with action dispatch**
- [ ] **Step 7.2: Update SPRING_TOOL_NAMES + SPRING_BOOT_TOOL_NAMES to `setOf("spring")`**
- [ ] **Step 7.3: Remove individual spring_* registrations, register SpringTool**

---

### Task 8: Create `MavenTool` meta-tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenTool.kt`

Consolidates 8 Maven tools.

Actions: `dependencies`, `properties`, `plugins`, `profiles`, `dependency_tree`, `effective_pom`, `project_modules`, `module_dependency_graph`

- [ ] **Step 8.1: Create MavenTool.kt with action dispatch**
- [ ] **Step 8.2: Update MAVEN_TOOL_NAMES + MAVEN_ENHANCED_TOOL_NAMES to `setOf("maven")`**
- [ ] **Step 8.3: Remove individual maven_* registrations, register MavenTool**

---

### Task 9: Create `RuntimeTool` meta-tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeTool.kt`

Consolidates 9 runtime tools.

Actions: `get_run_configurations`, `create_run_config`, `modify_run_config`, `delete_run_config`, `get_running_processes`, `get_run_output`, `get_test_results`, `run_tests`, `compile_module`

- [ ] **Step 9.1: Create RuntimeTool.kt with action dispatch**
- [ ] **Step 9.2: Update RUNTIME_TOOL_NAMES to `setOf("runtime")`**
- [ ] **Step 9.3: Remove individual runtime registrations, register RuntimeTool**

---

### Task 10: Update ToolPreferences UI

The per-tool checkbox panel must map old individual tool names to their new meta-tool group.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/ToolPreferences.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/ToolsPanelView.kt` (or equivalent)

- [ ] **Step 10.1: Add tool group display names mapping meta-tool → displayed category**
- [ ] **Step 10.2: When a meta-tool is "disabled", add its name to `disabledTools`**
- [ ] **Step 10.3: Add sub-action granularity toggle (optional — Phase 3)**

---

### Task 11: Verify & test Phase 1

- [ ] **Step 11.1: Run `./gradlew :agent:clean :agent:test --rerun --no-build-cache`**
- [ ] **Step 11.2: Count tool definitions sent in a test session — should be ~25 vs former 150+**
- [ ] **Step 11.3: Token count: run `TokenEstimator.estimateToolDefinitions()` on new tool set — target < 10K**
- [ ] **Step 11.4: Verify all old tool names still work via meta-tool dispatch (backward compat)**
- [ ] **Step 11.5: Release as v0.41.0**

---

## Phase 2 — Deferred Schema Loading (Claude Code Pattern)

**Goal:** Send only name + one-line description initially. Full JSON schema sent only when tool is first used or explicitly requested.

**Token saving:** From ~8K (Phase 1) to ~2-3K for the "names-only" initial list.

**Note:** This requires the LLM to be capable of calling tools by name with minimal schema. Sourcegraph/Claude handles this well — it infers arguments from the description. Test extensively before enabling by default.

### Task 12: Implement `DeferredToolDefinition`

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DeferredToolDefinition.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt`

- [ ] **Step 12.1: Add `toSlimDefinition()` method to `AgentTool` interface**

```kotlin
// Slim version: name + single-line description only, no parameters
fun toSlimDefinition(): ToolDefinition = ToolDefinition(
    function = FunctionDefinition(
        name = name,
        description = description.lines().first().take(120), // first line only
        parameters = FunctionParameters(properties = emptyMap(), required = emptyList())
    )
)
```

- [ ] **Step 12.2: Add `DeferredSchemaRegistry` — tracks which tools have been used this session**

```kotlin
object DeferredSchemaRegistry {
    private val usedTools = mutableSetOf<String>()
    fun markUsed(toolName: String) { usedTools.add(toolName) }
    fun hasBeenUsed(toolName: String) = toolName in usedTools
    fun reset() { usedTools.clear() }
}
```

- [ ] **Step 12.3: In `AgentOrchestrator.executeTask()`, split tool definitions by phase:**
  - First call: `tool.toSlimDefinition()` for all tools
  - After first use of tool X: replace slim with `tool.toToolDefinition()` (full schema) in next API call

- [ ] **Step 12.4: Add `AgentSettings.deferredSchemaEnabled: Boolean = false` (off by default)**
- [ ] **Step 12.5: Add checkbox in AI & Advanced settings page**
- [ ] **Step 12.6: Wrap deferred logic behind the settings flag**

---

## Phase 3 — Allow Tool Set to Shrink Per Message

**Goal:** Remove "tools only expand, never shrink" stabilization. Re-select fresh per message based on current context window.

**Current behavior** (`DynamicToolSelector`):
```kotlin
// STABILIZE: Merge with existing session tools — only ADD, never remove.
val stableToolNames = session.activeToolNames
stableToolNames.addAll(newlySelectedTools.map { it.name })
session.activeToolNames = stableToolNames
```

**Problem:** A session that starts with "check Jira" then continues with "now fix the bug" keeps ALL Jira tools loaded for the rest of the session even when they're irrelevant.

**Solution:** Re-select each turn from scratch, but with two safeguards:
1. Keep "core always-include" set (read_file, edit_file, etc.) — never remove these.
2. Keep any tool that was called in the **last 3 iterations** — prevents mid-task tool disappearance.

### Task 13: Implement per-message re-selection with recent-use protection

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`

- [ ] **Step 13.1: Track `recentlyUsedTools: MutableSet<String>` in `ConversationSession`**

```kotlin
// In ConversationSession
var recentlyUsedTools: MutableSet<String> = mutableSetOf()  // tools called in last 3 iterations

fun recordToolCall(toolName: String) {
    recentlyUsedTools.add(toolName)
}
fun pruneRecentTools(keepLastN: Int = 3) {
    // after N iterations, clear tools not used in last N
    // simplest: session.activeToolNames = recentlyUsedTools + ALWAYS_INCLUDE after each iteration
}
```

- [ ] **Step 13.2: In `AgentOrchestrator`, replace stabilization block:**

```kotlin
// OLD (remove):
val stableToolNames = session.activeToolNames
stableToolNames.addAll(newlySelectedTools.map { it.name })
session.activeToolNames = stableToolNames

// NEW:
val recentlyUsed = session.recentlyUsedTools
val freshSelection = newlySelectedTools.map { it.name }.toMutableSet()
freshSelection.addAll(recentlyUsed)  // protect tools used in last 3 iterations
session.activeToolNames = freshSelection
```

- [ ] **Step 13.3: In `SingleAgentSession`, after each tool call, invoke `session.recordToolCall(toolName)`**

- [ ] **Step 13.4: Add `AgentSettings.adaptiveToolSelectionEnabled: Boolean = true`**

- [ ] **Step 13.5: Remove the `STABILIZE` comment block from DynamicToolSelector / AgentOrchestrator**

- [ ] **Step 13.6: Run tests; verify tool count doesn't spike after multi-turn sessions**

---

## Phase 4 — System Prompt Tool List (Text-Based Fallback, Cline Pattern)

**Goal:** For tools that are rarely used but need to exist, describe them in the system prompt as text rather than JSON schema — zero token cost in tool definitions.

**Candidates for text-in-prompt description:**
- `activate_skill`, `deactivate_skill` — rarely needed, LLM understands from description
- `changelist_shelve` — niche VCS operation
- `save_memory` (legacy) — superseded by core_memory_*

### Task 14: Move niche tools to text-in-prompt description

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt`
- Modify: `DynamicToolSelector` (remove from ALWAYS_INCLUDE / groups)

- [ ] **Step 14.1: In `TOOL_POLICY` section of PromptAssembler, add a `<rarely_used_tools>` block listing 5-10 niche tools with name + one-sentence description only**
- [ ] **Step 14.2: Remove those tools from `ALWAYS_INCLUDE` and tool groups in `DynamicToolSelector`**
- [ ] **Step 14.3: They remain registered in `ToolRegistry` and still execute when called — just not in the visible tool schema**
- [ ] **Step 14.4: Test that LLM can still invoke them by name when description context is relevant**

---

## Summary: Token Budget Before/After

| Phase | Tools Sent | Approx Tokens | % of 190K Budget |
|-------|-----------|---------------|-----------------|
| Current (baseline) | 150-160 | ~45,000 | ~24% |
| After Phase 1 (meta-tools) | ~25 | ~8,000 | ~4.2% |
| After Phase 2 (deferred schema) | ~25 names + used schemas | ~3,000-5,000 | ~1.6-2.6% |
| After Phase 3 (adaptive selection) | 15-25 (context-dependent) | ~4,000-8,000 | ~2-4% |
| After Phase 4 (text-in-prompt) | 18-22 | ~6,000-7,000 | ~3.2-3.7% |

**Net result:** From 24% of context eaten by tool definitions to ~3-4%. That's ~20K tokens freed for actual reasoning — directly addresses the file-path response and loop degradation behaviors.

---

## Files Summary

| File | Action | Phase |
|------|--------|-------|
| `tools/integration/JiraTool.kt` | Create | 1 |
| `tools/integration/BambooTool.kt` | Create | 1 |
| `tools/integration/SonarTool.kt` | Create | 1 |
| `tools/integration/BitbucketTool.kt` | Create | 1 |
| `tools/runtime/DebugTool.kt` | Create | 1 |
| `tools/vcs/GitTool.kt` | Create | 1 |
| `tools/framework/SpringTool.kt` | Create | 1 |
| `tools/framework/MavenTool.kt` | Create | 1 |
| `tools/runtime/RuntimeTool.kt` | Create | 1 |
| `tools/DynamicToolSelector.kt` | Modify (groups) | 1 |
| `settings/ToolPreferences.kt` | Modify (meta-tool groups) | 1 |
| `tools/AgentTool.kt` | Add `toSlimDefinition()` | 2 |
| `tools/DeferredToolDefinition.kt` | Create | 2 |
| `orchestrator/AgentOrchestrator.kt` | Modify (deferred + shrink) | 2, 3 |
| `runtime/ConversationSession.kt` | Add recentlyUsedTools | 3 |
| `runtime/SingleAgentSession.kt` | Record tool calls | 3 |
| `orchestrator/PromptAssembler.kt` | Add rarely_used_tools block | 4 |
| `settings/AgentSettings.kt` | Add 2 feature flags | 2, 3 |
| `settings/AgentSettingsConfigurable.kt` | Add 2 checkboxes | 2, 3 |

---

## Rollout Order

1. **Phase 1 first** — biggest token saving, zero LLM behavior change (same actions, different tool name)
2. **Phase 3 next** — prevents bloat from re-accumulating across multi-turn sessions
3. **Phase 2 behind flag** — requires thorough testing (LLM must tolerate slim schemas)
4. **Phase 4 last** — minor savings, validation-heavy

Phase 1 alone solves the critical bug. Phases 2-4 are optimization.
