package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-contract test for the IDE-B delegation CONVERSATION narration cards (2026-06-01).
 *
 * On IDE-B's panel the agent's WORK already renders; this pins the four conversation
 * legs that were missing — (a) incoming task bubble, (b) question routed to the
 * delegator, (c) answer received back, (d) result sent back — plus the load-bearing
 * wiring (viewedSessionId set on the delegated start, the (d) wrapper still calling the
 * socket onResult, persistence as DELEGATION_CARD UiMessages).
 *
 * Behavioral coroutine/EDT tests are impractical headless (AgentController is a UI
 * service needing a live Application/EDT), so we pin source-level contracts — the same
 * pattern as BusyDelegationTopBarTest and the other delegation source-contract tests.
 *
 * CRITICAL NAMING RULE: the cards must use the delegator's REPO NAME (delegatorRepo),
 * never "IDE-A"/"IDE-B".
 */
class DelegationConversationNarrationTest {
    private fun src(rel: String): String {
        val d = System.getProperty("user.dir")
        val root = if (File("$d/src/main/kotlin").isDirectory) "$d/src/main/kotlin" else "$d/agent/src/main/kotlin"
        return File(root, rel).readText()
    }

    private fun controller() = src("com/workflow/orchestrator/agent/ui/AgentController.kt")
    private fun inbound() = src("com/workflow/orchestrator/agent/delegation/DelegationInboundService.kt")
    private fun service() = src("com/workflow/orchestrator/agent/AgentService.kt")
    private fun uiMessage() = src("com/workflow/orchestrator/agent/session/UiMessage.kt")

    // ── model ─────────────────────────────────────────────────────────────────

    @Test
    fun `UiMessage carries a DELEGATION_CARD say variant and a typed card payload`() {
        val s = uiMessage()
        assertTrue(s.contains("DELEGATION_CARD"), "UiSay must have a DELEGATION_CARD variant")
        assertTrue(s.contains("data class DelegationCardData"), "a typed DelegationCardData payload must exist")
        assertTrue(s.contains("enum class DelegationCardKind"), "card kind enum must exist")
        assertTrue(
            s.contains("ASKED") && s.contains("ANSWERED") && s.contains("RESULT"),
            "card kinds must distinguish ASKED / ANSWERED / RESULT",
        )
        assertTrue(s.contains("val delegatorRepo"), "card must carry the delegator REPO NAME")
        assertTrue(s.contains("val delegationCardData"), "UiMessage must expose delegationCardData")
    }

    // ── (a) incoming task ───────────────────────────────────────────────────────

    @Test
    fun `(a) delegated start sets viewedSessionId and pushes the incoming-task bubble with the repo name`() {
        val c = controller()
        // viewedSessionId set on the delegated start so gated pushes (banner + cards) fire.
        val onStarted = c.substringAfter("private fun onDelegatedSessionStarted(")
            .substringBefore("private fun wrapDelegatedOnResult(")
        assertTrue(
            onStarted.contains("viewedSessionId = sid"),
            "onDelegatedSessionStarted must set viewedSessionId = sid",
        )
        assertTrue(
            onStarted.contains("delegatedIncomingTaskText"),
            "the delegated start must push the incoming-task bubble via delegatedIncomingTaskText",
        )
        // runDelegatedNow wires the extracted handler on the fresh-start path.
        assertTrue(
            c.contains("onSessionStarted = { sid -> onDelegatedSessionStarted("),
            "runDelegatedNow must wire onDelegatedSessionStarted on the fresh delegated start",
        )
        // The shared text builder uses the repo name, never IDE-A/IDE-B.
        val svc = service()
        assertTrue(svc.contains("fun delegatedIncomingTaskText"), "AgentService must expose delegatedIncomingTaskText")
        val builder = svc.substringAfter("fun delegatedIncomingTaskText").substringBefore("fun mapLoopResultToDelegationResult")
        assertTrue(builder.contains("delegatorRepo"), "the incoming-task text must name the delegator REPO")
        assertFalse(builder.contains("IDE-A") || builder.contains("IDE-B"), "must NOT mention IDE-A/IDE-B")
        // The persisted uiMessageOverride uses the SAME builder so live + history match.
        assertTrue(
            svc.contains("text = delegatedIncomingTaskText(delegationMetadata, request)"),
            "the persisted uiMessageOverride must reuse delegatedIncomingTaskText",
        )
    }

    // ── (b) routed question ──────────────────────────────────────────────────────

    @Test
    fun `(b) routeQuestion narrates an Asked card via the controller using the repo name`() {
        val i = inbound()
        val routeBody = i.substringAfter("suspend fun routeQuestion(").substringBefore("fun deliverAnswer(")
        assertTrue(
            routeBody.contains("notifyDelegatedQuestionAsked"),
            "routeQuestion must narrate the Asked card",
        )
        assertTrue(i.contains("fun notifyDelegatedQuestionAsked"), "inbound must expose notifyDelegatedQuestionAsked")
        // Controller hook is null-safe + repo-named.
        val c = controller()
        assertTrue(c.contains("fun pushDelegatedQuestionAsked"), "controller must expose pushDelegatedQuestionAsked")
        val hook = c.substringAfter("fun pushDelegatedQuestionAsked").substringBefore("fun pushDelegatedAnswer")
        assertTrue(hook.contains("_appendDelegatedQuestion"), "must push the _appendDelegatedQuestion bridge")
        assertTrue(hook.contains("delegatorRepo"), "Asked card must carry the delegator REPO")
        assertTrue(hook.contains("viewedSessionId != sessionId"), "push must gate on the active panel session")
        assertTrue(hook.contains("appendDelegationCardToSession"), "Asked card must be persisted")
    }

