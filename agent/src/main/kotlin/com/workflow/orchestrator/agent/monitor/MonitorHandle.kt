package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.agent.tools.background.AttachResult
import com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent
import com.workflow.orchestrator.agent.tools.background.BackgroundHandle
import com.workflow.orchestrator.agent.tools.background.BackgroundState
import com.workflow.orchestrator.agent.tools.background.OutputChunk
import java.util.concurrent.atomic.AtomicLong

/**
 * A monitor presented as a background unit so EnvironmentDetailsBuilder can surface it
 * passively. "Output" is the recent-event ring buffer; "kill" cancels the wrapped source's
 * coroutine/process (no OS signal for non-shell sources).
 */
class MonitorHandle(
    val source: MonitorSource,
    override val sessionId: String,
    override val startedAt: Long,
    private val maxLines: Int = 50,
) : BackgroundHandle {
    override val bgId: String = source.monitorId
    override val kind: String = "monitor"
    override val label: String = source.description

    private val lines = ArrayDeque<String>()
    private val bytes = AtomicLong(0)
    private val killed = java.util.concurrent.atomic.AtomicBoolean(false)

    @Synchronized
    fun appendLine(line: String) {
        lines.addLast(line)
        bytes.addAndGet(line.toByteArray().size.toLong())
        while (lines.size > maxLines) lines.removeFirst()
    }

    override fun state(): BackgroundState = if (killed.get()) BackgroundState.KILLED else BackgroundState.RUNNING
    override fun exitCode(): Int? = null
    override fun runtimeMs(): Long = 0
    override fun outputBytes(): Long = bytes.get()

    /**
     * Reads the recent-event ring buffer.
     *
     * Ring semantics: [sinceOffset] is intentionally IGNORED — a bounded ring buffer has
     * no stable byte offsets (old lines are evicted), so there is no meaningful "resume from
     * offset N" position. [truncated] is true when the returned window is smaller than the
     * current buffer (i.e. [tailLines] clipped some retained lines).
     */
    @Synchronized
    override fun readOutput(sinceOffset: Long, tailLines: Int?): OutputChunk {
        val all = lines.toList()
        val window = if (tailLines != null) all.takeLast(tailLines) else all
        val content = window.joinToString("\n")
        return OutputChunk(content = content, nextOffset = bytes.get(), truncated = window.size < all.size, spillPath = null)
    }

    override suspend fun attach(timeoutMs: Long): AttachResult =
        AttachResult.Idle(reason = "MONITOR_NO_ATTACH", lastOutput = readOutput(tailLines = 20).content)

    override fun kill(): Boolean {
        if (!killed.compareAndSet(false, true)) return false
        runCatching { source.stop() }
        return true
    }

    override fun onComplete(callback: (event: BackgroundCompletionEvent) -> Unit) { /* monitors don't self-complete */ }
}
