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
    fun `saveQueueEntry and getActiveQueueEntries round-trip`() {
        val entry = QueueEntry(
            id = "q-1", suitePlanKey = "PROJ-AUTO",
            dockerTagsPayload = """{"auth":"2.4.0"}""",
            variables = mapOf("suiteType" to "regression"),
            stages = listOf("QA Automation"),
            enqueuedAt = Instant.now(),
            status = QueueEntryStatus.WAITING_LOCAL, bambooResultKey = null
        )
        service.saveQueueEntry(entry, sequenceOrder = 1)

        val active = service.getActiveQueueEntries()
        assertEquals(1, active.size)
        assertEquals("q-1", active[0].id)
        assertEquals(QueueEntryStatus.WAITING_LOCAL, active[0].status)
    }

    @Test
    fun `updateQueueEntryStatus changes status and result key`() {
        val entry = QueueEntry(
            id = "q-1", suitePlanKey = "PROJ-AUTO",
            dockerTagsPayload = "{}", variables = emptyMap(),
            stages = emptyList(), enqueuedAt = Instant.now(),
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
            QueueEntry("q-1", "P", "{}", emptyMap(), emptyList(), Instant.now(), QueueEntryStatus.WAITING_LOCAL, null),
            sequenceOrder = 1
        )
        service.saveQueueEntry(
            QueueEntry("q-2", "P", "{}", emptyMap(), emptyList(), Instant.now(), QueueEntryStatus.COMPLETED, null),
            sequenceOrder = 2
        )
        service.saveQueueEntry(
            QueueEntry("q-3", "P", "{}", emptyMap(), emptyList(), Instant.now(), QueueEntryStatus.CANCELLED, null),
            sequenceOrder = 3
        )

        val active = service.getActiveQueueEntries()
        assertEquals(1, active.size)
        assertEquals("q-1", active[0].id)
    }

    @Test
    fun `deleteQueueEntry removes entry`() {
        service.saveQueueEntry(
            QueueEntry("q-1", "P", "{}", emptyMap(), emptyList(), Instant.now(), QueueEntryStatus.WAITING_LOCAL, null),
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
            QueueEntry("q-1", "P", "{}", emptyMap(), emptyList(), Instant.now(), QueueEntryStatus.WAITING_LOCAL, null),
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
