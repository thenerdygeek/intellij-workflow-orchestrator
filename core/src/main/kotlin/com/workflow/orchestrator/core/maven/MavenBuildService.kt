package com.workflow.orchestrator.core.maven

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Service(Service.Level.PROJECT)
class MavenBuildService(private val project: Project) {

    suspend fun runBuild(
        goals: String,
        modules: List<String> = emptyList(),
        offline: Boolean = false
    ): MavenBuildResult = withContext(Dispatchers.IO) {
        val detector = MavenModuleDetector(project)
        val args = detector.buildMavenArgs(modules, goals)
        val allArgs = if (offline) args + "-o" else args

        val executable = detectMavenExecutable()
        val commandLine = GeneralCommandLine(
            listOf(executable) + allArgs
        ).withWorkDirectory(project.basePath)
            .withEnvironment(sanitizedEnvironment())

        val handler = OSProcessHandler(commandLine)
        val output = StringBuilder()
        val errors = StringBuilder()

        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                when (outputType) {
                    ProcessOutputTypes.STDOUT -> output.append(event.text)
                    ProcessOutputTypes.STDERR -> errors.append(event.text)
                }
            }
        })

        handler.startNotify()
        val timeoutMs = PluginSettings.getInstance(project).state.healthCheckTimeoutSeconds * 1000L
        val completed = handler.waitFor(timeoutMs)

        if (!completed) {
            handler.destroyProcess()
        }

        MavenBuildResult(
            success = completed && handler.exitCode == 0,
            exitCode = handler.exitCode ?: -1,
            output = output.toString(),
            errors = errors.toString(),
            timedOut = !completed
        )
    }

    /**
     * Primary path: uses IntelliJ's MavenRunner for IDE-integrated builds.
     * Reuses IDE's Maven installation, settings, and local repository cache.
     */
    suspend fun runBuildViaRunner(
        goals: String,
        modules: List<String> = emptyList()
    ): MavenBuildResult = withContext(Dispatchers.IO) {
        val mavenManager = try {
            org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
                .takeIf { it.isMavenizedProject }
        } catch (_: Exception) { null }

        if (mavenManager == null) {
            return@withContext runBuild(goals, modules)
        }

        try {
            val params = org.jetbrains.idea.maven.execution.MavenRunnerParameters(
                true,
                project.basePath!!,
                "pom.xml",
                goals.split(" "),
                emptyList()
            )

            val settings = org.jetbrains.idea.maven.execution.MavenRunner.getInstance(project).settings.clone()
            val startTime = System.currentTimeMillis()

            // MavenRunner.run() integrates with IntelliJ's build tool window
            val completionFuture = java.util.concurrent.CompletableFuture<Boolean>()
            org.jetbrains.idea.maven.execution.MavenRunner.getInstance(project).run(params, settings) {
                completionFuture.complete(true)
            }

            val timeoutMs = PluginSettings.getInstance(project).state.healthCheckTimeoutSeconds * 1000L
            val completed = try {
                completionFuture.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                false
            }

            MavenBuildResult(
                success = completed,
                exitCode = if (completed) 0 else -1,
                output = "Build executed via MavenRunner (IDE-integrated)",
                errors = "",
                timedOut = !completed
            )
        } catch (e: Exception) {
            // Fallback to subprocess if MavenRunner fails
            runBuild(goals, modules)
        }
    }

    fun buildCommandLine(goals: String, modules: List<String> = emptyList()): GeneralCommandLine {
        val detector = MavenModuleDetector(project)
        val args = detector.buildMavenArgs(modules, goals)
        val executable = detectMavenExecutable()
        return GeneralCommandLine(listOf(executable) + args)
            .withWorkDirectory(project.basePath)
            .withEnvironment(sanitizedEnvironment())
    }

    private fun sanitizedEnvironment(): Map<String, String> {
        val sensitivePattern = Regex("(?i).*(TOKEN|KEY|SECRET|PASSWORD|CREDENTIAL|AWS_|GITHUB_|GITLAB_|NPM_|DOCKER_).*")
        return System.getenv().filterKeys { !sensitivePattern.matches(it) }
    }

    internal fun detectMavenExecutable(): String {
        val basePath = project.basePath
        if (basePath != null) {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val wrapperName = if (isWindows) "mvnw.cmd" else "mvnw"
            val wrapper = File(basePath, wrapperName)
            if (wrapper.exists() && wrapper.canExecute()) {
                return wrapper.absolutePath
            }
        }

        val mavenHome = System.getenv("MAVEN_HOME") ?: System.getenv("M2_HOME")
        if (mavenHome != null) {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val mvnName = if (isWindows) "mvn.cmd" else "mvn"
            val mvnBin = File(File(mavenHome, "bin"), mvnName)
            if (mvnBin.exists()) return mvnBin.absolutePath
        }

        return if (System.getProperty("os.name").lowercase().contains("win")) "mvn.cmd" else "mvn"
    }

    companion object {
        fun getInstance(project: Project): MavenBuildService =
            project.getService(MavenBuildService::class.java)
    }
}

data class MavenBuildResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String,
    val errors: String,
    val timedOut: Boolean = false
)
