# New Sonar Agent Integration Tools — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 2 new Sonar agent tools (`sonar_analysis_tasks`, `sonar_project_health`) to expose recently added SonarQube capabilities to the AI agent.

**Architecture:** `sonar_analysis_tasks` wraps the existing `SonarService.getAnalysisTasks()` (added in main commit ca1c8b5). `sonar_project_health` requires a new service interface method wrapping `SonarApiClient.getProjectMeasures()` to expose tech debt, ratings, and duplication metrics. Both tools follow the existing integration tool pattern: `ServiceLookup.sonar()` → `service.method()` → `.toAgentToolResult()`.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform services, SonarQube Web API

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `agent/src/main/kotlin/.../tools/integration/SonarAnalysisTasksTool.kt` | `sonar_analysis_tasks` — wraps `SonarService.getAnalysisTasks()` |
| `agent/src/main/kotlin/.../tools/integration/SonarProjectHealthTool.kt` | `sonar_project_health` — wraps new `SonarService.getProjectHealth()` |
| `agent/src/test/kotlin/.../tools/integration/SonarAnalysisTasksToolTest.kt` | Tests for analysis tasks tool |
| `agent/src/test/kotlin/.../tools/integration/SonarProjectHealthToolTest.kt` | Tests for project health tool |

### Modified Files
| File | Change |
|------|--------|
| `core/.../services/SonarService.kt` | Add `getProjectHealth()` method to interface |
| `core/.../model/sonar/SonarModels.kt` | Add `ProjectHealthData` data class |
| `sonar/.../service/SonarServiceImpl.kt` | Implement `getProjectHealth()` |
| `agent/.../AgentService.kt` | Register 2 new tools |
| `agent/.../tools/ToolCategoryRegistry.kt` | Add new tools to sonar category |
| `agent/.../tools/DynamicToolSelector.kt` | Add keyword triggers for new tools |

---

## Task 1: `sonar_analysis_tasks` Tool

