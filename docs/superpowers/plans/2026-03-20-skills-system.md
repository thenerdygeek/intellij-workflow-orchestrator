# User-Extensible Skills System — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add user-extensible SKILL.md-based skills with progressive discovery, `/command` invocation, toolbar dropdown, LLM auto-trigger, and soft tool preferences.

**Architecture:** `SkillRegistry` scans project + user directories for SKILL.md files, parses YAML frontmatter. `SkillManager` manages active skill state per session. `activate_skill`/`deactivate_skill` tools let the LLM control skills. `ContextManager.skillAnchor` holds active skill instructions (compression-proof). Chat UI shows banner + toolbar dropdown.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform, kotlinx.serialization, YAML parsing (manual — frontmatter is simple key-value), JCEF for banner

**Spec:** `docs/superpowers/specs/2026-03-20-skills-system.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `agent/.../runtime/SkillRegistry.kt` | Scan directories, parse frontmatter, maintain skill index |
| `agent/.../runtime/SkillManager.kt` | Active skill state, activation/deactivation, callbacks |
| `agent/.../tools/builtin/ActivateSkillTool.kt` | LLM tool to activate a skill |
| `agent/.../tools/builtin/DeactivateSkillTool.kt` | LLM tool to deactivate current skill |
| `agent/src/test/.../runtime/SkillRegistryTest.kt` | Scan, parse, lookup tests |
| `agent/src/test/.../runtime/SkillManagerTest.kt` | Activation, deactivation, callback tests |
| `agent/src/test/.../tools/builtin/ActivateSkillToolTest.kt` | Tool validation tests |

### Modified Files
| File | Change |
|------|--------|
| `agent/.../context/ContextManager.kt` | Add `skillAnchor` slot (like `planAnchor`) |
| `agent/.../orchestrator/PromptAssembler.kt` | Add `skillDescriptions` parameter |
| `agent/.../runtime/ConversationSession.kt` | Add `skillManager` field, load skill descriptions at create() |
| `agent/.../tools/DynamicToolSelector.kt` | Add `preferredTools` parameter |
| `agent/.../ui/AgentController.kt` | Intercept `/` commands, wire callbacks, toolbar |
| `agent/.../ui/AgentCefPanel.kt` | Add skill banner JS bridge |
| `agent/.../ui/AgentDashboardPanel.kt` | Add skill toolbar dropdown + banner delegation |
| `agent/.../resources/webview/agent-chat.html` | Add banner CSS + JS |
| `agent/.../AgentService.kt` | Register tools, add `currentSkillManager` |
| `agent/.../tools/ToolCategoryRegistry.kt` | Add tools to planning category |

---

## Task 1: SkillRegistry — Scan + Parse SKILL.md Files

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SkillRegistry.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SkillRegistryTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SkillRegistryTest {

    @TempDir lateinit var tempDir: Path

    private fun writeSkill(base: File, name: String, content: String) {
        val dir = File(base, ".workflow/skills/$name")
        dir.mkdirs()
        File(dir, "SKILL.md").writeText(content)
    }

    @Test
    fun `scan finds skills in project directory`() {
        val projectDir = tempDir.toFile()
        writeSkill(projectDir, "hotfix", """
            ---
            name: hotfix
            description: Create hotfix for production
            ---
            ## Steps
            1. Do stuff
        """.trimIndent())

        val registry = SkillRegistry(projectDir.absolutePath, "/nonexistent")
        val skills = registry.scan()
        assertEquals(1, skills.size)
        assertEquals("hotfix", skills[0].name)
        assertEquals("Create hotfix for production", skills[0].description)
        assertEquals(SkillRegistry.SkillScope.PROJECT, skills[0].scope)
    }

    @Test
    fun `scan finds skills in user directory`() {
        val userDir = File(tempDir.toFile(), "user")
        val skillDir = File(userDir, ".workflow-orchestrator/skills/standup")
        skillDir.mkdirs()
        File(skillDir, "SKILL.md").writeText("""
            ---
            name: standup
            description: Generate standup context
            ---
            Content here.
        """.trimIndent())

        val registry = SkillRegistry("/nonexistent", userDir.absolutePath)
        val skills = registry.scan()
        assertEquals(1, skills.size)
        assertEquals(SkillRegistry.SkillScope.USER, skills[0].scope)
    }

    @Test
    fun `project skill overrides user skill with same name`() {
        val projectDir = tempDir.toFile()
        val userDir = File(tempDir.toFile(), "user")
        writeSkill(projectDir, "deploy", "---\nname: deploy\ndescription: Project deploy\n---\nProject version")
        val uSkillDir = File(userDir, ".workflow-orchestrator/skills/deploy")
        uSkillDir.mkdirs()
        File(uSkillDir, "SKILL.md").writeText("---\nname: deploy\ndescription: User deploy\n---\nUser version")

        val registry = SkillRegistry(projectDir.absolutePath, userDir.absolutePath)
        val skills = registry.scan()
        assertEquals(1, skills.size)
        assertEquals(SkillRegistry.SkillScope.PROJECT, skills[0].scope)
        assertEquals("Project deploy", skills[0].description)
    }

    @Test
    fun `parses preferred-tools from frontmatter`() {
        val projectDir = tempDir.toFile()
        writeSkill(projectDir, "review", """
            ---
            name: review
            description: Code review prep
            preferred-tools: [sonar_issues, sonar_coverage, run_tests]
            ---
            Content
        """.trimIndent())

        val registry = SkillRegistry(projectDir.absolutePath, "/nonexistent")
        val skill = registry.getSkill("review")
        assertNotNull(skill)
        assertEquals(listOf("sonar_issues", "sonar_coverage", "run_tests"), skill!!.preferredTools)
    }

    @Test
    fun `parses disable-model-invocation and user-invocable`() {
        val projectDir = tempDir.toFile()
        writeSkill(projectDir, "dangerous", """
            ---
            name: dangerous
            description: Dangerous operation
            disable-model-invocation: true
            user-invocable: true
            ---
            Content
        """.trimIndent())

        val skill = SkillRegistry(projectDir.absolutePath, "/nonexistent").scan()[0]
        assertTrue(skill.disableModelInvocation)
        assertTrue(skill.userInvocable)
    }

    @Test
    fun `getSkillContent returns full file content`() {
        val projectDir = tempDir.toFile()
        writeSkill(projectDir, "test", "---\nname: test\ndescription: Test\n---\n## Full Content\nDetailed instructions here.")

        val registry = SkillRegistry(projectDir.absolutePath, "/nonexistent")
        registry.scan()
        val content = registry.getSkillContent("test")
        assertNotNull(content)
        assertTrue(content!!.contains("Full Content"))
        assertTrue(content.contains("Detailed instructions"))
    }

    @Test
    fun `buildDescriptionIndex formats compact list`() {
        val projectDir = tempDir.toFile()
        writeSkill(projectDir, "a", "---\nname: alpha\ndescription: Does alpha things\n---\nContent")
        writeSkill(projectDir, "b", "---\nname: beta\ndescription: Does beta things\n---\nContent")

        val registry = SkillRegistry(projectDir.absolutePath, "/nonexistent")
        registry.scan()
        val index = registry.buildDescriptionIndex()
        assertTrue(index.contains("alpha"))
        assertTrue(index.contains("Does alpha things"))
        assertTrue(index.contains("beta"))
    }

    @Test
    fun `scan handles malformed YAML gracefully`() {
        val projectDir = tempDir.toFile()
        writeSkill(projectDir, "bad", "Not valid YAML frontmatter at all")
        writeSkill(projectDir, "good", "---\nname: good\ndescription: Valid\n---\nContent")

        val registry = SkillRegistry(projectDir.absolutePath, "/nonexistent")
        val skills = registry.scan()
        assertEquals(1, skills.size)
        assertEquals("good", skills[0].name)
    }

    @Test
    fun `scan returns empty when no skills directory exists`() {
        val registry = SkillRegistry("/nonexistent", "/also-nonexistent")
        assertTrue(registry.scan().isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.SkillRegistryTest" --rerun --no-build-cache
```

