package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.io.File

/**
 * Pre-flight validator for `java_runtime_exec.run_tests`.
 *
 * Catches the "module resolved in IntelliJ but unrunnable in the build tool" failure family
 * before the native runner or shell fallback dispatches. Surfaces actionable errors
 * (reason + suggestion) to the agent so the LLM can self-correct without wasting shell
 * cycles on doomed invocations.
 *
 * The validator itself is pure — its Gradle and Maven probes are injected via
 * [GradleSubprojectResolver] / [MavenModuleResolver] so tests can supply fakes and the
 * agent module does not pick up a compile-time dependency on the Gradle/Maven IntelliJ
 * integration plugins (they're optional at runtime; the production resolvers use
 * `Class.forName` reflection and degrade gracefully).
 *
 * See `docs/plans/2026-04-17-phase2-multi-module-build-validation.md` for the failure
 * modes this validator must detect.
 */
class BuildSystemValidator(
    private val project: Project,
    private val gradleResolver: GradleSubprojectResolver = ReflectiveGradleSubprojectResolver,
    private val mavenResolver: MavenModuleResolver = ReflectiveMavenModuleResolver
) {
    sealed class ValidationResult {
        /**
         * The test class is runnable. Dispatch can proceed.
         *
         * @param module the IntelliJ module owning the test class
         * @param authoritativeBuildPath e.g. `":services:auth"` (Gradle) or
         *   `"/abs/path/services/auth"` (Maven). `null` means the build-tool integration
         *   was not available and the caller should fall back to filesystem derivation.
         * @param testSourceRoot the test source root containing the class
         * @param detectedTestCount PSI count of `@Test`-annotated methods in the class
         * @param warning non-blocking note (e.g. "Using filesystem-derived build path…")
         */
        data class Ok(
            val module: Module,
            val authoritativeBuildPath: String?,
            val testSourceRoot: VirtualFile,
            val detectedTestCount: Int,
            val warning: String? = null
        ) : ValidationResult()

        /**
         * Pre-flight check failed. The caller MUST return this as an error tool result
         * and MUST surface both [reason] and [suggestion] to the LLM — diagnostic-only
         * errors without a suggestion are an architectural bug.
         */
        data class Blocked(
            val reason: String,
            val suggestion: String
        ) : ValidationResult()
    }

    /**
     * Validate that [className] is runnable as a test in [module].
     *
     * Runs the checks in the following short-circuit order:
     *  1. PSI class resolution (Blocked if class not found).
     *  2. Test source root verification — Task 2.2 (Blocked if class lives in main sources).
     *  3. `@Test` method count pre-check — Task 2.6 (Blocked if zero).
     *  4. Gradle subproject probe — Task 2.3 (Blocked if declared but not in project tree).
     *  5. Maven module probe — Task 2.4 (Blocked if declared but not registered).
     *  6. Filesystem fallback — Task 2.5 (Ok-with-warning, or Blocked when no build file).
     *
     * @see docs/plans/2026-04-17-phase2-multi-module-build-validation.md
     */
    fun validateForTestRun(className: String, module: Module): ValidationResult {
        // Wrap the whole PSI-touching body in a ReadAction — PSI, VFS, and module-roots
        // reads must run under a read lock in IntelliJ. The test harness stubs
        // ReadAction.compute to run inline on the test thread.
        return ReadAction.compute<ValidationResult, RuntimeException> {
            validateInReadAction(className, module)
        }
    }

    private fun validateInReadAction(className: String, module: Module): ValidationResult {
        // -- Step 1: PSI class resolution -----------------------------------------
        val psiClass: PsiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.projectScope(project))
            ?: return ValidationResult.Blocked(
                reason = "Class '$className' not found in project sources.",
                suggestion = "Verify the fully-qualified name and that the module containing it is imported."
            )

        val classVf: VirtualFile = psiClass.containingFile?.virtualFile
            ?: return ValidationResult.Blocked(
                reason = "Class '$className' has no backing source file.",
                suggestion = "This usually means the class is in a compiled jar. Point the test runner at a source class."
            )

        // -- Step 2: Test source root verification (Task 2.2) ---------------------
        val rootManager = ModuleRootManager.getInstance(module)
        val testSourceRoots: List<VirtualFile> = rootManager.getSourceRoots(JavaSourceRootType.TEST_SOURCE)

        val owningTestRoot: VirtualFile? = testSourceRoots.firstOrNull { root ->
            VfsUtilCore.isAncestor(root, classVf, false)
        }

        if (owningTestRoot == null) {
            val candidates = findTestCandidateNames(testSourceRoots, className)
            val candidateHint = if (candidates.isNotEmpty()) {
                " Candidates in this module: ${candidates.joinToString(", ")}."
            } else {
                ""
            }
            return ValidationResult.Blocked(
                reason = "Class '$className' is in main sources (src/main/java), not test sources.",
                suggestion = "Tests live under src/test/java.$candidateHint " +
                    "Use test_finder to list all test classes if unsure."
            )
        }

        // -- Step 3: @Test method count pre-check (Task 2.6) ----------------------
        val testMethodCount = countTestAnnotatedMethods(psiClass)
        if (testMethodCount == 0) {
            return ValidationResult.Blocked(
                reason = "Class '$className' exists in test sources but contains zero @Test methods.",
                suggestion = "Check the test annotations — JUnit 5 uses @Test from org.junit.jupiter.api.Test, " +
                    "JUnit 4 uses org.junit.Test."
            )
        }

        // Relative path used in Gradle/Maven Blocked messages. Derived from the module's
        // content root so the LLM sees the nested path (e.g. `services/parent/auth`) it
        // needs to add to settings.gradle / the parent pom.
        val contentRootPath: String? = rootManager.contentRoots.firstOrNull()?.path
        val moduleRelativePath: String = describeModulePath(module, contentRootPath)

        // -- Step 4: Gradle subproject probe (Task 2.3) ---------------------------
        val gradleResolution = gradleResolver.resolveSubprojectPath(project, module)
        when (gradleResolution) {
            is GradleResolution.Found -> return ValidationResult.Ok(
                module = module,
                authoritativeBuildPath = gradleResolution.path,
                testSourceRoot = owningTestRoot,
                detectedTestCount = testMethodCount
            )

            is GradleResolution.NotInProjectTree -> return ValidationResult.Blocked(
                reason = "Module '$moduleRelativePath' exists in IntelliJ but Gradle's settings.gradle " +
                    "doesn't declare it.",
                suggestion = "Add `include ':$moduleRelativePath'` to settings.gradle " +
                    "(use colons for nested paths, e.g. `include ':services:auth'`), then run File | Gradle | Refresh."
            )

            is GradleResolution.NotApplicable -> {
                // fall through to Maven probe
            }
        }

        // -- Step 5: Maven module probe (Task 2.4) --------------------------------
        val mavenResolution = mavenResolver.resolveModuleDirectory(project, module)
        when (mavenResolution) {
            is MavenResolution.Found -> return ValidationResult.Ok(
                module = module,
                authoritativeBuildPath = mavenResolution.directory,
                testSourceRoot = owningTestRoot,
                detectedTestCount = testMethodCount
            )

            is MavenResolution.NotRegistered -> return ValidationResult.Blocked(
                reason = "Module '$moduleRelativePath' exists in IntelliJ but Maven hasn't registered it.",
                suggestion = "Add <module>$moduleRelativePath</module> to the parent pom.xml, " +
                    "then run Maven | Reload Project."
            )

            is MavenResolution.NotApplicable -> {
                // fall through to filesystem fallback
            }
        }

        // -- Step 6: Filesystem fallback (Task 2.5) -------------------------------
        if (contentRootPath == null) {
            return ValidationResult.Blocked(
                reason = "Module '${module.name}' has no content root — cannot dispatch tests.",
                suggestion = "Re-import the module from its build file, or ensure at least one content root is configured."
            )
        }

        val contentRootDir = File(contentRootPath)
        val hasBuildFile = File(contentRootDir, "pom.xml").exists() ||
            File(contentRootDir, "build.gradle").exists() ||
            File(contentRootDir, "build.gradle.kts").exists()

        return if (hasBuildFile) {
            ValidationResult.Ok(
                module = module,
                authoritativeBuildPath = null,
                testSourceRoot = owningTestRoot,
                detectedTestCount = testMethodCount,
                warning = "Using filesystem-derived build path. If this is a composite build or non-standard layout, invocation may fail."
            )
        } else {
            ValidationResult.Blocked(
                reason = "Module '${module.name}' has no pom.xml or build.gradle at its content root.",
                suggestion = "Test dispatch requires a build tool. " +
                    "Add a pom.xml or build.gradle at the module's content root."
            )
        }
    }

    /**
     * Count methods on [psiClass] whose modifier list contains a `@Test` annotation from
     * JUnit 4, JUnit 5, or TestNG. Counts a method once even if it carries several of
     * these annotations.
     */
    private fun countTestAnnotatedMethods(psiClass: PsiClass): Int {
        return psiClass.methods.count { method ->
            val annotations = method.modifierList?.annotations.orEmpty().toList() +
                method.annotations.toList()
            annotations.any { it?.qualifiedName in TEST_ANNOTATION_FQNS }
        }
    }

    /**
     * Build a human-readable relative path for the module, used in Blocked reasons /
     * suggestions. Prefers the module's content root under the project base path (gives
     * `services/auth`), falling back to the IntelliJ module name's dot-to-slash
     * translation (`services.parent.auth` → `services/parent/auth`) when the content
     * root isn't under the project base.
     */
    private fun describeModulePath(module: Module, contentRootPath: String?): String {
        val basePath = project.basePath
        if (contentRootPath != null && basePath != null) {
            val relative = contentRootPath
                .removePrefix(basePath)
                .trimStart('/', File.separatorChar)
            if (relative.isNotBlank() && relative != contentRootPath) {
                return relative
            }
        }
        // Fall back to IntelliJ's module name. IntelliJ typically encodes nesting as
        // dots (e.g. `services.auth`); translate to slashes for filesystem-style paths.
        return module.name.replace('.', '/')
    }

    /**
     * Produce a short "did-you-mean" list of test-class names adjacent to [className]'s
     * simple name (e.g. for `com.example.MyService` → `MyServiceTest`, `MyServiceIT`).
     *
     * Kept intentionally cheap: a flat scan of each test source root for files whose
     * name matches one of the common test-class suffixes. Returns the first few matches.
     */
    private fun findTestCandidateNames(
        testSourceRoots: List<VirtualFile>,
        className: String
    ): List<String> {
        val simpleName = className.substringAfterLast('.')
        if (simpleName.isBlank()) return emptyList()

        val wantedFileNames = TEST_SUFFIXES.map { "$simpleName$it.java" } +
            TEST_SUFFIXES.map { "$simpleName$it.kt" }

        val found = linkedSetOf<String>()
        for (root in testSourceRoots) {
            VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
                if (!vf.isDirectory && vf.name in wantedFileNames) {
                    found += vf.nameWithoutExtension
                }
                // Cap the scan when we already have a few suggestions.
                found.size < MAX_CANDIDATE_SUGGESTIONS
            }
            if (found.size >= MAX_CANDIDATE_SUGGESTIONS) break
        }
        return found.toList()
    }

    companion object {
        /**
         * FQNs of the `@Test` annotation across JUnit 4, JUnit 5, and TestNG.
         */
        private val TEST_ANNOTATION_FQNS = setOf(
            "org.junit.Test",                    // JUnit 4
            "org.junit.jupiter.api.Test",        // JUnit 5
            "org.testng.annotations.Test"        // TestNG
        )

        /** Common test-class name suffixes used for the "did-you-mean" candidate list. */
        private val TEST_SUFFIXES = listOf("Test", "Tests", "IT", "ITest", "IntegrationTest")

        private const val MAX_CANDIDATE_SUGGESTIONS = 3
    }
}

