# Custom Subagents System — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add user-definable custom subagents via `.workflow/agents/` directory — markdown files with YAML frontmatter defining tools, model, maxTurns, skills preloading, and persistent per-agent memory.

**Architecture:** A new `AgentDefinitionRegistry` scans `.workflow/agents/` and `~/.workflow-orchestrator/agents/` for markdown files. `DelegateTaskTool` is extended to accept an `agent` parameter that selects a custom subagent definition. `WorkerSession` uses the subagent's system prompt, tool restrictions, and model. Per-agent memory is stored in `.workflow/agent-memory/{name}/` with a `MEMORY.md` index.

**Tech Stack:** Kotlin, IntelliJ Platform, existing WorkerSession/DelegateTaskTool infrastructure

**Reference:** `docs/superpowers/research/2026-03-21-claude-code-skills-subagents-reference.md`

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `agent/src/main/kotlin/.../runtime/AgentDefinitionRegistry.kt` | Scans for agent markdown files, parses YAML frontmatter, provides agent definitions |
| `agent/src/main/kotlin/.../runtime/AgentMemoryStore.kt` | Already exists — extend for per-agent memory scopes |
| `agent/src/test/kotlin/.../runtime/AgentDefinitionRegistryTest.kt` | Tests for agent definition parsing |

### Modified Files

| File | Changes |
|------|---------|
| `agent/src/main/kotlin/.../tools/builtin/DelegateTaskTool.kt` | Add `agent` parameter to select custom subagent definition. Apply definition's tools, model, maxTurns. |
| `agent/src/main/kotlin/.../runtime/WorkerSession.kt` | Accept optional model override. |
| `agent/src/main/kotlin/.../orchestrator/PromptAssembler.kt` | Add method to build subagent system prompt with preloaded skills. |
| `agent/src/main/kotlin/.../runtime/ConversationSession.kt` | Load agent definitions at session start. |

---

## Task 1: AgentDefinitionRegistry — Parse Agent Markdown Files

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentDefinitionRegistry.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/AgentDefinitionRegistryTest.kt`

- [ ] **Step 1: Create AgentDefinition data class and registry**

```kotlin
package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Scans for custom subagent definitions in markdown files with YAML frontmatter.
 *
 * Locations (priority order):
 * 1. Project: {basePath}/.workflow/agents/{name}.md
 * 2. User: ~/.workflow-orchestrator/agents/{name}.md
 *
 * Format:
 * ---
 * name: code-reviewer
 * description: Reviews code for quality and best practices
 * tools: read_file, search_code, glob_files
 * disallowed-tools: edit_file, run_command
 * model: sonnet
 * max-turns: 15
 * skills: systematic-debugging, api-conventions
 * memory: project
 * ---
 * You are a code reviewer. Analyze code and provide feedback.
 */
