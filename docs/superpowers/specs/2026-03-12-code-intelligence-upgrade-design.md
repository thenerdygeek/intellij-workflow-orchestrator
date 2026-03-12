# Code Intelligence Upgrade — Design Specification

> **Scope:** Cross-cutting upgrade across ALL modules (`:core`, `:cody`, `:bamboo`, `:sonar`, `:handover`)
> **Goal:** Establish IDE-level code intelligence as a first-class capability — every feature that touches code uses IntelliJ's deep understanding (PSI, SpringManager, MavenProjectsManager, ProjectFileIndex) instead of regex/string parsing
> **Build target change:** `intellijIdea()` → `intellijIdeaUltimate()` to enable compile-time Spring API access
> **Backward compatibility:** All upgrades use optional dependencies with graceful fallback — plugin still installs on Community edition
> **Target intelligence level:** ~90% of available IntelliJ intelligence (see §2.4 for what the remaining ~10% represents)

---

## 1. Overview

The Workflow Orchestrator plugin currently treats IntelliJ as a container rather than a source of intelligence. Across 19 features, code context is either absent, hardcoded, or extracted via regex — when the IDE already understands the code's structure, types, annotations, Spring semantics, and Maven module graph.

This spec covers two categories of upgrades:

**Category A — Fix Bad Patterns (7 files):** Replace regex/string parsing with proper IntelliJ APIs where the code is actively doing things wrong (e.g., hand-parsing `pom.xml` XML when `MavenProjectsManager` exists).

**Category B — Add Missing Intelligence (12 features):** Wire IntelliJ's PSI/Spring/Maven intelligence into features that currently have zero code context — most critically, the Cody AI integration where the difference between "raw file path" and "Spring bean with `@Transactional` scope, injected dependencies, and related test file" is the difference between a generic fix and an accurate one.

**Design philosophy:** Every upgrade maintains a fallback path. If Maven plugin isn't loaded yet (project importing), fall back to regex. If Spring plugin isn't installed (Community edition), provide PSI-only context. No feature degrades — they only get better when richer APIs are available.

**Intelligence ceiling:** We target ~90% of available IntelliJ intelligence. The remaining ~10% consists of runtime-only information (active Spring profiles, `@ConditionalOnProperty` resolution, AOP proxy chains, database schema), cross-system correlation (APM data, production traffic), and diminishing-return PSI traversals (500+ reference searches). Cody's AI compensates for this gap — 90% context lets it infer the rest; 10% context means it's guessing blind.

---

## 2. Scope

### 2.1 Category A — Fix Bad Patterns (7 files using regex where IntelliJ APIs exist)

| # | File | Module | Current Problem | Upgrade |
|---|------|--------|----------------|---------|
| A1 | `MavenModuleDetector.kt` | `:core` | Regex XML state machine to parse `pom.xml` | `MavenProjectsManager` API |
| A2 | `CodyContextService.kt` + `CodyContextServiceLogic.kt` | `:cody` | String-based test resolution, zero Spring/PSI context | PSI + Spring enrichment |
| A3 | `CopyrightCheckService.kt` | `:core` | Hardcoded file extensions, raw byte reading | `FileTypeRegistry`, document API |
| A4 | `CopyrightFixService.kt` | `:handover` | Hardcoded comment syntax, hardcoded generated paths | `LanguageCommenters`, `ProjectFileIndex` |
| A5 | `PlanDetectionService.kt` | `:bamboo` | YAML regex for Bamboo specs | SnakeYAML parser |
| A6 | `SonarIssueAnnotator.kt` | `:sonar` | String prefix removal for relative paths | `VfsUtilCore` |
| A7 | `CoverageLineMarkerProvider.kt` | `:sonar` | String path + manual `BufferedImage` icons | `VfsUtilCore` + SVG icons |

### 2.2 Category B — Add Missing Intelligence (12 features with zero/minimal code context)

#### B1: Cody Intelligence Layer (highest user-facing impact)

| # | File | Module | Current State | Upgrade |
|---|------|--------|--------------|---------|
| B1 | `CodyIntentionAction.kt` | `:cody` | Sends hardcoded `issueType="CODE_SMELL"`, `issueMessage="Issue detected"`, `ruleKey="unknown"` + single file path | Pass real Sonar issue data + PSI context (method, class, annotations) + Spring context |
| B2 | `CodyCommitMessageHandlerFactory.kt` | `:cody` | **Empty skeleton** — no implementation | PSI-enriched diff: changed Spring beans, affected endpoints, Maven modules |
| B3 | `CodyChatService.kt` | `:cody` | Sends raw `git diff` text only for commit messages | Add `contextFiles` to ChatMessage, include PSI metadata in prompt |
| B4 | `CodyTestGenerator.kt` | `:cody` | **Empty skeleton** — no implementation | PSI: target method + Spring bean type → `@WebMvcTest`/`@DataJpaTest`/`@MockBean` selection |
| B5 | `CodyEditService.kt` | `:cody` | Accepts `contextFiles` parameter but **never passes it** — `EditCommandsCodeParams` DTO missing the field | Fix protocol DTO, pass context files to Cody Agent |
| B6 | `PreReviewService.kt` | `:handover` | Sends raw diff with generic prompt "analyze for anti-patterns" | PSI-annotate diff: `@Transactional` boundaries, `@Async` contexts, Spring bean modifications |

#### B2: Build & Navigation Intelligence (developer efficiency)

| # | File | Module | Current State | Upgrade |
|---|------|--------|--------------|---------|
| B7 | `HealthCheckService.kt` | `:core` | Runs ALL checks (compile+test+copyright+sonar) on every push | PSI + `ProjectFileIndex`: detect test-only changes → skip compile (60s→15s) |
| B8 | `MavenBuildService.kt` | `:core` | Shells out to `mvn` via `OSProcessHandler` subprocess | Use `MavenRunner.run()` for IDE-integrated builds |
| B9 | `SonarIssueAnnotator.kt` | `:sonar` | Maps issues to text ranges but doesn't know WHAT code element | PSI: resolve to `PsiMethod`/`PsiClass`, show method signature + Spring annotations in tooltip |
| B10 | `PrService.kt` | `:handover` | PR description is `"PROJ-123: Summary\nBranch: feature/PROJ-123"` | `MavenProjectsManager`: changed modules. PSI: affected `@RestController` endpoints |
| B11 | `CoverageLineMarkerProvider.kt` | `:sonar` | All uncovered lines look identical | Spring-aware: highlight uncovered `@RequestMapping` endpoints differently |
| B12 | `CoverageTreeDecorator.kt` | `:sonar` | `path.contains("/test/")` string matching | `ProjectFileIndex.isInTestSourceContent()` |

### 2.3 Out of Scope (Correct as-is)

| File | Module | Reason |
|------|--------|--------|
| `CveRemediationService.kt` | `:bamboo` | Parses external Bamboo build logs — regex correct for unstructured external data |
| `BuildLogParser.kt` | `:bamboo` | Same — external Bamboo log output, not IDE artifacts |
| `CveIntentionAction.kt` | `:bamboo` | Already uses correct PSI patterns — reference implementation |
| `CveAnnotator.kt` | `:bamboo` | Already uses correct ExternalAnnotator + PSI — reference implementation |
| `BranchingService.kt` | `:jira` | Already uses correct Git4Idea APIs — reference implementation |
| `JiraClosureService.kt` | `:handover` | Operates on computed data, not source code |
| `BuildMonitorService.kt` | `:bamboo` | Speaks only to Bamboo REST API, context-independent by design |

### 2.4 Intelligence Ceiling (~90% target, ~10% unreachable)

The remaining ~10% consists of information that is either impossible to obtain statically or not worth the complexity:

| Category | Examples | Why Unreachable |
|----------|---------|----------------|
| **Runtime-only** | Active Spring profile, `@ConditionalOnProperty` resolution, AOP proxy chains, database schema | Only exists when `java -jar` runs — IDE does static analysis |
| **Cross-system** | Production traffic patterns, APM metrics, deployment topology | Requires Grafana/Datadog integration — outside IDE scope |
| **Diminishing returns** | Scanning 500+ bean consumers, full transitive dependency graph | 2-3s delay per action; user-perceptible jank for marginal context gain |
| **Protocol limits** | Cody Agent may ignore extra context fields | Agent must be updated to consume enriched context |

Cody's AI compensates for this gap — with 90% context it can infer the rest. With 10% context (current state), it's guessing blind.

### 2.5 Priority Tiers

**Tier 1 — CRITICAL (Cody intelligence + Maven foundation):**
- A1: MavenModuleDetector → `MavenProjectsManager`
- A2: CodyContextService → PSI + Spring enrichment (new `PsiContextEnricher` + `SpringContextEnricher`)
- B1: CodyIntentionAction → pass real issue data + enriched context
- B5: CodyEditService + EditCommandsCodeParams → fix protocol DTO, pass context files

**Tier 2 — HIGH (Cody features + build intelligence):**
- B2: CodyCommitMessageHandler → implement with PSI-enriched diff
- B3: CodyChatService → add contextFiles + semantic metadata to prompt
- B4: CodyTestGenerator → implement with Spring-aware test patterns
- B6: PreReviewService → PSI-annotate diff before sending to Cody
- B7: HealthCheckService → smart check skipping
- B8: MavenBuildService → `MavenRunner` integration

**Tier 3 — MEDIUM (Sonar + Handover enrichment):**
- A3: CopyrightCheckService → `FileTypeRegistry` + document API
- A4: CopyrightFixService → `LanguageCommenters` + `ProjectFileIndex`
- A5: PlanDetectionService → SnakeYAML
- B9: SonarIssueAnnotator → PsiElement resolution for method-level context
- B10: PrService → module/endpoint-aware descriptions
- B11: CoverageLineMarkerProvider → Spring-aware endpoint highlighting

