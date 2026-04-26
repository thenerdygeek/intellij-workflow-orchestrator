package com.workflow.orchestrator.bamboo.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BambooSpecsLocalParserTest {

    @TempDir
    lateinit var tempDir: Path

    // --- parsePlanKey ---

    @Test
    fun `returns null when no bamboo-specs directory exists`() {
        // tempDir has no bamboo-specs child
        assertNull(BambooSpecsLocalParser.parsePlanKey(tempDir))
    }

    @Test
    fun `returns null when bamboo-specs directory exists but is empty`() {
        Files.createDirectory(tempDir.resolve("bamboo-specs"))
        assertNull(BambooSpecsLocalParser.parsePlanKey(tempDir))
    }

    @Test
    fun `returns key from a file named bamboo_yml`() {
        val specsDir = Files.createDirectory(tempDir.resolve("bamboo-specs"))
        specsDir.resolve("bamboo.yml").toFile().writeText(
            """
            plan:
              project-key: MYPROJ
              key: BUILD
            """.trimIndent()
        )
        assertEquals("MYPROJ-BUILD", BambooSpecsLocalParser.parsePlanKey(tempDir))
    }

    @Test
    fun `returns key from a file named pipeline_yaml`() {
        val specsDir = Files.createDirectory(tempDir.resolve("bamboo-specs"))
        specsDir.resolve("pipeline.yaml").toFile().writeText(
            """
            plan:
              project-key: ACME
              key: CI
            """.trimIndent()
        )
        assertEquals("ACME-CI", BambooSpecsLocalParser.parsePlanKey(tempDir))
    }

    @Test
    fun `returns key from a file with yml extension`() {
        val specsDir = Files.createDirectory(tempDir.resolve("bamboo-specs"))
        specsDir.resolve("custom.yml").toFile().writeText(
            """
            plan:
              project-key: ORG
              key: DEPLOY
            """.trimIndent()
        )
        assertEquals("ORG-DEPLOY", BambooSpecsLocalParser.parsePlanKey(tempDir))
    }

    @Test
    fun `skips files with malformed YAML and returns null cleanly`() {
        val specsDir = Files.createDirectory(tempDir.resolve("bamboo-specs"))
        specsDir.resolve("bamboo.yml").toFile().writeText(
            """
            : this is not valid yaml: [unclosed
            """.trimIndent()
        )
        assertNull(BambooSpecsLocalParser.parsePlanKey(tempDir))
    }

    @Test
    fun `skips files where plan block is missing`() {
        val specsDir = Files.createDirectory(tempDir.resolve("bamboo-specs"))
        specsDir.resolve("bamboo.yml").toFile().writeText(
            """
            deployment:
              name: my-deployment
            """.trimIndent()
        )
        assertNull(BambooSpecsLocalParser.parsePlanKey(tempDir))
    }

    @Test
    fun `returns null when project-key is blank`() {
        val specsDir = Files.createDirectory(tempDir.resolve("bamboo-specs"))
        specsDir.resolve("bamboo.yml").toFile().writeText(
            """
            plan:
              project-key: ""
              key: BUILD
            """.trimIndent()
        )
        assertNull(BambooSpecsLocalParser.parsePlanKey(tempDir))
    }

    @Test
    fun `returns null when key is blank`() {
        val specsDir = Files.createDirectory(tempDir.resolve("bamboo-specs"))
        specsDir.resolve("bamboo.yml").toFile().writeText(
            """
            plan:
              project-key: PROJ
              key: ""
            """.trimIndent()
        )
        assertNull(BambooSpecsLocalParser.parsePlanKey(tempDir))
    }

    @Test
    fun `returns first valid key when multiple files present`() {
        val specsDir = Files.createDirectory(tempDir.resolve("bamboo-specs"))
        // Write two valid files; the parser uses findFirst() on a stream walk, order is FS-dependent
        // so we write only one valid file and one invalid to guarantee a deterministic result
        specsDir.resolve("bamboo.yml").toFile().writeText(
            """
            plan:
              project-key: FIRST
              key: PLAN
            """.trimIndent()
        )
        specsDir.resolve("other.yml").toFile().writeText(
            """
            deployment:
              name: irrelevant
            """.trimIndent()
        )
        // Should return the key from the valid file, not null
        assertNotNull(BambooSpecsLocalParser.parsePlanKey(tempDir))
    }

    // --- extractKey (internal) ---

    @Test
    fun `extractKey returns null for non-map root`() {
        val file = tempDir.resolve("bad.yml")
        file.toFile().writeText("- just a list")
        assertNull(BambooSpecsLocalParser.extractKey(file))
    }
}
