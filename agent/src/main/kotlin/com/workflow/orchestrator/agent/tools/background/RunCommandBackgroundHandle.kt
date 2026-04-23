package com.workflow.orchestrator.agent.tools.background

import com.workflow.orchestrator.agent.tools.process.ManagedProcess
import com.workflow.orchestrator.agent.tools.process.OutputCollector
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * BackgroundHandle backed by ProcessRegistry.ManagedProcess. v1's only
 * BackgroundCapable implementer is RunCommandTool, which hands us its spawned
 * ManagedProcess.
 */
class RunCommandBackgroundHandle(
    override val bgId: String,
    override val sessionId: String,
    val managed: ManagedProcess,
    override val label: String,
) : BackgroundHandle {

    override val kind: String = "run_command"
    override val startedAt: Long = managed.startedAt

    private val completionFired = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(BackgroundCompletionEvent) -> Unit>()
    private val killed = AtomicBoolean(false)

    override fun state(): BackgroundState {
        if (killed.get()) return BackgroundState.KILLED
        return if (managed.process.isAlive) BackgroundState.RUNNING else BackgroundState.EXITED
    }

    override fun exitCode(): Int? =
        if (managed.process.isAlive) null
        else runCatching { managed.process.exitValue() }.getOrNull()

    override fun runtimeMs(): Long = System.currentTimeMillis() - startedAt

    override fun outputBytes(): Long =
        managed.outputLines.sumOf { it.toByteArray().size.toLong() }

    override fun readOutput(sinceOffset: Long, tailLines: Int?): OutputChunk {
        val full = managed.outputLines.joinToString("")
        val bytes = full.toByteArray()
        if (sinceOffset >= bytes.size) {
            return OutputChunk("", bytes.size.toLong(), false, null)
        }
        val windowBytes = bytes.copyOfRange(sinceOffset.toInt().coerceAtLeast(0), bytes.size)
        val windowText = String(windowBytes)
        val content = if (tailLines != null) {
            windowText.trimEnd('\n', '\r').lines().takeLast(tailLines).joinToString("\n")
        } else windowText
        return OutputChunk(
            content = OutputCollector.stripAnsi(content),
            nextOffset = bytes.size.toLong(),
            truncated = false,
            spillPath = null,
        )
    }

    override fun sendStdin(input: String): Boolean = ProcessRegistry.writeStdin(bgId, input)

    override suspend fun attach(timeoutMs: Long): AttachResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!managed.process.isAlive) {
                val out = OutputCollector.stripAnsi(managed.outputLines.joinToString(""))
                return AttachResult.Exited(managed.process.exitValue(), out)
            }
            delay(500)
        }
        return AttachResult.AttachTimeout(
            elapsedMs = timeoutMs,
            lastOutput = OutputCollector.stripAnsi(managed.outputLines.joinToString(""))
                .lines().takeLast(20).joinToString("\n"),
        )
    }

    override fun kill(): Boolean {
        if (killed.compareAndSet(false, true)) {
            return ProcessRegistry.kill(bgId)
        }
        return true
    }

    override fun onComplete(callback: (BackgroundCompletionEvent) -> Unit) {
        if (completionFired.get()) {
            callback(buildCompletionEvent())
            return
        }
        listeners.add(callback)
    }

    /** Called by the pool's supervisor coroutine when it observes !isAlive. */
    fun fireCompletion() {
        if (completionFired.compareAndSet(false, true)) {
            val ev = buildCompletionEvent()
            listeners.forEach { runCatching { it(ev) } }
        }
    }

    private fun buildCompletionEvent(): BackgroundCompletionEvent {
        val state = state()
        return BackgroundCompletionEvent(
            bgId = bgId,
            kind = kind,
            label = label,
            sessionId = sessionId,
            exitCode = exitCode() ?: -1,
            state = state,
            runtimeMs = runtimeMs(),
            tailContent = readOutput(tailLines = 20).content,
            spillPath = null,
            occurredAt = System.currentTimeMillis(),
        )
    }
}
