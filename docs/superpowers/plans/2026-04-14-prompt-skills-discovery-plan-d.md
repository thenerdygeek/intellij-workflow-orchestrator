# Modular Prompt, Skill Variants & Tool Discovery — Implementation Plan (Plan D)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the system prompt, bundled skills, and agent personas IDE-aware. Replace Java/Spring-hardcoded content with dynamic sections that adapt to the IDE environment. Add deferred tool discovery improvements so the LLM prefers specialized tools over generic fallbacks.

**Architecture:** `SystemPrompt.build()` gains an `IdeContext` parameter. Sections with Java/Spring references get language-conditional variants. `InstructionLoader.getSkillContent()` loads `SKILL.java.md` or `SKILL.python.md` variants alongside the base `SKILL.md`. Agent persona registration is filtered by IDE context. Four deferred tool discovery techniques are implemented.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform SDK, JUnit 5 + MockK

**Depends on:** Plan A (IdeContext — completed). Independent of Plans B2 and C.

**Research:** `docs/research/2026-04-13-system-prompt-structure-map.md` — complete section-by-section analysis

---

## Sections That Need Changes

From the research, only these sections contain Java/Spring/IntelliJ-specific content:

| Section | Line | Content to change |
|---|---|---|
| `agentRole()` | 130 | "running inside IntelliJ IDEA" → dynamic IDE name |
| `capabilities()` | 215-260 | "Spring Boot endpoints", "mvn compile", "./gradlew", Maven/Gradle workflows |
| `rules()` | 326-413 | "mvn compile", "./gradlew test", "spring-boot-engineer", "devops-engineer" agent names |
| `systemInfo()` | 441 | "IDE: IntelliJ IDEA" → dynamic IDE name |

All other sections (editingFiles, actVsPlanMode, objective, memory, skills, userInstructions) are already universal.

---

## File Map

| Action | File | Purpose |
|---|---|---|
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt` | Add IdeContext parameter, make 4 sections dynamic |
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/InstructionLoader.kt` | Load skill language variants |
| Create | `agent/src/main/resources/skills/tdd/SKILL.java.md` | Java/Kotlin TDD variant |
| Create | `agent/src/main/resources/skills/tdd/SKILL.python.md` | Python TDD variant |
| Create | `agent/src/main/resources/skills/interactive-debugging/SKILL.java.md` | Java/Kotlin debug variant |
| Create | `agent/src/main/resources/skills/interactive-debugging/SKILL.python.md` | Python debug variant |
| Create | `agent/src/main/resources/skills/systematic-debugging/SKILL.java.md` | Java/Kotlin investigation variant |
| Create | `agent/src/main/resources/skills/systematic-debugging/SKILL.python.md` | Python investigation variant |
| Create | `agent/src/main/resources/skills/subagent-driven/SKILL.java.md` | Java/Kotlin verification variant |
| Create | `agent/src/main/resources/skills/subagent-driven/SKILL.python.md` | Python verification variant |
| Create | `agent/src/main/resources/skills/writing-plans/SKILL.java.md` | Java/Kotlin build commands variant |
| Create | `agent/src/main/resources/skills/writing-plans/SKILL.python.md` | Python build commands variant |
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` | Pass IdeContext to SystemPrompt, filter personas |
| Create | `agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptIdeContextTest.kt` | Tests for IDE-aware prompt generation |
| Create | `agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SkillVariantTest.kt` | Tests for skill variant loading |

---

## Task 1: Golden Snapshot of Current System Prompt

Before changing anything, capture the current prompt output as a test reference.

**Files:**
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptIdeContextTest.kt`
- Create: `agent/src/test/resources/prompt-snapshot-intellij-ultimate.txt`

- [ ] **Step 1: Write snapshot capture test**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptIdeContextTest.kt
package com.workflow.orchestrator.agent.prompt

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class SystemPromptIdeContextTest {

    @Test
    fun `capture current prompt as golden snapshot`() {
        val prompt = SystemPrompt.build(
            projectName = "TestProject",
            projectPath = "/test/project",
            osName = "macOS",
            shell = "zsh",
        )

        // Save snapshot for comparison after refactoring
        val snapshotFile = File("src/test/resources/prompt-snapshot-intellij-ultimate.txt")
        snapshotFile.parentFile.mkdirs()
        snapshotFile.writeText(prompt)

        // Basic structural invariants that must hold after refactoring
        assertTrue(prompt.contains("===="), "Sections must be separated by ====")
        assertTrue(prompt.contains("IntelliJ"), "Current prompt mentions IntelliJ")
        assertTrue(prompt.length > 3000, "Prompt must be substantive (got ${prompt.length})")
        assertTrue(prompt.length < 30000, "Prompt must not be excessive (got ${prompt.length})")
    }
}
```

- [ ] **Step 2: Run to generate snapshot**

Run: `./gradlew :agent:test --tests "*SystemPromptIdeContextTest*capture*" -v`
Expected: PASS — snapshot file created

- [ ] **Step 3: Commit snapshot**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptIdeContextTest.kt \
        agent/src/test/resources/prompt-snapshot-intellij-ultimate.txt
git commit -m "test(agent): capture golden snapshot of current system prompt

Reference for verifying prompt refactoring doesn't break existing behavior."
```

