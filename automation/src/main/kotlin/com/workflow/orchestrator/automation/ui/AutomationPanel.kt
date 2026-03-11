package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.service.*
import kotlinx.coroutines.*
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JPanel

class AutomationPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val tagBuilderService by lazy { project.getService(TagBuilderService::class.java) }
    private val driftDetectorService by lazy { project.getService(DriftDetectorService::class.java) }
    private val conflictDetectorService by lazy { project.getService(ConflictDetectorService::class.java) }

    private val tagStagingPanel: TagStagingPanel
    private val suiteConfigPanel: SuiteConfigPanel
    private val queueStatusPanel: QueueStatusPanel

    init {
        border = JBUI.Borders.empty(4)

        tagStagingPanel = TagStagingPanel(project)
        suiteConfigPanel = SuiteConfigPanel(project)
        queueStatusPanel = QueueStatusPanel(project)

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(queueStatusPanel)
            add(tagStagingPanel)
            add(suiteConfigPanel)
        }

        add(JBScrollPane(contentPanel), BorderLayout.CENTER)

        Disposer.register(this, tagStagingPanel)
        Disposer.register(this, suiteConfigPanel)
        Disposer.register(this, queueStatusPanel)
    }

    override fun dispose() {
        scope.cancel()
    }
}
