# Existing Test Infrastructure Audit — IntelliJ Workflow Orchestrator Plugin

**Date:** 2026-04-07
**Worktree:** `feature/agent-tools-update`
**Scope:** Inventory of test code, fixtures, and patterns to inform a new comprehensive AI agent tool test suite.

---

## 1. Executive Summary

- **176 test files** total across all 9 modules; **97 in `:agent`**, **25 in `:core`**, balance distributed across 6 feature modules. The `:pullrequest` module has **zero tests** and **no `src/test` directory** at all.
- Test stack is **JUnit 5 + MockK + kotlinx-coroutines-test + OkHttp MockWebServer + Turbine** — declared centrally in `gradle/libs.versions.toml:22-27`. JUnit 4 is on the classpath for IntelliJ Platform compatibility but never used directly.
- **No `BasePlatformTestCase` / `LightPlatformTestCase` / `HeavyPlatformTestCase` is used anywhere.** Every test runs as a plain JUnit 5 unit test with `mockk<Project>` for the `Project` reference. Tests requiring real PSI, JCEF, indexing, or write actions are deliberately skipped (the `SyntaxValidatorTest` and `GenerateExplanationToolTest` files explicitly document this gap).
- **Heavyweight platform testing is wired in the build files but never triggered:** `core/build.gradle.kts:33`, `jira/build.gradle.kts:28`, `pullrequest/build.gradle.kts:28` declare `testFramework(TestFrameworkType.Platform)` but no test class extends a Platform test base. Capacity exists; nothing uses it.
- **Of 82 `*Tool.kt` source files in `:agent`, only 44 have a matching `*ToolTest.kt`** — leaving **38 tools with no direct test coverage**, including all 11 `git` VCS tools, all 7 `ide/` tools, all 7 memory tools (covered by an aggregate `MemoryToolsTest`), all 4 database tools, and all integration meta-tool implementation paths (`JiraTool`, `SonarTool`, `BambooBuildsTool` only have metadata tests).
- The `:mock-server` module is a **standalone Ktor 3.1.1 application** (not a library, not used in agent tests today) with full Jira/Bamboo/Sonar route stubs, scenario switching, chaos middleware, and admin endpoints. It is currently consumed only by feature-module sprint/dashboard tests indirectly via MockWebServer mocking the SAME endpoints.
- Existing tool tests follow a **highly uniform "metadata + parameter validation + happy-path with TempDir + error cases" pattern** that is excellent for builtin file tools but inadequate for tools that require IntelliJ services, real HTTP, or PSI.
- **Bridge / JCEF testing is contract-only**: `BridgeContractTest.kt:13` validates JSON shapes against shared fixtures in `agent/src/test/resources/contracts/` (5 contract files). There is **no test that drives the live JCEF browser or verifies the React webview**.
- **Threading patterns are consistent**: 100% of coroutine tests use `runTest`, no test calls `runInEdtAndWait`, no test acquires a `WriteCommandAction`. All file tests use JUnit 5's `@TempDir`.
- **There is already an interactive `ToolTestingPanel.kt`** at `agent/src/main/kotlin/com/workflow/orchestrator/agent/testing/ToolTestingPanel.kt:27` — a runtime UI that lets a developer pick any registered agent tool, fill parameters, and execute it inside the IDE. This is production code (shipped to users) but is conceptually a manual testing harness we could elevate to automated tests.

---

## 2. Test Infrastructure Inventory

### 2.1 Test directories per module

| Module | Test directory | Test files | Resources |
|---|---|---|---|
| `:agent` | `agent/src/test/kotlin/...` | 97 | `agent/src/test/resources/contracts/` (5 JSON contracts + README) |
| `:core` | `core/src/test/kotlin/...` | 25 | `core/src/test/resources/fixtures/` (Bitbucket PR JSON) |
| `:jira` | `jira/src/test/kotlin/...` | 10 | `jira/src/test/resources/fixtures/` (5 JSON: boards, sprints, issue detail, transitions) |
| `:bamboo` | `bamboo/src/test/kotlin/...` | 4 | `bamboo/src/test/resources/` (empty) |
| `:sonar` | `sonar/src/test/kotlin/...` | 14 | `sonar/src/test/resources/` |
| `:automation` | `automation/src/test/kotlin/...` | 9 | `automation/src/test/resources/` |
| `:handover` | `handover/src/test/kotlin/...` | 10 | `handover/src/test/resources/` |
| `:pullrequest` | **none** | 0 | none |
| `:mock-server` | `mock-server/src/test/kotlin/...` | 7 | none (uses Ktor `testApplication` host) |

### 2.2 Build-file test dependencies

Centralized version catalog at `gradle/libs.versions.toml:22-27`:

```
junit         = 4.13.2
junit5        = 5.10.2
mockk         = 1.13.10
opentest4j    = 1.3.0
turbine       = 1.1.0
kotlinxCoroutines = 1.8.0   # provides kotlinx-coroutines-test
okhttp        = 4.12.0      # provides okhttp-mockwebserver
```

Per-module test dependency block (`agent/build.gradle.kts:36-44`):

```kotlin
testImplementation(libs.junit5.api)
testImplementation(libs.junit5.params)
testRuntimeOnly(libs.junit5.engine)
testRuntimeOnly(libs.junit5.platform.launcher)
testImplementation(libs.mockk)
testImplementation(libs.kotlinx.coroutines.test)
testImplementation(libs.kotlinx.serialization.json)
testImplementation(libs.okhttp.mockwebserver)
testImplementation(libs.turbine)
```

`:core`, `:jira`, `:bamboo`, `:sonar`, `:automation`, `:handover`, `:pullrequest` all declare the same set (minus turbine in some places). All declare `testFramework(TestFrameworkType.Platform)` from the IntelliJ Platform Gradle plugin (e.g. `core/build.gradle.kts:33`, `jira/build.gradle.kts:28`) — this **provides** the heavyweight test classpath but no test class actually extends `BasePlatformTestCase` etc.

