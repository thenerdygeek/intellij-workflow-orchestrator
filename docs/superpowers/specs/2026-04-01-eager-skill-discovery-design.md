# Eager Skill Discovery — Design Spec

## Problem

Skills don't appear in the `/` dropdown or the Skills chip until the user sends their first message. This is because `SkillRegistry` is created inside `ConversationSession.create()`, and `updateSkillsList()` is called inside `wireSessionCallbacks()` — both of which only execute when a session is created (triggered by the first user message).

The user opens the agent tab, types `/`, sees nothing. They must send a message first, then type `/` again to see skills. This makes the skill system appear broken.

## Root Cause

`SkillRegistry` (filesystem scan of SKILL.md files) is coupled to `ConversationSession` lifecycle. The scan is lightweight (directory walk + YAML frontmatter parsing) but is bundled with heavy session creation work (PSI tree walk, memory loading, context bridge setup).

**Current call chain:**
```
AgentController.sendMessage(task)
  → ConversationSession.create(project, agentService)    // HEAVY: PSI walk, memory, context bridge
    → SkillRegistry(basePath, userHome).scan()            // LIGHT: filesystem only
    → SkillManager(skillRegistry, basePath)
  → wireSessionCallbacks(session)
    → dashboard.updateSkillsList(skillsJson)              // Skills reach UI HERE
```

## Solution

Decouple `SkillRegistry` from `ConversationSession`. Create it eagerly in `AgentController.init{}` (where `MentionSearchProvider` is already wired), send skills to the UI immediately, and pass the pre-scanned registry into session creation.

**New call chain:**
```
AgentController.init{}
  → SkillRegistry(basePath, userHome).scan()              // Eager, at tab open
  → dashboard.updateSkillsList(skillsJson)                // Skills visible IMMEDIATELY

AgentController.sendMessage(task)
  → ConversationSession.create(project, agentService, skillRegistry)  // Reuses existing registry
    → SkillManager(skillRegistry, basePath)               // No redundant scan
  → wireSessionCallbacks(session)                         // No redundant updateSkillsList
```

---

## Architecture

### Lifecycle Scopes

| Component | Scope | Created When | Purpose |
|---|---|---|---|
| `SkillRegistry` | Controller (tab lifetime) | `AgentController.init{}` | Discovery, metadata, description index |
| `SkillManager` | Session (conversation lifetime) | `ConversationSession.create()` | Active skill state, preprocessing, callbacks |

### Design Principle

`SkillRegistry` is a **read-only data structure** after `scan()`. It has no mutable state related to active skills. This makes it safe to share across sessions — multiple sessions can reference the same registry.

`SkillManager` holds **mutable session state**: the active skill, callbacks, preferred tools. It must remain session-scoped because each conversation has its own active skill.

---

## File Changes

### 1. `AgentController.kt` — Create SkillRegistry eagerly

**In `init{}` block (after `MentionSearchProvider` wiring, ~line 298):**

```kotlin
// Eager skill discovery — independent of session lifecycle
private val skillRegistry = SkillRegistry(
    project.basePath,
    System.getProperty("user.home")
).also { it.scan() }

// In init{} block:
dashboard.updateSkillsList(buildSkillsJson(skillRegistry))
```

**Add helper method:**

```kotlin
private fun buildSkillsJson(registry: SkillRegistry): String {
    return kotlinx.serialization.json.buildJsonArray {
        registry.getUserInvocableSkills().forEach { skill ->
            add(kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(skill.name))
                put("description", kotlinx.serialization.json.JsonPrimitive(skill.description))
            })
        }
    }.toString()
}
```

**In `wireSessionCallbacks()`:**
- Remove the `updateSkillsList` block (lines 982–991) — already sent at init
- Keep skill activation/deactivation callback wiring (lines 963–980) — these are session-scoped

**In `wireExtraPanel()`:**
- Add `panel.updateSkillsList(buildSkillsJson(skillRegistry))` — mirror panels also need the list

**In `resumeSession()`:**
- Remove the `SkillRegistry` creation inside `ConversationSession.load()` — pass the controller's registry instead

### 2. `ConversationSession.kt` — Accept SkillRegistry as parameter

**`create()` method (~line 276):**

Before:
```kotlin
val skillRegistry = SkillRegistry(project.basePath, System.getProperty("user.home"))
skillRegistry.scan()
val skillManager = SkillManager(skillRegistry, project.basePath)
```

After:
```kotlin
// skillRegistry passed as parameter — already scanned by AgentController
val skillManager = SkillManager(skillRegistry, project.basePath)
```

**Signature change:**
```kotlin
fun create(
    project: Project,
    agentService: AgentService,
    planMode: Boolean = false,
    skillRegistry: SkillRegistry  // NEW parameter
): ConversationSession
```

**`load()` method (~line 466):**

Same change — accept `skillRegistry` as parameter instead of creating internally.

```kotlin
fun load(
    project: Project,
    agentService: AgentService,
    sessionId: String,
    skillRegistry: SkillRegistry  // NEW parameter
): ConversationSession
```