---

## Task 2: Add IdeContext to SystemPrompt.build()

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

- [ ] **Step 1: Add IdeContext parameter to SystemPrompt.build()**

Add `ideContext: IdeContext? = null` as an optional parameter (default null preserves backward compatibility):

```kotlin
fun build(
    projectName: String,
    projectPath: String,
    osName: String = System.getProperty("os.name") ?: "Unknown",
    shell: String = defaultShell(),
    repoMap: String? = null,
    planModeEnabled: Boolean = false,
    additionalContext: String? = null,
    availableSkills: List<SkillMetadata>? = null,
    activeSkillContent: String? = null,
    taskProgress: String? = null,
    deferredToolCatalog: Map<String, List<Pair<String, String>>>? = null,
    coreMemoryXml: String? = null,
    toolDefinitionsMarkdown: String? = null,
    recalledMemoryXml: String? = null,
    ideContext: IdeContext? = null  // NEW — IDE environment context
): String = buildString {
```

- [ ] **Step 2: Make agentRole() IDE-aware**

```kotlin
private fun agentRole(ideContext: IdeContext?): String {
    val ideName = ideContext?.productName ?: "IntelliJ IDEA"
    return "You are an AI coding agent running inside $ideName. You have programmatic access to the IDE's debugger, test runner, code analysis, build system, refactoring engine, and enterprise integrations (Jira, Bamboo, SonarQube, Bitbucket). You help users with software engineering tasks by using IDE-native tools that are faster and more accurate than shell equivalents. You are highly skilled with extensive knowledge of programming languages, frameworks, design patterns, and best practices."
}
```

Update the call in `build()`:
```kotlin
// 1. AGENT ROLE
append(agentRole(ideContext))
```

- [ ] **Step 3: Make capabilities() IDE-aware**

The `capabilities()` section currently has Spring/Maven/Gradle examples. Replace with dynamic content based on `ideContext`:

```kotlin
private fun capabilities(projectPath: String, ideContext: IdeContext?): String = buildString {
    appendLine("CAPABILITIES")
    appendLine()
    val ideName = ideContext?.productName ?: "IntelliJ IDEA"
    appendLine("You run inside $ideName with access to tools across several categories. Core tools are always available; deferred tools are loaded via tool_search.")
    appendLine()
    appendLine("**IMPORTANT — IDE tools are your primary tools.** Before falling back to search_code, glob_files, or run_command, check if a dedicated IDE tool handles the task.")
    appendLine()

    // IDE context summary (if available)
    ideContext?.let {
        appendLine(it.summary())
        appendLine()

        // Specialized tools hint (primacy zone — high attention)
        val specializedTools = mutableListOf<String>()
        if (it.supportsSpring) specializedTools.add("spring")
        if (Framework.DJANGO in it.detectedFrameworks) specializedTools.add("django")
        if (Framework.FASTAPI in it.detectedFrameworks) specializedTools.add("fastapi")
        if (Framework.FLASK in it.detectedFrameworks) specializedTools.add("flask")
        specializedTools.add("build")
        specializedTools.add("debug")
        specializedTools.add("database")

        if (specializedTools.isNotEmpty()) {
            appendLine("Specialized tools available via tool_search: ${specializedTools.joinToString()}.")
            appendLine()
        }
    }

    // Language-specific capability examples
    if (ideContext == null || ideContext.supportsJava) {
        appendLine("- curl/wget to localhost/127.0.0.1 is always allowed — useful for testing Spring Boot endpoints. Remote URLs require approval.")
    }
    if (ideContext?.supportsPython == true) {
        appendLine("- curl/wget to localhost/127.0.0.1 is always allowed — useful for testing Django/FastAPI/Flask endpoints. Remote URLs require approval.")
    }

    appendLine("- Use find_definition and find_references for code navigation (faster and more precise than grepping).")
    appendLine("- Use diagnostics to check for errors after edits (faster than running the compiler).")
    appendLine("- Use tool_search to discover specialized tools by keyword if unsure.")

    // Database workflow (universal)
    appendLine("- Database tools available for direct SQL queries — use db_list_profiles, db_query, db_schema.")
}
```

