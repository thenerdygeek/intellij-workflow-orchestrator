package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.Disposable
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

    private val timer = Timer(intervalMs) {
        flushIfNeeded()
    }.apply {
        isRepeats = true
        isCoalesce = true
    }

    fun start() {
        if (!disposed.get()) timer.start()
    }

    fun stop() {
        timer.stop()
    }

    fun append(chunk: String) {
        synchronized(lock) {
            buffer.append(chunk)
        }
        if (!timer.isRunning && !disposed.get()) {
            timer.start()
        }
    }

    /** Flush remaining buffer immediately (called on endStream / cancel). */
    fun flush() {
        timer.stop()
        flushIfNeeded()
    }

    fun clear() {
        timer.stop()
        synchronized(lock) {
            buffer.setLength(0)
        }
    }

    private fun flushIfNeeded() {
        val text: String
        synchronized(lock) {
            if (buffer.isEmpty()) return
            text = buffer.toString()
            buffer.setLength(0)
        }
        if (!disposed.get()) {
            invokeLater {
                onFlush(text)
            }
        }
    }

    override fun dispose() {
        disposed.set(true)
        timer.stop()
        synchronized(lock) {
            buffer.setLength(0)
        }
    }
}
