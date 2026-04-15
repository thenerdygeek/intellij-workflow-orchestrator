package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shows the query execution plan for a SELECT statement using EXPLAIN.
 *
 * Safety: EXPLAIN ANALYZE is executed inside an autoCommit=false + isReadOnly=true
 * connection that is always rolled back on close, so no data is modified even when
 * the database actually executes the statement to collect runtime statistics.
 */
class DbExplainTool : AgentTool {
    override val name = "db_explain"
    override val description = """
        Show the query execution plan for a SELECT statement using EXPLAIN.

        Only SELECT statements are accepted — the same read-only guard as db_query.
        EXPLAIN ANALYZE is safe: the statement executes inside a read-only, auto-rollback
        transaction so no data is written or permanently modified.

        Behaviour:
        - PostgreSQL + analyze=true  → EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) <sql>
        - All other cases            → EXPLAIN <sql>

        Use this tool to:
        - Understand why a query is slow (seq scan vs index scan, row estimates, etc.)
        - Verify that an index is being used before and after adding it
        - Compare estimated vs actual row counts (requires analyze=true on PostgreSQL)

        Examples:
          db_explain(profile="qa", sql="SELECT * FROM orders WHERE status='PENDING'")
          db_explain(profile="qa", sql="SELECT id FROM users WHERE email='x@y.com'", analyze=true)
          db_explain(profile="local", database="analytics", sql="SELECT count(*) FROM events")
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
                description = "The SELECT statement whose execution plan to show"
            ),
            "analyze" to ParameterProperty(
                type = "boolean",
                description = "When true, runs EXPLAIN ANALYZE (PostgreSQL only: includes BUFFERS). " +
                    "The statement is actually executed but immediately rolled back — data is safe. " +
                    "Defaults to false."
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
        val analyze = params["analyze"]?.jsonPrimitive?.booleanOrNull ?: false

        // Validate read-only before touching the database
        DatabaseConnectionManager.validateReadOnly(sql)?.let { msg -> return error(msg) }

        val profile = try {
            DatabaseSettings.getInstance(project).getProfile(profileId)
        } catch (_: Exception) {
            return error("DatabaseSettings service not available.")
        } ?: return error(
            "Profile '$profileId' not found. Call db_list_profiles to see available profiles."
        )

        val modeLabel = if (analyze) "EXPLAIN ANALYZE" else "EXPLAIN"

        val explainSql = when {
            profile.dbType == DbType.POSTGRESQL && analyze ->
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) $sql"
            else -> "EXPLAIN $sql"
        }

        val result = DatabaseConnectionManager.withConnection(profile, database) { conn ->
            DatabaseConnectionManager.createStatement(conn).use { stmt ->
                stmt.executeQuery(explainSql).use { rs ->
                    val lines = mutableListOf<String>()
                    while (rs.next()) {
                        lines.add(rs.getString(1))
                    }
                    lines.joinToString("\n")
                }
            }
        }

        val targetLabel = database?.let { "${profile.displayName} / $it" } ?: profile.displayName
        val summaryProfileLabel = "${profile.id}${database?.let { "/$it" } ?: ""}"

        return result.fold(
            onSuccess = { plan ->
                val content = "$modeLabel for **$targetLabel** (${profile.dbType.displayName}):\n\n" +
                    "```sql\n$sql\n```\n\n" +
                    "```\n$plan\n```"
                ToolResult(
                    content = content,
                    summary = "db_explain on '$summaryProfileLabel' ($modeLabel)",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            },
            onFailure = { e ->
                val hint = DatabaseConnectionManager.connectionErrorHint(e, profile.dbType)
                error("EXPLAIN failed on '$summaryProfileLabel': ${e.message}$hint")
            }
        )
    }

    private fun error(msg: String) = ToolResult(
        content = "Error: $msg",
        summary = "db_explain error: $msg",
        tokenEstimate = TokenEstimator.estimate(msg),
        isError = true
    )
}
