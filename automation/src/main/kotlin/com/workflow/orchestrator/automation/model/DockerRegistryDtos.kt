package com.workflow.orchestrator.automation.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DockerTagListResponse(
    val name: String = "",
    val tags: List<String>? = null
)

@Serializable
data class DockerAuthTokenResponse(
    val token: String = "",
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresIn: Int = 300,
    @SerialName("issued_at") val issuedAt: String = ""
) {
    fun effectiveToken(): String = token.ifEmpty { accessToken }
}

data class DockerAuthChallenge(
    val realm: String,
    val service: String,
    val scope: String
)
