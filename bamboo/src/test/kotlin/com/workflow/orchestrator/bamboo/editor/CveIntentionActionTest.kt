package com.workflow.orchestrator.bamboo.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CveIntentionActionTest {

    @AfterEach
    fun cleanup() = unmockkAll()

    @Test
    fun `getText returns bump message`() {
        assertEquals("Bump to fix CVE vulnerability", CveIntentionAction().text)
    }

    @Test
    fun `getFamilyName returns CVE category`() {
        assertEquals("Workflow Orchestrator CVE", CveIntentionAction().familyName)
    }

    @Test
    fun `isAvailable returns false for non-XML files`() {
        val project = mockk<Project>()
        val editor = mockk<Editor>()
        val file = mockk<PsiFile>()
        every { file.name } returns "Foo.kt"

        assertFalse(CveIntentionAction().isAvailable(project, editor, file))
    }

    @Test
    fun `isAvailable returns false for non-pom XML files`() {
        val project = mockk<Project>()
        val editor = mockk<Editor>()
        val file = mockk<XmlFile>()
        every { file.name } returns "web.xml"

        assertFalse(CveIntentionAction().isAvailable(project, editor, file))
    }
}