- [ ] **Step 4: Make rules() IDE-aware**

The rules section has build-system-specific examples and agent persona names. Make them conditional:

```kotlin
private fun rules(projectPath: String, ideContext: IdeContext?): String = buildString {
    appendLine("RULES")
    appendLine()

    // Tool preference rule — language-aware examples
    append("Prefer IDE tools over shell commands: diagnostics over ")
    if (ideContext == null || ideContext.supportsJava) {
        append("mvn compile, ")
    }
    if (ideContext?.supportsPython == true) {
        append("python -m py_compile, ")
    }
    appendLine("run_inspections over linter CLI, runtime_exec over build CLI, refactor_rename over find-and-replace. Use run_command only for tasks with no IDE equivalent.")
    appendLine()

    // Build-specific rules
    appendLine("## IDE Tool Equivalents")
    if (ideContext == null || ideContext.supportsJava) {
        appendLine("- Use diagnostics instead of `run_command(\"mvn compile\")` or `run_command(\"./gradlew compileKotlin\")`")
        appendLine("- Use runtime_exec(action=\"run_tests\") instead of `run_command(\"./gradlew test\")`")
        appendLine("- Use runtime_exec(action=\"compile_module\") instead of `run_command(\"mvn compile\")`")
    }
    if (ideContext?.supportsPython == true) {
        appendLine("- Use diagnostics instead of `run_command(\"python -m py_compile file.py\")`")
        appendLine("- Use runtime_exec(action=\"run_tests\") instead of `run_command(\"pytest\")`")
    }
    appendLine()

    // ... (keep other universal rules as-is: read-before-edit, output management, safety, etc.)

    // Subagent delegation — only show relevant agents
    appendLine("## When to Delegate to Subagents")
    appendLine("Available specialist agents:")
    if (ideContext == null || ideContext.supportsJava) {
        appendLine("- \"spring-boot-engineer\" — Spring Boot feature development. Discovers project patterns before implementing.")
    }
    if (ideContext?.supportsPython == true) {
        appendLine("- \"python-engineer\" — Python web frameworks (Django, FastAPI, Flask), pytest, async patterns.")
    }
    // Universal agents (always shown)
    appendLine("- \"code-reviewer\" — Code quality, review, test assessment.")
    appendLine("- \"architect-reviewer\" — Architecture review, dependency analysis.")
    appendLine("- \"test-automator\" — Test generation and coverage improvement.")
    appendLine("- \"refactoring-specialist\" — Safe refactoring with test preservation.")
    appendLine("- \"devops-engineer\" — CI/CD, Docker, deployment configs.")
    appendLine("- \"security-auditor\" — Security audit: OWASP Top 10, secrets scanning, dependency CVEs.")
    appendLine("- \"performance-engineer\" — Performance analysis and optimization.")
}
```

- [ ] **Step 5: Make systemInfo() IDE-aware**

```kotlin
private fun systemInfo(osName: String, shell: String, projectPath: String, ideContext: IdeContext?): String {
    val ideName = ideContext?.productName ?: "IntelliJ IDEA"
    return """SYSTEM INFORMATION
Operating System: $osName
Default Shell: $shell
Home Directory: ${System.getProperty("user.home") ?: "/home/user"}
Current Working Directory: $projectPath
IDE: $ideName"""
}
```

- [ ] **Step 6: Pass IdeContext from AgentService**

In `AgentService.kt`, where `SystemPrompt.build()` is called, pass the `ideContext`:

```kotlin
val systemPrompt = SystemPrompt.build(
    projectName = project.name,
    projectPath = project.basePath ?: "",
    // ... existing params ...
    ideContext = ideContext  // NEW
)
```

Find all call sites of `SystemPrompt.build()` in `AgentService.kt` and add `ideContext = ideContext`.

- [ ] **Step 7: Write comparison tests**

Add to `SystemPromptIdeContextTest.kt`:

```kotlin
@Test
fun `IntelliJ Ultimate prompt is similar to golden snapshot`() {
    // IdeContext for IntelliJ Ultimate with Java + Spring
    val context = IdeContext(
        product = IdeProduct.INTELLIJ_ULTIMATE,
        productName = "IntelliJ IDEA 2025.1 Ultimate",
        edition = Edition.ULTIMATE,
        languages = setOf(Language.JAVA, Language.KOTLIN),
        hasJavaPlugin = true,
        hasPythonPlugin = false,
        hasPythonCorePlugin = false,
        hasSpringPlugin = true,
        detectedFrameworks = setOf(Framework.SPRING),
        detectedBuildTools = setOf(BuildTool.GRADLE),
    )

    val prompt = SystemPrompt.build(
        projectName = "TestProject",
        projectPath = "/test/project",
        ideContext = context,
    )

    // Must still contain key IntelliJ/Spring content
    assertTrue(prompt.contains("IntelliJ"))
    assertTrue(prompt.contains("Spring"))
    assertTrue(prompt.contains("spring-boot-engineer"))
    assertTrue(prompt.contains("gradlew") || prompt.contains("Gradle"))
    // Must NOT contain Python-specific content
    assertFalse(prompt.contains("pytest"))
    assertFalse(prompt.contains("python-engineer"))
    assertFalse(prompt.contains("Django"))
}

