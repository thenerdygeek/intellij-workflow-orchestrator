package com.workflow.orchestrator.core.healthcheck.checks

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CopyrightCheckTest {

    @AfterEach
    fun cleanup() = unmockkAll()

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

        val file = mockk<VirtualFile>()
        every { file.extension } returns "java"
        every { file.name } returns "Foo.java"
        every { file.contentsToByteArray() } returns "// Copyright 2026\nclass Foo {}".toByteArray()

        val context = HealthCheckContext(project, listOf(file), "msg", "main")
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

        val file = mockk<VirtualFile>()
        every { file.extension } returns "java"
        every { file.name } returns "Bar.java"
        every { file.contentsToByteArray() } returns "class Bar {}".toByteArray()

        val context = HealthCheckContext(project, listOf(file), "msg", "main")
        val result = CopyrightCheck().execute(context)
        assertFalse(result.passed)
        assertTrue(result.message.contains("1 file(s)"))
    }
}
