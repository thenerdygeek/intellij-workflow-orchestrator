package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.SonarService
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration test for the monitor RESUME RE-ARM pipeline.
 *
 * Composes the real production pieces (MonitorPersistence, MonitorSourceFactory,
 * MonitorHandle, MonitorPool, MonitorManager) headlessly — without an IntelliJ
 * Application or AgentService — to verify the complete re-arm flow.
 *
 * Cases:
 *  1. Same-id re-arm across source types — handle bgId equals persisted spec id.
 *  2. Dormancy reset — forget() restores wake-eligibility after markAllDormant().
 *  3. New-chat clears, resume does not — clear vs. double-load semantics.
 *  4. MaxConcurrentReached during re-arm is survivable — pipeline continues, pool unchanged.
 *  5. Factory Failed spec is skipped — not registered, no throw.
 *  6. Source-contract pin — AgentService.reArmMonitors contains the required call sites in order;
 *     resumeSession calls reArmMonitors; killBackgroundsOnTransition calls clearPersistedMonitors.
 */
class MonitorReArmIntegrationTest {

    @TempDir
    lateinit var agentDir: Path

    private val project: Project = mockk(relaxed = true)
    private lateinit var cs: CoroutineScope
    private lateinit var poolScope: CoroutineScope
    private lateinit var pool: MonitorPool
    private lateinit var persistence: MonitorPersistence

    // ── stub providers (services all configured) ──────────────────────────

    private val bamboo: BambooService = mockk(relaxed = true)
    private val bitbucket: BitbucketService = mockk(relaxed = true)
    private val eventBusFlow = MutableSharedFlow<WorkflowEvent>()

    private val realBambooProvider: (Project) -> BambooService? = { bamboo }
    private val realBitbucketProvider: (Project) -> BitbucketService? = { bitbucket }
    private val nullJiraProvider: (Project) -> JiraService? = { null }
    private val nullSonarProvider: (Project) -> SonarService? = { null }
    private val realEventBusProvider: (Project) -> kotlinx.coroutines.flow.SharedFlow<WorkflowEvent>? = { eventBusFlow }

    // ── setup / teardown ──────────────────────────────────────────────────

    @BeforeEach
    fun setup() {
        cs = CoroutineScope(Dispatchers.IO + SupervisorJob())
        poolScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        pool = MonitorPool(project, poolScope)
        persistence = MonitorPersistence(agentDir)
    }

    @AfterEach
    fun teardown() {
        pool.dispose()
        cs.cancel()
        poolScope.cancel()
    }

    // ─── production-pipeline helper ──────────────────────────────────────────────

    /**
     * Replicates the production [AgentService.reArmMonitors] pipeline:
     * persistence.load → factory.build (stub providers) → MonitorHandle → pool.register
     * (catch MaxConcurrentReached → src.stop → continue) → ensureMonitorManager →
     * manager.forget → intentionally does NOT call src.start() to avoid launching
     * real pollers or shell processes in tests.
     *
     * @param manager Optional [MonitorManager] to call forget() on; if null forget is skipped
     *                (simulates first-ever re-arm with no stale dormancy).
     * @return list of (specId, registered) outcomes for each spec found in persistence.
     */
    private fun reArm(
        sessionId: String,
        persistence: MonitorPersistence,
        pool: MonitorPool,
        project: Project,
        cs: CoroutineScope,
        manager: MonitorManager? = null,
        bambooProvider: (Project) -> BambooService? = realBambooProvider,
        bitbucketProvider: (Project) -> BitbucketService? = realBitbucketProvider,
    ): List<Pair<String, Boolean>> = runBlocking {
        val specs = persistence.load(sessionId)
        val outcomes = mutableListOf<Pair<String, Boolean>>()
        for (spec in specs) {
            val result = MonitorSourceFactory.build(
                spec = spec,
                project = project,
                cs = cs,
                bambooProvider = bambooProvider,
                bitbucketProvider = bitbucketProvider,
                jiraProvider = nullJiraProvider,
                sonarProvider = nullSonarProvider,
                eventBusProvider = realEventBusProvider,
                onShellExit = null,
            )
            when (result) {
                is MonitorSourceFactory.BuildResult.Failed -> {
                    outcomes += spec.id to false
                }
                is MonitorSourceFactory.BuildResult.Built -> {
                    val src = result.source
                    val handle = MonitorHandle(src, sessionId, System.currentTimeMillis())
                    val registered = try {
                        pool.register(sessionId, handle)
                        // Mirror production: reset stale dormancy on the manager for this id.
                        manager?.forget(spec.id)
                        // NOTE: src.start() intentionally omitted — no live pollers in tests.
                        true
                    } catch (e: MonitorPool.MaxConcurrentReached) {
                        src.stop()
                        false
                    }
                    outcomes += spec.id to registered
                }
            }
        }
        outcomes
    }

