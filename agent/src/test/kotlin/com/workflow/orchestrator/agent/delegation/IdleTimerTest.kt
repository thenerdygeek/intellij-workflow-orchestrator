package com.workflow.orchestrator.agent.delegation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleTimerTest {

    @Test
    fun `fires onTimeout when lastSeenAt is stale`() = runTest {
        var now = 0L
        val clock = object : Clock { override fun nowMillis() = now }
        var lastSeen = 0L
        var fired = false
        val timer = IdleTimer(
            handleId = "h1",
            scope = this,
            checkIntervalMillis = 1_000L,
            timeoutMillisProvider = { 5_000L },
            clock = clock,
            lastSeenAtProvider = { lastSeen },
            onTimeout = { fired = true },
        )
        timer.start()

        now = 6_000L
        advanceTimeBy(1_500L)

        assertTrue(fired, "IdleTimer should fire when now - lastSeen > timeout")
        timer.stop()
    }

    @Test
    fun `does not fire when lastSeenAt keeps advancing`() = runTest {
        var now = 0L
        val clock = object : Clock { override fun nowMillis() = now }
        var lastSeen = 0L
        var fired = false
        val timer = IdleTimer(
            handleId = "h2",
            scope = this,
            checkIntervalMillis = 1_000L,
            timeoutMillisProvider = { 5_000L },
            clock = clock,
            lastSeenAtProvider = { lastSeen },
            onTimeout = { fired = true },
        )
        timer.start()

        repeat(10) {
            now += 2_000L
            lastSeen = now
            advanceTimeBy(2_000L)
        }
        timer.stop()
        assertFalse(fired)
    }

    @Test
    fun `timeoutMillisProvider returning 0 means no tick fires`() = runTest {
        var fired = false
        val timer = IdleTimer(
            handleId = "h3",
            scope = this,
            checkIntervalMillis = 1_000L,
            timeoutMillisProvider = { 0L },
            clock = SystemClock,
            lastSeenAtProvider = { 0L },
            onTimeout = { fired = true },
        )
        timer.start()
        advanceTimeBy(10_000_000L)
        timer.stop()
        assertFalse(fired)
    }

    @Test
    fun `does not fire when lastSeenAt provider returns null`() = runTest {
        var fired = false
        val timer = IdleTimer(
            handleId = "h4",
            scope = this,
            checkIntervalMillis = 500L,
            timeoutMillisProvider = { 5_000L },
            clock = SystemClock,
            lastSeenAtProvider = { null },
            onTimeout = { fired = true },
        )
        timer.start()
        advanceTimeBy(20_000L)
        timer.stop()
        assertFalse(fired)
    }

    @Test
    fun `provider re-read each tick — disable mid-flight is honored`() = runTest {
        var now = 0L
        var lastSeen = 0L
        var timeoutMillis = 5_000L
        var fired = false
        val clock = object : Clock { override fun nowMillis() = now }
        val timer = IdleTimer(
            handleId = "h5",
            scope = this,
            checkIntervalMillis = 1_000L,
            timeoutMillisProvider = { timeoutMillis },
            clock = clock,
            lastSeenAtProvider = { lastSeen },
            onTimeout = { fired = true },
        )
        timer.start()
        now = 4_000L
        advanceTimeBy(1_500L)
        assertFalse(fired)
        timeoutMillis = 0L
        now = 7_000L
        advanceTimeBy(1_500L)
        assertFalse(fired, "Provider returning 0 mid-flight must suppress the firing tick")
        timer.stop()
    }
}
