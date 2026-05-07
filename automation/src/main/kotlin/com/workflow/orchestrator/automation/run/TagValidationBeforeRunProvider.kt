package com.workflow.orchestrator.automation.run

import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.icons.AllIcons
import com.workflow.orchestrator.automation.service.TagValidationService
import com.workflow.orchestrator.core.model.DockerTagsProvider
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.DockerRegistryUrls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        // Use dockerRegistryUrl if set, fall back to nexusUrl — same fallback as QueueService
        val registryUrl = (settings.state.dockerRegistryUrl.takeUnless { it.isNullOrBlank() }
            ?: settings.connections.nexusUrl.orEmpty()).trimEnd('/')
        if (registryUrl.isBlank()) {
            log.warn("[Automation:TagValidation] No registry URL configured")
            return false
        }
        val basePath = settings.state.dockerBasePath.orEmpty()

        // Only validate configs that provide docker tags via the shared interface
        if (configuration !is DockerTagsProvider) return true
        val buildVariables = configuration.getDockerTagsJson()

        val configuredVarName = settings.state.bambooBuildVariableName?.takeIf { it.isNotBlank() }
            ?: "DockerTagsAsJSON"
        val dockerTagsJson = TagValidationLogic.extractDockerTagsJson(buildVariables, configuredVarName)
        if (dockerTagsJson.isBlank()) return true

        val tags = TagValidationLogic.parseDockerTags(dockerTagsJson)
        if (tags.isEmpty()) return true

        val validationService = TagValidationService.getInstance(project)
        val result = validationService.validateTags(registryUrl, tags, basePath)

        if (result.isError) {
            log.warn("[Automation:TagValidation] ${result.summary}")
            return false
        }

        if (!result.data.allPresent) {
            log.warn("[Automation:TagValidation] Missing tags: ${result.data.missingTags}")
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

    /**
     * Extracts the Docker tags JSON string from a build-variables JSON envelope.
     *
     * Uses a **case-insensitive** key lookup so that plans using `DockerTagsAsJSON`,
     * `DockerTagsAsJson`, or `dockerTagsAsJson` all resolve correctly regardless of
     * the configured [varName] default (A-P0-3 fix).
     *
     * @param buildVariables JSON envelope string (the raw build-variables payload).
     * @param varName Name of the variable key to look up (defaults to `"DockerTagsAsJSON"`).
     */
    fun extractDockerTagsJson(
        buildVariables: String,
        varName: String = "DockerTagsAsJSON"
    ): String {
        if (buildVariables.isBlank()) return ""
        return try {
            val obj = json.decodeFromString<JsonObject>(buildVariables)
            obj.entries.firstOrNull { it.key.equals(varName, ignoreCase = true) }
                ?.value?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Constructs a Docker manifest URL, honouring Nexus path-based registries.
     * Delegates to [DockerRegistryUrls.manifestUrl] — consolidation of A-P2-3.
     *
     * @param registryUrl Registry base URL (host root for Nexus path-based).
     * @param imageName Image / repository name.
     * @param tag Docker tag.
     * @param basePath Optional Nexus sub-path (e.g. `/repository/docker-hosted`). Blank for root.
     */
    fun buildManifestUrl(
        registryUrl: String,
        imageName: String,
        tag: String,
        basePath: String = ""
    ): String = DockerRegistryUrls.manifestUrl(registryUrl, basePath, imageName, tag)
}
