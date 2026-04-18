package com.workflow.orchestrator.core.autodetect

import com.intellij.openapi.diagnostic.Logger
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

    private val log = Logger.getInstance(BambooSpecsParser::class.java)

    private val CONSTANT_REGEX = Regex(
        """(?:private|public|protected)?\s+static\s+final\s+String\s+(\w+)\s*=\s*"([^"]+)""""
    )

    fun parseConstants(projectRoot: Path): Map<String, String> {
        val javaDir = projectRoot.resolve("bamboo-specs/src/main/java")
        log.info("[BambooSpecs] Searching for constants in: $javaDir (exists=${javaDir.exists()})")
        if (!javaDir.exists()) {
            log.info("[BambooSpecs] Directory does not exist: $javaDir")
            return emptyMap()
        }

        val result = mutableMapOf<String, String>()
        var filesScanned = 0
        Files.walk(javaDir).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.toString().endsWith(".java") }
                .forEach { file ->
                    filesScanned++
                    log.info("[BambooSpecs] Scanning file: $file")
                    val content = try {
                        file.readText()
                    } catch (e: Exception) {
                        log.warn("[BambooSpecs] Failed to read $file: ${e.message}")
                        return@forEach
                    }
                    val matches = CONSTANT_REGEX.findAll(content).toList()
                    if (matches.isEmpty()) {
                        log.info("[BambooSpecs]   No static final String constants found in $file")
                        // Log first 200 chars to help diagnose regex mismatches
                        log.debug("[BambooSpecs]   File preview: ${content.take(200)}")
                    }
                    for (match in matches) {
                        val name = match.groupValues[1]
                        val value = match.groupValues[2]
                        log.info("[BambooSpecs]   Found: $name = \"$value\"")
                        result.putIfAbsent(name, value)
                    }
                }
        }
        log.info("[BambooSpecs] Scanned $filesScanned .java file(s), found ${result.size} constant(s): ${result.keys}")
        return result
    }
}
