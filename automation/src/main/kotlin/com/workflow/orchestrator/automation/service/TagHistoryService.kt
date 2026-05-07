package com.workflow.orchestrator.automation.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

@Service(Service.Level.PROJECT)
class TagHistoryService : Disposable {

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

    fun saveQueueEntry(entry: QueueEntry, sequenceOrder: Int) {
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
            stmt.setString(5, json.encodeToString(entry.stages))
            stmt.setString(6, entry.status.name)
            stmt.setString(7, entry.bambooResultKey)
            stmt.setLong(8, entry.enqueuedAt.epochSecond)
            stmt.setInt(9, sequenceOrder)
            stmt.setLong(10, Instant.now().epochSecond)
            stmt.setString(11, entry.errorMessage)
            stmt.executeUpdate()
        }
    }

    fun updateQueueEntryStatus(
        entryId: String,
        status: QueueEntryStatus,
        bambooResultKey: String? = null,
        errorMessage: String? = null
    ) {
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

    fun getActiveQueueEntries(): List<QueueEntry> {
        return connection.prepareStatement("""
            SELECT * FROM queue_entries
            WHERE status NOT IN (?, ?)
            ORDER BY sequence_order ASC
        """).use { stmt ->
            stmt.setString(1, QueueEntryStatus.COMPLETED.name)
            stmt.setString(2, QueueEntryStatus.CANCELLED.name)
            val rs = stmt.executeQuery()
            val results = mutableListOf<QueueEntry>()
            while (rs.next()) {
                results.add(
                    QueueEntry(
                        id = rs.getString("id"),
                        suitePlanKey = rs.getString("suite_plan_key"),
                        dockerTagsPayload = rs.getString("docker_tags_json"),
                        variables = json.decodeFromString(rs.getString("variables_json")),
                        stages = json.decodeFromString(rs.getString("stages_json")),
                        enqueuedAt = Instant.ofEpochSecond(rs.getLong("enqueued_at")),
                        status = QueueEntryStatus.valueOf(rs.getString("status")),
                        bambooResultKey = rs.getString("bamboo_result_key"),
                        errorMessage = rs.getString("error_message")
                    )
                )
            }
            results
        }
    }

    fun deleteQueueEntry(entryId: String) {
        connection.prepareStatement("DELETE FROM queue_entries WHERE id = ?").use { stmt ->
            stmt.setString(1, entryId)
            stmt.executeUpdate()
        }
    }

    fun integrityCheck(): Boolean {
        return connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA quick_check")
            rs.next() && rs.getString(1) == "ok"
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
