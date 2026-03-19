package com.workflow.orchestrator.core.polling

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.IdeFocusManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Activity-aware polling with exponential backoff and jitter.
 * - Active (tab visible + IDE focused): baseInterval (e.g., 30s)
 * - Background (tab hidden or IDE unfocused): baseInterval * 4 (e.g., 120s)
 * - Backoff: multiplies by 1.5x on no-change responses, caps at maxInterval
 * - Jitter: +/-10% random offset to prevent thundering herd
 */
class SmartPoller(
    private val name: String,
    private val baseIntervalMs: Long = 30_000,
    private val maxIntervalMs: Long = 300_000,
    private val scope: CoroutineScope,
    private val action: suspend () -> Boolean  // returns true if data changed
) {
    private val log = Logger.getInstance(SmartPoller::class.java)
    private var job: Job? = null
    private val visible = AtomicBoolean(true)
    private var currentBackoff = 1.0

    fun start() {
        if (job?.isActive == true) return
        currentBackoff = 1.0
        job = scope.launch {
            while (isActive) {
                try {
                    val changed = action()
                    currentBackoff = if (changed) {
                        1.0
                    } else {
                        (currentBackoff * 1.5).coerceAtMost(maxIntervalMs.toDouble() / baseIntervalMs)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("[Poller:$name] Error: ${e.message}")
                    currentBackoff = (currentBackoff * 2.0).coerceAtMost(maxIntervalMs.toDouble() / baseIntervalMs)
                }

                val effectiveInterval = if (visible.get() && isIdeFocused()) {
                    (baseIntervalMs * currentBackoff).toLong()
                } else {
                    (baseIntervalMs * currentBackoff * 4).toLong().coerceAtMost(maxIntervalMs)
                }

                // Add +/-10% jitter
                val jitter = (effectiveInterval * 0.1 * (Random.nextDouble() * 2 - 1)).toLong()
                val finalDelay = (effectiveInterval + jitter).coerceAtLeast(baseIntervalMs)

                log.debug("[Poller:$name] Next poll in ${finalDelay / 1000}s (backoff=${String.format("%.1f", currentBackoff)}x, visible=${visible.get()})")
                delay(finalDelay)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun setVisible(isVisible: Boolean) {
        val wasHidden = !visible.getAndSet(isVisible)
        if (wasHidden && isVisible) {
            // Became visible -- reset backoff and poll immediately
            currentBackoff = 1.0
            scope.launch {
                try {
                    action()
                } catch (_: Exception) {
                    // Swallow; normal polling will handle errors
                }
            }
        }
    }

    fun resetBackoff() {
        currentBackoff = 1.0
    }

    private fun isIdeFocused(): Boolean {
        return try {
            val frame = IdeFocusManager.getGlobalInstance().lastFocusedFrame
            frame != null
        } catch (_: Exception) {
            true
        }
    }
}
