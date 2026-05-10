package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.events.WorkflowEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BuildLogCacheTest {

    private fun makeEvent(
        planKey: String = "PROJ-BUILD",
        chainKey: String = planKey,
        buildNumber: Int = 42,
        logText: String = "Unique Docker Tag : my-tag-123",
        status: WorkflowEvent.BuildEventStatus = WorkflowEvent.BuildEventStatus.SUCCESS
    ): WorkflowEvent.BuildLogReady = WorkflowEvent.BuildLogReady(
        planKey = planKey,
        buildNumber = buildNumber,
        resultKey = "$planKey-$buildNumber",
        status = status,
        logText = logText,
        chainKey = chainKey,
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
    fun `getLatest is keyed per chainKey and isolated across chains`() {
        val cache = BuildLogCache()
        val eventA = makeEvent(planKey = "PLAN-A", chainKey = "PLAN-A-1", buildNumber = 100)
        cache.put(eventA)
        // Hit by chain key
        assertEquals(eventA, cache.getLatest("PLAN-A-1"))
        // Miss: parent plan key is not the chain key
        assertNull(cache.getLatest("PLAN-A"))
        // Miss: unrelated chain key
        assertNull(cache.getLatest("PLAN-B"))
    }

    @Test
    fun `getLatest is case-insensitive on chainKey`() {
        // Chain-key sources across the codebase (PluginSettings, RepoConfig,
        // Bamboo responses) sometimes differ in case. The cache must match that
        // contract or callers would silently miss hits.
        val cache = BuildLogCache()
        cache.put(makeEvent(planKey = "PROJ-BUILD", chainKey = "PROJ-BUILD523"))
        assertEquals("PROJ-BUILD523", cache.getLatest("proj-build523")?.chainKey)
        assertEquals("PROJ-BUILD523", cache.getLatest("Proj-Build523")?.chainKey)
    }

    @Test
    fun `put with newer build replaces older event for the same chain key`() {
        val cache = BuildLogCache()
        cache.put(makeEvent(chainKey = "PROJ-BUILD523", buildNumber = 41, logText = "old"))
        cache.put(makeEvent(chainKey = "PROJ-BUILD523", buildNumber = 42, logText = "new"))
        assertEquals(42, cache.getLatest("PROJ-BUILD523")?.buildNumber)
        assertEquals("new", cache.getLatest("PROJ-BUILD523")?.logText)
    }

    @Test
    fun `different chain keys are stored independently`() {
        val cache = BuildLogCache()
        val eventA = makeEvent(planKey = "PROJ", chainKey = "PROJ-BUILD523", buildNumber = 10, logText = "feature-branch")
        val eventB = makeEvent(planKey = "PROJ", chainKey = "PROJ-BUILD", buildNumber = 20, logText = "master")
        cache.put(eventA)
        cache.put(eventB)
        // Feature branch chain read
        assertEquals("feature-branch", cache.getLatest("PROJ-BUILD523")?.logText)
        // Master chain read — not cross-contaminated
        assertEquals("master", cache.getLatest("PROJ-BUILD")?.logText)
    }

    @Test
    fun `chainKey defaults to planKey when not supplied`() {
        // When BuildLogReady is constructed without an explicit chainKey (legacy
        // call sites or test helpers that omit the field), the cache key falls back
        // to planKey — consistent with pre-Phase-E behaviour.
        val cache = BuildLogCache()
        val event = WorkflowEvent.BuildLogReady(
            planKey = "PROJ-BUILD",
            buildNumber = 99,
            resultKey = "PROJ-BUILD-99",
            status = WorkflowEvent.BuildEventStatus.SUCCESS,
            logText = "default-chain-key",
            // chainKey omitted → defaults to planKey
        )
        cache.put(event)
        assertEquals(event, cache.getLatest("PROJ-BUILD"))
    }
}
