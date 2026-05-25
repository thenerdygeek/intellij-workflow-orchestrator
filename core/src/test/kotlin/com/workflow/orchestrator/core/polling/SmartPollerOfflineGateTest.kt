package com.workflow.orchestrator.core.polling

import com.workflow.orchestrator.core.network.NetworkProbe
import com.workflow.orchestrator.core.network.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SmartPollerOfflineGateTest {

    private class FakeProbe(initial: NetworkState) : NetworkProbe {
        private val s = MutableStateFlow(initial)
        override val state: StateFlow<NetworkState> = s
        fun setOnline() { s.value = NetworkState.ONLINE }
        override fun reportFailure(targetUrl: String) {}
        override fun reportSuccess() { s.value = NetworkState.ONLINE }
        override suspend fun checkNow(targetUrl: String?) = s.value
        override suspend fun awaitOnline(timeoutMs: Long): Boolean { s.first { it == NetworkState.ONLINE }; return true }
    }

    @Test
    fun `does not poll while offline, polls after reconnect`() = runTest {
        val calls = AtomicInteger(0)
        val probe = FakeProbe(NetworkState.OFFLINE)
        val poller = SmartPoller(
            name = "test",
            baseIntervalMs = 1_000,
            maxIntervalMs = 10_000,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            action = { calls.incrementAndGet(); false },
            networkProbe = probe,
        )
        poller.start()
        testScheduler.runCurrent()
        assertEquals(0, calls.get()) // gated while OFFLINE
        probe.setOnline()
        // Bounded advance — the poller loops forever, so advanceUntilIdle() would never return.
        // 2x baseInterval covers the resume stagger (0..baseInterval) plus the first poll.
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        poller.stop()
        org.junit.jupiter.api.Assertions.assertTrue(calls.get() >= 1) // resumed after reconnect
    }
}
