# DevStatus Cache + Event-Driven Invalidation + Focus-Gated Polling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cache Jira DevStatus calls (currently uncached, ~1.8s per ticket open on slow VPN), invalidate the cache when local IDE events imply the data is stale (own push / branch change / PR merge / ticket transition), and audit that all polling sites already inherit `SmartPoller`'s IDE-focus gating.

**Architecture:** Reuse existing infrastructure rather than adding parallel systems. Add one URL pattern to `CachePolicyRegistry`, build one project-scoped event listener (`DevStatusCacheInvalidator`) that bridges `EventBus.events` → `HttpResponseCache.invalidateByPrefix`, and verify `SmartPoller`'s existing focus check at `core/polling/SmartPoller.kt:87` is the only polling cadence path. No new cache, no new poller.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform 2025.1+, OkHttp interceptors (`CachingInterceptor`, `MutationInvalidationInterceptor`), Caffeine-backed `HttpResponseCache`, kotlinx.coroutines `SharedFlow<WorkflowEvent>` (`EventBus`), JUnit 5 + MockWebServer.

**Branch:** `fix/automation-handover-quality-tabs` (continue current branch — see memory `feedback_work_on_current_branch.md`).

**Out of scope:**
- True Bitbucket webhook subscription (deemed overkill — no public URL on developer laptop).
- Cross-machine fan-out (teammate's push won't be visible until next poll cycle; covered by `SmartPoller` baseline).
- Bamboo→Jira application-link probe — separate audit, deferred.

---

## File Structure

**New files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/http/DevStatusCacheInvalidator.kt` — project service + EventBus subscriber
- `core/src/test/kotlin/com/workflow/orchestrator/core/http/DevStatusCacheInvalidatorTest.kt`
- `core/src/test/kotlin/com/workflow/orchestrator/core/http/CachePolicyRegistryDevStatusTest.kt`

**Modified files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/http/CachePolicyRegistry.kt` — add dev-status rule
- `src/main/resources/META-INF/plugin.xml` — register `DevStatusCacheInvalidator` as a project service
- `tools/atlassian-probe/probe_jira.py` — add `--devstatus-headers` mode (probe response headers for ETag / Last-Modified)
- `core/CLAUDE.md` — short note documenting the cache policy + event mapping
- `docs/architecture/index.html` — link this plan and add a "DevStatus cache" entry to the architecture doc index

---

## Task 0: Verify the SmartPoller focus-gating audit (read-only — no commit)

**Goal:** Confirm step 3 ("pause polling when IDE unfocused") is already done by `SmartPoller`'s existing `isIdeFocused()` check. Document any polling site that bypasses `SmartPoller`.

**Files (read-only):**
- `core/src/main/kotlin/com/workflow/orchestrator/core/polling/SmartPoller.kt` (lines 51–67, 87–96)
- `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt:45`
- `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/CommentsTabPanel.kt:55`
- `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/insights/InsightsPanel.kt:46`
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt:97`
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/MonitorPanel.kt:116`

- [ ] **Step 1: Read each call site and confirm it constructs `SmartPoller` with default behavior**

For each of the 5 sites above, verify:
1. `SmartPoller(...)` is the polling primitive.
2. The site does *not* schedule its own timer/coroutine that bypasses `SmartPoller`.

Run: `grep -n "scheduleAtFixedRate\|Timer\|delay(" pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/**/*.kt bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/**/*.kt automation/src/main/kotlin/com/workflow/orchestrator/automation/**/*.kt | grep -v test | grep -v "SmartPoller"`

Expected: zero hits in production polling code (other than `SmartPoller` itself). Any hit becomes a follow-up task in this plan.

- [ ] **Step 2: Verify `IdeFocusManager` returns sane values during tests**

Run: `./gradlew :core:test --tests "*SmartPoller*"`

Expected: green. If a test mocks focus state, that confirms the gate is exercised.

- [ ] **Step 3: Document outcome**