class AgentDefinitionRegistry(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(AgentDefinitionRegistry::class.java)
    }

    data class AgentDefinition(
        val name: String,
        val description: String,
        val systemPrompt: String,
        val tools: List<String>? = null,           // allowlist (null = inherit all)
        val disallowedTools: List<String> = emptyList(), // denylist
        val model: String? = null,                  // null = inherit
        val maxTurns: Int = 10,
        val skills: List<String> = emptyList(),     // skills to preload
        val memory: MemoryScope? = null,
        val filePath: String,
        val scope: AgentScope
    )

    enum class AgentScope { USER, PROJECT }
    enum class MemoryScope { USER, PROJECT, LOCAL }

    private val agents = mutableMapOf<String, AgentDefinition>()

    fun scan() {
        agents.clear()
        // User-level agents (lower priority)
        val userDir = File(System.getProperty("user.home"), ".workflow-orchestrator/agents")
        scanDirectory(userDir, AgentScope.USER)
        // Project-level agents (higher priority — overwrites user)
        val projectDir = File(project.basePath ?: return, ".workflow/agents")
        scanDirectory(projectDir, AgentScope.PROJECT)
        LOG.info("AgentDefinitionRegistry: loaded ${agents.size} agent definitions")
    }

    fun getAgent(name: String): AgentDefinition? = agents[name]

    fun getAllAgents(): List<AgentDefinition> = agents.values.sortedBy { it.name }

    fun buildDescriptionIndex(): String {
        val sorted = getAllAgents()
        if (sorted.isEmpty()) return ""
        return "Available subagents:\n" + sorted.joinToString("\n") {
            "- ${it.name} — ${it.description}"
        }
    }

    private fun scanDirectory(dir: File, scope: AgentScope) {
        if (!dir.isDirectory) return
        val files = dir.listFiles { f -> f.isFile && f.extension == "md" } ?: return
        for (file in files) {
            try {
                val content = file.readText()
                val (frontmatter, body) = parseFrontmatter(content) ?: continue
                val name = frontmatter["name"] ?: file.nameWithoutExtension
                val description = frontmatter["description"] ?: continue // required

                agents[name] = AgentDefinition(
                    name = name,
                    description = description,
                    systemPrompt = body.trim(),
                    tools = frontmatter["tools"]?.let { parseList(it) }?.takeIf { it.isNotEmpty() },
                    disallowedTools = frontmatter["disallowed-tools"]?.let { parseList(it) } ?: emptyList(),
                    model = frontmatter["model"]?.trim()?.takeIf { it.isNotBlank() },
                    maxTurns = frontmatter["max-turns"]?.toIntOrNull() ?: 10,
                    skills = frontmatter["skills"]?.let { parseList(it) } ?: emptyList(),
                    memory = frontmatter["memory"]?.trim()?.uppercase()?.let {
                        try { MemoryScope.valueOf(it) } catch (_: Exception) { null }
                    },
                    filePath = file.absolutePath,
                    scope = scope
                )
            } catch (e: Exception) {
                LOG.warn("AgentDefinitionRegistry: failed to parse ${file.name}: ${e.message}")
            }
        }
    }

    private fun parseFrontmatter(content: String): Pair<Map<String, String>, String>? {
        if (!content.trimStart().startsWith("---")) return null
        val endIdx = content.indexOf("---", content.indexOf("---") + 3)
        if (endIdx < 0) return null
        val frontmatterStr = content.substring(content.indexOf("---") + 3, endIdx).trim()
        val body = content.substring(endIdx + 3)
        val map = mutableMapOf<String, String>()
        for (line in frontmatterStr.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                map[key] = value
            }
        }
        return map to body
    }

    private fun parseList(value: String): List<String> =
        value.split(",").map { it.trim() }.filter { it.isNotBlank() }
}
```

- [ ] **Step 2: Write tests**

```kotlin
@Test
fun `parses agent definition from markdown file`() {
    val agentDir = tempDir.resolve("agents").also { it.mkdirs() }
    File(agentDir, "code-reviewer.md").writeText("""
        ---
        name: code-reviewer
        description: Reviews code for quality
        tools: read_file, search_code, glob_files
        model: sonnet
        max-turns: 15
        skills: systematic-debugging
        memory: project
        ---
        You are a code reviewer. Analyze code and provide feedback.
    """.trimIndent())

    val registry = AgentDefinitionRegistry(project)
    // Point at temp dir... (test setup)
    val agent = registry.getAgent("code-reviewer")
    assertNotNull(agent)
    assertEquals("code-reviewer", agent!!.name)
    assertEquals(listOf("read_file", "search_code", "glob_files"), agent.tools)
    assertEquals("sonnet", agent.model)
    assertEquals(15, agent.maxTurns)
    assertEquals(listOf("systematic-debugging"), agent.skills)
    assertEquals(AgentDefinitionRegistry.MemoryScope.PROJECT, agent.memory)
}

