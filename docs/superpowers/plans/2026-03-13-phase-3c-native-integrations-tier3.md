# Phase 3C: Native IntelliJ Integrations — Tier 3 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add nice-to-have integrations: build status decorators in project tree, coverage in diff gutters, and Docker tag validation as a before-run step.

**Architecture:** Thin adapters over existing services. Each reads from cached data — no new API endpoints.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (2025.1+)

**Design Spec:** `docs/superpowers/specs/2026-03-13-intellij-native-integrations-design.md` (Sections 4.1–4.4)

---

## Chunk 1: Build Status Decorators + Diff Coverage

### Task 1: Project View Build Status Decorator

**Files:**
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildStatusNodeDecorator.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Implement the decorator**

Create `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildStatusNodeDecorator.kt`:

```kotlin
package com.workflow.orchestrator.bamboo.ui

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.service.BuildMonitorService
import java.awt.Color

/**
 * Decorates the project tree root with the latest Bamboo build status.
 * Shows a colored suffix like " ✓ #123" or " ✗ #123" on the project node.
 */
class BuildStatusNodeDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        // Only decorate the project root node
        val project = node.project ?: return
        if (node.virtualFile != project.baseDir) return

        val state = BuildMonitorService.getInstance(project).stateFlow.value ?: return

        val (symbol, color) = when (state.overallStatus) {
            BuildStatus.SUCCESS -> "✓" to Color(0x59, 0xA6, 0x0F)
            BuildStatus.FAILED -> "✗" to Color(0xE0, 0x40, 0x40)
            BuildStatus.IN_PROGRESS -> "⟳" to Color(0x40, 0x7E, 0xC9)
            BuildStatus.PENDING -> "◷" to Color(0x99, 0x99, 0x99)
            BuildStatus.UNKNOWN -> return
        }

        data.addText(
            " $symbol #${state.buildNumber}",
            SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, color)
        )
    }
}
```

- [ ] **Step 2: Register in plugin.xml**

Add to `<extensions defaultExtensionNs="com.intellij">`:

```xml
<!-- Project View: Build Status Badge -->
<projectViewNodeDecorator
    implementation="com.workflow.orchestrator.bamboo.ui.BuildStatusNodeDecorator"/>
```

Note: There is already a `projectViewNodeDecorator` registered for `CoverageTreeDecorator`. Both can coexist — IntelliJ calls all registered decorators.

- [ ] **Step 3: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildStatusNodeDecorator.kt
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(bamboo): add build status badge to project tree root"
```

---

### Task 2: Diff Gutter Coverage Extension

**Files:**
- Create: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/diff/CoverageDiffExtension.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Implement the diff extension**

Create `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/diff/CoverageDiffExtension.kt`:

```kotlin
package com.workflow.orchestrator.sonar.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.sonar.service.SonarDataService
import java.awt.Color

/**
 * Shows SonarQube coverage data (green/red) in the diff gutter.
 * Covered lines in the right (new code) panel get a green background,
 * uncovered lines get a red background.
 */
class CoverageDiffExtension : DiffExtension() {

    private val log = Logger.getInstance(CoverageDiffExtension::class.java)

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val project = context.project ?: return
        if (request !is ContentDiffRequest) return

        val sonarService = SonarDataService.getInstance(project)
        val coverageData = sonarService.getCoverageData() ?: return

        // Get the right-side editor (new code)
        val editors = try {
            viewer.javaClass.getMethod("getEditors").invoke(viewer) as? List<*>
        } catch (e: Exception) {
            return
        }

        val rightEditor = (editors?.lastOrNull() as? Editor) ?: return
        val contents = request.contents
        if (contents.size < 2) return

        val rightContent = contents.last()
        val filePath = rightContent.file?.path ?: return

        // Find coverage for this file
        val fileLines = coverageData[filePath] ?: return

        val coveredColor = Color(0x59, 0xA6, 0x0F, 0x30) // translucent green
        val uncoveredColor = Color(0xE0, 0x40, 0x40, 0x30) // translucent red

