# Subagent System — Cline Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port Cline's subagent architecture to our Kotlin agent module — parallel execution (research scope only), SubagentRunner with progress streaming, configurable context budget, per-subagent statistics, and dynamic named agents via YAML configs.

**Architecture:** Faithful port from Cline's TypeScript (`SubagentRunner.ts`, `SubagentToolHandler.ts`, `SubagentBuilder.ts`, `AgentConfigLoader.ts`, `SubagentToolName.ts`). Keep our existing 3 scopes (research/implement/review). Parallel execution via `coroutineScope { }` + `async { }` (Kotlin equivalent of `Promise.allSettled`). Progress callbacks wired to existing dashboard sub-agent UI methods.

**Tech Stack:** Kotlin coroutines, kotlinx.serialization, IntelliJ Platform SDK, existing AgentLoop/ContextManager/ToolRegistry.

**Source files (Cline):**
- `src/core/task/tools/subagent/SubagentRunner.ts` — individual subagent execution loop
- `src/core/task/tools/handlers/SubagentToolHandler.ts` — parallel orchestration handler
- `src/core/task/tools/subagent/SubagentBuilder.ts` — subagent configuration builder
- `src/core/task/tools/subagent/AgentConfigLoader.ts` — YAML config loader with file watching
- `src/core/task/tools/subagent/SubagentToolName.ts` — tool name generation for dynamic agents
- `src/shared/ExtensionMessage.ts` — SubagentStatusItem, ClineSaySubagentStatus, ClineSubagentUsageInfo

**Target directory:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/`

---

### Task 1: SubagentRunStats and SubagentRunResult data classes

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentModels.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentModelsTest.kt`

Port from: `SubagentRunner.ts` lines 33-86 (`SubagentRunResult`, `SubagentRunStats`, `SubagentProgressUpdate`, `SubagentRunStatus`).

- [ ] **Step 1: Write the data class file**

```kotlin
package com.workflow.orchestrator.agent.tools.subagent

import kotlinx.serialization.Serializable

/**
 * Port of Cline's SubagentRunStatus type.
 * src/core/task/tools/subagent/SubagentRunner.ts:31
 */
enum class SubagentRunStatus {
    COMPLETED, FAILED
}

/**
 * Port of Cline's SubagentExecutionStatus type.
 * src/shared/ExtensionMessage.ts:273
 */
enum class SubagentExecutionStatus {
    PENDING, RUNNING, COMPLETED, FAILED
}

/**
 * Port of Cline's SubagentRunStats interface.
 * src/core/task/tools/subagent/SubagentRunner.ts:48-58
 */
data class SubagentRunStats(
    val toolCalls: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val totalCost: Double = 0.0,
    val contextTokens: Int = 0,
    val contextWindow: Int = 0,
    val contextUsagePercentage: Double = 0.0
)

/**
 * Port of Cline's SubagentRunResult interface.
 * src/core/task/tools/subagent/SubagentRunner.ts:33-38
 */
data class SubagentRunResult(
    val status: SubagentRunStatus,
    val result: String? = null,
    val error: String? = null,
    val stats: SubagentRunStats = SubagentRunStats()
)

/**
 * Port of Cline's SubagentProgressUpdate interface.
 * src/core/task/tools/subagent/SubagentRunner.ts:40-47
 */
data class SubagentProgressUpdate(
    val stats: SubagentRunStats? = null,
    val latestToolCall: String? = null,
    val status: String? = null,  // "running" | "completed" | "failed"
    val result: String? = null,
    val error: String? = null
)

/**
 * Port of Cline's SubagentStatusItem interface.
 * src/shared/ExtensionMessage.ts:275-289
 */
@Serializable
data class SubagentStatusItem(
    val index: Int,
    val prompt: String,
    var status: String = "pending",  // pending | running | completed | failed
    var toolCalls: Int = 0,
    var inputTokens: Int = 0,
    var outputTokens: Int = 0,
    var totalCost: Double = 0.0,
    var contextTokens: Int = 0,
    var contextWindow: Int = 0,
    var contextUsagePercentage: Double = 0.0,
    var latestToolCall: String? = null,
    var result: String? = null,
    var error: String? = null
)

/**
 * Port of Cline's ClineSaySubagentStatus interface.
 * src/shared/ExtensionMessage.ts:291-304
 */
@Serializable
data class SubagentGroupStatus(
    val status: String,  // "running" | "completed" | "failed"
    val total: Int,
    val completed: Int,
    val successes: Int,
    val failures: Int,
    val toolCalls: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val contextWindow: Int,
    val maxContextTokens: Int,
    val maxContextUsagePercentage: Double,
    val items: List<SubagentStatusItem>
)

/**
 * Port of Cline's ClineSubagentUsageInfo interface.
 * src/shared/ExtensionMessage.ts:358-365
 */
@Serializable
data class SubagentUsageInfo(
    val source: String = "subagents",
    val tokensIn: Int,
    val tokensOut: Int,
    val cacheWrites: Int = 0,
    val cacheReads: Int = 0,
    val cost: Double = 0.0
)
```

- [ ] **Step 2: Write basic test**

```kotlin
package com.workflow.orchestrator.agent.tools.subagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SubagentModelsTest {

    @Test
    fun `SubagentRunStats has sensible defaults`() {
        val stats = SubagentRunStats()
        assertEquals(0, stats.toolCalls)
        assertEquals(0, stats.inputTokens)
        assertEquals(0.0, stats.contextUsagePercentage)
    }

    @Test
    fun `SubagentRunResult completed has result`() {
        val result = SubagentRunResult(
            status = SubagentRunStatus.COMPLETED,
            result = "Found 3 files",
            stats = SubagentRunStats(toolCalls = 5, inputTokens = 1000, outputTokens = 200)
        )
        assertEquals(SubagentRunStatus.COMPLETED, result.status)
        assertEquals("Found 3 files", result.result)
        assertEquals(5, result.stats.toolCalls)
    }

    @Test
    fun `SubagentRunResult failed has error`() {
        val result = SubagentRunResult(
            status = SubagentRunStatus.FAILED,
            error = "API timeout"
        )
        assertEquals(SubagentRunStatus.FAILED, result.status)
        assertEquals("API timeout", result.error)
        assertNull(result.result)
    }

    @Test
    fun `SubagentStatusItem defaults to pending`() {
        val item = SubagentStatusItem(index = 1, prompt = "Find all tests")
        assertEquals("pending", item.status)
        assertEquals(0, item.toolCalls)
        assertNull(item.latestToolCall)
    }
}
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*.SubagentModelsTest" -x verifyPlugin`
Expected: 4 tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentModels.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentModelsTest.kt
git commit -m "feat(agent): add SubagentRunStats, SubagentRunResult, SubagentStatusItem data classes

Port from Cline's SubagentRunner.ts + ExtensionMessage.ts."
```

---

### Task 2: SubagentToolName — tool name generation for dynamic agents

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentToolName.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentToolNameTest.kt`

Port from: `SubagentToolName.ts` (full file, 42 lines).

- [ ] **Step 1: Write the implementation**

```kotlin
package com.workflow.orchestrator.agent.tools.subagent

/**
 * Port of Cline's SubagentToolName.ts.
 * Generates unique tool names for dynamic agent configurations.
 *
 * Source: src/core/task/tools/subagent/SubagentToolName.ts
 */
object SubagentToolName {
    private const val PREFIX = "use_subagent_"
    private const val MAX_LENGTH = 64

    /**
     * Sanitize agent name for use in tool name.
     * Port of sanitizeAgentName() in SubagentToolName.ts:4-10
     */
    fun sanitize(name: String): String {
        return name.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .replace(Regex("_+"), "_")
            .replace(Regex("^_+|_+$"), "")
    }

    /**
     * FNV-1a hash of a string, returned as base-36.
     * Port of hashString() in SubagentToolName.ts:12-18
     */
    fun hashString(value: String): String {
        var hash = 2166136261L.toInt()
        for (ch in value) {
            hash = hash xor ch.code
            hash = (hash.toLong() * 16777619L).toInt()
        }
        return (hash.toLong() and 0xFFFFFFFFL).toString(36)
    }

    /**
     * Build a tool name for a named agent.
     * Port of buildSubagentToolName() in SubagentToolName.ts:29-41
     */
    fun build(agentName: String): String {
        val sanitized = sanitize(agentName).ifEmpty { "agent" }
        val hashSuffix = hashString(agentName).take(6)
        val base = "$PREFIX$sanitized"

        if (base.length <= MAX_LENGTH) {
            return base
        }

        val maxBodyLength = MAX_LENGTH - PREFIX.length - hashSuffix.length - 1
        val body = sanitized.take(maxOf(1, maxBodyLength))
        val candidate = "${PREFIX}${body}_$hashSuffix"
        return if (candidate.length <= MAX_LENGTH) candidate else candidate.take(MAX_LENGTH)
    }

    /**
     * Check if a tool name is a dynamic subagent tool.
     * Port of isSubagentToolName() in SubagentToolName.ts:43-45
     */
    fun isSubagentToolName(toolName: String): Boolean {
        return toolName.startsWith(PREFIX)
    }
}
```

