# Network Connectivity Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single application-level connectivity authority so the agent fails fast (with a one-click Retry) instead of burning its retry budget into a dead VPN tunnel, and background pollers pause while offline and stagger-resume on reconnect.

**Architecture:** A new `:core` `NetworkStateService` (`@Service(Service.Level.APP)`) is the single source of truth. It is fed reactively (HTTP clients report transport failures/successes via an OkHttp interceptor), by one active reachability probe (the only thing that discovers reconnection while everything is paused), and by a monotonic-clock-gap wake watchdog. The agent loop consults it via `checkNow()` at its retry seam; `SmartPoller` gates each poll on `awaitOnline()`.

**Tech Stack:** Kotlin, IntelliJ Platform `@Service` (constructor-injected `CoroutineScope`), OkHttp, kotlinx.coroutines (`StateFlow`), JUnit 5 + MockK + `kotlinx-coroutines-test`.

**Design spec:** `docs/superpowers/specs/2026-05-26-network-connectivity-resilience-design.md`

**Scope note (trimmed from spec):** The spec's optional `ApplicationActivationListener` complement is deferred — the clock-gap watchdog alone solves the wake-detection requirement and avoids a `plugin.xml` listener registration. The per-host reachability map remains a documented non-goal.

**Reporting coverage note (important):** Reactive `reportFailure`/`reportSuccess` (Task 4) flows only through clients built by `HttpClientFactory` — i.e. all the feature pollers (Jira/PR/Bamboo/Sonar). The agent's LLM client (`SourcegraphChatClient`) and `BitbucketBranchClient` deliberately build their own OkHttpClients and bypass `HttpClientFactory` (see memory: "Sourcegraph HTTP client kept isolated — never migrate"). The agent path is therefore **not** covered by reactive reporting — it is covered by the active `checkNow()` probe at the retry seam (Task 6), which independently probes the LLM host and also flips global state (pausing pollers + arming the recovery probe). This split is by design: pollers feed-and-consume reactive state; the agent actively probes. Do not "fix" it by routing the Sourcegraph client through `HttpClientFactory`.

---

### Task 1: NetworkState enum + probe interfaces (`:core`)

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/network/NetworkState.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/network/ReachabilityProbe.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/network/NetworkProbe.kt`

- [ ] **Step 1: Create the state enum + the two interfaces**

`NetworkState.kt`:
```kotlin
package com.workflow.orchestrator.core.network

/** Coalesced machine-wide connectivity state. RECONNECTING is a transient post-wake state. */
enum class NetworkState { ONLINE, OFFLINE, RECONNECTING }
```

`ReachabilityProbe.kt`:
```kotlin
package com.workflow.orchestrator.core.network

/**
 * A single, cheap, no-retry reachability check. Implementations must NOT route
 * through HttpClientFactory (no RetryInterceptor, no reporting interceptor) — the
 * probe is the one thing allowed to talk to the network while everything else is paused.
 */
interface ReachabilityProbe {
    /** true if [targetUrl]'s host answered at all (any HTTP status counts); false on IOException/timeout. */
    suspend fun isReachable(targetUrl: String): Boolean
}
```

`NetworkProbe.kt`:
```kotlin
package com.workflow.orchestrator.core.network

import kotlinx.coroutines.flow.StateFlow

/**
 * The connectivity authority's public surface. Injected (defaulting to the APP service)
 * into SmartPoller and AgentLoop so both can be unit-tested with a fake.
 */
interface NetworkProbe {
    val state: StateFlow<NetworkState>

    /** Reactive input: any HTTP client that got a transport (IOException) failure. */
    fun reportFailure(targetUrl: String)

    /** Reactive input: any HTTP request reached the server (even a 4xx/5xx). Flips to ONLINE. */
    fun reportSuccess()

    /**
     * Bounded live probe used by the agent at its retry seam. Probes [targetUrl]
     * (or the last-failed target when null), updates [state], and returns the result.
     */
    suspend fun checkNow(targetUrl: String?): NetworkState

