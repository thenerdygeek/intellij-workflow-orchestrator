package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AttachmentDropTargetTest {
    private val active = mutableListOf<Boolean>()
    private val dropped = mutableListOf<List<File>>()
    private val handler = AttachmentDropTarget.DropHandler(
        onDropActive = { active += it },
        onFilesDropped = { dropped += it },
    )

    @Test fun `dragEnter with files sets active true`() {
        assertTrue(handler.onDragEnter(hasFiles = true))
        assertEquals(listOf(true), active)
    }

    @Test fun `dragEnter without files is rejected and no overlay`() {
        assertEquals(false, handler.onDragEnter(hasFiles = false))
        assertTrue(active.isEmpty())
    }

    @Test fun `dragExit clears active`() {
        handler.onDragEnter(hasFiles = true)
        handler.onDragExit()
        assertEquals(listOf(true, false), active)
    }

    @Test fun `drop clears active and forwards files`() {
        val files = listOf(File("/tmp/a.pdf"))
        handler.onDrop(files)
        assertEquals(listOf(false), active)
        assertEquals(listOf(files), dropped)
    }
}
