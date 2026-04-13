package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class TestFinderTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "test_finder"
    override val description = "Find the test class for a source class, or the source class for a test class. " +
        "Uses IntelliJ's test framework integration (JUnit4, JUnit5, TestNG). " +
        "Convention-based: looks for FooTest, FooTests, TestFoo patterns."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "Source or test file path relative to project or absolute"),
            "class_name" to ParameterProperty(type = "string", description = "Specific class name within the file (optional, uses first class if omitted)")
        ),
        required = listOf("file")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'file' parameter is required",
                "Error: missing file", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        val className = try {
            params["class_name"]?.jsonPrimitive?.content
        } catch (_: Exception) { null }

        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val (resolvedPath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
        if (pathError != null) return pathError

        val content = ReadAction.nonBlocking<String> {
            val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(resolvedPath!!)
                ?: return@nonBlocking "Error: file not found: $filePath"

            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@nonBlocking "Error: could not parse file: $filePath"

            val provider = registry.forFile(psiFile)
                ?: return@nonBlocking "Code intelligence not available for ${psiFile.language.displayName}"

            // Find the target class in the file
            val targetClass = if (className != null) {
                findClassByName(psiFile, className)
                    ?: return@nonBlocking "Error: class '$className' not found in $filePath"
            } else {
                findFirstClass(psiFile)
                    ?: return@nonBlocking "Error: no class found in $filePath"
            }

            // Delegate test finding to the provider
            val result = provider.findRelatedTests(targetClass)

            val qualifiedName = (targetClass as? PsiClass)?.qualifiedName
                ?: (targetClass as? PsiClass)?.name ?: "Unknown"
            val relativePath = PsiToolUtils.relativePath(project, targetClass.containingFile?.virtualFile?.path ?: filePath)

            val sb = StringBuilder()
            sb.appendLine("Source class: $qualifiedName ($relativePath)")
            sb.appendLine("Is test: ${result.isTestElement}")
            sb.appendLine()

            if (result.relatedElements.isEmpty()) {
                if (result.isTestElement) {
                    sb.appendLine("No source classes found for this test.")
                } else {
                    sb.appendLine("No test classes found for this source class.")
                }
            } else {
                val label = if (result.isTestElement) "source" else "test"
                sb.appendLine("Found ${result.relatedElements.size} $label class${if (result.relatedElements.size > 1) "es" else ""}:")
                result.relatedElements.forEachIndexed { index, info ->
                    sb.appendLine("  ${index + 1}. ${info.name} (${info.filePath})")
                }
            }

            sb.toString().trimEnd()
        }.inSmartMode(project).executeSynchronously()

        val isError = content.startsWith("Error:") || content.startsWith("Code intelligence not available")
        return ToolResult(
            content = content,
            summary = if (isError) content else "Test finder results for $filePath",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = isError
        )
    }

    private fun findClassByName(psiFile: com.intellij.psi.PsiFile, className: String): com.intellij.psi.PsiElement? {
        val classes = mutableListOf<PsiClass>()
        psiFile.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (element is PsiClass) {
                    classes.add(element)
                }
                super.visitElement(element)
            }
        })
        // Match by simple name or qualified name
        return classes.firstOrNull { it.name == className || it.qualifiedName == className }
    }

    private fun findFirstClass(psiFile: com.intellij.psi.PsiFile): com.intellij.psi.PsiElement? {
        var found: PsiClass? = null
        psiFile.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (found == null && element is PsiClass) {
                    found = element
                    return
                }
                if (found == null) {
                    super.visitElement(element)
                }
            }
        })
        return found
    }
}