- [ ] **Step 3: Implement SkillRegistry**

Key implementation details:
- Parse YAML frontmatter manually (split on `---`, parse key: value lines) — no external YAML library needed
- `preferred-tools` parsed from `[tool1, tool2]` format
- `scan()` scans project dir first (`.workflow/skills/*/SKILL.md`), then user dir (`~/.workflow-orchestrator/skills/*/SKILL.md`)
- Dedup by name — project wins
- All file operations wrapped in try/catch
- `buildDescriptionIndex()` returns: `"Available skills:\n- /alpha — Does alpha things\n- /beta — Does beta things"`

- [ ] **Step 4: Run tests**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.SkillRegistryTest" --rerun --no-build-cache
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SkillRegistry.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SkillRegistryTest.kt
git commit -m "feat(agent): SkillRegistry — scan and parse SKILL.md files from project + user directories"
```

---

## Task 2: SkillManager — Active Skill State Machine

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SkillManager.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SkillManagerTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SkillManagerTest {

    @TempDir lateinit var tempDir: Path

    private fun createRegistry(): SkillRegistry {
        val projectDir = tempDir.toFile()
        val dir = File(projectDir, ".workflow/skills/hotfix")
        dir.mkdirs()
        File(dir, "SKILL.md").writeText("""
            ---
            name: hotfix
            description: Hotfix workflow
            preferred-tools: [jira_get_ticket, bamboo_trigger_build]
            ---
            ## Hotfix
            1. Get ticket ${'$'}ARGUMENTS
            2. Create branch
        """.trimIndent())
        return SkillRegistry(projectDir.absolutePath, "/nonexistent").also { it.scan() }
    }

    @Test
    fun `activateSkill loads content and fires callback`() {
        val mgr = SkillManager(createRegistry())
        var activated = false
        mgr.onSkillActivated = { activated = true }
        val skill = mgr.activateSkill("hotfix")
        assertNotNull(skill)
        assertTrue(activated)
        assertTrue(mgr.isActive())
    }

    @Test
    fun `activateSkill substitutes ARGUMENTS`() {
        val mgr = SkillManager(createRegistry())
        val skill = mgr.activateSkill("hotfix", "PROJ-456")
        assertTrue(skill!!.content.contains("PROJ-456"))
        assertFalse(skill.content.contains("\$ARGUMENTS"))
    }

    @Test
    fun `activateSkill without arguments leaves placeholders`() {
        val mgr = SkillManager(createRegistry())
        val skill = mgr.activateSkill("hotfix")
        assertTrue(skill!!.content.contains("\$ARGUMENTS"))
    }

    @Test
    fun `deactivateSkill clears state and fires callback`() {
        val mgr = SkillManager(createRegistry())
        mgr.activateSkill("hotfix")
        var deactivated = false
        mgr.onSkillDeactivated = { deactivated = true }
        mgr.deactivateSkill()
        assertFalse(mgr.isActive())
        assertTrue(deactivated)
    }

    @Test
    fun `activating new skill deactivates previous`() {
        val mgr = SkillManager(createRegistry())
        mgr.activateSkill("hotfix")
        var deactivated = false
        mgr.onSkillDeactivated = { deactivated = true }
        mgr.activateSkill("hotfix", "new-args")
        assertTrue(deactivated)
        assertTrue(mgr.isActive())
    }

    @Test
    fun `getPreferredTools returns active skill tools`() {
        val mgr = SkillManager(createRegistry())
        mgr.activateSkill("hotfix")
        val tools = mgr.getPreferredTools()
        assertTrue(tools.contains("jira_get_ticket"))
        assertTrue(tools.contains("bamboo_trigger_build"))
    }

    @Test
    fun `getPreferredTools returns empty when no skill active`() {
        val mgr = SkillManager(createRegistry())
        assertTrue(mgr.getPreferredTools().isEmpty())
    }

    @Test
    fun `activateSkill returns null for unknown skill`() {
        val mgr = SkillManager(createRegistry())
        assertNull(mgr.activateSkill("nonexistent"))
    }
}
```

