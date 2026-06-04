package com.workflow.orchestrator.agent.loop

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * environment_details dedup: only the latest <environment_details> block should remain in
 * history. Stripping removes the block from prior messages while retaining the user's text
 * and the decoupled time stamp, and persists the edit via onHistoryOverwrite.
 */
class ContextManagerEnvStripTest {

    private val ENV = "<environment_details>\n# Current Mode\nACT MODE\n# Open Tabs\nFoo.kt\n</environment_details>"

    @Test
    fun `strip removes env block from a user message but keeps text and time`() = runTest {
        val cm = ContextManager(maxInputTokens = 100_000)
        var persisted: List<*>? = null
        cm.onHistoryOverwrite = { msgs, _ -> persisted = msgs }

        cm.addUserMessage("do the thing\n\nCurrent time: 1/1/2026, 1:10:00 AM (UTC)\n\n$ENV")
        cm.addToolResult("c1", "result body", isError = false)

        cm.stripStaleEnvironmentDetails()

        val userMsg = cm.getMessages().first { it.role == "user" }
        assertFalse(userMsg.content!!.contains("<environment_details>"), "env block must be stripped")
        assertTrue(userMsg.content!!.contains("do the thing"), "user text retained")
        assertTrue(userMsg.content!!.contains("Current time:"), "decoupled time stamp retained")
        assertNotNull(persisted, "a change must persist via onHistoryOverwrite")
    }

    @Test
    fun `strip is a no-op and does not persist when no env blocks exist`() = runTest {
        val cm = ContextManager(maxInputTokens = 100_000)
        var persistCount = 0
        cm.onHistoryOverwrite = { _, _ -> persistCount++ }

        cm.addUserMessage("hello, no env here")
        cm.stripStaleEnvironmentDetails()

        assertEquals(0, persistCount, "no env block → no rewrite")
    }

    @Test
    fun `strip leaves only the newest env block after a second user turn`() = runTest {
        val cm = ContextManager(maxInputTokens = 100_000)
        cm.onHistoryOverwrite = { _, _ -> }

        // First user turn carries an env block.
        cm.addUserMessage("turn one\n\n$ENV")
        // Second user turn: strip prior, then add fresh env (simulates withEnvDetails order).
        cm.stripStaleEnvironmentDetails()
        cm.addUserMessage("turn two\n\n$ENV")

        val envCount = cm.getMessages().count { it.content?.contains("<environment_details>") == true }
        assertEquals(1, envCount, "exactly one (the latest) env block survives")
        assertTrue(cm.getMessages().last { it.role == "user" }.content!!.contains("turn two"))
    }
}
