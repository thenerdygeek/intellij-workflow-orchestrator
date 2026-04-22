package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.sonar.SonarFileComponent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the component-key fallback matrix used by sonar(action=local_analysis).
 *
 * Multi-module Maven/Gradle projects key source files as `projectKey:moduleName:pathWithinModule`,
 * which is NOT derivable from repo-relative path alone. These tests lock in the five-stage
 * resolution strategy so that a regression never again silently produces empty per-file reports.
 */
class SonarToolComponentResolutionTest {

    private val tool = SonarTool()
    private val projectKey = "com.example:my-service"

    private fun comp(key: String, path: String) = SonarFileComponent(
        key = key,
        path = path,
        name = path.substringAfterLast('/')
    )

    @Test
    fun `exact path match wins`() {
        val components = listOf(
            comp("$projectKey:order-service:src/main/java/Foo.java", "services/order/src/main/java/Foo.java")
        )
        val result = tool.resolveComponentKey(
            relativePath = "services/order/src/main/java/Foo.java",
            components = components,
            projectKey = projectKey
        )
        assertEquals("$projectKey:order-service:src/main/java/Foo.java", result.key)
        assertNull(result.note, "exact match should not annotate the result")
    }

    @Test
    fun `multi-module Maven — sonar stores module-relative path but user passes repo-relative`() {
        // User passed: services/order/src/main/java/OrderService.java
        // Sonar stored path: src/main/java/OrderService.java (module-relative)
        val components = listOf(
            comp("$projectKey:order-service:src/main/java/OrderService.java", "src/main/java/OrderService.java"),
            comp("$projectKey:payment-service:src/main/java/PaymentService.java", "src/main/java/PaymentService.java")
        )
        val result = tool.resolveComponentKey(
            relativePath = "services/order/src/main/java/OrderService.java",
            components = components,
            projectKey = projectKey
        )
        assertEquals("$projectKey:order-service:src/main/java/OrderService.java", result.key)
        assertNotNull(result.note)
        assertTrue(result.note!!.contains("module-relative"), "note should explain the strategy: was '${result.note}'")
    }

    @Test
    fun `sonar stores repo-prefixed path but user passes module-relative`() {
        // Opposite direction — user passed the shorter path.
        val components = listOf(
            comp("$projectKey:services/order/src/main/java/Foo.java", "services/order/src/main/java/Foo.java")
        )
        val result = tool.resolveComponentKey(
            relativePath = "src/main/java/Foo.java",
            components = components,
            projectKey = projectKey
        )
        assertEquals("$projectKey:services/order/src/main/java/Foo.java", result.key)
        assertNotNull(result.note)
        assertTrue(result.note!!.contains("repo-relative suffix"), "should use longest-suffix match: was '${result.note}'")
    }

    @Test
    fun `unique basename fallback when no path match`() {
        val components = listOf(
            comp("$projectKey:module-a:src/Unique.java", "src/Unique.java"),
            comp("$projectKey:module-b:src/Other.java", "src/Other.java")
        )
        val result = tool.resolveComponentKey(
            relativePath = "totally/unrelated/Unique.java",
            components = components,
            projectKey = projectKey
        )
        assertEquals("$projectKey:module-a:src/Unique.java", result.key)
        assertNotNull(result.note)
        assertTrue(result.note!!.contains("basename"), "should use basename-unique fallback")
    }

    @Test
    fun `ambiguous basename across modules falls back with warning`() {
        // Same filename in two modules — basename match is NOT safe.
        val components = listOf(
            comp("$projectKey:module-a:src/Config.java", "module-a/src/Config.java"),
            comp("$projectKey:module-b:src/Config.java", "module-b/src/Config.java")
        )
        val result = tool.resolveComponentKey(
            relativePath = "totally/unrelated/Config.java",
            components = components,
            projectKey = projectKey
        )
        // Legacy construction chosen (no authoritative match possible)
        assertEquals("$projectKey:totally/unrelated/Config.java", result.key)
        assertNotNull(result.note)
        assertTrue(result.note!!.contains("ambiguous"), "warning should flag cross-module collision")
    }

    @Test
    fun `empty component list falls back to legacy concatenation with no note`() {
        // Simulates: component_tree call failed or returned nothing.
        val result = tool.resolveComponentKey(
            relativePath = "src/main/java/Foo.java",
            components = emptyList(),
            projectKey = projectKey
        )
        assertEquals("$projectKey:src/main/java/Foo.java", result.key)
        assertNull(result.note, "empty-list fallback preserves legacy single-module behavior silently")
    }

    @Test
    fun `longest suffix wins when multiple components could match`() {
        // Two modules have a file that could suffix-match the user's path; pick the longer one
        // since longer = more specific = less likely to be a false positive.
        val components = listOf(
            comp("$projectKey:short:Foo.java", "Foo.java"),
            comp("$projectKey:long:services/order/src/Foo.java", "services/order/src/Foo.java")
        )
        val result = tool.resolveComponentKey(
            relativePath = "repo/services/order/src/Foo.java",
            components = components,
            projectKey = projectKey
        )
        assertEquals("$projectKey:long:services/order/src/Foo.java", result.key)
    }

    @Test
    fun `no match at all returns legacy key with explanatory note`() {
        val components = listOf(
            comp("$projectKey:mod:src/NothingMatches.java", "src/NothingMatches.java")
        )
        val result = tool.resolveComponentKey(
            relativePath = "entirely/different/File.java",
            components = components,
            projectKey = projectKey
        )
        assertEquals("$projectKey:entirely/different/File.java", result.key)
        assertNotNull(result.note)
        assertTrue(result.note!!.contains("no SonarQube component matches"))
    }
}
