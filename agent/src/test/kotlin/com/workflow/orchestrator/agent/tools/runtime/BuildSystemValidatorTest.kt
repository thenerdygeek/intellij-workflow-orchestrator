package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierList
import com.intellij.psi.search.GlobalSearchScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Scenario tests for [BuildSystemValidator].
 *
 * These tests encode the seven failure modes described in Task 2.10 of the phase-2 plan
 * (`docs/plans/2026-04-17-phase2-multi-module-build-validation.md`). They are written
 * from the plan's requirements — not from the current implementation — and deliberately
 * FAIL against the skeleton validator. A later pass implements Tasks 2.2–2.6 and turns
 * these tests green.
 *
 * Pattern: MockK statics for IntelliJ platform APIs (`JavaPsiFacade`, `ModuleUtilCore`,
 * `ModuleRootManager`, `ReadAction`, `VfsUtilCore`), plus pluggable resolver fakes for
 * Gradle/Maven probes — the validator accepts resolver interfaces so these tests never
 * touch `Class.forName` or the optional Gradle/Maven IntelliJ plugins.
 */
class BuildSystemValidatorTest {

    private lateinit var project: Project
    private lateinit var module: Module

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        module = mockk(relaxed = true)
        every { project.basePath } returns "/project/root"
        every { module.name } returns "services.auth"