**Tier 4 — LOW (Polish):**
- A6: SonarIssueAnnotator → `VfsUtilCore` path fix
- A7: CoverageLineMarkerProvider → SVG icons
- B12: CoverageTreeDecorator → `ProjectFileIndex`

---

## 3. Build Target Change

### 3.1 Current Configuration

```properties
# gradle.properties
platformVersion = 2025.1
platformBundledPlugins = com.intellij.java
```

```kotlin
// build.gradle.kts
intellijIdea(providers.gradleProperty("platformVersion"))
```

### 3.2 New Configuration

```properties
# gradle.properties
platformVersion = 2025.1
platformBundledPlugins = com.intellij.java, org.jetbrains.idea.maven, com.intellij.spring, com.intellij.spring.boot
```

```kotlin
// build.gradle.kts
intellijIdeaUltimate(providers.gradleProperty("platformVersion"))
```

### 3.3 Plugin Descriptor Changes

**plugin.xml** — add optional dependencies (after existing `<depends>Git4Idea</depends>`):

```xml
<depends optional="true" config-file="plugin-withMaven.xml">org.jetbrains.idea.maven</depends>
<depends optional="true" config-file="plugin-withSpring.xml">com.intellij.spring</depends>
```

**plugin-withMaven.xml** — register Maven-aware service override:

```xml
<idea-plugin>
    <extensions defaultExtensionNs="com.intellij">
        <!-- No extensions needed — Maven API is used directly in :core services -->
        <!-- This file exists to declare the optional dependency relationship -->
    </extensions>
</idea-plugin>
```

**plugin-withSpring.xml** — register Spring-aware Cody context enricher:

```xml
<idea-plugin>
    <extensions defaultExtensionNs="com.intellij">
        <projectService
            serviceInterface="com.workflow.orchestrator.cody.service.SpringContextEnricher"
            serviceImplementation="com.workflow.orchestrator.cody.service.SpringContextEnricherImpl"/>
    </extensions>
</idea-plugin>
```

### 3.4 Runtime Behavior Matrix

| IDE Edition | Maven APIs | Spring APIs | Behavior |
|---|---|---|---|
| Ultimate (all plugins) | ✓ Direct | ✓ Direct | Full code intelligence |
| Ultimate (Spring disabled) | ✓ Direct | ✗ Fallback | PSI + Maven context only |
| Community | ✓ Direct | ✗ N/A | PSI + Maven context only |
| Community (Maven not imported) | ✗ Fallback | ✗ N/A | Regex fallback (current behavior) |

---

## 4. Tier 1: MavenModuleDetector Upgrade

### 4.1 Current Implementation (Problem)

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenModuleDetector.kt`

The current `extractArtifactId()` method (lines 40-58) implements a hand-rolled XML state machine:

```kotlin
// Current: 20 lines of fragile regex parsing
internal fun extractArtifactId(pomFile: VirtualFile): String? {
    val content = String(pomFile.contentsToByteArray())
    val lines = content.lines()
    var inParent = false
    var inDependencies = false
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("<parent>")) inParent = true
        if (trimmed.startsWith("</parent>")) inParent = false
        if (trimmed.startsWith("<dependencies>")) inDependencies = true
        if (trimmed.startsWith("</dependencies>")) inDependencies = false
        if (!inParent && !inDependencies) {
            val match = ARTIFACT_ID_PATTERN.find(trimmed)
            if (match != null) return match.groupValues[1]
        }
    }
    return null
}
```

**Problems:**
1. Breaks on multi-line `<artifactId>` tags (rare but valid XML)
2. Breaks on `<dependencyManagement>` blocks (not tracked)
3. Breaks on `<build><plugins><plugin><artifactId>` (false positive)
4. Ignores Maven's property interpolation (`<artifactId>${project.artifactId}</artifactId>`)
5. `findNearestPom()` walks directories manually — `MavenProjectsManager` already maps files to modules

### 4.2 New Implementation

```kotlin
class MavenModuleDetector(private val project: Project) {

    fun detectChangedModules(changedFiles: List<VirtualFile>): List<String> {
        val mavenManager = getMavenManager()
        if (mavenManager != null && mavenManager.projects.isNotEmpty()) {
            return detectViaMavenApi(changedFiles, mavenManager)
        }
        return detectViaFallback(changedFiles)
    }

    /**
     * Primary path: uses MavenProjectsManager which provides the full Maven model.
     * Maps each changed file to its owning Maven module via directory ancestry.
     */
    private fun detectViaMavenApi(
        changedFiles: List<VirtualFile>,
        mavenManager: MavenProjectsManager
    ): List<String> {
        val modules = mutableSetOf<String>()
        val mavenProjects = mavenManager.projects
        for (file in changedFiles) {
            val owningProject = mavenProjects.find { mavenProject ->
                val moduleDir = mavenProject.directoryFile
                VfsUtilCore.isAncestor(moduleDir, file, false)
            }
            if (owningProject != null) {
                modules.add(owningProject.mavenId.artifactId)
            }
        }
        return modules.toList()
    }

    /**
     * Fallback path: used when Maven plugin is not available or projects haven't imported yet.
     * Uses the existing regex-based approach.
     */
    private fun detectViaFallback(changedFiles: List<VirtualFile>): List<String> {
        val modules = mutableSetOf<String>()
        for (file in changedFiles) {
            val pomFile = findNearestPomFallback(file) ?: continue
            val artifactId = extractArtifactIdFallback(pomFile)
            if (artifactId != null) {
                modules.add(artifactId)
            }
        }
        return modules.toList()
    }

    fun buildMavenArgs(modules: List<String>, goals: String): List<String> {
        val goalList = goals.trim().split("\\s+".toRegex())
        if (modules.isEmpty()) return goalList
        return listOf("-pl", modules.joinToString(","), "-am") + goalList
    }

    private fun getMavenManager(): MavenProjectsManager? {
        return try {
            MavenProjectsManager.getInstance(project).takeIf { it.isMavenizedProject }
        } catch (_: Exception) {
            null // Maven plugin not available
        }
    }

    // --- Fallback methods (preserved from current implementation) ---

    internal fun findNearestPomFallback(file: VirtualFile): VirtualFile? {
        var dir = file.parent
        while (dir != null) {
            val pom = dir.findChild("pom.xml")
            if (pom != null) return pom
            dir = dir.parent
        }
        return null
    }

    internal fun extractArtifactIdFallback(pomFile: VirtualFile): String? {
        val content = String(pomFile.contentsToByteArray())
        val lines = content.lines()
        var inParent = false
        var inDependencies = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("<parent>") || trimmed.startsWith("<parent ")) inParent = true
            if (trimmed.startsWith("</parent>")) inParent = false
            if (trimmed.startsWith("<dependencies>") || trimmed.startsWith("<dependencies ")) inDependencies = true
            if (trimmed.startsWith("</dependencies>")) inDependencies = false
            if (!inParent && !inDependencies) {
                val match = ARTIFACT_ID_PATTERN.find(trimmed)
                if (match != null) return match.groupValues[1]
            }
        }
        return null
    }

    companion object {
        private val ARTIFACT_ID_PATTERN = Regex("<artifactId>([^<]+)</artifactId>")
    }
}
```

### 4.3 Key Imports

```kotlin
import org.jetbrains.idea.maven.project.MavenProjectsManager
import com.intellij.openapi.vfs.VfsUtilCore
```

### 4.4 Test Strategy

| Test | Approach |
|------|----------|
| `detectViaMavenApi` with mock MavenProjectsManager | Unit test with test fixtures |
| `detectViaFallback` (existing behavior) | Keep existing tests, rename to `*Fallback` |
| Fallback triggers when Maven not available | Unit test: null MavenManager → uses fallback |
| Maven projects not yet imported (empty list) | Unit test: empty projects → uses fallback |
| `buildMavenArgs` | Existing tests unchanged |

---

## 5. Tier 1: CodyContextService + Spring/PSI Enrichment

### 5.1 Current Implementation (Problem)

**File:** `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyContextServiceLogic.kt`

```kotlin
// Current: string replacement for test file discovery
fun resolveTestFile(sourceFilePath: String): String? {
    val normalized = sourceFilePath.replace('\\', '/')
    if (!normalized.contains("src/main/")) return null
    val testPath = normalized.replace("src/main/", "src/test/")
    val ext = testPath.substringAfterLast(".")
    val nameWithoutExt = testPath.substringBeforeLast(".")
    return "${nameWithoutExt}Test.$ext"
}
```

**Problems:**
1. Misses test files not named `*Test.kt` (e.g., `*Spec.kt`, `*Tests.kt`, `*IT.kt`)
2. Misses test files in non-standard locations (multi-module projects, Gradle conventions)
3. Returns paths that may not exist (no file existence check)
4. `gatherFixContext()` sends ONLY the issue file — Cody has zero awareness of:
   - Spring beans and injection graph
   - `@Transactional` boundaries
   - `@RestController` / `@RequestMapping` context
   - Related files (controller that calls this service, repository it depends on)
   - Existing test patterns in the project

### 5.2 Architecture

```
CodyContextService (orchestrator)
    ├── PsiContextEnricher (always available — uses bundled Java PSI)
    │   ├── findTestFile() — via TestFinderHelper
    │   ├── getClassAnnotations() — via AnnotationUtil
    │   ├── getMethodAnnotations() — via AnnotationUtil
    │   ├── getImports() — via PsiJavaFile
    │   ├── getRelatedFiles() — via ReferencesSearch
    │   └── getMavenModule() — via MavenProjectsManager
    │
    └── SpringContextEnricher (only when Spring plugin present)
        ├── isSpringBean() — via SpringManager
        ├── getBeanType() — @Service, @Controller, @Repository, @Component
        ├── getInjectedDependencies() — @Autowired fields/constructor params
        ├── getTransactionalMethods() — @Transactional methods
        ├── getRequestMappings() — @RequestMapping, @GetMapping, etc.
        └── getBeanConsumers() — who injects this bean
