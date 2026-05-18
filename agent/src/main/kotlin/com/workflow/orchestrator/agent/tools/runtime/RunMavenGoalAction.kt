package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val CATEGORY_INVALID_ARGS = "INVALID_ARGS"
internal const val CATEGORY_NOT_A_MAVEN_PROJECT = "NOT_A_MAVEN_PROJECT"
internal const val CATEGORY_APPROVAL_DENIED = "APPROVAL_DENIED"
internal const val CATEGORY_EXECUTION_EXCEPTION = "EXECUTION_EXCEPTION"
internal const val CATEGORY_PROCESS_NOT_STARTED = "PROCESS_NOT_STARTED"
internal const val CATEGORY_BUILD_FAILURE = "BUILD_FAILURE"
internal const val CATEGORY_TIMEOUT = "TIMEOUT"

internal fun buildPreflightError(category: String, message: String): ToolResult =
    ToolResult(
        content = "$category: $message",
        summary = "$category — see content",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )

internal suspend fun executeRunMavenGoal(
    params: JsonObject,
    project: Project,
    tool: AgentTool
): ToolResult {
    val goals = params["goals"]?.jsonPrimitive?.content ?: ""
    if (goals.isBlank()) {
        return buildPreflightError(
            CATEGORY_INVALID_ARGS,
            "goals is blank — Maven CLI silently produces BUILD SUCCESS for an empty goal list, which is misleading. Provide at least one goal (e.g., \"clean\", \"install\", \"dependency:tree\")."
        )
    }
    if (MavenUtils.getMavenManager(project) == null) {
        return buildPreflightError(
            CATEGORY_NOT_A_MAVEN_PROJECT,
            "this project is not Mavenized (MavenProjectsManager reports isMavenizedProject=false). " +
                "Use ./gradlew goals via run_command for Gradle projects, or open the project's pom.xml to import as Maven first."
        )
    }
    // Remaining pre-flights, approval, launch wired in later tasks
    return buildPreflightError(CATEGORY_EXECUTION_EXCEPTION, "not yet implemented past blank-goals pre-flight")
}
