package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Coverage for the fixes documented in tools/feedback.md:
 *   §2  — set_value mutation-shape detection (clearer error than the JDI assignment
 *         fallback's "Incompatible types for '=' operation").
 *   §5  — CGLIB / Spring-proxy field filtering in formatVariables.
 *
 * Note: the actual XValue placeholder-skip in `AgentDebugController.resolvePresentation`
 * cannot be unit-tested without a live IntelliJ XDebugger instance — the IntelliJ test
 * framework would be required. We pin the public observable contract here and leave
 * the lazy-await behaviour to integration testing on a real debugger session.
 */
class DebugFeedbackFixesTest {

    // ──────────────────────────────────────────────────────────────────────────
    // set_value mutation detection — DebugInspectTool.detectMutationExpression
    // ──────────────────────────────────────────────────────────────────────────

    private val project = mockk<Project>(relaxed = true)
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val tool = DebugInspectTool(controller)

    @Test
    @DisplayName("§2 — plain integer literal is not flagged as mutation")
    fun `plain integer literal passes through`() {
        assertNull(tool.detectMutationExpression("42"))
    }

    @Test
    @DisplayName("§2 — quoted string literal is not flagged")
    fun `quoted string literal passes through`() {
        assertNull(tool.detectMutationExpression("\"hello\""))
    }

    @Test
    @DisplayName("§2 — null literal is not flagged")
    fun `null literal passes through`() {
        assertNull(tool.detectMutationExpression("null"))
    }

    @Test
    @DisplayName("§2 — boolean literals are not flagged")
    fun `boolean literals pass through`() {
        assertNull(tool.detectMutationExpression("true"))
        assertNull(tool.detectMutationExpression("false"))
    }

    @Test
    @DisplayName("§2 — float literals are not flagged")
    fun `float literals pass through`() {
        assertNull(tool.detectMutationExpression("3.14"))
    }

    @Test
    @DisplayName("§2 — method-call expression IS flagged")
    fun `method call expression flagged`() {
        val hint = tool.detectMutationExpression("orderPrice.setSelectedItem(new ItemOption())")
        assertNotNull(hint)
        assertTrue(hint!!.contains("method"))
    }

    @Test
    @DisplayName("§2 — bare function-call shape IS flagged")
    fun `bare function call flagged`() {
        val hint = tool.detectMutationExpression("foo()")
        assertNotNull(hint)
    }

    @Test
    @DisplayName("§2 — new-expression IS flagged as constructor")
    fun `new expression flagged as constructor`() {
        val hint = tool.detectMutationExpression("new ItemOption()")
        assertNotNull(hint)
        assertTrue(hint!!.contains("constructor") || hint.contains("new"))
    }

    @Test
    @DisplayName("§2 — empty / blank expressions are NOT flagged (caller handles them)")
    fun `empty expression passes through`() {
        assertNull(tool.detectMutationExpression(""))
        assertNull(tool.detectMutationExpression("   "))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Proxy internal field filtering — DebugStepUtils.isProxyInternalField +
    // formatVariables filtering footer
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("§5 — CGLIB synthetic fields are detected")
    fun `CGLIB fields are detected as internal`() {
        assertTrue(isProxyInternalField("CGLIB\$BOUND"))
        assertTrue(isProxyInternalField("CGLIB\$FACTORY_DATA"))
        assertTrue(isProxyInternalField("CGLIB\$THREAD_CALLBACKS"))
    }

    @Test
    @DisplayName("§5 — ordinary fields are NOT filtered (including names that LOOK proxy-ish)")
    fun `ordinary fields are not filtered`() {
        assertFalse(isProxyInternalField("name"))
        assertFalse(isProxyInternalField("orderId"))
        assertFalse(isProxyInternalField("orderDetailsList"))
        assertFalse(isProxyInternalField("this\$0"))   // inner-class outer ref
        // Legitimate field names that earlier drafts incorrectly filtered — code review caught
        // the silent-drop regression. These MUST pass through.
        assertFalse(isProxyInternalField("definition"))
        assertFalse(isProxyInternalField("profiles"))
        assertFalse(isProxyInternalField("context"))
    }

    @Test
    @DisplayName("§5 — formatVariables drops internal fields by default and surfaces a footer")
    fun `formatVariables filters internals and shows footer`() {
        val vars = listOf(
            VariableInfo("CGLIB\$BOUND", "boolean", "true"),
            VariableInfo("CGLIB\$THREAD_CALLBACKS", "ThreadLocal", "ThreadLocal@27266"),
            VariableInfo("orderId", "String", "\"O-42\""),
        )
        val rendered = formatVariables(vars)
        // Real field is present
        assertTrue(rendered.contains("orderId"), "Expected real field 'orderId' to be present")
        // Internals are hidden
        assertFalse(rendered.contains("CGLIB\$BOUND"), "Expected CGLIB field to be hidden")
        assertFalse(rendered.contains("CGLIB\$THREAD_CALLBACKS"), "Expected ThreadCallbacks to be hidden")
        // Footer accounts for the hidden count
        assertTrue(rendered.contains("(+2 internal field(s) hidden"),
            "Expected footer with hidden count, got: $rendered")
    }

    @Test
    @DisplayName("§5 — include_internals=true bypasses the filter")
    fun `include_internals=true shows everything`() {
        val vars = listOf(
            VariableInfo("CGLIB\$BOUND", "boolean", "true"),
            VariableInfo("orderId", "String", "\"O-42\""),
        )
        val rendered = formatVariables(vars, includeInternals = true)
        assertTrue(rendered.contains("CGLIB\$BOUND"))
        assertTrue(rendered.contains("orderId"))
        // No "hidden" footer when we showed everything
        assertFalse(rendered.contains("internal field(s) hidden"))
    }

    @Test
    @DisplayName("§5 — no footer emitted when there are no internals")
    fun `no footer when no internals filtered`() {
        val vars = listOf(VariableInfo("orderId", "String", "\"O-42\""))
        val rendered = formatVariables(vars)
        assertFalse(rendered.contains("internal field(s) hidden"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Placeholder-value detection — AgentDebugController.isPlaceholderValue
    // (the "Collecting data…" fix from feedback.md §1)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("§1 — IntelliJ ellipsis-char placeholder is detected")
    fun `unicode ellipsis Collecting data is placeholder`() {
        val ctrl = AgentDebugController(project)
        assertTrue(ctrl.isPlaceholderValue("Collecting data…"))
    }

    @Test
    @DisplayName("§1 — three-dots Collecting data is detected (platform version drift)")
    fun `three dots Collecting data is placeholder`() {
        val ctrl = AgentDebugController(project)
        assertTrue(ctrl.isPlaceholderValue("Collecting data..."))
    }

    @Test
    @DisplayName("§1 — empty / blank rendering counts as placeholder")
    fun `empty rendering is placeholder`() {
        val ctrl = AgentDebugController(project)
        assertTrue(ctrl.isPlaceholderValue(""))
        assertTrue(ctrl.isPlaceholderValue("   "))
    }

    @Test
    @DisplayName("§1 — real values are NOT misclassified as placeholders")
    fun `real values are not placeholders`() {
        val ctrl = AgentDebugController(project)
        assertFalse(ctrl.isPlaceholderValue("42"))
        assertFalse(ctrl.isPlaceholderValue("\"O-42\""))
        assertFalse(ctrl.isPlaceholderValue("ArrayList[5 items]"))
        assertFalse(ctrl.isPlaceholderValue("null"))
        // A real value that happens to contain the word "data" must NOT be confused
        // with the placeholder.
        assertFalse(ctrl.isPlaceholderValue("\"some data here\""))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // v0.85.31 follow-up coverage — feedback 2026-05-17
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("§v31-1 — GET_VARIABLES_WALL_BUDGET_MS leaves safety margin under 120s tool wrap")
    fun `wall budget under tool timeout`() {
        // Defensive invariant: the 90s cumulative budget MUST be strictly less than the
        // 120s per-tool default in AgentLoop, with enough margin to emit a sentinel and
        // format the response. Bumping the budget without adjusting AgentLoop's tool
        // timeout would re-introduce the v0.85.30 regression where get_variables blew
        // past 120s on slow JDI sessions.
        val budget = AgentDebugController.GET_VARIABLES_WALL_BUDGET_MS
        assertTrue(budget < 120_000L, "Budget $budget must be < 120s tool timeout")
        assertTrue(budget >= 60_000L, "Budget $budget should be ≥ 60s — too small starves users on slow JVMs")
        assertTrue(120_000L - budget >= 20_000L,
            "Need ≥ 20s of slack between budget and tool wrap so the sentinel and response can flush")
    }

    @Test
    @DisplayName("§v31-1 — PRESENTATION_TIMEOUT_MS fits within the cumulative budget")
    fun `presentation timeout fits in budget`() {
        // Each per-value resolution can take up to PRESENTATION_TIMEOUT_MS; a frame of N
        // variables can therefore take up to N × PRESENTATION_TIMEOUT_MS in the worst case.
        // The cumulative budget is what stops that from blowing past 120s.
        val presentation = AgentDebugController.PRESENTATION_TIMEOUT_MS
        val budget = AgentDebugController.GET_VARIABLES_WALL_BUDGET_MS
        assertTrue(presentation < budget,
            "Per-value timeout ($presentation ms) must be smaller than wall budget ($budget ms)")
    }

    @Test
    @DisplayName("§v31-2 — placeholder string in TYPE slot is detected (not just VALUE)")
    fun `placeholder in type slot detected`() {
        val ctrl = AgentDebugController(project)
        // Feedback 2026-05-17 #2 said "Collecting data&" appeared as the result TYPE, not
        // the value. The prior fix only checked value. The new fix gates on both slots —
        // we exercise isPlaceholderValue against type-shaped strings the platform might
        // surface.
        assertTrue(ctrl.isPlaceholderValue("Collecting data…"))
        assertTrue(ctrl.isPlaceholderValue("Collecting data"))
        // The startsWith() match in isPlaceholderValue covers the "& ellipsis decoded" case:
        assertTrue(ctrl.isPlaceholderValue("Collecting data&"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // §v32-4 — exception_breakpoint warning is PSI-conditional, not unconditional
    //
    // Feedback verbatim:
    //   "The tool returned a note 'No validation that ... exists in the classpath'
    //    but the breakpoint worked correctly when the exception was thrown. The
    //    warning was confusing since the class clearly exists and the breakpoint
    //    functioned as expected."
    //
    // Behavioural test isn't viable in a unit test — the executeExceptionBreakpoint
    // path goes through `WriteAction.compute` on EDT and creates a real
    // JavaExceptionBreakpoint. Pin the contract via source-text inspection of the
    // tool implementation: it must use JavaPsiFacade.findClass + GlobalSearchScope
    // and only append the warning when the lookup returns null.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("§v32-4 — exception_breakpoint uses JavaPsiFacade.findClass to gate the warning")
    fun `exception breakpoint warning is PSI-conditional`() {
        val source = locateDebugBreakpointsToolSource()

        assertTrue(
            source.contains("import com.intellij.psi.JavaPsiFacade"),
            "DebugBreakpointsTool must import JavaPsiFacade for the PSI verification fix"
        )
        assertTrue(
            source.contains("import com.intellij.psi.search.GlobalSearchScope"),
            "DebugBreakpointsTool must import GlobalSearchScope for the PSI verification fix"
        )

        // The warning must live inside an `if (psiClass == null) { ... }` guard.
        // The unconditional `sb.append("\n  Note: No validation` from v0.85.31 must be gone.
        assertFalse(
            source.contains("Note: No validation that"),
            "Old unconditional warning string must be removed"
        )

        // Positive: the conditional warning must reference the project classpath via PSI lookup.
        val conditionalWarnRegex = Regex(
            """JavaPsiFacade\.getInstance\(project\)\s*\.findClass\(exceptionClass,\s*GlobalSearchScope\.allScope\(project\)\)"""
        )
        assertTrue(
            conditionalWarnRegex.containsMatchIn(source),
            "executeExceptionBreakpoint must call JavaPsiFacade.getInstance(project).findClass(exceptionClass, GlobalSearchScope.allScope(project))"
        )
        assertTrue(
            source.contains("if (psiClass == null)"),
            "Warning must be gated on `if (psiClass == null)` so it only fires when the FQN is missing"
        )
    }

    private fun locateDebugBreakpointsToolSource(): String {
        val candidates = listOf(
            java.nio.file.Path.of("src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt"),
            java.nio.file.Path.of("agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt")
        )
        val path = candidates.firstOrNull { java.nio.file.Files.exists(it) }
            ?: error("DebugBreakpointsTool.kt source not found in: $candidates")
        return java.nio.file.Files.readString(path)
    }
}