        // ReadAction.compute: execute the lambda inline so the PSI/roots code under
        // test runs synchronously in the test thread.
        mockkStatic(ReadAction::class)
        val computeSlot = slot<ThrowableComputable<Any?, RuntimeException>>()
        every { ReadAction.compute(capture(computeSlot)) } answers {
            computeSlot.captured.compute()
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ------------------------------------------------------------------------
    // Fake resolvers — tests inject these to simulate Gradle/Maven integration
    // states without touching reflection or the optional plugins.
    // ------------------------------------------------------------------------

    private class StubGradleResolver(val result: GradleResolution) : GradleSubprojectResolver {
        override fun resolveSubprojectPath(project: Project, module: Module): GradleResolution = result
    }

    private class StubMavenResolver(val result: MavenResolution) : MavenModuleResolver {
        override fun resolveModuleDirectory(project: Project, module: Module): MavenResolution = result
    }

    // ------------------------------------------------------------------------
    // Helpers for wiring a mocked PSI class + source roots.
    // ------------------------------------------------------------------------

    /**
     * Wires up [JavaPsiFacade], [ModuleUtilCore], and [ModuleRootManager] so that the
     * validator resolves [className] to a [PsiClass] whose containing file lives under
     * the returned [VirtualFile] "container" (either a test source root or a main source
     * root, depending on [sourceRootKind]).
     *
     * @param testMethodAnnotations FQNs to report on each method's annotations
     *   (empty list = no @Test methods).
     * @param sourceRootKind whether the class lives under a test source root or a main
     *   (non-test) source root. The validator is expected to use
     *   `ModuleRootManager.sourceRoots(JavaSourceRootType.TEST_SOURCE)` to distinguish.
     */
    private fun wirePsiClass(
        className: String,
        testMethodAnnotations: List<List<String>>,
        sourceRootKind: SourceRootKind
    ): WiredPsi {
        val psiClass = mockk<PsiClass>(relaxed = true)
        val psiFile = mockk<PsiFile>(relaxed = true)
        val classFile = mockk<VirtualFile>(relaxed = true)
        val testRoot = mockk<VirtualFile>(relaxed = true)
        val mainRoot = mockk<VirtualFile>(relaxed = true)

        every { psiClass.qualifiedName } returns className
        every { psiClass.containingFile } returns psiFile
        every { psiFile.virtualFile } returns classFile

        // Methods + their annotations
        val methods = testMethodAnnotations.map { fqns ->
            val method = mockk<PsiMethod>(relaxed = true)
            val modList = mockk<PsiModifierList>(relaxed = true)
            val anns = fqns.map { fqn ->
                mockk<PsiAnnotation>(relaxed = true).also {
                    every { it.qualifiedName } returns fqn
                }
            }
            every { modList.annotations } returns anns.toTypedArray()
            every { method.modifierList } returns modList
            every { method.annotations } returns anns.toTypedArray()
            method
        }
        every { psiClass.methods } returns methods.toTypedArray()
        every { psiClass.allMethods } returns methods.toTypedArray()

        val facade = mockk<JavaPsiFacade>(relaxed = true)
        mockkStatic(JavaPsiFacade::class)
        every { JavaPsiFacade.getInstance(project) } returns facade
        every { facade.findClass(className, any<GlobalSearchScope>()) } returns psiClass

        // Stub GlobalSearchScope.projectScope(project) — the validator calls it as the
        // search scope argument to findClass. Without this, the static helper dispatches
        // to ProjectScope.getProjectScope(project) which tries to resolve a project
        // service on the mock and ClassCasts.
        val projectScope = mockk<GlobalSearchScope>(relaxed = true)
        mockkStatic(GlobalSearchScope::class)
        every { GlobalSearchScope.projectScope(project) } returns projectScope

        mockkStatic(ModuleUtilCore::class)
        every { ModuleUtilCore.findModuleForPsiElement(psiClass) } returns module
        every { ModuleUtilCore.findModuleForFile(classFile, project) } returns module

        val rm = mockk<ModuleRootManager>(relaxed = true)
        mockkStatic(ModuleRootManager::class)
        every { ModuleRootManager.getInstance(module) } returns rm
        every { rm.getSourceRoots(JavaSourceRootType.TEST_SOURCE) } returns listOf(testRoot)
        every { rm.getSourceRoots(JavaSourceRootType.SOURCE) } returns listOf(mainRoot)
        every { rm.contentRoots } returns arrayOf(mockk<VirtualFile>(relaxed = true) {
            every { path } returns "/project/root/services/auth"
        })

        // VfsUtilCore.isAncestor: returns true only for the root that matches
        // sourceRootKind. This is how the validator distinguishes main vs test location.
        mockkStatic(VfsUtilCore::class)
        every { VfsUtilCore.isAncestor(any<VirtualFile>(), classFile, any<Boolean>()) } answers {
            val anc = firstArg<VirtualFile>()
            when (anc) {
                testRoot -> sourceRootKind == SourceRootKind.TEST
                mainRoot -> sourceRootKind == SourceRootKind.MAIN
                else -> false
            }
        }

        return WiredPsi(psiClass = psiClass, testSourceRoot = testRoot, mainSourceRoot = mainRoot, classFile = classFile)
    }

    private enum class SourceRootKind { TEST, MAIN }

    private data class WiredPsi(
        val psiClass: PsiClass,
        val testSourceRoot: VirtualFile,
        val mainSourceRoot: VirtualFile,
        val classFile: VirtualFile
    )

    // ========================================================================
    // Test 1 — Gradle module missing from settings.gradle is Blocked
    // ========================================================================

    @Test
    fun `gradle module missing from settings_gradle is Blocked with add-include suggestion`() {
        // Class itself is fine — under a test source root with an @Test method.
        wirePsiClass(
            className = "com.example.auth.AuthServiceTest",
            testMethodAnnotations = listOf(listOf("org.junit.jupiter.api.Test")),
            sourceRootKind = SourceRootKind.TEST
        )
        every { module.name } returns "services.auth"

        val validator = BuildSystemValidator(
            project = project,
            gradleResolver = StubGradleResolver(GradleResolution.NotInProjectTree),
            mavenResolver = StubMavenResolver(MavenResolution.NotApplicable)
        )

        val result = validator.validateForTestRun("com.example.auth.AuthServiceTest", module)

        assertTrue(result is BuildSystemValidator.ValidationResult.Blocked,
            "Expected Blocked when Gradle reports NotInProjectTree, got: $result")
        val blocked = result as BuildSystemValidator.ValidationResult.Blocked

        // reason must identify the module that's missing
        assertTrue(
            blocked.reason.contains("services", ignoreCase = true) ||
                blocked.reason.contains("auth", ignoreCase = true),
            "reason must mention the missing module; got: ${blocked.reason}"
        )
        assertTrue(
            blocked.reason.contains("settings.gradle", ignoreCase = true),
            "reason must mention settings.gradle; got: ${blocked.reason}"
        )

        // suggestion must be actionable — `include ':...'` + refresh
        assertTrue(
            blocked.suggestion.contains("include") &&
                blocked.suggestion.contains("'"),
            "suggestion must propose `include ':...'`; got: ${blocked.suggestion}"
        )
        assertTrue(
            blocked.suggestion.contains("refresh", ignoreCase = true) ||
                blocked.suggestion.contains("reload", ignoreCase = true) ||
                blocked.suggestion.contains("reimport", ignoreCase = true) ||
                blocked.suggestion.contains("re-import", ignoreCase = true),
            "suggestion must tell the user to refresh Gradle; got: ${blocked.suggestion}"
        )
    }

    // ========================================================================
    // Test 2 — Class lives in main sources (not test sources) is Blocked
    // ========================================================================

    @Test
    fun `class in main sources is Blocked with did-you-mean candidates`() {
        wirePsiClass(
            className = "com.example.MyService",
            testMethodAnnotations = listOf(listOf("org.junit.jupiter.api.Test")),
            sourceRootKind = SourceRootKind.MAIN
        )

        val validator = BuildSystemValidator(
            project = project,
            gradleResolver = StubGradleResolver(GradleResolution.Found(":services:auth")),
            mavenResolver = StubMavenResolver(MavenResolution.NotApplicable)
        )

        val result = validator.validateForTestRun("com.example.MyService", module)

        assertTrue(result is BuildSystemValidator.ValidationResult.Blocked,
            "Expected Blocked when class is in main sources, got: $result")
        val blocked = result as BuildSystemValidator.ValidationResult.Blocked

        // reason must make clear the class is in main / not test sources
        val reasonLower = blocked.reason.lowercase()
        assertTrue(
            reasonLower.contains("main") || reasonLower.contains("not test") ||
                reasonLower.contains("src/main"),
            "reason must identify main/non-test location; got: ${blocked.reason}"
        )

        // suggestion must direct the user toward src/test/java
        assertTrue(
            blocked.suggestion.contains("src/test/java") ||
                blocked.suggestion.lowercase().contains("test source"),
            "suggestion must point to test sources; got: ${blocked.suggestion}"
        )
        // NOTE: the "did-you-mean candidates" list (MyServiceTest, etc.) is a stretch
        // goal of Task 2.2. We assert only the must-have parts above so missing
        // candidates don't fail this test if the next pass defers that extraction.
    }

    // ========================================================================
    // Test 3 — Maven module not registered in parent pom is Blocked
    // ========================================================================

    @Test
    fun `maven module not registered is Blocked with reload-maven suggestion`() {
        wirePsiClass(
            className = "com.example.auth.AuthServiceTest",
            testMethodAnnotations = listOf(listOf("org.junit.jupiter.api.Test")),
            sourceRootKind = SourceRootKind.TEST
        )
        every { module.name } returns "services.auth"

        val validator = BuildSystemValidator(
            project = project,
            gradleResolver = StubGradleResolver(GradleResolution.NotApplicable),
            mavenResolver = StubMavenResolver(MavenResolution.NotRegistered)
        )

        val result = validator.validateForTestRun("com.example.auth.AuthServiceTest", module)

        assertTrue(result is BuildSystemValidator.ValidationResult.Blocked,
            "Expected Blocked when Maven reports NotRegistered, got: $result")
        val blocked = result as BuildSystemValidator.ValidationResult.Blocked

        assertTrue(
            blocked.reason.contains("services", ignoreCase = true) ||
                blocked.reason.contains("auth", ignoreCase = true),
            "reason must mention the module; got: ${blocked.reason}"
        )
        assertTrue(
            blocked.reason.contains("Maven", ignoreCase = true),
            "reason must mention Maven; got: ${blocked.reason}"
        )

        assertTrue(
            blocked.suggestion.contains("<module>") && blocked.suggestion.contains("</module>"),
            "suggestion must propose a <module>...</module> addition; got: ${blocked.suggestion}"
        )
        assertTrue(
            blocked.suggestion.lowercase().let { s ->
                s.contains("reload") || s.contains("reimport") || s.contains("re-import")
            } && blocked.suggestion.contains("Maven", ignoreCase = true),
            "suggestion must tell the user to reload Maven; got: ${blocked.suggestion}"
        )
    }

    // ========================================================================
    // Test 4 — Gradle authoritative path overrides filesystem-derived path
    // ========================================================================

    @Test
    fun `gradle authoritative path overrides filesystem-derived path`() {
        // Wire a class under a test source root. Force the module's content root to a
        // filesystem layout that would naively derive ":auth-svc" if the filesystem
        // derivation logic were used — proving the validator chose Gradle's answer.
        val wired = wirePsiClass(
            className = "com.example.auth.AuthServiceTest",
            testMethodAnnotations = listOf(listOf("org.junit.jupiter.api.Test")),
            sourceRootKind = SourceRootKind.TEST
        )
        val rm = ModuleRootManager.getInstance(module)
        every { rm.contentRoots } returns arrayOf(mockk<VirtualFile>(relaxed = true) {
            every { path } returns "/project/root/auth-svc"
        })

        val validator = BuildSystemValidator(
            project = project,
            // Gradle says the declared path is ":services:auth" — the filesystem would say
            // ":auth-svc". The authoritative value must win.
            gradleResolver = StubGradleResolver(GradleResolution.Found(":services:auth")),
            mavenResolver = StubMavenResolver(MavenResolution.NotApplicable)
        )

        val result = validator.validateForTestRun("com.example.auth.AuthServiceTest", module)

        assertTrue(result is BuildSystemValidator.ValidationResult.Ok,
            "Expected Ok when Gradle resolves the module, got: $result")
        val ok = result as BuildSystemValidator.ValidationResult.Ok

        assertEquals(":services:auth", ok.authoritativeBuildPath,
            "authoritative path must come from the Gradle resolver, not the filesystem layout")
        assertEquals(1, ok.detectedTestCount)
        assertEquals(wired.testSourceRoot, ok.testSourceRoot)
    }

    // ========================================================================
    // Test 5 — Test class with zero @Test methods is Blocked
    // ========================================================================

    @Test
    fun `test class with zero Test methods is Blocked with annotation hint`() {
        wirePsiClass(
            className = "com.example.EmptyTest",
            testMethodAnnotations = emptyList(), // no @Test methods at all
            sourceRootKind = SourceRootKind.TEST
        )

        val validator = BuildSystemValidator(
            project = project,
            gradleResolver = StubGradleResolver(GradleResolution.Found(":app")),
            mavenResolver = StubMavenResolver(MavenResolution.NotApplicable)
        )

        val result = validator.validateForTestRun("com.example.EmptyTest", module)

        assertTrue(result is BuildSystemValidator.ValidationResult.Blocked,
            "Expected Blocked when class has zero @Test methods, got: $result")
        val blocked = result as BuildSystemValidator.ValidationResult.Blocked

        assertTrue(
            blocked.reason.contains("@Test") &&
                (blocked.reason.contains("zero") || blocked.reason.contains("no ")),
            "reason must say 'zero @Test methods'; got: ${blocked.reason}"
        )

        // suggestion must distinguish JUnit 5 vs JUnit 4 annotation FQNs.
        assertTrue(
            blocked.suggestion.contains("org.junit.jupiter.api.Test"),
            "suggestion must mention the JUnit 5 annotation FQN; got: ${blocked.suggestion}"
        )
        assertTrue(
            blocked.suggestion.contains("org.junit.Test"),
            "suggestion must mention the JUnit 4 annotation FQN; got: ${blocked.suggestion}"
        )
    }

    // ========================================================================
    // Test 6 — No integration plugin but build file present → Ok + warning
    // ========================================================================
    //
    // NOTE: We wire a real on-disk build.gradle via @TempDir for the project basePath
    // so the validator's filesystem-fallback probe (which checks for pom.xml /
    // build.gradle[.kts] at the module's content root) finds the file. The VirtualFile
    // identity is mocked because wiring a real LocalFileSystem lookup through MockK is
    // noisy — we care about the warning + null authoritativeBuildPath contract here, not
    // the specific VirtualFile returned.

    @Test
    fun `build file present without integration plugin returns Ok with filesystem-fallback warning`(
        @TempDir tempDir: Path
    ) {
        val moduleDir = tempDir.resolve("services/auth").toFile().apply { mkdirs() }
        File(moduleDir, "build.gradle").writeText("// empty\n")

        every { project.basePath } returns tempDir.toAbsolutePath().toString()

        wirePsiClass(
            className = "com.example.auth.AuthServiceTest",
            testMethodAnnotations = listOf(listOf("org.junit.jupiter.api.Test")),
            sourceRootKind = SourceRootKind.TEST
        )
        // Override the contentRoot so the filesystem probe finds the real build.gradle.
        val rm = ModuleRootManager.getInstance(module)
        every { rm.contentRoots } returns arrayOf(mockk<VirtualFile>(relaxed = true) {
            every { path } returns moduleDir.absolutePath
        })

        val validator = BuildSystemValidator(
            project = project,
            gradleResolver = StubGradleResolver(GradleResolution.NotApplicable),
            mavenResolver = StubMavenResolver(MavenResolution.NotApplicable)
        )

        val result = validator.validateForTestRun("com.example.auth.AuthServiceTest", module)

        assertTrue(result is BuildSystemValidator.ValidationResult.Ok,
            "Expected Ok when build file present but no integration plugin, got: $result")
        val ok = result as BuildSystemValidator.ValidationResult.Ok

        assertNull(ok.authoritativeBuildPath,
            "authoritativeBuildPath must be null when both resolvers report NotApplicable (caller falls back to filesystem derivation)")
        assertNotNull(ok.warning,
            "Ok result must carry a non-blocking warning about filesystem-derived build path")
        assertTrue(
            ok.warning!!.lowercase().contains("filesystem") ||
                ok.warning!!.lowercase().contains("filesystem-derived"),
            "warning must mention filesystem derivation; got: ${ok.warning}"
        )
    }

    // ========================================================================
    // Test 7 — Nested Gradle module without intermediate declaration is Blocked
    // ========================================================================

    @Test
    fun `nested gradle module without intermediate declaration is Blocked`() {
        wirePsiClass(
            className = "com.example.auth.AuthServiceTest",
            testMethodAnnotations = listOf(listOf("org.junit.jupiter.api.Test")),
            sourceRootKind = SourceRootKind.TEST
        )
        // Nested-module layout: `services/parent/auth` where `services/parent` is just
        // a directory, NOT a declared Gradle project. IntelliJ imported the leaf module
        // but Gradle doesn't know about it.
        val rm = ModuleRootManager.getInstance(module)
        every { rm.contentRoots } returns arrayOf(mockk<VirtualFile>(relaxed = true) {
            every { path } returns "/project/root/services/parent/auth"
        })
        every { module.name } returns "services.parent.auth"

        val validator = BuildSystemValidator(
            project = project,
            gradleResolver = StubGradleResolver(GradleResolution.NotInProjectTree),
            mavenResolver = StubMavenResolver(MavenResolution.NotApplicable)
        )

        val result = validator.validateForTestRun("com.example.auth.AuthServiceTest", module)

        assertTrue(result is BuildSystemValidator.ValidationResult.Blocked,
            "Expected Blocked for nested module without intermediate declaration, got: $result")
        val blocked = result as BuildSystemValidator.ValidationResult.Blocked

        // reason must reference the nested path — either module name (dot-separated) or
        // the filesystem layout (slash-separated).
        assertTrue(
            blocked.reason.contains("services") && (
                blocked.reason.contains("parent") || blocked.reason.contains("auth")
                ),
            "reason must reference the nested module path; got: ${blocked.reason}"
        )

        // suggestion must propose adding an include to settings.gradle.
        assertTrue(
            blocked.suggestion.contains("include") &&
                blocked.suggestion.contains("settings.gradle"),
            "suggestion must propose an include in settings.gradle; got: ${blocked.suggestion}"
        )
    }
}