@Test
fun `PyCharm Community prompt has Python content, no Java content`() {
    val context = IdeContext(
        product = IdeProduct.PYCHARM_COMMUNITY,
        productName = "PyCharm 2025.1 Community",
        edition = Edition.COMMUNITY,
        languages = setOf(Language.PYTHON),
        hasJavaPlugin = false,
        hasPythonPlugin = false,
        hasPythonCorePlugin = true,
        hasSpringPlugin = false,
        detectedFrameworks = setOf(Framework.DJANGO),
        detectedBuildTools = setOf(BuildTool.POETRY),
    )

    val prompt = SystemPrompt.build(
        projectName = "TestProject",
        projectPath = "/test/project",
        ideContext = context,
    )

    assertTrue(prompt.contains("PyCharm"))
    assertTrue(prompt.contains("Python"))
    assertTrue(prompt.contains("Django"))
    assertTrue(prompt.contains("poetry"))
    assertTrue(prompt.contains("pytest"))
    assertTrue(prompt.contains("python-engineer"))
    // Must NOT contain Java-specific content
    assertFalse(prompt.contains("spring-boot-engineer"))
    assertFalse(prompt.contains("mvn compile"))
    assertFalse(prompt.contains("gradlew"))
}

@Test
fun `WebStorm prompt has minimal content`() {
    val context = IdeContext(
        product = IdeProduct.OTHER,
        productName = "WebStorm 2025.1",
        edition = Edition.OTHER,
        languages = emptySet(),
        hasJavaPlugin = false,
        hasPythonPlugin = false,
        hasPythonCorePlugin = false,
        hasSpringPlugin = false,
        detectedFrameworks = emptySet(),
        detectedBuildTools = emptySet(),
    )

    val prompt = SystemPrompt.build(
        projectName = "TestProject",
        projectPath = "/test/project",
        ideContext = context,
    )

    assertTrue(prompt.contains("WebStorm"))
    assertFalse(prompt.contains("spring-boot-engineer"))
    assertFalse(prompt.contains("python-engineer"))
    assertFalse(prompt.contains("mvn compile"))
    assertFalse(prompt.contains("pytest"))
}

@Test
fun `null ideContext produces backward-compatible prompt`() {
    val withContext = SystemPrompt.build(
        projectName = "TestProject",
        projectPath = "/test/project",
        ideContext = null,
    )

    // Should behave like current prompt (IntelliJ-flavored)
    assertTrue(withContext.contains("IntelliJ"))
}

@Test
fun `prompt sections are properly ordered`() {
    val prompt = SystemPrompt.build(
        projectName = "TestProject",
        projectPath = "/test/project",
    )

    val agentRoleIdx = prompt.indexOf("AI coding agent")
    val capabilitiesIdx = prompt.indexOf("CAPABILITIES")
    val rulesIdx = prompt.indexOf("RULES")
    val systemInfoIdx = prompt.indexOf("SYSTEM INFORMATION")
    val objectiveIdx = prompt.indexOf("OBJECTIVE")

    assertTrue(agentRoleIdx < capabilitiesIdx, "Agent role before capabilities")
    assertTrue(capabilitiesIdx < rulesIdx, "Capabilities before rules")
    assertTrue(rulesIdx < systemInfoIdx, "Rules before system info")
    assertTrue(systemInfoIdx < objectiveIdx, "System info before objective")
}
```

- [ ] **Step 8: Run tests**

Run: `./gradlew :agent:test --tests "*SystemPromptIdeContextTest*" -v`
Expected: ALL PASS

- [ ] **Step 9: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptIdeContextTest.kt
git commit -m "feat(agent): make system prompt IDE-aware via IdeContext

agentRole, capabilities, rules, and systemInfo sections now adapt to
the IDE environment. IntelliJ shows Java/Spring content, PyCharm shows
Python/Django content, other IDEs get minimal universal content. Null
IdeContext preserves backward compatibility."
```

