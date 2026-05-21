package com.workflow.orchestrator.agent.tools.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CoverageToolPackageFilterTest {

    @Test
    fun `resolvePackageFilter prefers explicit param over auto-derive`(@TempDir tempDir: Path) {
        val pom = tempDir.resolve("pom.xml")
        Files.writeString(
            pom,
            """
            <?xml version="1.0"?>
            <project>
                <groupId>com.derived</groupId>
                <artifactId>foo</artifactId>
            </project>
            """.trimIndent()
        )

        val resolved = CoverageTool.resolvePackageFilter(
            explicitParam = "com.explicit,com.other",
            projectRoot = tempDir
        )

        assertEquals(listOf("com.explicit", "com.other"), resolved)
    }

    @Test
    fun `resolvePackageFilter auto-derives Maven groupId when no explicit param`(@TempDir tempDir: Path) {
        val pom = tempDir.resolve("pom.xml")
        Files.writeString(
            pom,
            """
            <?xml version="1.0"?>
            <project>
                <groupId>com.example.app</groupId>
                <artifactId>foo</artifactId>
            </project>
            """.trimIndent()
        )

        val resolved = CoverageTool.resolvePackageFilter(explicitParam = null, projectRoot = tempDir)

        assertEquals(listOf("com.example.app"), resolved)
    }

    @Test
    fun `resolvePackageFilter auto-derives Gradle group when no pom`(@TempDir tempDir: Path) {
        val gradle = tempDir.resolve("build.gradle")
        Files.writeString(
            gradle,
            """
            plugins { id 'java' }
            group = "com.gradle.app"
            """.trimIndent()
        )

        val resolved = CoverageTool.resolvePackageFilter(explicitParam = null, projectRoot = tempDir)

        assertEquals(listOf("com.gradle.app"), resolved)
    }

    @Test
    fun `resolvePackageFilter falls back to source tree when no build file`(@TempDir tempDir: Path) {
        Files.createDirectories(tempDir.resolve("src/main/java/com/source/derived"))

        val resolved = CoverageTool.resolvePackageFilter(explicitParam = null, projectRoot = tempDir)

        assertTrue(
            resolved.isNotEmpty() && resolved.first().startsWith("com.source"),
            "Expected source-tree fallback to derive 'com.source*'; got $resolved"
        )
    }

    @Test
    fun `resolvePackageFilter returns empty list when nothing derivable`(@TempDir tempDir: Path) {
        val resolved = CoverageTool.resolvePackageFilter(explicitParam = null, projectRoot = tempDir)
        assertTrue(
            resolved.isEmpty(),
            "Expected empty list (unfiltered) when no pom, no gradle, no src tree; got $resolved"
        )
    }

    @Test
    fun `filterClasses excludes ByteBuddy and Mockito and CGLIB proxies`() {
        val all = mapOf(
            "com.example.app.UserService" to Any(),
            "com.example.app.UserController" to Any(),
            "net.bytebuddy.MyProxy" to Any(),
            "org.mockito.codegen.Foo" to Any(),
            "com.sun.proxy.\$Proxy42" to Any(),
            "kotlin.jvm.functions.Function1" to Any(),
        )

        val filtered = CoverageTool.filterClasses(all, filter = listOf("com.example.app"))

        assertEquals(2, filtered.size)
        assertTrue(filtered.containsKey("com.example.app.UserService"))
        assertTrue(filtered.containsKey("com.example.app.UserController"))
        assertFalse(filtered.containsKey("net.bytebuddy.MyProxy"))
    }

    @Test
    fun `filterClasses with empty filter returns everything`() {
        val all = mapOf(
            "com.example.app.UserService" to Any(),
            "net.bytebuddy.MyProxy" to Any(),
        )

        val filtered = CoverageTool.filterClasses(all, filter = emptyList())

        assertEquals(2, filtered.size)
    }
}
