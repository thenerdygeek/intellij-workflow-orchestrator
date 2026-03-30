package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.database.DatabaseConnectionManager
import com.workflow.orchestrator.agent.database.DatabaseSettings
import com.workflow.orchestrator.agent.runtime.WorkerType
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
        - Use db_schema to understand the table structure before writing queries.
        - Keep queries targeted — avoid SELECT * on large tables without a WHERE clause.

        Example: db_query(profile="qa", sql="SELECT id, name, status FROM orders WHERE status='PENDING' LIMIT 20")
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "profile" to ParameterProperty(
                type = "string",
                description = "Profile ID to query (from db_list_profiles), e.g. 'local', 'qa'"
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
        val sql = params["sql"]?.jsonPrimitive?.content?.trim()
            ?: return error("'sql' parameter is required.")

        // Validate read-only before touching the database
        DatabaseConnectionManager.validateReadOnly(sql)?.let { msg -> return error(msg) }

        val profile = try {
            DatabaseSettings.getInstance(project).getProfile(profileId)
        } catch (_: Exception) {
            return error("DatabaseSettings service not available.")
        } ?: return error(
            "Profile '$profileId' not found. Call db_list_profiles to see available profiles."
        )

        val result = DatabaseConnectionManager.withConnection(profile) { conn ->
            val stmt = DatabaseConnectionManager.createStatement(conn)
            val rs = stmt.executeQuery(sql)
            val (table, rowCount) = DatabaseConnectionManager.resultSetToMarkdown(rs)
            rs.close()
            stmt.close()
            Pair(table, rowCount)
        }

        return result.fold(
            onSuccess = { (table, rowCount) ->
                val content = "Query against **${profile.displayName}** (${profile.dbType.displayName}):\n\n" +
                    "```sql\n$sql\n```\n\n$table\n_$rowCount row(s) returned._"
                ToolResult(
                    content = content,
                    summary = "db_query on '${profile.id}': $rowCount row(s)",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            },
            onFailure = { e ->
                error("Query failed on '${profile.id}': ${e.message}")
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