---

## Task 3: Skill Variant System

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/InstructionLoader.kt`
- Create: 10 skill variant files (5 skills x 2 variants each)
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SkillVariantTest.kt`

- [ ] **Step 1: Modify InstructionLoader to support variants**

In `InstructionLoader.kt`, modify `getSkillContent()` to load a language variant after the base:

```kotlin
fun getSkillContent(
    skillName: String,
    availableSkills: List<SkillMetadata>,
    ideContext: IdeContext? = null  // NEW parameter
): SkillContent? {
    val skill = availableSkills.find { it.name == skillName } ?: return null

    return try {
        // Load base SKILL.md
        val baseContent = when (skill.source) {
            SkillSource.BUNDLED -> loadClasspathResource(skill.path)
            SkillSource.PROJECT, SkillSource.GLOBAL -> {
                val file = File(skill.path)
                if (file.isFile && file.canRead()) file.readText(Charsets.UTF_8) else null
            }
        } ?: return null

        val (_, baseBody) = parseYamlFrontmatter(baseContent)

        // Load language variant (if exists)
        val variantBody = loadSkillVariant(skill, ideContext)

        // Merge: base + variant (variant appended after base)
        val fullInstructions = if (variantBody != null) {
            baseBody.trim() + "\n\n" + variantBody.trim()
        } else {
            baseBody.trim()
        }

        SkillContent(
            name = skill.name,
            description = skill.description,
            path = skill.path,
            source = skill.source,
            instructions = fullInstructions
        )
    } catch (e: Exception) {
        LOG.warn("InstructionLoader: failed to load skill content for '${skill.name}': ${e.message}")
        null
    }
}

/**
 * Load the language-specific variant of a skill.
 *
 * Variant file naming: SKILL.java.md (IntelliJ) or SKILL.python.md (PyCharm).
 * Simple IDE-based selection — no mixed-language merging.
 */
private fun loadSkillVariant(skill: SkillMetadata, ideContext: IdeContext?): String? {
    if (ideContext == null) return null

    val variantSuffix = when (ideContext.product) {
        IdeProduct.INTELLIJ_ULTIMATE, IdeProduct.INTELLIJ_COMMUNITY -> "java"
        IdeProduct.PYCHARM_PROFESSIONAL, IdeProduct.PYCHARM_COMMUNITY -> "python"
        IdeProduct.OTHER -> return null
    }

    val variantFileName = "SKILL.$variantSuffix.md"

    return when (skill.source) {
        SkillSource.BUNDLED -> {
            // /skills/tdd/SKILL.md → /skills/tdd/SKILL.java.md
            val basePath = skill.path.substringBeforeLast("/")
            loadClasspathResource("$basePath/$variantFileName")
        }
        SkillSource.PROJECT, SkillSource.GLOBAL -> {
            val variantFile = File(skill.path).parentFile?.resolve(variantFileName)
            if (variantFile?.isFile == true && variantFile.canRead()) {
                variantFile.readText(Charsets.UTF_8)
            } else null
        }
    }
}
```

- [ ] **Step 2: Update UseSkillTool to pass IdeContext**

In the `UseSkillTool` class, pass `ideContext` when calling `getSkillContent()`. The tool gets `ideContext` from `AgentService.getIdeContext()`.

- [ ] **Step 3: Create Java variant files**

For each of the 5 skills that need variants, create `SKILL.java.md` containing the Java/Kotlin-specific content that is currently in `SKILL.md`. Then remove that content from the base `SKILL.md` so the base is universal.

**tdd/SKILL.java.md:**
```markdown
## Java/Kotlin Testing

### Test Framework
Use JUnit 5 with MockK for Kotlin tests:

```kotlin
@ExtendWith(MockKExtension::class)
class UserServiceTest {
    @MockK private lateinit var repository: UserRepository
    @InjectMockKs private lateinit var service: UserService

    @Test
    fun `creates user with valid input`() {
        every { repository.save(any()) } returns mockUser
        val result = service.createUser(validInput)
        assertEquals(mockUser, result)
        verify(exactly = 1) { repository.save(any()) }
    }
}
```

### Build Commands
- Run tests: `./gradlew :module:test`
- Run single test: `./gradlew :module:test --tests "ClassName.methodName"`
- Run with coverage: `./gradlew :module:test jacocoTestReport`

### Spring Boot Testing
- `@SpringBootTest` — full application context
- `@WebMvcTest` — MVC layer only
- `@DataJpaTest` — JPA repository layer
```

