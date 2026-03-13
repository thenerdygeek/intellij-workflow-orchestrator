package com.workflow.orchestrator.jira.tasks

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.tasks.TaskRepository
import com.intellij.tasks.TaskRepositoryType
import com.intellij.tasks.config.BaseRepositoryEditor
import com.intellij.tasks.config.TaskRepositoryEditor
import com.intellij.util.Consumer
import javax.swing.Icon

/**
 * Registers "Jira (Workflow)" in Tools > Tasks > Servers.
 *
 * This is separate from IntelliJ's built-in Jira integration
 * because it uses Bearer PAT authentication and integrates
 * with the Workflow Orchestrator plugin's settings.
 */
class JiraTaskRepositoryType : TaskRepositoryType<JiraTaskRepository>() {

    override fun getName(): String = "Jira (Workflow)"

    override fun getIcon(): Icon = AllIcons.Nodes.Tag

    override fun createRepository(): TaskRepository = JiraTaskRepository(this)

    override fun getRepositoryClass(): Class<JiraTaskRepository> = JiraTaskRepository::class.java

    override fun createEditor(
        repository: JiraTaskRepository,
        project: Project,
        changeListener: Consumer<in JiraTaskRepository>
    ): TaskRepositoryEditor {
        return BaseRepositoryEditor(project, repository, changeListener)
    }
}
