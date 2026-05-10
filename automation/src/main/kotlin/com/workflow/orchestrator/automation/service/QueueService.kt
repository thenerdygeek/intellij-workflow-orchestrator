package com.workflow.orchestrator.automation.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.PROJECT)
class QueueService {

    private val log = Logger.getInstance(QueueService::class.java)

    private var _project: Project? = null

    // Backing fields — set directly by test constructor, or lazily resolved from project
    private var _bambooService: BambooService? = null
    private var _eventBus: EventBus? = null
    private var _tagHistoryService: TagHistoryService? = null

    private val bambooService: BambooService get() = _bambooService ?: run {
        val p = _project!!
        p.getService(BambooService::class.java).also { _bambooService = it }
    }

    private val eventBus: EventBus get() = _eventBus ?: _project!!.getService(EventBus::class.java).also { _eventBus = it }
    private val tagHistoryService: TagHistoryService get() = _tagHistoryService ?: _project!!.getService(TagHistoryService::class.java).also { _tagHistoryService = it }

    private val cs: CoroutineScope
    private val autoTriggerEnabled: Boolean
    private val maxDepthPerSuite: Int
    private val buildVariableName: String

    /** Project service constructor — used by IntelliJ DI. Heavy deps are lazy-inited on first use. */
    constructor(project: Project, cs: CoroutineScope) {
        this._project = project
        val settings = PluginSettings.getInstance(project)
        this.cs = cs
        this.autoTriggerEnabled = settings.state.queueAutoTriggerEnabled
        this.maxDepthPerSuite = settings.state.queueMaxDepthPerSuite
        this.buildVariableName = settings.state.bambooBuildVariableName?.takeIf { it.isNotBlank() } ?: "DockerTagsAsJSON"
    }

    /** Test constructor — allows injecting mocks. */
    constructor(
        bambooService: BambooService,
        eventBus: EventBus,
        tagHistoryService: TagHistoryService,
        scope: CoroutineScope,
        autoTriggerEnabled: Boolean = true,
        maxDepthPerSuite: Int = 10,
        buildVariableName: String = "DockerTagsAsJSON"
    ) {
        this._bambooService = bambooService
        this._eventBus = eventBus
        this._tagHistoryService = tagHistoryService
        this.cs = scope
        this.autoTriggerEnabled = autoTriggerEnabled
        this.maxDepthPerSuite = maxDepthPerSuite
        this.buildVariableName = buildVariableName
    }

    private val _stateFlow = MutableStateFlow<List<QueueEntry>>(emptyList())
    val stateFlow: StateFlow<List<QueueEntry>> = _stateFlow.asStateFlow()

    private val mutex = Mutex()
    private val sequenceCounter = AtomicInteger(0)
    private val pollInProgress = AtomicBoolean(false)
    private var pollingJob: Job? = null

    fun enqueue(entry: QueueEntry) {
        cs.launch(Dispatchers.IO) {
            mutex.withLock {
                // PR 8: depth check counts only LIVE entries — terminal rows that
                // the user hasn't dismissed yet must not block new enqueues.
                val suiteEntries = _stateFlow.value.count {
                    it.suitePlanKey == entry.suitePlanKey && it.status !in TERMINAL_STATUSES_SET
                }
                if (suiteEntries >= maxDepthPerSuite) {
                    log.warn("[Automation:Queue] Queue depth limit reached for suite '${entry.suitePlanKey}' (max=$maxDepthPerSuite), rejecting entry ${entry.id}")
                    return@launch
                }

                val seq = sequenceCounter.incrementAndGet()
                tagHistoryService.saveQueueEntry(entry, seq)

                _stateFlow.value = _stateFlow.value + entry

                val position = _stateFlow.value
                    .filter { it.suitePlanKey == entry.suitePlanKey }
                    .indexOfFirst { it.id == entry.id }

                val serviceCount = try {
                    Json.decodeFromString<JsonObject>(entry.dockerTagsPayload).size
                } catch (_: Exception) { 0 }
                log.info("[Automation:Queue] Enqueued build with $serviceCount services, position: $position, suite='${entry.suitePlanKey}', entryId=${entry.id}")

                eventBus.emit(WorkflowEvent.QueuePositionChanged(
                    suitePlanKey = entry.suitePlanKey,
                    position = position,
                    estimatedWaitMs = null
                ))

                // Fast-path: if this is the first entry for this suite AND Bamboo is
                // idle for this plan, trigger immediately instead of waiting for the
                // next poll tick (which could be up to 60s when the queue was empty).
                if (autoTriggerEnabled && suiteEntries == 0) {
                    val runningResult = bambooService.getRunningBuilds(entry.suitePlanKey)
                    if (!runningResult.isError && runningResult.data!!.isEmpty()) {
                        log.info("[Automation:Queue] Fast-path trigger for entry ${entry.id} (queue was empty and Bamboo is idle)")
                        val triggerResult = doTrigger(entry)
                        if (!triggerResult.isError) {
                            val updatedEntry = entry.copy(
                                status = QueueEntryStatus.QUEUED_ON_BAMBOO,
                                bambooResultKey = triggerResult.data
                            )
                            _stateFlow.value = _stateFlow.value.map {
                                if (it.id == entry.id) updatedEntry else it
                            }
                        }
                        // Start poller to track the build we just fired (even on fast-path)
                        startPollingIfNeeded()
                        return@withLock
                    }
                }
            }

            if (autoTriggerEnabled) {
                startPollingIfNeeded()
            }
        }
    }

