package com.workflow.orchestrator.jira.vcs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PostCommitTransitionLogicTest {

    @Test
    fun `should suggest transition when status is To Do`() {
        assertTrue(PostCommitTransitionLogic.shouldSuggestTransition("To Do"))
    }

    @Test
    fun `should suggest transition when status is Open`() {
        assertTrue(PostCommitTransitionLogic.shouldSuggestTransition("Open"))
    }

    @Test
    fun `should suggest transition when status is Backlog`() {
        assertTrue(PostCommitTransitionLogic.shouldSuggestTransition("Backlog"))
    }

    @Test
    fun `should not suggest when already In Progress`() {
        assertFalse(PostCommitTransitionLogic.shouldSuggestTransition("In Progress"))
    }

    @Test
    fun `should not suggest when Done`() {
        assertFalse(PostCommitTransitionLogic.shouldSuggestTransition("Done"))
    }

    @Test
    fun `should not suggest when status is blank`() {
        assertFalse(PostCommitTransitionLogic.shouldSuggestTransition(""))
    }

    @Test
    fun `case insensitive matching`() {
        assertTrue(PostCommitTransitionLogic.shouldSuggestTransition("to do"))
        assertTrue(PostCommitTransitionLogic.shouldSuggestTransition("TO DO"))
    }

    @Test
    fun `parseTriggerStatuses null falls back to defaults`() {
        assertEquals(
            PostCommitTransitionLogic.DEFAULT_TRIGGER_STATUSES,
            PostCommitTransitionLogic.parseTriggerStatuses(null)
        )
    }

    @Test
    fun `parseTriggerStatuses blank or empty falls back to defaults`() {
        assertEquals(
            PostCommitTransitionLogic.DEFAULT_TRIGGER_STATUSES,
            PostCommitTransitionLogic.parseTriggerStatuses("")
        )
        assertEquals(
            PostCommitTransitionLogic.DEFAULT_TRIGGER_STATUSES,
            PostCommitTransitionLogic.parseTriggerStatuses("   ,  , ")
        )
    }

    @Test
    fun `parseTriggerStatuses default string matches DEFAULT set`() {
        assertEquals(
            PostCommitTransitionLogic.DEFAULT_TRIGGER_STATUSES,
            PostCommitTransitionLogic.parseTriggerStatuses("to do,open,new,backlog,selected for development")
        )
    }

    @Test
    fun `parseTriggerStatuses trims lowercases and drops blanks`() {
        assertEquals(
            setOf("triage", "waiting"),
            PostCommitTransitionLogic.parseTriggerStatuses("  Triage , ,WAITING ")
        )
    }

    @Test
    fun `custom trigger statuses gate matching`() {
        val custom = PostCommitTransitionLogic.parseTriggerStatuses("triage,blocked")
        assertTrue(PostCommitTransitionLogic.shouldSuggestTransition("Triage", custom))
        assertTrue(PostCommitTransitionLogic.shouldSuggestTransition("BLOCKED", custom))
        // A default status is no longer a trigger once a custom set is configured.
        assertFalse(PostCommitTransitionLogic.shouldSuggestTransition("To Do", custom))
    }
}
