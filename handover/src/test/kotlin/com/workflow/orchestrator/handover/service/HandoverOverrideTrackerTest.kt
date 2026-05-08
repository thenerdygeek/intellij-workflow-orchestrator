package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class HandoverOverrideTrackerTest {

    private lateinit var eventBus: EventBus
    private lateinit var pluginSettings: PluginSettings
    private lateinit var state: PluginSettings.State
    private lateinit var scope: CoroutineScope
    private lateinit var tracker: HandoverOverrideTracker

    @BeforeEach
    fun setUp() {
        eventBus = EventBus()
        state = PluginSettings.State()
        pluginSettings = mockk(relaxed = true)
        every { pluginSettings.state } returns state
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        tracker = HandoverOverrideTracker(eventBus, pluginSettings, scope)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    // -----------------------------------------------------------------------
    // Initial state
    // -----------------------------------------------------------------------

    @Test
    fun `count30d is zero before any events`() {
        assertEquals(0, tracker.count30d())
    }

    // -----------------------------------------------------------------------
    // Event recording
    // -----------------------------------------------------------------------

    @Test
    fun `HandoverOverride event increments count30d`() = runTest {
        eventBus.emit(overrideEvent())
        yield()
        assertEquals(1, tracker.count30d())
    }

    @Test
    fun `multiple HandoverOverride events accumulate`() = runTest {
        repeat(3) { eventBus.emit(overrideEvent()) }
        yield()
        assertEquals(3, tracker.count30d())
    }

    @Test
    fun `non-override events do not increment count`() = runTest {
        eventBus.emit(WorkflowEvent.BranchChanged("main", "feature/x"))
        yield()
        assertEquals(0, tracker.count30d())
    }

    // -----------------------------------------------------------------------
    // 30-day window pruning — happens in record(), not count30d()
    // -----------------------------------------------------------------------

    @Test
    fun `count30d excludes entries older than 30 days`() {
        val old = Instant.now().minus(31, ChronoUnit.DAYS)
        state.handoverOverrideLog.add(DateTimeFormatter.ISO_INSTANT.format(old))
        // count30d is read-only; the stale entry is not removed here, but is not counted
        assertEquals(0, tracker.count30d())
    }

    @Test
    fun `recent entries within 30 days are counted`() {
        val recent = Instant.now().minus(29, ChronoUnit.DAYS)
        state.handoverOverrideLog.add(DateTimeFormatter.ISO_INSTANT.format(recent))
        assertEquals(1, tracker.count30d())
    }

    @Test
    fun `count30d returns only recent entries when log is mixed`() {
        val old = Instant.now().minus(31, ChronoUnit.DAYS)
        val recent = Instant.now().minus(1, ChronoUnit.DAYS)
        state.handoverOverrideLog.add(DateTimeFormatter.ISO_INSTANT.format(old))
        state.handoverOverrideLog.add(DateTimeFormatter.ISO_INSTANT.format(recent))
        assertEquals(1, tracker.count30d())
    }

    @Test
    fun `stale entry is pruned when a new event is recorded`() = runTest {
        val old = Instant.now().minus(31, ChronoUnit.DAYS)
        state.handoverOverrideLog.add(DateTimeFormatter.ISO_INSTANT.format(old))
        // Triggering record() via an event prunes the stale entry
        eventBus.emit(overrideEvent())
        yield()
        // Only the fresh entry should remain
        assertEquals(1, state.handoverOverrideLog.size)
        assertEquals(1, tracker.count30d())
    }

    @Test
    fun `log size is capped at MAX_LOG_SIZE when records are added rapidly`() = runTest {
        val overLimit = HandoverOverrideTracker.MAX_LOG_SIZE + 1000
        repeat(overLimit) {
            eventBus.emit(overrideEvent())
            yield()
        }
        assertTrue(
            state.handoverOverrideLog.size <= HandoverOverrideTracker.MAX_LOG_SIZE,
            "Log size ${state.handoverOverrideLog.size} must not exceed MAX_LOG_SIZE " +
                "${HandoverOverrideTracker.MAX_LOG_SIZE}"
        )
    }

    // -----------------------------------------------------------------------
    // clear()
    // -----------------------------------------------------------------------

    @Test
    fun `clear() empties the log`() = runTest {
        eventBus.emit(overrideEvent())
        yield()
        tracker.clear()
        assertEquals(0, tracker.count30d())
        assertTrue(state.handoverOverrideLog.isEmpty())
    }

    @Test
    fun `clear() on empty log is a no-op`() {
        assertDoesNotThrow { tracker.clear() }
        assertEquals(0, tracker.count30d())
    }

    // -----------------------------------------------------------------------
    // Persistence in PluginSettings
    // -----------------------------------------------------------------------

    @Test
    fun `event timestamps are written to handoverOverrideLog`() = runTest {
        eventBus.emit(overrideEvent())
        yield()
        assertEquals(1, state.handoverOverrideLog.size)
        // Verify stored value is a parseable ISO-8601 instant
        assertDoesNotThrow { Instant.parse(state.handoverOverrideLog.first()) }
    }

    @Test
    fun `recorded timestamp is within 1 second of event timestamp`() = runTest {
        val eventTime = Instant.now()
        eventBus.emit(overrideEvent(eventTime))
        yield()
        val stored = Instant.parse(state.handoverOverrideLog.first())
        assertTrue(
            Math.abs(stored.epochSecond - eventTime.epochSecond) <= 1,
            "Stored timestamp $stored should be close to event time $eventTime"
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun overrideEvent(timestamp: Instant = Instant.now()) = WorkflowEvent.HandoverOverride(
        ticketId = "PROJ-1",
        action = WorkflowEvent.HandoverAction.COPY_CHIP,
        failedChecks = emptyList(),
        timestamp = timestamp
    )
}
