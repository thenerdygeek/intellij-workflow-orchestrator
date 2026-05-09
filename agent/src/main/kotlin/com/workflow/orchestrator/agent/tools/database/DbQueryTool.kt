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

    override fun documentation(): ToolDocumentation = toolDoc("db_query") {
        summary {
            technical("Executes a single read-only SQL SELECT against a configured `DatabaseProfile` over a short-lived JDBC connection. Defence in depth: prefix-based statement allow-list (rejects INSERT/UPDATE/DELETE/DDL/DCL/EXEC/CALL before any I/O), `Connection.isReadOnly=true`, `autoCommit=false` with rollback on close, and `Statement.queryTimeout=30s`. Results are rendered as a Markdown table truncated at 200 rows × 500 chars/cell; the formatted output is always passed through `spillOrFormat` so large result sets are auto-spilled to `{sessionDir}/tool-output/`.")
            plain("Like opening your IDE's database tool window, typing a SELECT query, and hitting Run — except the agent is the one driving. The tool refuses anything that would change the data, only opens a read-only connection, and caps results at 200 rows so a runaway `SELECT *` can't drown the chat.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without db_query, the LLM cannot directly observe production / staging / local DB state. It would have to either (a) `run_command mysql -e '...'` / `psql -c '...'` — assuming a CLI client is on PATH (often it isn't), credentials are reachable (PasswordSafe-stored profile passwords aren't), and the LLM picks the right driver flags per engine, or (b) write throw-away application code that opens a JDBC connection itself, which is approval-gated and easily 10× more iterations. In practice, killing this tool means the agent stops being able to debug data-shaped bugs (`why is this row in PENDING?`, `how many users hit this code path yesterday?`)."
        )
        llmMistake("Writes `SELECT * FROM <big_table>` with no WHERE/LIMIT — the table has 5M rows but the result is silently truncated at 200; the LLM treats those 200 as representative and reasons from a biased sample. Should always pair broad SELECTs with `LIMIT`, `WHERE`, or an aggregate.")
        llmMistake("Mixes engine dialects — uses `LIMIT 10 OFFSET 20` against SQL Server (which wants `OFFSET ... FETCH NEXT`), or `TOP 10` against PostgreSQL. The blocked-prefix validator does not catch dialect errors, so the call returns a JDBC syntax error and the LLM has to retry. Should call `db_list_profiles` first to learn `dbType`, or `db_schema` to confirm the engine.")
        llmMistake("Skips `db_list_databases` and queries against the profile's default DB when the data lives elsewhere on the same server (e.g. a Postgres profile defaulting to `postgres` when the app DB is `myapp`). Symptom is an empty result or a 'relation does not exist' error.")
        llmMistake("Tries DML hoping it'll slip through — `UPDATE orders SET status='X' WHERE id=1`. Blocked at the prefix check with 'Blocked: \\'UPDATE\\' statements are not permitted.' The LLM occasionally re-tries by quoting the statement or wrapping in a CTE; both still fail because the validator only inspects the first keyword.")
        llmMistake("Forgets to fill the `description` param — the user's approval dialog then shows an empty 'why is the agent running this?' line, increasing the chance of the user denying the call. Description is optional in the schema but high-leverage in practice.")
        llmMistake("Pastes the database name into `sql` (e.g. `SELECT * FROM analytics.events`) AND also passes `database='analytics'` — works on MySQL/Postgres, but on SQL Server the cross-database reference may fail because the JDBC connection is bound to a specific catalog after `database=` is applied.")
        params {
            required("profile", "string") {
                llmSeesIt("Profile ID to query (from db_list_profiles), e.g. 'local', 'qa'")
                humanReadable("Which configured database connection to use — like picking a saved connection in DBeaver. The ID comes from `db_list_profiles`; manual profiles live in `Settings → Tools → Workflow Orchestrator → Agent → Database Profiles` (XML-backed via `DatabaseSettings`), and IDE-managed data sources are surfaced read-only with their displayName.")
                whenPresent("Resolved through `lookupDbProfile` — manual profile (Settings UI) → `DbProfileLookup.Found`; IDE-managed data source → `DbProfileLookup.IdeManaged` (returns an explanatory error because IDE-stored credentials are not reachable from the agent); unknown id → `DbProfileLookup.NotFound` with a hint to call `db_list_profiles`.")
                constraint("must match an existing manual profile — IDE-managed data sources are surfaced but not queryable from the agent (their credentials live in IntelliJ's encrypted store, not PasswordSafe)")
                example("local")
                example("qa")
                example("prod-readonly")
            }
            required("sql", "string") {
                llmSeesIt("The SQL SELECT query to execute")
                humanReadable("The actual SQL — must start with SELECT (or WITH … SELECT). Anything else (INSERT/UPDATE/DELETE/DROP/CREATE/ALTER/TRUNCATE/REPLACE/MERGE/GRANT/REVOKE/EXEC/EXECUTE/CALL/DO) is rejected before the connection is even opened. Engine dialect (PG vs MySQL vs SQL Server vs SQLite) is the LLM's responsibility — there's no portability shim.")
                whenPresent("`validateReadOnly(sql)` strips leading whitespace, uppercases, and rejects any statement starting with one of the 14 blocked prefixes. If accepted, runs against the profile's connection with `queryTimeout=30s` and the result is converted to a Markdown table via `resultSetToMarkdown` (200 rows max, 500 chars per cell, ellipsis-truncated).")
                constraint("first non-whitespace keyword must NOT be one of: INSERT, UPDATE, DELETE, DROP, CREATE, ALTER, TRUNCATE, REPLACE, MERGE, GRANT, REVOKE, EXEC, EXECUTE, CALL, DO")
                constraint("queryTimeout=30s — long-running analytical queries WILL be killed; pre-aggregate or narrow the WHERE clause")
                constraint("at most 200 rows are materialized; rows beyond that are silently dropped with a `_Results truncated at 200 rows._` footer")
                constraint("each cell is truncated to 500 chars with a `…` suffix — large TEXT/BLOB columns will appear clipped")
                example("SELECT id, name FROM orders WHERE status='PENDING' LIMIT 20")
                example("SELECT count(*) FROM events WHERE created_at > now() - interval '1 day'")
                example("WITH recent AS (SELECT * FROM logs WHERE ts > now() - interval '1 hour') SELECT level, count(*) FROM recent GROUP BY level")
            }
            optional("database", "string") {
                llmSeesIt("Optional database name on the profile's server. Defaults to the profile's default database. Use db_list_databases to see what's available. Ignored for SQLite/Generic profiles.")
                humanReadable("Lets one server-engine profile reach any database on that server — the same way DBeaver lets you switch databases under a single connection. Useful when your Postgres profile defaults to `postgres` but the app's data is in `myapp`.")
                whenPresent("Forwarded to `DatabaseProfile.jdbcUrlFor(database)`, which substitutes the database segment of the JDBC URL for PostgreSQL/MySQL/SQL Server profiles. The new URL is opened for this single call only — the saved profile is not mutated.")
                whenAbsent("Connects to `DatabaseProfile.resolvedDefaultDatabase` (the database configured in the profile dialog).")
                constraint("ignored for SQLite (the URL points at a file) and Generic (the user supplies the URL verbatim)")
                constraint("must be a database that exists on the server — typos return a JDBC connection error, not a friendly 'use db_list_databases' hint")
                example("analytics")
                example("myapp_staging")
            }
            optional("description", "string") {
                llmSeesIt("Brief description of what this query investigates (shown in the approval dialog)")
                humanReadable("A one-liner shown to the user in the approval dialog so they understand WHY the agent wants to run this query — like a commit message for a single SELECT. Skipping it leaves the user staring at raw SQL with no context, which makes denial more likely.")
                whenPresent("Surfaced verbatim in the approval-gate UI alongside the rendered SQL.")
                whenAbsent("Approval dialog shows just the SQL; no contextual blurb. Functionally fine; UX-noisier.")
                example("Check how many orders are stuck in PENDING")
                example("Verify the bug — does user 42 have duplicate sessions?")
            }
            optional("grep_pattern", "string") {
                llmSeesIt("Regex pattern to filter output lines. Only lines matching this pattern are returned. Use when you only need specific information from a potentially large output.")
                humanReadable("Auto-injected by AgentLoop because `db_query` is in `OUTPUT_FILTERABLE_TOOLS`. A second-stage line filter applied AFTER the Markdown table is built — like piping `db_query | grep <grep_pattern>`. Useful for narrowing a wide multi-column result to just the rows mentioning a specific value, without rewriting the SQL.")
                whenPresent("Lines of the rendered Markdown table that don't match the regex are dropped before the LLM sees them; filtering happens before spill, so it also shrinks any spilled file.")
                whenAbsent("No second-stage filter — the full (200-row-capped) Markdown table is returned subject to ToolOutputSpiller.")
                constraint("filters Markdown output, not SQL — so it sees the table border lines (`| --- |`), the header row, and the trailing `_N row(s) returned._`. Pattern must tolerate or skip those")
                example("PENDING|FAILED")
                example("user_42")
            }
            optional("output_file", "boolean") {
                llmSeesIt("If true, save full output to a file and return a preview with the file path. Use for large outputs you may need to search later. Read the file with read_file or search_code.")
                humanReadable("Auto-injected by AgentLoop. When `true`, the full Markdown table is written to `{sessionDir}/tool-output/db_query-<epoch>-output.txt` and the LLM gets a head-20 + tail-10 preview plus the file path. Pattern: query once broad, then `read_file`/`search_code` against the spilled file instead of re-running the SELECT.")
                whenPresent("`spillOrFormat` writes the full content to disk and returns a preview; `ToolResult.spillPath` carries the path so the LLM can re-read it.")
                whenAbsent("`spillOrFormat` is still called (every db_query result goes through it) but only auto-spills when content exceeds 30K chars. Smaller results are inlined verbatim.")
                example("true")
            }
        }
        verdict {
            keep(
                "High-value, low-blast-radius. Hard-blocks DML/DDL at the prefix level, opens read-only connections, " +
                    "and caps result size — so it lets the agent debug data-shaped bugs without risk of mutating " +
                    "production state. Removing it forces the agent into shell-CLI fallbacks that are approval-gated, " +
                    "OS-divergent, credential-blind (PasswordSafe profile creds aren't reachable from the shell), " +
                    "and that bypass the read-only enforcement.",
                VerdictSeverity.STRONG,
            )
        }
        related("db_list_profiles", Relationship.COMPLEMENT, "Call first when you don't know which profiles exist — db_query needs a valid profile id to do anything.")
        related("db_list_databases", Relationship.COMPLEMENT, "Call before db_query when the profile is a server engine and you need to discover databases beyond the default.")
        related("db_schema", Relationship.COMPLEMENT, "Call before db_query to learn table/column names — saves a round-trip of guess-then-fix on column names.")
        related("db_explain", Relationship.COMPOSE_WITH, "After a slow db_query, use db_explain on the same SQL to see the engine's query plan and find the missing index.")
        related("db_stats", Relationship.SEE_ALSO, "Use for table-level row count / size summaries; cheaper than `SELECT count(*) FROM …` on large tables.")
        downside("Hard 200-row cap with no `limit` parameter — the LLM can't ask for more without rewriting the SQL with explicit `LIMIT` / `OFFSET` pagination. Large analytical results are silently truncated.")
        downside("30s queryTimeout is non-configurable — long analytical queries get killed mid-flight with a JDBC timeout error. Pre-aggregate or narrow before calling.")
        downside("Cell values truncated at 500 chars with a `…` suffix — large JSON/TEXT/BLOB columns appear clipped, and the LLM may parse the truncated value as if it were complete.")
        downside("Prefix-based read-only check is conservative — `WITH … SELECT` (CTE) works because it starts with `WITH` (not blocked), but `WITH … INSERT` and `WITH … UPDATE` would also slip past the prefix gate. Defence in depth (`isReadOnly=true` + `autoCommit=false` + final rollback) is what actually stops writes; a misbehaving driver that ignores `isReadOnly` would be the failure mode.")
        downside("IDE-managed data sources (the ones that show up in IntelliJ's Database tool window) are listed but not queryable — their credentials live in IntelliJ's encrypted store and are not exposed via reflection. Users have to re-enter them as a manual profile.")
        downside("No support for prepared statements / bind parameters — the LLM concatenates values into the SQL string, so a maliciously crafted user-supplied value in the prompt could in principle be SQL-injected into the query the agent runs. Mitigated by read-only enforcement (worst case: data exfiltration via crafted SELECT, not data corruption).")
        downside("`database` parameter is silently ignored for SQLite and Generic profiles — no error, no warning. The LLM may believe it's switching databases when it isn't.")
        downside("Each call opens a fresh JDBC connection (no pool reuse) — fine for one-off agent queries, but bursting many db_query calls in a row pays connect overhead each time and can hit per-DB connection limits on shared dev databases.")
        flowchart("""
            flowchart TD
                A[LLM calls db_query] --> B{profile + sql present?}
                B -- no --> X1[Return missing-param error]
                B -- yes --> C[validateReadOnly: prefix check]
                C -- blocked prefix --> X2[Return 'Blocked: STMT not permitted']
                C -- ok --> D[lookupDbProfile]
                D -- NotFound --> X3[Return 'profile not found']
                D -- IdeManaged --> X4[Return 'IDE creds not available']
                D -- Found --> E[withConnection: open read-only JDBC]
                E --> F[autoCommit=false; isReadOnly=true]
                F --> G[createStatement queryTimeout=30s]
                G --> H[executeQuery]
                H -- timeout/SQLException --> X5[Return error + connectionErrorHint]
                H -- ok --> I[resultSetToMarkdown: cap 200 rows]
                I --> J[spillOrFormat preview if >30K]
                J --> K[Return ToolResult with spillPath]
        """)
    }

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
