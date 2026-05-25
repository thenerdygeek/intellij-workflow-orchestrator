package com.workflow.orchestrator.automation.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.automation.model.BaselineDiagnostics
import com.workflow.orchestrator.automation.model.BaselineLoadResult
import com.workflow.orchestrator.automation.model.BaselineRun
import com.workflow.orchestrator.automation.model.RegistryStatus
import com.workflow.orchestrator.automation.model.TagEntry
import com.workflow.orchestrator.automation.model.TagSource
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Project-level cache of the last computed [BaselineLoadResult] per suite plan key.
 *
 * **Why this exists.** Without a cache, switching between Automation suites or
 * reopening the tool window after IDE restart triggers a fresh Bamboo scan on
 * every transition. The displayed baseline is small, terminal-build data is
 * effectively immutable (modulo stage re-runs — see spec §"Non-goals"), and the
 * user already has a Refresh button as the explicit cache-bust signal.
 *
 * **Schema.** Stored under
 * `~/.workflow-orchestrator/{slug}-{sha6}/automation/baseline-cache.json` via
 * atomic write (`.tmp` + `Files.move(ATOMIC_MOVE)`). No TTL, no eviction —
 * see spec for the immutability + size justification.
 */
@Service(Service.Level.PROJECT)
class BaselineCacheService(private val cacheDir: File) {

    private val log = Logger.getInstance(BaselineCacheService::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }
    // Guards the in-memory [entries] map and the [writeSeq] counter. Held only
    // for fast in-memory mutation + serialization — never during disk I/O, so a
    // concurrent get() is never blocked by a put()'s file write.
    private val mutex = Mutex()
    // Serializes disk writes w.r.t. each other (so two puts can't interleave a
    // tmp-write/atomic-move) without blocking reads under [mutex].
    private val diskMutex = Mutex()
    private val cacheFile: File get() = File(cacheDir, "baseline-cache.json")

    // In-memory state — source of truth at runtime. Disk is the durability layer.
    private val entries: MutableMap<String, CachedSuiteEntry> = mutableMapOf()

    // Monotonic write version, incremented under [mutex] each time a snapshot is
    // produced. The disk writer records the highest version actually written and
    // drops any snapshot whose version is older — so even if coroutines reach the
    // file-write step out of order, the newest in-memory state always wins on disk.
    private var writeSeq: Long = 0
    private var lastPersistedSeq: Long = 0

    init {
        loadFromDisk()
    }

    /** Used by IntelliJ DI — derives cacheDir from the project base path. */
    @Suppress("unused")
    constructor(project: Project) : this(
        ProjectIdentifier.automationDir(project.basePath ?: System.getProperty("user.home"))
    )

    suspend fun get(planKey: String): BaselineLoadResult? = mutex.withLock {
        entries[planKey]?.toModel()
    }

    suspend fun put(planKey: String, result: BaselineLoadResult) {
        val snapshot = mutex.withLock {
            entries[planKey] = CachedSuiteEntry.fromModel(planKey, result)
            takeSnapshotLocked()
        }
        persistSnapshot(snapshot)
    }

    suspend fun invalidate(planKey: String) {
        val snapshot = mutex.withLock {
            if (entries.remove(planKey) == null) return
            takeSnapshotLocked()
        }
        persistSnapshot(snapshot)
    }

    /** Reads disk into the in-memory map. Called once at service construction. */
    private fun loadFromDisk() {
        if (!cacheFile.exists()) {
            log.info("[Automation:Cache] No baseline cache at ${cacheFile.absolutePath}; starting empty.")
            return
        }
        try {
            val raw = cacheFile.readText(Charsets.UTF_8)
            val decoded = json.decodeFromString<CacheFile>(raw)
            if (decoded.version != SCHEMA_VERSION) {
                log.warn("[Automation:Cache] Ignoring cache with unknown schema version ${decoded.version} (expected $SCHEMA_VERSION); starting empty.")
                return
            }
            entries.clear()
            entries.putAll(decoded.entries)
            log.info("[Automation:Cache] Loaded ${entries.size} cached suite baseline(s) from disk.")
        } catch (e: Exception) {
            log.warn("[Automation:Cache] Failed to parse ${cacheFile.absolutePath}; treating as empty. (${e.message})")
            // Leave entries empty; next write will overwrite the corrupt file.
        }
    }

    /**
     * Captures the current in-memory state as an immutable [DiskSnapshot]: a
     * fully-serialized JSON string plus a monotonic version. MUST be called while
     * holding [mutex] so the serialized bytes and the assigned version are
     * consistent with the same map state.
     */
    private fun takeSnapshotLocked(): DiskSnapshot {
        val seq = ++writeSeq
        val serialized = json.encodeToString(CacheFile(version = SCHEMA_VERSION, entries = entries.toMap()))
        return DiskSnapshot(seq = seq, serialized = serialized)
    }