    // ─── Case 1: Same-id re-arm across source types ──────────────────────────────

    /**
     * Persist a bamboo spec and a pull_request spec with distinct ids.
     * Run the re-arm pipeline.  Each resulting MonitorHandle.bgId must equal the
     * persisted spec id exactly — proving same-id re-arm (not new-UUID generation).
     */
    @Test
    fun `case1 - same-id re-arm handle bgId equals persisted spec id for bamboo and pull_request`() {
        val sid = "session-rearm-case1"

        val bambooSpec = MonitorSpec(
            id = "bamboo-aabbccdd",
            sourceType = "bamboo",
            description = "watch ci build",
            params = mapOf("plan_key" to "PROJ-PLAN"),
        )
        val prSpec = MonitorSpec(
            id = "pr-11223344",
            sourceType = "pull_request",
            description = "watch pr 42",
            params = mapOf("pr_id" to "42", "aspects" to "state"),
        )

        persistence.add(sid, bambooSpec)
        persistence.add(sid, prSpec)

        val outcomes = reArm(sid, persistence, pool, project, cs)

        // Both should have registered successfully
        assertEquals(2, outcomes.size, "expected 2 outcomes, got: $outcomes")
        assertTrue(outcomes.all { it.second }, "both specs should register successfully: $outcomes")

        // The handles in the pool must carry the SAME ids as the persisted specs.
        val handles = pool.list(sid)
        assertEquals(2, handles.size, "pool should have 2 handles: $handles")
        val handleIds = handles.map { it.bgId }.toSet()
        assertEquals(setOf("bamboo-aabbccdd", "pr-11223344"), handleIds,
            "handle bgIds must equal persisted spec ids exactly, got: $handleIds")
    }

    // ─── Case 2: Dormancy reset ──────────────────────────────────────────────────

    /**
     * Construct a real MonitorManager, seed it with an event for a spec (so it has a
     * wake-budget entry), then markAllDormant() — the id is now dormant.
     * Call forget(id) (the re-arm step) → isDormant(id) must be false.
     * Verify that a subsequent wake-eligible event for that id, flushed while idle,
     * invokes wakeIdle (proving re-arm restores wakeability).
     */
    @Test
    fun `case2 - dormancy reset - forget after markAllDormant restores wake-eligibility`() {
        val specId = "bamboo-dormancy-test"
        var wakeIdleInvoked = false
        var deliverToLoopInvoked = false

        val manager = MonitorManager(
            config = MonitorConfig(coalesceWindowMs = 0L, wakeBudgetPerMonitor = 3),
            clock = { System.currentTimeMillis() },
            isLoopLive = { false },  // loop is idle so wakeIdle is used
            deliverToLoop = { deliverToLoopInvoked = true },
            wakeIdle = { _ ->
                wakeIdleInvoked = true
                WakeOutcome.WOKE
            },
        )

        // Seed with a NOTABLE event so the id gets a wake-budget entry.
        manager.onEvent(MonitorEvent(specId, Severity.NOTABLE, "build started"))

        // Mark all dormant — simulates what happens on abnormal loop exit.
        manager.markAllDormant()
        assertTrue(manager.isDormant(specId), "spec should be dormant after markAllDormant()")

        // Re-arm step: forget() clears all per-id state including dormancy.
        manager.forget(specId)
        assertFalse(manager.isDormant(specId), "spec should NOT be dormant after forget() (re-arm step)")

        // Emit another NOTABLE event — since not dormant, flushDue should call wakeIdle.
        manager.onEvent(MonitorEvent(specId, Severity.NOTABLE, "build completed"))
        manager.flushDue()  // coalesceWindowMs=0 so all pending are immediately due

        assertTrue(wakeIdleInvoked,
            "wakeIdle should be invoked for re-armed (non-dormant) monitor on wake-eligible event")
        assertFalse(deliverToLoopInvoked, "loop is idle so deliverToLoop should not be called")
    }

