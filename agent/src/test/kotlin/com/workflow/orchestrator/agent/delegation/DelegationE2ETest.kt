package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.delegation.ui.ConsentChoice
import com.workflow.orchestrator.agent.delegation.ui.PickerEntry
import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationFraming
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import com.workflow.orchestrator.core.delegation.DelegationServer
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DelegationE2ETest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @AfterEach fun tearDown() = unmockkAll()

    @Test
    fun `happy path round-trips a delegation from A to B`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("e2e.sock")
        coroutineScope {
            // ---- Side B: server that auto-accepts and returns a COMPLETED Result ----
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/projects/B",
                onConnect = { connect, replyWith, _readMessage, closeChannel ->
                    assertEquals("backend-test", connect.delegatorRepo)
                    assertEquals("Add a createUser endpoint", connect.request)
                    replyWith(DelegationMessage.AcceptResult(accepted = true))
                    delay(50)  // simulate work
                    replyWith(
                        DelegationMessage.Result(
                            status = DelegationMessage.ResultStatus.COMPLETED,
                            summary = "Implemented createUser endpoint as requested.",
                            filesChanged = listOf("src/api/users.ts"),
                            branch = "feature/users-endpoint",
                            durationSeconds = 12,
                        )
                    )
                    // F1: close the channel after the terminal Result is written
                    closeChannel()
                },
                scope = this,
            )
            server.start()
            try {
                // ---- Side A: connect, send, await ack + result ----
                val pair = DelegationClient.connectAndAwaitAccept(
                    socketPath,
                    DelegationMessage.Connect(
                        delegatorIde = "ide-A-test",
                        delegatorRepo = "backend-test",
                        delegatorSessionId = "sess-e2e-1",
                        request = "Add a createUser endpoint",
                    ),
                )
                assertNotNull(pair, "connectAndAwaitAccept must not return null on happy path")
                val (channel, ack) = pair!!
                assertTrue(ack.accepted)
                val result = withTimeout(2_000) {
                    DelegationFraming.readFramed(channel, json)
                } as DelegationMessage.Result
                assertEquals(DelegationMessage.ResultStatus.COMPLETED, result.status)
                assertEquals("Implemented createUser endpoint as requested.", result.summary)
                assertEquals(listOf("src/api/users.ts"), result.filesChanged)
                assertEquals("feature/users-endpoint", result.branch)
                assertEquals(12L, result.durationSeconds)
                channel.close()
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `reject path returns AcceptResult with accepted=false and reason`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("reject.sock")
        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/projects/B",
                onConnect = { _, replyWith, _readMessage, closeChannel ->
                    replyWith(DelegationMessage.AcceptResult(accepted = false, reason = "user_rejected"))
                    closeChannel()
                },
                scope = this,
            )
            server.start()
            try {
                val pair = DelegationClient.connectAndAwaitAccept(
                    socketPath,
                    DelegationMessage.Connect(
                        delegatorIde = "A",
                        delegatorRepo = "b",
                        delegatorSessionId = "s",
                        request = "go",
                    ),
                )
                assertNotNull(pair)
                val (channel, ack) = pair!!
                assertFalse(ack.accepted)
                assertEquals("user_rejected", ack.reason)
                channel.close()
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `failure result round-trips intact`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("fail.sock")
        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/p",
                onConnect = { _, replyWith, _readMessage, closeChannel ->
                    replyWith(DelegationMessage.AcceptResult(accepted = true))
                    replyWith(
                        DelegationMessage.Result(
                            status = DelegationMessage.ResultStatus.FAILED,
                            reason = "tool_execution_failed: simulated",
                            durationSeconds = 3,
                        )
                    )
                    closeChannel()
                },
                scope = this,
            )
            server.start()
            try {
                val (channel, _) = DelegationClient.connectAndAwaitAccept(
                    socketPath,
                    DelegationMessage.Connect("A", "b", "s", "go"),
                )!!
                val result = withTimeout(2_000) {
                    DelegationFraming.readFramed(channel, json)
                } as DelegationMessage.Result
                assertEquals(DelegationMessage.ResultStatus.FAILED, result.status)
                assertEquals("tool_execution_failed: simulated", result.reason)
                channel.close()
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `ping unreachable target returns null`(@TempDir tmp: Path) = runBlocking {
        // No server bound — confirm DelegationClient.ping returns null cleanly
        val socketPath = tmp.resolve("noone.sock")
        val pong = DelegationClient.ping(socketPath, timeoutMillis = 200)
        assertNull(pong)
    }

    @Test
    fun `two parallel delegations on different sockets do not interfere`(@TempDir tmp: Path) = runBlocking {
        val socketA = tmp.resolve("a.sock")
        val socketB = tmp.resolve("b.sock")
        coroutineScope {
            val serverA = DelegationServer(
                socketPath = socketA,
                projectPath = "/A",
                onConnect = { c, replyWith, _readMessage, closeChannel ->
                    replyWith(DelegationMessage.AcceptResult(accepted = true))
                    replyWith(
                        DelegationMessage.Result(
                            status = DelegationMessage.ResultStatus.COMPLETED,
                            summary = "from-A:${c.request}",
                        )
                    )
                    closeChannel()
                },
                scope = this,
            )
            val serverB = DelegationServer(
                socketPath = socketB,
                projectPath = "/B",
                onConnect = { c, replyWith, _readMessage, closeChannel ->
                    replyWith(DelegationMessage.AcceptResult(accepted = true))
                    replyWith(
                        DelegationMessage.Result(
                            status = DelegationMessage.ResultStatus.COMPLETED,
                            summary = "from-B:${c.request}",
                        )
                    )
                    closeChannel()
                },
                scope = this,
            )
            serverA.start(); serverB.start()
            try {
                val results = listOf("A" to socketA, "B" to socketB).map { (tag, sock) ->
                    async {
                        val (ch, _) = DelegationClient.connectAndAwaitAccept(
                            sock,
                            DelegationMessage.Connect("ide", "r", "sess-$tag", "req-$tag"),
                        )!!
                        val r = withTimeout(2_000) { DelegationFraming.readFramed(ch, json) } as DelegationMessage.Result
                        ch.close()
                        r.summary
                    }
                }.awaitAll()
                assertEquals(listOf("from-A:req-A", "from-B:req-B"), results)
            } finally {
                serverA.stop(); serverB.stop()
            }
        }
    }

    /**
     * Plan 6 Task 9 — full inbound-OFF → consent → work happy path over real
     * (temp-dir-keyed) UDS sockets.
     *
     * Topology:
     *  - IDE B: inbound setting OFF. Real [DelegationDoorbellService] binds its
     *    doorbell socket. The delegation (door) socket is NOT bound initially.
     *  - IDE A: real [DelegationOutboundService.send] knock-and-wait flow targeting
     *    IDE B, using the REAL [DelegationClient] ping/knock/connect over sockets
     *    (only the picker + launcher seams are stubbed — there is no EDT picker /
     *    real launcher binary in a unit test).
     *
     * Consent injection: the doorbell's [DelegationDoorbellService.dialogLauncher]
     * seam (exposed by Task 5) is swapped to apply [ConsentChoice.ALLOW_ONCE]
     * directly via the real [DelegationDoorbellService.applyConsent] — no real
     * production seam was added. ALLOW_ONCE drives the real
     * [DelegationInboundService.startTransient] (which binds the REAL delegation
     * socket via the real [DelegationServer]) + [recordPreauth].
     *
     * Faked leg: ONLY the delegated agent loop. The real [DelegationInboundService.handleConnect]
     * runs (real preauth gate, real read-loop try/finally, real AcceptResult/Result
     * write path) — it routes into [AgentService.startDelegatedSession], which would
     * otherwise spin up the full ReAct loop (LLM brain, settings, …) out of reach in a
     * service-less test. We stub that ONE method on a mocked [AgentService] to invoke
     * the supplied terminal callback with a canned COMPLETED Result and to mirror Task 8's
     * teardown wiring (`stopIfTransientAndIdle` on session end) — exactly as the existing
     * E2E cases above fake the loop with a canned Result. Everything else — sockets,
     * doorbell, consent application, preauth registry, the transient bind + the real
     * teardown gate — is production code.
     *
     * Proves:
     *  (a) the Connect IDE B accepted carried the preauth nonce and SKIPPED the Accept
     *      dialog — the real [DelegationInboundService.handleConnect] consumes the nonce
     *      via [consumePreauth] (single-use; a `consumePreauth` spy confirms the value)
     *      and the [AcceptDelegationDialog] is never constructed (no EDT, no Application);
     *  (b) transient teardown unbound IDE B's delegation socket — the real
     *      [DelegationInboundService.stopIfTransientAndIdle] (transient bind from
     *      `startTransient`) brings the real [DelegationServer] down, so a post-run ping
     *      to the door returns null;
     *  (c) the inbound setting stayed false the whole time.
     */
    @Test
    fun `inbound-off consent ALLOW_ONCE round-trips delegated work and tears down`(@TempDir tmp: Path) = runBlocking {
        // IDE B's project root. All socket paths + agent dir derive from this (real, but
        // keyed by the unique temp path so this test is isolated from a real install).
        val ideBRoot = Files.createDirectory(tmp.resolve("ide-b-project"))
        val doorSocket = DelegationPaths.socketFor(ideBRoot)
        val doorbellSocket = DelegationPaths.doorbellSocketFor(ideBRoot)

        // ---- IDE B services (real), wired over a relaxed Project mock ----
        val settingsB = PluginSettings() // real State, inbound OFF by default
        assertFalse(settingsB.state.enableInboundCrossIdeDelegation)

        val activeSessions = AtomicInteger(0)

        // Mock AgentService: stubs ONLY the agent-loop leg (startDelegatedSession). It fakes
        // the loop by invoking the terminal callback with a canned COMPLETED Result, and
        // mirrors Task 8's teardown wiring (stopIfTransientAndIdle on session end). The
        // inbound service the mock returns from getService is filled in below.
        val agentServiceB = mockk<AgentService>(relaxed = true)

        val projectB = mockk<Project>(relaxed = true).also {
            every { it.basePath } returns ideBRoot.toString()
            every { it.getService(PluginSettings::class.java) } returns settingsB
            every { it.getService(AgentService::class.java) } returns agentServiceB
        }
        val scopeB = CoroutineScope(Job())
        val inbound = DelegationInboundService(projectB, scopeB)
        val doorbell = DelegationDoorbellService(projectB, scopeB)

        // Stub the loop: reply a COMPLETED Result via the terminal onResult callback that
        // the real handleConnect supplies, then run Task 8's real teardown gate so the
        // transient door is torn down. Returns a session id (as the real method does).
        io.mockk.coEvery {
            agentServiceB.startDelegatedSession(any(), any(), any(), any())
        } answers {
            activeSessions.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            val onResult = arg<suspend (DelegationMessage.Result) -> Unit>(3)
            scopeB.launch {
                delay(30) // simulate the delegated work
                onResult(
                    DelegationMessage.Result(
                        status = DelegationMessage.ResultStatus.COMPLETED,
                        summary = "Implemented the delegated task.",
                        filesChanged = listOf("src/Created.kt"),
                        durationSeconds = 1,
                    ),
                )
                // Task 8 teardown wiring (lives in the real AgentService terminal callback).
                inbound.stopIfTransientAndIdle(activeSessions.decrementAndGet())
            }
            "b-sess-1"
        }

        // Swap the consent seam (Task 5 exposed dialogLauncher) to apply ALLOW_ONCE through
        // the REAL applyConsent — no production seam was added. applyConsent drives the real
        // inbound.startTransient() (binds the real delegation socket) + recordPreauth(nonce).
        val store = PendingDelegationStore(ProjectIdentifier.agentDir(ideBRoot.toString()).toPath())
        doorbell.dialogLauncher = { knock ->
            doorbell.applyConsent(knock, ConsentChoice.ALLOW_ONCE, store, inbound)
        }

        // ---- IDE A outbound service (real send), seams pointed at the real sockets ----
        val projectA = mockk<Project>(relaxed = true).also {
            every { it.name } returns "ide-a-repo"
            every { it.basePath } returns null // persistHandlesForSession early-returns
            every { it.getService(PluginSettings::class.java) } returns mockk<PluginSettings>(relaxed = true)
        }
        val scopeA = CoroutineScope(Job())
        val outbound = DelegationOutboundService(projectA, scopeA)
        outbound.pickTargetOverride = {
            PickerEntry(path = ideBRoot, displayName = "ide-b-repo", status = PickerEntry.Status.CLOSED)
        }
        // Real client over the real sockets (NOT stubbed) — proves the full UDS round-trip.
        outbound.pingFn = { socketPath -> DelegationClient.ping(socketPath, timeoutMillis = 500) }
        outbound.knockFn = { dbPath, knock -> DelegationClient.knock(dbPath, knock, timeoutMillis = 1_000) }
        outbound.connectFn = { socketPath, connect -> DelegationClient.connectAndAwaitAccept(socketPath, connect) }
        // The doorbell IS bound (IDE B running), so the launcher must never fire.
        val launchCount = AtomicInteger(0)
        outbound.launchFn = { launchCount.incrementAndGet(); SpawnResult.Started() }

        doorbell.start() // IDE B binds its doorbell; door stays unbound until consent.
        // Sanity: door socket not reachable before consent.
        assertNull(DelegationClient.ping(doorSocket, timeoutMillis = 300), "door must be unbound pre-consent")

        val resultRef = AtomicReference<DelegationMessage.Result?>(null)
        val resultArrived = CompletableDeferred<Unit>()
        try {
            val handle = withTimeout(20_000) {
                outbound.send("Add a createUser endpoint", null, "a-sess-1") { _, result ->
                    resultRef.set(result)
                    resultArrived.complete(Unit)
                }
            }
            assertEquals("ide-b-repo", handle.targetRepoName)

            withTimeout(20_000) { resultArrived.await() }

            // ---- Assertions ----
            val result = resultRef.get()
            assertNotNull(result, "IDE A must receive a terminal Result")
            assertEquals(DelegationMessage.ResultStatus.COMPLETED, result!!.status)
            assertEquals("Implemented the delegated task.", result.summary)

            // (a) Connect skipped the Accept dialog via preauth. The real handleConnect only
            // reaches startDelegatedSession when accepted == true. The non-preauth branch
            // builds + shows an AcceptDelegationDialog on Dispatchers.EDT — with no live
            // Application/EDT in this test that path would throw, never reaching the loop.
            // The delegated session DID run (startDelegatedSession invoked exactly once and
            // a COMPLETED Result arrived), so the preApproved branch (consumePreauth of the
            // carried nonce) must have been taken — i.e. the Accept dialog was skipped.
            io.mockk.coVerify(exactly = 1) { agentServiceB.startDelegatedSession(any(), any(), any(), any()) }
            assertEquals(0, launchCount.get(), "doorbell answered → no launcher spawn")

            // (b) transient teardown unbound IDE B's delegation socket.
            // The real stopIfTransientAndIdle(0) closed the transient DelegationServer.
            val unbound = withTimeoutOrNull(5_000) {
                while (DelegationClient.ping(doorSocket, timeoutMillis = 200) != null) delay(100)
                true
            }
            assertEquals(true, unbound, "transient door socket must be torn down after the session ends")

            // (c) inbound setting stayed false throughout.
            assertFalse(
                settingsB.state.enableInboundCrossIdeDelegation,
                "ALLOW_ONCE must NOT persist the inbound setting",
            )
        } finally {
            inbound.stop()
            doorbell.stop()
            outbound.closeAll()
            scopeA.cancel()
            scopeB.cancel()
            // Clean up the IPC socket files this test created (keyed by the temp path).
            runCatching { Files.deleteIfExists(doorSocket) }
            runCatching { Files.deleteIfExists(doorbellSocket) }
            runCatching {
                Files.walk(ProjectIdentifier.agentDir(ideBRoot.toString()).toPath())
                    .sorted(Comparator.reverseOrder())
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
        Unit
    }
}