`mock-server/build.gradle.kts:42-47` is distinct: Ktor server, `ktor-server-test-host` instead of MockWebServer.

`tasks.test { useJUnitPlatform() }` is set in every module.

**Special case:** `jira/build.gradle.kts:46-47` adds `dependsOn(":core:prepareTestSandbox")` — implying some Jira tests *intend* to use the platform sandbox, but no such test exists in the worktree.

### 2.3 Test base classes / shared utilities

There are **no test base classes** anywhere in the repository. The only test-side shared file is:

- `agent/src/test/kotlin/com/workflow/orchestrator/agent/TestModels.kt:1-6` — a single object exposing `MOCK_MODEL` for the Sourcegraph client tests. That is the entirety of the shared test infrastructure.

There are **no `TestUtils`, `TestFixtures`, `MockProjectFactory`, `LightProjectDescriptor`, or `IdeaTestFixture`** implementations.

### 2.4 Test fixture data

JSON fixtures live under `*/src/test/resources/fixtures/` and `agent/src/test/resources/contracts/`:

- `core/src/test/resources/fixtures/bitbucket-pr-created.json`
- `core/src/test/resources/fixtures/bitbucket-pr-list.json`
- `jira/src/test/resources/fixtures/jira-boards.json`
- `jira/src/test/resources/fixtures/jira-sprints.json`
- `jira/src/test/resources/fixtures/jira-sprint-issues.json`
- `jira/src/test/resources/fixtures/jira-issue-detail.json`
- `jira/src/test/resources/fixtures/jira-transitions.json`
- `jira/src/test/resources/fixtures/jira-transitions-with-fields.json`
- `agent/src/test/resources/contracts/plan-data.json`
- `agent/src/test/resources/contracts/plan-revise.json`
- `agent/src/test/resources/contracts/plan-revise-v2.json`
- `agent/src/test/resources/contracts/plan-step-update.json`
- `agent/src/test/resources/contracts/edit-stats.json`
- `agent/src/test/resources/contracts/mention-fields.json`
- `agent/src/test/resources/contracts/README.md`

There is **no shared "tool fixture" directory** and **no language-specific source-tree fixture** (e.g. sample Java/Kotlin project) for PSI tests.

---

## 3. Tool Coverage Matrix (`:agent` module)

### 3.1 Coverage rollup

| Source dir | Source `*Tool.kt` files | Direct `*ToolTest.kt` files | Gap |
|---|---|---|---|
| `tools/builtin/` | 28 (excluding helpers/non-tools) | 21 | 7 builtin tools untested |
| `tools/integration/` | 7 (incl. shared utils) | 5 | All meta-tools have **metadata tests only**; service-call paths are not exercised |
| `tools/psi/` | 14 | 11 | Find/Get tools missing direct tests |
| `tools/vcs/` | 11 | 1 (`ChangelistShelveToolTest`) | **10 of 11 git tools have NO test** |
| `tools/ide/` | 7 | 1 (`ProblemViewToolTest`) | **6 of 7 ide tools have NO test** |
| `tools/memory/` | 7 | 0 (covered by aggregate `MemoryToolsTest.kt:19`) | Indirect coverage only |
| `tools/runtime/` | 3 | 3 (Coverage, RuntimeConfig, RuntimeExec) | Complete metadata coverage |
| `tools/debug/` | 3 (+1 controller) | 3 + `AgentDebugControllerTest` | Metadata-level coverage |
| `tools/database/` | 4 | 1 (`DbListDatabasesToolTest`) | DbQuery / DbSchema / DbListProfiles untested |
| `tools/framework/` | 2 (Build, Spring) + 24 actions | 2 (Build, Spring) | Action handlers only tested via meta-tool dispatch |
| `tools/subagent/` | 4 | 4 (Models, ToolName, AgentConfigLoader, ParallelSubagentIntegration) | Plus `SubagentRunnerTest` |
| `tools/process/` (helper) | 1 | 0 | not exposed as a tool |

### 3.2 Tools with NO matching `*Test.kt` (38 total)

From `find ... -name "*Tool.kt"` minus `find ... -name "*ToolTest.kt"`:

```
ArchivalMemoryInsertTool      ConversationSearchTool       CoreMemoryReadTool
ArchivalMemorySearchTool      CoreMemoryAppendTool         CoreMemoryReplaceTool
SaveMemoryTool                                              (all covered by MemoryToolsTest)

AskUserInputTool              CreateFileTool                CurrentTimeTool
EnablePlanModeTool            ProjectContextTool            (builtin gaps)

DbListProfilesTool            DbQueryTool                   DbSchemaTool

GetAnnotationsTool            GetMethodBodyTool             FindImplementationsTool

GitBlameTool      GitBranchesTool  GitDiffTool      GitFileHistoryTool
GitLogTool        GitMergeBaseTool GitShowCommitTool GitShowFileTool
GitStashListTool  GitStatusTool

FormatCodeTool         OptimizeImportsTool   ListQuickFixesTool
RefactorRenameTool     RunInspectionsTool    SemanticDiagnosticsTool

JiraTool   SonarTool   BambooBuildsTool      (only metadata tests via IntegrationToolMetadataTest)
```

(Memory tools are in this list because their files lack a `*ToolTest.kt`, but `MemoryToolsTest.kt` exercises them through nested classes.)

### 3.3 Subsystem coverage (non-tool agent code)

