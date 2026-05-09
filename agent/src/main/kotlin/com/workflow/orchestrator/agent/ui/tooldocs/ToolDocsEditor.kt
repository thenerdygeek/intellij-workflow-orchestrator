package com.workflow.orchestrator.agent.ui.tooldocs

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
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
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.tools.docs.ToolDocPayloadBuilder
import com.workflow.orchestrator.agent.ui.AgentColors
import com.workflow.orchestrator.agent.ui.CefResourceSchemeHandler
import com.workflow.orchestrator.agent.ui.WorkflowAgentSchemeRegistrar
import com.workflow.orchestrator.agent.util.JsEscape
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent

// ── FileType ──

object ToolDocsFileType : FileType {
    override fun getName() = "ToolDocs"
    override fun getDescription() = "Agent Tool Documentation"
    override fun getDefaultExtension() = "tooldocs"
    override fun getIcon(): Icon = AllIcons.Toolwindows.Documentation
    override fun isBinary() = false
    override fun isReadOnly() = true
}

// ── VirtualFile ──

/**
 * One marker per tool — `FileEditorManager.openFile` deduplicates by VirtualFile
 * identity, so a fresh instance per `(project, toolName)` is required for the
 * "open multiple tool docs in side-by-side tabs" flow. Equality compares on
 * project + toolName so re-opening the same tool re-uses an existing tab.
 */
class ToolDocsVirtualFile(
    val ownerProject: Project,
    val toolName: String,
) : LightVirtualFile(
    "Tool: $toolName",
    ToolDocsFileType,
    ""
) {
    override fun isWritable() = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolDocsVirtualFile) return false
        return ownerProject == other.ownerProject && toolName == other.toolName
    }

    override fun hashCode(): Int {
        var result = ownerProject.hashCode()
        result = 31 * result + toolName.hashCode()
        return result
    }
}

// ── FileEditor ──

class ToolDocsEditor(
    private val project: Project,
    private val docsFile: ToolDocsVirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val browser = JBCefBrowser.createBuilder().build()

    init {
        // Reuse the shared registrar so we don't stomp on AgentCefPanel's upload-aware
        // factory (same pattern as AgentVisualizationEditor / AgentPlanEditor).
        WorkflowAgentSchemeRegistrar.ensureRegistered()
        browser.loadURL(CefResourceSchemeHandler.BASE_URL + "tool-docs.html")

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    injectPayload(b)
                }
            }
        }, browser.cefBrowser)
    }

    private fun injectPayload(cefBrowser: CefBrowser?) {
        val toolName = docsFile.toolName
        val payloadJson = try {
            val service = project.getService(AgentService::class.java)
            if (service == null) {
                LOG.warn("ToolDocsEditor: AgentService not available — agent disabled?")
                buildErrorPayload(toolName, "AgentService unavailable — is the agent enabled?")
            } else {
                ToolDocPayloadBuilder.buildJson(toolName, service.registry)
                    ?: buildErrorPayload(toolName, "Tool '$toolName' has no documentation() block yet.")
            }
        } catch (e: Exception) {
            LOG.warn("ToolDocsEditor: failed to build payload for $toolName", e)
            buildErrorPayload(toolName, "Failed to build doc payload: ${e.message}")
        }

        val isDark = UIUtil.isUnderDarcula()
        val themeObj = themeVarsJs(isDark)
        val escapedJson = JsEscape.escapeForJsString(payloadJson)

        // Poll for the React entry to register `renderToolDoc` — the bridge initializes
        // in a useEffect AFTER onLoadEnd fires, so a direct call would race.
        val js = """
            (function waitForToolDoc(attempt) {
                if (typeof window.renderToolDoc === 'function') {
                    if (typeof window.applyTheme === 'function') window.applyTheme({$themeObj});
                    window.renderToolDoc('$escapedJson');
                } else if (attempt < 60) {
                    setTimeout(function() { waitForToolDoc(attempt + 1); }, 50);
                } else {
                    console.error('ToolDocsEditor: renderToolDoc not registered after 3s');
                }
            })(0);
        """.trimIndent()
        cefBrowser?.executeJavaScript(js, "", 0)
    }

    private fun themeVarsJs(isDark: Boolean): String {
        val c = AgentColors
        val vars: Map<String, String> = if (isDark) mapOf(
            "bg" to c.hex(c.panelBg), "fg" to c.hex(c.primaryText),
            "fg-secondary" to c.hex(c.secondaryText), "fg-muted" to c.hex(c.mutedText),
            "border" to c.hex(c.border), "code-bg" to c.hex(c.codeBg),
            "accent" to "#6cb0e0",
            "success" to c.hex(c.success), "error" to c.hex(c.error), "warning" to c.hex(c.warning),
        ) else mapOf(
            "bg" to "#FFFFFF", "fg" to "#1E293B",
            "fg-secondary" to "#475569", "fg-muted" to "#64748B",
            "border" to "#E2E8F0", "code-bg" to "#F1F5F9",
            "accent" to "#2563EB",
            "success" to "#16A34A", "error" to "#DC2626", "warning" to "#D97706",
        )
        return vars.entries.joinToString(",") { (k, v) -> "'$k':'$v'" }
    }

    private fun buildErrorPayload(toolName: String, message: String): String =
        """{"toolName":${jsonString(toolName)},"error":${jsonString(message)}}"""

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    override fun getComponent(): JComponent = browser.component
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName() = "Tool Docs"
    override fun isValid() = true
    override fun isModified() = false
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = docsFile

    override fun dispose() {
        browser.dispose()
    }

    companion object {
        private val LOG = Logger.getInstance(ToolDocsEditor::class.java)

        /** Opens (or focuses) the documentation tab for [toolName] in the IDE. */
        fun open(project: Project, toolName: String) {
            val vf = ToolDocsVirtualFile(project, toolName)
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }
    }
}

// ── FileEditorProvider ──

class ToolDocsEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is ToolDocsVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return ToolDocsEditor(project, file as ToolDocsVirtualFile)
    }
    override fun getEditorTypeId() = "ToolDocsEditor"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