    // ─── Case 3: New-chat clears, resume does not ────────────────────────────────

    /**
     * Persist a spec + a pending notification.
     * Assert load() returns non-empty and loadPendingNotifications() returns the notification.
     * Call clear() + clearPendingNotifications() (the new-chat path).
     * Assert both are now empty.
     * Separately: double-call load() WITHOUT clear() to verify resume preserves the file.
     */
    @Test
    fun `case3 - new-chat clears both monitors and notifications and resume load leaves file intact`() {
        val sid = "session-clear-test"
        val spec = MonitorSpec(
            id = "bamboo-cleartest",
            sourceType = "bamboo",
            description = "to be cleared",
            params = mapOf("plan_key" to "CLEAR-PLAN"),
        )

        persistence.add(sid, spec)
        persistence.appendPendingNotification(sid, "build failed while away")

        // Pre-conditions: both present before clear.
        val specsBeforeClear = persistence.load(sid)
        val notificationsBeforeClear = persistence.loadPendingNotifications(sid)
        assertTrue(specsBeforeClear.isNotEmpty(), "monitors.json should be non-empty before clear")
        assertTrue(notificationsBeforeClear.isNotEmpty(),
            "monitor-notifications.json should be non-empty before clear")
        assertEquals("build failed while away", notificationsBeforeClear.first())

        // New-chat path: clear both.
        persistence.clear(sid)
        persistence.clearPendingNotifications(sid)

        val specsAfterClear = persistence.load(sid)
        val notificationsAfterClear = persistence.loadPendingNotifications(sid)
        assertTrue(specsAfterClear.isEmpty(), "monitors.json should be empty after clear()")
        assertTrue(notificationsAfterClear.isEmpty(),
            "monitor-notifications.json should be empty after clearPendingNotifications()")

        // Resume path: adding a spec back and loading twice should not delete it.
        val sid2 = "session-resume-persist-test"
        val spec2 = MonitorSpec(
            id = "bamboo-persisttest",
            sourceType = "bamboo",
            description = "persisted across loads",
            params = mapOf("plan_key" to "PERSIST-PLAN"),
        )
        persistence.add(sid2, spec2)

        // First load — simulates resume read.
        val firstLoad = persistence.load(sid2)
        assertEquals(1, firstLoad.size, "first load should return 1 spec")
        assertEquals("bamboo-persisttest", firstLoad.first().id)

        // Second load — file must still be present (resume does NOT delete on load).
        val secondLoad = persistence.load(sid2)
        assertEquals(1, secondLoad.size, "second load should still return 1 spec (file preserved by load)")
        assertEquals("bamboo-persisttest", secondLoad.first().id,
            "resume load must preserve the file — spec id must match on second load")
    }

    // ─── Case 4: MaxConcurrentReached during re-arm is survivable ────────────────

    /**
     * Register MAX_PER_SESSION handles first, then attempt to re-arm one more spec.
     * The pipeline must catch MaxConcurrentReached (stop the source, not throw),
     * and the pool size must stay at MAX_PER_SESSION.
     */
    @Test
    fun `case4 - MaxConcurrentReached is caught and pipeline continues without throwing`() {
        val sid = "session-max-reached"
        val maxPerSession = MonitorPool.MAX_PER_SESSION

        // Fill the pool to the cap using fake handles.
        runBlocking {
            for (i in 1..maxPerSession) {
                val fakeHandle = makeFakeHandle("fake-$i", sid)
                pool.register(sid, fakeHandle)
            }
        }
        assertEquals(maxPerSession, pool.list(sid).size,
            "pool should be at cap before re-arm attempt")

        // Persist one more spec for re-arm.
        val extraSpec = MonitorSpec(
            id = "bamboo-extra",
            sourceType = "bamboo",
            description = "one too many",
            params = mapOf("plan_key" to "EXTRA-PLAN"),
        )
        persistence.add(sid, extraSpec)

        // Run re-arm — must NOT throw; extra spec is skipped.
        val outcomes = reArm(sid, persistence, pool, project, cs)

        assertEquals(1, outcomes.size, "should have one outcome for the one persisted spec")
        assertFalse(outcomes.first().second,
            "extra spec re-arm should fail (MaxConcurrentReached), got: $outcomes")

        // Pool size must remain at MAX_PER_SESSION (no extra handle snuck in).
        assertEquals(maxPerSession, pool.list(sid).size,
            "pool size must stay at MAX_PER_SESSION after MaxConcurrentReached")
    }