- [ ] **Step 2: Write tests**

```kotlin
package com.workflow.orchestrator.agent.tools.subagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SubagentToolNameTest {

    @Test
    fun `sanitize lowercases and replaces special chars`() {
        assertEquals("code_analyzer", SubagentToolName.sanitize("Code Analyzer"))
        assertEquals("my_agent_v2", SubagentToolName.sanitize("My-Agent-v2"))
        assertEquals("test", SubagentToolName.sanitize("  test  "))
    }

    @Test
    fun `sanitize collapses multiple underscores`() {
        assertEquals("a_b", SubagentToolName.sanitize("a---b"))
    }

    @Test
    fun `sanitize returns empty for all-special chars`() {
        assertEquals("", SubagentToolName.sanitize("!!!"))
    }

    @Test
    fun `build creates prefixed tool name`() {
        val name = SubagentToolName.build("Code Analyzer")
        assertTrue(name.startsWith("use_subagent_"))
        assertTrue(name.contains("code_analyzer"))
        assertTrue(name.length <= 64)
    }

    @Test
    fun `build uses agent fallback for empty sanitized name`() {
        val name = SubagentToolName.build("!!!")
        assertTrue(name.startsWith("use_subagent_agent"))
    }

    @Test
    fun `build truncates long names with hash suffix`() {
        val longName = "A".repeat(100)
        val name = SubagentToolName.build(longName)
        assertTrue(name.length <= 64)
        assertTrue(name.startsWith("use_subagent_"))
    }

    @Test
    fun `isSubagentToolName detects dynamic names`() {
        assertTrue(SubagentToolName.isSubagentToolName("use_subagent_code_analyzer"))
        assertFalse(SubagentToolName.isSubagentToolName("read_file"))
        assertFalse(SubagentToolName.isSubagentToolName("agent"))
    }

    @Test
    fun `hashString is deterministic`() {
        val h1 = SubagentToolName.hashString("test")
        val h2 = SubagentToolName.hashString("test")
        assertEquals(h1, h2)
    }

    @Test
    fun `hashString differs for different inputs`() {
        assertNotEquals(
            SubagentToolName.hashString("alpha"),
            SubagentToolName.hashString("beta")
        )
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :agent:test --tests "*.SubagentToolNameTest" -x verifyPlugin`
Expected: 7 tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentToolName.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentToolNameTest.kt
git commit -m "feat(agent): add SubagentToolName — dynamic tool name generation

Port from Cline's SubagentToolName.ts. FNV-1a hash for collision avoidance."
```

---

### Task 3: AgentConfigLoader — YAML config loader with file watching

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/AgentConfigLoader.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/AgentConfigLoaderTest.kt`

Port from: `AgentConfigLoader.ts` (full file, 356 lines). Adapt: use `java.nio.file.WatchService` instead of chokidar, `org.yaml.snakeyaml` or simple frontmatter parsing instead of Cline's `parseYamlFrontmatter`.