@Test
fun `project agents override user agents with same name`() {
    // Create user and project agents with same name, verify project wins
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :agent:test --tests "*.AgentDefinitionRegistryTest" -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentDefinitionRegistry.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/AgentDefinitionRegistryTest.kt
git commit -m "feat(agent): add AgentDefinitionRegistry for custom subagent definitions

Scans .workflow/agents/ (project) and ~/.workflow-orchestrator/agents/ (user)
for markdown files with YAML frontmatter. Supports tools, disallowed-tools,
model, max-turns, skills preloading, and memory scope. Project overrides user."
```

---

## Task 2: Wire Custom Subagents into DelegateTaskTool

**Files:**
- Modify: `agent/src/main/kotlin/.../tools/builtin/DelegateTaskTool.kt`
- Modify: `agent/src/main/kotlin/.../runtime/ConversationSession.kt`

- [ ] **Step 1: Add `agent` parameter to DelegateTaskTool**

Add a new optional parameter to the tool:

```kotlin
ToolParameter("agent", "string",
    "Name of a custom subagent definition to use. If specified, the worker uses the subagent's system prompt, tool restrictions, and model.",
    required = false)
```

- [ ] **Step 2: Load agent definition in execute()**

In `execute()`, after parameter extraction, add:

```kotlin
val agentName = params["agent"]?.jsonPrimitive?.contentOrNull
val agentDef = if (agentName != null) {
    agentService.agentDefinitionRegistry?.getAgent(agentName)
        ?: return ToolResult("Error: subagent '$agentName' not found", "Error", 50, isError = true)
} else null
```

- [ ] **Step 3: Apply agent definition to worker creation**

When creating the WorkerSession, apply the definition's overrides:

```kotlin
// Tools: apply agent definition's allow/deny lists
val workerTools = if (agentDef != null) {
    var tools = if (agentDef.tools != null) {
        allRegisteredTools.filter { it.name in agentDef.tools }
    } else {
        allRegisteredTools.toList()
    }
    if (agentDef.disallowedTools.isNotEmpty()) {
        tools = tools.filter { it.name !in agentDef.disallowedTools }
    }
    tools
} else {
    // existing tool selection logic
}

// System prompt: use agent definition's system prompt + preloaded skills
val systemPrompt = if (agentDef != null) {
    buildSubagentPrompt(agentDef, agentService)
} else {
    // existing system prompt logic
}

// Max iterations from agent definition
val maxIter = agentDef?.maxTurns ?: 10
val workerSession = WorkerSession(maxIterations = maxIter, parentJob = parentJob)
```

- [ ] **Step 4: Build subagent prompt with preloaded skills**

```kotlin
private fun buildSubagentPrompt(def: AgentDefinitionRegistry.AgentDefinition, agentService: AgentService): String {
    val sb = StringBuilder()
    sb.appendLine(def.systemPrompt)

    // Preload skills content
    if (def.skills.isNotEmpty()) {
        sb.appendLine("\n<preloaded_skills>")
        for (skillName in def.skills) {
            val content = agentService.skillManager?.registry?.loadSkillContent(skillName)
            if (content != null) {
                sb.appendLine("<skill name=\"$skillName\">")
                sb.appendLine(content.take(10_000))
                sb.appendLine("</skill>")
            }
        }
        sb.appendLine("</preloaded_skills>")
    }

    return sb.toString()
}
```

- [ ] **Step 5: Add agentDefinitionRegistry to ConversationSession and AgentService**

In `ConversationSession.create()`, add:
```kotlin
val agentDefRegistry = AgentDefinitionRegistry(project).also { it.scan() }
```

Store it accessible for DelegateTaskTool.

- [ ] **Step 6: Compile and test**

```bash
./gradlew :agent:test --tests "*.DelegateTaskToolTest" -x verifyPlugin
```

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/DelegateTaskTool.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt
git commit -m "feat(agent): wire custom subagent definitions into DelegateTaskTool

delegate_task accepts optional 'agent' parameter to select a custom
subagent definition. Worker uses the subagent's system prompt, tool
restrictions, max-turns, and preloaded skill content."
```

---

## Task 3: Per-Subagent Persistent Memory

**Files:**
- Modify: `agent/src/main/kotlin/.../runtime/AgentDefinitionRegistry.kt`
- Modify: `agent/src/main/kotlin/.../tools/builtin/DelegateTaskTool.kt`

- [ ] **Step 1: Implement memory directory resolution**

Add to `AgentDefinitionRegistry`:

```kotlin
fun getMemoryDirectory(agentDef: AgentDefinition, project: Project): File? {
    val scope = agentDef.memory ?: return null
    val name = agentDef.name
    return when (scope) {
        MemoryScope.USER -> File(System.getProperty("user.home"), ".workflow-orchestrator/agent-memory/$name")
        MemoryScope.PROJECT -> File(project.basePath ?: return null, ".workflow/agent-memory/$name")
        MemoryScope.LOCAL -> File(project.basePath ?: return null, ".workflow/agent-memory-local/$name")
    }.also { it.mkdirs() }
}
```

- [ ] **Step 2: Inject memory into worker system prompt**

In `DelegateTaskTool.buildSubagentPrompt()`, after preloaded skills, add:

```kotlin
// Per-agent memory
val memoryDir = agentService.agentDefinitionRegistry?.getMemoryDirectory(def, project)
if (memoryDir != null && memoryDir.isDirectory) {
    val memoryIndex = File(memoryDir, "MEMORY.md")
    if (memoryIndex.isFile) {
        val memoryContent = memoryIndex.readText().lines().take(200).joinToString("\n")
        sb.appendLine("\n<agent_memory>")
        sb.appendLine(memoryContent)
        sb.appendLine("</agent_memory>")
    }
    sb.appendLine("\n<memory_instructions>")
    sb.appendLine("You have persistent memory at: ${memoryDir.absolutePath}")
    sb.appendLine("Use read_file/edit_file to read and update your memory files.")
    sb.appendLine("Keep MEMORY.md as an index of your learnings (max 200 lines).")
    sb.appendLine("</memory_instructions>")
}
```

- [ ] **Step 3: Ensure read_file/edit_file are available for memory access**

In the worker tools setup, if memory is enabled, ensure read_file and edit_file are in the tool list (even if the agent definition restricts tools):

```kotlin
if (agentDef?.memory != null) {
    val memoryTools = setOf("read_file", "edit_file")
    val missingTools = memoryTools.filter { name -> workerTools.none { it.name == name } }
    if (missingTools.isNotEmpty()) {
        workerTools = workerTools + allRegisteredTools.filter { it.name in missingTools }
    }
}
```

- [ ] **Step 4: Write test**

```kotlin
@Test
fun `memory directory resolved correctly for project scope`() {
    val def = AgentDefinitionRegistry.AgentDefinition(
        name = "reviewer",
        description = "test",
        systemPrompt = "test",
        memory = AgentDefinitionRegistry.MemoryScope.PROJECT,
        filePath = "/tmp/test.md",
        scope = AgentDefinitionRegistry.AgentScope.PROJECT
    )
    val dir = registry.getMemoryDirectory(def, project)
    assertNotNull(dir)
    assertTrue(dir!!.path.contains(".workflow/agent-memory/reviewer"))
}
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentDefinitionRegistry.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/DelegateTaskTool.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/AgentDefinitionRegistryTest.kt
git commit -m "feat(agent): per-subagent persistent memory with user/project/local scopes

Subagents with memory: user/project/local get a persistent MEMORY.md
index injected into their system prompt. read_file/edit_file auto-enabled
for memory management. Memory directory auto-created on first use."
```

---

## Task 4: Subagent Descriptions in System Prompt & Documentation

**Files:**
- Modify: `agent/src/main/kotlin/.../orchestrator/PromptAssembler.kt`
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Add subagent descriptions to system prompt**

In `PromptAssembler.buildSingleAgentPrompt()`, add after the skills section:

```kotlin
// 7. Available Subagents (custom agent definitions)
if (!agentDescriptions.isNullOrBlank()) {
    sections.add("<available_subagents>\n$agentDescriptions\n\nTo delegate to a subagent, call delegate_task with the agent parameter.\n</available_subagents>")
}
```

Add `agentDescriptions: String? = null` parameter to `buildSingleAgentPrompt()`.

Wire it in AgentOrchestrator to pass `agentDefinitionRegistry.buildDescriptionIndex()`.

- [ ] **Step 2: Update CLAUDE.md**

Add a new section:

```markdown
## Custom Subagents

User-definable agent definitions via markdown files with YAML frontmatter:
- Project: `{basePath}/.workflow/agents/{name}.md`
- User: `~/.workflow-orchestrator/agents/{name}.md`

**Frontmatter fields:**
| Field | Default | Description |
|-------|---------|-------------|
| `name` | filename | Unique identifier |
| `description` | — | When to delegate (required) |
| `tools` | inherit all | Tool allowlist |
| `disallowed-tools` | [] | Tool denylist |
| `model` | inherit | Model override |
| `max-turns` | 10 | Max agentic iterations |
| `skills` | [] | Skills preloaded at startup |
| `memory` | none | Persistent memory: user/project/local |

**Memory locations:**
- `user`: `~/.workflow-orchestrator/agent-memory/{name}/`
- `project`: `.workflow/agent-memory/{name}/`
- `local`: `.workflow/agent-memory-local/{name}/`

Invoked via `delegate_task(agent="name", task="...")`.
```

- [ ] **Step 3: Run all tests**

```bash
./gradlew :agent:test --rerun --no-build-cache -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt agent/CLAUDE.md
git commit -m "feat(agent): subagent descriptions in system prompt, update docs

LLM sees available subagent descriptions in system prompt and can delegate
to custom subagents via delegate_task(agent='name'). Documentation updated
with full subagent specification."
```

---

## Verification

```bash
./gradlew :agent:test --rerun --no-daemon
./gradlew verifyPlugin
```

Manual verification:
1. Create `.workflow/agents/code-reviewer.md` with tools restriction — verify worker only has listed tools
2. Create agent with `skills: systematic-debugging` — verify skill content appears in worker's context
3. Create agent with `memory: project` — verify `.workflow/agent-memory/code-reviewer/` is created
4. Ask LLM to delegate to custom agent — verify it appears in subagent descriptions
5. Verify project agents override user agents with same name