```

### 5.3 New File: PsiContextEnricher

**Path:** `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/PsiContextEnricher.kt`

This service uses Java/Kotlin PSI APIs (bundled in all IntelliJ editions) to extract code-level context.

```kotlin
package com.workflow.orchestrator.cody.service

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testIntegration.TestFinderHelper
import org.jetbrains.idea.maven.project.MavenProjectsManager

class PsiContextEnricher(private val project: Project) {

    data class PsiContext(
        val fileType: String,
        val packageName: String?,
        val className: String?,
        val classAnnotations: List<String>,
        val methodAnnotations: Map<String, List<String>>, // methodName → annotations
        val testFilePath: String?,
        val imports: List<String>,
        val mavenModule: String?,
        val relatedFiles: List<String>, // files that reference the target class
        val isTestFile: Boolean
    )

    suspend fun enrich(filePath: String): PsiContext {
        return readAction {
            val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: return@readAction emptyContext(filePath)
            val psiFile = PsiManager.getInstance(project).findFile(vFile)
                ?: return@readAction emptyContext(filePath)

            val fileIndex = ProjectFileIndex.getInstance(project)
            val isTest = fileIndex.isInTestSourceContent(vFile)

            val psiClass = PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)

            PsiContext(
                fileType = psiFile.fileType.name,
                packageName = (psiFile as? PsiJavaFile)?.packageName,
                className = psiClass?.qualifiedName,
                classAnnotations = psiClass?.let { extractAnnotations(it) } ?: emptyList(),
                methodAnnotations = psiClass?.let { extractMethodAnnotations(it) } ?: emptyMap(),
                testFilePath = if (!isTest) psiClass?.let { findTestFile(it) } else null,
                imports = extractImports(psiFile),
                mavenModule = detectMavenModule(vFile),
                relatedFiles = psiClass?.let { findRelatedFiles(it) } ?: emptyList(),
                isTestFile = isTest
            )
        }
    }

    private fun extractAnnotations(psiClass: PsiClass): List<String> {
        return psiClass.annotations.mapNotNull { it.qualifiedName?.substringAfterLast('.') }
    }

    private fun extractMethodAnnotations(psiClass: PsiClass): Map<String, List<String>> {
        return psiClass.methods.associate { method ->
            method.name to method.annotations.mapNotNull {
                it.qualifiedName?.substringAfterLast('.')
            }
        }.filterValues { it.isNotEmpty() }
    }

    private fun findTestFile(psiClass: PsiClass): String? {
        val tests = TestFinderHelper.findTestsForClass(psiClass)
        return tests.firstOrNull()?.containingFile?.virtualFile?.path
    }

    private fun extractImports(psiFile: PsiFile): List<String> {
        return (psiFile as? PsiJavaFile)?.importList?.allImportStatements
            ?.mapNotNull { it.text?.removePrefix("import ")?.removeSuffix(";")?.trim() }
            ?: emptyList()
    }

    private fun detectMavenModule(vFile: com.intellij.openapi.vfs.VirtualFile): String? {
        return try {
            val mavenManager = MavenProjectsManager.getInstance(project)
            if (!mavenManager.isMavenizedProject) return null
            mavenManager.projects.find { mavenProject ->
                VfsUtilCore.isAncestor(mavenProject.directoryFile, vFile, false)
            }?.mavenId?.artifactId
        } catch (_: Exception) {
            null
        }
    }

    private fun findRelatedFiles(psiClass: PsiClass): List<String> {
        // Limit to 10 references to avoid performance issues on large projects
        val refs = ReferencesSearch.search(psiClass).findAll().take(10)
        return refs.mapNotNull { ref ->
            ref.element.containingFile?.virtualFile?.path
        }.distinct()
    }

    private fun emptyContext(filePath: String) = PsiContext(
        fileType = filePath.substringAfterLast('.', "unknown"),
        packageName = null,
        className = null,
        classAnnotations = emptyList(),
        methodAnnotations = emptyMap(),
        testFilePath = null,
        imports = emptyList(),
        mavenModule = null,
        relatedFiles = emptyList(),
        isTestFile = false
    )
}
```

### 5.4 New File: SpringContextEnricher

**Path:** `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/SpringContextEnricher.kt`

This service uses Spring Framework plugin APIs (available in Ultimate edition) to extract Spring-specific context.

**Interface** (loaded always — in `:cody` module):

```kotlin
package com.workflow.orchestrator.cody.service

interface SpringContextEnricher {

    data class SpringContext(
        val isBean: Boolean,
        val beanType: String?,            // "Service", "Controller", "Repository", "Component"
        val injectedDependencies: List<BeanDependency>,
        val transactionalMethods: List<String>,
        val requestMappings: List<RequestMappingInfo>,
        val beanConsumers: List<String>   // classes that inject this bean
    )

    data class BeanDependency(
        val beanName: String,
        val beanType: String,  // fully-qualified class name
        val qualifier: String? // @Qualifier value, if any
    )

    data class RequestMappingInfo(
        val method: String,      // GET, POST, PUT, DELETE
        val path: String,        // "/api/orders/{id}"
        val handlerMethod: String // "getOrderById"
    )

    suspend fun enrich(filePath: String): SpringContext?

    companion object {
        val EMPTY = object : SpringContextEnricher {
            override suspend fun enrich(filePath: String): SpringContext? = null
        }
    }
}
```

**Implementation** (loaded only when Spring plugin is present — registered in `plugin-withSpring.xml`):

```kotlin
package com.workflow.orchestrator.cody.service

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.spring.model.SpringBeanPointer
import com.intellij.spring.model.SpringManager
import com.intellij.spring.model.SpringModel
import com.intellij.spring.model.utils.SpringModelSearchers

class SpringContextEnricherImpl(private val project: Project) : SpringContextEnricher {

    private val springBeanAnnotations = setOf(
        "org.springframework.stereotype.Service",
        "org.springframework.stereotype.Controller",
        "org.springframework.stereotype.RestController",
        "org.springframework.stereotype.Repository",
        "org.springframework.stereotype.Component",
        "org.springframework.context.annotation.Configuration",
        "org.springframework.context.annotation.Bean"
    )

    private val transactionalFqn = "org.springframework.transaction.annotation.Transactional"

    private val requestMappingAnnotations = mapOf(
        "org.springframework.web.bind.annotation.RequestMapping" to null,
        "org.springframework.web.bind.annotation.GetMapping" to "GET",
        "org.springframework.web.bind.annotation.PostMapping" to "POST",
        "org.springframework.web.bind.annotation.PutMapping" to "PUT",
        "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
        "org.springframework.web.bind.annotation.PatchMapping" to "PATCH"
    )

