package com.workflow.orchestrator.core.autodetect.sonar

import com.intellij.openapi.diagnostic.logger
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class SonarPropertiesResult(
    val primaryKey: String,
    val additionalCandidatePaths: List<Path>
)

object SonarPropertiesReader {
    private val log = logger<SonarPropertiesReader>()

    /**
     * Walks [repoRoot] up to [maxDepth] looking for `sonar-project.properties` files.
     * Returns the first non-blank `sonar.projectKey` found plus paths to any other
     * sonar-project.properties files (logged as a warning — monorepos with multiple
     * Sonar projects per Git repo are out of scope for the current 1:1 RepoConfig
     * model and require a picker workflow).
     *
     * Off-EDT — call from Dispatchers.IO.
     */
    fun readProjectKey(repoRoot: Path, maxDepth: Int = 2): SonarPropertiesResult? {
        if (!Files.isDirectory(repoRoot)) return null
        return try {
            val files: List<Path> = Files.walk(repoRoot, maxDepth).use { stream ->
                stream.filter { p -> p.fileName?.toString() == "sonar-project.properties" }
                    .collect(java.util.stream.Collectors.toList())
            }
            if (files.isEmpty()) return null
            // Sort by path depth (shortest = closest to repo root) so the root-level file wins
            // when multiple sonar-project.properties files exist.
            val sorted = files.sortedBy { it.nameCount }
            val first = sorted.firstNotNullOfOrNull { f -> readKey(f)?.let { f to it } } ?: return null
            val (primaryFile, primaryKey) = first
            val additional = sorted.filter { it != primaryFile }
            if (additional.isNotEmpty()) {
                log.warn(
                    "[Sonar:Detect:T1] Found ${additional.size} additional sonar-project.properties files; " +
                        "using $primaryFile (key=$primaryKey). Others: $additional"
                )
            }
            SonarPropertiesResult(primaryKey, additional)
        } catch (e: Exception) {
            log.warn("[Sonar:Detect:T1] Walk failed for $repoRoot", e); null
        }
    }

    private fun readKey(file: Path): String? = try {
        Files.newInputStream(file).use { stream ->
            val props = Properties()
            props.load(InputStreamReader(stream, StandardCharsets.UTF_8))
            props.getProperty("sonar.projectKey")?.trim()?.takeIf { it.isNotBlank() }?.also {
                log.info("[Sonar:Detect:T1] Found sonar.projectKey=$it in $file")
            }
        }
    } catch (e: Exception) {
        log.warn("[Sonar:Detect:T1] Failed to read $file", e); null
    }
}
