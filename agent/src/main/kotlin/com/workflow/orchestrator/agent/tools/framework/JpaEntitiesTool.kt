package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
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
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JpaEntitiesTool : AgentTool {
    override val name = "jpa_entities"
    override val description = "List JPA/Hibernate entities: class name, table name, fields with column mappings, relationships (@OneToMany, @ManyToOne, etc.)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "entity" to ParameterProperty(type = "string", description = "Optional: specific entity class name. If omitted, lists all entities.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    companion object {
        private val ENTITY_ANNOTATIONS = listOf(
            "jakarta.persistence.Entity",
            "javax.persistence.Entity"
        )
        private val TABLE_ANNOTATIONS = listOf(
            "jakarta.persistence.Table",
            "javax.persistence.Table"
        )
        private val RELATIONSHIP_ANNOTATIONS = listOf(
            "OneToMany", "ManyToOne", "OneToOne", "ManyToMany"
        )
        private val RELATIONSHIP_FQN_PREFIXES = listOf(
            "jakarta.persistence.",
            "javax.persistence."
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (DumbService.isDumb(project)) return PsiToolUtils.dumbModeError()

        val entityFilter = params["entity"]?.jsonPrimitive?.content

        return try {
            val content = ReadAction.nonBlocking<String> {
                val scope = GlobalSearchScope.projectScope(project)
                val allScope = GlobalSearchScope.allScope(project)
                val facade = JavaPsiFacade.getInstance(project)

                // Find the @Entity annotation class (try jakarta first, then javax)
                val entityAnnotationClass = ENTITY_ANNOTATIONS.firstNotNullOfOrNull { fqn ->
                    facade.findClass(fqn, allScope)
                } ?: return@nonBlocking "JPA not found in project dependencies. Ensure jakarta.persistence or javax.persistence is on the classpath."

                val entities = AnnotatedElementsSearch.searchPsiClasses(entityAnnotationClass, scope).findAll()

                if (entities.isEmpty()) {
                    return@nonBlocking "No @Entity classes found in project."
                }

                // Filter to specific entity if requested
                val targetEntities = if (entityFilter != null) {
                    val filtered = entities.filter { cls ->
                        cls.name.equals(entityFilter, ignoreCase = true) ||
                            cls.qualifiedName.equals(entityFilter, ignoreCase = true)
                    }
                    if (filtered.isEmpty()) {
                        return@nonBlocking "Entity '$entityFilter' not found. Available entities: ${entities.mapNotNull { it.name }.sorted().joinToString(", ")}"
                    }
                    filtered
                } else {
                    entities.toList()
                }

                buildString {
                    appendLine("JPA Entities (${targetEntities.size}):")
                    appendLine()

                    for (psiClass in targetEntities.sortedBy { it.name }) {
                        formatEntity(psiClass, this, detailed = entityFilter != null)
                    }
                }
            }.inSmartMode(project).executeSynchronously()

            ToolResult(
                content = content,
                summary = if (entityFilter != null) "JPA entity: $entityFilter" else "JPA entities",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error scanning JPA entities: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun formatEntity(psiClass: PsiClass, sb: StringBuilder, detailed: Boolean) {
        val className = psiClass.qualifiedName ?: psiClass.name ?: "?"
        val tableName = getTableName(psiClass) ?: psiClass.name?.lowercase() ?: "?"
        val file = psiClass.containingFile?.virtualFile?.path ?: ""

        sb.appendLine("@Entity ${psiClass.name} (table: $tableName)")
        if (detailed) {
            sb.appendLine("  Class: $className")
            sb.appendLine("  File: $file")
        }

        // Fields
        val allFields = psiClass.allFields.filter { field ->
            !field.hasModifierProperty(PsiModifier.STATIC) &&
                !field.hasModifierProperty(PsiModifier.TRANSIENT)
        }

        val idFields = mutableListOf<PsiField>()
        val columnFields = mutableListOf<PsiField>()
        val relationshipFields = mutableListOf<PsiField>()

        for (field in allFields) {
            when {
                hasAnnotation(field, "Id") -> idFields.add(field)
                hasRelationshipAnnotation(field) -> relationshipFields.add(field)
                else -> columnFields.add(field)
            }
        }

        // ID fields
        if (idFields.isNotEmpty()) {
            sb.appendLine("  ID:")
            for (field in idFields) {
                val colName = getColumnName(field) ?: field.name
                val genStrategy = getGenerationStrategy(field)
                val gen = if (genStrategy != null) " ($genStrategy)" else ""
                sb.appendLine("    ${field.type.presentableText} ${field.name} -> $colName$gen")
            }
        }

        // Regular columns
        if (detailed && columnFields.isNotEmpty()) {
            sb.appendLine("  Columns:")
            for (field in columnFields) {
                val colName = getColumnName(field) ?: field.name
                sb.appendLine("    ${field.type.presentableText} ${field.name} -> $colName")
            }
        } else if (columnFields.isNotEmpty()) {
            sb.appendLine("  Columns: ${columnFields.size} fields")
        }

        // Relationships
        if (relationshipFields.isNotEmpty()) {
            sb.appendLine("  Relationships:")
            for (field in relationshipFields) {
                val relType = getRelationshipType(field)
                val targetType = field.type.presentableText
                sb.appendLine("    @$relType ${field.name}: $targetType")
            }
        }

        sb.appendLine()
    }

    private fun getTableName(psiClass: PsiClass): String? {
        for (prefix in TABLE_ANNOTATIONS) {
            val annotation = psiClass.getAnnotation(prefix)
            if (annotation != null) {
                return getAnnotationStringValue(annotation, "name")
            }
        }
        return null
    }

    private fun getColumnName(field: PsiField): String? {
        for (prefix in listOf("jakarta.persistence.Column", "javax.persistence.Column")) {
            val annotation = field.getAnnotation(prefix)
            if (annotation != null) {
                return getAnnotationStringValue(annotation, "name")
            }
        }
        return null
    }

    private fun getGenerationStrategy(field: PsiField): String? {
        for (prefix in listOf("jakarta.persistence.GeneratedValue", "javax.persistence.GeneratedValue")) {
            val annotation = field.getAnnotation(prefix)
            if (annotation != null) {
                return getAnnotationStringValue(annotation, "strategy") ?: "AUTO"
            }
        }
        return null
    }

    private fun hasAnnotation(field: PsiField, simpleName: String): Boolean {
        return field.getAnnotation("jakarta.persistence.$simpleName") != null ||
            field.getAnnotation("javax.persistence.$simpleName") != null
    }

    private fun hasRelationshipAnnotation(field: PsiField): Boolean {
        return RELATIONSHIP_ANNOTATIONS.any { rel ->
            RELATIONSHIP_FQN_PREFIXES.any { prefix ->
                field.getAnnotation("$prefix$rel") != null
            }
        }
    }

    private fun getRelationshipType(field: PsiField): String {
        for (rel in RELATIONSHIP_ANNOTATIONS) {
            for (prefix in RELATIONSHIP_FQN_PREFIXES) {
                if (field.getAnnotation("$prefix$rel") != null) return rel
            }
        }
        return "?"
    }

    private fun getAnnotationStringValue(annotation: PsiAnnotation, attribute: String): String? {
        val value = annotation.findAttributeValue(attribute) ?: return null
        val text = value.text.removeSurrounding("\"")
        return if (text.isBlank() || text == "\"\"") null else text
    }
}
