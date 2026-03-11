package com.workflow.orchestrator.core.healthcheck.checks

import com.workflow.orchestrator.core.maven.MavenBuildService
import com.workflow.orchestrator.core.maven.MavenModuleDetector
import com.workflow.orchestrator.core.settings.PluginSettings

class MavenTestCheck : HealthCheck {
    override val id = "maven-test"
    override val displayName = "Maven Test"
    override val order = 20

    override fun isEnabled(settings: PluginSettings.State): Boolean =
        settings.healthCheckTestEnabled

    override suspend fun execute(context: HealthCheckContext): HealthCheck.CheckResult {
        val project = context.project
        val detector = MavenModuleDetector(project)
        val modules = detector.detectChangedModules(context.changedFiles)

        val result = MavenBuildService.getInstance(project)
            .runBuild("test", modules)

        return if (result.success) {
            HealthCheck.CheckResult(passed = true, message = "Maven tests passed")
        } else if (result.timedOut) {
            HealthCheck.CheckResult(passed = false, message = "Maven test timed out")
        } else {
            HealthCheck.CheckResult(
                passed = false,
                message = "Maven tests failed (exit code ${result.exitCode})",
                details = result.errors.lines().filter { it.contains("[ERROR]") || it.contains("FAILURE") }
            )
        }
    }
}
