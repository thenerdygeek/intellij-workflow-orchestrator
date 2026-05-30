package com.workflow.orchestrator.agent.tools.background

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * BUG #2 — cross-IDE delegation result/question nudges that arrive when the delegator
 * loop is idle must be PERSISTED before auto-wake (mirroring the background-completion
 * persist-first contract), so a guard-rejected nudge REPLAYS on the next resume instead
 * of vanishing. Background completions persist via [BackgroundPersistence]; delegation
 * nudges are plain text (no bgId/exitCode/state), so they persist via this parallel
 * [DelegationNudgePersistence] keyed by the same `sessions/{id}/background/` directory.
 *
 * Atomicity, per-session isolation, and consume-on-replay are pinned here exactly as
 * [BackgroundPersistenceTest] pins the background path.
 */
class DelegationNudgePersistenceTest {

    @Test
    fun `appendNudge creates and appends in order atomically`() {
        val dir = Files.createTempDirectory("dnp-")
        val persist = DelegationNudgePersistence(dir)

        persist.appendNudge("s1", "first delegation result")
        persist.appendNudge("s1", "second delegation result")

        val loaded = persist.loadPendingNudges("s1")
        assertEquals(2, loaded.size, "expected both nudges persisted in order")
        assertEquals("first delegation result", loaded[0].text)
        assertEquals("second delegation result", loaded[1].text)
        // ids are unique so consume can target a single entry
        assertTrue(loaded[0].id != loaded[1].id, "each nudge gets a distinct id")
    }

    @Test
    fun `consumeNudge removes a single entry by id`() {
        val dir = Files.createTempDirectory("dnp-")
        val persist = DelegationNudgePersistence(dir)
        persist.appendNudge("s1", "keep me")
        persist.appendNudge("s1", "drop me")
        val dropId = persist.loadPendingNudges("s1").first { it.text == "drop me" }.id

        persist.consumeNudge("s1", dropId)

        val remaining = persist.loadPendingNudges("s1")
        assertEquals(listOf("keep me"), remaining.map { it.text })
    }

    @Test
    fun `per-session isolation`() {
        val dir = Files.createTempDirectory("dnp-")
        val persist = DelegationNudgePersistence(dir)
        persist.appendNudge("s1", "for s1")
        persist.appendNudge("s2", "for s2")
        assertEquals(listOf("for s1"), persist.loadPendingNudges("s1").map { it.text })
        assertEquals(listOf("for s2"), persist.loadPendingNudges("s2").map { it.text })
    }

    @Test
    fun `missing file loads empty`() {
        val dir = Files.createTempDirectory("dnp-")
        assertTrue(DelegationNudgePersistence(dir).loadPendingNudges("never-written").isEmpty())
    }
}
