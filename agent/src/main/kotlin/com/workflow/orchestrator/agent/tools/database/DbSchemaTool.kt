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
import java.sql.DatabaseMetaData

/**
 * Hierarchically explores a database: list schemas → list tables → describe table.
 *
 * Behaviour depends on the parameters supplied:
 *   - profile only              → list all schemas in the database
 *   - profile + schema          → list all tables and views in that schema
 *   - profile + schema + table  → describe one table (columns, PKs, FKs, indexes)
 */
class DbSchemaTool : AgentTool {
    override val name = "db_schema"
    override val description = """
        Hierarchically explore a database. Behaviour depends on which parameters you supply:

          • profile only (no schema)         → list all schemas in the database
          • profile + schema (no table)      → list all tables and views in that schema
          • profile + schema + table         → describe one table: columns, data types,
                                               nullable flags, default values, primary key,
                                               foreign keys, and indexes

        Note: `database` selects which database on the server to connect to (e.g. `mydb`
        vs `analytics` on the same Postgres cluster). `schema` is the namespace WITHIN a
        database (e.g. `public`, `reporting`). They are different concepts.

        Examples:
          db_schema(profile="qa")                                   — list all schemas
          db_schema(profile="qa", schema="public")                  — list tables in public
          db_schema(profile="qa", schema="reporting")               — list tables in reporting
          db_schema(profile="qa", schema="public", table="orders")  — describe orders table
          db_schema(profile="qa", database="analytics", schema="public", table="events")
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "profile" to ParameterProperty(
                type = "string",
                description = "Profile ID (from db_list_profiles), e.g. 'local', 'qa'"
            ),
            "database" to ParameterProperty(
                type = "string",
                description = "Optional database name on the profile's server. Defaults to the " +
                    "profile's default database. Use db_list_databases to see what's available. " +
                    "Ignored for SQLite/Generic profiles."
            ),
            "schema" to ParameterProperty(
                type = "string",
                description = "Schema (namespace) within the database. " +
                    "If omitted, lists all schemas in the database. " +
                    "If provided without a table, lists all tables in this schema. " +
                    "Examples: 'public', 'reporting', 'billing'."
            ),
            "table" to ParameterProperty(
                type = "string",
                description = "Table to describe in detail. Requires schema to also be provided. " +
                    "Returns columns, data types, primary key, foreign keys, and indexes."
            ),
        ),
        required = listOf("profile")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override fun documentation(): ToolDocumentation = toolDoc("db_schema") {
        summary {
            technical("Hierarchically inspect a JDBC profile's structure: with profile only it lists schemas, with schema it lists tables/views, and with schema+table it returns columns, PKs, FKs, and grouped indexes via DatabaseMetaData.")
            plain("Like running `\\d` in psql or `DESCRIBE` in MySQL — drills from 'what databases live here?' down to 'what columns does this table have?' without forcing the agent to know each engine's catalog dialect.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without db_schema the LLM would `db_query` engine-specific catalog tables — MySQL `INFORMATION_SCHEMA.COLUMNS`, Postgres `pg_attribute` joined to `pg_class` and `pg_namespace`, SQL Server `sys.columns`, SQLite `PRAGMA table_info`. That requires the LLM to first detect the engine, then write four different SQL dialects, then re-implement PK/FK/index aggregation by hand. Net cost: ~3-4 extra tool calls per schema lookup and a high failure rate on less-common engines."
        )
        llmMistake("Calls `db_schema(profile=\"qa\", table=\"orders\")` without `schema` — the tool silently treats it as Level 1 (list schemas) because the table-describe path requires schema != null. The LLM then re-invokes with the right combo, wasting a turn.")
        llmMistake("Confuses `database` with `schema`. On Postgres, `database` selects the catalog (`mydb` vs `analytics` on the same cluster) while `schema` is the namespace within it (`public`, `reporting`). LLMs often pass the schema name as `database` and get back an empty schema list.")
        llmMistake("On MySQL, calls Level 1 expecting a schema list and gets back a redirection message ('MySQL uses databases as schemas — use db_list_databases'). MySQL has no separate schema concept, so the tool short-circuits.")
        llmMistake("Constructs a `db_query(\"SELECT col1, col2 FROM orders\")` from memory without first running `db_schema` — column names drift from the real schema and the query fails. The fix is to read schema first; the recurring miss suggests the system prompt needs a stronger 'always inspect before querying' nudge.")
        llmMistake("Drills straight to Level 3 (table describe) on a fresh profile without verifying the table exists in that schema — gets back a result with empty column rows (no error, just an empty markdown table) which the LLM then misreads as 'table has no columns'.")
        params {
            required("profile", "string") {
                llmSeesIt("Profile ID (from db_list_profiles), e.g. 'local', 'qa'")
                humanReadable("Which configured database connection to use — like picking a saved connection in DataGrip's 'Database' tool window. The agent gets these IDs by calling db_list_profiles first.")
                whenPresent("The profile is resolved through DbProfileResolver. Manual profiles connect; IDE-managed profiles return a friendly error pointing the user at Settings.")
                constraint("must match an id in PluginSettings.databaseProfiles or be the discoverable id of an IDE data source")
                example("local")
                example("qa")
                example("staging-readonly")
            }
            optional("database", "string") {
                llmSeesIt("Optional database name on the profile's server. Defaults to the profile's default database. Use db_list_databases to see what's available. Ignored for SQLite/Generic profiles.")
                humanReadable("On a multi-database server (e.g. one Postgres cluster hosting `mydb` AND `analytics`), this picks which one to inspect. SQLite has only one database per file, so it's irrelevant there.")
                whenPresent("DatabaseConnectionManager.withConnection routes the JDBC URL to the named database, overriding the profile's default.")
                whenAbsent("Connects to whatever database the profile's URL points at by default.")
                constraint("ignored for SQLite (single-file) and Generic JDBC profiles where the URL itself names the database")
                example("analytics")
                example("mydb")
            }
            optional("schema", "string") {
                llmSeesIt("Schema (namespace) within the database. If omitted, lists all schemas in the database. If provided without a table, lists all tables in this schema. Examples: 'public', 'reporting', 'billing'.")
                humanReadable("The namespace inside the database — like a folder grouping related tables. On Postgres `public` is the default; SQL Server uses `dbo`. MySQL has no schemas (use database= instead).")
                whenPresent("Switches behaviour from Level 1 (list schemas) to Level 2 (list tables in this schema) when `table` is null, or Level 3 (describe table) when `table` is also set.")
                whenAbsent("Tool runs Level 1 — lists user schemas via DatabaseMetaData.getSchemas(), filtering out system schemas (pg_catalog, information_schema, pg_toast, sys, dbo, etc.).")
                example("public")
                example("reporting")
                example("dbo")
            }
            optional("table", "string") {
                llmSeesIt("Table to describe in detail. Requires schema to also be provided. Returns columns, data types, primary key, foreign keys, and indexes.")
                humanReadable("The specific table you want a full breakdown of. Returns a markdown columns table plus a primary-key line, foreign-key list, and grouped indexes (composite indexes are rolled up under one entry).")
                whenPresent("Level 3: calls getColumns / getPrimaryKeys / getImportedKeys / getIndexInfo for the (schema, table) pair and renders markdown.")
                whenAbsent("If schema is set, falls back to Level 2 (list tables). If schema is also absent, Level 1.")
                constraint("requires `schema` to also be set — the tool does not guess the namespace; without schema, table is silently ignored")
                example("orders")
                example("user_events")
            }
        }
        verdict {
            keep(
                "Cross-engine schema introspection that would otherwise require the LLM to know four different catalog dialects (MySQL INFORMATION_SCHEMA, Postgres pg_*, SQL Server sys.*, SQLite PRAGMA). The hierarchical drill-down also works as scaffolding — Level 1 → 2 → 3 mirrors how a human DBA explores an unknown DB, so the LLM doesn't need to make up table names. Composite-index grouping is a real value-add over raw catalog rows.",
                VerdictSeverity.STRONG,
            )
        }
        related("db_list_profiles", Relationship.COMPLEMENT, "Call first to discover which profile IDs exist — db_schema's `profile` param is opaque without it.")
        related("db_list_databases", Relationship.COMPLEMENT, "Use to discover which `database=` values are valid on a multi-database server before drilling into schemas.")
        related("db_query", Relationship.COMPOSE_WITH, "Call db_schema first to learn column names and types, then craft db_query SELECTs that don't fail on typos.")
        related("db_explain", Relationship.SEE_ALSO, "Once you know the schema and have a query, db_explain shows whether the indexes db_schema lists are actually being used.")
        related("db_stats", Relationship.SEE_ALSO, "Schema tells you the structure; db_stats tells you the row counts and storage sizes for the same tables.")
        downside("Limited to whatever the JDBC driver exposes via DatabaseMetaData. Engine-specific features — generated columns, computed columns, check constraints, partitioning, table comments, GIN/GiST/BRIN index types, exclusion constraints, materialized views — are not surfaced.")
        downside("Level 3 (describe table) silently returns an empty columns table when (schema, table) doesn't exist. No 'table not found' error — the LLM may mistake an empty result for 'this table has no columns'.")
        downside("Level 1 system-schema filter is a hand-rolled allowlist (pg_catalog, information_schema, pg_toast, sys, dbo, guest, plus prefix matches for pg_temp_, pg_toast_temp_, db_). New Postgres extensions or custom audit schemas starting with 'db_' will be filtered out unintentionally.")
        downside("`NULLABLE` parsing reads the metadata column as a string and compares to \"1\" — the JDBC spec returns it as an int (DatabaseMetaData.columnNullable=1, columnNoNulls=0, columnNullableUnknown=2). Most drivers happen to stringify '1'/'0' the same way, but a non-conformant driver could mislabel every column as NOT NULL.")
        downside("Output for a wide table can be large — gets routed through `spillOrFormat` so the LLM sees a head/tail preview with the full markdown on disk. The LLM must follow the spillPath to read the full schema if it spilled.")
        downside("Foreign-key and index queries are wrapped in `runCatching {}` — drivers that throw on these calls (some Generic JDBC profiles) silently produce a partial result rather than reporting the failure.")
        flowchart("""
            flowchart TD
                A[LLM calls db_schema] --> B{profile resolves?}
                B -- IDE-managed --> X1[Return: configure manual profile]
                B -- not found --> X2[Return: not found, call db_list_profiles]
                B -- found --> C{schema set?}
                C -- no --> D[Level 1: list schemas]
                D --> D1{MySQL?}
                D1 -- yes --> D2[Redirect to db_list_databases]
                D1 -- no --> D3[getSchemas + filter system]
                C -- yes --> E{table set?}
                E -- no --> F[Level 2: getTables for schema]
                E -- yes --> G[Level 3: describeTable]
                G --> G1[getColumns]
                G --> G2[getPrimaryKeys]
                G --> G3[getImportedKeys]
                G --> G4[getIndexInfo grouped by name]
                G1 & G2 & G3 & G4 --> H[Render markdown]
                D3 & F & H --> I[spillOrFormat preview]
                I --> J[Return ToolResult]
        """)
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val profileId = params["profile"]?.jsonPrimitive?.content?.trim()
            ?: return error("'profile' parameter is required.")
        val database = params["database"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val schema = params["schema"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val table = params["table"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

        val profile = when (val lookup = lookupDbProfile(project, profileId)) {
            is DbProfileLookup.Found -> lookup.profile
            is DbProfileLookup.IdeManaged -> return ToolResult(
                content = "Profile '$profileId' (${lookup.displayName}) is an IDE-managed data source. " +
                    "IDE profile credentials are not available to the agent. " +
                    "To run queries, configure a manual profile in Settings → Tools → Workflow Orchestrator → Agent → Database Profiles.",
                summary = "db_schema: IDE profile credentials not available",
                tokenEstimate = 80,
                isError = true,
            )
            is DbProfileLookup.NotFound -> return error(
                "Profile '$profileId' not found. Call db_list_profiles first."
            )
        }

        val targetLabel = database?.let { "${profile.displayName} / $it" } ?: profile.displayName

        val result = DatabaseConnectionManager.withConnection(profile, database) { conn ->
            val meta = conn.metaData
            val sb = StringBuilder()

            when {
                schema != null && table != null -> {
                    // Level 3: describe a specific table in full detail
                    sb.append("Schema for **$targetLabel** (${profile.dbType.displayName})\n\n")
                    sb.append(describeTable(meta, schema, table, profileId, database))
                }
                schema != null -> {
                    // Level 2: list all tables in the given schema
                    sb.append("Schema for **$targetLabel** (${profile.dbType.displayName})\n\n")
                    sb.append(listTables(meta, schema, profileId, database))
                }
                else -> {
                    // Level 1: list all schemas in the database
                    sb.append("Schemas in **$targetLabel** (${profile.dbType.displayName})\n\n")
                    sb.append(listSchemas(meta, profile, profileId, database))
                }
            }

            sb.toString()
        }

        val summaryProfileLabel = "${profile.id}${database?.let { "/$it" } ?: ""}"
        val summaryDetail = when {
            schema != null && table != null -> "table '$schema.$table'"
            schema != null -> "schema '$schema' tables"
            else -> "schemas"
        }

        return result.fold(
            onSuccess = { raw ->
                val spilled = spillOrFormat(raw, project)
                ToolResult(
                    content = spilled.preview,
                    summary = "db_schema on '$summaryProfileLabel': $summaryDetail",
                    tokenEstimate = TokenEstimator.estimate(spilled.preview),
                    spillPath = spilled.spilledToFile,
                )
            },
            onFailure = { e ->
                val hint = DatabaseConnectionManager.connectionErrorHint(e, profile.dbType)
                error("Schema inspection failed on '$summaryProfileLabel': ${e.message}$hint")
            }
        )
    }

    // ----- Level 1: list schemas -----

    private fun listSchemas(
        meta: DatabaseMetaData,
        profile: DatabaseProfile,
        profileId: String,
        database: String?
    ): String {
        val sb = StringBuilder()

        // MySQL treats databases as schemas — direct user to db_list_databases instead
        if (profile.dbType == DbType.MYSQL) {
            return "MySQL uses databases as schemas. " +
                "Use `db_list_databases(profile=\"$profileId\")` to list databases, " +
                "then `db_schema(profile=\"$profileId\", schema=\"<dbname>\")` to list tables."
        }

        val schemas = mutableListOf<String>()
        runCatching {
            meta.schemas.use { rs ->
                while (rs.next()) {
                    val name = rs.getString("TABLE_SCHEM") ?: return@use
                    if (!isSystemSchema(name)) schemas.add(name)
                }
            }
        }

        if (schemas.isEmpty()) {
            sb.append("No user schemas found.\n")
        } else {
            sb.append("**Schemas** (${schemas.size}):\n\n")
            schemas.forEach { sb.append("- `$it`\n") }
        }

        val dbPart = database?.let { ", database=\"$it\"" } ?: ""
        sb.append(
            "\nUse `db_schema(profile=\"$profileId\"$dbPart, schema=\"<name>\")` " +
                "to list tables in a schema."
        )
        return sb.toString()
    }

    private fun isSystemSchema(name: String): Boolean {
        val lower = name.lowercase()
        return lower in setOf(
            "pg_catalog", "information_schema", "pg_toast",
            "sys", "guest", "dbo"
        ) || lower.startsWith("pg_temp_") ||
            lower.startsWith("pg_toast_temp_") ||
            lower.startsWith("db_")
    }

    // ----- Level 2: list tables -----

    private fun listTables(
        meta: DatabaseMetaData,
        schema: String,
        profileId: String,
        database: String?
    ): String {
        val sb = StringBuilder()
        val tables = mutableListOf<Pair<String, String>>() // name to type
        meta.getTables(null, schema, "%", arrayOf("TABLE", "VIEW")).use { rs ->
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME") to rs.getString("TABLE_TYPE"))
            }
        }

        if (tables.isEmpty()) {
            sb.append("No tables found in schema '$schema'.\n")
        } else {
            sb.append("**Tables in `$schema`** (${tables.size}):\n\n")
            tables.forEach { (name, type) ->
                sb.append("- `$name` ($type)\n")
            }
        }

        val dbPart = database?.let { ", database=\"$it\"" } ?: ""
        sb.append(
            "\nUse `db_schema(profile=\"$profileId\"$dbPart, schema=\"$schema\", table=\"<name>\")` " +
                "to describe a specific table."
        )
        return sb.toString()
    }

    // ----- Level 3: describe table -----

    private fun describeTable(
        meta: DatabaseMetaData,
        schema: String,
        table: String,
        profileId: String,
        database: String?
    ): String {
        val sb = StringBuilder()
        sb.append("### `$schema`.`$table`\n\n")

        // Columns
        val columnRows = mutableListOf<List<String>>()
        meta.getColumns(null, schema, table, "%").use { cols ->
            while (cols.next()) {
                columnRows.add(listOf(
                    cols.getString("COLUMN_NAME"),
                    cols.getString("TYPE_NAME"),
                    if (cols.getString("NULLABLE") == "1") "NULL" else "NOT NULL",
                    cols.getString("COLUMN_DEF") ?: ""
                ))
            }
        }

        sb.append("| Column | Type | Nullable | Default |\n")
        sb.append("| --- | --- | --- | --- |\n")
        columnRows.forEach { row -> sb.append("| ${row.joinToString(" | ")} |\n") }

        // Primary keys
        runCatching {
            val pkCols = mutableListOf<String>()
            meta.getPrimaryKeys(null, schema, table).use { pks ->
                while (pks.next()) pkCols.add(pks.getString("COLUMN_NAME"))
            }
            if (pkCols.isNotEmpty()) {
                sb.append("\n**Primary key:** ${pkCols.joinToString(", ")}\n")
            }
        }

        // Foreign keys
        runCatching {
            val fkLines = mutableListOf<String>()
            meta.getImportedKeys(null, schema, table).use { fks ->
                while (fks.next()) {
                    fkLines.add(
                        "`${fks.getString("FKCOLUMN_NAME")}` → " +
                            "`${fks.getString("PKTABLE_NAME")}.${fks.getString("PKCOLUMN_NAME")}`"
                    )
                }
            }
            if (fkLines.isNotEmpty()) {
                sb.append("\n**Foreign keys:**\n")
                fkLines.forEach { sb.append("- $it\n") }
            }
        }

        // Indexes — grouped by index name to handle composite indexes correctly
        runCatching {
            data class IndexInfo(val isUnique: Boolean, val columns: MutableList<String>)
            val indexes = LinkedHashMap<String, IndexInfo>()
            meta.getIndexInfo(null, schema, table, false, true).use { idx ->
                while (idx.next()) {
                    val indexName = idx.getString("INDEX_NAME") ?: continue
                    val columnName = idx.getString("COLUMN_NAME") ?: continue
                    indexes.getOrPut(indexName) {
                        IndexInfo(isUnique = !idx.getBoolean("NON_UNIQUE"), columns = mutableListOf())
                    }.columns.add(columnName)
                }
            }
            // Exclude the primary key index — already shown above
            val nonPkIndexes = indexes.entries.filter { it.key.uppercase() != "PRIMARY" }
            if (nonPkIndexes.isNotEmpty()) {
                sb.append("\n**Indexes:**\n")
                nonPkIndexes.forEach { (name, info) ->
                    val type = if (info.isUnique) "UNIQUE" else "INDEX"
                    sb.append("- `$name` ($type): ${info.columns.joinToString(", ") { "`$it`" }}\n")
                }
            }
        }

        return sb.toString()
    }

    private fun error(msg: String) = ToolResult(
        content = "Error: $msg",
        summary = "db_schema error: $msg",
        tokenEstimate = TokenEstimator.estimate(msg),
        isError = true
    )
}
