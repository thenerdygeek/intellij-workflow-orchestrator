package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.diagnostic.Logger

/**
 * Thread-safe state machine for dispatching JavaScript calls to a JCEF browser.
 *
 * Extracted from AgentCefPanel's `callJs`/`pageLoaded`/`pendingCalls` logic so that
 * the state transitions can be unit-tested without a live Chromium process.
 *
 * ## State machine
 *
 * ```
 *   NOT_LOADED  ──markLoaded()──▶  LOADED  ──dispose()──▶  DISPOSED
 *       │                            │                         │
 *       │ dispatch()                 │ dispatch()              │ dispatch()
 *       ▼                            ▼                         ▼
 *   buffer in pendingCalls      execute immediately        drop + warn
 * ```
 *
 * ## Race condition this class prevents
 *
 * If the consumer calls `dispatch()` before `markLoaded()`, calls are buffered.
 * When `markLoaded()` fires, ALL buffered calls are flushed in order. A missing
 * `markLoaded()` call (the root cause of the stuck-spinner bug) means the buffer
 * grows forever — detectable by checking [pendingCallCount] and [isLoaded].
 *
 * @param executor Function that actually executes JavaScript (e.g., `cefBrowser.executeJavaScript`)
 * @param maxPendingCalls Maximum buffer size before calls are dropped (backpressure)
 * @param interactiveFunctions Function names where silent failure leaves the user stuck — logged at WARN
 */
class JsBridgeDispatcher(
    private val executor: (String) -> Unit,
    private val maxPendingCalls: Int = 10_000,
    private val interactiveFunctions: Set<String> = DEFAULT_INTERACTIVE_FUNCTIONS
) {
    companion object {
        private val LOG = Logger.getInstance(JsBridgeDispatcher::class.java)

        val DEFAULT_INTERACTIVE_FUNCTIONS = setOf(
            "setBusy", "showQuestions", "setInputLocked", "focusInput"
        )
    }

    private val lock = Any()
    private val pendingCalls = mutableListOf<String>()

    @Volatile
    var isLoaded: Boolean = false
        private set

    @Volatile
    var isDisposed: Boolean = false
        private set

    /** Number of calls currently buffered (for diagnostics and testing). */
    val pendingCallCount: Int get() = synchronized(lock) { pendingCalls.size }

    /**
     * Dispatch a JavaScript call. Behavior depends on state:
     * - LOADED: execute immediately via [executor]
     * - NOT_LOADED: buffer in [pendingCalls], flush when [markLoaded] is called
     * - DISPOSED: drop with warning
     *
     * @return true if the call was executed or buffered, false if dropped
     */
    fun dispatch(code: String): Boolean {
        if (isDisposed) {
            warnIfInteractive(code, "DROPPED — dispatcher is disposed")
            return false
        }

        if (isLoaded) {
            execute(code)
            return true
        }

        // Not loaded yet — buffer
        synchronized(lock) {
            if (pendingCalls.size >= maxPendingCalls) {
                LOG.warn("JsBridgeDispatcher: pending calls queue exceeded $maxPendingCalls items, dropping: ${code.take(60)}")
                return false
            }
            pendingCalls.add(code)
        }
        warnIfInteractive(code, "BUFFERED — page not loaded (pendingCalls=$pendingCallCount)")
        return true
    }

    /**
     * Mark the page as loaded and flush all buffered calls in order.
     *
     * Must be called exactly once. Calling multiple times is idempotent (second call is a no-op).
     * Must be called AFTER the load handler is registered and the page finishes loading.
     *
     * @param preFlush Optional block to run before flushing (e.g., inject bridge functions).
     *                 Runs while holding the lock, so it's safe to assume no concurrent dispatches
     *                 will execute between preFlush and the flush.
     */
    fun markLoaded(preFlush: (() -> Unit)? = null) {
        if (isLoaded) return // idempotent

        synchronized(lock) {
            preFlush?.invoke()
            isLoaded = true
            pendingCalls.forEach { execute(it) }
            pendingCalls.clear()
        }
    }

    /**
     * Dispose the dispatcher. All future [dispatch] calls are dropped.
     * Any remaining buffered calls are discarded.
     */
    fun dispose() {
        isDisposed = true
        synchronized(lock) {
            pendingCalls.clear()
        }
    }

    private fun execute(code: String) {
        try {
            executor(code)
        } catch (e: Exception) {
            LOG.warn("JsBridgeDispatcher: JS execution failed for ${code.take(60)}: ${e.message}")
        }
    }

    private fun warnIfInteractive(code: String, reason: String) {
        val fn = code.substringBefore("(")
        if (fn in interactiveFunctions) {
            LOG.warn("JsBridgeDispatcher: $fn() $reason")
        }
    }
}
