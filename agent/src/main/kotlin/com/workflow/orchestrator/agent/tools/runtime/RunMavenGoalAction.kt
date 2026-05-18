package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
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
    val extraArgsRaw = params["extra_args"]?.jsonPrimitive?.content ?: ""
    val extraTokens: List<String> = try {
        tokenizeExtraArgs(extraArgsRaw)
    } catch (e: Exception) {
        return buildPreflightError(
            CATEGORY_INVALID_ARGS,
            "extra_args tokenization failed (${e::class.simpleName}: ${e.message}). " +
                "Check for unbalanced quotes or invalid escape sequences."
        )
    }
    val modules: List<String> = params["modules"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }
        ?: emptyList()
    val offline: Boolean = params["offline"]?.jsonPrimitive?.booleanOrNull ?: false

    val approvalArgs = buildString {
        append("mvn ")
        append(goals)
        if (modules.isNotEmpty()) append(" -pl ").append(modules.joinToString(","))
        if (offline) append(" -o")
    }
    val approval = tool.requestApproval(
        toolName = "java_runtime_exec.run_maven_goal",
        args = approvalArgs,
        riskLevel = "medium",
        allowSessionApproval = true
    )
    if (approval == ApprovalResult.DENIED) {
        return buildPreflightError(
            CATEGORY_APPROVAL_DENIED,
            "user declined approval for: $approvalArgs"
        )
    }

    // Remaining pre-flights, approval, launch wired in later tasks
    return buildPreflightError(CATEGORY_EXECUTION_EXCEPTION, "not yet implemented past blank-goals pre-flight")
}

internal fun tokenizeExtraArgs(raw: String): List<String> =
    if (raw.isBlank()) emptyList() else ParametersListUtil.parse(raw)

/**
 * Locale-independent lookup. The id "MavenRunConfiguration" is registered by
 * the bundled Maven plugin (`org.jetbrains.idea.maven.execution.MavenRunConfigurationType`)
 * and is stable across IntelliJ versions. The historical displayName "Maven" would
 * have been locale-sensitive (it flows through MavenRunnerBundle) and is intentionally
 * not used as a fallback.
 */
internal fun findMavenConfigurationType(types: List<ConfigurationType>): ConfigurationType? =
    types.firstOrNull { it.id == "MavenRunConfiguration" }

internal fun assembleGoalTokens(
    goals: String,
    modules: List<String>,
    extraTokens: List<String>,
    offline: Boolean
): List<String> {
    val moduleTokens =
        if (modules.isNotEmpty()) listOf("-pl", modules.joinToString(","), "-am") else emptyList()
    val goalsTokens = goals.trim().split("\\s+".toRegex())
    val offlineToken = if (offline) listOf("-o") else emptyList()
    return moduleTokens + goalsTokens + extraTokens + offlineToken
}

private const val HEADER_DIVIDER = "─────────────────────────────────────────"

internal fun formatHeader(
    goals: String,
    modules: List<String>,
    workingDir: String,
    mavenHome: String,
    exitCode: Int,
    durationSec: Double
): String = buildString {
    append("Maven goal: ").append(goals).append('\n')
    append("Modules: ").append(if (modules.isEmpty()) "all" else modules.joinToString(", ")).append('\n')
    append("Working directory: ").append(workingDir).append('\n')
    append("Maven home (IDE-configured): ").append(mavenHome).append('\n')
    append("Exit code: ").append(exitCode).append('\n')
    append("Duration: ").append("%.1f".format(durationSec)).append("s\n")
    append(HEADER_DIVIDER).append('\n')
}

internal fun buildSuccessResult(
    goals: String,
    modules: List<String>,
    workingDir: String,
    mavenHome: String,
    exitCode: Int,
    durationSec: Double,
    output: String,
    project: Project
): ToolResult {
    val header = formatHeader(goals, modules, workingDir, mavenHome, exitCode, durationSec)
    val isFailure = exitCode != 0
    val prefix = if (isFailure) "BUILD_FAILURE: exit code $exitCode\n\n" else ""
    val body = prefix + header + output
    val mark = if (isFailure) "✗" else "✓"
    val summary = "mvn $goals $mark (${"%.0f".format(durationSec)}s, exit=$exitCode)"
    return ToolResult(
        content = body,
        summary = summary,
        tokenEstimate = body.length / 4 + 1,
        isError = isFailure
    )
}

internal fun buildTimeoutResult(
    goals: String,
    modules: List<String>,
    workingDir: String,
    mavenHome: String,
    timeoutSec: Double,
    output: String,
    project: Project
): ToolResult {
    val header = formatHeader(
        goals, modules, workingDir, mavenHome,
        exitCode = -1, durationSec = timeoutSec
    )
    val body = "TIMEOUT: exceeded ${"%.0f".format(timeoutSec)}s — process killed.\n\n" +
        "Narrow scope: smaller modules list, skip tests with -DskipTests, " +
        "or use -T <n> for parallelism.\n\n" + header + output
    return ToolResult(
        content = body,
        summary = "mvn $goals ⏱ TIMEOUT after ${"%.0f".format(timeoutSec)}s",
        tokenEstimate = body.length / 4 + 1,
        isError = true
    )
}
