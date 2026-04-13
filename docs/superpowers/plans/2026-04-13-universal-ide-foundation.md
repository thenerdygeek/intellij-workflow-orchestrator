# Universal IDE Foundation — Implementation Plan (Plan A)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the infrastructure that enables the plugin to install and run on any JetBrains IDE (PyCharm, WebStorm, GoLand, etc.), with context-aware tool registration that filters tools based on IDE product, edition, and detected project frameworks.

**Architecture:** Three new classes (`IdeContext`, `IdeContextDetector`, `ProjectScanner`) detect the runtime environment. The plugin descriptor changes `com.intellij.modules.java` from required to optional, with Java-specific extensions moved to a conditional XML. `AgentService.registerAllTools()` uses `IdeContext` to filter which tools are registered — tools that can't work in the current environment are never registered, so the LLM never sees them.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform SDK (`ApplicationInfo`, `PluginManager`, VFS), JUnit 5 + MockK

**Depends on:** Tooling Architecture Enhancements (completed on this branch)

**Design Spec:** `docs/superpowers/specs/2026-04-13-universal-ide-support-design.md`

---

## File Map

| Action | File | Purpose |
|---|---|---|
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContext.kt` | Data class + enums for IDE environment |
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContextDetector.kt` | Detects IDE product, edition, available plugins |
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/ProjectScanner.kt` | Detects frameworks and build tools by scanning project files |
| Create | `agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/IdeContextDetectorTest.kt` | Unit tests for IDE detection |
| Create | `agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/ProjectScannerTest.kt` | Unit tests for framework/build tool detection |
| Create | `agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/ContextAwareRegistrationTest.kt` | Tests for filtered tool registration |
| Create | `src/main/resources/META-INF/plugin-withJava.xml` | Java-specific extensions (Sonar line markers, annotators, intention actions) |
| Create | `src/main/resources/META-INF/plugin-withPython.xml` | Stub for Python Professional plugin extensions (future) |
| Create | `src/main/resources/META-INF/plugin-withPythonCore.xml` | Stub for Python Community plugin extensions (future) |
| Modify | `src/main/resources/META-INF/plugin.xml` | Make Java optional, add Python optional deps, remove moved extensions |
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` | Use IdeContext for filtered tool registration |
| Modify | `agent/build.gradle.kts` | Remove MySQL JDBC driver |
| Modify | `gradle/libs.versions.toml` | Remove MySQL version entry |

---