/**
 * Resolves the authoritative Gradle subproject path (e.g. `":services:auth"`) for an
 * IntelliJ [Module]. Production implementation probes Gradle's project data tree via
 * reflection; tests inject fakes.
 */
interface GradleSubprojectResolver {
    fun resolveSubprojectPath(project: Project, module: Module): GradleResolution
}

sealed class GradleResolution {
    /** Gradle knows the module; [path] is the authoritative subproject path. */
    data class Found(val path: String) : GradleResolution()

    /**
     * Gradle has imported the project but does not declare this module — typically
     * a missing `include ':...'` in `settings.gradle`.
     */
    object NotInProjectTree : GradleResolution()

    /**
     * Gradle integration not available: plugin not installed, reflection failed, or the
     * project is not a Gradle project. The validator should fall back to filesystem
     * derivation with a warning.
     */
    object NotApplicable : GradleResolution()
}

/**
 * Resolves the authoritative Maven module directory for an IntelliJ [Module]. Production
 * implementation probes `MavenProjectsManager` via reflection; tests inject fakes.
 */
interface MavenModuleResolver {
    fun resolveModuleDirectory(project: Project, module: Module): MavenResolution
}

sealed class MavenResolution {
    /** Maven knows the module; [directory] is its module directory (absolute path). */
    data class Found(val directory: String) : MavenResolution()

