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
import com.intellij.util.ui.UIUtil
import com.workflow.orchestrator.agent.ui.AgentColors
import com.workflow.orchestrator.agent.ui.CefResourceSchemeHandler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Plan editor — displays implementation plan in an editor tab using a full JCEF browser.
 * Plan mode is ported from Cline: continuous conversation, user-controlled act mode.
 * The plan card with comments is rendered in the JCEF chat panel; this editor tab
 * provides a full-screen view for reviewing longer plans.
 */
class AgentPlanEditor(
    private val project: Project,
    private val planFile: AgentPlanVirtualFile,
    var onApprove: (() -> Unit)? = null,
    var onRevise: ((String) -> Unit)? = null
) : UserDataHolderBase(), FileEditor {

    private val json = Json { encodeDefaults = true }
    private val browser = JBCefBrowser()
    private val approveQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val reviseQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val fileClickQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val commentCountQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    /** Callback to update comment count on the chat panel's plan summary card. */
    var onCommentCountChanged: ((Int) -> Unit)? = null

    init {
        approveQuery.addHandler { _ ->
            try {
                onApprove?.invoke()
            } catch (_: Exception) {
            }
            null
        }

        reviseQuery.addHandler { commentsJson ->
            try {
                onRevise?.invoke(commentsJson)
            } catch (e: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(AgentPlanEditor::class.java)
                    .warn("AgentPlanEditor: failed to handle revision payload", e)
            }
            null
        }

        commentCountQuery.addHandler { countStr ->
            try {
                val count = countStr.toIntOrNull() ?: 0
                onCommentCountChanged?.invoke(count)
            } catch (_: Exception) {}
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

        try {
            org.cef.CefApp.getInstance().registerSchemeHandlerFactory(
                CefResourceSchemeHandler.SCHEME,
                CefResourceSchemeHandler.AUTHORITY
            ) { _, _, _, _ -> CefResourceSchemeHandler() }
        } catch (_: Exception) {
            // Already registered — OK
        }
        browser.loadURL(CefResourceSchemeHandler.BASE_URL + "plan-editor.html")

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

        // Build IDE-derived theme vars so the standalone plan-editor.html matches the IDE theme.
        val isDark = UIUtil.isUnderDarcula()
        val c = AgentColors
        val themeVars: Map<String, String> = if (isDark) mapOf(
            "bg" to c.hex(c.panelBg), "fg" to c.hex(c.primaryText),
            "fg-secondary" to c.hex(c.secondaryText), "fg-muted" to c.hex(c.mutedText),
            "border" to c.hex(c.border), "code-bg" to c.hex(c.codeBg),
            "tool-bg" to "rgba(255,255,255,0.04)",
            "accent" to "#6366f1",
            "accent-read" to c.hex(c.accentRead), "accent-edit" to c.hex(c.accentEdit),
            "badge-read-bg" to c.hex(c.badgeRead), "badge-read-fg" to c.hex(c.badgeReadText),
            "badge-edit-bg" to c.hex(c.badgeEdit), "badge-edit-fg" to c.hex(c.badgeEditText),
            "badge-write-bg" to c.hex(c.badgeWrite), "badge-write-fg" to c.hex(c.badgeWriteText),
            "badge-search-bg" to c.hex(c.badgeSearch), "badge-search-fg" to c.hex(c.badgeSearchText),
            "success" to c.hex(c.success), "error" to c.hex(c.error), "warning" to c.hex(c.warning)
        ) else mapOf(
            "bg" to "#FFFFFF", "fg" to "#1E293B",
            "fg-secondary" to "#475569", "fg-muted" to "#64748B",
            "border" to "#E2E8F0", "code-bg" to "#F1F5F9",
            "tool-bg" to "rgba(0,0,0,0.03)",
            "accent" to "#6366f1",
            "accent-read" to c.hex(c.accentRead), "accent-edit" to c.hex(c.accentEdit),
            "badge-read-bg" to c.hex(c.badgeRead), "badge-read-fg" to c.hex(c.badgeReadText),
            "badge-edit-bg" to c.hex(c.badgeEdit), "badge-edit-fg" to c.hex(c.badgeEditText),
            "badge-write-bg" to c.hex(c.badgeWrite), "badge-write-fg" to c.hex(c.badgeWriteText),
            "badge-search-bg" to c.hex(c.badgeSearch), "badge-search-fg" to c.hex(c.badgeSearchText),
            "success" to c.hex(c.success), "error" to c.hex(c.error), "warning" to c.hex(c.warning)
        )
        val themeObj = themeVars.entries.joinToString(",") { (k, v) -> "'$k':'$v'" }

        val js = """
            window._approvePlan = function() { ${approveQuery.inject("'approved'")} };
            window._revisePlan = function(json) { ${reviseQuery.inject("json")} };
            window._openFile = function(path) { ${fileClickQuery.inject("path")} };
            window._onCommentCountChanged = function(count) { ${commentCountQuery.inject("'' + count")} };
            if (typeof applyTheme === 'function') applyTheme({$themeObj});
            renderPlan('$escaped');
        """.trimIndent()
        cefBrowser?.executeJavaScript(js, "", 0)
    }

    /** Programmatically trigger the Revise action in the plan editor. Called from chat card's Revise button. */
    fun triggerRevise() {
        browser.cefBrowser.executeJavaScript(
            "if (typeof triggerReviseFromHost === 'function') triggerReviseFromHost();", "", 0
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
        commentCountQuery.dispose()
        browser.dispose()
    }
}