        val markupModel = rightEditor.markupModel
        val document = rightEditor.document

        for (lineData in fileLines) {
            val lineIndex = lineData.line - 1
            if (lineIndex < 0 || lineIndex >= document.lineCount) continue

            val startOffset = document.getLineStartOffset(lineIndex)
            val endOffset = document.getLineEndOffset(lineIndex)

            val color = if (lineData.covered) coveredColor else uncoveredColor
            val textAttributes = TextAttributes().apply { backgroundColor = color }

            markupModel.addRangeHighlighter(
                startOffset, endOffset,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                textAttributes,
                HighlighterTargetArea.LINES_IN_RANGE
            )
        }
    }
}
```

- [ ] **Step 2: Register in plugin.xml**

Add to `<extensions defaultExtensionNs="com.intellij">`:

```xml
<!-- Diff: Coverage Gutter Highlights -->
<diff.DiffExtension implementation="com.workflow.orchestrator.sonar.diff.CoverageDiffExtension"/>
```

- [ ] **Step 3: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/diff/CoverageDiffExtension.kt
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(sonar): add coverage highlights to diff gutter view"
```

---

## Chunk 2: Before Run Tag Validation

### Task 3: Docker Tag Validation Before Run Provider

**Files:**
- Create: `automation/src/main/kotlin/com/workflow/orchestrator/automation/run/TagValidationBeforeRunProvider.kt`
- Create: `automation/src/test/kotlin/com/workflow/orchestrator/automation/run/TagValidationLogicTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.automation.run

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TagValidationLogicTest {

    @Test
    fun `parses docker tags from JSON string`() {
        val json = """{"service-a": "1.2.3", "service-b": "4.5.6"}"""
        val tags = TagValidationLogic.parseDockerTags(json)
        assertEquals(2, tags.size)
        assertEquals("1.2.3", tags["service-a"])
        assertEquals("4.5.6", tags["service-b"])
    }

    @Test
    fun `returns empty map for invalid JSON`() {
        val tags = TagValidationLogic.parseDockerTags("not json")
        assertTrue(tags.isEmpty())
    }

    @Test
    fun `returns empty map for empty string`() {
        val tags = TagValidationLogic.parseDockerTags("")
        assertTrue(tags.isEmpty())
    }

    @Test
    fun `builds registry URL for tag check`() {
        val url = TagValidationLogic.buildManifestUrl("https://nexus.example.com", "myapp/service-a", "1.2.3")
        assertEquals("https://nexus.example.com/v2/myapp/service-a/manifests/1.2.3", url)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :automation:test --tests '*TagValidationLogicTest*'`
Expected: FAIL — class not found

- [ ] **Step 3: Implement the logic and provider**

Create `automation/src/main/kotlin/com/workflow/orchestrator/automation/run/TagValidationBeforeRunProvider.kt`:

