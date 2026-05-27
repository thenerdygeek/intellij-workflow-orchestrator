package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AttachmentStoreFileTest {
    @Test
    fun `storeFileBlocking writes bytes under attachments-files preserving original name`(@TempDir dir: Path) {
        val store = AttachmentStore(dir)
        val bytes = "hello pdf".toByteArray()

        val path = store.storeFileBlocking(bytes, "My Spec.pdf")

        assertTrue(Files.exists(path), "file should exist on disk")
        assertEquals("hello pdf", Files.readString(path))
        assertTrue(path.toString().contains("attachments"), "must live under attachments/")
        assertTrue(path.fileName.toString().endsWith("My_Spec.pdf"), "original name (sanitized) preserved for read_document")
    }

    @Test
    fun `storeFileBlocking sanitizes path separators in filename`(@TempDir dir: Path) {
        val store = AttachmentStore(dir)
        val path = store.storeFileBlocking("x".toByteArray(), "../../etc/passwd")
        assertTrue(path.parent.fileName.toString() == "files", "must stay inside attachments/files")
        assertTrue(!path.fileName.toString().contains("/") && !path.fileName.toString().contains(".."))
    }
}
