package com.workflow.orchestrator.core.diagnostics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * Pure-JUnit5 tests for [PluginLogNamespaceFilter] — the [java.util.logging.Filter] that gates the
 * ROOT-attached [java.util.logging.FileHandler] so `plugin-0.log` receives ONLY this plugin's
 * records (see [PluginDiagnosticLogService] KDoc for the root-cause analysis).
 *
 * No [com.intellij.testFramework.fixtures.BasePlatformTestCase] here — `:core` allows at most one
 * platform fixture per module, and a plain `java.util.logging.Filter` needs no running IDE.
 */
class PluginLogNamespaceFilterTest {

    private val filter = PluginLogNamespaceFilter()

    private fun recordFrom(loggerName: String?): LogRecord =
        LogRecord(Level.INFO, "msg").apply { this.loggerName = loggerName }

    @Test
    fun `passes bare string-category plugin loggers`() {
        // Logger.getInstance("com.workflow.orchestrator.…") → JUL name == the literal string.
        assertTrue(filter.isLoggable(recordFrom("com.workflow.orchestrator.foo")))
        assertTrue(filter.isLoggable(recordFrom("com.workflow.orchestrator")))
    }

    @Test
    fun `passes hash-prefixed class-based plugin loggers`() {
        // Logger.getInstance(SomeClass::class.java) → category "#" + fqcn → JUL name "#com.workflow…".
        assertTrue(filter.isLoggable(recordFrom("#com.workflow.orchestrator.core.diagnostics.PluginDiagnosticLogService")))
        assertTrue(filter.isLoggable(recordFrom("#com.workflow.orchestrator.foo")))
    }

    @Test
    fun `rejects platform loggers`() {
        assertFalse(filter.isLoggable(recordFrom("com.intellij.bar")))
        assertFalse(filter.isLoggable(recordFrom("#com.intellij.openapi.application.impl.ApplicationImpl")))
        assertFalse(filter.isLoggable(recordFrom("org.jetbrains.something")))
    }

    @Test
    fun `rejects out-of-namespace plugin string categories (known gap)`() {
        // The two PluginSettings web loggers are deliberately NOT under the plugin namespace.
        assertFalse(filter.isLoggable(recordFrom("WebAllowlist")))
        assertFalse(filter.isLoggable(recordFrom("WebEgressDenyList")))
    }

    @Test
    fun `rejects null logger name`() {
        assertFalse(filter.isLoggable(recordFrom(null)))
    }

    @Test
    fun `rejects a namespace lookalike that is not a true prefix`() {
        // Defensive: must not match an unrelated package that merely embeds the string.
        assertFalse(filter.isLoggable(recordFrom("org.evil.com.workflow.orchestrator.foo")))
        assertFalse(filter.isLoggable(recordFrom("#org.evil.com.workflow.orchestrator.foo")))
    }
}
