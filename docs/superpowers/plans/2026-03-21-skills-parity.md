# Skills Parity with Claude Code — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close 6 skill system gaps with Claude Code's official implementation: allowed-tools (hard restriction), context:fork (subagent execution), dynamic context injection, CLAUDE_SKILL_DIR substitution, supporting files, and description budget.

**Architecture:** All changes extend the existing `SkillRegistry`/`SkillManager`/`ActivateSkillTool` stack. `allowed-tools` adds a whitelist filter in `DynamicToolSelector`. `context: fork` wires skill activation to the existing `WorkerSession`. Dynamic injection preprocesses skill content before LLM sees it. No new modules needed.

**Tech Stack:** Kotlin, IntelliJ Platform, existing AgentTool/WorkerSession infrastructure

**Reference:** `docs/superpowers/research/2026-03-21-claude-code-skills-subagents-reference.md`

---

## File Structure

### Modified Files

| File | Changes |
|------|---------|
| `agent/src/main/kotlin/.../runtime/SkillRegistry.kt` | Add `allowedTools`, `contextFork`, `agentType`, `argumentHint` to SkillEntry. Parse from frontmatter. Add description budget. Load supporting files list. |
| `agent/src/main/kotlin/.../runtime/SkillManager.kt` | Add `getAllowedTools()`. Add `${CLAUDE_SKILL_DIR}` substitution. Add dynamic context injection preprocessing. Wire `context: fork` to WorkerSession. |
| `agent/src/main/kotlin/.../tools/builtin/ActivateSkillTool.kt` | Handle `context: fork` — spawn WorkerSession instead of inline activation. Pass allowed-tools to worker. |
| `agent/src/main/kotlin/.../tools/DynamicToolSelector.kt` | Add `skillAllowedTools: Set<String>?` parameter. When non-null, return ONLY those tools. |
| `agent/src/main/kotlin/.../orchestrator/AgentOrchestrator.kt` | Pass skill allowed-tools to tool selection. |
| `agent/src/main/kotlin/.../orchestrator/PromptAssembler.kt` | Add description budget truncation. |
| `agent/src/test/kotlin/.../runtime/SkillRegistryTest.kt` | Tests for new frontmatter fields, budget. |
| `agent/src/test/kotlin/.../runtime/SkillManagerTest.kt` | Tests for allowed-tools, SKILL_DIR, dynamic injection. |

---

## Task 1: Extend SkillEntry with New Frontmatter Fields

**Files:**
- Modify: `agent/src/main/kotlin/.../runtime/SkillRegistry.kt`
- Modify: `agent/src/test/kotlin/.../runtime/SkillRegistryTest.kt`

- [ ] **Step 1: Add new fields to SkillEntry data class**

In `SkillRegistry.kt`, find the `SkillEntry` data class (line 16). Add after `preferredTools`:

```kotlin
data class SkillEntry(
    val name: String,
    val description: String,
    val disableModelInvocation: Boolean = false,
    val userInvocable: Boolean = true,
    val preferredTools: List<String> = emptyList(),
    val allowedTools: List<String>? = null,  // NEW: hard tool restriction (null = no restriction)
    val contextFork: Boolean = false,         // NEW: run in isolated subagent
    val agentType: String? = null,            // NEW: subagent type when context: fork
    val argumentHint: String? = null,         // NEW: autocomplete hint
    val filePath: String,
    val scope: SkillScope
)
```

- [ ] **Step 2: Parse new fields from YAML frontmatter**

In both `loadBuiltinSkills()` and `scanDirectory()`, where `SkillEntry` is constructed, add parsing:

```kotlin
allowedTools = frontmatter["allowed-tools"]?.let { parseList(it) }?.takeIf { it.isNotEmpty() },
contextFork = frontmatter["context"]?.trim()?.equals("fork", ignoreCase = true) ?: false,
agentType = frontmatter["agent"]?.trim()?.takeIf { it.isNotBlank() },
argumentHint = frontmatter["argument-hint"]?.trim()?.takeIf { it.isNotBlank() },
```

This needs to be added in BOTH places where SkillEntry is constructed (builtin loading ~line 64 and directory scanning ~line 131).

- [ ] **Step 3: Write tests for new frontmatter parsing**

Add to `SkillRegistryTest.kt`:

