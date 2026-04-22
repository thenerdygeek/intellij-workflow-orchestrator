package com.workflow.orchestrator.jira.service

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.TicketTransitioned
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.jira.MissingFieldsError
import com.workflow.orchestrator.core.model.jira.StatusCategory
import com.workflow.orchestrator.core.model.jira.StatusRef
import com.workflow.orchestrator.core.model.jira.TransitionError
import com.workflow.orchestrator.core.model.jira.TransitionInput
import com.workflow.orchestrator.core.model.jira.TransitionMeta
import com.workflow.orchestrator.core.model.jira.TransitionOutcome
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.services.jira.TicketTransitionService
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Implements [TicketTransitionService] (T15 + T16 + T17):
 *
 * - T15: [getAvailableTransitions] with a 60-second TTL cache per ticket key.
 * - T16: [prepareTransition] — validates the requested transition exists; [executeTransition]
 *   performs required-field preflight, POSTs the transition, invalidates the cache,
 *   and emits [TicketTransitioned] on the bus.
 * - T17: [tryAutoTransition] — fires without prompting when no screen and no required fields;
 *   otherwise returns a [TransitionError.RequiresInteraction] payload.
 *
 * Cache invalidation is event-driven: a [TicketTransitioned] event on the shared bus
 * (e.g. emitted by this service itself or by a post-commit hook) removes the cached
 * entry so the next call fetches fresh transitions from the API.
 */
