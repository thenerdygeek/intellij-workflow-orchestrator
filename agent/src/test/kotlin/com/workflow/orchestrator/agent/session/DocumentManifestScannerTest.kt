package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DocumentManifestScannerTest {

    @TempDir lateinit var sessionDir: Path

    private fun write(rel: String, content: String = "x"): Path {
        val p = sessionDir.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, content)
        return p
    }

    @Test
    fun `scans attachments stripping the sha prefix and downloads with real names, excluding internal dirs`() {
        val att = write("attachments/files/a1b2c3d4-design.pdf")
        write("attachments/files/e5f6a7b8-notes.docx.tmp.123.456") // scratch — ignored
        val dl = write("downloads/jira-10042/requirements.pdf")
        // internal dirs that must be excluded:
        write("downloads/document-cache/abc123/content.md")
        write("downloads/tool-output/run_command-1-output.txt")
        write("downloads/document-9f8e7d/image-aabbcc.png")

        val docs = DocumentManifestScanner.scan(sessionDir)
        val names = docs.map { it.displayName }.toSet()

        assertTrue(names.contains("design.pdf"), "attachment name should drop the 8-hex prefix; got $names")
        assertTrue(names.contains("requirements.pdf"), "download name should be the real filename; got $names")
        assertTrue(names.none { it.contains("content.md") || it.contains("output.txt") || it.contains("image-") },
            "internal dirs must be excluded; got $names")
        assertTrue(names.none { it.endsWith(".tmp") || it.contains(".tmp.") }, "scratch files excluded; got $names")

        // Each doc carries the real absolute path.
        val designDoc = docs.first { it.displayName == "design.pdf" }
        assertEquals(att.toAbsolutePath().toString(), designDoc.absolutePath)
        val reqDoc = docs.first { it.displayName == "requirements.pdf" }
        assertEquals(dl.toAbsolutePath().toString(), reqDoc.absolutePath)
    }

    @Test
    fun `returns empty for a session dir with no attachments or downloads`() {
        assertEquals(emptyList<SessionDocument>(), DocumentManifestScanner.scan(sessionDir))
    }
}
