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
}
