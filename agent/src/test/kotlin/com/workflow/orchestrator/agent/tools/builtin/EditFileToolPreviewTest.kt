package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class EditFileToolPreviewTest {

    @TempDir
    lateinit var tempDir: Path

    private val project by lazy { mockk<Project> { every { basePath } returns tempDir.toFile().absolutePath } }

    @BeforeEach
    fun setUp() {
        installReadActionInlineShim()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `preview returns ValidationFailed when file missing`() = runTest {
        val params = buildJsonObject {
            put("path", "does-not-exist.kt")
            put("old_string", "anything")
            put("new_string", "replacement")
        }

        val result = EditFileTool.preview(params, project)
        assertEquals(EditFileTool.EditPreview.ValidationFailed, result)
    }

    @Test
    fun `preview returns ValidationFailed when old_string not in file`() = runTest {
        val f = File(tempDir.toFile(), "hello.txt").apply { writeText("hello world") }
        val params = buildJsonObject {
            put("path", f.absolutePath)
            put("old_string", "foobar")
            put("new_string", "replacement")
        }

        val result = EditFileTool.preview(params, project)
        assertEquals(EditFileTool.EditPreview.ValidationFailed, result)
    }

    @Test
    fun `preview returns ValidationFailed when old_string occurs twice without replace_all`() = runTest {
        val f = File(tempDir.toFile(), "dup.txt").apply { writeText("foo\nfoo\n") }
        val params = buildJsonObject {
            put("path", f.absolutePath)
            put("old_string", "foo")
            put("new_string", "bar")
        }

        val result = EditFileTool.preview(params, project)
        assertEquals(EditFileTool.EditPreview.ValidationFailed, result)
    }

    @Test
    fun `preview returns Ready with real file-anchored diff when old_string occurs once`() = runTest {
        // 10-line file; target line 5
        val lines = (1..10).map { "line $it" }
        val f = File(tempDir.toFile(), "big.txt").apply { writeText(lines.joinToString("\n")) }
        val params = buildJsonObject {
            put("path", f.absolutePath)
            put("old_string", "line 5")
            put("new_string", "LINE FIVE")
        }

        val result = EditFileTool.preview(params, project)
        assertTrue(result is EditFileTool.EditPreview.Ready, "Expected Ready, got $result")
        val ready = result as EditFileTool.EditPreview.Ready
        // With 3 context lines around line 5, the hunk header should start at line 2.
        assertTrue(
            ready.realDiff.contains("@@ -2,"),
            "diff should anchor hunk at line 2 (3 context lines before line 5); got:\n${ready.realDiff}"
        )
        assertTrue(ready.realDiff.contains("-line 5"))
        assertTrue(ready.realDiff.contains("+LINE FIVE"))
        assertEquals(5, ready.matchStartLine)
    }

    @Test
    fun `preview returns Ready with matchStartLine equal to 1-based line of first occurrence`() = runTest {
        val f = File(tempDir.toFile(), "target.txt").apply { writeText("a\nb\nTARGET\nc") }
        val params = buildJsonObject {
            put("path", f.absolutePath)
            put("old_string", "TARGET")
            put("new_string", "X")
        }

        val result = EditFileTool.preview(params, project)
        assertTrue(result is EditFileTool.EditPreview.Ready, "Expected Ready, got $result")
        val ready = result as EditFileTool.EditPreview.Ready
        assertEquals(3, ready.matchStartLine)
    }

    @Test
    fun `preview returns Ready when replace_all=true and old_string occurs multiple times`() = runTest {
        val f = File(tempDir.toFile(), "many.txt").apply { writeText("a\na\na\n") }
        val params = buildJsonObject {
            put("path", f.absolutePath)
            put("old_string", "a")
            put("new_string", "b")
            put("replace_all", true)
        }

        val result = EditFileTool.preview(params, project)
        assertTrue(result is EditFileTool.EditPreview.Ready, "Expected Ready, got $result")
        val ready = result as EditFileTool.EditPreview.Ready
        // First occurrence on line 1
        assertEquals(1, ready.matchStartLine)
    }

    @Test
    fun `preview returns ValidationFailed when path is outside project root`() = runTest {
        // /etc/passwd-style traversal: absolute path outside tempDir
        val outside = File(System.getProperty("java.io.tmpdir"), "definitely-not-in-project.txt")
        // Don't even create the file — the path validator should reject before file lookup.
        val params = buildJsonObject {
            put("path", outside.absolutePath)
            put("old_string", "x")
            put("new_string", "y")
        }

        val result = EditFileTool.preview(params, project)
        assertEquals(EditFileTool.EditPreview.ValidationFailed, result)
    }

    @Test
    fun `preview does not write to the file`() = runTest {
        val f = File(tempDir.toFile(), "untouched.txt").apply { writeText("foo\nbar\nbaz\n") }
        val originalContent = f.readText()
        val originalMtime = f.lastModified()
        // Wait a tick so any accidental write would shift mtime
        Thread.sleep(10)

        val params = buildJsonObject {
            put("path", f.absolutePath)
            put("old_string", "bar")
            put("new_string", "BAR")
        }

        val result = EditFileTool.preview(params, project)
        assertNotNull(result)
        assertTrue(result is EditFileTool.EditPreview.Ready)
        // File must be unchanged after preview.
        assertEquals(originalContent, f.readText(), "preview() must not mutate the file content")
        assertEquals(originalMtime, f.lastModified(), "preview() must not touch the file mtime")
    }

    @Test
    fun `preview returns ValidationFailed when path param is missing`() = runTest {
        val params = buildJsonObject {
            put("old_string", "x")
            put("new_string", "y")
        }
        val result = EditFileTool.preview(params, project)
        assertEquals(EditFileTool.EditPreview.ValidationFailed, result)
    }

    @Test
    fun `preview returns ValidationFailed when old_string param is missing`() = runTest {
        val f = File(tempDir.toFile(), "p.txt").apply { writeText("hello") }
        val params = buildJsonObject {
            put("path", f.absolutePath)
            put("new_string", "y")
        }
        val result = EditFileTool.preview(params, project)
        assertEquals(EditFileTool.EditPreview.ValidationFailed, result)
    }
}
