package com.workflow.orchestrator.core.model.build

/**
 * A structured build/import problem reported by the IDE for the local project.
 * Sourced from MavenProjectsManager (V1) and later from Gradle import + compile events (V1.1).
 *
 * Distinct from remote CI build failures (Bamboo etc.), which surface via the
 * bamboo_* tools, not via this model.
 */
data class BuildProblem(
    val source: BuildSource,
    val projectPath: String,
    val description: String,
    val type: ProblemType,
    val severity: Severity,
    val line: Int? = null,
    val artifactCoords: String? = null,
)

enum class BuildSource {
    MAVEN_IMPORT,
    GRADLE_IMPORT,
    COMPILE,
}

enum class ProblemType {
    DEPENDENCY,
    REPOSITORY,
    PARENT,
    STRUCTURE,
    SYNTAX,
    SETTINGS,
    COMPILE,
    OTHER,
}

enum class Severity {
    ERROR,
    WARNING,
}