```kotlin
package com.workflow.orchestrator.automation.run

import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.icons.AllIcons
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.swing.Icon

class TagValidationBeforeRunTask : com.intellij.execution.BeforeRunTask<TagValidationBeforeRunTask>(
    TagValidationBeforeRunProvider.ID
)

class TagValidationBeforeRunProvider : BeforeRunTaskProvider<TagValidationBeforeRunTask>() {

    companion object {
        val ID = Key.create<TagValidationBeforeRunTask>("WorkflowTagValidation")
    }

    private val log = Logger.getInstance(TagValidationBeforeRunProvider::class.java)

    override fun getId(): Key<TagValidationBeforeRunTask> = ID
    override fun getName(): String = "Validate Docker Tags"
    override fun getIcon(): Icon = AllIcons.Actions.Checked
    override fun getDescription(task: TagValidationBeforeRunTask): String = "Verify Docker tag existence in Nexus"

    override fun createTask(runConfiguration: RunConfiguration): TagValidationBeforeRunTask {
        return TagValidationBeforeRunTask().apply { isEnabled = false }
    }

    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        environment: ExecutionEnvironment,
        task: TagValidationBeforeRunTask
    ): Boolean {
        val project = configuration.project
        val settings = PluginSettings.getInstance(project)
        val registryUrl = settings.state.nexusUrl.orEmpty().trimEnd('/')
        if (registryUrl.isBlank()) {
            log.warn("[Automation:TagValidation] No registry URL configured")
            return false
        }

        // Get docker tags from the run configuration options (if Bamboo run config)
        val buildVariables = try {
            val options = configuration.javaClass.getMethod("getOptions").invoke(configuration)
            options.javaClass.getMethod("getBuildVariables").invoke(options) as? String ?: ""
        } catch (e: Exception) {
            return true // Not a Bamboo run config, skip validation
        }

        val dockerTagsJson = TagValidationLogic.extractDockerTagsJson(buildVariables)
        if (dockerTagsJson.isBlank()) return true // No tags to validate

        val tags = TagValidationLogic.parseDockerTags(dockerTagsJson)
        if (tags.isEmpty()) return true

        val credentialStore = CredentialStore()
        val nexusUsername = settings.state.nexusUsername.orEmpty()
        val nexusPassword = credentialStore.getNexusPassword() ?: ""

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val missingTags = mutableListOf<String>()

        for ((service, tag) in tags) {
            val url = TagValidationLogic.buildManifestUrl(registryUrl, service, tag)
            val request = Request.Builder()
                .url(url)
                .head()
                .addHeader("Authorization", Credentials.basic(nexusUsername, nexusPassword))
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                response.use {
                    if (it.code == 404) {
                        missingTags.add("$service:$tag")
                    }
                }
            } catch (e: Exception) {
                log.warn("[Automation:TagValidation] Error checking $service:$tag: ${e.message}")
                missingTags.add("$service:$tag (connection error)")
            }
        }

        if (missingTags.isNotEmpty()) {
            log.warn("[Automation:TagValidation] Missing tags: $missingTags")
            return false
        }

        return true
    }
}

/** Pure logic — testable without IntelliJ dependencies. */
object TagValidationLogic {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseDockerTags(jsonString: String): Map<String, String> {
        if (jsonString.isBlank()) return emptyMap()
        return try {
            val obj = json.decodeFromString<JsonObject>(jsonString)
            obj.mapValues { it.value.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun extractDockerTagsJson(buildVariables: String): String {
        if (buildVariables.isBlank()) return ""
        return try {
            val obj = json.decodeFromString<JsonObject>(buildVariables)
            obj["dockerTagsAsJson"]?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun buildManifestUrl(registryUrl: String, imageName: String, tag: String): String {
        return "${registryUrl.trimEnd('/')}/v2/$imageName/manifests/$tag"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :automation:test --tests '*TagValidationLogicTest*'`
Expected: 4 tests PASS

- [ ] **Step 5: Register in plugin.xml**

Add to `<extensions defaultExtensionNs="com.intellij">`:

```xml
<!-- Before Run: Docker Tag Validation -->
<stepsBeforeRunProvider
    implementation="com.workflow.orchestrator.automation.run.TagValidationBeforeRunProvider"/>
```

- [ ] **Step 6: Commit**

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/run/
git add automation/src/test/kotlin/com/workflow/orchestrator/automation/run/
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(automation): add Docker tag validation as before-run step"
```

---

## Verification

After all tasks are complete:

- [ ] `./gradlew :core:test` — all core tests pass
- [ ] `./gradlew :jira:test` — all jira tests pass
- [ ] `./gradlew :bamboo:test` — all bamboo tests pass
- [ ] `./gradlew :automation:test` — all automation tests pass
- [ ] `./gradlew verifyPlugin` — no API compatibility issues
- [ ] `./gradlew runIde` — manual verification:
  - Project tree shows build status badge on project root
  - Diff view shows coverage colors (if Sonar data available)
  - Run > Edit Configurations: "Validate Docker Tags" available as before-run step
