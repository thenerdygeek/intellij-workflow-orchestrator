package com.workflow.orchestrator.agent.tools.framework.endpoints

/**
 * Pure-function `.http` block renderer. One discovered endpoint in,
 * one `.http` file block out. Stays JetBrains-HTTP-Client-compatible:
 *
 *   ### <human label>
 *   <METHOD> <url>
 *   Content-Type: application/json   (only if body present)
 *
 *   <body>                           (only if body present)
 */
internal fun renderHttpBlock(
    handlerId: String,
    method: String,
    url: String,
    bodyPlaceholder: String?,
): String = buildString {
    appendLine("### $handlerId")
    append("$method $url")
    if (bodyPlaceholder != null) {
        appendLine()
        appendLine("Content-Type: application/json")
        appendLine()
        append(bodyPlaceholder)
    }
    appendLine()
}

/**
 * Best-effort JSON-literal default for a Kotlin/Java type name. The goal
 * isn't perfect fidelity — it's to give the user a valid-JSON starting
 * point they can edit. Nested objects resolve to `null`; the user fills
 * them in.
 */
internal fun defaultJsonLiteral(typeName: String): String {
    val normalized = typeName.trim()
    return when {
        normalized == "String" || normalized == "java.lang.String" -> "\"\""
        normalized in setOf("Int", "Integer", "Long", "Short", "Byte", "int", "long", "short", "byte") -> "0"
        normalized in setOf("Float", "Double", "float", "double") -> "0.0"
        normalized in setOf("Boolean", "boolean") -> "false"
        normalized.startsWith("List<") || normalized.startsWith("Set<") ||
            normalized.startsWith("Collection<") || normalized.endsWith("[]") -> "[]"
        normalized.startsWith("Map<") -> "{}"
        else -> "null"
    }
}
