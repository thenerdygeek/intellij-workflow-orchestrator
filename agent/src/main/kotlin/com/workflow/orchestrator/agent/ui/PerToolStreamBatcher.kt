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
 * does this. An **EDT-inline** invoker (`{ block -> if (isEventDispatchThread()) block()
 * else invokeLater { block() } }`) also satisfies the requirement and is used where a
 * caller needs `flush(id)` invoked FROM the EDT to deliver synchronously, before the
 * caller's next statement (P1-12: AgentController flushes a sub-agent's thinking batcher
 * inside the EDT completion handler, before pushing the completion card — a re-posted
 * delivery would land AFTER the card). Timer ticks also run on the EDT, so the inline
 * branch is correct for them too. The unconditional direct form `{ block -> block() }`
 * (no EDT check) remains safe **in tests only** because the Swing [Timer] is never ticked
 * in a headless test environment — callers drive flushing explicitly via [flush] or
 * [flushIfNeeded]. Note: timer START no longer routes through the invoker (Timer.start()
 * is thread-safe — P2-1); the invoker is still used for onFlush delivery.
 */
class PerToolStreamBatcher(
    private val onFlush: (toolCallId: String, batched: String) -> Unit,
    private val intervalMs: Int = 16,
    private val invoker: (() -> Unit) -> Unit = { block -> invokeLater { block() } }
) : Disposable {

    private val buffers = LinkedHashMap<String, StringBuilder>()
    private val lock = Any()
    private val disposed = AtomicBoolean(false)
    private val timerRunning = AtomicBoolean(false)

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
        stopTimer()
    }

    /**
     * Drop ALL pending buffers without delivering (mirrors [StreamBatcher.clear]).
     * Used on cancel / new chat: a cancelled run's tail deltas must not deliver into
     * the next session (W4-B3 review minor #2).
     */
    fun clear() {
        stopTimer()
        synchronized(lock) { buffers.clear() }
    }

    /** Visible for tests. */
    internal fun isTimerRunning(): Boolean = timerRunning.get() && timer.isRunning

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
        // javax.swing.Timer.start() is thread-safe — the per-chunk invoker hop is gone (P2-1).
        if (!disposed.get() && timerRunning.compareAndSet(false, true)) {
            timer.start()
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
        stopTimer()
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

    internal fun flushIfNeeded() {
        // Snapshot ALL entries (not just non-empty) and clear the whole map under lock.
        // Clearing all entries (not only non-empty ones) prevents empty StringBuilder
        // entries from accumulating indefinitely when a tool call produces no output.
        val entries: List<Pair<String, String>>
        synchronized(lock) {
            if (buffers.isEmpty()) {
                stopTimer()   // B12 sibling: per-id flush() drains entries; once empty, stop.
                return
            }
            entries = buffers.entries.map { it.key to it.value.toString() }
            buffers.clear()  // always clear all, not just non-empty
        }
        // B12: stop on the same tick as the drain rather than waiting for the next empty
        // tick. If append() races in between, the CAS in append() restarts the timer.
        synchronized(lock) {
            if (buffers.isEmpty()) stopTimer()
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
    //  Internal helpers
    // ────────────────────────────────────────────

    private fun stopTimer() {
        timer.stop()
        timerRunning.set(false)
    }

    // ────────────────────────────────────────────
    //  Lifecycle
    // ────────────────────────────────────────────

    override fun dispose() {
        disposed.set(true)
        stopTimer()
        synchronized(lock) { buffers.clear() }
    }
}
