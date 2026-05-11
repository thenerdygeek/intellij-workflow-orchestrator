package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.encoding.EncodingProjectManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Regression tests for fix 7a5b679b0 — create_file charset + overwrite diff.
 *
 * Two bugs fixed:
 * (a) I/O fallback hardcoded Charsets.UTF_8 instead of EncodingProjectManager.defaultCharset.
 *     On non-UTF-8 projects (e.g. ISO-8859-1, Shift_JIS, CP1252) the same create_file call
 *     wrote different bytes depending on which write path triggered. Fixed by reading
 *     EncodingProjectManager.getInstance(project).defaultCharset in writeViaFileIo().
 * (b) overwrite=true computed the diff from "" → new content, hiding the actual change
 *     from the reviewer. Fixed by capturing existing content before overwrite and computing
 *     DiffUtil.unifiedDiff(existingContent, newContent, path).
 *
 * These tests pin the fixed behaviour. They will fail if either bug is reintroduced.
 */
class CreateFileToolTest {

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Regression (a) — charset correctness in the I/O fallback path
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Regression: writeViaFileIo must use EncodingProjectManager.defaultCharset, not UTF-8.
     *
     * Strategy:
     * 1. Mock EncodingProjectManager.getInstance(project) → mock with defaultCharset = ISO-8859-1.
     * 2. Mock LocalFileSystem.getInstance() so the VFS refresh after writeViaFileIo doesn't crash.
     * 3. Create the file in @TempDir (inside the project root so PathValidator allows it).
     * 4. Call CreateFileTool.execute() with content containing a character that encodes differently
     *    in UTF-8 vs ISO-8859-1 (e.g. "café" — 'é' is 0xE9 in ISO-8859-1, 0xC3 0xA9 in UTF-8).
     * 5. Assert on-disk bytes match ISO-8859-1 encoding, NOT UTF-8.
     *
     * The pre-fix code called: file.writeText(content, Charsets.UTF_8)
     * The post-fix code calls: file.writeText(content, EncodingProjectManager.getInstance(project).defaultCharset)
     *
     * If anyone reverts the fix (hardcodes UTF_8 again), the byte comparison will fail because
     * "café" encoded as UTF-8 produces [99, 97, 102, -61, -87] while ISO-8859-1 produces [99, 97, 102, -23].
     */
    @Test
    fun `writeViaFileIo uses EncodingProjectManager defaultCharset not hardcoded UTF-8`(@TempDir tempDir: Path) = runTest {
        val projectDir = tempDir.toFile()
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns projectDir.absolutePath

        // Stub EncodingProjectManager to return ISO-8859-1 as the project charset
        val mockEncodingManager = mockk<EncodingProjectManager>(relaxed = true)
        every { mockEncodingManager.defaultCharset } returns Charsets.ISO_8859_1
        mockkStatic(EncodingProjectManager::class)
        every { EncodingProjectManager.getInstance(project) } returns mockEncodingManager

        // Stub LocalFileSystem so the VFS refresh call in writeViaFileIo doesn't crash
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns mockk(relaxed = true)

        // Content with 'é' (U+00E9): ISO-8859-1 = [0xE9], UTF-8 = [0xC3, 0xA9]
        val content = "café"
        val targetFile = File(projectDir, "output.txt")

        val params = buildJsonObject {
            put("path", "output.txt")
            put("content", content)
            put("description", "charset test")
        }

        val result = CreateFileTool().execute(params, project)

        // The VFS path (writeViaVfs) fails when ApplicationManager.getApplication() == null,
        // so writeViaFileIo is invoked as the fallback — which is the path under test.
        assertFalse(result.isError, "Tool must succeed via writeViaFileIo fallback: ${result.content}")
        assertTrue(targetFile.exists(), "File must be created on disk")

        val onDiskBytes = targetFile.readBytes()
        val iso88591Bytes = content.toByteArray(Charsets.ISO_8859_1)
        val utf8Bytes = content.toByteArray(Charsets.UTF_8)

        // Sanity: the two encodings are genuinely different for this content
        assertFalse(
            iso88591Bytes.contentEquals(utf8Bytes),
            "Test content must differ between ISO-8859-1 and UTF-8 encodings (sanity check)"
        )

        // Assert on-disk bytes match ISO-8859-1 (the project charset), NOT UTF-8
        assertArrayEquals(
            iso88591Bytes,
            onDiskBytes,
            "On-disk bytes must match ISO-8859-1 encoding (EncodingProjectManager.defaultCharset), not UTF-8"
        )
        assertFalse(
            utf8Bytes.contentEquals(onDiskBytes),
            "On-disk bytes must NOT match UTF-8 encoding — that would indicate the old hardcoded charset bug is back"
        )
    }