    /**
     * User-initiated cancel: stop the run on Bamboo (if applicable) and transition
     * the entry to [QueueEntryStatus.CANCELLED]. The entry STAYS in [_stateFlow]
     * so the user can still see it in the Monitor list — they remove it explicitly
     * via [dismiss]. (PR 8: Monitor lifecycle no longer auto-prunes terminal entries.)
     */
    fun cancel(entryId: String) {
        cs.launch(Dispatchers.IO) {
            mutex.withLock {
                val entry = _stateFlow.value.find { it.id == entryId } ?: return@launch
                log.info("[Automation:Queue] Cancelling entry $entryId (status=${entry.status}, suite='${entry.suitePlanKey}')")

                val resultKey = entry.bambooResultKey
                if (resultKey != null && entry.status == QueueEntryStatus.QUEUED_ON_BAMBOO) {
                    log.info("[Automation:Queue] Cancelling Bamboo build $resultKey")
                    bambooService.cancelBuild(resultKey)
                }

                tagHistoryService.updateQueueEntryStatus(entryId, QueueEntryStatus.CANCELLED)
                _stateFlow.value = _stateFlow.value.map {
                    if (it.id == entryId) it.copy(status = QueueEntryStatus.CANCELLED) else it
                }

                eventBus.emit(WorkflowEvent.QueuePositionChanged(
                    suitePlanKey = entry.suitePlanKey,
                    position = -1,
                    estimatedWaitMs = null
                ))
            }
        }
    }

    /**
     * Removes a terminal entry from the active list. The Monitor panel exposes
     * this as the per-row "Remove" button. No-op (with a warn log) if the entry
     * is non-terminal — callers should use [cancel] for live entries first.
     */
    fun dismiss(entryId: String) {
        cs.launch(Dispatchers.IO) {
            mutex.withLock {
                val entry = _stateFlow.value.find { it.id == entryId } ?: return@launch
                if (entry.status !in QueueEntryStatus.TERMINAL) {
                    log.warn("[Automation:Queue] dismiss($entryId) ignored — entry is non-terminal (status=${entry.status})")
                    return@launch
                }
                log.info("[Automation:Queue] Dismissing terminal entry $entryId (status=${entry.status})")
                _stateFlow.value = _stateFlow.value.filter { it.id != entryId }
            }
        }
    }

    /** Live (non-terminal) entries only. PR 8: terminal rows persist in [_stateFlow]
     *  but should not be exposed via "active" accessors. UI subscribers that need
     *  to render terminal rows should read [stateFlow] directly. */
    fun getActiveEntries(): List<QueueEntry> =
        _stateFlow.value.filter { it.status !in TERMINAL_STATUSES_SET }

    fun getQueuePositionForSuite(suitePlanKey: String, entryId: String): Int {
        return _stateFlow.value
            .filter { it.suitePlanKey == suitePlanKey && it.status !in TERMINAL_STATUSES_SET }
            .indexOfFirst { it.id == entryId }
    }

    suspend fun triggerNow(entry: QueueEntry): ToolResult<String> {
        log.info("[Automation:Queue] Manual trigger requested for entry ${entry.id}, suite='${entry.suitePlanKey}'")
        return mutex.withLock {
            doTrigger(entry)
        }
    }

