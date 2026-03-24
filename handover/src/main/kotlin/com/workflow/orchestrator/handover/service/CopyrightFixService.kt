package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.model.CopyrightFileEntry
import com.workflow.orchestrator.handover.model.CopyrightStatus
import java.time.Year
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class CopyrightFixService {

    private var project: Project? = null

    /** IntelliJ DI constructor. */
    constructor(project: Project) {
        this.project = project
    }

    /** Test constructor — no project needed for pure logic tests. */
    constructor()

    private val log = Logger.getInstance(CopyrightFixService::class.java)

    private val YEAR_PATTERN = Regex("""\b((?:19|20)\d{2})\b""")
    private val YEAR_RANGE_PATTERN = Regex("""((?:19|20)\d{2})\s*[-–]\s*((?:19|20)\d{2})""")
    private val FULL_YEAR_EXPR = Regex("""(?:(?:19|20)\d{2})(?:\s*[-–,]\s*(?:(?:19|20)\d{2}))*""")

    private val SOURCE_EXTENSIONS = setOf("java", "kt", "kts", "xml", "yaml", "yml", "properties")
    private val GENERATED_PATH_PREFIXES = listOf("target/", "build/", ".gradle/", "node_modules/", ".idea/")

    /**
     * Cache of FileType by file extension. FileTypeRegistry.getFileTypeByFile() involves
     * registry lookups that are redundant when processing many files with the same extension
     * (e.g., scanning 200 .kt files). Each extension is resolved once and reused.
     */
    private val fileTypeCache = ConcurrentHashMap<String, FileType>()

    private fun getCachedFileType(file: com.intellij.openapi.vfs.VirtualFile): FileType {
        val ext = file.extension ?: ""
        return fileTypeCache.getOrPut(ext) {
            com.intellij.openapi.fileTypes.FileTypeRegistry.getInstance().getFileTypeByFile(file)
        }
    }

    fun updateYearInHeader(headerText: String, currentYear: Int): String {
        val yearExprMatch = FULL_YEAR_EXPR.find(headerText) ?: return headerText
        val replacement = consolidateYears(yearExprMatch.value, currentYear) ?: return headerText
        if (yearExprMatch.value == replacement) return headerText
        return headerText.replaceRange(yearExprMatch.range, replacement)
    }

    /**
     * Parses all years from a year expression (e.g. "2018, 2020-2023, 2025")
     * and consolidates into earliest-currentYear format.
     * Returns null if no years found.
     */
    fun consolidateYears(yearExpr: String, currentYear: Int): String? {
        val allYears = mutableSetOf<Int>()
        YEAR_RANGE_PATTERN.findAll(yearExpr).forEach { match ->
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toInt()
            allYears.addAll(start..end)
        }
        YEAR_PATTERN.findAll(yearExpr).forEach { match ->
            allYears.add(match.groupValues[1].toInt())
        }
        if (allYears.isEmpty()) return null
        val minYear = allYears.min()
        return if (minYear == currentYear) "$currentYear" else "$minYear-$currentYear"
    }

    fun wrapForLanguage(template: String, file: com.intellij.openapi.vfs.VirtualFile): String {
        val fileType = getCachedFileType(file)
        val language = (fileType as? com.intellij.openapi.fileTypes.LanguageFileType)?.language ?: return template
        val commenter = com.intellij.lang.LanguageCommenters.INSTANCE.forLanguage(language) ?: return template

        val blockStart = commenter.blockCommentPrefix
        val blockEnd = commenter.blockCommentSuffix
        val linePrefix = commenter.lineCommentPrefix

        return if (blockStart != null && blockEnd != null) {
            "$blockStart\n${template.lines().joinToString("\n") { " * $it" }}\n $blockEnd"
        } else if (linePrefix != null) {
            template.lines().joinToString("\n") { "$linePrefix $it" }
        } else {
            template
        }
    }

    /** @deprecated Use wrapForLanguage(template, VirtualFile) instead */
    @Deprecated("Use VirtualFile overload", ReplaceWith("wrapForLanguage(template, file)"))
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

    fun isSourceFile(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
        val fileType = getCachedFileType(file)
        return !fileType.isBinary
    }

    /** @deprecated Use isSourceFile(VirtualFile) instead */
    @Deprecated("Use VirtualFile overload", ReplaceWith("isSourceFile(file)"))
    fun isSourceFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        return extension in SOURCE_EXTENSIONS
    }

    fun isGeneratedFile(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
        val proj = project ?: return false
        val fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(proj)
        return fileIndex.isInGeneratedSources(file) || fileIndex.isExcluded(file)
    }

    /** @deprecated Use isGeneratedFile(VirtualFile) instead */
    @Deprecated("Use VirtualFile overload", ReplaceWith("isGeneratedFile(file)"))
    fun isGeneratedPath(filePath: String): Boolean {
        val normalized = filePath.replace('\\', '/')
        return GENERATED_PATH_PREFIXES.any { normalized.startsWith(it) }
    }

    fun analyzeFile(
        filePath: String,
        content: String,
        currentYear: Int = Year.now().value
    ): CopyrightFileEntry {
        log.debug("[Handover:Copyright] Analyzing file: $filePath")
        if (!hasCopyrightHeader(content)) {
            log.info("[Handover:Copyright] Missing copyright header: $filePath")
            return CopyrightFileEntry(
                filePath = filePath,
                status = CopyrightStatus.MISSING_HEADER
            )
        }

        val headerRegion = content.lines().take(15).joinToString("\n")
        val updated = updateYearInHeader(headerRegion, currentYear)

        return if (updated == headerRegion) {
            log.debug("[Handover:Copyright] Copyright OK: $filePath")
            CopyrightFileEntry(filePath = filePath, status = CopyrightStatus.OK)
        } else {
            val yearExprMatch = FULL_YEAR_EXPR.find(headerRegion)
            log.info("[Handover:Copyright] Year outdated in $filePath: ${yearExprMatch?.value} -> ${yearExprMatch?.let { consolidateYears(it.value, currentYear) }}")
            CopyrightFileEntry(
                filePath = filePath,
                status = CopyrightStatus.YEAR_OUTDATED,
                oldYear = yearExprMatch?.value,
                newYear = yearExprMatch?.let { consolidateYears(it.value, currentYear) }
            )
        }
    }

    companion object {
        fun getInstance(project: Project): CopyrightFixService {
            return project.getService(CopyrightFixService::class.java)
        }
    }
}