- [ ] **Step 2: Implement SkillManager**

```kotlin
class SkillManager(private val registry: SkillRegistry) {
    data class ActiveSkill(
        val entry: SkillRegistry.SkillEntry,
        val content: String,
        val arguments: String?
    )

    var activeSkill: ActiveSkill? = null; private set

    var onSkillActivated: ((ActiveSkill) -> Unit)? = null
    var onSkillDeactivated: (() -> Unit)? = null

    fun activateSkill(name: String, arguments: String? = null): ActiveSkill? {
        val entry = registry.getSkill(name) ?: return null
        val rawContent = registry.getSkillContent(name) ?: return null

        if (activeSkill != null) deactivateSkill()

        val content = if (arguments != null) {
            rawContent.replace("\$ARGUMENTS", arguments)
                .also { /* split args for $1, $2, etc. */
                    val parts = arguments.split(" ")
                    var result = it
                    parts.forEachIndexed { i, part -> result = result.replace("\$${i + 1}", part) }
                }
        } else rawContent

        val skill = ActiveSkill(entry, content, arguments)
        activeSkill = skill
        onSkillActivated?.invoke(skill)
        return skill
    }

    fun deactivateSkill() {
        if (activeSkill != null) {
            activeSkill = null
            onSkillDeactivated?.invoke()
        }
    }

    fun getPreferredTools(): Set<String> = activeSkill?.entry?.preferredTools?.toSet() ?: emptySet()
    fun isActive(): Boolean = activeSkill != null
}
```

