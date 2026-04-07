package com.workflow.orchestrator.core.healthcheck.checks

import com.workflow.orchestrator.core.copyright.CopyrightCheckService
import com.workflow.orchestrator.core.settings.PluginSettings

class CopyrightCheck : HealthCheck {
    override val id = "copyright"
    override val displayName = "Copyright Headers"
    override val order = 30

    override fun isEnabled(settings: PluginSettings.State): Boolean =
        settings.healthCheckCopyrightEnabled && !settings.copyrightHeaderPattern.isNullOrBlank()

    override suspend fun execute(context: HealthCheckContext): HealthCheck.CheckResult {
        val service = CopyrightCheckService(context.project)
        val result = service.checkFiles(context.changedFiles)

        return HealthCheck.CheckResult(
            passed = result.passed,
            message = if (result.passed) "All files have copyright headers"
                     else "${result.violations.size} file(s) missing copyright header"
        )
    }
}
