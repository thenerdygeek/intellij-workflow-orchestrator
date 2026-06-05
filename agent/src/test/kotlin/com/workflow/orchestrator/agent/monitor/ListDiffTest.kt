package com.workflow.orchestrator.agent.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListDiffTest {

    private data class Item(val key: String, val v: String)

    // ---- basic partition -------------------------------------------------------

    @Test
    fun `added removed retained partition is correct`() {
        val previous = listOf(Item("a", "v1"), Item("b", "v1"))
        val current  = listOf(Item("b", "v2"), Item("c", "v1"))
        val changes  = ListDiff.byKey(previous, current) { it.key }

        assertEquals(setOf("c"), changes.added.map { it.key }.toSet())
        assertEquals(setOf("a"), changes.removed.map { it.key }.toSet())
        assertEquals(setOf("b"), changes.retained.map { it.first.key }.toSet())
    }

    // ---- empty previous → all added --------------------------------------------

    @Test
    fun `empty previous produces all added and no removed or retained`() {
        val current = listOf(Item("x", "1"), Item("y", "2"))
        val changes = ListDiff.byKey(emptyList(), current) { it.key }

        assertEquals(setOf("x", "y"), changes.added.map { it.key }.toSet())
        assertTrue(changes.removed.isEmpty())
        assertTrue(changes.retained.isEmpty())
    }

    // ---- empty current → all removed -------------------------------------------

    @Test
    fun `empty current produces all removed and no added or retained`() {
        val previous = listOf(Item("p", "1"), Item("q", "2"))
        val changes  = ListDiff.byKey(previous, emptyList()) { it.key }

        assertEquals(setOf("p", "q"), changes.removed.map { it.key }.toSet())
        assertTrue(changes.added.isEmpty())
        assertTrue(changes.retained.isEmpty())
    }

    // ---- identical lists → all retained ----------------------------------------

    @Test
    fun `identical lists produce all retained and no added or removed`() {
        val items   = listOf(Item("a", "1"), Item("b", "2"), Item("c", "3"))
        val changes = ListDiff.byKey(items, items.map { it.copy() }) { it.key }

        assertTrue(changes.added.isEmpty())
        assertTrue(changes.removed.isEmpty())
        assertEquals(setOf("a", "b", "c"), changes.retained.map { it.first.key }.toSet())
    }

    // ---- retained pair order: (previous, current) ------------------------------

    @Test
    fun `retained pairs carry previous item first and current item second`() {
        val previous = listOf(Item("k", "old"))
        val current  = listOf(Item("k", "new"))
        val changes  = ListDiff.byKey(previous, current) { it.key }

        assertEquals(1, changes.retained.size)
        val (prev, cur) = changes.retained.first()
        assertEquals("old", prev.v)
        assertEquals("new", cur.v)
    }

    // ---- duplicate keys (associateBy last-wins) --------------------------------

    @Test
    fun `duplicate keys in input do not crash (associateBy last-wins semantics)`() {
        // Both previous and current have the key "dup" twice; last-wins on both sides
        val previous = listOf(Item("dup", "p1"), Item("dup", "p2"))
        val current  = listOf(Item("dup", "c1"), Item("dup", "c2"))

        // Should not throw
        val changes = ListDiff.byKey(previous, current) { it.key }

        // Under last-wins: only one retained entry with key "dup"
        assertTrue(changes.added.isEmpty())
        assertTrue(changes.removed.isEmpty())
        assertEquals(1, changes.retained.size)
        assertEquals("dup", changes.retained.first().first.key)
    }
}
