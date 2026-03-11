package com.workflow.orchestrator.core.copyright

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CopyrightCheckServiceTest {

    @AfterEach
    fun cleanup() = unmockkAll()

    private fun setupSettings(pattern: String): Project {
        val project = mockk<Project>()
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.copyrightHeaderPattern } returns pattern
        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state
        return project
    }

    private fun mockFile(name: String, content: String): VirtualFile {
        val file = mockk<VirtualFile>()
        every { file.name } returns name
        every { file.extension } returns name.substringAfterLast('.')
        every { file.contentsToByteArray() } returns content.toByteArray()
        return file
    }

    @Test
    fun `returns empty result when pattern is blank`() {
        val project = setupSettings("")
        val result = CopyrightCheckService(project).checkFiles(listOf(mockFile("Foo.java", "class Foo {}")))
        assertTrue(result.passed)
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `detects missing copyright header`() {
        val project = setupSettings("Copyright \\(c\\) \\d{4}")
        val file = mockFile("Foo.java", "package com.example;\nclass Foo {}")
        val result = CopyrightCheckService(project).checkFiles(listOf(file))
        assertFalse(result.passed)
        assertEquals(1, result.violations.size)
    }

    @Test
    fun `passes when copyright header present`() {
        val project = setupSettings("Copyright \\(c\\) \\d{4}")
        val file = mockFile("Foo.java", "// Copyright (c) 2026 MyCompany\nclass Foo {}")
        val result = CopyrightCheckService(project).checkFiles(listOf(file))
        assertTrue(result.passed)
    }

    @Test
    fun `skips non-source files`() {
        val project = setupSettings("Copyright")
        val file = mockFile("image.png", "binary content")
        every { file.extension } returns "png"
        val result = CopyrightCheckService(project).checkFiles(listOf(file))
        assertTrue(result.passed)
    }

    @Test
    fun `checks only first 10 lines`() {
        val project = setupSettings("Copyright")
        val lines = (1..20).map { "// line $it" }.toMutableList()
        lines.add("// Copyright notice here")
        val file = mockFile("Foo.java", lines.joinToString("\n"))
        val result = CopyrightCheckService(project).checkFiles(listOf(file))
        assertFalse(result.passed) // Copyright is on line 21, not in first 10
    }
}