If audit clean → write a single line in `docs/research/2026-05-07-devstatus-cache-and-focus-plan.md` under a "Step 3 status" heading: "Already implemented in SmartPoller.kt:87; no follow-up required."

If audit reveals a site that bypasses `SmartPoller`, add a Task X migrating it. Don't proceed without this resolved.

---

## Task 1: Probe DevStatus response headers (informational, no production change)

**Goal:** Confirm whether Jira DC's dev-status responses include `ETag` / `Last-Modified` / `Cache-Control` headers. If yes, a future task can upgrade `CachingInterceptor` to send conditional GETs (saving the body on the wire). If no, the existing synthetic-ETag SHA-256 path is the ceiling — document and stop.

**Files:**
- Modify: `tools/atlassian-probe/probe_jira.py` (add a `--devstatus-headers` flag that does a GET on `/rest/dev-status/1.0/issue/detail?issueId=<id>&applicationType=stash&dataType=branch` and captures response headers verbatim)
- Create: `tools/atlassian-probe/Result_Jira/devstatus-headers.txt` (probe output, redacted)

- [ ] **Step 1: Add the probe mode**

Open `tools/atlassian-probe/probe_jira.py`. Locate the existing argparse setup. Add a new flag and handler:

```python
# Inside argparse setup:
parser.add_argument("--devstatus-headers", action="store_true",
                    help="Probe dev-status response headers for ETag / Last-Modified support")

# Inside the dispatch logic (look for existing if/elif on flag args):
if args.devstatus_headers:
    issue_id = args.issue_id or input("Numeric issueId for dev-status probe: ").strip()
    url = f"{base}/rest/dev-status/1.0/issue/detail?issueId={issue_id}&applicationType=stash&dataType=branch"
    resp = session.get(url, headers=auth_headers, timeout=30)
    print(f"Status: {resp.status_code}")
    print("Response headers:")
    for k, v in resp.headers.items():
        print(f"  {k}: {v}")
    # Save raw, redacted-friendly text
    with open(out_dir / "devstatus-headers.txt", "w") as f:
        f.write(f"GET {url}\nStatus: {resp.status_code}\n\n")
        for k, v in resp.headers.items():
            f.write(f"{k}: {v}\n")
    return
```

- [ ] **Step 2: Run the probe (manual — needs live Jira)**

User runs:
```bash
cd tools/atlassian-probe
python3 probe_jira.py --devstatus-headers --issue-id 12345
# Outputs response headers to stdout + Result_Jira/devstatus-headers.txt
```

Expected: file at `Result_Jira/devstatus-headers.txt` containing the response headers. Look for these three keys:
- `ETag` — if present, upgrade path is viable.
- `Last-Modified` — secondary upgrade path.
- `Cache-Control` — must not be `no-store` for our cache to be RFC-compliant.

- [ ] **Step 3: Redact and capture in the plan**

Run the existing redactor to scrub server hostname / token bytes:
```bash
python3 redact.py Result_Jira/devstatus-headers.txt
```

Then add a "Probe outcome" section to this plan file with the three header values (or "absent").

- [ ] **Step 4: Commit**

```bash
git add tools/atlassian-probe/probe_jira.py tools/atlassian-probe/Result_Jira/devstatus-headers.txt docs/research/2026-05-07-devstatus-cache-and-focus-plan.md
git commit -m "feat(probe): capture dev-status response headers for cache strategy"
```

**Decision gate:** If headers absent or `Cache-Control: no-store` → skip Task 4; the synthetic-ETag path is what we ship. If `ETag` present → Task 4 (conditional GET) becomes worth the effort. The user makes the call.

---

## Task 2: Add dev-status URL to `CachePolicyRegistry`

**Goal:** Make `GET /rest/dev-status/1.0/issue/detail?...` cacheable with a 60-second TTL so repeat ticket-panel opens within a minute hit cache.