    // ─── Case 5: Factory Failed spec is skipped ──────────────────────────────────

    /**
     * Persist a bamboo spec but provide a null bambooProvider → factory returns Failed.
     * Assert it is NOT registered in the pool and no exception is thrown.
     */
    @Test
    fun `case5 - factory Failed spec is skipped - not registered and no throw`() {
        val sid = "session-failed-spec"
        val spec = MonitorSpec(
            id = "bamboo-failspec",
            sourceType = "bamboo",
            description = "unconfigured bamboo",
            params = mapOf("plan_key" to "PROJ-PLAN"),
        )
        persistence.add(sid, spec)

        // Provide null bamboo provider → factory returns Failed("Bamboo is not configured.")
        val nullBambooProvider: (Project) -> BambooService? = { null }

        val outcomes = reArm(
            sid, persistence, pool, project, cs,
            bambooProvider = nullBambooProvider,
        )

        assertEquals(1, outcomes.size, "should have 1 outcome for the 1 persisted spec")
        assertFalse(outcomes.first().second,
            "Failed spec should not register — expected false, got: $outcomes")
        assertTrue(pool.list(sid).isEmpty(),
            "pool should be empty — Failed spec must not be registered, pool: ${pool.list(sid)}")
    }

    // ─── Case 6: Source-contract pin ─────────────────────────────────────────────

