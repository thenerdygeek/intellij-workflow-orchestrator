# Internal Meta-Tools Audit

**Date:** 2026-04-21
**Platform:** IntelliJ IDEA Ultimate 2025.1.7
**Scope:** 10 internal meta-tools (excludes spring — already upgraded in the Ultimate API Adoption plan; excludes external integrations jira/bamboo/sonar/bitbucket)
**Methodology:** Research-agent dump + controller verification of the two largest claims. One false-positive (CoverageTool "incomplete") was rejected after verification. One real bug (RuntimeExecTool listener leak) confirmed.

## Executive summary

**8 tools are solid** (Tier S/A), with correct API usage, proper `RunInvocation` disposal patterns where applicable, and clear necessity. **1 tool has a real listener leak** (`runtime_exec.get_test_results` after timeout). **1 tool is redundant** with existing `run_command` + `edit_file` flows (`runtime_config`). The biggest upgrade win is a 10-line listener-leak fix in `RuntimeExecTool`; the biggest simplification win is deprecating `RuntimeConfigTool`.

---

## Tier S — Solid (well-designed, correct API use, clear necessity)

### `JavaRuntimeExecTool` (`runtime/JavaRuntimeExecTool.kt`)

Exemplary design. `run_tests` uses the established `RunInvocation` pattern (documented in `agent/CLAUDE.md`) with proper cleanup: `try { … } finally { Disposer.dispose(invocation) }` at ~line 527; all process listeners routed through `invocation.attachProcessListener()` (~line 604); all `TestResultsViewer.EventsListener` instances wrapped via `invocation.attachListener()` (~line 630). `BuildSystemValidator` pre-flight checks before dispatch catch main-source failures, zero-test modules, and unregistered subprojects with actionable suggestions. `compile_module` uses `ProjectTaskManager.build()` with comprehensive compile-error formatting via shared `formatCompileErrors()`. Output spilling via `spillOrFormat()` for large result sets. No reflection except safely-gated Spring/JUnit config factory lookups.

### `PythonRuntimeExecTool` (`runtime/PythonRuntimeExecTool.kt`)

Mirrors Java design intentionally. `runPytestNative()` implements `RunInvocation` pattern correctly — no manual listener cleanup needed. Shell fallback via `executePytestRun()` is a clean delegation. `compile_module` via `python -m py_compile` with proper interpreter resolution. Output spilling on large stderr. No API issues.

### `CoverageTool` (`runtime/CoverageTool.kt`)

