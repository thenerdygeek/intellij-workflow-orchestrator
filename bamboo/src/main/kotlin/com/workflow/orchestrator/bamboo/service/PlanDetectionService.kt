package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooSearchEntity
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType

class PlanDetectionService(
    private val apiClient: BambooApiClient
) {

    private val log = Logger.getInstance(PlanDetectionService::class.java)

    suspend fun autoDetect(gitRemoteUrl: String): ApiResult<String> {
        log.info("[Bamboo:Plan] Auto-detecting plan for remote URL: $gitRemoteUrl")
        val normalizedRemote = normalizeRepoUrl(gitRemoteUrl)
        log.debug("[Bamboo:Plan] Normalized remote URL: $normalizedRemote")

        val plansResult = apiClient.getPlans()
        val plans = plansResult.getOrNull() ?: run {
            log.error("[Bamboo:Plan] Could not fetch plans for auto-detection")
            return ApiResult.Error(
                ErrorType.NETWORK_ERROR,
                "Could not fetch plans for auto-detection"
            )
        }

        log.debug("[Bamboo:Plan] Scanning ${plans.size} plans for matching repository")
        val matches = mutableListOf<String>()
        for (plan in plans) {
            val specsResult = apiClient.getPlanSpecs(plan.key)
            val specsYaml = specsResult.getOrNull() ?: continue
            val repoUrls = extractRepoUrls(specsYaml)
            if (repoUrls.any { normalizeRepoUrl(it) == normalizedRemote }) {
                log.debug("[Bamboo:Plan] Plan ${plan.key} matches repository")
                matches.add(plan.key)
            }
        }

        return when {
            matches.size == 1 -> {
                log.info("[Bamboo:Plan] Auto-detected plan: ${matches[0]}")
                ApiResult.Success(matches[0])
            }
            matches.size > 1 -> {
                log.warn("[Bamboo:Plan] Multiple plans match repository: ${matches.joinToString()}")
                ApiResult.Error(
                    ErrorType.NOT_FOUND,
                    "Multiple plans match this repository: ${matches.joinToString()}"
                )
            }
            else -> {
                log.warn("[Bamboo:Plan] No plan found matching repository: $gitRemoteUrl")
                ApiResult.Error(
                    ErrorType.NOT_FOUND,
                    "No Bamboo plan found matching repository: $gitRemoteUrl"
                )
            }
        }
    }

    suspend fun search(query: String): ApiResult<List<BambooSearchEntity>> {
        log.info("[Bamboo:Plan] Searching plans with query='$query'")
        return apiClient.searchPlans(query)
    }

    companion object {
        private val URL_REGEX = Regex("""url:\s+(.+)""")

        fun normalizeRepoUrl(url: String): String {
            var normalized = url.trim()
            normalized = normalized.removeSuffix(".git")
            normalized = normalized.replace(Regex("""^(https?|ssh|git)://"""), "")
            normalized = normalized.replace(Regex("""^git@([^:]+):"""), "$1/")
            normalized = normalized.trimEnd('/')
            return normalized
        }

        internal fun extractRepoUrls(specsYaml: String): List<String> {
            return try {
                val yaml = org.yaml.snakeyaml.Yaml()
                val data = yaml.load<Any>(specsYaml)
                extractUrlsFromYamlTree(data)
            } catch (_: Exception) {
                // Fallback to regex if YAML parsing fails
                URL_REGEX.findAll(specsYaml).map { it.groupValues[1].trim() }.toList()
            }
        }

        private fun extractUrlsFromYamlTree(node: Any?): List<String> {
            val urls = mutableListOf<String>()
            when (node) {
                is Map<*, *> -> {
                    for ((key, value) in node) {
                        if (key == "url" && value is String) {
                            urls.add(value)
                        } else {
                            urls.addAll(extractUrlsFromYamlTree(value))
                        }
                    }
                }
                is List<*> -> node.forEach { urls.addAll(extractUrlsFromYamlTree(it)) }
            }
            return urls
        }
    }
}
