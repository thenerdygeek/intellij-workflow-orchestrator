package com.workflow.orchestrator.core.maven

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MavenModuleDetectorTest {

    private val project = mockk<Project>()

    @Test
    fun `buildMavenArgs returns goals only for empty modules`() {
        val detector = MavenModuleDetector(project)
        val args = detector.buildMavenArgs(emptyList(), "clean compile")
        assertEquals(listOf("clean", "compile"), args)
    }

    @Test
    fun `buildMavenArgs uses -pl for single module`() {
        val detector = MavenModuleDetector(project)
        val args = detector.buildMavenArgs(listOf("my-module"), "clean compile")
        assertEquals(listOf("-pl", "my-module", "-am", "clean", "compile"), args)
    }

    @Test
    fun `buildMavenArgs adds -pl and -am for multiple modules`() {
        val detector = MavenModuleDetector(project)
        val args = detector.buildMavenArgs(listOf("mod-a", "mod-b"), "test")
        assertEquals(listOf("-pl", "mod-a,mod-b", "-am", "test"), args)
    }

    @Test
    fun `extractArtifactId parses simple pom`() {
        val pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <groupId>com.example</groupId>
                <artifactId>my-service</artifactId>
                <version>1.0.0</version>
            </project>
        """.trimIndent()

        val pomFile = mockk<VirtualFile>()
        every { pomFile.contentsToByteArray() } returns pomContent.toByteArray()

        val detector = MavenModuleDetector(project)
        assertEquals("my-service", detector.extractArtifactId(pomFile))
    }

    @Test
    fun `extractArtifactId skips parent artifactId`() {
        val pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>child-module</artifactId>
            </project>
        """.trimIndent()

        val pomFile = mockk<VirtualFile>()
        every { pomFile.contentsToByteArray() } returns pomContent.toByteArray()

        val detector = MavenModuleDetector(project)
        assertEquals("child-module", detector.extractArtifactId(pomFile))
    }

    @Test
    fun `findNearestPom walks up directory tree`() {
        val pomFile = mockk<VirtualFile>()
        val parentDir = mockk<VirtualFile>()
        val childDir = mockk<VirtualFile>()
        val file = mockk<VirtualFile>()

        every { file.parent } returns childDir
        every { childDir.findChild("pom.xml") } returns null
        every { childDir.parent } returns parentDir
        every { parentDir.findChild("pom.xml") } returns pomFile

        val detector = MavenModuleDetector(project)
        assertEquals(pomFile, detector.findNearestPom(file))
    }

    @Test
    fun `findNearestPom returns null when no pom found`() {
        val file = mockk<VirtualFile>()
        val dir = mockk<VirtualFile>()
        every { file.parent } returns dir
        every { dir.findChild("pom.xml") } returns null
        every { dir.parent } returns null

        val detector = MavenModuleDetector(project)
        assertNull(detector.findNearestPom(file))
    }

    @Test
    fun `detectChangedModules deduplicates modules`() {
        val pomContent = """
            <project><artifactId>my-mod</artifactId></project>
        """.trimIndent()
        val pomFile = mockk<VirtualFile>()
        every { pomFile.contentsToByteArray() } returns pomContent.toByteArray()

        val dir = mockk<VirtualFile>()
        every { dir.findChild("pom.xml") } returns pomFile
        every { dir.parent } returns null

        val file1 = mockk<VirtualFile>()
        val file2 = mockk<VirtualFile>()
        every { file1.parent } returns dir
        every { file2.parent } returns dir

        val detector = MavenModuleDetector(project)
        val modules = detector.detectChangedModules(listOf(file1, file2))
        assertEquals(1, modules.size)
        assertEquals("my-mod", modules[0])
    }
}
