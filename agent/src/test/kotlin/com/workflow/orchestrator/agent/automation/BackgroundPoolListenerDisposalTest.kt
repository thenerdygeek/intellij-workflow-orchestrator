package com.workflow.orchestrator.agent.automation

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent
import com.workflow.orchestrator.agent.tools.background.BackgroundPool
import com.workflow.orchestrator.agent.tools.background.BackgroundState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BackgroundPoolListenerDisposalTest {

    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
    private lateinit var pool: BackgroundPool
    private lateinit var poolScope: CoroutineScope

    @BeforeEach
    fun setUp() {
        mockkObject(AgentSettings.Companion)
        every { AgentSettings.getInstance(any()) } returns mockk {
            every { state } returns mockk {
                every { concurrentBackgroundProcessesPerSession } returns 5
            }
        }
        poolScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        pool = BackgroundPool(project, poolScope)
    }

    @AfterEach
    fun tearDown() {
        pool.stopSupervisor()
        poolScope.cancel()
        unmockkObject(AgentSettings.Companion)
    }

    @Test
    fun `disposing the returned Disposable detaches the listener`() {
        var fired = 0
        val disposable: Disposable = pool.addCompletionListener { fired++ }
        pool.notifyCompletionForTest(SAMPLE_EVENT)
        assertEquals(1, fired)
        Disposer.dispose(disposable)
        pool.notifyCompletionForTest(SAMPLE_EVENT)
        assertEquals(1, fired) // unchanged — listener detached
    }

    companion object {
        private val SAMPLE_EVENT = BackgroundCompletionEvent(
            bgId = "bg-1",
            kind = "test",
            label = "test process",
            sessionId = "s1",
            exitCode = 0,
            state = BackgroundState.EXITED,
            runtimeMs = 1000L,
            tailContent = "",
            spillPath = null,
            occurredAt = System.currentTimeMillis(),
        )
    }
}
