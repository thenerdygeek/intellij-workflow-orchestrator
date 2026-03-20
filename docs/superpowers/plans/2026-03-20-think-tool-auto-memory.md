# Think Tool + Cross-Session Auto-Memory — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `think` tool for structured reasoning pauses (proven 54% improvement on complex tasks) and a cross-session auto-memory system where the agent saves project learnings to markdown files, loaded at session start.

**Architecture:** The `think` tool is a no-op tool that returns "Thought recorded" — the value is in forcing the LLM to pause and reason via tool call. Auto-memory uses a project-local directory (`{projectBasePath}/.workflow/agent/memory/`) with a `MEMORY.md` index + topic files. A `save_memory` tool lets the LLM persist learnings. Memories are loaded into the system prompt at session start (first 200 lines of MEMORY.md, like Claude Code).

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform, kotlinx.serialization, markdown files

**Research:** `docs/superpowers/research/2026-03-20-sequential-thinking-memory-research.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `agent/src/main/kotlin/.../tools/builtin/ThinkTool.kt` | `think` tool — no-op, returns "Thought recorded" |
| `agent/src/main/kotlin/.../tools/builtin/SaveMemoryTool.kt` | `save_memory` tool — LLM persists a learning to memory file |
| `agent/src/main/kotlin/.../runtime/AgentMemoryStore.kt` | Load/save/manage memory files in project directory |
| `agent/src/test/kotlin/.../tools/builtin/ThinkToolTest.kt` | Think tool tests |
| `agent/src/test/kotlin/.../tools/builtin/SaveMemoryToolTest.kt` | Save memory tool tests |
| `agent/src/test/kotlin/.../runtime/AgentMemoryStoreTest.kt` | Memory store tests |

### Modified Files
| File | Change |
|------|--------|
| `agent/.../AgentService.kt` | Register ThinkTool + SaveMemoryTool |
| `agent/.../tools/ToolCategoryRegistry.kt` | Add tools to core/planning categories |
| `agent/.../tools/DynamicToolSelector.kt` | Add `think` to ALWAYS_INCLUDE |
| `agent/.../runtime/ConversationSession.kt` | Load memories at session start, inject into system prompt |
| `agent/.../orchestrator/PromptAssembler.kt` | Add `memoryContext` parameter to `buildSingleAgentPrompt()` |

---

## Task 1: Think Tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ThinkTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/ThinkToolTest.kt`

