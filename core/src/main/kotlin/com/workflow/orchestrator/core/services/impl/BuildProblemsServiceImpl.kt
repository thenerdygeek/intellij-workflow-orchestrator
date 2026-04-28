package com.workflow.orchestrator.core.services.impl

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.build.BuildProblem
import com.workflow.orchestrator.core.model.build.BuildSource
import com.workflow.orchestrator.core.model.build.ProblemType
import com.workflow.orchestrator.core.model.build.Severity
import com.workflow.orchestrator.core.services.BuildProblemsService
import com.workflow.orchestrator.core.services.ToolResult

/**
 * Reflectively probes the Maven plugin for raw `MavenProjectProblem`s.
 * Production impl is [ReflectiveMavenProblemsProbe]; tests inject fakes.
 *
 * Returns a list of opaque records (path + description + raw type-name) — mapping
 * to the typed [BuildProblem] domain happens in [BuildProblemsServiceImpl] so the
 * probe stays thin and isolated from the data shape.
 */
interface MavenProblemsProbe {
    data class RawProblem(val path: String, val description: String, val typeName: String?)

    fun read(project: Project): List<RawProblem>
}

/**
 * V1: reads Maven import problems via reflective probe of `MavenProjectsManager`.
 * Gradle import + compile event capture is V1.1.
 *
 * Reflective access keeps `:core` free of a compile-time dependency on
 * `org.jetbrains.idea.maven.*` (the Maven plugin is optional at runtime).
 * Same pattern as `agent/tools/runtime/BuildSystemValidator.ReflectiveMavenModuleResolver`.
 */
@Service(Service.Level.PROJECT)
class BuildProblemsServiceImpl(
    private val project: Project,
    private val mavenProbe: MavenProblemsProbe = ReflectiveMavenProblemsProbe,
) : BuildProblemsService {

    override suspend fun getRecentBuildProblems(): ToolResult<List<BuildProblem>> {
        // Reflective access to MavenProject.problems mutates under the import write
        // thread; readAction { } gives the platform's standard read-side synchronization
        // per :core "Service & threading conventions" (Phase 4).
        val raws = readAction { mavenProbe.read(project) }
        val maven = raws.map { mapMavenProblem(it) }
        // V1.1: combine with Gradle + compile sources here.
        val all = maven
        val summary = if (all.isEmpty()) {
            "No build/import problems."
        } else {
            "${all.size} build/import problem(s) — sources: ${all.map { it.source }.distinct().joinToString(", ")}"
        }
        return ToolResult.success(data = all, summary = summary)
    }

    internal fun mapMavenProblem(raw: MavenProblemsProbe.RawProblem): BuildProblem {
        val type = mapMavenProblemType(raw.typeName)
        val artifactCoords = if (type == ProblemType.DEPENDENCY) extractArtifactCoords(raw.description) else null
        return BuildProblem(
            source = BuildSource.MAVEN_IMPORT,
            projectPath = raw.path,
            description = raw.description,
            type = type,
            severity = Severity.ERROR,
            line = null,
            artifactCoords = artifactCoords,
        )
    }

    /**
     * Map IntelliJ's `MavenProjectProblem.ProblemType` enum names to our [ProblemType].
     * Names observed across platform versions: STRUCTURE, SETTINGS_OR_PROFILES, SYNTAX,
     * DEPENDENCY, PARENT, REPOSITORY.
     */
    internal fun mapMavenProblemType(name: String?): ProblemType = when (name?.uppercase()) {
        "DEPENDENCY" -> ProblemType.DEPENDENCY
        "REPOSITORY" -> ProblemType.REPOSITORY
        "PARENT" -> ProblemType.PARENT
        "STRUCTURE" -> ProblemType.STRUCTURE
        "SYNTAX" -> ProblemType.SYNTAX
        "SETTINGS_OR_PROFILES", "SETTINGS" -> ProblemType.SETTINGS
        else -> ProblemType.OTHER
    }

    /**
     * Best-effort extraction of `groupId:artifactId:version` from a Maven dependency
     * error message like "Could not transfer artifact org.foo:bar:jar:1.2.3 from/to ..."
     * Returns null when no coordinate-shape token is found.
     *
     * The token must look like 3-5 colon-separated alphanumeric/dot/dash segments and
     * must not contain `/` or look like a URL — that excludes false positives like
     * `https://nexus.example.com:443/path` (which has 3 colons but a URL shape).
     */
    internal fun extractArtifactCoords(description: String): String? {
        val token = description.split(' ', '\n', '\t', ',').firstOrNull { fragment ->
            val trimmed = fragment.trimEnd('.', ',', ':')
            COORD_REGEX.matches(trimmed)
        } ?: return null
        return token.trimEnd('.', ',', ':')
    }

    private companion object {
        private val COORD_REGEX = Regex("""^[\w.\-]+(:[\w.\-]+){2,4}$""")
    }
}

/**
 * Production probe — reflectively reads `MavenProjectsManager.rootProjects[*].problems`.
 * Returns empty list when the Maven plugin is absent or the project isn't mavenized.
 */
object ReflectiveMavenProblemsProbe : MavenProblemsProbe {
    private val log = Logger.getInstance(ReflectiveMavenProblemsProbe::class.java)

    override fun read(project: Project): List<MavenProblemsProbe.RawProblem> {
        return try {
            val managerClass = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
            val getInstance = managerClass.getMethod("getInstance", Project::class.java)
            val manager = getInstance.invoke(null, project) ?: return emptyList()

            val isMavenized = try {
                managerClass.getMethod("isMavenizedProject").invoke(manager) as? Boolean ?: false
            } catch (_: Throwable) {
                true
            }
            if (!isMavenized) return emptyList()

            @Suppress("UNCHECKED_CAST")
            val rootProjects = invokeAny(manager, "getRootProjects", "rootProjects") as? List<Any>
                ?: return emptyList()

            val out = mutableListOf<MavenProblemsProbe.RawProblem>()
            for (mavenProject in rootProjects) {
                val pomPath = readMavenProjectPath(mavenProject)
                @Suppress("UNCHECKED_CAST")
                val mavenProblems = invokeAny(mavenProject, "getProblems", "problems") as? List<Any>
                    ?: continue

                for (problem in mavenProblems) {
                    val description = invokeAny(problem, "getDescription", "description") as? String
                        ?: "Maven import problem"

                    val path = invokeAny(problem, "getPath", "path") as? String ?: pomPath

                    val typeName = invokeAny(problem, "getType", "type")?.toString()

                    out += MavenProblemsProbe.RawProblem(path = path, description = description, typeName = typeName)
                }
            }
            out
        } catch (_: ClassNotFoundException) {
            // Maven plugin not present — silent no-op.
            emptyList()
        } catch (e: Throwable) {
            log.warn("[BuildProblems] Failed to read Maven problems reflectively", e)
            emptyList()
        }
    }

    private fun readMavenProjectPath(mavenProject: Any): String {
        return (invokeAny(mavenProject, "getPath", "path") as? String)
            ?: (invokeAny(mavenProject, "getFile", "file")?.toString())
            ?: ""
    }

    /**
     * Try a list of method names against a target object; return the first non-null result.
     * Mirrors the "older/newer Maven APIs" hedge in `BuildSystemValidator.kt:482-487` —
     * Kotlin properties on platform classes can be exposed as either `getX()` or `x()`
     * depending on the build, and we want to be resilient to either.
     */
    private fun invokeAny(target: Any, vararg methodNames: String): Any? {
        for (name in methodNames) {
            try {
                return target.javaClass.getMethod(name).invoke(target)
            } catch (_: NoSuchMethodException) {
                continue
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }
}
