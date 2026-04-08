package com.workflow.orchestrator.agent.tools.database

/**
 * Per-engine JDBC URL assembly and parsing.
 *
 * Database profiles are stored as **structured fields** (host / port / database)
 * for the major server engines (PostgreSQL, MySQL, SQL Server). This object
 * builds an engine-correct JDBC URL from those fields, and can parse a raw
 * JDBC URL back into structured fields for backward compatibility with the
 * old single-`jdbcUrl`-field profile format.
 *
 * SQLite and Generic JDBC types are deliberately NOT supported here — they
 * use the raw `DatabaseProfile.jdbcUrl` field directly.
 */
object JdbcUrlBuilder {

    /**
     * Parsed components of a JDBC URL.
     */
    data class UrlParts(
        val host: String,
        val port: Int,
        val database: String,
    )

    /**
     * Returns the standard default port for [dbType], or 0 if the engine
     * has no concept of a port (SQLite, Generic).
     */
    fun defaultPort(dbType: DbType): Int = when (dbType) {
        DbType.POSTGRESQL -> 5432
        DbType.MYSQL -> 3306
        DbType.MSSQL -> 1433
        DbType.SQLITE, DbType.GENERIC -> 0
    }

    /**
     * Returns the engine's default "connect-to" database — the system
     * catalog used when no specific database is requested.
     */
    private fun defaultDatabase(dbType: DbType): String = when (dbType) {
        DbType.POSTGRESQL -> "postgres"
        DbType.MYSQL -> "mysql"
        DbType.MSSQL -> "master"
        DbType.SQLITE, DbType.GENERIC -> ""
    }

    /**
     * Builds a JDBC URL for [dbType] connecting to [host]:[port]/[database].
     *
     * - If [port] is 0, the engine's default port is used.
     * - If [database] is blank, the engine's default catalog is used.
     * - SQLite and Generic types throw — use the raw URL directly for those.
     */
    fun build(dbType: DbType, host: String, port: Int, database: String): String {
        val effectivePort = if (port > 0) port else defaultPort(dbType)
        val effectiveDb = database.ifBlank { defaultDatabase(dbType) }
        return when (dbType) {
            DbType.POSTGRESQL -> "jdbc:postgresql://$host:$effectivePort/$effectiveDb"
            DbType.MYSQL -> "jdbc:mysql://$host:$effectivePort/$effectiveDb"
            DbType.MSSQL -> "jdbc:sqlserver://$host:$effectivePort;databaseName=$effectiveDb"
            DbType.SQLITE, DbType.GENERIC ->
                error("JdbcUrlBuilder.build does not support ${dbType.displayName}; use the raw jdbcUrl field.")
        }
    }

    /**
     * Parses a raw JDBC URL into structured [UrlParts] for [dbType].
     *
     * Returns `null` if the URL doesn't match the expected format for that
     * engine, or if [dbType] is SQLite/Generic (which have no parseable
     * structure). Used by the migration path that backfills host/port/database
     * for legacy profiles that only stored a `jdbcUrl`.
     */
    fun parse(dbType: DbType, url: String): UrlParts? = when (dbType) {
        DbType.POSTGRESQL -> parseStandard(url, "jdbc:postgresql://")
        DbType.MYSQL -> parseStandard(url, "jdbc:mysql://")
        DbType.MSSQL -> parseMssql(url)
        DbType.SQLITE, DbType.GENERIC -> null
    }

    /**
     * Parses URLs of shape `jdbc:<engine>://host:port/database[?params]`.
     */
    private fun parseStandard(url: String, expectedPrefix: String): UrlParts? {
        if (!url.startsWith(expectedPrefix)) return null
        val tail = url.removePrefix(expectedPrefix)
        // Strip query string (?ssl=true&user=foo)
        val withoutQuery = tail.substringBefore('?')
        // Split host:port/database — exactly one '/' separates the two
        val slashIdx = withoutQuery.indexOf('/')
        if (slashIdx < 0) return null
        val hostPort = withoutQuery.substring(0, slashIdx)
        val database = withoutQuery.substring(slashIdx + 1)
        if (database.isBlank()) return null
        // Split host:port
        val colonIdx = hostPort.indexOf(':')
        if (colonIdx < 0) return null
        val host = hostPort.substring(0, colonIdx)
        val portStr = hostPort.substring(colonIdx + 1)
        val port = portStr.toIntOrNull() ?: return null
        if (host.isBlank()) return null
        return UrlParts(host, port, database)
    }

    /**
     * Parses URLs of shape `jdbc:sqlserver://host:port;databaseName=database`.
     */
    private fun parseMssql(url: String): UrlParts? {
        val expectedPrefix = "jdbc:sqlserver://"
        if (!url.startsWith(expectedPrefix)) return null
        val tail = url.removePrefix(expectedPrefix)
        // SQL Server uses ;key=value;key=value style after the host:port
        val semicolonIdx = tail.indexOf(';')
        if (semicolonIdx < 0) return null
        val hostPort = tail.substring(0, semicolonIdx)
        val params = tail.substring(semicolonIdx + 1)
        // Split host:port
        val colonIdx = hostPort.indexOf(':')
        if (colonIdx < 0) return null
        val host = hostPort.substring(0, colonIdx)
        val portStr = hostPort.substring(colonIdx + 1)
        val port = portStr.toIntOrNull() ?: return null
        if (host.isBlank()) return null
        // Find databaseName parameter (case-insensitive key, preserve value)
        val dbName = params.split(';')
            .firstOrNull { it.startsWith("databaseName=", ignoreCase = true) }
            ?.substringAfter('=')
            ?: return null
        if (dbName.isBlank()) return null
        return UrlParts(host, port, dbName)
    }
}
