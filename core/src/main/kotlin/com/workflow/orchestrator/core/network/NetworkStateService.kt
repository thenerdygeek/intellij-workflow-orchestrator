package com.workflow.orchestrator.core.network

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-level connectivity authority (the VPN tunnel is per-machine, not per-project).
 * Constructor takes the platform-injected [cs]; never allocate a CoroutineScope here.
 */
@Service(Service.Level.APP)
class NetworkStateService(
    private val cs: CoroutineScope,
    private val probe: ReachabilityProbe = NetworkReachabilityProbe()
) : NetworkProbe {

    private val log = Logger.getInstance(NetworkStateService::class.java)
    private val _state = MutableStateFlow(NetworkState.ONLINE)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    @Volatile private var lastFailedTarget: String? = null
    private val probeLoopActive = AtomicBoolean(false)

    init { startWakeWatchdog() }

    override fun reportSuccess() {
        if (_state.value != NetworkState.ONLINE) log.info("[Network] back ONLINE (request succeeded)")
        _state.value = NetworkState.ONLINE
    }

    override fun reportFailure(targetUrl: String) {
        lastFailedTarget = targetUrl
        if (_state.value == NetworkState.ONLINE) {
            log.info("[Network] transport failure on $targetUrl — OFFLINE")
            _state.value = NetworkState.OFFLINE
        }
        ensureProbeLoop()
    }

    override suspend fun checkNow(targetUrl: String?): NetworkState {
        val target = targetUrl ?: lastFailedTarget ?: return _state.value
        val reachable = probe.isReachable(target)
        _state.value = if (reachable) NetworkState.ONLINE else NetworkState.OFFLINE
        if (!reachable) { lastFailedTarget = target; ensureProbeLoop() }
        return _state.value
    }

    override suspend fun awaitOnline(timeoutMs: Long): Boolean {
        if (_state.value == NetworkState.ONLINE) return true
        return withTimeoutOrNull(timeoutMs) { _state.first { it == NetworkState.ONLINE }; true } ?: false
    }

    /** The single active prober — discovers reconnection while pollers are paused. */
    private fun ensureProbeLoop() {
        if (!probeLoopActive.compareAndSet(false, true)) return
        // Launch on the injected scope (no forced dispatcher) so unit tests with a
        // TestDispatcher stay in virtual time. NetworkReachabilityProbe does its own
        // withContext(Dispatchers.IO) for the actual blocking HEAD.
        cs.launch {
            try {
                var attempt = 1
                while (_state.value != NetworkState.ONLINE) {
                    val target = lastFailedTarget
                    if (target != null && probe.isReachable(target)) { reportSuccess(); break }
                    delay((PROBE_BASE_MS * (1L shl (attempt - 1).coerceAtMost(5))).coerceAtMost(PROBE_MAX_MS))
                    attempt++
                }
            } finally {
                probeLoopActive.set(false)
                // Re-arm guard: if a failure was re-signaled while we were exiting (TOCTOU
                // between the loop break and clearing the flag), relaunch so we don't get
                // permanently stuck OFFLINE with no prober running.
                if (_state.value != NetworkState.ONLINE) ensureProbeLoop()
            }
        }
    }

    /** Clock-gap wake detector: a large jump between ticks means the machine slept. */
    private fun startWakeWatchdog() {
        cs.launch {
            var last = System.currentTimeMillis()
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val now = System.currentTimeMillis()
                val gap = now - last
                last = now
                if (isWakeGap(gap)) {
                    log.info("[Network] wake detected (clock gap ${gap}ms) — reprobing")
                    _state.value = NetworkState.RECONNECTING
                    val target = lastFailedTarget
                    val reachable = target == null || probe.isReachable(target)
                    _state.value = if (reachable) NetworkState.ONLINE else NetworkState.OFFLINE
                    if (!reachable) ensureProbeLoop()
                }
            }
        }
    }

    companion object {
        private const val WATCHDOG_TICK_MS = 10_000L
        private const val WAKE_GAP_THRESHOLD_MS = 30_000L
        private const val PROBE_BASE_MS = 2_000L
        private const val PROBE_MAX_MS = 30_000L

        /** A gap past one tick + threshold indicates the process was suspended (sleep/lock). */
        fun isWakeGap(gapMs: Long): Boolean = gapMs > WATCHDOG_TICK_MS + WAKE_GAP_THRESHOLD_MS

        fun getInstanceOrNull(): NetworkProbe? =
            ApplicationManager.getApplication()?.getService(NetworkStateService::class.java)
    }
}
