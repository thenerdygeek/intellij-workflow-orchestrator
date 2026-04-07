package com.workflow.orchestrator.automation.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.automation.api.DockerRegistryClient
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.PROJECT)
class QueueService : Disposable {

    private val log = Logger.getInstance(QueueService::class.java)

    private var _project: Project? = null

    // Backing fields — set directly by test constructor, or lazily resolved from project
    private var _bambooService: BambooService? = null
    private var _registryClient: DockerRegistryClient? = null
    private var _eventBus: EventBus? = null
    private var _tagHistoryService: TagHistoryService? = null

    private val bambooService: BambooService get() = _bambooService ?: run {
        val p = _project!!
        p.getService(BambooService::class.java).also { _bambooService = it }
    }

    private val registryClient: DockerRegistryClient get() = _registryClient ?: run {
        val p = _project!!
        val settings = PluginSettings.getInstance(p)
        val credentialStore = CredentialStore()
        val registryUrl = (settings.state.dockerRegistryUrl.takeUnless { it.isNullOrBlank() }
            ?: settings.connections.nexusUrl.orEmpty()).trimEnd('/')
        val nexusUsername = settings.connections.nexusUsername.orEmpty()
        val timeouts = com.workflow.orchestrator.core.http.HttpClientFactory.timeoutsFromSettings(p)
        DockerRegistryClient(
            registryUrl = registryUrl,
            tokenProvider = { credentialStore.getNexusBasicAuthToken(nexusUsername) },
            connectTimeoutSeconds = timeouts.connectSeconds,
            readTimeoutSeconds = timeouts.readSeconds
        ).also { _registryClient = it }
    }

    private val eventBus: EventBus get() = _eventBus ?: _project!!.getService(EventBus::class.java).also { _eventBus = it }
    private val tagHistoryService: TagHistoryService get() = _tagHistoryService ?: _project!!.getService(TagHistoryService::class.java).also { _tagHistoryService = it }

    private val scope: CoroutineScope
    private val autoTriggerEnabled: Boolean
    private val maxDepthPerSuite: Int
    private val tagValidationOnTrigger: Boolean
    private val buildVariableName: String

    /** Project service constructor — used by IntelliJ DI. Heavy deps are lazy-inited on first use. */
    constructor(project: Project) {
        this._project = project
        val settings = PluginSettings.getInstance(project)
        this.scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        this.autoTriggerEnabled = settings.state.queueAutoTriggerEnabled
        this.maxDepthPerSuite = settings.state.queueMaxDepthPerSuite
        this.tagValidationOnTrigger = settings.state.tagValidationOnTrigger
        this.buildVariableName = settings.state.bambooBuildVariableName?.takeIf { it.isNotBlank() } ?: "dockerTagsAsJson"
    }

    /** Test constructor — allows injecting mocks. */
    constructor(
        bambooService: BambooService,
        registryClient: DockerRegistryClient,
        eventBus: EventBus,
        tagHistoryService: TagHistoryService,
        scope: CoroutineScope,
        autoTriggerEnabled: Boolean = true,
        maxDepthPerSuite: Int = 10,
        tagValidationOnTrigger: Boolean = true,
        buildVariableName: String = "dockerTagsAsJson"
    ) {
        this._bambooService = bambooService
        this._registryClient = registryClient
        this._eventBus = eventBus
        this._tagHistoryService = tagHistoryService
        this.scope = scope
        this.autoTriggerEnabled = autoTriggerEnabled
        this.maxDepthPerSuite = maxDepthPerSuite
        this.tagValidationOnTrigger = tagValidationOnTrigger
        this.buildVariableName = buildVariableName
    }

    private val _stateFlow = MutableStateFlow<List<QueueEntry>>(emptyList())
    val stateFlow: StateFlow<List<QueueEntry>> = _stateFlow.asStateFlow()

    private val mutex = Mutex()
    private val sequenceCounter = AtomicInteger(0)
    private val pollInProgress = AtomicBoolean(false)
    private var pollingJob: Job? = null

    fun enqueue(entry: QueueEntry) {
        scope.launch {
            mutex.withLock {
                val suiteEntries = _stateFlow.value.count { it.suitePlanKey == entry.suitePlanKey }
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
            }

            if (autoTriggerEnabled) {
                startPollingIfNeeded()
            }
        }
    }

    fun cancel(entryId: String) {
        scope.launch {
            mutex.withLock {
                val entry = _stateFlow.value.find { it.id == entryId } ?: return@launch
                log.info("[Automation:Queue] Cancelling entry $entryId (status=${entry.status}, suite='${entry.suitePlanKey}')")

                val resultKey = entry.bambooResultKey
                if (resultKey != null && entry.status == QueueEntryStatus.QUEUED_ON_BAMBOO) {
                    log.info("[Automation:Queue] Cancelling Bamboo build $resultKey")
                    bambooService.cancelBuild(resultKey)
                }

                tagHistoryService.updateQueueEntryStatus(entryId, QueueEntryStatus.CANCELLED)
                _stateFlow.value = _stateFlow.value.filter { it.id != entryId }

                eventBus.emit(WorkflowEvent.QueuePositionChanged(
                    suitePlanKey = entry.suitePlanKey,
                    position = -1,
                    estimatedWaitMs = null
                ))
            }
        }
    }

