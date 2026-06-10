package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-contract guard for the lazy-Agent-tab controller resolution in
 * [DelegationInboundService] (W4-B1 follow-up; companion to perf audit P0-6).
 *
 * With lazy extension tabs, the IDE-B `AgentController` only exists in
 * `AgentControllerRegistry` AFTER the Agent tab is SELECTED (materialization runs
 * synchronously inside the tool-window factory's selectionChanged listener). The old
 * shape in `handleConnect` / `handleChannelResume` read the registry right after
 * `toolWindow.activate { … }` RETURNED — racing the activate callback (the platform may
 * run it via invokeLater) and deterministically returning null on a cold IDE-B:
 *
 * - `handleConnect` had already consumed the single-use preauth nonce, so the premature
 *   null burned the consent → FAILED reply → IDE-A `TargetNotReachable` → unattended
 *   auto-launch delegation defeated 100%.
 * - `handleChannelResume` misreported a LIVE resumable handle as
 *   `ide_b_agent_unavailable` → mapped to `DelegationException.Expired` on IDE-A.
 *
 * The fix: a shared `resolveControllerViaAgentTab()` helper reads the registry INSIDE
 * the activate callback, after `setSelectedContent`, bridges the value out via
 * `CompletableDeferred`, and awaits with a bounded timeout — keeping the existing
 * `ide_b_agent_unavailable` reply as the timeout fallback. The activate path needs a
 * live ToolWindowManager + EDT and is not reachable from headless tests (every
 * delegation test injects `testDelegatedSessionStarter` / `testDelegatedResumeStarter`),
 * so these source-text pins lock the resolution order (DialogModalityContractTest
 * precedent).
 */
class DelegationControllerResolutionContractTest {

    // ── Source location ──────────────────────────────────────────────────────

    private fun agentMainRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir system property is not set")
        val root = File(userDir)
        val moduleRooted = File(root, "src/main/kotlin") // user.dir == <repo>/agent
        val repoRooted = File(root, "agent/src/main/kotlin") // user.dir == <repo>
        return when {
            moduleRooted.isDirectory -> moduleRooted
            repoRooted.isDirectory -> repoRooted
            else -> error("agent main sources not found at either layout; user.dir=$userDir")
        }
    }

    private fun inboundSource(): String {
        val f = File(agentMainRoot(), "com/workflow/orchestrator/agent/delegation/DelegationInboundService.kt")
        assertTrue(f.isFile, "Expected source file not found: ${f.absolutePath} — module layout may have changed.")
        return f.readText()
    }

    /** Slice of the resolver helper body (decl to the next top-level member decl). */
    private fun resolverSlice(source: String): String {
        val decl = "private suspend fun resolveControllerViaAgentTab("
        val start = source.indexOf(decl)
        assertTrue(
            start >= 0,
            "resolveControllerViaAgentTab not found — the shared lazy-tab controller resolver " +
                "must exist in DelegationInboundService (see class KDoc of this test)."
        )
        val rest = source.substring(start)
        val end = rest.indexOf("\n    companion object")
        return if (end >= 0) rest.substring(0, end) else rest
    }

    // ── 1. Registry read is INSIDE the activate callback, after tab selection ─

    @Test
    fun `resolver reads the registry inside the activate callback after selecting the Agent tab`() {
        val body = resolverSlice(inboundSource())
        val activateIdx = body.indexOf("toolWindow.activate {")
        val selectIdx = body.indexOf("setSelectedContent(")
        val registryIdx = body.indexOf("AgentControllerRegistry")
        // Search from the activate callback onward: the no-tool-window guard branch has an
        // earlier resolved.complete(null) that is irrelevant to the ordering being pinned.
        val completeIdx = body.indexOf("resolved.complete(", startIndex = maxOf(activateIdx, 0))
        assertTrue(activateIdx >= 0, "resolver must activate the Workflow tool window.")
        assertTrue(
            selectIdx > activateIdx,
            "resolver must select the Agent tab INSIDE the activate callback — selection is what " +
                "materializes the lazy Agent panel and registers the controller."
        )
        assertTrue(
            registryIdx > selectIdx,
            "resolver must read AgentControllerRegistry AFTER setSelectedContent (inside the " +
                "activate callback) — reading after activate() returns races the callback and " +
                "returns null on a cold IDE-B."
        )
        assertTrue(
            completeIdx in (activateIdx + 1) until registryIdx,
            "resolver must bridge the registry value out via a CompletableDeferred completed " +
                "inside the activate callback."
        )
        assertTrue(
            body.contains("withTimeoutOrNull(CONTROLLER_RESOLVE_TIMEOUT_MS)"),
            "resolver must await the deferred with a bounded timeout so callers fall back to the " +
                "ide_b_agent_unavailable reply when the callback never runs (headless/no tool window)."
        )
    }

    // ── 2. Both production seams route through the resolver ─────────────────

    @Test
    fun `handleConnect and handleChannelResume both resolve via the shared helper`() {
        val source = inboundSource()
        val calls = Regex(Regex.escape("resolveControllerViaAgentTab()")).findAll(source).count()
        assertTrue(
            calls >= 2,
            "Expected BOTH production seams (testDelegatedSessionStarter ?: … in handleConnect " +
                "and testDelegatedResumeStarter ?: … in handleChannelResume) to call " +
                "resolveControllerViaAgentTab(); found $calls call site(s)."
        )
        val connectSeam = source.substringAfter("testDelegatedSessionStarter ?:")
            .substringBefore("DelegatedSessionStarter {")
        assertTrue(
            connectSeam.contains("resolveControllerViaAgentTab()"),
            "handleConnect's starter seam must resolve the controller via resolveControllerViaAgentTab()."
        )
        val resumeSeam = source.substringAfter("testDelegatedResumeStarter ?:")
            .substringBefore("DelegatedResumeStarter {")
        assertTrue(
            resumeSeam.contains("resolveControllerViaAgentTab()"),
            "handleChannelResume's starter seam must resolve the controller via resolveControllerViaAgentTab()."
        )
    }

    // ── 3. The racy read-after-activate shape must not come back ────────────

    @Test
    fun `no registry read immediately follows an activate block close`() {
        val source = inboundSource()
        val oldShape = Regex("""\}\s*\n\s*com\.workflow\.orchestrator\.agent\.ui\.AgentControllerRegistry""")
        assertFalse(
            oldShape.containsMatchIn(source),
            "Found a registry read on the line after a block close — this is the old racy " +
                "read-after-activate shape (controller resolved OUTSIDE the activate callback). " +
                "Route it through resolveControllerViaAgentTab() instead."
        )
    }
}
