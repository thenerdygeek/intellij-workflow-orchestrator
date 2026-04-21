package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Shared constants and utilities used by multiple Spring action handlers.
 *
 * Note: `detectStereotype` is shared between context and bean_graph actions
 * and is declared `internal` in [SpringContextAction.kt] for that purpose.
 */

internal val SPRING_ENDPOINT_MAPPING_ANNOTATIONS = mapOf(
    "org.springframework.web.bind.annotation.RequestMapping" to null,
    "org.springframework.web.bind.annotation.GetMapping" to "GET",
    "org.springframework.web.bind.annotation.PostMapping" to "POST",
    "org.springframework.web.bind.annotation.PutMapping" to "PUT",
    "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
    "org.springframework.web.bind.annotation.PatchMapping" to "PATCH"
)

internal val SPRING_CONTROLLER_ANNOTATIONS = listOf(
    "org.springframework.web.bind.annotation.RestController",
    "org.springframework.stereotype.Controller"
)

/**
 * Parses a YAML string into a flat map of dotted property keys to their string values.
 *
 * Uses SnakeYAML (bundled with IntelliJ Platform) for correct handling of all YAML
 * features: multi-line values (| and >), flow mappings ({key: value}), flow sequences
 * ([a, b, c]), anchors/aliases (&anchor, *anchor), block scalars, and inline comments.
 *
 * Example: the YAML
 * ```yaml
 * server:
 *   port: 8080
 *   servlet:
 *     context-path: /api
 * ```
 * produces `{"server.port" -> "8080", "server.servlet.context-path" -> "/api"}`.
 *
 * Lists are flattened with bracket-index keys: `items[0]`, `items[1]`, etc.
 */
internal fun parseYamlToFlatProperties(content: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    try {
        val yaml = Yaml()
        val data = yaml.load<Any>(content) ?: return emptyMap()
        if (data is Map<*, *>) {
            flattenYamlMap("", data, result)
        }
    } catch (_: Exception) {
        // If SnakeYAML fails, return empty — callers already handle empty results
    }
    return result
}

private fun flattenYamlMap(prefix: String, map: Map<*, *>, target: MutableMap<String, String>) {
    for ((key, value) in map) {
        val fullKey = if (prefix.isEmpty()) key.toString() else "$prefix.${key}"
        when (value) {
            is Map<*, *> -> flattenYamlMap(fullKey, value, target)
            is List<*> -> flattenYamlList(fullKey, value, target)
            null -> { /* skip null values */ }
            else -> target[fullKey] = value.toString()
        }
    }
}

/**
 * Matches any Spring Boot config file under a resource directory:
 *
 * - `application.properties`, `application.yml`, `application.yaml`
 * - `application-{anyProfile}.properties|yml|yaml` — profile name is
 *   user-defined, so `application-mydocker.properties`,
 *   `application-staging.yml`, `application-local-dev.yaml` all match.
 * - `bootstrap.properties`, `bootstrap.yml`, `bootstrap.yaml`
 * - `bootstrap-{anyProfile}.properties|yml|yaml`
 *
 * Profile names allow letters, digits, dashes, underscores, and dots — the
 * character set Spring Boot itself tolerates in profile identifiers.
 */
internal val SPRING_CONFIG_FILE_PATTERN: Regex =
    Regex("""^(application|bootstrap)(?:-[\w.\-]+)?\.(properties|ya?ml)$""", RegexOption.IGNORE_CASE)

/**
 * Module-aware Spring resource-file scan using exact filename matching.
 *
 * Walks every module's `ModuleRootManager.contentRoots` and checks the
 * conventional Maven/Gradle `src/main/resources`, `src/main/resources/config`,
 * and `src/test/resources` subfolders for files whose name matches one of
 * [fileNames]. Use this for tools that only care about canonical file names
 * (e.g., `boot_actuator` reading `application.properties` without profile
 * variants).
 *
 * For broader matching (any profile-suffixed variant), use
 * [scanSpringResourceFilesMatching] with [SPRING_CONFIG_FILE_PATTERN].
 *
 * Works for arbitrary multi-module layouts because it relies on IntelliJ's
 * module model, not on scanning from `project.basePath` with a hardcoded
 * depth. Falls back to a `project.basePath` scan only when no modules are
 * registered (defensive).
 */
internal fun scanSpringResourceFiles(project: Project, fileNames: List<String>): List<SpringResourceFile> {
    val nameSet = fileNames.toSet()
    return scanSpringResourceFilesMatching(project) { it.name in nameSet }
}

/**
 * Module-aware Spring resource-file scan using a predicate over filenames.
 *
 * Use with [SPRING_CONFIG_FILE_PATTERN] to pick up user-defined profile
 * variants such as `application-mydocker.properties` that exact-name lookups
 * would miss.
 */
internal fun scanSpringResourceFilesMatching(
    project: Project,
    accept: (File) -> Boolean,
): List<SpringResourceFile> {
    val searchDirs = listOf(
        "src/main/resources",
        "src/main/resources/config",
        "src/test/resources",
    )

    val found = linkedMapOf<String, SpringResourceFile>()
    val modules = ModuleManager.getInstance(project).modules

    if (modules.isEmpty()) {
        val basePath = project.basePath ?: return emptyList()
        val baseDir = File(basePath)
        collectFromRoot(baseDir, baseDir, searchDirs, accept, moduleName = null, found)
        return found.values.toList()
    }

    for (module in modules) {
        val contentRoots = ModuleRootManager.getInstance(module).contentRoots
        for (root in contentRoots) {
            val rootFile = File(root.path)
            if (!rootFile.isDirectory) continue
            collectFromRoot(rootFile, rootFile, searchDirs, accept, moduleName = module.name, found)
        }
    }

    return found.values.toList()
}

internal data class SpringResourceFile(val file: File, val relativePath: String, val moduleName: String?)

private fun collectFromRoot(
    contentRoot: File,
    relativizeTo: File,
    searchDirs: List<String>,
    accept: (File) -> Boolean,
    moduleName: String?,
    found: MutableMap<String, SpringResourceFile>,
) {
    for (dir in searchDirs) {
        val resourceDir = File(contentRoot, dir)
        if (!resourceDir.isDirectory) continue
        val children = resourceDir.listFiles() ?: continue
        for (file in children) {
            if (!file.isFile) continue
            if (!accept(file)) continue
            val key = file.canonicalPath
            if (key in found) continue
            val relBase = runCatching { file.relativeTo(relativizeTo).path }.getOrDefault(file.path)
            val display = if (moduleName != null) "$moduleName/$relBase" else relBase
            found[key] = SpringResourceFile(file, display, moduleName)
        }
    }
}

private fun flattenYamlList(prefix: String, list: List<*>, target: MutableMap<String, String>) {
    for ((index, item) in list.withIndex()) {
        val indexedKey = "$prefix[$index]"
        when (item) {
            is Map<*, *> -> flattenYamlMap(indexedKey, item, target)
            is List<*> -> flattenYamlList(indexedKey, item, target)
            null -> { /* skip null values */ }
            else -> target[indexedKey] = item.toString()
        }
    }
    // Also store the comma-joined form for simple scalar lists (e.g., spring.profiles.active)
    val scalars = list.filterNotNull().filter { it !is Map<*, *> && it !is List<*> }
    if (scalars.isNotEmpty() && scalars.size == list.size) {
        target[prefix] = scalars.joinToString(",")
    }
}
