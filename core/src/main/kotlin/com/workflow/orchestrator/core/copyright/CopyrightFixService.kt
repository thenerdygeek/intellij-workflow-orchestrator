package com.workflow.orchestrator.core.copyright

import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.TestOnly
import java.time.Year
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class CopyrightFixService {

    private var project: Project? = null

    /** IntelliJ DI constructor. */
    constructor(project: Project) {
        this.project = project
    }

    /** No-arg constructor for unit tests (audit H-P1-2). Production DI always uses `(Project)`. */
    @TestOnly
    constructor()

    private val log = Logger.getInstance(CopyrightFixService::class.java)

    private val YEAR_PATTERN = Regex("""\b((?:19|20)\d{2})\b""")
    private val YEAR_RANGE_PATTERN = Regex("""((?:19|20)\d{2})\s*[-–]\s*((?:19|20)\d{2})""")
    private val FULL_YEAR_EXPR = Regex("""(?:(?:19|20)\d{2})(?:\s*[-–,]\s*(?:(?:19|20)\d{2}))*""")

    /**
     * Cache of FileType by file extension. FileTypeRegistry.getFileTypeByFile() involves
     * registry lookups that are redundant when processing many files with the same extension
     * (e.g., scanning 200 .kt files). Each extension is resolved once and reused.
     */
    private val fileTypeCache = ConcurrentHashMap<String, FileType>()

    private fun getCachedFileType(file: VirtualFile): FileType {
        val ext = file.extension ?: ""
        return fileTypeCache.getOrPut(ext) {
            FileTypeRegistry.getInstance().getFileTypeByFile(file)
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

    fun wrapForLanguage(template: String, file: VirtualFile): String {
        val language = (getCachedFileType(file) as? LanguageFileType)?.language ?: return template
        val commenter = LanguageCommenters.INSTANCE.forLanguage(language) ?: return template

        val blockStart = commenter.blockCommentPrefix
        val blockEnd = commenter.blockCommentSuffix
        val linePrefix = commenter.lineCommentPrefix

        return when {
            blockStart != null && blockEnd != null ->
                "$blockStart\n${template.lines().joinToString("\n") { " * $it" }}\n $blockEnd"
            linePrefix != null ->
                template.lines().joinToString("\n") { "$linePrefix $it" }
            else -> template
        }
    }

    fun prepareHeader(template: String, currentYear: Int): String =
        template.replace("{year}", currentYear.toString())

    /**
     * Returns true when the file's opening region contains a copyright statement.
     *
     * Scans the first 30 lines (raised from 15 — long EUPL/Apache headers can easily
     * exceed 15 lines before the "Copyright" keyword appears). Two strategies are used
     * so that both keyword-based and year-only headers are detected:
     *   1. Case-insensitive "copyright" keyword anywhere in the first 30 lines.
     *   2. Year-regex fallback: a four-digit year in the range 1900-2099 appearing
     *      on a comment line (// or * prefix) in the first 30 lines, which covers
     *      short SPDX-style headers that omit the word "copyright" entirely.
     */
    fun hasCopyrightHeader(content: String): Boolean {
        val headerLines = content.lines().take(30)
        val headerRegion = headerLines.joinToString("\n").lowercase()
        if (headerRegion.contains("copyright")) return true
        // Fallback: a year on a comment line (covers SPDX-only headers)
        val yearOnCommentLine = Regex("""^\s*(?://|/?\*)\s*.*\b(?:19|20)\d{2}\b""")
        return headerLines.any { yearOnCommentLine.containsMatchIn(it) }
    }

    fun isSourceFile(file: VirtualFile): Boolean = !getCachedFileType(file).isBinary

    fun isGeneratedFile(file: VirtualFile): Boolean {
        val proj = project ?: return false
        val fileIndex = ProjectFileIndex.getInstance(proj)
        return fileIndex.isInGeneratedSources(file) || fileIndex.isExcluded(file)
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

        val headerRegion = content.lines().take(30).joinToString("\n")
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
        /**
         * Number of lines scanned from the file header for copyright detection and year update.
         * Must be kept in sync with `CopyrightFixCard.applyFixes` (in :handover) which uses the
         * same window to reassemble the file after patching.
         */
        const val HEADER_SCAN_LINES = 30

        fun getInstance(project: Project): CopyrightFixService =
            project.getService(CopyrightFixService::class.java)
    }
}