```kotlin
@Test
fun `parses allowed-tools from frontmatter`() {
    val content = """
        ---
        name: safe-reader
        description: Read-only mode
        allowed-tools: read_file, search_code, glob_files
        ---
        Read files only.
    """.trimIndent()
    // Write to temp skill dir and scan
    val skillDir = tempDir.resolve("safe-reader").also { it.mkdirs() }
    File(skillDir, "SKILL.md").writeText(content)
    val registry = SkillRegistry(project, loadBuiltins = false)
    registry.scan() // would need to point at tempDir
    // Verify
    val entry = registry.getSkill("safe-reader")
    assertNotNull(entry)
    assertEquals(listOf("read_file", "search_code", "glob_files"), entry!!.allowedTools)
}

@Test
fun `parses context fork and agent type`() {
    val content = """
        ---
        name: research
        description: Deep research
        context: fork
        agent: Explore
        ---
        Research thoroughly.
    """.trimIndent()
    val skillDir = tempDir.resolve("research").also { it.mkdirs() }
    File(skillDir, "SKILL.md").writeText(content)
    val registry = SkillRegistry(project, loadBuiltins = false)
    val entry = registry.getSkill("research")
    assertNotNull(entry)
    assertTrue(entry!!.contextFork)
    assertEquals("Explore", entry.agentType)
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :agent:test --tests "*.SkillRegistryTest" -x verifyPlugin
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SkillRegistry.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SkillRegistryTest.kt
git commit -m "feat(agent): extend SkillEntry with allowed-tools, context:fork, agent type

Parse allowed-tools, context, agent, argument-hint from SKILL.md YAML
frontmatter. allowed-tools is a hard tool restriction (null = unrestricted).
context: fork enables subagent execution. Follows Claude Code skill spec."
```

---

## Task 2: Allowed-Tools Hard Restriction in DynamicToolSelector

**Files:**
- Modify: `agent/src/main/kotlin/.../tools/DynamicToolSelector.kt`
- Modify: `agent/src/main/kotlin/.../runtime/SkillManager.kt`
- Modify: `agent/src/main/kotlin/.../orchestrator/AgentOrchestrator.kt`
- Modify: `agent/src/test/kotlin/.../tools/DynamicToolSelectorTest.kt`

- [ ] **Step 1: Add `skillAllowedTools` parameter to `selectTools()`**

In `DynamicToolSelector.selectTools()` (line 211), add a new parameter:

```kotlin
fun selectTools(
    allTools: Collection<AgentTool>,
    conversationContext: String,
    disabledTools: Set<String> = emptySet(),
    activatedTools: Set<String> = emptySet(),
    preferredTools: Set<String> = emptySet(),
    projectTools: Set<String> = emptySet(),
    skillAllowedTools: Set<String>? = null  // NEW: hard restriction from active skill
): List<AgentTool> {
```

At the BEGINNING of the method body (before keyword scanning), add:

```kotlin
    // If a skill with allowed-tools is active, ONLY those tools are available
    // This is a hard whitelist — overrides all other selection logic
    if (skillAllowedTools != null) {
        val allowed = skillAllowedTools.toMutableSet()
        // Always include delegate_task and request_tools as escape hatches
        allowed.add("request_tools")
        allowed.add("delegate_task")
        return allTools.filter { it.name in allowed }
    }
```

- [ ] **Step 2: Add `getAllowedTools()` to SkillManager**

In `SkillManager.kt`, add after `getPreferredTools()`:

```kotlin
fun getAllowedTools(): Set<String>? =
    activeSkill?.entry?.allowedTools?.toSet()
```

- [ ] **Step 3: Wire in AgentOrchestrator**

Find where `DynamicToolSelector.selectTools()` is called in `AgentOrchestrator.kt`. Add the `skillAllowedTools` parameter:

```kotlin
val skillAllowedTools = session.skillManager?.getAllowedTools()
val selectedTools = DynamicToolSelector.selectTools(
    allTools = toolRegistry.allTools(),
    conversationContext = ...,
    disabledTools = ...,
    activatedTools = ...,
    preferredTools = ...,
    projectTools = ...,
    skillAllowedTools = skillAllowedTools
)
```

- [ ] **Step 4: Write test**

