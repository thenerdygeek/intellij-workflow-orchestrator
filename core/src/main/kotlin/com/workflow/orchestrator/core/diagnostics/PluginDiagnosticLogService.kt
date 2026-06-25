package com.workflow.orchestrator.core.diagnostics

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * Writes a small, rotating, SHAREABLE diagnostic log containing ONLY this plugin's own log
 * records — so a tester running in a sandbox IDE can hand over a focused log without the
 * cumulative 13K+ line platform `idea.log` (full of unrelated platform noise).
 *
 * **Approach (java.util.logging parent-handler propagation).** On IntelliJ 2025.1,
 * `Logger.getInstance(category)` is backed by an `IdeaLogger` whose JUL logger name equals the
 * category string. Every plugin category begins with `com.workflow.orchestrator`, so attaching a
 * single [FileHandler] to the parent JUL logger `"com.workflow.orchestrator"` captures ALL plugin
 * records via JUL parent-handler propagation — with no per-category wiring. The platform's
 * `clearHandlers()` only touches the root (`""`) logger, so our handler survives.
 *
 * **Known gap (acceptable).** Two string-category loggers in
 * `com.workflow.orchestrator.core.settings.PluginSettings` —
 * `Logger.getInstance("WebAllowlist")` and `Logger.getInstance("WebEgressDenyList")` — are NOT
 * under the `com.workflow.orchestrator` namespace, so their (low-volume, parse-failure-only)
 * records are not captured here. They still reach `idea.log` normally.
 *
 * App-level service: the handler is process-wide (one JUL logger tree per IDE), so this is
 * `Service.Level.APP`. The constructor does no heavy work — installation is deferred to
 * [ensureInstalled], called once at startup by `PluginDiagnosticLogActivity`.
 *
 * **App-wide path.** The file lives at the app-wide `~/.workflow-orchestrator/diagnostics/`; if
 * multiple IDE instances run concurrently the JUL [FileHandler] contends on the `.lck` and rolls to
 * the next generation (`plugin-1.log` / `plugin-2.log`). A single sandbox is the expected case.
 *
 * **Setting scope.** The toggle is read per-project but the handler is app-global, so the effective
 * state is "enabled if ANY open project enables it"; a project's "off" cannot override another open
 * project's "on" within a session.
 */
@Service(Service.Level.APP)
class PluginDiagnosticLogService : Disposable {

    private val log = Logger.getInstance(PluginDiagnosticLogService::class.java)

    private val installed = AtomicBoolean(false)
    // @Volatile: written by the install thread after the CAS, read by dispose() on a different
    // thread — the volatile edge guarantees dispose() sees the handler rather than a stale null.
    @Volatile private var handler: FileHandler? = null

    /**
     * Attach the rotating file handler to the `com.workflow.orchestrator` JUL logger. No-op when
     * [enabled] is false or when already installed. The install is ONE-SHOT per IDE session — once
     * latched there is no uninstall path, so toggling the setting off only takes effect after a
     * restart (the UI label says as much).
     */
    fun ensureInstalled(enabled: Boolean) {
        if (!enabled) return
        // Skip install when user.home is unusable — File(null, child) would otherwise resolve the
        // log under the IDE's CWD instead of the home dir. Treat as permanently un-installable.
        val userHome = System.getProperty("user.home")
        if (userHome.isNullOrBlank()) return
        if (!installed.compareAndSet(false, true)) return
        var fileHandler: FileHandler? = null
        try {
            val dir = File(userHome, "$ROOT_DIR/diagnostics")
            dir.mkdirs()
            // pattern: plugin-0.log, plugin-1.log, plugin-2.log — 5 MB each, 3 generations, appended.
            fileHandler = FileHandler(File(dir, "plugin-%g.log").absolutePath, FILE_LIMIT_BYTES, FILE_COUNT, true)
            fileHandler.level = Level.ALL
            fileHandler.formatter = CompactFormatter()
            JUL_LOGGER.addHandler(fileHandler)
            // Do NOT touch useParentHandlers — idea.log must keep receiving plugin records too.
            handler = fileHandler
            // One INFO line via the IDE Logger: lands in idea.log AND (since this category is under
            // com.workflow.orchestrator) becomes the first record written to the new file — a handy header.
            log.info("Plugin diagnostic log enabled: ${File(dir, "plugin-0.log").absolutePath}")
        } catch (e: Exception) {
            // Close the partially-constructed handler so its file lock / fd doesn't leak.
            try { fileHandler?.close() } catch (_: Exception) {}
            // Leave `installed` true: an I/O failure here (dir not writable, disk full) is effectively
            // permanent, so retrying on every subsequent project open would only spam this warning.
            log.warn("Failed to install plugin diagnostic log handler; disabling for this session", e)
        }
    }

    override fun dispose() {
        val h = handler ?: return
        try {
            JUL_LOGGER.removeHandler(h)
            h.flush()
            h.close()
        } catch (e: Exception) {
            log.warn("Failed to close plugin diagnostic log handler", e)
        } finally {
            handler = null
        }
    }

    private companion object {
        private const val ROOT_DIR = ".workflow-orchestrator"
        private const val FILE_LIMIT_BYTES = 5 * 1024 * 1024
        private const val FILE_COUNT = 3

        /** The plugin's parent JUL logger; every `Logger.getInstance` category propagates to it. */
        private val JUL_LOGGER: java.util.logging.Logger =
            java.util.logging.Logger.getLogger("com.workflow.orchestrator")
    }
}

/**
 * One compact line per record: `yyyy-MM-dd HH:mm:ss.SSS  LEVEL  [<simpleLoggerName>]  <message>`,
 * followed by the throwable stacktrace on subsequent lines when present. Uses ONLY public
 * java.util.logging + java.time APIs (no `@ApiStatus.Internal` platform formatter).
 */
private class CompactFormatter : Formatter() {

    private val timestampFormat = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    override fun format(record: LogRecord): String {
        val sb = StringBuilder(128)
        sb.append(timestampFormat.format(Instant.ofEpochMilli(record.millis)))
        sb.append("  ").append(record.level.name)
        sb.append("  [").append(simpleLoggerName(record.loggerName)).append("]")
        sb.append("  ").append(formatMessage(record))
        sb.append('\n')
        record.thrown?.let { thrown ->
            val stack = java.io.StringWriter()
            thrown.printStackTrace(java.io.PrintWriter(stack))
            sb.append(stack)
        }
        return sb.toString()
    }

    /** Substring after the last '.' of the logger name; falls back to the full name when absent. */
    private fun simpleLoggerName(loggerName: String?): String {
        val name = loggerName ?: return "?"
        val lastDot = name.lastIndexOf('.')
        return if (lastDot >= 0 && lastDot < name.length - 1) name.substring(lastDot + 1) else name
    }
}
