# Database Profile Dialog Redesign — Test-Driven Discovery

**Date:** 2026-04-07
**Status:** Approved for planning
**Module:** `:agent`
**Files affected (primary):** `agent/.../settings/DatabaseProfileDialog.kt`, `agent/.../tools/database/DatabaseConnectionManager.kt`

## Background

The current `DatabaseProfileDialog` lets the user type a "Default database" name as a free-form text field. The field is **required** for server engines (PostgreSQL, MySQL, SQL Server) per `doValidate` (line 197–198), and is used as the database component of the JDBC URL when the agent calls `db_query(profile=…, sql=…)` without an explicit `database` parameter.

Two real problems with this:

1. **No validation at config time.** Users can type a database name that doesn't exist on the server, save the profile, and only discover the typo when the agent makes its first query. The error surfaces hours later in an agent transcript instead of immediately in the dialog.

2. **The "default database" claim is over-restrictive for MySQL and SQL Server.** Their JDBC drivers accept bare server URLs (`jdbc:mysql://host:3306/`, `jdbc:sqlserver://host:1433`) and let you switch databases per-query via `USE`. The plugin's `JdbcUrlBuilder.build` always inserts a database into the URL — a self-imposed constraint that makes the plugin less flexible than DBeaver for these engines. (PostgreSQL is the exception: its driver genuinely requires a database in the URL.)

The redesign borrows DBeaver's approach: bootstrap a connection, list databases, let the user pick from a populated dropdown, gate Save behind a successful test.

## Goals

1. **Catch wrong-database errors at config time, not query time.** The user cannot save a profile that has never successfully connected.
2. **Eliminate typos in the database name.** The user picks from a discovered list; they no longer type the database name.
3. **Match DBeaver's UX** for users who already know that workflow.
4. **Keep the persisted profile schema unchanged.** No data migration. Existing profiles continue to work without modification.
5. **Preserve the agent's per-query convenience.** The agent can still call `db_query(profile="qa", sql=…)` without passing a `database` parameter; it falls back to the user's chosen default exactly as it does today.

## Non-goals

- Changing the persisted `DatabaseProfile` data class.
- Adding a custom-bootstrap-database UI for environments where PostgreSQL's `postgres` database isn't accessible. (The existing "Use raw JDBC URL" escape hatch covers this for v1.)
- Refactoring `JdbcUrlBuilder.build` to support bare-server URLs for MySQL/SQL Server. The redesign uses bare URLs *only during the test connection*; saved profiles still get a default database. A future redesign could lift this restriction, but it's out of scope here.
- Caching discovered database lists. Each click of "Test Connection" issues a fresh discovery query.
- Supporting Test Connection in raw URL mode. Raw mode skips discovery entirely; the test only verifies that the URL prefix is valid and the driver can open the connection.

## Design

### Dialog state machine

The dialog has three states for the **OK button enable flag**, depending on engine and lifecycle:

| State | When | OK enabled? | Dropdown enabled? |
|---|---|---|---|
| **Untested (new profile)** | User just opened "Add" or changed any field after a previous successful test | No | No |
| **Test passed (new profile)** | User clicked Test, server engine, discovery succeeded | Yes | Yes, populated with discovered databases |
| **Grandfathered (edit existing)** | User opened an existing saved profile | Yes (assumed-good) | Yes, pre-seeded with the saved `defaultDatabase` as the only entry until user re-tests |

For SQLite and Generic profiles there is no dropdown (they have no listable databases); the dialog uses **Untested** vs **Test passed** based on whether the file or URL opens successfully.

### Field changes

Current `DatabaseProfileDialog` fields → new fields:

```diff
- private val defaultDbField = JBTextField(existing?.resolvedDefaultDatabase ?: "")
+ private val defaultDbCombo: ComboBox<String> = ComboBox<String>().apply {
+     // Initial enablement depends on grandfathering: existing profiles open enabled
+     // (with the saved DB pre-seeded), new profiles open disabled until Test succeeds.
+     val seed = existing?.resolvedDefaultDatabase?.takeIf { it.isNotBlank() }
+     if (seed != null) {
+         addItem(seed)
+         selectedItem = seed
+         isEnabled = true
+     } else {
+         isEnabled = false
+     }
+ }
+
+ private val testButton = JButton("Test Connection")
+ private val testStatusLabel = JBLabel("").apply {
+     foreground = JBColor.GRAY
+ }
+
+ // Lifecycle tracking
+ private var testPassed: Boolean = (existing != null)  // grandfather edits
+ private var discoveredDatabases: List<String> = emptyList()
```

The new `Test Connection` button and status label live in their own row, immediately below the `Default database:` row. Clicking the button:

