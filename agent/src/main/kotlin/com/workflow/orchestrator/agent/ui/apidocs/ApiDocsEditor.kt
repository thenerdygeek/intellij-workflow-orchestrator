package com.workflow.orchestrator.agent.ui.apidocs

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
import com.workflow.orchestrator.agent.tools.apidocs.ApiDocPayloadBuilder
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

object ApiDocsFileType : FileType {
    override fun getName() = "ApiDocs"
    override fun getDescription() = "External API Documentation"
    override fun getDefaultExtension() = "apidocs"
    override fun getIcon(): Icon = AllIcons.Toolwindows.Documentation
    override fun isBinary() = false
    override fun isReadOnly() = true
}

// ── VirtualFile ──

/** Single marker — the API-docs page is one combined page, so all instances are equal. */
class ApiDocsVirtualFile(val ownerProject: Project) :
    LightVirtualFile("API Documentation", ApiDocsFileType, "") {
    override fun isWritable() = false
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApiDocsVirtualFile) return false
        return ownerProject == other.ownerProject
    }
    override fun hashCode(): Int = ownerProject.hashCode()
}

// ── FileEditor ──

class ApiDocsEditor(
    @Suppress("unused") private val project: Project,  // kept for parity with ToolDocsEditor + future use
    private val docsFile: ApiDocsVirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val browser = JBCefBrowser.createBuilder().build()

    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
            if (frame?.isMain == true) injectPayload(b)
        }
    }

    init {
        WorkflowAgentSchemeRegistrar.ensureRegistered()
        browser.loadURL(CefResourceSchemeHandler.BASE_URL + "api-docs.html")
        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
    }

    private fun injectPayload(cefBrowser: CefBrowser?) {
        val payloadJson = try {
            ApiDocPayloadBuilder.buildJson()
        } catch (e: Exception) {
            LOG.warn("ApiDocsEditor: failed to build payload", e)
            // JSON-escape the message so a `"`/`\` in e.message can't malform the payload
            // (matches ToolDocsEditor.jsonString — agent-ui error-payload safety).
            """{"families":[],"loadErrors":[{"id":"all","error":${jsonString("Failed to build payload: ${e.message}")}}]}"""
        }
        val isDark = UIUtil.isUnderDarcula()
        val themeObj = themeVarsJs(isDark)
        val escapedJson = JsEscape.escapeForJsString(payloadJson)
        val js = """
            (function waitForApiDocs(attempt) {
                if (typeof window.renderApiDocs === 'function') {
                    if (typeof window.applyTheme === 'function') window.applyTheme({$themeObj});
                    window.renderApiDocs('$escapedJson');
                } else if (attempt < 60) {
                    setTimeout(function() { waitForApiDocs(attempt + 1); }, 50);
                } else {
                    console.error('ApiDocsEditor: renderApiDocs not registered after 3s');
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

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    override fun getComponent(): JComponent = browser.component
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName() = "API Docs"
    override fun isValid() = true
    override fun isModified() = false
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = docsFile

    override fun dispose() {
        // Explicit removal — browser.dispose() does not cascade-remove handlers added via
        // addLoadHandler(handler, cefBrowser) (agent-ui:F-7, same as ToolDocsEditor).
        browser.jbCefClient.removeLoadHandler(loadHandler, browser.cefBrowser)
        browser.dispose()
    }

    companion object {
        private val LOG = Logger.getInstance(ApiDocsEditor::class.java)

        fun open(project: Project) {
            val vf = ApiDocsVirtualFile(project)
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }
    }
}

// ── FileEditorProvider ──

class ApiDocsEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is ApiDocsVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        ApiDocsEditor(project, file as ApiDocsVirtualFile)
    override fun getEditorTypeId() = "ApiDocsEditor"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
