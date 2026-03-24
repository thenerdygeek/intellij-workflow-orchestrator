package com.workflow.orchestrator.agent.ui.plan

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.workflow.orchestrator.agent.AgentService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class AgentPlanEditor(
    private val project: Project,
    private val planFile: AgentPlanVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val json = Json { encodeDefaults = true }
    private val browser = JBCefBrowser()
    private val approveQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val reviseQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val fileClickQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    init {
        approveQuery.addHandler { _ ->
            try {
                AgentService.getInstance(project).currentPlanManager?.approvePlan()
            } catch (_: Exception) {
            }
            null
        }

        reviseQuery.addHandler { commentsJson ->
            try {
                val comments = Json.decodeFromString<Map<String, String>>(commentsJson)
                AgentService.getInstance(project).currentPlanManager?.revisePlan(comments)
            } catch (_: Exception) {
            }
            null
        }

        fileClickQuery.addHandler { filePath ->
            try {
                val basePath = project.basePath ?: return@addHandler null
                // Resolve: try absolute first, then relative to project
                val resolvedFile = java.io.File(filePath).let { f ->
                    if (f.isAbsolute) f else java.io.File(basePath, filePath)
                }
                // Security: verify within project
                if (!resolvedFile.canonicalPath.startsWith(java.io.File(basePath).canonicalPath + java.io.File.separator)) {
                    return@addHandler null
                }
                val vf = LocalFileSystem.getInstance()
                    .findFileByPath(resolvedFile.canonicalPath.replace("\\", "/"))  // VFS uses forward slashes
                if (vf != null) {
                    ApplicationManager.getApplication().invokeLater {
                        FileEditorManager.getInstance(project).openFile(vf, true)
                    }
                }
            } catch (_: Exception) {
            }
            null
        }

        val htmlUrl = javaClass.getResource("/webview/dist/plan-editor.html")
        if (htmlUrl != null) {
            browser.loadURL(htmlUrl.toExternalForm())
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    injectPlanData(b)
                }
            }
        }, browser.cefBrowser)
    }

    private fun injectPlanData(cefBrowser: CefBrowser?) {
        val planJson = json.encodeToString(planFile.currentPlan)
        val escaped = planJson
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        val js = """
            window._approvePlan = function() { ${approveQuery.inject("'approved'")} };
            window._revisePlan = function(json) { ${reviseQuery.inject("json")} };
            window._openFile = function(path) { ${fileClickQuery.inject("path")} };
            renderPlan('$escaped');
        """.trimIndent()
        cefBrowser?.executeJavaScript(js, "", 0)
    }

    fun updatePlanStep(stepId: String, status: String) {
        val safeId = stepId.replace("'", "\\'")
        val safeStatus = status.replace("'", "\\'")
        browser.cefBrowser.executeJavaScript(
            "updatePlanStep('$safeId', '$safeStatus');", "", 0
        )
    }

    override fun getComponent(): JComponent = browser.component
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName() = "Plan"
    override fun isValid() = true
    override fun isModified() = false
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = planFile

    override fun dispose() {
        approveQuery.dispose()
        reviseQuery.dispose()
        fileClickQuery.dispose()
        browser.dispose()
    }
}
