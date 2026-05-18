package com.workflow.orchestrator.agent.checkpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SessionCheckpointStoreTest {

    @Test
    fun `beginUserMessage creates msg dir with meta json containing userText and ts`(@TempDir tmp: java.nio.file.Path) {
        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        store.beginUserMessage(messageTs = 12345L, userText = "fix the bug")

        val msgDir = File(tmp.toFile(), "checkpoints/msg-12345")
        assertTrue(msgDir.isDirectory, "msg-12345 dir should exist")

        val meta = store.listMessageCheckpoints()
        assertEquals(1, meta.size)
        assertEquals(12345L, meta[0].messageTs)
        assertEquals("fix the bug", meta[0].userText)
    }

    @Test
    fun `listMessageCheckpoints returns sorted by ts ascending`(@TempDir tmp: java.nio.file.Path) {
        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        store.beginUserMessage(300L, "third")
        store.beginUserMessage(100L, "first")
        store.beginUserMessage(200L, "second")

        val sorted = store.listMessageCheckpoints().map { it.messageTs }
        assertEquals(listOf(100L, 200L, 300L), sorted)
    }

    @Test
    fun `captureIfFirstTouch copies file bytes on first call only`(@TempDir tmp: java.nio.file.Path) {
        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        store.beginUserMessage(100L, "edit foo")

        val foo = File(tmp.toFile(), "src/Foo.kt").apply { parentFile.mkdirs(); writeText("original") }
        store.captureIfFirstTouch(100L, foo.absolutePath)

        val snap = File(tmp.toFile(), "checkpoints/msg-100/files/${foo.absolutePath.trimStart('/')}")
        assertTrue(snap.exists(), "snapshot file should exist")
        assertEquals("original", snap.readText())

        // Mutate; second call should NOT overwrite
        foo.writeText("mutated")
        store.captureIfFirstTouch(100L, foo.absolutePath)
        assertEquals("original", snap.readText(), "snapshot must reflect pre-edit state from first capture")
    }

    @Test
    fun `captureIfFirstTouch on non-existent file marks it as created in meta`(@TempDir tmp: java.nio.file.Path) {
        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        store.beginUserMessage(100L, "create bar")
        val bar = File(tmp.toFile(), "src/Bar.kt").absolutePath  // does NOT exist on disk yet

        store.captureIfFirstTouch(100L, bar)

        val meta = store.listMessageCheckpoints().first()
        assertTrue(meta.createdPaths.contains(bar), "created path should be tracked in meta")
    }

    @Test
    fun `clear removes all checkpoint dirs`(@TempDir tmp: java.nio.file.Path) {
        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        store.beginUserMessage(100L, "a"); store.beginUserMessage(200L, "b")
        assertEquals(2, store.listMessageCheckpoints().size)

        store.clear()

        assertEquals(0, store.listMessageCheckpoints().size)
    }

    @Test
    fun `aggregateDiff with no checkpoints returns zero`(@TempDir tmp: java.nio.file.Path) {
        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        val agg = store.aggregateDiff()
        assertEquals(0, agg.totalAdded); assertEquals(0, agg.totalRemoved); assertTrue(agg.files.isEmpty())
    }

    @Test
    fun `aggregateDiff uses earliest snapshot as per-file baseline`(@TempDir tmp: java.nio.file.Path) {
        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        val foo = File(tmp.toFile(), "src/Foo.kt").apply { parentFile.mkdirs(); writeText("a\nb\nc") }

        // msg 100: first touch — baseline captured as "a\nb\nc"
        store.beginUserMessage(100L, "edit foo")
        store.captureIfFirstTouch(100L, foo.absolutePath)
        foo.writeText("a\nb\nc\nd")  // simulate agent adding line d

        // msg 200: second touch — the pre-state "a\nb\nc\nd" is captured, but baseline is still msg-100's "a\nb\nc"
        store.beginUserMessage(200L, "edit foo again")
        store.captureIfFirstTouch(200L, foo.absolutePath)
        foo.writeText("a\nb\nc\nd\ne")  // current state

        val agg = store.aggregateDiff()
        assertEquals(1, agg.files.size)
        assertEquals(2, agg.files[0].added)  // "d" and "e" added vs baseline "a\nb\nc"
        assertEquals(0, agg.files[0].removed)
        assertEquals(2, agg.totalAdded); assertEquals(0, agg.totalRemoved)
    }

    @Test
    fun `aggregateDiff treats createdPaths as fully added`(@TempDir tmp: java.nio.file.Path) {
        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        store.beginUserMessage(100L, "create bar")
        val bar = File(tmp.toFile(), "src/Bar.kt").absolutePath
        store.captureIfFirstTouch(100L, bar)  // file does not exist yet — recorded as created
        File(bar).apply { parentFile.mkdirs(); writeText("line1\nline2\nline3") }  // simulate agent create

        val agg = store.aggregateDiff()
        assertEquals(1, agg.files.size)
        assertEquals(FileStatus.CREATED, agg.files[0].status)
        assertEquals(3, agg.files[0].added)
        assertEquals(0, agg.files[0].removed)
    }

    @Test
    fun `revertToMessage restores files to earliest snapshot and deletes created files`(@TempDir tmp: java.nio.file.Path) {
        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        val foo = File(tmp.toFile(), "src/Foo.kt").apply { parentFile.mkdirs(); writeText("original") }
        val bar = File(tmp.toFile(), "src/Bar.kt").absolutePath  // will be created at msg 200

        // msg 100: edit Foo
        store.beginUserMessage(100L, "first user msg")
        store.captureIfFirstTouch(100L, foo.absolutePath)
        foo.writeText("edited-by-msg-100")

        // msg 200: edit Foo again, create Bar
        store.beginUserMessage(200L, "second user msg")
        store.captureIfFirstTouch(200L, foo.absolutePath)
        foo.writeText("edited-by-msg-200")
        store.captureIfFirstTouch(200L, bar)
        File(bar).writeText("brand new bar")

        // Revert to msg 200 (means: undo everything from msg 200 onwards)
        val result = store.revertToMessage(200L)

        assertEquals("second user msg", result.userText)
        assertEquals("edited-by-msg-100", foo.readText(), "Foo should be restored to msg-200's pre-state == msg-100's final")
        assertFalse(File(bar).exists(), "Bar created at msg 200 should be deleted")
        assertEquals(listOf(foo.absolutePath), result.restoredFiles)
        assertEquals(listOf(bar), result.deletedFiles)

        // msg-200 dir should be gone
        assertEquals(listOf(100L), store.listMessageCheckpoints().map { it.messageTs })
    }

    @Test
    fun `revertToMessage to earliest message restores files to session baseline`(@TempDir tmp: java.nio.file.Path) {
        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        val foo = File(tmp.toFile(), "src/Foo.kt").apply { parentFile.mkdirs(); writeText("ORIGINAL") }
        store.beginUserMessage(100L, "fix it")
        store.captureIfFirstTouch(100L, foo.absolutePath)
        foo.writeText("CHANGED")

        val result = store.revertToMessage(100L)

        assertEquals("ORIGINAL", foo.readText())
        assertEquals("fix it", result.userText)
        assertTrue(store.listMessageCheckpoints().isEmpty())
    }
}