    override suspend fun enrich(filePath: String): SpringContextEnricher.SpringContext? {
        return readAction {
            val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@readAction null
            val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return@readAction null
            val psiClass = PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)
                ?: return@readAction null

            val springModel = SpringManager.getInstance(project).getCombinedModel(psiFile)
                ?: return@readAction null

            val isBean = isSpringBean(psiClass, springModel)
            if (!isBean) return@readAction SpringContextEnricher.SpringContext(
                isBean = false,
                beanType = null,
                injectedDependencies = emptyList(),
                transactionalMethods = emptyList(),
                requestMappings = emptyList(),
                beanConsumers = emptyList()
            )

            SpringContextEnricher.SpringContext(
                isBean = true,
                beanType = detectBeanType(psiClass),
                injectedDependencies = findInjectedDependencies(psiClass, springModel),
                transactionalMethods = findTransactionalMethods(psiClass),
                requestMappings = findRequestMappings(psiClass),
                beanConsumers = findBeanConsumers(psiClass, springModel)
            )
        }
    }

    private fun isSpringBean(psiClass: PsiClass, model: SpringModel): Boolean {
        // Check if any SpringBeanPointer references this class
        val beans = SpringModelSearchers.findBeans(model, psiClass)
        return beans.isNotEmpty()
    }

    private fun detectBeanType(psiClass: PsiClass): String? {
        for ((fqn, _) in springBeanAnnotations.associateWith { it }) {
            val annotation = psiClass.getAnnotation(fqn)
            if (annotation != null) {
                return fqn.substringAfterLast('.')
            }
        }
        return null
    }

    private fun findInjectedDependencies(
        psiClass: PsiClass,
        model: SpringModel
    ): List<SpringContextEnricher.BeanDependency> {
        val deps = mutableListOf<SpringContextEnricher.BeanDependency>()

        // Constructor injection (preferred Spring pattern)
        val constructors = psiClass.constructors
        val primaryConstructor = constructors.firstOrNull() // In Spring, single constructor = auto-inject
        primaryConstructor?.parameterList?.parameters?.forEach { param ->
            deps.add(paramToBeanDependency(param))
        }

        // Field injection (@Autowired fields)
        psiClass.fields.filter { field ->
            field.getAnnotation("org.springframework.beans.factory.annotation.Autowired") != null
        }.forEach { field ->
            deps.add(fieldToBeanDependency(field))
        }

        return deps
    }

    private fun paramToBeanDependency(param: PsiParameter): SpringContextEnricher.BeanDependency {
        val qualifier = param.getAnnotation("org.springframework.beans.factory.annotation.Qualifier")
            ?.findAttributeValue("value")?.text?.removeSurrounding("\"")
        return SpringContextEnricher.BeanDependency(
            beanName = param.name,
            beanType = param.type.canonicalText,
            qualifier = qualifier
        )
    }

    private fun fieldToBeanDependency(field: PsiField): SpringContextEnricher.BeanDependency {
        val qualifier = field.getAnnotation("org.springframework.beans.factory.annotation.Qualifier")
            ?.findAttributeValue("value")?.text?.removeSurrounding("\"")
        return SpringContextEnricher.BeanDependency(
            beanName = field.name,
            beanType = field.type.canonicalText,
            qualifier = qualifier
        )
    }

    private fun findTransactionalMethods(psiClass: PsiClass): List<String> {
        val methods = mutableListOf<String>()
        // Class-level @Transactional → all public methods are transactional
        if (psiClass.getAnnotation(transactionalFqn) != null) {
            methods.addAll(psiClass.methods.filter { it.hasModifierProperty("public") }.map { it.name })
        } else {
            // Method-level @Transactional
            psiClass.methods.filter { it.getAnnotation(transactionalFqn) != null }
                .forEach { methods.add(it.name) }
        }
        return methods
    }

    private fun findRequestMappings(psiClass: PsiClass): List<SpringContextEnricher.RequestMappingInfo> {
        val mappings = mutableListOf<SpringContextEnricher.RequestMappingInfo>()

        // Class-level @RequestMapping prefix
        val classPrefix = psiClass.getAnnotation(
            "org.springframework.web.bind.annotation.RequestMapping"
        )?.let { extractMappingPath(it) } ?: ""

        for (method in psiClass.methods) {
            for ((annotationFqn, httpMethod) in requestMappingAnnotations) {
                val annotation = method.getAnnotation(annotationFqn) ?: continue
                val path = classPrefix + extractMappingPath(annotation)
                val resolvedMethod = httpMethod
                    ?: extractRequestMethod(annotation)
                    ?: "GET"
                mappings.add(
                    SpringContextEnricher.RequestMappingInfo(
                        method = resolvedMethod,
                        path = path,
                        handlerMethod = method.name
                    )
                )
            }
        }
        return mappings
    }

    private fun extractMappingPath(annotation: PsiAnnotation): String {
        val value = annotation.findAttributeValue("value")
            ?: annotation.findAttributeValue("path")
        return value?.text?.removeSurrounding("\"")?.removeSurrounding("{", "}") ?: ""
    }

    private fun extractRequestMethod(annotation: PsiAnnotation): String? {
        val method = annotation.findAttributeValue("method") ?: return null
        return method.text?.removeSurrounding("RequestMethod.", "")
    }

    private fun findBeanConsumers(psiClass: PsiClass, model: SpringModel): List<String> {
        // Find beans that inject this class (limited to 10 for performance)
        val consumers = mutableListOf<String>()
        val allBeans = SpringModelSearchers.findBeans(model, psiClass)
        for (bean in allBeans.take(10)) {
            val beanClass = bean.springBean?.beanClass
            if (beanClass != null && beanClass != psiClass) {
                consumers.add(beanClass.qualifiedName ?: beanClass.name ?: "unknown")
            }
        }
        return consumers.distinct()
    }
}
```

### 5.5 Updated CodyContextService

The main orchestrator now combines PSI + Spring context:

```kotlin
package com.workflow.orchestrator.cody.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.protocol.ContextFile
import com.workflow.orchestrator.cody.protocol.Range

@Service(Service.Level.PROJECT)
class CodyContextService(private val project: Project) {

    private val psiEnricher = PsiContextEnricher(project)
    private val springEnricher: SpringContextEnricher = try {
        project.getService(SpringContextEnricher::class.java) ?: SpringContextEnricher.EMPTY
    } catch (_: Exception) {
        SpringContextEnricher.EMPTY
    }

    data class FixContext(
        val instruction: String,
        val contextFiles: List<ContextFile>
    )

    data class TestContext(
        val instruction: String,
        val contextFiles: List<ContextFile>,
        val existingTestFile: String?
    )

    suspend fun gatherFixContext(
        filePath: String,
        issueRange: Range,
        issueType: String,
        issueMessage: String,
        ruleKey: String
    ): FixContext {
        val psiContext = psiEnricher.enrich(filePath)
        val springContext = springEnricher.enrich(filePath)

        val instruction = buildEnrichedFixInstruction(
            issueType, issueMessage, ruleKey, psiContext, springContext
        )

        val contextFiles = buildContextFileList(filePath, issueRange, psiContext)
        return FixContext(instruction, contextFiles)
    }

    suspend fun gatherTestContext(
        filePath: String,
        targetRange: Range
    ): TestContext {
        val psiContext = psiEnricher.enrich(filePath)
        val springContext = springEnricher.enrich(filePath)

        val existingTestFile = psiContext.testFilePath
        val instruction = buildEnrichedTestInstruction(
            targetRange, existingTestFile, psiContext, springContext
        )

        val contextFiles = mutableListOf(
            ContextFile(uri = filePath, range = targetRange)
        )
        if (existingTestFile != null) {
            contextFiles.add(ContextFile(uri = existingTestFile))
        }

        return TestContext(instruction, contextFiles, existingTestFile)
    }

    private fun buildEnrichedFixInstruction(
        issueType: String,
        issueMessage: String,
        ruleKey: String,
        psi: PsiContextEnricher.PsiContext,
        spring: SpringContextEnricher.SpringContext?
    ): String = buildString {
        append("Fix the following SonarQube $issueType issue (rule: $ruleKey):\n")
        append("$issueMessage\n\n")

        // PSI context
        if (psi.packageName != null) append("Package: ${psi.packageName}\n")
        if (psi.mavenModule != null) append("Maven module: ${psi.mavenModule}\n")
        if (psi.classAnnotations.isNotEmpty()) {
            append("Class annotations: ${psi.classAnnotations.joinToString(", ") { "@$it" }}\n")
        }

        // Spring context
        if (spring != null && spring.isBean) {
            append("\nSpring Context:\n")
            if (spring.beanType != null) append("- This class is a @${spring.beanType} bean\n")
            if (spring.injectedDependencies.isNotEmpty()) {
                append("- Dependencies: ${spring.injectedDependencies.joinToString(", ") { "${it.beanName}: ${it.beanType.substringAfterLast('.')}" }}\n")
            }
            if (spring.transactionalMethods.isNotEmpty()) {
                append("- @Transactional methods: ${spring.transactionalMethods.joinToString(", ")}\n")
            }
            if (spring.requestMappings.isNotEmpty()) {
                append("- REST endpoints:\n")
                spring.requestMappings.forEach {
                    append("  - ${it.method} ${it.path} → ${it.handlerMethod}()\n")
                }
            }
            if (spring.beanConsumers.isNotEmpty()) {
                append("- Injected by: ${spring.beanConsumers.joinToString(", ") { it.substringAfterLast('.') }}\n")
            }
        }

        append("\nProvide a minimal fix that addresses the issue without changing behavior.")
    }

    private fun buildEnrichedTestInstruction(
        range: Range,
        existingTestFile: String?,
        psi: PsiContextEnricher.PsiContext,
        spring: SpringContextEnricher.SpringContext?
    ): String = buildString {
        append("Generate a unit test covering the code at lines ${range.start.line}-${range.end.line}. ")
        append("Use JUnit 5 with standard assertions. ")

        if (existingTestFile != null) {
            append("Add to the existing test file: $existingTestFile. ")
            append("Match the existing test style and imports. ")
        } else {
            append("Create a new test class with proper package and imports. ")
        }

        // Spring-aware test guidance
        if (spring != null && spring.isBean) {
            when (spring.beanType) {
                "Service" -> append("Mock injected dependencies with @MockBean or Mockito. ")
                "Controller", "RestController" -> append("Use MockMvc for controller testing. ")
                "Repository" -> append("Use @DataJpaTest for repository testing. ")
            }
            if (spring.transactionalMethods.isNotEmpty()) {
                append("Test transactional behavior — verify rollback on exception. ")
            }
        }

        if (psi.mavenModule != null) {
            append("Module: ${psi.mavenModule}. ")
        }
    }

    private fun buildContextFileList(
        filePath: String,
        issueRange: Range,
        psi: PsiContextEnricher.PsiContext
    ): List<ContextFile> {
        val files = mutableListOf(
            ContextFile(uri = filePath, range = issueRange)
        )
        // Add test file for reference
        psi.testFilePath?.let { files.add(ContextFile(uri = it)) }
        // Add top related files (up to 3)
        psi.relatedFiles.take(3).forEach { files.add(ContextFile(uri = it)) }
        return files
    }
}
```

### 5.6 Updated CodyContextServiceLogic

The logic class retains the fallback `resolveTestFile()` for use in tests and when PSI is unavailable:

```kotlin
package com.workflow.orchestrator.cody.service

import com.workflow.orchestrator.cody.protocol.Range

class CodyContextServiceLogic {

    fun buildFixInstruction(issueType: String, issueMessage: String, ruleKey: String): String =
        """Fix the following SonarQube $issueType issue (rule: $ruleKey):
           |$issueMessage
           |
           |Provide a minimal fix that addresses the issue without changing behavior.""".trimMargin()

    fun buildTestInstruction(range: Range, existingTestFile: String?): String = buildString {
        append("Generate a unit test covering the code at lines ")
        append("${range.start.line}-${range.end.line}. ")
        append("Use JUnit 5 with standard assertions. ")
        if (existingTestFile != null) {
            append("Add to the existing test file: $existingTestFile. ")
            append("Match the existing test style and imports.")
        } else {
            append("Create a new test class with proper package and imports.")
        }
    }