1083 lines, fully implemented. `executeRunWithCoverage` follows the `RunInvocation` + `CompletableDeferred<CoverageSnapshot?>` pattern, registers a listener disposable wired to `invocation.onDispose`, extracts results via the shared `interpretTestRoot()` utility, and resumes the continuation with a `CoverageRunResult`. `get_file_coverage` reads coverage data via `CoverageDataManager` and walks the per-file tuples. All listener lifecycles are via Disposable parents. (Research agent's "incomplete" claim was a false positive caused by a read-limit hit at line 200; verified against the full file — implementation is complete and production-ready.)

### `DebugBreakpointsTool` (`debug/DebugBreakpointsTool.kt`)

Correct use of `XDebuggerManager`, `XDebuggerUtil.setLineBreakpoint()`, `JavaExceptionBreakpointType`, `JavaFieldBreakpointType`, `JavaMethodBreakpointType`. Condition/pass-count/suspend-policy parameters map correctly to IntelliJ APIs. Remote debug attach via `RemoteConfigurationType` reflection properly gated. No listener leaks observed — breakpoint operations are one-shot API calls. One known `@Internal` usage (`XBreakpointManagerImpl`) flagged by verifyPlugin; documented as deliberate in the file.

### `DebugStepTool` (`debug/DebugStepTool.kt`)

Stateless — all operations are one-shot RPC calls on the debug session (step_over, step_into, step_out, pause, resume, stop, run_to_cursor, get_state). No resource leaks, no listener management required. Correct use of `XDebugSession` APIs.

### `BuildTool` (`framework/BuildTool.kt` + `framework/build/*Action.kt`)

Thin dispatcher over 11 action implementations (maven_dependencies, gradle_tasks, project_modules, module_dependency_graph, maven_dependency_tree, maven_effective_pom, maven_plugins, maven_profiles, maven_properties, pip/poetry/uv/pytest actions). Each action uses the correct API: `MavenProjectsManager`, `GradleBuildSystem`, `ProjectFileIndex`, `ModuleRootManager`. Output spilling delegated to each action. No leaks observed.

### `ProjectStructureTool` (`psi/ProjectStructureTool.kt`)

14 actions covering read (resolve_file, module_detail, topology, list_sdks/libraries/facets) and write (refresh_external_project, add_source_root, set_module_sdk, set_module_dependency, remove_module_dependency, set_language_level, add_content_root, remove_content_root). Reads wrapped in `ReadAction.compute()`; writes in `WriteAction`. Correct APIs: `ModuleManager`, `ProjectFileIndex`, `ModuleRootManager`, `OrderEnumerator`, `ExternalSystemProjectsManager`. No leaks.

---

## Tier A — Tight with minor improvement opportunities

### `DebugInspectTool` (`debug/DebugInspectTool.kt`)

Correct HotSwap API use via `HotSwapUI` / `HotSwapStatusListener`. Expression evaluation via `XDebuggerEvaluator`. Variables, stack frames, thread dump all use public `XDebugSession` APIs.

**Minor issue:** `thread_dump` action declares an `include_daemon` parameter but the parameter isn't wired to the output filtering — threads are always shown regardless. **Fix:** either implement filtering or remove from the description. ~5 lines.

---

## Tier B — Significant issues

### `RuntimeExecTool.awaitTestingFinished()` listener leak — REAL BUG

**Location:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecTool.kt:483-494`

```kotlin
private suspend fun awaitTestingFinished(resultsViewer: TestResultsViewer, timeoutMs: Long) {
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : TestResultsViewer.EventsListener {
                override fun onTestingFinished(sender: TestResultsViewer) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            resultsViewer.addEventsListener(listener)   // ← no removal on timeout
        }
    }
}
```

`addEventsListener` registers the listener one-way; `TestResultsViewer` exposes no symmetric `removeEventsListener` API (framework quirk). When the timeout fires, `withTimeoutOrNull` cancels the coroutine but the listener remains registered on the viewer. Every `runtime_exec(action=get_test_results)` call that times out leaks one listener object for the viewer's lifetime.

**Impact:** `runtime_exec` is observation-only (doesn't launch runs) so the leak footprint per call is small, but it accumulates — any agent session that hits a test-result timeout leaks. `get_test_results` is the only caller of this path (~line 409-416 of the same file).

**Fix:** mirror `JavaRuntimeExecTool.kt`'s pattern — call `invocation.attachListener()` which wraps the listener in a proxy that gates post-dispose callbacks and registers the whole thing for cleanup when the invocation disposes. Since `runtime_exec` doesn't own the viewer's run (`get_test_results` queries an existing descriptor the user or `java_runtime_exec` created), the fix needs a per-call `Disposable` registered on the project-scoped parent. ~10-15 lines. Pattern already proven in `RunInvocation.attachListener`.

**Regression test:** extend `RunInvocationLeakTest` (agent/src/test/...) with an `awaitTestingFinished` source-text assertion matching the existing `addEventsListener` / `Disposer.register` / `RunInvocation` rule.

### `RuntimeExecTool.awaitProcessTermination()` `addProcessListener` — secondary concern

**Location:** same file, ~line 462

Uses 1-arg `addProcessListener(listener)` with `continuation.invokeOnCancellation { removeProcessListener(listener) }`. This works but has a narrow race: the listener can fire and hold a reference to the (now-cancelled) continuation before the cancellation handler runs. **Fix:** switch to 2-arg `addProcessListener(listener, disposable)` form with a per-call Disposable, matching the pattern already enforced in `JavaRuntimeExecTool` by `RunInvocationLeakTest`. ~3 lines.

---

## Tier C — Questionable necessity

### `RuntimeConfigTool` (`runtime/RuntimeConfigTool.kt`)

Four actions (`get_run_configurations`, `create_run_config`, `modify_run_config`, `delete_run_config`) wrapping `RunManager` APIs.

**The LLM already has cheaper alternatives for the same work:**

- `get_run_configurations`: `read_file` on `.idea/runConfigurations/*.xml` returns the same data in a parseable form.
- `create_run_config`: `create_file` on the corresponding XML file does the job for Application / JUnit / Maven / Gradle configs. The schema is stable and well-documented.
- `modify_run_config`, `delete_run_config`: `edit_file` and `run_command rm` equivalents.

**The one technical reason to keep it:** a subtle IntelliJ quirk where creating a transient `RunnerAndConfigurationSettings` without calling `RunManager.setTemporaryConfiguration(settings)` avoids stealing the user's selected config — documented in commit `9b164bf3`. `java_runtime_exec` and `coverage` use this internally, but the pattern isn't exposed through `RuntimeConfigTool`'s action surface anyway.

**Cost of keeping:**
- 12 parameters on `create_run_config` (name, type, main_class, test_class, test_method, module, env_vars, replace_env_vars, vm_options, program_args, working_dir, active_profiles, port)
- Reflection on 5 config types (Application, Spring Boot, JUnit, Gradle, Remote)
- Schema tokens in every tool-search result

**Recommendation:** deprecate and remove. If the LLM needs environment variables for a test run, it should use `java_runtime_exec(action=run_tests, env=...)` directly (requires adding an `env` param there — trivial). For one-off custom runs, `run_command` with the relevant shell command covers the use case.

**Effort:** 30 min (remove tool + update prompt catalog) + 1 hour review to confirm no internal code path depends on the tool's action-side wrappers (vs. `RunManager` APIs directly).

---

## Top findings (prioritized)

1. **[P0] Fix `RuntimeExecTool.awaitTestingFinished()` listener leak.** Pattern already proven in `JavaRuntimeExecTool`; just port it. Adds regression coverage to `RunInvocationLeakTest`. ~15 lines. *Blocking production use of `get_test_results` in long-running agent sessions.*

2. **[P1] Deprecate `RuntimeConfigTool`.** 4 actions × 12-parameter schema each for functionality the LLM can do via `read_file`/`edit_file`/`run_command`. Token-budget win, consolidation win. *Not blocking.*

3. **[P2] Fix `RuntimeExecTool.awaitProcessTermination()` `addProcessListener` to use 2-arg Disposable form.** Tightens a race that `RunInvocationLeakTest` already rejects elsewhere. ~3 lines.

4. **[P3] Fix `DebugInspectTool.thread_dump` `include_daemon` parameter.** Either wire it to output filtering or remove it from the description. ~5 lines.

5. **[P3] Document `@Internal` API usage in `RuntimeConfigTool`.** File has several `getPersistentData()`-style reflection calls that the verifier flags; class-level comment should list them for future maintainer clarity. ~15 min.

---

## Follow-up tickets

| # | Ticket | Effort | Priority |
|---|---|---|---|
| 1 | Fix `RuntimeExecTool.awaitTestingFinished()` listener leak + add `RunInvocationLeakTest` regression | 1-2h | P0 |
| 2 | Switch `RuntimeExecTool.awaitProcessTermination()` to 2-arg `addProcessListener(listener, disposable)` | 30m | P2 |
| 3 | Deprecate `RuntimeConfigTool`: remove from ToolRegistry, audit internal callers, update prompt surfaces | 1-2h | P1 |
| 4 | Fix `DebugInspectTool.thread_dump` `include_daemon` wiring | 30m | P3 |
| 5 | Document `@Internal` reflection usage in `RuntimeConfigTool` class-level KDoc | 15m | P3 |

---

## Methodology note (for future audits)

The research agent's first pass included a false-positive "CoverageTool is incomplete" finding caused by hitting a read limit at line 200 of a 1083-line file. Controller verified via `wc -l` + targeted `grep` for later-file symbols before accepting claims. Pattern worth repeating: **any claim that an implementation is "cut off" or "incomplete" must be verified against the file's actual size and later-line content** before being promoted to a finding. Read-limit artifacts mimic real incompleteness, and landing them in a report wastes engineer time on ghost bugs.

---

## 2026-04-21 addendum — boot_autoconfig and boot_actuator audit

### boot_autoconfig

**Verdict: keep PSI-only. No suitable model-backed replacement exists.**

The research doc (`2026-04-21-intellij-spring-boot-metadata-api-signatures.md`) catalogues every reflectively-accessible class in `com.intellij.spring.boot`. Three candidates were evaluated against the hypothesis that a structured list of autoconfig FQNs can be obtained without annotation scanning:

1. **`AutoConfigureMetadataIndex`** (`com.intellij.spring.boot.model.autoconfigure.AutoConfigureMetadataIndex`) — This is a `FileBasedIndexExtension<String, AutoConfigureMetadata>` that indexes `spring-autoconfigure-metadata.properties` and `spring-boot/spring-autoconfigure.imports`. Its Kotlin companion exposes exactly one utility: `findAutoConfigureMetadata(PsiClass)` — a per-class reverse lookup (given a class, return its metadata record), not a forward enumerator. The `FileBasedIndex.getInstance().getValues(NAME, key, scope)` usage pattern would require already knowing all the FQN keys, making it circular.

2. **`AutoConfigClassCollector`** (`com.intellij.spring.boot.model.autoconfigure.AutoConfigClassCollector`) — The only public methods are `collectConfigurationClasses(EnableAutoConfiguration, ConditionalOnEvaluationContext)` and `collectConfigurationClasses(ImportAutoConfiguration, ConditionalOnEvaluationContext)`. Both require a JAM element (`@EnableAutoConfiguration` or `@ImportAutoConfiguration` on a specific `PsiClass`) plus a fully constructed `ConditionalOnEvaluationContext`. This is a resolution helper, not a module-wide lister.

3. **`SpringBootAutoConfigClassFilterService`** (`com.intellij.spring.boot.model.autoconfigure.SpringBootAutoConfigClassFilterService`) — The only method (verified from `SpringBootAutoConfigClassFilterServiceImpl.class` via `javap -p`) is `filterByConditionalOnClass(Module, List<PsiClass>)`. It takes an existing list and filters it; it does not produce the list. A caller would still need to supply the initial `List<PsiClass>` from somewhere — i.e., the PSI annotation scan.

The current implementation (`SpringBootAutoConfigAction.kt`) is the correct approach: `AnnotatedElementsSearch.searchPsiClasses(@Configuration/@AutoConfiguration, scope)` is the canonical IntelliJ Platform way to enumerate annotation-carrying classes. The Spring Boot plugin's own APIs presuppose that the caller already holds `PsiClass` references obtained via PSI search. The only upgrade available via model APIs — using `AutoConfigureMetadataIndex` to enrich individual classes with ordering metadata (`autoConfigureOrder`, `autoConfigureAfter`, `autoConfigureBefore`) after the PSI scan — is an additive improvement on the output format, not a replacement for the scan itself.

Self-gating pattern: `SpringBootAutoConfigClassFilterService.getInstance()` is reached via `Class.forName("...SpringBootAutoConfigClassFilterService").getMethod("getInstance").invoke(null)`, the same pattern already established in `SpringBootMetadataResolver`. This is available if ordering enrichment is ever added; the current tool does not need it.

### boot_actuator

**Verdict: keep as-is. No duplication with the `endpoints` tool on Ultimate.**

The complete `<microservices.endpointsProvider>` registry was obtained by inspecting every plugin jar in `ideaIU-2025.1.7-aarch64/plugins/` for `plugin.xml` entries under that extension point. The registered providers are:

| Plugin jar | Provider class |
|---|---|
| `spring-boot-core.jar` | `SpringFeignClientEndpointsProvider` (Feign HTTP clients only) |
| `spring-mvc-impl.jar` | `SpringMvcControllersEndpointsProvider`, `SpringMvcFunctionalEndpointsProvider`, WebSocket, `WebTestClientFramework`, `RestOperationsFramework`, `TestRestTemplateFramework`, `MockMvcFramework`, `SpringExchangeEndpointsProvider`, `RestClientFramework` |
| `spring-boot-cloud.jar` | `SpringCloudGatewayServerProvider` |
| `grpc.jar` | `ProtoEndpointsProvider` |
| `swagger.jar` | `SwEndpointsProvider`, `AsyncapiEndpointsProvider` |
| `micronaut.jar` | `MnHttpServerFramework`, `MnHttpClientFramework`, `MnServerWebSocketFramework`, `MnClientWebSocketFramework`, `MnManagementFramework` |
| `microservices-jvm.jar` | `RetrofitEndpointsProvider`, `OkHttpEndpointsProvider` |
| `javaee-jax-rs.jar` | `RSServerFramework`, `RSClientFramework` |
| `ktor.jar` | `KtorHttpServerEndpointsProvider`, `KtorWebsocketEndpointsProvider` |

**No actuator endpoint provider appears anywhere in this registry.** The Spring Boot plugin's actuator integration uses a completely separate extension point: `spring.boot.run.endpoint`, under which `RequestMappingsEndpoint` is registered (`id="mappings"`). This extension point connects to a live running application via the Actuator JMX/HTTP bridge (`SpringBootActuatorConnector`) — it is a lifecycle/runtime data source, not a static code-analysis endpoint provider. It has no relationship to `EndpointsProvider.getAvailableProviders(project)`.

Furthermore, the `boot_actuator` action (`SpringBootActuatorAction.kt`) operates on a fundamentally different data source than `endpoints(action=list)`: it reads `management.*` properties from `application.properties`/`application.yml` files on disk (via `readManagementProperties()`) and checks `pom.xml`/`build.gradle` for the `spring-boot-starter-actuator` dependency. It surfaces actuator *configuration* — which endpoints are exposed, at which base path, with which include/exclude rules — rather than HTTP route mappings. This is complementary, not overlapping.

`boot_actuator` must stay as-is. Edition-conditional trimming (the `includeEndpointActions` pattern from `SpringTool`) does not apply here because no Ultimate API surfaces the same information. Community users and Ultimate users both need this action.

### Consolidation plan implications

Neither finding requires changes to the Spring consolidation sequence:

- **boot_autoconfig model-backed upgrade:** No task added. The PSI scan is the correct and only viable implementation. If a future Spring Boot plugin release exposes a dedicated `getAutoConfigurationClasses(Module): List<PsiClass>` utility, this decision should be revisited; the self-gating reflective pattern in `SpringBootMetadataResolver` makes it trivial to adopt. An optional additive enhancement — enriching each autoconfig entry with ordering metadata (`autoConfigureOrder`, `autoConfigureAfter`, `autoConfigureBefore`) from `AutoConfigureMetadataIndexKt.findAutoConfigureMetadata(psiClass)` — can be filed as a low-priority output-format improvement without changing the scan strategy.

- **boot_actuator edition-conditional trim:** No task added. The tool is not duplicated on Ultimate; it fills a distinct niche (static actuator configuration introspection) that no `EndpointsProvider` covers. The `includeEndpointActions=false` trim pattern is inapplicable here.
