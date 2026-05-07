package com.workflow.orchestrator.jira.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Architectural guard: `JiraApiClient.transitionIssue` must have **exactly one** caller
 * project-wide — `TicketTransitionServiceImpl`.
 *
 * Locks down the "no shortcut paths to write APIs" rule established by the 2026-05-08
 * write-ops audit (PR 4). Before this fix, [JiraServiceImpl.startWork] and
 * [JiraServiceImpl.transition] both posted to `/transitions` directly with
 * `fields={}`, bypassing the `expand=transitions.fields` preflight that
 * `TicketTransitionService.executeTransition` runs. If a Jira admin marks any field
 * required on a transition (a common workflow rule), that produced silent 400s.
 *
 * The canonical write path is:
 *
 * ```
 * caller → TicketTransitionService.executeTransition
 *        → JiraApiClient.transitionIssue
 * ```
 *
 * Any new caller introduced to `JiraApiClient.transitionIssue` is a regression and
 * fails this test in CI. Future contributors: do **not** "just add another call site"
 * — route through `TicketTransitionService` so the field-preflight runs.
 *
 * Source-text scan rather than reflection because the constraint is about who calls
 * the function, not what it returns. Modeled on `IntegrationToolDispatchEnumDriftTest`
 * in `:agent`.
 */
class SingleCallerOfTransitionIssueTest {

    /**
     * Modules to scan. Anything that depends on `:jira` (transitively or directly) and
     * could conceivably hold a `JiraApiClient.transitionIssue` call site lives here.
     *
     * The `:jira` module is where the API client and the canonical service live, so it
     * is also the module where the one allowed caller (`TicketTransitionServiceImpl`)
     * is defined. The other modules are agent / handover / automation tool wrappers
     * that route writes through `JiraService` or `TicketTransitionService`; the test
     * fails if any of them ever bypasses those façades.
     */
    private val modulesToScan = listOf("jira", "agent", "handover", "automation", "core")

    @Test
    fun `transitionIssue is invoked by exactly one production source file`() {
        val projectRoot = locateProjectRoot()
        val callers = mutableListOf<String>()

        modulesToScan.forEach { module ->
            val mainRoot = File(projectRoot, "$module/src/main/kotlin")
            if (!mainRoot.isDirectory) return@forEach
            mainRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { ktFile ->
                    val source = Files.readString(ktFile.toPath())
                    if (containsTransitionIssueCall(source)) {
                        // Record path relative to project root for a clean failure message
                        val rel = ktFile.relativeTo(projectRoot).path
                        callers += rel
                    }
                }
        }

        // Exactly one allowed caller: TicketTransitionServiceImpl in :jira.
        val allowed = "jira/src/main/kotlin/com/workflow/orchestrator/jira/service/TicketTransitionServiceImpl.kt"
        val unauthorized = callers.filter { it != allowed }

        assertTrue(
            unauthorized.isEmpty(),
            "Found ${unauthorized.size} unauthorized caller(s) of JiraApiClient.transitionIssue. " +
                "All transition writes MUST go through TicketTransitionService.executeTransition " +
                "so the required-field preflight runs. Offending files:\n" +
                unauthorized.joinToString("\n") { "  - $it" } +
                "\n\nFix: replace the direct api.transitionIssue(...) call with " +
                "TicketTransitionService.executeTransition(key, TransitionInput(...))."
        )

        assertNotNull(
            callers.firstOrNull { it == allowed },
            "Expected the canonical caller `$allowed` to be present and call transitionIssue. " +
                "If the implementation moved, update this test's `allowed` constant. " +
                "All callers found: $callers"
        )

        assertEquals(
            1, callers.size,
            "Expected exactly 1 caller (TicketTransitionServiceImpl). Found ${callers.size}: $callers"
        )
    }

    /**
     * Detects whether [source] calls `transitionIssue(`. Excludes:
     * - Definitions: `fun transitionIssue(` (so `JiraApiClient.kt` itself does not
     *   count as a caller).
     * - String literals or KDoc references: only matches dot-call form `.transitionIssue(`
     *   so a `// see [transitionIssue]` comment, a log message containing the word, or
     *   a function name that ends with `transitionIssue` at column 0 will not trip it.
     */
    internal fun containsTransitionIssueCall(source: String): Boolean {
        // The dot-call regex is the operative one — `.transitionIssue(` only matches
        // call sites, not the definition (which is `fun transitionIssue(`).
        val callPattern = Regex("""\.transitionIssue\s*\(""")
        return callPattern.containsMatchIn(source)
    }

    // ── Regex unit tests (guard against regex rot) ───────────────────────────

    @Test
    fun `regex matches a dot-call`() {
        assertTrue(containsTransitionIssueCall("api.transitionIssue(key, input)"))
        // Whitespace between the function name and the open paren is allowed by Kotlin
        // and must still match.
        assertTrue(containsTransitionIssueCall("api.transitionIssue (key, input)"))
        // `when (val r = api.transitionIssue(key, input))` — common in this codebase.
        assertTrue(containsTransitionIssueCall("return when (val r = api.transitionIssue(\"PROJ-1\", input)) {"))
    }

    @Test
    fun `regex does not match the definition or KDoc references`() {
        // The `fun transitionIssue(` definition in JiraApiClient.kt must NOT count
        // as a caller, otherwise the test fails for the wrong reason.
        val definition = """
            class JiraApiClient {
                suspend fun transitionIssue(issueKey: String, input: TransitionInput): ApiResult<Unit> {
                    return ApiResult.Success(Unit)
                }
            }
        """.trimIndent()
        // The class declaration alone (no caller) must not match.
        assertTrue(
            !containsTransitionIssueCall(definition),
            "Definition without any call site must not be counted as a caller"
        )

        val kdoc = """
            /**
             * See [transitionIssue] for the canonical write path.
             */
            class Foo
        """.trimIndent()
        assertTrue(
            !containsTransitionIssueCall(kdoc),
            "KDoc reference must not be counted as a caller"
        )
    }

    @Test
    fun `regex matches both definition-bearing file and a separate caller when both forms appear`() {
        // Edge case: a single file that both defines AND calls (e.g. JiraApiClient
        // calling itself recursively). The dot-call regex catches the recursion site
        // but not the bare definition. We accept this — recursion would still be a
        // call site and should count.
        val withRecursion = """
            class JiraApiClient {
                suspend fun transitionIssue(): Unit {
                    if (false) this.transitionIssue()
                }
            }
        """.trimIndent()
        assertTrue(containsTransitionIssueCall(withRecursion))
    }

    /**
     * Returns the project root, handling Gradle's habit of running tests from either
     * the module root (`:jira:test`) or the project root.
     */
    private fun locateProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        // Project root contains the `core/` and `jira/` modules side-by-side.
        listOf(cwd, cwd.parentFile, cwd.parentFile?.parentFile)
            .filterNotNull()
            .forEach { candidate ->
                if (File(candidate, "jira/src/main/kotlin").isDirectory &&
                    File(candidate, "core/src/main/kotlin").isDirectory) {
                    return candidate
                }
            }
        throw IllegalStateException(
            "Cannot locate project root from cwd=${cwd.absolutePath}. " +
                "Expected to find jira/src/main/kotlin and core/src/main/kotlin side-by-side."
        )
    }
}
