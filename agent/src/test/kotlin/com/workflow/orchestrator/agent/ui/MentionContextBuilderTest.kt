package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MentionContextBuilderTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var project: Project
    private lateinit var builder: MentionContextBuilder

    @BeforeEach
    fun setUp() {
        project = mockk<Project>(relaxed = true).apply {
            every { basePath } returns tempDir.absolutePath
        }
        builder = MentionContextBuilder(project)
    }

    @Test
    fun `buildFileContext reads file content`() {
        val file = tempDir.resolve("Test.kt").also { it.writeText("class Test {\n  fun hello() {}\n}") }
        val mention = MentionContextBuilder.Mention("file", "Test.kt", file.absolutePath)
        val context = builder.buildContext(listOf(mention))
        assertNotNull(context)
        assertTrue(context!!.contains("class Test"))
        assertTrue(context.contains("<mentioned_file"))
    }

    @Test
    fun `buildFolderContext generates tree`() {
        val dir = tempDir.resolve("src").also { it.mkdirs() }
        File(dir, "Main.kt").writeText("fun main() {}")
        File(dir, "Utils.kt").writeText("object Utils {}")
        val mention = MentionContextBuilder.Mention("folder", "src", dir.absolutePath)
        val context = builder.buildContext(listOf(mention))
        assertNotNull(context)
        assertTrue(context!!.contains("Main.kt"))
        assertTrue(context.contains("Utils.kt"))
        assertTrue(context.contains("<mentioned_folder"))
    }

    @Test
    fun `large file is truncated`() {
        val largeContent = (1..1000).joinToString("\n") { "line $it: some code here" }
        val file = tempDir.resolve("Large.kt").also { it.writeText(largeContent) }
        val mention = MentionContextBuilder.Mention("file", "Large.kt", file.absolutePath)
        val context = builder.buildContext(listOf(mention))
        assertNotNull(context)
        assertTrue(context!!.contains("[File truncated"))
    }

    @Test
    fun `tool mention provides instruction`() {
        val mention = MentionContextBuilder.Mention("tool", "search_code", "search_code")
        val context = builder.buildContext(listOf(mention))
        assertNotNull(context)
        assertTrue(context!!.contains("use the search_code tool"))
    }

    @Test
    fun `empty mentions returns null`() {
        assertNull(builder.buildContext(emptyList()))
    }
}
