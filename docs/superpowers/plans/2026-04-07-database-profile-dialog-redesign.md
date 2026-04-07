# Database Profile Dialog Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the free-text "Default database" field in `DatabaseProfileDialog` with a discovered dropdown gated behind a "Test Connection" button, catching wrong-database typos at config time and matching DBeaver's UX.

**Architecture:** Extract pure helpers (`bootstrapUrl`, `discoveryQuery`, `filterSystemDatabases`) into a new `DatabaseDiscovery` object for unit testability. Add `DatabaseConnectionManager.testConnectionAndDiscover` that bootstraps a JDBC connection (PostgreSQL via `/postgres`, MySQL/MSSQL bare, SQLite/Generic via raw URL), runs the per-engine discovery query, and returns the database list. The dialog wires a Test Connection button to this method via a coroutine, populates a `ComboBox<String>` on success, and gates the OK button on `testPassed`. Existing profiles are grandfathered (`testPassed = true` on edit). The persisted `DatabaseProfile` schema is unchanged.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform 2025.1, JUnit 5, Kotlinx Coroutines, JDBC (sqlite-jdbc bundled for tests), IntelliJ DialogWrapper + Swing.

**Spec:** `docs/superpowers/specs/2026-04-07-database-profile-dialog-redesign.md`

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscovery.kt` | **Create** | Pure helper functions: `bootstrapUrl`, `discoveryQuery`, `filterSystemDatabases`. No JDBC, no IntelliJ APIs — fully unit-testable. |
| `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscoveryTest.kt` | **Create** | JUnit 5 unit tests for the three helpers. |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManager.kt` | **Modify** | Add `DiscoveryResult` data class and `testConnectionAndDiscover` suspend function. |
| `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManagerTest.kt` | **Create** | JUnit 5 tests for `testConnectionAndDiscover`. SQLite happy/unhappy paths use real `@TempDir`. PostgreSQL/MySQL/MSSQL test only the unreachable-host failure path (no live servers in CI). |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/DatabaseProfileDialog.kt` | **Modify** | Replace `defaultDbField: JBTextField` with `defaultDbCombo: ComboBox<String>`. Add `testButton`, `testStatusLabel`, `testPassed` state, `dialogScope: CoroutineScope`. Wire field listeners, click handler, validation, OK gating, PostgreSQL hint. |

---

## Task 1: Create DatabaseDiscovery — bootstrap URL helper (TDD)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscovery.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscoveryTest.kt`

- [ ] **Step 1: Create the empty test file**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscoveryTest.kt
package com.workflow.orchestrator.agent.tools.database

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [DatabaseDiscovery] — pure helpers for the test-connection-and-discover
 * flow used by [DatabaseProfileDialog]. No JDBC, no IntelliJ APIs.
 */
class DatabaseDiscoveryTest {

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
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.database.DatabaseDiscoveryTest" 2>&1 | tail -20
```

Expected: compilation error — `Unresolved reference: DatabaseDiscovery`.

- [ ] **Step 3: Create DatabaseDiscovery.kt with bootstrapUrl**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscovery.kt
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
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.database.DatabaseDiscoveryTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all 5 tests in `BootstrapUrl` pass.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscovery.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscoveryTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): add DatabaseDiscovery.bootstrapUrl helper

Pure helper for choosing the bootstrap JDBC URL per engine type.
PostgreSQL → /postgres, MySQL/MSSQL → bare server URL, SQLite/Generic
→ null (raw URL used directly). First step toward the test-driven
database profile dialog redesign.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: DatabaseDiscovery — discovery query helper (TDD)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscovery.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscoveryTest.kt`

- [ ] **Step 1: Add the failing test class**

Append to `DatabaseDiscoveryTest.kt` inside the outer `DatabaseDiscoveryTest` class, right after the `BootstrapUrl` nested class:

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.database.DatabaseDiscoveryTest" 2>&1 | tail -20
```

Expected: compilation error — `Unresolved reference: discoveryQuery`.

- [ ] **Step 3: Add discoveryQuery to DatabaseDiscovery**

Append inside `object DatabaseDiscovery` in `DatabaseDiscovery.kt`, after `bootstrapUrl`:

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.database.DatabaseDiscoveryTest" 2>&1 | tail -20
```

Expected: all 10 tests pass.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscovery.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscoveryTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): add DatabaseDiscovery.discoveryQuery helper

