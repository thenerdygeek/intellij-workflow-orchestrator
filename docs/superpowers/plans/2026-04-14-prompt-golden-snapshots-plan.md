# Prompt Golden Snapshots — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add golden snapshot regression tests for every IDE variant's system prompt. Any future prompt refactor that changes wording, section ordering, or content inclusion will be caught by a snapshot diff — not discovered in production when the LLM behaves differently.

**Architecture:** Generate a snapshot file per IDE variant. Tests compare `SystemPrompt.build()` output against the saved snapshot. On intentional changes, the developer re-runs the snapshot capture to update the files. This is the same pattern used by Jest snapshot testing — verified output is committed, diffs are visible in code review.

**Tech Stack:** Kotlin, JUnit 5

**Depends on:** Plan D (completed — IDE-aware system prompt)

---

## File Map

| Action | File | Purpose |
|---|---|---|
| Modify | `agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptIdeContextTest.kt` | Add per-variant snapshot tests |
| Create | `agent/src/test/resources/prompt-snapshots/intellij-ultimate.txt` | IntelliJ Ultimate with Spring + Gradle |
| Create | `agent/src/test/resources/prompt-snapshots/intellij-community.txt` | IntelliJ Community with Maven, no Spring |
| Create | `agent/src/test/resources/prompt-snapshots/pycharm-professional.txt` | PyCharm Professional with Django + Poetry |
| Create | `agent/src/test/resources/prompt-snapshots/pycharm-community.txt` | PyCharm Community with FastAPI + uv |
| Create | `agent/src/test/resources/prompt-snapshots/webstorm.txt` | WebStorm (OTHER) — base tools only |
| Create | `agent/src/test/resources/prompt-snapshots/intellij-ultimate-mixed.txt` | IntelliJ Ultimate with Java + Python + Spring + Django |
| Create | `agent/src/test/resources/prompt-snapshots/null-context.txt` | Null IdeContext — backward compatibility baseline |

---

## Task 1: Create Snapshot Infrastructure and Generate All Snapshots

