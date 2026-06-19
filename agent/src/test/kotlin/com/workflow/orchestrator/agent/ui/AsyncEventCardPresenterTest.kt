package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.monitor.Severity
import com.workflow.orchestrator.agent.session.AsyncEventKind
import com.workflow.orchestrator.agent.session.AsyncEventStatus
import com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent
import com.workflow.orchestrator.agent.tools.background.BackgroundState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsyncEventCardPresenterTest {
    private fun bg(exit: Int, state: BackgroundState) = BackgroundCompletionEvent(
        bgId = "bg7", kind = "command", label = "npm run build", sessionId = "s1",
        exitCode = exit, state = state, runtimeMs = 12_400, tailContent = "line1\nDone in 12s",
        spillPath = null, occurredAt = 100L,
    )

    @Test fun `background exit 0 EXITED → SUCCESS`() {
        val c = AsyncEventCardPresenter.fromBackground(bg(0, BackgroundState.EXITED))
        assertEquals(AsyncEventKind.BACKGROUND, c.kind)
        assertEquals(AsyncEventStatus.SUCCESS, c.status)
        assertEquals("bg-bg7-100", c.id)
        assertEquals("bg7", c.sourceId)
        assertTrue(c.summary.contains("exit 0"))
        assertTrue(c.details.contains("Done in 12s"))
    }

    @Test fun `background non-zero exit → FAILURE`() {
        assertEquals(AsyncEventStatus.FAILURE, AsyncEventPresenterStatus(bg(1, BackgroundState.EXITED)))
    }

    @Test fun `background KILLED → FAILURE`() {
        assertEquals(AsyncEventStatus.FAILURE, AsyncEventPresenterStatus(bg(0, BackgroundState.KILLED)))
    }

    @Test fun `monitor ALERT severity → ALERT status`() {
        val c = AsyncEventCardPresenter.fromMonitor("m1", Severity.ALERT, "boom", 50L)
        assertEquals(AsyncEventKind.MONITOR, c.kind)
        assertEquals(AsyncEventStatus.ALERT, c.status)
        assertEquals("mon-m1-50", c.id)
        assertEquals("boom", c.details)
    }

    @Test fun `monitor NOTABLE severity → NOTABLE status`() {
        assertEquals(AsyncEventStatus.NOTABLE, AsyncEventCardPresenter.fromMonitor("m1", Severity.NOTABLE, "x", 1L).status)
    }

    private fun AsyncEventPresenterStatus(e: BackgroundCompletionEvent) =
        AsyncEventCardPresenter.fromBackground(e).status
}
