package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.DefinitionInfo
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class FindDefinitionTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "find_definition"
    override val description = "Find the declaration/definition location of a class, method, or field."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "symbol" to ParameterProperty(type = "string", description = "Symbol name to find (class FQN, method name, or field name)"),
            "class_name" to ParameterProperty(type = "string", description = "Optional: class name for disambiguation when multiple symbols share the same name")
        ),
        required = listOf("symbol")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val symbol = params["symbol"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'symbol' parameter required", "Error: missing symbol", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val classNameHint = params["class_name"]?.jsonPrimitive?.content

        // Resolve the provider for Java/Kotlin (the only languages currently supported)
        val provider = registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")
            ?: return ToolResult(
                "Code intelligence not available — no language provider registered",
                "Error: no provider",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val content = ReadAction.nonBlocking<String> {
            // If class_name hint provided, search within that class first using "class#symbol" syntax
            if (classNameHint != null) {
                val element = provider.findSymbol(project, "$classNameHint#$symbol")
                if (element != null) {
                    val info = provider.getDefinitionInfo(element)
                    if (info != null) {
                        return@nonBlocking formatDefinitionOutput(element, info, symbol)
                    }
                }
            }

            // General symbol lookup (handles FQN, Class#method, bare names)
            val element = provider.findSymbol(project, symbol)
            if (element != null) {
                val info = provider.getDefinitionInfo(element)
                if (info != null) {
                    // Check for disambiguation hint
                    val disambiguationNote = if (element is PsiMethod) {
                        val scope = GlobalSearchScope.projectScope(project)
                        val cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
                        val allMethods = cache.getMethodsByName(element.name, scope)
                        if (allMethods.size > 1)
                            "\n\n(${allMethods.size - 1} other method(s) with same name — provide class_name to disambiguate)"
                        else ""
                    } else ""
                    return@nonBlocking formatDefinitionOutput(element, info, symbol) + disambiguationNote
                }
            }

            "No definition found for '$symbol'"
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Definition of '$symbol'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    /**
     * Format the definition output to match the original tool's output format exactly.
     */
    private fun formatDefinitionOutput(
        element: com.intellij.psi.PsiElement,
        info: DefinitionInfo,
        originalSymbol: String
    ): String {
        return when (element) {
            is PsiClass -> {
                val qualifiedName = element.qualifiedName ?: element.name ?: originalSymbol
                val skeleton = info.skeleton
                "Definition of '$qualifiedName':\n" +
                    "  File: ${info.filePath}\n" +
                    "  Line: ${info.line}" +
                    if (skeleton != null) "\n\n$skeleton" else ""
            }
            is PsiMethod -> {
                val qualifiedRef = "${element.containingClass?.qualifiedName ?: ""}#${element.name}"
                "Definition of '$qualifiedRef':\n" +
                    "  File: ${info.filePath}\n" +
                    "  Line: ${info.line}\n" +
                    "  Signature: ${info.signature}"
            }
            is PsiField -> {
                val qualifiedRef = "${element.containingClass?.qualifiedName ?: ""}#${element.name}"
                "Definition of '$qualifiedRef':\n" +
                    "  File: ${info.filePath}\n" +
                    "  Line: ${info.line}\n" +
                    "  Type: ${element.type.presentableText}"
            }
            else -> {
                "Definition of '$originalSymbol':\n" +
                    "  File: ${info.filePath}\n" +
                    "  Line: ${info.line}\n" +
                    "  Signature: ${info.signature}"
            }
        }
    }
}
