package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer

/**
 * Per-tool-call stream batcher that coalesces rapid LLM stream chunks into 16ms batched
 * EDT dispatches, keyed by [toolCallId]. Each tool call's output is batched independently
 * so tool N's chunks never bleed into tool M's buffer.
 *
 * Sibling of [StreamBatcher] (which merges everything into a single rolling buffer).
 * Use this class when the caller needs to route finalized text back to the originating
 * tool call (e.g., `updateLastToolCall` in the JCEF bridge).
 *
 * Thread safety: all buffer mutations are guarded by [lock]. The timer callback and
 * [flushIfNeeded] run under that lock only for the snapshot extraction phase; [onFlush]
 * is always called *outside* the lock to avoid lock inversion.
 *
 * Insertion order is preserved via [LinkedHashMap]: when multiple tool calls have pending
 * text in a single timer tick they flush in first-seen order (deterministic in tests).
 */
class PerToolStreamBatcher(
    private val onFlush: (toolCallId: String, batched: String) -> Unit,
    private val intervalMs: Int = 16,
    private val invoker: (() -> Unit) -> Unit = { block -> invokeLater { block() } }
) : Disposable {

    private val buffers = LinkedHashMap<String, StringBuilder>()
    private val lock = Any()
    private val disposed = AtomicBoolean(false)

    private val timer = Timer(intervalMs) {
        flushIfNeeded()
    }.apply {
        isRepeats = true
        isCoalesce = true
    }

    // ────────────────────────────────────────────
    //  Public API
    // ────────────────────────────────────────────

    /** Stop the timer without flushing (mirrors [StreamBatcher.stop]). */
    fun stop() {
        timer.stop()
    }

    /**
     * Append [chunk] to the buffer for [toolCallId].
     *
     * If the timer is not yet running and the batcher hasn't been disposed, starts it
     * so the first chunk is delivered within [intervalMs] milliseconds.
     */
    fun append(toolCallId: String, chunk: String) {
        synchronized(lock) {
            buffers.getOrPut(toolCallId) { StringBuilder() }.append(chunk)
        }
        if (!disposed.get()) {
            invoker {
                if (!timer.isRunning && !disposed.get()) {
                    timer.start()
                }
            }
        }
    }

    /**
     * Synchronously drain the buffer for a single [toolCallId] and deliver it to
     * [onFlush]. Called before `updateLastToolCall` so the final partial chunk is
     * flushed before the tool result is committed to the UI.
     *
     * Calling with an unknown [toolCallId] is a no-op (no crash, no callback).
     */
    fun flush(toolCallId: String) {
        val text: String
        synchronized(lock) {
            val sb = buffers.remove(toolCallId) ?: return
            text = sb.toString()
        }
        if (text.isNotEmpty() && !disposed.get()) {
            invoker { onFlush(toolCallId, text) }
        }
    }

    /**
     * Full synchronous drain: stop the timer, then deliver all pending buffers to
     * [onFlush] in insertion order. Called on session end or cancel.
     */
    fun flush() {
        timer.stop()
        // Snapshot and clear all entries under lock
        val entries: List<Pair<String, String>>
        synchronized(lock) {
            entries = buffers.entries.map { it.key to it.value.toString() }
            buffers.clear()
        }
        // Deliver outside lock — no lock inversion risk
        for ((id, text) in entries) {
            if (text.isNotEmpty() && !disposed.get()) {
                invoker { onFlush(id, text) }
            }
        }
    }

    // ────────────────────────────────────────────
    //  Timer callback
    // ────────────────────────────────────────────

    private fun flushIfNeeded() {
        // Snapshot non-empty entries and clear them under lock
        val entries: List<Pair<String, String>>
        synchronized(lock) {
            if (buffers.isEmpty()) return
            entries = buffers.entries
                .filter { it.value.isNotEmpty() }
                .map { it.key to it.value.toString() }
            entries.forEach { (id, _) -> buffers.remove(id) }
        }
        // Deliver outside lock
        if (!disposed.get()) {
            for ((id, text) in entries) {
                invoker { onFlush(id, text) }
            }
        }
    }

    // ────────────────────────────────────────────
    //  Lifecycle
    // ────────────────────────────────────────────

    override fun dispose() {
        disposed.set(true)
        timer.stop()
        synchronized(lock) { buffers.clear() }
    }
}
