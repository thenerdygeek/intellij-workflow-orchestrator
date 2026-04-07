package com.workflow.orchestrator.agent.settings

import com.intellij.openapi.application.EDT
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    // Default database is now a dropdown populated by Test Connection.
    // For new profiles it starts disabled and empty. For existing profiles
    // (edit flow) it is grandfathered: pre-seeded with the saved DB and
    // enabled, so the user can save without re-testing.
    private val defaultDbCombo: com.intellij.openapi.ui.ComboBox<String> =
        com.intellij.openapi.ui.ComboBox<String>().apply {
            preferredSize = Dimension(200, 28)
            val seed = existing?.resolvedDefaultDatabase?.takeIf { it.isNotBlank() }
            if (seed != null) {
                addItem(seed)
                selectedItem = seed
                isEnabled = true
            } else {
                isEnabled = false
            }
        }

    // Test Connection UI
    private val testButton = javax.swing.JButton("Test Connection")
    private val testStatusLabel = JBLabel("").apply {
        // Default neutral colour; success/error update to green/red on click.
        foreground = com.intellij.ui.JBColor.GRAY
    }

    // Coroutine scope owned by the dialog — cancelled in dispose() so an
    // in-flight test connection can't outlive the dialog. Base dispatcher
    // is EDT so UI updates after `withContext(Dispatchers.IO)` blocks
    // return directly to the EDT without another explicit switch.
    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

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

    // True when the user has successfully tested the connection, OR when editing
    // an existing profile (grandfathered — assumed valid until they change a
    // connection-affecting field). Gates the OK button.
    private var testPassed: Boolean = (existing != null)

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
        // The combo's enable state is also driven by Test Connection results
        // (set in the click handler). Raw mode just hides it entirely.
        defaultDbCombo.isEnabled = !rawMode && (testPassed || existing != null)
        urlField.isEnabled = rawMode
    }

    private fun buildPreviewUrl(): String {
        val type = typeCombo.selectedItem as DbType
        if (type == DbType.SQLITE) return "jdbc:sqlite:/path/to/database.db"
        if (type == DbType.GENERIC) return ""
        val host = hostField.text.ifBlank { "localhost" }
        val port = portField.text.toIntOrNull() ?: 0
        val db = (defaultDbCombo.selectedItem as? String)?.ifBlank { "mydb" } ?: "mydb"
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
            cell(defaultDbCombo).align(AlignX.FILL).comment(
                "Click 'Test Connection' below to discover and select a database. " +
                    "The agent uses this when no explicit database is supplied to a query, " +
                    "and can still switch databases per-query via db_query(database=…)."
            )
        }

        row {
            cell(testButton)
        }
        row {
            cell(testStatusLabel).align(AlignX.FILL)
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
            val selected = defaultDbCombo.selectedItem as? String
            if (selected.isNullOrBlank())
                return ValidationInfo(
                    "Select a database from the list (click Test Connection first)",
                    defaultDbCombo
                )
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
                defaultDatabase = (defaultDbCombo.selectedItem as? String)?.trim().orEmpty(),
            )
        }

        val pw = String(passField.password)
        if (pw.isNotEmpty()) {
            DatabaseCredentialHelper.storePassword(profile.id, pw)
        }
        return profile
    }

    override fun dispose() {
        dialogScope.cancel()
        super.dispose()
    }
}
