package com.workflow.orchestrator.core.services

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AttachmentSinkContractTest {

    @Test
    fun `store returns ImageRefData with the bytes' SHA256, MIME, and original filename`() = runTest {
        val sink = FakeAttachmentSink()
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG magic
        val ref = sink.store(bytes, "image/png", "screenshot.png")
        assertEquals(64, ref.sha256.length)
        assertEquals("image/png", ref.mime)
        assertEquals(4L, ref.size)
        assertEquals("screenshot.png", ref.originalFilename)
    }
}

private class FakeAttachmentSink : AttachmentSink {
    override suspend fun store(bytes: ByteArray, mime: String, originalFilename: String?): ToolResult.ImageRefData {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes).joinToString("") { "%02x".format(it) }
        return ToolResult.ImageRefData(sha256 = digest, mime = mime, size = bytes.size.toLong(), originalFilename = originalFilename)
    }
}
