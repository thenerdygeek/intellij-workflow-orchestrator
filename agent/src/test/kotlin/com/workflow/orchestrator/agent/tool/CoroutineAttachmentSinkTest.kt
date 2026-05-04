package com.workflow.orchestrator.agent.tool

import com.workflow.orchestrator.agent.session.AttachmentRef
import com.workflow.orchestrator.agent.session.AttachmentStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class CoroutineAttachmentSinkTest {

    @Test
    fun `store delegates to the active session AttachmentStore`() = runTest {
        val store = mockk<AttachmentStore>()
        coEvery { store.store(any(), any(), any()) } returns AttachmentRef(
            sha256 = "abc123", mime = "image/png", size = 4, originalFilename = "x.png",
            onDiskPath = Paths.get("/tmp/x.png")
        )

        withContext(SessionAttachmentAccess(store)) {
            val sink = CoroutineAttachmentSink()
            val ref = sink.store(byteArrayOf(1, 2, 3, 4), "image/png", "x.png")
            assertEquals("abc123", ref.sha256)
            assertEquals("image/png", ref.mime)
            assertEquals(4L, ref.size)
            coVerify(exactly = 1) { store.store(any(), "image/png", "x.png") }
        }
    }

    @Test
    fun `store throws when no session is active`() {
        val sink = CoroutineAttachmentSink()
        assertThrows(AttachmentStoreUnavailable::class.java) {
            runBlocking { sink.store(byteArrayOf(1), "image/png", null) }
        }
    }
}
