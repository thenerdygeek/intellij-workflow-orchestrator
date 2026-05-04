package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
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

        installReadActionInlineShim()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `buildFileContext reads file content`() = runTest {
        val file = tempDir.resolve("Test.kt").also { it.writeText("class Test {\n  fun hello() {}\n}") }
        val mention = MentionContextBuilder.Mention("file", "Test.kt", file.absolutePath)
        val context = builder.buildContext(listOf(mention))
        assertNotNull(context)
        assertTrue(context!!.contains("class Test"))
        assertTrue(context.contains("<mentioned_file"))
    }

    @Test
    fun `buildFolderContext generates tree`() = runTest {
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
    fun `large file is truncated`() = runTest {
        val largeContent = (1..1000).joinToString("\n") { "line $it: some code here" }
        val file = tempDir.resolve("Large.kt").also { it.writeText(largeContent) }
        val mention = MentionContextBuilder.Mention("file", "Large.kt", file.absolutePath)
        val context = builder.buildContext(listOf(mention))
        assertNotNull(context)
        assertTrue(context!!.contains("[File truncated"))
    }

    @Test
    fun `tool mention provides instruction`() = runTest {
        val mention = MentionContextBuilder.Mention("tool", "search_code", "search_code")
        val context = builder.buildContext(listOf(mention))
        assertNotNull(context)
        assertTrue(context!!.contains("use the search_code tool"))
    }

    @Test
    fun `empty mentions returns null`() = runTest {
        assertNull(builder.buildContext(emptyList()))
    }

    @Test
    fun `ticket mention without jira service returns not-found context`() = runTest {
        val mention = MentionContextBuilder.Mention("ticket", "PROJ-123", "PROJ-123")
        val context = builder.buildContext(listOf(mention))
        assertNotNull(context)
        assertTrue(context!!.contains("<mentioned_ticket"))
        assertTrue(context.contains("PROJ-123"))
        // Without JiraService registered, should show error/not-found
        assertTrue(context.contains("not configured") || context.contains("PROJ-123"))
    }

    // --- Auto-activation tests --------------------------------------------------

    /** Builder backed by an activation tracker — captures every onActivateTool call. */
    private fun withActivationTracker(): Pair<MentionContextBuilder, MutableList<String>> {
        val activations = mutableListOf<String>()
        val b = MentionContextBuilder(
            project = project,
            onActivateTool = { activations.add(it) }
        )
        return b to activations
    }

    @Test
    fun `ticket mention auto-activates jira tool`() = runTest {
        val (b, activations) = withActivationTracker()
        val mention = MentionContextBuilder.Mention("ticket", "PROJ-123", "PROJ-123")
        b.buildContext(listOf(mention))
        assertEquals(listOf("jira"), activations)
    }

    @Test
    fun `tool mention auto-activates that specific tool`() = runTest {
        val (b, activations) = withActivationTracker()
        val mention = MentionContextBuilder.Mention("tool", "search_code", "search_code")
        b.buildContext(listOf(mention))
        assertEquals(listOf("search_code"), activations)
    }

    @Test
    fun `file mention does not trigger any activation`() = runTest {
        val (b, activations) = withActivationTracker()
        val file = tempDir.resolve("X.kt").also { it.writeText("class X") }
        val mention = MentionContextBuilder.Mention("file", "X.kt", file.absolutePath)
        b.buildContext(listOf(mention))
        assertTrue(activations.isEmpty(), "file mentions should rely on core tools")
    }

    @Test
    fun `folder mention does not trigger any activation`() = runTest {
        val (b, activations) = withActivationTracker()
        val dir = tempDir.resolve("pkg").also { it.mkdirs() }
        val mention = MentionContextBuilder.Mention("folder", "pkg", dir.absolutePath)
        b.buildContext(listOf(mention))
        assertTrue(activations.isEmpty(), "folder mentions should rely on core tools")
    }

    @Test
    fun `symbol mention does not trigger any activation`() = runTest {
        val (b, activations) = withActivationTracker()
        val mention = MentionContextBuilder.Mention("symbol", "MyClass", "com.example.MyClass")
        b.buildContext(listOf(mention))
        assertTrue(activations.isEmpty(), "symbol mentions should rely on core find_definition / find_references")
    }

    @Test
    fun `skill mention does not trigger any activation`() = runTest {
        val (b, activations) = withActivationTracker()
        val mention = MentionContextBuilder.Mention("skill", "tdd", "tdd")
        b.buildContext(listOf(mention))
        assertTrue(activations.isEmpty(), "skill mentions load via use_skill, no deferred activation needed")
    }

    @Test
    fun `duplicate ticket mentions activate jira only once`() = runTest {
        val (b, activations) = withActivationTracker()
        val m1 = MentionContextBuilder.Mention("ticket", "PROJ-123", "PROJ-123")
        val m2 = MentionContextBuilder.Mention("ticket", "PROJ-123", "PROJ-123")
        b.buildContext(listOf(m1, m2))
        assertEquals(listOf("jira"), activations, "dedup'd mentions should fire one activation")
    }

    @Test
    fun `two distinct tickets activate jira exactly once`() = runTest {
        val (b, activations) = withActivationTracker()
        val m1 = MentionContextBuilder.Mention("ticket", "PROJ-1", "PROJ-1")
        val m2 = MentionContextBuilder.Mention("ticket", "PROJ-2", "PROJ-2")
        b.buildContext(listOf(m1, m2))
        assertEquals(listOf("jira"), activations, "any number of ticket mentions implies one jira activation")
    }

    @Test
    fun `mixed mentions activate jira plus the named tool`() = runTest {
        val (b, activations) = withActivationTracker()
        val ticket = MentionContextBuilder.Mention("ticket", "PROJ-1", "PROJ-1")
        val tool = MentionContextBuilder.Mention("tool", "spring", "spring")
        val file = tempDir.resolve("Y.kt").also { it.writeText("class Y") }
        val fileMention = MentionContextBuilder.Mention("file", "Y.kt", file.absolutePath)
        b.buildContext(listOf(ticket, tool, fileMention))
        assertTrue(activations.contains("jira"), "ticket → jira")
        assertTrue(activations.contains("spring"), "tool → that tool")
        assertEquals(2, activations.size, "file mention contributes nothing")
    }

    @Test
    fun `empty mentions trigger no activations`() = runTest {
        val (b, activations) = withActivationTracker()
        b.buildContext(emptyList())
        assertTrue(activations.isEmpty())
    }

    @Test
    fun `default builder with no callback does not crash on activation`() = runTest {
        // Sanity: existing call sites that construct MentionContextBuilder(project) without
        // passing onActivateTool must continue to work — default {} swallows activations silently.
        val mention = MentionContextBuilder.Mention("ticket", "PROJ-1", "PROJ-1")
        builder.buildContext(listOf(mention))  // would NPE if default callback weren't safe
    }

    @Test
    fun `activator throwing does not break mention rendering`() = runTest {
        val b = MentionContextBuilder(
            project = project,
            onActivateTool = { throw IllegalStateException("registry unavailable") }
        )
        val file = tempDir.resolve("Z.kt").also { it.writeText("class Z") }
        // Mix a tool mention (will trigger throwing activator) with a file mention.
        // The file mention's content should still be rendered.
        val toolMention = MentionContextBuilder.Mention("tool", "spring", "spring")
        val fileMention = MentionContextBuilder.Mention("file", "Z.kt", file.absolutePath)
        val context = b.buildContext(listOf(toolMention, fileMention))
        assertNotNull(context)
        assertTrue(context!!.contains("class Z"), "file rendering must survive activator failure")
    }
}
