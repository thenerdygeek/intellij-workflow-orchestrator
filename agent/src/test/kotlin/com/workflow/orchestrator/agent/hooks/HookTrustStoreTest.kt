package com.workflow.orchestrator.agent.hooks

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for [HookTrustStore] — the per-project SHA-pinned trust store that gates
 * .agent-hooks.json auto-load (audit finding agent-runtime:F-1).
 *
 * HookTrustStore is pure in-memory map logic over its PersistentStateComponent
 * state, so it can be exercised directly without an IntelliJ runtime.
 */
class HookTrustStoreTest {

    private val shaA = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
    private val shaB = "ccddeeff00112233445566778899aabbccddeeff00112233445566778899aabb"

    @Test
    fun `unknown sha returns UNKNOWN`() {
        val store = HookTrustStore()
        assertEquals(HookTrustStore.TrustState.UNKNOWN, store.checkTrust(shaA))
    }

    @Test
    fun `setTrusted then checkTrust returns TRUSTED`() {
        val store = HookTrustStore()
        store.setTrusted(shaA)
        assertEquals(HookTrustStore.TrustState.TRUSTED, store.checkTrust(shaA))
    }

    @Test
    fun `setRejected then checkTrust returns REJECTED`() {
        val store = HookTrustStore()
        store.setRejected(shaA)
        assertEquals(HookTrustStore.TrustState.REJECTED, store.checkTrust(shaA))
    }

    @Test
    fun `setTrusted clears a prior rejection for the same sha`() {
        val store = HookTrustStore()
        store.setRejected(shaA)
        store.setTrusted(shaA)
        assertEquals(HookTrustStore.TrustState.TRUSTED, store.checkTrust(shaA))
        // The rejection must be gone, not merely shadowed.
        assertFalse(store.getState().rejectedShas.containsKey(shaA))
    }

    @Test
    fun `setRejected clears a prior trust for the same sha`() {
        val store = HookTrustStore()
        store.setTrusted(shaA)
        store.setRejected(shaA)
        assertEquals(HookTrustStore.TrustState.REJECTED, store.checkTrust(shaA))
        assertFalse(store.getState().trustedShas.containsKey(shaA))
    }

    @Test
    fun `forget resets a decision to UNKNOWN`() {
        val store = HookTrustStore()
        store.setTrusted(shaA)
        store.forget(shaA)
        assertEquals(HookTrustStore.TrustState.UNKNOWN, store.checkTrust(shaA))
    }

    @Test
    fun `distinct shas have independent decisions`() {
        val store = HookTrustStore()
        store.setTrusted(shaA)
        store.setRejected(shaB)
        assertEquals(HookTrustStore.TrustState.TRUSTED, store.checkTrust(shaA))
        assertEquals(HookTrustStore.TrustState.REJECTED, store.checkTrust(shaB))
    }

    @Test
    fun `state survives getState then loadState round-trip`() {
        val store = HookTrustStore()
        store.setTrusted(shaA)
        store.setRejected(shaB)

        val snapshot = store.state

        val restored = HookTrustStore()
        restored.loadState(snapshot)

        assertEquals(HookTrustStore.TrustState.TRUSTED, restored.checkTrust(shaA))
        assertEquals(HookTrustStore.TrustState.REJECTED, restored.checkTrust(shaB))
    }

    @Test
    fun `prune drops oldest entries when store exceeds 1000`() {
        val store = HookTrustStore()
        // Insert 1101 trusted entries with increasing timestamps via the public API.
        // setTrusted stamps System.currentTimeMillis(), which is monotonic enough here;
        // to make ordering deterministic we seed the state map directly with explicit ts.
        for (i in 0 until 1100) {
            store.getState().trustedShas["sha-old-$i"] = i.toLong()
        }
        // The 1101st insertion via setTrusted triggers pruneIfNeeded (total > 1000).
        store.setTrusted("sha-newest")

        // Oldest 100 (ts 0..99) should be gone; newer ones and the newest remain.
        assertFalse(store.getState().trustedShas.containsKey("sha-old-0"))
        assertFalse(store.getState().trustedShas.containsKey("sha-old-99"))
        assertTrue(store.getState().trustedShas.containsKey("sha-old-100"))
        assertEquals(HookTrustStore.TrustState.TRUSTED, store.checkTrust("sha-newest"))
    }
}
