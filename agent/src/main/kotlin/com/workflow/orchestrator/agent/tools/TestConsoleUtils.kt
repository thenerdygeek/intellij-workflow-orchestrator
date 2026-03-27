package com.workflow.orchestrator.agent.tools

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunContentDescriptor

/**
 * Utility for unwrapping IntelliJ console wrappers to find the underlying SMTRunnerConsoleView.
 *
 * IntelliJ Ultimate (and plugins) wrap the real test console with decorator widgets.
 * Known wrappers:
 * - `JavaConsoleWithProfilerWidget` (Ultimate profiler) — implements `ConsoleViewWithDelegate`,
 *    has `getDelegate()` returning the inner console.
 * - `BaseTestsOutputConsoleView` — has `getConsole()` returning the inner console.
 *
 * This utility follows the delegate chain (max 5 levels) using reflection to avoid
 * hard dependencies on Ultimate-only classes.
 */
object TestConsoleUtils {

    /**
     * Unwrap delegate/wrapper consoles to find the underlying SMTRunnerConsoleView.
     * Returns null if no SMTRunnerConsoleView is found in the chain.
     */
    fun unwrapToTestConsole(console: ExecutionConsole?): SMTRunnerConsoleView? {
        if (console == null) return null
        if (console is SMTRunnerConsoleView) return console

        var current: Any? = console
        repeat(5) {
            // Try getDelegate() — ConsoleViewWithDelegate (IntelliJ Ultimate profiler wrapper)
            val delegate = tryInvoke(current, "getDelegate")
            if (delegate is SMTRunnerConsoleView) return delegate
            if (delegate != null && delegate !== current) {
                current = delegate
                return@repeat
            }

            // Try getConsole() — BaseTestsOutputConsoleView
            val inner = tryInvoke(current, "getConsole")
            if (inner is SMTRunnerConsoleView) return inner
            if (inner != null && inner !== current) {
                current = inner
                return@repeat
            }

            return null // no more wrappers to unwrap
        }

        return null
    }

    /**
     * Find the SMTestProxy root from a descriptor's execution console,
     * unwrapping any delegate wrappers first.
     */
    fun findTestRoot(descriptor: RunContentDescriptor): SMTestProxy.SMRootTestProxy? {
        val testConsole = unwrapToTestConsole(descriptor.executionConsole) ?: return null
        return testConsole.resultsViewer.testsRootNode as? SMTestProxy.SMRootTestProxy
    }

    private fun tryInvoke(target: Any?, methodName: String): Any? {
        return try {
            target?.javaClass?.getMethod(methodName)?.invoke(target)
        } catch (_: Exception) {
            null
        }
    }
}
