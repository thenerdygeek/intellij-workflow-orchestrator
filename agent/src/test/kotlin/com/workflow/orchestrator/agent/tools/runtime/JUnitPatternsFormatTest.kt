package com.workflow.orchestrator.agent.tools.runtime

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the multi-method JUnit pattern STORAGE format against the IntelliJ
 * Platform contract in JUnitConfiguration.bePatternConfiguration:
 *
 *   patterns.add(qualifiedClassName + "," + methodName)
 *
 * Each LinkedHashSet entry is exactly one Class,method pair. The `||`
 * separator from getPatternPresentation() is for display only — it is NOT
 * the persistence format, and using it as the storage format produces an
 * unparseable selector that JUnit fails to match against any test.
 *
 * Backing field `myPattern` is private; the public `setPatterns(LinkedHashSet)`
 * is the stable API across platform versions (and across JBR module-isolation
 * tightening). Reading the private field via setAccessible would also work
 * but is more fragile.
 *
 * This test is source-text (not a runtime test) because instantiating a real
 * JUnitConfiguration.Data requires booting the IntelliJ test fixture.
 *
 * Regression: introduced 2026-04-24 in commit 42f70c30
 * (feat(agent): multi-method run_tests + coverage). The original code used
 * `getField("PATTERNS")` (NoSuchFieldException — field does not exist) and
 * `joinToString("|")` (wrong separator). Coverage surfaced as
 * "Could not create run configuration"; java_runtime_exec was routed through
 * the shell fallback by the recovery sentinel and silently failed without
 * IDE-visible run-window output.
 */
class JUnitPatternsFormatTest {

    private val javaRuntimeSource by lazy { readSource("JavaRuntimeExecTool.kt") }
    private val coverageSource by lazy { readSource("CoverageTool.kt") }

    @Test
    fun `JavaRuntimeExecTool uses setPatterns method, not getField('PATTERNS')`() {
        val multiMethodBlock = extractMultiMethodPatternBlock(javaRuntimeSource)
        assertTrue(
            "setPatterns" in multiMethodBlock,
            "Multi-method JUnit setup must call setPatterns(LinkedHashSet) — the public " +
                "API on JUnitConfiguration.Data. Found block:\n$multiMethodBlock"
        )
        assertFalse(
            """getField("PATTERNS")""" in multiMethodBlock,
            "getField(\"PATTERNS\") fails with NoSuchFieldException — the backing field is " +
                "named myPattern (private). Use setPatterns(LinkedHashSet) instead. " +
                "Found block:\n$multiMethodBlock"
        )
    }

    @Test
    fun `rerun_failed_tests preserves per-method granularity in pattern entries`() {
        // The multi-class rerun branch must iterate failedClassMethods (which carries
        // method info from result-tree parsing) and emit "Class,method" entries — not
        // bare class names. Otherwise rerun re-tests the entire containing class
        // instead of just the failures.
        val rerunBlock = extractRerunPatternBlock(javaRuntimeSource)
        assertTrue(
            "failedClassMethods" in rerunBlock,
            "rerun_failed_tests pattern emission must consume failedClassMethods (the " +
                "(class, method?) pair list). Found block:\n$rerunBlock"
        )
        assertFalse(
            "addAll(failedClasses)" in rerunBlock,
            "addAll(failedClasses) drops method info — every entry becomes a whole-class " +
                "selector and the rerun re-tests every method in the class. Use a per-pair " +
                "iteration that emits `\"\$cls\"` or `\"\$cls,\$method\"` instead. " +
                "Found block:\n$rerunBlock"
        )
    }

    @Test
    fun `getField('PATTERNS') appears nowhere in production runtime sources`() {
        // Whole-file guard: any code populating JUnitConfiguration.Data.myPattern via
        // reflection must use setPatterns(LinkedHashSet). The literal getField("PATTERNS")
        // is always wrong because the public field PATTERNS does not exist on the Data
        // class — the backing field is private and named myPattern. Catches:
        //   - run_tests multi-method path (createJUnitRunSettings)
        //   - rerun_failed_tests multi-class pattern mode
        //   - run_with_coverage multi-method path (CoverageTool.createJUnitRunSettings)
        //   - any future site that reaches for PATTERNS by mistake
        val sources = mapOf(
            "JavaRuntimeExecTool.kt" to javaRuntimeSource,
            "CoverageTool.kt" to coverageSource,
        )
        for ((name, src) in sources) {
            assertFalse(
                """getField("PATTERNS")""" in src,
                "$name still contains getField(\"PATTERNS\") — replace with " +
                    "getMethod(\"setPatterns\", LinkedHashSet::class.java).invoke(data, patterns)."
            )
        }
    }

