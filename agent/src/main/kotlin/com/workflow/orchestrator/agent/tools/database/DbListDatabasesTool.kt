package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lists all databases available on a profile's server.
 *
 * One profile = one server. The LLM calls this tool to discover which databases
 * exist on that server before drilling into one with `db_query` or `db_schema`.
 * The exact query used depends on the engine:
 *
 *   - **PostgreSQL**: `SELECT datname FROM pg_database WHERE datistemplate = false`
 *   - **MySQL**: `SHOW DATABASES`
 *   - **SQL Server**: `SELECT name FROM sys.databases WHERE database_id > 4` (skip system DBs)
 *   - **SQLite / Generic**: not applicable — the profile IS the database
 */
class DbListDatabasesTool : AgentTool {
    override val name = "db_list_databases"
    override val description = """
        List all databases hosted on a profile's server. Use this after db_list_profiles
        to see which databases on a given server are available before querying one with
        db_query or db_schema.

        Examples:
          db_list_databases(profile="local")    — list databases on the local Postgres
          db_list_databases(profile="qa")       — list databases on the QA MySQL server

        Note: not applicable to SQLite or Generic JDBC profiles since each profile maps
        to exactly one database for those engine types.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "profile" to ParameterProperty(
                type = "string",
                description = "Profile ID (from db_list_profiles), e.g. 'local', 'qa'"
            ),
        ),
        required = listOf("profile")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val profileId = params["profile"]?.jsonPrimitive?.content?.trim()
            ?: return error("'profile' parameter is required.")

        val profile = when (val lookup = lookupDbProfile(project, profileId)) {
            is DbProfileLookup.Found -> lookup.profile
            is DbProfileLookup.IdeManaged -> return ToolResult(
                content = "Profile '$profileId' (${lookup.displayName}) is an IDE-managed data source. " +
                    "IDE profile credentials are not available to the agent. " +
                    "To run queries, configure a manual profile in Settings → Tools → Workflow Orchestrator → Agent → Database Profiles.",
                summary = "db_list_databases: IDE profile credentials not available",
                tokenEstimate = 80,
                isError = true,
            )
            is DbProfileLookup.NotFound -> return error(
                "Profile '$profileId' not found. Call db_list_profiles first."
            )
        }

        if (!profile.isServerProfile) {
            return error(
                "Profile '${profile.id}' is a ${profile.dbType.displayName} profile, which has " +
                    "no concept of multiple databases per server. Use db_schema directly."
            )
        }

        val listSql = DatabaseDiscovery.discoveryQuery(profile.dbType)
            ?: return error("No database listing query implemented for ${profile.dbType.displayName}.")

        val result = DatabaseConnectionManager.withConnection(profile) { conn ->
            DatabaseConnectionManager.createStatement(conn).use { stmt ->
                stmt.executeQuery(listSql).use { rs ->
                    val raw = mutableListOf<String>()
                    while (rs.next()) raw.add(rs.getString(1))
                    DatabaseDiscovery.filterSystemDatabases(profile.dbType, raw)
                }
            }
        }

        return result.fold(
            onSuccess = { databases ->
                if (databases.isEmpty()) {
                    val msg = "No databases found on '${profile.displayName}'."
                    return@fold ToolResult(content = msg, summary = msg, tokenEstimate = TokenEstimator.estimate(msg))
                }
                val sb = StringBuilder()
                sb.append("Databases on **${profile.displayName}** (${profile.dbType.displayName}):\n\n")
                databases.forEach { db ->
                    val marker = if (db == profile.resolvedDefaultDatabase) "  *(default)*" else ""
                    sb.append("- `$db`$marker\n")
                }
                sb.append("\nUse `db_query(profile=\"${profile.id}\", database=\"<name>\", sql=\"...\")` ")
                sb.append("or `db_schema(profile=\"${profile.id}\", database=\"<name>\")` to drill in.")
                ToolResult(
                    content = sb.toString(),
                    summary = "Listed ${databases.size} database(s) on '${profile.id}'",
                    tokenEstimate = TokenEstimator.estimate(sb.toString())
                )
            },
            onFailure = { e ->
                val hint = DatabaseConnectionManager.connectionErrorHint(e, profile.dbType)
                error("Failed to list databases on '${profile.id}': ${e.message}$hint")
            }
        )
    }

    private fun error(msg: String) = ToolResult(
        content = "Error: $msg",
        summary = "db_list_databases error: $msg",
        tokenEstimate = TokenEstimator.estimate(msg),
        isError = true
    )
}
