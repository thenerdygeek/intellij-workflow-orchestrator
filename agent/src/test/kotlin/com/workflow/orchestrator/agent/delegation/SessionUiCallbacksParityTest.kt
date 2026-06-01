package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Anti-regression parity lock for the cross-IDE delegation UI-callback wiring.
 *
 * **Root cause class this guards against:**
 * The delegated entry points (`AgentController.runDelegatedNow` / `runResumedDelegatedNow` →
 * `AgentService.startDelegatedSession` / `resumeDelegatedSession`) used to forward only ~7 of the
 * ~24 controller→loop UI callbacks that the normal path (`executeTaskInternal` → `executeTask`)
 * wires. Every callback added to `executeTask` after the delegated wrappers were written was
 * silently dropped on the delegated path (subagent cards, token counts, retry pill, compaction
 * overlay, model-switch chip, …).
 *
 * **Structural fix being locked in:**
 * A single [com.workflow.orchestrator.agent.ui.SessionUiCallbacks] bundle is the single source of
 * truth. [com.workflow.orchestrator.agent.ui.AgentController.buildSessionUiCallbacks] builds it
 * once; `executeTaskInternal`, `runDelegatedNow`, and `runResumedDelegatedNow` ALL source the
 * controller callbacks from that one builder. `startDelegatedSession` / `resumeDelegatedSession`
 * accept the bundle and forward every field into `executeTask` / `resumeSession`.
 *
 * **What this test enforces:**
 * For EVERY property declared on `SessionUiCallbacks`, both delegated AgentService entry points must
 * forward `<prop> = callbacks.<prop>` (or destructure the field by name) into their
 * `executeTask` / `resumeSession` call. If someone adds a callback to the bundle but forgets the
 * delegated path, the relevant assertion fails. Plus: the two controller entry points must pass the
 * SAME `buildSessionUiCallbacks()` builder that `executeTaskInternal` uses, so the paths can't
 * diverge at the source.
 *
 * Pure source-text pins (no platform harness) — same approach as
 * [DelegatedSubagentProgressWiringTest] / RunInvocationLeakTest.
 */
class SessionUiCallbacksParityTest {

    private fun agentRoot(): File {
        val d = System.getProperty("user.dir")
        return if (File("$d/src/main/kotlin").isDirectory) File("$d/src/main/kotlin")
        else File("$d/agent/src/main/kotlin")
    }

    private val serviceSource: String by lazy {
        File(agentRoot(), "com/workflow/orchestrator/agent/AgentService.kt").readText()
    }
    private val controllerSource: String by lazy {
        File(agentRoot(), "com/workflow/orchestrator/agent/ui/AgentController.kt").readText()
    }
    private val bundleSource: String by lazy {
        File(agentRoot(), "com/workflow/orchestrator/agent/ui/SessionUiCallbacks.kt").readText()
    }

    /**
     * Parse the property names declared in the `SessionUiCallbacks` primary constructor.
     * Matches `val <name>:` inside the data class parameter list.
     */
    private fun bundlePropertyNames(): List<String> {
        val start = bundleSource.indexOf("data class SessionUiCallbacks(")
        assertTrue(start >= 0, "SessionUiCallbacks.kt must declare `data class SessionUiCallbacks(`")
        // Constructor param block ends at the first `)` at column 0 (`)\n`) — the close of the ctor.
        val ctor = bundleSource.substring(start)
        val end = ctor.indexOf("\n)")
        assertTrue(end >= 0, "SessionUiCallbacks primary constructor must close with `\\n)`")
        val params = ctor.substring(0, end)
        val regex = Regex("""\bval\s+([A-Za-z0-9_]+)\s*:""")
        return regex.findAll(params).map { it.groupValues[1] }.toList()
    }

    private fun startDelegatedBody(): String =
        serviceSource.substringAfter("fun startDelegatedSession(").substringBefore("\n    fun ")

    private fun resumeDelegatedBody(): String =
        serviceSource.substringAfter("fun resumeDelegatedSession(").substringBefore("\n    fun ")

    private fun executeTaskInternalBody(): String =
        controllerSource.substringAfter("private suspend fun executeTaskInternal(")
            .substringBefore("\n    private fun onStreamChunk(")

    // ── The bundle exists and is non-trivial ─────────────────────────────

    @Test
    fun `SessionUiCallbacks enumerates the full controller-to-loop UI callback set`() {
        val props = bundlePropertyNames()
        // The known full set as of the structural fix. These are the controller-sourced UI
        // callbacks executeTaskInternal wires individually into executeTask.
        val required = listOf(
            "onStreamChunk", "onToolCall", "onComplete", "onRetry", "onCompactionState",
            "onModelSwitch", "onPlanResponse", "onPlanPartialContent", "onPlanModeToggled",
            "onPlanDiscarded", "onSubagentProgress", "onTokenUpdate", "onSessionStats",
            "onDebugLog", "onSessionStarted", "onSteeringDrained", "onAwaitingUserInput",
            "onUserInputReceived", "streamingEditCallback", "onHandoffProposed",
            "steeringQueue", "sessionApprovalStore",
        )
        val missing = required.filter { it !in props }
        assertTrue(
            missing.isEmpty(),
            "SessionUiCallbacks is missing required callback properties: $missing\nDeclared: $props"
        )
    }

