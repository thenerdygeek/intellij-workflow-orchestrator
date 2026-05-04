package com.workflow.orchestrator.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BuildToolExecutableResolverTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun `executable names match the current OS`() {
        if (isWindows) {
            assertEquals("mvn.cmd", BuildToolExecutableResolver.mavenExecutableName())
            assertEquals("mvnw.cmd", BuildToolExecutableResolver.mavenWrapperName())
            assertEquals("gradle.bat", BuildToolExecutableResolver.gradleExecutableName())
            assertEquals("gradlew.bat", BuildToolExecutableResolver.gradleWrapperName())
        } else {
            assertEquals("mvn", BuildToolExecutableResolver.mavenExecutableName())
            assertEquals("mvnw", BuildToolExecutableResolver.mavenWrapperName())
            assertEquals("gradle", BuildToolExecutableResolver.gradleExecutableName())
            assertEquals("gradlew", BuildToolExecutableResolver.gradleWrapperName())
        }
    }

    @Test
    fun `resolveMaven falls back to PATH executable when no wrapper exists`(@TempDir baseDir: File) {
        val resolved = BuildToolExecutableResolver.resolveMaven(baseDir)
        assertEquals(BuildToolExecutableResolver.mavenExecutableName(), resolved)
    }

    @Test
    fun `resolveGradle falls back to PATH executable when no wrapper exists`(@TempDir baseDir: File) {
        val resolved = BuildToolExecutableResolver.resolveGradle(baseDir)
        assertEquals(BuildToolExecutableResolver.gradleExecutableName(), resolved)
    }

    @Test
    fun `resolveMaven prefers wrapper over PATH executable when wrapper exists and is executable`(@TempDir baseDir: File) {
        val wrapper = File(baseDir, BuildToolExecutableResolver.mavenWrapperName())
        wrapper.writeText("#!/bin/sh\nexec true\n")
        assertTrue(wrapper.setExecutable(true), "test setup: must be able to mark wrapper executable")

        val resolved = BuildToolExecutableResolver.resolveMaven(baseDir)
        assertEquals(wrapper.absolutePath, resolved)
    }

    @Test
    fun `resolveGradle prefers wrapper over PATH executable when wrapper exists and is executable`(@TempDir baseDir: File) {
        val wrapper = File(baseDir, BuildToolExecutableResolver.gradleWrapperName())
        wrapper.writeText("#!/bin/sh\nexec true\n")
        assertTrue(wrapper.setExecutable(true), "test setup: must be able to mark wrapper executable")

        val resolved = BuildToolExecutableResolver.resolveGradle(baseDir)
        assertEquals(wrapper.absolutePath, resolved)
    }

    @Test
    fun `resolveMaven returns absolute path for wrapper even when baseDir is relative`(@TempDir baseDir: File) {
        val wrapper = File(baseDir, BuildToolExecutableResolver.mavenWrapperName())
        wrapper.writeText("#!/bin/sh\nexec true\n")
        assertTrue(wrapper.setExecutable(true))

        val resolved = BuildToolExecutableResolver.resolveMaven(baseDir)
        assertTrue(File(resolved).isAbsolute, "wrapper resolution must return an absolute path")
    }
}
