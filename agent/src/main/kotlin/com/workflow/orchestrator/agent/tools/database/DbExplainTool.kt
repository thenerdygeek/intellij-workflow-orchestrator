package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
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

    override fun documentation(): ToolDocumentation = toolDoc("db_explain") {
        summary {
            technical("Returns the engine's query plan for a single SELECT statement. Reuses `DatabaseConnectionManager.validateReadOnly` (rejects DML/DDL by prefix), opens a short-lived `autoCommit=false`+`isReadOnly=true` connection, and runs `EXPLAIN <sql>` (or `EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) <sql>` on PostgreSQL when `analyze=true`). Plan rows are joined with newlines and rendered as a fenced code block; the formatted output is always passed through `spillOrFormat` so multi-page plans are auto-spilled to `{sessionDir}/tool-output/`.")
            plain("Like running `EXPLAIN ANALYZE` in psql or `EXPLAIN FORMAT=JSON` in MySQL — shows the agent how the database intends to (or did) run the query: which indexes it picked, which scans it used, and how many rows it estimated vs. actually saw. Same read-only guards as db_query, so even when ANALYZE actually executes the SELECT to gather runtime stats, nothing gets written back.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without db_explain, the LLM falls back to `db_query(\"EXPLAIN ...\")`, which works on Postgres/MySQL but means the LLM has to remember the engine-specific dialect: PostgreSQL `EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) ...`, MySQL `EXPLAIN FORMAT=TREE ...` (8.0.18+) or `EXPLAIN FORMAT=JSON`, SQL Server `SET SHOWPLAN_TEXT ON` then re-run, SQLite `EXPLAIN QUERY PLAN`. db_query also Markdown-tables the output, so a Postgres TEXT plan ends up cell-truncated at 500 chars per line, mangling indentation. Net: ~2-3 extra turns per investigation and brittle on less-common engines."
        )
        llmMistake("Forgets that on PostgreSQL `EXPLAIN ANALYZE` actually executes the statement to gather runtime stats — believes it's a 'dry run'. Safety-wise it's fine here (the connection rolls back), but the LLM sometimes uses `analyze=true` on a query the user said 'don't run yet' and is surprised when expensive computation happens. The plain `analyze=false` path is a true cost-only estimate and doesn't execute.")
        llmMistake("Sets `analyze=true` on a non-PostgreSQL profile expecting MySQL-style `EXPLAIN ANALYZE` (8.0.18+) — but the tool only injects the `(ANALYZE, BUFFERS, FORMAT TEXT)` flags when `dbType == POSTGRESQL`. For MySQL/SQL Server/SQLite the `analyze` flag is silently ignored and a plain `EXPLAIN` runs, producing only cost estimates. The LLM then misreads cost-only output as actual runtime stats.")
        llmMistake("Runs db_explain on a non-SELECT — INSERT/UPDATE/DELETE — assuming EXPLAIN is itself a 'safe wrapper' that prevents execution. The tool blocks this at `validateReadOnly` because the prefix is INSERT/UPDATE/DELETE; never even reaches the EXPLAIN keyword. The LLM occasionally retries with `EXPLAIN UPDATE ...` as the SQL value (which would technically be a SELECT-ish prefix on some engines), but that still fails because the prefix check only inspects the first keyword and `EXPLAIN` itself is not in the allow-list — meaning even `EXPLAIN SELECT ...` would be blocked if passed verbatim. The contract is: pass the bare SELECT, the tool wraps it.")
        llmMistake("Pastes the EXPLAIN keyword into the `sql` parameter — `db_explain(sql=\"EXPLAIN SELECT ...\")` — producing `EXPLAIN EXPLAIN SELECT ...` on the wire, which is a JDBC syntax error. The contract is to pass the bare SELECT and let the tool prepend EXPLAIN.")
        llmMistake("Reads a Postgres plan with `Seq Scan on big_table  (cost=0.00..1234.00 rows=1000)` and concludes 'this is fine' without checking that the planner-estimated `rows=1000` matches reality — only `analyze=true` shows the `actual rows=N` count. Cost-only plans are estimates from cached statistics that may be stale (run `ANALYZE` on the table first to refresh).")
        llmMistake("Doesn't realize that `database` parameter is silently ignored for SQLite/Generic profiles — passes `database=\"analytics\"` against a SQLite profile thinking the EXPLAIN runs on a different file; in reality it runs against whatever file the profile's URL points at.")
        params {
            required("profile", "string") {
                llmSeesIt("Profile ID to query (from db_list_profiles), e.g. 'local', 'qa'")
                humanReadable("Which configured database connection to use — same picker semantics as db_query. The id comes from `db_list_profiles`; manual profiles live in `Settings → Tools → Workflow Orchestrator → Agent → Database Profiles`, and IDE-managed data sources are surfaced read-only with their displayName.")
                whenPresent("Resolved through `lookupDbProfile` — manual profile → `DbProfileLookup.Found`; IDE-managed data source → `DbProfileLookup.IdeManaged` (returns an explanatory error because IDE-stored credentials are not reachable from the agent); unknown id → `DbProfileLookup.NotFound` with a hint to call `db_list_profiles`.")
                constraint("must match an existing manual profile — IDE-managed data sources are surfaced but not queryable from the agent (their credentials live in IntelliJ's encrypted store, not PasswordSafe)")
                example("local")
                example("qa")
                example("prod-readonly")
            }
            required("sql", "string") {
                llmSeesIt("The SELECT statement whose execution plan to show")
                humanReadable("The bare SELECT — do NOT prefix it with `EXPLAIN`; the tool wraps it. Engine dialect is the LLM's responsibility (Postgres `LIMIT/OFFSET`, SQL Server `TOP`/`OFFSET FETCH`, etc.).")
                whenPresent("`validateReadOnly(sql)` strips leading whitespace, uppercases, and rejects any statement starting with one of the 14 blocked prefixes (INSERT/UPDATE/DELETE/DROP/CREATE/ALTER/TRUNCATE/REPLACE/MERGE/GRANT/REVOKE/EXEC/EXECUTE/CALL/DO). If accepted, the tool prepends `EXPLAIN` (or `EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)` on Postgres + `analyze=true`) and runs it with `queryTimeout=30s`.")
                constraint("first non-whitespace keyword must NOT be one of: INSERT, UPDATE, DELETE, DROP, CREATE, ALTER, TRUNCATE, REPLACE, MERGE, GRANT, REVOKE, EXEC, EXECUTE, CALL, DO")
                constraint("must NOT be pre-wrapped with `EXPLAIN` — the tool prepends it; doing so produces `EXPLAIN EXPLAIN <sql>` which is a syntax error")
                constraint("queryTimeout=30s applies — for long-running queries, ANALYZE may exceed this; reduce the WHERE clause first")
                example("SELECT * FROM orders WHERE status='PENDING'")
                example("SELECT id FROM users WHERE email='x@y.com'")
                example("SELECT count(*) FROM events WHERE created_at > now() - interval '1 day'")
            }
            optional("database", "string") {
                llmSeesIt("Optional database name on the profile's server. Defaults to the profile's default database. Use db_list_databases to see what's available. Ignored for SQLite/Generic profiles.")
                humanReadable("Lets one server-engine profile reach any database on that server. Useful when the profile defaults to `postgres` but the query targets `myapp`.")
                whenPresent("Forwarded to `DatabaseProfile.jdbcUrlFor(database)`, which substitutes the database segment of the JDBC URL for PostgreSQL/MySQL/SQL Server profiles. The new URL is opened for this single call only — the saved profile is not mutated.")
                whenAbsent("Connects to `DatabaseProfile.resolvedDefaultDatabase` (the database configured in the profile dialog).")
                constraint("ignored for SQLite (the URL points at a file) and Generic (the user supplies the URL verbatim) — no error, no warning, just silently uses the profile's URL as-is")
                constraint("must be a database that exists on the server — typos return a JDBC connection error")
                example("analytics")
                example("myapp_staging")
            }
            optional("analyze", "boolean") {
                llmSeesIt("When true, runs EXPLAIN ANALYZE (PostgreSQL only: includes BUFFERS). The statement is actually executed but immediately rolled back — data is safe. Defaults to false.")
                humanReadable("Toggles between cost-only estimation (false, fast, no execution) and actual runtime stats (true, slow, executes the query). On PostgreSQL only — silently ignored on MySQL/SQL Server/SQLite.")
                whenPresent("On PostgreSQL: switches the wrap to `EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) <sql>`, which actually runs the SELECT to gather `actual time`, `rows`, and buffer-hit stats. The result includes `Planning Time` and `Execution Time` lines. The `autoCommit=false` + always-rollback pattern guarantees no writes survive even though the query was 'really' executed. On non-Postgres engines: the flag is silently ignored and a plain `EXPLAIN` runs.")
                whenAbsent("Defaults to `false` — runs plain `EXPLAIN <sql>`, which on every supported engine returns cost estimates from cached statistics WITHOUT executing the SELECT. Fast and cheap; data may be stale if `ANALYZE`/`OPTIMIZE TABLE` hasn't run recently.")
                constraint("only honoured for PostgreSQL profiles — silently ignored otherwise (no error)")
                constraint("when true, the SELECT really executes — same wall-clock cost as running it via db_query, but with rollback")
                example("true")
                example("false")
            }
            optional("grep_pattern", "string") {
                llmSeesIt("Regex pattern to filter output lines. Only lines matching this pattern are returned. Use when you only need specific information from a potentially large output.")
                humanReadable("Auto-injected by AgentLoop because `db_explain` is in `OUTPUT_FILTERABLE_TOOLS`. A second-stage line filter applied after the plan is rendered — useful for grep-ing 'Seq Scan', 'Index Scan', 'Hash Join', etc., out of a multi-page plan.")
                whenPresent("Lines of the rendered plan that don't match the regex are dropped before the LLM sees them; filtering happens before spill, so it also shrinks any spilled file.")
                whenAbsent("No second-stage filter — the full plan is returned subject to ToolOutputSpiller.")
                constraint("filters the rendered Markdown output (which includes the SQL fence and plan fence), not the raw plan rows — pattern must tolerate the fenced-code-block markers")
                example("Seq Scan|Index Scan")
                example("rows=")
            }
            optional("output_file", "boolean") {
                llmSeesIt("If true, save full output to a file and return a preview with the file path. Use for large outputs you may need to search later. Read the file with read_file or search_code.")
                humanReadable("Auto-injected by AgentLoop. When `true`, the full plan is written to `{sessionDir}/tool-output/db_explain-<epoch>-output.txt` and the LLM gets a head-20 + tail-10 preview plus the file path. Useful for huge nested-loop plans you want to grep through later.")
                whenPresent("`spillOrFormat` writes the full content to disk and returns a preview; `ToolResult.spillPath` carries the path so the LLM can re-read it.")
                whenAbsent("`spillOrFormat` is still called (every db_explain result goes through it) but only auto-spills when content exceeds 30K chars. Smaller plans are inlined verbatim.")
                example("true")
            }
        }
        verdict {
            keep(
                "Niche but high-leverage. Performance debugging is a real workflow (slow query → check plan → add index → re-explain), and the alternative — db_query with engine-specific EXPLAIN dialect — is genuinely worse: each engine has different syntax, the Markdown-table renderer mangles plan indentation, and the LLM has to remember which dialect to use. Same read-only guards as db_query (validateReadOnly + isReadOnly + autoCommit=false rollback), so blast radius is identical. Worth the schema slot.",
                VerdictSeverity.NORMAL,
            )
        }
        related("db_query", Relationship.COMPOSE_WITH, "Identify a slow query via db_query, then run db_explain on the same SQL to see why — Seq Scan vs Index Scan, missing where clauses, bad row estimates.")
        related("db_schema", Relationship.COMPLEMENT, "Read the table's indexes via db_schema before db_explain so you know which indexes the planner could have picked but didn't.")
        related("db_stats", Relationship.SEE_ALSO, "Use for table-level row counts and storage size — sometimes the cheaper answer to 'why is this slow?' is 'the table is 50GB'.")
        related("db_list_profiles", Relationship.COMPLEMENT, "Required first call when the LLM doesn't yet know which profile id to pass.")
        related("db_list_databases", Relationship.COMPLEMENT, "Call before db_explain when the EXPLAIN target lives in a non-default database on a server-engine profile.")
        downside("`analyze=true` is PostgreSQL-only — silently ignored on MySQL/SQL Server/SQLite. The LLM gets a plain EXPLAIN back and may not realize the runtime stats it asked for are missing. No warning, no error.")
        downside("`analyze=true` actually executes the SELECT (Postgres 'EXPLAIN ANALYZE' runs the query to collect runtime stats). The connection's `autoCommit=false` + always-rollback contract makes this safe for SELECT (no writes survive), but a side-effecting function call inside the SELECT (e.g. `SELECT my_logging_proc()`) WOULD execute. The blocked-prefix gate doesn't inspect function calls — only the first keyword.")
        downside("Plan output is engine-specific text — Postgres returns a TEXT tree, MySQL returns a tabular EXPLAIN, SQL Server returns a flat estimate, SQLite returns `id|parent|notused|detail` rows. The LLM has to know each format to interpret the output; there's no normalization layer.")
        downside("Cost estimates use the engine's cached statistics (`pg_stats`, `INFORMATION_SCHEMA.STATISTICS`, etc.) which can be wildly stale on a database that hasn't been ANALYZED/OPTIMIZED recently. A plan claiming `rows=10` may actually scan 10M — only `analyze=true` (Postgres) reveals the truth.")
        downside("Hard `queryTimeout=30s` — for `analyze=true` on a slow query this can fire mid-execution and return a JDBC timeout instead of a partial plan. The LLM should pre-narrow with WHERE before asking for ANALYZE.")
        downside("Each call opens a fresh JDBC connection (no pool reuse) — fine for one-off calls, but pays connect overhead per invocation.")
        downside("`database` parameter is silently ignored for SQLite/Generic profiles — same gotcha as db_query.")
        flowchart("""
            flowchart TD
                A[LLM calls db_explain] --> B{profile + sql present?}
                B -- no --> X1[Return missing-param error]
                B -- yes --> C[validateReadOnly: prefix check]
                C -- blocked prefix --> X2[Return 'Blocked: STMT not permitted']
                C -- ok --> D[lookupDbProfile]
                D -- NotFound --> X3[Return 'profile not found']
                D -- IdeManaged --> X4[Return 'IDE creds not available']
                D -- Found --> E{dbType == POSTGRESQL && analyze?}
                E -- yes --> F1[Wrap: EXPLAIN ANALYZE BUFFERS FORMAT TEXT]
                E -- no --> F2[Wrap: EXPLAIN]
                F1 --> G[withConnection: open read-only JDBC]
                F2 --> G
                G --> H[autoCommit=false; isReadOnly=true]
                H --> I[createStatement queryTimeout=30s]
                I --> J[executeQuery on wrapped SQL]
                J -- timeout/SQLException --> X5[Return error + connectionErrorHint]
                J -- ok --> K[Join plan rows with newlines]
                K --> L[Render with SQL + plan fenced blocks]
                L --> M[spillOrFormat preview if >30K]
                M --> N[Return ToolResult with spillPath]
                N --> Z[finally: rollback + close connection]
        """)
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val profileId = params["profile"]?.jsonPrimitive?.content?.trim()
            ?: return error("'profile' parameter is required. Call db_list_profiles to see options.")
        val database = params["database"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val sql = params["sql"]?.jsonPrimitive?.content?.trim()
            ?: return error("'sql' parameter is required.")
        val analyze = params["analyze"]?.jsonPrimitive?.booleanOrNull ?: false

        // Validate read-only before touching the database
        DatabaseConnectionManager.validateReadOnly(sql)?.let { msg -> return error(msg) }

        val profile = when (val lookup = lookupDbProfile(project, profileId)) {
            is DbProfileLookup.Found -> lookup.profile
            is DbProfileLookup.IdeManaged -> return ToolResult(
                content = "Profile '$profileId' (${lookup.displayName}) is an IDE-managed data source. " +
                    "IDE profile credentials are not available to the agent. " +
                    "To run queries, configure a manual profile in Settings → Tools → Workflow Orchestrator → Agent → Database Profiles.",
                summary = "db_explain: IDE profile credentials not available",
                tokenEstimate = 80,
                isError = true,
            )
            is DbProfileLookup.NotFound -> return error(
                "Profile '$profileId' not found. Call db_list_profiles to see available profiles."
            )
        }

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
                val raw = "$modeLabel for **$targetLabel** (${profile.dbType.displayName}):\n\n" +
                    "```sql\n$sql\n```\n\n" +
                    "```\n$plan\n```"
                val spilled = spillOrFormat(raw, project)
                ToolResult(
                    content = spilled.preview,
                    summary = "db_explain on '$summaryProfileLabel' ($modeLabel)",
                    tokenEstimate = TokenEstimator.estimate(spilled.preview),
                    spillPath = spilled.spilledToFile,
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
