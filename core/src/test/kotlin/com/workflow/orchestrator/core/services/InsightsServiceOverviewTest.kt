package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.insights.SessionRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class InsightsServiceOverviewTest {

    @Test
    fun `getOverview loads the session file exactly once`() {
        val reader = mockk<SessionHistoryReader>()
        every { reader.loadSessions(any()) } returns emptyList()
        val service = InsightsServiceImpl(reader, File("/tmp/unused"))

        service.getOverview()

        verify(exactly = 1) { reader.loadSessions(any()) }
    }

    @Test
    fun `getOverview today and week windows filter correctly from one load`() {
        val now = System.currentTimeMillis()
        val today = sessionRecord(ts = now - 1_000) // 1s ago — valid even right after local midnight
        val thisWeek = sessionRecord(ts = now - 3L * 24 * 60 * 60 * 1000) // 3 days ago
        val ancient = sessionRecord(ts = now - 30L * 24 * 60 * 60 * 1000) // 30 days ago
        val reader = mockk<SessionHistoryReader>()
        every { reader.loadSessions(any()) } returns listOf(today, thisWeek, ancient)
        val service = InsightsServiceImpl(reader, File("/tmp/unused"))

        val overview = service.getOverview()

        assertEquals(1, overview.today.sessionCount)
        assertEquals(2, overview.week.sessionCount)
        assertEquals(3, overview.sessions.size)
        verify(exactly = 1) { reader.loadSessions(any()) }
    }

    // SessionRecord has: id: String, ts: Long, task: String, plus optional tokensIn/tokensOut/totalCost/modelId/isFavorited
    private fun sessionRecord(ts: Long): SessionRecord =
        SessionRecord(id = "test-id-$ts", ts = ts, task = "test task", tokensIn = 0, tokensOut = 0, totalCost = 0.0)
}
