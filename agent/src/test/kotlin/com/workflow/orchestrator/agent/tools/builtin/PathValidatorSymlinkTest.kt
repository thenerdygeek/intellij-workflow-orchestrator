package com.workflow.orchestrator.agent.tools.builtin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Security tests for E4: PathValidator symlink TOCTOU resistance.
 *
 * `resolveAndValidate` and `resolveAndValidateForWrite` canonicalize the path and confirm
 * it lives under the project root, but then `LocalFileSystem.findFileByPath` re-resolves
 * at write time. A concurrent symlink swap allows an attacker to escape the project root
 * between the canonical check and the actual write (TOCTOU).
 *
 * The fix walks every path component between the validated root and the leaf; if any
 * component is a symbolic link, validation is rejected before any write occurs.
 *
 * Audit finding: agent-tools:F-5.
 */
class PathValidatorSymlinkTest {

    // ── E4: findSymlinkInPath helper ──────────────────────────────────────────

    @Test
    fun `findSymlinkInPath detects symlink in a path component`(@TempDir tempDir: Path) {
        assumeTrue(
            !System.getProperty("os.name").startsWith("Windows"),
            "Symlink creation requires POSIX — skipped on Windows"
        )
        // Use a real (resolved) project root — toRealPath() resolves /var -> /private/var on macOS
        // so that both rootCanonical and the normalized path share the same prefix.
        val projectRoot = Files.createDirectory(tempDir.resolve("proj")).toRealPath()
        val outsideDir = Files.createTempDirectory("outside-${UUID.randomUUID()}")

        // Create escape/ -> outsideDir (symlink directory inside project root)
        val symlinkDir = projectRoot.resolve("escape")
        Files.createSymbolicLink(symlinkDir, outsideDir)

        // Pass the NORMALIZED (not toRealPath) path. We start from projectRoot which is already
        // canonical, so the path up to 'escape' is real; 'escape' itself remains as a symlink.
        val normalizedPath = symlinkDir.resolve("file.txt").normalize().toString()
        val rootCanonical = projectRoot.toString()  // already real (no deref needed)

        val found = PathValidator.findSymlinkInPath(normalizedPath, rootCanonical)

        assertNotNull(found, "findSymlinkInPath must detect the symlink directory 'escape'")
        assertTrue(found!!.contains("escape"), "reported symlink segment must be the escape/ directory")

        outsideDir.toFile().deleteRecursively()
    }

    @Test
    fun `findSymlinkInPath returns null for a clean non-symlink path`(@TempDir tempDir: Path) {
        val projectRoot = Files.createDirectory(tempDir.resolve("proj")).toRealPath()
        val subDir = Files.createDirectory(projectRoot.resolve("src"))
        val file = subDir.resolve("Main.kt").also { Files.createFile(it) }

        val result = PathValidator.findSymlinkInPath(
            file.normalize().toString(),
            projectRoot.toString(),
        )

        assertNull(result, "clean path must return null — no symlinks found")
    }

    // ── E4: resolveAndValidate (read path) ────────────────────────────────────

    @Test
    fun `resolveAndValidate rejects path through symlink directory`(@TempDir tempDir: Path) {
        assumeTrue(
            !System.getProperty("os.name").startsWith("Windows"),
            "Symlink creation requires POSIX — skipped on Windows"
        )
        val projectRoot = Files.createDirectory(tempDir.resolve("proj"))
        val outsideDir = Files.createTempDirectory("outside-${UUID.randomUUID()}")
        val outsideFile = outsideDir.resolve("target.kt").toFile().also { it.writeText("secret") }

        // plant proj/escape -> outsideDir
        val symlinkDir = projectRoot.resolve("escape")
        Files.createSymbolicLink(symlinkDir, outsideDir)

        val (path, error) = PathValidator.resolveAndValidate(
            symlinkDir.resolve("target.kt").toAbsolutePath().toString(),
            projectRoot.toFile().absolutePath,
        )

        // Security invariant: path through a symlink-escape directory must be REJECTED.
        // The exact error message varies (may say "symlink" or "outside the project directory"
        // depending on which check fires first), but the path must be null and error non-null.
        assertNull(path, "Path through symlink directory must be rejected — got path=$path")
        assertNotNull(error, "A ToolResult error must be returned")
        assertTrue(error!!.isError, "Result must be marked as error")
        // Verify the outside file is never touched (the security property)
        assertTrue(outsideFile.readText() == "secret", "Outside file must not be modified")

        outsideDir.toFile().deleteRecursively()
    }

