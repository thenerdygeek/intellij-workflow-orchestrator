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
 *
 * **[invoker] EDT requirement:** In production the [invoker] parameter must dispatch work
 * to the EDT (e.g. `{ block -> invokeLater { block() } }`). The default argument already
 * does this. The injectable form `{ block -> block() }` (direct call, no EDT hop) is safe
 * **in tests only** because the Swing [Timer] is never actually ticked in a headless test
 * environment — callers drive flushing explicitly via [flush] or [flushIfNeeded].
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
     *
     * The disposed check is performed once at entry (before the buffer snapshot), not
     * per-entry inside the delivery loop. This guards against the two failure modes:
     *
     * - **Already disposed:** `dispose()` completed before this call started — bail out
     *   immediately so data appended after dispose is not delivered.
     * - **Race during execution:** `dispose()` fires *while* this method is mid-execution.
     *   Bytes already extracted from the buffer (before `dispose()` cleared it) are
     *   delivered unconditionally — the data was committed to `entries` before dispose
     *   ran, and silently dropping it would break session-end guarantees.
     */
    fun flush() {
        if (disposed.get()) return
        timer.stop()
        // Snapshot and clear all entries under lock
        val entries: List<Pair<String, String>>
        synchronized(lock) {
            entries = buffers.entries.map { it.key to it.value.toString() }
            buffers.clear()
        }
        // Deliver unconditionally — these bytes were extracted before any concurrent
        // dispose cleared the map. Do NOT re-check disposed here.
        for ((id, text) in entries) {
            if (text.isNotEmpty()) {
                invoker { onFlush(id, text) }
            }
        }
    }

    // ────────────────────────────────────────────
    //  Timer callback
    // ────────────────────────────────────────────

    private fun flushIfNeeded() {
        // Snapshot ALL entries (not just non-empty) and clear the whole map under lock.
        // Clearing all entries (not only non-empty ones) prevents empty StringBuilder
        // entries from accumulating indefinitely when a tool call produces no output.
        val entries: List<Pair<String, String>>
        synchronized(lock) {
            if (buffers.isEmpty()) return
            entries = buffers.entries.map { it.key to it.value.toString() }
            buffers.clear()  // always clear all, not just non-empty
        }
        // Deliver outside lock; skip delivery if disposed (timer-driven path only)
        if (!disposed.get()) {
            for ((id, text) in entries) {
                if (text.isNotEmpty()) {
                    invoker { onFlush(id, text) }
                }
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
