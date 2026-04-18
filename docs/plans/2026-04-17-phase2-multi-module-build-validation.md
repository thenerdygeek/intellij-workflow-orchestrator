# Phase 2 — Multi-Module Build Validation

**Fixes:** "Module resolved in IntelliJ but the build tool can't find it" failures — the most common reason `run_tests` fails silently in a multi-module project. Currently the shell fallback either runs a doomed command (returns 0 tests) or the native runner errors opaquely. Pre-flight validation surfaces actionable errors **before** dispatch runs against invalid state.

**Audit source:** Identified during Phase 1 review (2026-04-17) as a gap in the native-runner and shell fallback paths.

**Preconditions:** None. Runs independently of Phase 1. If Phase 1 is still in flight, Phase 2 can start in parallel — the two phases touch disjoint code paths (Phase 1 = result parsing, Phase 2 = pre-dispatch validation).

**Estimated:** 1–2 days. Small-medium complexity.

---

## Context

Current state of module resolution in `JavaRuntimeExecTool`:

1. `findModuleForClass(project, className)` uses `JavaPsiFacade.findClass` + `ModuleUtilCore.findModuleForPsiElement` to resolve the IntelliJ `Module` — **multi-module aware**.
2. Native runner passes that module into `JUnitConfiguration.setModule(...)` — works.
3. Shell fallback (`executeWithShell` at `JavaRuntimeExecTool.kt:634–787`) derives a Gradle subproject path or Maven module dir from the module's `contentRoot` relative to `basePath`:
   ```kotlin
   val rel = contentRoot.removePrefix(basePath).trimStart('/', File.separatorChar)
   if (rel.isNotBlank()) ":" + rel.replace(File.separatorChar, ':') else null
   ```
   Works when the filesystem layout matches the build tool's declared project structure.

**Failure modes the current code doesn't detect:**

