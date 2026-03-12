package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.model.CopyrightFileEntry
import com.workflow.orchestrator.handover.model.CopyrightStatus
import java.time.Year

@Service(Service.Level.PROJECT)
class CopyrightFixService {

    private var project: Project? = null

    /** IntelliJ DI constructor. */
    constructor(project: Project) {
        this.project = project
    }

    /** Test constructor — no project needed for pure logic tests. */
    constructor()

    private val YEAR_PATTERN = Regex("""\b((?:19|20)\d{2})\b""")
    private val YEAR_RANGE_PATTERN = Regex("""((?:19|20)\d{2})\s*[-–]\s*((?:19|20)\d{2})""")
    private val FULL_YEAR_EXPR = Regex("""(?:(?:19|20)\d{2})(?:\s*[-–,]\s*(?:(?:19|20)\d{2}))*""")

    private val SOURCE_EXTENSIONS = setOf("java", "kt", "kts", "xml", "yaml", "yml", "properties")
    private val GENERATED_PATH_PREFIXES = listOf("target/", "build/", ".gradle/", "node_modules/", ".idea/")

    fun updateYearInHeader(headerText: String, currentYear: Int): String {
        val yearExprMatch = FULL_YEAR_EXPR.find(headerText) ?: return headerText
        val yearExpr = yearExprMatch.value

        val allYears = mutableSetOf<Int>()
        YEAR_RANGE_PATTERN.findAll(yearExpr).forEach { match ->
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toInt()
            allYears.addAll(start..end)
        }
        YEAR_PATTERN.findAll(yearExpr).forEach { match ->
            allYears.add(match.groupValues[1].toInt())
        }

        if (allYears.isEmpty()) return headerText

        val minYear = allYears.min()
        val replacement = if (minYear == currentYear) "$currentYear" else "$minYear-$currentYear"
        if (yearExpr == replacement) return headerText

        return headerText.replaceRange(yearExprMatch.range, replacement)
    }

    fun wrapForLanguage(template: String, fileExtension: String): String {
        return when (fileExtension) {
            "java", "kt", "kts" -> "/*\n${template.lines().joinToString("\n") { " * $it" }}\n */"
            "xml" -> "<!--\n$template\n-->"
            "properties", "yaml", "yml" -> template.lines().joinToString("\n") { "# $it" }
            else -> template
        }
    }

    fun prepareHeader(template: String, currentYear: Int): String {
        return template.replace("{year}", currentYear.toString())
    }

    fun hasCopyrightHeader(content: String): Boolean {
        val headerRegion = content.lines().take(15).joinToString("\n").lowercase()
        return headerRegion.contains("copyright")
    }

    fun isSourceFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        return extension in SOURCE_EXTENSIONS
    }

    fun isGeneratedPath(filePath: String): Boolean {
        return GENERATED_PATH_PREFIXES.any { filePath.startsWith(it) }
    }

    fun analyzeFile(
        filePath: String,
        content: String,
        currentYear: Int = Year.now().value
    ): CopyrightFileEntry {
        if (!hasCopyrightHeader(content)) {
            return CopyrightFileEntry(
                filePath = filePath,
                status = CopyrightStatus.MISSING_HEADER
            )
        }

        val headerRegion = content.lines().take(15).joinToString("\n")
        val updated = updateYearInHeader(headerRegion, currentYear)

        return if (updated == headerRegion) {
            CopyrightFileEntry(filePath = filePath, status = CopyrightStatus.OK)
        } else {
            val yearExprMatch = FULL_YEAR_EXPR.find(headerRegion)
            CopyrightFileEntry(
                filePath = filePath,
                status = CopyrightStatus.YEAR_OUTDATED,
                oldYear = yearExprMatch?.value,
                newYear = if (yearExprMatch != null) {
                    val allYears = mutableSetOf<Int>()
                    YEAR_RANGE_PATTERN.findAll(yearExprMatch.value).forEach { m ->
                        allYears.addAll(m.groupValues[1].toInt()..m.groupValues[2].toInt())
                    }
                    YEAR_PATTERN.findAll(yearExprMatch.value).forEach { m ->
                        allYears.add(m.groupValues[1].toInt())
                    }
                    val min = allYears.min()
                    if (min == currentYear) "$currentYear" else "$min-$currentYear"
                } else null
            )
        }
    }

    companion object {
        fun getInstance(project: Project): CopyrightFixService {
            return project.getService(CopyrightFixService::class.java)
        }
    }
}
