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
 * Reads database schema metadata (tables, columns, types, PKs, FKs) via JDBC [DatabaseMetaData].
 * No SQL required — metadata is read from the driver without executing any queries.
 */
class DbSchemaTool : AgentTool {
    override val name = "db_schema"
    override val description = """
        Inspect the schema of a database: list tables, columns, data types, nullable flags,
        primary keys, and foreign key relationships. Uses JDBC metadata — no SQL needed.
        Call this before db_query to understand the structure before writing queries.

        Note on terminology: `database` selects which database on the profile's server to
        connect to (e.g. `mydb` vs `analytics` on the same Postgres cluster). `schema` is
        the namespace WITHIN a database (e.g. `public` in Postgres). They're different.

        Examples:
          db_schema(profile="qa")                                — tables in profile's default DB
          db_schema(profile="qa", database="orders")             — tables in the 'orders' DB
          db_schema(profile="qa", database="orders", table="line_items")  — describe a table
          db_schema(profile="qa", schema="reporting")            — tables in a Postgres schema
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "profile" to ParameterProperty(
                type = "string",
                description = "Profile ID (from db_list_profiles)"
            ),
            "database" to ParameterProperty(
                type = "string",
                description = "Optional database name on the profile's server. Defaults to the " +
                    "profile's default database. Use db_list_databases to see what's available. " +
                    "Ignored for SQLite/Generic profiles."
            ),
            "schema" to ParameterProperty(
                type = "string",
                description = "Database schema (namespace) name. Different from `database` — this " +
                    "is the inner namespace, e.g. 'public' in Postgres. Default: 'public' for " +
                    "PostgreSQL, null = all schemas."
            ),
            "table" to ParameterProperty(
                type = "string",
                description = "If specified, describe only this table (columns, types, PKs, FKs)"
            ),
        ),
        required = listOf("profile")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val profileId = params["profile"]?.jsonPrimitive?.content?.trim()
            ?: return error("'profile' parameter is required.")
        val database = params["database"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val schemaFilter = params["schema"]?.jsonPrimitive?.content?.trim()
        val tableFilter = params["table"]?.jsonPrimitive?.content?.trim()

        val profile = try {
            DatabaseSettings.getInstance(project).getProfile(profileId)
        } catch (_: Exception) {
            return error("DatabaseSettings service not available.")
        } ?: return error("Profile '$profileId' not found. Call db_list_profiles first.")

        val targetLabel = database?.let { "${profile.displayName} / $it" } ?: profile.displayName

        val result = DatabaseConnectionManager.withConnection(profile, database) { conn ->
            val meta = conn.metaData
            val sb = StringBuilder()
            sb.append("Schema for **$targetLabel** (${profile.dbType.displayName})\n\n")

            if (tableFilter != null) {
                // Describe a single table in detail
                val effectiveSchema = schemaFilter ?: defaultSchema(profile)
                sb.append(describeTable(meta, effectiveSchema, tableFilter))
            } else {
                // List all tables in the schema
                val effectiveSchema = schemaFilter ?: defaultSchema(profile)
                val tables = mutableListOf<Pair<String, String>>() // name to type
                meta.getTables(null, effectiveSchema, "%", arrayOf("TABLE", "VIEW")).use { rs ->
                    while (rs.next()) {
                        tables.add(rs.getString("TABLE_NAME") to rs.getString("TABLE_TYPE"))
                    }
                }

                if (tables.isEmpty()) {
                    sb.append("No tables found in schema '${effectiveSchema ?: "default"}'.\n")
                } else {
                    sb.append("**Tables** (${tables.size}):\n\n")
                    tables.forEach { (name, type) ->
                        sb.append("- `$name` ($type)\n")
                    }
                    sb.append(
                        "\nUse `db_schema(profile=\"$profileId\", table=\"<name>\")` " +
                            "to describe a specific table."
                    )
                }
            }
            sb.toString()
        }

        val summaryProfileLabel = "${profile.id}${database?.let { "/$it" } ?: ""}"

        return result.fold(
            onSuccess = { content ->
                ToolResult(
                    content = content,
                    summary = "db_schema on '$summaryProfileLabel'" +
                        (if (tableFilter != null) " table '$tableFilter'" else ""),
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            },
            onFailure = { e ->
                val hint = DatabaseConnectionManager.connectionErrorHint(e, profile.dbType)
                error("Schema inspection failed on '$summaryProfileLabel': ${e.message}$hint")
            }
        )
    }

    // ----- private helpers -----

    private fun defaultSchema(profile: com.workflow.orchestrator.agent.tools.database.DatabaseProfile): String? {
        return when (profile.dbType) {
            com.workflow.orchestrator.agent.tools.database.DbType.POSTGRESQL -> "public"
            com.workflow.orchestrator.agent.tools.database.DbType.MYSQL -> null  // use catalog instead
            else -> null
        }
    }

    private fun describeTable(
        meta: java.sql.DatabaseMetaData,
        schema: String?,
        table: String
    ): String {
        val sb = StringBuilder()
        sb.append("### `$table`\n\n")

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

        return sb.toString()
    }

    private fun error(msg: String) = ToolResult(
        content = "Error: $msg",
        summary = "db_schema error: $msg",
        tokenEstimate = TokenEstimator.estimate(msg),
        isError = true
    )
}
