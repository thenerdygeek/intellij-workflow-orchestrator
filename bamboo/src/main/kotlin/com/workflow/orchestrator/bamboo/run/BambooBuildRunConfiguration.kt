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

    /**
     * Comma-separated stage names to restrict this run to, or blank/null to run all stages.
     * e.g., `"Build,Unit Tests"` → posts `stages_Build=true&stages_Unit+Tests=true` to the
     * Bamboo action endpoint via [BambooApiClient.queueBuildWithStageSelection] (C-faithful).
     * Stored as a plain comma-separated string for simple XML persistence.
     */
    private val _stages = string("")
    var stages: String?
        get() = _stages.getValue(this)
        set(value) { _stages.setValue(this, value) }
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

    fun getPlanKey(): String =
        options.planKey.orEmpty().ifBlank {
            PluginSettings.getInstance(project).state.bambooPlanKey.orEmpty()
        }

    fun getBranch(): String =
        options.branch.orEmpty().ifBlank {
            try {
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

    /**
     * Parses [stages] option (comma-separated) into a [Set<String>?].
     * Returns null when blank (run all stages) or non-null set of stage names.
     */
    fun getStages(): Set<String>? {
        val raw = options.stages.orEmpty().trim()
        if (raw.isBlank()) return null
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet().takeIf { it.isNotEmpty() }
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

    var stagesValue: String
        get() = options.stages.orEmpty()
        set(value) { options.stages = value }
}

class BambooBuildSettingsEditor(private val project: Project) : SettingsEditor<BambooBuildRunConfiguration>() {

    private var planKey: String = ""
    private var branch: String = ""
    private var buildVariables: String = ""
    /**
     * Comma-separated stage names to run, or blank to run all stages.
     * Example: `"Build,Unit Tests"` runs only those two stages.
     */
    private var stages: String = ""
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
            row("Stages (optional):") {
                textField()
                    .bindText(::stages)
                    .comment("Comma-separated stage names to run, e.g. \"Build,Unit Tests\". Leave blank to run all stages.")
            }
        }
        return myPanel
    }

    override fun applyEditorTo(s: BambooBuildRunConfiguration) {
        myPanel.apply()
        s.planKeyValue = planKey
        s.branchValue = branch
        s.buildVariablesValue = buildVariables
        s.stagesValue = stages
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
        stages = s.stagesValue
        myPanel.reset()
    }
}