1. Validates the minimum fields needed to connect (host, port, user, password). If any are missing, sets a red error message in `testStatusLabel` and returns.
2. Disables the button, sets `testStatusLabel` text to "Testing..." in gray.
3. Launches a coroutine in a UI-scoped `CoroutineScope` that calls `DatabaseConnectionManager.testConnectionAndDiscover` on `Dispatchers.IO` (see Threading section below).
4. On success: re-enables the button, sets the status label to green "✓ Connected. Found N database(s).", populates the dropdown via `setEnabled(true) + removeAllItems() + addItem` for each discovered DB, sets `testPassed = true`, refreshes `okAction.isEnabled`.
5. On failure: re-enables the button, sets the status label to red with the driver's error message (truncated to ~200 chars), `testPassed` stays false.

Any subsequent edit to host, port, username, password, type, or raw URL mode resets `testPassed = false`, clears the status label, disables the dropdown, and disables OK. This is wired via document listeners on the relevant text fields and an action listener on the type combo.

### Per-engine bootstrap and discovery

A new method on `DatabaseConnectionManager`:

```kotlin
data class DiscoveryResult(
    val databases: List<String>,
    val systemDatabasesFiltered: Int  // for the success label
)

suspend fun testConnectionAndDiscover(
    dbType: DbType,
    host: String,
    port: Int,
    username: String,
    password: String,
): Result<DiscoveryResult>
```

Implementation:

| Engine | Bootstrap URL | Discovery query | System DBs filtered |
|---|---|---|---|
| **PostgreSQL** | `jdbc:postgresql://$host:$port/postgres` | `SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname` | `template0`, `template1` (already excluded by `WHERE datistemplate = false`) |
| **MySQL** | `jdbc:mysql://$host:$port/` (bare) | `SHOW DATABASES` | `mysql`, `sys`, `information_schema`, `performance_schema` filtered in Kotlin after the query |
| **SQL Server** | `jdbc:sqlserver://$host:$port` (bare) | `SELECT name FROM sys.databases WHERE database_id > 4 ORDER BY name` | system DBs already excluded by `database_id > 4` |
| **SQLite** | open the user's raw URL via the SQLite driver | n/a — no `SELECT` required, opening the connection is the test | n/a — dropdown stays hidden |
| **Generic** | open the user's raw URL via the configured driver | n/a — opening is the test | n/a — dropdown stays hidden |

For SQLite/Generic, `testConnectionAndDiscover` returns `DiscoveryResult(emptyList(), 0)` and the dialog reads `dbType` to know not to enable the dropdown.

For PostgreSQL specifically, the bootstrap to `/postgres` is the documented DBeaver approach. If the corp environment has dropped or locked the `postgres` database, the test fails with the driver's error verbatim. The status label additionally appends a one-line hint:

> *Tip: if your environment doesn't expose the `postgres` database, use the "Use raw JDBC URL" escape hatch with a database you can access.*

This hint is only shown when `dbType == POSTGRESQL` AND the error message contains `postgres` AND `does not exist` (case-insensitive). It points users at the existing `rawUrlCheckbox`, which already supports the workaround — no new UI needed.

### Validation changes

`doValidate` updates:

```diff
- if (defaultDbField.text.isBlank())
-     return ValidationInfo("Default database is required", defaultDbField)
+ if (defaultDbCombo.selectedItem == null || (defaultDbCombo.selectedItem as String).isBlank())
+     return ValidationInfo("Select a database from the list (click Test Connection first)", defaultDbCombo)
```

A new top-level guard on the OK action enables/disables it based on `testPassed`. The dialog overrides `getOKAction()` to wire this up after `init()`. Validation messages still appear via `doValidate` for the missing-field cases.

### Persistence

`DatabaseProfile.kt` is **unchanged**. The dialog reads `defaultDbCombo.selectedItem as String` in `buildProfile()` and writes it into `defaultDatabase` exactly as the current code reads `defaultDbField.text`. No schema change, no migration.

The agent's query path is unchanged: `db_query(profile=…)` falls back to `profile.resolvedDefaultDatabase`, which is now guaranteed to be a real database name on the server (not a typo).

### Migration of existing profiles

When the user opens an existing profile in the dialog:

1. `testPassed` is initialised to `true` (line: `private var testPassed: Boolean = (existing != null)`).
2. The dropdown is pre-seeded with the saved `existing.resolvedDefaultDatabase` as a single entry, selected.
3. The Test Connection button is still functional. If the user clicks it:
   - On success, the dropdown is repopulated with the full discovered list. The saved default stays selected if it still exists; otherwise the first discovered database is selected and a yellow warning appears: "Saved default database '<name>' no longer exists on the server. Selected '<new>' instead."
   - On failure, the dropdown stays as-is, the status label shows the error, but `testPassed` remains true (the user can still save without re-testing).
