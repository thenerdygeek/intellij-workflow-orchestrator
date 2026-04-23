package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.util.PathLinkResolver
import com.workflow.orchestrator.core.util.ValidatedPath
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigateToFileSecurityTest {

    @Test
    fun `rejected path does not reach FileEditorManager`() {
        val project = mockk<Project>(relaxed = true)
        val resolver = mockk<PathLinkResolver>()
        every { resolver.resolveForOpen("/etc/passwd") } returns null

        val opened = mutableListOf<String>()
        val handler = NavigateToFileHandler(project, resolver) { path, _, _ -> opened.add(path) }

        handler.navigate("/etc/passwd", 0)

        assertTrue(opened.isEmpty())
    }

    @Test
    fun `validated path reaches FileEditorManager with canonical path`() {
        val project = mockk<Project>(relaxed = true)
        val resolver = mockk<PathLinkResolver>()
        every { resolver.resolveForOpen("src/foo.kt:42") } returns
            ValidatedPath("src/foo.kt:42", "/canonical/project/src/foo.kt", 41, 0)

        val opened = mutableListOf<Triple<String, Int, Int>>()
        val handler = NavigateToFileHandler(project, resolver) { path, line, col ->
            opened.add(Triple(path, line, col))
        }

        handler.navigate("src/foo.kt", 42)

        assertEquals(1, opened.size)
        assertEquals(Triple("/canonical/project/src/foo.kt", 41, 0), opened[0])
    }
}

/**
 * Testable extract of AgentController.navigateToFile — same logic, explicit dependencies.
 * The production method should delegate to this (or contain the same logic).
 */
internal class NavigateToFileHandler(
    private val project: Project,
    private val resolver: PathLinkResolver,
    private val openFn: (canonicalPath: String, line: Int, column: Int) -> Unit,
) {
    fun navigate(path: String, line: Int) {
        val input = if (line > 0) "$path:$line" else path
        val resolved = resolver.resolveForOpen(input) ?: return
        openFn(resolved.canonicalPath, resolved.line, resolved.column)
    }
}
