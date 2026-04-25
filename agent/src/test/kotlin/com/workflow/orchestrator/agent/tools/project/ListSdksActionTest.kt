package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [executeListSdks].
 *
 *  1. testListSdksReturnsContent — mock readAction + ProjectJdkTable + ProjectRootManager →
 *                                  no error, content contains "SDK"
 */
class ListSdksActionTest {

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
    // Test 1 — Happy path: SDKs listed, project SDK name present
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testListSdksReturnsContent() = runTest {
        // -- Mock SDK type --
        val mockSdkType = mockk<SdkType>(relaxed = true)
        every { mockSdkType.name } returns "Java"

        // -- Mock individual SDKs --
        val mockSdk1 = mockk<Sdk>(relaxed = true)
        every { mockSdk1.name } returns "JDK-17"
        every { mockSdk1.sdkType } returns mockSdkType
        every { mockSdk1.versionString } returns "17.0.2"

        val mockSdk2 = mockk<Sdk>(relaxed = true)
        every { mockSdk2.name } returns "JDK-21"
        every { mockSdk2.sdkType } returns mockSdkType
        every { mockSdk2.versionString } returns "21.0.1"

        // -- ProjectJdkTable: returns both SDKs --
        val mockJdkTable = mockk<ProjectJdkTable>(relaxed = true)
        every { mockJdkTable.allJdks } returns arrayOf(mockSdk1, mockSdk2)
        mockkStatic(ProjectJdkTable::class)
        every { ProjectJdkTable.getInstance() } returns mockJdkTable

        // -- ProjectRootManager: project SDK is JDK-17 --
        val mockRootManager = mockk<ProjectRootManager>(relaxed = true)
        every { mockRootManager.projectSdk } returns mockSdk1
        mockkStatic(ProjectRootManager::class)
        every { ProjectRootManager.getInstance(project) } returns mockRootManager

        val params = buildJsonObject {}
        val result = executeListSdks(params, project)

        assertFalse(result.isError, "Expected no error but got: ${result.content}")
        assertTrue(
            result.content.uppercase().contains("SDK"),
            "Expected 'SDK' in content: ${result.content}"
        )
        assertTrue(
            result.content.contains("JDK-17"),
            "Expected 'JDK-17' in content: ${result.content}"
        )
        assertTrue(
            result.content.contains("JDK-21"),
            "Expected 'JDK-21' in content: ${result.content}"
        )
    }
}