| Subsystem | Tests present | Notes |
|---|---|---|
| `loop/AgentLoop` | 7 tests (`AgentLoopTest`, `ContextManagerTest`, `LoopDetectorTest`, `ModelFallbackLoopTest`, `ModelFallbackManagerTest`, `PlanCompactionTest`, `PlanModeLoopTest`, `SessionHandoffLoopTest`, `SkillCompactionTest`, `TaskProgressTest`, `CostTrackingTest`) | `AgentLoopTest.kt:106` defines a `SequenceBrain` fake LLM — solid pattern for agent flow tests |
| `session/SessionStore` | `SessionStoreTest`, `CheckpointReversionTest` | JSONL persistence + revert |
| `memory/` (3-tier) | `CoreMemoryTest`, `ArchivalMemoryTest`, `ConversationRecallTest`, `MemoryIntegrationTest` | Full coverage of storage behaviour |
| `hooks/` | `HookConfigTest`, `HookEventTest`, `HookManagerTest`, `HookRunnerTest`, `AgentLoopHookIntegrationTest` | Excellent coverage |
| `prompt/` | `SystemPromptTest`, `InstructionLoaderTest` | |
| `security/` | `CommandSafetyAnalyzerTest`, `CredentialRedactorTest`, `OutputValidatorTest` | |
| `observability/` | `AgentFileLoggerTest`, `SessionMetricsTest` | |
| `api/` (Sourcegraph LLM client) | `SourcegraphChatClientTest`, `SourcegraphChatClientStreamTest` | Both use `MockWebServer` |
| `ui/` | `MentionContextBuilderTest`, `MentionSearchProviderTest`, `MarkdownRenderingTest`, `CefResourceSchemeHandlerTest` (trivial) | No test exercises any actual UI rendering |
| `bridge/` | `BridgeContractTest` (single file, contract-only) | No live JCEF tests |
| `listeners/` | `AgentStartupActivityTest` (1 trivial assertion) | Type check only |
| `util/` | `DiffUtilTest` | |
| `tools/` (top-level) | `ToolRegistryTest` | |

**Note about architecture mismatch:** the root `agent/CLAUDE.md` describes an **event-sourced context system** (`SingleAgentSession`, `EventSourcedContextBridge`, `EventStore`, `CondenserPipeline`, `ConversationMemory`, `BudgetEnforcer`, `LoopGuard`, `PromptAssembler`, `WorkerSession`, etc.). **None of those classes exist** in this worktree's source tree. The current source uses the legacy `AgentLoop` / `ContextManager` / `Session` / `SessionStore` classes (`agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`, `loop/ContextManager.kt`, `session/SessionStore.kt`). Tests cover the legacy system. **A new test suite must be designed against the legacy classes that actually live in this worktree, not the doc-described ones.**

---

## 4. Existing Test Patterns (with code references)

### 4.1 Pattern A — Tool metadata + parameter validation only

The dominant pattern. Used for **every integration meta-tool** and many builtin/PSI tools. Example: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooPlansToolTest.kt:12-69`

```kotlin
class BambooPlansToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = BambooPlansTool()

    @Test fun `tool name is bamboo_plans`() { ... }
    @Test fun `action enum contains all 8 actions`() { ... }
    @Test fun `only action is required`() { ... }
    @Test fun `allowedWorkers includes TOOLER and ORCHESTRATOR`() { ... }
    @Test fun `toToolDefinition produces valid schema`() { ... }
    @Test fun `missing action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
    }
    @Test fun `unknown action returns error`() = runTest { ... }
}
```

**What this tests:** the tool registers, accepts the right parameters, rejects the wrong ones. **What this misses:** the tool actually doing its job. None of the `bamboo_plans` actions are exercised.

### 4.2 Pattern B — TempDir-based file tool tests

Used for `read_file`, `edit_file`, `glob_files`, `search_code`, `revert_file`. Best example: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileToolTest.kt:15-207`

```kotlin
class EditFileToolTest {
    @TempDir lateinit var tempDir: Path
    private val project by lazy {
        mockk<Project> { every { basePath } returns tempDir.toFile().absolutePath }
    }

    @Test
    fun `execute replaces unique string in file`() = runTest {
        val tmpFile = File(tempDir.toFile(), "test.kt").apply { writeText(...) }
        val tool = EditFileTool()
        val params = buildJsonObject { put("path", ...); put("old_string", ...); put("new_string", ...) }
        val result = tool.execute(params, project)
        assertFalse(result.isError)
        assertTrue(tmpFile.readText().contains("..."))
    }
}
```

**Strengths:** Real filesystem, real string manipulation, fast (~ms), no IntelliJ services required. Eleven file-test scenarios in a single class — exhaustive for happy/sad/edge.
**Limitation:** Bypasses IntelliJ's `Document` API and VFS — production code uses `WriteCommandAction`, undo, unsaved-document visibility; these tests do not.

### 4.3 Pattern C — `MockWebServer` HTTP tests

Used for **every API client**: `JiraApiClient`, `BambooApiClient`, `SonarApiClient`, `BitbucketApiClient`, `DockerRegistryClient`, `SourcegraphChatClient`. Example: `jira/src/test/kotlin/com/workflow/orchestrator/jira/api/JiraApiClientTest.kt:20-93`

```kotlin
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: JiraApiClient

    @BeforeEach fun setUp() {
        server = MockWebServer()
        server.start()
        client = JiraApiClient(baseUrl = server.url("/").toString().trimEnd('/'),
                                tokenProvider = { "test-token" })
    }
    @AfterEach fun tearDown() { server.shutdown() }

    @Test fun `getBoards returns parsed boards`() = runTest {
        server.enqueue(MockResponse().setBody("""{"...":...}"""))
        val result = client.getBoards("scrum")
        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }
}
```

This is the **only place in the codebase** where IntelliJ test framework classes are imported (`com.intellij.testFramework.LoggedErrorProcessorEnabler` — used as a JUnit 5 `@ExtendWith` to suppress `log.error()` test failures). Notable because it shows the IntelliJ test classpath is reachable from a "unit" test without extending a heavyweight base class.

### 4.4 Pattern D — Fake LLM brain via interface stub

Used by `AgentLoopTest` to drive end-to-end loop scenarios. `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopTest.kt:106-135`

```kotlin
private class SequenceBrain(private val responses: List<ApiResult<ChatCompletionResponse>>) : LlmBrain {
    override val modelId: String = "test-model"
    private var callIndex = 0
    var cancelCalled = false; private set