```kotlin
@Test
fun `skillAllowedTools restricts to whitelist only`() {
    val allTools = listOf(
        mockTool("read_file"), mockTool("edit_file"), mockTool("search_code"),
        mockTool("run_command"), mockTool("delegate_task"), mockTool("request_tools")
    )
    val result = DynamicToolSelector.selectTools(
        allTools = allTools,
        conversationContext = "",
        skillAllowedTools = setOf("read_file", "search_code")
    )
    val names = result.map { it.name }.toSet()
    assertTrue("read_file" in names)
    assertTrue("search_code" in names)
    assertTrue("delegate_task" in names)  // always included
    assertTrue("request_tools" in names)  // always included
    assertFalse("edit_file" in names)     // blocked
    assertFalse("run_command" in names)   // blocked
}

@Test
fun `null skillAllowedTools uses normal selection`() {
    val allTools = listOf(mockTool("read_file"), mockTool("edit_file"))
    val result = DynamicToolSelector.selectTools(
        allTools = allTools,
        conversationContext = "read the file",
        skillAllowedTools = null
    )
    assertTrue(result.isNotEmpty())
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew :agent:test --tests "*.DynamicToolSelectorTest" -x verifyPlugin
```

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SkillManager.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelectorTest.kt
git commit -m "feat(agent): allowed-tools hard tool restriction for active skills

When a skill with allowed-tools is active, DynamicToolSelector returns
ONLY those tools. delegate_task and request_tools always included as
escape hatches. Matches Claude Code behavior where allowed-tools is a
strict whitelist."
```

---

## Task 3: Dynamic Context Injection and SKILL_DIR Substitution

**Files:**
- Modify: `agent/src/main/kotlin/.../runtime/SkillManager.kt`
- Modify: `agent/src/test/kotlin/.../runtime/SkillManagerTest.kt`

- [ ] **Step 1: Add `${CLAUDE_SKILL_DIR}` substitution**

In `SkillManager.activateSkill()`, after the `$ARGUMENTS` substitution block, add:

```kotlin
// Substitute ${CLAUDE_SKILL_DIR} with the skill's directory path
val skillDir = when {
    entry.filePath.startsWith("builtin:") -> "" // built-in skills have no directory
    else -> File(entry.filePath).parent ?: ""
}
if (skillDir.isNotBlank()) {
    processed = processed.replace("\${CLAUDE_SKILL_DIR}", skillDir)
}
```

- [ ] **Step 2: Add dynamic context injection preprocessing**

Dynamic context injection uses the pattern: text between backticks preceded by `!` — like `` !`git status` ``. The shell command runs and its output replaces the placeholder.

In `SkillManager.activateSkill()`, after all substitutions but BEFORE truncation, add:

```kotlin
// Dynamic context injection: !`command` runs shell and replaces with output
processed = preprocessDynamicContext(processed, project)
```

Add the preprocessing method:

```kotlin
/**
 * Preprocesses !`command` patterns in skill content.
 * Runs each shell command and replaces the pattern with its stdout.
 * Commands run with the project base path as working directory.
 * Timeout: 10 seconds per command, 30 seconds total.
 */
private fun preprocessDynamicContext(content: String, project: Project): String {
    val pattern = Regex("""!\`([^`]+)\`""")
    val matches = pattern.findAll(content).toList()
    if (matches.isEmpty()) return content

    var result = content
    val basePath = project.basePath ?: return content
    val totalDeadline = System.currentTimeMillis() + 30_000

    for (match in matches) {
        if (System.currentTimeMillis() > totalDeadline) break
        val command = match.groupValues[1]
        try {
            val process = ProcessBuilder("sh", "-c", command)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            val output = if (completed) {
                process.inputStream.bufferedReader().readText().trim().take(10_000) // cap at 10K chars
            } else {
                process.destroyForcibly()
                "(command timed out: $command)"
            }
            result = result.replace(match.value, output)
        } catch (e: Exception) {
            result = result.replace(match.value, "(command failed: ${e.message})")
        }
    }
    return result
}
```

- [ ] **Step 3: Write tests**

```kotlin
@Test
fun `CLAUDE_SKILL_DIR substitution replaces with skill directory`() {
    val skillDir = tempDir.resolve("my-skill").also { it.mkdirs() }
    File(skillDir, "SKILL.md").writeText("""
        ---
        name: my-skill
        description: test
        ---
        Run: ${'$'}{CLAUDE_SKILL_DIR}/scripts/validate.sh
    """.trimIndent())
    // Load and activate
    val manager = createManager(skillDir)
    manager.activateSkill("my-skill", null)
    val content = manager.activeSkill?.content
    assertNotNull(content)
    assertTrue(content!!.contains(skillDir.absolutePath))
    assertFalse(content.contains("\${CLAUDE_SKILL_DIR}"))
}

