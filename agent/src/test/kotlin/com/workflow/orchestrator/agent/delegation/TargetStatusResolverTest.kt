package com.workflow.orchestrator.agent.delegation

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Unit tests for [TargetStatusResolver].
 *
 * The pure [TargetStatusResolver.resolveTargetStatus] is tested for all four
 * cases; [TargetStatusResolver.dualProbeStatus] is tested with an injectable
 * ping function so no real sockets are needed.
 */
class TargetStatusResolverTest {

    // ── resolveTargetStatus (pure) ────────────────────────────────────────────

    @Test
    fun `missing when path does not exist`() {
        assertEquals(
            TargetStatusResolver.TargetStatus.MISSING,
            TargetStatusResolver.resolveTargetStatus(
                exists = false,
                delegationReachable = false,
                doorbellReachable = false,
            ),
        )
    }

    @Test
    fun `running when delegation socket is reachable`() {
        assertEquals(
            TargetStatusResolver.TargetStatus.RUNNING,
            TargetStatusResolver.resolveTargetStatus(
                exists = true,
                delegationReachable = true,
                doorbellReachable = false,
            ),
        )
    }

    @Test
    fun `available when only doorbell is reachable — running IDE with inbound off`() {
        // THE BUG CASE: IDE is running, inbound delegation OFF.
        // Delegation socket is not bound, but doorbell IS always bound.
        // Must be AVAILABLE, not CLOSED.
        assertEquals(
            TargetStatusResolver.TargetStatus.AVAILABLE,
            TargetStatusResolver.resolveTargetStatus(
                exists = true,
                delegationReachable = false,
                doorbellReachable = true,
            ),
        )
    }

    @Test
    fun `closed when neither socket reachable but path exists`() {
        assertEquals(
            TargetStatusResolver.TargetStatus.CLOSED,
            TargetStatusResolver.resolveTargetStatus(
                exists = true,
                delegationReachable = false,
                doorbellReachable = false,
            ),
        )
    }

    // ── resolveTargetStatusString (string variant for list_targets JSON) ──────

    @Test
    fun `string variant returns expected strings for all four states`() {
        assertEquals("missing", TargetStatusResolver.resolveTargetStatusString(false, false, false))
        assertEquals("running", TargetStatusResolver.resolveTargetStatusString(true, true, false))
        assertEquals("available", TargetStatusResolver.resolveTargetStatusString(true, false, true))
        assertEquals("closed", TargetStatusResolver.resolveTargetStatusString(true, false, false))
    }

    // ── dualProbeStatus (with injectable ping) ────────────────────────────────

    @Test
    fun `dualProbeStatus returns MISSING for non-existent path`(@TempDir tmp: Path) = runBlocking {
        val nonExistent = tmp.resolve("does-not-exist")
        val status = TargetStatusResolver.dualProbeStatus(nonExistent, pingFn = { null })
        assertEquals(TargetStatusResolver.TargetStatus.MISSING, status)
    }

    @Test
    fun `dualProbeStatus returns RUNNING when delegation socket pongs`(@TempDir tmp: Path) = runBlocking {
        // tmp itself exists on disk
        val pings = ArrayList<Path>()
        val status = TargetStatusResolver.dualProbeStatus(tmp) { socket ->
            pings.add(socket)
            if (socket.toString().endsWith(".sock")) "pong" else null
        }
        assertEquals(TargetStatusResolver.TargetStatus.RUNNING, status)
        // Doorbell must not be probed when delegation socket already responds
        assertEquals(1, pings.size, "Only delegation socket should be probed when it responds")
        assert(pings.first().toString().endsWith(".sock") && !pings.first().toString().endsWith(".doorbell.sock")) {
            "First probe should be the delegation socket, not the doorbell: ${pings.first()}"
        }
    }

    @Test
    fun `dualProbeStatus returns AVAILABLE when only doorbell pongs — the bug case`(@TempDir tmp: Path) = runBlocking {
        // THE KEY REGRESSION TEST:
        // Delegation socket is down (inbound OFF), doorbell is up (always bound).
        // Old code: CLOSED. Fixed code: AVAILABLE.
        val status = TargetStatusResolver.dualProbeStatus(tmp) { socket ->
            when {
                socket.toString().endsWith(".doorbell.sock") -> "pong"
                else -> null
            }
        }
        assertEquals(
            TargetStatusResolver.TargetStatus.AVAILABLE,
            status,
            "Running IDE with inbound-OFF must be AVAILABLE, not CLOSED",
        )
    }

    @Test
    fun `dualProbeStatus returns CLOSED when both sockets are unreachable`(@TempDir tmp: Path) = runBlocking {
        val status = TargetStatusResolver.dualProbeStatus(tmp, pingFn = { null })
        assertEquals(TargetStatusResolver.TargetStatus.CLOSED, status)
    }

    @Test
    fun `dualProbeStatus skips doorbell probe when delegation socket pongs`(@TempDir tmp: Path) = runBlocking {
        val probed = ArrayList<Path>()
        TargetStatusResolver.dualProbeStatus(tmp) { socket ->
            probed.add(socket)
            "pong" // Both sockets would respond, but doorbell must not be called
        }
        assertEquals(1, probed.size, "Doorbell probe is skipped when delegation socket already pongs")
    }
}
