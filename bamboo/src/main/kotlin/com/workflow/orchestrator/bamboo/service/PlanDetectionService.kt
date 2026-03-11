package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooSearchEntity
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType

class PlanDetectionService(
    private val apiClient: BambooApiClient
) {

    suspend fun autoDetect(gitRemoteUrl: String): ApiResult<String> {
        val normalizedRemote = normalizeRepoUrl(gitRemoteUrl)

        val plansResult = apiClient.getPlans()
        val plans = plansResult.getOrNull() ?: return ApiResult.Error(
            ErrorType.NETWORK_ERROR,
            "Could not fetch plans for auto-detection"
        )

        val matches = mutableListOf<String>()
        for (plan in plans) {
            val specsResult = apiClient.getPlanSpecs(plan.key)
            val specsYaml = specsResult.getOrNull() ?: continue
            val repoUrls = extractRepoUrls(specsYaml)
            if (repoUrls.any { normalizeRepoUrl(it) == normalizedRemote }) {
                matches.add(plan.key)
            }
        }

        return when {
            matches.size == 1 -> ApiResult.Success(matches[0])
            matches.size > 1 -> ApiResult.Error(
                ErrorType.NOT_FOUND,
                "Multiple plans match this repository: ${matches.joinToString()}"
            )
            else -> ApiResult.Error(
                ErrorType.NOT_FOUND,
                "No Bamboo plan found matching repository: $gitRemoteUrl"
            )
        }
    }

    suspend fun search(query: String): ApiResult<List<BambooSearchEntity>> {
        return apiClient.searchPlans(query)
    }

    companion object {
        private val URL_REGEX = Regex("""url:\s+(.+)""")

        fun normalizeRepoUrl(url: String): String {
            var normalized = url.trim()
            // Remove .git suffix
            normalized = normalized.removeSuffix(".git")
            // Remove protocol (https://, ssh://, git://)
            normalized = normalized.replace(Regex("""^(https?|ssh|git)://"""), "")
            // Convert SSH format: git@host:path -> host/path
            normalized = normalized.replace(Regex("""^git@([^:]+):"""), "$1/")
            // Remove trailing slash
            normalized = normalized.trimEnd('/')
            return normalized
        }

        internal fun extractRepoUrls(specsYaml: String): List<String> {
            return URL_REGEX.findAll(specsYaml).map { it.groupValues[1].trim() }.toList()
        }
    }
}
