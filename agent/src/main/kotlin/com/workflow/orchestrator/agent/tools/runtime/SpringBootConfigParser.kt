package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.workflow.orchestrator.agent.tools.framework.spring.parseYamlToFlatProperties
import java.io.File
import java.util.Properties

/**
 * Actuator path metadata extracted from Spring Boot run config + resource files.
 *
 * NOTE: Port fields (`serverPort`, `managementPort`) were intentionally removed.
 * Static config parsing cannot reliably determine the port because run configurations
 * can override it via VM options, program args, env vars, active profiles,
 * programmatic `SpringApplication.setDefaultProperties`, cloud config, or
 * `server.port=0` (random port). Only OS PID-based discovery (`lsof`/`ss`/`netstat`)
 * is authoritative — see `RuntimeExecTool.discoverListeningPorts`.
 */
data class SpringBootActuatorPaths(
    /** Actuator base path (`management.endpoints.web.base-path`), default `/actuator`. */
    val actuatorBasePath: String,
    /** Health endpoint path segment (`management.endpoints.web.path-mapping.health`), default `/health`. */
    val healthPath: String,
)

/**
 * Utilities for Spring Boot run configuration inspection.
 *
 * Reflection-based: no compile-time dependency on the IntelliJ Spring plugin.
 * Matches the style of RuntimeConfigTool reflection calls.
 *
 * Port-related parsing was removed. The only fields retained are the actuator
 * base path and health path, which are needed to construct the HTTP probe URL.
 * The probe URL's port comes exclusively from OS PID discovery.
 */
object SpringBootConfigParser {

    // Spring Boot run-config class names (IntelliJ Spring plugin 2025.1+)
    private val SPRING_BOOT_CONFIG_CLASS_NAMES = listOf(
        "com.intellij.spring.boot.run.SpringBootApplicationRunConfiguration",
        "com.intellij.spring.boot.run.SpringBootApplicationRunConfigurationBase",
        "com.intellij.spring.boot.run.AbstractSpringBootRunConfiguration",
        "com.intellij.execution.application.ApplicationConfiguration", // fallback for Spring Boot apps
    )

    /**
     * Returns true if [settings] represents a Spring Boot run configuration.
     *
     * Checks by config type ID first (fast path), then by reflection on the config class
     * name (covers older Spring plugin versions and edge cases).
     */
    fun isSpringBootConfig(settings: RunnerAndConfigurationSettings): Boolean {
        val config = settings.configuration
        val typeId = config.type.id

        // Fast path: type ID contains Spring Boot markers
        if (typeId.contains("SpringBoot", ignoreCase = true) ||
            typeId.contains("spring-boot", ignoreCase = true)
        ) {
            return true
        }

        // Reflection path: check class hierarchy
        return SPRING_BOOT_CONFIG_CLASS_NAMES.any { className ->
            isInstanceOf(config, className)
        }
    }