| # | Scenario | Current behavior | Impact |
|---|---|---|---|
| 1 | Module added to IntelliJ (Gradle refresh picked it up) but missing from `settings.gradle` | Shell fires `./gradlew :newmodule:test` → Gradle errors "project ':newmodule' not found" → exit non-0, no XML → Phase 1 reports NO_TESTS_FOUND with no root cause | High |
| 2 | LLM passes a class in main sources (e.g. `MyService` instead of `MyServiceTest`) | Compiles fine, finds zero `@Test` methods, Surefire reports `Tests run: 0`, exit 0 → Phase 1 reports NO_TESTS_FOUND with "class has no test methods" — but by then the shell already ran a wasted full compile | Medium-High |
| 3 | Nested Gradle module `services/parent/auth` where `services/parent` is just a directory, not a Gradle project | Derived path `:services:parent:auth:test` fails because `:services:parent` isn't declared | Medium |
| 4 | Source root outside module contentRoot (unusual but legal IntelliJ layout) | Filesystem-derived shell path is wrong | Low |
| 5 | Gradle composite builds (`includeBuild`) | Derived `:X` path doesn't work; composite builds need different invocation | Low (rare) |
| 6 | Stale module index (user hasn't refreshed after adding module) | `findModuleForClass` returns null, existing error message surfaces "re-import the module" — already covered | None (already handled) |

Silently-wrong cases (1, 2, 3, 4) are worse than outright failures because the LLM gets a "successful-looking" result. The fix is pre-dispatch validation that distinguishes these scenarios and returns actionable errors.

---

## Scope

**In:**
- New class `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/BuildSystemValidator.kt` — encapsulates pre-dispatch checks.
- `JavaRuntimeExecTool.kt` — invoke validator before both the native and shell paths.
- Platform-aware subproject resolution via Gradle and Maven IntelliJ integrations (both are **optional plugins** — handle gracefully when absent).

**Out:**
- Gradle composite builds / cross-project `includeBuild` — rare, defer.
- Auto-refreshing stale Gradle projects — user action, not tool action; keep the existing "re-import" error message.
- Python equivalent (pytest collection pre-check) — Phase 4 (pytest native port) reuses this pattern.

---

## Task list

### Task 2.1 — `BuildSystemValidator` class

**New file:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/BuildSystemValidator.kt`.

Single entry point:
```kotlin
class BuildSystemValidator(private val project: Project) {
    sealed class ValidationResult {
        data class Ok(
            val module: Module,
            val authoritativeBuildPath: String?,  // e.g. ":services:auth" from Gradle, or module.dir from Maven
            val testSourceRoot: VirtualFile,
            val detectedTestCount: Int           // PSI count of @Test methods in the class
        ) : ValidationResult()

        data class Blocked(
            val reason: String,
            val suggestion: String
        ) : ValidationResult()
    }

    fun validateForTestRun(className: String, module: Module): ValidationResult
}
```

Runs the checks in Tasks 2.2–2.5 in order; short-circuits on first failure.

### Task 2.2 — Test source root verification (fixes failure mode 2)

Use `ModuleRootManager.getInstance(module).sourceRoots(JavaSourceRootType.TEST_SOURCE)` to get test source roots. Resolve the PSI class's `VirtualFile`; check it lives under one of them.

If the class is found but NOT under a test source root:
```
Blocked(
    reason = "Class 'com.example.MyService' is in main sources (src/main/java), not test sources.",
    suggestion = "Tests live under src/test/java. Candidates in this module: MyServiceTest, MyServiceIntegrationTest. " +
                 "Use test_finder to list all test classes if unsure."
)
```

Use `TestFinderTool`'s existing discovery logic (or extract a shared helper) for the "candidates" list so we surface a useful did-you-mean.

### Task 2.3 — Gradle subproject validation (fixes failure modes 1, 3)

When Gradle is detected (`hasGradle = File(baseDir, "build.gradle").exists() || File(baseDir, "build.gradle.kts").exists()`):

1. Probe the Gradle integration via reflection (zero compile-time dependency on the Gradle plugin):
   ```kotlin
   val gradleConstantsClass = Class.forName("org.jetbrains.plugins.gradle.util.GradleConstants")
   val systemId = gradleConstantsClass.getField("SYSTEM_ID").get(null)
   val externalSystemApiUtilClass = Class.forName("com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil")
   val findProjectNode = externalSystemApiUtilClass.getMethod("findProjectNode", Project::class.java, com.intellij.openapi.externalSystem.model.ProjectSystemId::class.java)
   val projectNode = findProjectNode.invoke(null, project, systemId)
   ```
2. Walk the project tree (`DataNode<ProjectData>` → children of type `ModuleData`) to find the IntelliJ module's Gradle path via `moduleData.getId()` (returns `:services:auth`).
3. If the module is not in the Gradle project tree:
   ```
   Blocked(
       reason = "Module 'services/auth' exists in IntelliJ but Gradle's settings.gradle doesn't declare it.",
       suggestion = "Add `include ':services:auth'` to settings.gradle, then run File | Gradle | Refresh."
   )
   ```
4. If found, cache the authoritative Gradle path (replaces filesystem-derived `:services:auth`).

**Graceful fallback:** If reflection throws (Gradle plugin not installed, API changed), log and fall through to Task 2.5's filesystem derivation.

### Task 2.4 — Maven module validation (fixes failure mode 1 for Maven)

When Maven is detected:
1. Reflective probe of `MavenProjectsManager`:
   ```kotlin
   val mavenProjectsManagerClass = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
   val getInstance = mavenProjectsManagerClass.getMethod("getInstance", Project::class.java)
   val manager = getInstance.invoke(null, project)
   val findProject = mavenProjectsManagerClass.getMethod("findProject", Module::class.java)
   val mavenProject = findProject.invoke(manager, module)
   ```
2. If null:
   ```
   Blocked(
       reason = "Module 'services/auth' exists in IntelliJ but Maven hasn't registered it.",
       suggestion = "Add <module>services/auth</module> to the parent pom, then run Maven | Reload Project."
   )
   ```
3. If present, use `mavenProject.directory` as the authoritative submodule directory (supersedes filesystem derivation).

**Graceful fallback:** same as Task 2.3.

### Task 2.5 — Filesystem fallback for non-standard layouts (fixes failure mode 4)

If neither Gradle nor Maven integration APIs know about the module, but a `build.gradle[.kts]` / `pom.xml` exists at the module's contentRoot:
- Proceed with current filesystem-derived path.
- Attach a non-blocking warning to the result: `"[WARNING] Using filesystem-derived build path. If this is a composite build or non-standard layout, invocation may fail."`

If NO build file exists at the contentRoot → `Blocked`: `"Module 'X' has no pom.xml or build.gradle at its content root. Test dispatch requires a build tool."`

### Task 2.6 — Test method count pre-check (fixes failure mode 2 extended)

After resolving the class, count `@Test`-annotated methods via PSI (reuse `TestFinderTool` logic). If count == 0:
```
Blocked(
    reason = "Class 'com.example.MyTest' exists in test sources but contains zero @Test methods.",
    suggestion = "Check the test annotations — JUnit 5 uses @Test from org.junit.jupiter.api, JUnit 4 uses org.junit.Test."
)
```

This catches the "class name looks like a test but is empty / has wrong imports" scenario before the shell wastes compile cycles.

### Task 2.7 — Wire validator into `executeRunTests`

**File:** `JavaRuntimeExecTool.kt:143–195`.

After `val module = findModuleForClass(project, className)` succeeds (and before `useNativeRunner` branch):

```kotlin
val validation = BuildSystemValidator(project).validateForTestRun(className, module)
when (validation) {
    is ValidationResult.Blocked -> return ToolResult(
        content = "${validation.reason}\n\n${validation.suggestion}",
        summary = validation.reason.substringBefore('\n'),
        tokenEstimate = 50,
        isError = true
    )
    is ValidationResult.Ok -> {
        // pass validation.authoritativeBuildPath and validation.detectedTestCount down
    }
}
```

### Task 2.8 — `executeWithShell` uses authoritative path when available

**File:** `JavaRuntimeExecTool.kt:634–787`.

Current code derives `gradleSubprojectPath` / `mavenModuleDir` inline. Change:
- If validator provided `authoritativeBuildPath` → use it directly.
- If `authoritativeBuildPath == null` → fall through to the existing filesystem derivation + warn.

Keep the filesystem derivation as the fallback path; don't delete it.

### Task 2.9 — Surface discovered context in tool result

Even on success, prepend a breadcrumb line to the result:
```
Running tests in module: services/auth (Gradle path: :services:auth, 42 test methods detected in MyTest)
```

This gives the LLM a sanity-check anchor: if the line says "0 test methods detected" but the final count is 0, the cause is clear. If it says "42 methods detected" but final count is 0, that's a different bug (compile or runner failure).

### Task 2.10 — Tests

**New file:** `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/BuildSystemValidatorTest.kt`.

Fixtures covering each failure mode:
1. Multi-module Gradle project with one module NOT in `settings.gradle` → assert `Blocked` with "add to settings.gradle" suggestion.
2. Class in `src/main/java` → assert `Blocked` with did-you-mean hint.
3. Maven multi-module with missing `<module>` declaration → assert `Blocked` with reload-maven suggestion.
4. Happy path where Gradle's declared path differs from filesystem derivation → assert the authoritative path is returned.
5. Test class with zero `@Test` methods → assert `Blocked` with annotation hint.
6. Non-standard layout (no Gradle/Maven integration, but build file present) → assert `Ok` with warning attached.
7. Nested module without intermediate Gradle project declaration → assert `Blocked`.

Use `HeavyPlatformTestCase` or `BasePlatformTestCase` depending on Gradle-plugin test support. If the Gradle plugin isn't available in the test classpath, mock the reflective lookups.

---

## Validation

```bash
./gradlew :agent:test --tests "*BuildSystemValidator*" --tests "*Runtime*"
./gradlew verifyPlugin
```

## Manual verification

In a multi-module Gradle sandbox project:
1. **Scenario 1 reproduction:** Temporarily comment out `include ':services:auth'` in `settings.gradle`. Reload Gradle (IntelliJ will re-import, module still exists in IntelliJ from the previous refresh). Ask agent to run a test in `:services:auth`.
   - Before fix: shell runs `./gradlew :services:auth:test`, fails, no XML, Phase 1 reports NO_TESTS_FOUND.
   - After fix: validator catches it, returns "Module 'services/auth' exists in IntelliJ but Gradle's settings.gradle doesn't declare it. Add `include ':services:auth'`..."

2. **Scenario 2 reproduction:** Ask agent to run `com.example.MyService` (production class, not a test).
   - Before fix: compiles, Surefire runs 0 tests, reports NO_TESTS_FOUND (after Phase 1).
   - After fix: validator catches it, returns "Class 'com.example.MyService' is in main sources... Candidates: MyServiceTest, MyServiceImplTest."

## Exit criteria

- `BuildSystemValidator` runs before every native/shell dispatch in `java_runtime_exec.run_tests`.
- All four "silently wrong" failure modes (settings.gradle mismatch, main-sources class, nested module, zero `@Test` methods) produce actionable errors with suggestions.
- Authoritative Gradle/Maven paths used when the IntelliJ integration plugin is present; filesystem fallback preserved otherwise.
- Test count pre-check surfaces "0 test methods" before any shell dispatch.
- Tool result includes the resolved module + detected count breadcrumb on success.
- Seven test fixtures cover each scenario.

## Follow-ups

- **Python equivalent** — pytest's `--collect-only -q` pre-probe before running tests, same "module not in build config" detection via PyCharm's `PyProjectsManager`. Feed into Phase 4.
- **Gradle composite builds** — `includeBuild` support via `CompositeBuildManager`. Separate spike.
- **Missing-JUnit-dependency detection** — class has `@Test` annotation but the test framework isn't on the classpath. Current behavior: JUnit's `initializationError` with confusing message. Could pre-check via module dependencies. Defer; use test output to diagnose when it surfaces.
