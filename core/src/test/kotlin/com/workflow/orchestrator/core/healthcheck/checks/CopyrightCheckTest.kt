package com.workflow.orchestrator.core.healthcheck.checks

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CopyrightCheckTest {

    private val fileTypeRegistry = mockk<FileTypeRegistry>()
    private val fileDocumentManager = mockk<FileDocumentManager>()
    private val projectFileIndex = mockk<ProjectFileIndex>()

    @BeforeEach
    fun setup() {
        mockkStatic(FileTypeRegistry::class)
        every { FileTypeRegistry.getInstance() } returns fileTypeRegistry

        mockkStatic(FileDocumentManager::class)
        every { FileDocumentManager.getInstance() } returns fileDocumentManager

        mockkStatic(ProjectFileIndex::class)
    }

    @AfterEach
    fun cleanup() = unmockkAll()

    private fun mockSourceFile(name: String, content: String): VirtualFile {
        val file = mockk<VirtualFile>()
        every { file.name } returns name
        every { file.extension } returns name.substringAfterLast('.')

        val fileType = mockk<FileType>()
        every { fileType.isBinary } returns false
        every { fileTypeRegistry.getFileTypeByFile(file) } returns fileType

        every { projectFileIndex.isInSourceContent(file) } returns true
        every { projectFileIndex.isInGeneratedSources(file) } returns false

        val document = mockk<Document>()
        every { document.text } returns content
        every { fileDocumentManager.getDocument(file) } returns document

        return file
    }

    @Test
    fun `id is copyright`() {
        assertEquals("copyright", CopyrightCheck().id)
    }

    @Test
    fun `order is 30`() {
        assertEquals(30, CopyrightCheck().order)
    }

    @Test
    fun `isEnabled returns false when pattern blank`() {
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckCopyrightEnabled } returns true
        every { state.copyrightHeaderPattern } returns ""
        assertFalse(CopyrightCheck().isEnabled(state))
    }

    @Test
    fun `isEnabled returns true when pattern set and enabled`() {
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckCopyrightEnabled } returns true
        every { state.copyrightHeaderPattern } returns "Copyright"
        assertTrue(CopyrightCheck().isEnabled(state))
    }

    @Test
    fun `execute returns passed when all files have headers`() = runTest {
        val project = mockk<Project>()
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.copyrightHeaderPattern } returns "Copyright"
        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state
        every { ProjectFileIndex.getInstance(project) } returns projectFileIndex

        val file = mockSourceFile("Foo.java", "// Copyright 2026\nclass Foo {}")

        val context = HealthCheckContext(project, listOf(file), "main")
        val result = CopyrightCheck().execute(context)
        assertTrue(result.passed)
    }

    @Test
    fun `execute returns failed when files missing headers`() = runTest {
        val project = mockk<Project>()
        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.copyrightHeaderPattern } returns "Copyright"
        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state
        every { ProjectFileIndex.getInstance(project) } returns projectFileIndex

        val file = mockSourceFile("Bar.java", "class Bar {}")

        val context = HealthCheckContext(project, listOf(file), "main")
        val result = CopyrightCheck().execute(context)
        assertFalse(result.passed)
        assertTrue(result.message.contains("1 file(s)"))
    }
}
