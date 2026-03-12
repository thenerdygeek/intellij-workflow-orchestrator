# Code Intelligence Upgrade Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade all 19 features from regex/string parsing to IntelliJ PSI, Spring, and Maven APIs — achieving ~90% IDE-level code intelligence across the entire plugin.

**Architecture:** Two new shared services (`PsiContextEnricher`, `SpringContextEnricher`) provide code context to all consumers. Build target changes from `intellijIdea()` to `intellijIdeaUltimate()` to compile against Spring APIs. All upgrades maintain regex fallback when APIs are unavailable.

**Tech Stack:** IntelliJ Platform 2025.1+, Kotlin 2.1.10, PSI APIs, SpringManager, MavenProjectsManager, ProjectFileIndex, VfsUtilCore, SnakeYAML, JUnit 5, MockK, BasePlatformTestCase

**Spec:** `docs/superpowers/specs/2026-03-12-code-intelligence-upgrade-design.md`

---

## Chunk 1: Build Configuration Foundation

This chunk changes the build target to Ultimate, adds optional Maven/Spring dependencies, and wires plugin descriptor files. Everything else depends on this.

---

### Task 1: Update Build Target to IntelliJ Ultimate

**Files:**
- Modify: `build.gradle.kts:33`
- Modify: `gradle.properties:17`

- [ ] **Step 1: Write the failing build verification**

Run: `./gradlew dependencies --configuration compileClasspath 2>&1 | grep -c "spring"`
Expected: `0` (Spring APIs not yet on classpath)

- [ ] **Step 2: Update gradle.properties to add bundled plugins**

In `gradle.properties`, change line 17:

```properties
# Before:
platformBundledPlugins = com.intellij.java

# After:
platformBundledPlugins = com.intellij.java, org.jetbrains.idea.maven, com.intellij.spring, com.intellij.spring.boot
```

- [ ] **Step 3: Update build.gradle.kts to target Ultimate**

In `build.gradle.kts`, change line 33:

```kotlin
// Before:
intellijIdea(providers.gradleProperty("platformVersion"))

// After:
intellijIdeaUltimate(providers.gradleProperty("platformVersion"))
```

- [ ] **Step 4: Verify build resolves new dependencies**

Run: `./gradlew dependencies --configuration compileClasspath 2>&1 | grep -c "spring"`
Expected: non-zero (Spring APIs now on classpath)

Run: `./gradlew assemble`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts gradle.properties
git commit -m "build: target IntelliJ Ultimate for Spring/Maven API access

Add org.jetbrains.idea.maven, com.intellij.spring, com.intellij.spring.boot
to platformBundledPlugins. Switch from intellijIdea() to
intellijIdeaUltimate() so Spring APIs are available at compile time.

The plugin still installs on Community edition — these are used as
optional dependencies with graceful fallback."
```

---

### Task 2: Wire Optional Plugin Dependencies in plugin.xml

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml:13`
- Modify: `src/main/resources/META-INF/plugin-withMaven.xml`
- Modify: `src/main/resources/META-INF/plugin-withSpring.xml`

- [ ] **Step 1: Add optional depends to plugin.xml**

In `src/main/resources/META-INF/plugin.xml`, after line 13 (`<depends optional="true" config-file="plugin-withCody.xml">com.sourcegraph.jetbrains</depends>`), add:

```xml
    <depends optional="true" config-file="plugin-withMaven.xml">org.jetbrains.idea.maven</depends>
    <depends optional="true" config-file="plugin-withSpring.xml">com.intellij.spring</depends>
```

- [ ] **Step 2: Update plugin-withMaven.xml**

Replace contents of `src/main/resources/META-INF/plugin-withMaven.xml`:

```xml
<!--
  Extensions registered only when Maven plugin is available.
  Referenced from plugin.xml via: <depends optional="true" config-file="plugin-withMaven.xml">org.jetbrains.idea.maven</depends>
-->
<idea-plugin>
    <extensions defaultExtensionNs="com.intellij">
        <!-- Maven API is used directly in :core services via runtime check.
             This file declares the optional dependency relationship. -->
    </extensions>
</idea-plugin>
```

- [ ] **Step 3: Update plugin-withSpring.xml with SpringContextEnricher registration**

Replace contents of `src/main/resources/META-INF/plugin-withSpring.xml`:

```xml
<!--
  Extensions registered only when Spring plugin is available.
  Referenced from plugin.xml via: <depends optional="true" config-file="plugin-withSpring.xml">com.intellij.spring</depends>
-->
<idea-plugin>
    <extensions defaultExtensionNs="com.intellij">
        <projectService
            serviceInterface="com.workflow.orchestrator.cody.service.SpringContextEnricher"
            serviceImplementation="com.workflow.orchestrator.cody.service.SpringContextEnricherImpl"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 4: Verify plugin descriptor is valid**

Run: `./gradlew verifyPlugin`
Expected: No errors related to plugin.xml structure

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml src/main/resources/META-INF/plugin-withMaven.xml src/main/resources/META-INF/plugin-withSpring.xml
git commit -m "feat: add optional Maven/Spring dependencies to plugin descriptor

Wire plugin-withMaven.xml and plugin-withSpring.xml as optional depends.
Spring config registers SpringContextEnricherImpl as a projectService
that is only loaded when the Spring plugin is present."
```

---

## Chunk 2: PsiContextEnricher + SpringContextEnricher (Shared Foundation)

These two services are the core of the intelligence upgrade. Every subsequent task depends on them.

---

### Task 3: Create PsiContextEnricher — Write Tests

**Files:**
- Create: `cody/src/test/kotlin/com/workflow/orchestrator/cody/service/PsiContextEnricherTest.kt`

- [ ] **Step 1: Write the test file**

```kotlin
package com.workflow.orchestrator.cody.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for PsiContextEnricher's pure-logic helpers.
 * PSI integration tests require BasePlatformTestCase and are in PsiContextEnricherPlatformTest.
 * These tests verify the emptyContext fallback and data class structure.
 */
class PsiContextEnricherTest {

    @Test
    fun `PsiContext data class has correct defaults in emptyContext`() {
        val ctx = PsiContextEnricher.PsiContext(
            fileType = "unknown",
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
        assertNull(ctx.packageName)
        assertNull(ctx.className)
        assertNull(ctx.testFilePath)
        assertNull(ctx.mavenModule)
        assertFalse(ctx.isTestFile)
        assertEquals("unknown", ctx.fileType)
        assertTrue(ctx.classAnnotations.isEmpty())
        assertTrue(ctx.methodAnnotations.isEmpty())
        assertTrue(ctx.imports.isEmpty())
        assertTrue(ctx.relatedFiles.isEmpty())
    }

    @Test
    fun `PsiContext correctly stores annotation data`() {
        val ctx = PsiContextEnricher.PsiContext(
            fileType = "JAVA",
            packageName = "com.example",
            className = "com.example.UserService",
            classAnnotations = listOf("Service", "Transactional"),
            methodAnnotations = mapOf("getUser" to listOf("Transactional", "Override")),
            testFilePath = "src/test/java/com/example/UserServiceTest.java",
            imports = listOf("org.springframework.stereotype.Service"),
            mavenModule = "user-service",
            relatedFiles = listOf("src/main/java/com/example/UserController.java"),
            isTestFile = false
        )
        assertEquals("com.example", ctx.packageName)
        assertEquals("com.example.UserService", ctx.className)
        assertEquals(2, ctx.classAnnotations.size)
        assertTrue(ctx.classAnnotations.contains("Service"))
        assertEquals(listOf("Transactional", "Override"), ctx.methodAnnotations["getUser"])
        assertEquals("user-service", ctx.mavenModule)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :cody:test --tests "com.workflow.orchestrator.cody.service.PsiContextEnricherTest" -v`
Expected: FAIL — `PsiContextEnricher` class not found

---

### Task 4: Create PsiContextEnricher — Implement

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/PsiContextEnricher.kt`

- [ ] **Step 1: Write PsiContextEnricher implementation**

```kotlin
package com.workflow.orchestrator.cody.service

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testIntegration.TestFinderHelper

class PsiContextEnricher(private val project: Project) {