**Files:**
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptIdeContextTest.kt`
- Create: 7 snapshot files in `agent/src/test/resources/prompt-snapshots/`

- [ ] **Step 1: Add snapshot helper and IDE context factories**

Add to `SystemPromptIdeContextTest.kt`:

```kotlin
companion object {
    private val SNAPSHOT_DIR = "src/test/resources/prompt-snapshots"

    /** Standard build params used for all snapshots (consistent, reproducible) */
    private fun buildPrompt(ideContext: IdeContext? = null) = SystemPrompt.build(
        projectName = "SnapshotProject",
        projectPath = "/snapshot/project",
        osName = "Linux",
        shell = "/bin/bash",
        ideContext = ideContext,
    )

    private fun saveSnapshot(name: String, content: String) {
        val file = File("$SNAPSHOT_DIR/$name.txt")
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun loadSnapshot(name: String): String? {
        val file = File("$SNAPSHOT_DIR/$name.txt")
        return if (file.exists()) file.readText() else null
    }

    // ---- IDE Context Factories ----

    fun intellijUltimate() = IdeContext(
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

    fun intellijCommunity() = IdeContext(
        product = IdeProduct.INTELLIJ_COMMUNITY,
        productName = "IntelliJ IDEA 2025.1 Community",
        edition = Edition.COMMUNITY,
        languages = setOf(Language.JAVA, Language.KOTLIN),
        hasJavaPlugin = true,
        hasPythonPlugin = false,
        hasPythonCorePlugin = false,
        hasSpringPlugin = false,
        detectedFrameworks = emptySet(),
        detectedBuildTools = setOf(BuildTool.MAVEN),
    )

    fun pycharmProfessional() = IdeContext(
        product = IdeProduct.PYCHARM_PROFESSIONAL,
        productName = "PyCharm 2025.1 Professional",
        edition = Edition.PROFESSIONAL,
        languages = setOf(Language.PYTHON),
        hasJavaPlugin = false,
        hasPythonPlugin = true,
        hasPythonCorePlugin = false,
        hasSpringPlugin = false,
        detectedFrameworks = setOf(Framework.DJANGO),
        detectedBuildTools = setOf(BuildTool.POETRY),
    )

    fun pycharmCommunity() = IdeContext(
        product = IdeProduct.PYCHARM_COMMUNITY,
        productName = "PyCharm 2025.1 Community",
        edition = Edition.COMMUNITY,
        languages = setOf(Language.PYTHON),
        hasJavaPlugin = false,
        hasPythonPlugin = false,
        hasPythonCorePlugin = true,
        hasSpringPlugin = false,
        detectedFrameworks = setOf(Framework.FASTAPI),
        detectedBuildTools = setOf(BuildTool.UV),
    )

    fun webstorm() = IdeContext(
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

    fun intellijUltimateMixed() = IdeContext(
        product = IdeProduct.INTELLIJ_ULTIMATE,
        productName = "IntelliJ IDEA 2025.1 Ultimate",
        edition = Edition.ULTIMATE,
        languages = setOf(Language.JAVA, Language.KOTLIN, Language.PYTHON),
        hasJavaPlugin = true,
        hasPythonPlugin = true,
        hasPythonCorePlugin = false,
        hasSpringPlugin = true,
        detectedFrameworks = setOf(Framework.SPRING, Framework.DJANGO),
        detectedBuildTools = setOf(BuildTool.GRADLE, BuildTool.POETRY),
    )
}
```

- [ ] **Step 2: Add snapshot generation test (run once to create files)**

```kotlin
@Test
fun `generate all golden snapshots`() {
    saveSnapshot("null-context", buildPrompt(null))
    saveSnapshot("intellij-ultimate", buildPrompt(intellijUltimate()))
    saveSnapshot("intellij-community", buildPrompt(intellijCommunity()))
    saveSnapshot("pycharm-professional", buildPrompt(pycharmProfessional()))
    saveSnapshot("pycharm-community", buildPrompt(pycharmCommunity()))
    saveSnapshot("webstorm", buildPrompt(webstorm()))
    saveSnapshot("intellij-ultimate-mixed", buildPrompt(intellijUltimateMixed()))

    // Verify all files were created
    val dir = File(SNAPSHOT_DIR)
    assertTrue(dir.exists())
    assertEquals(7, dir.listFiles()?.count { it.extension == "txt" },
        "Should have created 7 snapshot files")
}
```

- [ ] **Step 3: Run to generate snapshots**

Run: `./gradlew :agent:test --tests "*SystemPromptIdeContextTest*generate*" -v`
Expected: PASS — 7 snapshot files created

- [ ] **Step 4: Verify snapshots look correct**

Manually inspect each snapshot file to confirm:
- `intellij-ultimate.txt` contains Spring, Gradle, spring-boot-engineer, no Python
- `intellij-community.txt` contains Maven, no Spring, no Python
- `pycharm-professional.txt` contains Python, Django, poetry, python-engineer, no Spring/mvn/gradlew
- `pycharm-community.txt` contains Python, FastAPI, uv, no Spring/mvn/gradlew
- `webstorm.txt` contains WebStorm, no Spring/Python/mvn/gradlew/pytest
- `intellij-ultimate-mixed.txt` contains Java, Kotlin, Python, Spring, Django, Gradle, poetry, both engineers
- `null-context.txt` contains IntelliJ IDEA, Spring/Java defaults

- [ ] **Step 5: Commit snapshot files**

```bash
git add agent/src/test/resources/prompt-snapshots/
git commit -m "test(agent): generate golden prompt snapshots for all 7 IDE variants

Captures SystemPrompt.build() output for: IntelliJ Ultimate, IntelliJ
Community, PyCharm Professional, PyCharm Community, WebStorm, IntelliJ
mixed (Java+Python), and null context (backward compatibility)."
```

---

## Task 2: Add Snapshot Regression Tests

**Files:**
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptIdeContextTest.kt`

- [ ] **Step 1: Add regression test for each variant**

```kotlin
// ==================== Golden Snapshot Regression Tests ====================

@Test
fun `SNAPSHOT null context matches golden file`() {
    val prompt = buildPrompt(null)
    val snapshot = loadSnapshot("null-context")
    assertNotNull(snapshot, "Golden snapshot 'null-context.txt' not found — run 'generate all golden snapshots' first")
    assertEquals(snapshot, prompt,
        "Prompt for null context has changed from golden snapshot. " +
        "If this change is intentional, re-run 'generate all golden snapshots' to update.")
}

@Test
fun `SNAPSHOT IntelliJ Ultimate matches golden file`() {
    val prompt = buildPrompt(intellijUltimate())
    val snapshot = loadSnapshot("intellij-ultimate")
    assertNotNull(snapshot, "Golden snapshot 'intellij-ultimate.txt' not found")
    assertEquals(snapshot, prompt,
        "Prompt for IntelliJ Ultimate has changed from golden snapshot. " +
        "If intentional, re-run 'generate all golden snapshots' to update.")
}

@Test
fun `SNAPSHOT IntelliJ Community matches golden file`() {
    val prompt = buildPrompt(intellijCommunity())
    val snapshot = loadSnapshot("intellij-community")
    assertNotNull(snapshot, "Golden snapshot 'intellij-community.txt' not found")
    assertEquals(snapshot, prompt,
        "Prompt for IntelliJ Community has changed from golden snapshot. " +
        "If intentional, re-run 'generate all golden snapshots' to update.")
}

@Test
fun `SNAPSHOT PyCharm Professional matches golden file`() {
    val prompt = buildPrompt(pycharmProfessional())
    val snapshot = loadSnapshot("pycharm-professional")
    assertNotNull(snapshot, "Golden snapshot 'pycharm-professional.txt' not found")
    assertEquals(snapshot, prompt,
        "Prompt for PyCharm Professional has changed from golden snapshot. " +
        "If intentional, re-run 'generate all golden snapshots' to update.")
}

@Test
fun `SNAPSHOT PyCharm Community matches golden file`() {
    val prompt = buildPrompt(pycharmCommunity())
    val snapshot = loadSnapshot("pycharm-community")
    assertNotNull(snapshot, "Golden snapshot 'pycharm-community.txt' not found")
    assertEquals(snapshot, prompt,
        "Prompt for PyCharm Community has changed from golden snapshot. " +
        "If intentional, re-run 'generate all golden snapshots' to update.")
}

@Test
fun `SNAPSHOT WebStorm matches golden file`() {
    val prompt = buildPrompt(webstorm())
    val snapshot = loadSnapshot("webstorm")
    assertNotNull(snapshot, "Golden snapshot 'webstorm.txt' not found")
    assertEquals(snapshot, prompt,
        "Prompt for WebStorm has changed from golden snapshot. " +
        "If intentional, re-run 'generate all golden snapshots' to update.")
}

@Test
fun `SNAPSHOT IntelliJ Ultimate mixed matches golden file`() {
    val prompt = buildPrompt(intellijUltimateMixed())
    val snapshot = loadSnapshot("intellij-ultimate-mixed")
    assertNotNull(snapshot, "Golden snapshot 'intellij-ultimate-mixed.txt' not found")
    assertEquals(snapshot, prompt,
        "Prompt for IntelliJ Ultimate (mixed Java+Python) has changed from golden snapshot. " +
        "If intentional, re-run 'generate all golden snapshots' to update.")
}
```

- [ ] **Step 2: Add cross-variant exclusion tests**

These verify that content from one variant doesn't leak into another:

```kotlin
// ==================== Cross-Variant Isolation Tests ====================

@Test
fun `ISOLATION no Python content in IntelliJ-only snapshots`() {
    val prompt = buildPrompt(intellijUltimate())
    assertFalse(prompt.contains("pytest"), "IntelliJ Ultimate should not mention pytest")
    assertFalse(prompt.contains("python-engineer"), "IntelliJ Ultimate should not mention python-engineer")
    assertFalse(prompt.contains("Django URLs"), "IntelliJ Ultimate should not mention Django URLs")
    assertFalse(prompt.contains("FastAPI routes"), "IntelliJ Ultimate should not mention FastAPI routes")
    assertFalse(prompt.contains("Flask routes"), "IntelliJ Ultimate should not mention Flask routes")
}

@Test
fun `ISOLATION no Java content in PyCharm-only snapshots`() {
    val prompt = buildPrompt(pycharmProfessional())
    assertFalse(prompt.contains("spring-boot-engineer"), "PyCharm should not mention spring-boot-engineer")
    assertFalse(prompt.contains("mvn compile"), "PyCharm should not mention mvn compile")
    assertFalse(prompt.contains("gradlew"), "PyCharm should not mention gradlew")
    assertFalse(prompt.contains("@PostMapping"), "PyCharm should not mention @PostMapping")
    assertFalse(prompt.contains("@Bean"), "PyCharm should not mention @Bean")
}

@Test
fun `ISOLATION WebStorm has no language-specific content`() {
    val prompt = buildPrompt(webstorm())
    assertFalse(prompt.contains("spring-boot-engineer"))
    assertFalse(prompt.contains("python-engineer"))
    assertFalse(prompt.contains("mvn compile"))
    assertFalse(prompt.contains("gradlew"))
    assertFalse(prompt.contains("pytest"))
    assertFalse(prompt.contains("Django"))
    assertFalse(prompt.contains("FastAPI"))
    assertFalse(prompt.contains("Flask"))
    assertFalse(prompt.contains("@PostMapping"))
}

@Test
fun `ISOLATION mixed project has content from both languages`() {
    val prompt = buildPrompt(intellijUltimateMixed())
    // Should have Java content
    assertTrue(prompt.contains("spring-boot-engineer"))
    assertTrue(prompt.contains("mvn compile") || prompt.contains("gradlew"))
    // Should also have Python content
    assertTrue(prompt.contains("python-engineer"))
    assertTrue(prompt.contains("pytest") || prompt.contains("Django"))
}
```

- [ ] **Step 3: Add prompt size bounds test per variant**

```kotlin
// ==================== Size Bounds Tests ====================

@Test
fun `all variants are within acceptable size bounds`() {
    data class VariantSize(val name: String, val context: IdeContext?, val minChars: Int, val maxChars: Int)

    val variants = listOf(
        VariantSize("null", null, 5000, 25000),
        VariantSize("IntelliJ Ultimate", intellijUltimate(), 5000, 25000),
        VariantSize("IntelliJ Community", intellijCommunity(), 5000, 25000),
        VariantSize("PyCharm Professional", pycharmProfessional(), 5000, 25000),
        VariantSize("PyCharm Community", pycharmCommunity(), 5000, 25000),
        VariantSize("WebStorm", webstorm(), 4000, 20000), // Smaller — less content
        VariantSize("Mixed", intellijUltimateMixed(), 6000, 28000), // Larger — both languages
    )

    for ((name, context, min, max) in variants) {
        val prompt = buildPrompt(context)
        assertTrue(prompt.length in min..max,
            "$name prompt size ${prompt.length} chars outside bounds [$min, $max]")
    }
}

@Test
fun `WebStorm prompt is smaller than IntelliJ Ultimate prompt`() {
    val webstormPrompt = buildPrompt(webstorm())
    val intellijPrompt = buildPrompt(intellijUltimate())
    assertTrue(webstormPrompt.length < intellijPrompt.length,
        "WebStorm prompt (${webstormPrompt.length}) should be smaller than IntelliJ (${intellijPrompt.length}) — fewer language-specific sections")
}
```

- [ ] **Step 4: Run all tests**

Run: `./gradlew :agent:test --tests "*SystemPromptIdeContextTest*" -v`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptIdeContextTest.kt
git commit -m "test(agent): add golden snapshot regression tests for all IDE prompt variants

7 snapshot comparisons (exact match), 4 cross-variant isolation tests,
and size bounds checks. Any future prompt change will show up as a test
failure with a clear message to re-generate snapshots if intentional."
```

---

## Task 3: Remove Old Snapshot and Clean Up

**Files:**
- Delete: `agent/src/test/resources/prompt-snapshot-intellij-ultimate.txt` (old single snapshot from Plan D Task 1)
- Modify: `SystemPromptIdeContextTest.kt` (remove old snapshot test that references the old file)

- [ ] **Step 1: Remove old snapshot file and test**

The old `prompt-snapshot-intellij-ultimate.txt` was created in Plan D Task 1 as a temporary reference. It's now superseded by the 7-variant snapshots in `prompt-snapshots/`. Remove the old file and the test that writes to it.

- [ ] **Step 2: Run all tests**

Run: `./gradlew :agent:test --tests "*SystemPromptIdeContextTest*" -v`
Expected: ALL PASS

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore(agent): replace single prompt snapshot with 7-variant snapshot suite

Old prompt-snapshot-intellij-ultimate.txt superseded by prompt-snapshots/
directory with per-variant golden files."
```

---

## Task 4: Document Snapshot Workflow

**Files:**
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Add snapshot update instructions**

Add to the "Language Intelligence Providers" or "System Prompt" section in `agent/CLAUDE.md`:

```markdown
## System Prompt Snapshot Tests

Golden snapshot tests verify the exact system prompt output for 7 IDE variants:
- `prompt-snapshots/null-context.txt` — backward compatibility baseline
- `prompt-snapshots/intellij-ultimate.txt` — Java/Kotlin + Spring + Gradle
- `prompt-snapshots/intellij-community.txt` — Java/Kotlin + Maven, no Spring
- `prompt-snapshots/pycharm-professional.txt` — Python + Django + Poetry
- `prompt-snapshots/pycharm-community.txt` — Python + FastAPI + uv
- `prompt-snapshots/webstorm.txt` — base tools only (no language-specific content)
- `prompt-snapshots/intellij-ultimate-mixed.txt` — Java + Python + Spring + Django

**When you change SystemPrompt.kt:**
1. Run `./gradlew :agent:test --tests "*SNAPSHOT*"` — expect failures on changed variants
2. Review the diff to confirm changes are intentional
3. Run `./gradlew :agent:test --tests "*generate all golden snapshots*"` to regenerate
4. Run `./gradlew :agent:test --tests "*SNAPSHOT*"` again — all should pass
5. Commit the updated snapshot files alongside the code change
```

- [ ] **Step 2: Commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs(agent): add prompt snapshot update workflow to CLAUDE.md"
```

---

## Summary

After this plan:

| What changed | Result |
|---|---|
| 7 golden snapshot files | Exact prompt output for every IDE variant committed as test resources |
| 7 snapshot regression tests | `assertEquals(snapshot, prompt)` — catches any prompt wording change |
| 4 cross-variant isolation tests | Verifies no Java content leaks into PyCharm, no Python into IntelliJ |
| Size bounds tests | Verifies prompts stay within reasonable token budgets per variant |
| Documented workflow | CLAUDE.md explains how to update snapshots after intentional changes |

**Test count added:** ~18 new tests in `SystemPromptIdeContextTest` (7 snapshot comparisons + 4 isolation + 2 size bounds + generation + cleanup + existing).
