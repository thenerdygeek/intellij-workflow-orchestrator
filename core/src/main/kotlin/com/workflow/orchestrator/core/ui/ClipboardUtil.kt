package com.workflow.orchestrator.core.ui

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Thin wrapper around the AWT system clipboard for "copy text" actions.
 *
 * All workflow panels that expose a "Copy" button should use this utility
 * rather than duplicating `Toolkit.getDefaultToolkit().systemClipboard.setContents(...)`.
 */
object ClipboardUtil {

    /** Copies [text] to the system clipboard. */
    fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
