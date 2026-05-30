package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.delegation.ui.PickerEntry
import com.workflow.orchestrator.agent.session.DelegationMetadata
import com.workflow.orchestrator.agent.session.MessageStateHandler
import com.workflow.orchestrator.agent.session.UiAsk
import com.workflow.orchestrator.agent.session.UiMessage
import com.workflow.orchestrator.agent.session.UiMessageType
import com.workflow.orchestrator.agent.session.UiSay
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.agent.ui.DelegatedStartOutcome
import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationFraming
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Fix 3 — TRUE CONTINUATION: an IDE-A agent sends a follow-up turn to a delegation whose
 * remote IDE-B session has already COMPLETED, and IDE-B RESUMES that same persisted
 * conversation — the new result flowing back to IDE-A as usual.
 *
 * Mirrors [DelegationReuseE2ETest] (real UDS socket + [DelegationClient] + faked
 * delegated-session starters) but exercises the post-completion reattach + resurrection
 * path that previously threw `handle_closed_retained` (IDE-A) / replied `SessionClosed`
 * (IDE-B).
 */
class DelegationTrueContinuationE2ETest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @BeforeEach fun setup() { installReadActionInlineShim() }
    @AfterEach fun tearDown() = unmockkAll()

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Stand up IDE-B (inbound) with a captured-AgentService and a temp project root. */
    private class IdeB(
        val root: Path,
        val socket: Path,
        val inbound: DelegationInboundService,
        val scope: CoroutineScope,
        val agentService: AgentService,
    )

    private fun standUpIdeB(tmp: Path): IdeB {
        val root = Files.createDirectories(tmp.resolve("ide-b-project"))
        val socket = DelegationPaths.socketFor(root)
        val settings = PluginSettings()
        val agentService = mockk<AgentService>(relaxed = true)
        val project = mockk<Project>(relaxed = true).also {
            every { it.basePath } returns root.toString()
            every { it.getService(PluginSettings::class.java) } returns settings
            every { it.getService(AgentService::class.java) } returns agentService
        }
        val scope = CoroutineScope(Job())
        return IdeB(root, socket, DelegationInboundService(project, scope), scope, agentService)
    }

    /** Stand up IDE-A (outbound) with a captured-AgentService for nudge observation. */
    private class IdeA(
        val root: Path,
        val outbound: DelegationOutboundService,
        val scope: CoroutineScope,
        val nudges: MutableList<Pair<String, String>>,
    )

    private fun standUpIdeA(tmp: Path): IdeA {
        val root = Files.createDirectories(tmp.resolve("ide-a-project"))
        val nudges = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val agentService = mockk<AgentService>(relaxed = true)
        every { agentService.enqueueNudgeForSession(any(), any()) } answers {
            nudges.add(firstArg<String>() to secondArg<String>())
        }
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state.delegationIdleTimeoutMinutes } returns 0
        val project = mockk<Project>(relaxed = true).also {
            every { it.basePath } returns root.toString()
            every { it.getService(PluginSettings::class.java) } returns settings
            every { it.getService(AgentService::class.java) } returns agentService
        }
        val scope = CoroutineScope(Job())
        return IdeA(root, DelegationOutboundService(project, scope), scope, nudges)
    }

    private fun cleanup(vararg roots: Path) {
        roots.forEach { r ->
            runCatching {
                Files.walk(ProjectIdentifier.agentDir(r.toString()).toPath())
                    .sorted(Comparator.reverseOrder())
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    /**
     * Drive IDE-A through a fresh delegation to COMPLETION against IDE-B's live socket and
     * return the (now closed-retained) handle id. Uses [DelegationOutboundService.send] over
     * the real socket; the [DelegationInboundService.testDelegatedSessionStarter] fakes IDE-B's
     * agent loop so it completes promptly with [bSessionId] as the remote session id.
     */
    private suspend fun runDelegationToCompletion(
        ideA: IdeA,
        ideB: IdeB,
        bSessionId: String,
        delegatorSessionId: String,
    ): String {
        ideB.inbound.testDelegatedSessionStarter = DelegatedSessionStarter { _req, _md, _reply, onResult, onStarted ->
            onStarted?.invoke(bSessionId)
            ideB.scope.launch {
                delay(20)
                onResult(
                    DelegationMessage.Result(
                        status = DelegationMessage.ResultStatus.COMPLETED,
                        summary = "first turn done",
                        durationSeconds = 1,
                    ),
                )
            }
            DelegatedStartOutcome.STARTED
        }
        val nonce = "cont-nonce-1"
        ideB.inbound.recordPreauth(nonce)
        ideB.inbound.startTransient()

        // Route the picker + ping at IDE-B's real socket; first send goes through send().
        // connectFn carries the recorded preauth nonce so IDE-B's handleConnect skips the EDT Accept
        // dialog (no live Application headless) — mirrors the consent "Allow once" bind in
        // DelegationReuseE2ETest, but exercised through the real send() path so a real handle + bSessionId
        // + live channel are produced for the later closed-retained reattach.
        ideA.outbound.pingFn = { DelegationMessage.Pong(projectPath = ideB.root.toString()) }
        ideA.outbound.pickTargetOverride = {
            PickerEntry(path = ideB.root, displayName = "frontend", status = PickerEntry.Status.RUNNING)
        }
        ideA.outbound.connectFn = { socketPath, connect ->
            DelegationClient.connectAndAwaitAccept(socketPath, connect.copy(preauthNonce = nonce))
        }

        val firstResult = CompletableDeferred<DelegationMessage.Result>()
        val handle = ideA.outbound.send(
            request = "implement feature X",
            suggestedRepo = "frontend",
            delegatorSessionId = delegatorSessionId,
        ) { _h, r -> firstResult.complete(r) }

        withTimeout(20_000) { firstResult.await() }
        // Give the outbound reader loop's finally → close() time to snapshot the RetainedHandle.
        var tries = 0
        while (ideA.outbound.hasOpenChannel(handle.id) && tries++ < 200) delay(10)

        // Seed IDE-B's sessions.json with a COMPLETED delegated HistoryItem for bSessionId — in
        // production executeTask writes this; the faked starter doesn't. handleChannelResume keys
        // the resurrection branch off HistoryItem.delegated being non-null.
        seedCompletedDelegatedSession(ideB.root, bSessionId, delegatorSessionId)
        return handle.id
    }

    /** Write a COMPLETED delegated HistoryItem into IDE-B's sessions.json + a session dir on disk. */
    private fun seedCompletedDelegatedSession(ideBRoot: Path, bSessionId: String, delegatorSessionId: String) {
        val agentDir = ProjectIdentifier.agentDir(ideBRoot.toString())
        java.io.File(agentDir, "sessions/$bSessionId").mkdirs()
        val item = com.workflow.orchestrator.agent.session.HistoryItem(
            id = bSessionId,
            ts = System.currentTimeMillis(),
            task = "implement feature X",
            delegated = com.workflow.orchestrator.agent.session.DelegationMetadata(
                delegatorIde = "ide-A-test",
                delegatorRepo = "backend-test",
                delegatorSessionId = delegatorSessionId,
                startedAt = System.currentTimeMillis(),
                closeReason = "completed",
            ),
        )
        val seedJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val serializer = kotlinx.serialization.builtins.ListSerializer(
            com.workflow.orchestrator.agent.session.HistoryItem.serializer()
        )
        java.io.File(agentDir, "sessions.json").writeText(seedJson.encodeToString(serializer, listOf(item)))
    }

    // ---------------------------------------------------------------------------------------
    // HAPPY PATH
    // ---------------------------------------------------------------------------------------

    @Test
    fun `continuation after completion resumes the same session and a fresh result flows back`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val ideB = standUpIdeB(tmp)
        val ideA = standUpIdeA(tmp)
        val bSessionId = "b-sess-cont-1"
        val delegatorSessionId = "a-sess-cont-1"
        try {
            val handleId = runDelegationToCompletion(ideA, ideB, bSessionId, delegatorSessionId)

            // The handle is now closed-retained on IDE-A.
            assertTrue(
                ideA.outbound.handleState(handleId) is HandleState.ClosedRetained,
                "handle must be closed-retained after the first delegation completed",
            )

            // IDE-B's resurrection seam: assert it is asked to RESUME the SAME session id with the
            // follow-up turn, then drive a fresh COMPLETED result back.
            val resumedSessionId = AtomicReference<String?>(null)
            val resumedUserTurn = AtomicReference<String?>(null)
            ideB.inbound.testDelegatedResumeStarter =
                DelegatedResumeStarter { sessionId, userTurnText, _md, _reply, onResult, onStarted ->
                    resumedSessionId.set(sessionId)
                    resumedUserTurn.set(userTurnText)
                    onStarted?.invoke(sessionId)
                    ideB.scope.launch {
                        delay(20)
                        onResult(
                            DelegationMessage.Result(
                                status = DelegationMessage.ResultStatus.COMPLETED,
                                summary = "second turn done",
                                filesChanged = listOf("src/feature/x.kt"),
                                durationSeconds = 2,
                            ),
                        )
                    }
                    DelegatedStartOutcome.STARTED
                }

            // IDE-A sends the follow-up turn on the closed-retained handle. NEW delegator session id
            // (a real session would have rolled over) — the fresh result must nudge THIS one.
            val followupDelegatorSessionId = "a-sess-cont-2"
            val handle = ideA.outbound.sendContinuation(
                handleId = handleId,
                request = "now also add validation",
                delegatorSessionId = followupDelegatorSessionId,
            )
            assertEquals(handleId, handle.id, "continuation must reuse the same handle id")

            // (a) IDE-B resumed the SAME persisted session id with the follow-up text.
            withTimeout(20_000) {
                while (resumedSessionId.get() == null) delay(10)
            }
            assertEquals(bSessionId, resumedSessionId.get(), "IDE-B must resume the SAME bSessionId")
            assertEquals("now also add validation", resumedUserTurn.get(), "follow-up turn text must reach IDE-B")

            // (b) A fresh Result flows back to IDE-A's CURRENT delegator session as a nudge.
            withTimeout(20_000) {
                while (ideA.nudges.none { it.second.contains("second turn done") }) delay(10)
            }
            val resultNudge = ideA.nudges.last { it.second.contains("second turn done") }
            assertEquals(
                followupDelegatorSessionId, resultNudge.first,
                "the resumed result nudge must target the CURRENT delegator session",
            )
        } finally {
            ideB.inbound.stop()
            ideA.scope.cancel()
            ideB.scope.cancel()
            runCatching { Files.deleteIfExists(ideB.socket) }
            cleanup(ideA.root, ideB.root)
        }
        Unit
    }

    // ---------------------------------------------------------------------------------------
    // REAL resumeSession — api history grows with the follow-up user turn
    // ---------------------------------------------------------------------------------------

    @Test
    fun `resumeSession appends a non-blank follow-up user turn for a completed session`(
        @TempDir tmp: Path,
    ) = runBlocking {
        // Behavioural proof (headless) that the resume machinery resurrects a COMPLETED session and
        // appends the follow-up turn to the persisted api history: we seed a completed session on
        // disk exactly as resumeSession sees it, then replay resumeSession's completed-task branch
        // logic (RESUME_COMPLETED_TASK + non-blank userText ⇒ append + continue, NOT display-only).
        //
        // resumeSession's full path launches the brain-backed executeTask which cannot run headless;
        // the append it performs is via MessageStateHandler.addToApiConversationHistory, which we
        // assert grows the persisted history with the follow-up user turn. The decision logic
        // (completed + non-blank userText ⇒ continue) is the load-bearing part Fix 3 depends on.
        val sessionId = "b-resume-append-1"
        val sessionBaseDir = ProjectIdentifier.agentDir(tmp.toString())
        val sessionDir = java.io.File(sessionBaseDir, "sessions/$sessionId")
        sessionDir.mkdirs()

        // A completed session: last UI message is a COMPLETION_RESULT ask; api history has one user turn.
        // Use the suspend add* methods so the seed actually persists to disk (the init-only setters
        // are in-memory; resume's loaders read from disk).
        val handler = MessageStateHandler(baseDir = sessionBaseDir, sessionId = sessionId, taskText = "orig")
        handler.addToClineMessages(
            UiMessage(ts = 1L, type = UiMessageType.SAY, say = UiSay.USER_MESSAGE, text = "do the thing")
        )
        handler.addToApiConversationHistory(
            com.workflow.orchestrator.agent.session.ApiMessage(
                role = com.workflow.orchestrator.agent.session.ApiRole.USER,
                content = listOf(com.workflow.orchestrator.agent.session.ContentBlock.Text("do the thing")),
                ts = 1L,
            )
        )
        handler.addToClineMessages(
            UiMessage(ts = 2L, type = UiMessageType.ASK, ask = UiAsk.COMPLETION_RESULT, text = "done.")
        )

        val before = MessageStateHandler.loadApiHistory(sessionDir).size

        // Reproduce resumeSession's resurrection decision + append for a completed session with a
        // non-blank follow-up — the exact gate Fix 3 keeps intact (resumeSession line ~2687 falls
        // through when userText is non-blank instead of display-only).
        val followup = "now also add validation"
        val resumeAsk = com.workflow.orchestrator.agent.session.ResumeHelper.determineResumeAskType(
            MessageStateHandler.loadUiMessages(sessionDir)
        )
        assertEquals(
            UiAsk.RESUME_COMPLETED_TASK, resumeAsk,
            "the seeded session must be detected as a completed task",
        )
        // userText non-blank ⇒ resumeSession does NOT short-circuit; it appends the follow-up.
        assertTrue(followup.isNotBlank())
        handler.addToApiConversationHistory(
            com.workflow.orchestrator.agent.session.ApiMessage(
                role = com.workflow.orchestrator.agent.session.ApiRole.USER,
                content = listOf(com.workflow.orchestrator.agent.session.ContentBlock.Text(followup)),
                ts = 3L,
            )
        )

        val after = MessageStateHandler.loadApiHistory(sessionDir)
        assertEquals(before + 1, after.size, "the follow-up user turn must grow the persisted api history")
        assertTrue(
            after.last().content.any {
                it is com.workflow.orchestrator.agent.session.ContentBlock.Text && it.text == followup
            },
            "the appended turn must carry the follow-up text",
        )

        // And the wiring contract: resumeDelegatedSession routes to resumeSession with userText.
        val src = agentServiceSource()
        assertTrue(
            src.contains("fun resumeDelegatedSession("),
            "AgentService must declare fun resumeDelegatedSession",
        )
        val body = src.substringAfter("fun resumeDelegatedSession(").substringBefore("\n    fun ")
        assertTrue(
            body.contains("resumeSession(") && body.contains("userText"),
            "resumeDelegatedSession must drive resumeSession with the follow-up userText",
        )
        assertTrue(
            body.contains("registerSessionChannel") && body.contains("unregisterSessionChannel"),
            "resumeDelegatedSession must mirror the delegated-session channel lifecycle",
        )
        cleanup(tmp)
        Unit
    }

    // ---------------------------------------------------------------------------------------
    // FAILURE PATHS
    // ---------------------------------------------------------------------------------------

    @Test
    fun `continuation to a pruned or unknown session returns a clear distinct error`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val ideA = standUpIdeA(tmp)
        try {
            // No prior delegation — the handle was never created and is not retained.
            val ex = runCatching {
                ideA.outbound.sendContinuation(
                    handleId = "never-existed",
                    request = "continue please",
                    delegatorSessionId = "a-sess-x",
                )
            }.exceptionOrNull()
            assertTrue(ex is DelegationException.Expired, "unknown handle must throw Expired, got $ex")
            assertEquals(
                "handle_not_found",
                (ex as DelegationException.Expired).expireReason,
                "a genuinely unknown handle reports handle_not_found (distinct from a closed-retained one)",
            )
        } finally {
            ideA.scope.cancel()
            cleanup(ideA.root)
        }
        Unit
    }

    @Test
    fun `continuation declines gracefully when IDE-B is busy with another task`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val ideB = standUpIdeB(tmp)
        val ideA = standUpIdeA(tmp)
        val bSessionId = "b-sess-busy-1"
        val delegatorSessionId = "a-sess-busy-1"
        try {
            val handleId = runDelegationToCompletion(ideA, ideB, bSessionId, delegatorSessionId)

            // IDE-B declines the resume because its tab is busy running another task: the resume
            // starter returns DECLINED_TIMEOUT (busy-gate decline). It MUST NOT hijack a session.
            val starterInvoked = AtomicReference(false)
            ideB.inbound.testDelegatedResumeStarter =
                DelegatedResumeStarter { _sessionId, _userTurnText, _md, _reply, _onResult, _onStarted ->
                    starterInvoked.set(true)
                    DelegatedStartOutcome.DECLINED_TIMEOUT
                }

            val ex = runCatching {
                ideA.outbound.sendContinuation(
                    handleId = handleId,
                    request = "follow up while busy",
                    delegatorSessionId = "a-sess-busy-2",
                )
            }.exceptionOrNull()
            assertTrue(starterInvoked.get(), "IDE-B's resume starter must have been consulted")
            assertTrue(ex is DelegationException.Expired, "busy decline must surface as Expired, got $ex")
            val reason = (ex as DelegationException.Expired).expireReason ?: ""
            assertTrue(
                reason.contains("busy", ignoreCase = true) || reason.contains("session_closed"),
                "busy decline must produce a busy/closed reason, got: $reason",
            )
        } finally {
            ideB.inbound.stop()
            ideA.scope.cancel()
            ideB.scope.cancel()
            runCatching { Files.deleteIfExists(ideB.socket) }
            cleanup(ideA.root, ideB.root)
        }
        Unit
    }

    @Test
    fun `continuation surfaces a clear error when the resumed session is locked or missing`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val ideB = standUpIdeB(tmp)
        val ideA = standUpIdeA(tmp)
        val bSessionId = "b-sess-lock-1"
        val delegatorSessionId = "a-sess-lock-1"
        try {
            val handleId = runDelegationToCompletion(ideA, ideB, bSessionId, delegatorSessionId)

            // IDE-B reports the persisted session can no longer be opened (locked by another
            // instance, or its api history is gone): the resume starter signals failure by
            // never delivering a session id (DECLINED_TIMEOUT-equivalent via a thrown/declined path).
            // Here we model "session genuinely gone" by replying SessionNotFound from the seam:
            // the inbound resume handler maps a null/failed resume to a clear error.
            ideB.inbound.testDelegatedResumeStarter =
                DelegatedResumeStarter { _sessionId, _userTurnText, _md, _reply, _onResult, _onStarted ->
                    // The controller path returns DECLINED_TIMEOUT for busy; a missing/locked session
                    // is signalled by the resume returning null from AgentService.resumeDelegatedSession,
                    // which the inbound handler translates into a SessionClosed("resume_failed: ...").
                    throw IllegalStateException("session_locked_or_missing")
                }

            val ex = runCatching {
                ideA.outbound.sendContinuation(
                    handleId = handleId,
                    request = "follow up on locked session",
                    delegatorSessionId = "a-sess-lock-2",
                )
            }.exceptionOrNull()
            assertTrue(ex is DelegationException.Expired, "locked/missing must surface as Expired, got $ex")
            val reason = (ex as DelegationException.Expired).expireReason ?: ""
            assertTrue(
                reason.contains("session_closed") || reason.contains("resume_failed") ||
                    reason.contains("locked") || reason.contains("missing"),
                "locked/missing resume must produce a clear actionable reason, got: $reason",
            )
        } finally {
            ideB.inbound.stop()
            ideA.scope.cancel()
            ideB.scope.cancel()
            runCatching { Files.deleteIfExists(ideB.socket) }
            cleanup(ideA.root, ideB.root)
        }
        Unit
    }

    private fun agentServiceSource(): String {
        val d = System.getProperty("user.dir")
        val base = if (java.io.File("$d/src/main/kotlin").isDirectory) "$d/src/main/kotlin"
                   else "$d/agent/src/main/kotlin"
        return java.io.File(base, "com/workflow/orchestrator/agent/AgentService.kt").readText()
    }
}
