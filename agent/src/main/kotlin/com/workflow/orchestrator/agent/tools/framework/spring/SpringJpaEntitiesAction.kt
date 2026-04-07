package com.workflow.orchestrator.agent.tools.framework.spring

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
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val jpaEntityAnnotations = listOf(
    "jakarta.persistence.Entity",
    "javax.persistence.Entity"
)
private val jpaTableAnnotations = listOf(
    "jakarta.persistence.Table",
    "javax.persistence.Table"
)
private val jpaRelationshipAnnotations = listOf(
    "OneToMany", "ManyToOne", "OneToOne", "ManyToMany"
)
private val jpaRelationshipFqnPrefixes = listOf(
    "jakarta.persistence.",
    "javax.persistence."
)

internal suspend fun executeJpaEntities(params: JsonObject, project: Project): ToolResult {
    if (DumbService.isDumb(project)) return PsiToolUtils.dumbModeError()

    val entityFilter = params["entity"]?.jsonPrimitive?.content

    return try {
        val content = ReadAction.nonBlocking<String> {
            val scope = GlobalSearchScope.projectScope(project)
            val allScope = GlobalSearchScope.allScope(project)
            val facade = JavaPsiFacade.getInstance(project)

            val entityAnnotationClass = jpaEntityAnnotations.firstNotNullOfOrNull { fqn ->
                facade.findClass(fqn, allScope)
            } ?: return@nonBlocking "JPA not found in project dependencies. Ensure jakarta.persistence or javax.persistence is on the classpath."

            val entities = AnnotatedElementsSearch.searchPsiClasses(entityAnnotationClass, scope).findAll()

            if (entities.isEmpty()) {
                return@nonBlocking "No @Entity classes found in project."
            }

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
                    formatJpaEntity(psiClass, this, detailed = entityFilter != null)
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

private fun formatJpaEntity(psiClass: PsiClass, sb: StringBuilder, detailed: Boolean) {
    val className = psiClass.qualifiedName ?: psiClass.name ?: "?"
    val tableName = getJpaTableName(psiClass) ?: psiClass.name?.lowercase() ?: "?"
    val file = psiClass.containingFile?.virtualFile?.path ?: ""

    sb.appendLine("@Entity ${psiClass.name} (table: $tableName)")
    if (detailed) {
        sb.appendLine("  Class: $className")
        sb.appendLine("  File: $file")
    }

    val allFields = psiClass.allFields.filter { field ->
        !field.hasModifierProperty(PsiModifier.STATIC) &&
            !field.hasModifierProperty(PsiModifier.TRANSIENT)
    }

    val idFields = mutableListOf<PsiField>()
    val columnFields = mutableListOf<PsiField>()
    val relationshipFields = mutableListOf<PsiField>()

    for (field in allFields) {
        when {
            hasJpaAnnotation(field, "Id") -> idFields.add(field)
            hasJpaRelationshipAnnotation(field) -> relationshipFields.add(field)
            else -> columnFields.add(field)
        }
    }

    if (idFields.isNotEmpty()) {
        sb.appendLine("  ID:")
        for (field in idFields) {
            val colName = getJpaColumnName(field) ?: field.name
            val genStrategy = getJpaGenerationStrategy(field)
            val gen = if (genStrategy != null) " ($genStrategy)" else ""
            sb.appendLine("    ${field.type.presentableText} ${field.name} -> $colName$gen")
        }
    }

    if (detailed && columnFields.isNotEmpty()) {
        sb.appendLine("  Columns:")
        for (field in columnFields) {
            val colName = getJpaColumnName(field) ?: field.name
            sb.appendLine("    ${field.type.presentableText} ${field.name} -> $colName")
        }
    } else if (columnFields.isNotEmpty()) {
        sb.appendLine("  Columns: ${columnFields.size} fields")
    }

    if (relationshipFields.isNotEmpty()) {
        sb.appendLine("  Relationships:")
        for (field in relationshipFields) {
            val relType = getJpaRelationshipType(field)
            val targetType = field.type.presentableText
            sb.appendLine("    @$relType ${field.name}: $targetType")
        }
    }

    sb.appendLine()
}

private fun getJpaTableName(psiClass: PsiClass): String? {
    for (prefix in jpaTableAnnotations) {
        val annotation = psiClass.getAnnotation(prefix)
        if (annotation != null) {
            return getJpaAnnotationStringValue(annotation, "name")
        }
    }
    return null
}

private fun getJpaColumnName(field: PsiField): String? {
    for (prefix in listOf("jakarta.persistence.Column", "javax.persistence.Column")) {
        val annotation = field.getAnnotation(prefix)
        if (annotation != null) {
            return getJpaAnnotationStringValue(annotation, "name")
        }
    }
    return null
}

private fun getJpaGenerationStrategy(field: PsiField): String? {
    for (prefix in listOf("jakarta.persistence.GeneratedValue", "javax.persistence.GeneratedValue")) {
        val annotation = field.getAnnotation(prefix)
        if (annotation != null) {
            return getJpaAnnotationStringValue(annotation, "strategy") ?: "AUTO"
        }
    }
    return null
}

private fun hasJpaAnnotation(field: PsiField, simpleName: String): Boolean {
    return field.getAnnotation("jakarta.persistence.$simpleName") != null ||
        field.getAnnotation("javax.persistence.$simpleName") != null
}

private fun hasJpaRelationshipAnnotation(field: PsiField): Boolean {
    return jpaRelationshipAnnotations.any { rel ->
        jpaRelationshipFqnPrefixes.any { prefix ->
            field.getAnnotation("$prefix$rel") != null
        }
    }
}

private fun getJpaRelationshipType(field: PsiField): String {
    for (rel in jpaRelationshipAnnotations) {
        for (prefix in jpaRelationshipFqnPrefixes) {
            if (field.getAnnotation("$prefix$rel") != null) return rel
        }
    }
    return "?"
}

private fun getJpaAnnotationStringValue(annotation: PsiAnnotation, attribute: String): String? {
    val value = annotation.findAttributeValue(attribute) ?: return null
    val text = value.text.removeSurrounding("\"")
    return if (text.isBlank() || text == "\"\"") null else text
}
