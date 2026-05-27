package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AttachmentIngestServiceTest {
    private lateinit var dir: Path
    private val chips = mutableListOf<AttachmentIngestService.ChipMeta>()
    private val toasts = mutableListOf<Pair<String, String>>()
    private var filesAttachedCalls = 0

    private fun service(
        imageEnabled: Boolean = true,
        imageMaxBytes: Long = 5_242_880,
        fileMaxBytes: Long = 52_428_800,
        imagesPerTurn: Int = 2,
        filesPerTurn: Int = 5,
    ) = AttachmentIngestService(
        sessionDirProvider = { dir },
        settingsProvider = {
            AttachmentIngestService.Settings(
                imageEnabled = imageEnabled,
                imageMimeWhitelist = setOf("image/png", "image/jpeg", "image/webp"),
                imageMaxBytes = imageMaxBytes,
                fileMaxBytes = fileMaxBytes,
                imagesPerTurnCap = imagesPerTurn,
                filesPerTurnCap = filesPerTurn,
            )
        },
        onChip = { chips += it },
        onToast = { msg, kind -> toasts += msg to kind },
        onFilesAttached = { filesAttachedCalls++ },
    )

    @BeforeEach fun setup(@TempDir d: Path) { dir = d; chips.clear(); toasts.clear(); filesAttachedCalls = 0 }

    private fun file(name: String, mime: String, bytes: ByteArray) =
        AttachmentIngestService.IncomingFile(name, mime, bytes)

    @Test fun `image is stored and emits image chip`() {
        service().ingest(listOf(file("a.png", "image/png", byteArrayOf(1, 2, 3))))
        assertEquals(1, chips.size)
        assertEquals("image", chips[0].kind)
        assertEquals(null, chips[0].path)
    }

    @Test fun `document emits file chip with absolute path and triggers read_document activation`() {
        service().ingest(listOf(file("spec.pdf", "application/pdf", "doc".toByteArray())))
        assertEquals(1, chips.size)
        assertEquals("file", chips[0].kind)
        assertTrue(chips[0].path!!.endsWith("spec.pdf"))
        assertEquals(1, filesAttachedCalls)
    }

    @Test fun `image rejected with toast when visual support disabled`() {
        service(imageEnabled = false).ingest(listOf(file("a.png", "image/png", byteArrayOf(1))))
        assertEquals(0, chips.size)
        assertTrue(toasts.single().first.contains("visual support", ignoreCase = true))
    }

    @Test fun `file over cap rejected with toast`() {
        service(fileMaxBytes = 2).ingest(listOf(file("big.pdf", "application/pdf", byteArrayOf(1, 2, 3))))
        assertEquals(0, chips.size)
        assertTrue(toasts.single().first.contains("too large", ignoreCase = true))
    }

    @Test fun `per-turn file cap enforced across calls until reset`() {
        val s = service(filesPerTurn = 1)
        s.ingest(listOf(file("a.txt", "text/plain", byteArrayOf(1))))
        s.ingest(listOf(file("b.txt", "text/plain", byteArrayOf(2))))
        assertEquals(1, chips.size)
        assertTrue(toasts.any { it.first.contains("per message", ignoreCase = true) })
        s.resetTurn()
        s.ingest(listOf(file("c.txt", "text/plain", byteArrayOf(3))))
        assertEquals(2, chips.size)
    }

    @Test fun `no session dir toasts and skips`() {
        val s = AttachmentIngestService(
            sessionDirProvider = { null },
            settingsProvider = { AttachmentIngestService.Settings(true, setOf("image/png"), 5_242_880, 52_428_800, 2, 5) },
            onChip = { chips += it }, onToast = { m, k -> toasts += m to k }, onFilesAttached = { filesAttachedCalls++ },
        )
        s.ingest(listOf(file("a.txt", "text/plain", byteArrayOf(1))))
        assertEquals(0, chips.size)
        assertTrue(toasts.single().first.contains("Start a chat", ignoreCase = true))
    }

    @Test fun `same file attached twice in a turn emits only one chip`() {
        val s = service()
        s.ingest(listOf(file("a.txt", "text/plain", byteArrayOf(9, 9, 9))))
        s.ingest(listOf(file("a.txt", "text/plain", byteArrayOf(9, 9, 9))))
        assertEquals(1, chips.size)
    }
}