    /** Suspends until ONLINE or [timeoutMs] elapses. Returns true if online, false on timeout. */
    suspend fun awaitOnline(timeoutMs: Long): Boolean
}
```

- [ ] **Step 2: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/network/
git commit -m "feat(core): NetworkState + NetworkProbe/ReachabilityProbe interfaces"
```

---

### Task 2: NetworkReachabilityProbe (dedicated OkHttp probe)

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/network/NetworkReachabilityProbe.kt`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/network/NetworkReachabilityProbeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkReachabilityProbeTest {

    @Test
    fun `reachable when server answers (even 404)`() = runTest {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(404)); start() }
        try {
            val probe = NetworkReachabilityProbe()
            assertTrue(probe.isReachable(server.url("/").toString()))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `unreachable when host does not resolve`() = runTest {
        val probe = NetworkReachabilityProbe()
        // RFC 6761 reserved TLD .invalid never resolves
        assertFalse(probe.isReachable("https://this-host-does-not-exist.invalid"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "*NetworkReachabilityProbeTest*"`
Expected: FAIL — `NetworkReachabilityProbe` is unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.workflow.orchestrator.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Short-timeout HEAD probe. Deliberately uses its OWN OkHttpClient — never
 * HttpClientFactory — so it has no RetryInterceptor and no reporting interceptor
 * (a probe must not feed itself back into the detector).
 */
