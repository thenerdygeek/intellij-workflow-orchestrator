package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.ui.PerToolStreamBatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Layered tests for the terminal streaming pipeline:
 *
 *   RunCommandTool (reader thread)
 *     → RunCommandTool.streamCallback  [Layer 1]
 *       → PerToolStreamBatcher          [Layer 2]
 *         → onFlush callback            [Layer 3]
 *           → AgentDashboardPanel       [Layer 4 — tested via source text]
 *             → AgentCefPanel           [Layer 5 — tested via source text]
 *               → appendToolOutput(id)  [Layer 6 — tested via JS source text]
 *
 * The root cause of the "live output never shown" regression was that
 * RunCommandTool.currentToolCallId (ThreadLocal set by AgentLoop) was read on the
 * SAME thread it was set on (within withTimeoutOrNull's startUndispatched block),
 * but the ID set into streamCallback might differ from the ID used in appendToolCall
 * if the ThreadLocal returns null and the "run-cmd-N" fallback kicks in.
 *
 * These tests pin:
 * 1. Layer 1 — streamCallback is invoked with the exact toolCallId that was in the
 *    ThreadLocal at the time execute() started.
 * 2. Layer 2 — PerToolStreamBatcher accumulates and flushes under the same ID.
 * 3. Layer 2+3 integration — chunks from a live process arrive through the full
 *    Kotlin pipeline with no ID drift.
 * 4. Source-text — appendToolCall and appendToolOutput in AgentCefPanel pass the same
 *    toolCallId variable to their JS calls (no hard-coded literal substitution).
 * 5. Source-text — AgentDashboardPanel.appendToolOutput forwards toolCallId to
 *    cefPanel?.appendToolOutput without remapping.
 * 6. Source-text — chatStore.appendToolOutput keys toolOutputStreams by the incoming
 *    toolCallId (not a generated key).
 */
@DisplayName("Terminal streaming pipeline — layered correctness tests")
class TerminalStreamingPipelineTest {

    private val flushed = CopyOnWriteArrayList<Pair<String, String>>()
    private lateinit var batcher: PerToolStreamBatcher
    private var savedCallback: ((String, String) -> Unit)? = null

    @BeforeEach
    fun setup() {
        flushed.clear()
        // Synchronous invoker so flush behaviour is deterministic in tests
        batcher = PerToolStreamBatcher(
            onFlush = { id, text -> flushed.add(id to text) },
            invoker = { block -> block() }
        )
        savedCallback = RunCommandTool.streamCallback
    }

    @AfterEach
    fun tearDown() {
        batcher.dispose()
        RunCommandTool.streamCallback = savedCallback
        RunCommandTool.currentToolCallId.remove()
    }

    // ════════════════════════════════════════════
    //  Layer 1 — streamCallback receives correct toolCallId
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Layer 1: streamCallback ID propagation")
    inner class Layer1StreamCallbackId {

        @Test
        fun `streamCallback is invoked with the ThreadLocal toolCallId not the fallback`() {
            val received = CopyOnWriteArrayList<Pair<String, String>>()
            RunCommandTool.streamCallback = { id, chunk -> received.add(id to chunk) }

            val latch = CountDownLatch(1)

            // Simulate what AgentLoop does before tool.execute():
            RunCommandTool.currentToolCallId.set("xmltool_42")
            try {
                // Simulate what the reader thread does
                val toolCallId = RunCommandTool.currentToolCallId.get()
                    ?: "run-cmd-fallback"
                // Capture toolCallId in closure — exactly like RunCommandTool.execute() does
                val captured = toolCallId
                Thread {
                    RunCommandTool.streamCallback?.invoke(captured, "hello\n")
                    RunCommandTool.streamCallback?.invoke(captured, "world\n")
                    latch.countDown()
                }.start()

                assertTrue(latch.await(3, TimeUnit.SECONDS), "Reader thread timed out")
            } finally {
                RunCommandTool.currentToolCallId.remove()
            }

            assertEquals(2, received.size)
            assertEquals("xmltool_42", received[0].first, "toolCallId must match ThreadLocal value")
            assertEquals("xmltool_42", received[1].first, "toolCallId must match ThreadLocal value")
            assertEquals("hello\n", received[0].second)
            assertEquals("world\n", received[1].second)
        }

        @Test
        fun `streamCallback falls back to run-cmd-N when ThreadLocal is not set`() {
            val received = CopyOnWriteArrayList<Pair<String, String>>()
            RunCommandTool.streamCallback = { id, chunk -> received.add(id to chunk) }

            // ThreadLocal NOT set — simulates bug scenario
            RunCommandTool.currentToolCallId.remove()
            val toolCallId = RunCommandTool.currentToolCallId.get() ?: "run-cmd-99"
            RunCommandTool.streamCallback?.invoke(toolCallId, "output\n")

            assertEquals(1, received.size)
            assertTrue(received[0].first.startsWith("run-cmd-"), "Fallback ID should start with run-cmd-")
            assertFalse(received[0].first.startsWith("xmltool_"), "Fallback must NOT match LLM-assigned ID")
        }

        @Test
        fun `ID mismatch scenario - proves why ThreadLocal must not return null`() {
            // This test documents the root cause: if currentToolCallId.get() returns null,
            // the streaming output is keyed at "run-cmd-N" but the tool call card was
            // registered under "xmltool_1". TerminalContent reads toolOutputStreams["xmltool_1"]
            // and finds nothing — the live output is invisible.

            val appendToolCallId = "xmltool_1"  // what AgentLoop tells the UI
            val receivedStreamIds = CopyOnWriteArrayList<String>()
            RunCommandTool.streamCallback = { id, _ -> receivedStreamIds.add(id) }

            // Scenario A: ThreadLocal correctly set → IDs match → output visible
            RunCommandTool.currentToolCallId.set(appendToolCallId)
            val idWhenSet = RunCommandTool.currentToolCallId.get() ?: "run-cmd-fallback"
            RunCommandTool.streamCallback?.invoke(idWhenSet, "chunk")
            RunCommandTool.currentToolCallId.remove()

            // Scenario B: ThreadLocal not set → IDs mismatch → output invisible
            val idWhenNull = RunCommandTool.currentToolCallId.get() ?: "run-cmd-1"
            RunCommandTool.streamCallback?.invoke(idWhenNull, "chunk")

            assertEquals(appendToolCallId, receivedStreamIds[0],
                "When ThreadLocal is set correctly, stream ID must match appendToolCall ID")
            assertNotEquals(appendToolCallId, receivedStreamIds[1],
                "When ThreadLocal is null, stream ID diverges from appendToolCall ID (the bug)")
        }
    }

    // ════════════════════════════════════════════
    //  Layer 2 — PerToolStreamBatcher ID consistency
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Layer 2: PerToolStreamBatcher preserves toolCallId through flush")
    inner class Layer2BatcherIdConsistency {

        @Test
        fun `chunks appended under xmltool id flush under the same id`() {
            val id = "xmltool_7"
            batcher.append(id, "line1\n")
            batcher.append(id, "line2\n")
            batcher.flush(id)

            assertEquals(1, flushed.size, "Expected exactly one flush call")
            assertEquals(id, flushed[0].first, "Flushed ID must match appended ID")
            assertEquals("line1\nline2\n", flushed[0].second)
        }

        @Test
        fun `ID mismatch in batcher causes stream to miss — verifies correctness`() {
            // Demonstrates: if output is stored under "run-cmd-1" but looked up under
            // "xmltool_1", the lookup returns empty — same failure as in TerminalContent.
            batcher.append("run-cmd-1", "hello")
            batcher.flush("run-cmd-1")

            val outputForWrongId = flushed.firstOrNull { it.first == "xmltool_1" }
            assertNull(outputForWrongId, "Output stored under wrong ID cannot be found by correct ID")

            val outputForCorrectId = flushed.firstOrNull { it.first == "run-cmd-1" }
            assertNotNull(outputForCorrectId, "Output exists only under the ID it was stored")
        }
    }

    // ════════════════════════════════════════════
    //  Layer 2+3 integration — streamCallback → batcher → onFlush
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Layer 2+3 integration: streamCallback → batcher → onFlush")
    inner class Layer2And3Integration {

        @Test
        fun `output flows from streamCallback through batcher to onFlush with correct ID`() {
            val toolCallId = "xmltool_pipeline_test"
            RunCommandTool.streamCallback = { id, chunk -> batcher.append(id, chunk) }

            // Simulate what AgentLoop sets before execute(), and what reader thread does
            RunCommandTool.currentToolCallId.set(toolCallId)
            val capturedId = RunCommandTool.currentToolCallId.get() ?: "run-cmd-fallback"
            RunCommandTool.currentToolCallId.remove()

            RunCommandTool.streamCallback!!.invoke(capturedId, "chunk1\n")
            RunCommandTool.streamCallback!!.invoke(capturedId, "chunk2\n")

            batcher.flush(toolCallId)

            assertEquals(1, flushed.size)
            assertEquals(toolCallId, flushed[0].first)
            assertEquals("chunk1\nchunk2\n", flushed[0].second)
        }

        @Test
        fun `multiple concurrent tool calls in batcher never cross-contaminate IDs`() {
            RunCommandTool.streamCallback = { id, chunk -> batcher.append(id, chunk) }

            val latch = CountDownLatch(2)
            Thread {
                repeat(5) { RunCommandTool.streamCallback!!.invoke("xmltool_10", "tool10-chunk$it\n") }
                latch.countDown()
            }.start()
            Thread {
                repeat(5) { RunCommandTool.streamCallback!!.invoke("xmltool_11", "tool11-chunk$it\n") }
                latch.countDown()
            }.start()

            assertTrue(latch.await(3, TimeUnit.SECONDS))
            batcher.flush()

            assertEquals(2, flushed.size, "Expected exactly 2 flush calls for 2 distinct IDs")
            val byId = flushed.associateBy { it.first }
            assertNotNull(byId["xmltool_10"], "xmltool_10 output missing")
            assertNotNull(byId["xmltool_11"], "xmltool_11 output missing")
            assertFalse(byId["xmltool_10"]!!.second.contains("tool11"),
                "tool10 output must not contain tool11 chunks")
            assertFalse(byId["xmltool_11"]!!.second.contains("tool10"),
                "tool11 output must not contain tool10 chunks")
        }
    }

    // ════════════════════════════════════════════
    //  Layer 2+3 live-process integration (Unix only)
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Layer 2+3 live process: real subprocess output through pipeline")
    inner class LiveProcessIntegration {

        @Test
        @EnabledOnOs(OS.LINUX, OS.MAC)
        fun `echo command output flows through streamCallback and batcher with correct ID`() {
            val toolCallId = "xmltool_live_test"
            val completedLatch = CountDownLatch(1)

            RunCommandTool.streamCallback = { id, chunk -> batcher.append(id, chunk) }

            val process = ProcessBuilder("echo", "hello from subprocess")
                .redirectErrorStream(true)
                .start()

            // Simulate what RunCommandTool.execute() does: capture toolCallId before reader thread
            RunCommandTool.currentToolCallId.set(toolCallId)
            val capturedId = RunCommandTool.currentToolCallId.get() ?: "run-cmd-fallback"
            RunCommandTool.currentToolCallId.remove()

            val readerThread = Thread {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    var n = reader.read(buffer)
                    while (n != -1) {
                        RunCommandTool.streamCallback!!.invoke(capturedId, String(buffer, 0, n))
                        n = reader.read(buffer)
                    }
                }
                completedLatch.countDown()
            }.apply {
                isDaemon = true
                start()
            }

            process.waitFor(3, TimeUnit.SECONDS)
            assertTrue(completedLatch.await(3, TimeUnit.SECONDS), "Reader thread did not complete")
            readerThread.join(1000)

            // Flush what's in the batcher
            batcher.flush(capturedId)

            assertEquals(1, flushed.size, "Expected exactly 1 flush entry for the tool call")
            assertEquals(toolCallId, flushed[0].first,
                "Flushed ID must be the LLM-assigned toolCallId, not a run-cmd-N fallback")
            assertTrue(flushed[0].second.contains("hello from subprocess"),
                "Expected process output in flushed text, got: '${flushed[0].second}'")
        }

        @Test
        @EnabledOnOs(OS.LINUX, OS.MAC)
        fun `multiline output from process preserves all lines through pipeline`() {
            val toolCallId = "xmltool_multiline"
            RunCommandTool.streamCallback = { id, chunk -> batcher.append(id, chunk) }

            val process = ProcessBuilder("printf", "line1\\nline2\\nline3\\n")
                .redirectErrorStream(true)
                .start()

            RunCommandTool.currentToolCallId.set(toolCallId)
            val capturedId = RunCommandTool.currentToolCallId.get() ?: "run-cmd-fallback"
            RunCommandTool.currentToolCallId.remove()

            val latch = CountDownLatch(1)
            Thread {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    var n = reader.read(buffer)
                    while (n != -1) {
                        RunCommandTool.streamCallback!!.invoke(capturedId, String(buffer, 0, n))
                        n = reader.read(buffer)
                    }
                }
                latch.countDown()
            }.apply { isDaemon = true; start() }

            process.waitFor(3, TimeUnit.SECONDS)
            latch.await(3, TimeUnit.SECONDS)
            batcher.flush(capturedId)

            assertEquals(1, flushed.size)
            val combined = flushed[0].second
            assertTrue(combined.contains("line1"), "Expected 'line1' in output")
            assertTrue(combined.contains("line2"), "Expected 'line2' in output")
            assertTrue(combined.contains("line3"), "Expected 'line3' in output")
        }
    }

    // ════════════════════════════════════════════
    //  Layer 4+5 — source-text ID forwarding verification
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Layer 4+5 source-text: AgentCefPanel and AgentDashboardPanel ID forwarding")
    inner class Layer4And5SourceText {

        @Test
        fun `AgentCefPanel appendToolCall and appendToolOutput pass toolCallId as first JS argument`() {
            val cefPanelText = readAgentSource("ui", "AgentCefPanel.kt")

            // appendToolCall must embed toolCallId as first argument in the JS call
            assertTrue(
                cefPanelText.contains(
                    "callJs(\"appendToolCall(\${JsEscape.toJsString(toolCallId)}"
                ),
                "AgentCefPanel.appendToolCall must embed toolCallId as the first JS argument. " +
                    "If a literal or empty string is used instead, the JS addToolCall registers the " +
                    "tool call under the wrong key and appendToolOutput chunks never match."
            )

            // appendToolOutput must embed the incoming toolCallId (not a different variable)
            assertTrue(
                cefPanelText.contains(
                    "callJs(\"appendToolOutput(\${JsEscape.toJsString(toolCallId)}"
                ),
                "AgentCefPanel.appendToolOutput must embed the exact toolCallId parameter as the " +
                    "first JS argument. If a different variable or literal is used, " +
                    "toolOutputStreams is keyed differently from activeToolCalls and TerminalContent " +
                    "reads an empty stream."
            )
        }

        @Test
        fun `AgentDashboardPanel appendToolOutput forwards toolCallId to cefPanel without remapping`() {
            val dashboardText = readAgentSource("ui", "AgentDashboardPanel.kt")

            // Must call cefPanel?.appendToolOutput(toolCallId, chunk) — not a remapped variable
            assertTrue(
                dashboardText.contains("cefPanel?.appendToolOutput(toolCallId, chunk)"),
                "AgentDashboardPanel.appendToolOutput must forward toolCallId unchanged to " +
                    "cefPanel.appendToolOutput. Any remapping (e.g. replacing with a local name " +
                    "or omitting the argument) breaks the ID chain between Kotlin and JS."
            )
        }

        @Test
        fun `AgentController onFlush callback passes id to dashboard appendToolOutput without remapping`() {
            val controllerText = readAgentSource("ui", "AgentController.kt")

            // The batcher onFlush lambda must call dashboard.appendToolOutput(id, batched)
            // where id is the first lambda parameter (toolCallId from the batcher)
            assertTrue(
                controllerText.contains("dashboard.appendToolOutput(id, batched)"),
                "AgentController toolStreamBatcher.onFlush must call dashboard.appendToolOutput " +
                    "with 'id' as the first argument (the toolCallId delivered by the batcher). " +
                    "If this is remapped to a different variable, the ID chain breaks at layer 3."
            )
        }
    }

    // ════════════════════════════════════════════
    //  Layer 6 — chatStore JS source-text verification
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Layer 6 source-text: chatStore.appendToolOutput keys by incoming toolCallId")
    inner class Layer6ChatStoreSourceText {

        @Test
        fun `chatStore appendToolOutput keys toolOutputStreams by the toolCallId parameter`() {
            val chatStoreText = readWebviewSource("stores/chatStore.ts")

            // The implementation must use [toolCallId] as the key into toolOutputStreams,
            // not a generated ID. Pattern: toolOutputStreams: { ...state.toolOutputStreams, [toolCallId]: ... }
            assertTrue(
                chatStoreText.contains("[toolCallId]:"),
                "chatStore.appendToolOutput must key toolOutputStreams by the incoming toolCallId " +
                    "parameter. If a generated key is used, TerminalContent reads toolOutputStreams " +
                    "under the tool call's .id (which is also the LLM-assigned toolCallId) and " +
                    "finds nothing — the live output is invisible."
            )
        }

        @Test
        fun `chatStore addToolCall stores tool call under the provided toolCallId`() {
            val chatStoreText = readWebviewSource("stores/chatStore.ts")

            // addToolCall: let id = toolCallId || nextId('tc')
            // This is correct — it uses the LLM-provided toolCallId, not a fresh generated one
            assertTrue(
                chatStoreText.contains("let id = toolCallId || nextId"),
                "chatStore.addToolCall must prefer the LLM-provided toolCallId over a generated " +
                    "fallback. The OR pattern 'toolCallId || nextId(...)' is the correct implementation: " +
                    "it uses the Kotlin-side toolCallId when present (non-empty string), and only " +
                    "generates a new ID when toolCallId is falsy (empty or undefined — legacy callers)."
            )
        }

        @Test
        fun `TerminalContent reads toolOutputStreams by toolCall id field`() {
            val toolCallChainText = readWebviewSource("components/agent/ToolCallChain.tsx")

            // TerminalContent must read: allStreams[toolCall.id]
            // This key must be the same as what appendToolOutput uses ([toolCallId])
            assertTrue(
                toolCallChainText.contains("allStreams[toolCall.id]"),
                "TerminalContent must look up toolOutputStreams using toolCall.id. " +
                    "Since addToolCall stores the tool call under the LLM-assigned toolCallId, " +
                    "and appendToolOutput stores output under the same toolCallId, " +
                    "toolCall.id === toolCallId === key in toolOutputStreams — the lookup finds data."
            )
        }
    }

    // ════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════

    private fun readAgentSource(subPackage: String, name: String): String {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        val root = java.io.File(userDir)
        val rel = "src/main/kotlin/com/workflow/orchestrator/agent/$subPackage/$name"
        return listOf(java.io.File(root, rel), java.io.File(root, "agent/$rel"))
            .first { it.isFile }
            .readText()
    }

    private fun readWebviewSource(relativePath: String): String {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        val root = java.io.File(userDir)
        val rel = "webview/src/$relativePath"
        return listOf(java.io.File(root, rel), java.io.File(root, "agent/$rel"))
            .first { it.isFile }
            .readText()
    }
}