**tdd/SKILL.python.md:**
```markdown
## Python Testing

### Test Framework
Use pytest with fixtures:

```python
import pytest
from myapp.services import UserService

@pytest.fixture
def user_service(db_session):
    return UserService(db_session)

def test_creates_user_with_valid_input(user_service, valid_user_data):
    user = user_service.create_user(valid_user_data)
    assert user.email == valid_user_data["email"]
    assert user.id is not None

class TestUserService:
    def test_raises_on_duplicate_email(self, user_service, existing_user):
        with pytest.raises(ValueError, match="already exists"):
            user_service.create_user({"email": existing_user.email})
```

### Build Commands
- Run tests: `pytest tests/ -v`
- Run single test: `pytest tests/test_users.py::TestUserService::test_creates_user -v`
- Run with coverage: `pytest --cov=myapp --cov-report=term-missing`
- Run with markers: `pytest -m "not slow"`
```

Create similar variant files for:
- **interactive-debugging/SKILL.java.md** — JVM debugging, Spring proxies, CGLIB, JDWP
- **interactive-debugging/SKILL.python.md** — Python debugger, Django template debug, pdb
- **systematic-debugging/SKILL.java.md** — Spring tools for investigation, bean context
- **systematic-debugging/SKILL.python.md** — Django/FastAPI tools for investigation
- **subagent-driven/SKILL.java.md** — `./gradlew :module:test` as verification
- **subagent-driven/SKILL.python.md** — `pytest tests/ -v` as verification
- **writing-plans/SKILL.java.md** — Gradle/Maven build commands
- **writing-plans/SKILL.python.md** — pip/poetry/uv/pytest commands

- [ ] **Step 4: Extract Java-specific content from base SKILL.md files**

For each of the 5 skills, remove the Java/Kotlin-specific examples from `SKILL.md` and leave only universal content. The Java content now lives in `SKILL.java.md`.

**Important:** The base `SKILL.md` should still be useful on its own (for "Other" IDEs that get no variant). Keep universal guidance, remove only language-specific examples and tool references.

- [ ] **Step 5: Write tests for variant loading**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SkillVariantTest.kt
package com.workflow.orchestrator.agent.prompt

