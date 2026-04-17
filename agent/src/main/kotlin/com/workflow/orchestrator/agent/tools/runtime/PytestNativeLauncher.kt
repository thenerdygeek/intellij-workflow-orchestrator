package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.project.Project

/**
 * Creates a [RunnerAndConfigurationSettings] for pytest via reflection.
 *
 * Zero compile-time dependency on the Python plugin — all access to
 * PyTestConfigurationType and AbstractPythonTestRunConfiguration goes
 * through reflection, same pattern as [PythonProvider].
 */
internal class PytestNativeLauncher(private val project: Project) {

    /**
     * Create a temporary pytest run configuration.
     *
     * @param pytestPath pytest path or node id (e.g. "tests/test_api.py" or
     *   "tests/test_api.py::test_login"). Maps to `setTarget()` on the config.
     * @param keywordExpr -k expression (e.g. "login and not flaky"). Maps to `setKeyword()`.
     * @param markerExpr -m expression (e.g. "slow or integration"). Maps to `setMarker()`.
     * @return null if PyTestConfigurationType is not registered or reflection fails.
     */
    fun createSettings(
        pytestPath: String?,
        keywordExpr: String?,
        markerExpr: String?,
    ): RunnerAndConfigurationSettings? {
        return try {
            val configType = findPyTestConfigurationType() ?: return null
            val factory = configType.configurationFactories.firstOrNull() ?: return null
            val runManager = RunManager.getInstance(project)
            val settings = runManager.createConfiguration("[Agent] pytest", factory)
            val config = settings.configuration

            // Set working directory first — critical for conftest.py discovery.
            project.basePath?.let { basePath ->
                invokeIfPresent(config, "setWorkingDirectory", basePath)
            }

            // Set the test target (path or node id).
            if (pytestPath != null) {
                invokeIfPresent(config, "setTarget", pytestPath)
            }

            // Set -k keyword expression.
            if (keywordExpr != null) {
                invokeIfPresent(config, "setKeyword", keywordExpr)
            }

            // Set -m marker expression.
            if (markerExpr != null) {
                invokeIfPresent(config, "setMarker", markerExpr)
            }

            settings.isTemporary = true
            // Do NOT register in RunManager — same reasoning as JavaRuntimeExecTool:
            // avoids side effects on selectedConfiguration.
            settings
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /**
         * Find the PyTestConfigurationType via the extension point — no import needed.
         * Returns null if the Python plugin is not installed or not enabled.
         */
        fun findPyTestConfigurationType(): ConfigurationType? =
            ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
                .firstOrNull { it.id == "PyTest" || it.id == "py.test" }

        /**
         * Attempt to invoke a no-arg or single-String setter on [target] by name.
         * Silently ignores NoSuchMethodException so callers don't need to handle
         * plugin-version differences.
         */
        private fun invokeIfPresent(target: Any, methodName: String, value: String) {
            try {
                val method = findMethod(target.javaClass, methodName, String::class.java)
                method?.invoke(target, value)
            } catch (_: Exception) { }
        }

        /**
         * Walk the class hierarchy to find a method with the given name and parameter
         * types. Required because AbstractPythonTestRunConfiguration's setTarget/setKeyword
         * etc. may be declared on a superclass, not the concrete PyTestConfiguration.
         */
        fun findMethod(
            clazz: Class<*>,
            name: String,
            vararg paramTypes: Class<*>
        ): java.lang.reflect.Method? {
            var c: Class<*>? = clazz
            while (c != null) {
                try {
                    return c.getDeclaredMethod(name, *paramTypes).also { it.isAccessible = true }
                } catch (_: NoSuchMethodException) {
                    c = c.superclass
                }
            }
            return null
        }
    }
}
