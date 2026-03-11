package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.model.HistoryEntry
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
    fun `saveHistory and getHistory round-trip`() {
        val entry = HistoryEntry(
            id = "hist-1", suitePlanKey = "PROJ-AUTO",
            dockerTagsJson = """{"auth":"2.4.0"}""",
            variables = mapOf("suiteType" to "regression"),
            stages = listOf("QA Automation"),
            triggeredAt = Instant.now(),
            buildResultKey = "PROJ-AUTO-847", buildPassed = true
        )
        service.saveHistory(entry)

        val history = service.getHistory("PROJ-AUTO")
        assertEquals(1, history.size)
        assertEquals("hist-1", history[0].id)
        assertEquals("regression", history[0].variables["suiteType"])
        assertTrue(history[0].buildPassed!!)
    }

    @Test
    fun `getHistory limits to 5 entries per suite`() {
        for (i in 1..8) {
            service.saveHistory(
                HistoryEntry(
                    id = "hist-$i", suitePlanKey = "PROJ-AUTO",
                    dockerTagsJson = """{"auth":"$i.0.0"}""",
                    variables = emptyMap(), stages = emptyList(),
                    triggeredAt = Instant.ofEpochSecond(i.toLong() * 1000),
                    buildResultKey = null, buildPassed = null
                )
            )
        }

        val history = service.getHistory("PROJ-AUTO", limit = 5)
        assertEquals(5, history.size)
        assertEquals("hist-8", history[0].id)
    }

    @Test
    fun `getHistory separates suites`() {
        service.saveHistory(
            HistoryEntry("h1", "SUITE-A", "{}", emptyMap(), emptyList(), Instant.now(), null, null)
        )
        service.saveHistory(
            HistoryEntry("h2", "SUITE-B", "{}", emptyMap(), emptyList(), Instant.now(), null, null)
        )

        assertEquals(1, service.getHistory("SUITE-A").size)
        assertEquals(1, service.getHistory("SUITE-B").size)
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
    fun `loadAsBaseline returns tag map from history entry`() {
        service.saveHistory(
            HistoryEntry(
                "h1", "PROJ-AUTO",
                """{"auth":"2.4.0","payments":"2.3.1"}""",
                emptyMap(), emptyList(), Instant.now(), null, null
            )
        )

        val tags = service.loadAsBaseline("h1")
        assertEquals(2, tags.size)
        assertEquals("2.4.0", tags["auth"])
        assertEquals("2.3.1", tags["payments"])
    }

    @Test
    fun `loadAsBaseline returns empty map for unknown entry`() {
        assertTrue(service.loadAsBaseline("unknown").isEmpty())
    }

    @Test
    fun `database integrity check passes on fresh DB`() {
        assertTrue(service.integrityCheck())
    }
}
