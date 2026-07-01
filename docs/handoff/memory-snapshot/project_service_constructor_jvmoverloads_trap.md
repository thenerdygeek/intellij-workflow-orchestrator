---
name: project-service-constructor-jvmoverloads-trap
description: IntelliJ @Service with a bare Kotlin default constructor param crashes at startup; use @JvmOverloads or an explicit platform secondary constructor
metadata: 
  node_type: memory
  type: project
  originSessionId: a2d1c4e6-39d4-4b18-8de5-2b8ea4a454b0
---

⚠ TRAP (surfaced 2026-05-26): An `@Service` class whose constructor has a **bare Kotlin default param** (e.g. `class NetworkStateService(private val cs: CoroutineScope, private val probe: ReachabilityProbe = NetworkReachabilityProbe())`) crashes the plugin at **startup**, not compile/test time.

**Why:** The platform DI container instantiates services by matching a JVM constructor against a fixed allow-list of signatures (`()`, `(CoroutineScope)`, `(Application)`, `(Application,CoroutineScope)`, `(ComponentManager)` for APP; `(Project)`/`(Project,CoroutineScope)`/`(CoroutineScope)` for PROJECT). A bare default compiles to a *single* `(cs, probe)` JVM constructor + a `DefaultConstructorMarker` synthetic — the container matches **neither**, falls through to (forbidden) constructor injection of the extra param, and throws `InstantiationException` + `PluginException: do not use constructor injection`.

**Fix (three valid forms in this repo):**
- `@JvmOverloads constructor(cs, probe = Default())` — emits the zero-default `(cs)` overload the container needs, keeps the 2-arg form for test injection. **Smallest diff; preferred.** Precedent: `core/.../BuildProblemsServiceImpl.kt` (`@JvmOverloads`, `(Project, MavenProblemsProbe=...)`).
- Explicit secondary platform constructor — `constructor(project, cs) : this(realDeps...)`. Precedent: `jira/.../TicketTransitionServiceImpl.kt`.
- `@NonInjectable` on the test-only constructor.

**Verify conclusively without launching the IDE:** `javap -p` the compiled `.class` and confirm a constructor matching a supported signature exists (e.g. `NetworkStateService(kotlinx.coroutines.CoroutineScope)`). Unit tests + verifyPlugin do **not** catch this — they never instantiate via the container. This is the exact "manual smoke still pending" gap from [[project_network_connectivity_resilience_shipped]].

**How to apply:** Whenever adding/reviewing an `@Service` with more constructor params than the platform injects (`cs`/`project`), add `@JvmOverloads` (if extras are defaulted) and `javap`-verify the supported-signature overload exists.
