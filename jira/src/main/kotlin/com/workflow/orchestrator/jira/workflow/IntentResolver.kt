package com.workflow.orchestrator.jira.workflow

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

    fun resolveFromTransitions(
        intent: WorkflowIntent,
        transitions: List<JiraTransition>,
        mappingStore: TransitionMappingStore,
        projectKey: String,
        issueTypeId: String? = null
    ): ApiResult<ResolvedTransition> {

        // Step 1: Empty transitions → Error
        if (transitions.isEmpty()) {
            return ApiResult.Error(
                type = ErrorType.NOT_FOUND,
                message = "No transitions available for intent '${intent.displayName}'"
            )
        }

        // Step 2: Check explicit mapping in store → match against available transitions
        val explicitMapping = mappingStore.getMapping(intent.name, projectKey, issueTypeId)
            ?.takeIf { it.source == "explicit" }

        if (explicitMapping != null) {
            val matched = transitions.find {
                it.name.equals(explicitMapping.transitionName, ignoreCase = true)
            }
            if (matched != null) {
                return ApiResult.Success(matched.toResolved(ResolutionMethod.EXPLICIT_MAPPING))
            }
            // Explicit mapping target not found in available transitions — fall through
        }

        // Step 3: Check learned mapping in store → match against available transitions
        val learnedMapping = mappingStore.getMapping(intent.name, projectKey, issueTypeId)
            ?.takeIf { it.source == "learned" }

        if (learnedMapping != null) {
            val matched = transitions.find {
                it.name.equals(learnedMapping.transitionName, ignoreCase = true)
            }
            if (matched != null) {
                return ApiResult.Success(matched.toResolved(ResolutionMethod.LEARNED_MAPPING))
            }
        }

        // Step 4: Name matching — iterate intent.defaultNames, find case-insensitive match
        for (defaultName in intent.defaultNames) {
            val matched = transitions.find {
                it.name.equals(defaultName, ignoreCase = true)
            }
            if (matched != null) {
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
        val targetCategory = intent.targetCategory
        if (targetCategory != null) {
            val categoryMatches = transitions.filter {
                it.to.statusCategory?.key?.equals(targetCategory, ignoreCase = true) == true
            }

            when {
                categoryMatches.size == 1 -> {
                    val matched = categoryMatches.first()
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
                    return ApiResult.Error(
                        type = ErrorType.VALIDATION_ERROR,
                        message = "DISAMBIGUATE:$options"
                    )
                }
            }
        }

        // Step 7: No matches → Error with helpful message
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
