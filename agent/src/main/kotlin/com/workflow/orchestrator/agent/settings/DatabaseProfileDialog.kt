package com.workflow.orchestrator.agent.settings

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.workflow.orchestrator.agent.tools.database.DatabaseCredentialHelper
import com.workflow.orchestrator.agent.tools.database.DatabaseProfile
import com.workflow.orchestrator.agent.tools.database.DbType
import com.workflow.orchestrator.agent.tools.database.JdbcUrlBuilder
import java.awt.Dimension
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Add / edit dialog for a single [DatabaseProfile].
 *
 * **Two input modes** depending on the engine type:
 *
 *  - **Server-based** (PostgreSQL / MySQL / SQL Server): the user fills in
 *    *Host*, *Port*, *Default database*, and the dialog assembles the JDBC URL
 *    on save. One profile = one server, and the LLM can switch databases
 *    per-query via `db_query(profile=…, database=…)`.
 *
 *  - **File-based or escape-hatch** (SQLite / Generic JDBC, or any user that
 *    needs custom URL parameters): the user fills in *JDBC URL* directly. The
 *    "Use raw JDBC URL" checkbox forces this mode for server engines too.
 *
 * On OK: saves metadata fields to the returned profile and stores the password
 * in PasswordSafe.
 */
class DatabaseProfileDialog(
    private val existing: DatabaseProfile? = null
) : DialogWrapper(true) {

    private val idField = JBTextField(existing?.id ?: "").apply { preferredSize = Dimension(200, 28) }
    private val nameField = JBTextField(existing?.displayName ?: "").apply { preferredSize = Dimension(200, 28) }
    private val typeCombo = JComboBox(DbType.entries.toTypedArray()).apply {
        selectedItem = existing?.dbType ?: DbType.POSTGRESQL
    }

    // Structured fields (server-based engines)
    private val hostField = JBTextField(existing?.resolvedHost ?: "localhost").apply {
        preferredSize = Dimension(200, 28)
    }
    private val portField = JBTextField(
        // Show explicit port if set; otherwise show the engine default for the selected type
        (existing?.resolvedPort?.takeIf { it > 0 } ?: JdbcUrlBuilder.defaultPort(
            (existing?.dbType ?: DbType.POSTGRESQL)
        )).toString()
    ).apply { preferredSize = Dimension(80, 28) }
    private val defaultDbField = JBTextField(existing?.resolvedDefaultDatabase ?: "").apply {
        preferredSize = Dimension(200, 28)
    }

    // Raw URL field (SQLite, Generic, or escape hatch for server engines)
    private val urlField = JBTextField(existing?.jdbcUrl ?: "").apply {
        preferredSize = Dimension(360, 28)
    }

    // Escape hatch — when checked, only the raw URL field is used (server engines included)
    private val rawUrlCheckbox = JCheckBox(
        "Use raw JDBC URL (advanced — bypasses host/port/database fields)",
        // Default to raw mode for SQLite/Generic, or for legacy profiles where the parser failed
        (existing?.dbType?.let { it == DbType.SQLITE || it == DbType.GENERIC } == true) ||
            (existing?.host.isNullOrBlank() && existing?.jdbcUrl?.isNotBlank() == true &&
                JdbcUrlBuilder.parse(existing.dbType, existing.jdbcUrl) == null)
    )

    private val userField = JBTextField(existing?.username ?: "").apply { preferredSize = Dimension(200, 28) }
    private val passField = JBPasswordField().apply { preferredSize = Dimension(200, 28) }
    private val passHint = JBLabel(if (existing != null) "(leave blank to keep existing password)" else "")

    init {
        title = if (existing != null) "Edit Database Profile" else "Add Database Profile"
        init()
        // Pre-fill password
        if (existing != null) {
            val stored = DatabaseCredentialHelper.getPassword(existing.id)
            if (!stored.isNullOrEmpty()) passField.text = stored
        }

        // Populate the raw URL field with what would have been built (helpful when
        // toggling the checkbox the user can see and edit the equivalent URL).
        if (urlField.text.isBlank()) {
            urlField.text = buildPreviewUrl()
        }

        // Wire dependent UI updates
        typeCombo.addActionListener { onTypeChanged() }
        rawUrlCheckbox.addActionListener { updateFieldVisibility() }
        updateFieldVisibility()
    }

    private fun onTypeChanged() {
        val type = typeCombo.selectedItem as DbType
        // SQLite/Generic must use raw URL — force the checkbox on and disable it
        if (type == DbType.SQLITE || type == DbType.GENERIC) {
            rawUrlCheckbox.isSelected = true
            rawUrlCheckbox.isEnabled = false
        } else {
            rawUrlCheckbox.isEnabled = true
            // For server engines, default port jumps to the engine default if the user
            // hasn't typed a custom one
            val defaultPort = JdbcUrlBuilder.defaultPort(type)
            if (portField.text.isBlank() || portField.text.toIntOrNull() in
                listOf(5432, 3306, 1433)
            ) {
                portField.text = defaultPort.toString()
            }
        }
        updateFieldVisibility()
    }

    private fun updateFieldVisibility() {
        val rawMode = rawUrlCheckbox.isSelected
        hostField.isEnabled = !rawMode
        portField.isEnabled = !rawMode
        defaultDbField.isEnabled = !rawMode
        urlField.isEnabled = rawMode
    }

    private fun buildPreviewUrl(): String {
        val type = typeCombo.selectedItem as DbType
        if (type == DbType.SQLITE) return "jdbc:sqlite:/path/to/database.db"
        if (type == DbType.GENERIC) return ""
        val host = hostField.text.ifBlank { "localhost" }
        val port = portField.text.toIntOrNull() ?: 0
        val db = defaultDbField.text.ifBlank { "mydb" }
        return JdbcUrlBuilder.build(type, host, port, db)
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

        // Structured fields — visible for all engines but disabled when raw URL mode is on
        row("Host:") {
            cell(hostField).align(AlignX.FILL).comment("Server hostname, e.g. localhost or db.example.com")
        }
        row("Port:") {
            cell(portField).comment("Defaults to engine standard if blank (5432 / 3306 / 1433)")
        }
        row("Default database:") {
            cell(defaultDbField).align(AlignX.FILL).comment(
                "Database to connect to when no `database` parameter is supplied. " +
                    "The agent can switch via db_list_databases + db_query(database=…)."
            )
        }

        row {
            cell(rawUrlCheckbox)
        }
        row("JDBC URL:") {
            cell(urlField).align(AlignX.FILL).comment(
                "Raw URL — required for SQLite (e.g. jdbc:sqlite:/path/to/file.db), " +
                    "Generic JDBC, or when you need custom connection params."
            )
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
        if (userField.text.isBlank()) return ValidationInfo("Username is required", userField)

        if (rawUrlCheckbox.isSelected) {
            if (urlField.text.isBlank()) return ValidationInfo("JDBC URL is required in raw mode", urlField)
            if (!urlField.text.startsWith("jdbc:"))
                return ValidationInfo("JDBC URL must start with 'jdbc:'", urlField)
        } else {
            if (hostField.text.isBlank()) return ValidationInfo("Host is required", hostField)
            val port = portField.text.toIntOrNull()
            if (port == null || port <= 0 || port > 65535)
                return ValidationInfo("Port must be a number between 1 and 65535", portField)
            if (defaultDbField.text.isBlank())
                return ValidationInfo("Default database is required", defaultDbField)
        }

        if (existing == null && passField.password.isEmpty())
            return ValidationInfo("Password is required", passField)
        return null
    }

    /**
     * Returns the resulting [DatabaseProfile] after OK is pressed.
     * Also persists the password to PasswordSafe.
     */
    fun buildProfile(): DatabaseProfile {
        val type = typeCombo.selectedItem as DbType
        val rawMode = rawUrlCheckbox.isSelected

        val profile = if (rawMode) {
            DatabaseProfile(
                id = idField.text.trim(),
                displayName = nameField.text.trim(),
                dbType = type,
                username = userField.text.trim(),
                jdbcUrl = urlField.text.trim(),
            )
        } else {
            DatabaseProfile(
                id = idField.text.trim(),
                displayName = nameField.text.trim(),
                dbType = type,
                username = userField.text.trim(),
                host = hostField.text.trim(),
                port = portField.text.toIntOrNull() ?: JdbcUrlBuilder.defaultPort(type),
                defaultDatabase = defaultDbField.text.trim(),
            )
        }

        val pw = String(passField.password)
        if (pw.isNotEmpty()) {
            DatabaseCredentialHelper.storePassword(profile.id, pw)
        }
        return profile
    }
}