4. Editing host/port/credentials on an existing profile **does** flip `testPassed` to false — once a connection-affecting field changes, the user must re-test before saving.

### Threading

`DatabaseConnectionManager.testConnectionAndDiscover` is a `suspend` function that switches to `Dispatchers.IO` internally via `withContext(Dispatchers.IO)`, matching the existing `withConnection` pattern. The dialog calls it from a coroutine launched in a UI-scoped `CoroutineScope` (created in `init` and disposed in `dispose()`). All UI state mutations (button enable/disable, label text, dropdown population) happen inside `withContext(Dispatchers.EDT) { ... }` blocks back in the dialog. The IntelliJ `ProgressManager.run(Backgroundable)` API is intentionally not used here — the result is consumed inline by the dialog, and the coroutine approach gives cleaner cancellation if the dialog is closed mid-test.

The test connection uses a short timeout (10 seconds connect, 10 seconds read) — these are baked into the new method and do not use the per-profile JDBC connect properties, since the user hasn't saved the profile yet. Hardcoded constants in `DatabaseConnectionManager`.

### Testing strategy

Unit tests (no IDE fixture needed) for the new `DatabaseConnectionManager.testConnectionAndDiscover` logic:

1. **PostgreSQL discovery query is correct** — assert the SQL string.
2. **MySQL system database filter** — given a fake `SHOW DATABASES` result containing `mysql, sys, information_schema, performance_schema, app_db`, assert the returned list is `[app_db]`.
3. **SQL Server discovery query is correct** — assert the SQL includes `database_id > 4`.
4. **SQLite test = file open** — given a temp file with valid SQLite header, returns `Result.success(DiscoveryResult(emptyList(), 0))`.
5. **Generic test = URL open** — given a malformed URL, returns `Result.failure` with the driver's error.

For the dialog, since `DatabaseProfileDialog` extends `DialogWrapper` and exercising it requires a JCEF/Swing fixture, dialog-level testing is **out of scope** for the initial implementation. Manual testing matrix:

- New PostgreSQL profile, valid → Test → discover → save
- New PostgreSQL profile, wrong password → Test → red error
- New PostgreSQL profile, host unreachable → Test → timeout error
- New MySQL profile, valid → Test → discover → save (verify system DBs filtered)
- New SQL Server profile, valid → Test → discover → save
- New SQLite profile, valid file → Test → green check → save
- New SQLite profile, invalid file → Test → red error
- Edit existing profile, no changes → OK is enabled (grandfathered)
- Edit existing profile, change host → OK disables, Test required
- Edit existing profile, click Test, server has lost old default → yellow warning, new default selected
- PostgreSQL bootstrap fails (postgres DB unavailable) → red error + escape hatch hint shown
- Switch to raw URL mode → dropdown hidden, Test still works (just opens URL)

## Risks

- **Bootstrap PostgreSQL database not always available.** Mitigated by the inline hint pointing at the existing raw URL escape hatch. The risk is a UX cost (one extra click for affected users), not a correctness issue.
- **Discovery queries may be slow on large MySQL servers.** `SHOW DATABASES` is O(N) where N is the database count. For typical N < 1000 this is sub-second. Hard timeout of 10 seconds caps the worst case.
- **Threading bugs** — the dialog must keep all UI mutations on EDT. Mitigated by funneling all UI updates through `withContext(Dispatchers.EDT) { ... }` blocks.
- **Existing profiles with stale `defaultDatabase`** — if a saved profile's database has been dropped on the server since the profile was created, agent queries will fail at run time. The redesign doesn't fix this for already-saved profiles unless the user re-tests, but it does *prevent the same problem from being introduced for new profiles*. This is acceptable.

## Out of scope

The following are intentionally deferred. Listed here so they're explicit, not lost.

- **Custom bootstrap database for PostgreSQL** — let the user specify which database to bootstrap with, instead of hardcoding `postgres`. Would handle environments where `postgres` is locked.
- **Bare-URL support for MySQL/SQL Server in saved profiles** — let the user save a profile with no default database, requiring the agent to pass `database=…` on every query. Would more closely match DBeaver's "server-level connection" model.
- **Cached discovered database lists** — show the last-known list when re-opening a saved profile, refresh on demand.
- **Auto-test on field blur** — currently the user must click Test explicitly. Could auto-test after a debounce.
- **Connection pooling for the test connection** — currently each test opens and closes a fresh connection. Pooling would reduce latency for repeated tests but adds complexity.