    @Test
    fun `CoverageTool uses setPatterns method, not getField('PATTERNS')`() {
        val multiMethodBlock = extractMultiMethodPatternBlock(coverageSource)
        assertTrue(
            "setPatterns" in multiMethodBlock,
            "Multi-method coverage setup must call setPatterns(LinkedHashSet). Found block:\n$multiMethodBlock"
        )
        assertFalse(
            """getField("PATTERNS")""" in multiMethodBlock,
            "getField(\"PATTERNS\") fails with NoSuchFieldException. Use setPatterns instead. " +
                "Found block:\n$multiMethodBlock"
        )
    }

    @Test
    fun `JavaRuntimeExecTool emits one pattern entry per method, not pipe-joined`() {
        val multiMethodBlock = extractMultiMethodPatternBlock(javaRuntimeSource)
        assertFalse(
            """joinToString("|")""" in multiMethodBlock,
            "Pipe-joined entry 'Class,m1|m2|m3' is the display format, not the storage format. " +
                "Storage requires one entry per method: 'Class,m1', 'Class,m2', ... — emitted " +
                "by methods.forEach { add(\"$\\\$className,\$\\\$it\") } or equivalent. " +
                "Found block:\n$multiMethodBlock"
        )
        assertTrue(
            "methods.forEach" in multiMethodBlock || "for (" in multiMethodBlock,
            "Expected per-method iteration when emitting pattern entries. Found block:\n$multiMethodBlock"
        )
    }

    @Test
    fun `CoverageTool emits one pattern entry per method, not pipe-joined`() {
        val multiMethodBlock = extractMultiMethodPatternBlock(coverageSource)
        assertFalse(
            """joinToString("|")""" in multiMethodBlock,
            "Pipe-joined entry is the display format. Storage requires one entry per method. " +
                "Found block:\n$multiMethodBlock"
        )
        assertTrue(
            "methods.forEach" in multiMethodBlock || "for (" in multiMethodBlock,
            "Expected per-method iteration. Found block:\n$multiMethodBlock"
        )
    }

    /**
     * Carve out the rerun_failed_tests multi-class pattern emission block.
     * Anchored on a comment unique to that branch so it doesn't drift onto
     * the run_tests multi-method block above.
     */
    private fun extractRerunPatternBlock(source: String): String {
        val anchor = "Pattern mode: one entry per failed"
        val start = source.indexOf(anchor)
        check(start >= 0) {
            "Anchor '$anchor' not found in JavaRuntimeExecTool.kt — the rerun_failed_tests " +
                "multi-class branch was renamed or removed."
        }
        val end = (start + 1500).coerceAtMost(source.length)
        return source.substring(start, end)
    }

    /**
     * Carve out the `methods.size >= 2 -> { ... }` branch that contains the
     * multi-method pattern setup. We avoid asserting against the whole file
     * so the message-format checks aren't fooled by similar text elsewhere
     * (e.g., docstrings, single-method paths, error messages).
     */
    private fun extractMultiMethodPatternBlock(source: String): String {
        val anchor = "methods.size >= 2 ->"
        val start = source.indexOf(anchor)
        check(start >= 0) { "Anchor '$anchor' not found — the multi-method branch was renamed or removed." }
        // Take a generous window — enough to span the brace block and any
        // catch arms — without trying to balance braces precisely.
        val end = (start + 2000).coerceAtMost(source.length)
        return source.substring(start, end)
    }

    private fun readSource(name: String): String {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        val root = File(userDir)
        val relSubdir = "src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/$name"
        val moduleRootedPath = File(root, relSubdir)               // user.dir == <repo>/agent
        val repoRootedPath = File(root, "agent/$relSubdir")        // user.dir == <repo>
        val path = when {
            moduleRootedPath.isFile -> moduleRootedPath
            repoRootedPath.isFile -> repoRootedPath
            else -> error(
                "Source file '$name' not found at:\n" +
                    "  1. ${moduleRootedPath.absolutePath}\n" +
                    "  2. ${repoRootedPath.absolutePath}"
            )
        }
        return path.readText()
    }
}