    override suspend fun chatStream(...) =
        if (callIndex >= responses.size) ApiResult.Error(ErrorType.SERVER_ERROR, "...")
        else responses[callIndex++]

    override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
    override fun cancelActiveRequest() { cancelCalled = true }
}
```

Coupled with `fakeTool(...)` builders that return precomputed `ToolResult`s, this lets the loop be driven through any scripted assistant→tool→assistant sequence purely in JVM memory. **This is the highest-value reusable pattern in the codebase** for the new tool test suite.

### 4.5 Pattern E — Aggregate "subsystem" test class

`agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/memory/MemoryToolsTest.kt:19-80` covers all 6 memory tools in nested `@Nested inner class CoreMemoryReadTests { ... }` blocks. Each nested class instantiates the real backing storage (`CoreMemory`, `ArchivalMemory`, `ConversationRecall`) under `@TempDir` and exercises one tool. Pros: less ceremony per tool. Cons: failures harder to attribute, can't run a single tool's suite.

### 4.6 Pattern F — Static mocking for IntelliJ services

Used when a tool calls e.g. `DumbService.isDumb(project)` directly. `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/psi/CallHierarchyToolTest.kt:44-61`:

```kotlin
mockkStatic(com.intellij.openapi.project.DumbService::class)
every { DumbService.isDumb(project) } returns true
val result = tool.execute(params, project)
assertTrue(result.isError)
assertTrue(result.content.contains("indexing"))
unmockkStatic(com.intellij.openapi.project.DumbService::class)
```

Same pattern in `RunCommandToolTest.kt:32-38` for `mockkObject(AgentSettings.Companion)`. Risky (relies on MockK reflection into IntelliJ classes) but functional for narrow shims.

### 4.7 Pattern G — Bridge contract roundtrip

`agent/src/test/kotlin/com/workflow/orchestrator/agent/bridge/BridgeContractTest.kt:13-120` reads JSON fixtures from `agent/src/test/resources/contracts/` and verifies Kotlin can parse them and round-trip them. Designed to fail on the OTHER side (TypeScript / React) when the schema drifts. **No actual JCEF browser is launched.**

### 4.8 Pattern H — Ktor `testApplication` host (mock-server only)

`mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/IntegrationTest.kt:17-72` shows the only true end-to-end integration test in the codebase. `testApplication { ... }` spins up an in-process Ktor server, installs the same routes the real `MockServerMain` registers, and exercises a 6-step Jira workflow against it.

```kotlin
@Test
fun `full Jira workflow - discover board, find sprint, load issues, transition`() = testApplication {
    val state = JiraDataFactory.createDefaultState()
    application {
        install(ContentNegotiation) { json() }
        routing { jiraRoutes { state } }
    }
    val boardsResponse = client.get("/rest/agile/1.0/board?type=scrum")
    ...
}
```

This is the model we'd need to follow to test agent integration tools end-to-end without external network calls.

---

## 5. The `:mock-server` Module

### 5.1 What it is

`mock-server/build.gradle.kts:1-52` declares a **standalone Ktor 3.1.1 application** with `mainClass = "MockServerMainKt"`. Not an IntelliJ Platform module, not a library — it is a runnable JVM process.

### 5.2 Structure

```
mock-server/src/main/kotlin/com/workflow/orchestrator/mockserver/
├── MockServerMain.kt          # Entry point, runs 3 Netty servers in parallel
├── admin/AdminRoutes.kt       # /__admin/state, /__admin/reset, /__admin/scenario
├── admin/AuthMiddleware.kt    # Bearer token validation
├── chaos/ChaosMiddleware.kt   # Latency / 5xx injection
├── config/MockConfig.kt       # Port config, scenarios
├── jira/JiraDataFactory.kt    # 6 scenarios: default, happy-path, empty-sprint, large-sprint, no-active-sprint, transition-blocked
├── jira/JiraMockRoutes.kt     # All Jira REST routes
├── jira/JiraState.kt          # Mutable state
├── bamboo/BambooDataFactory.kt # 4 scenarios incl. live build progression (coroutine-driven)
├── bamboo/BambooMockRoutes.kt
├── bamboo/BambooState.kt
├── sonar/SonarDataFactory.kt   # 5 scenarios
├── sonar/SonarMockRoutes.kt
└── sonar/SonarState.kt
```

`MockServerMain.kt:32-109` shows the orchestration pattern: a `StateHolder<T>` wrapper so routes always read the current state through a lambda (`{ jiraHolder.state }`) — necessary because Ktor binds routes once at startup and would otherwise capture a stale snapshot.

### 5.3 Existing scenarios

```
JIRA   = default, happy-path, empty-sprint, large-sprint, no-active-sprint, transition-blocked
BAMBOO = default, happy-path, all-builds-failing, build-progression
SONAR  = default, happy-path, quality-gate-warn, metrics-missing, auth-invalid
```

Each scenario has a corresponding `*DataFactory.create*State()` method.

### 5.4 Current usage

- The mock-server has its **own** test suite (7 files) that uses Ktor's `testApplication` host to exercise the routes in-process.
- **No agent test currently boots the mock server.** The `:agent` integration tool tests only call `tool.execute()` with bad arguments to assert validation errors — they never reach the HTTP layer.
- **No feature module currently boots the mock server in its tests either.** Sprint/build/quality dashboard tests use `MockWebServer` directly, duplicating the route logic that the mock server already implements.

### 5.5 Reusability for agent tool testing

The mock server is **architecturally ready** to be embedded in agent tool tests via Ktor's `testApplication { ... }` block (no network ports needed) — this would let us test the full path **AgentTool → service → API client → HTTP → mock server → response → ToolResult** without any external dependency. The data factories already provide diverse scenarios. The chaos middleware lets us inject 5xx and latency for retry/timeout tests.

---

## 6. JCEF / Webview Test Coverage

- `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`, `AgentDashboardPanel.kt`, `CefResourceSchemeHandler.kt` are the JCEF entry points.
- Only `CefResourceSchemeHandlerTest.kt:1-22` exists and it tests **two static constants** (`SCHEME`, `AUTHORITY`, `BASE_URL`). It does not instantiate a `JBCefBrowser`.
- `BridgeContractTest.kt` validates the JSON bridge protocol but never sends a message through `JBCefJSQuery`.
- The React webview lives at `agent/webview/` (built by `tasks.register<Exec>("buildWebview")` in `agent/build.gradle.kts:58-67`) and presumably has its own JS test infrastructure (Vitest/Vite), but **no Kotlin-side test exercises the React app's behaviour**.
- **There is no integration test that loads the webview, dispatches a tool call, and verifies the chat UI renders the result.** This is a known gap.

---

## 7. Threading / EDT Patterns in Tests

| Pattern | Used? | Where |
|---|---|---|
| `runTest` (kotlinx-coroutines-test) | Yes (every coroutine test) | All `*Test.kt` files |
| `runBlocking` in tests | Rarely | None of the `:agent` tests use it |
| `runInEdtAndWait` / `EdtTestUtil` | **No** | Never imported |
| `WriteCommandAction.runWriteCommandAction` | **No** | Never invoked from tests |
| `PlatformTestUtil` | **No** | Never imported |
| `Dispatchers.setMain` (`kotlinx-coroutines-test`) | **No** | Never used |
| `TestCoroutineScheduler` advance | **No** | Never used |
| `withContext(Dispatchers.IO)` in tests | Implicit only | Never explicit |
| `Turbine` (`turbine` library) | Yes (declared) | `EventBusTest`, possibly others (declared in build files but only one direct usage discovered) |

**Implication:** Production code paths that touch EDT (`invokeLater`, `WriteCommandAction`, `Dispatchers.EDT`) are tested *only* in their non-EDT happy paths. Anything that depends on document undo, PSI commit, or VFS refresh is bypassed.

---

## 8. Test Run Commands

From the root `CLAUDE.md` and `agent/CLAUDE.md`:

```bash
./gradlew :core:test                # Core unit tests
./gradlew :jira:test                # Jira module tests
./gradlew :bamboo:test
./gradlew :sonar:test
./gradlew :pullrequest:test         # NOTE: documented but module has no tests
./gradlew :automation:test
./gradlew :handover:test
./gradlew :agent:test               # All agent tests (~470 in production, ~97 source files in worktree)
./gradlew :mock-server:test
./gradlew :agent:test --tests "...Test"
./gradlew :agent:clean :agent:test --rerun --no-build-cache
./gradlew verifyPlugin              # API compatibility, NOT a test
./gradlew buildPlugin               # Builds the ZIP
./gradlew runIde                    # Sandbox launch
```

**No integration test source set, no test profile, no test category, no JUnit 5 tag-based filtering anywhere in the build files.** Every test runs in the same `:test` task. Kover (`build.gradle.kts:131-138`) is wired for code-coverage XML/HTML reports.

**No CI configuration:** there is no `.github/workflows/` directory at the repository root (only inside `node_modules` of webview deps). Whatever CI exists must live outside the repo or has not been added yet.

---

## 9. Pain Points / Documented Gaps

### 9.1 From in-code comments

- `SyntaxValidatorTest.kt:9-16` — **explicit gap**: "SyntaxValidator uses PsiFileFactory which requires a full IntelliJ platform environment (BasePlatformTestCase). In unit tests without platform, validate() catches the exception and returns empty (fail-open). [...] Full PSI-based validation is tested in integration tests with BasePlatformTestCase." — But no such integration test exists in the repo.
- `GenerateExplanationToolTest.kt:11-21` — **explicit gap**: "Tool execution requires git4idea (IntelliJ git plugin) which is not available in unit tests. [...] Integration tests with actual git repos would require IntelliJ's heavyweight test framework (BasePlatformTestCase)." — Again, none exists.
- `EditFileToolTest.kt:204` — `// TODO: re-enable after runtime.WorkerType is restored in lean agent rewrite` — indicates a half-finished refactor leaving an assertion commented out.
- `JiraApiClientTest.kt:15-19` — note about `LoggedErrorProcessorEnabler.DoNoRethrowErrors` being needed because production code uses `log.error()` and the IntelliJ test framework rethrows it. This is a friction point: every test against a class that logs errors needs the same workaround.

