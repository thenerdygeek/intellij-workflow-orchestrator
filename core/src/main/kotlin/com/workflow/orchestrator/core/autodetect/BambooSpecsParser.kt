package com.workflow.orchestrator.core.autodetect

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Parses `private/public static final String NAME = "value";` declarations from
 * every `.java` file under `<projectRoot>/bamboo-specs/src/main/java/`.
 *
 * Returns a flat map of constant name -> string value. First occurrence wins on
 * duplicates. Returns empty map if the directory does not exist.
 *
 * Pure utility — no IDE APIs, no caching, no project context.
 */
object BambooSpecsParser {

    private val CONSTANT_REGEX = Regex(
        """(?:private|public|protected)?\s+static\s+final\s+String\s+(\w+)\s*=\s*"([^"]+)""""
    )

    fun parseConstants(projectRoot: Path): Map<String, String> {
        val javaDir = projectRoot.resolve("bamboo-specs/src/main/java")
        if (!javaDir.exists()) return emptyMap()

        val result = mutableMapOf<String, String>()
        Files.walk(javaDir).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.toString().endsWith(".java") }
                .forEach { file ->
                    val content = try { file.readText() } catch (_: Exception) { return@forEach }
                    CONSTANT_REGEX.findAll(content).forEach { match ->
                        val name = match.groupValues[1]
                        val value = match.groupValues[2]
                        result.putIfAbsent(name, value)
                    }
                }
        }
        return result
    }
}
