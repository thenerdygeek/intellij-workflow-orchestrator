package com.workflow.orchestrator.automation.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

@Service(Service.Level.PROJECT)
class TagHistoryService : Disposable {

    private val log = Logger.getInstance(TagHistoryService::class.java)

    private val dbPath: String

    /** Project service constructor — used by IntelliJ DI. */
    constructor(project: com.intellij.openapi.project.Project) {
        this.dbPath = File(project.basePath ?: ".", ".idea${File.separator}workflow-orchestrator${File.separator}automation.db").path
    }

    /** Test constructor — allows injecting explicit path. */
    constructor(dbPath: String) {
        this.dbPath = dbPath
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var connectionInitialized = false

    private val connection: Connection by lazy {
        val parentDir = File(dbPath).parentFile
        parentDir?.mkdirs()
        // Explicitly load the SQLite JDBC driver — IntelliJ's plugin classloader
        // isolates the driver JAR from DriverManager's system classloader SPI scan.
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA journal_mode = WAL").close()
        }
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA busy_timeout = 5000")
        }
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA wal_autocheckpoint = 1000")
        }
        initSchema(conn)
        connectionInitialized = true
        conn
    }

    /**
     * D4 (audit finding automation:F-1): All JDBC operations must run under this Mutex.
     *
     * SQLite's JDBC driver is NOT thread-safe — sharing a single [Connection] across
     * coroutines without synchronization causes silent data corruption (wrong row counts,
     * interleaved statements, torn reads). WAL mode reduces blocking but does NOT make
     * the JDBC layer safe for concurrent access from multiple threads/coroutines.
     *
     * Every public method wraps its JDBC work in
     * `withContext(Dispatchers.IO) { dbMutex.withLock { ... } }`.
     * All callers (QueueService, QueueRecoveryStartupActivity) already run in
     * suspend contexts on Dispatchers.IO, so the `suspend` keyword propagates cleanly.
     */
    private val dbMutex = Mutex()

    private fun initSchema(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS schema_metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """)
            stmt.executeUpdate("""
                INSERT OR IGNORE INTO schema_metadata VALUES ('schema_version', '1')
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS queue_entries (
                    id TEXT PRIMARY KEY,
                    suite_plan_key TEXT NOT NULL,
                    docker_tags_json TEXT NOT NULL,
                    variables_json TEXT NOT NULL,
                    stages_json TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'WAITING_LOCAL',
                    bamboo_result_key TEXT,
                    enqueued_at INTEGER NOT NULL,
                    sequence_order INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    error_message TEXT
                )
            """)
            stmt.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_queue_suite ON queue_entries(suite_plan_key, sequence_order)
            """)
            stmt.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_queue_status ON queue_entries(status)
            """)
        }
    }

    suspend fun saveQueueEntry(entry: QueueEntry, sequenceOrder: Int) {
        withContext(Dispatchers.IO) {
            dbMutex.withLock {
                connection.prepareStatement("""
                    INSERT OR REPLACE INTO queue_entries
                    (id, suite_plan_key, docker_tags_json, variables_json, stages_json,
                     status, bamboo_result_key, enqueued_at, sequence_order, updated_at, error_message)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).use { stmt ->
                    stmt.setString(1, entry.id)
                    stmt.setString(2, entry.suitePlanKey)
                    stmt.setString(3, entry.dockerTagsPayload)
                    stmt.setString(4, json.encodeToString(entry.variables))
                    stmt.setString(5, encodeStages(entry.stages))
                    stmt.setString(6, entry.status.name)
                    stmt.setString(7, entry.bambooResultKey)
                    stmt.setLong(8, entry.enqueuedAt.epochSecond)
                    stmt.setInt(9, sequenceOrder)
                    stmt.setLong(10, Instant.now().epochSecond)
                    stmt.setString(11, entry.errorMessage)
                    stmt.executeUpdate()
                }
            }
        }
    }

    suspend fun updateQueueEntryStatus(
        entryId: String,
        status: QueueEntryStatus,
        bambooResultKey: String? = null,
        errorMessage: String? = null
    ) {
        withContext(Dispatchers.IO) {
            dbMutex.withLock {
                if (bambooResultKey != null) {
                    connection.prepareStatement("""
                        UPDATE queue_entries SET status = ?, bamboo_result_key = ?, updated_at = ?, error_message = ?
                        WHERE id = ?
                    """).use { stmt ->
                        stmt.setString(1, status.name)
                        stmt.setString(2, bambooResultKey)
                        stmt.setLong(3, Instant.now().epochSecond)
                        stmt.setString(4, errorMessage)
                        stmt.setString(5, entryId)
                        stmt.executeUpdate()
                    }
                } else {
                    connection.prepareStatement("""
                        UPDATE queue_entries SET status = ?, updated_at = ?, error_message = ?
                        WHERE id = ?
                    """).use { stmt ->
                        stmt.setString(1, status.name)
                        stmt.setLong(2, Instant.now().epochSecond)
                        stmt.setString(3, errorMessage)
                        stmt.setString(4, entryId)
                        stmt.executeUpdate()
                    }
                }
            }
        }
    }

    suspend fun getActiveQueueEntries(): List<QueueEntry> {
        // Drive the exclusion list off the canonical [QueueEntryStatus.TERMINAL] set so
        // FAILED / FAILED_TO_TRIGGER / TAG_INVALID rows don't leak back as "active" on
        // IDE restart. Matches the contract documented in :automation CLAUDE.md
        // ("Across IDE restarts, terminal entries are dropped by getActiveQueueEntries()").
        val terminalStatuses = QueueEntryStatus.TERMINAL
        val placeholders = terminalStatuses.joinToString(",") { "?" }
        return withContext(Dispatchers.IO) {
            dbMutex.withLock {
                connection.prepareStatement("""
                    SELECT * FROM queue_entries
                    WHERE status NOT IN ($placeholders)
                    ORDER BY sequence_order ASC
                """).use { stmt ->
                    terminalStatuses.forEachIndexed { idx, status ->
                        stmt.setString(idx + 1, status.name)
                    }
                    // D4 (automation:F-2): wrap ResultSet in use{} to prevent leak on exception.
                    stmt.executeQuery().use { rs ->
                        val results = mutableListOf<QueueEntry>()
                        while (rs.next()) {
                            // D4 (automation:F-6): per-row recovery — a single corrupt row must
                            // not abort the entire restore. Log and skip bad rows.
                            try {
                                results.add(
                                    QueueEntry(
                                        id = rs.getString("id"),
                                        suitePlanKey = rs.getString("suite_plan_key"),
                                        dockerTagsPayload = rs.getString("docker_tags_json"),
                                        variables = json.decodeFromString(rs.getString("variables_json")),
                                        stages = decodeStages(rs.getString("stages_json")),
                                        enqueuedAt = Instant.ofEpochSecond(rs.getLong("enqueued_at")),
                                        status = QueueEntryStatus.valueOf(rs.getString("status")),
                                        bambooResultKey = rs.getString("bamboo_result_key"),
                                        errorMessage = rs.getString("error_message")
                                    )
                                )
                            } catch (e: Exception) {
                                val rowId = runCatching { rs.getString("id") }.getOrElse { "<unknown>" }
                                log.warn("[TagHistoryService] Skipping corrupt queue entry id=$rowId: ${e.message}", e)
                            }
                        }
                        results
                    }
                }
            }
        }
    }

    suspend fun deleteQueueEntry(entryId: String) {
        withContext(Dispatchers.IO) {
            dbMutex.withLock {
                connection.prepareStatement("DELETE FROM queue_entries WHERE id = ?").use { stmt ->
                    stmt.setString(1, entryId)
                    stmt.executeUpdate()
                }
            }
        }
    }

    /**
     * Serialize [stages] for the `stages_json` column.
     * - null → `null` (JSON null literal) so we can distinguish "run all" from old empty-array rows.
     * - non-null set → JSON array of stage name strings.
     */
    private fun encodeStages(stages: Set<String>?): String =
        if (stages == null) "null" else json.encodeToString(stages.toList())

    /**
     * Deserialize the `stages_json` column back into [Set<String>?].
     * - JSON null → null (run all stages).
     * - Empty JSON array `[]` → null (backward-compat: old rows stored `[]` for "all stages").
     * - Non-empty array → parsed set of stage names.
     * - Any parse error → null (fail-safe; treated as "run all").
     */
    private fun decodeStages(raw: String?): Set<String>? {
        if (raw.isNullOrBlank() || raw.trim() == "null") return null
        return try {
            val arr = json.parseToJsonElement(raw).jsonArray
            if (arr.isEmpty()) null else arr.map { it.jsonPrimitive.content }.toSet()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun integrityCheck(): Boolean {
        return withContext(Dispatchers.IO) {
            dbMutex.withLock {
                // D4 (automation:F-2): wrap ResultSet in use{} so it is closed on exception.
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA quick_check").use { rs ->
                        rs.next() && rs.getString(1) == "ok"
                    }
                }
            }
        }
    }

    fun close() {
        if (!connectionInitialized) return
        try { connection.close() } catch (_: Exception) {}
    }

    /**
     * Closes the SQLite connection on project close (A-P1-5).
     * IntelliJ's `Service.Level.PROJECT` lifecycle disposes services with the project,
     * so this releases the WAL/SHM file locks and prevents `database is locked` errors
     * when the same project is reopened in another IDE window.
     */
    override fun dispose() {
        close()
    }
}