    // ── SSOT: one builder used by all three entry points ─────────────────

    @Test
    fun `executeTaskInternal sources callbacks from buildSessionUiCallbacks`() {
        val body = executeTaskInternalBody()
        assertTrue(
            body.contains("buildSessionUiCallbacks()"),
            "executeTaskInternal must build the UI callbacks via the buildSessionUiCallbacks() SSOT " +
                "so the normal and delegated paths source callbacks from one place"
        )
    }

    @Test
    fun `runDelegatedNow passes the same buildSessionUiCallbacks SSOT`() {
        val body = controllerSource.substringAfter("private fun runDelegatedNow(")
            .substringBefore("\n    private fun ")
        assertTrue(
            body.contains("buildSessionUiCallbacks()"),
            "runDelegatedNow must pass buildSessionUiCallbacks() to startDelegatedSession — the SAME " +
                "builder executeTaskInternal uses, so a future callback flows to both paths"
        )
    }

    @Test
    fun `runResumedDelegatedNow passes the same buildSessionUiCallbacks SSOT`() {
        val body = controllerSource.substringAfter("private fun runResumedDelegatedNow(")
            .substringBefore("\n    private fun ")
        assertTrue(
            body.contains("buildSessionUiCallbacks()"),
            "runResumedDelegatedNow must pass buildSessionUiCallbacks() to resumeDelegatedSession"
        )
    }

    @Test
    fun `buildSessionUiCallbacks builder exists and constructs the bundle`() {
        assertTrue(
            controllerSource.contains("fun buildSessionUiCallbacks(")
                && controllerSource.contains("SessionUiCallbacks("),
            "AgentController must declare buildSessionUiCallbacks() that constructs a SessionUiCallbacks"
        )
    }

    // ── Parity: every bundle field is forwarded on the delegated start path ──

    @Test
    fun `startDelegatedSession accepts the SessionUiCallbacks bundle`() {
        val sig = serviceSource.substring(
            serviceSource.indexOf("fun startDelegatedSession("),
            serviceSource.indexOf("fun startDelegatedSession(") + 2000
        )
        assertTrue(
            sig.contains("SessionUiCallbacks"),
            "startDelegatedSession must accept a SessionUiCallbacks parameter (the bundle)"
        )
    }

    @Test
    fun `resumeDelegatedSession accepts the SessionUiCallbacks bundle`() {
        val sig = serviceSource.substring(
            serviceSource.indexOf("fun resumeDelegatedSession("),
            serviceSource.indexOf("fun resumeDelegatedSession(") + 2000
        )
        assertTrue(
            sig.contains("SessionUiCallbacks"),
            "resumeDelegatedSession must accept a SessionUiCallbacks parameter (the bundle)"
        )
    }

    /**
     * A bundle field is "forwarded" if the call site references `<bundle>.<field>`. The common case
     * is `<field> = <bundle>.<field>`; `onComplete` is intentionally CHAINED on the delegated path
     * (`onComplete = { result -> callbacks.onComplete(result); loopResultDeferred.complete(result) }`)
     * so we accept any `<ident>.<field>` reference inside the call as proof the bundle value flows.
     */
    private fun forwardsBundleField(call: String, prop: String): Boolean =
        Regex("""\b[A-Za-z0-9_]+\.$prop\b""").containsMatchIn(call)

    @Test
    fun `every SessionUiCallbacks field is forwarded into the delegated start executeTask call`() {
        val body = startDelegatedBody()
        val call = body.substring(body.indexOf("executeTask("))
        val props = bundlePropertyNames()
        val missing = props.filterNot { forwardsBundleField(call, it) }
        assertTrue(
            missing.isEmpty(),
            "startDelegatedSession's executeTask call drops these bundle callbacks: $missing\n" +
                "Each SessionUiCallbacks field must flow from the bundle (`<field> = <bundle>.<field>`, " +
                "or chained for onComplete)."
        )
    }

    @Test
    fun `every SessionUiCallbacks field is forwarded into the delegated resume resumeSession call`() {
        val body = resumeDelegatedBody()
        val call = body.substring(body.indexOf("resumeSession("))
        // resumeSession does not take attachments/uiMessageOverride, but it DOES take every UI
        // callback in the bundle. Forward all bundle fields.
        val props = bundlePropertyNames()
        val missing = props.filterNot { forwardsBundleField(call, it) }
        assertTrue(
            missing.isEmpty(),
            "resumeDelegatedSession's resumeSession call drops these bundle callbacks: $missing\n" +
                "Each SessionUiCallbacks field must flow from the bundle."
        )
    }
}
