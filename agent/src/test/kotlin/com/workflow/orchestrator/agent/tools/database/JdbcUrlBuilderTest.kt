package com.workflow.orchestrator.agent.tools.database

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [JdbcUrlBuilder] — assembles per-engine JDBC URLs from
 * structured (host/port/database) profile fields, and parses existing
 * raw URLs into the structured fields for backward compatibility.
 *
 * Scenarios are written from the spec, NOT from the implementation:
 *
 *  1. Build a URL for each engine when given host/port/database
 *  2. Parse a raw URL back into host/port/database for each engine
 *  3. Round-trip: build then parse should preserve all fields
 *  4. Edge cases: missing port (engine default), missing database
 *  5. SQLite is special — URL is the file path, no host/port
 *  6. Generic JDBC — pass through, no parsing
 */
class JdbcUrlBuilderTest {

    // ────────────────────────────────────────────────────────────────────────
    // Build URL from structured fields
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class BuildUrl {

        @Test
        fun `postgres builds standard url`() {
            val url = JdbcUrlBuilder.build(DbType.POSTGRESQL, "localhost", 5432, "mydb")
            assertEquals("jdbc:postgresql://localhost:5432/mydb", url)
        }

        @Test
        fun `mysql builds standard url`() {
            val url = JdbcUrlBuilder.build(DbType.MYSQL, "localhost", 3306, "mydb")
            assertEquals("jdbc:mysql://localhost:3306/mydb", url)
        }

        @Test
        fun `mssql builds with semicolon database param`() {
            val url = JdbcUrlBuilder.build(DbType.MSSQL, "localhost", 1433, "mydb")
            assertEquals("jdbc:sqlserver://localhost:1433;databaseName=mydb", url)
        }

        @Test
        fun `postgres uses default port when port is zero`() {
            val url = JdbcUrlBuilder.build(DbType.POSTGRESQL, "localhost", 0, "mydb")
            assertEquals("jdbc:postgresql://localhost:5432/mydb", url)
        }

        @Test
        fun `mysql uses default port when port is zero`() {
            val url = JdbcUrlBuilder.build(DbType.MYSQL, "localhost", 0, "mydb")
            assertEquals("jdbc:mysql://localhost:3306/mydb", url)
        }

        @Test
        fun `mssql uses default port when port is zero`() {
            val url = JdbcUrlBuilder.build(DbType.MSSQL, "localhost", 0, "mydb")
            assertEquals("jdbc:sqlserver://localhost:1433;databaseName=mydb", url)
        }

        @Test
        fun `postgres without database connects to postgres default`() {
            val url = JdbcUrlBuilder.build(DbType.POSTGRESQL, "localhost", 5432, "")
            assertEquals("jdbc:postgresql://localhost:5432/postgres", url)
        }

        @Test
        fun `mysql without database connects to mysql default`() {
            val url = JdbcUrlBuilder.build(DbType.MYSQL, "localhost", 3306, "")
            assertEquals("jdbc:mysql://localhost:3306/mysql", url)
        }

        @Test
        fun `mssql without database connects to master default`() {
            val url = JdbcUrlBuilder.build(DbType.MSSQL, "localhost", 1433, "")
            assertEquals("jdbc:sqlserver://localhost:1433;databaseName=master", url)
        }

        @Test
        fun `non-default host is preserved`() {
            val url = JdbcUrlBuilder.build(DbType.POSTGRESQL, "db.prod.example.com", 6543, "analytics")
            assertEquals("jdbc:postgresql://db.prod.example.com:6543/analytics", url)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Parse URL into structured fields
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ParseUrl {

        @Test
        fun `parses standard postgres url`() {
            val parts = JdbcUrlBuilder.parse(DbType.POSTGRESQL, "jdbc:postgresql://localhost:5432/mydb")
            assertNotNull(parts)
            assertEquals("localhost", parts!!.host)
            assertEquals(5432, parts.port)
            assertEquals("mydb", parts.database)
        }

        @Test
        fun `parses standard mysql url`() {
            val parts = JdbcUrlBuilder.parse(DbType.MYSQL, "jdbc:mysql://localhost:3306/mydb")
            assertNotNull(parts)
            assertEquals("localhost", parts!!.host)
            assertEquals(3306, parts.port)
            assertEquals("mydb", parts.database)
        }

        @Test
        fun `parses mssql semicolon-style url`() {
            val parts = JdbcUrlBuilder.parse(DbType.MSSQL, "jdbc:sqlserver://localhost:1433;databaseName=mydb")
            assertNotNull(parts)
            assertEquals("localhost", parts!!.host)
            assertEquals(1433, parts.port)
            assertEquals("mydb", parts.database)
        }

        @Test
        fun `parses postgres url with non-default port`() {
            val parts = JdbcUrlBuilder.parse(DbType.POSTGRESQL, "jdbc:postgresql://db.example.com:6543/analytics")
            assertNotNull(parts)
            assertEquals("db.example.com", parts!!.host)
            assertEquals(6543, parts.port)
            assertEquals("analytics", parts.database)
        }

        @Test
        fun `parses postgres url with query parameters strips them`() {
            val parts = JdbcUrlBuilder.parse(DbType.POSTGRESQL, "jdbc:postgresql://localhost:5432/mydb?ssl=true&user=foo")
            assertNotNull(parts)
            assertEquals("localhost", parts!!.host)
            assertEquals(5432, parts.port)
            assertEquals("mydb", parts.database)
        }

        @Test
        fun `returns null for malformed url`() {
            assertNull(JdbcUrlBuilder.parse(DbType.POSTGRESQL, "not-a-jdbc-url"))
        }

        @Test
        fun `returns null for url of wrong engine type`() {
            assertNull(JdbcUrlBuilder.parse(DbType.POSTGRESQL, "jdbc:mysql://localhost:3306/mydb"))
        }

        @Test
        fun `returns null for sqlite`() {
            // SQLite has no host/port — caller should not use parsed structure
            assertNull(JdbcUrlBuilder.parse(DbType.SQLITE, "jdbc:sqlite:/tmp/test.db"))
        }

        @Test
        fun `returns null for generic`() {
            // Generic JDBC has no standard format — must be used as raw URL
            assertNull(JdbcUrlBuilder.parse(DbType.GENERIC, "jdbc:custom://anything"))
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Round-trip
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class RoundTrip {

        @Test
        fun `postgres build then parse preserves fields`() {
            val original = JdbcUrlBuilder.build(DbType.POSTGRESQL, "db.example.com", 5433, "analytics")
            val parsed = JdbcUrlBuilder.parse(DbType.POSTGRESQL, original)
            assertNotNull(parsed)
            assertEquals("db.example.com", parsed!!.host)
            assertEquals(5433, parsed.port)
            assertEquals("analytics", parsed.database)
        }

        @Test
        fun `mysql build then parse preserves fields`() {
            val original = JdbcUrlBuilder.build(DbType.MYSQL, "db.example.com", 3307, "analytics")
            val parsed = JdbcUrlBuilder.parse(DbType.MYSQL, original)
            assertNotNull(parsed)
            assertEquals("db.example.com", parsed!!.host)
            assertEquals(3307, parsed.port)
            assertEquals("analytics", parsed.database)
        }

        @Test
        fun `mssql build then parse preserves fields`() {
            val original = JdbcUrlBuilder.build(DbType.MSSQL, "db.example.com", 1434, "analytics")
            val parsed = JdbcUrlBuilder.parse(DbType.MSSQL, original)
            assertNotNull(parsed)
            assertEquals("db.example.com", parsed!!.host)
            assertEquals(1434, parsed.port)
            assertEquals("analytics", parsed.database)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Default port lookup
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class DefaultPort {

        @Test
        fun `postgres default port is 5432`() {
            assertEquals(5432, JdbcUrlBuilder.defaultPort(DbType.POSTGRESQL))
        }

        @Test
        fun `mysql default port is 3306`() {
            assertEquals(3306, JdbcUrlBuilder.defaultPort(DbType.MYSQL))
        }

        @Test
        fun `mssql default port is 1433`() {
            assertEquals(1433, JdbcUrlBuilder.defaultPort(DbType.MSSQL))
        }

        @Test
        fun `sqlite has no default port`() {
            assertEquals(0, JdbcUrlBuilder.defaultPort(DbType.SQLITE))
        }

        @Test
        fun `generic has no default port`() {
            assertEquals(0, JdbcUrlBuilder.defaultPort(DbType.GENERIC))
        }
    }
}
