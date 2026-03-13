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
}
