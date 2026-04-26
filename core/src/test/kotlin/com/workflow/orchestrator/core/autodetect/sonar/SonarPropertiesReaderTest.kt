package com.workflow.orchestrator.core.autodetect.sonar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SonarPropertiesReaderTest {

    // ──────────────────────────────────────────────────────────────────
    // Null / missing-input cases
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `returns null when directory does not exist`(@TempDir root: Path) {
        val missing = root.resolve("nonexistent")
        assertNull(SonarPropertiesReader.readProjectKey(missing))
    }

    @Test
    fun `returns null when no sonar-project-properties file exists`(@TempDir root: Path) {
        assertNull(SonarPropertiesReader.readProjectKey(root))
    }

    @Test
    fun `returns null when file exists but has no sonar-projectKey property`(@TempDir root: Path) {
        Files.writeString(root.resolve("sonar-project.properties"), "sonar.host.url=http://sonar.example.com\n")
        assertNull(SonarPropertiesReader.readProjectKey(root))
    }

    @Test
    fun `returns null when sonar-projectKey is blank`(@TempDir root: Path) {
        Files.writeString(root.resolve("sonar-project.properties"), "sonar.projectKey=\n")
        assertNull(SonarPropertiesReader.readProjectKey(root))
    }

    // ──────────────────────────────────────────────────────────────────
    // Happy-path cases
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `returns key when one sonar-project-properties file present at root`(@TempDir root: Path) {
        Files.writeString(root.resolve("sonar-project.properties"), "sonar.projectKey=my-project\n")

        val result = SonarPropertiesReader.readProjectKey(root)
        assertEquals("my-project", result?.primaryKey)
        assertTrue(result?.additionalCandidatePaths.isNullOrEmpty())
    }

    @Test
    fun `returns key found in subdirectory within maxDepth`(@TempDir root: Path) {
        val sub = root.resolve("subproject")
        Files.createDirectories(sub)
        Files.writeString(sub.resolve("sonar-project.properties"), "sonar.projectKey=sub-key\n")

        val result = SonarPropertiesReader.readProjectKey(root)
        assertEquals("sub-key", result?.primaryKey)
    }

    @Test
    fun `lists additional candidates when multiple files present`(@TempDir root: Path) {
        Files.writeString(root.resolve("sonar-project.properties"), "sonar.projectKey=root-key\n")

        val sub = root.resolve("subproject")
        Files.createDirectories(sub)
        Files.writeString(sub.resolve("sonar-project.properties"), "sonar.projectKey=sub-key\n")

        val result = SonarPropertiesReader.readProjectKey(root)
        assertEquals("root-key", result?.primaryKey)
        assertEquals(1, result?.additionalCandidatePaths?.size)
    }

    @Test
    fun `primaryKey is returned even when additional candidates exist`(@TempDir root: Path) {
        Files.writeString(root.resolve("sonar-project.properties"), "sonar.projectKey=primary-key\n")

        val subA = root.resolve("mod-a")
        Files.createDirectories(subA)
        Files.writeString(subA.resolve("sonar-project.properties"), "sonar.projectKey=mod-a-key\n")

        val subB = root.resolve("mod-b")
        Files.createDirectories(subB)
        Files.writeString(subB.resolve("sonar-project.properties"), "sonar.projectKey=mod-b-key\n")

        val result = SonarPropertiesReader.readProjectKey(root)
        assertEquals("primary-key", result?.primaryKey)
        assertEquals(2, result?.additionalCandidatePaths?.size)
    }

    @Test
    fun `returns null when all files lack sonar-projectKey`(@TempDir root: Path) {
        Files.writeString(root.resolve("sonar-project.properties"), "sonar.host.url=http://sonar.example.com\n")

        val sub = root.resolve("subproject")
        Files.createDirectories(sub)
        Files.writeString(sub.resolve("sonar-project.properties"), "sonar.host.url=http://sonar.example.com\n")

        assertNull(SonarPropertiesReader.readProjectKey(root))
    }

    @Test
    fun `does not find files beyond maxDepth`(@TempDir root: Path) {
        // Default maxDepth=2, so depth-3 file should not be found
        val deep = root.resolve("a").resolve("b").resolve("c")
        Files.createDirectories(deep)
        Files.writeString(deep.resolve("sonar-project.properties"), "sonar.projectKey=too-deep\n")

        assertNull(SonarPropertiesReader.readProjectKey(root, maxDepth = 2))
    }

    @Test
    fun `Properties load trims whitespace from key values`(@TempDir root: Path) {
        Files.writeString(root.resolve("sonar-project.properties"), "sonar.projectKey = trimmed-key \n")

        val result = SonarPropertiesReader.readProjectKey(root)
        assertEquals("trimmed-key", result?.primaryKey)
    }
}
