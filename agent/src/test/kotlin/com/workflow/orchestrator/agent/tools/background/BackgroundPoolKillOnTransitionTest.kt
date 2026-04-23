package com.workflow.orchestrator.agent.tools.background

import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BackgroundPoolKillOnTransitionTest {

    private lateinit var project: Project
    private lateinit var pool: BackgroundPool

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        val settings = mockk<AgentSettings>(relaxed = true)
        val state = mockk<AgentSettings.State>(relaxed = true)
        every { state.concurrentBackgroundProcessesPerSession } returns 5
        every { settings.state } returns state
        mockkObject(AgentSettings.Companion)
        every { AgentSettings.getInstance(project) } returns settings
        pool = BackgroundPool(project)
    }

    @AfterEach
    fun tearDown() {
        pool.stopSupervisor()
        unmockkAll()
        ProcessRegistry.killAll()
    }

    @Test
    fun `killAll clears session pool and kills underlying process`() = runBlocking {
        val p = ProcessBuilder("sh", "-c", "sleep 60").start()
        val m = ProcessRegistry.register("bg_kt1", p, "sleep")
        val h = RunCommandBackgroundHandle("bg_kt1", "sess-kill", m, "sleep")
        pool.register("sess-kill", h)

        pool.killAll("sess-kill")

        assertTrue(pool.list("sess-kill").isEmpty(), "pool must be empty after killAll")
        p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(!p.isAlive, "underlying process must be killed")
    }
}
