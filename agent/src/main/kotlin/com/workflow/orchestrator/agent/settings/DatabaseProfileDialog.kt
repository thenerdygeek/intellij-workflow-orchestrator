package com.workflow.orchestrator.agent.settings

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.workflow.orchestrator.agent.database.DatabaseCredentialHelper
import com.workflow.orchestrator.agent.database.DatabaseProfile
import com.workflow.orchestrator.agent.database.DbType
import java.awt.Dimension
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Add / edit dialog for a single [DatabaseProfile].
 * On OK: saves metadata fields to the returned profile and stores the password in PasswordSafe.
 */
class DatabaseProfileDialog(
    private val existing: DatabaseProfile? = null
) : DialogWrapper(true) {

    private val idField = JBTextField(existing?.id ?: "").apply { preferredSize = Dimension(200, 28) }
    private val nameField = JBTextField(existing?.displayName ?: "").apply { preferredSize = Dimension(200, 28) }
    private val typeCombo = JComboBox(DbType.entries.toTypedArray()).apply {
        selectedItem = existing?.dbType ?: DbType.POSTGRESQL
    }
    private val urlField = JBTextField(existing?.jdbcUrl ?: "jdbc:postgresql://localhost:5432/mydb").apply {
        preferredSize = Dimension(360, 28)
    }
    private val userField = JBTextField(existing?.username ?: "").apply { preferredSize = Dimension(200, 28) }
    private val passField = JBPasswordField().apply { preferredSize = Dimension(200, 28) }
    private val passHint = JBLabel(if (existing != null) "(leave blank to keep existing password)" else "")

    init {
        title = if (existing != null) "Edit Database Profile" else "Add Database Profile"
        init()
        // Pre-fill password hint
        if (existing != null) {
            val stored = DatabaseCredentialHelper.getPassword(existing.id)
            if (!stored.isNullOrEmpty()) passField.text = stored
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Profile ID:") {
            cell(idField).align(AlignX.FILL).comment("Short slug, e.g. local, docker, qa (used as the key)")
        }
        row("Display name:") {
            cell(nameField).align(AlignX.FILL).comment("Human-readable name shown in agent output")
        }
        row("Database type:") {
            cell(typeCombo)
        }
        row("JDBC URL:") {
            cell(urlField).align(AlignX.FILL)
                .comment("e.g. jdbc:postgresql://host:5432/dbname  |  jdbc:mysql://host:3306/dbname")
        }
        row("Username:") {
            cell(userField).align(AlignX.FILL)
        }
        row("Password:") {
            cell(passField).align(AlignX.FILL)
            cell(passHint)
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (idField.text.isBlank()) return ValidationInfo("Profile ID is required", idField)
        if (!idField.text.matches(Regex("[a-zA-Z0-9_-]+")))
            return ValidationInfo("Profile ID may only contain letters, numbers, hyphens, underscores", idField)
        if (nameField.text.isBlank()) return ValidationInfo("Display name is required", nameField)
        if (urlField.text.isBlank()) return ValidationInfo("JDBC URL is required", urlField)
        if (userField.text.isBlank()) return ValidationInfo("Username is required", userField)
        if (existing == null && passField.password.isEmpty())
            return ValidationInfo("Password is required", passField)
        return null
    }

    /**
     * Returns the resulting [DatabaseProfile] after OK is pressed.
     * Also persists the password to PasswordSafe.
     */
    fun buildProfile(): DatabaseProfile {
        val profile = DatabaseProfile(
            id = idField.text.trim(),
            displayName = nameField.text.trim(),
            dbType = typeCombo.selectedItem as DbType,
            jdbcUrl = urlField.text.trim(),
            username = userField.text.trim(),
        )
        val pw = String(passField.password)
        if (pw.isNotEmpty()) {
            DatabaseCredentialHelper.storePassword(profile.id, pw)
        }
        return profile
    }
}