- [ ] **Step 1: Write test**

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ThinkToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = ThinkTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("think", tool.name)
        assertTrue(tool.parameters.required.contains("thought"))
        assertTrue(tool.description.contains("think"))
    }

    @Test
    fun `execute returns thought recorded`() = runTest {
        val params = buildJsonObject { put("thought", "I should read the file before editing") }
        val result = tool.execute(params, project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("Thought recorded"))
    }

    @Test
    fun `returns error when thought is missing`() = runTest {
        val params = buildJsonObject { }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
    }

    @Test
    fun `token estimate is minimal`() = runTest {
        val params = buildJsonObject { put("thought", "test") }
        val result = tool.execute(params, project)
        assertTrue(result.tokenEstimate <= 5)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.ThinkToolTest" --rerun --no-build-cache
```

- [ ] **Step 3: Implement ThinkTool**

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A no-op tool that gives the LLM a structured pause to reason.
 *
 * The tool performs ZERO computation — the value is entirely in forcing
 * the LLM to externalize its reasoning via a tool call, creating a
 * natural checkpoint before taking action.
 *
 * Anthropic's benchmarks show 54% improvement on complex multi-step tasks.
 * See: https://www.anthropic.com/engineering/claude-think-tool
 */
class ThinkTool : AgentTool {
    override val name = "think"
    override val description = "Use this tool to think about something before acting. It will not obtain new information or change any files, but lets you reason through complex decisions. Use when: analyzing tool output, planning multi-step changes, choosing between approaches, or before making irreversible edits."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "thought" to ParameterProperty(
                type = "string",
                description = "Your reasoning or analysis. Think through the problem step by step."
            )
        ),
        required = listOf("thought")
    )
    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.TOOLER
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val thought = params["thought"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'thought' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        // No computation — just acknowledge the thought
        return ToolResult(
            content = "Thought recorded.",
            summary = "Thought recorded",
            tokenEstimate = 2
        )
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.ThinkToolTest" --rerun --no-build-cache
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ThinkTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/ThinkToolTest.kt
git commit -m "feat(agent): think tool — structured reasoning pause for complex tasks"
```

---

## Task 2: AgentMemoryStore — Load/Save Memory Files

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentMemoryStore.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/AgentMemoryStoreTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AgentMemoryStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `saveMemory creates memory file and updates index`() {
        val store = AgentMemoryStore(tempDir.toFile())
        store.saveMemory("build-patterns", "This project uses Gradle with custom tasks for Docker builds.")

        val memoryDir = File(tempDir.toFile(), ".workflow/agent/memory")
        assertTrue(File(memoryDir, "build-patterns.md").exists())
        assertTrue(File(memoryDir, "MEMORY.md").readText().contains("build-patterns"))
    }

    @Test
    fun `loadMemories returns formatted string for system prompt`() {
        val store = AgentMemoryStore(tempDir.toFile())
        store.saveMemory("testing", "Tests require Redis running on port 6379.")
        store.saveMemory("api-quirks", "Bamboo API returns XML not JSON for build logs.")

        val memories = store.loadMemories(maxLines = 200)
        assertNotNull(memories)
        assertTrue(memories!!.contains("Redis"))
        assertTrue(memories.contains("Bamboo"))
    }

    @Test
    fun `loadMemories returns null when no memories exist`() {
        val store = AgentMemoryStore(tempDir.toFile())
        assertNull(store.loadMemories())
    }

    @Test
    fun `loadMemories respects maxLines limit`() {
        val store = AgentMemoryStore(tempDir.toFile())
        // Save a very long memory
        store.saveMemory("long", "Line\n".repeat(300))
        val memories = store.loadMemories(maxLines = 50)
        assertNotNull(memories)
        assertTrue(memories!!.lines().size <= 55) // some slack for headers
    }

    @Test
    fun `saveMemory overwrites existing memory with same topic`() {
        val store = AgentMemoryStore(tempDir.toFile())
        store.saveMemory("config", "Old value")
        store.saveMemory("config", "New value")

        val content = File(tempDir.toFile(), ".workflow/agent/memory/config.md").readText()
        assertTrue(content.contains("New value"))
        assertFalse(content.contains("Old value"))
    }

    @Test
    fun `deleteMemory removes file and index entry`() {
        val store = AgentMemoryStore(tempDir.toFile())
        store.saveMemory("temp", "Temporary")
        store.deleteMemory("temp")

        assertFalse(File(tempDir.toFile(), ".workflow/agent/memory/temp.md").exists())
        assertFalse(File(tempDir.toFile(), ".workflow/agent/memory/MEMORY.md").readText().contains("temp"))
    }

    @Test
    fun `listMemories returns all topics`() {
        val store = AgentMemoryStore(tempDir.toFile())
        store.saveMemory("build", "Build info")
        store.saveMemory("testing", "Test info")

        val topics = store.listMemories()
        assertEquals(2, topics.size)
        assertTrue(topics.any { it.contains("build") })
        assertTrue(topics.any { it.contains("testing") })
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.AgentMemoryStoreTest" --rerun --no-build-cache
```

- [ ] **Step 3: Implement AgentMemoryStore**

```kotlin
package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Cross-session auto-memory for the agent.
 *
 * Stores project-specific learnings as markdown files in
 * `{projectBasePath}/.workflow/agent/memory/`:
 *   - MEMORY.md — index of all memories (loaded at session start)
 *   - {topic}.md — individual memory files
 *
 * Design inspired by:
 *   - Claude Code's MEMORY.md (auto-generated, first 200 lines at session start)
 *   - Antigravity's Knowledge Items (agent-contributed, project-local)
 *
 * Memory format per file:
 *   # {Topic}
 *   {Content}
 *
 * MEMORY.md format:
 *   # Agent Memory
 *   - [{topic}]({topic}.md) — {first line of content}
 */
class AgentMemoryStore(private val projectBasePath: File) {

    companion object {
        private val LOG = Logger.getInstance(AgentMemoryStore::class.java)
        private const val MEMORY_DIR = ".workflow/agent/memory"
        private const val INDEX_FILE = "MEMORY.md"
    }

    private val memoryDir: File get() = File(projectBasePath, MEMORY_DIR)

    fun saveMemory(topic: String, content: String) {
        try {
            memoryDir.mkdirs()
            val safeTopic = topic.replace(Regex("[^a-zA-Z0-9_-]"), "-").lowercase()

            // Write memory file
            File(memoryDir, "$safeTopic.md").writeText("# $topic\n\n$content\n")

            // Update index
            rebuildIndex()
        } catch (e: Exception) {
            LOG.warn("AgentMemoryStore: failed to save memory '$topic'", e)
        }
    }

    fun deleteMemory(topic: String) {
        try {
            val safeTopic = topic.replace(Regex("[^a-zA-Z0-9_-]"), "-").lowercase()
            File(memoryDir, "$safeTopic.md").delete()
            rebuildIndex()
        } catch (e: Exception) {
            LOG.warn("AgentMemoryStore: failed to delete memory '$topic'", e)
        }
    }

    fun listMemories(): List<String> {
        if (!memoryDir.exists()) return emptyList()
        return memoryDir.listFiles { f -> f.extension == "md" && f.name != INDEX_FILE }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    /**
     * Load memories for injection into the system prompt.
     * Returns formatted markdown string, or null if no memories exist.
     * Respects maxLines to keep token budget manageable.
     */
    fun loadMemories(maxLines: Int = 200): String? {
        val indexFile = File(memoryDir, INDEX_FILE)
        if (!indexFile.exists()) return null

        val lines = indexFile.readLines()
        if (lines.isEmpty() || lines.all { it.isBlank() }) return null

        val truncated = lines.take(maxLines)
        val result = truncated.joinToString("\n")

        // Load referenced topic files inline (for richer context)
        val topicFiles = memoryDir.listFiles { f -> f.extension == "md" && f.name != INDEX_FILE } ?: return result
        val sb = StringBuilder(result)
        var totalLines = truncated.size

        for (file in topicFiles.sortedByDescending { it.lastModified() }) {
            if (totalLines >= maxLines) break
            val topicContent = file.readLines()
            val available = maxLines - totalLines
            val toAdd = topicContent.take(available)
            sb.append("\n\n").append(toAdd.joinToString("\n"))
            totalLines += toAdd.size
        }

        return sb.toString()
    }

    private fun rebuildIndex() {
        val files = memoryDir.listFiles { f -> f.extension == "md" && f.name != INDEX_FILE }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        val index = buildString {
            appendLine("# Agent Memory")
            appendLine()
            for (file in files) {
                val firstContentLine = file.readLines()
                    .drop(1) // skip "# Topic" header
                    .firstOrNull { it.isNotBlank() }
                    ?.take(80)
                    ?: ""
                appendLine("- [${file.nameWithoutExtension}](${file.name}) — $firstContentLine")
            }
        }
        File(memoryDir, INDEX_FILE).writeText(index)
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.AgentMemoryStoreTest" --rerun --no-build-cache
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentMemoryStore.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/AgentMemoryStoreTest.kt
git commit -m "feat(agent): cross-session auto-memory store — markdown files in project directory"
```

---

## Task 3: SaveMemoryTool — LLM Persists Learnings

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SaveMemoryTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/SaveMemoryToolTest.kt`

- [ ] **Step 1: Write test**

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SaveMemoryToolTest {
    @TempDir
    lateinit var tempDir: Path

    private val tool = SaveMemoryTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("save_memory", tool.name)
        assertTrue(tool.parameters.required.containsAll(listOf("topic", "content")))
    }

    @Test
    fun `returns error when topic is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject { put("content", "test") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
    }

    @Test
    fun `returns error when content is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject { put("topic", "test") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
    }

    @Test
    fun `saves memory when project basePath available`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns tempDir.toFile().absolutePath

        val params = buildJsonObject {
            put("topic", "build-config")
            put("content", "This project uses custom Gradle tasks for Docker image building.")
        }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("saved"))
        assertTrue(File(tempDir.toFile(), ".workflow/agent/memory/build-config.md").exists())
    }
}
```

- [ ] **Step 2: Implement SaveMemoryTool**

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.AgentMemoryStore
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Lets the LLM save project-specific learnings for future sessions.
 *
 * Use for: build patterns, debugging insights, API quirks, user preferences,
 * project conventions discovered during task execution.
 *
 * Memories persist across sessions and are loaded into the system prompt
 * at the start of each new conversation.
 */
class SaveMemoryTool : AgentTool {
    override val name = "save_memory"
    override val description = "Save a project-specific learning for future sessions. Use when you discover something worth remembering: build quirks, API behaviors, project conventions, debugging insights, user preferences. Memories are loaded at the start of each new conversation."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "topic" to ParameterProperty(
                type = "string",
                description = "Short topic name for the memory (e.g., 'build-config', 'api-quirks', 'testing-patterns')"
            ),
            "content" to ParameterProperty(
                type = "string",
                description = "The learning to remember. Be concise but specific — include file paths, commands, or patterns."
            )
        ),
        required = listOf("topic", "content")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val topic = params["topic"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'topic' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'content' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val store = AgentMemoryStore(File(basePath))
        store.saveMemory(topic, content)

        return ToolResult(
            content = "Memory saved: '$topic'. This will be available in future sessions.",
            summary = "Saved memory: $topic",
            tokenEstimate = 5
        )
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.SaveMemoryToolTest" --rerun --no-build-cache
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SaveMemoryTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/SaveMemoryToolTest.kt
git commit -m "feat(agent): save_memory tool — LLM persists project learnings across sessions"
```

---

## Task 4: Load Memories at Session Start

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt:188-230`

- [ ] **Step 1: Add memoryContext parameter to PromptAssembler**

In `PromptAssembler.buildSingleAgentPrompt()`, add a `memoryContext: String? = null` parameter (alongside existing `repoMapContext`):

After the repo map section injection (around line 44-47), add:
```kotlin
if (!memoryContext.isNullOrBlank()) {
    sections.add("<agent_memory>\n$memoryContext\n</agent_memory>")
}
```

- [ ] **Step 2: Load memories in ConversationSession.create()**

In `ConversationSession.create()` (around line 188-230), after the repo map generation and before building the system prompt:

```kotlin
// Load cross-session memories
val memoryContext = try {
    val basePath = project.basePath
    if (basePath != null) {
        AgentMemoryStore(java.io.File(basePath)).loadMemories(maxLines = 200)
    } else null
} catch (_: Exception) { null }
```

Then pass it to the prompt assembler:
```kotlin
val systemPrompt = promptAssembler.buildSingleAgentPrompt(
    projectName = project.name,
    projectPath = project.basePath,
    repoMapContext = repoMap.ifBlank { null },
    memoryContext = memoryContext,
    planMode = planMode
)
```

- [ ] **Step 3: Verify compilation and tests**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt
git commit -m "feat(agent): load cross-session memories into system prompt at session start"
```

---

## Task 5: Register Tools + Update Category/Selector

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`

- [ ] **Step 1: Register both tools in AgentService**

In the tool registry, add alongside existing tools:
```kotlin
register(ThinkTool())
register(SaveMemoryTool())
```

- [ ] **Step 2: Add think to core category in ToolCategoryRegistry**

Add `"think"` to the core category tools list (it should always be available):
```kotlin
tools = listOf(
    "read_file", "edit_file", "search_code", "run_command",
    "diagnostics", "format_code", "optimize_imports",
    "file_structure", "find_definition", "find_references",
    "type_hierarchy", "call_hierarchy",
    "delegate_task", "think"
)
```

Add `"save_memory"` to the planning category:
```kotlin
tools = listOf("create_plan", "update_plan_step", "ask_questions", "save_memory")
```

- [ ] **Step 3: Add think to DynamicToolSelector ALWAYS_INCLUDE**

Add `"think"` to `ALWAYS_INCLUDE` set:
```kotlin
private val ALWAYS_INCLUDE = setOf(
    "read_file", "edit_file", "search_code", "run_command",
    "file_structure", "find_definition", "find_references", "type_hierarchy", "call_hierarchy",
    "diagnostics", "format_code", "optimize_imports",
    "delegate_task", "think"
)
```

Add keyword trigger for save_memory:
```kotlin
"remember" to setOf("save_memory"),
"memory" to setOf("save_memory"),
"learn" to setOf("save_memory"),
```

- [ ] **Step 4: Add think to CORE_TOOL_NAMES in SingleAgentSession**

In `SingleAgentSession.kt`, add `"think"` to `CORE_TOOL_NAMES` (line 77) so it survives context reduction:
```kotlin
val CORE_TOOL_NAMES = setOf("read_file", "edit_file", "search_code", "run_command", "diagnostics", "delegate_task", "think")
```

- [ ] **Step 5: Verify compilation and tests**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "feat(agent): register think + save_memory tools, update categories and selectors"
```

---

## Task 6: Add Memory Rules to System Prompt

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt`

- [ ] **Step 1: Add MEMORY_RULES constant**

After `DELEGATION_RULES`, add:

```kotlin
val MEMORY_RULES = """
    <memory>
    You have access to save_memory to persist project-specific learnings across sessions.

    Save a memory when you discover:
    - Build configuration quirks (e.g., "tests require Redis on port 6379")
    - API behaviors or workarounds (e.g., "Bamboo returns XML for build logs")
    - Project conventions not obvious from code (e.g., "all DTOs must use kotlinx.serialization")
    - Debugging insights that would save time later
    - User preferences expressed during conversation

    Do NOT save:
    - Information already in code or configuration files
    - Temporary task-specific context (use the plan for that)
    - Obvious patterns discoverable by reading the code

    Keep memories concise and actionable. Use descriptive topic names.
    </memory>
""".trimIndent()
```

Include `MEMORY_RULES` in `buildSingleAgentPrompt()` alongside the other rules.

- [ ] **Step 2: Verify compilation and tests**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "feat(agent): add memory rules to system prompt — when to save, what to save"
```

---

## Task 7: Final Verification

- [ ] **Step 1: Run full test suite**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

- [ ] **Step 2: Verify plugin**

```bash
./gradlew verifyPlugin
```

- [ ] **Step 3: Update agent/CLAUDE.md**

Add to the tools table:
```
| Core (always active) | ..., think |
| Planning | create_plan, update_plan_step, ask_questions, save_memory |
```

Add a Memory section:
```
## Cross-Session Memory
- Location: `{projectBasePath}/.workflow/agent/memory/`
- Index: `MEMORY.md` (first 200 lines loaded at session start)
- Topic files: `{topic}.md` (loaded inline after index)
- LLM saves via `save_memory` tool
- Injected into system prompt as `<agent_memory>` section
```

- [ ] **Step 4: Commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs(agent): update CLAUDE.md with think tool and cross-session memory"
```

---

## Implementation Order

```
Task 1: ThinkTool                    ← independent
Task 2: AgentMemoryStore             ← independent
Task 3: SaveMemoryTool               ← depends on Task 2
Task 4: Load memories at session     ← depends on Task 2
Task 5: Register + wire              ← depends on Tasks 1, 3
Task 6: Memory rules in prompt       ← depends on Task 4
Task 7: Final verification           ← depends on all
```

Parallelizable groups:
- **Group A (parallel):** Tasks 1, 2
- **Group B (after Task 2):** Tasks 3, 4
- **Group C (after Tasks 1, 3):** Task 5
- **Group D (after Task 4):** Task 6
- **Group E (final):** Task 7
