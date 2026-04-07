package com.workflow.orchestrator.agent.tools.database

import kotlinx.serialization.Serializable

/**
 * A named database connection profile for the AI agent's read-only query tools.
 *
 * **Profiles are server-level**, not database-level. A single profile can be used to
 * query any database on the server (e.g. one Postgres profile can list and query all
 * databases on a Postgres cluster), mirroring the way DBeaver and other JDBC clients
 * work — they connect to one default database and reach the others via per-query
 * connection switching.
 *
 * For PostgreSQL / MySQL / SQL Server, fill [host], [port], and [defaultDatabase]
 * (the default database is what the connection lands in when no `database` parameter
 * is supplied to a tool call). For SQLite and Generic JDBC, fill [jdbcUrl] directly
 * since those engines have no separable host/port concept.
 *
 * Passwords are NOT stored here — they live in PasswordSafe under the key
 * "WorkflowOrchestrator.DB.{id}".
 *
 * **Backward compatibility:** profiles persisted before the structured-fields refactor
 * have only [jdbcUrl] populated; [host] / [port] / [defaultDatabase] will be empty.
 * On read, [parsedUrlParts] is used to backfill the structured view in memory.
 */
@Serializable
data class DatabaseProfile(
    val id: String,                    // Slug used as PasswordSafe key, e.g. "local", "qa"
    val displayName: String,           // Human-readable, shown in agent tool output
    val dbType: DbType,
    val username: String,
    // Server-level fields (PostgreSQL / MySQL / SQL Server)
    val host: String = "",
    val port: Int = 0,
    val defaultDatabase: String = "",
    // SQLite / Generic JDBC: raw URL directly. Also used as legacy storage for
    // pre-refactor profiles, which are auto-parsed back into host/port/defaultDatabase
    // on read by [resolvedHost] / [resolvedPort] / [resolvedDefaultDatabase].
    val jdbcUrl: String = "",
) {
    /** True if this profile uses the structured (host/port/database) connection model. */
    val isServerProfile: Boolean
        get() = dbType != DbType.SQLITE && dbType != DbType.GENERIC

    /**
     * Parse [jdbcUrl] back into structured fields, or null if [jdbcUrl] is empty
     * or this profile already has [host] populated (no migration needed).
     */
    private val parsedUrlParts: JdbcUrlBuilder.UrlParts? by lazy {
        if (host.isNotBlank() || jdbcUrl.isBlank()) null
        else JdbcUrlBuilder.parse(dbType, jdbcUrl)
    }

    /** Effective host: structured field if set, else parsed from legacy [jdbcUrl]. */
    val resolvedHost: String
        get() = host.ifBlank { parsedUrlParts?.host ?: "" }

    /** Effective port: structured field if set, else parsed from legacy [jdbcUrl], else engine default. */
    val resolvedPort: Int
        get() = if (port > 0) port else parsedUrlParts?.port ?: JdbcUrlBuilder.defaultPort(dbType)

    /** Effective default database: structured field if set, else parsed from legacy [jdbcUrl]. */
    val resolvedDefaultDatabase: String
        get() = defaultDatabase.ifBlank { parsedUrlParts?.database ?: "" }

    /**
     * Returns the JDBC URL to use for connecting to [database] on this profile's server.
     * If [database] is null or blank, uses [resolvedDefaultDatabase].
     *
     * For SQLite and Generic profiles, [database] is ignored and [jdbcUrl] is returned
     * unchanged — those engines have no per-query database switching.
     */
    fun jdbcUrlFor(database: String? = null): String {
        if (!isServerProfile) return jdbcUrl
        // For legacy profiles where the user only filled jdbcUrl and the parser couldn't
        // crack it (custom schemes, unusual params), fall back to the raw URL when no
        // database override is requested.
        if (database.isNullOrBlank() && host.isBlank() && parsedUrlParts == null && jdbcUrl.isNotBlank()) {
            return jdbcUrl
        }
        val effectiveDatabase = database?.takeIf { it.isNotBlank() } ?: resolvedDefaultDatabase
        return JdbcUrlBuilder.build(dbType, resolvedHost, resolvedPort, effectiveDatabase)
    }
}

enum class DbType(val displayName: String, val driverClass: String) {
    POSTGRESQL("PostgreSQL",  "org.postgresql.Driver"),
    MYSQL("MySQL",            "com.mysql.cj.jdbc.Driver"),
    SQLITE("SQLite",          "org.sqlite.JDBC"),
    MSSQL("SQL Server",       "com.microsoft.sqlserver.jdbc.SQLServerDriver"),
    GENERIC("Generic JDBC",   ""),  // user-supplied driver via classpath
}