    /**
     * Maven has imported the project but did not register this module — typically a
     * missing `<module>...</module>` declaration in the parent pom.
     */
    object NotRegistered : MavenResolution()

    /**
     * Maven integration not available: plugin not installed, reflection failed, or the
     * project is not a Maven project. The validator should fall back to filesystem
     * derivation with a warning.
     */
    object NotApplicable : MavenResolution()
}

/**
 * Production Gradle resolver. Probes `org.jetbrains.plugins.gradle.util.GradleConstants`
 * reflectively so the `:agent` module has zero compile-time dependency on the Gradle
 * plugin (it's optional at runtime). Walks the external-system project data tree to
 * find the [ModuleData] whose IntelliJ-side `internalName` matches the given [Module]'s
 * name.
 *
 * Any exception escaping reflection — Gradle plugin not installed, API drift, null
 * project tree — degrades to [GradleResolution.NotApplicable] so the validator falls
 * through to the filesystem fallback instead of failing the run.
 */
object ReflectiveGradleSubprojectResolver : GradleSubprojectResolver {
    override fun resolveSubprojectPath(project: Project, module: Module): GradleResolution {
        return try {
            // Load GradleConstants.SYSTEM_ID via reflection — the only piece that forces
            // a dependency on the Gradle plugin. The rest of the probe uses the
            // external-system core APIs which ship with the platform.
            val gradleConstantsClass = Class.forName("org.jetbrains.plugins.gradle.util.GradleConstants")
            val systemId = gradleConstantsClass.getField("SYSTEM_ID").get(null) as? ProjectSystemId
                ?: return GradleResolution.NotApplicable

            val projectNode: DataNode<ProjectData> =
                ExternalSystemApiUtil.findProjectNode(project, systemId, project.basePath ?: "")
                    ?: findAnyGradleProjectNode(project, systemId)
                    ?: return GradleResolution.NotApplicable

            val moduleNodes: Collection<DataNode<ModuleData>> =
                ExternalSystemApiUtil.findAll(projectNode, ProjectKeys.MODULE)

            val match = moduleNodes.firstOrNull { node ->
                val data = node.data
                data.internalName == module.name ||
                    data.id == module.name ||
                    data.moduleName == module.name
            }

            if (match != null) {
                GradleResolution.Found(match.data.id)
            } else {
                GradleResolution.NotInProjectTree
            }
        } catch (_: Throwable) {
            // ClassNotFoundException (plugin absent), NoSuchFieldException (API drift),
            // ClassCastException (systemId shape changed), NullPointerException on an
            // unexpected null — any of these means we should silently degrade.
            GradleResolution.NotApplicable
        }
    }

