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
     * The set of group IDs declared in the project's root plugin.xml.
     *
     * We read directly from the source tree rather than via
     * `classLoader.getResourceAsStream("META-INF/plugin.xml")` because the test
     * classpath under the IntelliJ instrumented runner also contains platform
     * plugin.xml files (the Coverage plugin, etc.), and the classloader would
     * return whichever one it finds first — typically NOT ours.
     */
    private val registeredGroups: Set<String> by lazy {
        parseNotificationGroupIds().toSet()
    }

    private fun parseNotificationGroupIds(): List<String> {
        val pluginXml = resolveProjectRoot()
            .resolve("src/main/resources/META-INF/plugin.xml")
        return pluginXml.inputStream().use { stream ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
            val nodes = doc.getElementsByTagName("notificationGroup")
            buildList {
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
        val ids = parseNotificationGroupIds()
        val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue(duplicates.isEmpty()) {
            "Duplicate notificationGroup ids found in plugin.xml: $duplicates"
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Resolves the root project directory.
     *
     * Walks up from candidate starting points (class file location if available,
     * then `user.dir`) until a directory containing `settings.gradle.kts` is
     * found. The classfile-location approach fails under the IntelliJ
     * instrumented test runner because [java.security.CodeSource.getLocation]
     * returns null, so `user.dir` is used as a fallback.
     */
    private fun resolveProjectRoot(): java.io.File {
        val candidates = buildList {
            runCatching {
                javaClass.protectionDomain?.codeSource?.location?.toURI()?.let {
                    add(java.io.File(it))
                }
            }
            add(java.io.File(System.getProperty("user.dir")))
        }
        for (start in candidates) {
            var dir: java.io.File? = start
            while (dir != null) {
                if (dir.resolve("settings.gradle.kts").exists()) return dir
                dir = dir.parentFile
            }
        }
        error("Could not locate project root (no settings.gradle.kts found walking up from: $candidates)")
    }
}