    data class PsiContext(
        val fileType: String,
        val packageName: String?,
        val className: String?,
        val classAnnotations: List<String>,
        val methodAnnotations: Map<String, List<String>>,
        val testFilePath: String?,
        val imports: List<String>,
        val mavenModule: String?,
        val relatedFiles: List<String>,
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

    private fun detectMavenModule(vFile: VirtualFile): String? {
        return try {
            val mavenManager = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
            if (!mavenManager.isMavenizedProject) return null
            mavenManager.projects.find { mavenProject ->
                VfsUtilCore.isAncestor(mavenProject.directoryFile, vFile, false)
            }?.mavenId?.artifactId
        } catch (_: Exception) {
            null
        }
    }

    private fun findRelatedFiles(psiClass: PsiClass): List<String> {
        return try {
            val refs = ReferencesSearch.search(psiClass).findAll().take(10)
            refs.mapNotNull { ref ->
                ref.element.containingFile?.virtualFile?.path
            }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
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

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :cody:test --tests "com.workflow.orchestrator.cody.service.PsiContextEnricherTest" -v`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/service/PsiContextEnricher.kt cody/src/test/kotlin/com/workflow/orchestrator/cody/service/PsiContextEnricherTest.kt
git commit -m "feat(cody): add PsiContextEnricher for IDE-level code context

Extracts annotations, package name, test file, imports, Maven module,
and related files using IntelliJ PSI APIs. Falls back to empty context
when PSI is unavailable. Uses TestFinderHelper for test discovery
instead of string path replacement."
```

---

### Task 5: Create SpringContextEnricher Interface — Write Tests

**Files:**
- Create: `cody/src/test/kotlin/com/workflow/orchestrator/cody/service/SpringContextEnricherTest.kt`

- [ ] **Step 1: Write the test file**

```kotlin
package com.workflow.orchestrator.cody.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpringContextEnricherTest {

    @Test
    fun `EMPTY enricher returns null for any file`() {
        val enricher = SpringContextEnricher.EMPTY
        // EMPTY is synchronous — wrap in runBlocking for test
        val result = kotlinx.coroutines.runBlocking { enricher.enrich("/any/path.kt") }
        assertNull(result)
    }

    @Test
    fun `SpringContext data class stores bean information correctly`() {
        val ctx = SpringContextEnricher.SpringContext(
            isBean = true,
            beanType = "Service",
            injectedDependencies = listOf(
                SpringContextEnricher.BeanDependency(
                    beanName = "userRepo",
                    beanType = "com.example.UserRepository",
                    qualifier = null
                )
            ),
            transactionalMethods = listOf("createUser", "deleteUser"),
            requestMappings = emptyList(),
            beanConsumers = listOf("com.example.UserController")
        )
        assertTrue(ctx.isBean)
        assertEquals("Service", ctx.beanType)
        assertEquals(1, ctx.injectedDependencies.size)
        assertEquals("userRepo", ctx.injectedDependencies[0].beanName)
        assertEquals(2, ctx.transactionalMethods.size)
        assertEquals(1, ctx.beanConsumers.size)
    }

    @Test
    fun `RequestMappingInfo stores endpoint data`() {
        val info = SpringContextEnricher.RequestMappingInfo(
            method = "GET",
            path = "/api/users/{id}",
            handlerMethod = "getUserById"
        )
        assertEquals("GET", info.method)
        assertEquals("/api/users/{id}", info.path)
        assertEquals("getUserById", info.handlerMethod)
    }

    @Test
    fun `non-bean SpringContext has empty collections`() {
        val ctx = SpringContextEnricher.SpringContext(
            isBean = false,
            beanType = null,
            injectedDependencies = emptyList(),
            transactionalMethods = emptyList(),
            requestMappings = emptyList(),
            beanConsumers = emptyList()
        )
        assertFalse(ctx.isBean)
        assertNull(ctx.beanType)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :cody:test --tests "com.workflow.orchestrator.cody.service.SpringContextEnricherTest" -v`
Expected: FAIL — `SpringContextEnricher` not found

---

### Task 6: Create SpringContextEnricher — Implement Interface + Impl

**Files:**
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/SpringContextEnricher.kt`
- Create: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/SpringContextEnricherImpl.kt`

- [ ] **Step 1: Write the SpringContextEnricher interface**

```kotlin
package com.workflow.orchestrator.cody.service

interface SpringContextEnricher {

    data class SpringContext(
        val isBean: Boolean,
        val beanType: String?,
        val injectedDependencies: List<BeanDependency>,
        val transactionalMethods: List<String>,
        val requestMappings: List<RequestMappingInfo>,
        val beanConsumers: List<String>
    )

    data class BeanDependency(
        val beanName: String,
        val beanType: String,
        val qualifier: String?
    )

    data class RequestMappingInfo(
        val method: String,
        val path: String,
        val handlerMethod: String
    )

    suspend fun enrich(filePath: String): SpringContext?

    companion object {
        val EMPTY = object : SpringContextEnricher {
            override suspend fun enrich(filePath: String): SpringContext? = null
        }
    }
}
```

- [ ] **Step 2: Write the SpringContextEnricherImpl**

```kotlin
package com.workflow.orchestrator.cody.service

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil
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
                injectedDependencies = findInjectedDependencies(psiClass),
                transactionalMethods = findTransactionalMethods(psiClass),
                requestMappings = findRequestMappings(psiClass),
                beanConsumers = findBeanConsumers(psiClass, springModel)
            )
        }
    }

    private fun isSpringBean(psiClass: PsiClass, model: SpringModel): Boolean {
        val beans = SpringModelSearchers.findBeans(model, psiClass)
        return beans.isNotEmpty()
    }

    private fun detectBeanType(psiClass: PsiClass): String? {
        for (fqn in springBeanAnnotations) {
            if (psiClass.getAnnotation(fqn) != null) {
                return fqn.substringAfterLast('.')
            }
        }
        return null
    }

    private fun findInjectedDependencies(
        psiClass: PsiClass
    ): List<SpringContextEnricher.BeanDependency> {
        val deps = mutableListOf<SpringContextEnricher.BeanDependency>()

        // Constructor injection (preferred Spring pattern)
        val primaryConstructor = psiClass.constructors.firstOrNull()
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
        if (psiClass.getAnnotation(transactionalFqn) != null) {
            methods.addAll(psiClass.methods.filter { it.hasModifierProperty("public") }.map { it.name })
        } else {
            psiClass.methods.filter { it.getAnnotation(transactionalFqn) != null }
                .forEach { methods.add(it.name) }
        }
        return methods
    }

    private fun findRequestMappings(psiClass: PsiClass): List<SpringContextEnricher.RequestMappingInfo> {
        val mappings = mutableListOf<SpringContextEnricher.RequestMappingInfo>()

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

- [ ] **Step 3: Run tests**

Run: `./gradlew :cody:test --tests "com.workflow.orchestrator.cody.service.SpringContextEnricherTest" -v`
Expected: PASS

- [ ] **Step 4: Run full cody module tests**

Run: `./gradlew :cody:test`
Expected: All existing tests still pass

- [ ] **Step 5: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/service/SpringContextEnricher.kt cody/src/main/kotlin/com/workflow/orchestrator/cody/service/SpringContextEnricherImpl.kt cody/src/test/kotlin/com/workflow/orchestrator/cody/service/SpringContextEnricherTest.kt
git commit -m "feat(cody): add SpringContextEnricher for Spring bean context

Interface (always loaded) + SpringContextEnricherImpl (loaded only when
Spring plugin present via plugin-withSpring.xml). Extracts bean type,
injected dependencies, @Transactional methods, @RequestMapping endpoints,
and bean consumers from SpringManager.getCombinedModel()."
```

---

## Chunk 3: Category A — MavenModuleDetector + CodyContextService Upgrade

---

### Task 7: Upgrade MavenModuleDetector — Write Tests

**Files:**
- Modify: `core/src/test/kotlin/com/workflow/orchestrator/core/maven/MavenModuleDetectorTest.kt`

- [ ] **Step 1: Add tests for MavenProjectsManager API path**

Add the following tests to the existing `MavenModuleDetectorTest.kt`:

```kotlin
    @Test
    fun `extractArtifactId is renamed to extractArtifactIdFallback`() {
        // Verify the fallback method name exists — existing behavior preserved
        val pomContent = """
            <project><artifactId>my-service</artifactId></project>
        """.trimIndent()
        val pomFile = mockk<VirtualFile>()
        every { pomFile.contentsToByteArray() } returns pomContent.toByteArray()

        val detector = MavenModuleDetector(project)
        assertEquals("my-service", detector.extractArtifactIdFallback(pomFile))
    }

    @Test
    fun `findNearestPom is renamed to findNearestPomFallback`() {
        val pomFile = mockk<VirtualFile>()
        val dir = mockk<VirtualFile>()
        val file = mockk<VirtualFile>()
        every { file.parent } returns dir
        every { dir.findChild("pom.xml") } returns pomFile

        val detector = MavenModuleDetector(project)
        assertEquals(pomFile, detector.findNearestPomFallback(file))
    }
```

- [ ] **Step 2: Run tests to see expected failures**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.maven.MavenModuleDetectorTest" -v`
Expected: FAIL — `extractArtifactIdFallback` and `findNearestPomFallback` methods not found

---

### Task 8: Upgrade MavenModuleDetector — Implement

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenModuleDetector.kt`

- [ ] **Step 1: Rewrite MavenModuleDetector with MavenProjectsManager primary path**

Replace the entire contents of `core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenModuleDetector.kt`:

```kotlin
package com.workflow.orchestrator.core.maven

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

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
        mavenManager: org.jetbrains.idea.maven.project.MavenProjectsManager
    ): List<String> {
        val modules = mutableSetOf<String>()
        val mavenProjects = mavenManager.projects
        for (file in changedFiles) {
            val owningProject = mavenProjects.find { mavenProject ->
                VfsUtilCore.isAncestor(mavenProject.directoryFile, file, false)
            }
            if (owningProject != null) {
                modules.add(owningProject.mavenId.artifactId)
            }
        }
        return modules.toList()
    }

    /**
     * Fallback path: used when Maven plugin is not available or projects haven't imported yet.
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
        if (modules.isEmpty()) {
            return goalList
        }
        return listOf("-pl", modules.joinToString(","), "-am") + goalList
    }

    private fun getMavenManager(): org.jetbrains.idea.maven.project.MavenProjectsManager? {
        return try {
            org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
                .takeIf { it.isMavenizedProject }
        } catch (_: Exception) {
            null // Maven plugin not available
        }
    }

    // --- Fallback methods (preserved from original implementation) ---

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

- [ ] **Step 2: Update existing tests to use new method names**

In `MavenModuleDetectorTest.kt`, rename:
- `extractArtifactId` → `extractArtifactIdFallback` in existing tests
- `findNearestPom` → `findNearestPomFallback` in existing tests

- [ ] **Step 3: Run all MavenModuleDetector tests**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.maven.MavenModuleDetectorTest" -v`
Expected: ALL PASS

- [ ] **Step 4: Run full core module tests**

Run: `./gradlew :core:test`
Expected: All pass

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenModuleDetector.kt core/src/test/kotlin/com/workflow/orchestrator/core/maven/MavenModuleDetectorTest.kt
git commit -m "feat(core): upgrade MavenModuleDetector to use MavenProjectsManager

Primary path uses MavenProjectsManager.projects with VfsUtilCore
ancestry check. Falls back to regex XML parsing when Maven plugin
unavailable or projects not yet imported. Existing tests updated
to use *Fallback method names."
```

---

### Task 9: Upgrade CodyContextService — Write Tests

**Files:**
- Modify: `cody/src/test/kotlin/com/workflow/orchestrator/cody/service/CodyContextServiceTest.kt`

- [ ] **Step 1: Update test for renamed resolveTestFile method**

In `CodyContextServiceTest.kt`, rename `resolveTestFile` → `resolveTestFileFallback` in the three existing test methods that call it:

```kotlin
    @Test
    fun `resolveTestFileFallback maps main to test path`() {
        val result = service.resolveTestFileFallback("src/main/kotlin/com/app/UserService.kt")
        assertEquals("src/test/kotlin/com/app/UserServiceTest.kt", result)
    }

    @Test
    fun `resolveTestFileFallback maps java main to test path`() {
        val result = service.resolveTestFileFallback("src/main/java/com/app/UserService.java")
        assertEquals("src/test/java/com/app/UserServiceTest.java", result)
    }

    @Test
    fun `resolveTestFileFallback returns null for non-main files`() {
        val result = service.resolveTestFileFallback("src/test/kotlin/FooTest.kt")
        assertNull(result)
    }
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew :cody:test --tests "com.workflow.orchestrator.cody.service.CodyContextServiceTest" -v`
Expected: FAIL — `resolveTestFileFallback` not found

---

### Task 10: Upgrade CodyContextService + CodyContextServiceLogic — Implement

**Files:**
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyContextServiceLogic.kt`
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyContextService.kt`

- [ ] **Step 1: Update CodyContextServiceLogic — rename resolveTestFile**

Replace `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyContextServiceLogic.kt`:

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

- [ ] **Step 2: Rewrite CodyContextService with PSI + Spring enrichment**

Replace `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyContextService.kt`:

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

        if (psi.packageName != null) append("Package: ${psi.packageName}\n")
        if (psi.mavenModule != null) append("Maven module: ${psi.mavenModule}\n")
        if (psi.classAnnotations.isNotEmpty()) {
            append("Class annotations: ${psi.classAnnotations.joinToString(", ") { "@$it" }}\n")
        }

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
        psi.testFilePath?.let { files.add(ContextFile(uri = it)) }
        psi.relatedFiles.take(3).forEach { files.add(ContextFile(uri = it)) }
        return files
    }
}
```

- [ ] **Step 3: Register CodyContextService in plugin.xml**

In `plugin.xml`, add after the existing Cody Agent services (around line 119):

```xml
        <!-- Cody Context Service (PSI+Spring enrichment) -->
        <projectService
            serviceImplementation="com.workflow.orchestrator.cody.service.CodyContextService"/>
```

- [ ] **Step 4: Run all cody tests**

Run: `./gradlew :cody:test -v`
Expected: All pass (including renamed resolveTestFileFallback tests)

- [ ] **Step 5: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyContextService.kt cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyContextServiceLogic.kt cody/src/test/kotlin/com/workflow/orchestrator/cody/service/CodyContextServiceTest.kt src/main/resources/META-INF/plugin.xml
git commit -m "feat(cody): upgrade CodyContextService with PSI+Spring enrichment

CodyContextService now uses PsiContextEnricher and SpringContextEnricher
to provide rich code context to all Cody operations. Fix instructions
include package, annotations, Spring bean type, dependencies, endpoints.
Test instructions select patterns (@WebMvcTest, @DataJpaTest, @MockBean)
based on Spring bean type. Context file list includes test file and
related files from PSI reference search.

resolveTestFile renamed to resolveTestFileFallback in CodyContextServiceLogic."
```

---

## Chunk 4: Category B — Cody Intelligence Layer

---

### Task 11: Fix EditModels.kt Protocol DTO + CodyEditService

**Files:**
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/protocol/EditModels.kt:5-10`
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyEditService.kt:20-22`

- [ ] **Step 1: Add contextFiles to EditCommandsCodeParams**

In `EditModels.kt`, change `EditCommandsCodeParams`:

```kotlin
data class EditCommandsCodeParams(
    val instruction: String,
    val model: String? = null,
    val mode: String = "edit",
    val range: Range? = null,
    val contextFiles: List<ContextFile>? = null
)
```

Add import at top of file:
```kotlin
import com.workflow.orchestrator.cody.protocol.ContextFile
```

Note: `ContextFile` is defined in `ChatModels.kt` in the same package — no additional import path needed if they share the package.

- [ ] **Step 2: Update CodyEditService to pass contextFiles**

In `CodyEditService.kt`, update `requestFix()`:

```kotlin
    suspend fun requestFix(
        filePath: String,
        range: Range,
        instruction: String,
        contextFiles: List<ContextFile> = emptyList()
    ): EditTask {
        val server = providerService().ensureRunning()
        server.textDocumentDidFocus(TextDocumentIdentifier(filePath))
        return server.editCommandsCode(
            EditCommandsCodeParams(
                instruction = instruction,
                mode = "edit",
                range = range,
                contextFiles = contextFiles.takeIf { it.isNotEmpty() }
            )
        ).await()
    }
```

- [ ] **Step 3: Run cody tests to verify no regressions**

Run: `./gradlew :cody:test -v`
Expected: All pass

- [ ] **Step 4: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/protocol/EditModels.kt cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyEditService.kt
git commit -m "fix(cody): pass contextFiles through EditCommandsCodeParams to Cody Agent

Add contextFiles field to EditCommandsCodeParams DTO. Update
CodyEditService.requestFix() to actually pass context files instead
of silently dropping them."
```

---

### Task 12: Upgrade CodyIntentionAction — Pass Real Sonar Issue Data

**Files:**
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionAction.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/SonarIssueAnnotator.kt`

- [ ] **Step 1: Add SONAR_ISSUE_KEY to SonarIssueAnnotator**

In `SonarIssueAnnotator.kt`, add at the top of the companion object:

```kotlin
    companion object {
        val SONAR_ISSUE_KEY = com.intellij.openapi.util.Key.create<MappedIssue>("workflow.sonar.issue")

        fun mapSeverity(type: IssueType, severity: IssueSeverity): HighlightSeverity = when {
            // ... existing code unchanged ...
        }
    }
```

Then in the `apply()` method, after `holder.newAnnotation(...).create()`, store the issue data. Update the `apply()` method:

```kotlin
    override fun apply(file: PsiFile, annotationResult: SonarAnnotationResult, holder: AnnotationHolder) {
        val doc = file.viewProvider.document ?: return
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(file.project)
            .selectedTextEditor

        for (issue in annotationResult.issues) {
            val startLine = (issue.startLine - 1).coerceIn(0, doc.lineCount - 1)
            val endLine = (issue.endLine - 1).coerceIn(0, doc.lineCount - 1)

            val startOffset = doc.getLineStartOffset(startLine) + issue.startOffset
            val endOffset = if (issue.endOffset > 0) {
                doc.getLineStartOffset(endLine) + issue.endOffset
            } else {
                doc.getLineEndOffset(endLine)
            }

            val textRange = TextRange(
                startOffset.coerceIn(0, doc.textLength),
                endOffset.coerceIn(0, doc.textLength)
            )

            if (textRange.isEmpty) continue

            val severity = mapSeverity(issue.type, issue.severity)
            val tooltip = "[${issue.rule}] ${issue.message}" +
                (issue.effort?.let { " (effort: $it)" } ?: "")

            holder.newAnnotation(severity, tooltip)
                .range(textRange)
                .tooltip(tooltip)
                .create()

            // Store issue in highlighter for CodyIntentionAction to retrieve
            if (editor != null) {
                val highlighters = editor.markupModel.allHighlighters
                    .filter { it.startOffset == textRange.startOffset && it.endOffset == textRange.endOffset }
                for (hl in highlighters) {
                    hl.putUserData(SONAR_ISSUE_KEY, issue)
                }
            }
        }
    }
```

- [ ] **Step 2: Rewrite CodyIntentionAction to use real issue data**

Replace `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionAction.kt`:

```kotlin
package com.workflow.orchestrator.cody.editor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.cody.protocol.Position
import com.workflow.orchestrator.cody.protocol.Range
import com.workflow.orchestrator.cody.service.CodyContextService
import com.workflow.orchestrator.cody.service.CodyEditService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.sonar.model.MappedIssue
import com.workflow.orchestrator.sonar.ui.SonarIssueAnnotator
import kotlinx.coroutines.*

class CodyIntentionAction : IntentionAction {

    override fun getText(): String = "Workflow: Fix with Cody"

    override fun getFamilyName(): String = "Workflow Orchestrator"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null) return false
        val settings = PluginSettings.getInstance(project)
        if (settings.state.sourcegraphUrl.isNullOrBlank()) return false
        if (settings.state.codyEnabled == false) return false
        if (!CredentialStore().hasToken(ServiceType.SOURCEGRAPH)) return false
        val caretLine = editor.caretModel.logicalPosition.line
        val hasIssue = editor.markupModel.allHighlighters.any { hl ->
            val startLine = editor.document.getLineNumber(hl.startOffset)
            startLine == caretLine && hl.errorStripeTooltip != null
        }
        return hasIssue
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return

        val caretOffset = editor.caretModel.offset
        val sonarIssue = findSonarIssueAtCaret(editor, caretOffset)

        val range = if (sonarIssue != null) {
            Range(
                start = Position(line = sonarIssue.startLine - 1, character = 0),
                end = Position(line = sonarIssue.endLine, character = 0)
            )
        } else {
            val caretLine = editor.caretModel.logicalPosition.line
            Range(
                start = Position(line = caretLine, character = 0),
                end = Position(line = caretLine + 1, character = 0)
            )
        }

        val filePath = file.virtualFile.path
        val contextService = project.service<CodyContextService>()

        @Suppress("DEPRECATION")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
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
        return editor.markupModel.allHighlighters
            .filter { it.startOffset <= offset && offset <= it.endOffset }
            .mapNotNull { it.getUserData(SonarIssueAnnotator.SONAR_ISSUE_KEY) }
            .firstOrNull()
    }

    override fun startInWriteAction(): Boolean = false
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :cody:test :sonar:test -v`
Expected: All pass

- [ ] **Step 4: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionAction.kt sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/SonarIssueAnnotator.kt
git commit -m "feat(cody): CodyIntentionAction passes real Sonar issue data + PSI context

Instead of hardcoded CODE_SMELL/unknown, extracts actual MappedIssue
from SonarIssueAnnotator highlighter userData. Uses CodyContextService
(PSI+Spring enriched) for instruction and context files."
```

---

### Task 13: Implement CodyCommitMessageHandler

**Files:**
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/vcs/CodyCommitMessageHandlerFactory.kt`

- [ ] **Step 1: Implement the commit message handler**

Replace `cody/src/main/kotlin/com/workflow/orchestrator/cody/vcs/CodyCommitMessageHandlerFactory.kt`:

```kotlin
package com.workflow.orchestrator.cody.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.workflow.orchestrator.cody.service.PsiContextEnricher
import git4idea.GitVcs

class CodyCommitMessageHandlerFactory : VcsCheckinHandlerFactory(GitVcs.getKey()) {

    override fun createVcsHandler(
        panel: CheckinProjectPanel,
        commitContext: CommitContext
    ): CheckinHandler {
        return CodyCommitMessageHandler(panel)
    }
}

class CodyCommitMessageHandler(
    private val panel: CheckinProjectPanel
) : CheckinHandler() {

    private val log = Logger.getInstance(CodyCommitMessageHandler::class.java)

    override fun getBeforeCheckinConfigurationPanel() = null

    /**
     * Generates a commit message using Cody with PSI-enriched context.
     * Called from the commit dialog when user clicks "Generate with Cody".
     */
    suspend fun generateMessage(): String? {
        val prompt = buildEnrichedPrompt()
        val diff = getDiff()
        val enrichedPrompt = "$prompt\n```diff\n$diff\n```"
        return try {
            com.workflow.orchestrator.cody.service.CodyChatService(panel.project)
                .generateCommitMessage(enrichedPrompt)
        } catch (e: Exception) {
            log.warn("Cody commit message generation failed: ${e.message}")
            null
        }
    }

    private suspend fun getDiff(): String {
        return try {
            val changes = panel.selectedChanges.toList()
            changes.joinToString("\n") { it.toString() }
        } catch (_: Exception) { "" }
    }

    internal suspend fun buildEnrichedPrompt(): String {
        val project = panel.project
        val changedFiles = panel.virtualFiles.toList()

        val psiEnricher = PsiContextEnricher(project)
        val fileContexts = changedFiles.mapNotNull { file ->
            try {
                val ctx = psiEnricher.enrich(file.path)
                if (ctx.className != null) {
                    "${ctx.className} (${ctx.classAnnotations.joinToString(", ") { "@$it" }})"
                } else null
            } catch (e: Exception) {
                log.debug("Failed to enrich ${file.name}: ${e.message}")
                null
            }
        }

        val modules = try {
            val mavenManager = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
            if (mavenManager.isMavenizedProject) {
                mavenManager.projects
                    .filter { mp -> changedFiles.any { VfsUtilCore.isAncestor(mp.directoryFile, it, false) } }
                    .map { it.mavenId.artifactId }
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        return buildString {
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
        }
    }
}
```

- [ ] **Step 2: Run cody tests**

Run: `./gradlew :cody:test -v`
Expected: All pass

- [ ] **Step 3: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/vcs/CodyCommitMessageHandlerFactory.kt
git commit -m "feat(cody): implement CodyCommitMessageHandler with PSI-enriched diff

Previously empty skeleton. Now builds semantic prompt including changed
class names, annotations, and affected Maven modules for Cody to
generate accurate conventional commit messages."
```

---

### Task 14: Implement CodyTestGenerator with Spring-Aware Patterns

**Files:**
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyTestGenerator.kt`

- [ ] **Step 1: Implement the test generator**

Replace `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyTestGenerator.kt`:

```kotlin
package com.workflow.orchestrator.cody.editor

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.workflow.orchestrator.cody.protocol.Position
import com.workflow.orchestrator.cody.protocol.Range
import com.workflow.orchestrator.cody.service.CodyContextService
import com.workflow.orchestrator.cody.service.CodyEditService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import com.workflow.orchestrator.sonar.service.SonarDataService
import kotlinx.coroutines.*

class CodyTestGenerator : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        if (elements.isEmpty()) return
        val file = elements.firstOrNull()?.containingFile ?: return
        val project = file.project

        val settings = PluginSettings.getInstance(project)
        if (settings.state.sourcegraphUrl.isNullOrBlank()) return
        if (settings.state.codyEnabled == false) return
        if (!CredentialStore().hasToken(ServiceType.SOURCEGRAPH)) return

        val virtualFile = file.virtualFile ?: return
        val basePath = project.basePath ?: return
        val relativePath = virtualFile.path.removePrefix("$basePath/")

        val state = try {
            SonarDataService.getInstance(project).stateFlow.value
        } catch (_: Exception) { return }

        val fileCoverage = state.fileCoverage[relativePath] ?: return

        for (element in elements) {
            if (element !is PsiIdentifier) continue
            val method = element.parent as? PsiMethod ?: continue

            val doc = file.viewProvider.document ?: continue
            val methodStartLine = doc.getLineNumber(method.textRange.startOffset) + 1
            val methodEndLine = doc.getLineNumber(method.textRange.endOffset) + 1

            val hasUncoveredLines = (methodStartLine..methodEndLine).any { line ->
                fileCoverage.lineStatuses[line] == LineCoverageStatus.UNCOVERED
            }

            if (!hasUncoveredLines) continue

            val marker = LineMarkerInfo(
                element,
                element.textRange,
                AllIcons.RunConfigurations.TestState.Run,
                { "Workflow: Cover with Cody" },
                { _, _ ->
                    val range = Range(
                        start = Position(line = methodStartLine - 1, character = 0),
                        end = Position(line = methodEndLine, character = 0)
                    )
                    @Suppress("DEPRECATION")
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        val contextService = project.service<CodyContextService>()
                        val testContext = contextService.gatherTestContext(
                            filePath = virtualFile.path,
                            targetRange = range
                        )
                        // Use enriched instruction (Spring-aware test patterns) via requestFix
                        // since requestTestGeneration doesn't accept custom instructions
                        CodyEditService(project).requestFix(
                            filePath = virtualFile.path,
                            range = range,
                            instruction = testContext.instruction,
                            contextFiles = testContext.contextFiles
                        )
                    }
                },
                GutterIconRenderer.Alignment.RIGHT,
                { "Workflow: Cover with Cody" }
            )
            result.add(marker)
        }
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :cody:test -v`
Expected: All pass

- [ ] **Step 3: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyTestGenerator.kt
git commit -m "feat(cody): implement CodyTestGenerator with Spring-aware test patterns

Previously empty skeleton. Now adds 'Cover with Cody' gutter markers
on uncovered methods (from SonarQube coverage data). Uses CodyContextService
to select Spring-aware test patterns (@WebMvcTest, @DataJpaTest, @MockBean)
based on bean type."
```

---

### Task 15: Upgrade PreReviewService with PSI-Annotated Diff

**Files:**
- Modify: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/PreReviewService.kt`

- [ ] **Step 1: Add buildEnrichedReviewPrompt method**

Add the following method to `PreReviewService` (after existing `buildReviewPrompt`):

```kotlin
    /**
     * Enhanced review prompt that includes PSI + Spring annotations for changed files.
     * Falls back to plain diff if PSI enrichment fails.
     */
    suspend fun buildEnrichedReviewPrompt(
        diff: String,
        changedFiles: List<com.intellij.openapi.vfs.VirtualFile>
    ): String {
        val proj = project ?: return buildReviewPrompt(diff)

        val psiEnricher = com.workflow.orchestrator.cody.service.PsiContextEnricher(proj)
        val springEnricher: com.workflow.orchestrator.cody.service.SpringContextEnricher = try {
            proj.getService(com.workflow.orchestrator.cody.service.SpringContextEnricher::class.java)
                ?: com.workflow.orchestrator.cody.service.SpringContextEnricher.EMPTY
        } catch (_: Exception) {
            com.workflow.orchestrator.cody.service.SpringContextEnricher.EMPTY
        }

        val fileAnnotations = changedFiles.mapNotNull { file ->
            try {
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
            } catch (_: Exception) { null }
        }

        return buildString {
            append("Analyze this Spring Boot code diff for anti-patterns, ")
            append("missing @Transactional annotations, incorrect bean scoping, ")
            append("and potential issues.\n")
            append("For each issue found, format as:\n")
            append("**SEVERITY** `file:line` — description [pattern-name]\n\n")
            if (fileAnnotations.isNotEmpty()) {
                append("## Changed Classes (IDE Analysis)\n")
                fileAnnotations.forEach { append("- $it\n") }
                append("\n")
            }
            append("## Diff\n```diff\n$diff\n```")
        }
    }
```

- [ ] **Step 2: Run handover tests**

Run: `./gradlew :handover:test -v`
Expected: All pass (new method doesn't affect existing tests)

- [ ] **Step 3: Commit**

```bash
git add handover/src/main/kotlin/com/workflow/orchestrator/handover/service/PreReviewService.kt
git commit -m "feat(handover): add PSI-annotated diff to PreReviewService

New buildEnrichedReviewPrompt() annotates the diff with class names,
Spring annotations, @Transactional methods, and REST endpoints from
PsiContextEnricher + SpringContextEnricher. Falls back to plain
buildReviewPrompt() when PSI unavailable."
```

---

## Chunk 5: Build Intelligence + Sonar/Copyright/Plan Fixes

---

### Task 16: Upgrade HealthCheckService — Smart Check Skipping

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckService.kt`

- [ ] **Step 1: Add ChangeClassification and classifyChanges()**

Add before `runChecks()` in `HealthCheckService.kt`:

```kotlin
    data class ChangeClassification(
        val hasProductionCode: Boolean,
        val hasTestCode: Boolean,
        val hasResources: Boolean,
        val hasBuildConfig: Boolean
    )

    fun classifyChanges(changedFiles: List<com.intellij.openapi.vfs.VirtualFile>): ChangeClassification {
        val fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
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
```

- [ ] **Step 2: Update runChecks to use classification when changedFiles available**

In `HealthCheckService.kt`, add a `changedFiles` field to `HealthCheckContext` (if not present) or add an overload. Update the check filtering logic:

After line `val enabledChecks = checks.filter { it.isEnabled(settings) }`, add:

```kotlin
        val checksToRun = if (context.changedFiles.isNotEmpty()) {
            val classification = classifyChanges(context.changedFiles)
            enabledChecks.filter { check ->
                when (check.id) {
                    "maven-compile" -> classification.hasProductionCode || classification.hasBuildConfig
                    "maven-test" -> classification.hasProductionCode || classification.hasTestCode || classification.hasBuildConfig
                    "copyright" -> classification.hasProductionCode
                    "sonar-gate" -> true
                    else -> true
                }
            }
        } else {
            enabledChecks
        }
```

Then use `checksToRun` instead of `enabledChecks` in the execution loop and event emission.

- [ ] **Step 3: Run core tests**

Run: `./gradlew :core:test -v`
Expected: All pass

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckService.kt
git commit -m "feat(core): smart health check skipping via ProjectFileIndex

Classifies changed files into production/test/resource/build categories
using ProjectFileIndex. Skips compile check for test-only changes,
skips copyright check for test/resource-only changes. Saves ~45s on
test-only pushes."
```

---

### Task 17: Fix SonarIssueAnnotator Path — Use VfsUtilCore

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/SonarIssueAnnotator.kt:30`

- [ ] **Step 1: Replace removePrefix with VfsUtilCore**

In `SonarIssueAnnotator.kt`, update `collectInformation()`:

```kotlin
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): SonarAnnotationInput? {
        val project = file.project
        val virtualFile = file.virtualFile ?: return null

        val baseDir = project.basePath?.let {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it)
        }
        val relativePath = if (baseDir != null) {
            com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(virtualFile, baseDir) ?: virtualFile.path
        } else {
            virtualFile.path
        }

        val state = try {
            SonarDataService.getInstance(project).stateFlow.value
        } catch (_: Exception) { return null }

        if (state.issues.none { it.filePath == relativePath }) return null

        return SonarAnnotationInput(relativePath, state)
    }
```

Remove the now-unused `val basePath = project.basePath ?: return null` line.

- [ ] **Step 2: Run sonar tests**

Run: `./gradlew :sonar:test -v`
Expected: All pass

- [ ] **Step 3: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/SonarIssueAnnotator.kt
git commit -m "fix(sonar): use VfsUtilCore for relative path in SonarIssueAnnotator

Replace string removePrefix with VfsUtilCore.getRelativePath() for
proper cross-platform path handling."
```

---

### Task 18: Fix CoverageLineMarkerProvider — VfsUtilCore + SVG Icons

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageLineMarkerProvider.kt`
- Create: `sonar/src/main/resources/icons/coverage-covered.svg`
- Create: `sonar/src/main/resources/icons/coverage-covered_dark.svg`
- Create: `sonar/src/main/resources/icons/coverage-uncovered.svg`
- Create: `sonar/src/main/resources/icons/coverage-uncovered_dark.svg`
- Create: `sonar/src/main/resources/icons/coverage-partial.svg`
- Create: `sonar/src/main/resources/icons/coverage-partial_dark.svg`

- [ ] **Step 1: Create SVG icon files**

Create `sonar/src/main/resources/icons/coverage-covered.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="6" height="14" viewBox="0 0 6 14">
  <rect x="1" y="0" width="4" height="14" rx="1" fill="#2EA043"/>
</svg>
```

Create `sonar/src/main/resources/icons/coverage-covered_dark.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="6" height="14" viewBox="0 0 6 14">
  <rect x="1" y="0" width="4" height="14" rx="1" fill="#3FB950"/>
</svg>
```

Create `sonar/src/main/resources/icons/coverage-uncovered.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="6" height="14" viewBox="0 0 6 14">
  <rect x="1" y="0" width="4" height="14" rx="1" fill="#888888"/>
</svg>
```

Create `sonar/src/main/resources/icons/coverage-uncovered_dark.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="6" height="14" viewBox="0 0 6 14">
  <rect x="1" y="0" width="4" height="14" rx="1" fill="#999999"/>
</svg>
```

Create `sonar/src/main/resources/icons/coverage-partial.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="6" height="14" viewBox="0 0 6 14">
  <rect x="1" y="0" width="4" height="14" rx="1" fill="#D4A020"/>
</svg>
```

Create `sonar/src/main/resources/icons/coverage-partial_dark.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="6" height="14" viewBox="0 0 6 14">
  <rect x="1" y="0" width="4" height="14" rx="1" fill="#E3B341"/>
</svg>
```

- [ ] **Step 2: Update CoverageLineMarkerProvider**

Replace `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageLineMarkerProvider.kt`:

```kotlin
package com.workflow.orchestrator.sonar.ui

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import com.workflow.orchestrator.sonar.model.SonarState
import com.workflow.orchestrator.sonar.service.SonarDataService
import javax.swing.Icon

class CoverageLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.parent !is PsiFile && element != element.parent?.firstChild) return null

        val project = element.project
        val file = element.containingFile?.virtualFile ?: return null

        val baseDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val relativePath = if (baseDir != null) {
            VfsUtilCore.getRelativePath(file, baseDir) ?: file.path
        } else {
            file.path
        }

        val state = getSonarState(project)
        val fileCoverage = state.fileCoverage[relativePath] ?: return null

        val doc = element.containingFile?.viewProvider?.document ?: return null
        val lineNumber = doc.getLineNumber(element.textRange.startOffset) + 1
        val lineStatus = fileCoverage.lineStatuses[lineNumber] ?: return null

        val (icon, tooltip) = when (lineStatus) {
            LineCoverageStatus.COVERED -> ICON_COVERED to "Line covered"
            LineCoverageStatus.UNCOVERED -> ICON_UNCOVERED to "Line not covered"
            LineCoverageStatus.PARTIAL -> ICON_PARTIAL to "Partially covered (some branches uncovered)"
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { tooltip },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { tooltip }
        )
    }

    private fun getSonarState(project: Project): SonarState {
        return try {
            SonarDataService.getInstance(project).stateFlow.value
        } catch (_: Exception) {
            SonarState.EMPTY
        }
    }

    companion object {
        private val ICON_COVERED: Icon = IconLoader.getIcon("/icons/coverage-covered.svg", CoverageLineMarkerProvider::class.java)
        private val ICON_UNCOVERED: Icon = IconLoader.getIcon("/icons/coverage-uncovered.svg", CoverageLineMarkerProvider::class.java)
        private val ICON_PARTIAL: Icon = IconLoader.getIcon("/icons/coverage-partial.svg", CoverageLineMarkerProvider::class.java)
    }
}
```

- [ ] **Step 3: Run sonar tests**

Run: `./gradlew :sonar:test -v`
Expected: All pass

- [ ] **Step 4: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageLineMarkerProvider.kt sonar/src/main/resources/icons/
git commit -m "fix(sonar): use VfsUtilCore + SVG icons in CoverageLineMarkerProvider

Replace removePrefix path with VfsUtilCore.getRelativePath().
Replace BufferedImage gutter icons with proper SVG files with
light/dark variants via IconLoader."
```

---

### Task 19: Fix CoverageTreeDecorator — Use ProjectFileIndex

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTreeDecorator.kt`

- [ ] **Step 1: Replace string matching with ProjectFileIndex**

In `CoverageTreeDecorator.kt`, update `decorate()`:

```kotlin
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project = node.project ?: return
        val file = node.virtualFile ?: return
        if (file.isDirectory) return

        val basePath = project.basePath ?: return

        val fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
        if (fileIndex.isInTestSourceContent(file)) return
        if (fileIndex.isInGeneratedSources(file)) return
        if (fileIndex.isExcluded(file)) return
        if (!fileIndex.isInSourceContent(file)) return

        val baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(basePath)
        val relativePath = if (baseDir != null) {
            com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, baseDir) ?: file.path
        } else {
            file.path.removePrefix("$basePath/")
        }

        val state = getSonarState(project)
        val coverage = state.fileCoverage[relativePath] ?: return

        val pct = coverage.lineCoverage
        val color = when {
            pct >= 80.0 -> JBColor(Color(46, 160, 67), Color(46, 160, 67))
            pct >= 50.0 -> JBColor(Color(212, 160, 32), Color(212, 160, 32))
            else -> JBColor(Color(255, 68, 68), Color(255, 68, 68))
        }

        data.addText(
            " ${"%.0f".format(pct)}%",
            SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, color)
        )
    }
```

- [ ] **Step 2: Run sonar tests**

Run: `./gradlew :sonar:test -v`
Expected: All pass

- [ ] **Step 3: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTreeDecorator.kt
git commit -m "fix(sonar): use ProjectFileIndex in CoverageTreeDecorator

Replace path.contains('/test/') string matching with
ProjectFileIndex.isInTestSourceContent(). Also excludes generated
and excluded files properly."
```

---

### Task 20: Upgrade CopyrightCheckService — FileTypeRegistry + Document API

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/copyright/CopyrightCheckService.kt`

- [ ] **Step 1: Update isSourceFile and content reading**

Replace `core/src/main/kotlin/com/workflow/orchestrator/core/copyright/CopyrightCheckService.kt`:

```kotlin
package com.workflow.orchestrator.core.copyright

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.settings.PluginSettings

class CopyrightCheckService(private val project: Project) {

    fun checkFiles(files: List<VirtualFile>): CopyrightCheckResult {
        val settings = PluginSettings.getInstance(project).state
        val pattern = settings.copyrightHeaderPattern
        if (pattern.isNullOrBlank()) return CopyrightCheckResult(emptyList())

        val regex = try {
            Regex(pattern)
        } catch (_: java.util.regex.PatternSyntaxException) {
            return CopyrightCheckResult(emptyList())
        }
        val violations = mutableListOf<CopyrightViolation>()

        for (file in files) {
            if (!isSourceFile(file)) continue
            val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
            val content = document.text
            val headerLines = content.lines().take(10).joinToString("\n")
            if (!regex.containsMatchIn(headerLines)) {
                violations.add(CopyrightViolation(file, "Missing copyright header"))
            }
        }

        return CopyrightCheckResult(violations)
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        val fileType = FileTypeRegistry.getInstance().getFileTypeByFile(file)
        if (fileType.isBinary) return false
        val fileIndex = ProjectFileIndex.getInstance(project)
        return fileIndex.isInSourceContent(file) && !fileIndex.isInGeneratedSources(file)
    }
}

data class CopyrightCheckResult(val violations: List<CopyrightViolation>) {
    val passed: Boolean get() = violations.isEmpty()
}

data class CopyrightViolation(
    val file: VirtualFile,
    val reason: String
)
```

- [ ] **Step 2: Run core tests**

Run: `./gradlew :core:test -v`
Expected: All pass

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/copyright/CopyrightCheckService.kt
git commit -m "fix(core): use FileTypeRegistry + Document API in CopyrightCheckService

Replace hardcoded extension set with FileTypeRegistry.isBinary +
ProjectFileIndex.isInSourceContent. Replace raw byte reading with
FileDocumentManager for proper encoding handling."
```

---

### Task 21: Upgrade CopyrightFixService — LanguageCommenters + ProjectFileIndex

**Files:**
- Modify: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/CopyrightFixService.kt`

- [ ] **Step 1: Update wrapForLanguage, isSourceFile, isGeneratedPath**

In `CopyrightFixService.kt`, make these changes:

**Replace `wrapForLanguage`** (lines 56-63):
```kotlin
    fun wrapForLanguage(template: String, file: com.intellij.openapi.vfs.VirtualFile): String {
        val fileType = com.intellij.openapi.fileTypes.FileTypeRegistry.getInstance().getFileTypeByFile(file)
        val language = (fileType as? com.intellij.openapi.fileTypes.LanguageFileType)?.language ?: return template
        val commenter = com.intellij.lang.LanguageCommenters.INSTANCE.forLanguage(language) ?: return template

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

    /** @deprecated Use wrapForLanguage(template, VirtualFile) instead */
    @Deprecated("Use VirtualFile overload", ReplaceWith("wrapForLanguage(template, file)"))
    fun wrapForLanguage(template: String, fileExtension: String): String {
        return when (fileExtension) {
            "java", "kt", "kts" -> "/*\n${template.lines().joinToString("\n") { " * $it" }}\n */"
            "xml" -> "<!--\n$template\n-->"
            "properties", "yaml", "yml" -> template.lines().joinToString("\n") { "# $it" }
            else -> template
        }
    }
```

**Replace `isSourceFile`** (lines 74-77):
```kotlin
    fun isSourceFile(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
        val fileType = com.intellij.openapi.fileTypes.FileTypeRegistry.getInstance().getFileTypeByFile(file)
        return !fileType.isBinary
    }

    /** @deprecated Use isSourceFile(VirtualFile) instead */
    @Deprecated("Use VirtualFile overload", ReplaceWith("isSourceFile(file)"))
    fun isSourceFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        return extension in SOURCE_EXTENSIONS
    }
```

**Replace `isGeneratedPath`** (lines 79-82):
```kotlin
    fun isGeneratedFile(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
        val proj = project ?: return false
        val fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(proj)
        return fileIndex.isInGeneratedSources(file) || fileIndex.isExcluded(file)
    }

    /** @deprecated Use isGeneratedFile(VirtualFile) instead */
    @Deprecated("Use VirtualFile overload", ReplaceWith("isGeneratedFile(file)"))
    fun isGeneratedPath(filePath: String): Boolean {
        val normalized = filePath.replace('\\', '/')
        return GENERATED_PATH_PREFIXES.any { normalized.startsWith(it) }
    }
```

- [ ] **Step 2: Run handover tests**

Run: `./gradlew :handover:test -v`
Expected: All pass (deprecated methods still exist for backward compat)

- [ ] **Step 3: Commit**

```bash
git add handover/src/main/kotlin/com/workflow/orchestrator/handover/service/CopyrightFixService.kt
git commit -m "feat(handover): use LanguageCommenters + ProjectFileIndex in CopyrightFixService

wrapForLanguage now uses LanguageCommenters for language-aware comment
wrapping. isSourceFile uses FileTypeRegistry. isGeneratedFile uses
ProjectFileIndex. Old string-based methods preserved as @Deprecated
for one release cycle."
```

---

### Task 22: Upgrade PlanDetectionService — SnakeYAML Parsing

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt`

- [ ] **Step 1: Replace extractRepoUrls with SnakeYAML**

In `PlanDetectionService.kt`, update the companion object:

```kotlin
    companion object {
        private val URL_REGEX = Regex("""url:\s+(.+)""")

        fun normalizeRepoUrl(url: String): String {
            var normalized = url.trim()
            normalized = normalized.removeSuffix(".git")
            normalized = normalized.replace(Regex("""^(https?|ssh|git)://"""), "")
            normalized = normalized.replace(Regex("""^git@([^:]+):"""), "$1/")
            normalized = normalized.trimEnd('/')
            return normalized
        }

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
    }
```

- [ ] **Step 2: Run bamboo tests**

Run: `./gradlew :bamboo:test -v`
Expected: All pass (existing extractRepoUrls tests work because SnakeYAML handles simple cases, and regex fallback handles edge cases)

- [ ] **Step 3: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt
git commit -m "feat(bamboo): use SnakeYAML for Bamboo specs YAML parsing

Replace URL_REGEX.findAll with proper YAML tree traversal via SnakeYAML.
Falls back to regex if YAML parsing fails. Handles quoted values,
nested structures, and YAML anchors correctly."
```

---

### Task 23: Upgrade PrService — Module/Endpoint-Aware Descriptions

**Files:**
- Modify: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/PrService.kt`

- [ ] **Step 1: Add buildEnrichedDescription method**

Add to `PrService.kt`:

```kotlin
    /**
     * Generates enriched PR description with Maven modules and REST endpoint info.
     * Falls back to buildFallbackDescription() when PSI unavailable.
     */
    suspend fun buildEnrichedDescription(
        ticketId: String,
        ticketSummary: String,
        branchName: String,
        changedFiles: List<com.intellij.openapi.vfs.VirtualFile>
    ): String {
        val proj = project ?: return buildFallbackDescription(ticketId, ticketSummary, branchName)
        if (changedFiles.isEmpty()) return buildFallbackDescription(ticketId, ticketSummary, branchName)

        val psiEnricher = com.workflow.orchestrator.cody.service.PsiContextEnricher(proj)

        val modules = try {
            val mavenManager = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(proj)
            if (mavenManager.isMavenizedProject) {
                mavenManager.projects
                    .filter { mp -> changedFiles.any { com.intellij.openapi.vfs.VfsUtilCore.isAncestor(mp.directoryFile, it, false) } }
                    .map { it.mavenId.artifactId }
            } else emptyList()
        } catch (_: Exception) { emptyList() }

        val endpoints = mutableListOf<String>()
        for (file in changedFiles) {
            try {
                val psi = psiEnricher.enrich(file.path)
                val isController = psi.classAnnotations.any {
                    it in listOf("RestController", "Controller")
                }
                if (isController) {
                    endpoints.add("${file.nameWithoutExtension} (${psi.classAnnotations.joinToString(", ") { "@$it" }})")
                }
            } catch (_: Exception) { /* skip */ }
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
            append("**Branch:** $branchName\n")
        }
    }
```

- [ ] **Step 2: Run handover tests**

Run: `./gradlew :handover:test -v`
Expected: All pass

- [ ] **Step 3: Commit**

```bash
git add handover/src/main/kotlin/com/workflow/orchestrator/handover/service/PrService.kt
git commit -m "feat(handover): add module/endpoint-aware PR descriptions in PrService

New buildEnrichedDescription() includes affected Maven modules and
REST controller endpoints from PSI context. Falls back to
buildFallbackDescription() when PSI unavailable."
```

---

### Task 25: B3 — Add contextFiles to CodyChatService

**Files:**
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyChatService.kt` (or equivalent agent chat service)

- [ ] **Step 1: Verify ChatMessage DTO has contextFiles field**

Check `cody/src/main/kotlin/com/workflow/orchestrator/cody/protocol/ChatModels.kt` — the `ChatMessage` data class already has `contextFiles: List<ContextFile> = emptyList()`. No DTO change needed.

- [ ] **Step 2: Update CodyChatService to accept and pass contextFiles**

In `CodyChatService`, find the method that sends chat messages (e.g., `generateCommitMessage`, `submitMessage`) and update to pass contextFiles:

```kotlin
// Before — contextFiles not passed
val response = server.chatSubmitMessage(
    ChatSubmitParams(id = chatId, message = ChatMessage(text = prompt))
)

// After — pass contextFiles from changed files
suspend fun generateCommitMessage(
    prompt: String,
    contextFiles: List<ContextFile> = emptyList()
): String {
    val server = providerService().ensureRunning()
    val chatId = server.chatNew().await()
    val response = server.chatSubmitMessage(
        ChatSubmitParams(
            id = chatId,
            message = ChatMessage(
                text = prompt,
                contextFiles = contextFiles
            )
        )
    ).await()
    return response.text
}
```

- [ ] **Step 3: Update CodyCommitMessageHandler to pass changed files as contextFiles**

In the `generateMessage()` method of `CodyCommitMessageHandler` (Task 13), update the chat service call:

```kotlin
val changedFiles = panel.virtualFiles.toList()
return try {
    com.workflow.orchestrator.cody.service.CodyChatService(panel.project)
        .generateCommitMessage(
            prompt = enrichedPrompt,
            contextFiles = changedFiles.map {
                com.workflow.orchestrator.cody.protocol.ContextFile(uri = it.path)
            }
        )
} catch (e: Exception) {
    log.warn("Cody commit message generation failed: ${e.message}")
    null
}
```

- [ ] **Step 4: Run cody tests**

Run: `./gradlew :cody:test -v`
Expected: All pass

- [ ] **Step 5: Commit**

```bash
git add cody/src/main/kotlin/com/workflow/orchestrator/cody/service/CodyChatService.kt cody/src/main/kotlin/com/workflow/orchestrator/cody/vcs/CodyCommitMessageHandlerFactory.kt
git commit -m "feat(cody): pass contextFiles in CodyChatService ChatMessage

Add contextFiles parameter to generateCommitMessage(). Changed files
are now sent as ContextFile entries alongside the prompt text, giving
the Cody Agent direct access to file contents for better responses."
```

---

### Task 26: B8 — Upgrade MavenBuildService to MavenRunner

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenBuildService.kt`

- [ ] **Step 1: Add MavenRunner-based build method**

Add a new method `runMavenBuildViaRunner` to `MavenBuildService.kt`, keeping the existing subprocess method as fallback:

```kotlin
    /**
     * Primary path: uses IntelliJ's MavenRunner for IDE-integrated builds.
     * Reuses IDE's Maven installation, settings, and local repository cache.
     */
    suspend fun runBuildViaRunner(
        goals: String,
        modules: List<String> = emptyList()
    ): MavenBuildResult = withContext(Dispatchers.IO) {
        val mavenManager = try {
            org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
                .takeIf { it.isMavenizedProject }
        } catch (_: Exception) { null }

        if (mavenManager == null) {
            return@withContext runBuild(goals, modules)
        }

        try {
            val params = org.jetbrains.idea.maven.execution.MavenRunnerParameters(
                project.basePath!!,
                "pom.xml",
                emptyMap(),
                goals.split(" "),
                modules
            )

            val settings = org.jetbrains.idea.maven.execution.MavenRunner.getInstance(project).settings.clone()
            val startTime = System.currentTimeMillis()

            // MavenRunner.run() integrates with IntelliJ's build tool window
            val completionFuture = java.util.concurrent.CompletableFuture<Boolean>()
            org.jetbrains.idea.maven.execution.MavenRunner.getInstance(project).run(params, settings) {
                completionFuture.complete(true)
            }

            val timeoutMs = PluginSettings.getInstance(project).state.healthCheckTimeoutSeconds * 1000L
            val completed = try {
                completionFuture.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                false
            }

            MavenBuildResult(
                success = completed,
                exitCode = if (completed) 0 else -1,
                output = "Build executed via MavenRunner (IDE-integrated)",
                errors = "",
                timedOut = !completed
            )
        } catch (e: Exception) {
            // Fallback to subprocess if MavenRunner fails
            runBuild(goals, modules)
        }
    }
```

- [ ] **Step 2: Run core tests**

Run: `./gradlew :core:test -v`
Expected: All pass (new method doesn't affect existing tests)

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenBuildService.kt
git commit -m "feat(core): add MavenRunner-based build in MavenBuildService

New runBuildViaRunner() uses IntelliJ's MavenRunner for IDE-integrated
builds that reuse Maven settings and show in the build tool window.
Falls back to OSProcessHandler subprocess when MavenRunner unavailable."
```

---

### Task 27: B9 — SonarIssueAnnotator PsiElement Resolution for Rich Tooltips

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/SonarIssueAnnotator.kt`

- [ ] **Step 1: Add PsiElement resolution in apply() for richer tooltips**

In `SonarIssueAnnotator.kt`, update the `apply()` method to resolve PSI elements for method-level context:

```kotlin
    override fun apply(file: PsiFile, annotationResult: SonarAnnotationResult, holder: AnnotationHolder) {
        val doc = file.viewProvider.document ?: return
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(file.project)
            .selectedTextEditor

        for (issue in annotationResult.issues) {
            val startLine = (issue.startLine - 1).coerceIn(0, doc.lineCount - 1)
            val endLine = (issue.endLine - 1).coerceIn(0, doc.lineCount - 1)

            val startOffset = doc.getLineStartOffset(startLine) + issue.startOffset
            val endOffset = if (issue.endOffset > 0) {
                doc.getLineStartOffset(endLine) + issue.endOffset
            } else {
                doc.getLineEndOffset(endLine)
            }

            val textRange = TextRange(
                startOffset.coerceIn(0, doc.textLength),
                endOffset.coerceIn(0, doc.textLength)
            )

            if (textRange.isEmpty) continue

            val severity = mapSeverity(issue.type, issue.severity)

            // PSI resolution for richer tooltips
            val element = file.findElementAt(textRange.startOffset)
            val containingMethod = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                element, com.intellij.psi.PsiMethod::class.java
            )
            val containingClass = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                element, com.intellij.psi.PsiClass::class.java
            )

            val tooltip = buildString {
                append("[${issue.rule}] ${issue.message}")
                issue.effort?.let { append(" (effort: $it)") }
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

            holder.newAnnotation(severity, tooltip)
                .range(textRange)
                .tooltip(tooltip)
                .create()

            // Store issue in highlighter for CodyIntentionAction to retrieve
            if (editor != null) {
                val highlighters = editor.markupModel.allHighlighters
                    .filter { it.startOffset == textRange.startOffset && it.endOffset == textRange.endOffset }
                for (hl in highlighters) {
                    hl.putUserData(SONAR_ISSUE_KEY, issue)
                }
            }
        }
    }
```

- [ ] **Step 2: Run sonar tests**

Run: `./gradlew :sonar:test -v`
Expected: All pass

- [ ] **Step 3: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/SonarIssueAnnotator.kt
git commit -m "feat(sonar): add PsiElement resolution for rich Sonar tooltips

Tooltips now show containing method name and class annotations
(e.g. 'In method: getUser()' + 'Class: @Service, @Transactional UserService')
using PsiTreeUtil.getParentOfType() resolution."
```

---

### Task 28: B11 — CoverageLineMarkerProvider Spring-Aware Endpoint Highlighting

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageLineMarkerProvider.kt`
- Create: `sonar/src/main/resources/icons/coverage-endpoint-uncovered.svg`
- Create: `sonar/src/main/resources/icons/coverage-endpoint-uncovered_dark.svg`

- [ ] **Step 1: Create endpoint-uncovered SVG icons**

Create `sonar/src/main/resources/icons/coverage-endpoint-uncovered.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="6" height="14" viewBox="0 0 6 14">
  <rect x="1" y="0" width="4" height="14" rx="1" fill="#FF4444"/>
</svg>
```

Create `sonar/src/main/resources/icons/coverage-endpoint-uncovered_dark.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="6" height="14" viewBox="0 0 6 14">
  <rect x="1" y="0" width="4" height="14" rx="1" fill="#FF6666"/>
</svg>
```

- [ ] **Step 2: Add Spring-aware endpoint detection to CoverageLineMarkerProvider**

In the already-rewritten `CoverageLineMarkerProvider.kt` (from Task 18), update the `getLineMarkerInfo` method to detect uncovered `@RequestMapping` methods:

Add after the `lineStatus` check and before creating `LineMarkerInfo`:

```kotlin
        // Spring-aware: highlight uncovered @RequestMapping endpoints more urgently
        val isEndpoint = if (lineStatus == LineCoverageStatus.UNCOVERED) {
            val containingMethod = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                element, com.intellij.psi.PsiMethod::class.java
            )
            containingMethod?.annotations?.any {
                it.qualifiedName in REQUEST_MAPPING_ANNOTATIONS
            } ?: false
        } else false

