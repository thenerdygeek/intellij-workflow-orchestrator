package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.database.DatabaseConnectionManager
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
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

    override fun documentation(): ToolDocumentation = toolDoc("db_stats") {
        summary {
            technical(
                "Returns row counts and storage sizes for tables in a database, " +
                "scoped to profile-only (top 50 by size), profile+schema (all tables in schema), " +
                "or profile+schema+table (one specific table). PostgreSQL uses pg_stat_user_tables " +
                "+ pg_total_relation_size() for exact live row counts including table size, index size, " +
                "and last-analyzed date. MySQL, SQL Server, and Generic JDBC fall back to " +
                "information_schema.tables for estimated row counts and MB-level size figures. " +
                "SQLite is unsupported and returns an error immediately. Output is a Markdown table " +
                "always passed through spillOrFormat so large multi-schema listings are auto-spilled."
            )
            plain(
                "Like asking 'how big is this table?' — the agent gets row counts and storage sizes " +
                "for every table in the database (or just one), without writing any SQL itself. " +
                "Think of it as the database equivalent of `du -sh` on a folder: one call tells you " +
                "which tables are huge, how much of that is indexes vs data, and when statistics were " +
                "last refreshed. The numbers are exact on PostgreSQL and approximate on MySQL/SQL Server."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without db_stats, the LLM would construct engine-specific queries to gather the same " +
            "information: PostgreSQL requires joining pg_stat_user_tables with pg_total_relation_size() " +
            "and pg_relation_size(); MySQL needs INFORMATION_SCHEMA.TABLES with data_length + index_length; " +
            "SQL Server requires sys.dm_db_partition_stats joined with sys.tables; SQLite offers only " +
            "PRAGMA page_count * page_size or sqlite_stat1 (if ANALYZE has been run). Each dialect is " +
            "different, the LLM often picks the wrong one, and none of them format the size columns " +
            "into human-readable strings without an additional formatting expression. Net cost: 2-4 " +
            "extra db_query calls per investigation, high chance of a dialect error on the first attempt, " +
            "and raw-byte sizes instead of '42 MB' labels."
        )
        llmMistake(
            "Passes `table` without `schema` — the tool's three-mode dispatch requires schema when " +
            "table is set; supplying table alone causes table to be silently ignored and the call " +
            "runs as profile-only (top 50 tables). The LLM then sees a broad result and mistakenly " +
            "concludes the named table doesn't exist."
        )
        llmMistake(
            "Calls db_stats on a SQLite profile expecting a table-size figure — SQLite is explicitly " +
            "unsupported and returns an error immediately. The LLM should detect DbType.SQLITE in " +
            "db_list_profiles output and use `db_query('SELECT count(*) FROM <table>')` + " +
            "`PRAGMA page_count` instead."
        )
        llmMistake(
            "Trusts MySQL/SQL Server row counts as exact — information_schema.tables stores estimates " +
            "updated at ANALYZE/OPTIMIZE time, not live counts. On large tables the estimate can be " +
            "off by an order of magnitude. To get an exact count on these engines, follow up with " +
            "`db_query('SELECT count(*) FROM <table>')` which does a full scan."
        )
        llmMistake(
            "Passes the schema name as `database` and the database name as `schema` — " +
            "on PostgreSQL these are distinct concepts: `database` selects the catalog " +
            "(e.g. 'myapp' vs 'analytics' on the same cluster) while `schema` is the namespace " +
            "inside it ('public', 'reporting'). Mixing them produces an empty result or a " +
            "connection error with no clear hint."
        )
        llmMistake(
            "Interprets an empty Markdown table result (no rows returned) as 'this schema has no " +
            "tables' on PostgreSQL — but pg_stat_user_tables only surfaces tables that have been " +
            "analyzed at least once. Brand-new tables with no ANALYZE run yet may not appear. " +
            "Confirm with db_schema(schema=...) which uses DatabaseMetaData.getTables() instead."
        )
        params {
            required("profile", "string") {
                llmSeesIt("Profile ID from db_list_profiles, e.g. 'local', 'qa'")
                humanReadable(
                    "Which configured database connection to use. IDs come from db_list_profiles. " +
                    "Manual profiles live in Settings → Tools → Workflow Orchestrator → Agent → Database Profiles. " +
                    "IDE-managed data sources are surfaced read-only and return a friendly error because " +
                    "their credentials live in IntelliJ's encrypted store."
                )
                whenPresent(
                    "Resolved via lookupDbProfile: Found → proceed; IdeManaged → error with setup hint; " +
                    "NotFound → error with hint to call db_list_profiles. SQLite profiles return an " +
                    "immediate error before any connection attempt — db_stats is not supported for SQLite."
                )
                constraint("must match an existing manual profile — SQLite and IDE-managed profiles are blocked")
                example("local")
                example("qa")
                example("prod-readonly")
            }
            optional("database", "string") {
                llmSeesIt(
                    "Optional database name on the profile's server. Defaults to the " +
                    "profile's default database. Ignored for SQLite/Generic profiles."
                )
                humanReadable(
                    "On a multi-database server (one Postgres cluster hosting 'myapp' and 'analytics') " +
                    "this picks which catalog to inspect. The same way you'd switch databases in DBeaver " +
                    "while keeping the same server connection. SQLite has one database per file so the " +
                    "param is irrelevant there."
                )
                whenPresent(
                    "Forwarded to DatabaseConnectionManager.withConnection which substitutes the database " +
                    "segment of the JDBC URL for this call only — the saved profile is not mutated."
                )
                whenAbsent("Connects to DatabaseProfile.resolvedDefaultDatabase — whatever the profile's URL points at by default.")
                constraint("ignored for SQLite and Generic JDBC profiles where the URL names the database verbatim")
                example("analytics")
                example("myapp_staging")
            }
            optional("schema", "string") {
                llmSeesIt(
                    "Filter to one schema. If omitted, all user schemas are included. " +
                    "Examples: 'public', 'reporting', 'billing'."
                )
                humanReadable(
                    "The namespace inside the database. Without it you get the top 50 biggest tables " +
                    "across all user schemas. With it you get every table in that one namespace. " +
                    "On PostgreSQL 'public' is the typical default; SQL Server typically uses 'dbo'."
                )
                whenPresent("Switches from profile-only mode (top 50 across all schemas) to schema mode (all tables in this schema). If table is also provided, switches to single-table mode.")
                whenAbsent("Runs profile-only mode: all user tables, ordered by total size descending, capped at 50 rows.")
                example("public")
                example("reporting")
                example("dbo")
            }
            optional("table", "string") {
                llmSeesIt("Filter to one specific table. Requires schema to also be provided.")
                humanReadable(
                    "Narrows the result to a single table's stats row. Requires schema — without it " +
                    "the parameter is silently ignored. Returns one-row output with table size, index " +
                    "size, total size, row count, and last-analyzed date (PostgreSQL) or just " +
                    "row count and MB sizes (MySQL/SQL Server)."
                )
                whenPresent("Runs single-table mode: applies both schema and table filter to the engine-specific query. Returns at most one row.")
                whenAbsent("Reverts to schema mode (all tables in schema) when schema is set, or profile-only mode when schema is also absent.")
                constraint("requires `schema` to also be set — silently ignored without it, making the call run as profile-only")
                example("orders")
                example("user_events")
            }
        }
        verdict {
            keep(
                "Fills a genuine capability gap: cross-engine table-size and row-count stats with " +
                "human-readable size labels, no SQL required, no dialect switching. The PostgreSQL path " +
                "surfaces index size and last-analyzed date which db_query cannot produce without a " +
                "multi-join catalog query. The three-tier scope (profile / schema / table) maps cleanly " +
                "onto how DBAs actually investigate space issues. Low blast radius — read-only, no schema " +
                "mutation.",
                VerdictSeverity.STRONG,
            )
        }
        related("db_explain", Relationship.COMPOSE_WITH, "After db_stats reveals a large table, use db_explain on a slow query against that table to see whether its indexes are being used.")
        related("db_query", Relationship.ALTERNATIVE, "Use db_query with engine-specific catalog SQL when you need exact row counts on MySQL/SQL Server or need to filter by a custom criterion — at the cost of writing the right dialect yourself.")
        related("db_schema", Relationship.COMPLEMENT, "db_schema shows the structure (columns, PKs, FKs, indexes); db_stats shows the volume (row counts, storage sizes). Run both when investigating an unfamiliar table.")
        downside(
            "MySQL, SQL Server, and Generic JDBC row counts are estimates from information_schema, " +
            "not live counts. On tables that haven't been ANALYZEd recently the estimate can be " +
            "orders of magnitude off — common on tables with heavy insert/delete churn. " +
            "Follow up with db_query('SELECT count(*) FROM <table>') for an exact figure."
        )
        downside(
            "PostgreSQL stats can also be stale if ANALYZE (or autovacuum) hasn't run recently. " +
            "The last_analyzed column in the output reveals when statistics were last refreshed. " +
            "A 'never' value means the table has never been analyzed and n_live_tup may be 0 " +
            "even for a populated table."
        )
        downside("SQLite is unsupported — no row count or storage size API is available via standard JDBC for SQLite. The tool returns an error immediately without opening a connection.")
        downside(
            "Profile-only mode caps at 50 tables on PostgreSQL and MySQL (ORDER BY size DESC LIMIT 50). " +
            "The Generic JDBC fallback path has no LIMIT clause — it returns the full information_schema.tables " +
            "list ordered by table name. Narrow with `schema=` to control output size on Generic profiles; on " +
            "PG/MySQL the cap silently omits beyond rank 50."
        )
        downside(
            "The `database` parameter is silently ignored for SQLite and Generic JDBC profiles — " +
            "no error, no warning. The LLM may believe it is inspecting a different database when it isn't."
        )
        downside(
            "Output for large multi-schema listings can exceed 30K chars and will be auto-spilled " +
            "to {sessionDir}/tool-output/ via spillOrFormat. The LLM receives a preview (head 20 + " +
            "tail 10 lines) and must use read_file on the spill path to see the rest."
        )
        observation(
            "The three scoping modes (profile-only / schema / single-table) are determined by which " +
            "optional params are supplied, not by an explicit `action` enum — meaning the LLM must " +
            "read the parameter contract carefully. A future refactor could make the scope explicit " +
            "via an enum to prevent the silent-ignore of `table` when `schema` is missing."
        )
        flowchart("""
            flowchart TD
                A[LLM calls db_stats] --> B{profile present?}
                B -- no --> X1[Return missing-param error]
                B -- yes --> C[lookupDbProfile]
                C -- IdeManaged --> X2[Return: configure manual profile]
                C -- NotFound --> X3[Return: profile not found]
                C -- Found --> D{SQLite profile?}
                D -- yes --> X4[Return: SQLite not supported]
                D -- no --> E[withConnection: open JDBC]
                E --> F{engine type?}
                F -- PostgreSQL --> G[queryPostgres: pg_stat_user_tables]
                F -- MySQL --> H[queryMysql: information_schema.tables]
                F -- MSSQL/GENERIC --> I[queryGeneric: information_schema.tables]
                G & H & I --> J{schema + table set?}
                J -- table only\n(schema missing) --> K[table silently ignored → scope = all user tables]
                J -- schema only --> L[all tables in schema]
                J -- both --> M[single table row]
                J -- neither --> N[top 50 by size]
                K & L & M & N --> O[resultSetToMarkdown]
                O --> P[spillOrFormat preview if >30K]
                P --> Q[Return ToolResult with spillPath]
        """)
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val profileId = params["profile"]?.jsonPrimitive?.content?.trim()
            ?: return error("'profile' parameter is required. Call db_list_profiles to see options.")
        val database = params["database"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val schema = params["schema"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val table = params["table"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

        val profile = when (val lookup = lookupDbProfile(project, profileId)) {
            is DbProfileLookup.Found -> lookup.profile
            is DbProfileLookup.IdeManaged -> return ToolResult(
                content = "Profile '$profileId' (${lookup.displayName}) is an IDE-managed data source. " +
                    "IDE profile credentials are not available to the agent. " +
                    "To run queries, configure a manual profile in Settings → Tools → Workflow Orchestrator → Agent → Database Profiles.",
                summary = "db_stats: IDE profile credentials not available",
                tokenEstimate = 80,
                isError = true,
            )
            is DbProfileLookup.NotFound -> return error(
                "Profile '$profileId' not found. Call db_list_profiles to see available profiles."
            )
        }

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
