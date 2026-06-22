package com.workflow.orchestrator.core.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.impl.ExtensionsAreaImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.model.ServiceType

class WorkflowConfigOverrideTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The workflowConfig EP is declared in the root plugin.xml, but the :core test sandbox
        // does not load the full plugin descriptor. Register it here via the non-deprecated
        // ExtensionsAreaImpl overload that accepts a Disposable (auto-unregisters at teardown).
        val area = ApplicationManager.getApplication().extensionArea as ExtensionsAreaImpl
        if (area.getExtensionPointIfRegistered<WorkflowConfig>(WorkflowConfig.EP_NAME.name) == null) {
            area.registerExtensionPoint(
                WorkflowConfig.EP_NAME,
                WorkflowConfig::class.java.name,
                ExtensionPoint.Kind.INTERFACE,
                testRootDisposable,
            )
        }
    }

    fun `test lower-order WorkflowConfig override wins over the default`() {
        // Competitor: high-order provider (mimics DefaultWorkflowConfig) — must NOT win.
        val highOrderCompetitor = object : WorkflowConfig {
            override val order: Int get() = Int.MAX_VALUE
            override fun baseUrl(service: ServiceType): String =
                if (service == ServiceType.JIRA) "https://jira.default.example/" else ""
        }
        // Override: low-order provider (order=0) — must win.
        val override = object : WorkflowConfig {
            override val order: Int get() = 0
            override fun baseUrl(service: ServiceType): String =
                if (service == ServiceType.JIRA) "https://jira.company-b.example/" else ""
        }
        // Registration order must NOT matter — that's the point of the ordering test.
        WorkflowConfig.EP_NAME.point.registerExtension(highOrderCompetitor, testRootDisposable)
        WorkflowConfig.EP_NAME.point.registerExtension(override, testRootDisposable)
        // The order=0 override must win over the order=Int.MAX_VALUE competitor.
        assertEquals("https://jira.company-b.example/", WorkflowConfig.resolve().baseUrl(ServiceType.JIRA))
    }
}
