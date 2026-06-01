package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Focused E2E for the controller-routed inbound accept path (Plan 2 Task 4).
 *
 * Drives the REAL [DelegationInboundService.handleConnect] over a real (temp-dir-keyed) UDS
 * socket, with the delegated-session leg faked via [DelegationInboundService.testDelegatedSessionStarter]
 * (the production starter is [com.workflow.orchestrator.agent.ui.AgentController.startDelegatedSession],
 * a UI service unavailable headless). Unlike the doorbell round-trip in [DelegationE2ETest], IDE-A
 * here connects directly with [DelegationClient.connectAndAwaitAccept] so the [DelegationMessage.AcceptResult]
 * and the terminal [DelegationMessage.Result] are DIRECTLY observable on the wire.
 *
 * Proves:
 *  - IDE-A receives `AcceptResult(accepted=true, bSessionId == the sid the starter delivered)`.
 *  - IDE-A receives a `Result` whose `summary` equals the FULL verbose completion text (no truncation).
 */
class DelegationReuseE2ETest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @AfterEach fun tearDown() = unmockkAll()

    @Test
    fun `controller-routed accept delivers bSessionId and an untruncated verbose result`(@TempDir tmp: Path) = runBlocking {
        val ideBRoot = Files.createDirectory(tmp.resolve("ide-b-project"))
        val doorSocket = DelegationPaths.socketFor(ideBRoot)

        val settingsB = PluginSettings()
        val projectB = mockk<Project>(relaxed = true).also {
            every { it.basePath } returns ideBRoot.toString()
            every { it.getService(PluginSettings::class.java) } returns settingsB
        }
        val scopeB = CoroutineScope(Job())
        val inbound = DelegationInboundService(projectB, scopeB)

        // Verbose, multi-line completion text — must survive the wire byte-for-byte.
        val verboseSummary = buildString {
            appendLine("Implemented the createUser endpoint as requested.")
            appendLine()
            appendLine("Changes:")
            repeat(60) { i -> appendLine("  - step ${i + 1}: refactored handler logic and added validation guard #$i") }
            appendLine()
            append("All ${"integration ".repeat(30)}tests pass.")
        }
        val deliveredSid = "b-reuse-sess-1"
        val capturedRequest = AtomicReference<String?>(null)

        // Fake the controller leg via the seam: deliver the sid through onSessionStarted (so
        // handleConnect emits AcceptResult(accepted=true, bSessionId=sid)), fire onResult with the
        // verbose COMPLETED Result, return STARTED.
        inbound.testDelegatedSessionStarter = DelegatedSessionStarter { request, _md, _reply, onResult, onSessionStarted, _onBusy ->
            capturedRequest.set(request)
            onSessionStarted?.invoke(deliveredSid)
            scopeB.launch {
                delay(20)
                onResult(
                    DelegationMessage.Result(
                        status = DelegationMessage.ResultStatus.COMPLETED,
                        summary = verboseSummary,
                        filesChanged = listOf("src/api/users.kt"),
                        durationSeconds = 7,
                    ),
                )
            }
            DelegatedStartOutcome.STARTED
        }

        // Bind IDE-B's delegation socket without persisting the inbound setting (mirrors the
        // consent "Allow once" path). Record a preauth nonce so handleConnect skips the EDT
        // Accept dialog (no live Application in a headless test).
        val nonce = "reuse-nonce-1"
        inbound.recordPreauth(nonce)
        inbound.startTransient()
        try {
            val pair = withTimeout(20_000) {
                DelegationClient.connectAndAwaitAccept(
                    doorSocket,
                    DelegationMessage.Connect(
                        delegatorIde = "ide-A-test",
                        delegatorRepo = "backend-test",
                        delegatorSessionId = "a-reuse-sess-1",
                        request = "Add a createUser endpoint",
                        preauthNonce = nonce,
                    ),
                )
            }
            assertNotNull(pair, "connectAndAwaitAccept must not return null on the accept path")
            val (channel, ack) = pair!!

            // (a) AcceptResult carries accepted=true and the sid the starter delivered.
            assertTrue(ack.accepted, "accept must be true on the controller-routed path")
            assertEquals(deliveredSid, ack.bSessionId, "bSessionId must equal the starter-delivered sid")

            // (b) the verbose Result round-trips untruncated.
            val result = withTimeout(20_000) {
                DelegationFraming.readFramed(channel, json)
            } as DelegationMessage.Result
            assertEquals(DelegationMessage.ResultStatus.COMPLETED, result.status)
            assertEquals(verboseSummary, result.summary, "verbose summary must survive the wire intact")
            assertEquals(listOf("src/api/users.kt"), result.filesChanged)

            // The starter was driven with the delegated request.
            assertEquals("Add a createUser endpoint", capturedRequest.get())

            channel.close()
        } finally {
            inbound.stop()
            scopeB.cancel()
            runCatching { Files.deleteIfExists(doorSocket) }
            runCatching {
                Files.walk(ProjectIdentifier.agentDir(ideBRoot.toString()).toPath())
                    .sorted(Comparator.reverseOrder())
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
        Unit
    }
}
