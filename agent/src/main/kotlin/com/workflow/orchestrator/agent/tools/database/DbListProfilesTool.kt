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
 * are available before calling db_query or db_schema. On IntelliJ Ultimate,
 * IDE-configured data sources from the Database tool window are prepended to
 * the list automatically.
 */
class DbListProfilesTool : AgentTool {
    override val name = "db_list_profiles"
    override val description = """
        List all configured database connection profiles available for querying.
        Call this first to discover which environments (local, docker, qa, sandbox, etc.)
        are configured before using db_query or db_schema.

        On IntelliJ Ultimate with the Database plugin, IDE-configured data sources
        (View | Tool Windows | Database) appear here too, tagged as IDE. Note: IDE
        profile credentials are managed by the IDE — the agent can show them in
        listings but cannot currently use them for db_query. Use a manual profile
        for agent-driven queries.
    """.trimIndent()

    override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val manualProfiles = try {
            DatabaseSettings.getInstance(project).getProfiles()
        } catch (_: Exception) {
            return ToolResult(
                content = "Error: DatabaseSettings service not available.",
                summary = "db_list_profiles failed: no service",
                tokenEstimate = 20,
                isError = true,
            )
        }
        val ideProfiles = IdeDataSourceResolver.discover(project)
        // IDE profiles first so users see IDE-managed sources at the top;
        // manual profiles follow (they are the ones usable for db_query).
        val profiles = ideProfiles + manualProfiles

        if (profiles.isEmpty()) {
            val msg = "No database profiles configured. " +
                "Add profiles in Settings → Tools → Workflow Orchestrator → Agent → Database Profiles, " +
                "or configure data sources in IntelliJ's Database tool window (Ultimate)."
            return ToolResult(content = msg, summary = msg, tokenEstimate = TokenEstimator.estimate(msg))
        }

        val sb = StringBuilder("Available database profiles:\n\n")
        profiles.forEach { p ->
            val sourceLabel = when (p.source) {
                ProfileSource.IDE -> " _(IDE)_"
                ProfileSource.MANUAL -> ""
            }
            sb.append("- **${p.id}** — ${p.displayName} (${p.dbType.displayName})$sourceLabel\n")
            if (p.isServerProfile) {
                val host = p.resolvedHost
                val port = p.resolvedPort
                val db = p.resolvedDefaultDatabase.ifBlank { "(none)" }
                sb.append("  Server: `$host:$port`,  Default DB: `$db`\n")
            } else {
                // SQLite / Generic — show the raw URL
                sb.append("  URL: `${p.jdbcUrl}`\n")
            }
            sb.append("  User: `${p.username}`\n")
        }
        sb.append("\nUse `db_query(profile=\"<id>\", sql=\"SELECT ...\")` to run queries.\n")
        sb.append("For server profiles (Postgres / MySQL / SQL Server) call ")
        sb.append("`db_list_databases(profile=\"<id>\")` to discover all databases on the server, ")
        sb.append("then pass `database=\"<name>\"` to `db_query` / `db_schema` to switch into one.")
        if (ideProfiles.isNotEmpty()) {
            sb.append("\n\n_IDE profiles are discovered from IntelliJ's Database tool window. Credentials are managed there — not here._")
        }

        return ToolResult(
            content = sb.toString(),
            summary = "Listed ${profiles.size} database profile(s) (${ideProfiles.size} IDE, ${manualProfiles.size} manual)",
            tokenEstimate = TokenEstimator.estimate(sb.toString()),
        )
    }
}
