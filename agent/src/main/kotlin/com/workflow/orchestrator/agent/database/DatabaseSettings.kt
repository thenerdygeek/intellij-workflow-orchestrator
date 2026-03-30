package com.workflow.orchestrator.agent.database

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Project-level persistent store for database connection profiles.
 * Profile metadata (URL, username, DB type) is stored in workflowDatabases.xml.
 * Passwords are stored separately in PasswordSafe via [DatabaseCredentialHelper].
 */
@Service(Service.Level.PROJECT)
@State(
    name = "DatabaseSettings",
    storages = [Storage("workflowDatabases.xml")]
)
class DatabaseSettings : SimplePersistentStateComponent<DatabaseSettings.State>(State()) {

    class State : BaseState() {
        // Serialised as a JSON array of DatabaseProfile objects.
        // Using a single string field avoids IntelliJ's complex XML list serialisation.
        var profilesJson by string("[]")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun getProfiles(): List<DatabaseProfile> = try {
        json.decodeFromString<List<DatabaseProfile>>(state.profilesJson ?: "[]")
    } catch (_: Exception) {
        emptyList()
    }

    fun saveProfiles(profiles: List<DatabaseProfile>) {
        state.profilesJson = json.encodeToString(profiles)
    }

    fun getProfile(id: String): DatabaseProfile? = getProfiles().firstOrNull { it.id == id }

    companion object {
        fun getInstance(project: Project): DatabaseSettings =
            project.getService(DatabaseSettings::class.java)
    }
}