    /** Returns true if [obj] is an instance of [className] (direct or superclass). */
    private fun isInstanceOf(obj: Any, className: String): Boolean {
        return try {
            val clazz = Class.forName(className)
            clazz.isInstance(obj)
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parse actuator path configuration from [settings].
     *
     * Only the actuator base path and health path are parsed — these are needed
     * to construct the HTTP probe URL. The port is intentionally NOT parsed here:
     * run-configuration overrides (VM options, env vars, active profiles,
     * programmatic setDefaultProperties, cloud config, random `server.port=0`)
     * make static parsing unreliable. Use OS PID discovery (`lsof`/`ss`/`netstat`)
     * as the authoritative port source.
     *
     * Resolution order per path property:
     * 1. VM args (`-Dmanagement.endpoints.web.base-path=...`)
     * 2. Program args (`--management.endpoints.web.base-path=...`)
     * 3. Active-profile properties files (e.g., `application-prod.properties`)
     * 4. Base `application.properties` / `application.yml`
     * 5. Default (`/actuator`, `/health`)
     *
     * Active profiles are read from the run configuration via reflection on
     * `getActiveProfiles()`. Falls back to empty profile list.
     */
    fun parseActuatorPaths(settings: RunnerAndConfigurationSettings, project: Project): SpringBootActuatorPaths {
        val config = settings.configuration

        // 1. VM args override
        val vmArgs = extractVmArgs(config)
        val vmActuatorBase = parseStringPropertyFromArgs(vmArgs, "management.endpoints.web.base-path", argPrefix = "-D")
        val vmHealthPath = parseStringPropertyFromArgs(vmArgs, "management.endpoints.web.path-mapping.health", argPrefix = "-D")

        // 2. Program args override
        val programArgs = extractProgramArgs(config)
        val argActuatorBase = parseStringPropertyFromArgs(programArgs, "management.endpoints.web.base-path", argPrefix = "--")
        val argHealthPath = parseStringPropertyFromArgs(programArgs, "management.endpoints.web.path-mapping.health", argPrefix = "--")

        // 3+4. Properties files (profile-aware) — only actuator paths, not port
        val activeProfiles = extractActiveProfiles(config)
        val fileProps = loadApplicationProperties(project, activeProfiles)
        val fileActuatorBase = fileProps["management.endpoints.web.base-path"]
        val fileHealthPath = fileProps["management.endpoints.web.path-mapping.health"]

        // 5. Merge with resolution priority: VM args > program args > files > defaults
        val actuatorBase: String = (vmActuatorBase ?: argActuatorBase ?: fileActuatorBase ?: DEFAULT_ACTUATOR_BASE)
            .trimEnd('/')
        val healthPath: String = (vmHealthPath ?: argHealthPath ?: fileHealthPath ?: DEFAULT_HEALTH_PATH)
            .let { path -> if (path.startsWith("/")) path else "/$path" }

        return SpringBootActuatorPaths(
            actuatorBasePath = actuatorBase,
            healthPath = healthPath,
        )
    }

    // ── Reflection helpers ─────────────────────────────────────────────────

    private fun extractVmArgs(config: Any): String {
        return try {
            val method = config.javaClass.methods.firstOrNull {
                it.name == "getVMParameters" || it.name == "getVmParameters" || it.name == "getVMArgs"
            }
            method?.invoke(config) as? String ?: ""
        } catch (_: Exception) { "" }
    }

    private fun extractProgramArgs(config: Any): String {
        return try {
            val method = config.javaClass.methods.firstOrNull {
                it.name == "getProgramParameters" || it.name == "getProgramArgs"
            }
            method?.invoke(config) as? String ?: ""
        } catch (_: Exception) { "" }
    }

    private fun extractActiveProfiles(config: Any): List<String> {
        return try {
            // IntelliJ Spring Boot run config: getActiveProfiles() returns String
            val method = config.javaClass.methods.firstOrNull { it.name == "getActiveProfiles" }
            val raw = method?.invoke(config) as? String ?: ""
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
    }

    // ── Property parsing helpers ───────────────────────────────────────────

    private fun parseStringPropertyFromArgs(args: String, key: String, argPrefix: String): String? {
        if (args.isBlank()) return null
        val tokens = args.trim().split(Regex("\\s+"))

        val singleTokenPrefix = "$argPrefix$key="
        for (token in tokens) {
            if (token.startsWith(singleTokenPrefix)) {
                return token.substringAfter("=").ifBlank { null }
            }
        }
        val twoTokenKey = "$argPrefix$key"
        for (i in tokens.indices) {
            if (tokens[i] == twoTokenKey && i + 1 < tokens.size) {
                return tokens[i + 1].ifBlank { null }
            }
        }
        return null
    }

    // ── Properties file loading ────────────────────────────────────────────

    /**
     * Load Spring Boot application properties from the module's resource directories.
     *
     * Scans only for actuator path properties. Port-related properties are intentionally
     * ignored — static parsing cannot see run-config overrides (VM options, env vars,
     * active profiles, programmatic setDefaultProperties, cloud config, random port mode).
     *
     * Scans:
     * - `src/main/resources/application.properties` (base)
     * - `src/main/resources/application.yml`
     * - `src/main/resources/application-{profile}.properties` (profile-specific, higher priority)
     * - `src/main/resources/application-{profile}.yml`
     *
     * Returns a merged property map; profile-specific files override base values.
     */
    internal fun loadApplicationProperties(project: Project, activeProfiles: List<String>): Map<String, String> {
        val resourceDirs = collectResourceDirs(project)
        val merged = mutableMapOf<String, String>()

        // Base files first (lower priority)
        for (dir in resourceDirs) {
            readPropertiesFile(dir.resolve("application.properties"))?.let { merged.putAll(it) }
            readYamlFile(dir.resolve("application.yml"))?.let { merged.putAll(it) }
            readYamlFile(dir.resolve("application.yaml"))?.let { merged.putAll(it) }
        }

        // Profile-specific files (higher priority)
        for (profile in activeProfiles) {
            for (dir in resourceDirs) {
                readPropertiesFile(dir.resolve("application-$profile.properties"))?.let { merged.putAll(it) }
                readYamlFile(dir.resolve("application-$profile.yml"))?.let { merged.putAll(it) }
                readYamlFile(dir.resolve("application-$profile.yaml"))?.let { merged.putAll(it) }
            }
        }

        return merged
    }

    private fun collectResourceDirs(project: Project): List<File> {
        val dirs = mutableListOf<File>()
        try {
            val modules = ModuleManager.getInstance(project).modules
            for (module in modules) {
                val contentRoots = ModuleRootManager.getInstance(module).contentRoots
                for (root in contentRoots) {
                    val rootPath = root.path
                    for (subDir in RESOURCE_SUBDIRS) {
                        val dir = File(rootPath, subDir)
                        if (dir.isDirectory) dirs.add(dir)
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback: project base path
            val basePath = project.basePath
            if (basePath != null) {
                for (subDir in RESOURCE_SUBDIRS) {
                    val dir = File(basePath, subDir)
                    if (dir.isDirectory) dirs.add(dir)
                }
            }
        }
        return dirs
    }

    private fun readPropertiesFile(file: File): Map<String, String>? {
        if (!file.isFile) return null
        return try {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            props.entries.associate { (k, v) -> k.toString() to v.toString() }
        } catch (_: Exception) { null }
    }

    private fun readYamlFile(file: File): Map<String, String>? {
        if (!file.isFile) return null
        return try {
            parseYamlToFlatProperties(file.readText())
        } catch (_: Exception) { null }
    }

    // ── Constants ─────────────────────────────────────────────────────────

    private val RESOURCE_SUBDIRS = listOf("src/main/resources", "src/main/resources/config")

    const val DEFAULT_ACTUATOR_BASE = "/actuator"
    const val DEFAULT_HEALTH_PATH = "/health"

    /** Paths to try when probing Spring Boot 3.x (readiness endpoint first). */
    val SPRING_BOOT_3_PROBE_PATHS = listOf("/readiness", "")  // suffix after actuatorBase + "/health"
}
