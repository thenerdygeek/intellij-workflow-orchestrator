# IntelliJ Platform Testing Capabilities — Comprehensive Research

**Date:** 2026-04-07
**Author:** Research agent
**Purpose:** Inform the design of a real-world test suite for the `:agent` module's ~70 AI tools across file ops, shell exec, git, search, sub-agent delegation, memory, debug/run, refactoring, and HTTP service integrations.
**Target stack:** IntelliJ IDEA 2025.1+, Kotlin 2.1.10, Gradle IntelliJ Platform Plugin v2 (2.12.0), JUnit 5.

---

## Executive Summary — Top 15 Findings

1. **JetBrains officially discourages heavy mocking.** The platform's stated philosophy is to "work with real components instead, with options to replace components/services and extension points in test scenarios" ([testing-plugins.html](https://plugins.jetbrains.com/docs/intellij/testing-plugins.html)). Most existing tests in this repo use plain MockK + `mockk<Project>()`, which works for pure-logic tools but cannot reach PSI/VFS/RunManager/RefactoringFactory paths. To test those tools as the LLM will actually invoke them, we need a real `Application` and `Project`.
2. **There is a first-class JUnit 5 test framework** at `com.intellij.testFramework.junit5`. The two key annotations are `@TestApplication` (one shared `Application` instance per test class root) and `@TestDisposable` (per-test scoped `Disposable`). Both work as JUnit 5 extensions, no JUnit 3/4 base class needed.
3. **`projectFixture()` (Kotlin DSL)** is the modern way to spin up a real `Project` per test. It supports static (shared across tests in a class) and instance (per-test) lifetimes, can copy a "blueprint" resource directory into the project, and is composable with `moduleFixture`, `sourceRootFixture`, `psiFileFixture`, `editorFixture`, `disposableFixture`, `tempPathFixture` ([fixtures.kt](https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5/src/fixture/fixtures.kt)).
4. **For PSI/VFS-touching tests, `BasePlatformTestCase` (JUnit 3) remains the highest-leverage choice.** It provides `myFixture: CodeInsightTestFixture` with `configureByText`, `configureByFile`, `copyFileToProject`, `complete`, `findUsages`, `renameElementAtCaret`, `checkResult`, etc. — the same APIs JetBrains uses to test its own bundled plugins.
5. **For multi-module / SDK / library projects, `HeavyPlatformTestCase` is the only option.** Pay the per-test setup cost (~2-5s) but gain the ability to add modules, configure SDKs, register VCS roots.
6. **Light vs Heavy is a project-instance reuse decision.** Light tests reuse a single `Project` keyed by `LightProjectDescriptor.equals()` across all test methods in a class. Heavy tests recreate the project per test ([light-and-heavy-tests.html](https://plugins.jetbrains.com/docs/intellij/light-and-heavy-tests.html)).
7. **JetBrains ships a built-in HTTP server fixture** at `com.intellij.testFramework.junit5.http.localhostHttpServer()` returning `TestFixture<com.sun.net.httpserver.HttpServer>`. This is the JetBrains-blessed pattern for service integration tests — no MockWebServer classloader gymnastics needed ([server.kt](https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5/src/http/server.kt)).
8. **JimFS is also officially blessed for in-memory file system tests** via `com.intellij.platform.testFramework.junit5.jimfs.jimFsFixture()`. Useful for fast file-tool tests that don't need IDE indexing ([JimFsFixture.kt](https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5.jimfs/src/JimFsFixture.kt)).
9. **`@RunInEdt` is now legacy/deprecated.** JetBrains recommends `timeoutRunBlocking(context = Dispatchers.UiWithModelAccess)` instead for any code that needs the EDT or write access ([RunInEdt.kt](https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5/src/RunInEdt.kt)). This matters for our agent because all our tools are `suspend`.
10. **Service replacement is via `ServiceContainerUtil.replaceService(...)` / `Application.replaceService(...)` extension functions**, not Mockito-style global mocking. These are scoped to a `Disposable` so they auto-revert at test teardown — perfectly compatible with `@TestDisposable`.
11. **JCEF cannot run in CI/headless mode reliably.** Always gate JCEF tests on `JBCefApp.isSupported()` and isolate JCEF behavior into a thin wrapper that's easy to swap out. The webview React side has its own Vitest/Jest tests; bridge contracts are validated via shared JSON fixtures (already done in this repo via `BridgeContractTest`).
12. **Git plugin has its own rich test base class hierarchy** — `GitPlatformTest extends VcsPlatformTest extends HeavyPlatformTestCase` — that creates real `git init` repos with helper methods like `createRepository()`, `commit()`, `prepareRemoteRepo()`. Reuse this directly for `GitTool` integration tests instead of shelling out.
13. **Run configurations / debug sessions are testable** via `HeavyPlatformTestCase`. `RunManager.getInstance(project).addConfiguration(...)` works, and `XBreakpointManager` is fully exercisable. Live debug session tests are possible but slow — keep them in a separate sourceset.
14. **Integration tests (Starter framework)** are a separate category that launch a real IDE in a child JVM via `Starter.newContext(...).runIdeWithDriver()`. Required for true end-to-end UI tests. JetBrains has migrated away from "Remote Robot" toward this new Driver framework ([integration-tests-intro.html](https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html)).
15. **Gradle IntelliJ Platform Plugin v2 cleanly separates unit tests, integration tests, and UI tests** into distinct source sets via the `intellijPlatformTesting.testIdeUi.registering` block, with `testFramework(TestFrameworkType.Platform)` for unit tests and `testFramework(TestFrameworkType.Starter)` for integration tests.

---

## 1. Core Test Framework Classes

### 1.1 `BasePlatformTestCase` (JUnit 3, light) — the workhorse

**Package:** `com.intellij.testFramework.fixtures.BasePlatformTestCase`
**Source:** [BasePlatformTestCase.java](https://github.com/JetBrains/intellij-community/blob/master/platform/testFramework/src/com/intellij/testFramework/fixtures/BasePlatformTestCase.java)
**Parent:** `UsefulTestCase`

**What it provides:**
- A real `Project` instance, reused across all test methods in the class (until `LightProjectDescriptor` differs).
- Real PSI infrastructure (`PsiManager.getInstance(getProject())`).
- Real VFS, but typically backed by `TempDirTestFixture` (in-memory or temp dir, configurable).
- A pre-built `myFixture: CodeInsightTestFixture` field exposing 30+ helper methods.
- Single module named `"src"` with no SDK by default. Override `getProjectDescriptor()` for custom setup.

**Key methods:**
```java
protected Project getProject()
protected Module getModule()
protected PsiManager getPsiManager()
protected String getTestDataPath()      // override to point at src/test/testData
protected LightProjectDescriptor getProjectDescriptor()
```

**`myFixture` capabilities** (`CodeInsightTestFixture`):
- `configureByText(fileName, text)` / `configureByFile(path)` / `configureByFiles(paths)`
- `copyFileToProject(src)` / `copyDirectoryToProject(srcDir, targetDir)`
- `complete(CompletionType.BASIC)` → `LookupElement[]`
- `findUsages(element)` → `Collection<UsageInfo>`
- `renameElementAtCaret(newName)`
- `checkResult(expected)` / `checkResultByFile(path)`
- `enableInspections(class)` + `checkHighlighting()` / `doHighlighting()`
- `findSingleIntention(text)` + `launchAction(intention)`
- `editor`, `file`, `caretOffset` properties

**Pros:** Fast, reuses project, lightweight setup, JetBrains-canonical for Java/Kotlin/PSI tests.
**Cons:** JUnit 3 (extends `UsefulTestCase` → `TestCase`). Test method names must start with `test`. No `@Test` annotation, no `@BeforeEach`. Not natively composable with our existing JUnit 5 + MockK setup.

**Migration note:** A `LightPlatform4TestCase` (JUnit 4) exists at `com.intellij.testFramework.LightPlatform4TestCase`, but for JUnit 5 the recommended path is fixture composition (see Section 1.6).

### 1.2 `LightPlatformTestCase` (JUnit 3) — minimal

**Package:** `com.intellij.testFramework.LightPlatformTestCase`

The thin parent of `BasePlatformTestCase`. Provides project init but no `myFixture`. Use only when you need a project but don't need any of the `CodeInsightTestFixture` helpers — rare in practice.

### 1.3 `HeavyPlatformTestCase` — when you need real modules

**Package:** `com.intellij.testFramework.HeavyPlatformTestCase`
**Source:** [HeavyPlatformTestCase.java](https://github.com/JetBrains/intellij-community/blob/master/platform/testFramework/src/com/intellij/testFramework/HeavyPlatformTestCase.java)

**When to use:**
- Multi-module Java/Kotlin projects.
- Real SDK setup (not the no-SDK default).
- VCS plugin tests (Git4Idea's `GitPlatformTest` is a `HeavyPlatformTestCase` descendant).
- Tests that need to read actual `iml` files or `.idea/` config.
- Anything that needs `ProjectRootManager.modifyModel(...)` to actually persist.

**Trade-off:** Per-test project creation costs ~2-5 seconds. Quote from docs: *"Heavy tests create a fresh project for each test execution"* ([light-and-heavy-tests.html](https://plugins.jetbrains.com/docs/intellij/light-and-heavy-tests.html)).

**Composition for multi-module:** Use `JavaTestFixtureFactory.createFixtureBuilder(name)` + `addModule(JavaModuleFixtureBuilder.class)` in `setUp()` to assemble multi-module projects.

### 1.4 `UsefulTestCase` — base utilities

**Package:** `com.intellij.testFramework.UsefulTestCase`

The grandparent of every IntelliJ test class. Provides:
- `getTestName(lowercaseFirstLetter: boolean)` — derives test name for testdata paths.
- `getTestRootDisposable(): Disposable` — auto-disposed at test teardown. Pass this to anything you need cleaned up.
- `addSuppressedException(...)` — for tearDown chaining.
- Inheritance check helpers and array assertions.

### 1.5 Fixtures: composition over inheritance

**Package:** `com.intellij.testFramework.fixtures.*`
**Doc:** [tests-and-fixtures.html](https://plugins.jetbrains.com/docs/intellij/tests-and-fixtures.html)

The fixture system lets you avoid base classes entirely. Lifecycle:

1. `IdeaTestFixtureFactory.getFixtureFactory()` — get the factory.
2. `factory.createFixtureBuilder(testName)` — get a `TestFixtureBuilder<IdeaProjectTestFixture>`.
3. `builder.getFixture()` — extract `IdeaProjectTestFixture`.
4. `fixture.setUp()` / `fixture.tearDown()` — invoke from your framework's lifecycle.

**Key types:**
| Type | Purpose |
|---|---|
| `IdeaTestFixture` | Base interface |
| `IdeaProjectTestFixture` | Has `getProject()`, `getModule()` |
| `CodeInsightTestFixture` | Wraps `IdeaProjectTestFixture` + adds editor/PSI helpers |
| `TempDirTestFixture` | Manages temp dir VFS, two impls: in-memory (`LightTempDirTestFixtureImpl`) or on-disk (`TempDirTestFixtureImpl`) |
| `ModuleFixture` / `JavaModuleFixtureBuilder` | Real Java module with sources/SDK/libraries |

### 1.6 JUnit 5 Platform Support — the modern path

**Package:** `com.intellij.testFramework.junit5`
**Browse:** [junit5/src](https://github.com/JetBrains/intellij-community/tree/master/platform/testFramework/junit5/src)

This is the path to use for **all new tests** in this repo. Verbatim from `TestApplication.kt`:

> "Initializes shared application instance once before all tests are run. The application is disposed together with the root context, i.e., after all tests were run."

Key annotations and helpers:

| Symbol | Package | What it does |
|---|---|---|
| `@TestApplication` | `com.intellij.testFramework.junit5` | Class-level. Boots a shared `Application` once per JUnit class root. Required to use any IntelliJ service in a JUnit 5 test. |
| `@TestDisposable` | `com.intellij.testFramework.junit5` | Field/parameter. Injects a per-test `Disposable` that auto-disposes at `@AfterEach`. |
| `@RunInEdt` | `com.intellij.testFramework.junit5` | **Deprecated**. Use `timeoutRunBlocking(context = Dispatchers.UiWithModelAccess) { ... }` instead. |
| `@RegistryKey(key, value)` | `com.intellij.testFramework.junit5` | Sets a `Registry` key for the test method or class. Auto-reverted. |
| `@RegistryKeyAppLevel(key, value)` | `com.intellij.testFramework.junit5` | Same but applied in `BeforeAllCallback` so it affects services starting before the test (e.g. `projectActivity`). |
| `@SystemProperty(key, value)` | `com.intellij.testFramework.junit5` | Sets a system property for the test, auto-reverted. |
| `@TestFixtures` | `com.intellij.testFramework.junit5.fixture` | Meta-annotation enabling the fixture DSL. |

**Showcase tests** (canonical examples) live at [platform/testFramework/junit5/test/showcase](https://github.com/JetBrains/intellij-community/tree/master/platform/testFramework/junit5/test/showcase):
- `JUnit5ApplicationTest.kt`
- `JUnit5ProjectFixtureTest.kt`
- `JUnit5ModuleFixtureTest.kt`
- `JUnit5PsiFileFixtureTest.kt`
- `JUnit5EditorFixtureTest.kt`
- `JUnit5DisposableTest.kt`
- `JUnit5RunInEdtTest.java`
- `JUnit5SimpleProjectTest.kt`

**Minimal example** (verbatim from JetBrains):
```kotlin
@TestApplication
class JUnit5ApplicationTest {

  @Test
  fun `application is initialized`() {
    Assertions.assertNotNull(ApplicationManager.getApplication())
  }
}
```

**Project fixture example** (verbatim, condensed from `JUnit5ProjectFixtureTest.kt`):
```kotlin
@TestApplication
class JUnit5ProjectFixtureTest {

  private companion object {
    val sharedProject0 = projectFixture()              // shared across all tests in this class
    val openedProject = projectFixture(openAfterCreation = true)
    val tempPath = tempPathFixture()
    val blueprintProjectRoot: Path = PathManager.getHomeDirFor(JUnit5ProjectFixtureTest::class.java)!!
      .resolve("platform/testFramework/junit5/test-resources/projectFixture/blueprintProject")
    val projectFromBlueprint = projectFixture(openAfterCreation = true, blueprintResourcePath = blueprintProjectRoot)
  }

  private val localProject0 = projectFixture()        // recreated per test method

  @Test
  fun `open after creation`() {
    assertTrue(openedProject.get().isOpen)
  }
}
```

**Per-test disposable example** (verbatim from `TestDisposable.kt`):
```kotlin
class MyTest {
  @TestDisposable
  lateinit var disposable: Disposable    // a disposable will be created for each test

  @Test fun test1() {}

  @Test
  fun test2(@TestDisposable disposable: Disposable) {  // also works as a parameter
    // ...
  }
}
```

---

## 2. Project / Module / SDK / Git Setup

### 2.1 Multi-module projects

**JUnit 5 fixture API** (preferred for new code):
```kotlin
@TestApplication
class MultiModuleTest {
  companion object {
    val project = projectFixture()
    val moduleA = project.moduleFixture("moduleA", moduleType = "JAVA_MODULE")
    val pathB = tempPathFixture(subdirName = "moduleB")
    val moduleB = project.moduleFixture(pathFixture = pathB, addPathToSourceRoot = true)
    val srcRoot = moduleA.sourceRootFixture(isTestSource = false)
    val mainKt = srcRoot.psiFileFixture("Main.kt", "fun main() { println(\"hi\") }")
  }
}
```

`fixtures.kt` exposes:
- `projectFixture(pathFixture, openProjectTask, openAfterCreation, blueprintResourcePath)`
- `TestFixture<Project>.moduleFixture(name, moduleType)`
- `TestFixture<Project>.moduleFixture(pathFixture, addPathToSourceRoot, moduleTypeId)`
- `TestFixture<Module>.sourceRootFixture(isTestSource, pathFixture, blueprintResourcePath)`
- `TestFixture<PsiDirectory>.psiFileFixture(name, content)`
- `TestFixture<PsiDirectory>.virtualFileFixture(name, content)`
- `TestFixture<PsiFile>.editorFixture()` — opens editor with caret/selection markers from text
- `disposableFixture()`
- `tempPathFixture(root, prefix, subdirName)`
- `TestFixture<Project>.fileOrDirInProjectFixture(relativePath)`
- `TestFixture<Project>.existingPsiFileFixture(relativePath)`

**JUnit 3 / `HeavyPlatformTestCase` style:** Use `JavaTestFixtureFactory.createFixtureBuilder(name)` + `JavaModuleFixtureBuilder` ([test-project-and-testdata-directories.html](https://plugins.jetbrains.com/docs/intellij/test-project-and-testdata-directories.html)).

### 2.2 SDK and libraries

`LightProjectDescriptor` controls SDK / module type / libraries for **light** tests:
```kotlin
class KotlinLightDescriptor : LightProjectDescriptor() {
  override fun getModuleTypeId() = "JAVA_MODULE"
  override fun getSdk(): Sdk? = IdeaTestUtil.getMockJdk17()
  override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
    super.configureModule(module, model, contentEntry)
    PsiTestUtil.addLibrary(model, "kotlin-stdlib", path, "kotlin-stdlib.jar")
  }
}
```

For Kotlin specifically, the bundled Kotlin plugin ships `KotlinLightCodeInsightFixtureTestCase` and `KotlinLightProjectDescriptor`. Reuse them when the agent module's tests need real Kotlin PSI (find references, type inference, structural search).

For SDK injection in **heavy** tests:
```kotlin
override fun setUp() {
  super.setUp()
  ModuleRootModificationUtil.setModuleSdk(module, IdeaTestUtil.getMockJdk17())
}
```

### 2.3 Git repositories in tests

**Real `git init` repos:** Extend `GitPlatformTest` (or its parent `VcsPlatformTest`) from `git4idea.test`. Source: [GitPlatformTest.kt](https://github.com/JetBrains/intellij-community/blob/master/plugins/git4idea/tests/git4idea/test/GitPlatformTest.kt).

**Helpers it provides** (verbatim from source):
```kotlin
abstract class GitPlatformTest : VcsPlatformTest() {
  protected lateinit var repositoryManager: GitRepositoryManager
  protected lateinit var settings: GitVcsSettings
  protected lateinit var git: TestGitImpl
  protected lateinit var vcs: GitVcs

  protected open fun createRepository(rootDir: String): GitRepository
  protected fun setupRepositories(repoRoot: String, parentName: String, broName: String): ReposTrinity
  protected fun prepareRemoteRepo(source: GitRepository, target: Path, remoteName: String): Path
}
```

These actually shell out to real `git` binary on `PATH` in the test JVM. For our `GitTool` (11 actions: status/blame/diff/log/branches/show_file/show_commit/stash_list/merge_base/file_history/shelve), this is the right approach.

**Lighter alternative** (if you don't want to depend on `git4idea` test sources): use JGit + a `tempPathFixture`, then make `GitTool` accept an injected `GitRepository`. But the JetBrains-blessed path is `GitPlatformTest`.

### 2.4 Maven / Gradle project structure

For tests of `BuildTool` (11 actions including `maven_dependencies`, `gradle_tasks`, etc.), the bundled Maven plugin's test base is `MavenImportingTestCase`/`MavenServerTestCase` and the Gradle plugin's is `GradleImportingTestCase`. Both extend `HeavyPlatformTestCase`. They can import a real `pom.xml`/`build.gradle` from a tempdir and assert on the resulting model. Use these for end-to-end build tool tests.

---

## 3. PSI / VFS / File Operations Testing

### 3.1 Creating files

**In a Light test (`BasePlatformTestCase`):**
```kotlin
val psiFile: PsiFile = myFixture.configureByText("Foo.kt", "fun foo() {}")
val vfile: VirtualFile = psiFile.virtualFile
```

**Via fixture DSL (JUnit 5):**
```kotlin
companion object {
  val project = projectFixture()
  val mod = project.moduleFixture("m")
  val src = mod.sourceRootFixture()
  val file = src.psiFileFixture("Foo.kt", "fun foo() {}")
}
```

### 3.2 Write operations

PSI/VFS writes must happen on EDT under a write action. In tests:

```kotlin
// JUnit 5 modern way:
timeoutRunBlocking(context = Dispatchers.UiWithModelAccess) {
  writeAction {
    psiFile.virtualFile.setBinaryContent("new content".toByteArray())
  }
}

// Or in a JUnit 3 BasePlatformTestCase:
WriteCommandAction.runWriteCommandAction(project) {
  document.replaceString(0, document.textLength, "new content")
}
```

### 3.3 LocalHistory in tests

`LocalHistory` works in tests when running under `@TestApplication` or `BasePlatformTestCase`. The action label is the same as production code: `LocalHistory.getInstance().putUserLabel(project, "My checkpoint")`. Critical for testing the agent's `rollback_changes` and `revert_file` tools, which depend on LocalHistory primarily and git as fallback.

**Gotcha:** The default heavy test setup may not start the LocalHistory daemon. If your test asserts on LocalHistory revisions, ensure you call `LocalHistoryImpl.getInstanceImpl().cleanupForNextTest()` or use a `@TestDisposable` to clean state.

### 3.4 VFS refresh

When you write through `java.io.File` instead of VFS, you must manually refresh:
```kotlin
LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(path))
// or
VfsUtil.markDirtyAndRefresh(false, true, true, file)
```

This is critical for testing tools like `EditFileTool`/`CreateFileTool` whose IntelliJ-aware paths only work if VFS knows about the file.

### 3.5 In-memory file system: `TempDirTestFixture` and JimFS

**`TempDirTestFixture`** has two implementations:
- `LightTempDirTestFixtureImpl` — VFS-backed, in-memory, fast.
- `TempDirTestFixtureImpl` — actual temp directory on disk.

**`jimFsFixture()`** ([JimFsFixture.kt](https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5.jimfs/src/JimFsFixture.kt)):
```kotlin
@TestApplication
class FileToolTest {
  companion object {
    val fs = jimFsFixture(Configuration.unix())  // or Configuration.windows()
  }

  @Test
  fun `read file works on jimfs`() {
    val path = fs.get().getPath("/work/foo.txt")
    Files.createDirectories(path.parent)
    Files.writeString(path, "hello")
    // exercise tool against this Path
  }
}
```

JimFS is great for tests that ONLY exercise `java.nio.file.Path` logic with no IDE-side indexing. Use it for path-validation tests, glob tests, and sandboxed shell tests.

---

## 4. Mock Services & Dependency Injection

### 4.1 `ServiceContainerUtil` — replace IDE services in tests

**Source:** `com.intellij.testFramework.replaceService(...)` (extension functions on `ComponentManager`).

**Pattern:**
```kotlin
@TestApplication
class MyTest {
  @TestDisposable lateinit var disposable: Disposable

  @Test
  fun `replaces service for the duration of the test`() {
    val fake = FakeJiraService()
    val app = ApplicationManager.getApplication()
    app.replaceService(JiraService::class.java, fake, disposable)

    // exercise code that resolves JiraService via service<JiraService>()
    // at end of test, replaceService rolls back automatically because disposable disposes
  }
}
```

**Project-scoped service:**
```kotlin
project.replaceService(BambooService::class.java, FakeBambooService(), disposable)
```

**Why this is better than MockK alone:**
- Real `service<T>()` lookups go through it, including ones inside other services.
- Auto-rollback via `Disposable`.
- Works with `lazy` and `@RequiresService` annotations.
- Compatible with the rest of the IDE machinery (notifications, tool windows, etc.).

### 4.2 When to use MockK + `mockk<Project>()` vs replaceService

- **Pure-logic tools** (`PathValidator`, `JdbcUrlBuilder`, `SubagentToolName`, `LoopDetector`): MockK + `mockk<Project> { every { basePath } returns ... }` is fine. Already used throughout this repo.
- **Tools that resolve a `service<T>()`**: use `@TestApplication` + `replaceService`. Examples: `ReadFileTool` (resolves `FileEditorManager`), `RunCommandTool` (resolves `CredentialStore`), all `JiraTool`/`BambooTool` actions.
- **Tools that touch PSI/VFS**: use `BasePlatformTestCase` or `projectFixture()`. MockK cannot fake PSI without enormous boilerplate.

### 4.3 `ExtensionTestUtil`

**Package:** `com.intellij.testFramework.ExtensionTestUtil`

For replacing extension points (intentions, inspections, completion contributors). Two key methods:
- `maskExtensions(epName, newExtensions, parentDisposable)` — temporarily replace registered extensions.
- `addExtension(area, ep, instance, parentDisposable)` — add one without removing others.

Useful if your `RunInspectionsTool` test needs to install a fake inspection.

### 4.4 Mocking `CredentialStore` / `PasswordSafe`

`CredentialStore` is registered as an application service. Test pattern:
```kotlin
val fakeStore = object : CredentialStore {
  private val map = mutableMapOf<CredentialAttributes, Credentials?>()
  override fun get(attrs: CredentialAttributes) = map[attrs]
  override fun set(attrs: CredentialAttributes, creds: Credentials?) { map[attrs] = creds }
}
ApplicationManager.getApplication().replaceService(PasswordSafe::class.java, FakePasswordSafe(fakeStore), disposable)
```

Or simpler: in JetBrains' own tests they use `BasePasswordSafe.setSettings(PasswordSafeSettings().apply { providerType = ProviderType.MEMORY_ONLY })` so secrets stay in-memory. Either approach works.

### 4.5 No `MockApplicationManager` / `MockProject` in modern code

These existed historically but are deprecated. Use `@TestApplication` + `projectFixture()` instead.

---

## 5. Threading & EDT in Tests

### 5.1 Modern: `timeoutRunBlocking` + coroutine dispatchers

JetBrains' explicit recommendation as of 2024+:
> "Plugins targeting versions 2024.1+ should use coroutine dispatchers" rather than older EDT invocation patterns ([threading-model.html](https://plugins.jetbrains.com/docs/intellij/threading-model.html)).

```kotlin
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.openapi.application.UiWithModelAccess
import kotlinx.coroutines.Dispatchers

@Test
fun `tool runs on EDT and writes`() {
  timeoutRunBlocking(timeout = 30.seconds, context = Dispatchers.UiWithModelAccess) {
    writeAction {
      // PSI/VFS mutations
    }
  }
}
```

### 5.2 Legacy: `EdtTestUtil` and `runInEdtAndWait`

**Package:** `com.intellij.testFramework.EdtTestUtil`

Still works but discouraged:
```kotlin
EdtTestUtil.runInEdtAndWait { /* code */ }
val result = EdtTestUtil.runInEdtAndGet { /* code */ }
```

### 5.3 Pumping events: `PlatformTestUtil`

**Package:** `com.intellij.testFramework.PlatformTestUtil`
**Source:** [PlatformTestUtil.java](https://github.com/JetBrains/intellij-community/blob/master/platform/testFramework/src/com/intellij/testFramework/PlatformTestUtil.java)

Verbatim method signatures (from JetBrains source):
- `dispatchAllInvocationEventsInIdeEventQueue()` — drains `invokeLater` queue from EDT context.
- `dispatchAllEventsInIdeEventQueue()` — handles all queued events.
- `waitForPromise(promise: Promise<*>)` — blocks up to 2 minutes.
- `waitForFuture(future: Future<*>)` — dispatches events on EDT while waiting.
- `waitWhileBusy(tree: JTree)` — waits for async tree models.
- `assertDirectoriesEqual(expected: VirtualFile, actual: VirtualFile)` — recursive content comparison.
- `assertTreeEqual(tree: JTree, expected: String)` — string-based tree assertion.

### 5.4 `kotlinx-coroutines-test` compatibility

`runTest { ... }` from `kotlinx-coroutines-test` works fine in IntelliJ tests **as long as you don't touch the EDT**. If you do, switch to `timeoutRunBlocking` from `com.intellij.testFramework.common`.

The current repo uses `runTest { ... }` extensively and that's OK for 90% of tool tests. Mix in `timeoutRunBlocking` only for the IDE-touching tools.

### 5.5 Background tasks (`Task.Backgroundable`)

In tests, `Task.Backgroundable.queue()` runs synchronously by default in the test mode application. To explicitly wait, use `ApplicationManager.getApplication().executeOnPooledThread(...).get()` plus `PlatformTestUtil.waitForFuture(...)` for any subsequent EDT callbacks.

### 5.6 `IndexingTestUtil` and `StartupActivityTestUtil`

For tests that depend on indexes being ready or on `ProjectActivity` having run:
```kotlin
IndexingTestUtil.waitUntilIndexesAreReady(project)        // sync
IndexingTestUtil.suspendUntilIndexesAreReady(project)     // suspend variant
StartupActivityTestUtil.waitForProjectActivitiesToComplete(project)
```

These are essential for `FindReferencesTool`, `TypeHierarchyTool`, `StructuralSearchTool`, and any tool that relies on stub indexes.

### 5.7 Disposable lifecycle

Pass `getTestRootDisposable()` (in `UsefulTestCase`) or `@TestDisposable` (in JUnit 5) as the parent for ANY:
- Service replacement (`replaceService`)
- Extension point registration (`ExtensionTestUtil`)
- Listener installation
- Coroutine scope (`childScope(disposable)`)
- VFS listener
- File watcher

This is non-negotiable — leaks across tests will produce flaky cross-pollination.

---

## 6. HTTP / Network Mocking in IntelliJ Tests

### 6.1 First choice: JetBrains' built-in `localhostHttpServer()`

**Source:** [server.kt](https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5/src/http/server.kt) (verbatim):
```kotlin
@TestOnly
fun localhostHttpServer(): TestFixture<HttpServer> = testFixture {
  val server = HttpServer.create()
  server.bind(InetSocketAddress(LOCALHOST, 0), 1)
  server.start()
  initialized(server) {
    server.stop(0)
  }
}

val HttpServer.url: String
  get() = "http://${LOCALHOST}:${this.address.port}"
```

This uses the JDK's built-in `com.sun.net.httpserver.HttpServer`. Zero classloader issues, zero new dependencies. Pattern:

```kotlin
@TestApplication
class JiraToolTest {
  companion object {
    val httpServer = localhostHttpServer()
  }

  @Test
  fun `getTicket parses Jira response`() = runTest {
    val server = httpServer.get()
    server.createContext("/rest/api/2/issue/PROJ-123") { ex ->
      val body = """{"key":"PROJ-123","fields":{"summary":"Test","status":{"name":"Open"}}}"""
      ex.sendResponseHeaders(200, body.length.toLong())
      ex.responseBody.use { it.write(body.toByteArray()) }
    }
    val client = JiraApiClient(baseUrl = server.url, ...)
    val result = client.getTicket("PROJ-123")
    assertEquals("PROJ-123", result.data?.key)
  }
}
```

### 6.2 Current repo: OkHttp `MockWebServer`

The repo currently uses `okhttp3.mockwebserver.MockWebServer` (e.g. `SourcegraphChatClientTest.kt`). It works fine in plain JUnit 5 tests where you're not running `@TestApplication`. **However**, if you run `MockWebServer` inside `@TestApplication`, beware:
- IntelliJ's test JVM may have stricter classloader isolation; OkHttp's `MockWebServer` uses bundled Okio which usually doesn't conflict, but can clash with bundled-plugin OkHttp versions.
- The cleanup ordering between `@TestApplication` extension and JUnit 5's `@AfterEach` is not guaranteed, so always start/stop `MockWebServer` in `@BeforeEach`/`@AfterEach`, not `@BeforeAll`/`@AfterAll`.

**Recommendation:** Stick with `MockWebServer` for tests of pure HTTP clients (not touching `Project`). Switch to `localhostHttpServer()` fixture for tests that ALSO need a real `Project`/services (e.g. `JiraTool` tests where the tool resolves `JiraService` from `Project`).

### 6.3 Ktor test engine

If you need richer features (streaming, SSE, websockets), Ktor's `MockEngine` or `TestApplication` work in IntelliJ tests too, but they pull in ~30 transitive deps. Avoid unless you're specifically testing SSE/WS.

### 6.4 How JetBrains' bundled plugins do it

The bundled GitHub plugin (`plugins/github`) uses the JDK `HttpServer` approach internally. The bundled Maven plugin spins up an embedded Maven server (real, not mocked). The Sourcegraph Cody JetBrains plugin uses MockWebServer. Pattern verdict: **the JetBrains-blessed approach is the JDK HttpServer fixture, and MockWebServer is acceptable**.

---

## 7. JCEF / Webview Testing

### 7.1 Hard rule: `JBCefApp.isSupported()` first

JCEF will not run on:
- Headless CI without an X server (Linux without `Xvfb`).
- macOS without proper JBR (the bundled JBR ships JCEF; Adoptium does not).
- Tests started with `-Djava.awt.headless=true`.

**Always gate:**
```kotlin
@EnabledIf("isJcefSupported")
@TestApplication
class WebviewTest {
  companion object {
    @JvmStatic fun isJcefSupported() = JBCefApp.isSupported()
  }
  // ...
}
```

### 7.2 What you can test

- **JS bridge contract** (already done in this repo via `BridgeContractTest`): JSON shape validation against shared fixtures, no JCEF needed.
- **`JBCefJSQuery` registration / dispose**: needs `@TestApplication` and a real `JBCefBrowser`. Can run on macOS local but not in headless CI.
- **HTML resource serving** (`CefResourceSchemeHandler`): pure unit test, no JCEF.
- **Markdown rendering**: pure unit test (existing `MarkdownRenderingTest.kt`).

### 7.3 What you cannot easily test

- End-to-end JS execution.
- Visual snapshots.
- React component rendering.

For these, the React side has its own Vitest/Jest tests. For end-to-end, use the **Starter framework** (Section 13) which launches a real IDE with JCEF working.

### 7.4 Mocking JCEF for unit tests

The pragmatic pattern: **wrap `JBCefBrowser` in a thin interface** (`AgentCefHost`) and inject. In unit tests, pass a `FakeAgentCefHost` that records `callJs(...)` invocations. This is what most teams (including Cody) end up doing.

---

## 8. Run Configurations / Debug Testing

### 8.1 RunManager and RunConfiguration

`RunManager` is a real, non-mocked service in `@TestApplication` mode:
```kotlin
@TestApplication
class RuntimeConfigToolTest {
  companion object {
    val project = projectFixture()
  }

  @Test
  fun `creates ApplicationRunConfiguration`() {
    val proj = project.get()
    val rm = RunManager.getInstance(proj)
    val factory = ApplicationConfigurationType.getInstance().configurationFactories[0]
    val settings = rm.createConfiguration("[Agent] Test", factory)
    rm.addConfiguration(settings)

    val tool = RuntimeConfigTool()
    val result = tool.execute(buildJsonObject {
      put("action", "get_run_configurations")
    }, proj)

    assertTrue(result.content.contains("[Agent] Test"))
  }
}
```

**Helper:** `com.intellij.testFramework.runUtil.checkRunConfigurationSerialization(...)` validates that a `RunConfiguration` round-trips through JDOM correctly (verbatim source available at [runUtil.kt](https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/extensions/src/com/intellij/testFramework/runUtil.kt)).

### 8.2 Breakpoints (`XBreakpointManager`)

Fully exercisable in `HeavyPlatformTestCase` or `@TestApplication` + `projectFixture()`:
```kotlin
val bm = XDebuggerManager.getInstance(project).breakpointManager
val type = XBreakpointType.EXTENSION_POINT_NAME.findExtension(JavaLineBreakpointType::class.java)!!
val bp = bm.addLineBreakpoint(type, vfile.url, line, null)
```

### 8.3 `XDebugSession` — feasible but slow

You CAN start a real debug session in tests by:
1. Building a `RunConfiguration`.
2. Calling `ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())`.
3. Listening to `XDebuggerManagerListener.processStarted`.

But this requires a real JVM target, takes 5-15 seconds per test, and is flaky on CI. **Recommendation**: split debug-tool tests into a separate sourceset (`debugIntegrationTest`) that runs only on demand, and unit-test the parameter parsing / state machine logic with mocks.

The repo's `AgentDebugControllerTest.kt` already uses MockK. That's the right call for the tool's state-machine logic. Add a small set of `@TestApplication`-based tests for a single happy path per debug action.

---

## 9. Refactoring Testing

### 9.1 `RefactoringFactory`

```kotlin
@Test
fun `rename refactoring renames Kotlin function`() {
  myFixture.configureByText("Foo.kt", "fun <caret>foo() = 1\nfun bar() = foo()")
  val element = myFixture.elementAtCaret
  RefactoringFactory.getInstance(project).createRename(element, "baz").run()
  myFixture.checkResult("fun baz() = 1\nfun bar() = baz()")
}
```

This is exactly how JetBrains tests its own refactorings. Search the [intellij-community/plugins/kotlin/idea/tests/test/org/jetbrains/kotlin/idea/refactoring](https://github.com/JetBrains/intellij-community/tree/master/plugins/kotlin/idea/tests/test/org/jetbrains/kotlin/idea/refactoring) directory for hundreds of examples.

### 9.2 `myFixture.renameElementAtCaret(newName)`

Cleaner shortcut for the common case:
```kotlin
myFixture.configureByText("Foo.kt", "fun <caret>foo() {}")
myFixture.renameElementAtCaret("bar")
myFixture.checkResult("fun bar() {}")
```

### 9.3 Testing `RefactorRenameTool` (the agent tool)

Need a real `BasePlatformTestCase` (or `projectFixture()` + `psiFileFixture()`). MockK alone won't work because `RefactoringFactory` looks up real PSI elements.

---

## 10. Git Plugin Test Patterns

(Covered in 2.3.) Quick recap of the test class hierarchy:

```
HeavyPlatformTestCase
  └─ VcsPlatformTest
       └─ GitPlatformTest
            └─ (your test class)
```

Bundled Git test base provides `git: TestGitImpl` which intercepts every Git command for assertions, plus `createRepository()` and `prepareRemoteRepo()` helpers. For `GitTool` integration tests (the 11-action meta-tool), this is the right base class.

**Caveat:** `git4idea.test.GitPlatformTest` is in the **test sources** of `git4idea`, not a regular plugin dependency. To consume it from your `:agent` tests, you need `testImplementation` of the git4idea test artifacts. Check the IntelliJ Platform Gradle Plugin v2 docs for `intellijPlatform.testFramework(TestFrameworkType.Plugin.Bundled, ...)` setup.

---

## 11. Headless / Sandbox Mode

### 11.1 Test mode is automatically headless

When running unit tests (`./gradlew :agent:test`), the test JVM sets `idea.is.unit.test = true` and `java.awt.headless = true` automatically. This:
- Disables tool windows.
- Skips most UI updates.
- Runs `invokeLater` synchronously where safe.
- Suppresses notifications.

### 11.2 CI without a display

Unit tests with `@TestApplication` + `projectFixture()` work on Linux CI without `Xvfb`. JCEF tests do not. UI tests via Starter framework need a virtual display.

### 11.3 `intellijPlatform.testIde` block

In `build.gradle.kts`:
```kotlin
intellijPlatform {
  testIde {
    // configures the IDE used for tests
    splitMode = false
  }
}
```

For CI, ensure `org.gradle.jvmargs` includes `-Djava.awt.headless=true -Didea.force.use.core.classloader=true` and set `XDG_DATA_HOME` to a temp dir to avoid clobbering the host's IDE config.

---

## 12. IntelliJ Platform Plugin v2 — Test Setup in build.gradle.kts

### 12.1 Unit tests (the easy case)

Add to `agent/build.gradle.kts`:
```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

dependencies {
    intellijPlatform {
        intellijIdeaUltimate(providers.gradleProperty("platformVersion"))
        bundledPlugins(listOf("Git4Idea", "com.intellij.java", ...))

        // ADD THIS:
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)         // for JUnit5 + @TestApplication
        testFramework(TestFrameworkType.Plugin.Java)     // if you need JavaModuleFixtureBuilder
    }

    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.opentest4j)   // important: see Section 12.4 gotcha
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}
```

`TestFrameworkType` enum values (per [tools-intellij-platform-gradle-plugin-dependencies-extension.html](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html)):
- `Platform` — base, most tests
- `JUnit5` — JUnit 5 extensions and `@TestApplication`
- `Plugin.Java` — Java/Kotlin PSI helpers
- `Platform.Bundled` — rare (Rider only)
- `Starter` — integration tests
- `Plugin.Performance` — performance tests

### 12.2 Integration tests (separate source set)

```kotlin
sourceSets {
  create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
  }
}

dependencies {
  intellijPlatform {
    testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")
  }
  "integrationTestImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
  "integrationTestImplementation"("org.kodein.di:kodein-di-jvm:7.20.2")
  "integrationTestImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
}

val integrationTest by intellijPlatformTesting.testIdeUi.registering {
  task { useJUnitPlatform() }
}
```

(Verbatim from [integration-tests-intro.html](https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html).)

### 12.3 UI tests (Starter framework example)

```kotlin
@Test
fun `simple integration test`() {
  Starter.newContext(
    testName = "agentToolWindow",
    TestCase(
      IdeProductProvider.IU,
      LocalProjectInfo(Path.of("/path/to/test/project"))
    ).withVersion("2025.1")
  ).apply {
    val pluginPath = System.getProperty("path.to.build.plugin")
    PluginConfigurator(this).installPluginFromFolder(File(pluginPath))
  }.runIdeWithDriver().useDriverAndCloseIde {
    waitForIndicators(5.minutes)
    // Driver framework actions...
  }
}
```

### 12.4 Known gotchas (plugin v2 + IntelliJ 2025.1+)

From [tools-intellij-platform-gradle-plugin-dependencies-extension.html](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html), JetBrains explicitly documents:

1. **"Missing opentest4j dependency in Test Framework"** — when using `TestFrameworkType.Platform` with JUnit5. Fix: add `testImplementation("org.opentest4j:opentest4j:1.3.0")` explicitly. (Already in this repo's `libs.versions.toml` as `opentest4j = "1.3.0"`, just needs to be wired into `agent/build.gradle.kts`.)
2. **"JUnit5 Test Framework refers to JUnit4"** — Platform test framework still has lingering JUnit 4 references; you may need both `TestFrameworkType.JUnit5` and `testImplementation("junit:junit:4.13.2")` (excluded as needed). Add `testImplementation(libs.junit)` if you see "JUnit4 not found" errors.
3. **`bundledPlugins` ordering matters.** `Git4Idea` test sources need `com.intellij.java`. The current `agent/build.gradle.kts` already lists both — good.
4. **Configuration cache** — Some `@TestApplication` machinery isn't configuration-cache friendly. If you enable `org.gradle.configuration-cache=true`, expect to see `--no-configuration-cache` needed for `:agent:test` (or pin via `gradle.properties`).
5. **`runIde` sandbox vs test sandbox** — Tests use a separate sandbox. Don't expect IDE state from `runIde` to leak in. Use `intellijPlatform.sandboxContainer` to inspect.

---

## 13. Interaction / UI Testing

### 13.1 Remote Robot — legacy

`com.intellij.remoterobot:remote-robot` was JetBrains' previous UI test framework. **It is not deprecated but no longer the recommended path** as of 2024+. The official `intellij-platform-plugin-template` still includes it:
```kotlin
register("runIdeForUiTests") {
  task {
    jvmArgumentProviders += CommandLineArgumentProvider {
      listOf(
        "-Drobot-server.port=8082",
        "-Dide.mac.message.dialogs.as.sheets=false",
        "-Djb.privacy.policy.text=<!--999.999-->",
        "-Djb.consents.confirmation.enabled=false",
      )
    }
  }
  plugins {
    robotServerPlugin()
  }
}
```

Use only if you need the existing Robot ecosystem (Page Object DSL, XPath locators).

### 13.2 IDE Driver framework — modern

Bundled with the Starter framework (`TestFrameworkType.Starter`). Communicates with the running IDE over a JMX-based RPC. Supports:
- `findElement(query)` / `click()` / `type()`
- Tool window navigation
- File editor manipulation
- Run configuration execution

Per [integration-tests-intro.html](https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html): *"The Starter framework exclusively supports JUnit 5"* and *"the Driver framework enables UI interaction and JMX calls to the IDE's API."*

### 13.3 Page object pattern

For the agent's chat tool window, define a `AgentChatPage` page object that wraps Driver locators for the input bar, plan card, message list, etc. Reuse across tests.

### 13.4 Recommendation for our chat UI

Since the chat is a JCEF webview, end-to-end tests are best done in **two layers**:
- **React layer**: Vitest + React Testing Library inside `agent/webview/`. Already part of the build.
- **Plugin shell layer**: 1-2 Starter framework tests that just verify "tool window opens" and "send a message round-trips through the bridge". Don't try to do XPath assertions on JCEF DOM — Driver doesn't see into JCEF.

---

## 14. Snapshot / Golden / Approval Testing

### 14.1 IntelliJ's built-in approval helpers

- **`PlatformTestUtil.assertDirectoriesEqual(expected, actual)`** — recursive content+structure compare.
- **`myFixture.checkResultByFile("expected.txt")`** — compares editor content vs expected file.
- **`UsefulTestCase.assertSameLinesWithFile(path, actual)`** — line-by-line.

### 14.2 External libraries

- **`com.intellij.testFramework.assertions.Assertions.assertThat(element).isEqualTo(expectedString)`** — JetBrains' XML/JDOM assertion utility (used in `runUtil.kt` for run config serialization).
- **AssertJ** is bundled in the platform test framework (`com.intellij.testFramework.assertions.assertj`).
- For richer snapshots, **Approvals-Kotlin** or **kotest snapshot** can be added. Both are pure JUnit 5 compatible and work fine alongside `@TestApplication`.

**Recommendation**: Use the built-in helpers for tool output assertions (e.g. `attempt_completion` outputs). Add Approvals-Kotlin only if you find yourself maintaining many large golden files.

---

## 15. Property-Based Testing

### 15.1 Kotest property testing

`io.kotest:kotest-property` runs cleanly inside JUnit 5 tests. Compatible with `@TestApplication`.

```kotlin
@TestApplication
class PathValidatorPropertyTest {
  @Test
  fun `path traversal is always blocked`() = runTest {
    checkAll(Arb.list(Arb.string())) { segments ->
      val joined = segments.joinToString("/")
      val result = PathValidator.validate(joined, projectBase)
      if (joined.contains("..")) result.shouldBeInstanceOf<ValidationResult.Rejected>()
    }
  }
}
```

### 15.2 jqwik

`net.jqwik:jqwik` also works but uses its own JUnit 5 engine. If you mix it with `@TestApplication`, ensure `useJUnitPlatform { includeEngines("junit-jupiter", "jqwik") }`.

### 15.3 Recommendation

For agent tools, prop-based tests are most valuable on:
- `PathValidator` (path traversal, symlink, weird chars)
- `JdbcUrlBuilder` (URL injection)
- `CommandSafetyAnalyzer` (command parsing)
- `CredentialRedactor` (regex coverage)

Already covered with example-based tests in this repo. Adding kotest property is a 1-day addition and high ROI.

---

## Decision Matrix — Tool Category → Recommended Test Approach

| Tool category | Examples | Test base | Project type | Mocking strategy | HTTP mock |
|---|---|---|---|---|---|
| **Pure logic / parsing** | `PathValidator`, `JdbcUrlBuilder`, `LoopDetector`, `SubagentToolName`, `CommandSafetyAnalyzer` | Plain JUnit 5 | None | MockK + `@TempDir` | n/a |
| **File ops (path-only, no IDE)** | `EditFileTool`, `CreateFileTool`, `GlobFilesTool`, `ReadFileTool` (plain mode) | Plain JUnit 5 + `@TempDir` OR `jimFsFixture()` | None | `mockk<Project>()` for `basePath` | n/a |
| **File ops (IDE-aware)** | `EditFileTool` (Document API path), `RevertFileTool`, `RollbackChangesTool` (LocalHistory) | `BasePlatformTestCase` OR `@TestApplication + projectFixture()` | Light project | Real services | n/a |
| **PSI / code intelligence** | `FindDefinitionTool`, `FindReferencesTool`, `TypeHierarchyTool`, `StructuralSearchTool`, `RefactorRenameTool` | `BasePlatformTestCase` (with Kotlin descriptor) | Light project | Real services + `myFixture.configureByText` | n/a |
| **Run / build / test** | `RuntimeConfigTool`, `RuntimeExecTool`, `BuildTool` | `@TestApplication + projectFixture()` | Light or heavy depending on Maven/Gradle import | Real `RunManager` | n/a |
| **Debug** | `DebugBreakpointsTool`, `DebugStepTool`, `DebugInspectTool` | Unit: MockK; happy-path: `HeavyPlatformTestCase` | Heavy (real run target) | MockK for state machine, real `XBreakpointManager` for end-to-end | n/a |
| **Git** | `GitTool` (11 actions) | `GitPlatformTest` (extends `HeavyPlatformTestCase`) | Heavy with real `git init` | Real `git` binary | n/a |
| **HTTP service integrations** | `JiraTool`, `BambooBuildsTool`, `BambooPlansTool`, `SonarTool`, `BitbucketPrTool`, `BitbucketReviewTool`, `BitbucketRepoTool` | `@TestApplication` + `localhostHttpServer()` fixture; OR plain JUnit 5 + MockWebServer if not touching `Project` | None or light | `replaceService` for `JiraService`/etc. with fakes that hit the local HTTP server | `localhostHttpServer()` (preferred) OR `MockWebServer` |
| **Sub-agent delegation** | `SpawnAgentTool`, `SubagentRunner` | Plain JUnit 5 + MockK | None | MockK on `AgentLoop` boundary | n/a |
| **Memory** | `core_memory_*`, `archival_memory_*`, `conversation_search` | Plain JUnit 5 + `@TempDir` | None | `mockk<Project>()` for project-id derivation | n/a |
| **Inspections / quickfixes** | `RunInspectionsTool`, `ListQuickfixesTool`, `FindImplementationsTool` | `BasePlatformTestCase` + `myFixture.enableInspections(...)` | Light project | Real inspections | n/a |
| **Shell exec** | `RunCommandTool`, `KillProcessTool`, `SendStdinTool` | Plain JUnit 5 + `@TempDir` + real `bash`/`cmd` | None | Real subprocess via `ProcessBuilder` | n/a |
| **Coverage** | `CoverageTool` | `HeavyPlatformTestCase` (need real run config) | Heavy | Real `CoverageDataManager` | n/a |
| **JCEF / chat UI** | `AgentCefPanel`, `MarkdownRenderingTest` | Plain JUnit 5 (rendering logic); Starter framework (smoke test) | n/a | Bridge fixtures (already done) | n/a |

**Rule of thumb:**
- If the tool uses `service<T>()` from inside a real `Project`: `@TestApplication + projectFixture() + replaceService`.
- If the tool reads/writes PSI: `BasePlatformTestCase` (or `projectFixture() + psiFileFixture()`).
- If neither: plain JUnit 5 + MockK is fine. **This is what 80% of the existing tests do, and that's correct.**

---

## Recommended Dependencies — `build.gradle.kts` Diff

Apply these to `agent/build.gradle.kts`:

```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

dependencies {
    intellijPlatform {
        intellijIdeaUltimate(providers.gradleProperty("platformVersion"))
        bundledPlugins(listOf("Git4Idea", "com.intellij.java", "com.intellij.spring", "org.jetbrains.kotlin"))

        // === ADD THESE ===
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
        testFramework(TestFrameworkType.Plugin.Java)
        // testFramework(TestFrameworkType.Starter)  // only in integrationTest sourceset
    }

    implementation(project(":core"))
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.coroutines.jdk8)
    compileOnly(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.postgresql.jdbc)
    implementation(libs.mysql.jdbc)
    implementation(libs.sqlite.jdbc)

    // Test deps already present:
    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)

    // === ADD THESE ===
    testImplementation(libs.opentest4j)              // already in catalog at v1.3.0
    testImplementation(libs.junit)                   // JUnit4 — sometimes needed by Platform test framework
    // testImplementation("com.google.jimfs:jimfs:1.3.0")  // optional, for jimFsFixture()
    // testImplementation("io.kotest:kotest-property:5.9.1")  // optional, for property tests
}
```

**Version pins (verified against published artifacts):**
| Library | Version | Source |
|---|---|---|
| `org.jetbrains.intellij.platform` (Gradle plugin) | `2.12.0` | already in `libs.versions.toml` |
| `org.opentest4j:opentest4j` | `1.3.0` | already in `libs.versions.toml` |
| `junit:junit` | `4.13.2` | already in `libs.versions.toml` |
| `org.junit.jupiter:junit-jupiter-*` | `5.10.2` | already in `libs.versions.toml` |
| `io.mockk:mockk` | `1.13.10` | already in `libs.versions.toml` |
| `com.google.jimfs:jimfs` | `1.3.0` | published Mar 2024 |
| `io.kotest:kotest-property` | `5.9.1` | published Sep 2024 |

**Note on bundled-plugin test sources:** The IntelliJ Platform Gradle Plugin v2 supports `testFramework(TestFrameworkType.Plugin.Bundled, "Git4Idea")` to pull in Git4Idea's test sources, but availability varies by IDE version. Verify with `./gradlew :agent:dependencies` after adding.

---

## Gotchas & Known Limitations (Plugin v2 + IntelliJ 2025.1+)

1. **`@TestApplication` boots once per JVM, not per test class.** All tests sharing a JVM share the `Application`. If two test classes both call `replaceService(Foo, ...)`, the second wins. Solution: use `@TestDisposable` consistently and never use `forkEvery 0`.
2. **Light tests can pollute each other.** `LightProjectDescriptor.equals()` keys the cache. If two light tests use different descriptors, the project gets recreated — costly. Define a single shared `LightProjectDescriptor` companion object.
3. **`runTest` from `kotlinx-coroutines-test` uses a virtual time scheduler.** Tools that call `delay(...)` will skip time. If you specifically want to test backoff behavior, don't use `runTest`; use `runBlocking` or `timeoutRunBlocking`.
4. **`kotlinx.coroutines.test` and `Dispatchers.Main` conflict in IntelliJ tests.** `Dispatchers.Main` becomes available only after `@TestApplication` boots. Don't access it from `@BeforeAll` static initializers.
5. **VFS write actions in tests still need EDT.** Using `@TestApplication` does NOT bypass this. Either use `WriteCommandAction.runWriteCommandAction(project)` or `timeoutRunBlocking(context = Dispatchers.UiWithModelAccess)`.
6. **`ApplicationManager.getApplication().isUnitTestMode` is `true` in tests.** Use this to gate any production code that should behave differently in tests (e.g. skipping a notification).
7. **`Disposer.assertIsEmpty(true)` will fail if anything leaks across tests.** JetBrains itself runs `assertIsEmpty` in CI. If you see leaks, look for: unregistered listeners, undisposed coroutine scopes, services that hold strong refs to a `Project`.
8. **`./gradlew clean :agent:test --rerun --no-build-cache` is needed after constructor changes** (the project's CLAUDE.md already documents this).
9. **IntelliJ Platform test framework caches the IDE startup.** If you change a `<applicationListener>` or `<projectActivity>` in `plugin.xml`, you need a clean test JVM.
10. **The classpath order for `testFramework(TestFrameworkType.JUnit5)` matters.** Apply it AFTER `testFramework(TestFrameworkType.Platform)`. Otherwise you get "JUnit Jupiter API not on classpath" errors.
11. **`MockWebServer` and `@TestApplication` can coexist** but the test JVM may have a different `OkHttpClient` SSL trust manager. If your tests use HTTPS, install a permissive trust manager in `@BeforeEach`.
12. **`projectFixture()` does NOT install bundled plugins.** Plugin classes are available via the classpath, but extension points registered via `plugin.xml` only fire if the plugin is "loaded" by the test framework. This is normally automatic for the plugin under test, but for OTHER bundled plugins (Git4Idea, com.intellij.java), check `bundledPlugins(listOf(...))` in your `intellijPlatform` block — already correct in this repo.
13. **`@RunInEdt` is deprecated** ([source](https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5/src/RunInEdt.kt)). Use `timeoutRunBlocking(context = Dispatchers.UiWithModelAccess)` for new code.
14. **`@RunMethodInEdt`** is the per-method replacement, but JetBrains' own recommendation is the coroutine context approach.

---

## Concrete Test Templates

### Template A: Pure-logic tool (current style — keep using this)

```kotlin
class PathValidatorTest {
  @TempDir lateinit var tempDir: Path
  private val project by lazy { mockk<Project> { every { basePath } returns tempDir.toFile().absolutePath } }

  @Test
  fun `rejects path traversal`() = runTest {
    val result = PathValidator.validate("../etc/passwd", project)
    assertTrue(result is ValidationResult.Rejected)
  }
}
```

### Template B: Tool that uses an IDE service (NEW pattern to adopt)

```kotlin
@TestApplication
class JiraToolTest {
  companion object {
    val httpServer = localhostHttpServer()
    val project = projectFixture()
  }

  @TestDisposable lateinit var disposable: Disposable

  @BeforeEach
  fun setUp() {
    val proj = project.get()
    val server = httpServer.get()
    server.createContext("/rest/api/2/issue/PROJ-1") { ex ->
      val body = """{"key":"PROJ-1","fields":{"summary":"x"}}"""
      ex.sendResponseHeaders(200, body.length.toLong())
      ex.responseBody.use { it.write(body.toByteArray()) }
    }

    val fakeJira = JiraServiceImpl(baseUrl = server.url, ...)
    proj.replaceService(JiraService::class.java, fakeJira, disposable)
  }

  @Test
  fun `getTicket meta-action returns ToolResult success`() = runTest {
    val tool = JiraTool()
    val params = buildJsonObject {
      put("action", "get_ticket")
      put("issue_key", "PROJ-1")
    }
    val result = tool.execute(params, project.get())
    assertFalse(result.isError)
    assertTrue(result.content.contains("PROJ-1"))
  }
}
```

### Template C: PSI-touching tool

```kotlin
class FindDefinitionToolTest : BasePlatformTestCase() {
  override fun getProjectDescriptor(): LightProjectDescriptor = KotlinLightProjectDescriptor.INSTANCE

  fun `test find definition of Kotlin function`() = runBlocking {
    myFixture.configureByText("Foo.kt", """
      fun foo() = 1
      fun bar() = <caret>foo()
    """.trimIndent())

    val tool = FindDefinitionTool()
    val params = buildJsonObject {
      put("path", myFixture.file.virtualFile.path)
      put("line", 2)
      put("column", 14)
    }
    val result = tool.execute(params, project)
    assertFalse(result.isError)
    assertTrue(result.content.contains("foo"))
  }
}
```

(Note: `BasePlatformTestCase` is JUnit 3, so test methods start with `test` and you can't use `@Test`. If you want JUnit 5, use Template D below.)

### Template D: PSI-touching tool with JUnit 5 fixtures

```kotlin
@TestApplication
class FindDefinitionToolTest {
  companion object {
    val project = projectFixture()
    val mod = project.moduleFixture("main")
    val src = mod.sourceRootFixture()
    val fooKt = src.psiFileFixture("Foo.kt", """
      fun foo() = 1
      fun bar() = foo()
    """.trimIndent())
  }

  @Test
  fun `find definition of Kotlin function`() = runTest {
    val proj = project.get()
    val file = fooKt.get()
    val tool = FindDefinitionTool()
    val params = buildJsonObject {
      put("path", file.virtualFile.path)
      put("line", 2)
      put("column", 14)
    }
    val result = tool.execute(params, proj)
    assertFalse(result.isError)
  }
}
```

### Template E: Git tool

```kotlin
class GitToolIntegrationTest : GitPlatformTest() {
  fun `test status action returns clean state`() {
    val repo = createRepository(projectRoot.path)
    repo.checkout("main")
    git("add", "README.md")
    git("commit", "-m", "init")

    val tool = GitTool()
    val params = buildJsonObject {
      put("action", "status")
    }
    val result = runBlocking { tool.execute(params, project) }
    assertFalse(result.isError)
    assertTrue(result.content.contains("clean"))
  }
}
```

---

## References

### JetBrains Official Docs (canonical)
- Testing overview: https://plugins.jetbrains.com/docs/intellij/testing-plugins.html
- Light vs heavy: https://plugins.jetbrains.com/docs/intellij/light-and-heavy-tests.html
- Tests and fixtures: https://plugins.jetbrains.com/docs/intellij/tests-and-fixtures.html
- Test data dirs: https://plugins.jetbrains.com/docs/intellij/test-project-and-testdata-directories.html
- Writing tests: https://plugins.jetbrains.com/docs/intellij/writing-tests.html
- Testing highlighting: https://plugins.jetbrains.com/docs/intellij/testing-highlighting.html
- Testing FAQ: https://plugins.jetbrains.com/docs/intellij/testing-faq.html
- Threading model: https://plugins.jetbrains.com/docs/intellij/threading-model.html
- Integration tests intro: https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html
- Embedded browser (JCEF): https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html
- Plugin v2 dependencies extension: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
- Plugin v2 extension: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html

### IntelliJ Community Source
- JUnit 5 framework root: https://github.com/JetBrains/intellij-community/tree/master/platform/testFramework/junit5/src
- `TestApplication.kt`: https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5/src/TestApplication.kt
- `TestDisposable.kt`: https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5/src/TestDisposable.kt
- `RunInEdt.kt` (deprecated): https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5/src/RunInEdt.kt
- `RegistryKey.kt`: https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5/src/RegistryKey.kt
- `fixtures.kt` (Project/Module/PsiFile/Editor fixtures): https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5/src/fixture/fixtures.kt
- `localhostHttpServer()` (HTTP fixture): https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5/src/http/server.kt
- `JimFsFixture.kt` (in-memory FS): https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/junit5.jimfs/src/JimFsFixture.kt
- `runUtil.kt` (run config helpers): https://raw.githubusercontent.com/JetBrains/intellij-community/master/platform/testFramework/extensions/src/com/intellij/testFramework/runUtil.kt
- Showcase tests: https://github.com/JetBrains/intellij-community/tree/master/platform/testFramework/junit5/test/showcase
  - `JUnit5ApplicationTest.kt`
  - `JUnit5ProjectFixtureTest.kt`
  - `JUnit5ModuleFixtureTest.kt`
  - `JUnit5PsiFileFixtureTest.kt`
  - `JUnit5EditorFixtureTest.kt`
  - `JUnit5DisposableTest.kt`
- `BasePlatformTestCase.java`: https://github.com/JetBrains/intellij-community/blob/master/platform/testFramework/src/com/intellij/testFramework/fixtures/BasePlatformTestCase.java
- `HeavyPlatformTestCase.java`: https://github.com/JetBrains/intellij-community/blob/master/platform/testFramework/src/com/intellij/testFramework/HeavyPlatformTestCase.java
- `GitPlatformTest.kt` (Git plugin test base): https://github.com/JetBrains/intellij-community/blob/master/plugins/git4idea/tests/git4idea/test/GitPlatformTest.kt
- `LightProjectDescriptor.java`: https://github.com/JetBrains/intellij-community/blob/master/platform/testFramework/src/com/intellij/testFramework/LightProjectDescriptor.java

### Plugin Template
- IntelliJ Platform Plugin Template: https://github.com/JetBrains/intellij-platform-plugin-template

---

## Migration Plan for `:agent` Module (1-week implementation)

Based on this research, here's the recommended path to test ~70 tools as real-world as possible:

**Phase 1 (1 day) — Wire IntelliJ test framework into the agent module's build.**
- Add `testFramework(TestFrameworkType.Platform)`, `testFramework(TestFrameworkType.JUnit5)`, `testFramework(TestFrameworkType.Plugin.Java)` to `agent/build.gradle.kts`.
- Add `testImplementation(libs.opentest4j)` and `testImplementation(libs.junit)` (for JUnit4 transitive).
- Create a smoke test using `@TestApplication` + `projectFixture()` and confirm `./gradlew :agent:test` still passes.

**Phase 2 (2 days) — Convert HTTP integration tests to use `localhostHttpServer()`.**
- For each `*Tool` in `tools/integration/`, write a `@TestApplication` + `localhostHttpServer()` test that exercises 1 happy path and 1 error path per action.
- Keep existing MockK-based tests for the HTTP client itself; the new tests cover the full tool→service→HTTP path.

**Phase 3 (2 days) — Convert PSI tools to `BasePlatformTestCase` or fixture DSL.**
- `tools/psi/*Tool.kt` (10 tools): Migrate from MockK to `BasePlatformTestCase` with Kotlin/Java descriptor. These currently can't catch real PSI bugs.
- `tools/builtin/EditFileTool.kt`: Add a second test class using `@TestApplication + projectFixture()` to exercise the Document API path (the file-based path is already tested).

**Phase 4 (1 day) — Add Git integration tests via `GitPlatformTest`.**
- One end-to-end test per `git` action (11 tests total). Each creates a real `git init` repo via `createRepository()`, runs the tool, asserts on output.

**Phase 5 (1 day) — Add a Starter framework integration test for tool window smoke.**
- New `agent/integrationTest/` source set.
- One test that launches IDE, opens the agent tool window, sends a message, asserts a response appears.
- Runs only on demand (`./gradlew :agent:integrationTest`), not as part of default `check`.

---
