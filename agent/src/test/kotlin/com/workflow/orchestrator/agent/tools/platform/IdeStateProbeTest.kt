package com.workflow.orchestrator.agent.tools.platform

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class IdeStateProbeTest {

    private val project = mockk<Project>(relaxed = true)
    private val mgr = mockk<XDebuggerManager>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkStatic(XDebuggerManager::class)
        every { XDebuggerManager.getInstance(project) } returns mgr
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(XDebuggerManager::class)
    }

    private fun fakeSession(name: String, suspended: Boolean): XDebugSession =
        mockk(relaxed = true) {
            every { sessionName } returns name
            every { isSuspended } returns suspended
        }

    @Test
    fun `NoSession when platform reports no sessions and no current session`() {
        every { mgr.debugSessions } returns emptyArray()
        every { mgr.currentSession } returns null

        assertEquals(DebugState.NoSession, IdeStateProbe.debugState(project))
    }

    @Test
    fun `Paused when platform currentSession is suspended`() {
        val s = fakeSession("Main", suspended = true)
        every { mgr.debugSessions } returns arrayOf(s)
        every { mgr.currentSession } returns s

        val result = IdeStateProbe.debugState(project)
        assertTrue(result is DebugState.Paused)
        assertSame(s, (result as DebugState.Paused).session)
    }

    @Test
    fun `Running when platform currentSession exists but is not suspended`() {
        val s = fakeSession("Main", suspended = false)
        every { mgr.debugSessions } returns arrayOf(s)
        every { mgr.currentSession } returns s

        val result = IdeStateProbe.debugState(project)
        assertTrue(result is DebugState.Running)
        assertSame(s, (result as DebugState.Running).session)
    }

    @Test
    fun `falls back to single session when currentSession is null`() {
        // Some IDE states report a debug session in `debugSessions` even though
        // `currentSession` is momentarily null (focus change, IDE restoring state).
        // With exactly one session present we resolve it unambiguously.
        val s = fakeSession("Main", suspended = true)
        every { mgr.debugSessions } returns arrayOf(s)
        every { mgr.currentSession } returns null

        val result = IdeStateProbe.debugState(project)
        assertTrue(result is DebugState.Paused)
        assertSame(s, (result as DebugState.Paused).session)
    }

    @Test
    fun `AmbiguousSession when multiple sessions and no disambiguator`() {
        val a = fakeSession("Worker A", suspended = true)
        val b = fakeSession("Worker B", suspended = false)
        every { mgr.debugSessions } returns arrayOf(a, b)
        every { mgr.currentSession } returns null

        val result = IdeStateProbe.debugState(project)
        assertTrue(result is DebugState.AmbiguousSession)
        val amb = result as DebugState.AmbiguousSession
        assertEquals(2, amb.count)
        assertEquals(listOf("Worker A", "Worker B"), amb.names)
    }

    @Test
    fun `sessionId resolved via registryLookup takes precedence over platform`() {
        val registered = fakeSession("FromRegistry", suspended = true)
        val other = fakeSession("Other", suspended = false)
        every { mgr.debugSessions } returns arrayOf(other)
        every { mgr.currentSession } returns other

        val result = IdeStateProbe.debugState(
            project,
            sessionId = "agent-1",
            registryLookup = { id -> if (id == "agent-1") registered else null },
        )

        assertTrue(result is DebugState.Paused)
        assertSame(registered, (result as DebugState.Paused).session)
    }

    @Test
    fun `sessionId falls back to platform session by name when registry has no match`() {
        val byName = fakeSession("Main", suspended = true)
        every { mgr.debugSessions } returns arrayOf(byName)
        every { mgr.currentSession } returns null

        val result = IdeStateProbe.debugState(
            project,
            sessionId = "Main",
            registryLookup = { null },
        )

        assertTrue(result is DebugState.Paused)
        assertSame(byName, (result as DebugState.Paused).session)
    }

    @Test
    fun `regression - session started outside agent is found via platform`() {
        // The exact reported bug: user pressed Debug in the gutter, the agent's
        // controller registry is empty, but the platform sees a paused session.
        // Before IdeStateProbe, debug_inspect returned "no debug session" here.
        val external = fakeSession("Main", suspended = true)
        every { mgr.debugSessions } returns arrayOf(external)
        every { mgr.currentSession } returns external

        val result = IdeStateProbe.debugState(
            project,
            sessionId = null,
            registryLookup = { null }, // empty registry, like the bug
        )

        assertTrue(
            result is DebugState.Paused,
            "Probe must find platform session even when agent registry is empty",
        )
    }
}
