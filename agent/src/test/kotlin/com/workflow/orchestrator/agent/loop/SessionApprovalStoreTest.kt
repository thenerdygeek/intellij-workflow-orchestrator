package com.workflow.orchestrator.agent.loop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SessionApprovalStoreTest {

    @Test
    fun `approval persists across checks`() {
        val store = SessionApprovalStore()
        store.approve("edit_file")
        assertTrue(store.isApproved("edit_file"))
        assertTrue(store.isApproved("edit_file"))  // Still true
    }

    @Test
    fun `unapproved tool returns false`() {
        val store = SessionApprovalStore()
        assertFalse(store.isApproved("edit_file"))
    }

    @Test
    fun `clear resets all approvals`() {
        val store = SessionApprovalStore()
        store.approve("edit_file")
        store.approve("create_file")
        store.clear()
        assertFalse(store.isApproved("edit_file"))
        assertFalse(store.isApproved("create_file"))
    }

    @Test
    fun `approvedTools returns snapshot`() {
        val store = SessionApprovalStore()
        store.approve("edit_file")
        store.approve("create_file")
        assertEquals(setOf("edit_file", "create_file"), store.approvedTools())
    }

    @Test
    fun `thread-safe concurrent access`() = runTest {
        val store = SessionApprovalStore()
        coroutineScope {
            (0 until 100).map { i ->
                async(Dispatchers.Default) { store.approve("tool_$i") }
            }.forEach { it.await() }
        }
        repeat(100) { assertTrue(store.isApproved("tool_$it")) }
    }
}
