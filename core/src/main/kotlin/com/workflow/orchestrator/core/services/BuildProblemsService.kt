package com.workflow.orchestrator.core.services

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.build.BuildProblem

/**
 * Read structured build/import problems for the local IDE project — Maven (V1),
 * Gradle import + compile events (V1.1).
 *
 * NOT for remote CI build status — that's bamboo_* tools.
 */
interface BuildProblemsService {
    suspend fun getRecentBuildProblems(): ToolResult<List<BuildProblem>>

    companion object {
        fun getInstance(project: Project): BuildProblemsService =
            project.service<BuildProblemsService>()
    }
}