## Task 1: IdeContext Data Model

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContext.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContextDetector.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/IdeContextDetectorTest.kt`

- [ ] **Step 1: Write failing tests for IdeContextDetector**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/IdeContextDetectorTest.kt
package com.workflow.orchestrator.agent.ide

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class IdeContextDetectorTest {

    @Test
    fun `classifyProduct returns INTELLIJ_ULTIMATE for IU code`() {
        val result = IdeContextDetector.classifyProduct("IU")
        assertEquals(IdeProduct.INTELLIJ_ULTIMATE, result)
    }

    @Test
    fun `classifyProduct returns INTELLIJ_COMMUNITY for IC code`() {
        val result = IdeContextDetector.classifyProduct("IC")
        assertEquals(IdeProduct.INTELLIJ_COMMUNITY, result)
    }

    @Test
    fun `classifyProduct returns PYCHARM_PROFESSIONAL for PY code`() {
        val result = IdeContextDetector.classifyProduct("PY")
        assertEquals(IdeProduct.PYCHARM_PROFESSIONAL, result)
    }

    @Test
    fun `classifyProduct returns PYCHARM_COMMUNITY for PC code`() {
        val result = IdeContextDetector.classifyProduct("PC")
        assertEquals(IdeProduct.PYCHARM_COMMUNITY, result)
    }

    @Test
    fun `classifyProduct returns OTHER for unknown code`() {
        val result = IdeContextDetector.classifyProduct("WS")
        assertEquals(IdeProduct.OTHER, result)
    }

    @Test
    fun `classifyProduct returns OTHER for GoLand`() {
        val result = IdeContextDetector.classifyProduct("GO")
        assertEquals(IdeProduct.OTHER, result)
    }

    @Test
    fun `classifyEdition returns ULTIMATE for IU`() {
        val result = IdeContextDetector.classifyEdition(IdeProduct.INTELLIJ_ULTIMATE)
        assertEquals(Edition.ULTIMATE, result)
    }

    @Test
    fun `classifyEdition returns COMMUNITY for IC`() {
        val result = IdeContextDetector.classifyEdition(IdeProduct.INTELLIJ_COMMUNITY)
        assertEquals(Edition.COMMUNITY, result)
    }

    @Test
    fun `classifyEdition returns PROFESSIONAL for PY`() {
        val result = IdeContextDetector.classifyEdition(IdeProduct.PYCHARM_PROFESSIONAL)
        assertEquals(Edition.PROFESSIONAL, result)
    }

    @Test
    fun `classifyEdition returns COMMUNITY for PC`() {
        val result = IdeContextDetector.classifyEdition(IdeProduct.PYCHARM_COMMUNITY)
        assertEquals(Edition.COMMUNITY, result)
    }

    @Test
    fun `classifyEdition returns OTHER for unknown products`() {
        val result = IdeContextDetector.classifyEdition(IdeProduct.OTHER)
        assertEquals(Edition.OTHER, result)
    }

    @Test
    fun `deriveLanguages returns JAVA and KOTLIN for IntelliJ products`() {
        val result = IdeContextDetector.deriveLanguages(
            IdeProduct.INTELLIJ_ULTIMATE,
            hasJavaPlugin = true,
            hasPythonPlugin = false
        )
        assertEquals(setOf(Language.JAVA, Language.KOTLIN), result)
    }

    @Test
    fun `deriveLanguages returns PYTHON for PyCharm products`() {
        val result = IdeContextDetector.deriveLanguages(
            IdeProduct.PYCHARM_PROFESSIONAL,
            hasJavaPlugin = false,
            hasPythonPlugin = true
        )
        assertEquals(setOf(Language.PYTHON), result)
    }

    @Test
    fun `deriveLanguages returns JAVA KOTLIN and PYTHON for IU with Python plugin`() {
        val result = IdeContextDetector.deriveLanguages(
            IdeProduct.INTELLIJ_ULTIMATE,
            hasJavaPlugin = true,
            hasPythonPlugin = true
        )
        assertEquals(setOf(Language.JAVA, Language.KOTLIN, Language.PYTHON), result)
    }

    @Test
    fun `deriveLanguages returns empty set for OTHER with no plugins`() {
        val result = IdeContextDetector.deriveLanguages(
            IdeProduct.OTHER,
            hasJavaPlugin = false,
            hasPythonPlugin = false
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `IdeContext summary for IntelliJ Ultimate`() {
        val context = IdeContext(
            product = IdeProduct.INTELLIJ_ULTIMATE,
            productName = "IntelliJ IDEA Ultimate",
            edition = Edition.ULTIMATE,
            languages = setOf(Language.JAVA, Language.KOTLIN),
            hasJavaPlugin = true,
            hasPythonPlugin = false,
            hasPythonCorePlugin = false,
            hasSpringPlugin = true,
            detectedFrameworks = setOf(Framework.SPRING),
            detectedBuildTools = setOf(BuildTool.GRADLE),
        )
        val summary = context.summary()
        assertTrue(summary.contains("IntelliJ IDEA Ultimate"))
        assertTrue(summary.contains("Java"))
        assertTrue(summary.contains("Spring"))
        assertTrue(summary.contains("Gradle"))
    }

    @Test
    fun `IdeContext summary for PyCharm Community`() {
        val context = IdeContext(
            product = IdeProduct.PYCHARM_COMMUNITY,
            productName = "PyCharm Community",
            edition = Edition.COMMUNITY,
            languages = setOf(Language.PYTHON),
            hasJavaPlugin = false,
            hasPythonPlugin = false,
            hasPythonCorePlugin = true,
            hasSpringPlugin = false,
            detectedFrameworks = setOf(Framework.DJANGO),
            detectedBuildTools = setOf(BuildTool.POETRY),
        )
        val summary = context.summary()
        assertTrue(summary.contains("PyCharm Community"))
        assertTrue(summary.contains("Python"))
        assertTrue(summary.contains("Django"))
        assertTrue(summary.contains("poetry"))
    }

    @Test
    fun `IdeContext supportsJava returns true for IntelliJ`() {
        val context = IdeContext(
            product = IdeProduct.INTELLIJ_COMMUNITY,
            productName = "IntelliJ IDEA Community",
            edition = Edition.COMMUNITY,
            languages = setOf(Language.JAVA, Language.KOTLIN),
            hasJavaPlugin = true,
            hasPythonPlugin = false,
            hasPythonCorePlugin = false,
            hasSpringPlugin = false,
            detectedFrameworks = emptySet(),
            detectedBuildTools = setOf(BuildTool.MAVEN),
        )
        assertTrue(context.supportsJava)
        assertFalse(context.supportsPython)
    }

    @Test
    fun `IdeContext supportsPython returns true for PyCharm`() {
        val context = IdeContext(
            product = IdeProduct.PYCHARM_PROFESSIONAL,
            productName = "PyCharm Professional",
            edition = Edition.PROFESSIONAL,
            languages = setOf(Language.PYTHON),
            hasJavaPlugin = false,
            hasPythonPlugin = true,
            hasPythonCorePlugin = false,
            hasSpringPlugin = false,
            detectedFrameworks = emptySet(),
            detectedBuildTools = setOf(BuildTool.PIP),
        )
        assertFalse(context.supportsJava)
        assertTrue(context.supportsPython)
        assertTrue(context.supportsPythonAdvanced)
    }

    @Test
    fun `IdeContext supportsPythonAdvanced false for Community Python`() {
        val context = IdeContext(
            product = IdeProduct.PYCHARM_COMMUNITY,
            productName = "PyCharm Community",
            edition = Edition.COMMUNITY,
            languages = setOf(Language.PYTHON),
            hasJavaPlugin = false,
            hasPythonPlugin = false,
            hasPythonCorePlugin = true,
            hasSpringPlugin = false,
            detectedFrameworks = emptySet(),
            detectedBuildTools = emptySet(),
        )
        assertTrue(context.supportsPython)
        assertFalse(context.supportsPythonAdvanced)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*IdeContextDetectorTest*" -v`
Expected: FAIL — classes not found

- [ ] **Step 3: Implement IdeContext data class and enums**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContext.kt
package com.workflow.orchestrator.agent.ide

enum class IdeProduct {
    INTELLIJ_ULTIMATE,
    INTELLIJ_COMMUNITY,
    PYCHARM_PROFESSIONAL,
    PYCHARM_COMMUNITY,
    OTHER
}

enum class Edition { COMMUNITY, PROFESSIONAL, ULTIMATE, OTHER }
enum class Language { JAVA, KOTLIN, PYTHON }
enum class Framework { SPRING, DJANGO, FASTAPI, FLASK }
enum class BuildTool { MAVEN, GRADLE, PIP, POETRY, UV }