- [ ] **Step 3: Run tests, commit**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.SkillManagerTest" --rerun --no-build-cache
git commit -m "feat(agent): SkillManager — active skill state with argument substitution"
```

---

## Task 3: ActivateSkillTool + DeactivateSkillTool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ActivateSkillTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/DeactivateSkillTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/ActivateSkillToolTest.kt`

- [ ] **Step 1: Implement ActivateSkillTool**

```kotlin
class ActivateSkillTool : AgentTool {
    name = "activate_skill"
    description = "Activate a user-defined skill by name. Skills provide workflow instructions. Available skills are listed in the system context."
    parameters: name (required string), arguments (optional string)
    allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    execute():
    - Get SkillManager from AgentService.currentSkillManager
    - Check skill exists, check not disable-model-invocation
    - Call skillManager.activateSkill(name, arguments)
    - Return: "Skill '{name}' activated. Follow the skill instructions."
}
```

- [ ] **Step 2: Implement DeactivateSkillTool**

```kotlin
class DeactivateSkillTool : AgentTool {
    name = "deactivate_skill"
    description = "Deactivate the current active skill when the workflow is complete."
    parameters: none required
    allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    execute():
    - Get SkillManager, call deactivateSkill()
    - Return: "Skill deactivated."
}
```

- [ ] **Step 3: Write tests for ActivateSkillTool**

Test: metadata, missing name, unknown skill, disable-model-invocation check.

- [ ] **Step 4: Run tests, commit**

```bash
git commit -m "feat(agent): activate_skill and deactivate_skill tools"
```

---

## Task 4: Context Integration — SkillAnchor + PromptAssembler + DynamicToolSelector

**Files:**
- Modify: `agent/.../context/ContextManager.kt` — add `skillAnchor` field + `setSkillAnchor()`
- Modify: `agent/.../orchestrator/PromptAssembler.kt` — add `skillDescriptions` parameter
- Modify: `agent/.../tools/DynamicToolSelector.kt` — add `preferredTools` parameter
- Modify: `agent/.../runtime/ConversationSession.kt` — add `skillManager`, load descriptions at create()

- [ ] **Step 1: Add skillAnchor to ContextManager**

Same pattern as `planAnchor`:
```kotlin
private var skillAnchor: ChatMessage? = null

fun setSkillAnchor(message: ChatMessage?) {
    skillAnchor = message
    totalTokens = TokenEstimator.estimate(getMessages())
}
```

Update `getMessages()` to include `skillAnchor` after `planAnchor`.
Update `reset()` to clear `skillAnchor`.