    @Test
    fun `resolveAndValidate allows clean path inside project root`(@TempDir tempDir: Path) {
        val projectRoot = Files.createDirectory(tempDir.resolve("proj"))
        val srcDir = Files.createDirectory(projectRoot.resolve("src"))
        val file = srcDir.resolve("Main.kt").toFile().also { it.writeText("content") }

        val (path, error) = PathValidator.resolveAndValidate(
            file.absolutePath,
            projectRoot.toFile().absolutePath,
        )

        assertNotNull(path, "Normal path inside project must be allowed")
        assertNull(error, "No error expected for clean path")
    }

    // ── E4: resolveAndValidateForWrite ────────────────────────────────────────

    @Test
    fun `resolveAndValidateForWrite rejects symlink in path components`(
        @TempDir tempDir: Path,
        @TempDir memoryDir: Path,
    ) {
        assumeTrue(
            !System.getProperty("os.name").startsWith("Windows"),
            "Symlink creation requires POSIX — skipped on Windows"
        )
        val projectRoot = Files.createDirectory(tempDir.resolve("proj"))
        val outsideDir = Files.createTempDirectory("outside-${UUID.randomUUID()}")
        val outsideFile = outsideDir.resolve("secret.txt").toFile().also { it.writeText("secret") }

        // plant proj/escape -> outsideDir
        val symlinkDir = projectRoot.resolve("escape")
        Files.createSymbolicLink(symlinkDir, outsideDir)

        val (path, error) = PathValidator.resolveAndValidateForWrite(
            symlinkDir.resolve("file.txt").toAbsolutePath().toString(),
            projectRoot.toFile().absolutePath,
            listOf(memoryDir.toFile().absolutePath),
        )

        // Security invariant: write through a symlink-escape must be REJECTED
        assertNull(path, "Write through symlink directory must be rejected")
        assertNotNull(error)
        assertTrue(error!!.isError)
        // Outside file must be untouched
        assertTrue(outsideFile.readText() == "secret", "Outside file must not be modified")

        outsideDir.toFile().deleteRecursively()
    }

    @Test
    fun `resolveAndValidateForWrite allows clean path inside project`(
        @TempDir tempDir: Path,
        @TempDir memoryDir: Path,
    ) {
        val projectRoot = Files.createDirectory(tempDir.resolve("proj"))
        val srcDir = Files.createDirectory(projectRoot.resolve("src"))
        val file = srcDir.resolve("Foo.kt").toFile().absolutePath

        val (path, error) = PathValidator.resolveAndValidateForWrite(
            file,
            projectRoot.toFile().absolutePath,
            listOf(memoryDir.toFile().absolutePath),
        )

        assertNotNull(path)
        assertNull(error)
    }

    @Test
    fun `resolveAndValidateForWrite rejects symlink in memory dir path components`(
        @TempDir projectDir: Path,
        @TempDir tempDir: Path,
    ) {
        assumeTrue(
            !System.getProperty("os.name").startsWith("Windows"),
            "Symlink creation requires POSIX — skipped on Windows"
        )
        val memoryRoot = Files.createDirectory(tempDir.resolve("memory"))
        val outsideDir = Files.createTempDirectory("outside-mem-${UUID.randomUUID()}")
        val outsideFile = outsideDir.resolve("file.txt").toFile().also { it.writeText("protected") }

        // plant memory/escape -> outsideDir
        val symlinkInMemory = memoryRoot.resolve("escape")
        Files.createSymbolicLink(symlinkInMemory, outsideDir)

        val (path, error) = PathValidator.resolveAndValidateForWrite(
            symlinkInMemory.resolve("note.md").toAbsolutePath().toString(),
            projectDir.toFile().absolutePath,
            listOf(memoryRoot.toFile().absolutePath),
        )

        // Security invariant: write through a symlink in the memory dir must be REJECTED
        assertNull(path, "Write through symlink in memory dir must be rejected")
        assertNotNull(error)
        assertTrue(error!!.isError)
        // Outside file must be untouched
        assertTrue(outsideFile.readText() == "protected", "Outside file must not be modified")

        outsideDir.toFile().deleteRecursively()
    }
}