data class IdeContext(
    val product: IdeProduct,
    val productName: String,
    val edition: Edition,
    val languages: Set<Language>,
    val hasJavaPlugin: Boolean,
    val hasPythonPlugin: Boolean,
    val hasPythonCorePlugin: Boolean,
    val hasSpringPlugin: Boolean,
    val detectedFrameworks: Set<Framework>,
    val detectedBuildTools: Set<BuildTool>,
) {
    val supportsJava: Boolean
        get() = Language.JAVA in languages

    val supportsPython: Boolean
        get() = Language.PYTHON in languages

    /** True when full Python plugin is available (Professional/Ultimate features) */
    val supportsPythonAdvanced: Boolean
        get() = hasPythonPlugin

    val supportsSpring: Boolean
        get() = hasSpringPlugin && supportsJava

    fun summary(): String = buildString {
        append("You are running in $productName.")
        if (languages.isNotEmpty()) {
            append("\nAvailable languages: ${languages.joinToString { it.name.lowercase().replaceFirstChar(Char::uppercase) }}.")
        }
        if (detectedFrameworks.isNotEmpty()) {
            append("\nDetected frameworks: ${detectedFrameworks.joinToString { it.name.lowercase().replaceFirstChar(Char::uppercase) }}.")
        }
        if (detectedBuildTools.isNotEmpty()) {
            append("\nBuild tools: ${detectedBuildTools.joinToString { it.name.lowercase() }}.")
        }
    }
}
```

- [ ] **Step 4: Implement IdeContextDetector**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContextDetector.kt
package com.workflow.orchestrator.agent.ide

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project

object IdeContextDetector {

    private const val PYTHON_PRO_PLUGIN_ID = "com.intellij.python"
    private const val PYTHON_CORE_PLUGIN_ID = "PythonCore"
    private const val SPRING_PLUGIN_ID = "com.intellij.spring"
    private const val JAVA_PLUGIN_ID = "com.intellij.java"

    fun detect(project: Project): IdeContext {
        val appInfo = ApplicationInfo.getInstance()
        val productCode = appInfo.build.productCode
        val product = classifyProduct(productCode)
        val edition = classifyEdition(product)

        val hasJava = isPluginInstalled(JAVA_PLUGIN_ID)
        val hasPythonPro = isPluginInstalled(PYTHON_PRO_PLUGIN_ID)
        val hasPythonCore = isPluginInstalled(PYTHON_CORE_PLUGIN_ID)
        val hasSpring = isPluginInstalled(SPRING_PLUGIN_ID)

        val languages = deriveLanguages(product, hasJava, hasPythonPro || hasPythonCore)
        val frameworks = ProjectScanner.detectFrameworks(project)
        val buildTools = ProjectScanner.detectBuildTools(project)

        return IdeContext(
            product = product,
            productName = appInfo.fullApplicationName,
            edition = edition,
            languages = languages,
            hasJavaPlugin = hasJava,
            hasPythonPlugin = hasPythonPro,
            hasPythonCorePlugin = hasPythonCore,
            hasSpringPlugin = hasSpring,
            detectedFrameworks = frameworks,
            detectedBuildTools = buildTools,
        )
    }

    fun classifyProduct(productCode: String): IdeProduct = when (productCode) {
        "IU" -> IdeProduct.INTELLIJ_ULTIMATE
        "IC" -> IdeProduct.INTELLIJ_COMMUNITY
        "PY" -> IdeProduct.PYCHARM_PROFESSIONAL
        "PC" -> IdeProduct.PYCHARM_COMMUNITY
        else -> IdeProduct.OTHER
    }

    fun classifyEdition(product: IdeProduct): Edition = when (product) {
        IdeProduct.INTELLIJ_ULTIMATE -> Edition.ULTIMATE
        IdeProduct.INTELLIJ_COMMUNITY -> Edition.COMMUNITY
        IdeProduct.PYCHARM_PROFESSIONAL -> Edition.PROFESSIONAL
        IdeProduct.PYCHARM_COMMUNITY -> Edition.COMMUNITY
        IdeProduct.OTHER -> Edition.OTHER
    }

    fun deriveLanguages(
        product: IdeProduct,
        hasJavaPlugin: Boolean,
        hasPythonPlugin: Boolean,
    ): Set<Language> {
        val result = mutableSetOf<Language>()
        if (hasJavaPlugin) {
            result.add(Language.JAVA)
            result.add(Language.KOTLIN)
        }
        if (hasPythonPlugin) {
            result.add(Language.PYTHON)
        }
        return result
    }

    private fun isPluginInstalled(pluginId: String): Boolean =
        PluginManagerCore.getPlugin(PluginId.getId(pluginId))?.isEnabled == true
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*IdeContextDetectorTest*" -v`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContext.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContextDetector.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/IdeContextDetectorTest.kt
git commit -m "feat(agent): add IdeContext data model and detector

Introduce IdeContext data class with IDE product, edition, language,
framework, and build tool detection. IdeContextDetector classifies the
runtime environment from ApplicationInfo and PluginManager."
```

---

## Task 2: ProjectScanner — Framework and Build Tool Detection

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/ProjectScanner.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/ProjectScannerTest.kt`

- [ ] **Step 1: Write failing tests for ProjectScanner**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/ProjectScannerTest.kt
package com.workflow.orchestrator.agent.ide

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.io.path.createDirectories

class ProjectScannerTest {

    @TempDir
    lateinit var projectRoot: Path

    // --- Framework Detection ---

    @Test
    fun `detectFrameworksFromPath finds Django when manage_py and requirements exist`() {
        projectRoot.resolve("manage.py").createFile()
        projectRoot.resolve("requirements.txt").writeText("django==5.1\ncelery==5.4\n")

        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(Framework.DJANGO in result)
    }

    @Test
    fun `detectFrameworksFromPath does not detect Django without manage_py`() {
        projectRoot.resolve("requirements.txt").writeText("django==5.1\n")

        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertFalse(Framework.DJANGO in result)
    }

    @Test
    fun `detectFrameworksFromPath finds FastAPI from requirements_txt`() {
        projectRoot.resolve("requirements.txt").writeText("fastapi==0.115\nuvicorn==0.30\n")

        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(Framework.FASTAPI in result)
    }

    @Test
    fun `detectFrameworksFromPath finds Flask from requirements_txt`() {
        projectRoot.resolve("requirements.txt").writeText("flask==3.1\n")

        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(Framework.FLASK in result)
    }

