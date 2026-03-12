package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.handover.model.FindingSeverity
import com.workflow.orchestrator.handover.model.ReviewFinding
import java.lang.reflect.Method

@Service(Service.Level.PROJECT)
class PreReviewService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    private val log = Logger.getInstance(PreReviewService::class.java)

    /**
     * Parses Cody's text response into structured findings.
     * Cody returns free-text; we look for patterns like:
     * - **HIGH** `file.kt:42` — Missing @Transactional [missing-transactional]
     */
    fun parseFindings(codyResponse: String): List<ReviewFinding> {
        log.debug("[Handover:Review] Parsing findings from Cody response (${codyResponse.length} chars)")
        val findings = mutableListOf<ReviewFinding>()
        val pattern = Regex(
            """\*\*(HIGH|MEDIUM|LOW)\*\*\s+`([^:]+):(\d+)`\s*[-\u2013\u2014]\s*(.+?)\s*\[([^\]]+)]"""
        )

        for (match in pattern.findAll(codyResponse)) {
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

        log.info("[Handover:Review] Parsed ${findings.size} findings from Cody response")
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
     * Uses reflection to access PsiContextEnricher and SpringContextEnricher from the
     * :cody module to avoid a compile-time cross-module dependency. At runtime, the
     * classes are available because both modules are loaded into the same plugin classloader.
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
            log.warn("[Handover:Review] Enrichment failed, falling back to plain prompt: ${e.message}")
            buildReviewPrompt(diff)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun buildFileAnnotations(
        proj: Project,
        changedFiles: List<VirtualFile>
    ): List<String> {
        // Resolve PsiContextEnricher via reflection (lives in :cody module)
        val psiEnricherClass = Class.forName("com.workflow.orchestrator.cody.service.PsiContextEnricher")
        val psiEnricher = psiEnricherClass.getConstructor(Project::class.java).newInstance(proj)
        val enrichPsiMethod = psiEnricherClass.getMethod(
            "enrich", String::class.java, kotlin.coroutines.Continuation::class.java
        )

        // Resolve SpringContextEnricher via reflection (interface in :cody module)
        val springEnricherClass = Class.forName("com.workflow.orchestrator.cody.service.SpringContextEnricher")
        val springEnricher: Any = try {
            proj.getService(springEnricherClass)
                ?: getSpringEnricherEmpty(springEnricherClass)
        } catch (_: Exception) {
            getSpringEnricherEmpty(springEnricherClass)
        }
        val enrichSpringMethod = springEnricherClass.getMethod(
            "enrich", String::class.java, kotlin.coroutines.Continuation::class.java
        )

        return changedFiles.mapNotNull { file ->
            try {
                val psi = invokeSuspend(psiEnricher, enrichPsiMethod, file.path) ?: return@mapNotNull null
                val spring = invokeSuspend(springEnricher, enrichSpringMethod, file.path)

                val className = psi.javaClass.getMethod("getClassName").invoke(psi) as? String
                    ?: return@mapNotNull null
                val classAnnotations = psi.javaClass.getMethod("getClassAnnotations").invoke(psi) as List<String>

                buildString {
                    append("${file.name}: $className")
                    if (classAnnotations.isNotEmpty()) {
                        append(" (${classAnnotations.joinToString(", ") { "@$it" }})")
                    }
                    if (spring != null) {
                        val isBean = spring.javaClass.getMethod("isBean").invoke(spring) as Boolean
                        if (isBean) {
                            appendSpringDetails(spring)
                        }
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun StringBuilder.appendSpringDetails(spring: Any) {
        val transactionalMethods = spring.javaClass.getMethod("getTransactionalMethods")
            .invoke(spring) as List<String>
        if (transactionalMethods.isNotEmpty()) {
            append("\n  @Transactional methods: ${transactionalMethods.joinToString(", ")}")
        }

        val requestMappings = spring.javaClass.getMethod("getRequestMappings")
            .invoke(spring) as List<Any>
        if (requestMappings.isNotEmpty()) {
            val mappingStrings = requestMappings.map { mapping ->
                val method = mapping.javaClass.getMethod("getMethod").invoke(mapping) as String
                val path = mapping.javaClass.getMethod("getPath").invoke(mapping) as String
                "$method $path"
            }
            append("\n  Endpoints: ${mappingStrings.joinToString(", ")}")
        }

        val injectedDeps = spring.javaClass.getMethod("getInjectedDependencies")
            .invoke(spring) as List<Any>
        if (injectedDeps.isNotEmpty()) {
            val depStrings = injectedDeps.map { dep ->
                val beanType = dep.javaClass.getMethod("getBeanType").invoke(dep) as String
                beanType.substringAfterLast('.')
            }
            append("\n  Dependencies: ${depStrings.joinToString(", ")}")
        }
    }

    private fun getSpringEnricherEmpty(springEnricherClass: Class<*>): Any {
        val companion = springEnricherClass.getField("Companion").get(null)
        return companion.javaClass.getMethod("getEMPTY").invoke(companion)
    }

    private suspend fun invokeSuspend(obj: Any, method: Method, arg: String): Any? {
        return kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { cont ->
            method.invoke(obj, arg, cont)
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
