package com.workflow.orchestrator.agent.hooks

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

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

    /** Notification group ID registered in plugin.xml. */
    const val SECURITY_NOTIFICATION_GROUP = "Workflow Orchestrator Security"

    /**
     * Load hooks from the project's .agent-hooks.json file.
     *
     * When a project parameter is supplied the SHA-256 of the file content is
     * checked against [HookTrustStore]:
     * - TRUSTED  → parse and return hooks normally.
     * - REJECTED → return empty list silently (user already declined).
     * - UNKNOWN  → return empty list (degraded mode) and fire a non-blocking
     *              notification offering Trust / Reject / View actions.
     *
     * File changes invalidate trust automatically because the SHA changes.
     *
     * @param projectPath the project root directory path
     * @param project     optional IntelliJ Project for trust-gate enforcement.
     *                    When null the gate is skipped (legacy / test callers).
     * @return list of parsed HookConfig, or empty list if file doesn't exist,
     *         is invalid, or has not been trusted.
     */
    fun load(projectPath: String, project: Project? = null): List<HookConfig> {
        val configFile = File(projectPath, CONFIG_FILE_NAME)
        if (!configFile.exists()) return emptyList()

        return try {
            val bytes = configFile.readBytes()
            val sha256 = computeSha256(bytes)

            // Trust gate — only enforced when a Project is available.
            if (project != null) {
                val trustStore = HookTrustStore.getInstance(project)
                when (trustStore.checkTrust(sha256)) {
                    HookTrustStore.TrustState.TRUSTED -> {
                        // Fall through to normal parsing below.
                    }
                    HookTrustStore.TrustState.REJECTED -> {
                        LOG.info("[HookConfigLoader] $CONFIG_FILE_NAME skipped — SHA $sha256 previously rejected")
                        return emptyList()
                    }
                    HookTrustStore.TrustState.UNKNOWN -> {
                        // Degraded mode: no hooks until the user decides.
                        showTrustNotification(project, trustStore, sha256, configFile)
                        return emptyList()
                    }
                }
            }

            val content = bytes.toString(Charsets.UTF_8)
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

    /**
     * Compute the SHA-256 hex digest of [bytes].
     * Internal but visible for testing.
     */
    internal fun computeSha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Fire the non-blocking trust notification.
     * Extracted as a named function so tests can verify it is called
     * (or mock it) without touching the actual notification subsystem.
     */
    internal fun showTrustNotification(
        project: Project,
        trustStore: HookTrustStore,
        sha256: String,
        configFile: File
    ) {
        val groupManager = try {
            NotificationGroupManager.getInstance()
        } catch (_: Exception) {
            // Not available in unit-test environments — degrade gracefully.
            LOG.info("[HookConfigLoader] NotificationGroupManager unavailable; skipping trust notification")
            return
        }

        val group = groupManager.getNotificationGroup(SECURITY_NOTIFICATION_GROUP) ?: run {
            LOG.warn("[HookConfigLoader] Notification group '$SECURITY_NOTIFICATION_GROUP' not registered")
            return
        }

        val notification = group.createNotification(
            title = "Project hooks not trusted",
            content = "This repo defines <b>.agent-hooks.json</b> that runs shell commands. " +
                "Hooks are disabled until you trust this file.",
            type = NotificationType.WARNING
        )

        notification.addAction(NotificationAction.createSimple("Trust hooks for this file") {
            trustStore.setTrusted(sha256)
            notification.expire()
            val toastGroup = runCatching {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(SECURITY_NOTIFICATION_GROUP)
            }.getOrNull()
            toastGroup?.createNotification(
                "Hooks trusted",
                "Agent hooks will load on the next task.",
                NotificationType.INFORMATION
            )?.notify(project)
        })

        notification.addAction(NotificationAction.createSimple("Reject") {
            trustStore.setRejected(sha256)
            notification.expire()
        })

        notification.addAction(NotificationAction.createSimple("View hooks file") {
            val vf = LocalFileSystem.getInstance().findFileByIoFile(configFile)
            if (vf != null) {
                FileEditorManager.getInstance(project).openFile(vf, true)
            }
            notification.expire()
        })

        notification.notify(project)
    }
}
