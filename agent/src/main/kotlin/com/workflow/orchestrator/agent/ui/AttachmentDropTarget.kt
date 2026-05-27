package com.workflow.orchestrator.agent.ui

import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.dnd.DropTargetListener
import java.io.File

/**
 * AWT DropTarget that bridges OS file drags into the agent chat. JCEF does not
 * deliver OS file drops to the embedded Chromium layer, so we intercept them on
 * the Swing component. Drag feedback is driven from here (onDropActive) because
 * the browser never sees a CSS :drag-over state for OS drags.
 */
class AttachmentDropTarget(
    private val onDropActive: (Boolean) -> Unit,
    private val onFilesDropped: (List<File>) -> Unit,
) : DropTargetListener {

    /** Pure, unit-testable decision core (no AWT event objects). */
    class DropHandler(
        private val onDropActive: (Boolean) -> Unit,
        private val onFilesDropped: (List<File>) -> Unit,
    ) {
        fun onDragEnter(hasFiles: Boolean): Boolean {
            if (!hasFiles) return false
            onDropActive(true); return true
        }
        fun onDragExit() = onDropActive(false)
        fun onDrop(files: List<File>) { onDropActive(false); onFilesDropped(files) }
    }

    private val core = DropHandler(onDropActive, onFilesDropped)

    private fun offersFiles(flavors: Array<DataFlavor>) = flavors.any { it == DataFlavor.javaFileListFlavor }

    override fun dragEnter(e: DropTargetDragEvent) {
        if (core.onDragEnter(offersFiles(e.currentDataFlavors))) e.acceptDrag(DnDConstants.ACTION_COPY)
        else e.rejectDrag()
    }
    override fun dragOver(e: DropTargetDragEvent) {
        if (offersFiles(e.currentDataFlavors)) e.acceptDrag(DnDConstants.ACTION_COPY) else e.rejectDrag()
    }
    override fun dropActionChanged(e: DropTargetDragEvent) {}
    override fun dragExit(e: DropTargetEvent) { core.onDragExit() }

    override fun drop(e: DropTargetDropEvent) {
        if (!offersFiles(e.currentDataFlavors)) { e.rejectDrop(); core.onDragExit(); return }
        e.acceptDrop(DnDConstants.ACTION_COPY)
        val files = try {
            @Suppress("UNCHECKED_CAST")
            (e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
        } catch (ex: Exception) {
            emptyList()
        }
        core.onDrop(files)
        e.dropComplete(true)
    }

    /** Installs this listener on [component] as a DropTarget. */
    fun installOn(component: java.awt.Component) {
        DropTarget(component, DnDConstants.ACTION_COPY, this, true)
    }
}
