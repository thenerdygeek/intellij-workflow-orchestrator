# Eager Skill Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make skills appear in the `/` dropdown and Skills chip immediately when the agent tab opens, without waiting for the first message to create a session.

**Architecture:** Move `SkillRegistry` creation from `ConversationSession.create()` to `AgentController.init{}`. Pass the pre-scanned registry into session creation as a parameter. `SkillManager` remains session-scoped.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, JCEF

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `agent/.../ui/AgentController.kt` | Modify | Create `SkillRegistry` at init, send skills to UI eagerly |
| `agent/.../runtime/ConversationSession.kt` | Modify | Accept `SkillRegistry` as parameter in `create()` and `load()` |

No new files. No test file changes needed (no existing tests call `create()`/`load()` directly).

---

### Task 1: Add SkillRegistry as a parameter to ConversationSession.create()

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt:240`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt:280-283`

- [ ] **Step 1: Add `skillRegistry` parameter to `create()` signature**

In `ConversationSession.kt`, change the `create()` method signature at line 240:

```kotlin
fun create(project: Project, agentService: AgentService, planMode: Boolean = false, skillRegistry: SkillRegistry? = null): ConversationSession {
```

The parameter is nullable with default `null` for backward compatibility — if not provided, falls back to creating one internally (existing behavior).

- [ ] **Step 2: Use the provided registry or fall back to internal creation**

Replace lines 280-284 in `create()`:

```kotlin
// Discover skills — use provided registry or create one
val actualSkillRegistry = skillRegistry ?: SkillRegistry(project.basePath, System.getProperty("user.home")).also { it.scan() }
val skillManager = SkillManager(actualSkillRegistry, project.basePath)
val skillDescriptions = actualSkillRegistry.buildDescriptionIndex(maxInputTokens)
```

- [ ] **Step 3: Verify the build compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL (signature change is backward-compatible due to nullable default)

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt
git commit -m "refactor(agent): accept SkillRegistry as optional parameter in ConversationSession.create()"
```

---

### Task 2: Add SkillRegistry as a parameter to ConversationSession.load()

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt:453`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt:460`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt:466-469`

- [ ] **Step 1: Add `skillRegistry` parameter to `load()` signature**

Change line 453:

```kotlin
fun load(sessionId: String, project: Project, agentService: AgentService, skillRegistry: SkillRegistry? = null): ConversationSession? {
```

- [ ] **Step 2: Pass the registry through to `create()` and remove redundant scan**

Change line 460 to pass the registry to the internal `create()` call:

```kotlin
val freshSession = create(project, agentService, skillRegistry = skillRegistry)
```

Then replace lines 466-469 (the redundant `SkillRegistry` creation):

```kotlin
// Use provided registry (from AgentController) or the one inside freshSession
val actualSkillManager = if (skillRegistry != null) {
    SkillManager(skillRegistry, project.basePath)
} else {
    freshSession.skillManager ?: SkillManager(
        SkillRegistry(project.basePath, System.getProperty("user.home")).also { it.scan() },
        project.basePath
    )
}
```

Then update line 526 where `skillManager` is used in the constructor:

```kotlin
skillManager = actualSkillManager,
```

- [ ] **Step 3: Verify the build compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt
git commit -m "refactor(agent): accept SkillRegistry as optional parameter in ConversationSession.load()"
```

---

### Task 3: Create SkillRegistry eagerly in AgentController and send skills to UI

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt:298` (init block)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt:711` (sendMessage)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt:1112` (resumeSession)

- [ ] **Step 1: Add SkillRegistry field and helper method**

Add a field and helper after the existing class-level properties in `AgentController`:

```kotlin
// Eager skill discovery — independent of session lifecycle
private val skillRegistry = SkillRegistry(
    project.basePath,
    System.getProperty("user.home")
).also { it.scan() }

private fun buildSkillsJson(): String {
    return kotlinx.serialization.json.buildJsonArray {
        skillRegistry.getUserInvocableSkills().forEach { skill ->
            add(kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(skill.name))
                put("description", kotlinx.serialization.json.JsonPrimitive(skill.description))
            })
        }
    }.toString()
}
```

- [ ] **Step 2: Send skills to UI at init time**

In the `init{}` block, after `dashboard.setMentionSearchProvider(MentionSearchProvider(project))` (line 298), add:

```kotlin
// Send skills to UI immediately (before any session is created)
dashboard.updateSkillsList(buildSkillsJson())
```

- [ ] **Step 3: Pass skillRegistry to ConversationSession.create()**

In `sendMessage()`, change line 711:

```kotlin
session = ConversationSession.create(project, agentService, planMode = planModeEnabled, skillRegistry = skillRegistry)
```

- [ ] **Step 4: Pass skillRegistry to ConversationSession.load() in resumeSession()**

In `resumeSession()`, change line 1112:

```kotlin
val loaded = ConversationSession.load(sessionId, project, agentService, skillRegistry = skillRegistry)
```

- [ ] **Step 5: Verify the build compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): create SkillRegistry eagerly at tab open, skills visible immediately"
```

---

### Task 4: Remove redundant updateSkillsList from wireSessionCallbacks

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt:982-991`

- [ ] **Step 1: Remove the updateSkillsList block from wireSessionCallbacks()**

Delete lines 982-991 in `wireSessionCallbacks()`:

```kotlin
        // Skills list — serialize to JSON for JCEF input bar
        val skillsJson = kotlinx.serialization.json.buildJsonArray {
            currentSession.skillManager?.registry?.getUserInvocableSkills()?.forEach { skill ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive(skill.name))
                    put("description", kotlinx.serialization.json.JsonPrimitive(skill.description))
                })
            }
        }.toString()
        dashboard.updateSkillsList(skillsJson)
```

The `setCefSkillCallbacks` block (lines 993-995) must remain — it references session-scoped `skillManager`.

- [ ] **Step 2: Verify the build compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "cleanup(agent): remove redundant updateSkillsList from wireSessionCallbacks"
```

---

### Task 5: Add skills to mirror panels in wireExtraPanel()

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt:492-493`

- [ ] **Step 1: Add updateSkillsList to wireExtraPanel()**

After `panel.setMentionSearchProvider(MentionSearchProvider(project))` at line 492, add:

```kotlin
panel.updateSkillsList(buildSkillsJson())
```

- [ ] **Step 2: Verify the build compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "fix(agent): send skills list to mirror panels so editor tabs show Skills chip"
```

---

### Task 6: Run full test suite and verify

**Files:**
- No file changes — verification only

- [ ] **Step 1: Run agent tests**

Run: `./gradlew :agent:test`
Expected: All tests pass. No existing tests call `ConversationSession.create()` or `load()` directly, so the signature changes don't break anything.

- [ ] **Step 2: Run full build**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify plugin API compatibility**

Run: `./gradlew verifyPlugin`
Expected: No API compatibility issues

- [ ] **Step 4: Commit (if any fixes were needed)**

Only if tests revealed issues that required fixes.
