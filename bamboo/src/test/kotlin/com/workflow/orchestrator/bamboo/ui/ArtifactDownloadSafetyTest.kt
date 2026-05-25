package com.workflow.orchestrator.bamboo.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests for [StageDetailPanel.safeArtifactBasename] and
 * [StageDetailPanel.isContainedIn] which guard the artifact-download path
 * against server-supplied path traversal names.
 * (Audit finding bamboo:F-12)
 */
class ArtifactDownloadSafetyTest {

    @TempDir
    lateinit var tempDir: Path

    // ── safeArtifactBasename ──────────────────────────────────────────────────

    @Test
    fun `normal artifact name is returned unchanged`() {
        assertEquals("report.html", StageDetailPanel.safeArtifactBasename("report.html"))
    }

    @Test
    fun `artifact name with forward-slash traversal returns only final component`() {
        val result = StageDetailPanel.safeArtifactBasename("../../evil.sh")
        assertNotNull(result)
        assertEquals("evil.sh", result)
    }

    @Test
    fun `artifact name with backslash traversal returns only final component`() {
        val result = StageDetailPanel.safeArtifactBasename("..\\..\\evil.bat")
        assertNotNull(result)
        assertEquals("evil.bat", result)
    }

    @Test
    fun `pure dot-dot returns null`() {
        assertNull(StageDetailPanel.safeArtifactBasename(".."))
    }

    @Test
    fun `single dot returns null`() {
        assertNull(StageDetailPanel.safeArtifactBasename("."))
    }

    @Test
    fun `blank name returns null`() {
        assertNull(StageDetailPanel.safeArtifactBasename("   "))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(StageDetailPanel.safeArtifactBasename(""))
    }

    @Test
    fun `deeply nested traversal with mixed separators returns only leaf`() {
        val result = StageDetailPanel.safeArtifactBasename("a/b/../../.ssh/authorized_keys")
        assertNotNull(result)
        assertEquals("authorized_keys", result)
    }

    // ── isContainedIn ─────────────────────────────────────────────────────────

    @Test
    fun `normal file inside download dir passes containment check`() {
        val dir = tempDir.toFile()
        val target = File(dir, "report.html")
        assertTrue(StageDetailPanel.isContainedIn(target, dir))
    }

    @Test
    fun `file outside download dir fails containment check`() {
        val dir = tempDir.resolve("download").toFile().also { it.mkdirs() }
        val outside = tempDir.resolve("other").resolve("evil.sh").toFile()
        assertFalse(StageDetailPanel.isContainedIn(outside, dir))
    }

    @Test
    fun `sibling directory sharing prefix fails containment check`() {
        val dir = tempDir.resolve("download").toFile().also { it.mkdirs() }
        // "download-extra" starts with "download" but is not inside it
        val sibling = tempDir.resolve("download-extra").resolve("file.txt").toFile()
        assertFalse(StageDetailPanel.isContainedIn(sibling, dir))
    }

    @Test
    fun `canonical path resolution prevents dot-dot escape when combined`() {
        val dir = tempDir.resolve("download").toFile().also { it.mkdirs() }
        // Artificially build a path that tries to escape via ".."
        val tricky = File(dir, "../../../etc/passwd")
        // canonicalPath will resolve this to outside tempDir/download
        assertFalse(StageDetailPanel.isContainedIn(tricky, dir))
    }

    // ── End-to-end: safeBasename + isContainedIn pipeline ────────────────────

    @Test
    fun `traversal name yields safe basename that passes containment`() {
        val dir = tempDir.resolve("artifacts").toFile().also { it.mkdirs() }
        val rawName = "../../.bashrc"

        val safe = StageDetailPanel.safeArtifactBasename(rawName)!!
        val target = File(dir, safe)

        // The safe basename must stay inside the download dir
        assertTrue(StageDetailPanel.isContainedIn(target, dir))
        // And the final name must not contain path separators
        assertFalse(safe.contains('/') || safe.contains('\\'))
    }
}
