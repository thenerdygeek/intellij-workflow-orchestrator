package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.agent.tool.SessionAttachmentAccess
import com.workflow.orchestrator.core.services.SessionDownloadDir
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [ViewImageTool] — P2T6 of the Document Reader full-coverage initiative.
 *
 * The tool is exercised via direct coroutine context injection (same pattern as
 * [JiraDownloadAttachmentImageTest]), which avoids the need for a live IntelliJ
 * Application instance.
 */
class ViewImageToolTest {

    private val tool = ViewImageTool()

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Minimal PNG header bytes (valid enough for the store, not for an actual viewer). */
    private fun pngBytes() = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5, 6, 7, 8
    )

    private fun writeImage(dir: Path, filename: String, bytes: ByteArray): Path {
        val f = dir.resolve(filename)
        Files.write(f, bytes)
        return f
    }

    /** Build the coroutine context for a test session: SessionDownloadDir + SessionAttachmentAccess. */
    private fun testContext(downloadsDir: Path, store: AttachmentStore) =
        SessionDownloadDir(downloadsDir) + SessionAttachmentAccess(store)

    /** Project mock whose [PluginSettings] resolves to master ON + autoload OFF.
     *
     *  Master ON lets the tool body pass its first guard (the visual-support kill
     *  switch); autoload OFF makes the tool return the non-error "autoload
     *  disabled" message — matching the behaviour these tests were written
     *  against before the kill switch landed. */
    private fun project(): Project {
        val settings = PluginSettings().apply {
            state.enableImageInput = true
            state.enableToolImageAutoload = false
        }
        return mockk<Project>(relaxed = true).also {
            every { it.getService(PluginSettings::class.java) } returns settings
        }
    }

    // ── Happy path (autoload disabled in test — settings mock not injected) ──────

    @Test
    fun `view_image returns error when path argument is missing`(@TempDir tmp: Path) = runTest {
        val sessionDir = Files.createDirectory(tmp.resolve("session"))
        val downloadsDir = Files.createDirectory(sessionDir.resolve("downloads"))
        val store = AttachmentStore(sessionDir)

        val result = withContext(testContext(downloadsDir, store)) {
            tool.execute(buildJsonObject { }, project())
        }

        assertTrue(result.isError)
        assertTrue(result.content.contains("path"))
    }

    @Test
    fun `view_image returns error when no active session in coroutine context`(@TempDir tmp: Path) = runTest {
        val result = tool.execute(
            buildJsonObject { put("path", tmp.resolve("someimage.png").toString()) },
            project()
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("active session") || result.content.contains("downloads dir"))
    }

    @Test
    fun `view_image rejects missing file with a descriptive error`(@TempDir tmp: Path) = runTest {
        val sessionDir = Files.createDirectory(tmp.resolve("session"))
        val downloadsDir = Files.createDirectory(sessionDir.resolve("downloads"))
        val store = AttachmentStore(sessionDir)
        val missingPath = downloadsDir.resolve("nonexistent.png").toString()

        val result = withContext(testContext(downloadsDir, store)) {
            tool.execute(buildJsonObject { put("path", missingPath) }, project())
        }

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found") || result.content.contains("nonexistent.png"))
    }

    @Test
    fun `view_image rejects unsupported MIME types`(@TempDir tmp: Path) = runTest {
        val sessionDir = Files.createDirectory(tmp.resolve("session"))
        val downloadsDir = Files.createDirectory(sessionDir.resolve("downloads"))
        val store = AttachmentStore(sessionDir)
        val pdfFile = writeImage(downloadsDir, "chart.pdf", "%PDF-1.4 fake".toByteArray())

        val result = withContext(testContext(downloadsDir, store)) {
            tool.execute(buildJsonObject { put("path", pdfFile.toString()) }, project())
        }

        assertTrue(result.isError)
        assertTrue(result.content.contains("Unsupported") || result.content.contains("MIME"))
    }

    @Test
    fun `view_image rejects unsupported tiff extension`(@TempDir tmp: Path) = runTest {
        val sessionDir = Files.createDirectory(tmp.resolve("session"))
        val downloadsDir = Files.createDirectory(sessionDir.resolve("downloads"))
        val store = AttachmentStore(sessionDir)
        val tiff = writeImage(downloadsDir, "diagram.tiff", byteArrayOf(0x49, 0x49, 0x2A, 0x00))

        val result = withContext(testContext(downloadsDir, store)) {
            tool.execute(buildJsonObject { put("path", tiff.toString()) }, project())
        }

        assertTrue(result.isError)
        assertTrue(result.content.contains("MIME") || result.content.contains("Unsupported"))
    }

    // ── Security boundary tests ───────────────────────────────────────────────

    @Test
    fun `view_image rejects paths outside the session downloads dir`(@TempDir tmp: Path) = runTest {
        val sessionDir = Files.createDirectory(tmp.resolve("session"))
        val downloadsDir = Files.createDirectory(sessionDir.resolve("downloads"))
        val store = AttachmentStore(sessionDir)

        // A file in the session root — NOT under downloads/
        val outsideFile = sessionDir.resolve("api_conversation_history.json")
        Files.writeString(outsideFile, "{}")

        val result = withContext(testContext(downloadsDir, store)) {
            tool.execute(buildJsonObject { put("path", outsideFile.toString()) }, project())
        }

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("outside") || result.content.contains("downloads"),
            "Expected rejection for path outside downloads/. Got: ${result.content}"
        )
    }

    @Test
    fun `view_image rejects absolute traversal outside downloads`(@TempDir tmp: Path) = runTest {
        val sessionDir = Files.createDirectory(tmp.resolve("session"))
        val downloadsDir = Files.createDirectory(sessionDir.resolve("downloads"))
        val store = AttachmentStore(sessionDir)

        val etcPasswd = "/etc/passwd"
        val result = withContext(testContext(downloadsDir, store)) {
            tool.execute(buildJsonObject { put("path", etcPasswd) }, project())
        }

        assertTrue(result.isError)
    }

    @Test
    fun `view_image rejects symlink escape from inside downloads to outside`(@TempDir tmp: Path) = runTest {
        val sessionDir = Files.createDirectory(tmp.resolve("session"))
        val downloadsDir = Files.createDirectory(sessionDir.resolve("downloads"))
        val store = AttachmentStore(sessionDir)

        // Create a target file outside the session
        val escapedTarget = tmp.resolve("secret.txt")
        Files.writeString(escapedTarget, "top secret")

        // Create a symlink inside downloads pointing outside
        val symlink = downloadsDir.resolve("tricky.png")
        try {
            Files.createSymbolicLink(symlink, escapedTarget)
        } catch (_: UnsupportedOperationException) {
            // Symlinks not supported on this platform — skip
            return@runTest
        }

        val result = withContext(testContext(downloadsDir, store)) {
            tool.execute(buildJsonObject { put("path", symlink.toString()) }, project())
        }

        assertTrue(result.isError, "Symlink escape should be rejected. Got: ${result.content}")
    }

    // ── Autoload-disabled path ────────────────────────────────────────────────

    @Test
    fun `view_image with autoload disabled returns text message not imageRefs`(@TempDir tmp: Path) = runTest {
        val sessionDir = Files.createDirectory(tmp.resolve("session"))
        val downloadsDir = Files.createDirectory(sessionDir.resolve("downloads"))
        val store = AttachmentStore(sessionDir)
        val img = writeImage(downloadsDir, "figure.png", pngBytes())

        // Project mock causes PluginSettings.getInstance() to throw → tool treats as disabled
        val result = withContext(testContext(downloadsDir, store)) {
            tool.execute(buildJsonObject { put("path", img.toString()) }, project())
        }

        // Result should be a non-error informational message (autoload disabled)
        assertFalse(result.isError, "Disabled-autoload should not be an error. Content: ${result.content}")
        assertTrue(result.imageRefs.isEmpty(), "No imageRefs should be attached when autoload is disabled")
        assertTrue(
            result.content.contains("disabled") || result.content.contains("figure.png"),
            "Content should mention the file or the disabled state. Got: ${result.content}"
        )
    }

    // ── PathValidator.resolveAndValidateForSessionDownloads tests ─────────────

    @Test
    fun `resolveAndValidateForSessionDownloads accepts valid file under downloads`(@TempDir tmp: Path) {
        val sessionDir = tmp.resolve("session").also { Files.createDirectory(it) }
        val downloadsDir = sessionDir.resolve("downloads").also { Files.createDirectory(it) }
        val imgFile = downloadsDir.resolve("fig.png")
        Files.write(imgFile, pngBytes())

        val validated = PathValidator.resolveAndValidateForSessionDownloads(imgFile.toString(), sessionDir)
        assertEquals(imgFile.toRealPath(), validated)
    }

    @Test
    fun `resolveAndValidateForSessionDownloads rejects path outside downloads`(@TempDir tmp: Path) {
        val sessionDir = tmp.resolve("session").also { Files.createDirectory(it) }
        val outsideFile = sessionDir.resolve("sessions.json").also { Files.writeString(it, "{}") }
        Files.createDirectory(sessionDir.resolve("downloads"))

        val ex = assertThrows(SecurityException::class.java) {
            PathValidator.resolveAndValidateForSessionDownloads(outsideFile.toString(), sessionDir)
        }
        assertTrue(ex.message!!.contains("outside") || ex.message!!.contains("downloads"))
    }

    @Test
    fun `resolveAndValidateForSessionDownloads rejects missing file`(@TempDir tmp: Path) {
        val sessionDir = tmp.resolve("session").also { Files.createDirectory(it) }
        Files.createDirectory(sessionDir.resolve("downloads"))
        val missing = sessionDir.resolve("downloads/ghost.png")

        assertThrows(java.nio.file.NoSuchFileException::class.java) {
            PathValidator.resolveAndValidateForSessionDownloads(missing.toString(), sessionDir)
        }
    }

    @Test
    fun `resolveAndValidateForSessionDownloads rejects blank path`(@TempDir tmp: Path) {
        val sessionDir = tmp.resolve("session").also { Files.createDirectory(it) }
        Files.createDirectory(sessionDir.resolve("downloads"))

        assertThrows(IllegalArgumentException::class.java) {
            PathValidator.resolveAndValidateForSessionDownloads("   ", sessionDir)
        }
    }

    @Test
    fun `resolveAndValidateForSessionDownloads rejects directory not regular file`(@TempDir tmp: Path) {
        val sessionDir = tmp.resolve("session").also { Files.createDirectory(it) }
        val downloadsDir = sessionDir.resolve("downloads").also { Files.createDirectory(it) }
        val subDir = downloadsDir.resolve("subdir").also { Files.createDirectory(it) }

        val ex = assertThrows(SecurityException::class.java) {
            PathValidator.resolveAndValidateForSessionDownloads(subDir.toString(), sessionDir)
        }
        assertTrue(ex.message!!.contains("regular file"))
    }
}
