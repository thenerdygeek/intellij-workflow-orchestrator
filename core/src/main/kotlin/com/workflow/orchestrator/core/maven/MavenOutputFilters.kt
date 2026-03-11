package com.workflow.orchestrator.core.maven

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

object MavenOutputPatterns {
    val FILE_ERROR_PATTERN = Regex("""\[ERROR]\s+(.+?):(\d+)(?:,(\d+))?""")
    val FILE_WARNING_PATTERN = Regex("""\[WARNING]\s+(.+?):(\d+)(?:,(\d+))?""")
    val TEST_FAILURE_PATTERN = Regex("""(?:Tests run:.*Failures:\s*(\d+))|(?:<<<\s*FAILURE!\s*-\s*in\s+(\S+))""")
}

class MavenErrorFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        return applyPatternFilter(line, entireLength, MavenOutputPatterns.FILE_ERROR_PATTERN, project)
    }
}

class MavenWarningFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        return applyPatternFilter(line, entireLength, MavenOutputPatterns.FILE_WARNING_PATTERN, project)
    }
}

class MavenTestFailureFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val match = MavenOutputPatterns.TEST_FAILURE_PATTERN.find(line) ?: return null
        val testClass = match.groupValues[2].takeIf { it.isNotEmpty() } ?: return null

        val startOffset = entireLength - line.length + match.range.first
        val endOffset = entireLength - line.length + match.range.last + 1

        return Filter.Result(startOffset, endOffset, null)
    }
}

private fun applyPatternFilter(
    line: String,
    entireLength: Int,
    pattern: Regex,
    project: Project
): Filter.Result? {
    val match = pattern.find(line) ?: return null
    val filePath = match.groupValues[1]
    val lineNum = match.groupValues[2].toIntOrNull() ?: return null
    val col = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0

    val startOffset = entireLength - line.length + match.range.first
    val endOffset = entireLength - line.length + match.range.last + 1

    val hyperlinkInfo = HyperlinkInfo {
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@HyperlinkInfo
        OpenFileDescriptor(project, vFile, lineNum - 1, col).navigate(true)
    }

    return Filter.Result(startOffset, endOffset, hyperlinkInfo)
}
