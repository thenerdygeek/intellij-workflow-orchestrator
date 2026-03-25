package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class SpringBootConfigPropertiesTool : AgentTool {
    override val name = "spring_boot_config_properties"
    override val description = "List @ConfigurationProperties classes — the type-safe way Spring Boot binds " +
        "properties. Shows: prefix, field names with types, default values, @Valid/@NotNull constraints."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "prefix" to ParameterProperty(
                type = "string",
                description = "Optional: filter by prefix. E.g., 'app.security' to show only that binding class."
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Optional: filter by class name (simple or qualified). E.g., 'SecurityProperties'."
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val prefixFilter = params["prefix"]?.jsonPrimitive?.contentOrNull
        val classNameFilter = params["class_name"]?.jsonPrimitive?.contentOrNull

        val content: String = try {
            ReadAction.nonBlocking<String> {
                collectConfigProperties(project, prefixFilter, classNameFilter)
            }.inSmartMode(project).executeSynchronously()
        } catch (e: NoClassDefFoundError) {
            return ToolResult(
                "Spring plugin not available.",
                "No Spring",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } catch (e: Exception) {
            return ToolResult(
                "Error: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val filterDesc = listOfNotNull(
            prefixFilter?.let { "prefix=$it" },
            classNameFilter?.let { "class=$it" }
        ).joinToString(", ")

        return ToolResult(
            content = content,
            summary = "Spring Boot @ConfigurationProperties listed${if (filterDesc.isNotEmpty()) " ($filterDesc)" else ""}",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun collectConfigProperties(
        project: Project,
        prefixFilter: String?,
        classNameFilter: String?
    ): String {
        val scope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val annotationFqn = "org.springframework.boot.context.properties.ConfigurationProperties"
        val annotationClass = facade.findClass(annotationFqn, allScope)
            ?: return "No @ConfigurationProperties classes found — Spring Boot not on classpath."

        val annotatedClasses = AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll()

        if (annotatedClasses.isEmpty()) {
            return "No @ConfigurationProperties classes found in project."
        }

        val entries = annotatedClasses.mapNotNull { cls -> buildEntry(project, cls) }

        // Apply filters
        val filtered = entries.filter { entry ->
            val matchesPrefix = prefixFilter == null ||
                entry.prefix.contains(prefixFilter, ignoreCase = true)
            val matchesClass = classNameFilter == null ||
                entry.simpleClassName.contains(classNameFilter, ignoreCase = true) ||
                entry.qualifiedName.contains(classNameFilter, ignoreCase = true)
            matchesPrefix && matchesClass
        }

        if (filtered.isEmpty()) {
            val filterDesc = listOfNotNull(
                prefixFilter?.let { "prefix='$it'" },
                classNameFilter?.let { "class='$it'" }
            ).joinToString(", ")
            return "No @ConfigurationProperties classes found matching $filterDesc."
        }

        val sorted = filtered.sortedWith(compareBy({ it.prefix }, { it.simpleClassName }))

        val sb = StringBuilder("@ConfigurationProperties classes (${sorted.size}):\n")
        for (entry in sorted) {
            sb.appendLine()
            sb.appendLine("@ConfigurationProperties(prefix = \"${entry.prefix}\")")
            sb.appendLine("class ${entry.simpleClassName}  (${entry.filePath})")
            for (field in entry.fields) {
                sb.appendLine(formatField(entry.prefix, field))
            }
        }

        if (filtered.size < entries.size) {
            sb.appendLine()
            sb.append("(Showing ${filtered.size} of ${entries.size} total)")
        }

        return sb.toString().trimEnd()
    }

    private fun buildEntry(project: Project, cls: PsiClass): ConfigPropertiesEntry? {
        val fqn = cls.qualifiedName ?: return null
        val simpleName = cls.name ?: return null

        val annotation = cls.getAnnotation("org.springframework.boot.context.properties.ConfigurationProperties")
            ?: return null

        val prefix = extractPrefix(annotation)

        // Collect non-static fields only
        val fields = cls.fields
            .filter { !it.hasModifierProperty(PsiModifier.STATIC) }
            .map { buildFieldInfo(it) }

        val filePath = cls.containingFile?.virtualFile?.path
            ?.let { PsiToolUtils.relativePath(project, it) }
            ?: "(unknown)"

        return ConfigPropertiesEntry(
            qualifiedName = fqn,
            simpleClassName = simpleName,
            prefix = prefix,
            fields = fields,
            filePath = filePath
        )
    }

    /**
     * Extracts the prefix from @ConfigurationProperties.
     * Handles both @ConfigurationProperties("app.foo") and @ConfigurationProperties(prefix = "app.foo").
     */
    private fun extractPrefix(annotation: PsiAnnotation): String {
        // Try "prefix" attribute first, then fall back to "value"
        for (attrName in listOf("prefix", "value")) {
            val attrValue = annotation.findAttributeValue(attrName) ?: continue
            val text = attrValue.text ?: continue
            val stripped = text.removeSurrounding("\"")
            if (stripped.isNotBlank() && stripped != text) return stripped
        }
        return ""
    }

    private fun buildFieldInfo(field: PsiField): FieldInfo {
        val name = field.name
        val type = field.type.presentableText
        val defaultValue = field.initializer?.text

        // Collect relevant validation/nested annotations
        val constraints = mutableListOf<String>()
        for (annotation in field.annotations) {
            val shortName = annotation.qualifiedName?.substringAfterLast('.') ?: continue
            when (shortName) {
                "NotNull", "NotBlank", "NotEmpty" -> constraints.add("@$shortName")
                "Min" -> {
                    val v = extractAnnotationStringValue(annotation, "value")
                    constraints.add(if (v != null) "@Min($v)" else "@Min")
                }
                "Max" -> {
                    val v = extractAnnotationStringValue(annotation, "value")
                    constraints.add(if (v != null) "@Max($v)" else "@Max")
                }
                "Size" -> {
                    val min = extractAnnotationStringValue(annotation, "min")
                    val max = extractAnnotationStringValue(annotation, "max")
                    val args = listOfNotNull(min?.let { "min=$it" }, max?.let { "max=$it" })
                        .joinToString(", ")
                    constraints.add(if (args.isNotEmpty()) "@Size($args)" else "@Size")
                }
                "Pattern" -> {
                    val regexp = extractAnnotationStringValue(annotation, "regexp")
                    constraints.add(if (regexp != null) "@Pattern(\"$regexp\")" else "@Pattern")
                }
                "Email" -> constraints.add("@Email")
                "Valid" -> constraints.add("@Valid")
                "Validated" -> constraints.add("@Validated")
                "Positive" -> constraints.add("@Positive")
                "PositiveOrZero" -> constraints.add("@PositiveOrZero")
                "Negative" -> constraints.add("@Negative")
                "NegativeOrZero" -> constraints.add("@NegativeOrZero")
                "DecimalMin" -> {
                    val v = extractAnnotationStringValue(annotation, "value")
                    constraints.add(if (v != null) "@DecimalMin(\"$v\")" else "@DecimalMin")
                }
                "DecimalMax" -> {
                    val v = extractAnnotationStringValue(annotation, "value")
                    constraints.add(if (v != null) "@DecimalMax(\"$v\")" else "@DecimalMax")
                }
            }
        }

        return FieldInfo(name = name, type = type, defaultValue = defaultValue, constraints = constraints)
    }

    private fun formatField(prefix: String, field: FieldInfo): String {
        val qualifiedName = if (prefix.isNotEmpty()) "$prefix.${field.name}" else field.name
        val sb = StringBuilder("  $qualifiedName: ${field.type}")
        if (field.defaultValue != null) {
            sb.append(" = ${field.defaultValue}")
        }
        if (field.constraints.isNotEmpty()) {
            sb.append("  ${field.constraints.joinToString(" ")}")
        }
        return sb.toString()
    }

    private fun extractAnnotationStringValue(annotation: PsiAnnotation, attr: String): String? {
        val value = annotation.findAttributeValue(attr) ?: return null
        val text = value.text ?: return null
        val stripped = text.removeSurrounding("\"")
        return if (stripped != text) stripped else text.trim().takeIf { it.isNotBlank() }
    }

    private data class ConfigPropertiesEntry(
        val qualifiedName: String,
        val simpleClassName: String,
        val prefix: String,
        val fields: List<FieldInfo>,
        val filePath: String
    )

    private data class FieldInfo(
        val name: String,
        val type: String,
        val defaultValue: String?,
        val constraints: List<String>
    )
}
