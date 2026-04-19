// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.core.model

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pricing entry for a single normalized model ID.
 *
 * All rates are in USD per 1 million tokens.
 * Cache fields are null for models that don't support prompt caching.
 *
 * Cost formula:
 *   (inputTokens * inputUsdPer1M
 *    + outputTokens * outputUsdPer1M
 *    + cacheReadTokens * (cacheReadUsdPer1M ?: 0)
 *    + cacheWriteTokens * (cacheWriteUsdPer1M ?: 0)) / 1_000_000
 */
data class ModelPricing(
    val modelId: String,
    val inputUsdPer1M: Double,
    val outputUsdPer1M: Double,
    val cacheReadUsdPer1M: Double? = null,
    val cacheWriteUsdPer1M: Double? = null,
) {
    /**
     * Compute the total USD cost for one API call.
     *
     * @param inputTokens     prompt tokens billed at input rate
     * @param outputTokens    completion tokens billed at output rate
     * @param cacheReadTokens tokens served from prompt-cache read (Anthropic "cache_read_input_tokens")
     * @param cacheWriteTokens tokens written to prompt-cache (Anthropic "cache_creation_input_tokens")
     */
    fun computeCost(
        inputTokens: Int,
        outputTokens: Int,
        cacheReadTokens: Int = 0,
        cacheWriteTokens: Int = 0,
    ): Double {
        return (
            inputTokens * inputUsdPer1M +
            outputTokens * outputUsdPer1M +
            cacheReadTokens * (cacheReadUsdPer1M ?: 0.0) +
            cacheWriteTokens * (cacheWriteUsdPer1M ?: 0.0)
        ) / 1_000_000.0
    }
}

/**
 * Registry that maps normalized model IDs to [ModelPricing] entries.
 *
 * Loading strategy (last-write-wins per key):
 * 1. Bundled classpath `pricing.json` — shipped with the plugin JAR.
 * 2. User override `~/.workflow-orchestrator/pricing.json` — merges on top of bundled.
 *
 * Hot-reload: a daemon [WatchService] thread monitors the user override file's parent
 * directory with a 300 ms debounce, mirroring the pattern in [AgentConfigLoader].
 * Call [reload] explicitly in tests (or to force-refresh after the file changes).
 */
object ModelPricingRegistry {

    private val LOG = Logger.getInstance(ModelPricingRegistry::class.java)

    private const val DEBOUNCE_MS = 300L
    private const val BUNDLED_RESOURCE = "/pricing.json"

    /** Canonical location of the user's override file. */
    private val USER_OVERRIDE_PATH: Path =
        Paths.get(System.getProperty("user.home"), ".workflow-orchestrator", "pricing.json")

    /** Allow tests to inject a different override path. */
    @Volatile
    internal var overridePath: Path = USER_OVERRIDE_PATH

    private val registry = ConcurrentHashMap<String, ModelPricing>()
    private val disposed = AtomicBoolean(false)

    private var watchThread: Thread? = null
    private var watchService: WatchService? = null

    private val json = Json { ignoreUnknownKeys = true }

    init {
        reload()
        startWatching()
    }

    // ----- Public API -----

    /**
     * Look up pricing for [rawModelId].
     *
     * Normalizes the raw model ID via [ModelIdNormalizer] before lookup.
     * Returns `null` if the model is not in the registry (no exception).
     */
    fun lookup(rawModelId: String): ModelPricing? {
        val normalized = ModelIdNormalizer.normalize(rawModelId)
        return registry[normalized]
    }

