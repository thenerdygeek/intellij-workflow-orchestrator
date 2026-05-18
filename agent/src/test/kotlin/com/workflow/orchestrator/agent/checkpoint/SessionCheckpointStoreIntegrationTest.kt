package com.workflow.orchestrator.agent.checkpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SessionCheckpointStoreIntegrationTest {

    @Test
    fun `three-message flow with create edit delete revert`(@TempDir tmp: java.nio.file.Path) {
        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        val foo = File(tmp.toFile(), "src/Foo.kt").apply { parentFile.mkdirs(); writeText("v0") }
        val bar = File(tmp.toFile(), "src/Bar.kt").absolutePath
        val baz = File(tmp.toFile(), "src/Baz.kt").apply { parentFile.mkdirs(); writeText("baz-v0") }

        // ── msg 100: edit Foo, create Bar ─────────────────────────────
        store.beginUserMessage(100L, "first user msg")
        store.captureIfFirstTouch(100L, foo.absolutePath)
        foo.writeText("v1")
        store.captureIfFirstTouch(100L, bar)
        File(bar).writeText("bar-v0")

        // ── msg 200: edit Foo again, edit Baz ─────────────────────────
        store.beginUserMessage(200L, "second user msg")
        store.captureIfFirstTouch(200L, foo.absolutePath)
        foo.writeText("v2")
        store.captureIfFirstTouch(200L, baz.absolutePath)
        baz.writeText("baz-v1")

        val aggBefore = store.aggregateDiff()
        assertEquals(3, aggBefore.files.size)

        // ── Revert to msg 200: undo msg-200's edits only ──────────────
        val r = store.revertToMessage(200L)
        assertEquals("second user msg", r.userText)
        assertEquals("v1", foo.readText())                       // Foo back to msg-100's final state
        assertEquals("bar-v0", File(bar).readText())             // Bar untouched (created at msg 100, not deleted)
        assertEquals("baz-v0", baz.readText())                   // Baz restored to its pre-msg-200 state
        assertEquals(listOf(100L), store.listMessageCheckpoints().map { it.messageTs })

        // ── Revert to msg 100 (now the only checkpoint): full session revert ──
        val r2 = store.revertToMessage(100L)
        assertEquals("first user msg", r2.userText)
        assertEquals("v0", foo.readText())
        assertFalse(File(bar).exists(), "Bar was created at msg 100, should now be deleted")
        // Baz was first touched at msg 200; msg 200 dir is already gone, so Baz is not tracked here.
        assertEquals("baz-v0", baz.readText())
        assertTrue(store.listMessageCheckpoints().isEmpty())
    }
}
