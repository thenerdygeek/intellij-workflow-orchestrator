package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SpringVersionTool : AgentTool {
    override val name = "spring_version_info"
    override val description = "Detect Spring Boot, Spring Framework, Java, and key dependency versions from Maven."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(type = "string", description = "Optional: module name to inspect. If omitted, uses the root/first Maven project.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return try {
            val moduleFilter = params["module"]?.jsonPrimitive?.content

            val manager = MavenUtils.getMavenManager(project)
                ?: return MavenUtils.noMavenError()

            val mavenProjects = MavenUtils.getMavenProjects(manager)
            if (mavenProjects.isEmpty()) {
                return ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
                ?: return ToolResult(
                    "Module '${moduleFilter}' not found. Available: ${MavenUtils.getProjectNames(mavenProjects)}",
                    "Module not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            // Get project identity
            val projectName = MavenUtils.getDisplayName(targetProject)
            val projectVersion = MavenUtils.getMavenId(targetProject, "getVersion") ?: "unknown"

            // Get all dependencies and properties
            val dependencies = MavenUtils.getDependencies(targetProject)
            val properties = MavenUtils.getProperties(targetProject)

            // Detect versions from dependencies and properties
            val versions = mutableMapOf<String, String>()

            // Spring Boot
            findVersion(dependencies, "org.springframework.boot", "spring-boot-starter", "spring-boot")?.let {
                versions["Spring Boot"] = it
            }
            // Also check parent
            if ("Spring Boot" !in versions) {
                getParentVersion(targetProject, "org.springframework.boot")?.let {
                    versions["Spring Boot"] = it
                }
            }

            // Spring Framework
            findVersion(dependencies, "org.springframework", "spring-core", "spring-context", "spring-web")?.let {
                versions["Spring Framework"] = it
            }

            // Java version from properties
            val javaVersion = properties["java.version"]
                ?: properties["maven.compiler.source"]
                ?: properties["maven.compiler.target"]
                ?: properties["maven.compiler.release"]
            if (javaVersion != null) {
                versions["Java"] = javaVersion
            }

            // Kotlin
            findVersion(dependencies, "org.jetbrains.kotlin", "kotlin-stdlib", "kotlin-stdlib-jdk8", "kotlin-reflect")?.let {
                versions["Kotlin"] = it
            } ?: properties["kotlin.version"]?.let { versions["Kotlin"] = it }

            // JUnit
            findVersion(dependencies, "org.junit.jupiter", "junit-jupiter", "junit-jupiter-api")?.let {
                versions["JUnit"] = it
            }

            // Hibernate
            findVersion(dependencies, "org.hibernate.orm", "hibernate-core")?.let {
                versions["Hibernate"] = it
            } ?: findVersion(dependencies, "org.hibernate", "hibernate-core")?.let {
                versions["Hibernate"] = it
            }

            // Jackson
            findVersion(dependencies, "com.fasterxml.jackson.core", "jackson-databind")?.let {
                versions["Jackson"] = it
            }

            // Lombok
            findVersion(dependencies, "org.projectlombok", "lombok")?.let {
                versions["Lombok"] = it
            }

            // Apache Commons
            findVersion(dependencies, "org.apache.commons", "commons-lang3")?.let {
                versions["Commons Lang"] = it
            }

            // SLF4J/Logback
            findVersion(dependencies, "ch.qos.logback", "logback-classic")?.let {
                versions["Logback"] = it
            }

            if (versions.isEmpty()) {
                return ToolResult(
                    "Project: $projectName ($projectVersion)\nNo recognized framework versions detected from Maven dependencies.",
                    "No frameworks", 10
                )
            }

            val content = buildString {
                appendLine("Project: $projectName ($projectVersion)")
                for ((name, version) in versions) {
                    appendLine("$name: $version")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = versions.entries.joinToString(", ") { "${it.key} ${it.value}" },
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error detecting versions: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    /** Find version for any matching artifactId within a groupId */
    private fun findVersion(dependencies: List<MavenUtils.MavenDependencyInfo>, groupId: String, vararg artifactIds: String): String? {
        for (artifactId in artifactIds) {
            val dep = dependencies.find { it.groupId == groupId && it.artifactId == artifactId && it.version.isNotBlank() }
            if (dep != null) return dep.version
        }
        // Fallback: any artifact in the group
        return dependencies.find { it.groupId == groupId && it.version.isNotBlank() }?.version
    }

    private fun getParentVersion(mavenProject: Any, parentGroupId: String): String? {
        return try {
            val parentId = mavenProject.javaClass.getMethod("getParentId").invoke(mavenProject) ?: return null
            val groupId = parentId.javaClass.getMethod("getGroupId").invoke(parentId) as? String
            if (groupId == parentGroupId) {
                parentId.javaClass.getMethod("getVersion").invoke(parentId) as? String
            } else null
        } catch (_: Exception) { null }
    }
}
