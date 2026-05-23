package com.workflow.orchestrator.agent.delegation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PendingQuestionTokenTest {

    @Test
    fun `arm then resolve sets the deferred value`() = runBlocking {
        val token = PendingQuestionToken()
        val deferred = CompletableDeferred<String>()
        assertTrue(token.armIfClear("q-1", deferred))
        assertTrue(token.tryResolve("q-1", "v2"))
        assertEquals("v2", deferred.await())
    }

    @Test
    fun `arming twice without resolving fails the second arm`() {
        val token = PendingQuestionToken()
        assertTrue(token.armIfClear("q-1", CompletableDeferred<String>()))
        assertFalse(token.armIfClear("q-2", CompletableDeferred<String>()))
    }

    @Test
    fun `tryResolve with wrong questionId returns false and leaves deferred unfinished`() = runBlocking {
        val token = PendingQuestionToken()
        val deferred = CompletableDeferred<String>()
        token.armIfClear("q-1", deferred)
        assertFalse(token.tryResolve("q-WRONG", "should not deliver"))
        assertFalse(deferred.isCompleted)
    }

    @Test
    fun `two concurrent tryResolves — only one wins`() = runBlocking {
        val token = PendingQuestionToken()
        val deferred = CompletableDeferred<String>()
        token.armIfClear("q-1", deferred)
        val a = token.tryResolve("q-1", "answer-A")
        val b = token.tryResolve("q-1", "answer-B")
        assertTrue(a xor b) { "exactly one tryResolve must win" }
        val winner = deferred.await()
        assertTrue(winner == "answer-A" || winner == "answer-B")
    }

    @Test
    fun `cancel clears the slot and lets a new arm succeed`() {
        val token = PendingQuestionToken()
        assertTrue(token.armIfClear("q-1", CompletableDeferred<String>()))
        token.cancel("q-1", "canceled_for_test")
        assertTrue(token.armIfClear("q-2", CompletableDeferred<String>()))
    }

    // ── tryResolveCurrent (F2 fix) ────────────────────────────────────────────

    @Test
    fun `tryResolveCurrent with armed question delivers answer and returns questionId`() = runBlocking {
        val token = PendingQuestionToken()
        val deferred = CompletableDeferred<String>()
        token.armIfClear("q-1", deferred)
        val resolved = token.tryResolveCurrent("my-answer")
        assertEquals("q-1", resolved)
        assertEquals("my-answer", deferred.await())
    }

    @Test
    fun `tryResolveCurrent on empty slot returns null`() {
        val token = PendingQuestionToken()
        assertNull(token.tryResolveCurrent("should-not-deliver"))
    }

    @Test
    fun `tryResolveCurrent clears the slot so a new arm succeeds`() = runBlocking {
        val token = PendingQuestionToken()
        token.armIfClear("q-1", CompletableDeferred<String>())
        token.tryResolveCurrent("answer")
        assertTrue(token.armIfClear("q-2", CompletableDeferred<String>()))
    }
}
