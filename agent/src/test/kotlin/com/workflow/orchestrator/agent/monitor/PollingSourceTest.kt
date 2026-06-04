package com.workflow.orchestrator.agent.monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PollingSourceTest {

    /** Fake subclass: returns scripted values from [fetchResults] sequentially; null entries simulate fetch errors. */
    private class FakePollingSource(
        private val fetchResults: MutableList<String?>,
        cs: CoroutineScope,
    ) : PollingSource<String>("test-id", "test description", cs, 30_000) {

        override suspend fun fetch(): String? =
            if (fetchResults.isEmpty()) null else fetchResults.removeAt(0)

        override fun diff(previous: String?, current: String): List<MonitorEvent> =
            if (previous != current) listOf(
                MonitorEvent(monitorId, Severity.NOTABLE, "changed:$current")
            ) else emptyList()
    }

    // ------------------------------------------------------------------ test 1
    @Test
    fun `first poll establishes baseline and emits per diff(null, current)`() = runTest {
        val src = FakePollingSource(mutableListOf("stateA"), this)
        val emitted = mutableListOf<MonitorEvent>()

        val result = src.pollOnce { emitted += it }

        // diff(null, "stateA") → previous != current → one NOTABLE event
        assertTrue(result, "pollOnce should return true when events are emitted")
        assertEquals(1, emitted.size)
        assertEquals("changed:stateA", emitted[0].line)
        assertEquals(Severity.NOTABLE, emitted[0].severity)
        assertEquals("test-id", emitted[0].monitorId)
    }

    // ------------------------------------------------------------------ test 2
    @Test
    fun `no change between two polls — second pollOnce returns false and emits nothing`() = runTest {
        val src = FakePollingSource(mutableListOf("stateA", "stateA"), this)
        val emitted = mutableListOf<MonitorEvent>()

        src.pollOnce { emitted += it }   // first poll → baseline "stateA"
        emitted.clear()

        val result = src.pollOnce { emitted += it }  // same value → no diff

        assertFalse(result, "pollOnce should return false when nothing changed")
        assertTrue(emitted.isEmpty(), "No events should be emitted when snapshot is unchanged")
    }

    // ------------------------------------------------------------------ test 3
    @Test
    fun `change between polls — pollOnce returns true and emits the diff events`() = runTest {
        val src = FakePollingSource(mutableListOf("stateA", "stateB"), this)
        val emitted = mutableListOf<MonitorEvent>()

        src.pollOnce { emitted += it }   // baseline
        emitted.clear()

        val result = src.pollOnce { emitted += it }  // stateA → stateB

        assertTrue(result, "pollOnce should return true on a detected change")
        assertEquals(1, emitted.size)
        assertEquals("changed:stateB", emitted[0].line)
    }

    // ------------------------------------------------------------------ test 4
    @Test
    fun `fetch returning null — pollOnce returns false, snapshot unchanged, nothing emitted`() = runTest {
        val src = FakePollingSource(mutableListOf("stateA", null, "stateA"), this)
        val emitted = mutableListOf<MonitorEvent>()

        src.pollOnce { emitted += it }   // baseline "stateA"
        emitted.clear()

        val nullResult = src.pollOnce { emitted += it }  // null fetch → no-change

        assertFalse(nullResult, "pollOnce should return false when fetch returns null")
        assertTrue(emitted.isEmpty(), "Nothing should be emitted when fetch returns null")

        // Next poll: snapshot should still be "stateA" (not replaced by null),
        // so diff("stateA", "stateA") → no change.
        val noChangeResult = src.pollOnce { emitted += it }
        assertFalse(noChangeResult, "Snapshot must not have been clobbered by a null fetch")
        assertTrue(emitted.isEmpty(), "diff must see the pre-null snapshot as previous")
    }

    // ------------------------------------------------------------------ test 5
    @Test
    fun `snapshot advances across polls — previous passed to diff equals last non-null current`() = runTest {
        val capturedPrevious = mutableListOf<String?>()
        val src = object : PollingSource<String>("adv-id", "advance test", this, 30_000) {
            private val values = mutableListOf("v1", "v2", "v3")
            override suspend fun fetch(): String? =
                if (values.isEmpty()) null else values.removeAt(0)
            override fun diff(previous: String?, current: String): List<MonitorEvent> {
                capturedPrevious += previous
                return if (previous != current) listOf(MonitorEvent(monitorId, Severity.NOTABLE, "ev:$current")) else emptyList()
            }
        }

        src.pollOnce {}   // fetch "v1", diff(null, "v1"), snapshot = "v1"
        src.pollOnce {}   // fetch "v2", diff("v1", "v2"), snapshot = "v2"
        src.pollOnce {}   // fetch "v3", diff("v2", "v3"), snapshot = "v3"

        assertEquals(listOf(null, "v1", "v2"), capturedPrevious,
            "previous should advance: null → v1 → v2")
    }
}
