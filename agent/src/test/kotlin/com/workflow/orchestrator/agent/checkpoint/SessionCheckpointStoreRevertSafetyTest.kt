package com.workflow.orchestrator.agent.checkpoint

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Security tests for E3: checkpoint revert path validation.
 *
 * `revertToMessage` and `revertFileToBaseline` must validate that every path from
 * meta.json lies within the declared project root. Paths outside the root (whether
 * absolute out-of-tree paths or symlinks) must be skipped and surfaced rather than
 * silently written to.
 *
 * Audit finding: agent-runtime:F-3.
 */
class SessionCheckpointStoreRevertSafetyTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /** Write a meta.json directly into checkpoints/msg-{ts}/ for test injection. */
    private fun injectMeta(sessionDir: File, messageTs: Long, meta: CheckpointMeta) {
        val msgDir = File(sessionDir, "checkpoints/msg-$messageTs")
        msgDir.mkdirs()
        File(msgDir, "meta.json").writeText(json.encodeToString(meta))
        File(msgDir, "files").mkdirs()
    }

    /** Plant a fake snapshot at the path that the revert would look for. */
    private fun plantSnapshot(sessionDir: File, messageTs: Long, absolutePath: String, content: String) {
        val snapRelative = absolutePath.replace('\\', '/').trimStart('/')
        val snapFile = File(File(sessionDir, "checkpoints/msg-$messageTs/files"), snapRelative)
        snapFile.parentFile.mkdirs()
        snapFile.writeText(content)
    }

    // ── revertToMessage ───────────────────────────────────────────────────────

    @Test
    fun `revertToMessage skips path outside project root`(
        @TempDir sessionTmp: Path,
        @TempDir projectRoot: Path,
    ) {
        val sessionDir = sessionTmp.toFile()
        val store = SessionCheckpointStore(sessionDir = sessionDir, projectRoot = projectRoot.toFile())

        val sentinelDir = Files.createTempDirectory("sentinel-${UUID.randomUUID()}")
        val sentinelFile = sentinelDir.resolve("sensitive.txt").toFile()
        sentinelFile.writeText("this must not be touched")

        // Inject meta.json with the sentinel path as a touchedPath
        val meta = CheckpointMeta(
            messageTs = 100L,
            userText = "the user typed this",
            createdAt = System.currentTimeMillis(),
            touchedPaths = listOf(sentinelFile.absolutePath),
        )
        injectMeta(sessionDir, 100L, meta)
        plantSnapshot(sessionDir, 100L, sentinelFile.absolutePath, "attacker content")

        val result = store.revertToMessage(100L)

        assertFalse(
            result.restoredFiles.contains(sentinelFile.absolutePath),
            "out-of-root path must NOT appear in restoredFiles"
        )
        assertTrue(
            result.skippedPaths.contains(sentinelFile.absolutePath),
            "out-of-root path must appear in skippedPaths — got skippedPaths=${result.skippedPaths}"
        )
        // Critical: sentinel is unchanged
        assertTrue(
            sentinelFile.readText() == "this must not be touched",
            "sentinel content must be unchanged — got: '${sentinelFile.readText()}'"
        )

        sentinelDir.toFile().deleteRecursively()
    }

    @Test
    fun `revertToMessage skips created path outside project root`(
        @TempDir sessionTmp: Path,
        @TempDir projectRoot: Path,
    ) {
        val sessionDir = sessionTmp.toFile()
        val store = SessionCheckpointStore(sessionDir = sessionDir, projectRoot = projectRoot.toFile())

        val outsideFile = Files.createTempFile("outside-${UUID.randomUUID()}", ".kt").toFile()
        outsideFile.writeText("outside content")

        val meta = CheckpointMeta(
            messageTs = 200L,
            userText = "msg",
            createdAt = System.currentTimeMillis(),
            createdPaths = listOf(outsideFile.absolutePath),
        )
        injectMeta(sessionDir, 200L, meta)

        val result = store.revertToMessage(200L)

        assertFalse(
            result.deletedFiles.contains(outsideFile.absolutePath),
            "out-of-root created path must NOT be deleted"
        )
        assertTrue(
            result.skippedPaths.contains(outsideFile.absolutePath),
            "out-of-root created path must be in skippedPaths"
        )
        assertTrue(outsideFile.exists(), "outside file must still exist")

        outsideFile.delete()
    }

    // ── revertFileToBaseline ──────────────────────────────────────────────────

    @Test
    fun `revertFileToBaseline returns skipped=true for path outside project root`(
        @TempDir sessionTmp: Path,
        @TempDir projectRoot: Path,
    ) {
        val sessionDir = sessionTmp.toFile()
        val store = SessionCheckpointStore(sessionDir = sessionDir, projectRoot = projectRoot.toFile())

        val sentinelDir = Files.createTempDirectory("sentinel-${UUID.randomUUID()}")
        val sentinelFile = sentinelDir.resolve("file.txt").toFile()
        sentinelFile.writeText("protected")

        val meta = CheckpointMeta(
            messageTs = 100L,
            userText = "task",
            createdAt = System.currentTimeMillis(),
            touchedPaths = listOf(sentinelFile.absolutePath),
        )
        injectMeta(sessionDir, 100L, meta)
        plantSnapshot(sessionDir, 100L, sentinelFile.absolutePath, "would overwrite sentinel")

        val result = store.revertFileToBaseline(sentinelFile.absolutePath)

        assertFalse(result.reverted, "reverted must be false for out-of-root path")
        assertTrue(result.skipped, "skipped must be true — path was known but rejected by E3")
        assertTrue(
            sentinelFile.readText() == "protected",
            "sentinel content must be unchanged"
        )

        sentinelDir.toFile().deleteRecursively()
    }

    @Test
    fun `revertFileToBaseline successfully reverts an in-project file`(
        @TempDir sessionTmp: Path,
        @TempDir projectRoot: Path,
    ) {
        val sessionDir = sessionTmp.toFile()
        val store = SessionCheckpointStore(sessionDir = sessionDir, projectRoot = projectRoot.toFile())

        val inProjectFile = projectRoot.resolve("src/Foo.kt").toFile()
        inProjectFile.parentFile.mkdirs()
        inProjectFile.writeText("original")

        store.beginUserMessage(100L, "edit Foo")
        store.captureIfFirstTouch(100L, inProjectFile.absolutePath)
        inProjectFile.writeText("edited by agent")

        val result = store.revertFileToBaseline(inProjectFile.absolutePath)

        assertTrue(result.reverted, "in-project file must be reverted successfully")
        assertFalse(result.skipped)
        assertTrue(
            inProjectFile.readText() == "original",
            "file content must be restored to baseline"
        )
    }

    @Test
    fun `revertFileToBaseline returns not-reverted and not-skipped for unknown path`(
        @TempDir sessionTmp: Path,
        @TempDir projectRoot: Path,
    ) {
        val sessionDir = sessionTmp.toFile()
        val store = SessionCheckpointStore(sessionDir = sessionDir, projectRoot = projectRoot.toFile())

        val unknownPath = projectRoot.resolve("src/Unknown.kt").toAbsolutePath().toString()
        val result = store.revertFileToBaseline(unknownPath)

        assertFalse(result.reverted, "unknown path: reverted must be false")
        assertFalse(result.skipped, "unknown path: skipped must be false (not known to store)")
    }

    @Test
    fun `revertFileToBaseline skips symlink target`(
        @TempDir sessionTmp: Path,
        @TempDir projectRoot: Path,
    ) {
        assumeTrue(
            !System.getProperty("os.name").startsWith("Windows"),
            "Symlink creation requires POSIX — skipped on Windows"
        )

        val sessionDir = sessionTmp.toFile()
        val store = SessionCheckpointStore(sessionDir = sessionDir, projectRoot = projectRoot.toFile())

        val outsideDir = Files.createTempDirectory("outside-${UUID.randomUUID()}")
        val outsideFile = outsideDir.resolve("secret.kt").toFile()
        outsideFile.writeText("secret content")

        // Create a symlink inside the project root that points outside
        val symlinkPath = projectRoot.resolve("link.kt")
        Files.createSymbolicLink(symlinkPath, outsideFile.toPath())

        val symlinkAbsolute = symlinkPath.toAbsolutePath().toString()

        // Record the symlink path as a touched file (injected directly)
        val meta = CheckpointMeta(
            messageTs = 100L,
            userText = "touched symlink",
            createdAt = System.currentTimeMillis(),
            touchedPaths = listOf(symlinkAbsolute),
        )
        injectMeta(sessionDir, 100L, meta)
        plantSnapshot(sessionDir, 100L, symlinkAbsolute, "attacker overwrites via symlink")

        val result = store.revertFileToBaseline(symlinkAbsolute)

        assertFalse(result.reverted, "symlink leaf must not be reverted")
        assertTrue(result.skipped, "symlink must be reported as skipped — got skipped=${result.skipped}, reverted=${result.reverted}")
        assertTrue(
            outsideFile.readText() == "secret content",
            "outside file must be unchanged"
        )

        outsideDir.toFile().deleteRecursively()
    }
}
