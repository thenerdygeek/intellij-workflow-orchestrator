package com.workflow.orchestrator.agent.tools.apidocs

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json

/** Outcome of loading one family resource. Exactly one of [family]/[error] is non-null. */
data class FamilyLoadResult(val family: ApiFamily?, val error: String?)

object ApiDocLoader {
    /** Resource ids, in display order. */
    val FAMILY_IDS = listOf("jira", "bitbucket", "bamboo", "sonarqube", "sourcegraph")

    private const val RESOURCE_PREFIX = "/api-docs/"
    private val logger = Logger.getInstance(ApiDocLoader::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Parse a JSON string into an [ApiFamily]. Throws on malformed input. */
    fun parse(text: String): ApiFamily = json.decodeFromString(ApiFamily.serializer(), text)

    /** Load one family by id; never throws — failures are returned in [FamilyLoadResult.error]. */
    fun loadFamily(id: String): FamilyLoadResult {
        val path = "$RESOURCE_PREFIX$id.json"
        val stream = ApiDocLoader::class.java.getResourceAsStream(path)
            ?: return FamilyLoadResult(null, "API-docs resource missing: $path")
        return try {
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            FamilyLoadResult(parse(text), null)
        } catch (e: Exception) {
            logger.warn("Failed to parse API-docs resource $path", e)
            FamilyLoadResult(null, "Failed to parse $path: ${e.message}")
        }
    }

    /** Load every family in [FAMILY_IDS], preserving order. */
    fun loadAll(): List<FamilyLoadResult> = FAMILY_IDS.map { loadFamily(it) }
}