class TicketTransitionServiceImpl(
    private val api: JiraApiClient,
    private val eventBus: EventBus,
    /** Injectable clock for deterministic tests. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Scope used for the EventBus subscriber coroutine.
     * Callers may supply a project-scoped [CoroutineScope] so that the subscriber
     * is cancelled when the project closes.  When null a private scope is created.
     */
    parentScope: CoroutineScope? = null
) : TicketTransitionService {

    private val log = Logger.getInstance(TicketTransitionServiceImpl::class.java)

    // ── Cache ────────────────────────────────────────────────────────────────

    private data class CacheEntry(val value: List<TransitionMeta>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val ttlMs = 60_000L

    // ── EventBus subscriber scope ────────────────────────────────────────────

    private val scope = parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            eventBus.events.collect { event ->
                if (event is TicketTransitioned) {
                    log.debug("[TicketTransition] Cache invalidated for ${event.key} (TicketTransitioned event)")
                    cache.remove(event.key)
                }
            }
        }
    }

    // ── Interface ────────────────────────────────────────────────────────────

    /**
     * Returns all Jira transitions available from the ticket's current status.
     * Results are cached for [ttlMs] (60 s) per ticket key.  The cache is
     * invalidated when a [TicketTransitioned] event is received on the bus.
     */
    override suspend fun getAvailableTransitions(ticketKey: String): ToolResult<List<TransitionMeta>> {
        val now = clock()
        cache[ticketKey]?.takeIf { it.expiresAt > now }?.let { entry ->
            log.debug("[TicketTransition] Cache hit for $ticketKey")
            return ToolResult.success(
                data = entry.value,
                summary = "Transitions for $ticketKey (cached): ${entry.value.joinToString(", ") { "${it.name} (id=${it.id})" }}"
            )
        }

        return when (val result = api.getTransitions(ticketKey)) {
            is ApiResult.Success -> {
                val transitions = result.data
                cache[ticketKey] = CacheEntry(transitions, now + ttlMs)
                log.debug("[TicketTransition] Fetched ${transitions.size} transitions for $ticketKey")
                val listing = if (transitions.isEmpty()) "none"
                              else transitions.joinToString(", ") { "${it.name} (id=${it.id})" }
                ToolResult.success(
                    data = transitions,
                    summary = "Transitions for $ticketKey: $listing"
                )
            }
            is ApiResult.Error -> {
                log.warn("[TicketTransition] Failed to fetch transitions for $ticketKey: ${result.message}")
                ToolResult(
                    data = emptyList(),
                    summary = "Error fetching transitions for $ticketKey: ${result.message}",
                    isError = true,
                    hint = "Use getTicket first to verify the ticket exists and Jira is reachable."
                )
            }
        }
    }

    /**
     * Verifies that [transitionId] appears in the list of transitions currently
     * available for [ticketKey].  Returns the matching [TransitionMeta] so that
     * callers can inspect required fields before calling [executeTransition].
     *
     * Returns an error if [getAvailableTransitions] fails or if [transitionId] is
     * not in the list (i.e. the ticket is in a state that does not allow it).
     */
    override suspend fun prepareTransition(
        ticketKey: String,
        transitionId: String
    ): ToolResult<TransitionMeta> {
        val all = getAvailableTransitions(ticketKey)
        if (all.isError) {
            return ToolResult(
                data = sentinel(),
                summary = all.summary,
                isError = true,
                hint = all.hint,
                payload = all.payload
            )
        }
        val meta = all.data.firstOrNull { it.id == transitionId }
        return if (meta != null) {
            ToolResult.success(
                data = meta,
                summary = "Transition ${meta.name} (id=${meta.id}) is available for $ticketKey"
            )
        } else {
            ToolResult(
                data = sentinel(),
                summary = "Transition $transitionId not available for $ticketKey from its current status. " +
                    "Available: ${all.data.joinToString(", ") { "${it.name}(${it.id})" }.ifEmpty { "none" }}",
                isError = true,
                hint = "Call getAvailableTransitions to see which transitions are currently valid."
            )
        }
    }

    /**
     * Executes a Jira transition with optional field values and comment.
     *
     * Pre-flight checks (in order):
     * 1. Validates the transition is available ([prepareTransition]).
     * 2. Checks that all required screen fields in [TransitionMeta.fields] are provided
     *    in [TransitionInput.fieldValues]; returns [TransitionError.MissingFields] if not.
     * 3. POSTs [transitionIssue] to the Jira API.
     * 4. On success: removes the cache entry and emits [TicketTransitioned].
     */
    override suspend fun executeTransition(
        ticketKey: String,
        input: TransitionInput
    ): ToolResult<TransitionOutcome> {
        // Step 1: validate transition exists
        val prep = prepareTransition(ticketKey, input.transitionId)
        if (prep.isError) {
            return ToolResult(
                data = outcomeError(ticketKey),
                summary = prep.summary,
                isError = true,
                hint = prep.hint,
                payload = prep.payload
            )
        }
        val meta = prep.data

        // Step 2: required-field preflight
        val missing = meta.fields.filter { it.required && input.fieldValues[it.id] == null }
        if (missing.isNotEmpty()) {
            val err = MissingFieldsError(
                transitionId = meta.id,
                transitionName = meta.name,
                fields = missing,
                guidance = "Call ask_followup_question for each missing field, then retry with fields={...}."
            )
            log.warn("[TicketTransition] Missing required fields for $ticketKey transition ${meta.id}: ${missing.map { it.id }}")
            return ToolResult(
                data = outcomeError(ticketKey),
                summary = "missing_required_fields: transition '${meta.name}' requires: ${missing.joinToString(", ") { it.name }}",
                isError = true,
                hint = err.guidance,
                payload = TransitionError.MissingFields(err)
            )
        }

        // Step 3: best-effort fetch of current status for the event payload
        val fromStatus = fetchCurrentStatus(ticketKey)

        // Step 4: POST
        return when (val result = api.transitionIssue(ticketKey, input)) {
            is ApiResult.Success -> {
                cache.remove(ticketKey)
                eventBus.emit(TicketTransitioned(ticketKey, fromStatus, meta.toStatus, meta.id))
                log.info("[TicketTransition] Transitioned $ticketKey via ${meta.name} (${meta.id})")
                val outcome = TransitionOutcome(
                    key = ticketKey,
                    fromStatus = fromStatus,
                    toStatus = meta.toStatus,
                    transitionId = meta.id,
                    appliedFields = input.fieldValues
                )
                ToolResult.success(
                    data = outcome,
                    summary = "Transitioned $ticketKey: '${fromStatus.name}' → '${meta.toStatus.name}' via '${meta.name}'."
                )
            }
            is ApiResult.Error -> {
                log.warn("[TicketTransition] Transition failed for $ticketKey (${meta.id}): ${result.message}")
                ToolResult(
                    data = outcomeError(ticketKey),
                    summary = "Transition '${meta.name}' failed for $ticketKey: ${result.message}",
                    isError = true,
                    hint = "Check that the transition is still valid and required fields are satisfied.",
                    payload = TransitionError.InvalidTransition(result.message)
                )
            }
        }
    }

    /**
     * Fires a transition without user interaction **only** if the transition
     * has no screen and no required fields.
     *
     * If [meta.hasScreen] is true or the transition has at least one required field,
     * returns a [TransitionError.RequiresInteraction] payload so callers can fall back
     * to the interactive [executeTransition] path with collected field values.
     */
    override suspend fun tryAutoTransition(
        ticketKey: String,
        transitionId: String,
        comment: String?
    ): ToolResult<TransitionOutcome> {
        val prep = prepareTransition(ticketKey, transitionId)
        if (prep.isError) {
            return ToolResult(
                data = outcomeError(ticketKey),
                summary = prep.summary,
                isError = true,
                hint = prep.hint,
                payload = prep.payload
            )
        }
        val meta = prep.data

        val hasRequiredFields = meta.fields.any { it.required }
        if (meta.hasScreen || hasRequiredFields) {
            log.debug("[TicketTransition] Auto-transition blocked for $ticketKey (${meta.id}): hasScreen=${meta.hasScreen}, hasRequiredFields=$hasRequiredFields")
            return ToolResult(
                data = outcomeError(ticketKey),
                summary = "Transition '${meta.name}' for $ticketKey requires interaction (screen or required fields present).",
                isError = true,
                hint = "Use executeTransition with the required field values.",
                payload = TransitionError.RequiresInteraction(meta)
            )
        }

        return executeTransition(ticketKey, TransitionInput(transitionId, emptyMap(), comment))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Best-effort fetch of the current ticket status for the [TicketTransitioned] event.
     * Falls back to a sentinel [StatusRef] when the API call fails, so that the transition
     * itself is not blocked by a secondary read failure.
     */
    private suspend fun fetchCurrentStatus(ticketKey: String): StatusRef {
        return try {
            when (val r = api.getIssue(ticketKey)) {
                is ApiResult.Success -> {
                    val s = r.data.fields.status
                    val category = when (s.statusCategory?.key?.lowercase()) {
                        "new" -> StatusCategory.TO_DO
                        "indeterminate" -> StatusCategory.IN_PROGRESS
                        "done" -> StatusCategory.DONE
                        else -> StatusCategory.UNKNOWN
                    }
                    StatusRef(id = s.id ?: "?", name = s.name, category = category)
                }
                is ApiResult.Error -> {
                    log.debug("[TicketTransition] Could not fetch current status for $ticketKey: ${r.message} — using sentinel")
                    sentinelStatus()
                }
            }
        } catch (t: Throwable) {
            log.debug("[TicketTransition] Exception fetching current status for $ticketKey — using sentinel", t)
            sentinelStatus()
        }
    }

    /** Sentinel returned when the current status cannot be fetched. */
    private fun sentinelStatus() = StatusRef("?", "?", StatusCategory.UNKNOWN)

    /**
     * Minimal sentinel [TransitionMeta] used as the [ToolResult.data] placeholder
     * in error cases where a real [TransitionMeta] is unavailable.
     * Callers that check [ToolResult.isError] will never consume this value.
     */
    private fun sentinel(): TransitionMeta = TransitionMeta(
        id = "",
        name = "",
        toStatus = sentinelStatus(),
        hasScreen = false,
        fields = emptyList()
    )

    /** Sentinel [TransitionOutcome] used as placeholder in error results. */
    private fun outcomeError(ticketKey: String): TransitionOutcome = TransitionOutcome(
        key = ticketKey,
        fromStatus = sentinelStatus(),
        toStatus = sentinelStatus(),
        transitionId = "",
        appliedFields = emptyMap()
    )
}
