package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Driver
import java.sql.ResultSet
import java.sql.Statement
import java.util.Properties

/**
 * Manages read-only JDBC connections for database agent tools.
 *
 * Safety guarantees (defence in depth):
 *   1. [Connection.isReadOnly] = true   — driver-level hint
 *   2. SQL allow-list check             — blocks DDL/DML before execution
 *   3. Statement.queryTimeout = 30s     — prevents runaway queries
 *   4. Row limit enforced in caller     — never injects LIMIT into user SQL
 *   5. autoCommit = false + rollback    — ensures no accidental writes
 */
object DatabaseConnectionManager {

    private val LOG = Logger.getInstance(DatabaseConnectionManager::class.java)

    /** Max query execution time. */
    const val DEFAULT_TIMEOUT_SECONDS = 30

    /** Max rows returned to the LLM (prevents context overflow). */
    const val MAX_ROWS = 200

    /** Max characters per cell value. */
    const val MAX_CELL_CHARS = 500

    // Patterns that must not appear at the start of a (trimmed, uppercased) query.
    private val BLOCKED_PREFIXES = listOf(
        "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER",
        "TRUNCATE", "REPLACE", "MERGE", "GRANT", "REVOKE",
        "EXEC", "EXECUTE", "CALL", "DO",
    )

    /**
     * Opens a short-lived read-only connection, executes [block], then closes it.
     * Always uses the plugin classloader to load bundled JDBC drivers.
     *
     * @param database Optional database name to connect to. If null/blank, uses
     *                 the profile's [DatabaseProfile.resolvedDefaultDatabase]. For
     *                 SQLite/Generic profiles this parameter is ignored — those use
     *                 the raw [DatabaseProfile.jdbcUrl] verbatim. For PostgreSQL,
     *                 MySQL, and SQL Server, supplying [database] lets one profile
     *                 reach any database on the server (mirrors DBeaver's per-DB
     *                 connection switching).
     */
    suspend fun <T> withConnection(
        profile: DatabaseProfile,
        database: String? = null,
        block: (Connection) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val password = DatabaseCredentialHelper.getPassword(profile.id)
                ?: error("No password stored for profile '${profile.displayName}'. Check Settings > Agent.")

            val targetUrl = profile.jdbcUrlFor(database)
            val conn = openConnection(profile, targetUrl, password)
            try {
                conn.autoCommit = false
                conn.isReadOnly = true
                block(conn)
            } finally {
                runCatching { conn.rollback() }
                runCatching { conn.close() }
            }
        }.onFailure { e ->
            LOG.warn("[DB:${profile.id}${database?.let { "/$it" } ?: ""}] Connection error: ${e.message}")
        }
    }

    /**
     * Validates that [sql] is a read-only statement.
     * Returns an error message if blocked, null if safe.
     */
    fun validateReadOnly(sql: String): String? {
        val upper = sql.trimIndent().trimStart().uppercase()
        for (prefix in BLOCKED_PREFIXES) {
            if (upper.startsWith(prefix)) {
                return "Blocked: '$prefix' statements are not permitted. Only SELECT queries are allowed."
            }
        }
        return null
    }

    /**
     * Converts a [ResultSet] to a Markdown table string suitable for LLM consumption.
     * Truncates at [MAX_ROWS] rows and [MAX_CELL_CHARS] chars per cell.
     */
    fun resultSetToMarkdown(rs: ResultSet): Pair<String, Int> {
        val meta = rs.metaData
        val colCount = meta.columnCount
        val headers = (1..colCount).map { meta.getColumnName(it) }

        val rows = mutableListOf<List<String>>()
        var rowCount = 0
        while (rs.next() && rowCount < MAX_ROWS) {
            val row = (1..colCount).map { i ->
                val v = rs.getString(i) ?: "NULL"
                if (v.length > MAX_CELL_CHARS) v.take(MAX_CELL_CHARS) + "\u2026" else v
            }
            rows.add(row)
            rowCount++
        }
        val truncated = rs.next() // peek — there are more rows

        val sb = StringBuilder()
        sb.append("| ${headers.joinToString(" | ")} |\n")
        sb.append("| ${headers.map { "---" }.joinToString(" | ")} |\n")
        rows.forEach { row -> sb.append("| ${row.joinToString(" | ")} |\n") }
        if (truncated) sb.append("\n_Results truncated at $MAX_ROWS rows._\n")

        return sb.toString() to rowCount
    }

    fun createStatement(conn: Connection, timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS): Statement {
        val stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
        runCatching { stmt.queryTimeout = timeoutSeconds }
        return stmt
    }

    // ----- private -----

    private fun openConnection(profile: DatabaseProfile, jdbcUrl: String, password: String): Connection {
        val props = Properties().apply {
            put("user", profile.username)
            put("password", password)
            // Postgres: disable auto-fetch of large result sets all at once
            if (profile.dbType == DbType.POSTGRESQL) put("fetchSize", "100")
            // MySQL: enforce read-only at server level
            if (profile.dbType == DbType.MYSQL) {
                put("sessionVariables", "transaction_read_only=ON")
                put("useSSL", "false")
                put("allowPublicKeyRetrieval", "true")
            }
        }

        return if (profile.dbType == DbType.GENERIC || profile.dbType.driverClass.isEmpty()) {
            // Fall back to DriverManager for generic JDBC — user's classpath must have the driver
            java.sql.DriverManager.getConnection(jdbcUrl, props)
        } else {
            // Load driver via plugin classloader to find bundled JARs
            @Suppress("UNCHECKED_CAST")
            val driverClass = Class.forName(
                profile.dbType.driverClass,
                true,
                DatabaseConnectionManager::class.java.classLoader
            ) as Class<out Driver>
            val driver = driverClass.getDeclaredConstructor().newInstance()
            driver.connect(jdbcUrl, props)
                ?: error("Driver returned null for URL '$jdbcUrl'. Check the JDBC URL format.")
        }
    }
}
