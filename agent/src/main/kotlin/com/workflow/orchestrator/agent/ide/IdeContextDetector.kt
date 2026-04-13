package com.workflow.orchestrator.agent.ide

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/**
 * Classifies the current IDE product, edition, and available languages based on
 * the running ApplicationInfo and installed plugins.
 *
 * The full [detect] method (which also scans for frameworks and build tools via
 * ProjectScanner) will be wired in Task 2 once ProjectScanner exists.
 */
object IdeContextDetector {

    private const val PYTHON_PRO_PLUGIN_ID = "com.intellij.python"
    private const val PYTHON_CORE_PLUGIN_ID = "PythonCore"
    private const val SPRING_PLUGIN_ID = "com.intellij.spring"
    private const val JAVA_PLUGIN_ID = "com.intellij.java"

    fun classifyProduct(productCode: String): IdeProduct = when (productCode) {
        "IU" -> IdeProduct.INTELLIJ_ULTIMATE
        "IC" -> IdeProduct.INTELLIJ_COMMUNITY
        "PY" -> IdeProduct.PYCHARM_PROFESSIONAL
        "PC" -> IdeProduct.PYCHARM_COMMUNITY
        else -> IdeProduct.OTHER
    }

    fun classifyEdition(product: IdeProduct): Edition = when (product) {
        IdeProduct.INTELLIJ_ULTIMATE -> Edition.ULTIMATE
        IdeProduct.INTELLIJ_COMMUNITY -> Edition.COMMUNITY
        IdeProduct.PYCHARM_PROFESSIONAL -> Edition.PROFESSIONAL
        IdeProduct.PYCHARM_COMMUNITY -> Edition.COMMUNITY
        IdeProduct.OTHER -> Edition.OTHER
    }

    fun deriveLanguages(
        product: IdeProduct,
        hasJavaPlugin: Boolean,
        hasPythonPlugin: Boolean,
    ): Set<Language> {
        val result = mutableSetOf<Language>()
        if (hasJavaPlugin) {
            result.add(Language.JAVA)
            result.add(Language.KOTLIN)
        }
        if (hasPythonPlugin) {
            result.add(Language.PYTHON)
        }
        return result
    }

    internal fun isPluginInstalled(pluginId: String): Boolean =
        PluginManagerCore.getPlugin(PluginId.getId(pluginId))?.isEnabled == true
}
