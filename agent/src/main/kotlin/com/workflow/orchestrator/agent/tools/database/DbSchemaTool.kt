package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
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

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val profileId = params["profile"]?.jsonPrimitive?.content?.trim()
            ?: return error("'profile' parameter is required.")
        val database = params["database"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val schema = params["schema"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val table = params["table"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

        val profile = try {
            DatabaseSettings.getInstance(project).getProfile(profileId)
        } catch (_: Exception) {
            return error("DatabaseSettings service not available.")
        } ?: return error("Profile '$profileId' not found. Call db_list_profiles first.")

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
            onSuccess = { content ->
                ToolResult(
                    content = content,
                    summary = "db_schema on '$summaryProfileLabel': $summaryDetail",
                    tokenEstimate = TokenEstimator.estimate(content)
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
