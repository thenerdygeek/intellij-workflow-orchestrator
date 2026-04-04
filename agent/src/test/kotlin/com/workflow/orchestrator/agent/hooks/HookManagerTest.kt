package com.workflow.orchestrator.agent.hooks

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HookManagerTest {

    private lateinit var fakeRunner: FakeHookRunner
    private lateinit var manager: HookManager

    @BeforeEach
    fun setUp() {
        fakeRunner = FakeHookRunner()
        manager = HookManager(fakeRunner)
    }

    // ── Registration ──────────────────────────────────────────────────────

    @Test
    fun `register adds hook and hasHooks returns true`() {
        assertFalse(manager.hasHooks(HookType.TASK_START))

        manager.register(HookConfig(type = HookType.TASK_START, command = "echo test"))

        assertTrue(manager.hasHooks(HookType.TASK_START))
        assertFalse(manager.hasHooks(HookType.TASK_CANCEL))
        assertEquals(1, manager.hookCount(HookType.TASK_START))
        assertEquals(1, manager.totalHookCount())
    }

    @Test
    fun `multiple hooks for same type`() {
        manager.register(HookConfig(type = HookType.PRE_TOOL_USE, command = "cmd1"))
        manager.register(HookConfig(type = HookType.PRE_TOOL_USE, command = "cmd2"))

        assertEquals(2, manager.hookCount(HookType.PRE_TOOL_USE))
    }

    @Test
    fun `unregister removes specific hook`() {
        manager.register(HookConfig(type = HookType.PRE_TOOL_USE, command = "cmd1"))
        manager.register(HookConfig(type = HookType.PRE_TOOL_USE, command = "cmd2"))

        manager.unregister(HookType.PRE_TOOL_USE, "cmd1")

        assertEquals(1, manager.hookCount(HookType.PRE_TOOL_USE))
        assertTrue(manager.hasHooks(HookType.PRE_TOOL_USE))
    }

    @Test
    fun `unregister last hook removes type entirely`() {
        manager.register(HookConfig(type = HookType.TASK_START, command = "cmd1"))
        manager.unregister(HookType.TASK_START, "cmd1")

        assertFalse(manager.hasHooks(HookType.TASK_START))
        assertEquals(0, manager.totalHookCount())
    }

    @Test
    fun `clearAll removes all hooks`() {
        manager.register(HookConfig(type = HookType.TASK_START, command = "cmd1"))
        manager.register(HookConfig(type = HookType.PRE_TOOL_USE, command = "cmd2"))

        manager.clearAll()

        assertFalse(manager.hasAnyHooks())
        assertEquals(0, manager.totalHookCount())
    }

    // ── Dispatch — no hooks ──────────────────────────────────────────────

    @Test
    fun `dispatch returns Proceed when no hooks registered`() = runTest {
        val result = manager.dispatch(
            HookEvent(type = HookType.TASK_START, data = emptyMap())
        )
        assertTrue(result is HookResult.Proceed)
        assertNull((result as HookResult.Proceed).contextModification)
    }

    // ── Dispatch — cancellable hooks ─────────────────────────────────────

    @Test
    fun `dispatch returns Cancel when cancellable hook returns Cancel`() = runTest {
        fakeRunner.nextResult = HookResult.Cancel(reason = "blocked")
        manager.register(HookConfig(type = HookType.PRE_TOOL_USE, command = "check"))

        val result = manager.dispatch(
            HookEvent(type = HookType.PRE_TOOL_USE, data = mapOf("toolName" to "run_command"))
        )

        assertTrue(result is HookResult.Cancel)
        assertEquals("blocked", (result as HookResult.Cancel).reason)
    }

    @Test
    fun `dispatch stops on first Cancel for cancellable events`() = runTest {
        // First hook cancels, second should not run
        fakeRunner.resultSequence = mutableListOf(
            HookResult.Cancel(reason = "first hook cancelled"),
            HookResult.Proceed()
        )
        manager.register(HookConfig(type = HookType.TASK_START, command = "hook1"))
        manager.register(HookConfig(type = HookType.TASK_START, command = "hook2"))

        val result = manager.dispatch(
            HookEvent(type = HookType.TASK_START, data = emptyMap())
        )

        assertTrue(result is HookResult.Cancel)
        assertEquals(1, fakeRunner.executionCount)
    }

    // ── Dispatch — observation-only hooks ────────────────────────────────

    @Test
    fun `dispatch ignores Cancel for non-cancellable hooks`() = runTest {
        fakeRunner.nextResult = HookResult.Cancel(reason = "ignored")
        manager.register(HookConfig(type = HookType.POST_TOOL_USE, command = "audit"))

        val result = manager.dispatch(
            HookEvent(type = HookType.POST_TOOL_USE, data = mapOf("toolName" to "edit_file"))
        )

        // Cancel is ignored for observation-only hooks
        assertTrue(result is HookResult.Proceed)
    }

    @Test
    fun `dispatch ignores Cancel for TASK_CANCEL hook`() = runTest {
        fakeRunner.nextResult = HookResult.Cancel(reason = "ignored")
        manager.register(HookConfig(type = HookType.TASK_CANCEL, command = "cleanup"))

        val result = manager.dispatch(
            HookEvent(type = HookType.TASK_CANCEL, data = mapOf("reason" to "user"))
        )

        assertTrue(result is HookResult.Proceed)
    }

    // ── Dispatch — context modification ──────────────────────────────────

    @Test
    fun `dispatch aggregates context modifications from multiple hooks`() = runTest {
        fakeRunner.resultSequence = mutableListOf(
            HookResult.Proceed(contextModification = "context from hook 1"),
            HookResult.Proceed(contextModification = "context from hook 2")
        )
        manager.register(HookConfig(type = HookType.POST_TOOL_USE, command = "hook1"))
        manager.register(HookConfig(type = HookType.POST_TOOL_USE, command = "hook2"))

        val result = manager.dispatch(
            HookEvent(type = HookType.POST_TOOL_USE, data = emptyMap())
        )

        assertTrue(result is HookResult.Proceed)
        val ctx = (result as HookResult.Proceed).contextModification!!
        assertTrue(ctx.contains("context from hook 1"))
        assertTrue(ctx.contains("context from hook 2"))
    }

    @Test
    fun `dispatch returns null context when no hooks provide it`() = runTest {
        fakeRunner.nextResult = HookResult.Proceed(contextModification = null)
        manager.register(HookConfig(type = HookType.TASK_START, command = "cmd"))

        val result = manager.dispatch(
            HookEvent(type = HookType.TASK_START, data = emptyMap())
        )

        assertTrue(result is HookResult.Proceed)
        assertNull((result as HookResult.Proceed).contextModification)
    }

    // ── Dispatch — error handling ────────────────────────────────────────

    @Test
    fun `dispatch returns Proceed when hook throws exception`() = runTest {
        fakeRunner.shouldThrow = true
        manager.register(HookConfig(type = HookType.PRE_TOOL_USE, command = "bad"))

        val result = manager.dispatch(
            HookEvent(type = HookType.PRE_TOOL_USE, data = emptyMap())
        )

        // Hooks fail open — exception results in Proceed
        assertTrue(result is HookResult.Proceed)
    }

    @Test
    fun `dispatch continues to next hook after exception in non-cancellable`() = runTest {
        fakeRunner.throwOnFirst = true
        fakeRunner.resultSequence = mutableListOf(
            HookResult.Proceed(contextModification = "from hook 2")
        )
        manager.register(HookConfig(type = HookType.POST_TOOL_USE, command = "bad"))
        manager.register(HookConfig(type = HookType.POST_TOOL_USE, command = "good"))

        val result = manager.dispatch(
            HookEvent(type = HookType.POST_TOOL_USE, data = emptyMap())
        )

        assertTrue(result is HookResult.Proceed)
        // Second hook's context should still come through
        assertEquals("from hook 2", (result as HookResult.Proceed).contextModification)
    }

    // ── hasAnyHooks ──────────────────────────────────────────────────────

    @Test
    fun `hasAnyHooks returns false when empty`() {
        assertFalse(manager.hasAnyHooks())
    }

    @Test
    fun `hasAnyHooks returns true when at least one hook registered`() {
        manager.register(HookConfig(type = HookType.TASK_CANCEL, command = "cleanup"))
        assertTrue(manager.hasAnyHooks())
    }
}

/**
 * Fake HookRunner for unit testing HookManager dispatch logic
 * without executing actual shell commands.
 */
class FakeHookRunner : HookRunner() {
    var nextResult: HookResult = HookResult.Proceed()
    var resultSequence = mutableListOf<HookResult>()
    var executionCount = 0
    var shouldThrow = false
    var throwOnFirst = false
    var lastEvent: HookEvent? = null

    override suspend fun execute(hook: HookConfig, event: HookEvent): HookResult {
        lastEvent = event
        executionCount++

        if (shouldThrow) throw RuntimeException("Hook runner error")

        if (throwOnFirst && executionCount == 1) throw RuntimeException("First hook error")

        return if (resultSequence.isNotEmpty()) {
            resultSequence.removeFirst()
        } else {
            nextResult
        }
    }
}