    /**
     * Reads AgentService.kt and AgentController.kt source text and asserts that:
     *
     * a) reArmMonitors contains the production call sites in order:
     *    monitorPersistence.load( → MonitorSourceFactory.build( → .register(
     *    → ensureMonitorManager( → forget( → .start {
     *
     * b) resumeSession calls reArmMonitors(
     *
     * c) killBackgroundsOnTransition calls clearPersistedMonitors(
     *
     * This pins the integration test's replicated pipeline to the actual production code
     * so any divergence in AgentService causes this test to fail loudly.
     */
    @Test
    fun `case6 - source-contract pin - reArmMonitors pipeline and callers are wired correctly`() {
        val agentServiceText = readSourceFile(
            "AgentService.kt",
            "src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt",
        )
        val agentControllerText = readSourceFile(
            "AgentController.kt",
            "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt",
        )

        // ─── (a) reArmMonitors call-site ordering ────────────────────────────────
        // Bound the search to the reArmMonitors function BODY so the landmarks pin the
        // calls INSIDE reArmMonitors — not an earlier `forget(`/`ensureMonitorManager(` that
        // appears elsewhere in the file (e.g. forgetMonitor()'s body, the ensureMonitorManager
        // declaration, or a code comment). Slice from the declaration to the next top-level
        // member declaration after it.
        val reArmDeclIdx = agentServiceText.indexOf("fun reArmMonitors(")
        assertTrue(reArmDeclIdx >= 0, "AgentService must declare reArmMonitors(")
        val afterDecl = reArmDeclIdx + "fun reArmMonitors(".length
        val nextMemberRel = Regex("\\n    (private |internal |public )?(suspend )?fun ")
            .find(agentServiceText, afterDecl)?.range?.first ?: agentServiceText.length
        val body = agentServiceText.substring(reArmDeclIdx, nextMemberRel)

        // Each landmark must appear in the function body, in the correct relative order.
        val loadIdx          = body.indexOf("monitorPersistence.load(")
        val buildIdx         = body.indexOf("MonitorSourceFactory.build(")
        val registerIdx      = body.indexOf(".register(", buildIdx.coerceAtLeast(0))
        val ensureManagerIdx = body.indexOf("ensureMonitorManager(", registerIdx.coerceAtLeast(0))
        val forgetIdx        = body.indexOf("forget(", ensureManagerIdx.coerceAtLeast(0))
        val startIdx         = body.indexOf(".start {", forgetIdx.coerceAtLeast(0))

        assertTrue(loadIdx >= 0,
            "reArmMonitors body must call monitorPersistence.load(")
        assertTrue(buildIdx > loadIdx,
            "MonitorSourceFactory.build( must appear after monitorPersistence.load( in reArmMonitors")
        assertTrue(registerIdx > buildIdx,
            ".register( must appear after MonitorSourceFactory.build( in reArmMonitors")
        assertTrue(ensureManagerIdx > registerIdx,
            "ensureMonitorManager( must appear after .register( in reArmMonitors")
        assertTrue(forgetIdx > ensureManagerIdx,
            "forget( must appear after ensureMonitorManager( in reArmMonitors")
        assertTrue(startIdx > forgetIdx,
            ".start { must appear after forget( in reArmMonitors")

        // ─── (b) resumeSession calls reArmMonitors ────────────────────────────────
        // Find the resumeSession declaration then look for a reArmMonitors( call AFTER it.
        // (The private fun reArmMonitors declaration itself appears before resumeSession in
        //  the file, so we must search from the resumeSession declaration forward.)
        val resumeSessionIdx = agentServiceText.indexOf("fun resumeSession(")
        assertTrue(resumeSessionIdx >= 0, "AgentService must contain resumeSession(")
        val reArmCallFromResume = agentServiceText.indexOf("reArmMonitors(", resumeSessionIdx)
        assertTrue(reArmCallFromResume > resumeSessionIdx,
            "A reArmMonitors( call must appear inside/after the resumeSession( declaration")

        // ─── (c) killBackgroundsOnTransition calls clearPersistedMonitors ─────────
        assertTrue(agentControllerText.contains("clearPersistedMonitors("),
            "AgentController must call clearPersistedMonitors(")

        val killIdx  = agentControllerText.indexOf("fun killBackgroundsOnTransition(")
        val clearIdx = agentControllerText.indexOf("clearPersistedMonitors(")
        assertTrue(killIdx >= 0, "AgentController must contain killBackgroundsOnTransition(")
        assertTrue(clearIdx > killIdx,
            "clearPersistedMonitors( must appear after killBackgroundsOnTransition( declaration")
    }

    // ─── helpers ──────────────────────────────────────────────────────────────────

    /** Create a minimal no-op [MonitorHandle] for pre-filling the pool. */
    private fun makeFakeHandle(id: String, sessionId: String): MonitorHandle {
        val src = object : MonitorSource {
            override val monitorId = id
            override val description = "fake-$id"
            override fun start(emit: (MonitorEvent) -> Unit) {}
            override fun stop() {}
        }
        return MonitorHandle(src, sessionId, System.currentTimeMillis())
    }

    /**
     * Read a source file from the module layout.
     *
     * Gradle's `:agent:test` sets `user.dir = <repoRoot>/agent`.
     * IntelliJ's runner may set it to `<repoRoot>` (via `$MODULE_WORKING_DIR$`).
     * Both paths are tried; a missing file fails loudly with exact paths.
     */
    private fun readSourceFile(fileName: String, relPath: String): String {
        val userDir = System.getProperty("user.dir")
            ?: error("user.dir system property is not set")
        val root = File(userDir)
        val moduleRootedPath = File(root, relPath)          // user.dir == <repo>/agent
        val repoRootedPath   = File(root, "agent/$relPath") // user.dir == <repo>
        return when {
            moduleRootedPath.isFile -> moduleRootedPath.readText()
            repoRootedPath.isFile   -> repoRootedPath.readText()
            else -> error(
                "Source file '$fileName' not found at either expected path:\n" +
                    "  1. ${moduleRootedPath.absolutePath}\n" +
                    "  2. ${repoRootedPath.absolutePath}\n" +
                    "user.dir=$userDir — module layout may have changed."
            )
        }
    }
}