class NetworkReachabilityProbe(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
) : ReachabilityProbe {

    override suspend fun isReachable(targetUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(normalize(targetUrl)).head().build()
            client.newCall(req).execute().use { true } // any response = host answered
        } catch (e: Exception) {
            // IOException (connect/timeout) or IllegalArgumentException (bad URL) = unreachable.
            // CancellationException is an Exception subtype but withContext rethrows it for us.
            false
        }
    }

    private fun normalize(s: String): String {
        val withScheme = if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "*NetworkReachabilityProbeTest*"`
Expected: PASS (both cases).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/network/NetworkReachabilityProbe.kt core/src/test/kotlin/com/workflow/orchestrator/core/network/NetworkReachabilityProbeTest.kt
git commit -m "feat(core): NetworkReachabilityProbe (short-timeout, factory-bypassing HEAD probe)"
```

---

### Task 3: NetworkStateService (the authority)

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/network/NetworkStateService.kt`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/network/NetworkStateServiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkStateServiceTest {

    private class FakeProbe(@Volatile var reachable: Boolean) : ReachabilityProbe {
        override suspend fun isReachable(targetUrl: String) = reachable
    }

    @Test
    fun `reportFailure flips OFFLINE, reportSuccess flips ONLINE`() = runTest {
        val svc = NetworkStateService(CoroutineScope(StandardTestDispatcher(testScheduler)), FakeProbe(false))
        assertEquals(NetworkState.ONLINE, svc.state.value)
        svc.reportFailure("https://sg.example.com")
        assertEquals(NetworkState.OFFLINE, svc.state.value)
        svc.reportSuccess()
        assertEquals(NetworkState.ONLINE, svc.state.value)
    }

    @Test
    fun `checkNow returns OFFLINE when probe unreachable and ONLINE when reachable`() = runTest {
        val probe = FakeProbe(false)
        val svc = NetworkStateService(CoroutineScope(StandardTestDispatcher(testScheduler)), probe)
        assertEquals(NetworkState.OFFLINE, svc.checkNow("https://sg.example.com"))
        probe.reachable = true
        assertEquals(NetworkState.ONLINE, svc.checkNow("https://sg.example.com"))
    }

    @Test
    fun `awaitOnline suspends while OFFLINE and resumes on reportSuccess`() = runTest {
        val svc = NetworkStateService(CoroutineScope(StandardTestDispatcher(testScheduler)), FakeProbe(false))
        svc.reportFailure("https://sg.example.com")
        var resumed = false
        val waiter = launch { resumed = svc.awaitOnline(60_000); }
        testScheduler.runCurrent()
        assertFalse(resumed) // still suspended
        svc.reportSuccess()
        testScheduler.runCurrent()
        waiter.join()
        assertTrue(resumed)
    }

    @Test
    fun `isWakeGap true only past tick + threshold`() {
        assertFalse(NetworkStateService.isWakeGap(10_000))
        assertTrue(NetworkStateService.isWakeGap(120_000))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "*NetworkStateServiceTest*"`
Expected: FAIL — `NetworkStateService` is unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.workflow.orchestrator.core.network

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-level connectivity authority (the VPN tunnel is per-machine, not per-project).
 * Constructor takes the platform-injected [cs]; never allocate a CoroutineScope here.
 */
@Service(Service.Level.APP)
class NetworkStateService(
    private val cs: CoroutineScope,
    private val probe: ReachabilityProbe = NetworkReachabilityProbe()
) : NetworkProbe {

    private val log = Logger.getInstance(NetworkStateService::class.java)
    private val _state = MutableStateFlow(NetworkState.ONLINE)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    @Volatile private var lastFailedTarget: String? = null
    private val probeLoopActive = AtomicBoolean(false)

    init { startWakeWatchdog() }

    override fun reportSuccess() {
        if (_state.value != NetworkState.ONLINE) log.info("[Network] back ONLINE (request succeeded)")
        _state.value = NetworkState.ONLINE
    }

    override fun reportFailure(targetUrl: String) {
        lastFailedTarget = targetUrl
        if (_state.value == NetworkState.ONLINE) {
            log.info("[Network] transport failure on $targetUrl — OFFLINE")
            _state.value = NetworkState.OFFLINE
        }
        ensureProbeLoop()
    }

    override suspend fun checkNow(targetUrl: String?): NetworkState {
        val target = targetUrl ?: lastFailedTarget ?: return _state.value
        val reachable = probe.isReachable(target)
        _state.value = if (reachable) NetworkState.ONLINE else NetworkState.OFFLINE
        if (!reachable) { lastFailedTarget = target; ensureProbeLoop() }
        return _state.value
    }

    override suspend fun awaitOnline(timeoutMs: Long): Boolean {
        if (_state.value == NetworkState.ONLINE) return true
        return withTimeoutOrNull(timeoutMs) { _state.first { it == NetworkState.ONLINE }; true } ?: false
    }

    /** The single active prober — discovers reconnection while pollers are paused. */
    private fun ensureProbeLoop() {
        if (!probeLoopActive.compareAndSet(false, true)) return
        // Launch on the injected scope (no forced dispatcher) so unit tests with a
        // TestDispatcher stay in virtual time. NetworkReachabilityProbe does its own
        // withContext(Dispatchers.IO) for the actual blocking HEAD.
        cs.launch {
            try {
                var attempt = 1
                while (_state.value != NetworkState.ONLINE) {
                    val target = lastFailedTarget
                    if (target != null && probe.isReachable(target)) { reportSuccess(); break }
                    delay((PROBE_BASE_MS * (1L shl (attempt - 1).coerceAtMost(5))).coerceAtMost(PROBE_MAX_MS))
                    attempt++
                }
            } finally {
                probeLoopActive.set(false)
                // Re-arm guard: if a failure was re-signaled while we were exiting (TOCTOU
                // between the loop break and clearing the flag), relaunch so we don't get
                // permanently stuck OFFLINE with no prober running.
                if (_state.value != NetworkState.ONLINE) ensureProbeLoop()
            }
        }
    }

    /** Clock-gap wake detector: a large jump between ticks means the machine slept. */
    private fun startWakeWatchdog() {
        cs.launch {
            var last = System.currentTimeMillis()
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val now = System.currentTimeMillis()
                val gap = now - last
                last = now
                if (isWakeGap(gap)) {
                    log.info("[Network] wake detected (clock gap ${gap}ms) — reprobing")
                    _state.value = NetworkState.RECONNECTING
                    val target = lastFailedTarget
                    val reachable = target == null || probe.isReachable(target)
                    _state.value = if (reachable) NetworkState.ONLINE else NetworkState.OFFLINE
                    if (!reachable) ensureProbeLoop()
                }
            }
        }
    }

    companion object {
        private const val WATCHDOG_TICK_MS = 10_000L
        private const val WAKE_GAP_THRESHOLD_MS = 30_000L
        private const val PROBE_BASE_MS = 2_000L
        private const val PROBE_MAX_MS = 30_000L

        /** A gap past one tick + threshold indicates the process was suspended (sleep/lock). */
        fun isWakeGap(gapMs: Long): Boolean = gapMs > WATCHDOG_TICK_MS + WAKE_GAP_THRESHOLD_MS

        fun getInstanceOrNull(): NetworkProbe? =
            ApplicationManager.getApplication()?.getService(NetworkStateService::class.java)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "*NetworkStateServiceTest*"`
Expected: PASS (4 cases).

- [ ] **Step 5: Register the service in plugin.xml**

The `@Service(Service.Level.APP)` annotation auto-registers the service (proven by `ArtifactResultRegistry`, which is annotation-only). But the dominant convention in this repo is to ALSO list services in `plugin.xml` (`ConnectionSettings`, `AutomationSettingsService`, `AgentService`, `HealthCheckService` all do, and coexist with the annotation without a duplicate-registration crash on the 2025.1+ target). Match that convention so registration is unambiguous. In `src/main/resources/META-INF/plugin.xml`, add after the `AutomationSettingsService` entry (current line 308):
```xml
        <applicationService
            serviceImplementation="com.workflow.orchestrator.core.network.NetworkStateService"/>
```

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/network/NetworkStateService.kt core/src/test/kotlin/com/workflow/orchestrator/core/network/NetworkStateServiceTest.kt src/main/resources/META-INF/plugin.xml
git commit -m "feat(core): NetworkStateService — APP authority with probe loop + clock-gap wake watchdog"
```

---

### Task 4: Reactive reporting interceptor wired into HttpClientFactory

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/http/NetworkStateReportingInterceptor.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpClientFactory.kt:36-48`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/http/NetworkStateReportingInterceptorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.network.NetworkProbe
import com.workflow.orchestrator.core.network.NetworkState
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test

class NetworkStateReportingInterceptorTest {

    private fun probe(): NetworkProbe = mockk(relaxed = true) {
        io.mockk.every { state } returns MutableStateFlow(NetworkState.ONLINE)
    }

    @Test
    fun `reports success on any HTTP response`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(500)); start() }
        val p = probe()
        val client = OkHttpClient.Builder().addInterceptor(NetworkStateReportingInterceptor { p }).build()
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        verify { p.reportSuccess() }
        server.shutdown()
    }

    @Test
    fun `reports failure on transport IOException`() {
        // Point at a closed port so connect throws.
        val server = MockWebServer().apply { start() }
        val url = server.url("/").toString()
        server.shutdown() // now nothing is listening
        val p = probe()
        val client = OkHttpClient.Builder().addInterceptor(NetworkStateReportingInterceptor { p }).build()
        runCatching { client.newCall(Request.Builder().url(url).build()).execute() }
        verify { p.reportFailure(any()) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "*NetworkStateReportingInterceptorTest*"`
Expected: FAIL — `NetworkStateReportingInterceptor` is unresolved.

- [ ] **Step 3: Write the interceptor**

`NetworkStateReportingInterceptor.kt`:
```kotlin
package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.network.NetworkProbe
import com.workflow.orchestrator.core.network.NetworkStateService
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Feeds the connectivity authority from every service client. A completed response
 * (even 4xx/5xx) means the server was reached -> reportSuccess. An IOException means
 * the transport is down -> reportFailure. Added OUTERMOST (before RetryInterceptor)
 * so it reports the final outcome after any retries.
 */
class NetworkStateReportingInterceptor(
    private val probeProvider: () -> NetworkProbe? = { NetworkStateService.getInstanceOrNull() }
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val origin = "${url.scheme}://${url.host}:${url.port}"
        return try {
            val response = chain.proceed(chain.request())
            probeProvider()?.reportSuccess()
            response
        } catch (e: IOException) {
            probeProvider()?.reportFailure(origin)
            throw e
        }
    }
}
```

- [ ] **Step 4: Wire into the base client**

In `HttpClientFactory.kt`, modify the `baseClient` builder (currently lines 36-48) to add the reporting interceptor BEFORE `RetryInterceptor`:
```kotlin
    private val baseClient: OkHttpClient by lazy {
        sharedPool.newBuilder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(NetworkStateReportingInterceptor())
            .addInterceptor(RetryInterceptor())
            .addNetworkInterceptor(SensitiveEndpointNoCacheInterceptor())
            .build()
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "*NetworkStateReportingInterceptorTest*"`
Expected: PASS (both cases).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/http/NetworkStateReportingInterceptor.kt core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpClientFactory.kt core/src/test/kotlin/com/workflow/orchestrator/core/http/NetworkStateReportingInterceptorTest.kt
git commit -m "feat(core): report transport success/failure to NetworkStateService from all service clients"
```

---

### Task 5: SmartPoller pause-while-offline + stagger-resume

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/polling/SmartPoller.kt:16-22` (constructor) and `:39-69` (loop)
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/polling/SmartPollerOfflineGateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.core.polling

import com.workflow.orchestrator.core.network.NetworkProbe
import com.workflow.orchestrator.core.network.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SmartPollerOfflineGateTest {

    private class FakeProbe(initial: NetworkState) : NetworkProbe {
        private val s = MutableStateFlow(initial)
        override val state: StateFlow<NetworkState> = s
        fun setOnline() { s.value = NetworkState.ONLINE }
        override fun reportFailure(targetUrl: String) {}
        override fun reportSuccess() { s.value = NetworkState.ONLINE }
        override suspend fun checkNow(targetUrl: String?) = s.value
        override suspend fun awaitOnline(timeoutMs: Long): Boolean { s.first { it == NetworkState.ONLINE }; return true }
    }

    @Test
    fun `does not poll while offline, polls after reconnect`() = runTest {
        val calls = AtomicInteger(0)
        val probe = FakeProbe(NetworkState.OFFLINE)
        val poller = SmartPoller(
            name = "test",
            baseIntervalMs = 1_000,
            maxIntervalMs = 10_000,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            action = { calls.incrementAndGet(); false },
            networkProbe = probe,
        )
        poller.start()
        testScheduler.runCurrent()
        assertEquals(0, calls.get()) // gated while OFFLINE
        probe.setOnline()
        // Bounded advance — the poller loops forever, so advanceUntilIdle() would never return.
        // 2x baseInterval covers the resume stagger (0..baseInterval) plus the first poll.
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        poller.stop()
        org.junit.jupiter.api.Assertions.assertTrue(calls.get() >= 1) // resumed after reconnect
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "*SmartPollerOfflineGateTest*"`
Expected: FAIL — `SmartPoller` has no `networkProbe` parameter.

- [ ] **Step 3: Add the constructor parameter**

In `SmartPoller.kt`, extend the constructor (current lines 16-22). Add the import `import com.workflow.orchestrator.core.network.NetworkProbe`, `import com.workflow.orchestrator.core.network.NetworkState`, and `import com.workflow.orchestrator.core.network.NetworkStateService`, then:
```kotlin
class SmartPoller(
    private val name: String,
    private val baseIntervalMs: Long = 30_000,
    private val maxIntervalMs: Long = 300_000,
    private val scope: CoroutineScope,
    private val action: suspend () -> Boolean,  // returns true if data changed
    private val networkProbe: NetworkProbe? = NetworkStateService.getInstanceOrNull(),
) {
```

- [ ] **Step 4: Add the gate at the top of the loop**

In `start()`, insert the gate as the FIRST statement inside `while (isActive) {` (before the `try { val changed = action() ... }` block at current line 41):
```kotlin
            while (isActive) {
                // Connectivity gate: pause (don't fire requests) while offline; on reconnect
                // reset backoff and add a per-poller jittered stagger to avoid a reconnect stampede.
                val probe = networkProbe
                if (probe != null && probe.state.value != NetworkState.ONLINE) {
                    probe.awaitOnline(maxIntervalMs)
                    currentBackoff = 1.0
                    delay(Random.nextLong(baseIntervalMs + 1))
                }
                try {
                    val changed = action()
                    // ... unchanged ...
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:test --tests "*SmartPollerOfflineGateTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/polling/SmartPoller.kt core/src/test/kotlin/com/workflow/orchestrator/core/polling/SmartPollerOfflineGateTest.kt
git commit -m "feat(core): SmartPoller pauses while offline and stagger-resumes on reconnect"
```

---

### Task 6: Agent fail-fast on confirmed offline

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/LoopResult.kt:5-12` (add enum variant)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt:454-476` (constructor) and `:1097` (seam)
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopOfflineFailFastTest.kt`

- [ ] **Step 1: Add the OFFLINE failure reason**

In `LoopResult.kt`, add `OFFLINE` to the enum:
```kotlin
enum class FailureReason {
    MAX_ITERATIONS,
    DOOM_LOOP,
    EMPTY_RESPONSES,
    API_ERROR,
    NO_TOOLS_USED,
    EXCEPTION,
    OFFLINE
}
```

- [ ] **Step 2: Write the failing test**

Open `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopVisionFallbackTest.kt` and use it as the canonical harness for constructing `AgentLoop` in a test (fake brain, minimal tools, MockK project, read-action shim). Copy that setup into the new test file, then assert the offline path. The new test's distinguishing pieces:

```kotlin
// A fake NetworkProbe that always reports OFFLINE.
private class OfflineProbe : com.workflow.orchestrator.core.network.NetworkProbe {
    private val s = kotlinx.coroutines.flow.MutableStateFlow(com.workflow.orchestrator.core.network.NetworkState.OFFLINE)
    override val state = s
    override fun reportFailure(targetUrl: String) {}
    override fun reportSuccess() {}
    override suspend fun checkNow(targetUrl: String?) = com.workflow.orchestrator.core.network.NetworkState.OFFLINE
    override suspend fun awaitOnline(timeoutMs: Long) = false
}

@Test
fun `network error while offline fails fast with OFFLINE reason and no retries`() = runTest {
    // brain stubbed to return ApiResult.Error(ErrorType.NETWORK_ERROR) on chatStream
    // (follow the fake-brain pattern from AgentLoopVisionFallbackTest)
    val loop = AgentLoop(
        // ... all the same args as the vision-fallback harness ...
        networkProbe = OfflineProbe(),
        llmProbeUrl = "https://sg.example.com",
    )
    val result = loop.run("do something")
    assertTrue(result is LoopResult.Failed)
    assertEquals(FailureReason.OFFLINE, (result as LoopResult.Failed).reason)
    // brain.chatStream invoked exactly once — no retry budget consumed
    verify(exactly = 1) { /* brain.chatStream mock */ }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*AgentLoopOfflineFailFastTest*"`
Expected: FAIL — `AgentLoop` has no `networkProbe`/`llmProbeUrl` params.

- [ ] **Step 4: Add the constructor parameters**

In `AgentLoop.kt`, append two params after `streamingEditCallback` (current line 475, before the closing `) {` on 476):
```kotlin
    private val streamingEditCallback: StreamingEditCallback? = null,
    private val networkProbe: com.workflow.orchestrator.core.network.NetworkProbe? = null,
    private val llmProbeUrl: String? = null,
) {
```

- [ ] **Step 5: Insert the fail-fast branch at the retry seam**

In `AgentLoop.kt`, locate line 1097 (`val isTimeoutError = apiResult.type in TIMEOUT_ERRORS`). Insert the offline check immediately AFTER that line and BEFORE `val maxRetries = ...` (line 1098):
```kotlin
                val isTimeoutError = apiResult.type in TIMEOUT_ERRORS
                // Fail-fast on confirmed offline: a live probe says the tunnel is down (VPN still
                // reconnecting after unlock). Don't burn the retry budget into a dead socket — fail
                // now so the UI can offer a one-click Retry. checkNow() also flips global state to
                // OFFLINE, which pauses the pollers and arms the reconnection probe loop.
                if (isTimeoutError && networkProbe != null) {
                    val netState = networkProbe.checkNow(llmProbeUrl)
                    if (netState != com.workflow.orchestrator.core.network.NetworkState.ONLINE) {
                        LOG.warn("[Loop] Confirmed offline ($netState) on ${apiResult.type} — failing fast for manual retry")
                        onDebugLog?.invoke("warn", "offline", "Network offline — failing turn for manual retry", mapOf("errorType" to apiResult.type.name))
                        return makeFailed(
                            error = "You appear to be offline (network unreachable). This often happens right after unlocking when the VPN is still reconnecting. Click Retry once you're back online.",
                            iterations = iteration,
                            reason = FailureReason.OFFLINE,
                        )
                    }
                }
                val maxRetries = if (isTimeoutError) MAX_TIMEOUT_RETRIES else MAX_API_RETRIES
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*AgentLoopOfflineFailFastTest*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/LoopResult.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopOfflineFailFastTest.kt
git commit -m "feat(agent): fail fast on confirmed offline instead of burning the timeout-retry budget"
```

---

### Task 7: Wire NetworkProbe + LLM probe URL into AgentService

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:2051-2171` (AgentLoop construction)

- [ ] **Step 1: Pass the two new arguments**

In `AgentService.kt`, at the `AgentLoop(...)` construction site, add two named arguments after `streamingEditCallback = streamingEditCallback,` (current line 2170, just before the closing `)` on 2171):
```kotlin
                    streamingEditCallback = streamingEditCallback,
                    networkProbe = com.workflow.orchestrator.core.network.NetworkStateService.getInstanceOrNull(),
                    llmProbeUrl = com.workflow.orchestrator.core.settings.ConnectionSettings.getInstance()
                        .state.sourcegraphUrl.trimEnd('/').ifBlank { null },
                )
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(agent): wire NetworkStateService + Sourcegraph probe URL into AgentLoop"
```

---

### Task 8: Offline-specific caption on the existing Retry pill

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt:2549-2559` (LoopResult.Failed handler)

- [ ] **Step 1: Add the offline caption branch**

In `AgentController.kt`, in the `is LoopResult.Failed ->` arm, replace the `if (lastTaskText != null) { ... }` block (current lines 2549-2558) with:
```kotlin
                    // Gap 17 + offline: Show retry/continue button based on failure type
                    if (lastTaskText != null) {
                        val isMaxIter = result.reason == FailureReason.MAX_ITERATIONS
                        val isOffline = result.reason == FailureReason.OFFLINE
                        val kind = if (isMaxIter) "continue" else "retry"
                        val caption = when {
                            isMaxIter -> "The agent worked for many iterations without finishing. Click Continue to keep going."
                            isOffline -> "You appear to be offline — the VPN may still be reconnecting after unlock. Click Retry once you're back online."
                            else -> "Something went wrong while running the task."
                        }
                        dashboard.showRetryButton(kind, caption)
                    }
```

The existing `retryState` → `kotlinBridge.retryLastTask()` → `onRetryLastTask` → re-run path needs no change; the offline failure reuses it verbatim.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): offline-specific caption on the Retry pill"
```

---

### Task 9: Full verification

- [ ] **Step 1: Run the full affected test suites**

Run:
```bash
./gradlew :core:test :agent:test
```
Expected: BUILD SUCCESSFUL — all existing tests plus the 4 new test classes pass.

- [ ] **Step 2: Plugin verification**

Run:
```bash
./gradlew verifyPlugin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Update docs in the same change**

Add a short "Network Connectivity Resilience" subsection to `core/CLAUDE.md` (under SmartPoller / a new "NetworkStateService" heading) and `agent/CLAUDE.md` (under "Error Handling" — note the offline fail-fast at the retry seam and `FailureReason.OFFLINE`). Commit:
```bash
git add core/CLAUDE.md agent/CLAUDE.md
git commit -m "docs: document NetworkStateService connectivity authority + agent offline fail-fast"
```

---

## Manual smoke test (post-implementation, requires a real IDE + VPN)

1. Start a long agent turn, then disconnect Wi-Fi / drop the VPN mid-turn.
2. Expect: the turn fails within ~3-5s (not ~2 minutes) with the offline caption + a Retry button.
3. Reconnect, click Retry → the session resumes from where it left off.
4. Observe a poller tab (PR/Build): while disconnected it should go quiet (no error spam in the log); on reconnect it refreshes within ~baseInterval, staggered.
5. Lock the laptop for >1 minute, unlock: pollers refresh promptly rather than waiting out a stale backoff.