    fun getActiveEntries(): List<QueueEntry> = _stateFlow.value

    fun getQueuePositionForSuite(suitePlanKey: String, entryId: String): Int {
        return _stateFlow.value
            .filter { it.suitePlanKey == suitePlanKey }
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
        pollingJob = scope.launch {
            while (true) {
                if (pollInProgress.compareAndSet(false, true)) {
                    try {
                        pollOnce()
                    } finally {
                        pollInProgress.set(false)
                    }
                }
                val hasActive = _stateFlow.value.any { it.status in ACTIVE_STATUSES }
                val interval = if (hasActive) 15_000L else 60_000L
                val jitter = kotlin.random.Random.nextLong(interval / 10)
                delay(interval + jitter)

                if (_stateFlow.value.isEmpty()) {
                    log.info("[Automation:Queue] Queue empty, stopping polling")
                    break
                }
            }
            pollingJob = null
        }
    }

    internal suspend fun pollOnce() {
        mutex.withLock {
            val entries = _stateFlow.value.toList()
            val updatedEntries = mutableListOf<QueueEntry>()

            val bySuite = entries.groupBy { it.suitePlanKey }

            for ((planKey, suiteEntries) in bySuite) {
                for (entry in suiteEntries) {
                    val updated = when (entry.status) {
                        QueueEntryStatus.WAITING_LOCAL -> handleWaitingLocal(planKey, entry)
                        QueueEntryStatus.QUEUED_ON_BAMBOO,
                        QueueEntryStatus.RUNNING -> handleRunningOrQueued(entry)
                        else -> entry
                    }
                    updatedEntries.add(updated)
                }
            }

            _stateFlow.value = updatedEntries
        }
    }

    private suspend fun handleWaitingLocal(planKey: String, entry: QueueEntry): QueueEntry {
        val oldestWaiting = _stateFlow.value
            .firstOrNull { it.suitePlanKey == planKey && it.status == QueueEntryStatus.WAITING_LOCAL }

        if (oldestWaiting?.id != entry.id) return entry

        val runningResult = bambooService.getRunningBuilds(planKey)
        if (!runningResult.isError && runningResult.data.isEmpty()) {
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

        val buildData = result.data
        return when {
            buildData.state == "Successful" || buildData.state == "Failed" -> {
                val passed = buildData.state == "Successful"
                log.info("[Automation:Queue] Build finished for entry ${entry.id}, resultKey=$resultKey, passed=$passed")
                tagHistoryService.updateQueueEntryStatus(
                    entry.id, QueueEntryStatus.COMPLETED, resultKey
                )
                eventBus.emit(WorkflowEvent.AutomationFinished(
                    suitePlanKey = entry.suitePlanKey,
                    buildResultKey = resultKey,
                    passed = passed,
                    durationMs = buildData.durationSeconds * 1000
                ))
                entry.copy(status = QueueEntryStatus.COMPLETED)
            }
            buildData.state == "InProgress" || buildData.state == "Unknown" -> entry.copy(status = QueueEntryStatus.RUNNING)
            else -> entry
        }
    }

    private suspend fun doTrigger(entry: QueueEntry): ToolResult<String> {
        log.info("[Automation:Queue] Triggering build for entry ${entry.id}, suite='${entry.suitePlanKey}', tagValidation=$tagValidationOnTrigger")
        if (tagValidationOnTrigger) {
            val tagsValid = validateTags(entry)
            if (!tagsValid) {
                log.error("[Automation:Queue] Tag validation failed for entry ${entry.id}, aborting trigger")
                tagHistoryService.updateQueueEntryStatus(entry.id, QueueEntryStatus.TAG_INVALID)
                return ToolResult(
                    data = "",
                    summary = "One or more Docker tags no longer exist in the registry",
                    isError = true
                )
            }
        }

        val variables = entry.variables.toMutableMap()
        variables[buildVariableName] = entry.dockerTagsPayload
        log.debug("[Automation:Queue] Using build variable '$buildVariableName' for trigger")

        val result = bambooService.triggerBuild(entry.suitePlanKey, variables)
        return if (!result.isError) {
            val buildKey = result.data.buildKey
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

    private suspend fun validateTags(entry: QueueEntry): Boolean {
        val tags = try {
            val obj = Json.decodeFromString<JsonObject>(entry.dockerTagsPayload)
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        } catch (e: Exception) {
            return false
        }

        for ((service, tag) in tags) {
            val result = registryClient.tagExists(service, tag)
            if (result is ApiResult.Success && !result.data) return false
            if (result is ApiResult.Error) return false
        }
        return true
    }

    fun restoreFromPersistence() {
        scope.launch {
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

    override fun dispose() {
        log.info("[Automation:Queue] QueueService disposing, cancelling polling and scope")
        pollingJob?.cancel()
        scope.cancel()
    }

    private companion object {
        private val ACTIVE_STATUSES = setOf(QueueEntryStatus.RUNNING, QueueEntryStatus.QUEUED_ON_BAMBOO)
    }
}