- [ ] **Step 2: Add skillDescriptions to PromptAssembler**

Add `skillDescriptions: String? = null` parameter to `buildSingleAgentPrompt()`. Inject as `<available_skills>` section if non-null.

- [ ] **Step 3: Add preferredTools to DynamicToolSelector**

In `selectTools()`, add `preferredTools: Set<String> = emptySet()` parameter. Add preferred tools to `selectedNames` before filtering:
```kotlin
selectedNames.addAll(preferredTools)
```

- [ ] **Step 4: Wire into ConversationSession.create()**

Add `val skillManager: SkillManager` field. In `create()`:
- Build `SkillRegistry(projectBasePath, userHome)`, scan
- Create `SkillManager(registry)`
- Get `skillDescriptions = registry.buildDescriptionIndex()`
- Pass to `promptAssembler.buildSingleAgentPrompt(skillDescriptions = ...)`

- [ ] **Step 5: Verify compilation and tests**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(agent): skill context integration — skillAnchor, descriptions in prompt, preferred tools"
```

---

## Task 5: AgentController — `/command` Intercept + Skill Callbacks

**Files:**
- Modify: `agent/.../ui/AgentController.kt`
- Modify: `agent/.../AgentService.kt`

- [ ] **Step 1: Intercept `/` commands in executeTask()**

At the top of `executeTask()`, before any orchestrator logic:
```kotlin
if (task.startsWith("/")) {
    val parts = task.removePrefix("/").split(" ", limit = 2)
    val skillName = parts[0]
    val args = parts.getOrNull(1)
    val skillMgr = session?.skillManager
    if (skillMgr != null) {
        val activated = skillMgr.activateSkill(skillName, args)
        if (activated != null) {
            // Inject skill content into context
            session.contextManager.setSkillAnchor(ChatMessage(role = "system", content = "<active_skill>\n${activated.content}\n</active_skill>"))
            // Show banner
            dashboard.showSkillBanner(activated.entry.name)
            // Send the arguments (or a prompt) to the LLM as the user message
            val userMessage = args ?: "I've activated the ${activated.entry.name} skill. Please follow the skill instructions."
            // Continue with normal executeTask using userMessage instead of task
            // ... (fall through to normal flow with modified message)
        } else {
            dashboard.appendStatus("Skill '$skillName' not found. Available: ${session.skillManager.registry.getUserInvocableSkills().joinToString { it.name }}", RichStreamingPanel.StatusType.WARNING)
            return
        }
    }
}
```

- [ ] **Step 2: Wire SkillManager callbacks**

In the callback wiring section (alongside plan + question callbacks):
```kotlin
agentSvc.currentSkillManager = currentSession.skillManager

currentSession.skillManager.onSkillActivated = { skill ->
    currentSession.contextManager.setSkillAnchor(
        ChatMessage(role = "system", content = "<active_skill>\n${skill.content}\n</active_skill>")
    )
    dashboard.showSkillBanner(skill.entry.name)
}
currentSession.skillManager.onSkillDeactivated = {
    currentSession.contextManager.setSkillAnchor(null)
    dashboard.hideSkillBanner()
}
```

- [ ] **Step 3: Add currentSkillManager to AgentService**

```kotlin
@Volatile var currentSkillManager: SkillManager? = null
```

Register both tools:
```kotlin
register(ActivateSkillTool())
register(DeactivateSkillTool())
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(agent): /command intercept + skill callbacks in AgentController"
```

---

## Task 6: UI — Banner + Toolbar Dropdown

**Files:**
- Modify: `agent/.../resources/webview/agent-chat.html` — banner CSS + JS
- Modify: `agent/.../ui/AgentCefPanel.kt` — banner bridge methods
- Modify: `agent/.../ui/AgentDashboardPanel.kt` — banner delegation + toolbar dropdown

- [ ] **Step 1: Add skill banner CSS + JS to agent-chat.html**

CSS:
```css
.skill-banner {
    background: rgba(249, 226, 175, 0.1);
    border-bottom: 1px solid var(--border);
    padding: 6px 16px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 12px;
    color: #f9e2af;
}
.skill-banner-dismiss { cursor: pointer; padding: 2px 6px; border-radius: 4px; }
.skill-banner-dismiss:hover { background: rgba(255,255,255,0.1); }
```

JS:
```javascript
function showSkillBanner(name) {
    hideSkillBanner();
    var banner = document.createElement('div');
    banner.className = 'skill-banner';
    banner.id = 'skill-banner';
    banner.innerHTML = '<span>🔧 Skill active: <strong>' + esc(name) + '</strong></span>'
        + '<span class="skill-banner-dismiss" onclick="dismissSkill()">×</span>';
    container.parentElement.insertBefore(banner, container);
}

