package com.workflow.orchestrator.pullrequest.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.prreview.PrReviewFinding
import com.workflow.orchestrator.core.prreview.PrReviewFindingsStore
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.pullrequest.service.PrReviewSessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultListModel
import javax.swing.JButton

class AiReviewTabPanel(
    private val project: Project,
    private val projectKey: String,
    private val repoSlug: String,
    private val prId: Int,
    private val onRunReviewClicked: () -> Unit,
) : JBPanel<AiReviewTabPanel>(BorderLayout()), AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listModel = DefaultListModel<PrReviewFinding>()
    private val list = JBList(listModel).apply { cellRenderer = FindingRowRenderer() }

    private val statusLabel = JBLabel("").apply { border = JBUI.Borders.empty(4, 12) }

    private val runButton = JButton("Run AI review").apply { addActionListener { onRunReviewClicked() } }
    private val refreshButton = JButton("Refresh").apply { addActionListener { refresh() } }
    private val pushButton = JButton("Push selected").apply { addActionListener { pushSelected() } }
    private val pushAllButton = JButton("Push all kept").apply { addActionListener { pushAllKept() } }
    private val discardButton = JButton("Discard selected").apply { addActionListener { discardSelected() } }

    private var viewModel: AiReviewViewModel? = null
    private val registry = project.getService(PrReviewSessionRegistry::class.java)

    init {
        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(runButton)
            add(refreshButton)
            add(pushButton)
            add(pushAllButton)
            add(discardButton)
            add(statusLabel)
        }
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        bindSessionIfExists()
    }

    private fun bindSessionIfExists() {
        val prKey = "$projectKey/$repoSlug/PR-$prId"
        val entry = registry?.get(prKey)
        if (entry == null) {
            statusLabel.text = "No AI review run for this PR yet. Click \"Run AI review\" to start."
            listModel.clear()
            return
        }
        val store = project.getService(PrReviewFindingsStore::class.java)
        val service = project.getService(BitbucketService::class.java)
        if (store == null || service == null) {
            statusLabel.text = "Services not available."
            return
        }
        val vm = AiReviewViewModel(store, service, projectKey, repoSlug, prId, entry.sessionId).apply {
            addChangeListener {
                ApplicationManager.getApplication().invokeLater {
                    listModel.clear()
                    findings.forEach { listModel.addElement(it) }
                    statusLabel.text = lastError ?: "${findings.size} findings (session ${entry.sessionId}, ${entry.status})"
                }
            }
        }
        viewModel = vm
        refresh()
    }

    fun onSessionChanged() { bindSessionIfExists() }

    private fun refresh() {
        val vm = viewModel ?: return
        scope.launch { vm.refresh() }
    }

    private fun pushSelected() {
        val vm = viewModel ?: return
        val sel = list.selectedValue ?: return
        scope.launch { vm.pushFinding(sel) }
    }

    private fun pushAllKept() {
        val vm = viewModel ?: return
        scope.launch { vm.pushAllKept() }
    }

    private fun discardSelected() {
        val vm = viewModel ?: return
        val sel = list.selectedValue ?: return
        scope.launch { vm.discard(sel.id) }
    }

    override fun close() { scope.cancel() }
}