    /**
     * Fallback test file resolution using path convention.
     * Used when PSI/TestFinderHelper is not available (e.g., unit tests without IDE context).
     */
    fun resolveTestFileFallback(sourceFilePath: String): String? {
        val normalized = sourceFilePath.replace('\\', '/')
        if (!normalized.contains("src/main/")) return null
        val testPath = normalized.replace("src/main/", "src/test/")
        val ext = testPath.substringAfterLast(".")
        val nameWithoutExt = testPath.substringBeforeLast(".")
        return "${nameWithoutExt}Test.$ext"
    }
}
```

### 5.7 Test Strategy

| Test | Approach |
|------|----------|
| `PsiContextEnricher` | `BasePlatformTestCase` with fixture Java files containing annotations |
| `SpringContextEnricherImpl` | `BasePlatformTestCase` with Spring-annotated fixture classes + Spring test framework |
| `CodyContextService.gatherFixContext` — with Spring | Integration test: mock PSI + Spring enrichers, verify instruction text contains Spring context |
| `CodyContextService.gatherFixContext` — without Spring | Integration test: mock PSI enricher, null Spring enricher, verify basic instruction |
| `CodyContextServiceLogic.resolveTestFileFallback` | Pure unit test (existing tests renamed) |
| Instruction text quality | Snapshot tests: verify instruction text for @Service, @Controller, @Repository cases |

---

## 6. Tier 2: CopyrightCheckService Upgrade

### 6.1 Current Problem

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/copyright/CopyrightCheckService.kt`

- Line 34-35: Hardcoded extension set `setOf("java", "kt", "kts", "xml", "yaml", "yml", "properties")`
- Line 23: Raw byte reading `String(file.contentsToByteArray())`

### 6.2 Changes

```kotlin
// Before
private fun isSourceFile(file: VirtualFile): Boolean {
    val ext = file.extension ?: return false
    return ext in setOf("java", "kt", "kts", "xml", "yaml", "yml", "properties")
}

// After — uses IntelliJ's file type registry
private fun isSourceFile(file: VirtualFile): Boolean {
    val fileType = FileTypeRegistry.getInstance().getFileTypeByFile(file)
    if (fileType.isBinary) return false
    val fileIndex = ProjectFileIndex.getInstance(project)
    return fileIndex.isInSourceContent(file) && !fileIndex.isInGeneratedSources(file)
}
```

```kotlin
// Before — raw byte reading
val content = String(file.contentsToByteArray())

// After — use document API for proper encoding handling
val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
val content = document.text
```

### 6.3 Key Imports

```kotlin
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.fileEditor.FileDocumentManager
```

---

## 7. Tier 2: CopyrightFixService Upgrade

### 7.1 Current Problem

**File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/CopyrightFixService.kt`

- Lines 56-63: Hardcoded comment syntax (`when (fileExtension)`) — misses Groovy, Scala, SQL, etc.
- Line 26: Hardcoded `SOURCE_EXTENSIONS` set
- Line 27: Hardcoded `GENERATED_PATH_PREFIXES` list
- Lines 79-81: `isGeneratedPath()` uses string prefix matching

### 7.2 Changes

**Replace `wrapForLanguage()`:**

```kotlin
// Before
fun wrapForLanguage(template: String, fileExtension: String): String {
    return when (fileExtension) {
        "java", "kt", "kts" -> "/*\n${...}\n */"
        "xml" -> "<!--\n$template\n-->"
        "properties", "yaml", "yml" -> template.lines().joinToString("\n") { "# $it" }
        else -> template
    }
}

// After — uses IntelliJ's language-aware commenter
fun wrapForLanguage(template: String, file: VirtualFile): String {
    val fileType = FileTypeRegistry.getInstance().getFileTypeByFile(file)
    val language = (fileType as? LanguageFileType)?.language ?: return template
    val commenter = LanguageCommenters.INSTANCE.forLanguage(language) ?: return template

    val blockStart = commenter.blockCommentPrefix
    val blockEnd = commenter.blockCommentSuffix
    val linePrefix = commenter.lineCommentPrefix

    return if (blockStart != null && blockEnd != null) {
        "$blockStart\n${template.lines().joinToString("\n") { " * $it" }}\n $blockEnd"
    } else if (linePrefix != null) {
        template.lines().joinToString("\n") { "$linePrefix $it" }
    } else {
        template
    }
}
```

**Replace `isGeneratedPath()`:**

```kotlin
// Before
fun isGeneratedPath(filePath: String): Boolean {
    val normalized = filePath.replace('\\', '/')
    return GENERATED_PATH_PREFIXES.any { normalized.startsWith(it) }
}

// After — uses IntelliJ's project file index
fun isGeneratedFile(file: VirtualFile): Boolean {
    val fileIndex = ProjectFileIndex.getInstance(project!!)
    return fileIndex.isInGeneratedSources(file) ||
           fileIndex.isExcluded(file)
}
```

**Replace `isSourceFile()`:**

```kotlin
// Before
fun isSourceFile(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "")
    return extension in SOURCE_EXTENSIONS
}

// After — uses IntelliJ's file type registry
fun isSourceFile(file: VirtualFile): Boolean {
    val fileType = FileTypeRegistry.getInstance().getFileTypeByFile(file)
    return !fileType.isBinary
}
```

### 7.3 Backward Compatibility

The `wrapForLanguage()` signature changes from `(String, String)` to `(String, VirtualFile)`. Callers must be updated. Keep the old string-based overload as `@Deprecated` for one release cycle if needed, or update all callers in the same commit (there's only one caller in HandoverPanel).

### 7.4 Key Imports

```kotlin
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.roots.ProjectFileIndex
```

---

## 8. Tier 2: PlanDetectionService Upgrade

### 8.1 Current Problem

**File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt`

- Line 49: `URL_REGEX = Regex("""url:\s+(.+)""")` — fragile YAML parsing
- Lines 64-66: `URL_REGEX.findAll(specsYaml)` — misses quoted values, multi-line values, YAML anchors

### 8.2 Changes

Replace `extractRepoUrls()` with proper YAML parsing. Since IntelliJ bundles a YAML plugin with PSI support, use it:

```kotlin
// Before
internal fun extractRepoUrls(specsYaml: String): List<String> {
    return URL_REGEX.findAll(specsYaml).map { it.groupValues[1].trim() }.toList()
}

// After — uses SnakeYAML (already on classpath via IntelliJ) for proper parsing
internal fun extractRepoUrls(specsYaml: String): List<String> {
    return try {
        val yaml = org.yaml.snakeyaml.Yaml()
        val data = yaml.load<Any>(specsYaml)
        extractUrlsFromYamlTree(data)
    } catch (_: Exception) {
        // Fallback to regex if YAML parsing fails
        URL_REGEX.findAll(specsYaml).map { it.groupValues[1].trim() }.toList()
    }
}

private fun extractUrlsFromYamlTree(node: Any?): List<String> {
    val urls = mutableListOf<String>()
    when (node) {
        is Map<*, *> -> {
            for ((key, value) in node) {
                if (key == "url" && value is String) {
                    urls.add(value)
                } else {
                    urls.addAll(extractUrlsFromYamlTree(value))
                }
            }
        }
        is List<*> -> node.forEach { urls.addAll(extractUrlsFromYamlTree(it)) }
    }
    return urls
}
```

**Note:** SnakeYAML is used here rather than IntelliJ's YAML PSI because this method receives YAML as a `String` from the Bamboo API, not as a file in the project. PSI parsing would require creating a temporary PsiFile, which is overkill for this use case. SnakeYAML is already available on the classpath (transitive dependency of the IntelliJ Platform).

### 8.3 Test Strategy

| Test | Approach |
|------|----------|
| Simple YAML with url field | Unit test with YAML string |
| Quoted URL values | Unit test: `url: "https://..."` |
| Nested YAML structure | Unit test: repositories within plans |
| Malformed YAML (fallback to regex) | Unit test: verify fallback works |
| `normalizeRepoUrl` | Existing tests unchanged |

---

## 9. Tier 3: SonarIssueAnnotator Path Fix

### 9.1 Current Problem

**File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/SonarIssueAnnotator.kt`

String prefix removal for computing relative paths:
```kotlin
val relativePath = file.path.removePrefix("$basePath/")
```

### 9.2 Change

```kotlin
// Before
val relativePath = file.path.removePrefix("$basePath/")

// After
val baseDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
val relativePath = if (baseDir != null) {
    VfsUtilCore.getRelativePath(file, baseDir) ?: file.path
} else {
    file.path
}
```

### 9.3 Key Import

```kotlin
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.LocalFileSystem
```

---

## 10. Tier 3: CoverageLineMarkerProvider Path + Icon Fix

### 10.1 Current Problem

**File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageLineMarkerProvider.kt`

1. Same `removePrefix` path issue as SonarIssueAnnotator
2. Manual `BufferedImage` creation for gutter icons instead of SVG

### 10.2 Changes

**Path fix:** Same as §9.2 — use `VfsUtilCore.getRelativePath()`.

**Icon fix:**

```kotlin
// Before — manual BufferedImage rendering
private fun createCoverageIcon(covered: Boolean): Icon {
    val image = BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.color = if (covered) JBColor.GREEN else JBColor.RED
    g.fillRect(0, 0, 12, 12)
    g.dispose()
    return ImageIcon(image)
}

// After — use SVG icons with light/dark variants
private fun createCoverageIcon(covered: Boolean): Icon {
    return if (covered) {
        IconLoader.getIcon("/icons/coverage-covered.svg", javaClass)
    } else {
        IconLoader.getIcon("/icons/coverage-uncovered.svg", javaClass)
    }
}
```

**Required SVG files to create:**
- `src/main/resources/icons/coverage-covered.svg` (green circle, 12x12)
- `src/main/resources/icons/coverage-covered_dark.svg` (green circle for dark theme)
- `src/main/resources/icons/coverage-uncovered.svg` (red circle, 12x12)
- `src/main/resources/icons/coverage-uncovered_dark.svg` (red circle for dark theme)

### 10.3 Key Import

```kotlin
import com.intellij.openapi.util.IconLoader
```

---

## 11. Category B: Cody Intelligence Layer

### 11.1 B1: CodyIntentionAction — Pass Real Issue Data + PSI Context

**File:** `cody/src/main/kotlin/.../editor/CodyIntentionAction.kt`