function hideSkillBanner() {
    var b = document.getElementById('skill-banner');
    if (b) b.remove();
}

function dismissSkill() {
    if (window._deactivateSkill) window._deactivateSkill();
    hideSkillBanner();
}
```

- [ ] **Step 2: Add JCEF bridges to AgentCefPanel**

```kotlin
private val deactivateSkillQuery = JBCefJSQuery.create(browser)
var onSkillDismissed: (() -> Unit)? = null

// In loadHandler:
js("window._deactivateSkill = function() { ${deactivateSkillQuery.inject("'dismiss'")} }")

// Methods:
fun showSkillBanner(name: String) { callJs("showSkillBanner(${jsonStr(name)})") }
fun hideSkillBanner() { callJs("hideSkillBanner()") }
```

- [ ] **Step 3: Add toolbar dropdown to AgentDashboardPanel**

Add a `JBPopupMenu`-based dropdown triggered by a toolbar button:
```kotlin
private val skillsButton = JButton("Skills").apply {
    addActionListener { showSkillsPopup() }
}

private fun showSkillsPopup() {
    val skills = currentSkillRegistry?.getUserInvocableSkills() ?: return
    val popup = JBPopupMenu()
    // Group by scope
    val projectSkills = skills.filter { it.scope == SkillRegistry.SkillScope.PROJECT }
    val userSkills = skills.filter { it.scope == SkillRegistry.SkillScope.USER }

    if (projectSkills.isNotEmpty()) {
        popup.add(JMenuItem("— Project Skills —").apply { isEnabled = false })
        projectSkills.forEach { skill ->
            popup.add(JMenuItem("/${skill.name} — ${skill.description}").apply {
                addActionListener { onSkillSelected?.invoke(skill.name) }
            })
        }
    }
    // Similar for user skills
    popup.show(skillsButton, 0, skillsButton.height)
}
```

- [ ] **Step 4: Wire banner dismiss + toolbar to controller**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(agent): skill banner UI + toolbar dropdown"
```

---

## Task 7: Register Tools + Update Categories + Final Verification

**Files:**
- Modify: `agent/.../tools/ToolCategoryRegistry.kt`
- Modify: `agent/.../tools/DynamicToolSelector.kt`
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Add tools to planning category**

```kotlin
tools = listOf("create_plan", "update_plan_step", "ask_questions", "save_memory", "activate_skill", "deactivate_skill")
```

- [ ] **Step 2: Add keyword triggers**

```kotlin
"skill" to setOf("activate_skill", "deactivate_skill"),
"workflow" to setOf("activate_skill"),
```

- [ ] **Step 3: Update agent/CLAUDE.md**

Update tool count (52), add skills section.

- [ ] **Step 4: Full verification**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
./gradlew verifyPlugin
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(agent): register skill tools, update categories, docs"
```

---

## Implementation Order

```
Task 1: SkillRegistry (scan + parse)              ← independent
Task 2: SkillManager (state machine)               ← depends on Task 1
Task 3: activate_skill + deactivate_skill tools    ← depends on Task 2
Task 4: Context integration (anchors + prompt)     ← depends on Tasks 1, 2
Task 5: AgentController wiring                     ← depends on Tasks 3, 4
Task 6: UI (banner + toolbar)                      ← depends on Task 5
Task 7: Final registration + verification          ← depends on all
```
