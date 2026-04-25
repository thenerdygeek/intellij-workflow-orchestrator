package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTable
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [executeListLibraries].
 *
 *  1. testListLibrariesReturnsContent — mock the services, assert no error and content lists libraries
 */
class ListLibrariesActionTest {

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)

        // Stub the suspending readAction { } so it runs the lambda in-place — there is no
        // ApplicationManager in unit tests, so the real builder would NPE on its
        // ReadWriteActionSupport service lookup.
        mockkStatic("com.intellij.openapi.application.CoroutinesKt")
        coEvery { readAction<Any?>(any()) } coAnswers { firstArg<() -> Any?>().invoke() }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 1 — Happy path: project-scoped libraries listed
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testListLibrariesReturnsContent() = runTest {
        // -- Mock libraries --
        val mockLib1 = mockk<Library>(relaxed = true)
        every { mockLib1.name } returns "spring-core-6.0.0"
        every { mockLib1.getUrls(any()) } returns arrayOf("file:///path/to/spring-core.jar")

        val mockLib2 = mockk<Library>(relaxed = true)
        every { mockLib2.name } returns "jackson-databind-2.15.0"
        every { mockLib2.getUrls(any()) } returns emptyArray()

        // -- Mock project library table --
        val mockProjectTable = mockk<LibraryTable>(relaxed = true)
        every { mockProjectTable.libraries } returns arrayOf(mockLib1, mockLib2)

        // -- Mock app library table --
        val mockAppTable = mockk<LibraryTable>(relaxed = true)
        every { mockAppTable.libraries } returns emptyArray()

        // -- LibraryTablesRegistrar --
        val mockRegistrar = mockk<LibraryTablesRegistrar>(relaxed = true)
        every { mockRegistrar.getLibraryTable(project) } returns mockProjectTable
        every { mockRegistrar.libraryTable } returns mockAppTable
        mockkStatic(LibraryTablesRegistrar::class)
        every { LibraryTablesRegistrar.getInstance() } returns mockRegistrar

        val params = buildJsonObject { put("scope", "project") }
        val result = executeListLibraries(params, project)

        assertFalse(result.isError, "Expected no error but got: ${result.content}")
        assertTrue(
            result.content.contains("spring-core-6.0.0"),
            "Expected library name in content: ${result.content}"
        )
        assertTrue(
            result.content.contains("jackson-databind-2.15.0"),
            "Expected second library name in content: ${result.content}"
        )
    }
}
