package com.workflow.orchestrator.agent.tools.database

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [DatabaseDiscovery] — pure helpers for the test-connection-and-discover
 * flow used by [com.workflow.orchestrator.agent.settings.DatabaseProfileDialog].
 * No JDBC, no IntelliJ APIs.
 */
internal class DatabaseDiscoveryTest {

    @Nested
    inner class BootstrapUrl {

        @Test
        fun `postgres bootstraps via the postgres database`() {
            val url = DatabaseDiscovery.bootstrapUrl(DbType.POSTGRESQL, "db.example.com", 5432)
            assertEquals("jdbc:postgresql://db.example.com:5432/postgres", url)
        }

        @Test
        fun `mysql bootstraps with bare server url`() {
            val url = DatabaseDiscovery.bootstrapUrl(DbType.MYSQL, "db.example.com", 3306)
            assertEquals("jdbc:mysql://db.example.com:3306/", url)
        }

        @Test
        fun `mssql bootstraps with no databaseName parameter`() {
            val url = DatabaseDiscovery.bootstrapUrl(DbType.MSSQL, "db.example.com", 1433)
            assertEquals("jdbc:sqlserver://db.example.com:1433", url)
        }

        @Test
        fun `sqlite returns null because raw url is used directly`() {
            val url = DatabaseDiscovery.bootstrapUrl(DbType.SQLITE, "ignored", 0)
            assertNull(url)
        }

        @Test
        fun `generic returns null because raw url is used directly`() {
            val url = DatabaseDiscovery.bootstrapUrl(DbType.GENERIC, "ignored", 0)
            assertNull(url)
        }
    }

    @Nested
    inner class DiscoveryQuery {

        @Test
        fun `postgres discovery filters templates and orders by name`() {
            val sql = DatabaseDiscovery.discoveryQuery(DbType.POSTGRESQL)
            assertEquals(
                "SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname",
                sql
            )
        }

        @Test
        fun `mysql discovery uses SHOW DATABASES`() {
            val sql = DatabaseDiscovery.discoveryQuery(DbType.MYSQL)
            assertEquals("SHOW DATABASES", sql)
        }

        @Test
        fun `mssql discovery filters system databases via database_id`() {
            val sql = DatabaseDiscovery.discoveryQuery(DbType.MSSQL)
            assertEquals(
                "SELECT name FROM sys.databases WHERE database_id > 4 ORDER BY name",
                sql
            )
        }

        @Test
        fun `sqlite returns null because no discovery is run`() {
            assertNull(DatabaseDiscovery.discoveryQuery(DbType.SQLITE))
        }

        @Test
        fun `generic returns null because no discovery is run`() {
            assertNull(DatabaseDiscovery.discoveryQuery(DbType.GENERIC))
        }
    }
}
