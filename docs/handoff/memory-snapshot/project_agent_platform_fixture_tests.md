---
name: project_agent_platform_fixture_tests
description: ":agent now has BasePlatformTestCase platform-fixture test infra + the indexing-timeout / LightVirtualFile gotchas for testing Document/VFS code"
metadata: 
  node_type: memory
  type: reference
  originSessionId: eb2d0596-d309-4373-af8d-50bbcbba5496
---

# :agent platform-fixture (BasePlatformTestCase) test infra — added 2026-06-09 (PR #46)

Before PR #46, `:agent` had NO live-platform test infra (everything used `mockk<Project>`). The
data-loss bug #3 fix ([[project_subagent_contextbar_bugfixes]]) needed a real Document/VFS, so it added:
- `agent/build.gradle.kts`: `testFramework(TestFrameworkType.Platform)` (in the `intellijPlatform { }`
  deps block) + `testRuntimeOnly(libs.junit5.vintage.engine)` (BasePlatformTestCase is JUnit4 → needs the
  vintage engine to run under `useJUnitPlatform()`). Mirror `:core`'s setup.
- Regenerate `agent/gradle.lockfile` after adding deps: `./gradlew :agent:dependencies --write-locks
  --write-verification-metadata sha256` (the test-framework artifacts are already SHA-pinned by :core, so
  verification-metadata.xml usually doesn't change — only the per-module lockfile). Without this you get
  "Resolved 'com.jetbrains.intellij.platform:test-framework:...' which is not part of the dependency lock
  state". The model is `core/src/test/.../WorkflowContextEditorIntegrationTest.kt`.

## ⚠ Hard-won gotchas (cost a full day)
- **A SECOND `BasePlatformTestCase` test method in the same class/JVM hangs on "Indexing timeout"**
  (`AssertionError: Indexing timeout` at `PlatformTestUtil.java:1244`, ~5min) in this headless env. The
  FIRST method passes; the second's setUp/tearDown index-wait never completes. **Workaround: put everything
  in ONE test method** (or fork per test). This is a platform/lifecycle flake, NOT your code.
- **⚠ 2026-06-11 EXTENSION (PR #56 walkthrough): it's not just a 2nd METHOD — two SEPARATE
  `BasePlatformTestCase` CLASSES in one test JVM collide the same way.** Whichever class runs SECOND hangs
  in its OWN `setUp` on `waitUntilIndexesAreReady`, regardless of test body. Proven: `EditFilePersistenceFixtureTest`
  + a walkthrough fixture in one `:agent:test` invocation → the second times out (20-min hang). So the
  full single-JVM `:agent:test` is inherently flaky on loaded/headless runners with ≥2 heavy fixture classes
  (this IS issue #51). No `forkEvery` is set in `agent/build.gradle.kts` (only `maxHeapSize=4g`,
  `useJUnitPlatform()`). Mitigations: (a) MERGE related fixture coverage into ONE class + ONE method
  (walkthrough did this — `WalkthroughFixtureTest` folds navigator + validator); (b) for validation/logic
  that only needs a VirtualFile, add an injectable resolver seam and pass a `LightVirtualFile` so the test
  doesn't need the heavy fixture at all; (c) verify each fixture class in ISOLATION + rely on CI's unloaded
  runner for the combined run. Do NOT add `forkEvery=1` unilaterally (forks per class × ~112 test files = very slow).
- **`EditFileTool` (and the write tools) operate on REAL-disk `LocalFileSystem` files + `java.io.File`**, but
  `BasePlatformTestCase`'s managed project uses in-memory temp VFS. Bridging to real-disk files
  (`refreshAndFindFileByPath` under ~/.workflow-orchestrator) drags in the indexing machinery → 20-min hangs.
  **For Document-path tests use an in-memory `com.intellij.testFramework.LightVirtualFile("name", text)`** —
  `FileDocumentManager.getDocument(lightVFile)` gives a real Document with NO LocalFileSystem/disk/indexing.
  Fast + deterministic. (`writeViaDocument` works on it; a full `execute()` won't — it needs LocalFileSystem.)
- **Run OFF the EDT**: `override fun runInDispatchThread(): Boolean = false`, then `runBlocking { }` is safe and
  the tool's `withContext(Dispatchers.EDT)` dispatches to the real AWT EDT without deadlock. Wrap any
  helper-side `getDocument`/VFS access in `runReadAction { }` (the test logger escalates threading-assertion
  WARNINGS into hard failures — "Read access is allowed from inside read-action only").
- Source-text contract tests (`EditFileDocumentWriteContractTest`, `WriteToolsSuspendApiTest`) slice
  `EditFileTool.kt` on `"private suspend fun writeViaDocument"` — changing visibility to `internal` for
  testability broke them; update the matcher. Same family as [[project_source_text_sentinel_slice_trap]].
- detekt `Wrapping:EditFileTool.kt$...` baseline pins the WHOLE `runWriteCommandAction(...)` element text
  incl. the lambda body — editing inside the lambda invalidates it; surgically update the `<ID>`.

Reference test: `agent/src/test/.../tools/builtin/EditFilePersistenceFixtureTest.kt`.