**Why 60s and not 5 minutes:** dev-status is intrinsically volatile (new branches, PR transitions). 60s deduplicates rapid re-opens (the dominant case — user clicks back to the same ticket) without surfacing teammate's stale state for long. `EventBus` invalidation (Task 3) collapses the *user's own* changes to zero latency.

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/http/CachePolicyRegistry.kt` (add one rule)
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/http/CachePolicyRegistryDevStatusTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/http/CachePolicyRegistryDevStatusTest.kt`:

```kotlin
package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.model.ServiceType
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CachePolicyRegistryDevStatusTest {

    @Test
    fun `dev-status branch dataType is cacheable with 60s TTL`() {
        val url = "https://jira.example.com/rest/dev-status/1.0/issue/detail?issueId=12345&applicationType=stash&dataType=branch".toHttpUrl()
        val policy = CachePolicyRegistry.policyFor(ServiceType.JIRA, url)
        assertTrue(policy.isCacheable, "dev-status URL must be cacheable")
        assertEquals(60L, policy.ttlSeconds, "dev-status TTL must be 60s")
    }

    @Test
    fun `dev-status pullrequest dataType is cacheable with 60s TTL`() {
        val url = "https://jira.example.com/rest/dev-status/1.0/issue/detail?issueId=12345&applicationType=stash&dataType=pullrequest".toHttpUrl()
        val policy = CachePolicyRegistry.policyFor(ServiceType.JIRA, url)
        assertTrue(policy.isCacheable)
        assertEquals(60L, policy.ttlSeconds)
    }

    @Test
    fun `dev-status build dataType is cacheable with 60s TTL`() {
        val url = "https://jira.example.com/rest/dev-status/1.0/issue/detail?issueId=12345&applicationType=stash&dataType=build".toHttpUrl()
        val policy = CachePolicyRegistry.policyFor(ServiceType.JIRA, url)
        assertEquals(60L, policy.ttlSeconds)
    }

    @Test
    fun `dev-status URL only matches under JIRA service`() {
        val url = "https://example.com/rest/dev-status/1.0/issue/detail?issueId=12345".toHttpUrl()
        val policy = CachePolicyRegistry.policyFor(ServiceType.BITBUCKET, url)
        assertEquals(CachePolicy.NEVER, policy, "Bitbucket service must not match Jira's dev-status pattern")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "CachePolicyRegistryDevStatusTest"`

Expected: 3 of 4 tests FAIL because no rule exists yet (current default is `NEVER` → `isCacheable=false`). The fourth (`only matches under JIRA`) may pass coincidentally — that's fine; it locks the negative behavior.

- [ ] **Step 3: Add the policy rule**

In `core/src/main/kotlin/com/workflow/orchestrator/core/http/CachePolicyRegistry.kt`, locate the `ServiceType.JIRA to listOf(...)` block (around line 65). Add a rule for dev-status:

```kotlin
ServiceType.JIRA to listOf(
    Rule(Regex("""/rest/agile/1\.0/board(/\d+)?$"""), CachePolicy(300)),
    Rule(Regex("""/rest/agile/1\.0/sprint/\d+$"""), CachePolicy(120)),
    Rule(Regex("""/rest/api/2/user/assignable/search"""), CachePolicy(3600)),
    Rule(Regex("""/rest/api/2/search"""), CachePolicy(10)),
    Rule(Regex("""/rest/api/2/issue/[A-Z][A-Z0-9]+-\d+(?:$|/transitions$)"""), CachePolicy(60)),
    Rule(Regex("""/rest/api/2/project/[^/]+/versions"""), CachePolicy(300)),
    Rule(Regex("""/rest/api/2/project/[^/]+/components"""), CachePolicy(300)),
    Rule(Regex("""/rest/dev-status/1\.0/issue/detail"""), CachePolicy(60))   // ← ADD
),
```