    private fun startPollingIfNeeded() {
        if (pollingJob?.isActive == true) return
        log.info("[Automation:Queue] Starting queue polling")
        pollingJob = cs.launch(Dispatchers.IO) {
            while (true) {
                if (pollInProgress.compareAndSet(false, true)) {
                    try {
                        pollOnce()
                    } finally {
                        pollInProgress.set(false)
                    }
                }
                // Three-tier cadence (user reported up-to-30s delay between Trigger
                // click and Bamboo build start at v0.85.x — fast-path skipped because
                // Bamboo's prior build still showed Queued/Pending, then we waited 15s
                // for the next tick, sometimes twice):
                //   - Any WAITING_LOCAL entry → 3s. We're polling specifically to
                //     catch the moment Bamboo turns idle so we can fire doTrigger;
                //     responsiveness here is what the user perceives as queue lag.
                //   - Active but no WAITING_LOCAL (entries are QUEUED_ON_BAMBOO/RUNNING)
                //     → 15s. We're just monitoring already-triggered builds; Bamboo
                //     state changes on the order of minutes for these.
                //   - Queue empty → 60s. Heartbeat for safety; we'd normally `break`
                //     out below anyway.
                val state = _stateFlow.value
                val hasWaitingLocal = state.any { it.status == QueueEntryStatus.WAITING_LOCAL }
                val hasActive = state.any { it.status in ACTIVE_STATUSES }
                val interval = when {
                    hasWaitingLocal -> 3_000L
                    hasActive -> 15_000L
                    else -> 60_000L
                }
                val jitter = kotlin.random.Random.nextLong(interval / 10)
                delay(interval + jitter)

                // PR 8: terminal entries persist in _stateFlow but don't need polling.
                // Stop the loop when there is no remaining live work — even if the list
                // still contains COMPLETED/FAILED/CANCELLED rows the user hasn't dismissed.
                if (_stateFlow.value.none { it.status !in TERMINAL_STATUSES_SET }) {
                    log.info("[Automation:Queue] No live entries (terminal-only or empty), stopping polling")
                    break
                }
            }
            pollingJob = null
        }
    }

    internal suspend fun pollOnce() {
        mutex.withLock {
            val entries = _stateFlow.value.toList()

            val bySuite = entries.groupBy { it.suitePlanKey }

            for ((planKey, suiteEntries) in bySuite) {
                for (entry in suiteEntries) {
                    when (entry.status) {
                        QueueEntryStatus.WAITING_LOCAL -> {
                            val updated = handleWaitingLocal(planKey, entry)
                            // Replace in-place: the entry may have advanced to QUEUED_ON_BAMBOO
                            _stateFlow.value = _stateFlow.value.map {
                                if (it.id == entry.id) updated else it
                            }
                        }
                        QueueEntryStatus.QUEUED_ON_BAMBOO,
                        QueueEntryStatus.RUNNING -> {
                            val updated = handleRunningOrQueued(entry)
                            // PR 8: replace in-place for ALL outcomes — terminal entries
                            // now persist in _stateFlow until the user dismisses them.
                            _stateFlow.value = _stateFlow.value.map {
                                if (it.id == entry.id) updated else it
                            }
                        }
                        else -> { /* already terminal — no-op (skip polling) */ }
                    }
                }
            }
        }
    }

    private suspend fun handleWaitingLocal(planKey: String, entry: QueueEntry): QueueEntry {
        val oldestWaiting = _stateFlow.value
            .firstOrNull { it.suitePlanKey == planKey && it.status == QueueEntryStatus.WAITING_LOCAL }

        if (oldestWaiting?.id != entry.id) return entry

        val runningResult = bambooService.getRunningBuilds(planKey)
        if (!runningResult.isError && runningResult.data!!.isEmpty()) {
            val triggerResult = doTrigger(entry)
            return if (!triggerResult.isError) {
                log.info("[Automation:Queue] Auto-triggered entry ${entry.id} on Bamboo, resultKey=${triggerResult.data}")
                entry.copy(
                    status = QueueEntryStatus.QUEUED_ON_BAMBOO,
                    bambooResultKey = triggerResult.data
                )
            } else {
                log.error("[Automation:Queue] Failed to auto-trigger entry ${entry.id} for suite '${entry.suitePlanKey}'")
                entry.copy(status = QueueEntryStatus.FAILED_TO_TRIGGER)
            }
        }

        return entry
    }