**Current problem:** Lines 37-65 send hardcoded values:
```kotlin
issueType = "CODE_SMELL",        // HARDCODED
issueMessage = "Issue detected", // HARDCODED
ruleKey = "unknown"              // HARDCODED
```

**Fix:** The intention action is triggered from Sonar gutter markers. It must receive the actual Sonar issue from the `SonarIssueAnnotator` annotation data, then pass it through `CodyContextService` which now enriches with PSI + Spring context.

```kotlin
override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
    if (editor == null || file == null) return

    // Extract real Sonar issue from annotation at caret position
    val caretOffset = editor.caretModel.offset
    val sonarIssue = findSonarIssueAtCaret(editor, caretOffset)

    val range = if (sonarIssue != null) {
        Range(
            start = Position(line = sonarIssue.startLine - 1, character = 0),
            end = Position(line = sonarIssue.endLine, character = 0)
        )
    } else {
        val caretLine = editor.caretModel.logicalPosition.line
        Range(start = Position(line = caretLine, character = 0),
              end = Position(line = caretLine + 1, character = 0))
    }

    val filePath = file.virtualFile.path
    val contextService = project.service<CodyContextService>()

    // Now uses PsiContextEnricher + SpringContextEnricher internally
    project.coroutineScope.launch(Dispatchers.IO) {
        val fixContext = contextService.gatherFixContext(
            filePath = filePath,
            issueRange = range,
            issueType = sonarIssue?.type?.name ?: "CODE_SMELL",
            issueMessage = sonarIssue?.message ?: "Fix issue at cursor",
            ruleKey = sonarIssue?.rule ?: "manual"
        )
        CodyEditService(project).requestFix(
            filePath = filePath,
            range = range,
            instruction = fixContext.instruction,
            contextFiles = fixContext.contextFiles
        )
    }
}

private fun findSonarIssueAtCaret(editor: Editor, offset: Int): MappedIssue? {
    // Read Sonar issue data from ExternalAnnotator highlights at this offset
    val highlights = editor.markupModel.allHighlighters
    return highlights
        .filter { it.startOffset <= offset && offset <= it.endOffset }
        .mapNotNull { it.getUserData(SONAR_ISSUE_KEY) }
        .firstOrNull()
}
```

**Dependencies:** `SonarIssueAnnotator` must store `MappedIssue` in the highlighter's user data via `RangeHighlighter.putUserData(SONAR_ISSUE_KEY, issue)`. Add a `Key<MappedIssue>` companion object.

### 11.2 B2: CodyCommitMessageHandler — Implement with PSI-Enriched Diff

**File:** `cody/src/main/kotlin/.../vcs/CodyCommitMessageHandlerFactory.kt`

**Current problem:** Empty skeleton handler with no implementation.

**Implementation:** The commit message handler receives the staged changes from IntelliJ's VCS framework. It should:

1. Get the list of changed files from the `CommitContext`
2. For each changed file, use `PsiContextEnricher` to extract class/method/annotation metadata
3. Use `MavenProjectsManager` to identify affected modules
4. Build a semantic summary and pass to `CodyChatService`

```kotlin
class CodyCommitMessageHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, context: CommitContext): CheckinHandler {
        return CodyCommitMessageHandler(panel, context)
    }
}

class CodyCommitMessageHandler(
    private val panel: CheckinProjectPanel,
    private val context: CommitContext
) : CheckinHandler() {

    override fun getAfterCheckinConfigurationPanel(disposable: Disposable): RefreshableOnComponent? {
        // "Generate commit message with Cody" checkbox — already wired in plugin.xml
        return null
    }

    suspend fun generateMessage(): String? {
        val project = panel.project
        val diff = getDiff(project)
        val changedFiles = panel.virtualFiles.toList()

        // Gather PSI context for changed files
        val psiEnricher = PsiContextEnricher(project)
        val fileContexts = changedFiles.mapNotNull { file ->
            val ctx = psiEnricher.enrich(file.path)
            if (ctx.className != null) {
                "${ctx.className} (${ctx.classAnnotations.joinToString(", ") { "@$it" }})"
            } else null
        }

        // Gather Maven module context
        val modules = MavenProjectsManager.getInstance(project)
            .takeIf { it.isMavenizedProject }
            ?.projects
            ?.filter { mp -> changedFiles.any { VfsUtilCore.isAncestor(mp.directoryFile, it, false) } }
            ?.map { it.mavenId.artifactId }
            ?: emptyList()

        val enrichedPrompt = buildString {
            append("Generate a concise git commit message for this diff.\n")
            append("Use conventional commits format (feat/fix/refactor/etc).\n")
            append("One line summary, optional body.\n\n")
            if (modules.isNotEmpty()) {
                append("Affected Maven modules: ${modules.joinToString(", ")}\n")
            }
            if (fileContexts.isNotEmpty()) {
                append("Changed classes:\n")
                fileContexts.forEach { append("- $it\n") }
            }
            append("\n```diff\n$diff\n```")
        }

        return CodyChatService(project).generateCommitMessage(enrichedPrompt)
    }
}
```

### 11.3 B3: CodyChatService — Add Context Files to Chat

**File:** `cody/src/main/kotlin/.../agent/CodyChatService.kt`

**Change:** When sending chat messages (e.g., commit message generation), include the changed files as `contextFiles` in the `ChatMessage`. The Cody Agent protocol supports this field — it's just not being used.

```kotlin
// Before
val response = server.chatSubmitMessage(
    ChatSubmitParams(id = chatId, message = ChatMessage(text = prompt))
)

// After
val response = server.chatSubmitMessage(
    ChatSubmitParams(
        id = chatId,
        message = ChatMessage(
            text = prompt,
            contextFiles = changedFiles.map { ContextFile(uri = it.path) }
        )
    )
)
```

**Prerequisite:** Verify `ChatMessage` DTO has a `contextFiles` field. If not, add it to `ChatModels.kt`.

### 11.4 B4: CodyTestGenerator — Spring-Aware Test Patterns

**File:** `cody/src/main/kotlin/.../editor/CodyTestGenerator.kt`

**Current problem:** Empty skeleton — no implementation.

**Implementation:** When the user clicks the "Generate Test" gutter marker on an uncovered method, gather Spring context to select the right test pattern:

```kotlin
private fun buildTestInstruction(
    psiContext: PsiContextEnricher.PsiContext,
    springContext: SpringContextEnricher.SpringContext?,
    range: Range
): String = buildString {
    append("Generate a unit test covering lines ${range.start.line}-${range.end.line}. ")
    append("Use JUnit 5 with standard assertions. ")

    // Spring-aware test pattern selection
    if (springContext != null && springContext.isBean) {
        when (springContext.beanType) {
            "Controller", "RestController" -> {
                append("Use @WebMvcTest with MockMvc. ")
                append("Mock dependencies with @MockBean. ")
                if (springContext.requestMappings.isNotEmpty()) {
                    val endpoint = springContext.requestMappings.first()
                    append("Test endpoint: ${endpoint.method} ${endpoint.path}. ")
                }
            }
            "Service" -> {
                append("Use @ExtendWith(MockitoExtension.class). ")
                append("Mock injected dependencies: ")
                append(springContext.injectedDependencies.joinToString(", ") {
                    "${it.beanType.substringAfterLast('.')} (${it.beanName})"
                })
                append(". ")
                if (springContext.transactionalMethods.isNotEmpty()) {
                    append("Verify @Transactional rollback on exception. ")
                }
            }
            "Repository" -> {
                append("Use @DataJpaTest with @AutoConfigureTestDatabase. ")
                append("Test with real embedded DB, not mocks. ")
            }
        }
    }

    if (psiContext.testFilePath != null) {
        append("Add to existing test file: ${psiContext.testFilePath}. ")
        append("Match the existing test style and imports. ")
    } else {
        append("Create a new test class. ")
    }
}
```

### 11.5 B5: CodyEditService + EditCommandsCodeParams — Fix Protocol DTO

**File:** `cody/src/main/kotlin/.../protocol/EditModels.kt`

**Current problem:** `EditCommandsCodeParams` has no `contextFiles` field. The service accepts context files but never passes them.

**Fix:**

```kotlin
// Before
data class EditCommandsCodeParams(
    val instruction: String,
    val model: String? = null,
    val mode: String = "edit",
    val range: Range? = null
)

// After
data class EditCommandsCodeParams(
    val instruction: String,
    val model: String? = null,
    val mode: String = "edit",
    val range: Range? = null,
    val contextFiles: List<ContextFile>? = null  // NEW: pass context to Cody Agent
)
```

**And update `CodyEditService.requestFix()`:**

```kotlin
// Before — contextFiles parameter ignored
return server.editCommandsCode(
    EditCommandsCodeParams(instruction = instruction, mode = "edit", range = range)
)

// After — contextFiles passed through
return server.editCommandsCode(
    EditCommandsCodeParams(
        instruction = instruction,
        mode = "edit",
        range = range,
        contextFiles = contextFiles.takeIf { it.isNotEmpty() }
    )
)
```

### 11.6 B6: PreReviewService — PSI-Annotated Diff

**File:** `handover/src/main/kotlin/.../service/PreReviewService.kt`

**Current problem:** Sends raw diff with generic prompt. Cody guesses at Spring patterns without context.

**Fix:** Before sending the diff to Cody, annotate it with PSI metadata:

