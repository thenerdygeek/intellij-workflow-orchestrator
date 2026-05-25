package com.workflow.orchestrator.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.UIUtil
import com.workflow.orchestrator.agent.util.JsEscape
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent

// ── FileType ──

object AgentVisualizationFileType : FileType {
    override fun getName() = "AgentVisualization"
    override fun getDescription() = "Agent Visualization"
    override fun getDefaultExtension() = "agentvis"
    override fun getIcon(): Icon = AllIcons.Actions.Preview
    override fun isBinary() = false
    override fun isReadOnly() = true
}

// ── VirtualFile ──

class AgentVisualizationVirtualFile(
    val visualizationType: String,
    val content: String
) : LightVirtualFile(
    "${visualizationType.replaceFirstChar { it.uppercase() }} Visualization",
    AgentVisualizationFileType,
    ""
) {
    override fun isWritable() = false
}

// ── FileEditor ──

class AgentVisualizationEditor(
    private val vizFile: AgentVisualizationVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val browser = JBCefBrowser.createBuilder()
        .build()

    // Keep a reference to the load handler so dispose() can remove it.
    // JBCefBrowser.dispose() does NOT cascade-remove handlers registered via
    // addLoadHandler(handler, cefBrowser) — callers must remove them explicitly.
    // Failing to do so leaks the handler and its closure until IDE shutdown.
    // Closes audit finding agent-ui:F-7.
    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
            if (frame?.isMain == true) {
                injectVisualization(b)
            }
        }
    }

    init {
        // Load the React app via scheme handler (same as main chat panel).
        // Use the shared registrar so we don't stomp on AgentCefPanel's
        // upload-aware factory. The registrar's dispatching factory routes
        // /upload/* to whatever handler factory the active chat panel has
        // installed and falls through to CefResourceSchemeHandler for static
        // assets (which is all this editor needs).
        WorkflowAgentSchemeRegistrar.ensureRegistered()
        browser.loadURL(CefResourceSchemeHandler.BASE_URL + "index.html")

        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
    }

    private fun injectVisualization(cefBrowser: CefBrowser?) {
        val isDark = UIUtil.isUnderDarcula()
        val c = AgentColors
        // Apply theme first
        val vars = if (isDark) mapOf(
            "bg" to c.hex(c.panelBg), "fg" to c.hex(c.primaryText),
            "fg-secondary" to c.hex(c.secondaryText), "fg-muted" to c.hex(c.mutedText),
            "border" to c.hex(c.border), "code-bg" to c.hex(c.codeBg),
            "success" to c.hex(c.success), "error" to c.hex(c.error),
            "warning" to c.hex(c.warning), "link" to c.hex(c.linkText),
            "hover-overlay" to "rgba(255,255,255,0.03)",
            "hover-overlay-strong" to "rgba(255,255,255,0.05)"
        ) else mapOf(
            "bg" to "#FFFFFF", "fg" to "#1E293B",
            "fg-secondary" to "#475569", "fg-muted" to "#64748B",
            "border" to "#E2E8F0", "code-bg" to "#F1F5F9",
            "success" to "#16A34A", "error" to "#DC2626",
            "warning" to "#D97706", "link" to "#2563EB",
            "hover-overlay" to "rgba(0,0,0,0.03)",
            "hover-overlay-strong" to "rgba(0,0,0,0.05)"
        )

        val jsObj = vars.entries.joinToString(",") { "'${it.key}':'${it.value}'" }
        val isDarkJs = if (isDark) "true" else "false"

        val escapedContent = JsEscape.escapeForJsString(vizFile.content)
        val escapedType = JsEscape.escapeForJsString(vizFile.visualizationType)

        // Poll for React bridge readiness — initBridge() registers functions on window
        // after React mounts (useEffect), which is AFTER onLoadEnd fires.
        // Poll every 50ms up to 3s for the bridge to be ready.
        //
        // Editor-tab mode: `setEditorTabMode(true)` flips the chatStore flag so App.tsx
        // hides TopBar/InputBar/etc., RichBlock drops its chrome, and ArtifactRenderer's
        // iframe fills the pane. Toggled BEFORE appendToken so the message renders
        // straight into fullscreen layout (no flash of chat-shell chrome).
        val js = """
            (function waitForBridge(attempt) {
                if (typeof appendToken === 'function') {
                    applyTheme({$jsObj});
                    if (typeof setPrismTheme === 'function') setPrismTheme($isDarkJs);
                    if (typeof setMermaidTheme === 'function') setMermaidTheme($isDarkJs);
                    if (typeof setEditorTabMode === 'function') setEditorTabMode(true);
                    clearChat();
                    appendToken('```$escapedType\n$escapedContent\n' + '```');
                    endStream();
                } else if (attempt < 60) {
                    setTimeout(function() { waitForBridge(attempt + 1); }, 50);
                }
            })(0);
        """.trimIndent()
        cefBrowser?.executeJavaScript(js, CefResourceSchemeHandler.BASE_URL, 0)
    }

    override fun getComponent(): JComponent = browser.component
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName() = "Visualization"
    override fun isValid() = true
    override fun isModified() = false
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = vizFile

    override fun dispose() {
        // Remove the load handler before disposing the browser — JBCefBrowser.dispose()
        // does not cascade-remove handlers added via addLoadHandler(handler, cefBrowser).
        browser.jbCefClient.removeLoadHandler(loadHandler, browser.cefBrowser)
        browser.dispose()
    }

    companion object {
        /**
         * Opens a visualization in an IDE editor tab.
         * Called from AgentCefPanel's onOpenInEditorTab callback.
         */
        fun openVisualization(project: Project, type: String, content: String) {
            val vizFile = AgentVisualizationVirtualFile(type, content)
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(vizFile, true)
            }
        }
    }
}

// ── FileEditorProvider ──

class AgentVisualizationEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is AgentVisualizationVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return AgentVisualizationEditor(file as AgentVisualizationVirtualFile)
    }
    override fun getEditorTypeId() = "AgentVisualizationEditor"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