Returns the per-engine SQL for listing user-visible databases on
the bootstrap connection. PostgreSQL/MSSQL filter at the SQL level;
MySQL needs the upcoming filterSystemDatabases helper.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: DatabaseDiscovery — system database filter (TDD)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscovery.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscoveryTest.kt`

- [ ] **Step 1: Add the failing test class**

Append inside `DatabaseDiscoveryTest`, after the `DiscoveryQuery` nested class:

```kotlin
    @Nested
    inner class FilterSystemDatabases {

        @Test
        fun `mysql drops the four system schemas`() {
            val raw = listOf(
                "mysql", "sys", "information_schema", "performance_schema",
                "app_db", "analytics", "users"
            )
            val filtered = DatabaseDiscovery.filterSystemDatabases(DbType.MYSQL, raw)
            assertEquals(listOf("app_db", "analytics", "users"), filtered)
        }

        @Test
        fun `mysql preserves order of remaining databases`() {
            val raw = listOf("z_db", "a_db", "mysql", "m_db")
            val filtered = DatabaseDiscovery.filterSystemDatabases(DbType.MYSQL, raw)
            assertEquals(listOf("z_db", "a_db", "m_db"), filtered)
        }

        @Test
        fun `mysql with no system databases passes through unchanged`() {
            val raw = listOf("app_db", "analytics")
            val filtered = DatabaseDiscovery.filterSystemDatabases(DbType.MYSQL, raw)
            assertEquals(listOf("app_db", "analytics"), filtered)
        }

        @Test
        fun `postgres passes through unchanged because SQL already filtered`() {
            val raw = listOf("postgres", "app_db")
            val filtered = DatabaseDiscovery.filterSystemDatabases(DbType.POSTGRESQL, raw)
            assertEquals(listOf("postgres", "app_db"), filtered)
        }

        @Test
        fun `mssql passes through unchanged because SQL already filtered`() {
            val raw = listOf("master", "app_db")
            val filtered = DatabaseDiscovery.filterSystemDatabases(DbType.MSSQL, raw)
            assertEquals(listOf("master", "app_db"), filtered)
        }

        @Test
        fun `sqlite passes through unchanged`() {
            val raw = listOf("anything")
            val filtered = DatabaseDiscovery.filterSystemDatabases(DbType.SQLITE, raw)
            assertEquals(listOf("anything"), filtered)
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.database.DatabaseDiscoveryTest" 2>&1 | tail -20
```

Expected: compilation error — `Unresolved reference: filterSystemDatabases`.

- [ ] **Step 3: Add filterSystemDatabases to DatabaseDiscovery**

Append inside `object DatabaseDiscovery`, after `discoveryQuery`:

```kotlin
    /**
     * MySQL system database names that should be hidden from the
     * user's "default database" dropdown. Power users who genuinely
     * need to query these can override at query time via
     * `db_query(profile=…, database="information_schema", sql=…)`.
     */
    private val MYSQL_SYSTEM_DATABASES = setOf(
        "mysql", "sys", "information_schema", "performance_schema"
    )

    /**
     * Filters out engine-specific system databases from a raw discovery
     * result list. Only [DbType.MYSQL] needs post-query filtering — the
     * other engines filter at the SQL level (via `WHERE datistemplate = false`
     * or `database_id > 4`).
     */
    fun filterSystemDatabases(dbType: DbType, raw: List<String>): List<String> = when (dbType) {
        DbType.MYSQL -> raw.filter { it !in MYSQL_SYSTEM_DATABASES }
        else -> raw
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.database.DatabaseDiscoveryTest" 2>&1 | tail -20
```

Expected: all 16 tests pass.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscovery.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseDiscoveryTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): add DatabaseDiscovery.filterSystemDatabases

Hides MySQL system schemas (mysql, sys, information_schema,
performance_schema) from the discovered database list. PG/MSSQL
already filter at the SQL level so they pass through.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Add DiscoveryResult data class and method skeleton to DatabaseConnectionManager

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManager.kt`

This task introduces the new entry point with a stub implementation that fails for every engine. Subsequent tasks (5–8) implement each engine's branch under TDD. We commit the skeleton first so each engine task has a clean diff.

- [ ] **Step 1: Add DiscoveryResult and the method skeleton**

Add these to `DatabaseConnectionManager.kt` immediately after the `MAX_CELL_CHARS` constant (around line 33):

```kotlin
    /**
     * Result of a successful test-and-discover call. Used by
     * [com.workflow.orchestrator.agent.settings.DatabaseProfileDialog]
     * to populate the default-database dropdown after the user clicks
     * "Test Connection".
     */
    data class DiscoveryResult(
        /** User-visible databases on the server, system catalogs already filtered out. */
        val databases: List<String>,
        /** Number of system databases hidden from [databases] (informational, for the success label). */
        val systemDatabasesFiltered: Int,
    )

    /**
     * Connect timeout for the test-connection flow (seconds). Hardcoded
     * because the user has not yet saved a profile when this runs, so
     * we can't read per-profile JDBC properties.
     */
    private const val TEST_CONNECT_TIMEOUT_SECONDS = 10
```

- [ ] **Step 2: Add the testConnectionAndDiscover skeleton**

Append after the existing `withConnection` function (around line 76):

```kotlin
    /**
     * Opens a short-lived bootstrap connection for [dbType] and lists the
     * user-visible databases on the server. Used by the Add/Edit profile
     * dialog to validate credentials and populate the default-database
     * dropdown before the user saves the profile.
     *
     * For server engines (PostgreSQL/MySQL/SQL Server) this builds the
     * bootstrap URL via [DatabaseDiscovery.bootstrapUrl] and runs
     * [DatabaseDiscovery.discoveryQuery]. For SQLite/Generic it opens
     * the supplied [rawJdbcUrl] and returns an empty database list (those
     * engines have no per-server discovery).
     *
     * @param rawJdbcUrl Required for SQLite and Generic. Ignored for
     *                   PostgreSQL/MySQL/SQL Server (their bootstrap URL is
     *                   built from [host] and [port]).
     */
    suspend fun testConnectionAndDiscover(
        dbType: DbType,
        host: String,
        port: Int,
        username: String,
        password: String,
        rawJdbcUrl: String = "",
    ): Result<DiscoveryResult> = withContext(Dispatchers.IO) {
        runCatching {
            error("testConnectionAndDiscover: ${dbType.displayName} branch not implemented yet")
        }
    }
```

- [ ] **Step 3: Compile to check syntax**

```bash
./gradlew :agent:compileKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManager.kt
git commit -m "$(cat <<'EOF'
feat(agent): add DiscoveryResult and testConnectionAndDiscover skeleton

Stub returns failure for every engine — branches are filled in by
the next four tasks (one per engine type) under TDD. Splitting the
skeleton from the implementations keeps each engine's commit focused.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: testConnectionAndDiscover — SQLite happy path (TDD)

**Files:**
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManagerTest.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManager.kt`

- [ ] **Step 1: Create the failing test**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManagerTest.kt
package com.workflow.orchestrator.agent.tools.database

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

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

    @Nested
    inner class SqliteBranch {

        @Test
        fun `valid sqlite file returns success with empty database list`(@TempDir tmp: Path) = runTest {
            val dbFile = tmp.resolve("test.db").toFile()
            // Create a real SQLite database by opening and closing a connection.
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { it.createStatement().use { s -> s.execute("CREATE TABLE t (id INTEGER)") } }

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
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.database.DatabaseConnectionManagerTest" 2>&1 | tail -20
```

Expected: failure with `testConnectionAndDiscover: SQLite branch not implemented yet`.

- [ ] **Step 3: Implement the SQLite branch**

Replace the body of `testConnectionAndDiscover` in `DatabaseConnectionManager.kt`:

```kotlin
    suspend fun testConnectionAndDiscover(
        dbType: DbType,
        host: String,
        port: Int,
        username: String,
        password: String,
        rawJdbcUrl: String = "",
    ): Result<DiscoveryResult> = withContext(Dispatchers.IO) {
        runCatching {
            when (dbType) {
                DbType.SQLITE -> {
                    if (rawJdbcUrl.isBlank()) error("SQLite requires a JDBC URL")
                    // Loading via the plugin classloader so the bundled sqlite-jdbc driver is found.
                    @Suppress("UNCHECKED_CAST")
                    val driverClass = Class.forName(
                        DbType.SQLITE.driverClass,
                        true,
                        DatabaseConnectionManager::class.java.classLoader
                    ) as Class<out java.sql.Driver>
                    val driver = driverClass.getDeclaredConstructor().newInstance()
                    val conn = driver.connect(rawJdbcUrl, java.util.Properties())
                        ?: error("SQLite driver returned null for URL '$rawJdbcUrl'")
                    conn.close()
                    DiscoveryResult(databases = emptyList(), systemDatabasesFiltered = 0)
                }
                else -> error("testConnectionAndDiscover: ${dbType.displayName} branch not implemented yet")
            }
        }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.database.DatabaseConnectionManagerTest" 2>&1 | tail -20
```

Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManager.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManagerTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): implement testConnectionAndDiscover SQLite branch

Loads sqlite-jdbc via plugin classloader, opens the user's raw URL,
returns empty discovery (single-database engine). Test uses @TempDir
+ a real SQLite file so no server is required.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: testConnectionAndDiscover — SQLite negative path (TDD)

**Files:**
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManagerTest.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManager.kt` (no change expected — test should fail then pass without code changes if SQLite driver throws)

- [ ] **Step 1: Add the failing test**

Append inside the `SqliteBranch` nested class:

```kotlin
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
```

- [ ] **Step 2: Run the tests to verify behaviour**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.database.DatabaseConnectionManagerTest" 2>&1 | tail -20
```

Expected: both new tests pass (the SQLite driver throws on missing path, and the blank-URL guard already exists in the implementation from Task 5). If `non-existent sqlite path` passes but the SQLite driver actually creates the file silently, change the test to point at an explicitly read-only path or remove this test — note in the commit message.

- [ ] **Step 3: Commit**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManagerTest.kt
git commit -m "$(cat <<'EOF'
test(agent): cover SQLite testConnectionAndDiscover failure paths

Non-existent path and blank URL both return Result.failure with
meaningful error messages.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: testConnectionAndDiscover — Generic JDBC branch (TDD)

**Files:**
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManagerTest.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManager.kt`

- [ ] **Step 1: Add the failing test**

Append a new nested class inside `DatabaseConnectionManagerTest`:

```kotlin
    @Nested
    inner class GenericBranch {

        @Test
        fun `generic with sqlite url returns success with empty database list`(@TempDir tmp: Path) = runTest {
            val dbFile = tmp.resolve("test.db").toFile()
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use {
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
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.database.DatabaseConnectionManagerTest" 2>&1 | tail -20
```

Expected: failure with `Generic branch not implemented yet`.

- [ ] **Step 3: Add the Generic branch**

Update the `when` block in `testConnectionAndDiscover`:

```kotlin
                DbType.GENERIC -> {
                    if (rawJdbcUrl.isBlank()) error("Generic JDBC requires a URL")
                    val props = java.util.Properties().apply {
                        if (username.isNotBlank()) put("user", username)
                        if (password.isNotEmpty()) put("password", password)
                    }
                    // Generic falls back to DriverManager — the user is expected to have
                    // their own driver on the classpath.
                    val conn = java.sql.DriverManager.getConnection(rawJdbcUrl, props)
                    conn.close()
                    DiscoveryResult(databases = emptyList(), systemDatabasesFiltered = 0)
                }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.database.DatabaseConnectionManagerTest" 2>&1 | tail -20
```

Expected: all SQLite + Generic tests pass.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManager.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManagerTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): implement testConnectionAndDiscover Generic JDBC branch

Uses DriverManager directly so users can supply any driver on the
classpath. Tested with sqlite-jdbc as a portable in-process driver.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: testConnectionAndDiscover — server engine branches (TDD)

**Files:**
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManagerTest.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManager.kt`

The server engines (PostgreSQL, MySQL, SQL Server) all share the same shape: build the bootstrap URL via `DatabaseDiscovery`, open the connection with username/password, run the discovery query, filter results. We test only the failure path (unreachable host) since CI has no live servers.

- [ ] **Step 1: Add the failing test**

Append a new nested class:

```kotlin
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
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.database.DatabaseConnectionManagerTest" 2>&1 | tail -20
```

Expected: all 3 fail with `branch not implemented yet`.

- [ ] **Step 3: Implement the server engine branches**

Update `testConnectionAndDiscover` so the `when` block looks like this in full (replacing the previous body):

```kotlin
    suspend fun testConnectionAndDiscover(
        dbType: DbType,
        host: String,
        port: Int,
        username: String,
        password: String,
        rawJdbcUrl: String = "",
    ): Result<DiscoveryResult> = withContext(Dispatchers.IO) {
        runCatching {
            when (dbType) {
                DbType.SQLITE -> {
                    if (rawJdbcUrl.isBlank()) error("SQLite requires a JDBC URL")
                    @Suppress("UNCHECKED_CAST")
                    val driverClass = Class.forName(
                        DbType.SQLITE.driverClass,
                        true,
                        DatabaseConnectionManager::class.java.classLoader
                    ) as Class<out java.sql.Driver>
                    val driver = driverClass.getDeclaredConstructor().newInstance()
                    val conn = driver.connect(rawJdbcUrl, java.util.Properties())
                        ?: error("SQLite driver returned null for URL '$rawJdbcUrl'")
                    conn.close()
                    DiscoveryResult(databases = emptyList(), systemDatabasesFiltered = 0)
                }
                DbType.GENERIC -> {
                    if (rawJdbcUrl.isBlank()) error("Generic JDBC requires a URL")
                    val props = java.util.Properties().apply {
                        if (username.isNotBlank()) put("user", username)
                        if (password.isNotEmpty()) put("password", password)
                    }
                    val conn = java.sql.DriverManager.getConnection(rawJdbcUrl, props)
                    conn.close()
                    DiscoveryResult(databases = emptyList(), systemDatabasesFiltered = 0)
                }
                DbType.POSTGRESQL, DbType.MYSQL, DbType.MSSQL -> {
                    val bootstrapUrl = DatabaseDiscovery.bootstrapUrl(dbType, host, port)
                        ?: error("No bootstrap URL for ${dbType.displayName}")
                    val query = DatabaseDiscovery.discoveryQuery(dbType)
                        ?: error("No discovery query for ${dbType.displayName}")

                    val props = java.util.Properties().apply {
                        put("user", username)
                        put("password", password)
                        // Engine-specific tweaks copied from openConnection()
                        if (dbType == DbType.MYSQL) {
                            put("useSSL", "false")
                            put("allowPublicKeyRetrieval", "true")
                        }
                        // Cap how long the driver waits before giving up
                        put("loginTimeout", TEST_CONNECT_TIMEOUT_SECONDS.toString())
                    }
                    java.sql.DriverManager.setLoginTimeout(TEST_CONNECT_TIMEOUT_SECONDS)

                    @Suppress("UNCHECKED_CAST")
                    val driverClass = Class.forName(
                        dbType.driverClass,
                        true,
                        DatabaseConnectionManager::class.java.classLoader
                    ) as Class<out java.sql.Driver>
                    val driver = driverClass.getDeclaredConstructor().newInstance()
                    val conn = driver.connect(bootstrapUrl, props)
                        ?: error("Driver returned null for URL '$bootstrapUrl'")

                    try {
                        val rawNames = mutableListOf<String>()
                        conn.createStatement().use { stmt ->
                            stmt.queryTimeout = TEST_CONNECT_TIMEOUT_SECONDS
                            stmt.executeQuery(query).use { rs ->
                                while (rs.next()) {
                                    rawNames.add(rs.getString(1))
                                }
                            }
                        }
                        val filtered = DatabaseDiscovery.filterSystemDatabases(dbType, rawNames)
                        DiscoveryResult(
                            databases = filtered,
                            systemDatabasesFiltered = rawNames.size - filtered.size,
                        )
                    } finally {
                        runCatching { conn.close() }
                    }
                }
            }
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.database.DatabaseConnectionManagerTest" 2>&1 | tail -20
```

Expected: all DatabaseConnectionManager tests pass.

If any of the unreachable-host tests *do not* fail within 15 seconds (slow CI), increase the timeout or change `unreachablePort` to a different reserved port — but this is unlikely.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManager.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManagerTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): implement server engine branches in testConnectionAndDiscover

PostgreSQL/MySQL/SQL Server all share the same shape: bootstrap via
DatabaseDiscovery.bootstrapUrl, run discoveryQuery, filter system DBs.
Login timeout capped at 10s. Unreachable-host tests cover the failure
path; happy-path testing requires live servers and is deferred to
manual testing.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: DatabaseProfileDialog — replace defaultDbField with defaultDbCombo

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/DatabaseProfileDialog.kt`

This task swaps the data type only — UI flow updates come in later tasks. Compilation is the success criterion (no test, dialog tests are out of scope per the spec).

- [ ] **Step 1: Replace the field declaration**

Find this block in `DatabaseProfileDialog.kt` (currently around lines 56–58):

```kotlin
    private val defaultDbField = JBTextField(existing?.resolvedDefaultDatabase ?: "").apply {
        preferredSize = Dimension(200, 28)
    }
```

Replace it with:

```kotlin
    // Default database is now a dropdown populated by Test Connection.
    // For new profiles it starts disabled and empty. For existing profiles
    // (edit flow) it is grandfathered: pre-seeded with the saved DB and
    // enabled, so the user can save without re-testing.
    private val defaultDbCombo: com.intellij.openapi.ui.ComboBox<String> =
        com.intellij.openapi.ui.ComboBox<String>().apply {
            preferredSize = Dimension(200, 28)
            val seed = existing?.resolvedDefaultDatabase?.takeIf { it.isNotBlank() }
            if (seed != null) {
                addItem(seed)
                selectedItem = seed
                isEnabled = true
            } else {
                isEnabled = false
            }
        }
```

- [ ] **Step 2: Update updateFieldVisibility to refer to the new field**

Find (around line 119):

```kotlin
    private fun updateFieldVisibility() {
        val rawMode = rawUrlCheckbox.isSelected
        hostField.isEnabled = !rawMode
        portField.isEnabled = !rawMode
        defaultDbField.isEnabled = !rawMode
        urlField.isEnabled = rawMode
    }
```

Replace with:

```kotlin
    private fun updateFieldVisibility() {
        val rawMode = rawUrlCheckbox.isSelected
        hostField.isEnabled = !rawMode
        portField.isEnabled = !rawMode
        // The combo's enable state is also driven by Test Connection results
        // (set in the click handler). Raw mode just hides it entirely.
        defaultDbCombo.isEnabled = !rawMode && (testPassed || existing != null)
        urlField.isEnabled = rawMode
    }
```

- [ ] **Step 3: Update buildPreviewUrl**

Find (around line 127):

```kotlin
    private fun buildPreviewUrl(): String {
        val type = typeCombo.selectedItem as DbType
        if (type == DbType.SQLITE) return "jdbc:sqlite:/path/to/database.db"
        if (type == DbType.GENERIC) return ""
        val host = hostField.text.ifBlank { "localhost" }
        val port = portField.text.toIntOrNull() ?: 0
        val db = defaultDbField.text.ifBlank { "mydb" }
        return JdbcUrlBuilder.build(type, host, port, db)
    }
```

Replace `val db = defaultDbField.text.ifBlank { "mydb" }` with:

```kotlin
        val db = (defaultDbCombo.selectedItem as? String)?.ifBlank { "mydb" } ?: "mydb"
```

- [ ] **Step 4: Update the layout row**

Find (around lines 155–160):

```kotlin
        row("Default database:") {
            cell(defaultDbField).align(AlignX.FILL).comment(
                "Database to connect to when no `database` parameter is supplied. " +
                    "The agent can switch via db_list_databases + db_query(database=…)."
            )
        }
```

Replace with:

```kotlin
        row("Default database:") {
            cell(defaultDbCombo).align(AlignX.FILL).comment(
                "Click 'Test Connection' below to discover and select a database. " +
                    "The agent uses this when no explicit database is supplied to a query, " +
                    "and can still switch databases per-query via db_query(database=…)."
            )
        }
```

- [ ] **Step 5: Update doValidate**

Find (around lines 197–198):

```kotlin
            if (defaultDbField.text.isBlank())
                return ValidationInfo("Default database is required", defaultDbField)
```

Replace with:

```kotlin
            val selected = defaultDbCombo.selectedItem as? String
            if (selected.isNullOrBlank())
                return ValidationInfo(
                    "Select a database from the list (click Test Connection first)",
                    defaultDbCombo
                )
```

- [ ] **Step 6: Update buildProfile**

Find (around lines 222–232):

```kotlin
        } else {
            DatabaseProfile(
                id = idField.text.trim(),
                displayName = nameField.text.trim(),
                dbType = type,
                username = userField.text.trim(),
                host = hostField.text.trim(),
                port = portField.text.toIntOrNull() ?: JdbcUrlBuilder.defaultPort(type),
                defaultDatabase = defaultDbField.text.trim(),
            )
        }
```

Replace with:

```kotlin
        } else {
            DatabaseProfile(
                id = idField.text.trim(),
                displayName = nameField.text.trim(),
                dbType = type,
                username = userField.text.trim(),
                host = hostField.text.trim(),
                port = portField.text.toIntOrNull() ?: JdbcUrlBuilder.defaultPort(type),
                defaultDatabase = (defaultDbCombo.selectedItem as? String)?.trim().orEmpty(),
            )
        }
```

- [ ] **Step 7: Add the testPassed field declaration**

Add this private property near the top of the class, right after `passHint` (around line 76):

```kotlin
    // True when the user has successfully tested the connection, OR when editing
    // an existing profile (grandfathered — assumed valid until they change a
    // connection-affecting field). Gates the OK button.
    private var testPassed: Boolean = (existing != null)
```

- [ ] **Step 8: Compile**

```bash
./gradlew :agent:compileKotlin 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. There may be warnings about unused fields — those will go away in the next task.

- [ ] **Step 9: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/DatabaseProfileDialog.kt
git commit -m "$(cat <<'EOF'
refactor(agent): swap defaultDbField text field for ComboBox

Pure data-type swap. The combo is empty/disabled for new profiles
(populated later by Test Connection) and pre-seeded for existing
profiles (grandfathered). Validation, layout, buildProfile, and
buildPreviewUrl updated to read from the combo. Test Connection
button itself is added in the next task.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: DatabaseProfileDialog — add Test Connection button row

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/DatabaseProfileDialog.kt`

- [ ] **Step 1: Add new field declarations**

Add these private properties immediately after the `defaultDbCombo` declaration (after the closing `}` of the apply block):

```kotlin
    // Test Connection UI
    private val testButton = javax.swing.JButton("Test Connection")
    private val testStatusLabel = JBLabel("").apply {
        // Default neutral colour; success/error update to green/red on click.
        foreground = com.intellij.ui.JBColor.GRAY
    }

    // Coroutine scope owned by the dialog — cancelled in dispose() so an
    // in-flight test connection can't outlive the dialog.
    private val dialogScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )
```

- [ ] **Step 2: Add the new layout rows**

Find the layout block in `createCenterPanel`. Locate the `Default database` row added in Task 9. Immediately after that row's closing `}`, add these two new rows:

```kotlin
        row {
            cell(testButton)
        }
        row {
            cell(testStatusLabel).align(AlignX.FILL)
        }
```

The order should now be: Default database row → Test button row → Status label row → (existing) raw URL checkbox.

- [ ] **Step 3: Override dispose to cancel the scope**

Add this method to the class, after `buildProfile`:

```kotlin
    override fun dispose() {
        dialogScope.cancel()
        super.dispose()
    }
```

You will also need this import at the top of the file:

```kotlin
import kotlinx.coroutines.cancel
```

- [ ] **Step 4: Compile**

```bash
./gradlew :agent:compileKotlin 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. The button has no click handler yet — that's added in Task 11.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/DatabaseProfileDialog.kt
git commit -m "$(cat <<'EOF'
feat(agent): add Test Connection button row and dialog coroutine scope

Adds the button + status label rows below the Default database combo,
plus a SupervisorJob-backed dialogScope cancelled in dispose(). Click
handler is wired in the next task.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: DatabaseProfileDialog — wire Test Connection click handler

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/DatabaseProfileDialog.kt`

- [ ] **Step 1: Add the click handler method**

Add this method to the class (place it after `updateFieldVisibility`):

```kotlin
    /**
     * Runs the test connection in [dialogScope]. On success, populates
     * the database combo with the discovered list and enables the OK
     * button. On failure, surfaces the driver's error message in the
     * status label without enabling save.
     *
     * Uses bare host/port credentials for server engines and the raw
     * URL field for SQLite/Generic. Validates required fields before
     * attempting the connection.
     */
    private fun runTestConnection() {
        val type = typeCombo.selectedItem as DbType
        val rawMode = rawUrlCheckbox.isSelected

        // Pre-flight validation: surface obvious problems immediately
        // instead of issuing a doomed JDBC call.
        val preflightError = when {
            rawMode || type == DbType.SQLITE || type == DbType.GENERIC -> {
                if (urlField.text.isBlank()) "JDBC URL is required for ${type.displayName}" else null
            }
            else -> {
                when {
                    hostField.text.isBlank() -> "Host is required"
                    (portField.text.toIntOrNull() ?: 0) <= 0 -> "Port is required"
                    userField.text.isBlank() -> "Username is required"
                    passField.password.isEmpty() -> "Password is required"
                    else -> null
                }
            }
        }
        if (preflightError != null) {
            testStatusLabel.foreground = com.intellij.ui.JBColor.RED
            testStatusLabel.text = preflightError
            return
        }

        // Disable the button and clear status while we're working.
        testButton.isEnabled = false
        testStatusLabel.foreground = com.intellij.ui.JBColor.GRAY
        testStatusLabel.text = "Testing..."

        val host = hostField.text.trim()
        val port = portField.text.toIntOrNull() ?: JdbcUrlBuilder.defaultPort(type)
        val username = userField.text.trim()
        val password = String(passField.password)
        val rawUrl = urlField.text.trim()

        dialogScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                DatabaseConnectionManager.testConnectionAndDiscover(
                    dbType = type,
                    host = host,
                    port = port,
                    username = username,
                    password = password,
                    rawJdbcUrl = rawUrl,
                )
            }

            // Back on EDT (dialogScope is on Dispatchers.Main, which is EDT in Swing)
            testButton.isEnabled = true
            result.fold(
                onSuccess = { discovery ->
                    testPassed = true
                    if (type == DbType.SQLITE || type == DbType.GENERIC) {
                        // Single-database engines: nothing to put in the combo.
                        defaultDbCombo.removeAllItems()
                        defaultDbCombo.isEnabled = false
                        testStatusLabel.foreground = com.intellij.ui.JBColor.GREEN
                        testStatusLabel.text = "✓ Connected."
                    } else {
                        defaultDbCombo.removeAllItems()
                        discovery.databases.forEach { defaultDbCombo.addItem(it) }
                        if (discovery.databases.isNotEmpty()) {
                            defaultDbCombo.selectedIndex = 0
                        }
                        defaultDbCombo.isEnabled = true
                        testStatusLabel.foreground = com.intellij.ui.JBColor.GREEN
                        val filteredSuffix =
                            if (discovery.systemDatabasesFiltered > 0)
                                " (${discovery.systemDatabasesFiltered} system database(s) hidden)"
                            else ""
                        testStatusLabel.text =
                            "✓ Connected. Found ${discovery.databases.size} database(s)$filteredSuffix."
                    }
                    okAction.isEnabled = true
                },
                onFailure = { ex ->
                    testPassed = false
                    okAction.isEnabled = false
                    testStatusLabel.foreground = com.intellij.ui.JBColor.RED
                    val raw = ex.message ?: ex.javaClass.simpleName
                    val truncated = if (raw.length > 200) raw.take(200) + "…" else raw
                    testStatusLabel.text = truncated
                }
            )
        }
    }
```

- [ ] **Step 2: Wire the button to the handler in init**

Find the `init` block (around line 78). Just before the closing `}` of `init`, add:

```kotlin
        testButton.addActionListener { runTestConnection() }
```

- [ ] **Step 3: Add required imports**

Add to the top of the file (alongside the existing imports):

```kotlin
import kotlinx.coroutines.launch
```

- [ ] **Step 4: Compile**

```bash
./gradlew :agent:compileKotlin 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/DatabaseProfileDialog.kt
git commit -m "$(cat <<'EOF'
feat(agent): wire Test Connection button click handler

Click runs DatabaseConnectionManager.testConnectionAndDiscover in a
coroutine on Dispatchers.IO, then back on EDT either populates the
database combo + enables OK (success) or shows the driver error in
red (failure). SQLite/Generic show a simple "Connected." status
without populating the combo.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: DatabaseProfileDialog — reset testPassed when fields change

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/DatabaseProfileDialog.kt`

When the user edits a connection-affecting field, any prior test result is invalidated and they must re-test before saving.

- [ ] **Step 1: Add the invalidation helper**

Add this method to the class, after `runTestConnection`:

```kotlin
    /**
     * Marks the prior Test Connection result as invalid. Called when the
     * user edits any connection-affecting field. Disables the OK button
     * and clears the status label so the user knows they must re-test.
     *
     * Does NOT clear the combo's existing items — the user might still
     * want to see what was discovered last time. The dropdown is disabled
     * so it can't be re-selected without a fresh test.
     */
    private fun invalidateTestResult() {
        if (!testPassed) return
        testPassed = false
        okAction.isEnabled = (existing != null)  // grandfathered profiles stay saveable
        defaultDbCombo.isEnabled = false
        testStatusLabel.text = ""
    }
```

- [ ] **Step 2: Wire the document listeners**

Inside the `init` block, just after the `testButton.addActionListener` line from Task 11, add:

```kotlin
        // Re-test required after any edit to a connection-affecting field
        val invalidator = object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = invalidateTestResult()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = invalidateTestResult()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = invalidateTestResult()
        }
        hostField.document.addDocumentListener(invalidator)
        portField.document.addDocumentListener(invalidator)
        userField.document.addDocumentListener(invalidator)
        passField.document.addDocumentListener(invalidator)
        urlField.document.addDocumentListener(invalidator)
        // Type change also invalidates the test
        typeCombo.addActionListener { invalidateTestResult() }
        // rawUrlCheckbox toggle invalidates too
        rawUrlCheckbox.addActionListener { invalidateTestResult() }
```

Note: there is already a `typeCombo.addActionListener { onTypeChanged() }` line in init. Keep that one — the invalidator is in addition.

- [ ] **Step 3: Compile**

```bash
./gradlew :agent:compileKotlin 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/DatabaseProfileDialog.kt
git commit -m "$(cat <<'EOF'
feat(agent): invalidate test result when connection fields change

Document listeners on host/port/user/password/URL fields and action
listeners on type/raw-mode toggles all reset testPassed and disable
OK. Existing profiles stay saveable (grandfathered) but their combo
is disabled, forcing re-test before any new selection can be made.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: DatabaseProfileDialog — gate OK button on testPassed

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/DatabaseProfileDialog.kt`

- [ ] **Step 1: Set initial OK enable state in init**

In the `init` block, before any of the listeners (right after the existing `init()` call from `DialogWrapper`), add:

```kotlin
        // OK is disabled for new profiles until Test Connection succeeds.
        // Existing profiles open with OK enabled (grandfathered).
        okAction.isEnabled = (existing != null)
```

- [ ] **Step 2: Compile**

```bash
./gradlew :agent:compileKotlin 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/DatabaseProfileDialog.kt
git commit -m "$(cat <<'EOF'
feat(agent): gate OK button on Test Connection success

New profiles open with OK disabled; clicking Test Connection enables
it on success. Existing profiles open with OK enabled (grandfathered)
but flip to disabled if any connection-affecting field is edited.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: DatabaseProfileDialog — PostgreSQL bootstrap-fail hint

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/DatabaseProfileDialog.kt`

- [ ] **Step 1: Augment the failure path in runTestConnection**

In the `onFailure` branch of the `result.fold` block inside `runTestConnection`, replace this:

```kotlin
                onFailure = { ex ->
                    testPassed = false
                    okAction.isEnabled = false
                    testStatusLabel.foreground = com.intellij.ui.JBColor.RED
                    val raw = ex.message ?: ex.javaClass.simpleName
                    val truncated = if (raw.length > 200) raw.take(200) + "…" else raw
                    testStatusLabel.text = truncated
                }
```

with this:

```kotlin
                onFailure = { ex ->
                    testPassed = false
                    okAction.isEnabled = false
                    testStatusLabel.foreground = com.intellij.ui.JBColor.RED
                    val raw = ex.message ?: ex.javaClass.simpleName
                    val truncated = if (raw.length > 200) raw.take(200) + "…" else raw
                    val hint = if (
                        type == DbType.POSTGRESQL &&
                        raw.contains("postgres", ignoreCase = true) &&
                        raw.contains("does not exist", ignoreCase = true)
                    ) {
                        " — Tip: enable 'Use raw JDBC URL' and supply a database you can access."
                    } else ""
                    testStatusLabel.text = truncated + hint
                }
```

- [ ] **Step 2: Compile**

```bash
./gradlew :agent:compileKotlin 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/DatabaseProfileDialog.kt
git commit -m "$(cat <<'EOF'
feat(agent): hint at raw URL escape hatch when PG postgres DB is locked

When the PostgreSQL bootstrap fails specifically because the
'postgres' database doesn't exist on the server (common in hardened
corporate environments), append a one-line hint pointing the user at
the existing 'Use raw JDBC URL' checkbox.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: Final verification — full test suite + plugin build

**Files:**
- None modified — verification only.

- [ ] **Step 1: Run the full agent test suite**

```bash
./gradlew :agent:test 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`. All ~470 existing agent tests + the new `DatabaseDiscoveryTest` (16 tests) + new `DatabaseConnectionManagerTest` (6 tests) pass.

- [ ] **Step 2: Run the full plugin build**

```bash
./gradlew clean buildPlugin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. The ZIP is produced at `build/distributions/intellij-workflow-orchestrator-<version>.zip`.

- [ ] **Step 3: Sandbox smoke test (manual)**

```bash
./gradlew runIde
```

In the sandbox IDE:

1. Open **Settings → Tools → Workflow Orchestrator → AI Agent → Database Profiles**.
2. Click **Add**.
3. Verify the dialog now shows a Test Connection button below a (disabled) Default database dropdown.
4. Fill in a real PostgreSQL profile (host, port, user, password). Click **Test Connection**.
5. Verify: status label flips to green "✓ Connected. Found N database(s).", dropdown populates, OK button enables.
6. Pick a database from the dropdown. Click OK. Profile saves.
7. Re-open the saved profile. Verify the dropdown shows the saved DB pre-selected and OK is enabled (grandfathered).
8. Edit the host field. Verify OK disables and the status label clears.
9. Click Test Connection again. Verify it re-discovers and re-enables OK.
10. Try a wrong password. Verify red error in the status label.
11. Try host=`127.0.0.1` port=`1`. Verify red error within ~10 seconds (the test timeout).
12. Switch to SQLite type. Fill in a valid `jdbc:sqlite:/tmp/test.db` URL. Click Test. Verify green "✓ Connected." with no dropdown.
13. Confirm existing profiles in the list still load and edit correctly.

- [ ] **Step 4: If anything fails, fix inline and commit**

Any fixes needed during smoke testing get committed as their own commit. There's no checklist for this — use your judgment.

- [ ] **Step 5: Final commit (only if there were fixes)**

```bash
# Skip if step 4 produced no changes
git status
git log --oneline -15
```

---

## Self-Review

### Spec coverage check

| Spec section | Implementing tasks |
|---|---|
| Goals 1–5 (test-time validation, no typos, DBeaver UX, schema unchanged, query convenience preserved) | All implementing tasks; smoke test in Task 15 |
| Dialog state machine (Untested / Test passed / Grandfathered) | Tasks 9 (init state), 11 (transitions on success/failure), 12 (transitions on edit), 13 (initial OK gating) |
| Field changes table | Task 9 |
| Test Connection button + click handler steps 1–5 | Task 10 (UI), Task 11 (handler), Task 12 (invalidation), Task 14 (PG hint) |
| Per-engine bootstrap and discovery table | Task 1 (`bootstrapUrl`), Task 2 (`discoveryQuery`), Task 3 (`filterSystemDatabases`), Tasks 5–8 (`testConnectionAndDiscover` per engine) |
| Edge case: PG bootstrap unavailable + escape hatch hint | Task 14 |
| Validation changes (`doValidate` reads from combo) | Task 9 step 5 |
| Persistence (DatabaseProfile schema unchanged) | Verified by Task 9 step 6 reading from combo into the existing `defaultDatabase` field; no DataProfile.kt modification anywhere |
| Migration of existing profiles (grandfathered) | Task 9 step 1 (combo seeded), Task 9 step 7 (`testPassed = (existing != null)`), Task 12 (invalidation only on edits), Task 13 step 1 (initial OK enabled for existing) |
| Threading | Task 10 step 1 (dialogScope), Task 10 step 3 (dispose), Task 11 step 1 (`withContext(IO)`), Task 11 step 1 (rest of handler runs on dialogScope's Main dispatcher) |
| Testing strategy section | Tasks 1–3 (helper unit tests), Tasks 5–8 (`testConnectionAndDiscover` unit tests), Task 15 (manual smoke matrix) |
| Out of scope items | None implemented (correctly omitted) |

No spec gaps.

### Placeholder scan

Searched the plan for the No-Placeholders red flags:
- No "TBD" / "TODO" / "implement later"
- No "add appropriate error handling" / "handle edge cases" without specifics
- No "write tests for the above" without code
- No "similar to Task N" without repeating
- All code steps include the actual code
- No references to types/methods not defined in any task

Clean.

### Type consistency check

- `DiscoveryResult(val databases: List<String>, val systemDatabasesFiltered: Int)` — defined in Task 4, consumed in Tasks 5/6/7/8/11. Field names match across all uses.
- `DatabaseDiscovery.bootstrapUrl(dbType, host, port): String?` — defined Task 1, used Task 8. Signature matches.
- `DatabaseDiscovery.discoveryQuery(dbType): String?` — defined Task 2, used Task 8. Signature matches.
- `DatabaseDiscovery.filterSystemDatabases(dbType, raw): List<String>` — defined Task 3, used Task 8. Signature matches.
- `testConnectionAndDiscover(dbType, host, port, username, password, rawJdbcUrl)` — defined Task 4, refined Tasks 5/6/7/8, called Task 11. Signature matches everywhere.
- `defaultDbCombo: ComboBox<String>` — defined Task 9, populated Task 11, invalidated Task 12, gated Task 13. Field name consistent.
- `testPassed: Boolean` — declared Task 9, set Tasks 11/12, read Tasks 9/12/13. Consistent.
- `dialogScope: CoroutineScope` — declared Task 10, used Task 11, cancelled Task 10. Consistent.
- `runTestConnection()` — defined Task 11, wired Task 11, augmented Task 14. Consistent.
- `invalidateTestResult()` — defined Task 12, wired Task 12. Consistent.

All types and names match across tasks.
