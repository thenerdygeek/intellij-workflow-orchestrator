package com.workflow.orchestrator.agent.observability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhraseActivityGateTest {

    @Test
    fun `first tick always generates`() {
        val sig = PhraseActivityGate.signature(listOf("read_file"), "thinking...")
        assertTrue(PhraseActivityGate.shouldGenerate(previousSignature = null, signature = sig))
    }

    @Test
    fun `unchanged activity skips generation`() {
        val sig1 = PhraseActivityGate.signature(listOf("read_file"), "same snippet")
        val sig2 = PhraseActivityGate.signature(listOf("read_file"), "same snippet")
        assertEquals(sig1, sig2)
        assertFalse(PhraseActivityGate.shouldGenerate(previousSignature = sig1, signature = sig2))
    }

    @Test
    fun `new tool call re-enables generation`() {
        val before = PhraseActivityGate.signature(listOf("read_file"), "snippet")
        val after = PhraseActivityGate.signature(listOf("read_file", "edit_file"), "snippet")
        assertTrue(PhraseActivityGate.shouldGenerate(previousSignature = before, signature = after))
    }

    @Test
    fun `new stream output re-enables generation`() {
        val before = PhraseActivityGate.signature(listOf("read_file"), "old tail")
        val after = PhraseActivityGate.signature(listOf("read_file"), "new tail")
        assertTrue(PhraseActivityGate.shouldGenerate(previousSignature = before, signature = after))
    }
}
