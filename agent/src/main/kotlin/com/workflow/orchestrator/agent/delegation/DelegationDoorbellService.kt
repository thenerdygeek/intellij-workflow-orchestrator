package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.ui.ConsentChoice
import com.workflow.orchestrator.agent.delegation.ui.DelegationInboundConsentDialog
import com.workflow.orchestrator.core.delegation.DelegationFraming
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import com.workflow.orchestrator.core.delegation.KnockOutcome
import com.workflow.orchestrator.core.settings.CrossIdeDelegationSettingsListener
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project doorbell service for on-demand inbound delegation consent (Plan 6).
 *
 * The doorbell is a minimal, always-bound Unix-domain socket — DISTINCT from the
 * real delegation socket ([DelegationInboundService]'s [DelegationServer]). Its ONLY
 * power is to raise a [DelegationInboundConsentDialog]. When IDE-A knocks
 * ([DelegationMessage.Knock]), the doorbell replies [DelegationMessage.KnockAck]
 * immediately, then raises the consent dialog; the user's choice either binds the
 * delegation socket (transiently or persistently) and records a preauth nonce, or
 * writes a declined marker.
 *
 * Security boundary (Plan 6 spec §4 / §10): the doorbell can NEVER start a session
 * or accept work. Its accept loop is dedicated and handles ONLY [DelegationMessage.Knock]
 * — any other message is logged and the connection is closed. This is why the doorbell
 * does NOT reuse [com.workflow.orchestrator.core.delegation.DelegationServer], whose
 * dispatch is hardcoded to Ping / Connect / ChannelResume and would (a) drop a Knock to
 * its `else` branch and (b) wire the session-starting `onConnect` callback.
 *
 * Spec: docs/superpowers/specs/2026-05-25-cross-ide-inbound-consent.md (Task 5).
 */
@Service(Service.Level.PROJECT)
class DelegationDoorbellService(
    private val project: Project,
    private val cs: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    private var serverChannel: ServerSocketChannel? = null
    private var acceptJob: Job? = null

    /**
     * Nonces (and the [DelegationMessage.delegatorSessionId] they belong to) for which a
     * consent dialog is currently pending. Used for dedupe: a repeat knock for the same
     * nonce — or an equivalent request from the same delegator session while a dialog is
     * open — is answered [com.workflow.orchestrator.core.delegation.KnockOutcome.DUPLICATE]
     * and raises no second dialog.
     */
    private val pendingNonces = ConcurrentHashMap.newKeySet<String>()
    private val pendingDelegatorSessions = ConcurrentHashMap.newKeySet<String>()

    /**
     * Rate-limit gate keyed by [DelegationMessage.delegatorSessionId]: at most one consent
     * dialog per delegator session per [RATE_LIMIT_WINDOW_MS]. Value is the epoch-ms of the
     * last accepted knock for that delegator.
     */
    private val lastDialogAt = ConcurrentHashMap<String, Long>()

    /**
     * Visible-for-tests hook for raising the consent dialog. Production uses
     * [showDialogAndApply] (EDT dialog → [applyConsent]); tests swap a no-op so
     * [handleKnock] can be exercised without an [com.intellij.openapi.application.Application]
     * and so the dedupe slots are not cleared by the dialog's `finally`.
     */
    internal var dialogLauncher: (DelegationMessage.Knock) -> Unit = { knock ->
        cs.launch { showDialogAndApply(knock) }
    }

    /**
     * Visible-for-tests seam supplying the ADVISORY busy state stamped onto the [DelegationMessage.Pong]
     * liveness reply. Returns:
     *  - `true`  — this project's agent tab is actively running a task (an agent loop is live).
     *  - `false` — the agent tab is idle and ready to accept work.
     *  - `null`  — the controller/agent state isn't resolvable (service not yet up, no chat opened).
     *
     * Production resolves the live [com.workflow.orchestrator.agent.ui.AgentController] via
     * [com.workflow.orchestrator.agent.ui.AgentControllerRegistry] and reads its
     * [com.workflow.orchestrator.agent.ui.AgentController.isAgentBusy] — the SAME `currentJob?.isActive`
     * verdict the inbound busy gate uses. DEFENSIVE: any failure (registry/controller absent, throw)
     * resolves to `null` so the liveness path never throws. Tests swap a deterministic provider.
     */
    internal var busyStateProvider: () -> Boolean? = { resolveAgentBusy() }

    /**
     * Best-effort read of this project's agent busy state. Never throws — returns null on any
     * failure (controller not yet registered, service unavailable). Kept off the dialog/consent
     * path; only consulted from the side-effect-free Ping→Pong liveness branch.
     */
    private fun resolveAgentBusy(): Boolean? = try {
        com.workflow.orchestrator.agent.ui.AgentControllerRegistry
            .getInstance(project)
            .controller
            ?.isAgentBusy()
    } catch (e: Exception) {
        LOG.debug("doorbell: could not resolve agent busy state; leaving busy=null", e)
        null
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Bind the doorbell socket and start the accept loop. Idempotent — early-returns
     * if already bound. The socket is bound regardless of the inbound setting, so an
     * inbound-OFF IDE can still receive a [DelegationMessage.Knock].
     */
    @Synchronized
    fun start() {
        if (serverChannel != null) return
        val basePath = project.basePath ?: run {
            LOG.warn("Project has no basePath; cannot start DelegationDoorbellService")
            return
        }
        DelegationPaths.ensureIpcDir()
        val socketPath = DelegationPaths.doorbellSocketFor(Path.of(basePath))
        Files.deleteIfExists(socketPath) // clean up stale file from prior crash
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socketPath))
        serverChannel = server
        acceptJob = cs.launch(Dispatchers.IO) { acceptLoop(server) }
        LOG.info("DelegationDoorbellService bound at $socketPath for $basePath")
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try { serverChannel?.close() } catch (_: Exception) { /* ignore */ }
        serverChannel = null
        val basePath = project.basePath
        if (basePath != null) {
            val socketPath = DelegationPaths.doorbellSocketFor(Path.of(basePath))
            try { Files.deleteIfExists(socketPath) } catch (_: Exception) { /* ignore */ }
        }
        LOG.info("DelegationDoorbellService stopped")
    }

    // ── Dedicated minimal accept loop (REVIEW FIX B1) ──────────────────────────

    /**
     * Dedicated accept loop. Mirrors [DelegationServer]'s ServerSocketChannel bind +
     * per-connection [runInterruptible] read + [DelegationFraming] idiom, but handles
     * ONLY [DelegationMessage.Knock]. A non-Knock message is logged and the connection
     * is closed — the doorbell NEVER starts a session.
     */
    private suspend fun acceptLoop(server: ServerSocketChannel) {
        coroutineScope {
            while (isActive) {
                val client = try {
                    runInterruptible { server.accept() }
                } catch (e: Exception) {
                    if (isActive) LOG.warn("doorbell accept failed", e)
                    break
                }
                launch { handleConnection(client) }
            }
        }
    }

    private suspend fun handleConnection(client: SocketChannel) {
        try {
            val msg = withContext(Dispatchers.IO) { DelegationFraming.readFramed(client, json) }
            when (msg) {
                is DelegationMessage.Ping -> {
                    // Pure liveness probe — reply immediately with no side effects.
                    // The doorbell is always bound regardless of the inbound setting, so a
                    // Pong here correctly signals "IDE is open and the doorbell is listening"
                    // to the caller (DelegationClient.ping → TargetStatusResolver.dualProbeStatus).
                    // This makes doorbellReachable=true → TargetStatus.AVAILABLE for a running
                    // IDE that has inbound delegation OFF, fixing the CLOSED mis-classification.
                    //
                    // No consent dialog, no knock handling — Ping is intentionally side-effect-free.
                    //
                    // ADVISORY busy hint: stamp the agent tab's live busy state so a delegator's
                    // list_targets can pre-check running (busy) vs running (idle). Resolved via the
                    // injectable busyStateProvider (prod → AgentController.isAgentBusy()); defensive
                    // null when unavailable so this liveness path never throws.
                    val basePath = project.basePath ?: ""
                    val busy = try { busyStateProvider() } catch (_: Exception) { null }
                    withContext(Dispatchers.IO) {
                        DelegationFraming.writeFramed(
                            client,
                            DelegationMessage.Pong(projectPath = basePath, busy = busy),
                            json,
                        )
                    }
                    try { client.close() } catch (_: Exception) {}
                }
                is DelegationMessage.Knock -> {
                    // REVIEW FIX N2: reply KnockAck BEFORE showing/blocking on the dialog,
                    // so IDE-A's knock() returns promptly without waiting on the human.
                    val outcome = handleKnock(msg)
                    withContext(Dispatchers.IO) {
                        DelegationFraming.writeFramed(
                            client,
                            DelegationMessage.KnockAck(nonce = msg.nonce, outcome = outcome),
                            json,
                        )
                    }
                    try { client.close() } catch (_: Exception) {}
                }
                else -> {
                    // Security boundary: the doorbell only handles Ping (liveness) and Knock
                    // (consent). Anything else is dropped — it can NEVER start a session or accept work.
                    LOG.warn("Unexpected message on doorbell socket (dropped): ${msg::class.simpleName}")
                    try { client.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            LOG.warn("doorbell connection handler failed", e)
            try { client.close() } catch (_: Exception) {}
        }
    }

    // ── Knock handling: dedupe → rate-limit → KnockAck → dialog → consent ──────

    /**
     * Decides the [com.workflow.orchestrator.core.delegation.KnockOutcome] for a knock and,
     * when RINGING, launches the consent dialog asynchronously (so the KnockAck can be written
     * by the caller without waiting on the human — REVIEW FIX N2). Returns the outcome to send
     * back in the [DelegationMessage.KnockAck].
     *
     * Dedupe: if a dialog for this nonce (or an equivalent request from the same delegator
     * session) is already pending → DUPLICATE, no dialog.
     * Rate-limit: at most one dialog per delegator session per [RATE_LIMIT_WINDOW_MS] → DUPLICATE.
     */
    @Synchronized
    internal fun handleKnock(knock: DelegationMessage.Knock): KnockOutcome {
        // Dedupe by nonce or by an in-flight dialog for the same delegator session.
        if (pendingNonces.contains(knock.nonce) ||
            pendingDelegatorSessions.contains(knock.delegatorSessionId)
        ) {
            return KnockOutcome.DUPLICATE
        }
        // Rate-limit: ≤ 1 dialog per delegator session per window.
        val now = System.currentTimeMillis()
        val last = lastDialogAt[knock.delegatorSessionId]
        if (last != null && now - last < RATE_LIMIT_WINDOW_MS) {
            return KnockOutcome.DUPLICATE
        }

        // RINGING: reserve the dedupe slots, then raise the dialog asynchronously.
        pendingNonces.add(knock.nonce)
        pendingDelegatorSessions.add(knock.delegatorSessionId)
        lastDialogAt[knock.delegatorSessionId] = now

        dialogLauncher(knock)
        return KnockOutcome.RINGING
    }

    /**
     * Raise the [DelegationInboundConsentDialog] on the EDT and apply the user's choice.
     * Clears the dedupe slots in a `finally` so a later knock for the same delegator can
     * raise a fresh dialog once this one is resolved.
     */
    private suspend fun showDialogAndApply(knock: DelegationMessage.Knock) {
        try {
            // Bug B: the consent dialog is MODELESS, so show() is non-blocking — reading the dialog's
            // choice field right after show() returns the default CANCEL before the user clicks. Await
            // the dialog's reported selection via a CompletableDeferred the dialog completes on close.
            val choiceResult = CompletableDeferred<ConsentChoice>()
            withContext(Dispatchers.EDT) {
                DelegationInboundConsentDialog(project, knock) { chosen ->
                    choiceResult.complete(chosen)
                }.show()
            }
            val choice = choiceResult.await()
            val store = PendingDelegationStore(DelegationPaths.agentDirForDelegation(project.basePath ?: "."))
            val inbound = project.getService(DelegationInboundService::class.java)
            applyConsent(knock, choice, store, inbound)
        } catch (e: Exception) {
            LOG.warn("doorbell consent dialog/apply failed for nonce=${knock.nonce}", e)
        } finally {
            clearDedupeSlots(knock)
        }
    }

    /**
     * Release all dedupe/rate-limit reservations for [knock]. Called from [showDialogAndApply]'s
     * `finally` once the consent dialog has resolved, so a later knock from the same delegator
     * session can raise a fresh dialog.
     *
     * Bug B: this MUST clear [lastDialogAt] too. Previously only [pendingNonces] +
     * [pendingDelegatorSessions] were released, so a legitimate SECOND delegation from the same
     * delegator session — arriving after the first dialog resolved but still within
     * [RATE_LIMIT_WINDOW_MS] — was wrongly answered DUPLICATE and raised no dialog (and on IDE-A
     * that surfaced as a 90s dead-poll → TargetNotReachable). The rate-limit is meant to throttle
     * dialog SPAM while one is pending, not to block a real follow-up after the prior one resolved.
     */
    @Synchronized
    internal fun clearDedupeSlots(knock: DelegationMessage.Knock) {
        pendingNonces.remove(knock.nonce)
        pendingDelegatorSessions.remove(knock.delegatorSessionId)
        lastDialogAt.remove(knock.delegatorSessionId)
    }

    // ── Consent application (socket/EDT-free for unit testing) ─────────────────

    /**
     * Apply the user's consent choice. Deliberately free of socket/EDT concerns so the
     * test can drive it directly (REVIEW FIX N1 — also satisfies the spec's named
     * DelegationConsentFlowTest assertions).
     *
     * - [ConsentChoice.ALLOW_ALWAYS] → persist [PluginSettings.State.enableInboundCrossIdeDelegation]
     *   = true (fires the [CrossIdeDelegationSettingsListener] → DelegationInboundService.start()),
     *   then record the preauth nonce.
     * - [ConsentChoice.ALLOW_ONCE] → transient bind + record preauth nonce; setting unchanged.
     * - [ConsentChoice.CANCEL] → write the declined marker; no bind, no preauth.
     *
     * Fix D: on either ALLOW choice the consumed pending `.json` is dropped via
     * [PendingDelegationStore.clearPending] so a cold-launch request that the SENDER already
     * stopped polling for (its 90s wait timed out before the user consented) is not replayed again
     * on a later IDE restart and does not linger until [REPLAY_TTL_MS]. The `.declined` marker is
     * deliberately NOT removed on CANCEL — the sender's poll may still need to observe it.
     */
    internal fun applyConsent(
        knock: DelegationMessage.Knock,
        choice: ConsentChoice,
        store: PendingDelegationStore,
        inbound: DelegationInboundService,
    ) {
        when (choice) {
            ConsentChoice.ALLOW_ALWAYS -> {
                val settings = PluginSettings.getInstance(project)
                val wasEnabled = settings.state.enableInboundCrossIdeDelegation
                settings.state.enableInboundCrossIdeDelegation = true
                // Publishing the listener is what actually drives DelegationInboundService.start()
                // (setting the raw state field alone does not fire the message-bus topic — only
                // the Configurable.apply() path publishes on a UI toggle).
                if (!wasEnabled) {
                    try {
                        project.messageBus
                            .syncPublisher(CrossIdeDelegationSettingsListener.TOPIC)
                            .inboundSettingChanged(true)
                    } catch (e: Exception) {
                        LOG.warn("applyConsent: failed to publish inboundSettingChanged", e)
                    }
                }
                inbound.recordPreauth(knock.nonce)
                store.clearPending(knock.nonce)
            }
            ConsentChoice.ALLOW_ONCE -> {
                // Bug D: record the preauth nonce BEFORE binding the socket. Once startTransient()
                // binds, IDE-A's poll can detect the live socket and fire its Connect within
                // milliseconds; if the nonce weren't recorded yet, consumePreauth would miss and
                // IDE-B would pop a redundant Accept dialog. Recording first closes that window.
                inbound.recordPreauth(knock.nonce)
                inbound.startTransient()
                store.clearPending(knock.nonce)
            }
            ConsentChoice.CANCEL -> {
                store.markDeclined(knock.nonce)
            }
        }
    }

    // ── Startup-activity replay (Task 6) ───────────────────────────────────────

    /**
     * Replay any fresh file-based pending requests through the same dedupe → dialog →
     * applyConsent path used for live knocks. Used by Task 6's startup activity after
     * the project enters smart mode (fresh-launch path, where IDE-A knocked while IDE-B
     * was not running). Reads from THIS project's agent dir.
     */
    fun replayPendingRequests() {
        val basePath = project.basePath ?: return
        val store = PendingDelegationStore(DelegationPaths.agentDirForDelegation(basePath))
        val fresh = try {
            store.readFresh(ttlMillis = REPLAY_TTL_MS)
        } catch (e: Exception) {
            LOG.warn("replayPendingRequests: readFresh failed", e)
            return
        }
        for (req in fresh) {
            if (store.isDeclined(req.nonce)) continue
            val knock = DelegationMessage.Knock(
                delegatorIde = req.delegatorIde,
                delegatorRepo = req.delegatorRepo,
                delegatorSessionId = req.delegatorSessionId,
                requestPreview = req.requestPreview,
                nonce = req.nonce,
            )
            // Reuse the live dedupe gate so a request already handled by a live knock
            // (or a duplicate file) doesn't raise a second dialog.
            handleKnock(knock)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationDoorbellService::class.java)

        /** ≤ 1 consent dialog per delegator session per 10 s (Plan 6 spec §10). */
        const val RATE_LIMIT_WINDOW_MS = 10_000L

        /**
         * Fresh-launch replay TTL: 30 minutes (Fix D).
         *
         * This bounds how stale a fresh-launch pending request may be before [readFresh] discards
         * it (and thus before the consent dialog is suppressed). It is evaluated by the COLD,
         * newly-launched IDE-B *after* it finishes indexing — so it must comfortably exceed a
         * large-repo cold boot + full index, which can run well past the old 5-minute value. At
         * 5 min, a request that survived the sender's wait (the PRIMARY Fix-D change) could still be
         * dropped as "stale" the moment the cold IDE became ready, so the dialog never appeared.
         * 30 min gives generous headroom for indexing while still ensuring a genuinely abandoned
         * fresh-launch request (delegator long gone) does not pop a surprise dialog much later.
         * Stamped from IDE-A's `createdAt` (see [DelegationOutboundService.knockAndWaitForBind]).
         */
        const val REPLAY_TTL_MS = 30L * 60 * 1000
    }
}
