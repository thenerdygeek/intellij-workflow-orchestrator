// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Configuration for a named subagent, parsed from a YAML frontmatter markdown file.
 *
 * Ported from Cline's AgentConfigLoader.ts — adapted from chokidar/zod to
 * java.nio.file.WatchService with manual YAML-frontmatter parsing.
 *
 * Config files live in `~/.workflow-orchestrator/agents/` as `.yaml` or `.yml` files
 * with YAML frontmatter delimited by `---` and a markdown body for the system prompt.
 */
data class AgentConfig(
    val name: String,
    val description: String,
    val tools: List<String>,
    val skills: List<String>?,
    val modelId: String?,
    val systemPrompt: String,
)

/**
 * Listener notified when agent configs are added, removed, or modified.
 */
fun interface AgentConfigChangeListener {
    fun onConfigsChanged(configs: List<AgentConfig>)
}

/**
 * Loads, caches, and watches YAML agent configuration files.
 *
 * Singleton lifecycle tied to [Disposable]. File watching uses [WatchService]
 * on a daemon thread with 300 ms debounce to coalesce rapid filesystem events.
 *
 * Thread-safe: caches use [ConcurrentHashMap], listeners use [CopyOnWriteArrayList].
 */
class AgentConfigLoader private constructor() : Disposable {

    private val log = Logger.getInstance(AgentConfigLoader::class.java)

    /** name (lowercase) -> AgentConfig */
    private val configCache = ConcurrentHashMap<String, AgentConfig>()

    /** tool name -> agent name (original casing) */
    private val toolNameToAgentName = ConcurrentHashMap<String, String>()

    private val listeners = CopyOnWriteArrayList<AgentConfigChangeListener>()

    private var watchThread: Thread? = null
    private var watchService: WatchService? = null
    private val disposed = AtomicBoolean(false)

    var configDir: Path = DEFAULT_CONFIG_DIR
        private set

    // ----- Public API -----

    /**
     * Load all `.yaml` / `.yml` files from [configDir], populate caches, and start
     * the file watcher. Safe to call multiple times — subsequent calls reload from disk.
     */
    fun loadFromDisk(directory: Path = configDir) {
        configDir = directory
        ensureDirectoryExists(directory)
        val configs = scanDirectory(directory)
        rebuildCaches(configs)
        startWatching(directory)
    }

    fun getCachedConfig(name: String): AgentConfig? =
        configCache[name.lowercase()]

    fun getAllCachedConfigs(): List<AgentConfig> =
        configCache.values.toList()

    /**
     * Returns a map of generated tool name -> [AgentConfig] for all cached configs.
     */
    fun getAllCachedConfigsWithToolNames(): Map<String, AgentConfig> {
        val result = mutableMapOf<String, AgentConfig>()
        for ((toolName, agentName) in toolNameToAgentName) {
            configCache[agentName.lowercase()]?.let { result[toolName] = it }
        }
        return result
    }

    /**
     * Given a generated tool name (e.g. `use_subagent_my_helper`), resolve the
     * original agent name. Returns `null` if not a known dynamic tool.
     */
    fun resolveSubagentNameForTool(toolName: String): String? =
        toolNameToAgentName[toolName]

    /**
     * Returns `true` when [toolName] belongs to a currently-loaded dynamic subagent.
     */
    fun isDynamicSubagentTool(toolName: String): Boolean =
        toolNameToAgentName.containsKey(toolName)

