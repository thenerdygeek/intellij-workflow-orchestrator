package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.workflow.orchestrator.cody.protocol.ProtocolTextDocument
import java.util.concurrent.ConcurrentHashMap

class CodyDocumentSyncListener : EditorFactoryListener {

    private val log = Logger.getInstance(CodyDocumentSyncListener::class.java)
    private val changeListeners = ConcurrentHashMap<String, CodyDocumentChangeListener>()

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val document = editor.document
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return
        val project = editor.project ?: return

        val manager = try {
            CodyAgentManager.getInstance(project)
        } catch (e: Exception) { return }

        if (!manager.isRunning()) return

        val uri = "file://${vFile.path}"
        try {
            val server = manager.getServerOrNull() ?: return
            server.textDocumentDidOpen(
                ProtocolTextDocument(
                    uri = uri,
                    content = document.text
                )
            )
            log.debug("Sent didOpen for $uri")

            // Register per-editor change listener for debounced didChange
            val changeListener = CodyDocumentChangeListener(project, uri)
            document.addDocumentListener(changeListener)
            changeListeners[uri] = changeListener
        } catch (e: Exception) {
            log.debug("Failed to send didOpen", e)
        }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val document = editor.document
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return
        val project = editor.project ?: return

        val manager = try {
            CodyAgentManager.getInstance(project)
        } catch (e: Exception) { return }

        if (!manager.isRunning()) return

        val uri = "file://${vFile.path}"
        try {
            val server = manager.getServerOrNull() ?: return
            server.textDocumentDidClose(ProtocolTextDocument(uri = uri))
            log.debug("Sent didClose for $uri")

            // Unregister change listener
            changeListeners.remove(uri)?.dispose()
        } catch (e: Exception) {
            log.debug("Failed to send didClose", e)
        }
    }
}
