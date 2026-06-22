package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.impl.ExtensionsAreaImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JiraTicketProviderResolverTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The jiraTicketProvider EP is declared in the root plugin.xml, but the :core test sandbox
        // does not load the full plugin descriptor. Register it here via the non-deprecated
        // ExtensionsAreaImpl overload that accepts a Disposable (auto-unregisters at teardown).
        val area = ApplicationManager.getApplication().extensionArea as ExtensionsAreaImpl
        if (area.getExtensionPointIfRegistered<JiraTicketProvider>(JiraTicketProvider.EP_NAME.name) == null) {
            area.registerExtensionPoint(
                JiraTicketProvider.EP_NAME,
                JiraTicketProvider::class.java.name,
                ExtensionPoint.Kind.INTERFACE,
                testRootDisposable,
            )
        }
    }

    fun `test lowest-order JiraTicketProvider wins`() {
        val high = object : JiraTicketProvider {
            override val order: Int get() = 100
            override suspend fun getTicketDetails(ticketId: String): TicketDetails? = null
            override suspend fun getTicketContext(key: String): TicketContext? = null
        }
        val low = object : JiraTicketProvider {
            override val order: Int get() = 5
            override suspend fun getTicketDetails(ticketId: String): TicketDetails? = null
            override suspend fun getTicketContext(key: String): TicketContext? = null
        }
        JiraTicketProvider.EP_NAME.point.registerExtension(high, testRootDisposable)
        JiraTicketProvider.EP_NAME.point.registerExtension(low, testRootDisposable)
        assertSame(low, JiraTicketProvider.getInstance())
    }
}
