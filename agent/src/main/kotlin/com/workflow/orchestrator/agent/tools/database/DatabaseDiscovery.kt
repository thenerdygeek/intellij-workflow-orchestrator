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
        DbType.MSSQL -> "jdbc:sqlserver://$host:$port"
        DbType.SQLITE, DbType.GENERIC -> null
    }
}
