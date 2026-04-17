package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

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
     * SKELETON — always returns [ValidationResult.Blocked] with a placeholder message.
     * The real implementation (Tasks 2.2–2.6 in the phase-2 plan) is out of scope for
     * this pass and will be completed by a subsequent subagent.
     */
    fun validateForTestRun(className: String, module: Module): ValidationResult {
        return ValidationResult.Blocked(
            reason = "BuildSystemValidator not yet implemented",
            suggestion = "Implement Tasks 2.2–2.6 from docs/plans/2026-04-17-phase2-multi-module-build-validation.md"
        )
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
 * Production Gradle resolver. STUB — always returns [GradleResolution.NotApplicable]
 * until the reflective implementation lands in a later pass. Kept as a singleton so
 * `BuildSystemValidator`'s default constructor remains side-effect-free and cheap.
 */
object ReflectiveGradleSubprojectResolver : GradleSubprojectResolver {
    override fun resolveSubprojectPath(project: Project, module: Module): GradleResolution =
        GradleResolution.NotApplicable
}

/**
 * Production Maven resolver. STUB — always returns [MavenResolution.NotApplicable]
 * until the reflective implementation lands in a later pass.
 */
object ReflectiveMavenModuleResolver : MavenModuleResolver {
    override fun resolveModuleDirectory(project: Project, module: Module): MavenResolution =
        MavenResolution.NotApplicable
}