import com.workflow.orchestrator.agent.ide.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SkillVariantTest {

    @Test
    fun `tdd skill loads Java variant for IntelliJ`() {
        val skills = InstructionLoader.getAvailableSkills("/test/project", "/test/global")
        val intellijContext = makeIdeContext(IdeProduct.INTELLIJ_ULTIMATE)

        val content = InstructionLoader.getSkillContent("tdd", skills, intellijContext)
        assertNotNull(content)
        assertTrue(content!!.instructions.contains("JUnit") || content.instructions.contains("gradlew"),
            "IntelliJ TDD skill should include Java/Kotlin content")
        assertFalse(content.instructions.contains("pytest"),
            "IntelliJ TDD skill should NOT include Python content")
    }

    @Test
    fun `tdd skill loads Python variant for PyCharm`() {
        val skills = InstructionLoader.getAvailableSkills("/test/project", "/test/global")
        val pycharmContext = makeIdeContext(IdeProduct.PYCHARM_COMMUNITY)

        val content = InstructionLoader.getSkillContent("tdd", skills, pycharmContext)
        assertNotNull(content)
        assertTrue(content!!.instructions.contains("pytest"),
            "PyCharm TDD skill should include Python content")
        assertFalse(content.instructions.contains("gradlew"),
            "PyCharm TDD skill should NOT include Gradle content")
    }

    @Test
    fun `skill with no variant returns base only`() {
        val skills = InstructionLoader.getAvailableSkills("/test/project", "/test/global")
        val context = makeIdeContext(IdeProduct.PYCHARM_PROFESSIONAL)

        val content = InstructionLoader.getSkillContent("brainstorm", skills, context)
        assertNotNull(content)
        // brainstorm has no variants — base only
    }

    @Test
    fun `null ideContext returns base only (backward compatible)`() {
        val skills = InstructionLoader.getAvailableSkills("/test/project", "/test/global")

        val content = InstructionLoader.getSkillContent("tdd", skills, null)
        assertNotNull(content)
        // Should be base only — no variant loaded
    }

    private fun makeIdeContext(product: IdeProduct) = IdeContext(
        product = product,
        productName = product.name,
        edition = IdeContextDetector.classifyEdition(product),
        languages = when (product) {
            IdeProduct.INTELLIJ_ULTIMATE, IdeProduct.INTELLIJ_COMMUNITY ->
                setOf(Language.JAVA, Language.KOTLIN)
            IdeProduct.PYCHARM_PROFESSIONAL, IdeProduct.PYCHARM_COMMUNITY ->
                setOf(Language.PYTHON)
            else -> emptySet()
        },
        hasJavaPlugin = product in setOf(IdeProduct.INTELLIJ_ULTIMATE, IdeProduct.INTELLIJ_COMMUNITY),
        hasPythonPlugin = product == IdeProduct.PYCHARM_PROFESSIONAL,
        hasPythonCorePlugin = product == IdeProduct.PYCHARM_COMMUNITY,
        hasSpringPlugin = false,
        detectedFrameworks = emptySet(),
        detectedBuildTools = emptySet(),
    )
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew :agent:test --tests "*SkillVariantTest*" -v`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/InstructionLoader.kt \
        agent/src/main/resources/skills/ \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SkillVariantTest.kt
git commit -m "feat(agent): add language-variant skill loading

InstructionLoader loads SKILL.java.md or SKILL.python.md alongside base
SKILL.md based on IDE product. 5 skills have variants: tdd, interactive-
debugging, systematic-debugging, subagent-driven, writing-plans. Base
skills are now universal. Null IdeContext preserves backward compatibility."
```

---

## Task 4: Agent Persona Filtering

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` or the prompt section that lists agents

- [ ] **Step 1: Filter available agent descriptions in system prompt**

The `<available_agents>` section (or the subagent delegation rules in `rules()`) should only list agents relevant to the current IDE. This was partially done in Task 2 (rules section). Verify:

- `spring-boot-engineer` — only shown when `ideContext.supportsJava` (or `ideContext == null`)
- `python-engineer` — only shown when `ideContext.supportsPython`
- All other personas (code-reviewer, architect-reviewer, etc.) — always shown

- [ ] **Step 2: Filter AgentConfigLoader persona registration**

In `AgentConfigLoader` or wherever bundled agent configs are loaded, filter based on `IdeContext`:

```kotlin
// When loading bundled agents, skip irrelevant ones
fun loadBundledAgents(ideContext: IdeContext?): List<AgentConfig> {
    val all = loadAllBundledAgents()
    if (ideContext == null) return all // backward compatible

    return all.filter { config ->
        when (config.name) {
            "spring-boot-engineer" -> ideContext.supportsJava
            "python-engineer" -> ideContext.supportsPython
            else -> true // universal agents always available
        }
    }
}
```

- [ ] **Step 3: Run tests and commit**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS

```bash
git commit -m "feat(agent): filter agent personas by IDE context

spring-boot-engineer only available in IntelliJ with Java plugin.
python-engineer only available in PyCharm or IntelliJ with Python plugin.
Universal agents (code-reviewer, architect-reviewer, etc.) always available."
```

---

## Task 5: Deferred Tool Discovery Improvements

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt` (capabilities section)

The 4 techniques from the design spec:

- [ ] **Step 1: Technique 1 — Task-to-tool hints table**

Add to the `capabilities()` section, after the IDE context summary:

```kotlin
// Task-to-tool hints (exploits "instructions after data = 30% better recall")
appendLine("## When to Use Specialized Tools (via tool_search)")
appendLine()
appendLine("Before using glob_files or search_code for these tasks, use tool_search first:")
appendLine()
appendLine("| If you need to... | Search for... | Instead of... |")
appendLine("|---|---|---|")
if (ideContext == null || ideContext.supportsJava) {
    appendLine("| Find API endpoints | \"endpoints\" or \"spring\" | Grepping for @PostMapping |")
    appendLine("| Understand Spring beans/config | \"spring\" | Grepping for @Bean/@Component |")
}
if (ideContext?.supportsPython == true) {
    if (Framework.DJANGO in ideContext.detectedFrameworks) {
        appendLine("| Find Django URLs/views | \"django\" | Reading urls.py manually |")
        appendLine("| Analyze Django models | \"django\" | Reading models.py manually |")
    }
    if (Framework.FASTAPI in ideContext.detectedFrameworks) {
        appendLine("| Find FastAPI routes | \"fastapi\" | Grepping for @app.get |")
    }
    if (Framework.FLASK in ideContext.detectedFrameworks) {
        appendLine("| Find Flask routes | \"flask\" | Grepping for @app.route |")
    }
}
// Universal hints
appendLine("| Understand class/type relationships | \"type_hierarchy\" | Manually reading extends/impl |")
appendLine("| Trace who calls a function | \"call_hierarchy\" | Grepping for function name |")
appendLine("| Check test coverage | \"coverage\" | Reading coverage reports manually |")
appendLine("| Inspect database schema | \"db_schema\" | Reading migration files |")
appendLine("| Check code quality issues | \"run_inspections\" | Running linter via run_command |")
appendLine("| Rename across codebase | \"refactor_rename\" | Find-and-replace via edit_file |")
appendLine()
```

- [ ] **Step 2: Technique 2 — IdeContext category hints (already done in Step 3 of Task 2)**

The `capabilities()` section already includes:
```
Specialized tools available via tool_search: spring, build, debug, database, coverage, code-quality.
```

This is in the primacy zone. Already implemented.

- [ ] **Step 3: Technique 3 — Related tool suggestions from tool_search**

This requires modifying `ToolSearchTool.kt` (or `RequestToolsTool` if that's the current name). When returning search results, append a "Related tools" suggestion:

```kotlin
// In the tool search result, after listing matched tools:
val relatedTools = getRelatedTools(matchedToolNames)
if (relatedTools.isNotEmpty()) {
    result.appendLine()
    result.appendLine("Related tools you may also find useful: ${relatedTools.joinToString()}")
}

private fun getRelatedTools(matched: List<String>): List<String> {
    val related = mutableSetOf<String>()
    for (name in matched) {
        when {
            name.startsWith("spring") -> related.addAll(listOf("build", "coverage", "db_schema"))
            name.startsWith("django") -> related.addAll(listOf("build", "db_schema", "db_query"))
            name.startsWith("fastapi") -> related.addAll(listOf("build", "db_schema"))
            name.startsWith("flask") -> related.addAll(listOf("build", "db_schema"))
            name == "build" -> related.addAll(listOf("coverage", "runtime_exec"))
            name.startsWith("debug") -> related.addAll(listOf("diagnostics", "runtime_exec"))
            name == "coverage" -> related.add("runtime_exec")
        }
    }
    related.removeAll(matched.toSet())
    return related.take(3).toList()
}
```

- [ ] **Step 4: Technique 4 — Framework tool promotion (already done in Plan C Task 6)**

Framework tools are promoted from deferred to core when the framework is detected in the project. This was implemented in Plan C. If Plan C hasn't been executed yet, add the promotion logic here:

In `AgentService.registerAllTools()`, check if detected frameworks should promote their tools to core. This is already handled by the `shouldPromoteFrameworkTool()` filter and the registration logic in Plan C.

- [ ] **Step 5: Run all tests**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/
git commit -m "feat(agent): add deferred tool discovery improvements

Task-to-tool hints table in capabilities section (Technique 1).
IdeContext category hints in primacy zone (Technique 2). Related tool
suggestions from tool_search results (Technique 3). Framework tool
promotion handled by Plan C registration logic (Technique 4)."
```

---

## Task 6: Full Verification and Documentation

- [ ] **Step 1: Run full test suite**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS

- [ ] **Step 2: Run full project build**

Run: `./gradlew clean buildPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify plugin**

Run: `./gradlew verifyPlugin`
Expected: PASS

- [ ] **Step 4: Update agent CLAUDE.md**

Add sections for:
- Modular system prompt (IdeContext-aware sections)
- Skill variant system (SKILL.java.md / SKILL.python.md)
- Persona filtering
- Deferred tool discovery techniques

- [ ] **Step 5: Update root CLAUDE.md**

Update `:agent` module description to mention IDE-aware prompt and skill variants.

- [ ] **Step 6: Commit**

```bash
git add agent/CLAUDE.md CLAUDE.md
git commit -m "docs(agent): document IDE-aware prompt, skill variants, tool discovery"
```

---

## Summary

After Plan D:

| What changed | Result |
|---|---|
| System prompt | 4 sections (agentRole, capabilities, rules, systemInfo) adapt to IDE context |
| Skill variants | 5 skills have SKILL.java.md + SKILL.python.md alongside base SKILL.md |
| Persona filtering | spring-boot-engineer only in IntelliJ, python-engineer only in PyCharm |
| Tool discovery | Task-to-tool hints table + category hints + related tool suggestions |
| Backward compatibility | Null IdeContext produces same prompt as before |
| Token efficiency | Smaller prompts in simpler environments (no Spring content in PyCharm) |

**All plans complete after Plan D:**
- Plan A: Foundation (IdeContext, plugin descriptor, MySQL removal) — DONE
- Plan B1: Provider infrastructure (interface, registry, Java/Kotlin refactor) — DONE
- Plan B2: Python provider (PythonPsiHelper, PythonProvider) — DONE
- Plan C: Python ecosystem (Django/FastAPI/Flask, build, debug, persona) — independent
- Plan D: Prompt, skills, discovery (this plan) — independent
