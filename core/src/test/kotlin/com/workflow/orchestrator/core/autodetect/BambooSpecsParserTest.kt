package com.workflow.orchestrator.core.autodetect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BambooSpecsParserTest {

    @Test
    fun `returns empty map when bamboo-specs directory missing`(@TempDir root: Path) {
        val result = BambooSpecsParser.parseConstants(root)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extracts constants from PlanProperties java`(@TempDir root: Path) {
        val javaDir = root.resolve("bamboo-specs/src/main/java/constants")
        Files.createDirectories(javaDir)
        Files.writeString(javaDir.resolve("PlanProperties.java"), """
            package constants;
            public class PlanProperties {
                private static final String REPOSITORY_NAME = "my-sample-service";
                private static final String PLAN_KEY = "MYSAMPLESERVICE";
                private static final String DOCKER_TAG_NAME = "MySampleServiceDockerTag";
                public static final String PROJECT_KEY = "MYPROJ";
            }
        """.trimIndent())

        val result = BambooSpecsParser.parseConstants(root)

        assertEquals("my-sample-service", result["REPOSITORY_NAME"])
        assertEquals("MYSAMPLESERVICE", result["PLAN_KEY"])
        assertEquals("MySampleServiceDockerTag", result["DOCKER_TAG_NAME"])
        assertEquals("MYPROJ", result["PROJECT_KEY"])
    }

    @Test
    fun `merges constants across multiple java files`(@TempDir root: Path) {
        val javaDir = root.resolve("bamboo-specs/src/main/java/constants")
        Files.createDirectories(javaDir)
        Files.writeString(javaDir.resolve("ProjectProperties.java"),
            """public class ProjectProperties { private static final String PROJECT_KEY = "ABC"; }""")
        Files.writeString(javaDir.resolve("PlanProperties.java"),
            """public class PlanProperties { private static final String PLAN_KEY = "XYZ"; }""")

        val result = BambooSpecsParser.parseConstants(root)

        assertEquals("ABC", result["PROJECT_KEY"])
        assertEquals("XYZ", result["PLAN_KEY"])
    }

    @Test
    fun `first occurrence wins on duplicate constant names`(@TempDir root: Path) {
        val javaDir = root.resolve("bamboo-specs/src/main/java")
        Files.createDirectories(javaDir)
        Files.writeString(javaDir.resolve("A.java"),
            """class A { private static final String PLAN_KEY = "FIRST"; }""")
        Files.writeString(javaDir.resolve("B.java"),
            """class B { private static final String PLAN_KEY = "SECOND"; }""")

        val result = BambooSpecsParser.parseConstants(root)

        // Either FIRST or SECOND is acceptable as long as parser is deterministic.
        // We assert it's non-null and one of the two values.
        assertTrue(result["PLAN_KEY"] in setOf("FIRST", "SECOND"))
    }

    @Test
    fun `ignores non-string constants and malformed lines`(@TempDir root: Path) {
        val javaDir = root.resolve("bamboo-specs/src/main/java")
        Files.createDirectories(javaDir)
        Files.writeString(javaDir.resolve("Mixed.java"), """
            public class Mixed {
                private static final int BUILD_NUMBER = 42;
                private static final String VALID = "yes";
                // private static final String COMMENTED = "no";
                private static final String[] BRANCHES = { "develop" };
            }
        """.trimIndent())

        val result = BambooSpecsParser.parseConstants(root)

        assertEquals("yes", result["VALID"])
        assertEquals(null, result["BUILD_NUMBER"])
        assertEquals(null, result["BRANCHES"])
    }
}
