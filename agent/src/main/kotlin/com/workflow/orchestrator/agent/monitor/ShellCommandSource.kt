package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.diagnostic.Logger
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
) : MonitorSource {

    private var job: Job? = null
    private var process: Process? = null

    override fun start(emit: (MonitorEvent) -> Unit) {
        job = cs.launch(Dispatchers.IO) {
            runCatching {
                val pb = ProcessBuilder("/bin/bash", "-lc", command).redirectErrorStream(true)
                if (workingDir != null) pb.directory(workingDir)
                val p = pb.start().also { process = it }
                p.inputStream.bufferedReader().use { r: BufferedReader ->
                    r.lineSequence().forEach { line ->
                        classify(monitorId, line, filter)?.let(emit)
                    }
                }
            }.onFailure { LOG.warn("[ShellCommandSource:$monitorId] failed: ${it.message}", it) }
        }
    }

    override fun stop() {
        runCatching { process?.destroy() }
        job?.cancel(); job = null
    }

    companion object {
        private val LOG = Logger.getInstance(ShellCommandSource::class.java)

        /** Patterns that always escalate to ALERT regardless of the user's filter. */
        val FAILURE_SIGNATURES = Regex("Traceback|Exception|\\bERROR\\b|FAILED|\\bKilled\\b|OOM|panic:", RegexOption.IGNORE_CASE)

        /** Pure: returns the event for a line, or null when the line doesn't match [filter]. */
        fun classify(monitorId: String, line: String, filter: Regex): MonitorEvent? {
            if (!filter.containsMatchIn(line)) return null
            val sev = if (FAILURE_SIGNATURES.containsMatchIn(line)) Severity.ALERT else Severity.NOTABLE
            return MonitorEvent(monitorId, sev, line.trim())
        }
    }
}
