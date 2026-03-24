package com.workflow.orchestrator.bamboo.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.workflow.orchestrator.bamboo.service.CveRemediationService
import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveSeverity
import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveVulnerability
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CveAnnotatorTest {

    private val editor = mockk<Editor>(relaxed = true)

    @AfterEach
    fun cleanup() = unmockkAll()

    @Test
    fun `collectInformation returns null for non-pom files`() {
        val file = mockk<PsiFile>()
        every { file.name } returns "Foo.kt"

        assertNull(CveAnnotator().collectInformation(file, editor, false))
    }

    @Test
    fun `collectInformation returns null for pom with no CVEs`() {
        val project = mockk<Project>()
        val file = mockk<XmlFile>()
        every { file.name } returns "pom.xml"
        every { file.project } returns project

        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckCveEnabled } returns true
        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        val cveService = mockk<CveRemediationService>()
        every { cveService.vulnerabilities } returns MutableStateFlow(emptyList())
        mockkObject(CveRemediationService)
        every { CveRemediationService.getInstance(project) } returns cveService

        assertNull(CveAnnotator().collectInformation(file, editor, false))
    }

    @Test
    fun `collectInformation returns null when CVE annotations disabled`() {
        val project = mockk<Project>()
        val file = mockk<XmlFile>()
        every { file.name } returns "pom.xml"
        every { file.project } returns project

        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckCveEnabled } returns false
        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        assertNull(CveAnnotator().collectInformation(file, editor, false))
    }

    @Test
    fun `collectInformation returns info when CVEs present`() {
        val project = mockk<Project>()
        val file = mockk<XmlFile>()
        val virtualFile = mockk<com.intellij.openapi.vfs.VirtualFile>()
        every { virtualFile.path } returns "/project/pom.xml"
        every { file.name } returns "pom.xml"
        every { file.project } returns project
        every { file.virtualFile } returns virtualFile
        every { file.modificationStamp } returns 1L
        every { file.findElementAt(any()) } returns null

        val settings = mockk<PluginSettings>()
        val state = mockk<PluginSettings.State>()
        every { state.healthCheckCveEnabled } returns true
        mockkObject(PluginSettings)
        every { PluginSettings.getInstance(project) } returns settings
        every { settings.state } returns state

        val vuln = CveVulnerability(
            cveId = "CVE-2023-44487", groupId = "io.netty",
            artifactId = "netty-codec-http2", currentVersion = "4.1.93.Final",
            severity = CveSeverity.CRITICAL, description = "test"
        )
        val cveService = mockk<CveRemediationService>()
        every { cveService.vulnerabilities } returns MutableStateFlow(listOf(vuln))
        mockkObject(CveRemediationService)
        every { CveRemediationService.getInstance(project) } returns cveService

        val result = CveAnnotator().collectInformation(file, editor, false)

        assertNotNull(result)
        assertEquals(1, result!!.vulnerabilities.size)
    }
}
