package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.database.DatabaseSettings
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Lists all configured database profiles so the agent knows which environments
 * are available before calling db_query or db_schema.
 */
class DbListProfilesTool : AgentTool {
    override val name = "db_list_profiles"
    override val description = """
        List all configured database connection profiles available for querying.
        Call this first to discover which environments (local, docker, qa, sandbox, etc.)
        are configured before using db_query or db_schema.
    """.trimIndent()

    override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val profiles = try {
            DatabaseSettings.getInstance(project).getProfiles()
        } catch (_: Exception) {
            return ToolResult(
                content = "Error: DatabaseSettings service not available.",
                summary = "db_list_profiles failed: no service",
                tokenEstimate = 20,
                isError = true
            )
        }

        if (profiles.isEmpty()) {
            val msg = "No database profiles configured. " +
                "Add profiles in Settings → Tools → Workflow Orchestrator → Agent → Database Profiles."
            return ToolResult(content = msg, summary = msg, tokenEstimate = TokenEstimator.estimate(msg))
        }

        val sb = StringBuilder("Available database profiles:\n\n")
        profiles.forEach { p ->
            sb.append("- **${p.id}** — ${p.displayName} (${p.dbType.displayName})\n")
            sb.append("  URL: `${p.jdbcUrl}`\n")
            sb.append("  User: `${p.username}`\n")
        }
        sb.append("\nUse `db_query(profile=\"<id>\", sql=\"SELECT ...\")` to run queries.")

        return ToolResult(
            content = sb.toString(),
            summary = "Listed ${profiles.size} database profile(s): ${profiles.map { it.id }}",
            tokenEstimate = TokenEstimator.estimate(sb.toString())
        )
    }
}
