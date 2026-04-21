package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.database.DatabaseConnectionManager
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Executes a read-only SQL query against a named database profile.
 * Only SELECT statements are permitted. Results are returned as a Markdown table.
 */
class DbQueryTool : AgentTool {
    override val name = "db_query"
    override val description = """
        Execute a read-only SQL SELECT query against a configured database profile.
        Results are returned as a Markdown table (max ${DatabaseConnectionManager.MAX_ROWS} rows).

        Rules:
        - Only SELECT statements are allowed. INSERT/UPDATE/DELETE/DDL are blocked.
        - Always call db_list_profiles first if you don't know which profiles exist.
        - Use db_list_databases to see which databases exist on a server profile, then
          pass the database name via the optional `database` parameter to query a non-default DB.
        - Use db_schema to understand the table structure before writing queries.
        - Keep queries targeted — avoid SELECT * on large tables without a WHERE clause.

        Examples:
          db_query(profile="qa", sql="SELECT id, name FROM orders WHERE status='PENDING' LIMIT 20")
          db_query(profile="local", database="analytics", sql="SELECT count(*) FROM events")
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "profile" to ParameterProperty(
                type = "string",
                description = "Profile ID to query (from db_list_profiles), e.g. 'local', 'qa'"
            ),
            "database" to ParameterProperty(
                type = "string",
                description = "Optional database name on the profile's server. Defaults to the " +
                    "profile's default database. Use db_list_databases to see what's available. " +
                    "Ignored for SQLite/Generic profiles."
            ),
            "sql" to ParameterProperty(
                type = "string",
                description = "The SQL SELECT query to execute"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this query investigates (shown in the approval dialog)"
            ),
        ),
        required = listOf("profile", "sql")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val profileId = params["profile"]?.jsonPrimitive?.content?.trim()
            ?: return error("'profile' parameter is required. Call db_list_profiles to see options.")
        val database = params["database"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val sql = params["sql"]?.jsonPrimitive?.content?.trim()
            ?: return error("'sql' parameter is required.")

        // Validate read-only before touching the database
        DatabaseConnectionManager.validateReadOnly(sql)?.let { msg -> return error(msg) }

        val profile = when (val lookup = lookupDbProfile(project, profileId)) {
            is DbProfileLookup.Found -> lookup.profile
            is DbProfileLookup.IdeManaged -> return ToolResult(
                content = "Profile '$profileId' (${lookup.displayName}) is an IDE-managed data source. " +
                    "IDE profile credentials are not available to the agent. " +
                    "To run queries, configure a manual profile in Settings → Tools → Workflow Orchestrator → Agent → Database Profiles.",
                summary = "db_query: IDE profile credentials not available",
                tokenEstimate = 80,
                isError = true,
            )
            is DbProfileLookup.NotFound -> return error(
                "Profile '$profileId' not found. Call db_list_profiles to see available profiles."
            )
        }

        val result = DatabaseConnectionManager.withConnection(profile, database) { conn ->
            DatabaseConnectionManager.createStatement(conn).use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    DatabaseConnectionManager.resultSetToMarkdown(rs)
                }
            }
        }

        val targetLabel = database?.let { "${profile.displayName} / $it" } ?: profile.displayName

        return result.fold(
            onSuccess = { (table, rowCount) ->
                val raw = "Query against **$targetLabel** (${profile.dbType.displayName}):\n\n" +
                    "```sql\n$sql\n```\n\n$table\n_$rowCount row(s) returned._"
                val spilled = spillOrFormat(raw, project)
                ToolResult(
                    content = spilled.preview,
                    summary = "db_query on '${profile.id}${database?.let { "/$it" } ?: ""}': $rowCount row(s)",
                    tokenEstimate = TokenEstimator.estimate(spilled.preview),
                    spillPath = spilled.spilledToFile,
                )
            },
            onFailure = { e ->
                val hint = DatabaseConnectionManager.connectionErrorHint(e, profile.dbType)
                error("Query failed on '${profile.id}${database?.let { "/$it" } ?: ""}': ${e.message}$hint")
            }
        )
    }

    private fun error(msg: String) = ToolResult(
        content = "Error: $msg",
        summary = "db_query error: $msg",
        tokenEstimate = TokenEstimator.estimate(msg),
        isError = true
    )
}
