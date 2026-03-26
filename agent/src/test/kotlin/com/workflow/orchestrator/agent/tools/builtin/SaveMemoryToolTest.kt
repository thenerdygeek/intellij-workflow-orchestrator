package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.util.ProjectIdentifier
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SaveMemoryToolTest {

    @TempDir
    lateinit var tempDir: Path

    private val tool = SaveMemoryTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("save_memory", tool.name)
        assertTrue(tool.parameters.required.contains("topic"))
        assertTrue(tool.parameters.required.contains("content"))
        assertEquals(2, tool.parameters.required.size)
        assertEquals(3, tool.allowedWorkers.size)
    }

    @Test
    fun `returns error when topic is missing`() = runTest {
        val project = mockk<Project> { every { basePath } returns tempDir.toFile().absolutePath }
        val params = buildJsonObject { put("content", "some learning") }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("topic"))
    }

    @Test
    fun `returns error when content is missing`() = runTest {
        val project = mockk<Project> { every { basePath } returns tempDir.toFile().absolutePath }
        val params = buildJsonObject { put("topic", "build-config") }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("content"))
    }

    @Test
    fun `saves memory when project basePath available`() = runTest {
        val project = mockk<Project> { every { basePath } returns tempDir.toFile().absolutePath }
        val params = buildJsonObject {
            put("topic", "build-config")
            put("content", "Always run clean before test")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Memory saved"))
        assertTrue(result.content.contains("build-config"))

        val memoryFile = File(ProjectIdentifier.agentDir(tempDir.toFile().absolutePath), "memory/build-config.md")
        assertTrue(memoryFile.exists(), "Memory file should be created")
        val fileContent = memoryFile.readText()
        assertTrue(fileContent.contains("build-config"))
        assertTrue(fileContent.contains("Always run clean before test"))
    }
}
