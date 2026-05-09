package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.events.WorkflowEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BuildLogCacheTest {

    private fun makeEvent(
        planKey: String = "PROJ-BUILD",
        buildNumber: Int = 42,
        logText: String = "Unique Docker Tag : my-tag-123",
        status: WorkflowEvent.BuildEventStatus = WorkflowEvent.BuildEventStatus.SUCCESS
    ): WorkflowEvent.BuildLogReady = WorkflowEvent.BuildLogReady(
        planKey = planKey,
        buildNumber = buildNumber,
        resultKey = "$planKey-$buildNumber",
        status = status,
        logText = logText
    )

    @Test
    fun `getLatest returns null when no event has been put`() {
        val cache = BuildLogCache()
        assertNull(cache.getLatest("PROJ-BUILD"))
    }

    @Test
    fun `put then getLatest returns the stored event`() {
        val cache = BuildLogCache()
        val event = makeEvent()
        cache.put(event)
        assertEquals(event, cache.getLatest("PROJ-BUILD"))
    }

    @Test
    fun `getLatest is keyed per planKey and isolated across plans`() {
        val cache = BuildLogCache()
        val eventA = makeEvent(planKey = "PLAN-A", buildNumber = 100)
        cache.put(eventA)
        assertEquals(eventA, cache.getLatest("PLAN-A"))
        assertNull(cache.getLatest("PLAN-B"))
    }

    @Test
    fun `getLatest is case-insensitive on planKey`() {
        // Plan-key sources across the codebase (PluginSettings, RepoConfig,
        // Bamboo responses) sometimes differ in case. AutomationPanel already
        // matches BuildLogReady events with equals(ignoreCase = true); the cache
        // must match that contract or callers would silently miss hits.
        val cache = BuildLogCache()
        cache.put(makeEvent(planKey = "PROJ-BUILD"))
        assertEquals("PROJ-BUILD", cache.getLatest("proj-build")?.planKey)
        assertEquals("PROJ-BUILD", cache.getLatest("Proj-Build")?.planKey)
    }

    @Test
    fun `put with newer build replaces older event for the same plan`() {
        val cache = BuildLogCache()
        cache.put(makeEvent(buildNumber = 41, logText = "old"))
        cache.put(makeEvent(buildNumber = 42, logText = "new"))
        assertEquals(42, cache.getLatest("PROJ-BUILD")?.buildNumber)
        assertEquals("new", cache.getLatest("PROJ-BUILD")?.logText)
    }
}