    /**
     * Reload pricing from the bundled classpath resource and the user override file.
     *
     * Thread-safe. Safe to call multiple times; each call replaces the current registry.
     * Automatically called at init time and after each file-watcher event.
     */
    fun reload() {
        val merged = mutableMapOf<String, ModelPricing>()

        // 1. Load bundled pricing from plugin classpath
        try {
            val stream = ModelPricingRegistry::class.java.getResourceAsStream(BUNDLED_RESOURCE)
            if (stream != null) {
                val text = stream.bufferedReader().readText()
                parseAndMerge(text, merged, source = "bundled")
            } else {
                LOG.warn("Bundled pricing resource not found: $BUNDLED_RESOURCE")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to load bundled pricing: ${e.message}")
        }

        // 2. Load user overrides — keys present here win over bundled
        val userPath = overridePath
        if (Files.isReadable(userPath)) {
            try {
                val text = Files.readString(userPath)
                parseAndMerge(text, merged, source = "user override")
            } catch (e: Exception) {
                LOG.warn("Failed to load user pricing override at $userPath: ${e.message}")
            }
        }

        registry.clear()
        registry.putAll(merged)
        LOG.debug("ModelPricingRegistry loaded ${registry.size} entries")
    }

    // ----- Parsing -----

    /**
     * Parse a pricing JSON object and merge entries into [target].
     *
     * Expected shape (per entry):
     * ```json
     * "claude-sonnet-4": { "in": 3.00, "out": 15.00, "cacheRead": 0.30, "cacheWrite": 3.75}
     * ```
     *
     * The top-level `$comment` key is silently skipped.
     */
    private fun parseAndMerge(
        jsonText: String,
        target: MutableMap<String, ModelPricing>,
        source: String,
    ) {
        try {
            val root = json.parseToJsonElement(jsonText).jsonObject
            for ((key, value) in root) {
                if (key == "\$comment") continue
                try {
                    val entry = value.jsonObject
                    val inputRate = entry["in"]?.jsonPrimitive?.double ?: continue
                    val outputRate = entry["out"]?.jsonPrimitive?.double ?: continue
                    val cacheRead = entry["cacheRead"]?.jsonPrimitive?.doubleOrNull
                    val cacheWrite = entry["cacheWrite"]?.jsonPrimitive?.doubleOrNull

                    target[key] = ModelPricing(
                        modelId = key,
                        inputUsdPer1M = inputRate,
                        outputUsdPer1M = outputRate,
                        cacheReadUsdPer1M = cacheRead,
                        cacheWriteUsdPer1M = cacheWrite,
                    )
                } catch (e: Exception) {
                    LOG.warn("[$source] Skipping malformed pricing entry '$key': ${e.message}")
                }
            }
        } catch (e: Exception) {
            LOG.warn("[$source] Failed to parse pricing JSON: ${e.message}")
        }
    }

    // ----- File watching (mirrors AgentConfigLoader pattern) -----

    private fun startWatching() {
        stopWatching()
        if (disposed.get()) return

        val watchDir = overridePath.parent ?: return
        if (!Files.isDirectory(watchDir)) return

        try {
            val ws = watchDir.fileSystem.newWatchService()
            watchService = ws
            watchDir.register(
                ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE,
            )
            val thread = Thread({
                watchLoop(ws)
            }, "ModelPricingRegistry-Watcher").apply {
                isDaemon = true
            }
            watchThread = thread
            thread.start()
        } catch (e: Exception) {
            LOG.warn("Failed to start pricing file watcher: ${e.message}")
        }
    }

    private fun watchLoop(ws: WatchService) {
        try {
            while (!disposed.get()) {
                val key = ws.take() ?: break
                // 300 ms debounce — coalesce rapid filesystem events
                Thread.sleep(DEBOUNCE_MS)
                key.pollEvents()
                key.reset()

                if (disposed.get()) break
                reload()
            }
        } catch (_: InterruptedException) {
            // Expected on shutdown
        } catch (_: java.nio.file.ClosedWatchServiceException) {
            // Expected on shutdown
        } catch (e: Exception) {
            if (!disposed.get()) {
                LOG.warn("Pricing file watcher error: ${e.message}")
            }
        }
    }

    private fun stopWatching() {
        try {
            watchService?.close()
        } catch (_: Exception) {
        }
        watchService = null
        watchThread?.interrupt()
        watchThread = null
    }

    /** Reset for tests — clears registry and restores the default override path. */
    internal fun resetForTests() {
        stopWatching()
        registry.clear()
        overridePath = USER_OVERRIDE_PATH
    }
}
