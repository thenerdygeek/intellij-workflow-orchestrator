package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.core.delegation.DelegationFraming
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.channels.ClosedChannelException

/**
 * Pins the IDE-B detached completion coroutine's exception handling (AgentService
 * ~3250-3286). A delegated session that completes SUCCESSFULLY but whose terminal-result
 * delivery throws [ClosedChannelException] (IDE-A already hung up) is a BENIGN condition —
 * it must NOT be logged as "failed unexpectedly" and must NOT emit a FAILED result.
 *
 * The decision is extracted as a pure helper [AgentService.isBenignDeliveryDisconnect] so it
 * can be tested without standing up a full agent context. These tests MUST fail before the
 * fix (the helper does not exist; the catch block blanket-logs ERROR + emits FAILED) and pass
 * after.
 */
class DelegatedCompletionDeliveryFaultTest {

    @Test
    fun `peer-disconnect terminal delivery is treated as benign`() {
        assertTrue(
            AgentService.isBenignDeliveryDisconnect(ClosedChannelException()),
            "ClosedChannelException during result delivery means IDE-A hung up — benign, not FAILED",
        )
        assertTrue(
            AgentService.isBenignDeliveryDisconnect(IOException("Broken pipe")),
            "broken-pipe during result delivery is a benign peer-disconnect",
        )
    }

    @Test
    fun `genuine failures are NOT treated as benign`() {
        assertFalse(
            AgentService.isBenignDeliveryDisconnect(RuntimeException("real bug")),
            "an arbitrary exception during delivery is a genuine failure — must still log error + FAILED",
        )
        assertFalse(
            AgentService.isBenignDeliveryDisconnect(DelegationFraming.FrameSizeExceeded(99_999_999)),
            "an oversized frame is a genuine producer error, not a benign disconnect",
        )
    }

    @Test
    fun `benign classification reuses the shared core predicate`() {
        // The agent-side predicate must agree with the core DelegationFraming.isPeerDisconnect
        // classification for every case (single source of truth — no divergent copy).
        val benign = listOf(ClosedChannelException(), IOException("Connection reset"))
        val genuine = listOf(RuntimeException("x"), DelegationFraming.FrameSizeExceeded(1))
        benign.forEach {
            assertTrue(
                AgentService.isBenignDeliveryDisconnect(it) == DelegationFraming.isPeerDisconnect(it),
                "agent classification must match core for benign case: $it",
            )
        }
        genuine.forEach {
            assertTrue(
                AgentService.isBenignDeliveryDisconnect(it) == DelegationFraming.isPeerDisconnect(it),
                "agent classification must match core for genuine case: $it",
            )
        }
    }
}
