package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.psi.PsiContextEnricher
import com.workflow.orchestrator.handover.model.FindingSeverity
import com.workflow.orchestrator.handover.model.ReviewFinding

@Service(Service.Level.PROJECT)
class PreReviewService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    private val log = Logger.getInstance(PreReviewService::class.java)

    // PsiContextEnricher is now in :core — direct usage, no reflection needed.

    /**
     * Parses the AI review text response into structured findings.
     * The LLM returns free-text; we look for patterns like:
     * - **HIGH** `file.kt:42` — Missing @Transactional [missing-transactional]
     */
    fun parseFindings(aiResponse: String): List<ReviewFinding> {
        log.debug("[Handover:Review] Parsing findings from AI response (${aiResponse.length} chars)")
        val findings = mutableListOf<ReviewFinding>()
        val pattern = Regex(
            """\*\*(HIGH|MEDIUM|LOW)\*\*\s+`([^:]+):(\d+)`\s*[-\u2013\u2014]\s*(.+?)\s*\[([^\]]+)]"""
        )

        for (match in pattern.findAll(aiResponse)) {
            val severity = when (match.groupValues[1]) {
                "HIGH" -> FindingSeverity.HIGH
                "MEDIUM" -> FindingSeverity.MEDIUM
                else -> FindingSeverity.LOW
            }
            findings.add(ReviewFinding(
                severity = severity,
                filePath = match.groupValues[2],
                lineNumber = match.groupValues[3].toIntOrNull() ?: 0,
                message = match.groupValues[4].trim(),
                pattern = match.groupValues[5]
            ))
        }

        log.info("[Handover:Review] Parsed ${findings.size} findings from AI response")
        return findings.sortedBy { it.severity.ordinal }
    }

    fun buildReviewPrompt(diff: String): String {
        log.info("[Handover:Review] Building plain review prompt (diff: ${diff.lines().size} lines)")
        return """
            |Analyze this Spring Boot code diff for anti-patterns and issues.
            |For each issue found, format as:
            |**SEVERITY** `file:line` — description [pattern-name]
            |
            |Where SEVERITY is HIGH, MEDIUM, or LOW.
            |Pattern names: missing-transactional, unclosed-resource, missing-error-handling, n-plus-one-query, missing-validation
            |
            |Diff:
            |```
            |$diff
            |```
        """.trimMargin()
    }

    /**
     * Enhanced review prompt that includes PSI + Spring annotations for changed files.
     * Falls back to plain diff if PSI enrichment fails.
     *
     * Uses PsiContextEnricher from :core for PSI-based code intelligence.
     */
    suspend fun buildEnrichedReviewPrompt(
        diff: String,
        changedFiles: List<VirtualFile>
    ): String {
        val proj = project ?: run {
            log.warn("[Handover:Review] No project available, falling back to plain prompt")
            return buildReviewPrompt(diff)
        }

        return try {
            log.info("[Handover:Review] Building enriched review prompt with ${changedFiles.size} changed files")
            val fileAnnotations = buildFileAnnotations(proj, changedFiles)
            log.debug("[Handover:Review] Enriched prompt includes ${fileAnnotations.size} file annotations")
            buildAnnotatedPrompt(diff, fileAnnotations)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.warn("[Handover:Review] Enrichment failed, falling back to plain prompt: ${e.message}")
            buildReviewPrompt(diff)
        }
    }

    private suspend fun buildFileAnnotations(
        proj: Project,
        changedFiles: List<VirtualFile>
    ): List<String> {
        val enricher = PsiContextEnricher(proj)

        return changedFiles.mapNotNull { file ->
            try {
                val psi = enricher.enrich(file.path)

                val className = psi.className ?: return@mapNotNull null

                buildString {
                    append("${file.name}: $className")
                    if (psi.classAnnotations.isNotEmpty()) {
                        append(" (${psi.classAnnotations.joinToString(", ") { "@$it" }})")
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun buildAnnotatedPrompt(diff: String, fileAnnotations: List<String>): String {
        return buildString {
            append("Analyze this Spring Boot code diff for anti-patterns, ")
            append("missing @Transactional annotations, incorrect bean scoping, ")
            append("and potential issues.\n")
            append("For each issue found, format as:\n")
            append("**SEVERITY** `file:line` — description [pattern-name]\n\n")
            if (fileAnnotations.isNotEmpty()) {
                append("## Changed Classes (IDE Analysis)\n")
                fileAnnotations.forEach { append("- $it\n") }
                append("\n")
            }
            append("## Diff\n```diff\n$diff\n```")
        }
    }

    enum class DiffValidation { OK, EMPTY, TOO_LARGE }

    fun validateDiff(diff: String): DiffValidation {
        if (diff.isBlank()) {
            log.warn("[Handover:Review] Diff validation failed: diff is empty")
            return DiffValidation.EMPTY
        }
        val maxLines = project?.let {
            com.workflow.orchestrator.core.settings.PluginSettings.getInstance(it).state.maxDiffLinesForReview
        } ?: 10_000
        val lineCount = diff.lines().size
        if (lineCount > maxLines) {
            log.warn("[Handover:Review] Diff validation failed: $lineCount lines exceeds max $maxLines")
            return DiffValidation.TOO_LARGE
        }
        log.debug("[Handover:Review] Diff validation passed: $lineCount lines")
        return DiffValidation.OK
    }

    companion object {
        fun getInstance(project: Project): PreReviewService {
            return project.getService(PreReviewService::class.java)
        }
    }
}
