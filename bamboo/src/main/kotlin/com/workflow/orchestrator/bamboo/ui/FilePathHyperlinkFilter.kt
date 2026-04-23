package com.workflow.orchestrator.bamboo.ui

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.workflow.orchestrator.core.util.PathLinkResolver

/**
 * Filter for IntelliJ ConsoleView that turns detected file paths into clickable
 * hyperlinks opening the file at the specified line. Uses PathLinkResolver so
 * only paths inside project roots are made clickable; all Windows-hardening
 * rejects apply identically.
 *
 * Matches paths of the form:
 *   path/to/Foo.kt
 *   path/to/Foo.kt:42
 *   path/to/Foo.kt:42:7
 *
 * Supported extensions mirror the agent's file-link-scanner regex.
 */
class FilePathHyperlinkFilter(private val project: Project) : Filter {

    private val resolver = PathLinkResolver(project)

    private val regex = Regex(
        """(?<![\w/\\:.])[\w.\-]+(?:[/\\][\w.\-]+)+\.""" +
        """(?:kt|kts|java|py|ts|tsx|js|jsx|mjs|cjs|md|yml|yaml|gradle|xml|json|sql|html|css|scss|properties|proto)""" +
        """(?::\d{1,7}(?::\d{1,7})?)?(?![\w])"""
    )

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val results = mutableListOf<Filter.ResultItem>()
        val lineStart = entireLength - line.length
        for (m in regex.findAll(line)) {
            val validated = resolver.resolveForOpen(m.value) ?: continue
            val vf = LocalFileSystem.getInstance().findFileByPath(validated.canonicalPath) ?: continue
            val info = OpenFileHyperlinkInfo(project, vf, validated.line, validated.column)
            results.add(Filter.ResultItem(lineStart + m.range.first, lineStart + m.range.last + 1, info))
        }
        return if (results.isEmpty()) null else Filter.Result(results)
    }
}