    @Test
    fun `detectFrameworksFromPath finds Django from pyproject_toml poetry`() {
        projectRoot.resolve("manage.py").createFile()
        projectRoot.resolve("pyproject.toml").writeText("""
            [tool.poetry.dependencies]
            python = "^3.12"
            django = "^5.1"
        """.trimIndent())

        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(Framework.DJANGO in result)
    }

    @Test
    fun `detectFrameworksFromPath finds FastAPI from pyproject_toml uv`() {
        projectRoot.resolve("pyproject.toml").writeText("""
            [project]
            dependencies = ["fastapi>=0.115", "uvicorn"]
            
            [tool.uv]
            dev-dependencies = ["pytest"]
        """.trimIndent())

        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(Framework.FASTAPI in result)
    }

    @Test
    fun `detectFrameworksFromPath finds Spring — always detected by Spring plugin not file scan`() {
        // Spring detection relies on PluginManager, not file scanning
        // ProjectScanner never returns SPRING — that's handled by IdeContextDetector.hasSpringPlugin
        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertFalse(Framework.SPRING in result)
    }

    @Test
    fun `detectFrameworksFromPath returns empty for empty project`() {
        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `detectFrameworksFromPath detects multiple frameworks`() {
        projectRoot.resolve("manage.py").createFile()
        projectRoot.resolve("requirements.txt").writeText("django==5.1\nflask==3.1\n")

        val result = ProjectScanner.detectFrameworksFromPath(projectRoot)
        assertTrue(Framework.DJANGO in result)
        assertTrue(Framework.FLASK in result)
    }

    // --- Build Tool Detection ---

    @Test
    fun `detectBuildToolsFromPath finds Maven from pom_xml`() {
        projectRoot.resolve("pom.xml").createFile()

        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.MAVEN in result)
    }

