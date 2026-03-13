package com.workflow.orchestrator.automation.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.automation.api.DockerRegistryClient
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ServiceType
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
    private val bambooClient: BambooApiClient
    private val registryClient: DockerRegistryClient
    private val eventBus: EventBus
    private val tagHistoryService: TagHistoryService
    private val scope: CoroutineScope
    private val autoTriggerEnabled: Boolean
    private val maxDepthPerSuite: Int
    private val tagValidationOnTrigger: Boolean
    private val buildVariableName: String

    /** Project service constructor — used by IntelliJ DI. */
    constructor(project: Project) {
        val settings = PluginSettings.getInstance(project)
        val credentialStore = CredentialStore()
        this.bambooClient = BambooApiClient(
            baseUrl = settings.connections.bambooUrl.orEmpty().trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) },
            connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
            readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
        )
        val registryUrl = (settings.state.dockerRegistryUrl.takeUnless { it.isNullOrBlank() }
            ?: settings.connections.nexusUrl.orEmpty()).trimEnd('/')
        val nexusUsername = settings.connections.nexusUsername.orEmpty()
        this.registryClient = DockerRegistryClient(
            registryUrl = registryUrl,
            tokenProvider = { credentialStore.getNexusBasicAuthToken(nexusUsername) },
            connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
            readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
        )
        this.eventBus = project.getService(EventBus::class.java)
        this.tagHistoryService = project.getService(TagHistoryService::class.java)
        this.scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        this.autoTriggerEnabled = settings.state.queueAutoTriggerEnabled
        this.maxDepthPerSuite = settings.state.queueMaxDepthPerSuite
        this.tagValidationOnTrigger = settings.state.tagValidationOnTrigger
        this.buildVariableName = settings.state.bambooBuildVariableName?.takeIf { it.isNotBlank() } ?: "dockerTagsAsJson"
    }

    /** Test constructor — allows injecting mocks. */
    constructor(
        bambooClient: BambooApiClient,
        registryClient: DockerRegistryClient,
        eventBus: EventBus,
        tagHistoryService: TagHistoryService,
        scope: CoroutineScope,
        autoTriggerEnabled: Boolean = true,
        maxDepthPerSuite: Int = 10,
        tagValidationOnTrigger: Boolean = true,
        buildVariableName: String = "dockerTagsAsJson"
    ) {
        this.bambooClient = bambooClient
        this.registryClient = registryClient
        this.eventBus = eventBus
        this.tagHistoryService = tagHistoryService
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

                if (entry.bambooResultKey != null &&
                    entry.status in listOf(QueueEntryStatus.QUEUED_ON_BAMBOO)) {
                    log.info("[Automation:Queue] Cancelling Bamboo build ${entry.bambooResultKey}")
                    bambooClient.cancelBuild(entry.bambooResultKey!!)
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

    suspend fun triggerNow(entry: QueueEntry): ApiResult<String> {
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
                val hasActive = _stateFlow.value.any {
                    it.status in listOf(QueueEntryStatus.RUNNING, QueueEntryStatus.QUEUED_ON_BAMBOO)
                }
                delay(if (hasActive) 15_000L else 60_000L)

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
            .filter { it.suitePlanKey == planKey && it.status == QueueEntryStatus.WAITING_LOCAL }
            .firstOrNull()

        if (oldestWaiting?.id != entry.id) return entry

        val runningResult = bambooClient.getRunningAndQueuedBuilds(planKey)
        if (runningResult is ApiResult.Success && runningResult.data.isEmpty()) {
            val triggerResult = doTrigger(entry)
            return if (triggerResult is ApiResult.Success) {
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

        val result = bambooClient.getBuildResult(resultKey)
        if (result !is ApiResult.Success) return entry

        val dto = result.data
        return when (dto.lifeCycleState) {
            "Finished" -> {
                val passed = dto.state == "Successful"
                log.info("[Automation:Queue] Build finished for entry ${entry.id}, resultKey=$resultKey, passed=$passed")
                tagHistoryService.updateQueueEntryStatus(
                    entry.id, QueueEntryStatus.COMPLETED, resultKey
                )
                eventBus.emit(WorkflowEvent.AutomationFinished(
                    suitePlanKey = entry.suitePlanKey,
                    buildResultKey = resultKey,
                    passed = passed,
                    durationMs = dto.buildDurationInSeconds * 1000
                ))
                entry.copy(status = QueueEntryStatus.COMPLETED)
            }
            "InProgress" -> entry.copy(status = QueueEntryStatus.RUNNING)
            else -> entry
        }
    }

    private suspend fun doTrigger(entry: QueueEntry): ApiResult<String> {
        log.info("[Automation:Queue] Triggering build for entry ${entry.id}, suite='${entry.suitePlanKey}', tagValidation=$tagValidationOnTrigger")
        if (tagValidationOnTrigger) {
            val tagsValid = validateTags(entry)
            if (!tagsValid) {
                log.error("[Automation:Queue] Tag validation failed for entry ${entry.id}, aborting trigger")
                tagHistoryService.updateQueueEntryStatus(entry.id, QueueEntryStatus.TAG_INVALID)
                return ApiResult.Error(
                    ErrorType.VALIDATION_ERROR,
                    "One or more Docker tags no longer exist in the registry"
                )
            }
        }

        val variables = entry.variables.toMutableMap()
        variables[buildVariableName] = entry.dockerTagsPayload
        log.debug("[Automation:Queue] Using build variable '$buildVariableName' for trigger")

        val result = bambooClient.triggerBuild(entry.suitePlanKey, variables)
        return when (result) {
            is ApiResult.Success -> {
                val buildKey = result.data.buildResultKey
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
                ApiResult.Success(buildKey)
            }
            is ApiResult.Error -> {
                log.error("[Automation:Queue] Build trigger failed for entry ${entry.id}: ${result.message}")
                tagHistoryService.updateQueueEntryStatus(
                    entry.id, QueueEntryStatus.FAILED_TO_TRIGGER,
                    errorMessage = result.message
                )
                ApiResult.Error(result.type, result.message, result.cause)
            }
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
}
