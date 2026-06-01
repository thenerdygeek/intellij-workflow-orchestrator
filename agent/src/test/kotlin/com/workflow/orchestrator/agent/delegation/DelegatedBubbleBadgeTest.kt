package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.session.DelegationMetadata
import com.workflow.orchestrator.agent.session.UiSay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Part 2 (#2) — the cross-IDE delegation marker moved from a TEXT prefix on the
 * incoming-task bubble to a per-bubble flag (`delegated`/`delegatorRepo`) the
 * webview renders as a tint + accent stripe + "delegated · {repo}" pill.
 *
 * These pins guard the Kotlin half of that change:
 *  - `delegatedIncomingTaskText` no longer prepends "[⬇ Delegated task · …]".
 *  - the persisted leg-a UiMessage carries the delegated flag + delegator REPO.
 *  - `AcceptDelegationDialog` shows the delegator REPO, not the raw "ide-$pid" id.
 *  - `AgentController.onDelegatedSessionStarted` flags leg-a at creation via
 *    `startSessionDelegated(text, repo)`.
 */
class DelegatedBubbleBadgeTest {

    private val metadata = DelegationMetadata(
        delegatorIde = "ide-456",
        delegatorRepo = "team/backend-service",
        delegatorSessionId = "sess-a-1",
        startedAt = 1_000L,
    )
    private val request = "Investigate the flaky test in PaymentServiceTest"

    @Test
    fun `incoming task text no longer carries the delegated prefix`() {
        val text = AgentService.delegatedIncomingTaskText(metadata, request)
        assertFalse(text.contains("[⬇ Delegated task"), "stale TEXT prefix must be dropped")
        assertFalse(text.contains("Delegated task · from"), "stale TEXT prefix must be dropped")
        assertEquals(request, text, "the bubble text is now just the verbatim task")
    }

    @Test
    fun `persisted leg-a UiMessage is flagged delegated and carries the delegator repo`() {
        val msg = AgentService.delegatedIncomingUiMessageOverride(metadata, request)
        assertEquals(UiSay.USER_MESSAGE, msg.say)
        assertTrue(msg.delegated, "leg-a bubble must persist the delegated flag for history render")
        assertEquals("team/backend-service", msg.delegatorRepo, "pill must show the repo, not the ide id")
        assertEquals(request, msg.text)
        // Never leak the raw "ide-$pid" identifier through the persisted bubble.
        assertFalse((msg.text ?: "").contains("ide-456"))
    }

    @Test
    fun `AcceptDelegationDialog shows the delegator repo not the ide id`() {
        val src = File("src/main/kotlin/com/workflow/orchestrator/agent/delegation/ui/AcceptDelegationDialog.kt")
            .readText()
        // The dialog must surface the REPO, never the raw "ide-$pid" identifier.
        assertFalse(
            src.contains("connect.delegatorIde"),
            "AcceptDelegationDialog must not render connect.delegatorIde (the raw ide-\$pid)",
        )
        assertTrue(
            src.contains("connect.delegatorRepo"),
            "AcceptDelegationDialog must render the delegator REPO",
        )
    }

    @Test
    fun `onDelegatedSessionStarted flags leg-a at creation via startSessionDelegated`() {
        val src = File("src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt")
            .readText()
        // The delegated start must use the delegated-aware start so the leg-a bubble
        // is flagged AT CREATION (not dependent on banner push ordering), passing the repo.
        assertTrue(
            src.contains("dashboard.startSessionDelegated("),
            "delegated start must route through startSessionDelegated(text, repo)",
        )
        assertTrue(
            src.contains("metadata.delegatorRepo"),
            "delegated start must pass the delegator REPO into the bubble flag",
        )
    }
}