    @Test
    fun `detectBuildToolsFromPath finds Gradle from build_gradle_kts`() {
        projectRoot.resolve("build.gradle.kts").createFile()

        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.GRADLE in result)
    }

    @Test
    fun `detectBuildToolsFromPath finds Gradle from build_gradle`() {
        projectRoot.resolve("build.gradle").createFile()

        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.GRADLE in result)
    }

    @Test
    fun `detectBuildToolsFromPath finds pip from requirements_txt`() {
        projectRoot.resolve("requirements.txt").createFile()

        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.PIP in result)
    }

    @Test
    fun `detectBuildToolsFromPath finds Poetry from pyproject_toml with poetry section`() {
        projectRoot.resolve("pyproject.toml").writeText("""
            [tool.poetry]
            name = "my-project"
            version = "1.0.0"
        """.trimIndent())

        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.POETRY in result)
    }

    @Test
    fun `detectBuildToolsFromPath finds uv from uv_lock`() {
        projectRoot.resolve("uv.lock").createFile()

        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.UV in result)
    }

    @Test
    fun `detectBuildToolsFromPath finds uv from pyproject_toml with uv section`() {
        projectRoot.resolve("pyproject.toml").writeText("""
            [project]
            name = "my-project"
            
            [tool.uv]
            dev-dependencies = ["pytest"]
        """.trimIndent())

        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.UV in result)
    }

    @Test
    fun `detectBuildToolsFromPath returns empty for empty project`() {
        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `detectBuildToolsFromPath detects multiple build tools`() {
        projectRoot.resolve("pom.xml").createFile()
        projectRoot.resolve("requirements.txt").createFile()

        val result = ProjectScanner.detectBuildToolsFromPath(projectRoot)
        assertTrue(BuildTool.MAVEN in result)
        assertTrue(BuildTool.PIP in result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*ProjectScannerTest*" -v`
Expected: FAIL — `ProjectScanner` not found

- [ ] **Step 3: Implement ProjectScanner**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/ProjectScanner.kt
package com.workflow.orchestrator.agent.ide

import com.intellij.openapi.project.Project
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.name

object ProjectScanner {

    fun detectFrameworks(project: Project): Set<Framework> {
        val basePath = project.basePath ?: return emptySet()
        return detectFrameworksFromPath(Path.of(basePath))
    }

    fun detectBuildTools(project: Project): Set<BuildTool> {
        val basePath = project.basePath ?: return emptySet()
        return detectBuildToolsFromPath(Path.of(basePath))
    }

    fun detectFrameworksFromPath(root: Path): Set<Framework> {
        val frameworks = mutableSetOf<Framework>()
        val depContent = readDependencyContent(root)

        // Django: manage.py + django in dependencies
        if (root.resolve("manage.py").exists() && containsDependency(depContent, "django")) {
            frameworks.add(Framework.DJANGO)
        }

        // FastAPI: fastapi in dependencies
        if (containsDependency(depContent, "fastapi")) {
            frameworks.add(Framework.FASTAPI)
        }

        // Flask: flask in dependencies
        if (containsDependency(depContent, "flask")) {
            frameworks.add(Framework.FLASK)
        }

        // Spring is NOT detected by file scan — it's detected via PluginManager
        // in IdeContextDetector.hasSpringPlugin

        return frameworks
    }

    fun detectBuildToolsFromPath(root: Path): Set<BuildTool> {
        val buildTools = mutableSetOf<BuildTool>()

        // Maven
        if (root.resolve("pom.xml").exists()) {
            buildTools.add(BuildTool.MAVEN)
        }

        // Gradle
        if (root.resolve("build.gradle").exists() || root.resolve("build.gradle.kts").exists()) {
            buildTools.add(BuildTool.GRADLE)
        }

        // pip
        if (root.resolve("requirements.txt").exists()) {
            buildTools.add(BuildTool.PIP)
        }

        // Poetry
        val pyprojectContent = readFileIfExists(root.resolve("pyproject.toml"))
        if (pyprojectContent != null && pyprojectContent.contains("[tool.poetry]")) {
            buildTools.add(BuildTool.POETRY)
        }

        // uv
        if (root.resolve("uv.lock").exists()) {
            buildTools.add(BuildTool.UV)
        } else if (pyprojectContent != null && pyprojectContent.contains("[tool.uv]")) {
            buildTools.add(BuildTool.UV)
        }

        return buildTools
    }

    private fun readDependencyContent(root: Path): String {
        val parts = mutableListOf<String>()
        readFileIfExists(root.resolve("requirements.txt"))?.let { parts.add(it) }
        readFileIfExists(root.resolve("pyproject.toml"))?.let { parts.add(it) }
        return parts.joinToString("\n")
    }

    private fun containsDependency(content: String, name: String): Boolean {
        if (content.isEmpty()) return false
        // Match: django, django==5.1, django>=5.0, "django", 'django', django = "^5.1"
        val pattern = Regex("""(?i)\b$name\b""")
        return pattern.containsMatchIn(content)
    }

    private fun readFileIfExists(path: Path): String? =
        if (path.exists()) {
            try { path.readText() } catch (_: Exception) { null }
        } else null
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*ProjectScannerTest*" -v`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/ProjectScanner.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/ProjectScannerTest.kt
git commit -m "feat(agent): add ProjectScanner for framework and build tool detection

Scans project root for manage.py, requirements.txt, pyproject.toml,
pom.xml, build.gradle to detect Django, FastAPI, Flask, Maven, Gradle,
pip, Poetry, and uv."
```

---

## Task 3: Remove MySQL JDBC Driver

**Files:**
- Modify: `agent/build.gradle.kts:32-33`
- Modify: `gradle/libs.versions.toml:15`

- [ ] **Step 1: Remove MySQL driver dependency from agent build**

In `agent/build.gradle.kts`, remove line 33:

```kotlin
// BEFORE (lines 30-34):
// JDBC drivers — bundled in the plugin so the agent can query databases
// without requiring an external DB tool (IntelliJ DataSources, DBeaver, etc.)
implementation(libs.postgresql.jdbc)
implementation(libs.mysql.jdbc)      // ← REMOVE THIS LINE
implementation(libs.sqlite.jdbc)

// AFTER (lines 30-33):
// JDBC drivers — bundled in the plugin so the agent can query databases
// without requiring an external DB tool (IntelliJ DataSources, DBeaver, etc.)
// PostgreSQL (BSD-2) and SQLite (Apache 2.0) have clean licenses.
// MySQL (GPL) and SQL Server are user-supplied via Generic JDBC mode.
implementation(libs.postgresql.jdbc)
implementation(libs.sqlite.jdbc)
```

- [ ] **Step 2: Remove MySQL version from version catalog**

In `gradle/libs.versions.toml`, remove the mysql version and library entries. Find and remove:
- The `mysql = "8.3.0"` version line
- The `mysql-jdbc` library alias line (likely `mysql-jdbc = { module = "com.mysql:mysql-connector-j", version.ref = "mysql" }` or similar)

- [ ] **Step 3: Verify build succeeds**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Verify tests pass**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS (MySQL driver was bundled but not required for tests)

- [ ] **Step 5: Commit**

```bash
git add agent/build.gradle.kts gradle/libs.versions.toml
git commit -m "chore(agent): remove MySQL JDBC driver from bundle

MySQL Connector/J uses GPL license which is problematic for bundling.
Users needing MySQL can supply their own driver via Generic JDBC mode.
PostgreSQL (BSD-2) and SQLite (Apache 2.0) remain bundled."
```

---

## Task 4: Plugin Descriptor — Make Java Optional

**Files:**
- Create: `src/main/resources/META-INF/plugin-withJava.xml`
- Create: `src/main/resources/META-INF/plugin-withPython.xml`
- Create: `src/main/resources/META-INF/plugin-withPythonCore.xml`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create plugin-withJava.xml with Java-specific extensions**

Move the language-filtered Sonar extensions from `plugin.xml` into this file:

```xml
<!-- src/main/resources/META-INF/plugin-withJava.xml -->
<idea-plugin>
    <extensions defaultExtensionNs="com.intellij">
        <!-- SonarQube Editor Integration (Java/Kotlin only) -->
        <codeInsight.lineMarkerProvider language="JAVA"
            implementationClass="com.workflow.orchestrator.sonar.ui.CoverageLineMarkerProvider"/>
        <codeInsight.lineMarkerProvider language="kotlin"
            implementationClass="com.workflow.orchestrator.sonar.ui.CoverageLineMarkerProvider"/>

        <externalAnnotator language="JAVA"
            implementationClass="com.workflow.orchestrator.sonar.ui.SonarIssueAnnotator"/>
        <externalAnnotator language="kotlin"
            implementationClass="com.workflow.orchestrator.sonar.ui.SonarIssueAnnotator"/>

        <!-- Sonar Fix Intention Action (Alt+Enter) — Java/Kotlin only -->
        <intentionAction>
            <language>JAVA</language>
            <className>com.workflow.orchestrator.sonar.editor.SonarFixIntentionAction</className>
            <category>Workflow Orchestrator</category>
        </intentionAction>
        <intentionAction>
            <language>kotlin</language>
            <className>com.workflow.orchestrator.sonar.editor.SonarFixIntentionAction</className>
            <category>Workflow Orchestrator</category>
        </intentionAction>
    </extensions>
</idea-plugin>
```

- [ ] **Step 2: Create plugin-withPython.xml stub**

```xml
<!-- src/main/resources/META-INF/plugin-withPython.xml -->
<idea-plugin>
    <!-- Python Professional plugin extensions (Plan B/C will populate this) -->
</idea-plugin>
```

- [ ] **Step 3: Create plugin-withPythonCore.xml stub**

```xml
<!-- src/main/resources/META-INF/plugin-withPythonCore.xml -->
<idea-plugin>
    <!-- Python Community plugin extensions (Plan B/C will populate this) -->
</idea-plugin>
```

- [ ] **Step 4: Update plugin.xml — make Java optional, remove moved extensions, add Python deps**

In `src/main/resources/META-INF/plugin.xml`:

1. Change line 11 from required to optional:
```xml
<!-- BEFORE -->
<depends>com.intellij.modules.java</depends>

<!-- AFTER -->
<depends optional="true" config-file="plugin-withJava.xml">com.intellij.modules.java</depends>
```

2. Add Python optional dependencies after the Java line:
```xml
<depends optional="true" config-file="plugin-withPython.xml">com.intellij.python</depends>
<depends optional="true" config-file="plugin-withPythonCore.xml">PythonCore</depends>
```

3. Remove lines 186-206 (the Sonar Java/Kotlin extensions that were moved to `plugin-withJava.xml`):
   - Remove both `codeInsight.lineMarkerProvider` entries
   - Remove both `externalAnnotator` entries
   - Remove both `intentionAction` entries

4. Update plugin description to remove "Spring Boot" specificity:
```xml
<!-- BEFORE -->
<description><![CDATA[
    Eliminates context-switching between Jira, Bamboo, SonarQube, Bitbucket, and AI agent
    by consolidating the entire Spring Boot development lifecycle into a single IDE interface.
]]></description>

<!-- AFTER -->
<description><![CDATA[
    Eliminates context-switching between Jira, Bamboo, SonarQube, Bitbucket, and AI agent
    by consolidating the entire development lifecycle into a single IDE interface.
]]></description>
```

- [ ] **Step 5: Verify build succeeds**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Verify plugin verification passes**

Run: `./gradlew verifyPlugin`
Expected: No errors (warnings about optional deps are acceptable)

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml \
        src/main/resources/META-INF/plugin-withJava.xml \
        src/main/resources/META-INF/plugin-withPython.xml \
        src/main/resources/META-INF/plugin-withPythonCore.xml
git commit -m "feat: make Java optional, add Python optional deps in plugin descriptor

Move Java/Kotlin-specific Sonar extensions (line markers, annotators,
intention actions) to plugin-withJava.xml. Change com.intellij.modules.java
from required to optional. Add stub conditional XMLs for Python Professional
and Python Community plugins. Plugin can now install on any JetBrains IDE."
```

---

## Task 5: Context-Aware Tool Registration in AgentService

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/ContextAwareRegistrationTest.kt`

- [ ] **Step 1: Write failing tests for context-aware registration**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/ContextAwareRegistrationTest.kt
package com.workflow.orchestrator.agent.ide

import com.workflow.orchestrator.agent.tools.ToolRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ContextAwareRegistrationTest {

    @Test
    fun `shouldRegisterJavaPsiTools returns true for IntelliJ with Java plugin`() {
        val context = makeContext(
            product = IdeProduct.INTELLIJ_ULTIMATE,
            hasJavaPlugin = true,
        )
        assertTrue(ToolRegistrationFilter.shouldRegisterJavaPsiTools(context))
    }

    @Test
    fun `shouldRegisterJavaPsiTools returns false for PyCharm`() {
        val context = makeContext(
            product = IdeProduct.PYCHARM_PROFESSIONAL,
            hasJavaPlugin = false,
        )
        assertFalse(ToolRegistrationFilter.shouldRegisterJavaPsiTools(context))
    }

    @Test
    fun `shouldRegisterJavaPsiTools returns false for OTHER IDE without Java`() {
        val context = makeContext(
            product = IdeProduct.OTHER,
            hasJavaPlugin = false,
        )
        assertFalse(ToolRegistrationFilter.shouldRegisterJavaPsiTools(context))
    }

    @Test
    fun `shouldRegisterSpringTools returns true for IU with Spring plugin`() {
        val context = makeContext(
            product = IdeProduct.INTELLIJ_ULTIMATE,
            hasJavaPlugin = true,
            hasSpringPlugin = true,
        )
        assertTrue(ToolRegistrationFilter.shouldRegisterSpringTools(context))
    }

    @Test
    fun `shouldRegisterSpringTools returns false for IC without Spring`() {
        val context = makeContext(
            product = IdeProduct.INTELLIJ_COMMUNITY,
            hasJavaPlugin = true,
            hasSpringPlugin = false,
        )
        assertFalse(ToolRegistrationFilter.shouldRegisterSpringTools(context))
    }

    @Test
    fun `shouldRegisterSpringTools returns false for PyCharm even with Spring`() {
        val context = makeContext(
            product = IdeProduct.PYCHARM_PROFESSIONAL,
            hasJavaPlugin = false,
            hasSpringPlugin = true,
        )
        assertFalse(ToolRegistrationFilter.shouldRegisterSpringTools(context))
    }

    @Test
    fun `shouldRegisterJavaBuildTools returns true for IntelliJ`() {
        val context = makeContext(
            product = IdeProduct.INTELLIJ_COMMUNITY,
            hasJavaPlugin = true,
            detectedBuildTools = setOf(BuildTool.MAVEN),
        )
        assertTrue(ToolRegistrationFilter.shouldRegisterJavaBuildTools(context))
    }

    @Test
    fun `shouldRegisterJavaBuildTools returns false for PyCharm`() {
        val context = makeContext(
            product = IdeProduct.PYCHARM_COMMUNITY,
            hasJavaPlugin = false,
        )
        assertFalse(ToolRegistrationFilter.shouldRegisterJavaBuildTools(context))
    }

    @Test
    fun `shouldRegisterJavaDebugTools returns true for IntelliJ`() {
        val context = makeContext(
            product = IdeProduct.INTELLIJ_COMMUNITY,
            hasJavaPlugin = true,
        )
        assertTrue(ToolRegistrationFilter.shouldRegisterJavaDebugTools(context))
    }

    @Test
    fun `shouldRegisterJavaDebugTools returns false for PyCharm`() {
        val context = makeContext(
            product = IdeProduct.PYCHARM_PROFESSIONAL,
            hasJavaPlugin = false,
        )
        assertFalse(ToolRegistrationFilter.shouldRegisterJavaDebugTools(context))
    }

    @Test
    fun `shouldPromoteFrameworkTool returns true for detected framework`() {
        val context = makeContext(
            detectedFrameworks = setOf(Framework.SPRING),
            hasSpringPlugin = true,
            hasJavaPlugin = true,
        )
        assertTrue(ToolRegistrationFilter.shouldPromoteFrameworkTool(context, Framework.SPRING))
    }

    @Test
    fun `shouldPromoteFrameworkTool returns false for undetected framework`() {
        val context = makeContext(
            detectedFrameworks = emptySet(),
        )
        assertFalse(ToolRegistrationFilter.shouldPromoteFrameworkTool(context, Framework.DJANGO))
    }

    @Test
    fun `database tools always register regardless of IDE`() {
        for (product in IdeProduct.entries) {
            val context = makeContext(product = product)
            assertTrue(ToolRegistrationFilter.shouldRegisterDatabaseTools(context),
                "Database tools should register for $product")
        }
    }

    @Test
    fun `universal tools always register regardless of IDE`() {
        for (product in IdeProduct.entries) {
            val context = makeContext(product = product)
            assertTrue(ToolRegistrationFilter.shouldRegisterUniversalTools(context),
                "Universal tools should register for $product")
        }
    }

    private fun makeContext(
        product: IdeProduct = IdeProduct.INTELLIJ_ULTIMATE,
        hasJavaPlugin: Boolean = false,
        hasPythonPlugin: Boolean = false,
        hasPythonCorePlugin: Boolean = false,
        hasSpringPlugin: Boolean = false,
        detectedFrameworks: Set<Framework> = emptySet(),
        detectedBuildTools: Set<BuildTool> = emptySet(),
    ): IdeContext = IdeContext(
        product = product,
        productName = product.name,
        edition = IdeContextDetector.classifyEdition(product),
        languages = IdeContextDetector.deriveLanguages(product, hasJavaPlugin, hasPythonPlugin || hasPythonCorePlugin),
        hasJavaPlugin = hasJavaPlugin,
        hasPythonPlugin = hasPythonPlugin,
        hasPythonCorePlugin = hasPythonCorePlugin,
        hasSpringPlugin = hasSpringPlugin,
        detectedFrameworks = detectedFrameworks,
        detectedBuildTools = detectedBuildTools,
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*ContextAwareRegistrationTest*" -v`
Expected: FAIL — `ToolRegistrationFilter` not found

- [ ] **Step 3: Implement ToolRegistrationFilter**

```kotlin
// Add to agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContext.kt
// (append after the IdeContext class)

object ToolRegistrationFilter {

    /** Universal tools (file, git, terminal, memory, planning) — always register */
    fun shouldRegisterUniversalTools(context: IdeContext): Boolean = true

    /** Database tools — always register (pure JDBC, no IDE dependency) */
    fun shouldRegisterDatabaseTools(context: IdeContext): Boolean = true

    /** Java/Kotlin PSI tools (find_definition, type_hierarchy, etc.) */
    fun shouldRegisterJavaPsiTools(context: IdeContext): Boolean =
        context.hasJavaPlugin

    /** Spring framework meta-tool */
    fun shouldRegisterSpringTools(context: IdeContext): Boolean =
        context.hasSpringPlugin && context.hasJavaPlugin

    /** Java/Kotlin build tools (Maven/Gradle actions in build meta-tool) */
    fun shouldRegisterJavaBuildTools(context: IdeContext): Boolean =
        context.hasJavaPlugin

    /** Java/Kotlin debug tools */
    fun shouldRegisterJavaDebugTools(context: IdeContext): Boolean =
        context.hasJavaPlugin

    /** Coverage tool */
    fun shouldRegisterCoverageTool(context: IdeContext): Boolean =
        context.edition == Edition.ULTIMATE || context.edition == Edition.PROFESSIONAL

    /** Whether a detected framework's meta-tool should be promoted to core */
    fun shouldPromoteFrameworkTool(context: IdeContext, framework: Framework): Boolean =
        framework in context.detectedFrameworks
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*ContextAwareRegistrationTest*" -v`
Expected: ALL PASS

- [ ] **Step 5: Integrate IdeContext into AgentService.registerAllTools()**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`, modify the `registerAllTools()` method. The key changes:

1. Add an `ideContext` field to `AgentService`:

```kotlin
// Near the top of AgentService class, add:
private lateinit var ideContext: IdeContext
```

2. At the start of `registerAllTools()`, detect the IDE context:

```kotlin
// At the start of registerAllTools(), add:
ideContext = IdeContextDetector.detect(project)
LOG.info("IDE context detected: ${ideContext.product} (${ideContext.edition}), " +
    "languages=${ideContext.languages}, frameworks=${ideContext.detectedFrameworks}, " +
    "buildTools=${ideContext.detectedBuildTools}")
```

3. Wrap Java/Kotlin PSI tool registrations with the filter check. Find the section where PSI tools are registered (tools like `FindDefinitionTool`, `FindReferencesTool`, `SemanticDiagnosticsTool`, `TypeHierarchyTool`, `CallHierarchyTool`, `GetAnnotationsTool`, `GetMethodBodyTool`, `FileStructureTool`) and wrap them:

```kotlin
// Guard Java/Kotlin PSI tools
if (ToolRegistrationFilter.shouldRegisterJavaPsiTools(ideContext)) {
    safeRegisterCore { FindDefinitionTool(project) }
    safeRegisterCore { FindReferencesTool(project) }
    safeRegisterCore { SemanticDiagnosticsTool(project) }
    // ... other PSI tools ...
    
    safeRegisterDeferred("Code Intelligence") { TypeHierarchyTool(project) }
    safeRegisterDeferred("Code Intelligence") { CallHierarchyTool(project) }
    // ... other deferred PSI tools ...
} else {
    LOG.info("Skipping Java/Kotlin PSI tools — Java plugin not available")
}
```

4. Wrap Spring tools:

```kotlin
if (ToolRegistrationFilter.shouldRegisterSpringTools(ideContext)) {
    safeRegisterDeferred("Framework") { SpringTool(project) }
    
    // Promote to core if Spring is detected in project
    if (ToolRegistrationFilter.shouldPromoteFrameworkTool(ideContext, Framework.SPRING)) {
        // Will be promoted from deferred to core after registration
    }
} else {
    LOG.info("Skipping Spring tools — Spring plugin not available")
}
```

5. Wrap build tools:

```kotlin
if (ToolRegistrationFilter.shouldRegisterJavaBuildTools(ideContext)) {
    safeRegisterDeferred("Build") { BuildTool(project) }
} else {
    LOG.info("Skipping Java build tools — Java plugin not available")
}
```

6. Wrap debug tools:

```kotlin
if (ToolRegistrationFilter.shouldRegisterJavaDebugTools(ideContext)) {
    try {
        val controller = AgentDebugController(project)
        safeRegisterDeferred("Debug") { DebugBreakpointsTool(controller) }
        safeRegisterDeferred("Debug") { DebugStepTool(controller) }
        safeRegisterDeferred("Debug") { DebugInspectTool(controller) }
    } catch (e: Exception) {
        LOG.warn("Failed to register debug tools: ${e.message}")
    }
} else {
    LOG.info("Skipping Java debug tools — Java plugin not available")
}
```

7. Make `ideContext` accessible for the system prompt (add a getter):

```kotlin
fun getIdeContext(): IdeContext = ideContext
```

**Important:** Do NOT move or rename existing tool registration code. Only add `if` guards around the existing registrations. This minimizes risk.

- [ ] **Step 6: Verify all existing tests pass**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS (existing tests run in IntelliJ context where Java plugin is present, so all guards pass)

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContext.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/ContextAwareRegistrationTest.kt
git commit -m "feat(agent): context-aware tool registration with IdeContext

Guard Java/Kotlin PSI, Spring, build, and debug tool registrations behind
IdeContext checks. Tools that can't work in the current IDE are never
registered, so the LLM never sees them. Database and universal tools
always register regardless of IDE."
```

---

## Task 6: Full Test Suite Verification and Documentation

**Files:**
- Modify: `agent/CLAUDE.md` (add IdeContext section)
- Modify: root `CLAUDE.md` (update architecture table)

- [ ] **Step 1: Run full agent test suite**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS

- [ ] **Step 2: Run full project test suite**

Run: `./gradlew test -v`
Expected: ALL PASS

- [ ] **Step 3: Verify plugin builds**

Run: `./gradlew clean buildPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Verify plugin compatibility**

Run: `./gradlew verifyPlugin`
Expected: PASS (warnings acceptable)

- [ ] **Step 5: Update agent CLAUDE.md**

Add an "IDE Context" section after the "Tool Selection" section in `agent/CLAUDE.md`:

```markdown
## IDE Context Detection

`IdeContextDetector` detects the runtime environment at agent startup:

- **IDE product/edition**: `ApplicationInfo.getInstance().build.productCode` (IU, IC, PY, PC, etc.)
- **Available plugins**: Java, Python (Pro/Core), Spring checked via `PluginManagerCore`
- **Detected frameworks**: Django (manage.py + deps), FastAPI, Flask scanned from requirements/pyproject
- **Detected build tools**: Maven (pom.xml), Gradle, pip, Poetry, uv scanned from project root

Result stored as `IdeContext` in `AgentService`. Tools that can't work in the current environment are never registered. `IdeContext.summary()` provides a human-readable string for the system prompt.

Key files: `ide/IdeContext.kt`, `ide/IdeContextDetector.kt`, `ide/ProjectScanner.kt`
```

- [ ] **Step 6: Update root CLAUDE.md**

In the root `CLAUDE.md`, update the `:agent` module description to mention IdeContext:

Add after "conditional integration tools via `reregisterConditionalTools()`":
```
IDE context detection (IdeContextDetector — product/edition/plugins/frameworks, 3-layer tool filter),
```

- [ ] **Step 7: Commit**

```bash
git add agent/CLAUDE.md CLAUDE.md
git commit -m "docs(agent): add IdeContext detection to architecture docs

Document IDE context detection, 3-layer tool registration filter,
and supported IDE matrix in agent and root CLAUDE.md files."
```

---

## Summary

After Plan A is complete:

| What changed | Result |
|---|---|
| Plugin descriptor | `com.intellij.modules.java` is optional — plugin can install on any JetBrains IDE |
| Java/Kotlin extensions | Moved to `plugin-withJava.xml` — only load when Java module present |
| Python stubs | `plugin-withPython.xml` and `plugin-withPythonCore.xml` ready for Plan B/C |
| IdeContext | Detects IDE product, edition, plugins, frameworks, build tools at startup |
| Tool registration | Filtered by IDE capabilities — Java PSI/Spring/debug/build tools skip in PyCharm |
| Database | Universal — works in all IDEs. MySQL driver removed (GPL). |
| Backward compatibility | IntelliJ users see zero changes — all guards pass when Java plugin is present |

**What comes next:**
- **Plan B:** Language intelligence provider pattern + Python PSI implementation
- **Plan C:** Django/FastAPI/Flask meta-tools + pip/poetry/uv build tools + Python debug
- **Plan D:** Modular system prompt + skill variants + deferred tool discovery
