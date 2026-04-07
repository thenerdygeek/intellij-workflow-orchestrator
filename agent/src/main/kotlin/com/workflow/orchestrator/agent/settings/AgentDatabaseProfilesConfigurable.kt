package com.workflow.orchestrator.agent.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.workflow.orchestrator.agent.tools.database.DatabaseCredentialHelper
import com.workflow.orchestrator.agent.tools.database.DatabaseProfile
import com.workflow.orchestrator.agent.tools.database.DatabaseSettings
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent

/**
 * "AI Agent → Database Profiles" sub-page.
 *
 * Lets the user add / edit / remove the read-only database connections that
 * power the agent's `db_query` and `db_schema` tools. The profile list itself
 * is a plain Swing `JBList` + `ToolbarDecorator`, so modification tracking
 * here cannot rely on the [com.intellij.openapi.ui.DialogPanel] machinery —
 * we diff against [DatabaseSettings.getProfiles] by hand in [isModified].
 *
 * This page does not need a connection banner because database profiles have
 * their own credentials stored via [DatabaseCredentialHelper] / PasswordSafe.
 */
class AgentDatabaseProfilesConfigurable(
    private val project: Project
) : SearchableConfigurable {

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    private val dbSettings = DatabaseSettings.getInstance(project)
    private val dbProfileModel = DefaultListModel<DatabaseProfile>()

    override fun getId(): String = "workflow.orchestrator.agent.database_profiles"
    override fun getDisplayName(): String = "Database Profiles"

    override fun createComponent(): JComponent {
        val innerPanel = panel {
            group("Database Profiles") {
                row {
                    comment(
                        "Read-only database connections for the agent's db_query and db_schema tools. " +
                            "Passwords are stored in the system keychain via PasswordSafe."
                    )
                }
                row {
                    cell(buildDatabaseProfilePanel())
                        .align(Align.FILL)
                        .resizableColumn()
                }.resizableRow()
            }
        }
        dialogPanel = innerPanel

        // Populate profile list after panel is built
        dbProfileModel.clear()
        dbSettings.getProfiles().forEach { dbProfileModel.addElement(it) }

        return innerPanel
    }

    private fun buildDatabaseProfilePanel(): JComponent {
        val list = JBList(dbProfileModel).apply {
            cellRenderer = SimpleListCellRenderer.create("") { p ->
                "${p.displayName}  [${p.dbType.displayName}]  ${p.jdbcUrl}"
            }
            preferredSize = Dimension(600, 120)
        }

        return ToolbarDecorator.createDecorator(list)
            .setAddAction {
                val dlg = DatabaseProfileDialog()
                if (dlg.showAndGet()) {
                    val profile = dlg.buildProfile()
                    // Remove any existing entry with the same id
                    val existing = (0 until dbProfileModel.size).firstOrNull {
                        dbProfileModel.getElementAt(it).id == profile.id
                    }
                    if (existing != null) dbProfileModel.set(existing, profile)
                    else dbProfileModel.addElement(profile)
                }
            }
            .setEditAction {
                val idx = list.selectedIndex
                if (idx >= 0) {
                    val dlg = DatabaseProfileDialog(existing = dbProfileModel.getElementAt(idx))
                    if (dlg.showAndGet()) {
                        dbProfileModel.set(idx, dlg.buildProfile())
                    }
                }
            }
            .setRemoveAction {
                val idx = list.selectedIndex
                if (idx >= 0) {
                    val profile = dbProfileModel.getElementAt(idx)
                    DatabaseCredentialHelper.removePassword(profile.id)
                    dbProfileModel.remove(idx)
                }
            }
            .createPanel()
    }

    private fun currentProfiles(): List<DatabaseProfile> =
        (0 until dbProfileModel.size).map { dbProfileModel.getElementAt(it) }

    override fun isModified(): Boolean {
        val panelModified = dialogPanel?.isModified() ?: false
        return panelModified || currentProfiles() != dbSettings.getProfiles()
    }

    override fun apply() {
        dialogPanel?.apply()
        dbSettings.saveProfiles(currentProfiles())
    }

    override fun reset() {
        dialogPanel?.reset()
        dbProfileModel.clear()
        dbSettings.getProfiles().forEach { dbProfileModel.addElement(it) }
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }
}
