package com.workflow.orchestrator.bamboo.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.workflow.orchestrator.core.model.DockerTagsProvider
import com.workflow.orchestrator.core.settings.PluginSettings
import git4idea.repo.GitRepositoryManager
import javax.swing.JComponent

class BambooBuildRunConfigurationOptions : RunConfigurationOptions() {
    private val _planKey = string("")
    var planKey: String?
        get() = _planKey.getValue(this)
        set(value) { _planKey.setValue(this, value) }

    private val _branch = string("")
    var branch: String?
        get() = _branch.getValue(this)
        set(value) { _branch.setValue(this, value) }

    private val _buildVariables = string("")
    var buildVariables: String?
        get() = _buildVariables.getValue(this)
        set(value) { _buildVariables.setValue(this, value) }
}

class BambooBuildRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<BambooBuildRunConfigurationOptions>(project, factory, name), DockerTagsProvider {

    override fun getOptions(): BambooBuildRunConfigurationOptions {
        return super.getOptions() as BambooBuildRunConfigurationOptions
    }

    override fun getOptionsClass(): Class<out RunConfigurationOptions> =
        BambooBuildRunConfigurationOptions::class.java

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        BambooBuildSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        BambooBuildRunState(environment, this)

    fun getPlanKey(): String {
        val configured = options.planKey.orEmpty()
        if (configured.isNotBlank()) return configured
        return PluginSettings.getInstance(project).state.bambooPlanKey.orEmpty()
    }

    fun getBranch(): String {
        val configured = options.branch.orEmpty()
        if (configured.isNotBlank()) return configured
        return try {
            GitRepositoryManager.getInstance(project).repositories
                .firstOrNull()?.currentBranchName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun getBuildVariables(): Map<String, String> {
        val raw = options.buildVariables.orEmpty().trim()
        if (raw.isBlank()) return emptyMap()
        return raw.lines()
            .filter { it.contains("=") }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
    }

    override fun getDockerTagsJson(): String = options.buildVariables.orEmpty()

    // Public accessors for the settings editor
    var planKeyValue: String
        get() = options.planKey.orEmpty()
        set(value) { options.planKey = value }

    var branchValue: String
        get() = options.branch.orEmpty()
        set(value) { options.branch = value }

    var buildVariablesValue: String
        get() = options.buildVariables.orEmpty()
        set(value) { options.buildVariables = value }
}

class BambooBuildSettingsEditor(private val project: Project) : SettingsEditor<BambooBuildRunConfiguration>() {

    private var planKey: String = ""
    private var branch: String = ""
    private var buildVariables: String = ""
    private lateinit var myPanel: DialogPanel

    override fun createEditor(): JComponent {
        myPanel = panel {
            row("Plan Key:") {
                textField()
                    .bindText(::planKey)
                    .comment("e.g., PROJ-PLAN")
            }
            row("Branch:") {
                textField()
                    .bindText(::branch)
                    .comment("Leave blank for current Git branch")
            }
            row("Build Variables:") {
                textArea()
                    .bindText(::buildVariables)
                    .rows(4)
                    .comment("One per line: KEY=value")
            }
        }
        return myPanel
    }

    override fun applyEditorTo(s: BambooBuildRunConfiguration) {
        myPanel.apply()
        s.planKeyValue = planKey
        s.branchValue = branch
        s.buildVariablesValue = buildVariables
    }

    override fun resetEditorFrom(s: BambooBuildRunConfiguration) {
        planKey = s.planKeyValue.ifBlank {
            PluginSettings.getInstance(project).state.bambooPlanKey.orEmpty()
        }
        branch = s.branchValue.ifBlank {
            try {
                GitRepositoryManager.getInstance(project).repositories
                    .firstOrNull()?.currentBranchName ?: ""
            } catch (_: Exception) { "" }
        }
        buildVariables = s.buildVariablesValue
        myPanel.reset()
    }
}
