package com.workflow.orchestrator.agent.tools.framework.spring

import org.yaml.snakeyaml.Yaml

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
