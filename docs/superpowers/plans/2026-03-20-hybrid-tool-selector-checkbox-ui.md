# Hybrid Tool Selector + Checkbox UI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a hybrid tool selection system: DynamicToolSelector sends full definitions for likely-needed tools + a `request_tools` meta-tool that lets the LLM activate additional tool categories on demand. Plus a categorized Tools panel with checkboxes for user control over which tools are available.

**Architecture:** Three layers: (1) User checkboxes persist enabled/disabled per tool in settings. (2) DynamicToolSelector filters enabled tools by keywords. (3) `request_tools` meta-tool lets LLM activate categories not matched by keywords. Tool definitions can change between ReAct iterations (mutable active set).

**Tech Stack:** Kotlin, JCEF HTML/CSS/JS, IntelliJ PersistentStateComponent

---

## File Structure

### New Files (3)

| File | Responsibility |
|------|---------------|
| `agent/tools/builtin/RequestToolsTool.kt` | Meta-tool: LLM calls to activate a tool category mid-session |
| `agent/tools/ToolCategoryRegistry.kt` | Maps tools to categories, provides category descriptions for the meta-tool |
| `agent/settings/ToolPreferences.kt` | Persists user's enabled/disabled tool checkboxes per project |

### Modified Files (6)

| File | Change |
|------|--------|
| `agent/tools/DynamicToolSelector.kt` | Accept user preferences (disabled tools), append `request_tools` meta-tool when categories not expanded |
| `agent/runtime/SingleAgentSession.kt` | Allow tool definitions to change between iterations (mutable active set) |
| `agent/orchestrator/AgentOrchestrator.kt` | Pass ToolCategoryRegistry + preferences to session |
| `agent/ui/AgentDashboardPanel.kt` | Replace simple tools popup with full categorized panel |
| `agent/ui/AgentController.kt` | Wire tool preferences, handle request_tools activation |
| `agent/AgentService.kt` | Register RequestToolsTool |

---

## Task 1: ToolCategoryRegistry + ToolPreferences

The data layer: tool categories and user preferences.

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/ToolPreferences.kt`

### ToolCategoryRegistry

Defines the logical grouping of all tools. Single source of truth for categories.

```kotlin
package com.workflow.orchestrator.agent.tools

object ToolCategoryRegistry {

    data class ToolCategory(
        val id: String,           // "core", "ide", "vcs", "jira", etc.
        val displayName: String,  // "Core — Always Active"
        val color: String,        // CSS color for UI
        val tools: List<String>,  // Tool names in this category
        val alwaysActive: Boolean = false  // Core tools always sent
    )

    val CATEGORIES = listOf(
        ToolCategory(
            id = "core",
            displayName = "Core",
            color = "#22C55E",
            alwaysActive = true,
            tools = listOf(
                "read_file", "edit_file", "search_code", "run_command",
                "diagnostics", "format_code", "optimize_imports",
                "file_structure", "find_definition", "find_references",
                "type_hierarchy", "call_hierarchy"
            )
        ),
        ToolCategory(
            id = "ide",
            displayName = "IDE Intelligence",
            color = "#F59E0B",
            tools = listOf("run_inspections", "refactor_rename", "list_quickfixes", "compile_module", "run_tests")
        ),
        ToolCategory(
            id = "vcs",
            displayName = "VCS & Navigation",
            color = "#06B6D4",
            tools = listOf("git_status", "git_blame", "find_implementations")
        ),
        ToolCategory(
            id = "framework",
            displayName = "Spring & Framework",
            color = "#22C55E",
            tools = listOf("spring_context", "spring_endpoints", "spring_bean_graph", "spring_config", "jpa_entities", "project_modules")
        ),
        ToolCategory(
            id = "jira",
            displayName = "Jira",
            color = "#A855F7",
            tools = listOf("jira_get_ticket", "jira_get_transitions", "jira_transition", "jira_comment", "jira_get_comments", "jira_log_work")
        ),
        ToolCategory(
            id = "bamboo",
            displayName = "CI/CD — Bamboo",
            color = "#EF4444",
            tools = listOf("bamboo_build_status", "bamboo_get_build", "bamboo_trigger_build", "bamboo_get_build_log", "bamboo_get_test_results")
        ),
        ToolCategory(
            id = "sonar",
            displayName = "Quality — SonarQube",
            color = "#EC4899",
            tools = listOf("sonar_issues", "sonar_quality_gate", "sonar_coverage", "sonar_search_projects")
        ),
        ToolCategory(
            id = "bitbucket",
            displayName = "Pull Requests — Bitbucket",
            color = "#3B82F6",
            tools = listOf("bitbucket_create_pr")
        ),
        ToolCategory(
            id = "planning",
            displayName = "Planning",
            color = "#F59E0B",
            tools = listOf("create_plan", "update_plan_step")
        )
    )