    fun addChangeListener(listener: AgentConfigChangeListener) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: AgentConfigChangeListener) {
        listeners.remove(listener)
    }

    // ----- Dispose -----

    override fun dispose() {
        disposed.set(true)
        stopWatching()
        configCache.clear()
        toolNameToAgentName.clear()
        listeners.clear()
    }

    // ----- Parsing -----

    /**
     * Parse a YAML frontmatter markdown file into an [AgentConfig].
     *
     * Expected format:
     * ```
     * ---
     * name: "Agent Name"
     * description: "What this agent does"
     * tools: "tool_a, tool_b"
     * skills: "skill-1, skill-2"       # optional
     * modelId: "provider/model"        # optional
     * ---
     * System prompt body in markdown.
     * ```
     *
     * @throws IllegalArgumentException if required fields are missing or frontmatter is absent.
     */
    fun parseAgentConfigFromYaml(content: String): AgentConfig {
        val trimmed = content.trimStart()
        require(trimmed.startsWith("---")) { "Missing YAML frontmatter delimiter (---)" }

        val afterFirstDelimiter = trimmed.substring(3)
        val endIndex = afterFirstDelimiter.indexOf("\n---")
        require(endIndex >= 0) { "Missing closing YAML frontmatter delimiter (---)" }

        val frontmatterBlock = afterFirstDelimiter.substring(0, endIndex)
        val bodyStart = endIndex + 4 // skip "\n---"
        val body = afterFirstDelimiter.substring(bodyStart).trim()

        val fields = parseFrontmatterFields(frontmatterBlock)

        val name = fields["name"]
            ?: throw IllegalArgumentException("Missing required field: name")
        val description = fields["description"]
            ?: throw IllegalArgumentException("Missing required field: description")
        val toolsRaw = fields["tools"]
            ?: throw IllegalArgumentException("Missing required field: tools")
        require(body.isNotEmpty()) { "Missing system prompt body after frontmatter" }

        val tools = splitCsvField(toolsRaw)
        val skills = fields["skills"]?.let { splitCsvField(it) }
        val modelId = fields["modelId"]?.takeIf { it.isNotBlank() }

        return AgentConfig(
            name = name,
            description = description,
            tools = tools,
            skills = skills,
            modelId = modelId,
            systemPrompt = body,
        )
    }

    // ----- Internal -----

    /**
     * Rebuild dynamic tool name mappings with collision handling.
     * Port of rebuildDynamicToolMappings() in AgentConfigLoader.ts:330-354.
     * If two agents produce the same tool name, suffix incrementing (_2, _3, etc.) resolves it.
     */
    internal fun rebuildDynamicToolMappings() {
        val sorted = configCache.entries.sortedBy { it.key }
        val usedToolNames = mutableSetOf<String>()
        val newToolNameToAgent = mutableMapOf<String, String>()

        for ((_, config) in sorted) {
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
            newToolNameToAgent[candidate] = config.name
        }

        toolNameToAgentName.clear()
        toolNameToAgentName.putAll(newToolNameToAgent)
    }

    private fun rebuildCaches(configs: List<AgentConfig>) {
        configCache.clear()
        for (config in configs) {
            configCache[config.name.lowercase()] = config
        }
        rebuildDynamicToolMappings()
    }

    private fun scanDirectory(directory: Path): List<AgentConfig> {
        if (!Files.isDirectory(directory)) return emptyList()
        val configs = mutableListOf<AgentConfig>()
        Files.newDirectoryStream(directory) { entry ->
            val name = entry.fileName.toString().lowercase()
            name.endsWith(".yaml") || name.endsWith(".yml")
        }.use { stream ->
            for (path in stream) {
                try {
                    val content = Files.readString(path)
                    configs.add(parseAgentConfigFromYaml(content))
                } catch (e: Exception) {
                    log.warn("Failed to parse agent config ${path.fileName}: ${e.message}")
                }
            }
        }
        return configs
    }

    private fun ensureDirectoryExists(directory: Path) {
        if (!Files.exists(directory)) {
            try {
                Files.createDirectories(directory)
            } catch (e: Exception) {
                log.warn("Could not create agent config directory $directory: ${e.message}")
            }
        }
    }

    // ----- File Watching -----

    private fun startWatching(directory: Path) {
        stopWatching()
        if (disposed.get()) return
        try {
            val ws = directory.fileSystem.newWatchService()
            watchService = ws
            directory.register(
                ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE,
            )
            val thread = Thread({
                watchLoop(ws, directory)
            }, "AgentConfigLoader-Watcher").apply {
                isDaemon = true
            }
            watchThread = thread
            thread.start()
        } catch (e: Exception) {
            log.warn("Failed to start agent config file watcher: ${e.message}")
        }
    }

    private fun watchLoop(ws: WatchService, directory: Path) {
        try {
            while (!disposed.get()) {
                val key = ws.take() ?: break
                // 300 ms debounce — coalesce rapid filesystem events
                Thread.sleep(DEBOUNCE_MS)
                // Drain all pending events
                key.pollEvents()
                key.reset()

                if (disposed.get()) break

                val configs = scanDirectory(directory)
                rebuildCaches(configs)
                notifyListeners()
            }
        } catch (_: InterruptedException) {
            // Expected on dispose
        } catch (_: ClosedWatchServiceException) {
            // Expected on dispose
        } catch (e: Exception) {
            if (!disposed.get()) {
                log.warn("Agent config watcher error: ${e.message}")
            }
        }
    }

    private fun stopWatching() {
        try {
            watchService?.close()
        } catch (_: Exception) {
        }
        watchService = null
        watchThread?.interrupt()
        watchThread = null
    }

    private fun notifyListeners() {
        val snapshot = getAllCachedConfigs()
        for (listener in listeners) {
            try {
                listener.onConfigsChanged(snapshot)
            } catch (e: Exception) {
                log.warn("Agent config change listener error: ${e.message}")
            }
        }
    }

    // ----- Frontmatter field parsing -----

    private fun parseFrontmatterFields(block: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        for (line in block.lines()) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) continue
            val colonIndex = trimmedLine.indexOf(':')
            if (colonIndex < 0) continue
            val key = trimmedLine.substring(0, colonIndex).trim()
            val value = trimmedLine.substring(colonIndex + 1).trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
            if (key.isNotEmpty() && value.isNotEmpty()) {
                fields[key] = value
            }
        }
        return fields
    }

    private fun splitCsvField(raw: String): List<String> =
        raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // ----- Singleton -----

    companion object {
        private const val DEBOUNCE_MS = 300L

        @JvmStatic
        val DEFAULT_CONFIG_DIR: Path = Paths.get(
            System.getProperty("user.home"),
            ".workflow-orchestrator",
            "agents",
        )

        @Volatile
        private var instance: AgentConfigLoader? = null

        @JvmStatic
        fun getInstance(): AgentConfigLoader {
            return instance ?: synchronized(this) {
                instance ?: AgentConfigLoader().also { instance = it }
            }
        }

        /**
         * Reset the singleton for test isolation. Disposes the existing instance.
         */
        @JvmStatic
        fun resetForTests() {
            synchronized(this) {
                instance?.dispose()
                instance = null
            }
        }
    }
}
