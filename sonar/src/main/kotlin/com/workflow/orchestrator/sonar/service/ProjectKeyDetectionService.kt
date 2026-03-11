package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.api.dto.SonarProjectDto

class ProjectKeyDetectionService(
    private val apiClient: SonarApiClient
) {
    suspend fun autoDetect(repoName: String): ApiResult<String?> {
        return apiClient.searchProjects(repoName).map { projects ->
            if (projects.size == 1) projects[0].key else null
        }
    }

    suspend fun search(query: String): ApiResult<List<SonarProjectDto>> {
        return apiClient.searchProjects(query)
    }
}