    private suspend fun handleRunningOrQueued(entry: QueueEntry): QueueEntry {
        val resultKey = entry.bambooResultKey ?: return entry

        val result = bambooService.getBuild(resultKey)
        if (result.isError) return entry

        val buildData = result.data!!
        return when {
            buildData.state == "Successful" || buildData.state == "Failed" -> {
                val passed = buildData.state == "Successful"
                val terminalStatus = if (passed) QueueEntryStatus.COMPLETED else QueueEntryStatus.FAILED
                log.info("[Automation:Queue] Build finished for entry ${entry.id}, resultKey=$resultKey, passed=$passed → $terminalStatus")
                tagHistoryService.updateQueueEntryStatus(entry.id, terminalStatus, resultKey)
                eventBus.emit(WorkflowEvent.AutomationFinished(
                    suitePlanKey = entry.suitePlanKey,
                    buildResultKey = resultKey,
                    passed = passed,
                    durationMs = buildData.durationSeconds * 1000
                ))
                // PR 8: terminal entries STAY in _stateFlow — Monitor list keeps them
                // until the user dismisses. pollOnce skips terminal-status entries.
                entry.copy(status = terminalStatus)
            }
            // "Unknown" = Bamboo lifeCycleState "NotBuilt" (manual skip / already-up-to-date).
            // Treat as terminal (COMPLETED) to prevent infinite polling.
            buildData.state == "Unknown" -> {
                log.info("[Automation:Queue] Build in Unknown/NotBuilt state for entry ${entry.id}, treating as COMPLETED")
                tagHistoryService.updateQueueEntryStatus(
                    entry.id, QueueEntryStatus.COMPLETED, resultKey
                )
                entry.copy(status = QueueEntryStatus.COMPLETED)
            }
            else -> entry.copy(status = QueueEntryStatus.RUNNING)
        }
    }

    private suspend fun doTrigger(entry: QueueEntry): ToolResult<String> {
        log.info("[Automation:Queue] Triggering build for entry ${entry.id}, suite='${entry.suitePlanKey}', stages=${entry.stages ?: "all"}")

        val variables = entry.variables.toMutableMap()
        variables[buildVariableName] = entry.dockerTagsPayload
        log.debug("[Automation:Queue] Using build variable '$buildVariableName' for trigger")

        val result = bambooService.triggerBuild(entry.suitePlanKey, variables, entry.stages)
        return if (!result.isError) {
            val buildKey = result.data!!.buildKey
            log.info("[Automation:Queue] Build triggered successfully, buildKey=$buildKey")
            tagHistoryService.updateQueueEntryStatus(
                entry.id, QueueEntryStatus.QUEUED_ON_BAMBOO, buildKey
            )
            eventBus.emit(WorkflowEvent.AutomationTriggered(
                suitePlanKey = entry.suitePlanKey,
                buildResultKey = buildKey,
                dockerTagsJson = entry.dockerTagsPayload,
                triggeredBy = if (autoTriggerEnabled) "auto-queue" else "manual"
            ))
            ToolResult.success(data = buildKey, summary = "Build triggered: $buildKey")
        } else {
            log.error("[Automation:Queue] Build trigger failed for entry ${entry.id}: ${result.summary}")
            tagHistoryService.updateQueueEntryStatus(
                entry.id, QueueEntryStatus.FAILED_TO_TRIGGER,
                errorMessage = result.summary
            )
            ToolResult(
                data = "",
                summary = "Build trigger failed: ${result.summary}",
                isError = true
            )
        }
    }

    fun restoreFromPersistence() {
        cs.launch(Dispatchers.IO) {
            mutex.withLock {
                val persisted = tagHistoryService.getActiveQueueEntries()
                log.info("[Automation:Queue] Restored ${persisted.size} entries from persistence")
                if (persisted.isNotEmpty()) {
                    _stateFlow.value = persisted
                    startPollingIfNeeded()
                }
            }
        }
    }

    private companion object {
        private val ACTIVE_STATUSES = setOf(QueueEntryStatus.RUNNING, QueueEntryStatus.QUEUED_ON_BAMBOO)
        /** Delegates to the canonical set on the enum — single source of truth. */
        private val TERMINAL_STATUSES_SET = QueueEntryStatus.TERMINAL
    }
}