```kotlin
suspend fun buildEnrichedReviewPrompt(diff: String, changedFiles: List<VirtualFile>): String {
    val psiEnricher = PsiContextEnricher(project!!)
    val springEnricher: SpringContextEnricher = try {
        project!!.getService(SpringContextEnricher::class.java) ?: SpringContextEnricher.EMPTY
    } catch (_: Exception) { SpringContextEnricher.EMPTY }

    val fileAnnotations = changedFiles.mapNotNull { file ->
        val psi = psiEnricher.enrich(file.path)
        val spring = springEnricher.enrich(file.path)
        if (psi.className == null) return@mapNotNull null

        buildString {
            append("${file.name}: ${psi.className}")
            if (psi.classAnnotations.isNotEmpty()) {
                append(" (${psi.classAnnotations.joinToString(", ") { "@$it" }})")
            }
            if (spring != null && spring.isBean) {
                if (spring.transactionalMethods.isNotEmpty()) {
                    append("\n  @Transactional methods: ${spring.transactionalMethods.joinToString(", ")}")
                }
                if (spring.requestMappings.isNotEmpty()) {
                    append("\n  Endpoints: ${spring.requestMappings.joinToString(", ") { "${it.method} ${it.path}" }}")
                }
                if (spring.injectedDependencies.isNotEmpty()) {
                    append("\n  Dependencies: ${spring.injectedDependencies.joinToString(", ") { it.beanType.substringAfterLast('.') }}")
                }
            }
        }
    }

    return buildString {
        append("Review this Spring Boot code change for anti-patterns, ")
        append("missing @Transactional annotations, incorrect bean scoping, ")
        append("and potential issues.\n\n")
        if (fileAnnotations.isNotEmpty()) {
            append("## Changed Classes (IDE Analysis)\n")
            fileAnnotations.forEach { append("- $it\n") }
            append("\n")
        }
        append("## Diff\n```diff\n$diff\n```")
    }
}
```

---

## 12. Category B: Build & Navigation Intelligence

### 12.1 B7: HealthCheckService — Smart Check Skipping

**File:** `core/src/main/kotlin/.../healthcheck/HealthCheckService.kt`

**Current problem:** Runs all 4 checks (compile, test, copyright, sonar gate) on every pre-push, even when only test files changed.

**Fix:** Use `ProjectFileIndex` to classify changed files and skip unnecessary checks:

```kotlin
data class ChangeClassification(
    val hasProductionCode: Boolean,
    val hasTestCode: Boolean,
    val hasResources: Boolean,
    val hasBuildConfig: Boolean  // pom.xml, build.gradle
)

fun classifyChanges(changedFiles: List<VirtualFile>): ChangeClassification {
    val fileIndex = ProjectFileIndex.getInstance(project)
    var hasProd = false; var hasTest = false; var hasRes = false; var hasBuild = false

    for (file in changedFiles) {
        when {
            file.name == "pom.xml" || file.name.endsWith(".gradle.kts") -> hasBuild = true
            fileIndex.isInTestSourceContent(file) -> hasTest = true
            fileIndex.isInSourceContent(file) && !fileIndex.isInTestSourceContent(file) -> hasProd = true
            fileIndex.isInContent(file) && !fileIndex.isInSourceContent(file) -> hasRes = true
        }
    }
    return ChangeClassification(hasProd, hasTest, hasRes, hasBuild)
}

suspend fun runChecks(context: HealthCheckContext): HealthCheckResult {
    val classification = classifyChanges(context.changedFiles)

    val checksToRun = enabledChecks.filter { check ->
        when (check.id) {
            "maven-compile" -> classification.hasProductionCode || classification.hasBuildConfig
            "maven-test" -> classification.hasProductionCode || classification.hasTestCode || classification.hasBuildConfig
            "copyright" -> classification.hasProductionCode  // only production files need copyright
            "sonar-gate" -> true  // always check (server-side)
            else -> true
        }
    }
    // ... run checksToRun instead of all checks
}
```

**Impact:** Test-only changes skip compile → 60s → 15s. Resource-only changes skip both → 60s → 5s.

### 12.2 B8: MavenBuildService — IDE-Integrated Builds

**File:** `core/src/main/kotlin/.../maven/MavenBuildService.kt`

**Current problem:** Shells out to `mvn`/`mvn.cmd` via `GeneralCommandLine` + `OSProcessHandler`. This:
- Spawns a new process (30-60s overhead)
- Doesn't reuse IDE's Maven model/cache
- Fragile process management
- Output parsing via regex

**Fix:** Use IntelliJ's `MavenRunner` API for IDE-integrated builds:

```kotlin
// Before — subprocess
val commandLine = GeneralCommandLine(mvnExecutable)
    .withWorkDirectory(project.basePath)
    .withParameters(args)
val handler = OSProcessHandler(commandLine)

// After — IDE-integrated
fun runMavenBuild(goals: String, modules: List<String>): MavenBuildResult {
    val mavenManager = MavenProjectsManager.getInstance(project)
    if (!mavenManager.isMavenizedProject) {
        return runMavenBuildFallback(goals, modules)  // subprocess fallback
    }

    val params = MavenRunnerParameters(
        /* workingDirPath = */ project.basePath!!,
        /* pomFileName = */ "pom.xml",
        /* profilesMap = */ emptyMap(),
        /* goals = */ goals.split(" "),
        /* projectsCmdOptionValues = */ modules
    )

    val settings = MavenRunner.getInstance(project).settings.clone()
    // Use IDE's Maven settings (local repo, mirrors, etc.)

    MavenRunner.getInstance(project).run(params, settings, Runnable {
        // Build complete callback
    })
}
```

**Note:** `MavenRunner.run()` provides structured build output (no regex parsing needed), reuses IDE's Maven installation and settings, and integrates with IntelliJ's build tool window.

### 12.3 B9: SonarIssueAnnotator — PsiElement Resolution

**File:** `sonar/src/main/kotlin/.../ui/SonarIssueAnnotator.kt`

**Enhancement** (in addition to the A6 `VfsUtilCore` path fix):

When annotating Sonar issues, resolve the target `PsiElement` to provide richer tooltips:

```kotlin
// In apply() phase — EDT, can access PSI
override fun apply(file: PsiFile, result: AnnotationResult, holder: AnnotationHolder) {
    for (issue in result.issues) {
        val element = file.findElementAt(issue.startOffset)
        val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)

        val tooltip = buildString {
            append("[${issue.rule}] ${issue.message}")
            if (containingMethod != null) {
                append("\n\nIn method: ${containingMethod.name}()")
            }
            if (containingClass != null) {
                val annotations = containingClass.annotations
                    .mapNotNull { it.qualifiedName?.substringAfterLast('.') }
                if (annotations.isNotEmpty()) {
                    append("\nClass: @${annotations.joinToString(", @")} ${containingClass.name}")
                }
            }
        }

        // Store issue in highlighter for CodyIntentionAction to retrieve
        val annotation = holder.newAnnotation(severity, issue.message)
            .range(textRange)
            .tooltip(tooltip)
            .create()
    }
}
```

### 12.4 B10: PrService — Module/Endpoint-Aware Descriptions

**File:** `handover/src/main/kotlin/.../service/PrService.kt`

**Enhancement:** Generate rich PR descriptions using PSI + Maven context:

```kotlin
suspend fun buildEnrichedDescription(
    ticketId: String,
    ticketSummary: String,
    changedFiles: List<VirtualFile>
): String {
    val psiEnricher = PsiContextEnricher(project!!)

    // Detect affected Maven modules
    val modules = MavenProjectsManager.getInstance(project!!)
        .takeIf { it.isMavenizedProject }
        ?.projects
        ?.filter { mp -> changedFiles.any { VfsUtilCore.isAncestor(mp.directoryFile, it, false) } }
        ?.map { it.mavenId.artifactId }
        ?: emptyList()

    // Detect affected Spring endpoints
    val endpoints = mutableListOf<String>()
    for (file in changedFiles) {
        val psi = psiEnricher.enrich(file.path)
        // Spring endpoint detection via annotations
        val requestMappings = psi.classAnnotations.filter {
            it in listOf("RestController", "Controller")
        }
        if (requestMappings.isNotEmpty()) {
            endpoints.add("${file.nameWithoutExtension} (${psi.classAnnotations.joinToString(", ") { "@$it" }})")
        }
    }

    return buildString {
        append("## $ticketId: $ticketSummary\n\n")
        if (modules.isNotEmpty()) {
            append("**Affected modules:** ${modules.joinToString(", ")}\n\n")
        }
        if (endpoints.isNotEmpty()) {
            append("**Affected controllers:**\n")
            endpoints.forEach { append("- $it\n") }
            append("\n")
        }
        append("**Files changed:** ${changedFiles.size}\n")
    }
}
```

### 12.5 B11: CoverageLineMarkerProvider — Spring-Aware Endpoint Highlighting

**File:** `sonar/src/main/kotlin/.../ui/CoverageLineMarkerProvider.kt`

**Enhancement:** When an uncovered line is inside a `@RequestMapping` method, use a different (more urgent) icon:

```kotlin
override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // ... existing coverage check ...

    val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
    val isEndpoint = containingMethod?.annotations?.any {
        it.qualifiedName in REQUEST_MAPPING_ANNOTATIONS
    } ?: false

    val icon = when {
        covered -> IconLoader.getIcon("/icons/coverage-covered.svg", javaClass)
        isEndpoint -> IconLoader.getIcon("/icons/coverage-endpoint-uncovered.svg", javaClass) // RED+BOLD
        else -> IconLoader.getIcon("/icons/coverage-uncovered.svg", javaClass) // RED
    }

    // ...
}

companion object {
    private val REQUEST_MAPPING_ANNOTATIONS = setOf(
        "org.springframework.web.bind.annotation.RequestMapping",
        "org.springframework.web.bind.annotation.GetMapping",
        "org.springframework.web.bind.annotation.PostMapping",
        "org.springframework.web.bind.annotation.PutMapping",
        "org.springframework.web.bind.annotation.DeleteMapping",
        "org.springframework.web.bind.annotation.PatchMapping"
    )
}
```

### 12.6 B12: CoverageTreeDecorator — ProjectFileIndex

