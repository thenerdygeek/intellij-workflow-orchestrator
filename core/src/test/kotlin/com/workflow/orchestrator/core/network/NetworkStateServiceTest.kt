package com.workflow.orchestrator.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkStateServiceTest {

    private class FakeProbe(@Volatile var reachable: Boolean) : ReachabilityProbe {
        override suspend fun isReachable(targetUrl: String) = reachable
    }

    @Test
    fun `reportFailure flips OFFLINE, reportSuccess flips ONLINE`() = runTest {
        val svc = NetworkStateService(CoroutineScope(StandardTestDispatcher(testScheduler)), FakeProbe(false))
        assertEquals(NetworkState.ONLINE, svc.state.value)
        svc.reportFailure("https://sg.example.com")
        assertEquals(NetworkState.OFFLINE, svc.state.value)
        svc.reportSuccess()
        assertEquals(NetworkState.ONLINE, svc.state.value)
    }

    @Test
    fun `checkNow returns OFFLINE when probe unreachable and ONLINE when reachable`() = runTest {
        val probe = FakeProbe(false)
        val svc = NetworkStateService(CoroutineScope(StandardTestDispatcher(testScheduler)), probe)
        assertEquals(NetworkState.OFFLINE, svc.checkNow("https://sg.example.com"))
        probe.reachable = true
        assertEquals(NetworkState.ONLINE, svc.checkNow("https://sg.example.com"))
    }

    @Test
    fun `awaitOnline suspends while OFFLINE and resumes on reportSuccess`() = runTest {
        val svc = NetworkStateService(CoroutineScope(StandardTestDispatcher(testScheduler)), FakeProbe(false))
        svc.reportFailure("https://sg.example.com")
        var resumed = false
        val waiter = launch { resumed = svc.awaitOnline(60_000); }
        testScheduler.runCurrent()
        assertFalse(resumed) // still suspended
        svc.reportSuccess()
        testScheduler.runCurrent()
        waiter.join()
        assertTrue(resumed)
    }

    @Test
    fun `isWakeGap true only past tick + threshold`() {
        assertFalse(NetworkStateService.isWakeGap(10_000))
        assertTrue(NetworkStateService.isWakeGap(120_000))
    }
}
