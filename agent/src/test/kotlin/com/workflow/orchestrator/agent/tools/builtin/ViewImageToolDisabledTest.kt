package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.agent.tool.SessionAttachmentAccess
import com.workflow.orchestrator.core.services.SessionDownloadDir
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pins ViewImageTool.execute() behaviour when the visual-support master kill
 * switch is OFF (enableImageInput = false).
 *
 * The master check fires before any path validation, file I/O, or
 * enableToolImageAutoload check. When OFF the tool must return a ToolResult.Error
 * whose content mentions "Visual support is disabled in settings".
 *
 * The existing test infrastructure (project mock causes PluginSettings lookup
 * to throw, which the tool treats as disabled) provides the same trigger path
 * as the production "master OFF" case — the try/catch in the tool's master check
 * catches the service-not-found exception and returns false, which causes the
 * early error return. This is identical to what happens when the actual flag is
 * set to false in a live IDE session.
 */
class ViewImageToolDisabledTest {

    private val tool = ViewImageTool()

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /** A relaxed project mock whose PluginSettings service lookup throws,
     *  causing the master check to treat it as disabled. */
    private fun project(): Project = mockk<Project>(relaxed = true)

    private fun testContext(downloadsDir: Path, store: AttachmentStore) =
        SessionDownloadDir(downloadsDir) + SessionAttachmentAccess(store)

    @Test
    fun `with master OFF, execute returns ToolResult Error containing visual support disabled`(@TempDir tmp: Path) = runTest {
        val sessionDir = Files.createDirectory(tmp.resolve("session"))
        val downloadsDir = Files.createDirectory(sessionDir.resolve("downloads"))
        val store = AttachmentStore(sessionDir)
        // Write a valid PNG so path validation would succeed if we got that far.
        val img = downloadsDir.resolve("chart.png")
        Files.write(img, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        val result = withContext(testContext(downloadsDir, store)) {
            tool.execute(buildJsonObject { put("path", img.toString()) }, project())
        }

        assertTrue(result.isError, "Tool must return an error when visual support is disabled. Got: ${result.content}")
        assertTrue(
            result.content.contains("Visual support is disabled"),
            "Error message must mention 'Visual support is disabled'. Got: ${result.content}",
        )
    }

    @Test
    fun `with master OFF, execute fires before any file access`(@TempDir tmp: Path) = runTest {
        val sessionDir = Files.createDirectory(tmp.resolve("session"))
        val downloadsDir = Files.createDirectory(sessionDir.resolve("downloads"))
        val store = AttachmentStore(sessionDir)
        // Deliberately supply a non-existent file — if the master check fires
        // first the NoSuchFileException path is never reached.
        val missingFile = downloadsDir.resolve("missing.png")

        val result = withContext(testContext(downloadsDir, store)) {
            tool.execute(buildJsonObject { put("path", missingFile.toString()) }, project())
        }

        assertTrue(result.isError, "Tool must return an error when visual support is disabled")
        assertTrue(
            result.content.contains("Visual support is disabled"),
            "Master-disabled error must fire before file-not-found path. Got: ${result.content}",
        )
    }
}
