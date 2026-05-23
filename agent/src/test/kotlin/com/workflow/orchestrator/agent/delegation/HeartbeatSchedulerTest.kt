package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.core.delegation.DelegationMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatSchedulerTest {

    @Test
    fun `emits Heartbeat every interval until stop`() = runTest {
        val sends = mutableListOf<DelegationMessage>()
        val scheduler = HeartbeatScheduler(
            sessionId = "sess-1",
            scope = this,
            intervalMillis = 60_000L,
            sendMessage = { msg -> sends.add(msg) },
        )

        scheduler.start()
        advanceTimeBy(60_001L)
        advanceTimeBy(60_000L)
        advanceTimeBy(60_000L)
        scheduler.stop()
        advanceTimeBy(120_000L)

        assertEquals(3, sends.size)
        sends.forEach {
            val hb = it as DelegationMessage.Heartbeat
            assertEquals("sess-1", hb.sessionId)
        }
    }

    @Test
    fun `stop is idempotent`() = runTest {
        val scheduler = HeartbeatScheduler(
            sessionId = "sess-2",
            scope = this,
            intervalMillis = 60_000L,
            sendMessage = { },
        )
        scheduler.start()
        scheduler.stop()
        scheduler.stop()
    }

    @Test
    fun `start is a no-op if already started`() = runTest {
        val sends = mutableListOf<DelegationMessage>()
        val scheduler = HeartbeatScheduler(
            sessionId = "sess-3",
            scope = this,
            intervalMillis = 60_000L,
            sendMessage = { msg -> sends.add(msg) },
        )
        scheduler.start()
        scheduler.start()
        advanceTimeBy(60_001L)
        scheduler.stop()
        assertEquals(1, sends.size)
    }
}
