package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.database.DatabaseConnectionManager
import com.workflow.orchestrator.agent.tools.database.DatabaseSettings
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Returns row counts and storage sizes for tables in a database.
 *
 * Three scoping modes:
 *   - profile only              → all user tables (top 50 by size)
 *   - profile + schema          → all tables in that schema
 *   - profile + schema + table  → one specific table
 *
 * Engine behaviour varies:
 *   - PostgreSQL: uses pg_stat_user_tables + pg_total_relation_size() — exact row counts
 *   - MySQL: uses information_schema.tables — row counts are estimates
 *   - MSSQL / GENERIC: uses information_schema.tables — row counts are estimates
 *   - SQLite: not supported — returns an error immediately
 */
class DbStatsTool : AgentTool {
    override val name = "db_stats"
    override val description = """
        Show row counts and storage sizes for tables in a database.

        Three scoping modes (determined by which parameters you supply):
          • profile only                       → all user tables, top 50 by total size
          • profile + schema                   → all tables in that schema
          • profile + schema + table           → one specific table

        Note: PostgreSQL returns exact live row counts via pg_stat_user_tables.
        MySQL, SQL Server, and Generic JDBC return estimates from information_schema.

        Examples:
          db_stats(profile="qa")                                          — top 50 tables by size
          db_stats(profile="qa", schema="public")                         — all tables in public schema
          db_stats(profile="qa", schema="public", table="orders")         — stats for one table
          db_stats(profile="qa", database="analytics", schema="reporting") — different database
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "profile" to ParameterProperty(
                type = "string",
                description = "Profile ID from db_list_profiles, e.g. 'local', 'qa'"
            ),
            "database" to ParameterProperty(
                type = "string",
                description = "Optional database name on the profile's server. Defaults to the " +
                    "profile's default database. Ignored for SQLite/Generic profiles."
            ),
            "schema" to ParameterProperty(
                type = "string",
                description = "Filter to one schema. If omitted, all user schemas are included. " +
                    "Examples: 'public', 'reporting', 'billing'."
            ),
            "table" to ParameterProperty(
                type = "string",
                description = "Filter to one specific table. Requires schema to also be provided."
            ),
        ),
        required = listOf("profile")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val profileId = params["profile"]?.jsonPrimitive?.content?.trim()
            ?: return error("'profile' parameter is required. Call db_list_profiles to see options.")
        val database = params["database"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val schema = params["schema"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val table = params["table"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

        val profile = try {
            DatabaseSettings.getInstance(project).getProfile(profileId)
        } catch (_: Exception) {
            return error("DatabaseSettings service not available.")
        } ?: return error(
            "Profile '$profileId' not found. Call db_list_profiles to see available profiles."
        )

        // SQLite is not supported — return early before opening any connection
        if (profile.dbType == DbType.SQLITE) {
            return error("db_stats is not supported for SQLite")
        }

        val targetLabel = database?.let { "${profile.displayName} / $it" } ?: profile.displayName
        val scopeLabel = when {
            schema != null && table != null -> "table '$schema.$table'"
            schema != null -> "schema '$schema' tables"
            else -> "all user tables (top 50 by size)"
        }

        val result: Result<Pair<String, String>> = DatabaseConnectionManager.withConnection(profile, database) { conn ->
            when (profile.dbType) {
                DbType.POSTGRESQL -> queryPostgres(conn, schema, table)
                DbType.MYSQL -> queryMysql(conn, schema, table)
                DbType.MSSQL, DbType.GENERIC -> queryGeneric(conn, schema, table)
                else -> throw IllegalStateException("Unreachable — SQLite check above")
            }
        }

        val summaryProfileLabel = "${profile.id}${database?.let { "/$it" } ?: ""}"

        return result.fold(
            onSuccess = { (markdownTable, note) ->
                val header = "Table statistics for **$targetLabel** (${profile.dbType.displayName}) — $scopeLabel\n\n"
                val raw = header + markdownTable + (if (note.isNotEmpty()) "\n$note" else "")
                val spilled = spillOrFormat(raw, project)
                ToolResult(
                    content = spilled.preview,
                    summary = "db_stats on '$summaryProfileLabel': $scopeLabel",
                    tokenEstimate = TokenEstimator.estimate(spilled.preview),
                    spillPath = spilled.spilledToFile,
                )
            },
            onFailure = { e ->
                val hint = DatabaseConnectionManager.connectionErrorHint(e, profile.dbType)
                error("db_stats failed on '$summaryProfileLabel': ${e.message}$hint")
            }
        )
    }

    // ----- Engine-specific queries -----

    private fun queryPostgres(
        conn: java.sql.Connection,
        schema: String?,
        table: String?
    ): Pair<String, String> {
        val baseSql = """
            SELECT
                schemaname,
                tablename,
                n_live_tup AS row_count,
                pg_size_pretty(pg_total_relation_size(quote_ident(schemaname)||'.'||quote_ident(tablename))) AS total_size,
                pg_size_pretty(pg_relation_size(quote_ident(schemaname)||'.'||quote_ident(tablename))) AS table_size,
                pg_size_pretty(
                    pg_total_relation_size(quote_ident(schemaname)||'.'||quote_ident(tablename)) -
                    pg_relation_size(quote_ident(schemaname)||'.'||quote_ident(tablename))
                ) AS index_size,
                COALESCE(
                    to_char(GREATEST(last_analyze, last_autoanalyze), 'YYYY-MM-DD'),
                    'never'
                ) AS last_analyzed
            FROM pg_stat_user_tables
        """.trimIndent()

        val (whereClause, paramValues) = buildWhereClause(schema, table)
        val orderLimit = "\nORDER BY pg_total_relation_size(quote_ident(schemaname)||'.'||quote_ident(tablename)) DESC\nLIMIT 50"
        val fullSql = baseSql + whereClause + orderLimit

        val markdownTable = conn.prepareStatement(fullSql).use { pstmt ->
            runCatching { pstmt.queryTimeout = DatabaseConnectionManager.DEFAULT_TIMEOUT_SECONDS }
            paramValues.forEachIndexed { i, v -> pstmt.setString(i + 1, v) }
            pstmt.executeQuery().use { rs ->
                DatabaseConnectionManager.resultSetToMarkdown(rs).first
            }
        }
        return markdownTable to ""
    }

    private fun queryMysql(
        conn: java.sql.Connection,
        schema: String?,
        table: String?
    ): Pair<String, String> {
        val baseSql = """
            SELECT table_schema, table_name, table_rows AS row_count,
                   CONCAT(ROUND((data_length + index_length) / 1024 / 1024, 2), ' MB') AS total_size,
                   CONCAT(ROUND(data_length / 1024 / 1024, 2), ' MB') AS table_size,
                   CONCAT(ROUND(index_length / 1024 / 1024, 2), ' MB') AS index_size
            FROM information_schema.tables
            WHERE table_type = 'BASE TABLE'
        """.trimIndent()

        val (whereClause, paramValues) = buildInfoSchemaWhereClause(schema, table)
        val orderLimit = "\nORDER BY (data_length + index_length) DESC\nLIMIT 50"
        val fullSql = baseSql + whereClause + orderLimit

        val markdownTable = conn.prepareStatement(fullSql).use { pstmt ->
            runCatching { pstmt.queryTimeout = DatabaseConnectionManager.DEFAULT_TIMEOUT_SECONDS }
            paramValues.forEachIndexed { i, v -> pstmt.setString(i + 1, v) }
            pstmt.executeQuery().use { rs ->
                DatabaseConnectionManager.resultSetToMarkdown(rs).first
            }
        }
        return markdownTable to "_Note: row counts are estimates from information_schema._"
    }

    private fun queryGeneric(
        conn: java.sql.Connection,
        schema: String?,
        table: String?
    ): Pair<String, String> {
        val baseSql = """
            SELECT table_schema, table_name, table_rows
            FROM information_schema.tables
            WHERE table_type = 'BASE TABLE'
        """.trimIndent()

        val (whereClause, paramValues) = buildInfoSchemaWhereClause(schema, table)
        val orderBy = "\nORDER BY table_name"
        val fullSql = baseSql + whereClause + orderBy

        val markdownTable = conn.prepareStatement(fullSql).use { pstmt ->
            runCatching { pstmt.queryTimeout = DatabaseConnectionManager.DEFAULT_TIMEOUT_SECONDS }
            paramValues.forEachIndexed { i, v -> pstmt.setString(i + 1, v) }
            pstmt.executeQuery().use { rs ->
                DatabaseConnectionManager.resultSetToMarkdown(rs).first
            }
        }
        return markdownTable to "_Note: row counts are estimates from information_schema._"
    }

    // ----- Helpers -----

    /**
     * Builds a WHERE fragment for pg_stat_user_tables using PostgreSQL column names
     * (schemaname, tablename). The base SQL for PostgreSQL has no prior WHERE clause,
     * so this always uses the "WHERE" prefix.
     */
    private fun buildWhereClause(
        schema: String?,
        table: String?
    ): Pair<String, List<String>> {
        val parts = mutableListOf<String>()
        val values = mutableListOf<String>()

        if (schema != null) {
            parts.add("schemaname = ?")
            values.add(schema)
        }
        if (table != null) {
            parts.add("tablename = ?")
            values.add(table)
        }

        if (parts.isEmpty()) return "" to emptyList()

        val clause = "\nWHERE ${parts.joinToString(" AND ")}"
        return clause to values
    }

    /**
     * Builds an AND fragment for information_schema.tables using standard SQL column names
     * (table_schema, table_name). The base SQL for MySQL/MSSQL/GENERIC already has
     * WHERE table_type = 'BASE TABLE', so additional filters are appended with "AND".
     */
    private fun buildInfoSchemaWhereClause(
        schema: String?,
        table: String?
    ): Pair<String, List<String>> {
        val parts = mutableListOf<String>()
        val values = mutableListOf<String>()

        if (schema != null) {
            parts.add("table_schema = ?")
            values.add(schema)
        }
        if (table != null) {
            parts.add("table_name = ?")
            values.add(table)
        }

        if (parts.isEmpty()) return "" to emptyList()

        val clause = "\nAND ${parts.joinToString(" AND ")}"
        return clause to values
    }

    private fun error(msg: String) = ToolResult(
        content = "Error: $msg",
        summary = "db_stats error: $msg",
        tokenEstimate = TokenEstimator.estimate(msg),
        isError = true
    )
}
