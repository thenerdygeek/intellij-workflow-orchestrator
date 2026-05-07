package com.workflow.orchestrator.agent.tools.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Dispatch ⊆ enumValues drift test.
 *
 * Catches the failure mode where a developer adds a new `"new_action" ->` branch
 * to a tool's `when (action)` dispatch block but forgets to add `"new_action"` to
 * the tool's `enumValues` list. Result: the action works at the API level but the
 * LLM never learns about it because ToolPromptBuilder emits only the enumValues list.
 *
 * Uses source parsing rather than runtime reflection because the dispatch lives in a
 * `when` expression, not as a field exposed by the tool interface.
 */
class IntegrationToolDispatchEnumDriftTest {

    // ---------------------------------------------------------------------------
    // Data model
    // ---------------------------------------------------------------------------

    private data class ToolUnderTest(
        val name: String,
        val relativeSourcePath: String,          // relative to agent/src/main/kotlin
        val advertisedActions: Set<String>        // from enumValues at runtime
    )

    // ---------------------------------------------------------------------------
    // Tools under test
    // ---------------------------------------------------------------------------

    private fun toolsUnderTest(): List<ToolUnderTest> {
        val base = "com/workflow/orchestrator/agent/tools/integration"
        return listOf(
            ToolUnderTest(
                name = "jira",
                relativeSourcePath = "$base/JiraTool.kt",
                advertisedActions = JiraTool().parameters.properties["action"]?.enumValues?.toSet() ?: emptySet()
            ),
            ToolUnderTest(
                name = "bamboo_builds",
                relativeSourcePath = "$base/BambooBuildsTool.kt",
                advertisedActions = BambooBuildsTool().parameters.properties["action"]?.enumValues?.toSet() ?: emptySet()
            ),
            ToolUnderTest(
                name = "bamboo_plans",
                relativeSourcePath = "$base/BambooPlansTool.kt",
                advertisedActions = BambooPlansTool().parameters.properties["action"]?.enumValues?.toSet() ?: emptySet()
            ),
            ToolUnderTest(
                name = "bitbucket_pr",
                relativeSourcePath = "$base/BitbucketPrTool.kt",
                advertisedActions = BitbucketPrTool().parameters.properties["action"]?.enumValues?.toSet() ?: emptySet()
            ),
            ToolUnderTest(
                name = "bitbucket_repo",
                relativeSourcePath = "$base/BitbucketRepoTool.kt",
                advertisedActions = BitbucketRepoTool().parameters.properties["action"]?.enumValues?.toSet() ?: emptySet()
            ),
            ToolUnderTest(
                name = "bitbucket_review",
                relativeSourcePath = "$base/BitbucketReviewTool.kt",
                advertisedActions = BitbucketReviewTool().parameters.properties["action"]?.enumValues?.toSet() ?: emptySet()
            ),
            ToolUnderTest(
                name = "sonar",
                relativeSourcePath = "$base/SonarTool.kt",
                advertisedActions = SonarTool().parameters.properties["action"]?.enumValues?.toSet() ?: emptySet()
            )
        )
    }

    // ---------------------------------------------------------------------------
    // Main assertion
    // ---------------------------------------------------------------------------

    @Test
    fun `every action in dispatch when-block is advertised in enumValues`() {
        val sourceRoot = locateSourceRoot()
        val violations = mutableListOf<String>()

        toolsUnderTest().forEach { tool ->
            val sourceFile = File(sourceRoot, tool.relativeSourcePath)
            assertTrue(sourceFile.exists(),
                "Source file not found for '${tool.name}': ${sourceFile.absolutePath}")

            val source = Files.readString(sourceFile.toPath())
            val dispatchActions = extractDispatchActions(source)

            if (dispatchActions.isEmpty()) {
                // Fail loudly if the file has no recognisable dispatch block — the regex
                // may need updating if the tool was rewritten.
                assertTrue(
                    false,
                    "Tool '${tool.name}': extractDispatchActions returned empty — " +
                        "no `when (action)` block found in ${sourceFile.name}. " +
                        "Update the regex or the file path."
                )
            }

            val orphans = dispatchActions - tool.advertisedActions
            orphans.forEach { orphan ->
                violations += "Tool '${tool.name}': dispatch handles \"$orphan\" " +
                    "but it is missing from enumValues (LLM cannot reach it)"
            }
        }

        assertTrue(violations.isEmpty(),
            "Found ${violations.size} dispatch-only actions (invisible to LLM):\n" +
                violations.joinToString("\n"))
    }

    // ---------------------------------------------------------------------------
    // Regex unit tests (guard against regex rot)
    // ---------------------------------------------------------------------------

    @Test
    fun `extractDispatchActions returns empty set for source without when-block`() {
        val source = """
            class FakeTool {
                fun execute(action: String) {
                    if (action == "foo") doFoo()
                }
            }
        """.trimIndent()
        assertEquals(emptySet<String>(), extractDispatchActions(source),
            "Should find nothing when there is no `when (action)` block")
    }

    @Test
    fun `extractDispatchActions extracts single-string arms`() {
        val source = """
            return when (action) {
                "get_ticket" -> getTicket()
                "transition" -> doTransition()
                else -> error("unknown")
            }
        """.trimIndent()
        assertEquals(setOf("get_ticket", "transition"), extractDispatchActions(source))
    }

