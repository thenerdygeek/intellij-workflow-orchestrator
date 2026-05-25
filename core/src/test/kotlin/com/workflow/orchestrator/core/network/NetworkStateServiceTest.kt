package com.workflow.orchestrator.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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

    /** Creates a scope backed by the test scheduler that can be cancelled after assertions. */
    private fun makeScope(testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler): Pair<CoroutineScope, Job> {
        val job = Job()
        return Pair(CoroutineScope(StandardTestDispatcher(testScheduler) + job), job)
    }

    @Test
    fun `reportFailure flips OFFLINE, reportSuccess flips ONLINE`() = runTest {
        val (cs, job) = makeScope(testScheduler)
        val svc = NetworkStateService(cs, FakeProbe(false))
        assertEquals(NetworkState.ONLINE, svc.state.value)
        svc.reportFailure("https://sg.example.com")
        assertEquals(NetworkState.OFFLINE, svc.state.value)
        svc.reportSuccess()
        assertEquals(NetworkState.ONLINE, svc.state.value)
        job.cancel() // stop watchdog + probe loop so runTest cleanup terminates
    }

    @Test
    fun `checkNow returns OFFLINE when probe unreachable and ONLINE when reachable`() = runTest {
        val probe = FakeProbe(false)
        val (cs, job) = makeScope(testScheduler)
        val svc = NetworkStateService(cs, probe)
        assertEquals(NetworkState.OFFLINE, svc.checkNow("https://sg.example.com"))
        probe.reachable = true
        assertEquals(NetworkState.ONLINE, svc.checkNow("https://sg.example.com"))
        job.cancel()
    }

    @Test
    fun `awaitOnline suspends while OFFLINE and resumes on reportSuccess`() = runTest {
        val (cs, job) = makeScope(testScheduler)
        val svc = NetworkStateService(cs, FakeProbe(false))
        svc.reportFailure("https://sg.example.com")
        var resumed = false
        val waiter = launch { resumed = svc.awaitOnline(60_000); }
        testScheduler.runCurrent()
        assertFalse(resumed) // still suspended
        svc.reportSuccess()
        testScheduler.runCurrent()
        waiter.join()
        assertTrue(resumed)
        job.cancel() // stop watchdog + probe loop so runTest cleanup terminates
    }

    @Test
    fun `isWakeGap true only past tick + threshold`() {
        assertFalse(NetworkStateService.isWakeGap(10_000))
        assertTrue(NetworkStateService.isWakeGap(120_000))
    }
}
