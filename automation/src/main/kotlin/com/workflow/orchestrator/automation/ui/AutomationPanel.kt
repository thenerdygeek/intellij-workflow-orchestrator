package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.api.DockerRegistryClient
import com.workflow.orchestrator.automation.service.*
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.*
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JPanel

class AutomationPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val settings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bambooClient: BambooApiClient by lazy {
        BambooApiClient(
            baseUrl = settings.state.bambooUrl.orEmpty().trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) }
        )
    }

    private val registryClient: DockerRegistryClient by lazy {
        val registryUrl = settings.state.dockerRegistryUrl.takeUnless { it.isNullOrBlank() }
            ?: settings.state.nexusUrl.orEmpty()
        DockerRegistryClient(
            registryUrl = registryUrl.trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.NEXUS) }
        )
    }

    private val tagBuilderService by lazy { TagBuilderService(bambooClient) }
    private val driftDetectorService by lazy { DriftDetectorService(registryClient) }
    private val conflictDetectorService by lazy { ConflictDetectorService(bambooClient) }

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
