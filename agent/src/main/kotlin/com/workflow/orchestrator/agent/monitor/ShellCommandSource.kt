package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.process.ShellResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader

/**
 * Watches a long-running shell command and emits a [MonitorEvent] for each stdout line
 * matching [filter]. Mirrors Claude Code's Monitor: the filter should be failure-inclusive
 * ("silence is not success"); lines matching the built-in [FAILURE_SIGNATURES] escalate to ALERT.
 *
 * stderr is merged into stdout (redirectErrorStream) so a command's failures reach the filter.
 */
class ShellCommandSource(
    override val monitorId: String,
    override val description: String,
    private val command: String,
    private val filter: Regex,
    private val workingDir: java.io.File?,
    private val cs: CoroutineScope,
    private val project: Project?,
    private val onExit: (exitCode: Int?) -> Unit = {},
) : MonitorSource {

    @Volatile private var job: Job? = null
    @Volatile private var process: Process? = null
    @Volatile private var stopped = false

    override fun start(emit: (MonitorEvent) -> Unit) {
        // TODO(Phase 2): gate `command` through DefaultCommandFilter for parity with RunCommandTool.
        job = cs.launch(Dispatchers.IO) {
            var exit: Int? = null
            runCatching {
                // Platform-aware shell selection (Windows: Git Bash → PowerShell → cmd; Unix: /bin/bash → $SHELL → /bin/sh).
                // ShellResolver tolerates a null project, but fall back to /bin/bash -lc if resolution fails.
                val pb = runCatching {
                    val shellConfig = ShellResolver.resolve(null, project)
                    ProcessBuilder(shellConfig.executable, *shellConfig.args.toTypedArray(), command)
                }.getOrElse {
                    ProcessBuilder("/bin/bash", "-lc", command)
                }.redirectErrorStream(true)
                if (workingDir != null) pb.directory(workingDir)
                val p = pb.start().also { process = it }
                if (stopped) { killTree(p); return@launch }   // start-after-stop race guard
                p.inputStream.bufferedReader().use { r: BufferedReader ->
                    for (line in r.lineSequence()) {
                        if (stopped) break
                        classify(monitorId, line, filter)?.let(emit)
                    }
                }
                exit = runCatching { p.waitFor() }.getOrNull()
            }.onFailure { LOG.warn("[ShellCommandSource:$monitorId] failed: ${it.message}", it) }
            if (!stopped) onExit(exit)   // natural exit only — explicit stop suppresses the exit notification
        }
    }

    override fun stop() {
        stopped = true
        process?.let { killTree(it) }
        job?.cancel(); job = null
    }

    private fun killTree(p: Process) {
        runCatching { p.descendants().forEach { runCatching { it.destroyForcibly() } } }  // snapshot taken before parent dies
        runCatching { p.destroyForcibly() }
    }

    /** TEST-ONLY: exposes the tracked process handle so tests can snapshot the descendant subtree before stop(). */
    internal fun processHandleForTest(): java.lang.ProcessHandle? = process?.toHandle()

    companion object {
        private val LOG = Logger.getInstance(ShellCommandSource::class.java)

        /** Patterns that always escalate to ALERT regardless of the user's filter. */
        val FAILURE_SIGNATURES = Regex(
            "Traceback|Exception|\\bERROR\\b|\\bFAILED\\b|\\bKilled\\b|\\bOOM\\b|\\bpanic:",
            RegexOption.IGNORE_CASE
        )

        /** Pure: returns the event for a line, or null when the line doesn't match [filter]. */
        fun classify(monitorId: String, line: String, filter: Regex): MonitorEvent? {
            if (!filter.containsMatchIn(line)) return null
            val sev = if (FAILURE_SIGNATURES.containsMatchIn(line)) Severity.ALERT else Severity.NOTABLE
            return MonitorEvent(monitorId, sev, line.trim())
        }
    }
}
