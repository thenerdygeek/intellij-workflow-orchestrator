package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class TagHistoryServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var service: TagHistoryService

    @BeforeEach
    fun setUp() {
        service = TagHistoryService(tempDir.resolve("automation.db").toString())
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    @Test
    fun `saveQueueEntry and getActiveQueueEntries round-trip with non-null stages`() {
        val entry = QueueEntry(
            id = "q-1", suitePlanKey = "PROJ-AUTO",
            dockerTagsPayload = """{"auth":"2.4.0"}""",
            variables = mapOf("suiteType" to "regression"),
            stages = setOf("Build", "Unit Tests"),
            enqueuedAt = Instant.now(),
            status = QueueEntryStatus.WAITING_LOCAL, bambooResultKey = null
        )
        service.saveQueueEntry(entry, sequenceOrder = 1)

        val active = service.getActiveQueueEntries()
        assertEquals(1, active.size)
        assertEquals("q-1", active[0].id)
        assertEquals(QueueEntryStatus.WAITING_LOCAL, active[0].status)
        assertEquals(setOf("Build", "Unit Tests"), active[0].stages)
    }

    @Test
    fun `saveQueueEntry with null stages round-trips as null`() {
        val entry = QueueEntry(
            id = "q-null", suitePlanKey = "PROJ-AUTO",
            dockerTagsPayload = """{}""",
            variables = emptyMap(),
            stages = null,  // run all stages
            enqueuedAt = Instant.now(),
            status = QueueEntryStatus.WAITING_LOCAL, bambooResultKey = null
        )
        service.saveQueueEntry(entry, sequenceOrder = 1)

        val active = service.getActiveQueueEntries()
        assertEquals(1, active.size)
        assertNull(active[0].stages, "null stages (run all) must survive persistence round-trip")
    }

    @Test
    fun `legacy empty stages_json column rehydrates as null for backward compatibility`() {
        // Simulate an old persisted row where stages_json was stored as `[]` (old List<String>
        // serialization of "all stages") by directly writing to the database via the raw JDBC path.
        // IMPORTANT: force the lazy connection init (schema creation) BEFORE opening a raw JDBC
        // connection to the same file — otherwise the raw INSERT fails with "no such table".
        service.getActiveQueueEntries()

        val dbPath = tempDir.resolve("automation.db").toString()
        Class.forName("org.sqlite.JDBC")
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.prepareStatement("""
            INSERT OR REPLACE INTO queue_entries
            (id, suite_plan_key, docker_tags_json, variables_json, stages_json,
             status, bamboo_result_key, enqueued_at, sequence_order, updated_at, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).use { stmt ->
            stmt.setString(1, "q-legacy")
            stmt.setString(2, "PROJ-LEGACY")
            stmt.setString(3, "{}")
            stmt.setString(4, "{}")
            stmt.setString(5, "[]")  // old format: empty array = "run all stages"
            stmt.setString(6, "WAITING_LOCAL")
            stmt.setNull(7, java.sql.Types.VARCHAR)
            stmt.setLong(8, java.time.Instant.now().epochSecond)
            stmt.setInt(9, 99)
            stmt.setLong(10, java.time.Instant.now().epochSecond)
            stmt.setNull(11, java.sql.Types.VARCHAR)
            stmt.executeUpdate()
        }
        conn.close()

        val active = service.getActiveQueueEntries()
        val legacy = active.firstOrNull { it.id == "q-legacy" }
        assertNotNull(legacy, "Legacy entry must be loaded from DB")
        assertNull(legacy!!.stages,
            "Old empty-array stages_json `[]` must rehydrate as null (run all stages), not an empty set")
    }

    @Test
    fun `updateQueueEntryStatus changes status and result key`() {
        val entry = QueueEntry(
            id = "q-1", suitePlanKey = "PROJ-AUTO",
            dockerTagsPayload = "{}", variables = emptyMap(),
            stages = null, enqueuedAt = Instant.now(),
            status = QueueEntryStatus.WAITING_LOCAL, bambooResultKey = null
        )
        service.saveQueueEntry(entry, sequenceOrder = 1)

        service.updateQueueEntryStatus("q-1", QueueEntryStatus.RUNNING, bambooResultKey = "PROJ-AUTO-849")

        val active = service.getActiveQueueEntries()
        assertEquals(1, active.size)
        assertEquals(QueueEntryStatus.RUNNING, active[0].status)
        assertEquals("PROJ-AUTO-849", active[0].bambooResultKey)
    }

    @Test
    fun `getActiveQueueEntries excludes terminal statuses`() {
        service.saveQueueEntry(
            QueueEntry("q-1", "P", "{}", emptyMap(), null, Instant.now(), QueueEntryStatus.WAITING_LOCAL, null),
            sequenceOrder = 1
        )
        service.saveQueueEntry(
            QueueEntry("q-2", "P", "{}", emptyMap(), null, Instant.now(), QueueEntryStatus.COMPLETED, null),
            sequenceOrder = 2
        )
        service.saveQueueEntry(
            QueueEntry("q-3", "P", "{}", emptyMap(), null, Instant.now(), QueueEntryStatus.CANCELLED, null),
            sequenceOrder = 3
        )

        val active = service.getActiveQueueEntries()
        assertEquals(1, active.size)
        assertEquals("q-1", active[0].id)
    }

    @Test
    fun `getActiveQueueEntries excludes every terminal status from QueueEntryStatus_TERMINAL`() {
        // Regression for the persistence ↔ memory contract drift: the SQL
        // exclusion list MUST be aligned with QueueEntryStatus.TERMINAL.  Pre-fix
        // the WHERE clause only excluded COMPLETED + CANCELLED, so FAILED /
        // FAILED_TO_TRIGGER / TAG_INVALID rows resurrected as "active" on every
        // IDE restart — contradicting the documented "terminal entries are dropped
        // by getActiveQueueEntries() on restart" contract in :automation CLAUDE.md.

        // One live entry that MUST come back.
        service.saveQueueEntry(
            QueueEntry("live-1", "P", "{}", emptyMap(), null, Instant.now(),
                QueueEntryStatus.WAITING_LOCAL, null),
            sequenceOrder = 1
        )

        // One persisted row per terminal status — every single one of these must be filtered out.
        @Suppress("DEPRECATION")
        val terminalStatuses = listOf(
            QueueEntryStatus.COMPLETED,
            QueueEntryStatus.FAILED,
            QueueEntryStatus.CANCELLED,
            QueueEntryStatus.FAILED_TO_TRIGGER,
            QueueEntryStatus.TAG_INVALID
        )
        terminalStatuses.forEachIndexed { idx, status ->
            service.saveQueueEntry(
                QueueEntry("term-${status.name}", "P", "{}", emptyMap(), null,
                    Instant.now(), status, null),
                sequenceOrder = idx + 2
            )
        }

        val active = service.getActiveQueueEntries()
        assertEquals(1, active.size,
            "Only the WAITING_LOCAL row should be returned; got ${active.map { it.id to it.status }}")
        assertEquals("live-1", active[0].id)
    }

    @Test
    fun `deleteQueueEntry removes entry`() {
        service.saveQueueEntry(
            QueueEntry("q-1", "P", "{}", emptyMap(), null, Instant.now(), QueueEntryStatus.WAITING_LOCAL, null),
            sequenceOrder = 1
        )

        service.deleteQueueEntry("q-1")

        assertTrue(service.getActiveQueueEntries().isEmpty())
    }

    @Test
    fun `database integrity check passes on fresh DB`() {
        assertTrue(service.integrityCheck())
    }

    @Test
    fun `dispose closes underlying connection (A-P1-5)`() {
        // Force lazy connection initialization.
        service.saveQueueEntry(
            QueueEntry("q-1", "P", "{}", emptyMap(), null, Instant.now(), QueueEntryStatus.WAITING_LOCAL, null),
            sequenceOrder = 1
        )
        // Reading any active state confirms the conn is live.
        assertEquals(1, service.getActiveQueueEntries().size)

        service.dispose()

        // After dispose, integrityCheck on the closed connection throws SQLException —
        // catching it is the only safe assertion since prepareStatement on a closed
        // connection is itself the leak we wanted to prove was fixed.
        assertThrows(java.sql.SQLException::class.java) {
            service.integrityCheck()
        }
    }
}