    @Test
    fun `extractDispatchActions extracts multi-string arms`() {
        val source = """
            return when (action) {
                "get_ticket" -> getTicket()
                "search_issues", "search_tickets" -> doSearch()
                else -> error("unknown")
            }
        """.trimIndent()
        assertEquals(
            setOf("get_ticket", "search_issues", "search_tickets"),
            extractDispatchActions(source)
        )
    }

    @Test
    fun `extractDispatchActions does not include string literals inside handler bodies`() {
        val source = """
            return when (action) {
                "get_ticket" -> {
                    val url = someService.fetch("internal_endpoint")
                    val mode = "read_mode"
                    doGet()
                }
                "transition" -> doTransition()
                else -> error("unknown")
            }
        """.trimIndent()
        val result = extractDispatchActions(source)
        // internal_endpoint and read_mode should NOT appear; only when-arm labels
        assertEquals(setOf("get_ticket", "transition"), result,
            "Body string literals must not be captured as action keys")
    }

    // ---------------------------------------------------------------------------
    // Source parsing
    // ---------------------------------------------------------------------------

    /**
     * Extracts every quoted string that is a `when (action)` arm label.
     *
     * Strategy:
     * 1. Find the `when (action)` block and extract its body via brace-counting.
     * 2. Split the body into logical arms by scanning line-by-line for lines that
     *    contain a `->` and whose leftmost non-whitespace content is a quoted string
     *    or a comma-separated list of quoted strings.  This avoids matching string
     *    literals that appear INSIDE arm bodies (e.g. service calls, log messages).
     *
     * The approach handles:
     * - Single-string arms: `"foo" ->`
     * - Multi-string arms:  `"foo", "bar" ->`
     * - Arms with `{` on the same line: `"foo" -> { … }`
     * - Arms spanning multi-line when the labels are on one line and body on next.
     *
     * It does NOT handle arms where the action string is not the first token on the
     * line (e.g. `else -> …`, `is SomeType -> …`).  Those are intentionally excluded.
     */
    internal fun extractDispatchActions(source: String): Set<String> {
        // Step 1: locate `when (action)` and its opening brace
        val whenStart = source.indexOf("when (action)")
        if (whenStart == -1) return emptySet()
        val bodyStart = source.indexOf('{', whenStart)
        if (bodyStart == -1) return emptySet()

        // Step 2: find matching closing brace via depth counting
        var depth = 0
        var bodyEnd = -1
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        bodyEnd = i
                        break
                    }
                }
            }
        }
        if (bodyEnd == -1) return emptySet()

        val body = source.substring(bodyStart + 1, bodyEnd)

        // Step 3: extract arm labels only.
        //
        // An arm label line is a line whose trimmed content starts with `"` and
        // eventually contains `->`.  We collect all quoted tokens from the label
        // portion (everything before `->`), ignoring any strings that appear after
        // `->` (which are inside the handler body on the same line).
        val actions = mutableSetOf<String>()
        // Each logical line of the body may be an arm label.
        // Pattern: trim to leading `"…"` (and optional `, "…"` repetitions) followed by `->`.
        val armLabelPattern = Regex("""^\s*("([a-z][a-z0-9_]*)"(?:\s*,\s*"([a-z][a-z0-9_]*)")*)\s*->""")
        // Pattern to extract all individual quoted identifiers from a matched label segment
        val tokenPattern = Regex(""""([a-z][a-z0-9_]*)"""")

        for (line in body.lines()) {
            val match = armLabelPattern.find(line) ?: continue
            // The label segment is match.groupValues[1] — everything before `->`
            val labelSegment = match.groupValues[1]
            tokenPattern.findAll(labelSegment).forEach { m ->
                actions += m.groupValues[1]
            }
        }

        return actions
    }

    // ---------------------------------------------------------------------------
    // Path resolution (handles Gradle module-root and project-root CWD variants)
    // ---------------------------------------------------------------------------

    /**
     * Returns the `agent/src/main/kotlin` directory, regardless of whether Gradle
     * is running tests from the project root or the module root.
     *
     * Same multi-candidate strategy as [SpillingWiringTest].
     */
    private fun locateSourceRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        // Gradle usually sets cwd to the module root when running :agent:test
        val fromModuleRoot = File(cwd, "src/main/kotlin")
        if (fromModuleRoot.isDirectory) return fromModuleRoot
        // Fallback: cwd is the project root (e.g. when running from the repo root)
        val fromProjectRoot = File(cwd, "agent/src/main/kotlin")
        if (fromProjectRoot.isDirectory) return fromProjectRoot
        // Worktree variant: one level up
        val worktreeParent = cwd.parentFile ?: cwd
        val fromWorktree = File(worktreeParent, "agent/src/main/kotlin")
        if (fromWorktree.isDirectory) return fromWorktree
        throw IllegalStateException(
            "Cannot locate agent/src/main/kotlin; tried:\n" +
                "  $fromModuleRoot\n  $fromProjectRoot\n  $fromWorktree\n" +
                "cwd=${cwd.absolutePath}"
        )
    }
}