The service method `SonarService.getAnalysisTasks()` already exists. Just need the agent tool wrapper.

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarAnalysisTasksTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarAnalysisTasksToolTest.kt`

- [ ] **Step 1: Write test**

```kotlin
package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarAnalysisTasksToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = SonarAnalysisTasksTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("sonar_analysis_tasks", tool.name)
        assertTrue(tool.parameters.required.contains("project_key"))
        assertTrue(tool.description.contains("analysis"))
    }

    @Test
    fun `returns error when project_key is missing`() = runTest {
        val params = buildJsonObject { }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("project_key"))
    }
}
```

- [ ] **Step 2: Implement SonarAnalysisTasksTool**

```kotlin
package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SonarAnalysisTasksTool : AgentTool {
    override val name = "sonar_analysis_tasks"
    override val description = "Get recent SonarQube analysis tasks (Compute Engine) for a project. Shows analysis status (SUCCESS/FAILED/PENDING/IN_PROGRESS), errors, and timing. Useful for diagnosing stuck or failed analyses after builds."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "project_key" to ParameterProperty(type = "string", description = "SonarQube project key (e.g., 'com.example:my-service')")
        ),
        required = listOf("project_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val projectKey = params["project_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'project_key' parameter required", "Error: missing project_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
        val service = ServiceLookup.sonar(project) ?: return ServiceLookup.notConfigured("SonarQube")

        return service.getAnalysisTasks(projectKey).toAgentToolResult()
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.integration.SonarAnalysisTasksToolTest" --rerun --no-build-cache
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarAnalysisTasksTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarAnalysisTasksToolTest.kt
git commit -m "feat(agent): sonar_analysis_tasks tool — Compute Engine task status"
```

---

## Task 2: `ProjectHealthData` Model + Service Method

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/model/sonar/SonarModels.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/services/SonarService.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt`

- [ ] **Step 1: Add ProjectHealthData to SonarModels.kt**

After `SonarAnalysisTaskData` (line 70), add:

```kotlin
/**
 * Project-level health metrics: technical debt, ratings, duplication.
 * Provides a bird's eye view of project quality.
 */
@Serializable
data class ProjectHealthData(
    val technicalDebtMinutes: Long,
    val technicalDebtFormatted: String,  // "4h 30min" or "2d 3h"
    val maintainabilityRating: String,   // A-E
    val reliabilityRating: String,       // A-E
    val securityRating: String,          // A-E
    val duplicatedLinesDensity: Double,  // percentage
    val cognitiveComplexity: Long,
    val lineCoverage: Double,
    val branchCoverage: Double
)
```

- [ ] **Step 2: Add getProjectHealth to SonarService interface**

In `SonarService.kt`, after `getAnalysisTasks()`, add:

```kotlin
/** Get project-level health metrics: tech debt, ratings, duplication, complexity. */
suspend fun getProjectHealth(projectKey: String): ToolResult<ProjectHealthData>
```

Add import: `import com.workflow.orchestrator.core.model.sonar.ProjectHealthData`

- [ ] **Step 3: Implement getProjectHealth in SonarServiceImpl**

In `SonarServiceImpl.kt`, after `getAnalysisTasks()` method, add:

```kotlin
override suspend fun getProjectHealth(projectKey: String): ToolResult<ProjectHealthData> {
    val api = client ?: return ToolResult(
        data = ProjectHealthData(0, "N/A", "E", "E", "E", 0.0, 0, 0.0, 0.0),
        summary = "SonarQube not configured. Cannot fetch project health.",
        isError = true,
        hint = "Set up SonarQube connection in Settings."
    )

    return when (val result = api.getProjectMeasures(projectKey)) {
        is ApiResult.Success -> {
            val measures = result.data.associate { it.metric to it.value }

            val debtMinutes = measures["sqale_index"]?.toLongOrNull() ?: 0
            val debtFormatted = formatDebt(debtMinutes)
            val maintainability = ratingLetter(measures["sqale_rating"]?.toDoubleOrNull() ?: 5.0)
            val reliability = ratingLetter(measures["reliability_rating"]?.toDoubleOrNull() ?: 5.0)
            val security = ratingLetter(measures["security_rating"]?.toDoubleOrNull() ?: 5.0)
            val duplication = measures["duplicated_lines_density"]?.toDoubleOrNull() ?: 0.0
            val complexity = measures["cognitive_complexity"]?.toLongOrNull() ?: 0
            val lineCov = measures["coverage"]?.toDoubleOrNull() ?: 0.0
            val branchCov = measures["branch_coverage"]?.toDoubleOrNull() ?: 0.0

            val data = ProjectHealthData(
                technicalDebtMinutes = debtMinutes,
                technicalDebtFormatted = debtFormatted,
                maintainabilityRating = maintainability,
                reliabilityRating = reliability,
                securityRating = security,
                duplicatedLinesDensity = duplication,
                cognitiveComplexity = complexity,
                lineCoverage = lineCov,
                branchCoverage = branchCov
            )

            val summary = buildString {
                append("Project Health for $projectKey")
                append("\nRatings: Maintainability=$maintainability | Reliability=$reliability | Security=$security")
                append("\nTechnical Debt: $debtFormatted")
                append("\nDuplication: ${"%.1f".format(duplication)}%")
                append("\nCoverage: Line=${"%.1f".format(lineCov)}% | Branch=${"%.1f".format(branchCov)}%")
                append("\nCognitive Complexity: $complexity")
            }

            ToolResult.success(data = data, summary = summary)
        }
        is ApiResult.Error -> {
            log.warn("[SonarService] Failed to get project health for $projectKey: ${result.message}")
            ToolResult(
                data = ProjectHealthData(0, "N/A", "E", "E", "E", 0.0, 0, 0.0, 0.0),
                summary = "Error fetching project health for $projectKey: ${result.message}",
                isError = true,
                hint = "Check SonarQube connection and project key."
            )
        }
    }
}

/** Convert SonarQube rating value (1.0-5.0) to letter grade (A-E). */
private fun ratingLetter(value: Double): String = when {
    value <= 1.0 -> "A"
    value <= 2.0 -> "B"
    value <= 3.0 -> "C"
    value <= 4.0 -> "D"
    else -> "E"
}

/** Format technical debt minutes as human-readable string. */
private fun formatDebt(minutes: Long): String {
    if (minutes < 60) return "${minutes}min"
    val hours = minutes / 60
    val mins = minutes % 60
    if (hours < 24) return if (mins > 0) "${hours}h ${mins}min" else "${hours}h"
    val days = hours / 24
    val remainingHours = hours % 24
    return if (remainingHours > 0) "${days}d ${remainingHours}h" else "${days}d"
}
```

- [ ] **Step 4: Verify compilation across all modules**

```bash
./gradlew compileKotlin --no-build-cache
```
Expected: PASS (core, sonar, agent all compile)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/model/sonar/SonarModels.kt \
       core/src/main/kotlin/com/workflow/orchestrator/core/services/SonarService.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt
git commit -m "feat(sonar): add getProjectHealth service method — tech debt, ratings, duplication"
```

---

## Task 3: `sonar_project_health` Tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarProjectHealthTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarProjectHealthToolTest.kt`

- [ ] **Step 1: Write test**

```kotlin
package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarProjectHealthToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = SonarProjectHealthTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("sonar_project_health", tool.name)
        assertTrue(tool.parameters.required.contains("project_key"))
        assertTrue(tool.description.contains("health"))
    }

    @Test
    fun `returns error when project_key is missing`() = runTest {
        val params = buildJsonObject { }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("project_key"))
    }
}
```

- [ ] **Step 2: Implement SonarProjectHealthTool**

```kotlin
package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SonarProjectHealthTool : AgentTool {
    override val name = "sonar_project_health"
    override val description = "Get project-level health metrics from SonarQube: technical debt, maintainability/reliability/security ratings (A-E), duplication percentage, cognitive complexity, and coverage. Provides a bird's eye view of project quality before diving into individual issues."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "project_key" to ParameterProperty(type = "string", description = "SonarQube project key (e.g., 'com.example:my-service')")
        ),
        required = listOf("project_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val projectKey = params["project_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'project_key' parameter required", "Error: missing project_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
        val service = ServiceLookup.sonar(project) ?: return ServiceLookup.notConfigured("SonarQube")

        return service.getProjectHealth(projectKey).toAgentToolResult()
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.integration.SonarProjectHealthToolTest" --rerun --no-build-cache
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarProjectHealthTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarProjectHealthToolTest.kt
git commit -m "feat(agent): sonar_project_health tool — tech debt, ratings, duplication metrics"
```

---

## Task 4: Register Tools + Update Category/Selector

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:91-94`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt:82`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt:44-50`

- [ ] **Step 1: Register in AgentService**

After line 94 (`register(SonarSearchProjectsTool())`), add:
```kotlin
register(SonarAnalysisTasksTool())
register(SonarProjectHealthTool())
```

Add imports:
```kotlin
import com.workflow.orchestrator.agent.tools.integration.SonarAnalysisTasksTool
import com.workflow.orchestrator.agent.tools.integration.SonarProjectHealthTool
```

- [ ] **Step 2: Update ToolCategoryRegistry sonar category**

Change line 82 from:
```kotlin
tools = listOf("sonar_issues", "sonar_quality_gate", "sonar_coverage", "sonar_search_projects")
```
To:
```kotlin
tools = listOf("sonar_issues", "sonar_quality_gate", "sonar_coverage", "sonar_search_projects", "sonar_analysis_tasks", "sonar_project_health")
```

- [ ] **Step 3: Update DynamicToolSelector keyword triggers**

In the Sonar section (lines 44-50), add new keyword triggers:

After the existing `"quality gate" to setOf("sonar_quality_gate")` line, add:
```kotlin
"analysis" to setOf("sonar_analysis_tasks"),
"compute engine" to setOf("sonar_analysis_tasks"),
"tech debt" to setOf("sonar_project_health"),
"technical debt" to setOf("sonar_project_health"),
"rating" to setOf("sonar_project_health"),
"health" to setOf("sonar_project_health"),
"duplication" to setOf("sonar_project_health"),
```

Also update the existing `"sonar"` trigger to include the new tools:
```kotlin
"sonar" to setOf("sonar_issues", "sonar_quality_gate", "sonar_coverage", "sonar_search_projects", "sonar_analysis_tasks", "sonar_project_health"),
```

- [ ] **Step 4: Verify compilation and tests**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt
git commit -m "feat(agent): register sonar_analysis_tasks and sonar_project_health, update category and triggers"
```

---

## Task 5: Final Verification

- [ ] **Step 1: Run full test suite**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

- [ ] **Step 2: Verify plugin**

```bash
./gradlew verifyPlugin
```

- [ ] **Step 3: Verify tool count consistency**

The sonar category should now have 6 tools. The ToolCategoryRegistry test should still pass (no duplicate tools across categories).

---

## Implementation Order

```
Task 1: sonar_analysis_tasks tool          ← independent (service method already exists)
Task 2: ProjectHealthData + service method ← independent (core + sonar modules)
Task 3: sonar_project_health tool          ← depends on Task 2
Task 4: Register + category + selector     ← depends on Tasks 1, 3
Task 5: Final verification                 ← depends on all
```
