package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModelFallbackManagerTest {

    private val chain = listOf(
        "anthropic::2024-10-22::claude-opus-4-20250514-thinking",
        "anthropic::2024-10-22::claude-opus-4-20250514",
        "anthropic::2024-10-22::claude-sonnet-4-20250514-thinking",
        "anthropic::2024-10-22::claude-sonnet-4-20250514"
    )

    @Test
    fun `starts on primary model`() {
        val mgr = ModelFallbackManager(chain)
        assertEquals(chain[0], mgr.getCurrentModelId())
        assertTrue(mgr.isPrimary())
    }

    @Test
    fun `onNetworkError advances to next model in chain`() {
        val mgr = ModelFallbackManager(chain)
        val fallback = mgr.onNetworkError()
        assertEquals(chain[1], fallback)
        assertEquals(chain[1], mgr.getCurrentModelId())
        assertFalse(mgr.isPrimary())
    }

    @Test
    fun `onNetworkError walks full chain then returns null`() {
        val mgr = ModelFallbackManager(chain)
        assertEquals(chain[1], mgr.onNetworkError())
        assertEquals(chain[2], mgr.onNetworkError())
        assertEquals(chain[3], mgr.onNetworkError())
        assertNull(mgr.onNetworkError())
    }

    @Test
    fun `onIterationSuccess returns null before threshold`() {
        val mgr = ModelFallbackManager(chain, initialEscalationThreshold = 3)
        mgr.onNetworkError()
        assertNull(mgr.onIterationSuccess())
        assertNull(mgr.onIterationSuccess())
    }

    @Test
    fun `onIterationSuccess returns primary at threshold`() {
        val mgr = ModelFallbackManager(chain, initialEscalationThreshold = 3)
        mgr.onNetworkError()
        mgr.onIterationSuccess()
        mgr.onIterationSuccess()
        val escalation = mgr.onIterationSuccess()
        assertEquals(chain[0], escalation)
        assertTrue(mgr.isPrimary())
    }

    @Test
    fun `onEscalationFailed reverts to previous fallback and uses extended threshold`() {
        val mgr = ModelFallbackManager(chain, initialEscalationThreshold = 3, extendedEscalationThreshold = 6)
        mgr.onNetworkError()
        repeat(3) { mgr.onIterationSuccess() }
        assertTrue(mgr.isPrimary())
        val revert = mgr.onEscalationFailed()
        assertEquals(chain[1], revert)
        assertFalse(mgr.isPrimary())
        repeat(5) { assertNull(mgr.onIterationSuccess()) }
        val escalation = mgr.onIterationSuccess()
        assertEquals(chain[0], escalation)
    }

    @Test
    fun `successful escalation resets to initial threshold`() {
        val mgr = ModelFallbackManager(chain, initialEscalationThreshold = 3, extendedEscalationThreshold = 6)
        mgr.onNetworkError()
        repeat(3) { mgr.onIterationSuccess() }
        mgr.onEscalationFailed()
        repeat(6) { mgr.onIterationSuccess() }
        assertTrue(mgr.isPrimary())
        mgr.onNetworkError()
        repeat(2) { assertNull(mgr.onIterationSuccess()) }
        val escalation = mgr.onIterationSuccess()
        assertEquals(chain[0], escalation)
    }

    @Test
    fun `onIterationSuccess returns null when already on primary`() {
        val mgr = ModelFallbackManager(chain)
        assertNull(mgr.onIterationSuccess())
    }

    @Test
    fun `reset returns to primary`() {
        val mgr = ModelFallbackManager(chain)
        mgr.onNetworkError()
        mgr.onNetworkError()
        assertFalse(mgr.isPrimary())
        mgr.reset()
        assertTrue(mgr.isPrimary())
        assertEquals(chain[0], mgr.getCurrentModelId())
    }

    @Test
    fun `single model chain — onNetworkError returns null immediately`() {
        val mgr = ModelFallbackManager(listOf("only-model"))
        assertNull(mgr.onNetworkError())
    }

    @Test
    fun `empty chain throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelFallbackManager(emptyList())
        }
    }
}
