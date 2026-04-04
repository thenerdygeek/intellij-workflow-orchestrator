package com.workflow.orchestrator.agent.hooks

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Configuration for a single hook.
 *
 * Cline stores hooks as executable scripts in directory trees:
 * - Global: ~/Documents/Cline/Hooks/{HookType}/{script}
 * - Workspace: .clinerules/hooks/{HookType}/{script}
 *
 * We simplify this to JSON config with shell commands, matching the
 * task spec's .agent-hooks.json approach while staying faithful to
 * Cline's execution model (command + timeout + type).
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/hooks/utils.ts">Cline resolveHooksDirectory</a>
 */
data class HookConfig(
    val type: HookType,
    val command: String,
    val timeout: Long = DEFAULT_TIMEOUT_MS
) {
    companion object {
        /** Default timeout matching Cline's HOOK_EXECUTION_TIMEOUT_MS. */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

/**
 * JSON-serializable format for .agent-hooks.json config file.
 *
 * Cline stores hooks as filesystem directories with executable scripts.
 * We store them as a JSON config file in the project root, which is
 * more portable and easier to version control.
 *
 * File location: {projectRoot}/.agent-hooks.json
 *
 * Example:
 * ```json
 * {
 *   "hooks": [
 *     {"type": "PreToolUse", "command": "echo $TOOL_NAME >> /tmp/agent-tools.log"},
 *     {"type": "TaskStart", "command": "notify-send 'Agent started'", "timeout": 5000}
 *   ]
 * }
 * ```
 */
@Serializable
data class HookConfigFile(
    val hooks: List<HookConfigEntry> = emptyList()
)

@Serializable
data class HookConfigEntry(
    val type: String,
    val command: String,
    val timeout: Long = HookConfig.DEFAULT_TIMEOUT_MS
)

/**
 * Loads hook configurations from the project's .agent-hooks.json file.
 *
 * Cline uses getAllHooksDirs() to scan global + workspace directories.
 * We use a single JSON file per project for simplicity.
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/storage/disk.ts">Cline getAllHooksDirs</a>
 */
object HookConfigLoader {
    private val LOG = Logger.getInstance(HookConfigLoader::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val CONFIG_FILE_NAME = ".agent-hooks.json"

    /**
     * Load hooks from the project's .agent-hooks.json file.
     *
     * @param projectPath the project root directory path
     * @return list of parsed HookConfig, or empty list if file doesn't exist or is invalid
     */
    fun load(projectPath: String): List<HookConfig> {
        val configFile = File(projectPath, CONFIG_FILE_NAME)
        if (!configFile.exists()) return emptyList()

        return try {
            val content = configFile.readText(Charsets.UTF_8)
            val configFile2 = json.decodeFromString<HookConfigFile>(content)

            configFile2.hooks.mapNotNull { entry ->
                val hookType = HookType.fromString(entry.type)
                if (hookType == null) {
                    LOG.warn("[HookConfigLoader] Unknown hook type '${entry.type}', skipping")
                    null
                } else if (entry.command.isBlank()) {
                    LOG.warn("[HookConfigLoader] Empty command for hook type '${entry.type}', skipping")
                    null
                } else {
                    HookConfig(
                        type = hookType,
                        command = entry.command,
                        timeout = entry.timeout.coerceIn(1000, 120_000) // 1s-120s bounds
                    )
                }
            }
        } catch (e: Exception) {
            LOG.warn("[HookConfigLoader] Failed to load $CONFIG_FILE_NAME: ${e.message}")
            emptyList()
        }
    }
}
