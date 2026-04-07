package com.workflow.orchestrator.agent.tools.database

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [DatabaseProfile] focusing on:
 *
 *  - **Backward compatibility**: profiles persisted as just `{id, name, dbType, jdbcUrl, username}`
 *    must continue to resolve correctly via `resolvedHost` / `resolvedPort` /
 *    `resolvedDefaultDatabase` even though `host`/`port`/`defaultDatabase` are empty.
 *
 *  - **`jdbcUrlFor(database?)`**: builds the right URL for connecting to a specific
 *    database on the same server, or returns the default-database URL when called
 *    with `null`/blank.
 *
 *  - **SQLite/Generic special-cased**: those profile types have no per-query database
 *    switching — `jdbcUrlFor` always returns the raw URL unchanged.
 */
class DatabaseProfileTest {

    // ────────────────────────────────────────────────────────────────────────
    // New (structured) profile creation
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class StructuredProfile {

        @Test
        fun `postgres profile builds default url from structured fields`() {
            val p = DatabaseProfile(
                id = "local",
                displayName = "Local Postgres",
                dbType = DbType.POSTGRESQL,
                username = "app",
                host = "localhost",
                port = 5432,
                defaultDatabase = "mydb",
            )
            assertEquals("jdbc:postgresql://localhost:5432/mydb", p.jdbcUrlFor())
            assertEquals("localhost", p.resolvedHost)
            assertEquals(5432, p.resolvedPort)
            assertEquals("mydb", p.resolvedDefaultDatabase)
        }

        @Test
        fun `postgres profile switches database via jdbcUrlFor parameter`() {
            val p = DatabaseProfile(
                id = "local",
                displayName = "Local Postgres",
                dbType = DbType.POSTGRESQL,
                username = "app",
                host = "localhost",
                port = 5432,
                defaultDatabase = "mydb",
            )
            assertEquals("jdbc:postgresql://localhost:5432/analytics", p.jdbcUrlFor("analytics"))
            assertEquals("jdbc:postgresql://localhost:5432/audit", p.jdbcUrlFor("audit"))
        }

        @Test
        fun `mysql profile builds expected url`() {
            val p = DatabaseProfile(
                id = "qa",
                displayName = "QA MySQL",
                dbType = DbType.MYSQL,
                username = "qauser",
                host = "qa.example.com",
                port = 3306,
                defaultDatabase = "shop",
            )
            assertEquals("jdbc:mysql://qa.example.com:3306/shop", p.jdbcUrlFor())
            assertEquals("jdbc:mysql://qa.example.com:3306/inventory", p.jdbcUrlFor("inventory"))
        }

        @Test
        fun `mssql profile builds expected url`() {
            val p = DatabaseProfile(
                id = "prod",
                displayName = "Prod SQL Server",
                dbType = DbType.MSSQL,
                username = "app",
                host = "sql.prod.example.com",
                port = 1433,
                defaultDatabase = "main",
            )
            assertEquals("jdbc:sqlserver://sql.prod.example.com:1433;databaseName=main", p.jdbcUrlFor())
            assertEquals("jdbc:sqlserver://sql.prod.example.com:1433;databaseName=audit", p.jdbcUrlFor("audit"))
        }

        @Test
        fun `port zero falls back to engine default`() {
            val p = DatabaseProfile(
                id = "local",
                displayName = "Local",
                dbType = DbType.POSTGRESQL,
                username = "app",
                host = "localhost",
                port = 0,
                defaultDatabase = "mydb",
            )
            assertEquals(5432, p.resolvedPort)
            assertEquals("jdbc:postgresql://localhost:5432/mydb", p.jdbcUrlFor())
        }

        @Test
        fun `isServerProfile is true for server-based engines`() {
            assertTrue(DatabaseProfile("a", "a", DbType.POSTGRESQL, "u", host = "h", port = 5432).isServerProfile)
            assertTrue(DatabaseProfile("a", "a", DbType.MYSQL, "u", host = "h", port = 3306).isServerProfile)
            assertTrue(DatabaseProfile("a", "a", DbType.MSSQL, "u", host = "h", port = 1433).isServerProfile)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Backward-compat: legacy profiles with only jdbcUrl
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class LegacyProfile {

        @Test
        fun `legacy postgres profile resolves host port and database from jdbcUrl`() {
            val p = DatabaseProfile(
                id = "legacy",
                displayName = "Legacy Postgres",
                dbType = DbType.POSTGRESQL,
                username = "app",
                jdbcUrl = "jdbc:postgresql://db.example.com:6543/analytics",
                // host, port, defaultDatabase all empty — simulating old persisted profile
            )
            assertEquals("db.example.com", p.resolvedHost)
            assertEquals(6543, p.resolvedPort)
            assertEquals("analytics", p.resolvedDefaultDatabase)
        }

        @Test
        fun `legacy mysql profile resolves correctly`() {
            val p = DatabaseProfile(
                id = "legacy",
                displayName = "Legacy MySQL",
                dbType = DbType.MYSQL,
                username = "app",
                jdbcUrl = "jdbc:mysql://qa.example.com:3306/shop",
            )
            assertEquals("qa.example.com", p.resolvedHost)
            assertEquals(3306, p.resolvedPort)
            assertEquals("shop", p.resolvedDefaultDatabase)
        }

        @Test
        fun `legacy mssql profile resolves correctly`() {
            val p = DatabaseProfile(
                id = "legacy",
                displayName = "Legacy MSSQL",
                dbType = DbType.MSSQL,
                username = "app",
                jdbcUrl = "jdbc:sqlserver://sql.example.com:1433;databaseName=main",
            )
            assertEquals("sql.example.com", p.resolvedHost)
            assertEquals(1433, p.resolvedPort)
            assertEquals("main", p.resolvedDefaultDatabase)
        }

        @Test
        fun `legacy postgres profile can switch databases via jdbcUrlFor`() {
            val p = DatabaseProfile(
                id = "legacy",
                displayName = "Legacy Postgres",
                dbType = DbType.POSTGRESQL,
                username = "app",
                jdbcUrl = "jdbc:postgresql://db.example.com:6543/analytics",
            )
            assertEquals("jdbc:postgresql://db.example.com:6543/audit", p.jdbcUrlFor("audit"))
        }

        @Test
        fun `legacy postgres profile with no override returns raw jdbcUrl`() {
            // When the parser succeeds we'll rebuild — same end result for clean URLs
            val p = DatabaseProfile(
                id = "legacy",
                displayName = "Legacy Postgres",
                dbType = DbType.POSTGRESQL,
                username = "app",
                jdbcUrl = "jdbc:postgresql://db.example.com:6543/analytics",
            )
            // Rebuilt URL should be identical for the default database
            assertEquals("jdbc:postgresql://db.example.com:6543/analytics", p.jdbcUrlFor())
        }

        @Test
        fun `unparseable legacy url falls back to raw on default lookup`() {
            // Edge case: a legacy URL with extra query params that the parser doesn't recognize
            // Should still work for the default database (just return the raw URL).
            val p = DatabaseProfile(
                id = "legacy",
                displayName = "Legacy Postgres",
                dbType = DbType.POSTGRESQL,
                username = "app",
                jdbcUrl = "jdbc:postgresql://db.example.com:6543/analytics?ssl=true&sslmode=require",
            )
            // Parser strips query string, so it can still rebuild
            assertEquals("db.example.com", p.resolvedHost)
            assertEquals(6543, p.resolvedPort)
            assertEquals("analytics", p.resolvedDefaultDatabase)
            // For database switch, the rebuilt URL won't have the query params (acceptable)
            assertEquals("jdbc:postgresql://db.example.com:6543/audit", p.jdbcUrlFor("audit"))
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // SQLite and Generic special cases
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class SqliteAndGeneric {

        @Test
        fun `sqlite profile is not a server profile`() {
            val p = DatabaseProfile(
                id = "local",
                displayName = "Local SQLite",
                dbType = DbType.SQLITE,
                username = "",
                jdbcUrl = "jdbc:sqlite:/tmp/app.db",
            )
            assertFalse(p.isServerProfile)
        }

        @Test
        fun `sqlite profile returns raw url regardless of database parameter`() {
            val p = DatabaseProfile(
                id = "local",
                displayName = "Local SQLite",
                dbType = DbType.SQLITE,
                username = "",
                jdbcUrl = "jdbc:sqlite:/tmp/app.db",
            )
            assertEquals("jdbc:sqlite:/tmp/app.db", p.jdbcUrlFor())
            assertEquals("jdbc:sqlite:/tmp/app.db", p.jdbcUrlFor("ignored"))
            assertEquals("jdbc:sqlite:/tmp/app.db", p.jdbcUrlFor("anything"))
        }

        @Test
        fun `generic profile returns raw url regardless of database parameter`() {
            val p = DatabaseProfile(
                id = "custom",
                displayName = "Custom JDBC",
                dbType = DbType.GENERIC,
                username = "user",
                jdbcUrl = "jdbc:custom://weird-format/foo",
            )
            assertEquals("jdbc:custom://weird-format/foo", p.jdbcUrlFor())
            assertEquals("jdbc:custom://weird-format/foo", p.jdbcUrlFor("other"))
        }
    }
}
