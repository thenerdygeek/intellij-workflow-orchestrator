package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil

/**
 * Validates file content for syntax errors using PSI parsing.
 *
 * Inspired by SWE-Agent's "linter gate" — auto-rejecting syntactically invalid edits
 * prevents cascading failures (the #1 failure mode), improving agent success by ~15%.
 *
 * Strategy:
 * - For .kt/.java files: parse in-memory via PsiFileFactory, walk for PsiErrorElement
 * - For other file types: skip validation (return empty list)
 * - Uses ReadAction — no EDT requirement, lightweight
 */
object SyntaxValidator {

    private val LOG = Logger.getInstance(SyntaxValidator::class.java)

    data class SyntaxError(
        val line: Int,
        val column: Int,
        val message: String
    )

    /**
     * Validate file content for syntax errors.
     *
     * @param project The IntelliJ project (for PSI factory access)
     * @param filePath The file path (used to determine language from extension)
     * @param content The file content to validate
     * @return List of syntax errors found (empty if valid or non-supported file type)
     */
    fun validate(project: Project, filePath: String, content: String): List<SyntaxError> {
        val extension = filePath.substringAfterLast('.', "").lowercase()

        val language = when (extension) {
            "java" -> JavaLanguage.INSTANCE
            "kt" -> {
                try {
                    org.jetbrains.kotlin.idea.KotlinLanguage.INSTANCE
                } catch (_: Exception) {
                    return emptyList() // Kotlin plugin not available
                }
            }
            else -> return emptyList() // No validation for non-Java/Kotlin files
        }

        return try {
            ReadAction.compute<List<SyntaxError>, Exception> {
                val fileName = filePath.substringAfterLast('/')
                val psiFile = PsiFileFactory.getInstance(project)
                    .createFileFromText(fileName, language, content)

                val errors = PsiTreeUtil.collectElementsOfType(psiFile, PsiErrorElement::class.java)

                errors.map { error ->
                    val document = psiFile.viewProvider.document
                    val offset = error.textOffset
                    val line = document?.getLineNumber(offset)?.plus(1) ?: 0
                    val column = if (document != null) {
                        offset - document.getLineStartOffset(document.getLineNumber(offset)) + 1
                    } else 0

                    SyntaxError(
                        line = line,
                        column = column,
                        message = error.errorDescription
                    )
                }
            }
        } catch (e: Exception) {
            LOG.warn("SyntaxValidator: failed to validate $filePath", e)
            emptyList() // Fail open — don't block edits if validation itself fails
        }
    }
}