**File:** `sonar/src/main/kotlin/.../ui/CoverageTreeDecorator.kt`

**Fix:** Replace string matching with proper file classification:

```kotlin
// Before
val isTestFile = filePath.contains("/test/") || filePath.contains("/resources/")

// After
val fileIndex = ProjectFileIndex.getInstance(project)
val isTestFile = fileIndex.isInTestSourceContent(virtualFile)
val isGenerated = fileIndex.isInGeneratedSources(virtualFile)
val isExcluded = fileIndex.isExcluded(virtualFile)

// Only show coverage badges on main source files
if (isTestFile || isGenerated || isExcluded) return
```

---

## 13. File Change Summary

### New Files (7)

| Path | Module | Purpose |
|------|--------|---------|
| `cody/src/main/kotlin/.../service/PsiContextEnricher.kt` | `:cody` | PSI-based code context extraction (always available) |
| `cody/src/main/kotlin/.../service/SpringContextEnricher.kt` | `:cody` | Interface + `SpringContextEnricherImpl` for Spring context (Ultimate only) |
| `sonar/src/main/resources/icons/coverage-covered.svg` (+dark) | `:sonar` | SVG gutter icon (covered line) |
| `sonar/src/main/resources/icons/coverage-uncovered.svg` (+dark) | `:sonar` | SVG gutter icon (uncovered line) |
| `sonar/src/main/resources/icons/coverage-endpoint-uncovered.svg` (+dark) | `:sonar` | SVG gutter icon (uncovered `@RequestMapping` — urgent) |

### Modified Files (19)

| Path | Module | Category | Changes |
|------|--------|----------|---------|
| `build.gradle.kts` | root | A | `intellijIdea()` → `intellijIdeaUltimate()` |
| `gradle.properties` | root | A | Add Maven, Spring, Spring Boot to `platformBundledPlugins` |
| `plugin.xml` | root | A | Add 2 optional `<depends>` for Maven + Spring |
| `plugin-withMaven.xml` | root | A | Declare Maven dependency |
| `plugin-withSpring.xml` | root | A | Register `SpringContextEnricherImpl` as projectService |
| `MavenModuleDetector.kt` | `:core` | A1 | `MavenProjectsManager` API with regex fallback |
| `CopyrightCheckService.kt` | `:core` | A3 | `FileTypeRegistry` + `ProjectFileIndex` + document API |
| `CopyrightFixService.kt` | `:handover` | A4 | `LanguageCommenters` + `ProjectFileIndex` |
| `PlanDetectionService.kt` | `:bamboo` | A5 | SnakeYAML parsing with regex fallback |
| `SonarIssueAnnotator.kt` | `:sonar` | A6+B9 | `VfsUtilCore` + PsiElement resolution + Sonar issue user data |
| `CoverageLineMarkerProvider.kt` | `:sonar` | A7+B11 | `VfsUtilCore` + SVG icons + Spring endpoint detection |
| `CodyContextService.kt` | `:cody` | A2 | Orchestrate PSI + Spring enrichers, `suspend fun` |
| `CodyContextServiceLogic.kt` | `:cody` | A2 | Rename `resolveTestFile` → `resolveTestFileFallback` |
| `CodyIntentionAction.kt` | `:cody` | B1 | Pass real Sonar issue data + PSI/Spring context |
| `CodyCommitMessageHandlerFactory.kt` | `:cody` | B2 | Implement with PSI-enriched diff |
| `CodyChatService.kt` | `:cody` | B3 | Add `contextFiles` to ChatMessage |
| `EditModels.kt` | `:cody` | B5 | Add `contextFiles` field to `EditCommandsCodeParams` |
| `CodyEditService.kt` | `:cody` | B5 | Pass context files to Cody Agent |
| `PreReviewService.kt` | `:handover` | B6 | PSI-annotated diff prompt |
| `HealthCheckService.kt` | `:core` | B7 | Smart check skipping via `ProjectFileIndex` |
| `MavenBuildService.kt` | `:core` | B8 | `MavenRunner` integration with subprocess fallback |
| `PrService.kt` | `:handover` | B10 | Module/endpoint-aware descriptions |
| `CoverageTreeDecorator.kt` | `:sonar` | B12 | `ProjectFileIndex.isInTestSourceContent()` |

### Skeleton Files to Implement (2)

| Path | Module | Category | Currently |
|------|--------|----------|-----------|
| `CodyTestGenerator.kt` | `:cody` | B4 | Empty skeleton → Spring-aware test pattern selection |
| `CodyCommitMessageHandlerFactory.kt` | `:cody` | B2 | Empty handler → PSI-enriched commit messages |

---

## 14. Testing Strategy

### 14.1 Test Types by Tier

| Tier | Test Approach | Framework |
|------|--------------|-----------|
| Tier 1 (Maven) | Unit tests with mock `MavenProjectsManager` + fallback tests | JUnit 5 + MockK |
| Tier 1 (Cody PSI) | `BasePlatformTestCase` with fixture Java files containing `@Service`, `@Controller`, `@Transactional` | IntelliJ test framework |
| Tier 1 (Cody Spring) | `BasePlatformTestCase` with Spring-annotated fixtures + Spring test framework | IntelliJ test framework |
| Tier 2 (Cody features) | Unit tests for prompt building + `BasePlatformTestCase` for PSI integration | JUnit 5 + IntelliJ test framework |
| Tier 2 (Health Check) | Unit tests for `classifyChanges()` with mock `ProjectFileIndex` | JUnit 5 + MockK |
| Tier 3 (Copyright) | Unit tests (pure logic unchanged) + platform tests for `FileTypeRegistry` | JUnit 5 + BasePlatformTestCase |
| Tier 3 (YAML) | Unit tests with YAML strings | JUnit 5 |
| Tier 4 (Sonar) | Existing annotator tests + updated path assertions + PsiElement tooltip tests | IntelliJ test framework |

### 14.2 Regression Safety

Every modified file preserves its fallback path that matches the **exact current behavior**:
- All existing tests pass without modification (fallback paths are exercised)
- New tests verify the enhanced API paths
- `isMavenAvailable()` / `isSpringAvailable()` flags can force fallback in tests
- `PsiContextEnricher` returns `emptyContext()` when PSI is not available (safe degradation)

---

## 15. Edge Cases

| Scenario | Handling |
|----------|---------|
| Maven projects not yet imported (opening project) | `MavenProjectsManager.projects` is empty → regex fallback |
| Spring plugin disabled by user | `project.getService(SpringContextEnricher::class.java)` returns null → `EMPTY` |
| File outside any Maven module | `detectViaMavenApi()` skips it, no artifactId returned |
| Anonymous inner class in PSI | `PsiTreeUtil.findChildOfType(PsiClass)` finds outermost class |
| Kotlin file (no `PsiJavaFile`) | `packageName` from PSI cast returns null → instruction omits it |
| Very large class (>1000 methods) | `extractMethodAnnotations` processes all — acceptable as readAction is bounded |
| YAML parse failure | SnakeYAML throws → catch → regex fallback |
| Non-Maven project (Gradle) | `isMavenizedProject` returns false → regex fallback |
| Multiple constructors (Spring) | Takes first constructor for injection analysis — Spring convention |
| Test-only changes in health check | `classifyChanges()` detects → skips compile → runs tests only |
| No Sonar issue at caret (CodyIntentionAction) | Uses generic range + "manual" ruleKey — PSI context still enriched |
| Cody Agent ignores `contextFiles` field | Graceful — instruction text still contains enriched context as plain text |
| `MavenRunner` not available | Falls back to `OSProcessHandler` subprocess |
| Changed file is generated (build output) | `ProjectFileIndex.isInGeneratedSources()` → excluded from health check / coverage |

---

## 16. Service Lifecycle Notes

**PsiContextEnricher** — Lightweight, stateless helper class. Created as a plain instance in `CodyContextService` (not a registered service). Holds only a `Project` reference, which is the same lifecycle as `CodyContextService` itself (project-scoped). No memory leak risk — when the project closes, `CodyContextService` is disposed, and `PsiContextEnricher` is garbage collected with it.

**SpringContextEnricher** — Registered as an IntelliJ `projectService` in `plugin-withSpring.xml`. The interface lives in `:cody` (always on classpath). The implementation (`SpringContextEnricherImpl`) is only loaded when the Spring plugin is present. `CodyContextService` retrieves it via `project.getService()` — returns null on Community edition, falling back to `EMPTY`.

**CodyContextService** — Must be annotated `@Service(Service.Level.PROJECT)` and registered in `plugin.xml` as a `<projectService>`. Methods `gatherFixContext()` and `gatherTestContext()` become `suspend fun` (they call `readAction {}` internally). All existing callers must be updated to call from a coroutine scope.

---

## 17. Performance Considerations

| Operation | Cost | Mitigation |
|-----------|------|------------|
| `readAction {}` | Acquires read lock | Already required for PSI access; bounded per-file |
| `ReferencesSearch.search()` | Can be slow on large projects | Limited to `.take(10)` references |
| `SpringModelSearchers.findBeans()` | Depends on Spring model size | Limited to `.take(10)` consumers |
| `TestFinderHelper.findTestsForClass()` | Searches test indices | Built-in IntelliJ caching, typically <10ms |
| `MavenProjectsManager.projects` | Returns cached list | No network or disk I/O |
| SnakeYAML parsing | Fast for small YAML (<1KB) | Bamboo specs are typically small |

---

## 18. Migration Path

The upgrade is fully backward-compatible. No user action required. The enhanced behavior activates automatically when the IDE has Maven/Spring plugins loaded.

**Rollback:** If any API causes issues, the fallback paths (regex/string) are always available. A settings flag `useDeepCodeIntelligence` could be added to `PluginSettings` to let users force the fallback path, but this is not implemented in the initial version (YAGNI — add if users report issues).
