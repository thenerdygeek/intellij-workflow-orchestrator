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
 * **Approach (root JUL logger + namespace [Filter]).** On IntelliJ 2025.1, `Logger.getInstance`
 * is backed by `com.intellij.idea.LoggerFactory`, which wraps
 * `java.util.logging.Logger.getLogger(category)` with the category string VERBATIM. Two category
 * shapes exist and they land on DIFFERENT branches of the JUL name tree:
 *  - `Logger.getInstance(Class)`  → category `"#" + fqcn` → JUL logger `#com.workflow.orchestrator…`
 *    (the overwhelmingly common case — ~203 call sites)
 *  - `Logger.getInstance(String)` → JUL logger == the literal string (e.g. `com.workflow.orchestrator…`)
 *
 * The leading `#` on the class-based loggers means they are NOT descendants of a
 * `com.workflow.orchestrator` JUL parent — their ancestor chain is `#com.workflow.orchestrator` →
 * `#com.workflow` → `#com` → root `""`. So the original [FileHandler] on the
 * `com.workflow.orchestrator` parent captured nothing and `plugin-0.log` stayed 0 bytes. The only
 * JUL node shared by BOTH name shapes is the ROOT logger (`""`), which every record reaches by
 * parent-handler propagation — and that is exactly where the platform attaches its `idea.log`
 * handler (`JulLogger.configureLogFileAndConsole` calls `Logger.getLogger("")`), which is why
 * idea.log DID capture these records while our intermediate handler did not.
 *
 * We therefore attach our [FileHandler] to the ROOT logger and gate it with a
 * [PluginLogNamespaceFilter] so ONLY `com.workflow.orchestrator` records (after stripping the `#`)
 * are written — keeping the file plugin-only rather than a copy of idea.log. The platform's
 * `JulLogger.clearHandlers()` clears the ROOT logger's handlers, but it runs ONCE during early
 * startup (at logger init); this Activity installs at project-open, well after that, and
 * `addHandler` APPENDS alongside the existing idea.log handler, so ours persists for the session.
 * `useParentHandlers` is left untouched, so idea.log keeps receiving plugin records too.
 *
 * **Known gap (acceptable).** Two string-category loggers in
 * `com.workflow.orchestrator.core.settings.PluginSettings` —
 * `Logger.getInstance("WebAllowlist")` and `Logger.getInstance("WebEgressDenyList")` — are NOT
 * under the `com.workflow.orchestrator` namespace, so the filter rejects them; their (low-volume,
 * parse-failure-only) records are not captured here. They still reach `idea.log` normally.
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
     * Attach the rotating file handler to the ROOT JUL logger, gated by [PluginLogNamespaceFilter]
     * so only this plugin's records are written. No-op when [enabled] is false or when already
     * installed. The install is ONE-SHOT per IDE session — once latched there is no uninstall path,
     * so toggling the setting off only takes effect after a restart (the UI label says as much).
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
            // Filter at the handler so the ROOT-attached handler writes plugin records ONLY, not the
            // full idea.log stream — see class KDoc for why root (not the `com.workflow.orchestrator`
            // parent) is the correct attach point given the `#`-prefixed class-based logger names.
            fileHandler.filter = PluginLogNamespaceFilter()
            ROOT_JUL_LOGGER.addHandler(fileHandler)
            // Do NOT touch useParentHandlers — idea.log must keep receiving plugin records too.
            handler = fileHandler
            // One INFO line via the IDE Logger: lands in idea.log AND (the filter strips the leading
            // `#`, so this `#com.workflow.orchestrator…` category passes) becomes the first record
            // written to the new file — a handy header.
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
            ROOT_JUL_LOGGER.removeHandler(h)
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

        /**
         * The ROOT JUL logger (`""`). Every `Logger.getInstance` record propagates here via JUL
         * parent-handler propagation regardless of whether the category is `#`-prefixed (class-based)
         * or a bare string — see class KDoc. The [PluginLogNamespaceFilter] on the handler narrows
         * it back to plugin-only records.
         */
        private val ROOT_JUL_LOGGER: java.util.logging.Logger =
            java.util.logging.Logger.getLogger("")
    }
}

/**
 * Passes only records that originate from this plugin's own loggers, so a [FileHandler] attached to
 * the shared ROOT JUL logger writes a plugin-only file instead of the whole platform log stream.
 *
 * Handles the two JUL name shapes IntelliJ's `LoggerFactory` produces (see
 * [PluginDiagnosticLogService] KDoc):
 *  - `Logger.getInstance(Class)`  → `#com.workflow.orchestrator…` (leading `#`)
 *  - `Logger.getInstance(String)` → `com.workflow.orchestrator…`  (no `#`)
 *
 * A single leading `#` is stripped before the namespace check; `null` logger names (and platform
 * categories such as `com.intellij.*`) are rejected.
 */
internal class PluginLogNamespaceFilter : java.util.logging.Filter {
    override fun isLoggable(record: LogRecord): Boolean {
        val name = record.loggerName ?: return false
        val canonical = if (name.startsWith('#')) name.substring(1) else name
        return canonical.startsWith(PLUGIN_NAMESPACE)
    }

    companion object {
        /** Root package shared by every plugin logger category. */
        const val PLUGIN_NAMESPACE: String = "com.workflow.orchestrator"
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
