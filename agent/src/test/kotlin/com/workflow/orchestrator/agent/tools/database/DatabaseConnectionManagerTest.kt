package com.workflow.orchestrator.agent.tools.database

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Properties

/**
 * Tests for [DatabaseConnectionManager.testConnectionAndDiscover].
 *
 * Coverage strategy:
 *  - SQLite branches use real `@TempDir` files because sqlite-jdbc has
 *    no server requirement and runs anywhere.
 *  - Generic JDBC branch reuses the SQLite driver via a raw URL.
 *  - PostgreSQL / MySQL / SQL Server branches are tested only for
 *    failure (unreachable host on a port that is not listening). Real
 *    server tests would require testcontainers and are out of scope.
 */
class DatabaseConnectionManagerTest {

    /**
     * Shut down MySQL Connector-J's `AbandonedConnectionCleanupThread` daemon
     * after each test. The thread is started automatically when `DriverManager`
     * loads MySQL via SPI (which happens when the Generic branch test calls
     * `DriverManager.getConnection`). IntelliJ's `ThreadLeakTracker` runs after
     * each test and flags this daemon as a leak unless we shut it down first.
     *
     * `uncheckedShutdown()` is preferred over `checkedShutdown()` because
     * failure to shut down the cleanup thread should not abort the test —
     * the test has already verified the production code's contract.
     *
     * Wrapped in `runCatching` to handle the case where MySQL Connector-J
     * was never loaded (the thread doesn't exist) or the API changes in
     * a future version.
     *
     * Declared on the outer class so it covers all nested classes:
     * [SqliteBranch], [GenericBranch], and [ServerEngineBranches]. The MySQL
     * unreachable-host test in [ServerEngineBranches] can also start the
     * cleanup thread, so a teardown scoped only to [GenericBranch] would
     * leave it running after server-engine tests, causing flaky CI failures.
     */
    @AfterEach
    fun shutdownMysqlCleanupThread() {
        runCatching {
            val clazz = Class.forName("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread")
            clazz.getMethod("uncheckedShutdown").invoke(null)
        }
    }

    /**
     * Opens a SQLite connection via the plugin classloader (same path as production code)
     * to avoid triggering MySQL's `AbandonedConnectionCleanupThread` via DriverManager
     * service-provider auto-loading, which would cause an IntelliJ thread-leak assertion.
     */
    private fun openSqlite(url: String): java.sql.Connection {
        @Suppress("UNCHECKED_CAST")
        val driverClass = Class.forName(
            DbType.SQLITE.driverClass,
            true,
            DatabaseConnectionManager::class.java.classLoader
        ) as Class<out java.sql.Driver>
        val driver = driverClass.getDeclaredConstructor().newInstance()
        return driver.connect(url, Properties())
            ?: error("SQLite driver returned null for URL '$url'")
    }

    @Nested
    inner class SqliteBranch {

        @Test
        fun `valid sqlite file returns success with empty database list`(@TempDir tmp: Path) = runTest {
            val dbFile = tmp.resolve("test.db").toFile()
            // Create a real SQLite database using the plugin classloader path (avoids MySQL thread leak).
            openSqlite("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
                conn.createStatement().use { s -> s.execute("CREATE TABLE t (id INTEGER)") }
            }

            val result = DatabaseConnectionManager.testConnectionAndDiscover(
                dbType = DbType.SQLITE,
                host = "",
                port = 0,
                username = "",
                password = "",
                rawJdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}",
            )

            assertTrue(result.isSuccess, "Expected success, got ${result.exceptionOrNull()?.message}")
            val discovery = result.getOrThrow()
            assertEquals(emptyList<String>(), discovery.databases)
            assertEquals(0, discovery.systemDatabasesFiltered)
        }

        @Test
        fun `non-existent sqlite path returns failure`(@TempDir tmp: Path) = runTest {
            // Point at a directory that does not exist — sqlite-jdbc cannot create the file
            val missing = tmp.resolve("does/not/exist/test.db").toFile()

            val result = DatabaseConnectionManager.testConnectionAndDiscover(
                dbType = DbType.SQLITE,
                host = "",
                port = 0,
                username = "",
                password = "",
                rawJdbcUrl = "jdbc:sqlite:${missing.absolutePath}",
            )

            assertTrue(result.isFailure, "Expected failure for non-existent path")
        }

        @Test
        fun `blank sqlite jdbc url returns failure`() = runTest {
            val result = DatabaseConnectionManager.testConnectionAndDiscover(
                dbType = DbType.SQLITE,
                host = "",
                port = 0,
                username = "",
                password = "",
                rawJdbcUrl = "",
            )

            assertTrue(result.isFailure)
            assertTrue(
                result.exceptionOrNull()?.message?.contains("requires a JDBC URL") == true,
                "Expected error to mention JDBC URL requirement, got: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    @Nested
    inner class GenericBranch {

        @Test
        fun `generic with sqlite url returns success with empty database list`(@TempDir tmp: Path) = runTest {
            val dbFile = tmp.resolve("test.db").toFile()
            // Create the SQLite file via the same classloader path used by production code
            // (avoids triggering MySQL's AbandonedConnectionCleanupThread via DriverManager).
            openSqlite("jdbc:sqlite:${dbFile.absolutePath}").use {
                it.createStatement().use { s -> s.execute("CREATE TABLE t (id INTEGER)") }
            }

            val result = DatabaseConnectionManager.testConnectionAndDiscover(
                dbType = DbType.GENERIC,
                host = "",
                port = 0,
                username = "",
                password = "",
                rawJdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}",
            )

            assertTrue(result.isSuccess, "Expected success, got ${result.exceptionOrNull()?.message}")
            assertEquals(emptyList<String>(), result.getOrThrow().databases)
        }

        @Test
        fun `generic with malformed url returns failure`() = runTest {
            val result = DatabaseConnectionManager.testConnectionAndDiscover(
                dbType = DbType.GENERIC,
                host = "",
                port = 0,
                username = "",
                password = "",
                rawJdbcUrl = "not-a-jdbc-url",
            )

            assertTrue(result.isFailure)
        }
    }

    @Nested
    inner class ServerEngineBranches {

        // Port 1 is reserved (TCP/UDP port multiplexer) and reliably refuses
        // connections in CI environments — used here as a portable "unreachable host" target.
        private val unreachableHost = "127.0.0.1"
        private val unreachablePort = 1

        @Test
        fun `postgres unreachable host returns failure`() = runTest {
            val result = DatabaseConnectionManager.testConnectionAndDiscover(
                dbType = DbType.POSTGRESQL,
                host = unreachableHost,
                port = unreachablePort,
                username = "test",
                password = "test",
            )
            assertTrue(result.isFailure, "Expected failure when connecting to unreachable host")
        }

        @Test
        fun `mysql unreachable host returns failure`() = runTest {
            val result = DatabaseConnectionManager.testConnectionAndDiscover(
                dbType = DbType.MYSQL,
                host = unreachableHost,
                port = unreachablePort,
                username = "test",
                password = "test",
            )
            assertTrue(result.isFailure)
        }

        @Test
        fun `mssql unreachable host returns failure`() = runTest {
            val result = DatabaseConnectionManager.testConnectionAndDiscover(
                dbType = DbType.MSSQL,
                host = unreachableHost,
                port = unreachablePort,
                username = "test",
                password = "test",
            )
            assertTrue(result.isFailure)
        }
    }
}
