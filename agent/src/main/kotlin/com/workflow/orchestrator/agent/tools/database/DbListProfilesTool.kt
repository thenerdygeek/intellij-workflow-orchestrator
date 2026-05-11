package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.database.DatabaseSettings
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
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

    override fun documentation(): ToolDocumentation = toolDoc("db_list_profiles") {
        summary {
            technical(
                "Pure config read — enumerates all `DatabaseProfile` entries stored in `DatabaseSettings` " +
                    "(Settings → Tools → Workflow Orchestrator → Agent → Database Profiles) plus any IDE-managed " +
                    "data sources discovered via `IdeDataSourceResolver.discover()`. Zero network I/O — no JDBC " +
                    "connection is opened. Returns profile id, display name, DB engine type, host/port/default-DB " +
                    "(server profiles) or JDBC URL (SQLite/Generic), username, and an _(IDE)_ tag for IDE-managed sources."
            )
            plain(
                "Like reading your saved database bookmarks — it lists every configured DB connection by name " +
                    "so the agent knows what's available before it tries to connect to anything. No network is " +
                    "involved; it just reads settings. Think of it as opening the connection-manager sidebar in " +
                    "DBeaver to see what servers you've already set up."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without db_list_profiles, the LLM has no way to discover which database profiles exist and must " +
                "guess profile ids when calling db_query, db_schema, db_list_databases, db_explain, or db_stats. " +
                "Any guess that doesn't match an exact profile id returns a 'not found' error and a 'call " +
                "db_list_profiles first' hint — so the agent is forced to call this tool anyway after the failed " +
                "attempt, wasting a round-trip. This tool is the mandatory gateway for the entire db_* family."
        )
        llmMistake(
            "Confuses profiles with databases. A profile is a *configured server connection* (hostname, port, " +
                "credentials). A database is a *runtime namespace on that server* (e.g. `myapp` on a Postgres " +
                "cluster). Batch 12 audit explicitly called this out: after db_list_profiles returns a profile " +
                "named 'local', the LLM sometimes treats 'local' as a database name and passes it to `database=` " +
                "in db_query instead of to `profile=`. The two parameters are distinct; `profile` picks the " +
                "server connection, `database` selects which DB on that server."
        )
        llmMistake(
            "Skips db_list_profiles entirely and hard-codes a profile id like 'local' or 'qa' from prior " +
                "conversation. Profile ids are user-defined and vary per developer — always call " +
                "db_list_profiles at the start of any DB investigation session to confirm the actual ids."
        )
        llmMistake(
            "Sees an _(IDE)_ profile in the listing and tries to use it with db_query. IDE-managed data " +
                "sources appear in the listing but their credentials are stored in IntelliJ's encrypted store " +
                "and are not reachable by the agent. db_query returns a clear error for IDE profiles; the " +
                "LLM should check the source tag and use a manual profile for agent-driven queries."
        )
        llmMistake(
            "Re-calls db_list_profiles on every turn instead of caching the result for the session. " +
                "The profile list is static (changes only when a user edits Settings); one call per session " +
                "is sufficient. Unlike db_list_databases, there is no connection cost here, but the extra " +
                "tool call still consumes tokens and loop iterations."
        )
        verdict {
            keep(
                "The mandatory entry point for the entire db_* family. Every other database tool (db_query, " +
                    "db_schema, db_list_databases, db_explain, db_stats) requires a valid profile id as its " +
                    "first parameter. Without db_list_profiles the LLM cannot reliably discover those ids — " +
                    "it must either guess (causing 'not found' errors) or read raw Settings XML (fragile). " +
                    "The tool is pure config read (zero network, zero blast radius) and its description explicitly " +
                    "teaches the two-step workflow: call this first, then use the listed id in subsequent calls.",
                VerdictSeverity.STRONG,
            )
        }
        downside(
            "Relies entirely on what the user has configured in Settings. If no profiles are configured, " +
                "the output is an actionable empty-state message, but the agent cannot proceed with any db_* " +
                "tool until the user adds at least one profile manually."
        )
        downside(
            "IDE-managed data sources (IntelliJ Database tool window) are surfaced in the listing but are " +
                "not usable for agent-driven queries. The _(IDE)_ tag signals this, but the listing can be " +
                "misleading: a user who has many IDE sources but no manual profiles will see a full list yet " +
                "find that every db_query call fails."
        )
        downside(
            "Profile credentials (passwords) are never shown — only username and connection coordinates. " +
                "This is intentional for security, but it means the listing cannot help the agent diagnose " +
                "authentication failures. A wrong password shows up only when db_query fails with a JDBC auth error."
        )
        downside(
            "No pagination or filtering — all configured profiles are returned in a single response. In " +
                "environments with many profiles (dozens of environments × engines) the output can be long, " +
                "consuming non-trivial context tokens."
        )
        related("db_query", Relationship.COMPLEMENT, "Call db_list_profiles first to get a valid profile id, then pass it to db_query(profile=...) to run SELECT queries.")
        related("db_schema", Relationship.COMPLEMENT, "Use the profile id from db_list_profiles with db_schema to inspect table/column structure before writing queries.")
        related("db_list_databases", Relationship.COMPLEMENT, "For server-type profiles (Postgres/MySQL/SQL Server), follow up with db_list_databases(profile=...) to discover all databases on that server. Distinct concern: profiles = config, databases = runtime discovery.")
        related("db_explain", Relationship.SEE_ALSO, "Also takes a profile id as its first parameter; use db_list_profiles to resolve the id before calling db_explain on a slow query.")
        related("db_stats", Relationship.SEE_ALSO, "Also takes a profile id; db_list_profiles is the prerequisite for any db_stats call too.")
        observation(
            "db_list_profiles and db_list_databases are intentionally NOT merged (Batch 12 ruling). " +
                "Profiles are pure config — they list server connections the user defined in Settings and " +
                "IDE data sources; no network I/O, no JDBC connection. Databases are runtime — they are " +
                "discovered by querying the server. Merging them would force db_list_profiles to open a " +
                "connection (breaking its pure-config-read guarantee) or would make db_list_databases " +
                "confusingly return config data instead of live server state."
        )
    }

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
