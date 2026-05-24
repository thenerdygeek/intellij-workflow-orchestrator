package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.ui.DelegationPicker
import com.workflow.orchestrator.agent.delegation.ui.PickerEntry
import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationFraming
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project service. Owns the active outbound delegation channels (max 5)
 * for this IDE.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §3.3, §4, §6.6.
 */
@Service(Service.Level.PROJECT)
class DelegationOutboundService(
    private val project: Project,
    private val cs: CoroutineScope,
) {
    private val activeChannels = ConcurrentHashMap<String, SocketChannel>()

    /** Maps outbound delegation handle → the local Agent-A session that holds it. */
    private val handleToSessionId = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val idleTimers = java.util.concurrent.ConcurrentHashMap<String, IdleTimer>()

    /**
     * F1 fix: per-question text cache so [DelegationTool]'s `answer` action can look up the
     * question text when the auto-approve setting is off (for the confirm dialog).
     *
     * Key: questionId → (handleId, questionText). Populated when a Question message
     * arrives; cleared when the answer is sent or the channel closes.
     */
    private val pendingQuestionTexts = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    // Plan 3 fetch_transcript correlation.
    private val pendingFetches =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<DelegationMessage.FetchTranscriptReply>>()

    /**
     * F5: Serializes concurrent [send] calls so the 5-channel cap is atomic.
     *
     * Without this mutex two concurrent calls could both read [openChannelCount] == 4
     * and both proceed past the limit check, inserting two channels and breaching
     * MAX_CHANNELS. The mutex turns the check-then-insert into an atomic critical section.
     *
     * The picker dialog (an EDT suspension) runs inside the lock — this is acceptable
     * because we expect at most a few concurrent delegation_send calls per session, and
     * serializing them avoids the race entirely. The read-loop coroutine ([cs.launch])
     * is launched outside the lock so result delivery is not gated on the next send.
     */
    private val sendMutex = Mutex()

    /**
     * Epoch millis of the most recent IPC message received on each open channel.
     * Updated in the cs.launch reader loop on every framed message (any type,
     * including [DelegationMessage.Heartbeat]). Read by [IdleTimer] to decide
     * whether to fire [DelegationException.IdleTimedOut].
     *
     * Plan 3 spec §4.2.
     */
    private val lastSeenAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    val openChannelCount: Int get() = activeChannels.size

    /**
     * First-contact delegation: opens the picker, awaits user pick, opens IPC
     * channel, sends Connect, awaits AcceptResult. Returns the handle on Accept.
     *
     * Spawns a coroutine to read the Result from the channel; when Result arrives,
     * [onResult] is invoked. The channel is closed in the finally block.
     *
     * Serialized by [sendMutex] so the 5-channel cap check-then-insert is atomic
     * (F5 fix — spec §6.6).
     *
     * @throws DelegationException on the various failure modes.
     */
    suspend fun send(
        request: String,
        suggestedRepo: String?,
        delegatorSessionId: String,
        onResult: suspend (DelegationHandle, DelegationMessage.Result) -> Unit,
    ): DelegationHandle = sendMutex.withLock {
        if (openChannelCount >= MAX_CHANNELS) {
            throw DelegationException.LimitReached
        }
        val picked = pickTarget(suggestedRepo) ?: throw DelegationException.UserCanceledPicker
        val socketPath = DelegationPaths.socketFor(picked.path)
        val connect = DelegationMessage.Connect(
            delegatorIde = ideIdentifier(),
            delegatorRepo = project.name,
            delegatorSessionId = delegatorSessionId,
            request = request,
        )
        val pair = DelegationClient.connectAndAwaitAccept(socketPath, connect)
            ?: throw DelegationException.TargetNotReachable
        val (channel, ack) = pair
        if (!ack.accepted) {
            try { channel.close() } catch (_: Exception) {}
            throw DelegationException.Rejected(ack.reason)
        }
        // Plan 4: capture the remote session ID so sendContinuation can target it.
        val bSessionId = ack.bSessionId ?: run {
            try { channel.close() } catch (_: Exception) {}
            throw DelegationException.TargetNotReachable
        }
        val handle = DelegationHandle(
            id = UUID.randomUUID().toString(),
            targetProjectPath = picked.path.toString(),
            targetRepoName = picked.displayName,
        )
        activeChannels[handle.id] = channel
        lastSeenAt[handle.id] = System.currentTimeMillis()
        handleToSessionId[handle.id] = delegatorSessionId
        handleToRepoName[handle.id] = handle.targetRepoName
        // Plan 4: persist handle metadata needed for sendContinuation.
        handleToBSessionId[handle.id] = bSessionId
        handleToLastSeenState[handle.id] = "RUNNING"
        handleToTargetPath[handle.id] = handle.targetProjectPath
        // Plan 4: persist the new handle so it survives IDE-A restart.
        persistHandlesForSession(delegatorSessionId)

        // Plan 3 idle timer — the timeout-millis provider re-reads PluginSettings on every
        // tick so changes take effect for already-open channels (spec §3.3). A return of
        // <= 0 disables the check for that tick. We always start the timer; it's an idle
        // loop until the setting is non-zero.
        run {
            val settingsSvc = project.getService(
                com.workflow.orchestrator.core.settings.PluginSettings::class.java
            )
            val timer = IdleTimer(
                handleId = handle.id,
                scope = cs,
                checkIntervalMillis = IDLE_CHECK_INTERVAL_MILLIS,
                timeoutMillisProvider = {
                    settingsSvc.state.delegationIdleTimeoutMinutes.toLong() * 60_000L
                },
                clock = SystemClock,
                lastSeenAtProvider = { lastSeenMillis(handle.id) },
                onTimeout = {
                    val timedOut = DelegationException.IdleTimedOut(
                        handle = handle,
                        lastSeenAt = lastSeenMillis(handle.id) ?: 0L,
                    )
                    LOG.info("IdleTimer fired: ${timedOut.message} — closing channel ${handle.id}")
                    val sid = handleToSessionId[handle.id]
                    if (sid != null) {
                        project.getService(
                            com.workflow.orchestrator.agent.AgentService::class.java
                        ).enqueueNudgeForSession(
                            sid,
                            "Delegated session ${handle.targetRepoName} (handle ${handle.id.take(8)}) " +
                                "timed out due to inactivity. The channel has been closed."
                        )
                    }
                    close(handle.id)
                },
            )
            idleTimers[handle.id] = timer
            timer.start()
        }
        // end Plan 3 idle timer block

        // Note: the result-reader coroutine is launched OUTSIDE the sendMutex.withLock
        // body so that result delivery is not gated on the next send call. The channel
        // insertion into activeChannels above has already happened under the lock.
        cs.launch(Dispatchers.IO) {
            runOutboundReaderLoop(handle, channel, onResult)
        }
        handle
    }

    /**
     * Shared outbound reader-loop body for both [send] and [attemptResume].
     *
     * Reads frames from [channel] until:
     * - A [DelegationMessage.Result] arrives — calls [onResult] and returns.
     * - The channel throws (EOF / socket error) — synthesises a FAILED Result and returns.
     *
     * Dispatches:
     * - [DelegationMessage.Question] → [handleIncomingQuestion]
     * - [DelegationMessage.AnswerCanceled] → [rescindLocalQuestion]
     * - [DelegationMessage.Heartbeat] → no-op (lastSeenAt already updated before when-switch)
     * - [DelegationMessage.FetchTranscriptReply] → completes the matching [pendingFetches] deferred
     * - [DelegationMessage.Result] → calls [onResult] + returns
     * - else → [unknownCount]++ / threshold drop per Plan 3 F8
     *
     * H1 fix: previously only [send] had a reader loop; [attemptResume] stored the channel in
     * [activeChannels] but never spawned a reader, so follow-up Heartbeat / Question / Result
     * frames on resumed channels were never processed. Both paths now call this method.
     *
     * Plan 4 review fix H1.
     */
    private suspend fun runOutboundReaderLoop(
        handle: DelegationHandle,
        channel: java.nio.channels.SocketChannel,
        onResult: suspend (DelegationHandle, DelegationMessage.Result) -> Unit,
    ) {
        var unknownCount = 0
        try {
            while (true) {
                val msg = withContext(Dispatchers.IO) {
                    DelegationFraming.readFramed(channel, json)
                }
                lastSeenAt[handle.id] = System.currentTimeMillis()
                when (msg) {
                    is DelegationMessage.Question -> handleIncomingQuestion(handle, msg)
                    is DelegationMessage.AnswerCanceled -> rescindLocalQuestion(handle, msg.questionId, msg.reason)
                    is DelegationMessage.Heartbeat -> {
                        // Liveness signal — already accounted for by the lastSeenAt update above.
                        // No further action needed at this point in the loop.
                    }
                    is DelegationMessage.FetchTranscriptReply -> {
                        pendingFetches.remove(msg.requestId)?.complete(msg)
                            ?: LOG.debug(
                                "FetchTranscriptReply for unknown requestId ${msg.requestId} " +
                                    "(reply arrived after the 30s timeout — benign, deferred already cancelled)"
                            )
                    }
                    is DelegationMessage.Result -> {
                        onResult(handle, msg)
                        return
                    }
                    else -> {
                        unknownCount++
                        LOG.warn("Unexpected message on outbound channel: ${msg::class.simpleName} (count=$unknownCount)")
                        if (unknownCount >= MAX_UNKNOWN_MESSAGES_BEFORE_DROP) {
                            LOG.warn(
                                "Outbound reader for ${handle.id} dropping channel after " +
                                    "$unknownCount unknown messages — likely protocol drift"
                            )
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Result read failed for ${handle.id}", e)
            onResult(
                handle,
                DelegationMessage.Result(
                    status = DelegationMessage.ResultStatus.FAILED,
                    reason = "ipc_read_failed: ${e.message}",
                )
            )
        } finally {
            close(handle.id)
        }
    }

    /**
     * Closes the channel for [handleId]. Idempotent — no-op on unknown handle.
     * Returns true if a channel was found and closed.
     *
     * Plan 4: captures the owning sessionId before removal so the on-disk
     * handle snapshot can be updated after cleanup (reflecting that this handle
     * is no longer active). If there are no remaining handles for the session,
     * [PersistentHandleStore.save] will write an empty list.
     */
    fun close(handleId: String): Boolean {
        val sessionId: String? = handleToSessionId[handleId]
        val wasFound: Boolean = synchronized(this) {
            idleTimers.remove(handleId)?.stop()
            handleToSessionId.remove(handleId)
            handleToRepoName.remove(handleId)
            // Plan 4: clear continuation metadata.
            handleToBSessionId.remove(handleId)
            handleToLastSeenState.remove(handleId)
            handleToTargetPath.remove(handleId)
            // F1 fix: also clear any pending question texts for this handle.
            pendingQuestionTexts.entries.removeIf { it.value.first == handleId }
            lastSeenAt.remove(handleId)
            val ch = activeChannels.remove(handleId)
            if (ch != null) {
                try { ch.close() } catch (_: Exception) {}
                true
            } else {
                false
            }
        }
        // Plan 4: persist updated (possibly empty) handle list after removal.
        if (sessionId != null) persistHandlesForSession(sessionId)
        return wasFound
    }

    /** Close every active channel. Called from project disposal. */
    fun closeAll() {
        activeChannels.keys.toList().forEach(::close)
    }

    /**
     * Cancel-cascade entry point. Closes every active channel whose
     * outbound handle belongs to [delegatorSessionId]. Returns the list of
     * closed handle IDs (mostly for logging / tests).
     *
     * Called from [com.workflow.orchestrator.agent.AgentService.cancelCurrentTask]
     * when the human cancels Agent-A's session. Each IDE-B receiver will detect
     * the socket close and surface a CANCELED state to its session UI.
     *
     * Plan 3 spec §5.3.
     */
    fun cancelAllForSession(delegatorSessionId: String, reason: String): List<String> {
        val toClose = handleToSessionId.entries
            .filter { it.value == delegatorSessionId }
            .map { it.key }
        for (h in toClose) {
            LOG.info("cancelAllForSession: closing handle $h (session=$delegatorSessionId, reason=$reason)")
            close(h)
        }
        return toClose
    }

    /**
     * Sends an Answer for a previously-received Question over the channel for [handleId].
     * Returns false if the handle is unknown or the channel is closed.
     */
    suspend fun sendAnswer(handleId: String, questionId: String, answer: String): Boolean {
        val channel = activeChannels[handleId] ?: return false
        return try {
            withContext(Dispatchers.IO) {
                DelegationFraming.writeFramed(
                    channel,
                    DelegationMessage.Answer(questionId = questionId, text = answer),
                    json,
                )
            }
            // F1 fix: clear the pending question text now that the answer is sent.
            pendingQuestionTexts.remove(questionId)
            true
        } catch (e: Exception) {
            LOG.warn("sendAnswer failed for $handleId / $questionId", e)
            false
        }
    }

    /**
     * Continuation flow for `delegation_send(handle = X)`. Skips picker + Accept.
     * Sends a [DelegationMessage.UserTurn] over the existing channel for [handleId].
     *
     * Dead handles (persisted but not yet resumed after IDE-A restart) trigger
     * [attemptResume] automatically. On successful resume:
     * - H1 fix: a new reader loop is spawned on the resumed channel so subsequent
     *   Question / Heartbeat / Result frames are processed (spec §3.3).
     * - H4 fix: a coalesced nudge is enqueued to Agent-A before the UserTurn frame is
     *   sent, per spec §3.3: "Channel for {bRepoName} re-attached. Last-known state
     *   {lastSeenState}; current state {currentState}. Resuming."
     *
     * On failure (session closed, not found, or probe refused) a
     * [DelegationException.Expired] is thrown with a descriptive reason.
     *
     * Plan 4 spec §5.2, §5.3.
     *
     * @throws DelegationException.Expired if the handle is unknown, the channel is
     * dead and resume failed, or ownership fails the delegator-session check.
     * @throws DelegationException.WriteFailed if the [DelegationMessage.UserTurn]
     * frame cannot be written to the channel.
     */
    suspend fun sendContinuation(
        handleId: String,
        request: String,
        delegatorSessionId: String,
    ): DelegationHandle {
        val sessionId = handleToSessionId[handleId]
            ?: throw DelegationException.Expired("handle_not_found")
        if (sessionId != delegatorSessionId) {
            // Defensive: a session can only continue its own handles.
            throw DelegationException.Expired("handle_owned_by_other_session")
        }

        // Capture lastSeenState BEFORE attemptResume updates it (H4: needed for the nudge text).
        val lastSeenStateBeforeResume = handleToLastSeenState[handleId] ?: "unknown"

        val channel = activeChannels[handleId] ?: run {
            // Dead handle (persisted but not yet resumed). Attempt the resume protocol.
            val outcome = attemptResume(handleId)
            when (outcome) {
                is ResumeOutcome.Resumed -> {
                    val repoName = handleToRepoName[handleId] ?: handleId.take(8)

                    // H4 fix: enqueue the spec-mandated re-attach nudge to Agent-A BEFORE
                    // writing the UserTurn frame and before the null-check that might throw.
                    // Spec §3.3: "Emit a single coalesced nudge to Agent-A: 'Channel for
                    // {bRepoName} re-attached. Last-known state {lastSeenState}; current
                    // state {currentState}. Resuming.'"
                    val reattachNudge =
                        "Channel for $repoName re-attached. " +
                        "Last-known state $lastSeenStateBeforeResume; " +
                        "current state ${outcome.currentState}. Resuming."
                    project.getService(
                        com.workflow.orchestrator.agent.AgentService::class.java
                    ).enqueueNudgeForSession(delegatorSessionId, reattachNudge)

                    val resumedChannel = activeChannels[handleId]
                        ?: throw DelegationException.Expired("resume_inconsistent")

                    // H1 fix: spawn a reader loop on the resumed channel so subsequent
                    // Question / Heartbeat / FetchTranscriptReply / Result frames are
                    // dispatched. Without this, the resumed channel stored in activeChannels
                    // had no reader and all frames were silently dropped. Spec §3.3.
                    val handle = DelegationHandle(
                        id = handleId,
                        targetProjectPath = handleToTargetPath[handleId] ?: "",
                        targetRepoName = repoName,
                    )
                    cs.launch(Dispatchers.IO) {
                        runOutboundReaderLoop(handle, resumedChannel) { h, result ->
                            // Route the terminal result back to Agent-A as a nudge.
                            val nudge = buildResumedResultNudge(h.targetRepoName, h.id, result)
                            project.getService(
                                com.workflow.orchestrator.agent.AgentService::class.java
                            ).enqueueNudgeForSession(delegatorSessionId, nudge)
                        }
                    }

                    resumedChannel
                }
                is ResumeOutcome.Closed -> throw DelegationException.Expired(
                    "session_closed: ${outcome.closeReason}${outcome.summary?.let { " — $it" } ?: ""}"
                )
                ResumeOutcome.NotFound -> throw DelegationException.Expired("session_not_found")
                is ResumeOutcome.ProbeFailed -> throw DelegationException.Expired(outcome.reason)
            }
        }

        val bSessionId = handleToBSessionId[handleId]
            ?: throw DelegationException.Expired("missing_bSessionId")
        val repoName = handleToRepoName[handleId]
            ?: throw DelegationException.Expired("missing_repo_name")

        try {
            withContext(Dispatchers.IO) {
                DelegationFraming.writeFramed(
                    channel,
                    DelegationMessage.UserTurn(sessionId = bSessionId, text = request),
                    json,
                )
            }
        } catch (e: Exception) {
            LOG.warn("sendContinuation: UserTurn write failed for $handleId", e)
            throw DelegationException.WriteFailed("UserTurn write failed: ${e.message}")
        }

        return DelegationHandle(
            id = handleId,
            targetProjectPath = handleToTargetPath[handleId] ?: "",
            targetRepoName = repoName,
            lastSeenState = handleToLastSeenState[handleId] ?: "unknown",
        )
    }

    /**
     * Builds the nudge text for Agent-A when a resumed delegated session sends its
     * terminal Result. Mirrors [DelegationTool]'s `buildNudgeText` (send action) but is called from
     * the reader loop spawned by [sendContinuation] on a resumed channel.
     */
    private fun buildResumedResultNudge(
        repoName: String,
        handleId: String,
        result: DelegationMessage.Result,
    ): String = buildString {
        val shortId = handleId.take(8)
        appendLine("[DELEGATION RESULT (resumed) — $repoName ($shortId)]")
        appendLine("Status: ${result.status}")
        if (result.summary.isNotBlank()) appendLine("Summary: ${result.summary}")
        if (result.filesChanged.isNotEmpty()) {
            appendLine("Files changed (${result.filesChanged.size}): ${result.filesChanged.joinToString(", ")}")
        }
        if (result.reason != null) appendLine("Reason: ${result.reason}")
        appendLine("Duration: ${result.durationSeconds}s")
    }.trimEnd()

    /**
     * Test-injectable probe. Production code uses the live socket I/O path.
     * Tests override this to drive deterministic reply outcomes.
     *
     * The lambda receives the socketPath and the ChannelResume message to send, and
     * returns the reply DelegationMessage (or null to simulate no PONG / refused).
     *
     * Plan 4 spec §3.3.
     */
    internal var testResumeProbe: (suspend (java.nio.file.Path, DelegationMessage.ChannelResume) -> DelegationMessage?)? = null

    /**
     * Rehydrate persisted handles for [delegatorSessionId] into the dead-handle
     * registries. No live SocketChannels are opened — that happens on first
     * reference via [attemptResume].
     *
     * Called from [AgentService.resumeSession] when a session is resumed from disk.
     *
     * Plan 4 spec §3.3.
     */
    fun loadPersistedHandles(sessionDir: java.nio.file.Path, delegatorSessionId: String) {
        val store = PersistentHandleStore(sessionDir = sessionDir)
        for (entry in store.load()) {
            handleToSessionId[entry.handleId] = delegatorSessionId
            handleToBSessionId[entry.handleId] = entry.bSessionId
            handleToLastSeenState[entry.handleId] = entry.lastSeenState
            handleToRepoName[entry.handleId] = entry.targetRepoName
            handleToTargetPath[entry.handleId] = entry.targetProjectPath
            // No activeChannels entry — that's what marks the handle as DEAD.
        }
    }

    /**
     * Probe IDE-B, send `ChannelResume`, dispatch on the reply.
     *
     * When [testResumeProbe] is set (test mode), the lambda is invoked instead of
     * opening a real socket. In production, PING is sent first to confirm IDE-B is
     * up, then a fresh SocketChannel is opened to send [DelegationMessage.ChannelResume]
     * and read one reply. On [DelegationMessage.ChannelResumed] the channel is retained
     * in [activeChannels] so subsequent [sendContinuation] calls work.
     *
     * Plan 4 spec §3.3.
     */
    suspend fun attemptResume(handleId: String): ResumeOutcome {
        val bSessionId = handleToBSessionId[handleId]
            ?: return ResumeOutcome.NotFound
        val lastSeenState = handleToLastSeenState[handleId] ?: "unknown"
        val targetPath = handleToTargetPath[handleId]
            ?: return ResumeOutcome.NotFound

        val socketPath = DelegationPaths.socketFor(java.nio.file.Path.of(targetPath))

        val probe = testResumeProbe
        val reply: DelegationMessage? = if (probe != null) {
            probe.invoke(socketPath, DelegationMessage.ChannelResume(bSessionId, lastSeenState))
        } else {
            // Production path: PING first to confirm IDE-B is up, then open a fresh
            // channel and write ChannelResume + read one reply.
            DelegationClient.ping(socketPath)
                ?: return ResumeOutcome.ProbeFailed("ide_b_not_running")
            try {
                withContext(Dispatchers.IO) {
                    val ch = SocketChannel.open(
                        java.net.UnixDomainSocketAddress.of(socketPath)
                    )
                    try {
                        DelegationFraming.writeFramed(
                            ch,
                            DelegationMessage.ChannelResume(bSessionId, lastSeenState),
                            json,
                        )
                        val msg = DelegationFraming.readFramed(ch, json)
                        // On Resumed, re-attach the channel and keep it open.
                        if (msg is DelegationMessage.ChannelResumed) {
                            activeChannels[handleId] = ch
                            lastSeenAt[handleId] = System.currentTimeMillis()
                            handleToLastSeenState[handleId] = msg.currentState
                        } else {
                            try { ch.close() } catch (_: Exception) {}
                        }
                        msg
                    } catch (e: Exception) {
                        try { ch.close() } catch (_: Exception) {}
                        throw e
                    }
                }
            } catch (e: Exception) {
                LOG.warn("attemptResume: probe write/read failed for $handleId", e)
                return ResumeOutcome.ProbeFailed("io_error: ${e.message}")
            }
        }

        return when (reply) {
            is DelegationMessage.ChannelResumed -> ResumeOutcome.Resumed(reply.currentState)
            is DelegationMessage.SessionClosed -> ResumeOutcome.Closed(reply.closeReason, reply.summary)
            is DelegationMessage.SessionNotFound -> ResumeOutcome.NotFound
            else -> ResumeOutcome.ProbeFailed("unexpected_reply: ${reply?.let { it::class.simpleName } ?: "null"}")
        }
    }

    /**
     * Persist outbound handles belonging to [delegatorSessionId] to the session's
     * `delegation-handles.json` file via [PersistentHandleStore].
     *
     * Called from [send] after a successful Accept, and from [close] after cleanup,
     * so the on-disk snapshot reflects the current handle set for this session.
     *
     * Plan 4 spec §3.2.
     */
    private fun persistHandlesForSession(delegatorSessionId: String) {
        try {
            val basePath = project.basePath ?: return
            val sessionDir = java.io.File(
                ProjectIdentifier.agentDir(basePath),
                "sessions/$delegatorSessionId"
            ).toPath()
            val store = PersistentHandleStore(sessionDir = sessionDir)
            val entries = handleToSessionId.entries
                .filter { it.value == delegatorSessionId }
                .mapNotNull { (handleId, _) ->
                    val bSessionId = handleToBSessionId[handleId] ?: return@mapNotNull null
                    val repoName = handleToRepoName[handleId] ?: return@mapNotNull null
                    val targetPath = handleToTargetPath[handleId] ?: return@mapNotNull null
                    PersistentHandleEntry(
                        handleId = handleId,
                        targetProjectPath = targetPath,
                        targetRepoName = repoName,
                        bSessionId = bSessionId,
                        lastSeenState = handleToLastSeenState[handleId] ?: "unknown",
                        createdAt = System.currentTimeMillis(),
                    )
                }
            store.save(entries)
        } catch (e: Exception) {
            LOG.warn("persistHandlesForSession failed for $delegatorSessionId", e)
        }
    }

    /**
     * Returns the text of a pending question sent over the given handle, or null if
     * the question has already been answered or the questionId is unknown.
     * Used by [com.workflow.orchestrator.agent.tools.delegation.DelegationTool]'s `answer` action
     * to populate the confirm dialog when auto-approve is off.
     */
    fun lookupPendingQuestionText(handleId: String, questionId: String): String? =
        pendingQuestionTexts[questionId]?.takeIf { it.first == handleId }?.second

    /**
     * Returns the target repo display name for the given handle, or null if unknown.
     * Used by [com.workflow.orchestrator.agent.tools.delegation.DelegationTool]'s `answer` action
     * to populate the confirm dialog title.
     */
    fun targetRepoName(handleId: String): String? {
        // handleToRepoName is populated in send() when the handle is created.
        return handleToRepoName[handleId]
    }

    private val handleToRepoName = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Maps outbound delegation handle → the remote Agent-B session ID returned in
     * [DelegationMessage.AcceptResult.bSessionId]. Populated in [send] after a
     * successful Accept; used by [sendContinuation] to target the correct session
     * when writing a [DelegationMessage.UserTurn].
     *
     * Plan 4 spec §5.1.
     */
    private val handleToBSessionId = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Most-recently-observed remote state for each open handle ("RUNNING",
     * "AWAITING_ANSWER", etc.). Updated lazily; used only as a diagnostic hint
     * in [DelegationHandle.lastSeenState] returned by [sendContinuation].
     *
     * Plan 4 spec §5.1.
     */
    private val handleToLastSeenState = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Maps outbound delegation handle → the target project path.  Needed by
     * [sendContinuation] to rebuild a valid [DelegationHandle] without opening
     * the picker again.
     *
     * Plan 4 spec §5.1.
     */
    private val handleToTargetPath = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun handleIncomingQuestion(
        handle: DelegationHandle,
        question: DelegationMessage.Question,
    ) {
        // F1 fix: cache the question text so the delegation answer action can show it in the confirm dialog.
        pendingQuestionTexts[question.questionId] = handle.id to question.text

        val sessionId = handleToSessionId[handle.id] ?: run {
            LOG.warn("handleIncomingQuestion: no delegator session for ${handle.id}")
            return
        }
        val agentService = project.getService(
            com.workflow.orchestrator.agent.AgentService::class.java
        )
        // F6 fix: reorder guidance so "ask the user first if uncertain" is step 1
        // (matches spec §6.3) and "call delegation(action=answer) directly" is step 2.
        // The previous text had them reversed, causing the LLM to default to
        // delegation answer immediately rather than checking with the user.
        val nudge = buildString {
            append("Delegated session ${handle.targetRepoName} (handle ${handle.id.take(8)}) ")
            append("raised a clarifying question:\n\n")
            append(question.text)
            if (question.options.isNotEmpty()) {
                append("\n\nSuggested options: ${question.options.joinToString(", ")}")
            }
            append("\n\nQuestion ID: ${question.questionId}\n\n")
            append("How to respond:\n")
            append("1. If you cannot answer confidently from your own context, ask the user " +
                "first (via ask_followup_question), then use delegation(action=\"answer\") with the answer.\n")
            append("2. If you have enough context to answer correctly, call delegation(action=\"answer\") " +
                "directly with handle=${handle.id}, question_id=${question.questionId}, " +
                "and your answer text.")
        }
        agentService.enqueueNudgeForSession(sessionId, nudge)
    }

    private fun rescindLocalQuestion(handle: DelegationHandle, questionId: String, reason: String) {
        // F1 fix: remove the cached question text since it was answered locally (IDE-B side).
        pendingQuestionTexts.remove(questionId)
        val sessionId = handleToSessionId[handle.id] ?: return
        val agentService = project.getService(
            com.workflow.orchestrator.agent.AgentService::class.java
        )
        agentService.enqueueNudgeForSession(
            sessionId,
            "Delegated session question $questionId was answered locally by the " +
                "human in the receiving IDE ($reason). No action needed.",
        )
    }

    private suspend fun pickTarget(suggestedRepo: String?): PickerEntry? =
        withContext(Dispatchers.EDT) {
            val dlg = DelegationPicker(project, suggestedRepo)
            if (dlg.showAndGet()) dlg.selectedEntry else null
        }

    private fun ideIdentifier(): String {
        val pid = ProcessHandle.current().pid()
        return "ide-$pid"
    }

    /** Read accessor used by [IdleTimer] to evaluate stalls. Null if the handle is unknown. */
    fun lastSeenMillis(handleId: String): Long? = lastSeenAt[handleId]

    /** Returns true if a channel is currently open for [handleId]. */
    fun hasOpenChannel(handleId: String): Boolean = activeChannels.containsKey(handleId)

    /**
     * Send a [DelegationMessage.FetchTranscript] over [handleId] and suspend until
     * the matching [DelegationMessage.FetchTranscriptReply] arrives or 30 s elapses.
     * Returns a [FetchTranscriptResult].
     *
     * Plan 3 spec §5.7.
     */
    suspend fun fetchTranscript(handleId: String): FetchTranscriptResult {
        val channel = activeChannels[handleId]
            ?: return FetchTranscriptResult.NotFound("handle_not_found")
        val sessionId = handleToSessionId[handleId]
            ?: return FetchTranscriptResult.NotFound("handle_not_in_session_map")
        val requestId = java.util.UUID.randomUUID().toString()
        val deferred = kotlinx.coroutines.CompletableDeferred<DelegationMessage.FetchTranscriptReply>()
        pendingFetches[requestId] = deferred
        try {
            withContext(Dispatchers.IO) {
                DelegationFraming.writeFramed(
                    channel,
                    DelegationMessage.FetchTranscript(sessionId = sessionId, requestId = requestId),
                    json,
                )
            }
        } catch (e: Exception) {
            pendingFetches.remove(requestId)
            return FetchTranscriptResult.NotFound("write_failed: ${e.message}")
        }
        val reply = try {
            kotlinx.coroutines.withTimeoutOrNull(30_000L) { deferred.await() }
        } catch (e: Exception) {
            null
        } finally {
            pendingFetches.remove(requestId)
        }
        return when {
            reply == null -> FetchTranscriptResult.NotFound("timeout")
            reply.status == "ok" && reply.transcriptPath != null ->
                FetchTranscriptResult.Ok(reply.transcriptPath!!)
            else -> FetchTranscriptResult.NotFound(reply?.error ?: reply?.status ?: "unknown")
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationOutboundService::class.java)
        const val MAX_CHANNELS = 5
        /** Idle timer ticks at this cadence to keep CPU cost low while still being responsive. */
        const val IDLE_CHECK_INTERVAL_MILLIS = 30_000L
        /** Plan 2 F8: drop the channel after this many consecutive unknown messages. */
        const val MAX_UNKNOWN_MESSAGES_BEFORE_DROP = 16
    }
}

/**
 * Result type for [DelegationOutboundService.fetchTranscript].
 *
 * Plan 3 spec §5.7.
 */
sealed class FetchTranscriptResult {
    data class Ok(val transcriptPath: String) : FetchTranscriptResult()
    data class NotFound(val reason: String) : FetchTranscriptResult()
}
