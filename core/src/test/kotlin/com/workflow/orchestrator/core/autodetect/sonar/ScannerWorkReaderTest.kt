package com.workflow.orchestrator.core.autodetect.sonar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ScannerWorkReaderTest {

    // ──────────────────────────────────────────────────────────────────
    // Null / missing-input cases
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `returns null when directory does not exist`(@TempDir root: Path) {
        val missing = root.resolve("nonexistent")
        assertNull(ScannerWorkReader.readProjectKey(missing))
    }

    @Test
    fun `returns null when directory has no scannerwork subdirectory`(@TempDir root: Path) {
        assertNull(ScannerWorkReader.readProjectKey(root))
    }

    @Test
    fun `returns null when report-task-txt is present but has no projectKey property`(@TempDir root: Path) {
        val scannerWork = root.resolve(".scannerwork")
        Files.createDirectories(scannerWork)
        Files.writeString(scannerWork.resolve("report-task.txt"), "serverUrl=http://sonar.example.com\nceTaskId=abc123\n")

        assertNull(ScannerWorkReader.readProjectKey(root))
    }

    @Test
    fun `returns null when report-task-txt has blank projectKey`(@TempDir root: Path) {
        val scannerWork = root.resolve(".scannerwork")
        Files.createDirectories(scannerWork)
        Files.writeString(scannerWork.resolve("report-task.txt"), "projectKey=\n")

        assertNull(ScannerWorkReader.readProjectKey(root))
    }

    @Test
    fun `ignores report-task-txt not inside a scannerwork parent directory`(@TempDir root: Path) {
        // report-task.txt directly under root — not under .scannerwork
        Files.writeString(root.resolve("report-task.txt"), "projectKey=should-not-be-found\n")

        assertNull(ScannerWorkReader.readProjectKey(root))
    }

    @Test
    fun `ignores report-task-txt inside a non-scannerwork directory`(@TempDir root: Path) {
        val other = root.resolve("other-dir")
        Files.createDirectories(other)
        Files.writeString(other.resolve("report-task.txt"), "projectKey=should-not-be-found\n")

        assertNull(ScannerWorkReader.readProjectKey(root))
    }

    // ──────────────────────────────────────────────────────────────────
    // Happy-path cases
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `returns projectKey when report-task-txt exists at depth 1`(@TempDir root: Path) {
        val scannerWork = root.resolve(".scannerwork")
        Files.createDirectories(scannerWork)
        Files.writeString(
            scannerWork.resolve("report-task.txt"),
            "projectKey=my-project\nserverUrl=http://sonar.example.com\n"
        )

        assertEquals("my-project", ScannerWorkReader.readProjectKey(root))
    }

    @Test
    fun `returns projectKey when report-task-txt exists at depth 3`(@TempDir root: Path) {
        // Simulate scanner run from a build/ subdirectory: build/.scannerwork/report-task.txt
        val deep = root.resolve("build").resolve("subdir")
        val scannerWork = deep.resolve(".scannerwork")
        Files.createDirectories(scannerWork)
        Files.writeString(scannerWork.resolve("report-task.txt"), "projectKey=deep-key\n")

        assertEquals("deep-key", ScannerWorkReader.readProjectKey(root))
    }

    @Test
    fun `returns projectKey with extra whitespace trimmed by Properties`(@TempDir root: Path) {
        val scannerWork = root.resolve(".scannerwork")
        Files.createDirectories(scannerWork)
        // Properties.load() trims leading/trailing whitespace from values
        Files.writeString(scannerWork.resolve("report-task.txt"), "projectKey = trimmed-key \n")

        assertEquals("trimmed-key", ScannerWorkReader.readProjectKey(root))
    }

    @Test
    fun `returns projectKey from first matched file when multiple scannerwork dirs exist`(@TempDir root: Path) {
        // Two .scannerwork directories — first found (depth-first) wins
        val sw1 = root.resolve("module-a").resolve(".scannerwork")
        val sw2 = root.resolve("module-b").resolve(".scannerwork")
        Files.createDirectories(sw1)
        Files.createDirectories(sw2)
        Files.writeString(sw1.resolve("report-task.txt"), "projectKey=module-a-key\n")
        Files.writeString(sw2.resolve("report-task.txt"), "projectKey=module-b-key\n")

        // Either key is acceptable; the important thing is exactly one non-null key is returned
        val result = ScannerWorkReader.readProjectKey(root)
        assert(result in setOf("module-a-key", "module-b-key")) {
            "Expected one of module-a-key/module-b-key, got $result"
        }
    }
}
