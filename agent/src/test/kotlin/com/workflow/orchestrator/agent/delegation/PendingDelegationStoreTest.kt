package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PendingDelegationStoreTest {
    @Test fun `write then read returns the request`(@TempDir dir: Path) {
        val store = PendingDelegationStore(dir)
        val req = PendingDelegationRequest("ide1", "backend", "s1", "do X", "n1", System.currentTimeMillis())
        store.write(req)
        val read = store.readFresh(ttlMillis = 300_000)
        assertEquals(listOf(req), read)
    }

    @Test fun `expired requests are filtered and deleted`(@TempDir dir: Path) {
        val store = PendingDelegationStore(dir)
        store.write(PendingDelegationRequest("ide1", "backend", "s1", "x", "n1", System.currentTimeMillis() - 600_000))
        assertTrue(store.readFresh(ttlMillis = 300_000).isEmpty())
        // second read confirms the stale file was deleted
        assertTrue(store.readFresh(ttlMillis = 300_000).isEmpty())
    }

    @Test fun `declined marker is detectable and removable`(@TempDir dir: Path) {
        val store = PendingDelegationStore(dir)
        assertFalse(store.isDeclined("n1"))
        store.markDeclined("n1")
        assertTrue(store.isDeclined("n1"))
        store.clear("n1")
        assertFalse(store.isDeclined("n1"))
    }

    @Test fun `entry older than 5 min but younger than the replay TTL is still returned`(@TempDir dir: Path) {
        // Fix D — the 5-min REPLAY_TTL_MS was shorter than large-repo cold-start indexing, so a
        // surviving pending file (PRIMARY fix) could still be discarded as stale before the consent
        // dialog appeared. With the raised TTL, an entry stamped 10 min ago — older than the OLD
        // 5-min window but well within the new TTL — must still be returned by readFresh.
        val store = PendingDelegationStore(dir)
        val tenMinAgo = System.currentTimeMillis() - 10L * 60 * 1000
        val req = PendingDelegationRequest("ide1", "backend", "s1", "do X", "n1", tenMinAgo)
        store.write(req)
        assertEquals(
            listOf(req),
            store.readFresh(ttlMillis = DelegationDoorbellService.REPLAY_TTL_MS),
            "an entry 10 min old must survive the raised replay TTL",
        )
    }

    @Test fun `entry older than the replay TTL is dropped`(@TempDir dir: Path) {
        // Genuinely stale entries (older than the new TTL) are still GC'd by readFresh.
        val store = PendingDelegationStore(dir)
        val pastTtl = System.currentTimeMillis() - (DelegationDoorbellService.REPLAY_TTL_MS + 60_000L)
        store.write(PendingDelegationRequest("ide1", "backend", "s1", "x", "n1", pastTtl))
        assertTrue(
            store.readFresh(ttlMillis = DelegationDoorbellService.REPLAY_TTL_MS).isEmpty(),
            "an entry beyond the replay TTL must be dropped",
        )
        // confirms the stale file was deleted as a side effect
        assertTrue(store.readFresh(ttlMillis = DelegationDoorbellService.REPLAY_TTL_MS).isEmpty())
    }
}
