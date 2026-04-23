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
            every { currentStackFrame } returns if (suspended) mockk(relaxed = true) else null
            every { suspendContext } returns if (suspended) mockk(relaxed = true) else null
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
    fun `AmbiguousSession when multiple sessions and no disambiguator and none are paused`() {
        // With the C1 fix, a uniquely-paused session is resolved unambiguously even
        // without a sessionId. AmbiguousSession is only returned when no paused session
        // can be singled out AND currentSession is null AND multiple sessions exist.
        // Both sessions here are running (not suspended), so ambiguity stands.
        val a = fakeSession("Worker A", suspended = false)
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

    // ── Task 4.5: name-collision disambiguation ────────────────────────────

    @Test
    fun `debugState returns AmbiguousSession when two sessions share a name and no UUID resolves`() {
        // Task 4.5 correctness bug: two sessions both named "MyApp" (post-restart
        // duplicate, or two runs of the same config). Pre-fix `all.firstOrNull { ... }`
        // silently picked one. Post-fix the probe must surface the ambiguity so
        // debug_step / debug_inspect report it rather than operating on the wrong
        // session.
        val a = fakeSession("MyApp", suspended = true)
        val b = fakeSession("MyApp", suspended = false)
        every { mgr.debugSessions } returns arrayOf(a, b)
        every { mgr.currentSession } returns null

        val result = IdeStateProbe.debugState(
            project,
            sessionId = "MyApp",
            registryLookup = { null }, // no UUID handle registered for this name
        )

        assertTrue(
            result is DebugState.AmbiguousSession,
            "Expected AmbiguousSession when >1 platform sessions match by name — got $result"
        )
        val amb = result as DebugState.AmbiguousSession
        assertEquals(2, amb.count)
        assertEquals(listOf("MyApp", "MyApp"), amb.names)
    }

    @Test
    fun `debugState returns Paused when UUID resolves uniquely even if sessionName collides`() {
        // The collision-via-sessionName path is NOT triggered when the UUID registry
        // resolves the id: the registry path is authoritative. This protects
        // agent-started sessions (which always have a UUID handle) from being
        // confused with user-started duplicates that happen to share a display name.
        val agentSession = fakeSession("MyApp", suspended = true)
        val userSessionA = fakeSession("MyApp", suspended = false)
        val userSessionB = fakeSession("MyApp", suspended = false)
        every { mgr.debugSessions } returns arrayOf(agentSession, userSessionA, userSessionB)
        every { mgr.currentSession } returns null

        val result = IdeStateProbe.debugState(
            project,
            sessionId = "agent-debug-deadbeef",
            registryLookup = { id ->
                if (id == "agent-debug-deadbeef") agentSession else null
            },
        )

        assertTrue(
            result is DebugState.Paused,
            "UUID registry hit must win regardless of sessionName collisions — got $result"
        )
        assertSame(agentSession, (result as DebugState.Paused).session)
    }

    @Test
    fun `debugState preserves string-match fallback for unknown user-started sessions`() {
        // Ground rule from the plan: the string-match fallback must be preserved
        // as the last-resort path. When the LLM passes a display name that doesn't
        // resolve via the registry but DOES match exactly one platform session,
        // the probe must still find it. This protects the "user started session,
        // LLM typed its display name" happy path.
        val userSession = fakeSession("MyUserApp", suspended = true)
        every { mgr.debugSessions } returns arrayOf(userSession)
        every { mgr.currentSession } returns null

        val result = IdeStateProbe.debugState(
            project,
            sessionId = "MyUserApp",
            registryLookup = { null }, // registry doesn't know about user sessions
        )

        assertTrue(
            result is DebugState.Paused,
            "String-match fallback must still resolve user-started sessions uniquely — got $result"
        )
        assertSame(userSession, (result as DebugState.Paused).session)
    }

    @Test
    fun `prefers uniquely paused session over last-focused running session when no sessionId`() {
        val running = fakeSession("foo-service", suspended = false)
        val paused = fakePausedSession("bar-service")
        every { mgr.debugSessions } returns arrayOf(running, paused)
        every { mgr.currentSession } returns running  // user clicked the running one last

        val result = IdeStateProbe.debugState(project)
        assertTrue(result is DebugState.Paused, "expected Paused, got $result")
        assertSame(paused, (result as DebugState.Paused).session)
    }

    @Test
    fun `falls back to currentSession when multiple sessions are paused`() {
        val a = fakePausedSession("a")
        val b = fakePausedSession("b")
        every { mgr.debugSessions } returns arrayOf(a, b)
        every { mgr.currentSession } returns b

        val result = IdeStateProbe.debugState(project)
        assertTrue(result is DebugState.Paused)
        assertSame(b, (result as DebugState.Paused).session)
    }

    @Test
    fun `treats isSuspended=true but currentStackFrame=null as Running (race window)`() {
        val raceySession = mockk<XDebugSession>(relaxed = true) {
            every { sessionName } returns "racey"
            every { isSuspended } returns true
            every { currentStackFrame } returns null
            every { suspendContext } returns null
        }
        every { mgr.debugSessions } returns arrayOf(raceySession)
        every { mgr.currentSession } returns raceySession

        val result = IdeStateProbe.debugState(project)
        assertTrue(result is DebugState.Running, "expected Running during race, got $result")
    }

    private fun fakePausedSession(name: String): XDebugSession =
        mockk(relaxed = true) {
            every { sessionName } returns name
            every { isSuspended } returns true
            every { currentStackFrame } returns mockk(relaxed = true)
            every { suspendContext } returns mockk(relaxed = true)
        }
}