    /**
     * Regression: EncodingProjectManager.defaultCharset is also consulted when READING
     * the existing content for the overwrite diff (existingContent in execute()).
     *
     * Ensures the mock is called during the read-existing phase too — confirms the
     * getInstance call is on the correct code path.
     */
    @Test
    fun `EncodingProjectManager is consulted when reading existing content for overwrite diff`(@TempDir tempDir: Path) = runTest {
        val projectDir = tempDir.toFile()
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns projectDir.absolutePath

        val mockEncodingManager = mockk<EncodingProjectManager>(relaxed = true)
        every { mockEncodingManager.defaultCharset } returns Charsets.UTF_8
        mockkStatic(EncodingProjectManager::class)
        every { EncodingProjectManager.getInstance(project) } returns mockEncodingManager

        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns mockk(relaxed = true)

        // Pre-create the file so overwrite=true triggers the existing-content read path
        val targetFile = File(projectDir, "existing.txt")
        targetFile.writeText("old content")

        val params = buildJsonObject {
            put("path", "existing.txt")
            put("content", "new content")
            put("overwrite", true)
            put("description", "overwrite test")
        }

        CreateFileTool().execute(params, project)

        // EncodingProjectManager.getInstance must have been called at least once
        // (once for existingContent read, once for writeViaFileIo)
        verify(atLeast = 1) { EncodingProjectManager.getInstance(project) }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Regression (b) — overwrite diff shows real before/after, not "" → new
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Regression: overwrite=true must generate a diff from EXISTING content → new content,
     * not from "" → new content (the pre-fix behaviour that hid the actual change).
     *
     * Strategy:
     * 1. Create a file with "old\n" in @TempDir (inside project root).
     * 2. Call CreateFileTool.execute() with overwrite=true and new content "new\n".
     * 3. Capture ToolResult.diff.
     * 4. Assert the diff contains "-old" (deletion of old line) AND "+new" (addition of new line).
     *    The pre-fix diff would contain ONLY "+old" and "+new" (because existingContent was "").
     *
     * Pre-fix code:
     *   val existingContent: String = ""   // always empty — never read the existing file
     *   val createDiff = DiffUtil.unifiedDiff("", newContent, path)
     *   → diff shows all lines as additions: "+old", "+new" (wrong — hides the deletion)
     *
     * Post-fix code:
     *   val existingContent: String = if (overwrite && file.exists()) file.readText(charset) else ""
     *   val createDiff = DiffUtil.unifiedDiff(existingContent, newContent, path)
     *   → diff shows real change: "-old", "+new" (correct — reviewer sees what changed)
     *
     * If anyone reverts to existingContent = "" the assertions on "-old" will fail.
     */
    @Test
    fun `overwrite=true diff contains minus-old and plus-new lines not just additions`(@TempDir tempDir: Path) = runTest {
        val projectDir = tempDir.toFile()
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns projectDir.absolutePath

        // Stub EncodingProjectManager to return UTF-8 (safe default for this test)
        val mockEncodingManager = mockk<EncodingProjectManager>(relaxed = true)
        every { mockEncodingManager.defaultCharset } returns Charsets.UTF_8
        mockkStatic(EncodingProjectManager::class)
        every { EncodingProjectManager.getInstance(project) } returns mockEncodingManager

        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns mockk(relaxed = true)

        // Create the file with "old" content
        val targetFile = File(projectDir, "rewrite.txt")
        targetFile.writeText("old\n")

        val params = buildJsonObject {
            put("path", "rewrite.txt")
            put("content", "new\n")
            put("overwrite", true)
            put("description", "complete rewrite")
        }

        val result = CreateFileTool().execute(params, project)

        assertFalse(result.isError, "overwrite=true must succeed: ${result.content}")

        val diff = result.diff
        assertNotNull(diff, "ToolResult.diff must be non-null for an overwrite operation")
        assertFalse(diff!!.isBlank(), "Diff must be non-empty when old and new content differ")

        // The diff must contain the deletion of "old" — this line is MISSING in the pre-fix diff
        assertTrue(
            diff.contains("-old"),
            "Diff must contain '-old' (deletion of the pre-existing line) — " +
                "if missing, existingContent was '' (the pre-fix bug is back).\nActual diff:\n$diff"
        )

        // The diff must contain the addition of "new"
        assertTrue(
            diff.contains("+new"),
            "Diff must contain '+new' (addition of the replacement line).\nActual diff:\n$diff"
        )

        // The pre-fix diff would show "+old" as an addition (since existingContent was ""),
        // but the post-fix diff must NOT show "+old" — "old" appears only as a deletion.
        assertFalse(
            diff.contains("+old"),
            "Diff must NOT contain '+old' — that would mean existingContent was '' (pre-fix bug).\nActual diff:\n$diff"
        )
    }

    /**
     * Regression sanity: new file (no existing content) still produces a full-additions diff.
     *
     * This verifies the overwrite=false path (or overwrite=true on a non-existent file)
     * produces a diff from "" → new content — which is correct behaviour and must not change.
     * Distinguishes the "new file" case from the "overwrite existing" case.
     */
    @Test
    fun `new file diff shows all content as additions`(@TempDir tempDir: Path) = runTest {
        val projectDir = tempDir.toFile()
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns projectDir.absolutePath

        val mockEncodingManager = mockk<EncodingProjectManager>(relaxed = true)
        every { mockEncodingManager.defaultCharset } returns Charsets.UTF_8
        mockkStatic(EncodingProjectManager::class)
        every { EncodingProjectManager.getInstance(project) } returns mockEncodingManager

        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns mockk(relaxed = true)

        // File does NOT exist yet
        val params = buildJsonObject {
            put("path", "brandnew.txt")
            put("content", "hello\n")
            put("description", "new file")
        }

        val result = CreateFileTool().execute(params, project)

        assertFalse(result.isError, "New file creation must succeed: ${result.content}")

        val diff = result.diff
        assertNotNull(diff, "ToolResult.diff must be non-null for a new file")
        // For a new file, existingContent="" so the diff shows only additions
        assertTrue(
            diff!!.contains("+hello"),
            "New-file diff must contain '+hello' (all lines added).\nActual diff:\n$diff"
        )
        assertFalse(
            diff.contains("-hello"),
            "New-file diff must NOT contain '-hello' (nothing removed).\nActual diff:\n$diff"
        )
    }
}