    /**
     * Atomic write of a pre-serialized [DiskSnapshot] — tmp + move with
     * REPLACE_EXISTING + ATOMIC_MOVE — performed under [diskMutex] so reads under
     * [mutex] are never blocked by disk I/O. The [diskMutex] also serializes
     * concurrent writers, and the version guard drops any snapshot that is older
     * than what has already been persisted, so the latest state always wins even
     * when coroutines reach this step out of order.
     */
    private suspend fun persistSnapshot(snapshot: DiskSnapshot) = diskMutex.withLock {
        if (snapshot.seq <= lastPersistedSeq) {
            // A newer snapshot already reached disk; this one is stale — skip it.
            return@withLock
        }
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "baseline-cache.json.tmp.${System.currentTimeMillis()}.${(Math.random() * 100000).toInt()}")
        try {
            tmp.writeText(snapshot.serialized, Charsets.UTF_8)
            Files.move(
                tmp.toPath(),
                cacheFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            lastPersistedSeq = snapshot.seq
        } catch (e: Exception) {
            tmp.delete()
            log.warn("[Automation:Cache] Failed to persist cache: ${e.message}")
        }
    }

    /** Immutable, pre-serialized disk-write unit produced under [mutex]. */
    private class DiskSnapshot(val seq: Long, val serialized: String)

    companion object {
        private const val SCHEMA_VERSION = 1

        /** Test-only factory — bypasses IntelliJ DI so tests can use a TempDir. */
        fun forTesting(cacheDir: File): BaselineCacheService = BaselineCacheService(cacheDir)
    }

    // ---- Internal @Serializable representation ----
    //
    // The agent's BaselineRun / BaselineLoadResult use `java.time.Instant` and
    // are not annotated. Rather than push @Serializable into the model layer,
    // we keep dedicated DTOs here with explicit toModel/fromModel mappers.

    @Serializable
    private data class CacheFile(
        val version: Int,
        val entries: Map<String, CachedSuiteEntry>
    )

    @Serializable
    private data class CachedSuiteEntry(
        val planKey: String,
        val fetchedAtEpochMillis: Long,
        val selectedBuildNumber: Int?,
        val ranked: List<CachedBaselineRun>,
        val diagnostics: CachedDiagnostics
    ) {
        fun toModel(): BaselineLoadResult {
            val rankedRuns = ranked.map { it.toModel() }
            val selected = selectedBuildNumber?.let { num ->
                rankedRuns.firstOrNull { it.buildNumber == num }
            }
            val tags = selected?.dockerTags?.map { (svc, tag) ->
                TagEntry(
                    serviceName = svc,
                    currentTag = tag,
                    latestReleaseTag = null,
                    source = TagSource.BASELINE,
                    registryStatus = RegistryStatus.UNKNOWN,
                    isDrift = false,
                    isCurrentRepo = false
                )
            } ?: emptyList()
            return BaselineLoadResult(
                tags = tags,
                selectedBuild = selected,
                diagnostics = diagnostics.toModel(),
                allRanked = rankedRuns
            )
        }

        companion object {
            fun fromModel(planKey: String, result: BaselineLoadResult): CachedSuiteEntry =
                CachedSuiteEntry(
                    planKey = planKey,
                    fetchedAtEpochMillis = Instant.now().toEpochMilli(),
                    selectedBuildNumber = result.selectedBuild?.buildNumber,
                    ranked = result.allRanked.map { CachedBaselineRun.fromModel(it) },
                    diagnostics = CachedDiagnostics.fromModel(result.diagnostics)
                )
        }
    }

    @Serializable
    private data class CachedBaselineRun(
        val buildNumber: Int,
        val resultKey: String,
        val dockerTags: Map<String, String>,
        val releaseTagCount: Int,
        val totalServices: Int,
        val successfulStages: Int,
        val failedStages: Int,
        val triggeredAtEpochMillis: Long,
        val score: Int
    ) {
        fun toModel(): BaselineRun = BaselineRun(
            buildNumber = buildNumber,
            resultKey = resultKey,
            dockerTags = dockerTags,
            releaseTagCount = releaseTagCount,
            totalServices = totalServices,
            successfulStages = successfulStages,
            failedStages = failedStages,
            triggeredAt = Instant.ofEpochMilli(triggeredAtEpochMillis),
            score = score
        )

        companion object {
            fun fromModel(run: BaselineRun): CachedBaselineRun = CachedBaselineRun(
                buildNumber = run.buildNumber,
                resultKey = run.resultKey,
                dockerTags = run.dockerTags,
                releaseTagCount = run.releaseTagCount,
                totalServices = run.totalServices,
                successfulStages = run.successfulStages,
                failedStages = run.failedStages,
                triggeredAtEpochMillis = run.triggeredAt.toEpochMilli(),
                score = run.score
            )
        }
    }

    @Serializable
    private data class CachedDiagnostics(
        val buildsQueried: Int,
        val buildsWithVariables: Int,
        val buildsWithDockerTags: Int,
        val bambooError: String?,
        val skippedReasons: List<String>
    ) {
        fun toModel(): BaselineDiagnostics = BaselineDiagnostics(
            buildsQueried = buildsQueried,
            buildsWithVariables = buildsWithVariables,
            buildsWithDockerTags = buildsWithDockerTags,
            bambooError = bambooError,
            skippedReasons = skippedReasons
        )

        companion object {
            fun fromModel(d: BaselineDiagnostics) = CachedDiagnostics(
                buildsQueried = d.buildsQueried,
                buildsWithVariables = d.buildsWithVariables,
                buildsWithDockerTags = d.buildsWithDockerTags,
                bambooError = d.bambooError,
                skippedReasons = d.skippedReasons
            )
        }
    }
}