        val (icon, tooltip) = when {
            lineStatus == LineCoverageStatus.COVERED -> ICON_COVERED to "Line covered"
            lineStatus == LineCoverageStatus.UNCOVERED && isEndpoint ->
                ICON_ENDPOINT_UNCOVERED to "UNCOVERED REST endpoint — high priority"
            lineStatus == LineCoverageStatus.UNCOVERED -> ICON_UNCOVERED to "Line not covered"
            else -> ICON_PARTIAL to "Partially covered (some branches uncovered)"
        }
```

Add to the companion object:
```kotlin
    companion object {
        private val ICON_COVERED: Icon = IconLoader.getIcon("/icons/coverage-covered.svg", CoverageLineMarkerProvider::class.java)
        private val ICON_UNCOVERED: Icon = IconLoader.getIcon("/icons/coverage-uncovered.svg", CoverageLineMarkerProvider::class.java)
        private val ICON_PARTIAL: Icon = IconLoader.getIcon("/icons/coverage-partial.svg", CoverageLineMarkerProvider::class.java)
        private val ICON_ENDPOINT_UNCOVERED: Icon = IconLoader.getIcon("/icons/coverage-endpoint-uncovered.svg", CoverageLineMarkerProvider::class.java)

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

- [ ] **Step 3: Run sonar tests**

Run: `./gradlew :sonar:test -v`
Expected: All pass

- [ ] **Step 4: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageLineMarkerProvider.kt sonar/src/main/resources/icons/coverage-endpoint-uncovered.svg sonar/src/main/resources/icons/coverage-endpoint-uncovered_dark.svg
git commit -m "feat(sonar): Spring-aware endpoint highlighting in coverage markers

Uncovered lines inside @RequestMapping methods get a distinct red icon
(coverage-endpoint-uncovered.svg) and 'high priority' tooltip. Regular
uncovered lines keep the grey icon. Uses PsiTreeUtil to detect
@GetMapping/@PostMapping/etc. annotations on containing method."
```

---

### Task 29: Final Verification — All 19 Features

- [ ] **Step 1: Run all module tests**

```bash
./gradlew :core:test :cody:test :sonar:test :handover:test :bamboo:test -v
```
Expected: All pass

- [ ] **Step 2: Verify plugin**

```bash
./gradlew verifyPlugin
```
Expected: No API compatibility issues

- [ ] **Step 3: Build plugin**

```bash
./gradlew buildPlugin
```
Expected: BUILD SUCCESSFUL, ZIP produced

- [ ] **Step 4: Run IDE smoke test**

```bash
./gradlew runIde
```

Manual checks:
- Plugin loads without errors in IDE log
- "Workflow" tool window appears with 5 tabs
- Settings > Tools > Workflow Orchestrator accessible
- No `ClassNotFoundException` in IDE log

- [ ] **Step 5: Final commit (if any remaining changes)**

```bash
git status
# If clean, no commit needed
# If changes found, commit them with appropriate message
```
