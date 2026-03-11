package com.workflow.orchestrator.core.healthcheck.checks

import com.workflow.orchestrator.core.maven.MavenBuildService
import com.workflow.orchestrator.core.maven.MavenModuleDetector
import com.workflow.orchestrator.core.settings.PluginSettings

class MavenCompileCheck : HealthCheck {
    override val id = "maven-compile"
    override val displayName = "Maven Compile"
    override val order = 10

    override fun isEnabled(settings: PluginSettings.State): Boolean =
        settings.healthCheckCompileEnabled

    override suspend fun execute(context: HealthCheckContext): HealthCheck.CheckResult {
        val project = context.project
        val detector = MavenModuleDetector(project)
        val modules = detector.detectChangedModules(context.changedFiles)

        val result = MavenBuildService.getInstance(project)
            .runBuild("compile", modules)

        return if (result.success) {
            HealthCheck.CheckResult(passed = true, message = "Maven compile passed")
        } else if (result.timedOut) {
            HealthCheck.CheckResult(passed = false, message = "Maven compile timed out")
        } else {
            HealthCheck.CheckResult(
                passed = false,
                message = "Maven compile failed (exit code ${result.exitCode})",
                details = result.errors.lines().filter { it.contains("[ERROR]") }
            )
        }
    }
}
