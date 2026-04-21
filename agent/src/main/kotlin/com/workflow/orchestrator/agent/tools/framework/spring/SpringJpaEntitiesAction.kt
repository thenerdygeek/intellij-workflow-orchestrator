package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `spring(action=jpa_entities, entity?)` — JPA entity intelligence
 * sourced from the IntelliJ Persistence plugin's model rather than raw
 * annotation scanning. Emits inheritance strategy, relationship
 * cardinality (ONE_TO_MANY / MANY_TO_ONE / ...), fetch mode, cascade
 * types, `mappedBy`, and named queries — all metadata the previous
 * PSI-only version couldn't provide without duplicating what the
 * Persistence plugin already indexes.
 *
 * Resolver self-gates: on Community / no-Persistence-plugin, the
 * resolver's public methods return empty and this action reports that
 * gracefully.
 */
internal suspend fun executeJpaEntities(params: JsonObject, project: Project): ToolResult {
    if (DumbService.isDumb(project)) return PsiToolUtils.dumbModeError()

    val entityFilter = params["entity"]?.jsonPrimitive?.content

    return try {
        val content = ReadAction.nonBlocking<String> {
            renderEntities(project, entityFilter)
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

private fun renderEntities(project: Project, filter: String?): String {
    val all = PersistenceModelResolver.allEntities(project)
    if (all.isEmpty()) {
        return "No JPA entities found. Ensure the Persistence plugin is enabled and a JPA facet is attached to the module."
    }
    val target = if (filter != null) {
        val matched = all.filter { entity ->
            entity.className.equals(filter, ignoreCase = true) ||
                entity.className.substringAfterLast('.').equals(filter, ignoreCase = true) ||
                entity.entityName?.equals(filter, ignoreCase = true) == true
        }
        if (matched.isEmpty()) {
            return "Entity '$filter' not found. Available: " +
                all.joinToString(", ") { it.className.substringAfterLast('.') }
        }
        matched
    } else {
        all
    }

    return buildString {
        appendLine("JPA Entities (${target.size}):")
        for (entity in target.sortedBy { it.className }) {
            appendLine()
            val displayName = entity.entityName ?: entity.className.substringAfterLast('.')
            val tableSuffix = entity.tableName?.let { " (table: $it)" } ?: ""
            appendLine("@Entity $displayName$tableSuffix")
            appendLine("  Class: ${entity.className}")
            entity.inheritanceStrategy?.let { appendLine("  Inheritance: $it") }
            if (entity.relationships.isNotEmpty()) {
                appendLine("  Relationships:")
                for (rel in entity.relationships) {
                    val detail = buildList {
                        rel.fetchMode?.let { add(it) }
                        if (rel.cascadeTypes.isNotEmpty()) add("cascade=${rel.cascadeTypes.joinToString("|")}")
                        rel.mappedBy?.let { add("mappedBy=$it") }
                        if (rel.isInverseSide) add("inverse")
                    }.joinToString(", ")
                    val suffix = if (detail.isNotEmpty()) " ($detail)" else ""
                    val relTarget = rel.targetEntity?.substringAfterLast('.') ?: "?"
                    appendLine("    ${rel.cardinality} ${rel.fieldName} → $relTarget$suffix")
                }
            }
            if (entity.namedQueries.isNotEmpty()) {
                appendLine("  Named queries:")
                for ((name, query) in entity.namedQueries) {
                    val truncated = if (query.length > 120) "${query.take(120)}…" else query
                    appendLine("    $name: $truncated")
                }
            }
        }
    }
}