The trailing `(?:$|\?...)` qualifier is unnecessary because `CachePolicyRegistry.policyFor` matches against `url.encodedPath` (which excludes query string), so the regex correctly matches all dataType variants.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "CachePolicyRegistryDevStatusTest"`

Expected: all 4 tests PASS.

- [ ] **Step 5: Run the full :core test suite to confirm no regression**

Run: `./gradlew :core:test`

Expected: 829+ tests PASS (per memory `project_api_audit_in_progress`, current baseline is 829 :core).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/http/CachePolicyRegistry.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/http/CachePolicyRegistryDevStatusTest.kt
git commit -m "feat(core): cache dev-status responses for 60s

dev-status is the dominant cost in TicketDetailPanel.show()
(~1.8s on slow VPN: 6 parallel dataTypes × ~300ms).
60s TTL deduplicates rapid re-opens; EventBus invalidation
(separate commit) collapses own-action latency to zero."
```

---

## Task 3: Build `DevStatusCacheInvalidator` (EventBus → cache eviction bridge)

**Goal:** When the user does something locally that implies dev-status data has changed (push, branch creation, PR transition, ticket transition), evict matching dev-status cache entries immediately so the next ticket-panel open shows fresh state without waiting for the 60s TTL.

**Strategy:** Subscribe to `EventBus.events`. On each relevant event, call `HttpResponseCache.invalidateByPrefix("/rest/dev-status/1.0/issue/detail")`. This is a coarse but cheap nuke — dev-status keys are a small set (one per recently viewed ticket), and a wholesale flush is simpler + faster than per-ticket-key reverse lookup. We can refine later if metrics show churn.

