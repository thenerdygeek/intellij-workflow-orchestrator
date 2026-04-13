package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.agent.ide.MetadataInfo
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class GetAnnotationsTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "get_annotations"
    override val description =
        "List all annotations on a class, method, or field — with their parameter values. " +
        "Useful for understanding Spring config, JPA mappings, validation rules, security annotations."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "class_name" to ParameterProperty(
                type = "string",
                description = "Class name (simple or fully qualified)"
            ),
            "member" to ParameterProperty(
                type = "string",
                description = "Method or field name within the class. If omitted, shows class-level annotations."
            ),
            "include_inherited" to ParameterProperty(
                type = "boolean",
                description = "Include annotations from superclasses (default: false)"
            )
        ),
        required = listOf("class_name")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'class_name' parameter required",
                "Error: missing class_name",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val memberName = params["member"]?.jsonPrimitive?.content
        val includeInherited = params["include_inherited"]?.jsonPrimitive?.boolean ?: false

        // Resolve provider: try all registered providers until one finds the symbol
        val allProviders = registry.allProviders()
        if (allProviders.isEmpty()) {
            return ToolResult(
                "Code intelligence not available — no language provider registered",
                "Error: no provider",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val content = ReadAction.nonBlocking<String> {
            // Find the class via provider — try each provider until one finds the symbol
            val (provider, psiElement) = allProviders.firstNotNullOfOrNull { p ->
                p.findSymbol(project, className)?.let { p to it }
            } ?: return@nonBlocking "No class '$className' found in project."

            val psiClass = psiElement as? PsiClass
                ?: return@nonBlocking "No class '$className' found in project."

            val fqn = psiClass.qualifiedName ?: psiClass.name ?: className

            if (memberName != null) {
                // Method lookup first, then field
                val methods = psiClass.findMethodsByName(memberName, includeInherited)
                if (methods.isNotEmpty()) {
                    val sb = StringBuilder()
                    methods.forEachIndexed { idx, method ->
                        val label = if (methods.size > 1) "$fqn.$memberName (overload ${idx + 1})" else "$fqn.$memberName"
                        val metadata = provider.getMetadata(method, includeInherited)
                        sb.append(formatMetadataOutput("Annotations on $label:", metadata))
                        if (idx < methods.size - 1) sb.appendLine()
                    }
                    return@nonBlocking sb.toString().trimEnd()
                }

                val field = psiClass.findFieldByName(memberName, includeInherited)
                if (field != null) {
                    val metadata = provider.getMetadata(field, false) // fields don't have inherited annotations
                    return@nonBlocking formatMetadataOutput("Annotations on $fqn.$memberName (field):", metadata)
                }

                return@nonBlocking "No method or field '$memberName' found in '$fqn'."
            }

            // Class-level annotations
            val metadata = provider.getMetadata(psiClass, includeInherited)
            formatMetadataOutput("Annotations on $fqn:", metadata)
        }.inSmartMode(project).executeSynchronously()

        val summary = if (memberName != null) {
            "Annotations on $className.$memberName"
        } else {
            "Annotations on $className"
        }

        return ToolResult(
            content = content,
            summary = summary,
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    /**
     * Format the list of [MetadataInfo] into the original output format.
     */
    private fun formatMetadataOutput(header: String, metadata: List<MetadataInfo>): String {
        val sb = StringBuilder()
        sb.appendLine(header)

        if (metadata.isEmpty()) {
            sb.appendLine("  (none)")
            return sb.toString().trimEnd()
        }

        val direct = metadata.filter { !it.isInherited }
        val inherited = metadata.filter { it.isInherited }

        direct.forEach { info ->
            sb.append(formatSingleAnnotation(info, "  "))
        }

        if (inherited.isNotEmpty()) {
            // Group inherited annotations by their source (approximated from qualified name)
            inherited.forEach { info ->
                sb.append(formatSingleAnnotation(info, "    "))
            }
        }

        return sb.toString().trimEnd()
    }

    private fun formatSingleAnnotation(info: MetadataInfo, indent: String): String {
        val sb = StringBuilder()
        val shortName = info.qualifiedName.substringAfterLast('.')
        val paramText = if (info.parameters.isEmpty()) {
            ""
        } else {
            "(" + info.parameters.entries.joinToString(", ") { (k, v) -> "$k=$v" } + ")"
        }
        sb.appendLine("$indent@$shortName$paramText")
        sb.appendLine("$indent  FQN: ${info.qualifiedName}")
        return sb.toString()
    }
}
