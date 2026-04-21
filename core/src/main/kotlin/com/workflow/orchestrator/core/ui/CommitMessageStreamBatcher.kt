package com.workflow.orchestrator.core.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Timer

/**
 * 16ms EDT coalescer for streaming text updates into Swing components.
 *
 * Thread-safe: producer (IO thread) calls [submit] at high frequency;
 * the single consumer invocation runs on EDT every 16ms with the latest text.
 *
 * Without coalescing, SSE token rates of 30-60 Hz would cause Swing repaint storms.
 * With 16ms batching, effective update rate is capped at ~60 FPS.
 *
 * The [apply] callback is invoked on the EDT inside [ApplicationManager.invokeLater].
 * Callers that need undo-transparent writes (e.g. streaming into a commit message field)
 * should wrap their apply lambda in [com.intellij.openapi.command.CommandProcessor.runUndoTransparentAction]
 * to avoid polluting IntelliJ's undo stack with partial tokens.
 */
class CommitMessageStreamBatcher(
    private val modalityState: ModalityState,
    private val apply: (String) -> Unit
) : Disposable {

    private val pending = AtomicReference<String?>(null)

    private val timer = Timer(16) {
        drainPending()
    }.also { t ->
        t.isRepeats = true
    }

    /** Start the 16ms coalescing timer. Call once after construction. */
    fun start() {
        timer.start()
    }

    /**
     * Called from any thread at high frequency — stores only the latest text.
     * Previous unconsumed values are silently overwritten (we only care about
     * the most recent accumulated state).
     */
    fun submit(text: String) {
        pending.set(text)
    }

    /**
     * Flush any pending text synchronously without waiting for the next timer tick.
     * Call on stream completion to ensure the very last partial token is delivered
     * to the EDT before the final authoritative write.
     */
    fun flush() {
        drainPending()
    }

    private fun drainPending() {
        val text = pending.getAndSet(null) ?: return
        ApplicationManager.getApplication().invokeLater({ apply(text) }, modalityState)
    }

    override fun dispose() {
        timer.stop()
    }
}
