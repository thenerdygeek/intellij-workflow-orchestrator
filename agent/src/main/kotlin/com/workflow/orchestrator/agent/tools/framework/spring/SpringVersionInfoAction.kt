package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal suspend fun executeVersionInfo(params: JsonObject, project: Project): ToolResult {
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

        val projectName = MavenUtils.getDisplayName(targetProject)
        val projectVersion = MavenUtils.getMavenId(targetProject, "getVersion") ?: "unknown"

        val dependencies = MavenUtils.getDependencies(targetProject)
        val properties = MavenUtils.getProperties(targetProject)

        val versions = mutableMapOf<String, String>()

        findVersion(dependencies, "org.springframework.boot", "spring-boot-starter", "spring-boot")?.let {
            versions["Spring Boot"] = it
        }
        if ("Spring Boot" !in versions) {
            getParentVersion(targetProject, "org.springframework.boot")?.let {
                versions["Spring Boot"] = it
            }
        }

        findVersion(dependencies, "org.springframework", "spring-core", "spring-context", "spring-web")?.let {
            versions["Spring Framework"] = it
        }

        val javaVersion = properties["java.version"]
            ?: properties["maven.compiler.source"]
            ?: properties["maven.compiler.target"]
            ?: properties["maven.compiler.release"]
        if (javaVersion != null) {
            versions["Java"] = javaVersion
        }

        findVersion(dependencies, "org.jetbrains.kotlin", "kotlin-stdlib", "kotlin-stdlib-jdk8", "kotlin-reflect")?.let {
            versions["Kotlin"] = it
        } ?: properties["kotlin.version"]?.let { versions["Kotlin"] = it }

        findVersion(dependencies, "org.junit.jupiter", "junit-jupiter", "junit-jupiter-api")?.let {
            versions["JUnit"] = it
        }

        findVersion(dependencies, "org.hibernate.orm", "hibernate-core")?.let {
            versions["Hibernate"] = it
        } ?: findVersion(dependencies, "org.hibernate", "hibernate-core")?.let {
            versions["Hibernate"] = it
        }

        findVersion(dependencies, "com.fasterxml.jackson.core", "jackson-databind")?.let {
            versions["Jackson"] = it
        }

        findVersion(dependencies, "org.projectlombok", "lombok")?.let {
            versions["Lombok"] = it
        }

        findVersion(dependencies, "org.apache.commons", "commons-lang3")?.let {
            versions["Commons Lang"] = it
        }

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

private fun findVersion(dependencies: List<MavenUtils.MavenDependencyInfo>, groupId: String, vararg artifactIds: String): String? {
    for (artifactId in artifactIds) {
        val dep = dependencies.find { it.groupId == groupId && it.artifactId == artifactId && it.version.isNotBlank() }
        if (dep != null) return dep.version
    }
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
