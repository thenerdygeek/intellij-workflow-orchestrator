---
name: project_gradle_transform_bloat_94_bump
description: "Gradle artifact-transforms 50GB cache bloat fix + standardize on Gradle 9.4.0 (cross-ide) + the \"delete dirs named build\" source-loss trap"
metadata: 
  node_type: memory
  type: project
  originSessionId: d0662eb7-b474-4280-8d6c-afe69088c222
---

**SHIPPED 2026-05-29 (committed, unpushed) on `feature/cross-ide-delegation` @ `3aed75a15`.**

Artifact-transforms cache (`~/.gradle/caches/<version>/transforms`) ballooned past 50GB on a 228GB disk. Root cause: transforms are keyed **per Gradle version**, and three versions were live — IDE daemon on **8.10.2**, CLI wrapper **9.0.0**, system/brew **9.4.0** — each keeping its own full extracted-IDE + instrumentation tree, none GC'd while "in use". Multiplied further by `pluginVerification { ides { recommended() } }` extracting extra verifier IDEs (e.g. 2025.2). One IDE distribution is `idea/ideaIU` (Ultimate); `intellijIdea(...)` and `intellijIdeaUltimate(...)` both resolve to ideaIU, so the root-vs-submodule variant split is cosmetic, not duplication.

Fix (root `build.gradle.kts` + wrapper):
- Verifier pinned: `recommended()` → `create(IntelliJPlatformType.IntellijIdeaUltimate, platformVersion)` — reuses the build's own content-addressed IDE extraction (zero extra). ⚠ API is `create(type, version)`, NOT `ide(...)` (which doesn't exist in plugin 2.12.0; verified via javap on `IntelliJPlatformExtension$PluginVerification$Ides`).
- `configurations.all` → `configureEach` for SLF4J exclude (lazy).
- Wrapper bumped **9.0 → 9.4.0** so IDE+CLI share ONE tree. Bumping Gradle in this verification-enabled project REQUIRES regenerating `gradle/verification-metadata.xml` (9.4.0 brings kotlin-stdlib 2.3.0 build-script deps): `./gradlew --write-verification-metadata sha256 help` (merges, +3 components). Project protocol documented in `build.gradle.kts`.
- Existing `~/.gradle/init.d/cache-cleanup.gradle` already evicts transforms (`createdResources=7d`, `Cleanup.ALWAYS`) — but only UNUSED entries, so it can't fix multi-version multiplication; only one-version-everywhere does.

Manual step still needed: **IntelliJ → Settings → Build Tools → Gradle → Distribution: Wrapper** (now 9.4.0) so the IDE stops spawning the 8.10.2 tree. `.idea/` is gitignored so this can't be committed.

⚠ **TRAP that caused this session's build failures:** a disk-cleanup operation deleted **every directory named `build`** (likely `find . -name build -exec rm -rf`), which destroyed SOURCE packages named `build` — `core/.../core/model/build/` (BuildProblem.kt: BuildProblem/BuildSource/ProblemType/Severity) and `agent/.../tools/framework/build/` (18 files, consumed by BuildTool.kt). 19 files, all committed in HEAD, restored via `git restore`. To clear Gradle output safely use `./gradlew clean`, never a bare `-name build` glob. See [[project_cross_ide_plans_3_3_1_4_shipped]].

**Follow-up (committed `3d0da6fce`): aggregate `./gradlew test` fixed for Gradle 9.x.** The bump exposed a latent issue — `:core/:document/:jira/:automation/:agent` shared the default ROOT sandbox (`.intellijPlatform/sandbox`), so `:automation:test` consumed other modules' `prepareTestSandbox` outputs without a declared dep → Gradle 9.x strict task-graph validation hard-fails (only on the AGGREGATE run; per-module `./gradlew :x:test` always passed, which is why it went unnoticed — CLAUDE.md documents per-module). Fix: gave each of the 5 its own `intellijPlatform { sandboxContainer = layout.buildDirectory.dir("idea-sandbox") }`, matching `:bamboo/:sonar/:pullrequest/:handover` which already did. Verified: 4321 tests, 0 real failures. ⚠ `SharedCatalogHolderTest.warm-up coroutine survives a getCatalog throw` is a PRE-EXISTING flaky coroutine-timing test — fails under aggregate parallel CPU contention, passes in isolation; not a regression. ⚠ Gradle-version bouncing during diagnostics corrupted a 9.4.0 transform entry (missing `transformed/` dir) + left stale daemons pinning it — fix was `rm` the bad entry + `./gradlew --stop`. The `Cleanup.ALWAYS` init script can evict transforms aggressively.