### 9.2 From `MEMORY.md` and project docs (loaded via system reminder)

- `project_deferred_ui_refactors.md` — explicit memory: "PrDetailPanel and AgentController NOT refactored during 2026-04-07 cleanup. **Need test infrastructure (BasePlatformTestCase / JCEF mock) before refactor is safe.**" — This is the most important finding: the user has already identified that **the missing heavyweight + JCEF test infrastructure is blocking refactors of two key UI surfaces**.
- `feedback_real_tdd.md` — "Write tests from spec/requirements FIRST, not from code. Tests that mirror implementation reveal nothing. End-to-end scenario tests from the spec catch integration bugs." — relevant philosophy for the new suite.

### 9.3 Discovered gaps not yet documented

- **No tool exercises the integration meta-tools' real action handlers.** The ~144 actions documented in `agent/CLAUDE.md` are reachable only by metadata assertions; the `JiraTool.execute()` action dispatch logic is never called with valid inputs.
- **No test boots the mock server from agent code.** The mock server exists and is tested in isolation; the integration tools are tested in isolation; the two are never connected.
- **No test for the 11 git VCS tools** (only `ChangelistShelveToolTest.kt` exists, and it's metadata-only).
- **No test for any of the 7 IDE intelligence tools** (`format_code`, `optimize_imports`, `refactor_rename`, `run_inspections`, `list_quickfixes`, `find_implementations`, `semantic_diagnostics`) — all of which require a real PSI environment.
- **No test for the database query tools** (`db_query`, `db_schema`) — these require an actual JDBC connection to validate.
- **No test for `ConversationSession` / event-sourced context system** because that system does not yet exist in this worktree's source tree (only in the doc).
- **No PSI test data:** there are no `.java` / `.kt` test fixture files anywhere — every PSI tool test mocks `PsiMethod`, `PsiClass`, etc. via MockK, which exercises only the formatting helpers, not the discovery or navigation logic.

### 9.4 Hardest-to-test categories (ranked)

1. **PSI / IDE intelligence tools** — require a real `Project`, real indexing (`DumbService`), real `PsiFile`, real `PsiManager`. Currently 100% mocked or untested.
2. **JCEF / chat UI** — requires a real `JBCefBrowser` and a render loop. Currently 0% tested at runtime.
3. **VCS / git tools** — require a real `git4idea` plugin and a git working tree. Currently 0% tested.
4. **Refactoring tools** (`refactor_rename`) — require IntelliJ's refactoring infrastructure + write actions. Currently 0% tested.
5. **Debug tools** — require IntelliJ's `XDebugger` framework. Currently metadata-only.
6. **Integration meta-tools** — require a configured `JiraService` / `BambooService` / `SonarService` / `BitbucketService` instance reachable from `mockk<Project>`. Currently action dispatch is untested.

---

## 10. Recommendations for the New Test Suite

### 10.1 Keep (proven, fast, reliable)

- The **`@TempDir` + `mockk<Project>` + `runTest`** pattern (Pattern B) for any tool that touches the filesystem only.
- The **`MockWebServer` + `runTest`** pattern (Pattern C) for any tool that ultimately makes an HTTP call.
- The **`SequenceBrain` + `fakeTool` LLM-loop driver** (Pattern D) for any test that needs to drive the ReAct loop end-to-end.
- The **`@Nested inner class`** aggregate pattern (Pattern E) for clusters of small related tools (memory, git read-only, integration meta-tool-actions).
- The **JSON contract fixtures** (Pattern G) for the JCEF bridge — extend to cover more message types.
- The **central `gradle/libs.versions.toml`** test dependency catalog — add what's missing once.

### 10.2 Add (currently missing)

1. **One shared test base module / sourceSet** containing:
   - `MockProjectFactory` — `mockk<Project>` with sane defaults (basePath, name, settings stubs).
   - `TempProjectFixture` — creates a temp git working tree with one Kotlin and one Java file pre-committed, returns a real `Path`.
   - `LlmFakeBrain` — extracted from `AgentLoopTest.SequenceBrain`, public, reusable.
   - `ToolHarness` — boilerplate for `tool.execute(buildJsonObject { ... }, project)` and assertion helpers (`assertSuccess`, `assertError`, `assertSummaryContains`).
   - `MockServerFixture` — JUnit 5 extension that boots the `:mock-server` Ktor app via `testApplication` and exposes its base URL to the test, with `@Scenario("happy-path")` annotation support.

2. **A `:agent:integrationTest` source set** (separate from `:test`) for tests that need:
   - Real JCEF (`JBCefBrowser`) — requires headless display, slower.
   - Real PSI / `BasePlatformTestCase`.
   - Real `:mock-server` boot via `MockServerFixture`.
   - Real git working tree + `git4idea`.
   These are slow (~seconds each) and should not run on every save.

3. **`BasePlatformTestCase`-based PSI tool tests** — finally exercise `SyntaxValidator`, `RefactorRenameTool`, `RunInspectionsTool`, `FormatCodeTool`, `OptimizeImportsTool`, `FindDefinitionTool`, etc. with real `PsiFile`s loaded from a tiny test project under `agent/src/integrationTest/testData/`.

4. **Integration meta-tool action coverage** — for each of `jira`, `bamboo_builds`, `bamboo_plans`, `sonar`, `bitbucket_pr`, `bitbucket_review`, `bitbucket_repo`:
   - Boot the mock server with the right scenario.
   - Construct the meta-tool with a service implementation pointing at the mock URL.
   - Call every action with a valid happy-path payload, assert `ToolResult.data` and `summary` shape.
   - Call every action with one or two error scenarios via the chaos middleware.

5. **`AgentLoop` end-to-end scenarios** — using `SequenceBrain` and `fakeTool`, script the most common loop patterns:
   - Tool call → result → completion.
   - Empty response → recovery.
   - Tool error → retry → completion.
   - Plan mode → exploration → approval → act mode.
   - Loop detection → warning → continuation.
   - Truncated tool call → retry with smaller op.
   - Context overflow → compression replay.
   - Cancellation mid-tool.
   - Each scenario should assert the LLM message ledger and the resulting `LoopResult`.

6. **JCEF bridge live tests** — at least one integration test that:
   - Boots a real `JBCefBrowser` with the bundled webview HTML.
   - Sends a tool call from Kotlin via the bridge.
   - Reads back a JS-side acknowledgement via `JBCefJSQuery`.
   This validates the bridge's wire format end-to-end (currently only contract-level).

7. **Tag-based test filtering** in the build files: `@Tag("unit")`, `@Tag("integration")`, `@Tag("psi")`, `@Tag("jcef")`, `@Tag("slow")`. Configure `:test` to run only `unit` by default, `:integrationTest` to run the rest.

### 10.3 Refactor (existing-but-improvable)

- **Promote the `ToolTestingPanel.kt` model** (`agent/src/main/kotlin/com/workflow/orchestrator/agent/testing/ToolTestingPanel.kt:27`) into a **headless `ToolHarness`** that can be driven from tests as well as from the IDE UI. The UI panel and tests should call the same execution code path so that "if it works in the panel, it works in the test."
- **Remove the duplicated MockWebServer setup** in every API client test by moving it to a `BaseApiClientTest` (or a JUnit 5 extension).
- **Extract the `SequenceBrain` from `AgentLoopTest`** into the shared test module so subagent-runner tests, plan-mode tests, and the new tool e2e tests can all use it.
- **Add `LoggedErrorProcessorEnabler.DoNoRethrowErrors` to a base class extension** so we don't need to remember it per file.
- **Create the missing PSI test data tree** (`testData/Sample.java`, `testData/Sample.kt`, `testData/build.gradle`, etc.) and use `PsiTestUtil` to load them in BasePlatformTestCase tests.

### 10.4 Strategic decisions to make before writing tests

1. **Two-tier runtime: unit vs. integration?** Yes — separate source sets, separate gradle tasks, tagged differently.
2. **Embed `:mock-server` or shell out to it?** Embed via `testApplication` — zero ports, zero process management, zero flakiness.
3. **Real PSI tests in `:agent` or split to a new module?** Keep in `:agent` under a new `integrationTest` source set; pulling them out duplicates infrastructure.
4. **Snapshot-based assertions for tool output?** Probably yes for large LLM-facing strings (`SystemPromptTest` already uses substring matching, which is fragile). Consider [kotlinx-snapshot](https://github.com/Kotlin/kotlinx-knit) or a small custom helper.
5. **Coverage target?** Kover is already wired (`build.gradle.kts:131-138`) — pick a number (75%? 85%?) and enforce it on `verifyPlugin` for `:agent` once the gap is closed.
6. **End-to-end LLM tests with a recording/replay LLM?** Optional but valuable — use OkHttp `MockWebServer` to record real Sourcegraph API responses once and replay them.

---

## 11. Appendix — Complete Test File List by Module

### 11.1 `:agent` (97 files)

```
agent/src/test/kotlin/com/workflow/orchestrator/agent/
├── TestModels.kt
├── api/
│   ├── SourcegraphChatClientStreamTest.kt
│   └── SourcegraphChatClientTest.kt
├── bridge/
│   └── BridgeContractTest.kt
├── hooks/
│   ├── AgentLoopHookIntegrationTest.kt
│   ├── HookConfigTest.kt
│   ├── HookEventTest.kt
│   ├── HookManagerTest.kt
│   └── HookRunnerTest.kt
├── listeners/
│   └── AgentStartupActivityTest.kt
├── loop/
│   ├── AgentLoopTest.kt
│   ├── ContextManagerTest.kt
│   ├── CostTrackingTest.kt
│   ├── LoopDetectorTest.kt
│   ├── ModelFallbackLoopTest.kt
│   ├── ModelFallbackManagerTest.kt
│   ├── PlanCompactionTest.kt
│   ├── PlanModeLoopTest.kt
│   ├── SessionHandoffLoopTest.kt
│   ├── SkillCompactionTest.kt
│   └── TaskProgressTest.kt
├── memory/
│   ├── ArchivalMemoryTest.kt
│   ├── ConversationRecallTest.kt
│   ├── CoreMemoryTest.kt
│   └── MemoryIntegrationTest.kt
├── observability/
│   ├── AgentFileLoggerTest.kt
│   └── SessionMetricsTest.kt
├── prompt/
│   ├── InstructionLoaderTest.kt
│   └── SystemPromptTest.kt
├── security/
│   ├── CommandSafetyAnalyzerTest.kt
│   ├── CredentialRedactorTest.kt
│   └── OutputValidatorTest.kt
├── session/
│   ├── CheckpointReversionTest.kt
│   └── SessionStoreTest.kt
├── tools/
│   ├── ToolRegistryTest.kt
│   ├── builtin/
│   │   ├── ActModeRespondToolTest.kt
│   │   ├── AskQuestionsToolTest.kt
│   │   ├── AttemptCompletionToolTest.kt
│   │   ├── EditFileToolTest.kt
│   │   ├── GenerateExplanationToolTest.kt
│   │   ├── GlobFilesToolTest.kt
│   │   ├── KillProcessToolTest.kt
│   │   ├── NewTaskToolTest.kt
│   │   ├── PathValidatorTest.kt
│   │   ├── PlanModeRespondToolTest.kt
│   │   ├── ReadFileToolTest.kt
│   │   ├── RenderArtifactToolTest.kt
│   │   ├── RevertFileToolTest.kt
│   │   ├── RunCommandToolTest.kt
│   │   ├── SearchCodeToolTest.kt
│   │   ├── SendStdinToolTest.kt
│   │   ├── SpawnAgentToolTest.kt
│   │   ├── SyntaxValidatorTest.kt
│   │   ├── ThinkToolTest.kt
│   │   ├── ToolSearchToolTest.kt
│   │   └── UseSkillToolTest.kt
│   ├── database/
│   │   ├── DatabaseProfileTest.kt
│   │   ├── DbListDatabasesToolTest.kt
│   │   └── JdbcUrlBuilderTest.kt
│   ├── debug/
│   │   ├── AgentDebugControllerTest.kt
│   │   ├── DebugBreakpointsToolTest.kt
│   │   ├── DebugInspectToolTest.kt
│   │   └── DebugStepToolTest.kt
│   ├── framework/
│   │   ├── BuildToolTest.kt
│   │   └── SpringToolTest.kt
│   ├── ide/
│   │   └── ProblemViewToolTest.kt
│   ├── integration/
│   │   ├── BambooPlansToolTest.kt
│   │   ├── BitbucketPrToolTest.kt
│   │   ├── BitbucketRepoToolTest.kt
│   │   ├── BitbucketReviewToolTest.kt
│   │   └── IntegrationToolMetadataTest.kt
│   ├── memory/
│   │   └── MemoryToolsTest.kt
│   ├── psi/
│   │   ├── CallHierarchyToolTest.kt
│   │   ├── DataFlowAnalysisToolTest.kt
│   │   ├── FileStructureToolTest.kt
│   │   ├── FindDefinitionToolTest.kt
│   │   ├── FindReferencesToolTest.kt
│   │   ├── PsiToolUtilsTest.kt
│   │   ├── ReadWriteAccessToolTest.kt
│   │   ├── StructuralSearchToolTest.kt
│   │   ├── TestFinderToolTest.kt
│   │   ├── TypeHierarchyToolTest.kt
│   │   └── TypeInferenceToolTest.kt
│   ├── runtime/
│   │   ├── CoverageToolTest.kt
│   │   ├── RuntimeConfigToolTest.kt
│   │   └── RuntimeExecToolTest.kt
│   ├── subagent/
│   │   ├── AgentConfigLoaderTest.kt
│   │   ├── ParallelSubagentIntegrationTest.kt
│   │   ├── SubagentModelsTest.kt
│   │   ├── SubagentRunnerTest.kt
│   │   └── SubagentToolNameTest.kt
│   └── vcs/
│       └── ChangelistShelveToolTest.kt
├── ui/
│   ├── CefResourceSchemeHandlerTest.kt
│   ├── MarkdownRenderingTest.kt
│   ├── MentionContextBuilderTest.kt
│   └── MentionSearchProviderTest.kt
└── util/
    └── DiffUtilTest.kt
```

### 11.2 `:core` (25 files)

```
core/src/test/kotlin/com/workflow/orchestrator/core/
├── ai/
│   ├── ModelCacheFallbackChainTest.kt
│   └── ModelCacheTest.kt
├── auth/
│   ├── AuthTestServiceTest.kt
│   └── CredentialStoreTest.kt
├── bitbucket/
│   └── BitbucketApiClientTest.kt
├── copyright/
│   └── CopyrightCheckServiceTest.kt
├── events/
│   └── EventBusTest.kt
├── healthcheck/
│   ├── HealthCheckCheckinHandlerFactoryTest.kt
│   ├── HealthCheckServiceTest.kt
│   └── checks/
│       ├── CopyrightCheckTest.kt
│       ├── MavenCompileCheckTest.kt
│       ├── MavenTestCheckTest.kt
│       └── SonarGateCheckTest.kt
├── http/
│   ├── AuthInterceptorTest.kt
│   ├── HttpClientFactoryTest.kt
│   └── RetryInterceptorTest.kt
├── maven/
│   ├── MavenBuildServiceTest.kt
│   ├── MavenModuleDetectorTest.kt
│   ├── SurefireReportParserTest.kt
│   └── TeamCityMessageConverterTest.kt
├── model/
│   └── ApiResultTest.kt
├── psi/
│   └── PsiContextEnricherTest.kt
└── util/
    ├── DefaultBranchResolverTest.kt
    ├── GitMergeBaseUtilTest.kt
    └── ProjectIdentifierTest.kt
```

### 11.3 `:jira` (10 files)

```
jira/src/test/kotlin/com/workflow/orchestrator/jira/
├── api/
│   ├── JiraApiClientTest.kt
│   └── dto/
│       └── JiraDtoDeserializationTest.kt
├── service/
│   ├── ActiveTicketServiceTest.kt
│   ├── BranchNameValidatorTest.kt
│   ├── CommitPrefixServiceTest.kt
│   └── SprintServiceTest.kt
├── tasks/
│   └── JiraTaskMappingTest.kt
├── vcs/
│   ├── PostCommitTransitionLogicTest.kt
│   └── TimeTrackingLogicTest.kt
└── workflow/
    └── TransitionMappingStoreTest.kt
```

### 11.4 `:bamboo` (4 files)

```
bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/
├── api/
│   └── BambooApiClientTest.kt
└── service/
    ├── BuildLogParserTest.kt
    ├── BuildMonitorServiceTest.kt
    └── PlanDetectionServiceTest.kt
```

### 11.5 `:sonar` (14 files)

```
sonar/src/test/kotlin/com/workflow/orchestrator/sonar/
├── api/
│   ├── SonarApiClientTest.kt
│   └── dto/
│       └── SonarDtoSerializationTest.kt
├── editor/
│   └── SonarFixIntentionActionTest.kt
├── service/
│   ├── BranchQualityReportTest.kt
│   ├── CoverageMapperTest.kt
│   ├── IssueMapperTest.kt
│   ├── SonarDataServiceHotspotsTest.kt
│   └── SonarDataServiceTest.kt
└── ui/
    ├── CoverageLineMarkerLogicTest.kt
    ├── CoveragePreviewLogicTest.kt
    ├── CoverageThresholdsTest.kt
    ├── GateStatusBannerTest.kt
    ├── QualityListItemTest.kt
    └── SonarIssueAnnotatorLogicTest.kt
```

### 11.6 `:automation` (9 files)

```
automation/src/test/kotlin/com/workflow/orchestrator/automation/
├── api/
│   └── DockerRegistryClientTest.kt
├── model/
│   └── AutomationModelsTest.kt
├── run/
│   └── TagValidationLogicTest.kt
└── service/
    ├── AutomationSettingsServiceTest.kt
    ├── ConflictDetectorServiceTest.kt
    ├── DriftDetectorServiceTest.kt
    ├── QueueServiceTest.kt
    ├── TagBuilderServiceTest.kt
    └── TagHistoryServiceTest.kt
```

### 11.7 `:handover` (10 files)

```
handover/src/test/kotlin/com/workflow/orchestrator/handover/
├── model/
│   └── HandoverModelsTest.kt
└── service/
    ├── CompletionMacroServiceTest.kt
    ├── CopyrightFixServiceTest.kt
    ├── HandoverStateServiceTest.kt
    ├── JiraClosureServiceTest.kt
    ├── PrServiceTemplateTest.kt
    ├── PrServiceTest.kt
    ├── PreReviewServiceTest.kt
    ├── QaClipboardServiceTest.kt
    └── TimeTrackingServiceTest.kt
```

### 11.8 `:pullrequest` — **0 test files, no test directory**

### 11.9 `:mock-server` (7 files)

```
mock-server/src/test/kotlin/com/workflow/orchestrator/mockserver/
├── IntegrationTest.kt
├── bamboo/
│   ├── BambooDataFactoryTest.kt
│   └── BambooMockRoutesTest.kt
├── chaos/
│   └── ChaosMiddlewareTest.kt
├── jira/
│   ├── JiraDataFactoryTest.kt
│   └── JiraMockRoutesTest.kt
└── sonar/
    └── SonarMockRoutesTest.kt
```
