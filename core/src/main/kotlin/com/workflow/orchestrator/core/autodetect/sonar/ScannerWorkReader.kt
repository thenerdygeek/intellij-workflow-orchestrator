package com.workflow.orchestrator.core.autodetect.sonar

import com.intellij.openapi.diagnostic.logger
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

object ScannerWorkReader {
    private val log = logger<ScannerWorkReader>()

    /**
     * Walks [repoRoot] up to [maxDepth] looking for any `.scannerwork/report-task.txt`
     * written by SonarScanner CLI / Maven / Gradle. Parses the file's `projectKey`
     * property and returns it. Returns null if no such file is found, or if it
     * exists but has no `projectKey`.
     *
     * Off-EDT — call from Dispatchers.IO.
     */
    fun readProjectKey(repoRoot: Path, maxDepth: Int = 4): String? {
        if (!Files.isDirectory(repoRoot)) return null
        return try {
            Files.walk(repoRoot, maxDepth).use { stream ->
                stream
                    .filter { p -> p.fileName?.toString() == "report-task.txt" }
                    .filter { p -> p.parent?.fileName?.toString() == ".scannerwork" }
                    .map { p -> readKeyFromFile(p) }
                    .filter { it != null }
                    .map { it!! }
                    .findFirst()
                    .orElse(null)
            }
        } catch (e: Exception) {
            log.warn("[Sonar:Detect:T0] Walk failed for $repoRoot", e)
            null
        }
    }

    private fun readKeyFromFile(file: Path): String? = try {
        Files.newInputStream(file).use { stream ->
            val props = Properties()
            props.load(InputStreamReader(stream, StandardCharsets.UTF_8))
            props.getProperty("projectKey")?.trim()?.takeIf { it.isNotBlank() }?.also {
                log.info("[Sonar:Detect:T0] Found projectKey=$it in $file")
            }
        }
    } catch (e: Exception) {
        log.warn("[Sonar:Detect:T0] Failed to read $file", e); null
    }
}
