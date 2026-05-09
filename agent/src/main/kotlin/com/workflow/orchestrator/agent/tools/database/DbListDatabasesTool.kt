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

    override fun documentation(): ToolDocumentation = toolDoc("db_list_databases") {
        summary {
            technical(
                "Lists databases hosted on a server profile (PostgreSQL/MySQL/SQL Server) by running an engine-specific discovery query " +
                    "(`pg_database`, `SHOW DATABASES`, `sys.databases`) and filtering system DBs. SQLite/Generic profiles are rejected — " +
                    "they map 1:1 to a single DB so listing is meaningless."
            )
            plain(
                "Like running `SHOW DATABASES;` in MySQL — discovery before querying. Tells the agent which databases exist on a server " +
                    "so it knows where to point its next query. Only useful when one profile = one server with multiple databases (Postgres/MySQL/MS SQL); " +
                    "for SQLite or a generic JDBC URL the profile already pins a single database, so this tool refuses to run."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without db_list_databases, the LLM either (a) falls back to `db_query(profile=\"x\", sql=\"SHOW DATABASES\")`, which is engine-specific — " +
                "the right SQL is `SELECT datname FROM pg_database` on Postgres, `SHOW DATABASES` on MySQL, `SELECT name FROM sys.databases` on SQL Server, " +
                "and a hard error on SQLite — so the model has to remember dialect details and skip system DBs by hand, OR (b) it has no discovery affordance at all " +
                "and guesses database names from the profile's default. Net cost: a few wasted dialect-mismatch retries per cold-start, plus the agent never sees " +
                "non-default databases on the same server."
        )
        llmMistake(
            "Calls db_list_databases on a SQLite profile (or generic JDBC URL) — the tool errors with " +
                "'no concept of multiple databases per server. Use db_schema directly.' The LLM should check `db_list_profiles` output for the dbType first."
        )
        llmMistake(
            "Re-runs db_list_databases at the start of every turn instead of remembering the result. " +
                "The list barely changes — caching it once per session is sufficient. Each call costs a real round-trip + connection."
        )
        llmMistake(
            "Confuses `database` (a whole DB on the server, e.g. `analytics` vs `mydb` on the same Postgres cluster) with `schema` " +
                "(a namespace WITHIN a database, e.g. `public`, `reporting`). After db_list_databases the next step is db_schema, not another db_list_databases."
        )
        llmMistake(
            "Skips db_list_databases entirely and assumes the profile's default database is the only one — misses sibling databases on the same server " +
                "that the user might want queried (e.g. `prod_main` and `prod_audit` on one Postgres cluster, both reachable from one profile)."
        )
        params {
            required("profile", "string") {
                llmSeesIt("Profile ID (from db_list_profiles), e.g. 'local', 'qa'")
                humanReadable(
                    "Which configured connection to use — the same id you saw in `db_list_profiles` output. One profile points at one server; " +
                        "this tool then asks that server which databases live on it."
                )
                whenPresent(
                    "Profile is looked up via `lookupDbProfile`. If it's a server-type profile (Postgres/MySQL/SQL Server) the engine's " +
                        "discovery query runs; system databases (`pg_toast`, `mysql`, `master`, etc.) are filtered out before the result is returned."
                )
                constraint("must resolve to a manually-configured profile — IDE-managed Database tool window data sources error out because their credentials aren't accessible to the agent")
                constraint("must point at a server-type engine (Postgres / MySQL / SQL Server) — SQLite and Generic JDBC profiles return an error directing the LLM to db_schema instead")
                example("local")
                example("qa")
                example("prod-readonly")
            }
        }
        verdict {
            keep(
                "Earns its slot for multi-database servers — Postgres clusters with `prod_main` + `prod_audit`, MySQL servers with multiple application schemas, " +
                    "SQL Server instances hosting several apps. Without it the LLM has to remember three dialect-specific discovery queries and the system-DB " +
                    "exclusion rules. The hand-written engine adapters (`DatabaseDiscovery.discoveryQuery` + `filterSystemDatabases`) eliminate a class of dialect bugs.",
                VerdictSeverity.NORMAL,
            )
            drop(
                "In the common single-database deployment (most apps pin one DB per profile), this tool is dead weight — the LLM should call `db_schema` directly. " +
                    "Real-world telemetry might show <10% of agent sessions ever benefit from listing sibling databases, and `db_query(profile, \"SHOW DATABASES\")` " +
                    "covers the niche cases (Postgres+MySQL — the two most common) at the cost of a single dialect dispatch line in the prompt. Schema-token cost (tool " +
                    "definition) is paid every session whether anyone uses it or not.",
                VerdictSeverity.WEAK,
            )
        }
        observation(
            "The 'profile resolved default database' marker (`*(default)*`) overlaps with `db_list_profiles` output, which already prints `Default DB: \\`x\\``. " +
                "Two tools rendering the same fact slightly differently is a small DX papercut."
        )
        mergeOpportunity(
            "Could be folded into `db_schema` as a level-0 hierarchy step (`db_schema(profile=\"x\")` with no schema returns 'list of databases on this server' " +
                "for server profiles, then schemas for SQLite/Generic). `db_schema` already implements 3-level hierarchy (schemas → tables → columns); adding " +
                "'databases on server' as level 0 is a natural extension and would shrink the deferred-tool catalog by one. Trade-off: `db_schema` would need a " +
                "branch on `isServerProfile && schema == null && database == null`, which is a small complexity bump for a meaningful surface-area cut."
        )
        observation(
            "Could NOT plausibly fold into `db_list_profiles`. Profiles are *configured* (a list of server connections the user defined in Settings UI, plus IDE data sources). " +
                "Databases are *discovered at runtime by querying the server*. They are different concerns — db_list_profiles never connects, db_list_databases always does. " +
                "Merging would make `db_list_profiles` a write-path-with-network-IO, breaking its current 'pure config read' contract."
        )
        related("db_list_profiles", Relationship.COMPLEMENT, "Call first to discover which servers exist; this tool then lists databases on the chosen server. Different concerns: profiles = config, databases = runtime discovery.")
        related("db_schema", Relationship.COMPLEMENT, "Drill into a discovered database with db_schema(profile, database, schema?, table?) to see schemas → tables → columns.")
        related("db_query", Relationship.COMPLEMENT, "Once a database is chosen, run actual queries against it with db_query(profile, database=\"<name>\", sql=\"...\").")
        related("db_stats", Relationship.SEE_ALSO, "Same conceptual layer — discovery/inspection of an existing database; both are read-only and depend on a resolved profile.")
        downside("Depends on connection grants — many DB users in production lack permission to list all databases (e.g. a least-privilege Postgres role). The tool surfaces driver errors but cannot recover.")
        downside("Redundant when the profile pins a single database (the common case for app-scoped profiles). The `*(default)*` marker is the only useful info, and `db_list_profiles` already shows the same field.")
        downside("Hard error on SQLite/Generic profiles instead of gracefully returning the single profile-bound database. Forces the LLM to encode a 'check dbType before calling' rule that other db_* tools don't impose.")
        downside("System-database filtering (`pg_toast`, `information_schema`, `mysql.*`, etc.) is hand-rolled in `DatabaseDiscovery.filterSystemDatabases` — a new engine or a new system DB convention will silently leak through until the allowlist is updated.")
        downside("Connection cost: opens a JDBC connection just to run one discovery query. Fine for occasional use but expensive if the LLM re-calls every turn (see llmMistake about caching).")
    }

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
