package com.workflow.orchestrator.agent.tools.framework.build

import java.io.File

/**
 * Shared Gradle file-discovery helpers used by multiple build action handlers.
 *
 * - [findGradleBuildFiles] is used by `gradle_dependencies` and `gradle_tasks`.
 * - [listGradleModules] is used by `gradle_properties` and (transitively) by
 *   [findGradleBuildFiles].
 */

internal fun findGradleBuildFiles(baseDir: File, moduleFilter: String?): List<Pair<String, File>> {
    val result = mutableListOf<Pair<String, File>>()

    if (moduleFilter != null) {
        val moduleDir = File(baseDir, moduleFilter)
        val buildFile = findGradleBuildFile(moduleDir)
        if (buildFile != null) {
            result.add(moduleFilter to buildFile)
        } else {
            val rootBuild = findGradleBuildFile(baseDir)
            if (rootBuild != null) result.add("root" to rootBuild)
        }
    } else {
        val rootBuild = findGradleBuildFile(baseDir)
        if (rootBuild != null) result.add("root" to rootBuild)

        val modules = listGradleModules(baseDir)
        for (mod in modules) {
            val modDir = File(baseDir, mod.trimStart(':').replace(':', '/'))
            val buildFile = findGradleBuildFile(modDir)
            if (buildFile != null) result.add(mod.trimStart(':') to buildFile)
        }

        if (modules.isEmpty()) {
            baseDir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" && it.name != "gradle" }
                ?.forEach { subDir ->
                    val buildFile = findGradleBuildFile(subDir)
                    if (buildFile != null) result.add(subDir.name to buildFile)
                }
        }
    }

    return result
}

internal fun findGradleBuildFile(dir: File): File? {
    val kts = File(dir, "build.gradle.kts")
    if (kts.isFile) return kts
    val groovy = File(dir, "build.gradle")
    if (groovy.isFile) return groovy
    return null
}

internal fun listGradleModules(baseDir: File): List<String> {
    val settingsKts = File(baseDir, "settings.gradle.kts")
    val settingsGroovy = File(baseDir, "settings.gradle")
    val settingsFile = when {
        settingsKts.isFile -> settingsKts
        settingsGroovy.isFile -> settingsGroovy
        else -> return emptyList()
    }

    val modules = mutableListOf<String>()
    val pattern = Regex("""include\s*\(\s*["']([^"']+)["']\s*\)|include\s+['"]([^'"]+)['"]""")
    settingsFile.readLines().forEach { line ->
        pattern.findAll(line).forEach { match ->
            val mod = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (mod.isNotBlank()) modules.add(mod)
        }
    }
    return modules
}
