package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer

/**
 * Coalesces rapid-fire stream chunks into a single bridge dispatch per frame (~16ms).
 * Reduces JCEF callJs() calls from ~5000 per response to ~300.
 */
class StreamBatcher(
    private val onFlush: (String) -> Unit,
    private val intervalMs: Int = 16
) : Disposable {

    private val buffer = StringBuilder()
    private val lock = Any()
    private val disposed = AtomicBoolean(false)

    // P2-1: tracks the timer so append() can start it with a lock-free CAS instead of
    // posting an invokeLater per SSE chunk (~5000/response) just to check timer.isRunning.
    private val timerRunning = AtomicBoolean(false)

    private val timer = Timer(intervalMs) {
        flushIfNeeded()
    }.apply {
        isRepeats = true
        isCoalesce = true
    }

    fun stop() {
        stopTimer()
    }

    /** Visible for tests. */
    internal fun isTimerRunning(): Boolean = timerRunning.get() && timer.isRunning

    fun append(chunk: String) {
        synchronized(lock) {
            buffer.append(chunk)
        }
        // javax.swing.Timer.start() is thread-safe — no EDT hop needed.
        if (!disposed.get() && timerRunning.compareAndSet(false, true)) {
            timer.start()
        }
    }

    /** Flush remaining buffer immediately (called on endStream / cancel). */
    fun flush() {
        stopTimer()
        flushIfNeeded()
    }

    fun clear() {
        stopTimer()
        synchronized(lock) {
            buffer.setLength(0)
        }
    }

    private fun stopTimer() {
        timer.stop()
        timerRunning.set(false)
    }

    internal fun flushIfNeeded() {
        val text: String
        synchronized(lock) {
            if (buffer.isEmpty()) {
                // B12: an empty tick means the stream went quiet without flush()/clear()
                // (error exits). Stop instead of ticking the EDT at 60Hz forever.
                stopTimer()
                return
            }
            text = buffer.toString()
            buffer.setLength(0)
        }
        // B12: stop on the same tick as the drain rather than waiting for the next empty
        // tick. If append() races in between, the CAS in append() restarts the timer.
        synchronized(lock) {
            if (buffer.isEmpty()) stopTimer()
        }
        if (!disposed.get() && ApplicationManager.getApplication() != null) {
            invokeLater {
                onFlush(text)
            }
        }
    }

    override fun dispose() {
        disposed.set(true)
        stopTimer()
        synchronized(lock) {
            buffer.setLength(0)
        }
    }
}
