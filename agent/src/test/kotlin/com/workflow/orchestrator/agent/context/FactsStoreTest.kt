package com.workflow.orchestrator.agent.context

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FactsStoreTest {

    private lateinit var store: FactsStore

    @BeforeEach
    fun setUp() {
        store = FactsStore(maxFacts = 50)
    }

    @Test
    fun `record and retrieve facts`() {
        store.record(Fact(FactType.FILE_READ, "/src/Main.kt", "50 lines. Starts with: package com.example", 1))
        store.record(Fact(FactType.EDIT_MADE, "/src/Utils.kt", "Added helper function", 2))

        assertEquals(2, store.size)

        val context = store.toContextString()
        assertTrue(context.contains("<agent_facts>"))
        assertTrue(context.contains("</agent_facts>"))
        assertTrue(context.contains("[FILE_READ] /src/Main.kt"))
        assertTrue(context.contains("[EDIT_MADE] /src/Utils.kt"))
        assertTrue(context.contains("50 lines"))
        assertTrue(context.contains("Added helper function"))
    }

    @Test
    fun `toContextString caps at maxFacts`() {
        val smallStore = FactsStore(maxFacts = 5)
        for (i in 1..10) {
            // Use null path so dedup doesn't apply
            smallStore.record(Fact(FactType.COMMAND_RESULT, null, "Result $i: done", i))
        }

        assertEquals(5, smallStore.size)

        val context = smallStore.toContextString()
        // Should contain only the last 5 (6..10)
        assertFalse(context.contains("Result 1:"), "Result 1 should have been evicted")
        assertFalse(context.contains("Result 5:"), "Result 5 should have been evicted")
        assertTrue(context.contains("Result 6:"))
        assertTrue(context.contains("Result 10:"))
    }

    @Test
    fun `toContextString returns empty string when no facts`() {
        assertEquals("", store.toContextString())
    }

    @Test
    fun `facts are deduped by path and type`() {
        store.record(Fact(FactType.FILE_READ, "/src/Main.kt", "First read: 50 lines", 1))
        store.record(Fact(FactType.FILE_READ, "/src/Main.kt", "Second read: 75 lines", 3))

        assertEquals(1, store.size)

        val context = store.toContextString()
        assertFalse(context.contains("First read"))
        assertTrue(context.contains("Second read: 75 lines"))
    }

    @Test
    fun `different fact types for same path are kept`() {
        store.record(Fact(FactType.FILE_READ, "/src/Main.kt", "Read the file", 1))
        store.record(Fact(FactType.EDIT_MADE, "/src/Main.kt", "Edited the file", 2))
        store.record(Fact(FactType.ERROR_FOUND, "/src/Main.kt", "Found error in file", 3))

        assertEquals(3, store.size)

        val context = store.toContextString()
        assertTrue(context.contains("[FILE_READ] /src/Main.kt: Read the file"))
        assertTrue(context.contains("[EDIT_MADE] /src/Main.kt: Edited the file"))
        assertTrue(context.contains("[ERROR_FOUND] /src/Main.kt: Found error in file"))
    }

    @Test
    fun `token estimate is reasonable`() {
        // Empty store should be 0
        assertEquals(0, store.estimateTokens())

        // Add some facts
        store.record(Fact(FactType.FILE_READ, "/src/Main.kt", "50 lines. Starts with: package com.example", 1))
        store.record(Fact(FactType.EDIT_MADE, "/src/Utils.kt", "Added helper function", 2))

        val tokens = store.estimateTokens()
        assertTrue(tokens > 0, "Token estimate should be positive for non-empty store")
        // The context string is relatively short, so tokens should be reasonable
        assertTrue(tokens < 500, "Token estimate should be reasonable (got $tokens)")
    }

    @Test
    fun `clear removes all facts`() {
        store.record(Fact(FactType.FILE_READ, "/src/Main.kt", "Read", 1))
        store.record(Fact(FactType.EDIT_MADE, "/src/Utils.kt", "Edit", 2))
        assertEquals(2, store.size)

        store.clear()
        assertEquals(0, store.size)
        assertEquals("", store.toContextString())
    }

    @Test
    fun `facts with null path are not deduped`() {
        store.record(Fact(FactType.COMMAND_RESULT, null, "Build succeeded", 1))
        store.record(Fact(FactType.COMMAND_RESULT, null, "Tests passed", 2))

        assertEquals(2, store.size)
        val context = store.toContextString()
        assertTrue(context.contains("Build succeeded"))
        assertTrue(context.contains("Tests passed"))
    }

    @Test
    fun `content is capped at 200 characters in context string`() {
        val longContent = "A".repeat(300)
        store.record(Fact(FactType.DISCOVERY, null, longContent, 1))

        val context = store.toContextString()
        // toContextString calls content.take(200), so the long content should be truncated
        assertFalse(context.contains("A".repeat(300)))
        assertTrue(context.contains("A".repeat(200)))
    }
}
