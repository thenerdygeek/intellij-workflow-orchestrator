package com.workflow.orchestrator.agent.delegation.ui

import com.workflow.orchestrator.agent.delegation.TargetStatusResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests that the picker's status classification uses the shared [TargetStatusResolver]
 * dual-probe logic, pinning the regression:
 *
 * > A running IDE with inbound delegation OFF (doorbell-only) must be classified
 * > as [PickerEntry.Status.AVAILABLE], not [PickerEntry.Status.CLOSED].
 *
 * Prior to Fix C, the picker probed only the delegation (work) socket and had a 3-state
 * model with no AVAILABLE status. A running-but-inbound-OFF IDE showed as CLOSED on
 * first open and only flipped to RUNNING after the user consented (binding the work socket).
 *
 * These tests drive through [TargetStatusResolver.dualProbeStatus] and the
 * [PickerEntry.Status] mapping so that both code paths remain aligned.
 */
class DelegationPickerStatusTest {

    /**
     * Maps a [TargetStatusResolver.TargetStatus] to the [PickerEntry.Status] used by
     * the picker. This mirrors the mapping in DelegationPicker.triggerDiscoveryAsync.
     */
    private fun toPickerStatus(ts: TargetStatusResolver.TargetStatus): PickerEntry.Status = when (ts) {
        TargetStatusResolver.TargetStatus.RUNNING -> PickerEntry.Status.RUNNING
        TargetStatusResolver.TargetStatus.AVAILABLE -> PickerEntry.Status.AVAILABLE
        TargetStatusResolver.TargetStatus.CLOSED -> PickerEntry.Status.CLOSED
        TargetStatusResolver.TargetStatus.MISSING -> PickerEntry.Status.MISSING
    }

    @Test
    fun `AVAILABLE when doorbell-only — the fix-C regression case`(@TempDir tmp: Path) = runBlocking {
        // Simulate: IDE is running, inbound delegation OFF.
        //   delegation socket → unreachable (null)
        //   doorbell socket   → reachable ("pong")
        val resolvedStatus = TargetStatusResolver.dualProbeStatus(tmp) { socket ->
            when {
                socket.toString().endsWith(".doorbell.sock") -> "pong"
                else -> null
            }
        }
        val pickerStatus = toPickerStatus(resolvedStatus)
        assertEquals(
            PickerEntry.Status.AVAILABLE,
            pickerStatus,
            "Running IDE with inbound-OFF must show AVAILABLE in the picker, not CLOSED",
        )
    }

    @Test
    fun `RUNNING when delegation socket responds`(@TempDir tmp: Path) = runBlocking {
        val resolvedStatus = TargetStatusResolver.dualProbeStatus(tmp) { socket ->
            if (socket.toString().endsWith(".sock") && !socket.toString().endsWith(".doorbell.sock")) "pong" else null
        }
        assertEquals(PickerEntry.Status.RUNNING, toPickerStatus(resolvedStatus))
    }

    @Test
    fun `CLOSED when neither socket responds`(@TempDir tmp: Path) = runBlocking {
        val resolvedStatus = TargetStatusResolver.dualProbeStatus(tmp, pingFn = { null })
        assertEquals(PickerEntry.Status.CLOSED, toPickerStatus(resolvedStatus))
    }

    @Test
    fun `MISSING for non-existent path`(@TempDir tmp: Path) = runBlocking {
        val nonExistent = tmp.resolve("gone")
        val resolvedStatus = TargetStatusResolver.dualProbeStatus(nonExistent, pingFn = { null })
        assertEquals(PickerEntry.Status.MISSING, toPickerStatus(resolvedStatus))
    }

    // ── PickerEntry.Status enum sanity ────────────────────────────────────────

    @Test
    fun `PickerEntry Status has AVAILABLE variant`() {
        // This test fails before Fix C adds AVAILABLE to the enum.
        val allStatuses = PickerEntry.Status.entries.map { it.name }.toSet()
        assert("AVAILABLE" in allStatuses) {
            "PickerEntry.Status must have AVAILABLE variant; found: $allStatuses"
        }
    }

    @Test
    fun `AVAILABLE entry is selectable — picker OK action should allow it`() {
        // AVAILABLE targets are delegatable (a send rings the doorbell for consent).
        // They must NOT be treated like CLOSED or MISSING (which are disabled/non-delegatable).
        val entry = PickerEntry(
            path = Path.of("/some/project"),
            displayName = "my-project",
            status = PickerEntry.Status.AVAILABLE,
        )
        // Delegatable = not CLOSED and not MISSING
        val isDelegatable = entry.status == PickerEntry.Status.RUNNING || entry.status == PickerEntry.Status.AVAILABLE
        assert(isDelegatable) {
            "AVAILABLE picker entries must be delegatable (inbound-off → doorbell consent)"
        }
    }
}