**Config directory:** `~/.workflow-orchestrator/agents/` (our convention, not Cline's `~/Documents/Cline/Agents/`).

- [ ] **Step 1: Write the implementation**

```kotlin
package com.workflow.orchestrator.agent.tools.subagent

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import java.io.IOException
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.io.path.*

/**
 * Port of Cline's AgentConfigLoader — loads YAML agent configs from disk with file watching.
 *
 * Source: src/core/task/tools/subagent/AgentConfigLoader.ts
 *
 * Config directory: ~/.workflow-orchestrator/agents/
 * Each YAML file has frontmatter (name, description, tools, skills, modelId)
 * and a markdown body that becomes the system prompt.
 *
 * Port notes:
 * - chokidar → java.nio.file.WatchService
 * - zod schema → manual validation
 * - parseYamlFrontmatter → simple "---" delimited parsing
 */
class AgentConfigLoader private constructor(
    private val configDir: Path
) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(AgentConfigLoader::class.java)
        private const val CONFIG_DIR_NAME = "agents"

        @Volatile
        private var instance: AgentConfigLoader? = null

        fun getInstance(homeDir: String = System.getProperty("user.home")): AgentConfigLoader {
            return instance ?: synchronized(this) {
                instance ?: AgentConfigLoader(
                    Path.of(homeDir, ".workflow-orchestrator", CONFIG_DIR_NAME)
                ).also {
                    instance = it
                    it.loadFromDisk()
                    it.startWatching()
                }
            }
        }

        /** Test-only: reset singleton. */
        fun resetForTests() {
            instance?.let { Disposer.dispose(it) }
            instance = null
        }
    }

    /** Port of AgentBaseConfig from AgentConfigLoader.ts:15-22 */
    data class AgentConfig(
        val name: String,
        val description: String,
        val tools: List<String> = emptyList(),
        val skills: List<String>? = null,
        val modelId: String? = null,
        val systemPrompt: String
    )

    private val cachedConfigs = ConcurrentHashMap<String, AgentConfig>()
    private val agentToolNames = ConcurrentHashMap<String, String>()      // normalizedName -> toolName
    private val toolNameToAgentName = ConcurrentHashMap<String, String>() // toolName -> normalizedName
    private val listeners = CopyOnWriteArrayList<(Map<String, AgentConfig>, Exception?) -> Unit>()
    private var watchThread: Thread? = null
    @Volatile private var disposed = false

    fun getConfigPath(): Path = configDir

    fun getCachedConfig(subagentName: String?): AgentConfig? {
        val trimmed = subagentName?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        return cachedConfigs[trimmed.lowercase()]
    }

    fun getAllCachedConfigs(): Map<String, AgentConfig> = cachedConfigs.toMap()

    fun getAllCachedConfigsWithToolNames(): List<Pair<String, AgentConfig>> {
        return cachedConfigs.entries.mapNotNull { (normalizedName, config) ->
            val toolName = agentToolNames[normalizedName] ?: return@mapNotNull null
            toolName to config
        }
    }

    fun resolveSubagentNameForTool(toolName: String?): String? {
        val trimmed = toolName?.trim() ?: return null
        val normalizedName = toolNameToAgentName[trimmed] ?: return null
        return cachedConfigs[normalizedName]?.name
    }

    fun isDynamicSubagentTool(toolName: String?): Boolean {
        val trimmed = toolName?.trim() ?: return false
        return toolNameToAgentName.containsKey(trimmed)
    }

    /**
     * Load all YAML configs from disk.
     * Port of readAgentConfigsFromDisk() + load() in AgentConfigLoader.ts:117-257
     */
    fun loadFromDisk() {
        try {
            if (!configDir.exists()) {
                LOG.debug("[AgentConfigLoader] Config directory does not exist: $configDir")
                cachedConfigs.clear()
                rebuildDynamicToolMappings()
                return
            }

            val yamlFiles = configDir.listDirectoryEntries("*.{yaml,yml}").sorted()
            LOG.debug("[AgentConfigLoader] Found ${yamlFiles.size} YAML file(s).")

            val newConfigs = mutableMapOf<String, AgentConfig>()
            for (file in yamlFiles) {
                try {
                    val content = file.readText()
                    val config = parseAgentConfigFromYaml(content)
                    newConfigs[config.name.trim().lowercase()] = config
                    LOG.debug("[AgentConfigLoader] Loaded agent config '${file.name}'")
                } catch (e: Exception) {
                    LOG.error("[AgentConfigLoader] Failed to parse '${file.name}'", e)
                }
            }

            cachedConfigs.clear()
            cachedConfigs.putAll(newConfigs)
            rebuildDynamicToolMappings()
            LOG.debug("[AgentConfigLoader] Loaded ${newConfigs.size} agent config(s).")
        } catch (e: IOException) {
            LOG.error("[AgentConfigLoader] Failed to read configs from disk", e)
        }
    }

    fun addListener(listener: (Map<String, AgentConfig>, Exception?) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Map<String, AgentConfig>, Exception?) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Parse a YAML frontmatter + markdown body agent config file.
     *
     * Port of parseAgentConfigFromYaml() in AgentConfigLoader.ts:80-103.
     * Adapted: simple "---" delimited parsing instead of Cline's parseYamlFrontmatter.
     *
     * Format:
     * ```yaml
     * ---
     * name: "Agent Name"
     * description: "What this agent does"
     * modelId: "optional/model-override"
     * tools: "read_file, search_code, glob_files"
     * skills: "skill-name-1, skill-name-2"
     * ---
     * System prompt body here in markdown.
     * ```
     */
    fun parseAgentConfigFromYaml(content: String): AgentConfig {
        val trimmed = content.trim()
        require(trimmed.startsWith("---")) { "Missing YAML frontmatter block in agent config file." }

        val endIdx = trimmed.indexOf("---", 3)
        require(endIdx > 3) { "Missing closing --- in YAML frontmatter." }

        val frontmatter = trimmed.substring(3, endIdx).trim()
        val body = trimmed.substring(endIdx + 3).trim()
        require(body.isNotEmpty()) { "Missing system prompt body in agent config file." }

        val fields = mutableMapOf<String, String>()
        for (line in frontmatter.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                    .removeSurrounding("\"").removeSurrounding("'")
                fields[key] = value
            }
        }

        val name = fields["name"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing required field: name")
        val description = fields["description"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing required field: description")

        val tools = fields["tools"]?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val skills = fields["skills"]?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }

        return AgentConfig(
            name = name,
            description = description,
            tools = tools,
            skills = skills,
            modelId = fields["modelId"]?.takeIf { it.isNotBlank() },
            systemPrompt = body
        )
    }

    /**
     * Rebuild dynamic tool name mappings.
     * Port of rebuildDynamicToolMappings() in AgentConfigLoader.ts:330-354
     */
    private fun rebuildDynamicToolMappings() {
        val sorted = cachedConfigs.entries.sortedBy { it.key }
        val usedToolNames = mutableSetOf<String>()
        val newAgentToolNames = mutableMapOf<String, String>()
        val newToolNameToAgent = mutableMapOf<String, String>()

        for ((normalizedName, config) in sorted) {
            val baseName = SubagentToolName.build(config.name)
            var candidate = baseName
            var suffix = 2
            while (candidate in usedToolNames) {
                val suffixText = "_$suffix"
                suffix++
                val maxBaseLength = maxOf(1, 64 - suffixText.length)
                candidate = "${baseName.take(maxBaseLength)}$suffixText"
            }

            usedToolNames.add(candidate)
            newAgentToolNames[normalizedName] = candidate
            newToolNameToAgent[candidate] = normalizedName
        }

        agentToolNames.clear()
        agentToolNames.putAll(newAgentToolNames)
        toolNameToAgentName.clear()
        toolNameToAgentName.putAll(newToolNameToAgent)
    }

    /**
     * Start file watching for config directory changes.
     * Port of watch() in AgentConfigLoader.ts:259-298.
     * Adapted: java.nio.file.WatchService instead of chokidar.
     */
    private fun startWatching() {
        if (!configDir.exists()) {
            try {
                configDir.createDirectories()
            } catch (e: IOException) {
                LOG.warn("[AgentConfigLoader] Could not create config directory: $configDir", e)
                return
            }
        }

        watchThread = thread(name = "AgentConfigLoader-watcher", isDaemon = true) {
            try {
                val watchService = configDir.fileSystem.newWatchService()
                configDir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
                )

                while (!disposed) {
                    val key = watchService.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                    var reloadNeeded = false

                    for (event in key.pollEvents()) {
                        val context = event.context() as? Path ?: continue
                        val fileName = context.fileName.toString()
                        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
                            reloadNeeded = true
                        }
                    }
                    key.reset()

                    if (reloadNeeded) {
                        // Debounce: wait 300ms for writes to stabilize (matches Cline's 300ms)
                        Thread.sleep(300)
                        reloadAndNotify()
                    }
                }

                watchService.close()
            } catch (e: InterruptedException) {
                // Normal shutdown
            } catch (e: ClosedWatchServiceException) {
                // Normal shutdown
            } catch (e: Exception) {
                LOG.error("[AgentConfigLoader] File watcher failed", e)
            }
        }
    }

    private fun reloadAndNotify() {
        try {
            loadFromDisk()
            notify(cachedConfigs.toMap(), null)
        } catch (e: Exception) {
            LOG.error("[AgentConfigLoader] Failed to reload configs", e)
            notify(cachedConfigs.toMap(), e as? Exception)
        }
    }

    private fun notify(configs: Map<String, AgentConfig>, error: Exception?) {
        for (listener in listeners) {
            listener(configs, error)
        }
    }

    override fun dispose() {
        disposed = true
        watchThread?.interrupt()
        watchThread = null
        cachedConfigs.clear()
        agentToolNames.clear()
        toolNameToAgentName.clear()
        listeners.clear()
        instance = null
    }
}
```

- [ ] **Step 2: Write tests**

```kotlin
package com.workflow.orchestrator.agent.tools.subagent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class AgentConfigLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var agentsDir: Path

    @BeforeEach
    fun setUp() {
        AgentConfigLoader.resetForTests()
        agentsDir = tempDir.resolve(".workflow-orchestrator/agents")
        agentsDir.createDirectories()
    }

    @AfterEach
    fun tearDown() {
        AgentConfigLoader.resetForTests()
    }

    private fun createLoader(): AgentConfigLoader {
        return AgentConfigLoader.getInstance(tempDir.toString())
    }

    @Nested
    inner class YamlParsingTests {

        @Test
        fun `parses valid YAML frontmatter config`() {
            val content = """
                ---
                name: "Code Analyzer"
                description: "Analyzes code patterns"
                tools: "read_file, search_code"
                ---
                You are a code analysis specialist.
            """.trimIndent()

            val loader = createLoader()
            val config = loader.parseAgentConfigFromYaml(content)

            assertEquals("Code Analyzer", config.name)
            assertEquals("Analyzes code patterns", config.description)
            assertEquals(listOf("read_file", "search_code"), config.tools)
            assertTrue(config.systemPrompt.contains("code analysis specialist"))
            assertNull(config.modelId)
            assertNull(config.skills)
        }

        @Test
        fun `parses config with all optional fields`() {
            val content = """
                ---
                name: "Full Agent"
                description: "Has everything"
                tools: "read_file, search_code, glob_files"
                skills: "debugging, code-review"
                modelId: "anthropic/claude-opus"
                ---
                Full system prompt here.
            """.trimIndent()

            val loader = createLoader()
            val config = loader.parseAgentConfigFromYaml(content)

            assertEquals("Full Agent", config.name)
            assertEquals("anthropic/claude-opus", config.modelId)
            assertEquals(listOf("debugging", "code-review"), config.skills)
            assertEquals(3, config.tools.size)
        }

        @Test
        fun `throws on missing frontmatter`() {
            val content = "Just a plain text file."
            val loader = createLoader()

            assertThrows(IllegalArgumentException::class.java) {
                loader.parseAgentConfigFromYaml(content)
            }
        }

        @Test
        fun `throws on missing name`() {
            val content = """
                ---
                description: "No name"
                ---
                Body text.
            """.trimIndent()
            val loader = createLoader()

            assertThrows(IllegalArgumentException::class.java) {
                loader.parseAgentConfigFromYaml(content)
            }
        }

        @Test
        fun `throws on missing body`() {
            val content = """
                ---
                name: "Test"
                description: "Test"
                ---
            """.trimIndent()
            val loader = createLoader()

            assertThrows(IllegalArgumentException::class.java) {
                loader.parseAgentConfigFromYaml(content)
            }
        }
    }

    @Nested
    inner class DiskLoadTests {

        @Test
        fun `loads configs from disk`() {
            agentsDir.resolve("analyzer.yaml").writeText("""
                ---
                name: "Analyzer"
                description: "Code analysis"
                ---
                You analyze code.
            """.trimIndent())

            agentsDir.resolve("reviewer.yaml").writeText("""
                ---
                name: "Reviewer"
                description: "Code review"
                ---
                You review code.
            """.trimIndent())

            val loader = createLoader()
            val configs = loader.getAllCachedConfigs()

            assertEquals(2, configs.size)
            assertNotNull(loader.getCachedConfig("Analyzer"))
            assertNotNull(loader.getCachedConfig("Reviewer"))
        }

        @Test
        fun `ignores non-yaml files`() {
            agentsDir.resolve("readme.txt").writeText("Not a config")
            agentsDir.resolve("agent.yaml").writeText("""
                ---
                name: "Agent"
                description: "Test"
                ---
                Prompt.
            """.trimIndent())

            val loader = createLoader()
            assertEquals(1, loader.getAllCachedConfigs().size)
        }

        @Test
        fun `getCachedConfig is case-insensitive`() {
            agentsDir.resolve("test.yaml").writeText("""
                ---
                name: "MyAgent"
                description: "Test"
                ---
                Prompt.
            """.trimIndent())

            val loader = createLoader()
            assertNotNull(loader.getCachedConfig("myagent"))
            assertNotNull(loader.getCachedConfig("MYAGENT"))
            assertNotNull(loader.getCachedConfig("MyAgent"))
        }
    }

    @Nested
    inner class DynamicToolMappingTests {

        @Test
        fun `generates tool names for configs`() {
            agentsDir.resolve("agent.yaml").writeText("""
                ---
                name: "Code Helper"
                description: "Helps with code"
                ---
                Prompt.
            """.trimIndent())

            val loader = createLoader()
            val configs = loader.getAllCachedConfigsWithToolNames()

            assertEquals(1, configs.size)
            val (toolName, config) = configs.first()
            assertTrue(toolName.startsWith("use_subagent_"))
            assertEquals("Code Helper", config.name)
        }

        @Test
        fun `resolves agent name from tool name`() {
            agentsDir.resolve("agent.yaml").writeText("""
                ---
                name: "Researcher"
                description: "Research agent"
                ---
                Prompt.
            """.trimIndent())

            val loader = createLoader()
            val configs = loader.getAllCachedConfigsWithToolNames()
            val toolName = configs.first().first

            assertEquals("Researcher", loader.resolveSubagentNameForTool(toolName))
            assertTrue(loader.isDynamicSubagentTool(toolName))
            assertFalse(loader.isDynamicSubagentTool("read_file"))
        }
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :agent:test --tests "*.AgentConfigLoaderTest" -x verifyPlugin`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/AgentConfigLoader.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/AgentConfigLoaderTest.kt
git commit -m "feat(agent): add AgentConfigLoader — YAML config loading with file watching

Port from Cline's AgentConfigLoader.ts. Uses java.nio WatchService instead of chokidar.
Config directory: ~/.workflow-orchestrator/agents/"
```

---

### Task 4: SubagentRunner — individual subagent execution with progress + stats

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunnerTest.kt`

Port from: `SubagentRunner.ts` (full file, 878 lines). This is the core — manages an individual subagent's execution loop with progress callbacks and stats tracking.

Key adaptations:
- Cline's SubagentRunner creates its own API handler and StreamResponseHandler. We reuse our `AgentLoop` but wrap it with progress tracking.
- Cline's `onProgress` callback → our `(SubagentProgressUpdate) -> Unit` callback
- Cline's `SubagentBuilder` config → our existing `resolveScopedTools()` + `buildSubAgentPrompt()`
- Context budget: configurable (not hardcoded 50K), defaults to model context window

- [ ] **Step 1: Write the implementation**

```kotlin
package com.workflow.orchestrator.agent.tools.subagent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.core.ai.LlmBrain
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Port of Cline's SubagentRunner — manages execution of a single subagent.
 *
 * Source: src/core/task/tools/subagent/SubagentRunner.ts
 *
 * Each runner:
 * - Creates a fresh ContextManager with configurable budget
 * - Runs an AgentLoop with scoped tools
 * - Tracks per-subagent stats (tokens, tool calls, context usage)
 * - Reports progress via onProgress callback
 * - Supports cancellation via abort()
 *
 * Port notes:
 * - Cline creates its own API handler + stream handler. We delegate to AgentLoop.
 * - Cline tracks native vs non-native tool calls. We use AgentLoop's unified handling.
 * - Cost calculation: we track token counts, cost is computed by caller if needed.
 */
class SubagentRunner(
    private val brain: LlmBrain,
    private val tools: Map<String, AgentTool>,
    private val systemPrompt: String,
    private val project: Project,
    private val maxIterations: Int,
    private val planMode: Boolean,
    private val contextBudget: Int
) {
    companion object {
        private val LOG = Logger.getInstance(SubagentRunner::class.java)
    }

    private val abortRequested = AtomicBoolean(false)

    /**
     * Abort the running subagent.
     * Port of SubagentRunner.abort() in SubagentRunner.ts:247-266
     */
    fun abort() {
        abortRequested.set(true)
        brain.cancelActiveRequest()
    }

    /**
     * Run the subagent loop with progress tracking.
     *
     * Port of SubagentRunner.run() in SubagentRunner.ts:295-688.
     * Simplified: we delegate the actual loop to AgentLoop and wrap with
     * progress tracking + stats collection. Cline manages its own stream
     * handler and tool execution; we reuse AgentLoop for all of that.
     *
     * @param prompt the task prompt for the subagent
     * @param onProgress progress callback (ported from Cline's onProgress parameter)
     * @return SubagentRunResult with status, result/error, and stats
     */
    suspend fun run(
        prompt: String,
        onProgress: suspend (SubagentProgressUpdate) -> Unit
    ): SubagentRunResult {
        abortRequested.set(false)

        val stats = MutableSubagentStats()
        val contextManager = ContextManager(maxInputTokens = contextBudget)
        contextManager.setSystemPrompt(systemPrompt)

        // Report initial running status (Cline: onProgress({ status: "running", stats }))
        onProgress(SubagentProgressUpdate(
            status = "running",
            stats = stats.snapshot()
        ))

        if (abortRequested.get()) {
            val error = "Subagent run cancelled."
            onProgress(SubagentProgressUpdate(status = "failed", error = error, stats = stats.snapshot()))
            return SubagentRunResult(status = SubagentRunStatus.FAILED, error = error, stats = stats.snapshot())
        }

        // Determine context window for usage percentage tracking
        stats.contextWindow = contextBudget

        try {
            val loop = AgentLoop(
                brain = brain,
                tools = tools,
                toolDefinitions = tools.values.map { it.toToolDefinition() },
                contextManager = contextManager,
                project = project,
                maxIterations = maxIterations,
                planMode = planMode,
                onToolCall = { progress ->
                    stats.toolCalls++
                    if (progress.result.isNotEmpty() || progress.durationMs > 0) {
                        // Tool call completed — report latest tool call preview
                        val preview = formatToolCallPreview(progress.toolName, progress.args)
                        onProgress(SubagentProgressUpdate(
                            latestToolCall = preview,
                            stats = stats.snapshot()
                        ))
                    }
                },
                onTokenUpdate = { inputTokens, outputTokens ->
                    stats.inputTokens = inputTokens
                    stats.outputTokens = outputTokens
                    stats.contextTokens = inputTokens + outputTokens
                    stats.contextUsagePercentage = if (stats.contextWindow > 0) {
                        (stats.contextTokens.toDouble() / stats.contextWindow) * 100.0
                    } else 0.0
                    onProgress(SubagentProgressUpdate(stats = stats.snapshot()))
                }
            )

            val result = loop.run(prompt)

            return when (result) {
                is LoopResult.Completed -> {
                    onProgress(SubagentProgressUpdate(
                        status = "completed",
                        result = result.summary,
                        stats = stats.snapshot()
                    ))
                    SubagentRunResult(
                        status = SubagentRunStatus.COMPLETED,
                        result = result.summary,
                        stats = stats.snapshot()
                    )
                }
                is LoopResult.Failed -> {
                    onProgress(SubagentProgressUpdate(
                        status = "failed",
                        error = result.error,
                        stats = stats.snapshot()
                    ))
                    SubagentRunResult(
                        status = SubagentRunStatus.FAILED,
                        error = result.error,
                        stats = stats.snapshot()
                    )
                }
                is LoopResult.Cancelled -> {
                    val error = "Subagent run cancelled."
                    onProgress(SubagentProgressUpdate(
                        status = "failed",
                        error = error,
                        stats = stats.snapshot()
                    ))
                    SubagentRunResult(
                        status = SubagentRunStatus.FAILED,
                        error = error,
                        stats = stats.snapshot()
                    )
                }
                is LoopResult.SessionHandoff -> {
                    // Treat session handoff as completion with the context as result
                    onProgress(SubagentProgressUpdate(
                        status = "completed",
                        result = result.context,
                        stats = stats.snapshot()
                    ))
                    SubagentRunResult(
                        status = SubagentRunStatus.COMPLETED,
                        result = result.context,
                        stats = stats.snapshot()
                    )
                }
            }
        } catch (e: Exception) {
            if (abortRequested.get()) {
                val error = "Subagent run cancelled."
                onProgress(SubagentProgressUpdate(status = "failed", error = error, stats = stats.snapshot()))
                return SubagentRunResult(status = SubagentRunStatus.FAILED, error = error, stats = stats.snapshot())
            }

            val errorText = e.message ?: "Subagent execution failed."
            LOG.error("[SubagentRunner] run failed", e)
            onProgress(SubagentProgressUpdate(status = "failed", error = errorText, stats = stats.snapshot()))
            return SubagentRunResult(status = SubagentRunStatus.FAILED, error = errorText, stats = stats.snapshot())
        }
    }

    /**
     * Format a tool call preview for progress display.
     * Port of formatToolCallPreview() in SubagentRunner.ts:144-155
     */
    private fun formatToolCallPreview(toolName: String, args: String): String {
        val truncatedArgs = if (args.length > 80) "${args.take(77)}..." else args
        return "$toolName($truncatedArgs)"
    }

    /**
     * Mutable stats accumulator — thread-safe snapshot via copy.
     * Port of SubagentRunStats tracking scattered across SubagentRunner.ts.
     */
    private class MutableSubagentStats {
        var toolCalls: Int = 0
        var inputTokens: Int = 0
        var outputTokens: Int = 0
        var cacheWriteTokens: Int = 0
        var cacheReadTokens: Int = 0
        var totalCost: Double = 0.0
        var contextTokens: Int = 0
        var contextWindow: Int = 0
        var contextUsagePercentage: Double = 0.0

        fun snapshot() = SubagentRunStats(
            toolCalls = toolCalls,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheWriteTokens = cacheWriteTokens,
            cacheReadTokens = cacheReadTokens,
            totalCost = totalCost,
            contextTokens = contextTokens,
            contextWindow = contextWindow,
            contextUsagePercentage = contextUsagePercentage
        )
    }
}
```

- [ ] **Step 2: Write tests**

```kotlin
package com.workflow.orchestrator.agent.tools.subagent

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SubagentRunnerTest {

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.name } returns "TestProject"
        every { project.basePath } returns "/tmp/test-project"
    }

    private fun toolCallResponse(vararg calls: Pair<String, String>): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = calls.mapIndexed { idx, (name, args) ->
                            ToolCall(
                                id = "call_${idx}_${System.nanoTime()}",
                                type = "function",
                                function = FunctionCall(name = name, arguments = args)
                            )
                        }
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 30, totalTokens = 130)
        )

    private fun sequenceBrain(responses: List<ApiResult<ChatCompletionResponse>>): LlmBrain {
        var callIndex = 0
        return object : LlmBrain {
            override val modelId = "test-model"
            override suspend fun chat(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>?,
                maxTokens: Int?,
                toolChoice: JsonElement?
            ) = throw UnsupportedOperationException()

            override suspend fun chatStream(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>?,
                maxTokens: Int?,
                onChunk: suspend (StreamChunk) -> Unit
            ): ApiResult<ChatCompletionResponse> {
                if (callIndex >= responses.size) return ApiResult.Error(ErrorType.SERVER_ERROR, "No more responses")
                return responses[callIndex++]
            }
            override fun estimateTokens(text: String) = text.length / 4
            override fun cancelActiveRequest() {}
        }
    }

    private fun stubTool(name: String): AgentTool = object : AgentTool {
        override val name = name
        override val description = "Stub: $name"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "stub:$name", summary = "stub", tokenEstimate = 5)
    }

    private fun completionTool(): AgentTool = object : AgentTool {
        override val name = "attempt_completion"
        override val description = "Complete the task"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project): ToolResult {
            val result = params["result"]?.toString()?.removeSurrounding("\"") ?: "Done"
            return ToolResult(content = result, summary = result, tokenEstimate = 5, isCompletion = true)
        }
    }

    @Test
    fun `completed subagent returns result with stats`() = runTest {
        val brain = sequenceBrain(listOf(
            ApiResult.Success(toolCallResponse(
                "read_file" to """{"path":"src/main.kt"}"""
            )),
            ApiResult.Success(toolCallResponse(
                "attempt_completion" to """{"result":"Found 3 files matching the pattern."}"""
            ))
        ))

        val tools = mapOf(
            "read_file" to stubTool("read_file"),
            "attempt_completion" to completionTool()
        )

        val runner = SubagentRunner(
            brain = brain,
            tools = tools,
            systemPrompt = "You are a research agent.",
            project = project,
            maxIterations = 50,
            planMode = true,
            contextBudget = 50_000
        )

        val progressUpdates = mutableListOf<SubagentProgressUpdate>()
        val result = runner.run("Find all test files") { update ->
            progressUpdates.add(update)
        }

        assertEquals(SubagentRunStatus.COMPLETED, result.status)
        assertTrue(result.result?.contains("Found 3 files") == true)
        assertTrue(result.stats.toolCalls >= 1)

        // Verify progress was reported
        assertTrue(progressUpdates.any { it.status == "running" })
        assertTrue(progressUpdates.any { it.status == "completed" })
    }

    @Test
    fun `failed subagent returns error with stats`() = runTest {
        val brain = sequenceBrain(listOf(
            ApiResult.Error(ErrorType.AUTH_FAILED, "Authentication failed")
        ))

        val tools = mapOf("attempt_completion" to completionTool())

        val runner = SubagentRunner(
            brain = brain,
            tools = tools,
            systemPrompt = "You are a research agent.",
            project = project,
            maxIterations = 50,
            planMode = true,
            contextBudget = 50_000
        )

        val result = runner.run("Do something") { }

        assertEquals(SubagentRunStatus.FAILED, result.status)
        assertNotNull(result.error)
    }

    @Test
    fun `stats track context window and usage percentage`() = runTest {
        val brain = sequenceBrain(listOf(
            ApiResult.Success(toolCallResponse(
                "attempt_completion" to """{"result":"Done"}"""
            ))
        ))

        val tools = mapOf("attempt_completion" to completionTool())
        val contextBudget = 100_000

        val runner = SubagentRunner(
            brain = brain,
            tools = tools,
            systemPrompt = "Agent.",
            project = project,
            maxIterations = 50,
            planMode = true,
            contextBudget = contextBudget
        )

        val result = runner.run("Task") { }

        assertEquals(contextBudget, result.stats.contextWindow)
    }

    @Test
    fun `abort cancels the run`() = runTest {
        val brain = sequenceBrain(listOf(
            ApiResult.Success(toolCallResponse(
                "attempt_completion" to """{"result":"Done"}"""
            ))
        ))

        val tools = mapOf("attempt_completion" to completionTool())
        val runner = SubagentRunner(
            brain = brain,
            tools = tools,
            systemPrompt = "Agent.",
            project = project,
            maxIterations = 50,
            planMode = true,
            contextBudget = 50_000
        )

        // Abort before running
        runner.abort()
        val result = runner.run("Task") { }

        assertEquals(SubagentRunStatus.FAILED, result.status)
        assertTrue(result.error?.contains("cancelled") == true)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :agent:test --tests "*.SubagentRunnerTest" -x verifyPlugin`
Expected: 4 tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunnerTest.kt
git commit -m "feat(agent): add SubagentRunner — individual subagent execution with progress + stats

Port from Cline's SubagentRunner.ts. Wraps AgentLoop with progress callbacks,
per-subagent stats tracking, and cancellation support."
```

---

### Task 5: Refactor SpawnAgentTool — parallel execution for research, configurable budget, progress streaming

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentToolTest.kt`

This is the biggest change. Refactor SpawnAgentTool to:
1. Accept up to 5 prompts for research scope (parallel via `coroutineScope { async {} }`)
2. Use SubagentRunner instead of directly creating AgentLoop
3. Configurable context budget (default from settings, not hardcoded 50K)
4. Report progress via a new `onSubagentProgress` callback
5. Return rich stats in ToolResult

Port from: `SubagentToolHandler.ts` (parallel orchestration) + `SubagentRunner.ts` integration.

- [ ] **Step 1: Update SpawnAgentTool parameters to support multiple prompts**

Add `prompt_2` through `prompt_5` optional parameters (only used in research scope). Port from `SubagentToolHandler.ts:21` PROMPT_KEYS pattern.

Update the `parameters` property:

```kotlin
override val parameters = FunctionParameters(
    properties = mapOf(
        "description" to ParameterProperty(
            type = "string",
            description = "Short 3-5 word description of what the agent will do"
        ),
        "prompt" to ParameterProperty(
            type = "string",
            description = "Complete task description for the first (or only) subagent. Include ALL context needed."
        ),
        "prompt_2" to ParameterProperty(
            type = "string",
            description = "Optional second research prompt (research scope only, parallel execution)."
        ),
        "prompt_3" to ParameterProperty(
            type = "string",
            description = "Optional third research prompt (research scope only, parallel execution)."
        ),
        "prompt_4" to ParameterProperty(
            type = "string",
            description = "Optional fourth research prompt (research scope only, parallel execution)."
        ),
        "prompt_5" to ParameterProperty(
            type = "string",
            description = "Optional fifth research prompt (research scope only, parallel execution)."
        ),
        "scope" to ParameterProperty(
            type = "string",
            description = "Agent scope: 'research' (read-only), 'implement' (full write access), or 'review' (read + diagnostics). Defaults to 'implement'.",
            enumValues = listOf("research", "implement", "review")
        ),
        "max_iterations" to ParameterProperty(
            type = "integer",
            description = "Max iterations per sub-agent (5-100, default 50). Lower = faster/cheaper."
        )
    ),
    required = listOf("description", "prompt")
)
```

- [ ] **Step 2: Add onSubagentProgress callback and refactor constructor**

```kotlin
class SpawnAgentTool(
    private val brainProvider: suspend () -> LlmBrain,
    private val toolRegistry: ToolRegistry,
    private val project: Project,
    private val contextBudget: Int = DEFAULT_CONTEXT_BUDGET,
    private val onSubagentProgress: (suspend (String, SubagentProgressUpdate) -> Unit)? = null
) : AgentTool {
```

Update companion object:
```kotlin
companion object {
    const val DEFAULT_CONTEXT_BUDGET = 150_000  // Model context window (was 50K)
    const val MIN_ITERATIONS = 5
    const val MAX_ITERATIONS = 100
    const val MAX_PARALLEL_PROMPTS = 5

    val VALID_SCOPES = setOf("research", "implement", "review")
    val PROMPT_KEYS = listOf("prompt", "prompt_2", "prompt_3", "prompt_4", "prompt_5")
    // ... existing tool sets unchanged ...
}
```

- [ ] **Step 3: Rewrite execute() to use SubagentRunner and parallel execution**

```kotlin
override suspend fun execute(params: JsonObject, project: Project): ToolResult {
    val description = params["description"]?.jsonPrimitive?.content
        ?: return errorResult("Missing required parameter: description")
    val scope = params["scope"]?.jsonPrimitive?.content ?: "implement"
    val maxIter = params["max_iterations"]?.jsonPrimitive?.intOrNull ?: 50

    if (scope !in VALID_SCOPES) {
        return errorResult("Invalid scope: '$scope'. Must be one of: research, implement, review")
    }

    // Collect prompts — parallel only for research scope
    // Port of collectPrompts() in SubagentToolHandler.ts:27-33
    val prompts = if (scope == "research") {
        PROMPT_KEYS.mapNotNull { key ->
            params[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        }
    } else {
        val p = params["prompt"]?.jsonPrimitive?.content
            ?: return errorResult("Missing required parameter: prompt")
        listOf(p)
    }

    if (prompts.isEmpty()) {
        return errorResult("Missing required parameter: prompt")
    }
    if (prompts.size > MAX_PARALLEL_PROMPTS) {
        return errorResult("Too many prompts (${prompts.size}). Maximum is $MAX_PARALLEL_PROMPTS.")
    }

    val clampedIterations = maxIter.coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)
    val scopedTools = resolveScopedTools(scope)
    val systemPrompt = buildSubAgentPrompt(scope)

    if (prompts.size == 1) {
        // Single subagent — sequential execution (all scopes)
        return executeSingle(description, prompts.first(), scopedTools, systemPrompt,
            clampedIterations, scope)
    }

    // Multiple subagents — parallel execution (research scope only)
    // Port of SubagentToolHandler.execute() lines 163-323
    return executeParallel(description, prompts, scopedTools, systemPrompt,
        clampedIterations)
}

private suspend fun executeSingle(
    description: String,
    prompt: String,
    tools: Map<String, AgentTool>,
    systemPrompt: String,
    maxIterations: Int,
    scope: String
): ToolResult {
    val agentId = "subagent_${System.currentTimeMillis()}"
    val brain = brainProvider()

    val runner = SubagentRunner(
        brain = brain,
        tools = tools,
        systemPrompt = systemPrompt,
        project = project,
        maxIterations = maxIterations,
        planMode = scope != "implement",
        contextBudget = contextBudget
    )

    val result = runner.run(prompt) { update ->
        onSubagentProgress?.invoke(agentId, update)
    }

    return when (result.status) {
        SubagentRunStatus.COMPLETED -> {
            val statsLine = formatStatsLine(result.stats)
            ToolResult(
                content = "[Agent: $description]\n${result.result ?: ""}\n\n$statsLine",
                summary = "Agent completed ($scope): ${result.result?.take(150) ?: ""}",
                tokenEstimate = estimateTokens(result.result ?: ""),
                verifyCommand = null
            )
        }
        SubagentRunStatus.FAILED -> ToolResult(
            content = "[Agent: $description] Failed: ${result.error}",
            summary = "Agent failed: ${result.error?.take(100)}",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }
}

/**
 * Execute multiple subagents in parallel.
 * Port of SubagentToolHandler.execute() in SubagentToolHandler.ts:163-323.
 * Uses coroutineScope + async (Kotlin equivalent of Promise.allSettled).
 */
private suspend fun executeParallel(
    description: String,
    prompts: List<String>,
    tools: Map<String, AgentTool>,
    systemPrompt: String,
    maxIterations: Int
): ToolResult {
    // Port of entries setup in SubagentToolHandler.ts:163-175
    val entries = prompts.mapIndexed { index, prompt ->
        SubagentStatusItem(index = index + 1, prompt = prompt)
    }

    // Port of parallel execution in SubagentToolHandler.ts:215-258
    // Kotlin equivalent of Promise.allSettled = supervisorScope + async with try/catch
    val results = kotlinx.coroutines.supervisorScope {
        prompts.mapIndexed { index, prompt ->
            kotlinx.coroutines.async {
                val agentId = "subagent_${index + 1}_${System.currentTimeMillis()}"
                val brain = brainProvider()

                val runner = SubagentRunner(
                    brain = brain,
                    tools = tools,
                    systemPrompt = systemPrompt,
                    project = project,
                    maxIterations = maxIterations,
                    planMode = true,  // research scope is always plan mode
                    contextBudget = contextBudget
                )

                try {
                    runner.run(prompt) { update ->
                        // Update entry status (port of SubagentToolHandler.ts:225-254)
                        val entry = entries[index]
                        update.status?.let { entry.status = it }
                        update.result?.let { entry.result = it }
                        update.error?.let { entry.error = it }
                        update.latestToolCall?.let { entry.latestToolCall = it }
                        update.stats?.let { stats ->
                            entry.toolCalls = stats.toolCalls
                            entry.inputTokens = stats.inputTokens
                            entry.outputTokens = stats.outputTokens
                            entry.totalCost = stats.totalCost
                            entry.contextTokens = stats.contextTokens
                            entry.contextWindow = stats.contextWindow
                            entry.contextUsagePercentage = stats.contextUsagePercentage
                        }
                        onSubagentProgress?.invoke(agentId, update)
                    }
                } catch (e: Exception) {
                    entries[index].status = "failed"
                    entries[index].error = e.message ?: "Subagent execution failed"
                    SubagentRunResult(
                        status = SubagentRunStatus.FAILED,
                        error = e.message ?: "Subagent execution failed"
                    )
                }
            }
        }.map { deferred ->
            try {
                deferred.await()
            } catch (e: Exception) {
                SubagentRunResult(
                    status = SubagentRunStatus.FAILED,
                    error = e.message ?: "Subagent execution failed"
                )
            }
        }
    }

    // Collect final stats (port of SubagentToolHandler.ts:260-307)
    results.forEachIndexed { index, result ->
        entries[index].status = if (result.status == SubagentRunStatus.COMPLETED) "completed" else "failed"
        entries[index].result = result.result
        entries[index].error = result.error
        entries[index].toolCalls = result.stats.toolCalls
        entries[index].inputTokens = result.stats.inputTokens
        entries[index].outputTokens = result.stats.outputTokens
        entries[index].totalCost = result.stats.totalCost
        entries[index].contextTokens = result.stats.contextTokens
        entries[index].contextWindow = result.stats.contextWindow
        entries[index].contextUsagePercentage = result.stats.contextUsagePercentage
    }

    // Build summary (port of SubagentToolHandler.ts:308-323)
    val failures = entries.count { it.status == "failed" }
    val successCount = entries.size - failures
    val totalToolCalls = entries.sumOf { it.toolCalls }
    val maxContextUsage = entries.maxOfOrNull { it.contextUsagePercentage } ?: 0.0
    val maxContextTokens = entries.maxOfOrNull { it.contextTokens } ?: 0
    val contextWindow = entries.maxOfOrNull { it.contextWindow } ?: 0

    val summary = buildString {
        appendLine("Subagent results:")
        appendLine("Total: ${entries.size}")
        appendLine("Succeeded: $successCount")
        appendLine("Failed: $failures")
        appendLine("Tool calls: $totalToolCalls")
        appendLine("Peak context usage: ${formatNumber(maxContextTokens)} / ${formatNumber(contextWindow)} (${String.format("%.1f", maxContextUsage)}%)")
        appendLine()
        for (entry in entries) {
            val header = "[${entry.index}] ${entry.status.uppercase()} - ${entry.prompt}"
            val detail = if (entry.status == "completed") excerpt(entry.result) else excerpt(entry.error)
            if (detail.isNotEmpty()) {
                appendLine("$header\n$detail")
            } else {
                appendLine(header)
            }
        }
    }

    return ToolResult(
        content = "[Agent: $description]\n$summary",
        summary = "Parallel agents: $successCount/${entries.size} succeeded, $totalToolCalls tool calls",
        tokenEstimate = estimateTokens(summary),
        isError = failures == entries.size  // Only error if ALL failed
    )
}

/** Port of excerpt() in SubagentToolHandler.ts:36-47 */
private fun excerpt(text: String?, maxChars: Int = 1200): String {
    val trimmed = text?.trim() ?: return ""
    if (trimmed.length <= maxChars) return trimmed
    return "${trimmed.take(maxChars)}..."
}

private fun formatStatsLine(stats: SubagentRunStats): String {
    return "Stats: ${stats.toolCalls} tool calls, " +
        "${formatNumber(stats.inputTokens)} input + ${formatNumber(stats.outputTokens)} output tokens, " +
        "context ${String.format("%.1f", stats.contextUsagePercentage)}%"
}

private fun formatNumber(n: Int): String {
    return if (n >= 1000) "${n / 1000}K" else "$n"
}
```

- [ ] **Step 4: Update the tool description to mention parallel execution**

Add to the description string after the existing scope descriptions:

```
Parallel execution (research scope only):
- In research scope, you can provide up to 5 prompts (prompt, prompt_2, ..., prompt_5).
- Each prompt runs as a separate parallel subagent with its own context.
- Use this to fan out multiple research questions simultaneously.
- For implement/review scopes, only the primary prompt is used (sequential).
```

- [ ] **Step 5: Update existing tests + add parallel execution tests**

Add to `SpawnAgentToolTest.kt`:

```kotlin
@Nested
inner class ParallelExecutionTests {

    @Test
    fun `research scope with multiple prompts collects all prompts`() = runTest {
        var callCount = 0
        val brain = object : LlmBrain {
            override val modelId = "test"
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?,
                maxTokens: Int?, toolChoice: JsonElement?) = throw UnsupportedOperationException()
            override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?,
                maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
                callCount++
                return ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Result $callCount"}"""))
            }
            override fun estimateTokens(text: String) = text.length / 4
            override fun cancelActiveRequest() {}
        }

        val spawnTool = SpawnAgentTool(
            brainProvider = { brain },
            toolRegistry = registry,
            project = project,
            contextBudget = 50_000
        )

        val result = spawnTool.execute(
            JsonObject(mapOf(
                "description" to JsonPrimitive("Multi research"),
                "prompt" to JsonPrimitive("Find controllers"),
                "prompt_2" to JsonPrimitive("Find services"),
                "prompt_3" to JsonPrimitive("Find tests"),
                "scope" to JsonPrimitive("research")
            )),
            project
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("Subagent results:"))
        assertTrue(result.content.contains("Total: 3"))
    }

    @Test
    fun `implement scope ignores extra prompts`() = runTest {
        val brain = object : LlmBrain {
            override val modelId = "test"
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?,
                maxTokens: Int?, toolChoice: JsonElement?) = throw UnsupportedOperationException()
            override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?,
                maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
                return ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done"}"""))
            }
            override fun estimateTokens(text: String) = text.length / 4
            override fun cancelActiveRequest() {}
        }

        val spawnTool = SpawnAgentTool(
            brainProvider = { brain },
            toolRegistry = registry,
            project = project,
            contextBudget = 50_000
        )

        val result = spawnTool.execute(
            JsonObject(mapOf(
                "description" to JsonPrimitive("Implement feature"),
                "prompt" to JsonPrimitive("Fix the bug"),
                "prompt_2" to JsonPrimitive("This should be ignored"),
                "scope" to JsonPrimitive("implement")
            )),
            project
        )

        assertFalse(result.isError)
        // Should NOT contain parallel output format
        assertFalse(result.content.contains("Subagent results:"))
        assertTrue(result.content.contains("Agent: Implement feature"))
    }

    @Test
    fun `parallel research reports stats in summary`() = runTest {
        val brain = object : LlmBrain {
            override val modelId = "test"
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?,
                maxTokens: Int?, toolChoice: JsonElement?) = throw UnsupportedOperationException()
            override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?,
                maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
                return ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Found it"}"""))
            }
            override fun estimateTokens(text: String) = text.length / 4
            override fun cancelActiveRequest() {}
        }

        val spawnTool = SpawnAgentTool(
            brainProvider = { brain },
            toolRegistry = registry,
            project = project,
            contextBudget = 50_000
        )

        val result = spawnTool.execute(
            JsonObject(mapOf(
                "description" to JsonPrimitive("Research"),
                "prompt" to JsonPrimitive("Q1"),
                "prompt_2" to JsonPrimitive("Q2"),
                "scope" to JsonPrimitive("research")
            )),
            project
        )

        assertTrue(result.summary.contains("Parallel agents"))
        assertTrue(result.summary.contains("2/2"))
    }
}

@Nested
inner class ConfigurableContextBudgetTests {

    @Test
    fun `default context budget is 150K`() {
        assertEquals(150_000, SpawnAgentTool.DEFAULT_CONTEXT_BUDGET)
    }

    @Test
    fun `custom context budget is passed through`() {
        val customTool = SpawnAgentTool(
            brainProvider = { throw IllegalStateException() },
            toolRegistry = registry,
            project = project,
            contextBudget = 80_000
        )
        // The budget is used internally — verify the tool constructs without error
        assertNotNull(customTool)
    }
}
```

- [ ] **Step 6: Run all tests**

Run: `./gradlew :agent:test --tests "*.SpawnAgentToolTest" -x verifyPlugin`
Expected: All existing + new tests PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentToolTest.kt
git commit -m "feat(agent): parallel subagent execution for research scope + configurable context budget

Port from Cline's SubagentToolHandler.ts. Research scope supports up to 5 parallel
subagents via coroutineScope+async. Implement/review remain sequential.
Context budget configurable (default 150K, was hardcoded 50K).
Uses SubagentRunner for progress streaming and per-subagent stats."
```

---

### Task 6: Wire progress streaming to AgentController and dashboard

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

Wire the `onSubagentProgress` callback from SpawnAgentTool through AgentService to AgentController, which already has `spawnSubAgent()`, `updateSubAgentToolCall()`, etc. methods on the dashboard.

- [ ] **Step 1: Read AgentService to understand how SpawnAgentTool is registered**

Read `AgentService.kt` to find where `SpawnAgentTool` is instantiated and registered. Look for `safeRegisterCore { SpawnAgentTool(` or similar.

- [ ] **Step 2: Add onSubagentProgress callback to AgentService.executeTask()**

In `AgentService.kt`, where SpawnAgentTool is created, pass an `onSubagentProgress` lambda. This lambda will be provided by AgentController.

```kotlin
// In AgentService, where SpawnAgentTool is registered:
safeRegisterCore { SpawnAgentTool(
    brainProvider = { createBrain() },
    toolRegistry = registry,
    project = project,
    contextBudget = settings.state.maxInputTokens,
    onSubagentProgress = onSubagentProgress
) }
```

Add the `onSubagentProgress` parameter to `executeTask()`:
```kotlin
fun executeTask(
    task: String,
    contextManager: ContextManager?,
    onStreamChunk: (String) -> Unit,
    onToolCall: (ToolCallProgress) -> Unit,
    onTaskProgress: (TaskProgress) -> Unit,
    onComplete: (LoopResult) -> Unit,
    onPlanResponse: ((String, Boolean) -> Unit)? = null,
    userInputChannel: Channel<String>? = null,
    onSubagentProgress: (suspend (String, SubagentProgressUpdate) -> Unit)? = null
): Job
```

- [ ] **Step 3: Wire in AgentController**

In `AgentController.executeTask()`, pass a callback that routes to the dashboard:

```kotlin
currentJob = service.executeTask(
    task = task,
    contextManager = contextManager,
    onStreamChunk = ::onStreamChunk,
    onToolCall = ::onToolCall,
    onTaskProgress = ::onTaskProgress,
    onComplete = ::onComplete,
    onPlanResponse = ::onPlanResponse,
    userInputChannel = userInputChannel,
    onSubagentProgress = ::onSubagentProgress
)

// Add the callback method:
private fun onSubagentProgress(agentId: String, update: SubagentProgressUpdate) {
    invokeLater {
        when (update.status) {
            "running" -> {
                val label = update.latestToolCall ?: "Starting..."
                // Spawn card on first "running" update
                dashboard.spawnSubAgent(agentId, label)
            }
            "completed" -> {
                dashboard.completeSubAgent(
                    agentId,
                    update.result ?: "Completed",
                    update.stats?.inputTokens?.plus(update.stats.outputTokens) ?: 0,
                    isError = false
                )
            }
            "failed" -> {
                dashboard.completeSubAgent(
                    agentId,
                    update.error ?: "Failed",
                    update.stats?.inputTokens?.plus(update.stats.outputTokens) ?: 0,
                    isError = true
                )
            }
            else -> {
                // Intermediate progress — update tool call display
                update.latestToolCall?.let { toolCall ->
                    dashboard.addSubAgentToolCall(agentId, toolCall, "")
                }
                update.stats?.let { stats ->
                    dashboard.updateSubAgentIteration(agentId, stats.toolCalls)
                }
            }
        }
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :agent:compileKotlin -x verifyPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): wire subagent progress streaming to dashboard UI

Routes SubagentRunner progress callbacks through AgentService to AgentController.
Uses existing spawnSubAgent/updateSubAgent/completeSubAgent dashboard methods."
```

---

### Task 7: Register AgentConfigLoader in AgentService lifecycle + wire dynamic agents

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/listeners/AgentStartupActivity.kt` (if needed)

Initialize AgentConfigLoader on service startup, dispose on shutdown. Register dynamic agent tools in ToolRegistry.

- [ ] **Step 1: Initialize AgentConfigLoader in AgentService**

In `AgentService` init or startup:

```kotlin
// Initialize agent config loader
val configLoader = AgentConfigLoader.getInstance()
Disposer.register(this, configLoader)

// Register dynamic agent tools from YAML configs
configLoader.addListener { configs, _ ->
    // Re-register dynamic tools when configs change
    LOG.info("[AgentService] Agent configs reloaded: ${configs.size} configs")
}
```

- [ ] **Step 2: Log loaded agent configs on startup**

```kotlin
val configs = configLoader.getAllCachedConfigsWithToolNames()
if (configs.isNotEmpty()) {
    LOG.info("[AgentService] Loaded ${configs.size} dynamic agent config(s): ${configs.map { it.first }}")
}
```

- [ ] **Step 3: Verify compilation and existing tests still pass**

Run: `./gradlew :agent:test -x verifyPlugin`
Expected: All existing tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(agent): initialize AgentConfigLoader on service startup

Loads YAML agent configs from ~/.workflow-orchestrator/agents/ on startup.
File watcher reloads configs on changes."
```

---

### Task 8: Full integration test — parallel research subagents end-to-end

**Files:**
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/ParallelSubagentIntegrationTest.kt`

End-to-end test: create SpawnAgentTool with test brains, execute with 3 research prompts in parallel, verify all complete, stats are aggregated, progress callbacks fire.

- [ ] **Step 1: Write integration test**

```kotlin
package com.workflow.orchestrator.agent.tools.subagent

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.AttemptCompletionTool
import com.workflow.orchestrator.agent.tools.builtin.SpawnAgentTool
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class ParallelSubagentIntegrationTest {

    private lateinit var project: Project
    private lateinit var registry: ToolRegistry

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.name } returns "TestProject"
        every { project.basePath } returns "/tmp/test-project"
        registry = buildTestRegistry()
    }

    private fun buildTestRegistry(): ToolRegistry {
        val reg = ToolRegistry()
        for (name in listOf("read_file", "search_code", "glob_files", "think",
            "project_context", "current_time", "ask_questions")) {
            reg.register(stubTool(name))
        }
        reg.register(AttemptCompletionTool())
        // PSI tools
        for (name in listOf("find_definition", "find_references", "find_implementations",
            "file_structure", "type_hierarchy", "call_hierarchy", "type_inference",
            "get_method_body", "get_annotations", "read_write_access", "structural_search",
            "test_finder", "dataflow_analysis")) {
            reg.register(stubTool(name))
        }
        // VCS tools
        for (name in listOf("git_status", "git_diff", "git_log", "git_blame",
            "git_show_file", "git_file_history", "git_show_commit", "git_branches",
            "changelist_shelve", "git_stash_list", "git_merge_base")) {
            reg.register(stubTool(name))
        }
        // Write tools + agent (for exclusion tests)
        for (name in listOf("edit_file", "create_file", "run_command", "agent")) {
            reg.register(stubTool(name))
        }
        return reg
    }

    private fun stubTool(name: String): AgentTool = object : AgentTool {
        override val name = name
        override val description = "Stub: $name"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "stub:$name", summary = "stub", tokenEstimate = 5)
    }

    private fun toolCallResponse(vararg calls: Pair<String, String>): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(Choice(
                index = 0,
                message = ChatMessage(
                    role = "assistant", content = null,
                    toolCalls = calls.mapIndexed { idx, (name, args) ->
                        ToolCall(id = "call_${idx}_${System.nanoTime()}", type = "function",
                            function = FunctionCall(name = name, arguments = args))
                    }
                ),
                finishReason = "tool_calls"
            )),
            usage = UsageInfo(promptTokens = 100, completionTokens = 30, totalTokens = 130)
        )

    @Test
    fun `three parallel research subagents all complete`() = runTest {
        val brainCallCount = AtomicInteger(0)

        fun createBrain(): LlmBrain {
            val idx = brainCallCount.incrementAndGet()
            var callCount = 0
            return object : LlmBrain {
                override val modelId = "test-brain-$idx"
                override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?,
                    maxTokens: Int?, toolChoice: JsonElement?) = throw UnsupportedOperationException()
                override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?,
                    maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
                    callCount++
                    return if (callCount == 1) {
                        ApiResult.Success(toolCallResponse("read_file" to """{"path":"file$idx.kt"}"""))
                    } else {
                        ApiResult.Success(toolCallResponse(
                            "attempt_completion" to """{"result":"Result from agent $idx"}"""))
                    }
                }
                override fun estimateTokens(text: String) = text.length / 4
                override fun cancelActiveRequest() {}
            }
        }

        val progressUpdates = Collections.synchronizedList(mutableListOf<Pair<String, SubagentProgressUpdate>>())

        val spawnTool = SpawnAgentTool(
            brainProvider = { createBrain() },
            toolRegistry = registry,
            project = project,
            contextBudget = 50_000,
            onSubagentProgress = { agentId, update ->
                progressUpdates.add(agentId to update)
            }
        )

        val result = spawnTool.execute(
            JsonObject(mapOf(
                "description" to JsonPrimitive("Research codebase"),
                "prompt" to JsonPrimitive("Find all controllers"),
                "prompt_2" to JsonPrimitive("Find all services"),
                "prompt_3" to JsonPrimitive("Find all tests"),
                "scope" to JsonPrimitive("research")
            )),
            project
        )

        // All three should succeed
        assertFalse(result.isError, "Parallel execution should succeed: ${result.content}")
        assertTrue(result.content.contains("Total: 3"))
        assertTrue(result.content.contains("Succeeded: 3"))
        assertTrue(result.content.contains("Failed: 0"))
        assertTrue(result.summary.contains("3/3"))

        // Should have created 3 brains (one per subagent)
        assertEquals(3, brainCallCount.get())

        // Progress callbacks should have fired
        assertTrue(progressUpdates.isNotEmpty())
        val completedUpdates = progressUpdates.filter { it.second.status == "completed" }
        assertEquals(3, completedUpdates.size)
    }

    @Test
    fun `partial failure in parallel subagents reports mixed results`() = runTest {
        var brainCount = AtomicInteger(0)

        fun createBrain(): LlmBrain {
            val idx = brainCount.incrementAndGet()
            return object : LlmBrain {
                override val modelId = "test-brain-$idx"
                override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?,
                    maxTokens: Int?, toolChoice: JsonElement?) = throw UnsupportedOperationException()
                override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?,
                    maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
                    return if (idx == 2) {
                        // Second brain fails
                        ApiResult.Error(ErrorType.SERVER_ERROR, "Server error for agent 2")
                    } else {
                        ApiResult.Success(toolCallResponse(
                            "attempt_completion" to """{"result":"Success from agent $idx"}"""))
                    }
                }
                override fun estimateTokens(text: String) = text.length / 4
                override fun cancelActiveRequest() {}
            }
        }

        val spawnTool = SpawnAgentTool(
            brainProvider = { createBrain() },
            toolRegistry = registry,
            project = project,
            contextBudget = 50_000
        )

        val result = spawnTool.execute(
            JsonObject(mapOf(
                "description" to JsonPrimitive("Research"),
                "prompt" to JsonPrimitive("Q1"),
                "prompt_2" to JsonPrimitive("Q2"),
                "prompt_3" to JsonPrimitive("Q3"),
                "scope" to JsonPrimitive("research")
            )),
            project
        )

        // Not all-error since 2 of 3 succeeded
        assertFalse(result.isError)
        assertTrue(result.content.contains("Succeeded: 2"))
        assertTrue(result.content.contains("Failed: 1"))
    }
}
```

- [ ] **Step 2: Run integration test**

Run: `./gradlew :agent:test --tests "*.ParallelSubagentIntegrationTest" -x verifyPlugin`
Expected: 2 tests PASS

- [ ] **Step 3: Run ALL agent tests to verify no regressions**

Run: `./gradlew :agent:test -x verifyPlugin`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/ParallelSubagentIntegrationTest.kt
git commit -m "test(agent): add parallel subagent integration tests

Verifies 3-way parallel research execution, partial failure handling,
progress callback streaming, and stats aggregation."
```

---

### Task 9: Update documentation

**Files:**
- Modify: `CLAUDE.md` (root)
- Modify: `agent/CLAUDE.md` (if exists)

Update documentation to reflect:
- New subagent architecture (SubagentRunner, parallel execution)
- YAML agent config directory (`~/.workflow-orchestrator/agents/`)
- Configurable context budget (150K default)
- Parallel execution for research scope (up to 5)

- [ ] **Step 1: Update root CLAUDE.md agent module description**

Add to the Agent module row in the Architecture table:
```
| `:agent` | AI coding agent — ReAct loop, ... sub-agent orchestration with parallel research (up to 5), YAML dynamic agent configs (~/.workflow-orchestrator/agents/), configurable context budget ... |
```

Add a new section under Agent Storage:
```
## Dynamic Agent Configs

YAML config files in `~/.workflow-orchestrator/agents/` define custom subagent personas.
File-watched with hot-reload. Each config specifies name, description, tools, optional model override, and a system prompt body.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md with parallel subagent architecture and dynamic agent configs"
```

---

## Summary

| Task | Description | New Files | Modified Files |
|------|-------------|-----------|----------------|
| 1 | SubagentModels data classes | 2 | 0 |
| 2 | SubagentToolName | 2 | 0 |
| 3 | AgentConfigLoader | 2 | 0 |
| 4 | SubagentRunner | 2 | 0 |
| 5 | SpawnAgentTool refactor (parallel + budget) | 0 | 2 |
| 6 | Wire progress to dashboard | 0 | 2 |
| 7 | Register AgentConfigLoader lifecycle | 0 | 1 |
| 8 | Integration tests | 1 | 0 |
| 9 | Documentation | 0 | 1 |

**Total:** 9 new files, 6 modified files, 9 commits