    /** Get category for a tool name. */
    fun getCategoryForTool(toolName: String): ToolCategory? =
        CATEGORIES.find { toolName in it.tools }

    /** Get non-core categories with their tool lists (for request_tools description). */
    fun getActivatableCategories(): List<ToolCategory> =
        CATEGORIES.filter { !it.alwaysActive }

    /** Get all tool names in a category. */
    fun getToolsInCategory(categoryId: String): List<String> =
        CATEGORIES.find { it.id == categoryId }?.tools ?: emptyList()
}
```

### ToolPreferences

Persists which tools the user has enabled/disabled via checkboxes.

```kotlin
package com.workflow.orchestrator.agent.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "AgentToolPreferences",
    storages = [Storage("workflowAgentToolPreferences.xml")]
)
class ToolPreferences : SimplePersistentStateComponent<ToolPreferences.State>(State()) {

    class State : BaseState() {
        /** Tools explicitly disabled by the user. All tools enabled by default. */
        var disabledTools by list<String>()
    }

    fun isToolEnabled(toolName: String): Boolean =
        toolName !in state.disabledTools

    fun setToolEnabled(toolName: String, enabled: Boolean) {
        val disabled = state.disabledTools.toMutableList()
        if (enabled) {
            disabled.remove(toolName)
        } else {
            if (toolName !in disabled) disabled.add(toolName)
        }
        state.disabledTools = disabled
    }

    fun getDisabledTools(): Set<String> = state.disabledTools.toSet()