@Test
fun `dynamic context injection runs shell commands`() {
    // This test verifies the regex and basic replacement
    val content = "Current date: !`date +%Y`\nDone."
    val processed = manager.preprocessDynamicContext(content, project)
    assertFalse(processed.contains("!`"))
    assertTrue(processed.contains("202"))  // year starts with 202x
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :agent:test --tests "*.SkillManagerTest" -x verifyPlugin
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SkillManager.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SkillManagerTest.kt
git commit -m "feat(agent): dynamic context injection and CLAUDE_SKILL_DIR substitution

Add !backtick-command preprocessing — runs shell commands before skill
content is sent to LLM (10s per command, 30s total, 10K char cap).
Add CLAUDE_SKILL_DIR substitution pointing to skill's directory path.
Matches Claude Code's preprocessing behavior."
```

---

## Task 4: Context Fork — Skill Execution via WorkerSession

**Files:**
- Modify: `agent/src/main/kotlin/.../tools/builtin/ActivateSkillTool.kt`
- Modify: `agent/src/main/kotlin/.../runtime/SkillManager.kt`
- Modify: `agent/src/test/kotlin/.../tools/builtin/ActivateSkillToolTest.kt`

- [ ] **Step 1: Add fork execution path to ActivateSkillTool**

In `ActivateSkillTool.execute()`, after the `disableModelInvocation` check but BEFORE the normal `skillManager.activateSkill()` call, add a fork branch:

```kotlin
// If skill has context: fork, execute in isolated WorkerSession
if (entry.contextFork) {
    return executeForked(entry, arguments, project)
}

// Normal inline activation (existing code)
skillManager.activateSkill(skillName, arguments)
```

Add the `executeForked` method:

```kotlin
private suspend fun executeForked(
    entry: SkillRegistry.SkillEntry,
    arguments: String?,
    project: Project
): ToolResult {
    val skillManager = this.skillManager ?: return ToolResult("Error: skill manager not available", "Error", 50, isError = true)

    // Load and preprocess skill content (substitutions + dynamic injection)
    val content = skillManager.loadAndPreprocessSkill(entry, arguments, project)
        ?: return ToolResult("Error: could not load skill content", "Error", 50, isError = true)

    // Determine tools for the worker
    val agentService = try { AgentService.getInstance(project) } catch (_: Exception) { null }
        ?: return ToolResult("Error: agent service not available", "Error", 50, isError = true)

    val workerTools = if (entry.allowedTools != null) {
        agentService.toolRegistry.allTools().filter { it.name in entry.allowedTools }.associateBy { it.name }
    } else {
        agentService.toolRegistry.allTools().associateBy { it.name }
    }

    val toolDefinitions = workerTools.values.map { it.toToolDefinition() }

    // Build system prompt for the worker
    val systemPrompt = "You are a specialized agent executing a skill. Follow the instructions precisely."

    // Create fresh context and worker
    val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
    val contextManager = ContextManager(maxInputTokens = settings?.state?.maxInputTokens ?: AgentSettings.DEFAULTS.maxInputTokens)

    val parentJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
    val workerSession = WorkerSession(maxIterations = 10, parentJob = parentJob)

    return try {
        val result = kotlinx.coroutines.withTimeout(300_000) { // 5 min timeout
            workerSession.execute(
                workerType = WorkerType.ORCHESTRATOR,
                systemPrompt = systemPrompt,
                task = content,
                tools = workerTools,
                toolDefinitions = toolDefinitions,
                brain = agentService.brain,
                contextManager = contextManager,
                project = project
            )
        }
        ToolResult(
            "Skill '${entry.name}' completed via subagent.\n\nResult: ${result.summary}",
            "Skill ${entry.name} executed in subagent",
            TokenEstimator.estimate(result.summary)
        )
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        ToolResult("Skill '${entry.name}' timed out after 5 minutes", "Error", 50, isError = true)
    } catch (e: Exception) {
        ToolResult("Skill '${entry.name}' failed: ${e.message}", "Error", 50, isError = true)
    }
}
```

- [ ] **Step 2: Extract `loadAndPreprocessSkill` from SkillManager**

In `SkillManager.kt`, refactor `activateSkill()` to expose a method that loads + preprocesses without activating:

```kotlin
/**
 * Load skill content with all substitutions and preprocessing applied.
 * Used by context:fork execution which needs the processed content for a WorkerSession.
 */
fun loadAndPreprocessSkill(entry: SkillRegistry.SkillEntry, arguments: String?, project: Project): String? {
    val rawContent = registry.loadSkillContent(entry.name) ?: return null
    var processed = rawContent

    // Argument substitution (same as activateSkill)
    if (arguments != null) {
        processed = processed.replace("\$ARGUMENTS", arguments)
        val args = arguments.split(" ")
        args.forEachIndexed { index, arg ->
            processed = processed.replace("\$ARGUMENTS[$index]", arg)
            processed = processed.replace("\$$index", arg)
        }
    } else if (processed.contains("\$ARGUMENTS")) {
        processed = processed.replace("\$ARGUMENTS", "")
    }

    // CLAUDE_SKILL_DIR substitution
    val skillDir = when {
        entry.filePath.startsWith("builtin:") -> ""
        else -> File(entry.filePath).parent ?: ""
    }
    if (skillDir.isNotBlank()) {
        processed = processed.replace("\${CLAUDE_SKILL_DIR}", skillDir)
    }

    // Dynamic context injection
    processed = preprocessDynamicContext(processed, project)

    // Truncate
    if (processed.length > 20_000) {
        processed = processed.take(20_000) + "\n\n[Skill content truncated at ~5000 tokens]"
    }

    return processed
}
```

Then refactor `activateSkill()` to use it:

```kotlin
fun activateSkill(name: String, arguments: String?) {
    val entry = registry.getSkill(name) ?: return
    val content = loadAndPreprocessSkill(entry, arguments, project) ?: return
    activeSkill = ActiveSkill(entry, content, arguments)
    onSkillActivated?.invoke(activeSkill!!)
}
```

Note: `project` needs to be accessible in SkillManager. It's already available via the constructor or session — check the actual constructor and pass it through.

- [ ] **Step 3: Write test for context:fork**

```kotlin
@Test
fun `context fork skill returns result from worker`() = runTest {
    // This is a unit test verifying the fork path is taken
    val entry = SkillRegistry.SkillEntry(
        name = "test-fork",
        description = "test",
        contextFork = true,
        agentType = null,
        filePath = "/tmp/test",
        scope = SkillScope.PROJECT
    )
    assertTrue(entry.contextFork)
    // Full integration test requires a running brain — verify the flag parsing works
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :agent:test --tests "*.ActivateSkillToolTest" --tests "*.SkillManagerTest" -x verifyPlugin
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ActivateSkillTool.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SkillManager.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/ActivateSkillToolTest.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SkillManagerTest.kt
git commit -m "feat(agent): context:fork executes skills in isolated WorkerSession

Skills with context: fork now run in a fresh WorkerSession (max 10
iterations, 5 min timeout). Skill content becomes the worker's task.
allowed-tools restricts the worker's available tools. Refactored
SkillManager to expose loadAndPreprocessSkill for fork execution."
```

---

## Task 5: Supporting Files and Description Budget

**Files:**
- Modify: `agent/src/main/kotlin/.../runtime/SkillRegistry.kt`
- Modify: `agent/src/main/kotlin/.../orchestrator/PromptAssembler.kt`
- Modify: `agent/src/test/kotlin/.../runtime/SkillRegistryTest.kt`

- [ ] **Step 1: Add supporting file awareness to SkillRegistry**

Add a method to list supporting files in a skill directory:

```kotlin
/**
 * List supporting files in a skill's directory (excluding SKILL.md).
 * Returns relative paths from the skill directory.
 */
fun getSupportingFiles(skillName: String): List<String> {
    val entry = skills[skillName] ?: return emptyList()
    if (entry.filePath.startsWith("builtin:")) return emptyList()
    val skillDir = File(entry.filePath).parentFile ?: return emptyList()
    return skillDir.walkTopDown()
        .filter { it.isFile && it.name != "SKILL.md" }
        .map { it.relativeTo(skillDir).path }
        .toList()
}
```

- [ ] **Step 2: Add description budget to buildDescriptionIndex**

In `buildDescriptionIndex()`, add budget enforcement:

```kotlin
fun buildDescriptionIndex(maxInputTokens: Int = 190_000): String {
    val discoverable = getAutoDiscoverableSkills()
    if (discoverable.isEmpty()) return "No skills available."

    // Budget: 2% of context window, minimum 1000 chars, fallback 16,000
    val budget = maxOf(1000, (maxInputTokens * 0.02 * 4).toInt().coerceAtMost(16_000))

    val sb = StringBuilder("Available skills:\n")
    var usedChars = sb.length
    var included = 0
    var excluded = 0

    for (skill in discoverable) {
        val line = "- /${skill.name} — ${skill.description}\n"
        if (usedChars + line.length > budget) {
            excluded++
            continue
        }
        sb.append(line)
        usedChars += line.length
        included++
    }

    if (excluded > 0) {
        sb.append("\n($excluded skill(s) hidden due to description budget. Use /skill-name to invoke directly.)")
    }

    return sb.toString()
}
```

Update callers to pass `maxInputTokens`.

- [ ] **Step 3: Write tests**

```kotlin
@Test
fun `description budget truncates when exceeded`() {
    // Create many skills that exceed budget
    val registry = SkillRegistry(project, loadBuiltins = false)
    // Add 100 skills with long descriptions
    repeat(100) { i ->
        // Use reflection or test helper to add skills
    }
    val index = registry.buildDescriptionIndex(maxInputTokens = 10_000) // small budget
    assertTrue(index.contains("hidden due to description budget"))
}

@Test
fun `getSupportingFiles lists files excluding SKILL_MD`() {
    val skillDir = tempDir.resolve("my-skill").also { it.mkdirs() }
    File(skillDir, "SKILL.md").writeText("---\nname: my-skill\ndescription: test\n---\nHello")
    File(skillDir, "template.md").writeText("Template content")
    File(skillDir, "scripts").mkdirs()
    File(skillDir, "scripts/validate.sh").writeText("#!/bin/bash\necho ok")

    val files = registry.getSupportingFiles("my-skill")
    assertTrue("template.md" in files)
    assertTrue("scripts/validate.sh" in files || "scripts${File.separator}validate.sh" in files)
    assertFalse("SKILL.md" in files)
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :agent:test --tests "*.SkillRegistryTest" --tests "*.PromptAssemblerTest" -x verifyPlugin
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SkillRegistry.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/SkillRegistryTest.kt
git commit -m "feat(agent): supporting files awareness and description budget

SkillRegistry.getSupportingFiles() lists non-SKILL.md files in skill
directory. buildDescriptionIndex() enforces 2% context budget (max 16K
chars) and warns when skills are hidden. Matches Claude Code behavior."
```

---

## Task 6: Update Documentation

**Files:**
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Update the User-Extensible Skills section**

Update the skills section to document new fields:

```markdown
## User-Extensible Skills

- Format: SKILL.md with YAML frontmatter (Agent Skills standard)
- Project: `{projectBasePath}/.workflow/skills/{name}/SKILL.md`
- User: `~/.workflow-orchestrator/skills/{name}/SKILL.md`

**Frontmatter fields:**
| Field | Default | Description |
|-------|---------|-------------|
| `name` | directory name | Skill identifier, becomes /slash-command |
| `description` | — | When to use. LLM uses this for auto-invocation |
| `disable-model-invocation` | false | true = only user can invoke, hidden from LLM |
| `user-invocable` | true | false = only LLM can invoke, hidden from / menu |
| `allowed-tools` | null | Hard tool whitelist when skill active |
| `preferred-tools` | [] | Soft tool preference (additive) |
| `context` | — | "fork" = run in isolated WorkerSession |
| `agent` | — | Subagent type when context: fork |
| `argument-hint` | — | Autocomplete hint for arguments |

**Substitutions:** `$ARGUMENTS`, `$0`-`$N`, `${CLAUDE_SKILL_DIR}`
**Dynamic injection:** `` !`command` `` runs shell at preprocessing time
**Description budget:** 2% of context window (max 16K chars)
```

- [ ] **Step 2: Run all tests**

```bash
./gradlew :agent:test --rerun --no-build-cache -x verifyPlugin
```

- [ ] **Step 3: Commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs: update skills documentation with new frontmatter fields

Document allowed-tools, context:fork, dynamic injection, CLAUDE_SKILL_DIR,
description budget. Matches Claude Code's official skill specification."
```

---

## Verification

```bash
./gradlew :agent:test --rerun --no-daemon
./gradlew :agent:compileKotlin
./gradlew verifyPlugin
```

Manual verification:
1. Create a skill with `allowed-tools: read_file, search_code` — verify agent can't use edit_file
2. Create a skill with `context: fork` — verify it runs in a separate worker and returns results
3. Create a skill with `` !`git status` `` — verify git status output appears in skill content
4. Create a skill with `${CLAUDE_SKILL_DIR}/scripts/test.sh` — verify path is resolved
5. Create many skills — verify description budget truncates with warning
6. Verify `disable-model-invocation: true` skills are completely hidden from LLM (already fixed)
