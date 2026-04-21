package com.workflow.orchestrator.agent.ide

import com.intellij.execution.configurations.ConfigurationType
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Classifies the current IDE product, edition, and available languages based on
 * the running ApplicationInfo and installed plugins. Delegates framework and build
 * tool detection to [ProjectScanner].
 */
object IdeContextDetector {

    private const val PYTHON_PRO_PLUGIN_ID = "com.intellij.python"
    private const val PYTHON_CORE_PLUGIN_ID = "PythonCore"
    private const val SPRING_PLUGIN_ID = "com.intellij.spring"
    private const val SPRING_BOOT_PLUGIN_ID = "com.intellij.spring.boot"
    private const val JAVA_PLUGIN_ID = "com.intellij.java"
    private const val PERSISTENCE_PLUGIN_ID = "com.intellij.persistence"
    private const val DATABASE_PLUGIN_ID = "com.intellij.database"

    fun detect(project: Project): IdeContext {
        val appInfo = ApplicationInfo.getInstance()
        val productCode = appInfo.build.productCode
        val product = classifyProduct(productCode)
        val edition = classifyEdition(product)

        val hasJava = isPluginInstalled(JAVA_PLUGIN_ID)
        val hasPythonPro = isPluginInstalled(PYTHON_PRO_PLUGIN_ID)
        val hasPythonCore = isPluginInstalled(PYTHON_CORE_PLUGIN_ID)
        val hasSpring = isPluginInstalled(SPRING_PLUGIN_ID)
        val hasSpringBoot = isPluginInstalled(SPRING_BOOT_PLUGIN_ID)
        val hasPersistence = isPluginInstalled(PERSISTENCE_PLUGIN_ID)
        val hasDatabase = isPluginInstalled(DATABASE_PLUGIN_ID)
        val hasPyTestConfigType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
            .any { it.id == "PyTest" || it.id == "py.test" }

        val languages = deriveLanguages(product, hasJava, hasPythonPro || hasPythonCore)
        val frameworks = ProjectScanner.detectFrameworks(project)
        val buildTools = ProjectScanner.detectBuildTools(project)
        val isMultiModule = detectIsMultiModule(project)

        return IdeContext(
            product = product,
            productName = appInfo.fullApplicationName,
            edition = edition,
            languages = languages,
            hasJavaPlugin = hasJava,
            hasPythonPlugin = hasPythonPro,
            hasPythonCorePlugin = hasPythonCore,
            hasSpringPlugin = hasSpring,
            hasSpringBootPlugin = hasSpringBoot,
            hasPersistencePlugin = hasPersistence,
            hasDatabasePlugin = hasDatabase,
            detectedFrameworks = frameworks,
            detectedBuildTools = buildTools,
            hasPyTestConfigType = hasPyTestConfigType,
            isMultiModule = isMultiModule,
            hasMicroservicesModule = MicroservicesDetector.isAvailable(),
        )
    }

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

    private fun detectIsMultiModule(project: Project): Boolean {
        return try {
            ReadAction.compute<Boolean, Throwable> {
                ModuleManager.getInstance(project).modules.size > 1
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (_: Throwable) {
            val basePath = project.basePath ?: return false
            ProjectScanner.detectIsMultiModuleFromPath(Path.of(basePath))
        }
    }
}
