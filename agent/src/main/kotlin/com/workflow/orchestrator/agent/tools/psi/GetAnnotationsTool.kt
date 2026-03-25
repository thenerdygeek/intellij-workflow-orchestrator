package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifierListOwner
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class GetAnnotationsTool : AgentTool {
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

        val content = ReadAction.nonBlocking<String> {
            val psiClass = PsiToolUtils.findClassAnywhere(project, className)
                ?: return@nonBlocking "No class '$className' found in project."

            val fqn = psiClass.qualifiedName ?: psiClass.name ?: className

            if (memberName != null) {
                // Method lookup first, then field
                val methods = psiClass.findMethodsByName(memberName, includeInherited)
                if (methods.isNotEmpty()) {
                    val sb = StringBuilder()
                    methods.forEachIndexed { idx, method ->
                        val label = if (methods.size > 1) "$fqn.$memberName (overload ${idx + 1})" else "$fqn.$memberName"
                        sb.append(formatAnnotations("Annotations on $label:", method, includeInherited, psiClass))
                        if (idx < methods.size - 1) sb.appendLine()
                    }
                    return@nonBlocking sb.toString().trimEnd()
                }

                val field = psiClass.findFieldByName(memberName, includeInherited)
                if (field != null) {
                    return@nonBlocking formatAnnotations(
                        "Annotations on $fqn.$memberName (field):",
                        field,
                        includeInherited = false, // fields don't have inherited annotations
                        ownerClass = null
                    )
                }

                return@nonBlocking "No method or field '$memberName' found in '$fqn'."
            }

            // Class-level annotations
            formatAnnotations("Annotations on $fqn:", psiClass, includeInherited, psiClass)
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
     * Collect and format annotations from [owner], optionally walking the superclass chain.
     * [ownerClass] is used only for the superclass walk and may be null for fields.
     */
    private fun formatAnnotations(
        header: String,
        owner: PsiModifierListOwner,
        includeInherited: Boolean,
        ownerClass: PsiClass?
    ): String {
        val sb = StringBuilder()
        sb.appendLine(header)

        // Direct annotations on this element
        val direct = owner.annotations.toList()
        if (direct.isEmpty() && !includeInherited) {
            sb.appendLine("  (none)")
            return sb.toString()
        }
        direct.forEach { annotation ->
            sb.append(formatAnnotation(annotation, indent = "  "))
        }

        // Walk the superclass chain when requested
        if (includeInherited && ownerClass != null) {
            val seen = mutableSetOf<String>()
            direct.forEach { a -> a.qualifiedName?.let { seen.add(it) } }

            var cursor: PsiClass? = ownerClass.superClass
            while (cursor != null && cursor.qualifiedName != "java.lang.Object") {
                val inherited = cursor.annotations.filter { a ->
                    a.qualifiedName != null && seen.add(a.qualifiedName!!)
                }
                if (inherited.isNotEmpty()) {
                    val superFqn = cursor.qualifiedName ?: cursor.name ?: "superclass"
                    sb.appendLine("  [Inherited from $superFqn]")
                    inherited.forEach { annotation ->
                        sb.append(formatAnnotation(annotation, indent = "    "))
                    }
                }
                cursor = cursor.superClass
            }
        }

        val result = sb.toString().trimEnd()
        return if (result == header.trimEnd()) "$result\n  (none)" else result
    }

    /**
     * Format a single annotation as:
     *   @ShortName(param1=value1, param2=value2)
     *     FQN: fully.qualified.AnnotationName
     */
    private fun formatAnnotation(annotation: PsiAnnotation, indent: String): String {
        val sb = StringBuilder()
        val shortName = annotation.qualifiedName?.substringAfterLast('.') ?: annotation.text
        val fqn = annotation.qualifiedName

        // Collect parameter values
        val paramList = annotation.parameterList.attributes
        val paramText = if (paramList.isEmpty()) {
            ""
        } else {
            "(" + paramList.joinToString(", ") { attr ->
                val name = attr.name ?: "value"
                val value = attr.literalValue
                    ?: attr.value?.text
                    ?: "?"
                "$name=$value"
            } + ")"
        }

        sb.appendLine("$indent@$shortName$paramText")
        if (fqn != null) {
            sb.appendLine("$indent  FQN: $fqn")
        }
        return sb.toString()
    }
}
