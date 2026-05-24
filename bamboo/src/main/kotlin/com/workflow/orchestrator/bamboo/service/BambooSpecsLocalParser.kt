package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.diagnostic.Logger
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

// Parses local bamboo-specs YAML files to extract the master plan key (Tier 0 of plan auto-detection).
object BambooSpecsLocalParser {
    private val log: Logger = Logger.getInstance("com.workflow.orchestrator.bamboo.service.BambooSpecsLocalParser")

    // Searches bamboo-specs/ under repoRoot for bamboo.yml / *.yaml / *.yml files.
    // Extracts plan.project-key + plan.key and returns "PROJECT-KEY" on first match.
    // Returns null when no bamboo-specs dir exists or no parseable file is found.
    // Must be called off the EDT.
    fun parsePlanKey(repoRoot: Path): String? {
        val specsDir = repoRoot.resolve("bamboo-specs")
        if (!Files.isDirectory(specsDir)) return null
        return try {
            Files.walk(specsDir, 1).use { stream ->
                stream
                    .filter { p ->
                        val name = p.fileName?.toString() ?: return@filter false
                        name == "bamboo.yml" || name.endsWith(".yaml") || name.endsWith(".yml")
                    }
                    .map { extractKey(it) }
                    .filter { it != null }
                    .map { it!! }
                    .findFirst()
                    .orElse(null)
            }
        } catch (e: Exception) {
            log.warn("[Bamboo:Plan:T0] Walk of $specsDir failed", e)
            null
        }
    }

    internal fun extractKey(file: Path): String? = try {
        Files.newBufferedReader(file).use { reader ->
            val data = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(reader) ?: return null
            val plan = (data as? Map<*, *>)?.get("plan") as? Map<*, *> ?: return null
            val projectKey = (plan["project-key"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val key = (plan["key"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val result = "$projectKey-$key"
            log.info("[Bamboo:Plan:T0] Parsed $result from $file")
            result
        }
    } catch (e: Exception) {
        log.warn("[Bamboo:Plan:T0] Failed to parse $file", e)
        null
    }
}