    companion object {
        fun getInstance(project: Project): ToolPreferences =
            project.service<ToolPreferences>()
    }
}
```

Register in plugin.xml:
```xml
<projectService serviceImplementation="com.workflow.orchestrator.agent.settings.ToolPreferences"/>
```

- [ ] **Step 1:** Create `ToolCategoryRegistry.kt` with all 9 categories and 44 tools mapped
- [ ] **Step 2:** Create `ToolPreferences.kt` with enable/disable persistence
- [ ] **Step 3:** Register `ToolPreferences` in plugin.xml
- [ ] **Step 4:** Verify: `./gradlew :agent:compileKotlin`
- [ ] **Step 5:** Commit: `feat(agent): ToolCategoryRegistry + ToolPreferences for categorized tool management`

---

## Task 2: RequestToolsTool — The Meta-Tool

The LLM calls this to activate tool categories that weren't expanded by keywords.

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RequestToolsTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

### Design

```kotlin
class RequestToolsTool : AgentTool {
    override val name = "request_tools"
    override val description: String
        get() {
            // Dynamic description listing available categories
            val categories = ToolCategoryRegistry.getActivatableCategories()
            val catList = categories.joinToString("; ") { cat ->
                "'${cat.id}' (${cat.tools.joinToString(", ")})"
            }
            return "Request additional tools from a category. Call when you need tools not currently available. Categories: $catList"
        }
    override val parameters = FunctionParameters(
        properties = mapOf(
            "category" to ParameterProperty(type = "string",
                description = "Category to activate: ide, vcs, framework, jira, bamboo, sonar, bitbucket, planning"),
            "reason" to ParameterProperty(type = "string",
                description = "Brief reason why you need these tools")
        ),
        required = listOf("category")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)
}
```

The execute() method:
1. Validates the category exists
2. Checks which tools in that category are enabled (user prefs)
3. Adds them to the session's **active tool set** (a mutable set maintained by the session)
4. Returns a confirmation listing the newly activated tools

```kotlin
override suspend fun execute(params: JsonObject, project: Project): ToolResult {
    val categoryId = params["category"]?.jsonPrimitive?.content
        ?: return ToolResult("Error: 'category' required", "Error", 5, isError = true)
    val reason = params["reason"]?.jsonPrimitive?.content ?: ""

    val toolNames = ToolCategoryRegistry.getToolsInCategory(categoryId)
    if (toolNames.isEmpty()) {
        val available = ToolCategoryRegistry.getActivatableCategories().joinToString(", ") { it.id }
        return ToolResult("Unknown category '$categoryId'. Available: $available", "Unknown category", 5, isError = true)
    }

    // Filter by user preferences
    val prefs = try { ToolPreferences.getInstance(project) } catch (_: Exception) { null }
    val enabledTools = if (prefs != null) {
        toolNames.filter { prefs.isToolEnabled(it) }
    } else toolNames

    if (enabledTools.isEmpty()) {
        return ToolResult("All tools in '$categoryId' are disabled by user preferences.", "Disabled", 5, isError = true)
    }

    // Signal to the session to expand these tools on the next iteration
    // Uses a project-level holder (same pattern as PlanManager)
    val agentService = try { AgentService.getInstance(project) } catch (_: Exception) { null }
    agentService?.pendingToolActivations?.addAll(enabledTools)

    return ToolResult(
        "Activated ${enabledTools.size} tools from '$categoryId': ${enabledTools.joinToString(", ")}. These tools are now available for your next action.",
        "Activated $categoryId (${enabledTools.size} tools)",
        10
    )
}
```

Add to AgentService:
```kotlin
/** Tools requested by the LLM via request_tools, to be expanded on next iteration. */
val pendingToolActivations = java.util.concurrent.ConcurrentLinkedQueue<String>()
```

- [ ] **Step 1:** Create `RequestToolsTool.kt` with dynamic description and category validation
- [ ] **Step 2:** Add `pendingToolActivations` queue to `AgentService`
- [ ] **Step 3:** Register `RequestToolsTool` in AgentService toolRegistry
- [ ] **Step 4:** Verify compilation
- [ ] **Step 5:** Commit: `feat(agent): request_tools meta-tool — LLM activates tool categories on demand`

---

## Task 3: Update DynamicToolSelector + SingleAgentSession for Hybrid

Make tool definitions mutable between iterations. DynamicToolSelector uses preferences + pending activations.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt`

### DynamicToolSelector Changes

Add parameters for user preferences and pending activations:

```kotlin
fun selectTools(
    allTools: Collection<AgentTool>,
    conversationContext: String,
    disabledTools: Set<String> = emptySet(),        // From ToolPreferences
    activatedTools: Set<String> = emptySet()         // From request_tools
): List<AgentTool> {
    val lowerContext = conversationContext.lowercase()

    // Start with always-include tools
    val selectedNames = ALWAYS_INCLUDE.toMutableSet()

    // Add keyword-triggered tools
    for ((keyword, toolNames) in TOOL_TRIGGERS) {
        if (lowerContext.contains(keyword)) {
            selectedNames.addAll(toolNames)
        }
    }

    // Add LLM-activated tools (from request_tools)
    selectedNames.addAll(activatedTools)

    // Remove user-disabled tools
    selectedNames.removeAll(disabledTools)

    // Always include request_tools itself (so LLM can ask for more)
    selectedNames.add("request_tools")

    return allTools.filter { it.name in selectedNames }
}
```

