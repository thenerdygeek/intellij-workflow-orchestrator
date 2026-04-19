package com.workflow.orchestrator.core.notifications

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Verifies that every notification group ID referenced in
 * [WorkflowNotificationService] companion constants is declared as a
 * `<notificationGroup id="...">` element in the root plugin.xml.
 *
 * A group that is referenced but not declared causes
 * [com.intellij.notification.NotificationGroupManager.getNotificationGroup]
 * to return null at runtime, silently dropping the notification.
 *
 * The test is a pure static analysis — no IntelliJ platform required.
 */
class NotificationGroupRegistrationTest {

    /**
     * The set of group IDs declared in plugin.xml.
     * Parsed once from the project root resource.
     */
    private val registeredGroups: Set<String> by lazy {
        val pluginXml = javaClass.classLoader
            .getResourceAsStream("META-INF/plugin.xml")
            ?: run {
                // Fall back to reading from the source tree (test classpath may not include it)
                val root = resolveProjectRoot()
                root.resolve("src/main/resources/META-INF/plugin.xml").inputStream()
            }

        pluginXml.use { stream ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
            val nodes = doc.getElementsByTagName("notificationGroup")
            buildSet {
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as Element
                    val id = el.getAttribute("id")
                    if (id.isNotBlank()) add(id)
                }
            }
        }
    }

    /**
     * All group IDs that the service routes notifications through.
     * When a new constant is added to [WorkflowNotificationService], it must
     * also appear here and in plugin.xml or this test fails.
     */
    private val serviceGroupIds = setOf(
        WorkflowNotificationService.GROUP_BUILD,
        WorkflowNotificationService.GROUP_QUALITY,
        WorkflowNotificationService.GROUP_JIRA,
        WorkflowNotificationService.GROUP_AGENT,
        WorkflowNotificationService.GROUP_PR
    )

    @Test
    fun `every WorkflowNotificationService group constant is registered in plugin xml`() {
        val unregistered = serviceGroupIds - registeredGroups
        assertTrue(unregistered.isEmpty()) {
            "The following notification group(s) are used in WorkflowNotificationService " +
                "but NOT declared in plugin.xml — they will silently drop at runtime: $unregistered\n" +
                "Registered groups: $registeredGroups"
        }
    }

    @Test
    fun `plugin xml declares no duplicate notification group ids`() {
        val pluginXml = resolveProjectRoot()
            .resolve("src/main/resources/META-INF/plugin.xml")
            .inputStream()

        val ids = mutableListOf<String>()
        pluginXml.use { stream ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
            val nodes = doc.getElementsByTagName("notificationGroup")
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as Element
                val id = el.getAttribute("id")
                if (id.isNotBlank()) ids.add(id)
            }
        }

        val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue(duplicates.isEmpty()) {
            "Duplicate notificationGroup ids found in plugin.xml: $duplicates"
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Resolves the root project directory by walking up from this test's
     * class file location until we find the root `build.gradle.kts`.
     */
    private fun resolveProjectRoot(): java.io.File {
        var dir = java.io.File(javaClass.protectionDomain.codeSource.location.toURI())
        while (!dir.resolve("settings.gradle.kts").exists() && dir.parentFile != null) {
            dir = dir.parentFile
        }
        return dir
    }
}
