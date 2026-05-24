package com.workflow.orchestrator.bamboo.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression tests for audit finding bamboo:F-1 — SafeConstructor must be used for all
 * bamboo-specs YAML parsing so that deserialization gadget chains (e.g. via
 * !!javax.script.ScriptEngineManager) cannot execute arbitrary code in the IDE JVM.
 *
 * Both parsers catch the ConstructorException internally and return null/empty — the
 * critical invariant is that they do NOT return a successfully instantiated gadget object.
 */
class BambooSpecsLocalParserSafeYamlTest {

    // ---- BambooSpecsLocalParser ----

    @Test
    fun `extractKey returns null for YAML with unsafe class gadget tag - gadget not executed`(@TempDir tmp: Path) {
        // With SafeConstructor the unsafe tag raises ConstructorException which the catch block
        // swallows and returns null — the gadget chain is never instantiated.
        val maliciousYaml = """
            plan:
              project-key: !!javax.script.ScriptEngineManager [[]]
              key: EVIL
        """.trimIndent()
        val file = tmp.resolve("bamboo.yml")
        Files.writeString(file, maliciousYaml)

        // null == ConstructorException caught; no gadget executed
        val result = BambooSpecsLocalParser.extractKey(file)
        assertNull(result, "SafeConstructor must block gadget chains; extractKey must return null, not a successful result")
    }

    @Test
    fun `extractKey parses a well-formed bamboo-specs plan YAML`(@TempDir tmp: Path) {
        val validYaml = """
            plan:
              project-key: MYPROJ
              key: BUILD
              name: My Build Plan
        """.trimIndent()
        val file = tmp.resolve("bamboo.yml")
        Files.writeString(file, validYaml)

        val result = BambooSpecsLocalParser.extractKey(file)
        assertEquals("MYPROJ-BUILD", result)
    }

    // ---- PlanDetectionService.extractRepoUrls ----

    @Test
    fun `extractRepoUrls returns empty list for YAML with unsafe class gadget tag - gadget not executed`() {
        // With SafeConstructor the tag raises ConstructorException; fallback regex finds no url: line
        // for a bare object tag, so an empty list is returned rather than a gadget instance.
        val maliciousYaml = """
            repositories:
              - !!javax.script.ScriptEngineManager [[]]
        """.trimIndent()

        val urls = PlanDetectionService.extractRepoUrls(maliciousYaml)
        // Result must not contain any live object reference — an empty list is the expected safe outcome
        assertTrue(urls.isEmpty(), "SafeConstructor must block gadget chains; extractRepoUrls must return empty list")
    }

    @Test
    fun `extractRepoUrls returns repository URLs from valid specs YAML`() {
        val validYaml = """
            repositories:
              - name: my-repo
                url: https://bitbucket.example.com/scm/proj/repo.git
        """.trimIndent()

        val urls = PlanDetectionService.extractRepoUrls(validYaml)
        assertEquals(listOf("https://bitbucket.example.com/scm/proj/repo.git"), urls)
    }
}
