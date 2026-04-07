package com.workflow.orchestrator.agent.tools.database

/**
 * Pure helpers for the test-connection-and-discover flow used by
 * [com.workflow.orchestrator.agent.settings.DatabaseProfileDialog].
 *
 * No JDBC, no IntelliJ APIs — every function is fully unit-testable
 * without a database server or a Swing fixture.
 *
 * The dialog calls [DatabaseConnectionManager.testConnectionAndDiscover],
 * which in turn uses these helpers to choose the bootstrap URL, the
 * discovery SQL, and the post-query system-database filter.
 */
internal object DatabaseDiscovery {

    /**
     * Returns the JDBC URL to bootstrap the test connection for [dbType].
     *
     * - **PostgreSQL** connects via the conventional `postgres` maintenance
     *   database (the same approach DBeaver uses by default).
     * - **MySQL** and **SQL Server** support bare-server URLs — no database
     *   in the URL — and we exploit that to avoid forcing the user to know
     *   any database name up-front.
     * - **SQLite** and **Generic** return `null` because their raw JDBC URL
     *   is supplied directly by the user; there is no separate bootstrap.
     */
    fun bootstrapUrl(dbType: DbType, host: String, port: Int): String? = when (dbType) {
        DbType.POSTGRESQL -> "jdbc:postgresql://$host:$port/postgres"
        DbType.MYSQL -> "jdbc:mysql://$host:$port/"
        // No `;databaseName=...` parameter — the SQL Server JDBC driver accepts a
        // bare server URL and lands the connection in `master`. This is intentionally
        // asymmetric with JdbcUrlBuilder.build (which always emits a databaseName);
        // the bootstrap URL is for discovery only and never round-trips through
        // JdbcUrlBuilder.parseMssql, which would return null for a URL with no `;`.
        DbType.MSSQL -> "jdbc:sqlserver://$host:$port"
        DbType.SQLITE, DbType.GENERIC -> null
    }

    /**
     * Returns the SQL to list user-visible databases on the bootstrap connection
     * for [dbType], or `null` if discovery is not applicable (single-database
     * engines and raw-URL profiles).
     *
     * The PostgreSQL and SQL Server queries already filter system catalogs at
     * the SQL level. MySQL's `SHOW DATABASES` returns everything, so callers
     * should pipe its results through [filterSystemDatabases] (added in a
     * later task) to drop `mysql`, `sys`, `information_schema`, and
     * `performance_schema`.
     */
    fun discoveryQuery(dbType: DbType): String? = when (dbType) {
        DbType.POSTGRESQL -> "SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname"
        DbType.MYSQL -> "SHOW DATABASES"
        DbType.MSSQL -> "SELECT name FROM sys.databases WHERE database_id > 4 ORDER BY name"
        DbType.SQLITE, DbType.GENERIC -> null
    }
}
