package com.workflow.orchestrator.jira.workflow

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.workflow.WorkflowIntent
import com.workflow.orchestrator.jira.api.dto.JiraTransition

data class TransitionFieldInfo(
    val key: String,
    val name: String,
    val required: Boolean,
    val type: String,
    val allowedValues: List<String>,
    val hasDefaultValue: Boolean,
    val autoCompleteUrl: String? = null
)

enum class ResolutionMethod {
    EXPLICIT_MAPPING,
    LEARNED_MAPPING,
    NAME_MATCH,
    CATEGORY_MATCH,
    USER_SELECTED
}

data class ResolvedTransition(
    val transitionId: String,
    val transitionName: String,
    val targetStatusName: String,
    val requiredFields: List<TransitionFieldInfo>,
    val resolution: ResolutionMethod
)

object IntentResolver {

    private val log = Logger.getInstance(IntentResolver::class.java)

    fun resolveFromTransitions(
        intent: WorkflowIntent,
        transitions: List<JiraTransition>,
        mappingStore: TransitionMappingStore,
        projectKey: String,
        issueTypeId: String? = null
    ): ApiResult<ResolvedTransition> {

        log.debug("[Jira:Workflow] Resolving intent '${intent.name}' for project '$projectKey' (issueType=$issueTypeId)")
        log.debug("[Jira:Workflow] Available transitions: ${transitions.joinToString { "'${it.name}' (id=${it.id})" }}")

        // Step 1: Empty transitions → Error
        if (transitions.isEmpty()) {
            log.error("[Jira:Workflow] No transitions available for intent '${intent.displayName}'")
            return ApiResult.Error(
                type = ErrorType.NOT_FOUND,
                message = "No transitions available for intent '${intent.displayName}'"
            )
        }

        // Step 2: Check explicit mapping in store → match against available transitions
        log.debug("[Jira:Workflow] Step 2: Checking explicit mapping")
        val explicitMapping = mappingStore.getMapping(intent.name, projectKey, issueTypeId)
            ?.takeIf { it.source == "explicit" }

        if (explicitMapping != null) {
            log.debug("[Jira:Workflow] Found explicit mapping: '${explicitMapping.transitionName}'")
            val matched = transitions.find {
                it.name.equals(explicitMapping.transitionName, ignoreCase = true)
            }
            if (matched != null) {
                log.info("[Jira:Workflow] Resolved '${intent.name}' via EXPLICIT_MAPPING -> '${matched.name}' (id=${matched.id})")
                return ApiResult.Success(matched.toResolved(ResolutionMethod.EXPLICIT_MAPPING))
            }
            // Explicit mapping target not found in available transitions — fall through
            log.debug("[Jira:Workflow] Explicit mapping target '${explicitMapping.transitionName}' not in available transitions, falling through")
        }

        // Step 3: Check learned mapping in store → match against available transitions
        log.debug("[Jira:Workflow] Step 3: Checking learned mapping")
        val learnedMapping = mappingStore.getMapping(intent.name, projectKey, issueTypeId)
            ?.takeIf { it.source == "learned" }

        if (learnedMapping != null) {
            log.debug("[Jira:Workflow] Found learned mapping: '${learnedMapping.transitionName}'")
            val matched = transitions.find {
                it.name.equals(learnedMapping.transitionName, ignoreCase = true)
            }
            if (matched != null) {
                log.info("[Jira:Workflow] Resolved '${intent.name}' via LEARNED_MAPPING -> '${matched.name}' (id=${matched.id})")
                return ApiResult.Success(matched.toResolved(ResolutionMethod.LEARNED_MAPPING))
            }
            log.debug("[Jira:Workflow] Learned mapping target '${learnedMapping.transitionName}' not in available transitions, falling through")
        }

        // Step 4: Name matching — iterate intent.defaultNames, find case-insensitive match
        log.debug("[Jira:Workflow] Step 4: Trying name matching with defaultNames=${intent.defaultNames}")
        for (defaultName in intent.defaultNames) {
            val matched = transitions.find {
                it.name.equals(defaultName, ignoreCase = true)
            }
            if (matched != null) {
                log.info("[Jira:Workflow] Resolved '${intent.name}' via NAME_MATCH ('$defaultName') -> '${matched.name}' (id=${matched.id})")
                // Save learned mapping
                mappingStore.saveMapping(
                    TransitionMapping(
                        intent = intent.name,
                        transitionName = matched.name,
                        projectKey = projectKey,
                        issueTypeId = issueTypeId,
                        source = "learned"
                    )
                )
                return ApiResult.Success(matched.toResolved(ResolutionMethod.NAME_MATCH))
            }
        }

        // Step 5 & 6: Category matching
        log.debug("[Jira:Workflow] Step 5: Trying category matching (targetCategory=${intent.targetCategory})")
        val targetCategory = intent.targetCategory
        if (targetCategory != null) {
            val categoryMatches = transitions.filter {
                it.to.statusCategory?.key?.equals(targetCategory, ignoreCase = true) == true
            }

            when {
                categoryMatches.size == 1 -> {
                    val matched = categoryMatches.first()
                    log.info("[Jira:Workflow] Resolved '${intent.name}' via CATEGORY_MATCH (category='$targetCategory') -> '${matched.name}' (id=${matched.id})")
                    // Save learned mapping
                    mappingStore.saveMapping(
                        TransitionMapping(
                            intent = intent.name,
                            transitionName = matched.name,
                            projectKey = projectKey,
                            issueTypeId = issueTypeId,
                            source = "learned"
                        )
                    )
                    return ApiResult.Success(matched.toResolved(ResolutionMethod.CATEGORY_MATCH))
                }
                categoryMatches.size > 1 -> {
                    // Multiple matches — return DISAMBIGUATE error
                    val options = categoryMatches.joinToString("|") { "${it.id}::${it.name}" }
                    log.warn("[Jira:Workflow] Disambiguation needed for '${intent.name}': ${categoryMatches.joinToString { it.name }}")
                    return ApiResult.Error(
                        type = ErrorType.VALIDATION_ERROR,
                        message = "DISAMBIGUATE:$options"
                    )
                }
            }
        }

        // Step 7: No matches → Error with helpful message
        log.error("[Jira:Workflow] No transition found for intent '${intent.displayName}' in project '$projectKey'. Available: ${transitions.joinToString { it.name }}")
        return ApiResult.Error(
            type = ErrorType.NOT_FOUND,
            message = "No transition found for intent '${intent.displayName}' in project '$projectKey'. " +
                "Available transitions: ${transitions.joinToString { it.name }}"
        )
    }

    private fun JiraTransition.toResolved(resolution: ResolutionMethod): ResolvedTransition {
        val fieldInfos = fields?.map { (key, meta) ->
            TransitionFieldInfo(
                key = key,
                name = meta.name,
                required = meta.required,
                type = meta.schema?.type ?: "string",
                allowedValues = meta.allowedValues?.map { it.name } ?: emptyList(),
                hasDefaultValue = meta.hasDefaultValue,
                autoCompleteUrl = meta.autoCompleteUrl
            )
        } ?: emptyList()

        return ResolvedTransition(
            transitionId = id,
            transitionName = name,
            targetStatusName = to.name,
            requiredFields = fieldInfos,
            resolution = resolution
        )
    }
}
