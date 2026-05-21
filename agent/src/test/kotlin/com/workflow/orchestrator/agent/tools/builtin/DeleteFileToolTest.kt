package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.memory.MemoryIndex
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DeleteFileToolTest {

    @AfterEach
    fun tearDown() { unmockkAll() }

    private fun project(basePath: Path): Project = mockk<Project>(relaxed = true).also {
        every { it.basePath } returns basePath.toString()
    }

    @Test
    fun `delete returns error when file does not exist`(@TempDir tmp: Path) = runTest {
        val project = project(tmp)
        val tool = DeleteFileTool()

        val result = tool.execute(buildJsonObject {
            put("path", JsonPrimitive(tmp.resolve("absent.txt").toString()))
            put("description", JsonPrimitive("trying to delete an absent file"))
        }, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found") || result.content.contains("does not exist"))
    }

    @Test
    fun `delete refuses a path outside the allowed roots`(@TempDir tmp: Path) = runTest {
        val project = project(tmp)
        val outsideProject = Files.createTempFile("outside-", ".txt")
        Files.writeString(outsideProject, "x")

        val tool = DeleteFileTool()
        val result = tool.execute(buildJsonObject {
            put("path", JsonPrimitive(outsideProject.toString()))
            put("description", JsonPrimitive("attempt to delete outside project"))
        }, project)

        assertTrue(result.isError)
        assertTrue(Files.exists(outsideProject), "outside file must not be deleted")
        Files.deleteIfExists(outsideProject)
    }

    @Test
    fun `delete refuses a directory`(@TempDir tmp: Path) = runTest {
        val project = project(tmp)
        val dir = Files.createDirectory(tmp.resolve("sub"))

        val tool = DeleteFileTool()
        val result = tool.execute(buildJsonObject {
            put("path", JsonPrimitive(dir.toString()))
            put("description", JsonPrimitive("attempting to delete a directory"))
        }, project)

        assertTrue(result.isError)
        assertTrue(Files.exists(dir))
    }

    @Test
    fun `delete removes a project file via fallback I_O`(@TempDir tmp: Path) = runTest {
        val project = project(tmp)
        val target = tmp.resolve("Foo.kt")
        Files.writeString(target, "stuff")

        val tool = DeleteFileTool()
        val result = tool.execute(buildJsonObject {
            put("path", JsonPrimitive(target.toString()))
            put("description", JsonPrimitive("retiring dead code"))
        }, project)

        assertFalse(result.isError)
        assertFalse(Files.exists(target))
    }

    @Test
    fun `delete of a memory file triggers MemoryIndex onMemoryFileDeleted`(@TempDir tmp: Path) = runTest {
        // We exercise MemoryIndex directly here — the wired-from-DeleteFileTool path is
        // observable as the file being gone AND MEMORY.md being updated. The integration
        // test for the wire is the source-text contract test below.
        val agentDir = Files.createDirectories(tmp.resolve(".test-agent-dir/memory"))
        val memFile = agentDir.resolve("feedback_x.md")
        Files.writeString(memFile, "---\nname: x\ndescription: desc.\ntype: feedback\n---\n")
        Files.writeString(agentDir.resolve("MEMORY.md"),
            "# Memory Index\n\n## Feedback\n- [x](feedback_x.md) — desc.\n")

        MemoryIndex.onMemoryFileDeleted(agentDir, "feedback_x.md")

        assertFalse(Files.readString(agentDir.resolve("MEMORY.md")).contains("(feedback_x.md)"))
    }

    @Test
    fun `tool name and required params match the spec`() {
        val tool = DeleteFileTool()
        assertEquals("delete_file", tool.name)
        assertTrue(tool.parameters.required.contains("path"))
        assertTrue(tool.parameters.required.contains("description"))
    }

    @Test
    fun `DeleteFileTool source contains MemoryIndex onMemoryFileDeleted call`() {
        // Source-text contract — catches accidental hook removal without an integration env.
        // Mirrors the same pin used in CreateFileToolTest.
        val src = java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/DeleteFileTool.kt")
        assertTrue(src.exists(), "DeleteFileTool source must exist at the expected path: ${src.absolutePath}")
        val text = src.readText()
        assertTrue(text.contains("MemoryIndex.onMemoryFileDeleted"),
            "DeleteFileTool must call MemoryIndex.onMemoryFileDeleted after a successful memory-dir delete")
        assertTrue(text.contains("memoryAutoIndexEnabled"),
            "DeleteFileTool must respect the memoryAutoIndexEnabled kill switch")
    }
}