    // ── (c) answer ────────────────────────────────────────────────────────────────

    @Test
    fun `(c) deliverAnswer narrates an answered card via the controller using the repo name`() {
        val i = inbound()
        val body = i.substringAfter("fun deliverAnswer(").substringBefore("fun hasPendingQuestion(")
        assertTrue(body.contains("notifyDelegatedAnswer"), "deliverAnswer must narrate the answered card on a winning resolve")
        assertTrue(body.contains("findDelegationMetadata"), "deliverAnswer must resolve the delegator repo")
        assertTrue(i.contains("fun notifyDelegatedAnswer"), "inbound must expose notifyDelegatedAnswer")
        val c = controller()
        assertTrue(c.contains("fun pushDelegatedAnswer"), "controller must expose pushDelegatedAnswer")
        val hook = c.substringAfter("fun pushDelegatedAnswer").substringBefore("fun pushDelegatedResult")
        assertTrue(hook.contains("_appendDelegatedAnswer"), "must push the _appendDelegatedAnswer bridge")
        assertTrue(hook.contains("delegatorRepo"), "answered card must carry the delegator REPO")
        assertTrue(hook.contains("flipAskedQuestionId"), "answered leg must flip the matching ASKED card")
    }

    // ── (d) result ──────────────────────────────────────────────────────────────

    @Test
    fun `(d) runDelegatedNow wraps onResult to render the result card AND still call the socket onResult`() {
        val c = controller()
        // The fresh-start path must feed startDelegatedSession the WRAPPED onResult.
        assertTrue(
            c.contains("onResult = wrapDelegatedOnResult(metadata, onResult)"),
            "runDelegatedNow must pass the wrapped onResult to startDelegatedSession",
        )
        // The wrapper renders the result card FIRST, then STILL calls the original socket onResult.
        val wrapper = c.substringAfter("private fun wrapDelegatedOnResult(").substringBefore("private fun runDelegatedNow(")
        assertTrue(wrapper.contains("pushDelegatedResult"), "the wrapper must render the result card first")
        assertTrue(wrapper.contains("onResult(result)"), "the wrapper must still call the socket onResult(result)")
        // Result card hook is repo-named + status-mapped + persisted.
        assertTrue(c.contains("fun pushDelegatedResult"), "controller must expose pushDelegatedResult")
        val hook = c.substringAfter("fun pushDelegatedResult").substringBefore("Push the active session")
        assertTrue(hook.contains("_appendDelegatedResult"), "must push the _appendDelegatedResult bridge")
        assertTrue(hook.contains("result.status.name"), "result card must map the DelegationMessage.Result status")
        assertTrue(hook.contains("result.durationSeconds"), "result card must include the duration")
        assertTrue(hook.contains("delegatorRepo"), "result card must carry the delegator REPO")
        assertTrue(hook.contains("appendDelegationCardToSession"), "result card must be persisted")
    }

    // ── persistence ──────────────────────────────────────────────────────────────

    @Test
    fun `cards are persisted as DELEGATION_CARD UiMessages and the ASKED card can flip on disk`() {
        val svc = service()
        assertTrue(svc.contains("fun appendDelegationCardToSession"), "AgentService must persist delegation cards")
        val m = svc.substringAfter("fun appendDelegationCardToSession").substringBefore("Persist the completion")
        assertTrue(m.contains("UiSay.DELEGATION_CARD"), "cards persist as DELEGATION_CARD UiMessages")
        assertTrue(m.contains("addToClineMessages"), "must use MessageStateHandler.addToClineMessages")
        assertTrue(m.contains("markDelegationQuestionAnswered"), "the ANSWERED leg must flip the persisted ASKED card")
        val handler = src("com/workflow/orchestrator/agent/session/MessageStateHandler.kt")
        assertTrue(
            handler.contains("fun markDelegationQuestionAnswered"),
            "MessageStateHandler must expose the ASKED-card flip",
        )
    }

    // ── no IDE-A / IDE-B leakage in the user-facing card strings ───────────────────

    @Test
    fun `no IDE-A or IDE-B literals leak into the delegation card payloads`() {
        val c = controller()
        // Inspect only the three card-push hooks (the delegation narration surface).
        val asked = c.substringAfter("fun pushDelegatedQuestionAsked").substringBefore("fun pushDelegatedAnswer")
        val answer = c.substringAfter("fun pushDelegatedAnswer").substringBefore("fun pushDelegatedResult")
        val result = c.substringAfter("fun pushDelegatedResult").substringBefore("Push the active session")
        for (hook in listOf(asked, answer, result)) {
            assertFalse(hook.contains("\"IDE-A\""), "card hooks must not embed an IDE-A literal")
            assertFalse(hook.contains("\"IDE-B\""), "card hooks must not embed an IDE-B literal")
        }
    }
}
