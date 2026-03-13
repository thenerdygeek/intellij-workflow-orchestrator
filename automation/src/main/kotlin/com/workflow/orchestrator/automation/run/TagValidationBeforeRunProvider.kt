package com.workflow.orchestrator.automation.run

import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.icons.AllIcons
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.swing.Icon

class TagValidationBeforeRunTask : com.intellij.execution.BeforeRunTask<TagValidationBeforeRunTask>(
    TagValidationBeforeRunProvider.ID
)

class TagValidationBeforeRunProvider : BeforeRunTaskProvider<TagValidationBeforeRunTask>() {

    companion object {
        val ID = Key.create<TagValidationBeforeRunTask>("WorkflowTagValidation")
    }

    private val log = Logger.getInstance(TagValidationBeforeRunProvider::class.java)

    override fun getId(): Key<TagValidationBeforeRunTask> = ID
    override fun getName(): String = "Validate Docker Tags"
    override fun getIcon(): Icon = AllIcons.Actions.Checked
    override fun getDescription(task: TagValidationBeforeRunTask): String = "Verify Docker tag existence in Nexus"

    override fun createTask(runConfiguration: RunConfiguration): TagValidationBeforeRunTask {
        return TagValidationBeforeRunTask().apply { isEnabled = false }
    }

    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        environment: ExecutionEnvironment,
        task: TagValidationBeforeRunTask
    ): Boolean {
        val project = configuration.project
        val settings = PluginSettings.getInstance(project)
        val registryUrl = settings.state.nexusUrl.orEmpty().trimEnd('/')
        if (registryUrl.isBlank()) {
            log.warn("[Automation:TagValidation] No registry URL configured")
            return false
        }

        // Try to get docker tags from run configuration
        val buildVariables = try {
            val options = configuration.javaClass.getMethod("getOptions").invoke(configuration)
            options.javaClass.getMethod("getBuildVariables").invoke(options) as? String ?: ""
        } catch (e: Exception) {
            return true // Not a Bamboo run config, skip
        }

        val dockerTagsJson = TagValidationLogic.extractDockerTagsJson(buildVariables)
        if (dockerTagsJson.isBlank()) return true

        val tags = TagValidationLogic.parseDockerTags(dockerTagsJson)
        if (tags.isEmpty()) return true

        val credentialStore = CredentialStore()
        val nexusUsername = settings.state.nexusUsername.orEmpty()
        val nexusPassword = credentialStore.getNexusPassword() ?: ""

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val missingTags = mutableListOf<String>()

        for ((service, tag) in tags) {
            val url = TagValidationLogic.buildManifestUrl(registryUrl, service, tag)
            val request = Request.Builder()
                .url(url)
                .head()
                .addHeader("Authorization", Credentials.basic(nexusUsername, nexusPassword))
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                response.use {
                    if (it.code == 404) missingTags.add("$service:$tag")
                }
            } catch (e: Exception) {
                log.warn("[Automation:TagValidation] Error checking $service:$tag: ${e.message}")
                missingTags.add("$service:$tag (connection error)")
            }
        }

        if (missingTags.isNotEmpty()) {
            log.warn("[Automation:TagValidation] Missing tags: $missingTags")
            return false
        }
        return true
    }
}

/** Pure logic — testable without IntelliJ dependencies. */
object TagValidationLogic {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseDockerTags(jsonString: String): Map<String, String> {
        if (jsonString.isBlank()) return emptyMap()
        return try {
            val obj = json.decodeFromString<JsonObject>(jsonString)
            obj.mapValues { it.value.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun extractDockerTagsJson(buildVariables: String): String {
        if (buildVariables.isBlank()) return ""
        return try {
            val obj = json.decodeFromString<JsonObject>(buildVariables)
            obj["dockerTagsAsJson"]?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun buildManifestUrl(registryUrl: String, imageName: String, tag: String): String {
        return "${registryUrl.trimEnd('/')}/v2/$imageName/manifests/$tag"
    }
}