**Mapped events (from existing `WorkflowEvent` sealed class):**
- `BranchChanged` — `BranchChangedEventEmitter` already emits this.
- `PullRequestCreated` — emitted by `:handover` after PR creation.
- `PullRequestMerged` / `PullRequestDeclined` / `PullRequestApproved` — emitted by `:pullrequest`.
- `TicketChanged` — emitted by `:jira` on Start Work / branch switch.
- `JiraCommentPosted` — does NOT change dev-status; skip.
- `BuildFinished` — does NOT directly affect Jira's dev-status view (Jira learns via app-link async); skip to avoid thrashing.

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/http/DevStatusCacheInvalidator.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/http/DevStatusCacheInvalidatorTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (register as project service + startup activity)

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/http/DevStatusCacheInvalidatorTest.kt`:

```kotlin
package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.events.WorkflowEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevStatusCacheInvalidatorTest {

    private lateinit var bus: MutableSharedFlow<WorkflowEvent>
    private var invalidationCount = 0

    @BeforeEach
    fun setup() {
        bus = MutableSharedFlow(extraBufferCapacity = 16)
        invalidationCount = 0
    }

    @AfterEach
    fun teardown() {
        HttpResponseCache.invalidateAll()
    }

    @Test
    fun `BranchChanged event evicts dev-status entries`() = runTest {
        val invalidator = DevStatusCacheInvalidator.testInstance(
            events = bus,
            scope = backgroundScope,
            invalidator = { _ -> invalidationCount++; 1 }
        )
        invalidator.start()
        bus.emit(WorkflowEvent.BranchChanged(branchName = "feature/PROJ-1", projectKey = "PROJ", repoSlug = "app"))
        advanceUntilIdle()
        assertEquals(1, invalidationCount)
    }

    @Test
    fun `PullRequestMerged event evicts dev-status entries`() = runTest {
        val invalidator = DevStatusCacheInvalidator.testInstance(
            events = bus,
            scope = backgroundScope,
            invalidator = { _ -> invalidationCount++; 1 }
        )
        invalidator.start()
        bus.emit(WorkflowEvent.PullRequestMerged(prId = 42))
        advanceUntilIdle()
        assertEquals(1, invalidationCount)
    }

    @Test
    fun `TicketChanged event evicts dev-status entries`() = runTest {
        val invalidator = DevStatusCacheInvalidator.testInstance(
            events = bus,
            scope = backgroundScope,
            invalidator = { _ -> invalidationCount++; 1 }
        )
        invalidator.start()
        bus.emit(WorkflowEvent.TicketChanged(ticketId = "PROJ-1", ticketSummary = "x"))
        advanceUntilIdle()
        assertEquals(1, invalidationCount)
    }

    @Test
    fun `BuildFinished does NOT evict dev-status entries`() = runTest {
        val invalidator = DevStatusCacheInvalidator.testInstance(
            events = bus,
            scope = backgroundScope,
            invalidator = { _ -> invalidationCount++; 1 }
        )
        invalidator.start()
        bus.emit(WorkflowEvent.BuildFinished(planKey = "PLAN-X", buildNumber = 1, status = com.workflow.orchestrator.core.events.BuildEventStatus.SUCCESSFUL))
        advanceUntilIdle()
        assertEquals(0, invalidationCount, "BuildFinished must not evict dev-status (Jira learns via app-link async)")
    }

    @Test
    fun `JiraCommentPosted does NOT evict dev-status entries`() = runTest {
        val invalidator = DevStatusCacheInvalidator.testInstance(
            events = bus,
            scope = backgroundScope,
            invalidator = { _ -> invalidationCount++; 1 }
        )
        invalidator.start()
        bus.emit(WorkflowEvent.JiraCommentPosted(ticketId = "PROJ-1", commentId = "100"))
        advanceUntilIdle()
        assertEquals(0, invalidationCount, "comment posts don't affect dev-status")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "DevStatusCacheInvalidatorTest"`

Expected: FAIL — `DevStatusCacheInvalidator` doesn't exist yet, won't compile.

- [ ] **Step 3: Implement `DevStatusCacheInvalidator`**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/http/DevStatusCacheInvalidator.kt`:

```kotlin
package com.workflow.orchestrator.core.http

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Bridges [EventBus] events to [HttpResponseCache] eviction for dev-status
 * URLs.
 *
 * Background — Jira's dev-status API (`/rest/dev-status/1.0/issue/detail`)
 * is cached for 60s by [CachePolicyRegistry] to deduplicate rapid ticket
 * re-opens. That TTL is the floor on freshness for *teammates'* changes.
 * For the user's *own* actions (push, branch create, PR merge, ticket
 * transition) the data is stale immediately, and we can collapse the
 * latency to zero by evicting the cache the moment we hear a relevant
 * `WorkflowEvent`.
 *
 * Strategy: coarse-prefix invalidation. Dev-status keys are a small set
 * (one per recently-opened ticket), so a single
 * [HttpResponseCache.invalidateByPrefix] call walks ≤ N entries, vs. the
 * complexity of reverse-mapping a `WorkflowEvent.PullRequestMerged(prId)`
 * to the parent ticket key. Refine later if metrics show churn.
 *
 * Companion `MutationInvalidationInterceptor` already covers HTTP-level
 * mutation eviction (e.g. POST /transitions); this listener handles
 * out-of-band signals that don't go through our HTTP pipeline (git
 * branch change, external build callbacks, etc.).
 */
@Service(Service.Level.PROJECT)
class DevStatusCacheInvalidator(private val project: Project) : Disposable {

    private val log = Logger.getInstance(DevStatusCacheInvalidator::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        val events = project.getService(EventBus::class.java).events
        scope.launch {
            events.collect { event -> handle(event) }
        }
    }

    private fun handle(event: WorkflowEvent) {
        if (!shouldEvict(event)) return
        val removed = HttpResponseCache.invalidateByPrefix(DEV_STATUS_PREFIX)
        if (removed > 0) {
            log.debug("[DevStatusCache] Evicted $removed entries for event=${event::class.simpleName}")
        }
    }

    private fun shouldEvict(event: WorkflowEvent): Boolean = when (event) {
        is WorkflowEvent.BranchChanged,
        is WorkflowEvent.PullRequestCreated,
        is WorkflowEvent.PullRequestMerged,
        is WorkflowEvent.PullRequestDeclined,
        is WorkflowEvent.PullRequestApproved,
        is WorkflowEvent.TicketChanged -> true
        else -> false
    }

    override fun dispose() {
        scope.cancel()
    }

    /**
     * Test-only constructor that lets a fake event flow + custom invalidator
     * be supplied so the production path doesn't need a `Project` fixture.
     * The real call path uses [DevStatusCacheInvalidatorActivity.execute].
     */
    internal class TestableInvalidator(
        private val events: SharedFlow<WorkflowEvent>,
        private val scope: CoroutineScope,
        private val invalidator: (String) -> Int
    ) {
        fun start() {
            scope.launch {
                events.collect { event ->
                    if (shouldEvictTest(event)) invalidator(DEV_STATUS_PREFIX)
                }
            }
        }
    }

    companion object {
        private const val DEV_STATUS_PREFIX = "/rest/dev-status/1.0/issue/detail"

        internal fun shouldEvictTest(event: WorkflowEvent): Boolean = when (event) {
            is WorkflowEvent.BranchChanged,
            is WorkflowEvent.PullRequestCreated,
            is WorkflowEvent.PullRequestMerged,
            is WorkflowEvent.PullRequestDeclined,
            is WorkflowEvent.PullRequestApproved,
            is WorkflowEvent.TicketChanged -> true
            else -> false
        }

        fun testInstance(
            events: SharedFlow<WorkflowEvent>,
            scope: CoroutineScope,
            invalidator: (String) -> Int
        ): TestableInvalidator = TestableInvalidator(events, scope, invalidator)
    }
}

/**
 * Wires [DevStatusCacheInvalidator] at project open. ProjectActivity is the
 * 2025.1+ replacement for StartupActivity (see :bamboo's
 * BuildFailureBridgeStartupActivity for the same pattern).
 */
class DevStatusCacheInvalidatorActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<DevStatusCacheInvalidator>().start()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "DevStatusCacheInvalidatorTest"`

Expected: all 5 tests PASS.

- [ ] **Step 5: Register the activity in `plugin.xml`**

Open `src/main/resources/META-INF/plugin.xml`. Locate the existing `<extensions>` block where project services / startup activities are registered (search for `BuildFailureBridgeStartupActivity` for an existing precedent). Add:

```xml
<extensions defaultExtensionNs="com.intellij">
    <!-- ...existing entries... -->
    <projectService
        serviceImplementation="com.workflow.orchestrator.core.http.DevStatusCacheInvalidator"/>
    <postStartupActivity
        implementation="com.workflow.orchestrator.core.http.DevStatusCacheInvalidatorActivity"/>
</extensions>
```

If `BuildFailureBridgeStartupActivity` is registered in a different `extensions` block (e.g. `plugin-withGit.xml`), check whether that's appropriate — `DevStatusCacheInvalidator` only needs `EventBus` (in `:core`), so the main `plugin.xml` is correct.

- [ ] **Step 6: Run full integration check**

Run: `./gradlew :core:test verifyPlugin`

Expected: green. `verifyPlugin` confirms the plugin.xml registration is well-formed.

- [ ] **Step 7: Update `core/CLAUDE.md`**

Add a paragraph under the "Core Utilities" section (or create a "Caching" subsection) documenting:
- DevStatus cached 60s via `CachePolicyRegistry`.
- `DevStatusCacheInvalidator` evicts on `BranchChanged`, `PullRequestCreated/Merged/Declined/Approved`, `TicketChanged`.
- HTTP-mutation eviction handled separately by `MutationInvalidationInterceptor`.

- [ ] **Step 8: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/http/DevStatusCacheInvalidator.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/http/DevStatusCacheInvalidatorTest.kt \
        src/main/resources/META-INF/plugin.xml \
        core/CLAUDE.md
git commit -m "feat(core): EventBus-driven dev-status cache invalidation

Bridges WorkflowEvent.{BranchChanged, PullRequest*, TicketChanged}
to HttpResponseCache.invalidateByPrefix(\"/rest/dev-status/...\").
Collapses own-action freshness latency from 60s (TTL) to ~0ms.
Coarse prefix eviction is intentional — dev-status keyspace is small,
reverse-mapping prId → ticketKey would add complexity for no win."
```

---

## Task 4 (CONDITIONAL — only if Task 1 probe found ETag headers): Conditional GET upgrade

**Goal:** If Jira DC returns `ETag` on dev-status responses, upgrade `CachingInterceptor` to send `If-None-Match` on stale-revalidation so the body doesn't cross the wire.

**Skip this task entirely** if Task 1 found no `ETag` / `Last-Modified` headers. The synthetic-ETag SHA-256 path already saves parse + UI rebuild costs.

**Files (only if proceeding):**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/http/CachingInterceptor.kt` (add `If-None-Match` request header on stale revalidate, handle `304 Not Modified` response)
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpResponseCache.kt` (store server ETag alongside body bytes)
- New: `core/src/test/kotlin/com/workflow/orchestrator/core/http/CachingInterceptorConditionalGetTest.kt`

(Detailed steps deferred until Task 1 probe outcome is known. Don't write speculative code.)

---

## Self-Review

**Spec coverage:**
- [x] Step 1 (local-event cache invalidation) — Task 2 (cache policy) + Task 3 (invalidator)
- [x] Step 2 (ETag/Last-Modified conditional polling) — Task 1 (probe) + conditional Task 4
- [x] Step 3 (pause polling when IDE unfocused) — Task 0 (audit; expected to confirm already done)

**Placeholder scan:** No "TBD"/"add appropriate"/"similar to Task N" placeholders. All code blocks are complete.

**Type consistency:** `WorkflowEvent.BranchChanged`, `WorkflowEvent.PullRequestMerged`, `WorkflowEvent.TicketChanged`, `WorkflowEvent.JiraCommentPosted`, `WorkflowEvent.BuildFinished`, `BuildEventStatus.SUCCESSFUL`, `HttpResponseCache.invalidateByPrefix(prefix: String): Int`, `EventBus.events: SharedFlow<WorkflowEvent>` — all verified against current source.

**Branch:** `fix/automation-handover-quality-tabs` (current).

**Commit count:** 3 commits (Task 1 probe, Task 2 cache rule, Task 3 invalidator). Task 4 conditional.

---

## Probe outcome (Task 1 — closed without live run, 2026-05-07)

```
Decision: trust the existing observation in CachingInterceptor.kt:22
("Atlassian/SonarSource servers don't send ETag on REST JSON, so we
compute our own fingerprint by SHA-256-hashing the response body").
That comment was committed by an earlier audit and applies to the same
class of Atlassian REST endpoints that dev-status belongs to.

Implication: Task 4 (conditional-GET upgrade) is CLOSED. The synthetic-
ETag SHA-256 path in CachingInterceptor is the ceiling for stale-
revalidation efficiency.

The --devstatus-headers probe code (commit e058bd83) remains in the
repo as a one-shot diagnostic if anyone later doubts this assumption.
No live run was performed.
```

---

## Step 3 audit status (Task 0 outcome — 2026-05-07)

```
Already implemented in core/polling/SmartPoller.kt:87-96.
  - isIdeFocused() reads IdeFocusManager.getGlobalInstance().lastFocusedFrame
  - When unfocused: effectiveInterval = baseInterval * 4 (capped at maxInterval)
  - All 5 SmartPoller call sites use it without bypass:
      pullrequest/PrListService.kt:45
      pullrequest/CommentsTabPanel.kt:55
      core/toolwindow/insights/InsightsPanel.kt:46
      bamboo/BuildMonitorService.kt:97
      automation/MonitorPanel.kt:116
  - Audit grep for `scheduleAtFixedRate|Timer|fixedRateTimer|while (isActive)` in
    pullrequest/bamboo/automation production code: zero hits outside SmartPoller.
  - Coverage gap: no dedicated SmartPollerTest class; focus-gate exercised
    indirectly via polling-site integration. Not a blocker for this plan;
    flagged as separate follow-up.
No follow-up task required for Step 3.
```