### 3. No changes to `SkillRegistry.kt` or `SkillManager.kt`

Both classes are already correctly structured for this refactor. `SkillRegistry` is stateless after `scan()`, `SkillManager` is session-scoped.

---

## Data Flow

### Tab Open (No Session)

```
AgentController.init{}
  ├─ SkillRegistry(basePath, userHome).scan()
  │   └─ Scans: builtin resources → ~/.workflow-orchestrator/skills/ → .workflow/skills/
  │   └─ Result: Map<String, SkillEntry> with 5 built-in + any user/project skills
  ├─ buildSkillsJson(registry)
  │   └─ Filters: userInvocable=true → 4 skills (excludes interactive-debugging)
  │   └─ Serializes: [{name, description}, ...]
  └─ dashboard.updateSkillsList(json)
      └─ AgentCefPanel.callJs("updateSkillsList(...)") → queued if page not loaded
          └─ jcef-bridge.ts → chatStore.updateSkillsList(skills)
              └─ SkillsChip renders (items.length > 0)
              └─ InputBar: typing / triggers SkillDropdown with filtered list
```

### First Message (Session Created)

```
AgentController.sendMessage(task)
  └─ ConversationSession.create(project, agentService, planMode, skillRegistry)
      └─ SkillManager(skillRegistry, basePath)  // reuses controller's registry
      └─ skillRegistry.buildDescriptionIndex()   // for system prompt injection
  └─ wireSessionCallbacks(session)
      └─ Wire skill activation/deactivation callbacks only
      └─ NO updateSkillsList call (already done at init)
```

### Skill Activation

```
User types /systematic-debugging in chat
  └─ RichInput detects / trigger → SkillDropdown appears
  └─ User clicks skill → insertChip(mention)
  └─ User presses Enter → sendMessage includes skill mention
  └─ AgentController.handleSkillCommand("systematic-debugging")
      └─ skillManager.activateSkill("systematic-debugging")
      └─ onSkillActivated callback → bridge.setSkillAnchor() + dashboard.showSkillBanner()
```

---

## Edge Cases

### 1. JCEF page not loaded when skills are sent

Already handled. `AgentCefPanel.callJs()` queues calls in `pendingCalls` when `pageLoaded=false`. When the page finishes loading, `pendingCalls` are flushed. Skills will appear as soon as the React app mounts.

### 2. No skills exist (empty directories)

`SkillsChip` returns `null` when `items.length === 0` — the chip is hidden. The `/` dropdown shows `CommandEmpty`: "No skills found." No change needed.

### 3. User adds a skill file mid-session

Not addressed in this fix. No hot-reload. User must open a new agent tab or restart the IDE. This is a Layer 2 improvement.

### 4. Mirror panels (editor tabs)

`wireExtraPanel()` must call `panel.updateSkillsList(buildSkillsJson(skillRegistry))` so editor-tab mirrors also show the Skills chip and `/` dropdown. Currently only `setMentionSearchProvider` is called there.

### 5. Session resume

`resumeSession()` should pass the controller's `skillRegistry` to `ConversationSession.load()` instead of creating a new one internally. This ensures consistency and avoids a redundant filesystem scan.

### 6. Multiple sessions in same tab

The `skillRegistry` is shared across sessions (new chat, resume). Since it's read-only after `scan()`, this is safe. `SkillManager` is per-session, so active skill state is isolated.

---

## Testing Strategy

### Existing Tests (No Changes Needed)

- **SkillRegistryTest** (23 tests) — scanning, priority, frontmatter, budget. `SkillRegistry` is unchanged.
- **SkillManagerTest** (10 tests) — activation, preprocessing, callbacks. `SkillManager` is unchanged.
- **ActivateSkillToolTest** (9 tests) — tool validation, forked execution. Tool is unchanged.

### Tests to Update

- **ConversationSession tests** — update `create()` and `load()` call sites to pass a `SkillRegistry` instance. Use `SkillRegistry(tmpDir, tmpDir, loadBuiltins = false)` with `@TempDir` for test isolation.

### New Test (Optional)

- **AgentController integration test** — verify that `updateSkillsList` is called during init with the correct JSON structure. This may be difficult to test in isolation due to JCEF dependency; manual verification is acceptable.

---

## Impact Summary

| Aspect | Change |
|---|---|
| Lines of code | ~30 lines changed across 2 files |
| Risk | Low — `SkillRegistry` is already stateless after scan |
| Breaking changes | `ConversationSession.create()` and `load()` signatures change (new parameter) |
| Performance | Skill scan moves from first-message to tab-open. Scan is ~5ms (directory walk + YAML parse). No user-visible impact. |
| Backward compatibility | No user-facing changes. Skills appear sooner. |

## Out of Scope (Layer 2)

- Hot-reload (file watcher for new skills)
- Skill chaining / composition
- Model override per skill
- Skill-scoped hooks
- New built-in domain skills (hotfix, review-prep, standup, etc.)
- Supporting file access patterns
