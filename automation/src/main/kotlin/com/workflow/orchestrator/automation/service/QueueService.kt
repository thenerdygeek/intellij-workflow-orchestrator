package com.workflow.orchestrator.automation.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CancellationException
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
    private val pollingLifecycle = PollingLifecycle()

    /**
     * Wrap every fire-and-forget queue mutation so a SQLite I/O blip or
     * unexpected NPE is surfaced (log.error + balloon) instead of vanishing
     * into the void. The IDE-provided scope already absorbs the throw via
     * `SupervisorJob`, but pre-fix the user had no signal that their click did
     * nothing — flaw #9 in the local-queue audit.
     *
     * [CancellationException] is re-thrown so structured concurrency keeps
     * working.
     */
    private fun launchWithErrorSurface(operation: String, block: suspend () -> Unit) {
        cs.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log.error("[Automation:Queue] $operation failed: ${t.message}", t)
                notifyError(operation, t)
            }
        }
    }

    private fun notifyError(operation: String, t: Throwable) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("workflow.automation.queue")
                .createNotification(
                    "Automation queue: $operation failed",
                    t.message ?: t.javaClass.simpleName,
                    NotificationType.ERROR
                )
                .notify(_project)
        } catch (e: Throwable) {
            // NotificationGroupManager is unavailable in unit tests (no IDE
            // Application). Never let a notification failure cascade.
            log.warn("[Automation:Queue] Could not fire error notification: ${e.message}")
        }
    }

    fun enqueue(entry: QueueEntry) {
        launchWithErrorSurface("Enqueue") {
            mutex.withLock {
                // PR 8: depth check counts only LIVE entries — terminal rows that
                // the user hasn't dismissed yet must not block new enqueues.
                val suiteEntries = _stateFlow.value.count {
                    it.suitePlanKey == entry.suitePlanKey && it.status !in TERMINAL_STATUSES_SET
                }
                if (suiteEntries >= maxDepthPerSuite) {
                    log.warn("[Automation:Queue] Queue depth limit reached for suite '${entry.suitePlanKey}' (max=$maxDepthPerSuite), rejecting entry ${entry.id}")
                    return@launchWithErrorSurface
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
        launchWithErrorSurface("Cancel") {
            mutex.withLock {
                val entry = _stateFlow.value.find { it.id == entryId } ?: return@launchWithErrorSurface
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
        launchWithErrorSurface("Dismiss") {
            mutex.withLock {
                val entry = _stateFlow.value.find { it.id == entryId } ?: return@launchWithErrorSurface
                if (entry.status !in QueueEntryStatus.TERMINAL) {
                    log.warn("[Automation:Queue] dismiss($entryId) ignored — entry is non-terminal (status=${entry.status})")
                    return@launchWithErrorSurface
                }
                log.info("[Automation:Queue] Dismissing terminal entry $entryId (status=${entry.status})")
                _stateFlow.value = _stateFlow.value.filter { it.id != entryId }
                // Also drop from SQLite — otherwise automation.db grows without bound and
                // the row would re-surface on the next IDE start (terminal rows stay in
                // the DB; getActiveQueueEntries filters them but the bytes are still
                // there). dismiss() is the user's explicit "I'm done with this row" signal.
                tagHistoryService.deleteQueueEntry(entryId)
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
        pollingLifecycle.startIfNeeded {
            log.info("[Automation:Queue] Starting queue polling")
            cs.launch(Dispatchers.IO) {
                val self = coroutineContext[Job]!!
                try {
                    while (true) {
                        if (pollInProgress.compareAndSet(false, true)) {
                            try {
                                pollOnce()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                // Flaw #9: keep the poll loop alive across a single bad
                                // tick — without this catch, a transient SQLite/Bamboo
                                // exception would unwind the lambda and the loop would
                                // exit (forcing the user to re-trigger something to
                                // restart polling). The error is logged so it's still
                                // visible in idea.log; we deliberately do NOT balloon-
                                // notify on every tick of a persistent issue.
                                log.error("[Automation:Queue] pollOnce iteration failed; loop continues", t)
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
                        // Atomic exit-and-clear via [PollingLifecycle.tryExit] so an enqueue
                        // racing with our exit never sees `isActive == true` after we've decided
                        // to break — without it the new entry would be orphaned in WAITING_LOCAL.
                        val shouldExit = pollingLifecycle.tryExit(self) {
                            _stateFlow.value.any { it.status !in TERMINAL_STATUSES_SET }
                        }
                        if (shouldExit) {
                            log.info("[Automation:Queue] No live entries (terminal-only or empty), stopping polling")
                            break
                        }
                    }
                } finally {
                    // Defensive: cancellation / unexpected exception still releases the slot.
                    pollingLifecycle.clearIfStillOwnedBy(self)
                }
            }
        }
    }

    internal suspend fun pollOnce() {
        // Flaw #8: pre-fix this entire loop held [mutex] across every Bamboo HTTP
        // call. With 3 suites × 5 entries, a single poll tick blocked every user
        // action (cancel / dismiss / enqueue) for up to 15 sequential HTTPs.
        //
        // The fix: snapshot the entry IDs under the lock (microseconds), then
        // re-acquire the lock once *per entry* for the read-HTTP-write step.
        // Each entry's processing is still atomic (mutex held across one entry's
        // HTTP), but user actions can now interleave between entries instead of
        // queuing behind the whole tick. Worst-case lock-wait for the user is
        // now one entry's HTTP, not N.
        val entryIds = mutex.withLock { _stateFlow.value.map { it.id } }

        for (id in entryIds) {
            mutex.withLock {
                // Re-resolve under the lock — the entry may have been cancelled,
                // dismissed, or otherwise mutated by a user action that
                // interleaved between iterations.
                val entry = _stateFlow.value.find { it.id == id } ?: return@withLock
                val updated = when (entry.status) {
                    QueueEntryStatus.WAITING_LOCAL ->
                        handleWaitingLocal(entry.suitePlanKey, entry)
                    QueueEntryStatus.QUEUED_ON_BAMBOO,
                    QueueEntryStatus.RUNNING ->
                        handleRunningOrQueued(entry)
                    // PR 8: terminal entries stay in _stateFlow but don't need
                    // polling. Skip cleanly so they don't burn an HTTP call.
                    else -> return@withLock
                }
                if (updated !== entry) {
                    _stateFlow.value = _stateFlow.value.map {
                        if (it.id == id) updated else it
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
            if (!triggerResult.isError) {
                log.info("[Automation:Queue] Auto-triggered entry ${entry.id} on Bamboo, resultKey=${triggerResult.data}")
                return entry.copy(
                    status = QueueEntryStatus.QUEUED_ON_BAMBOO,
                    bambooResultKey = triggerResult.data
                )
            }

            // Classify the failure. Transient classes mean "Bamboo couldn't accept
            // the trigger right now" (5xx after HTTP-layer retries, a concurrent-
            // trigger 409 surfaced as SERVER_ERROR, network blip, rate limit). We
            // leave the entry in WAITING_LOCAL so the next poll tick retries —
            // burning it terminally would mean the user manually re-queues every
            // time Bamboo hiccups. Permanent classes (auth/perm/not-found/validation)
            // can't be fixed by waiting, so they go terminal here.
            val errorType = triggerResult.payload as? ErrorType
            return if (errorType in TRANSIENT_ERROR_TYPES) {
                log.warn("[Automation:Queue] Transient trigger failure for entry ${entry.id} (${errorType?.name}); will retry on next poll tick: ${triggerResult.summary}")
                entry
            } else {
                log.error("[Automation:Queue] Failed to auto-trigger entry ${entry.id} for suite '${entry.suitePlanKey}' (${errorType?.name ?: "no error type"}): ${triggerResult.summary}")
                tagHistoryService.updateQueueEntryStatus(
                    entry.id, QueueEntryStatus.FAILED_TO_TRIGGER,
                    errorMessage = triggerResult.summary
                )
                entry.copy(
                    status = QueueEntryStatus.FAILED_TO_TRIGGER,
                    errorMessage = triggerResult.summary
                )
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
        val triggerKey = entry.branchKey ?: entry.suitePlanKey
        log.info("[Automation:Queue] Triggering build for entry ${entry.id}, suite='${entry.suitePlanKey}', branch='${entry.branchKey ?: "<master>"}', stages=${entry.stages ?: "all"}")

        val variables = entry.variables.toMutableMap()
        variables[buildVariableName] = entry.dockerTagsPayload
        log.debug("[Automation:Queue] Using build variable '$buildVariableName' for trigger")

        val result = bambooService.triggerBuild(triggerKey, variables, entry.stages)
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
            // No log or SQLite write here — the caller (handleWaitingLocal /
            // fast-path) classifies the failure and decides whether to mark
            // FAILED_TO_TRIGGER (permanent) or leave the entry in WAITING_LOCAL
            // (transient). Forward `payload` so the caller can read ErrorType.
            ToolResult(
                data = "",
                summary = result.summary,
                isError = true,
                payload = result.payload
            )
        }
    }

    fun restoreFromPersistence() {
        launchWithErrorSurface("Restore from persistence") {
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

        /**
         * [ErrorType]s that mean "Bamboo couldn't accept the trigger right now,
         * try again later." Entries that hit one of these stay in WAITING_LOCAL
         * and are retried on the next poll tick — they do NOT transition to
         * FAILED_TO_TRIGGER. Any other [ErrorType] (or a missing payload) is
         * treated as permanent.
         *
         * Note: HTTP-layer 5xx retries already happen in core's RetryInterceptor
         * (3 attempts with exponential backoff). This set covers the cases that
         * survive that — 5xx that persists past retries, 4xx conflicts surfaced
         * as SERVER_ERROR (Bamboo's "plan already queued" race), and IO blips
         * that bubble up as NETWORK_ERROR / TIMEOUT.
         */
        private val TRANSIENT_ERROR_TYPES = setOf(
            ErrorType.SERVER_ERROR,
            ErrorType.NETWORK_ERROR,
            ErrorType.TIMEOUT,
            ErrorType.RATE_LIMITED
        )
    }
}
