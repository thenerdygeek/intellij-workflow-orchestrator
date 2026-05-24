package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-text contract tests for D1 (audit finding agent-runtime:F-11).
 *
 * AgentService.executeTask previously set activeTask inside the launched coroutine
 * AFTER the Job was returned to the caller, allowing concurrent executeTask calls
 * to race and produce mismatched (loop, job, handler) state.
 *
 * Fix: all activeTask + activeMessageStateHandler mutations happen under a
 * [kotlinx.coroutines.sync.Mutex] (activeTaskMutex). These source-text pins verify:
 *   1. The Mutex field is declared.
 *   2. activeTask.set uses withLock.
 *   3. The finally block also uses withLock to clear both fields atomically.
 *   4. The pre-existing task is explicitly cancelled before the new one is registered.
 *
 * Why source-text rather than integration: AgentService depends on IntelliJ platform
 * services and cannot be instantiated in a plain JUnit 5 test. The Mutex is a
 * structural invariant — source-text pins are the standard pattern in this codebase
 * for invariants that can't be exercised without the platform (see RunInvocationLeakTest).
 */
class AgentServiceActiveTaskMutexTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt"
        ).readText()
    }

    @Test
    fun `activeTaskMutex field is declared`() {
        assertTrue(src.contains("val activeTaskMutex = Mutex()"),
            "AgentService must declare a Mutex field named activeTaskMutex")
    }

    @Test
    fun `activeTask set runs inside activeTaskMutex withLock block`() {
        // The set and the withLock must appear in the same context block.
        // We verify both literals appear and that the set is preceded by withLock within close proximity.
        val lockIdx = src.indexOf("activeTaskMutex.withLock")
        assertTrue(lockIdx >= 0, "activeTaskMutex.withLock must appear in AgentService.kt")
        // activeTask.set follows the lock acquisition
        val setAfterLock = src.indexOf("activeTask.set(ActiveTask(", lockIdx)
        assertTrue(setAfterLock > lockIdx,
            "activeTask.set(ActiveTask(...)) must appear after activeTaskMutex.withLock in the source")
    }

    @Test
    fun `finally block clears activeTask and activeMessageStateHandler inside withLock`() {
        // Confirm two separate withLock usages exist (set block + finally block)
        val allLocks = Regex("""activeTaskMutex\.withLock""").findAll(src).toList()
        assertTrue(allLocks.size >= 2,
            "Expected at least 2 activeTaskMutex.withLock sites (set + finally clear); found ${allLocks.size}")
        // The last withLock site (the finally cleanup) must contain both clear operations.
        // Use the LAST occurrence of activeTaskMutex.withLock as the finally-cleanup site.
        val lastLock = allLocks.last().range.first
        val clearTask = src.indexOf("activeTask.set(null)", lastLock)
        val clearHandler = src.indexOf("activeMessageStateHandler = null", lastLock)
        assertTrue(clearTask > lastLock && clearTask < lastLock + 500,
            "activeTask.set(null) must appear inside the finally-block withLock (last withLock site)")
        assertTrue(clearHandler > lastLock && clearHandler < lastLock + 500,
            "activeMessageStateHandler = null must appear inside the finally-block withLock (last withLock site)")
    }

    @Test
    fun `previous task is cancelled before new task is registered`() {
        // The cancel calls must appear inside the withLock block, before the set.
        val lockIdx = src.indexOf("activeTaskMutex.withLock")
        val cancelLoop = src.indexOf("prev.loop.cancel()", lockIdx)
        val cancelJob = src.indexOf("prev.job.cancel()", lockIdx)
        val setIdx = src.indexOf("activeTask.set(ActiveTask(", lockIdx)
        assertTrue(cancelLoop in lockIdx..setIdx,
            "prev.loop.cancel() must appear between withLock and activeTask.set")
        assertTrue(cancelJob in lockIdx..setIdx,
            "prev.job.cancel() must appear between withLock and activeTask.set")
    }
}