Remove the old `ALWAYS_INCLUDE` set and `TOOL_TRIGGERS` map — replace them with lookups from `ToolCategoryRegistry`:

Actually, keep `TOOL_TRIGGERS` for keyword matching (it's fine), but derive `ALWAYS_INCLUDE` from `ToolCategoryRegistry.CATEGORIES.filter { it.alwaysActive }`.

### SingleAgentSession Changes

Currently tool definitions are fixed for the entire loop. Change: at the start of each iteration, check for pending activations and expand the tool set.

In the iteration loop, before the LLM call:

```kotlin
// Check for pending tool activations from request_tools
val pendingActivations = try {
    AgentService.getInstance(project).pendingToolActivations
} catch (_: Exception) { null }

if (pendingActivations != null && pendingActivations.isNotEmpty()) {
    val newToolNames = mutableSetOf<String>()
    while (pendingActivations.isNotEmpty()) {
        pendingActivations.poll()?.let { newToolNames.add(it) }
    }
    // Expand active tools
    val allToolsFromRegistry = /* need access to full tool registry */
    for (name in newToolNames) {
        val tool = allToolsFromRegistry[name]
        if (tool != null && name !in activeTools) {
            activeTools = activeTools + (name to tool)
            activeToolDefs = activeToolDefs + tool.toToolDefinition()
        }
    }
    LOG.info("SingleAgentSession: expanded tools with ${newToolNames.size} from request_tools")
}
```

This requires `activeTools` and `activeToolDefs` to be `var` (mutable), which they already are in the current code (`var activeToolDefs = toolDefinitions`, `var activeTools = tools`).

The session needs access to the full tool registry to look up tools by name. Pass it as a parameter or access via `AgentService.getInstance(project).toolRegistry`.

### AgentOrchestrator Changes

Pass user preferences when calling DynamicToolSelector:

```kotlin
val prefs = try { ToolPreferences.getInstance(project) } catch (_: Exception) { null }
val disabledTools = prefs?.getDisabledTools() ?: emptySet()

val selectedTools = DynamicToolSelector.selectTools(
    allToolsCollection, toolContext,
    disabledTools = disabledTools
)
```

- [ ] **Step 1:** Update `DynamicToolSelector.selectTools()` to accept `disabledTools` and `activatedTools` params
- [ ] **Step 2:** Add `request_tools` to the always-included set
- [ ] **Step 3:** Update `SingleAgentSession` iteration loop to check pending activations and expand tool set
- [ ] **Step 4:** Update `AgentOrchestrator` to pass user preferences to DynamicToolSelector
- [ ] **Step 5:** Test: verify request_tools activates a category and next iteration has the tools
- [ ] **Step 6:** Commit: `feat(agent): hybrid tool selection — keywords + request_tools + user preferences`

---

## Task 4: Categorized Tools Panel in JCEF

The UI: a categorized panel with checkboxes, badges, search, and tool detail popup.

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html` (add tools panel HTML/CSS/JS)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt` (add JS bridge methods)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt` (replace popup with panel)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` (wire checkboxes to ToolPreferences)

### Approach

Instead of a modal popup, open a **side panel** or **overlay** in the JCEF browser when the Tools button is clicked. This keeps everything in the same rendering context.

Add to agent-chat.html:
- `renderToolsPanel(toolsJson)` — renders the full categorized list with checkboxes
- `closeToolsPanel()` — hides it
- `showToolDetail(toolName)` — shows the detail popup with description, params, JSON schema, example
- Checkbox toggle calls back to Kotlin via JBCefJSQuery

### JS Functions:

```javascript
function renderToolsPanel(toolsJson) { /* parse JSON, render categories, checkboxes */ }
function closeToolsPanel() { /* hide panel */ }
function toggleToolEnabled(toolName, enabled) { /* call Kotlin bridge */ }
function showToolDetail(toolName) { /* show detail overlay */ }
```

### Kotlin Bridges:

```kotlin
// In AgentCefPanel
fun showToolsPanel(toolsJson: String) { callJs("renderToolsPanel(${jsonStr(toolsJson)})") }
fun hideToolsPanel() { callJs("closeToolsPanel()") }

// JBCefJSQuery for checkbox toggle
var onToolToggled: ((String, Boolean) -> Unit)? = null
// Bridge: window._toggleTool = function(name, enabled) { ... }
```

### Controller Wiring:

```kotlin
// Tools button click
dashboard.toolsButton.addActionListener {
    val tools = buildToolsPanelData() // Build JSON with categories, tools, enabled state
    dashboard.showToolsPanel(tools)
}

// Checkbox toggle callback
dashboard.setCefToolCallbacks(
    onToolToggled = { name, enabled ->
        ToolPreferences.getInstance(project).setToolEnabled(name, enabled)
    }
)
```

- [ ] **Step 1:** Add tools panel HTML/CSS to agent-chat.html (categories, checkboxes, detail view)
- [ ] **Step 2:** Add JS functions: renderToolsPanel, closeToolsPanel, toggleToolEnabled, showToolDetail
- [ ] **Step 3:** Add JBCefJSQuery bridge for tool toggle in AgentCefPanel
- [ ] **Step 4:** Update AgentDashboardPanel to show tools panel via JCEF
- [ ] **Step 5:** Wire AgentController to build tools JSON from ToolCategoryRegistry + ToolPreferences
- [ ] **Step 6:** Wire checkbox toggle to ToolPreferences.setToolEnabled()
- [ ] **Step 7:** Test: open tools panel, uncheck a tool, send message, verify tool not in request
- [ ] **Step 8:** Commit: `feat(agent): categorized Tools panel with checkboxes, detail view, and tool control`

---

## Task 5: Tool Detail View in JCEF

When a user clicks a tool in the panel, show a detail popup with 4 tabs.

**File:** `agent/src/main/resources/webview/agent-chat.html`

### Tabs:
1. **Description** — full description text
2. **Parameters** — table with name, type, required, description
3. **JSON Schema** — the exact JSON definition sent to the LLM (with copy button)
4. **Example** — sample request (what LLM sends) + response (what comes back)

### Data

The tool detail JSON is built in Kotlin from the AgentTool definition:
```kotlin
fun buildToolDetail(tool: AgentTool): String {
    return Json.encodeToString(mapOf(
        "name" to tool.name,
        "description" to tool.description,
        "category" to ToolCategoryRegistry.getCategoryForTool(tool.name)?.id,
        "parameters" to tool.parameters.properties.map { (name, prop) ->
            mapOf("name" to name, "type" to prop.type, "description" to prop.description,
                  "required" to (name in tool.parameters.required))
        },
        "schema" to Json.encodeToString(tool.toToolDefinition()),
        "example_request" to buildExampleRequest(tool),
        "example_response" to buildExampleResponse(tool)
    ))
}
```

- [ ] **Step 1:** Add tool detail overlay HTML/CSS to agent-chat.html
- [ ] **Step 2:** Add JS function `showToolDetail(toolJson)` with tab switching
- [ ] **Step 3:** Build tool detail JSON in AgentController from AgentTool definitions
- [ ] **Step 4:** Wire click → JCEF → detail overlay
- [ ] **Step 5:** Add copy button for JSON schema tab
- [ ] **Step 6:** Commit: `feat(agent): tool detail view — description, parameters, JSON schema, examples`

---

## Verification

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

Manual verification:
1. Click Tools button → categorized panel opens with checkboxes ✓
2. Uncheck a tool → send message → tool not in LLM request ✓
3. LLM calls request_tools("build") → next iteration has compile_module + run_tests ✓
4. Click a tool → detail popup shows description, params, JSON, example ✓
5. Re-enable a tool → available again on next message ✓