    /**
     * Fall back to scanning all linked Gradle projects for a matching root when
     * `findProjectNode(project, systemId, basePath)` doesn't return the primary tree.
     * Returns the first non-null project node.
     */
    private fun findAnyGradleProjectNode(
        project: Project,
        systemId: ProjectSystemId
    ): DataNode<ProjectData>? {
        return try {
            val settings = ExternalSystemApiUtil.getSettings(project, systemId)
            settings.linkedProjectsSettings
                .mapNotNull { it.externalProjectPath }
                .firstNotNullOfOrNull { path ->
                    ExternalSystemApiUtil.findProjectNode(project, systemId, path)
                }
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Production Maven resolver. Probes `MavenProjectsManager` reflectively — the Maven
 * plugin, like Gradle, is optional at runtime, so we keep zero compile-time dependency
 * on `org.jetbrains.idea.maven.*`. When `findProject(module)` returns a non-null
 * MavenProject we extract its directory; when it returns null we report
 * [MavenResolution.NotRegistered] so the validator can emit the "add <module>…" guidance.
 */
object ReflectiveMavenModuleResolver : MavenModuleResolver {
    override fun resolveModuleDirectory(project: Project, module: Module): MavenResolution {
        return try {
            val managerClass = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
            val getInstance = managerClass.getMethod("getInstance", Project::class.java)
            val manager = getInstance.invoke(null, project)
                ?: return MavenResolution.NotApplicable

            // If the Maven integration is installed but the project isn't a Maven
            // project, `isMavenizedProject()` is false — treat that as NotApplicable so
            // we don't emit misleading "add <module>" guidance on a pure Gradle project.
            val isMavenized = try {
                val method = managerClass.getMethod("isMavenizedProject")
                method.invoke(manager) as? Boolean ?: false
            } catch (_: Throwable) {
                // Older/newer Maven APIs may not expose this predicate; assume mavenized
                // and let findProject() decide.
                true
            }
            if (!isMavenized) return MavenResolution.NotApplicable

            val findProject = managerClass.getMethod("findProject", Module::class.java)
            val mavenProject = findProject.invoke(manager, module)
                ?: return MavenResolution.NotRegistered

            // MavenProject#getDirectory() returns the absolute path to the module dir.
            val directory = try {
                mavenProject.javaClass.getMethod("getDirectory").invoke(mavenProject) as? String
            } catch (_: NoSuchMethodException) {
                // Some versions expose the Kotlin property getter under a different name.
                mavenProject.javaClass.getMethod("directory").invoke(mavenProject) as? String
            }

            if (directory.isNullOrBlank()) {
                MavenResolution.NotRegistered
            } else {
                MavenResolution.Found(directory)
            }
        } catch (_: Throwable) {
            MavenResolution.NotApplicable
        }
    }
}
