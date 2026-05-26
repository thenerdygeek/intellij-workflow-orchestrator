package com.workflow.orchestrator.agent.tools.apidocs

import kotlinx.serialization.Serializable

/** Where a documented endpoint stands relative to the plugin's actual usage. */
@Serializable
enum class ApiEndpointStatus { USED, PROBED_UNUSED, KNOWN_UNUSED, DEPRECATED }

/** Where a parameter rides on the HTTP request. */
@Serializable
enum class ApiParamLocation { QUERY, PATH, BODY, HEADER }

@Serializable
data class ApiParam(
    val name: String,
    val location: ApiParamLocation,
    val type: String,                 // "string", "integer", "boolean", "array", "object"
    val required: Boolean,
    val description: String,
    val example: String? = null,
)

@Serializable
data class ApiVerdict(
    /** Audit classification, e.g. "R-INV: inventoried, not adopted", "Removed in DC 9.0". */
    val classification: String,
    val reasoning: String,
)

@Serializable
data class ApiEndpoint(
    val method: String,               // GET | POST | PUT | DELETE
    val pathTemplate: String,         // "/rest/api/2/issue/{key}/transitions"
    val status: ApiEndpointStatus,
    val summary: String,
    val params: List<ApiParam> = emptyList(),
    val requestBody: String? = null,  // body shape / form-encoding notes
    val sampleResponse: String? = null, // redacted excerpt from a probe bundle
    val callSite: String? = null,     // "JiraApiClient.kt:142" — required when status == USED
    val provenance: String,           // REQUIRED — e.g. "probe Result_Jira/raw/myself.json"
    val verdict: ApiVerdict? = null,
    val gotchas: List<String> = emptyList(),
)

@Serializable
data class ApiCategory(
    val name: String,                 // "Issues", "Sprints & Boards", "Dev-status", ...
    val endpoints: List<ApiEndpoint>,
)

@Serializable
data class ApiFamily(
    val id: String,                   // "jira", "bitbucket", "bamboo", "sonarqube", "sourcegraph"
    val displayName: String,          // "Jira (Data Center)"
    val authScheme: String,           // "Authorization: Bearer <PAT>"
    val probedServerVersion: String,  // "Jira DC 10.3.16"
    val description: String,
    val categories: List<ApiCategory>,
)
