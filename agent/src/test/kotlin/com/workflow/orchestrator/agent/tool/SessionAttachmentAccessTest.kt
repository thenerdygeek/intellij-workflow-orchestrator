package com.workflow.orchestrator.agent.tool

import com.workflow.orchestrator.agent.session.AttachmentStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionAttachmentAccessTest {

    @Test
    fun `current returns null when no element is installed`() = runTest {
        assertNull(SessionAttachmentAccess.current())
    }

    @Test
    fun `current returns the installed store`() = runTest {
        val store = mockk<AttachmentStore>()
        withContext(SessionAttachmentAccess(store)) {
            assertSame(store, SessionAttachmentAccess.current())
        }
    }

    @Test
    fun `requireStore throws AttachmentStoreUnavailable when not installed`() = runTest {
        val ex = assertThrows(AttachmentStoreUnavailable::class.java) {
            kotlinx.coroutines.runBlocking { SessionAttachmentAccess.requireStore() }
        }
        assertTrue(ex.message!!.contains("No active session"))
    }
}
