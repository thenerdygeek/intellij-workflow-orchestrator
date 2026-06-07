package com.workflow.orchestrator.core.auth

import okhttp3.Request
import java.util.Base64

/**
 * A resolved credential for an outbound service request. Sealed so the request-decoration
 * logic lives in one place and new schemes are added here, not scattered across interceptors.
 *
 * STABLE, fork-facing API (see docs/STABLE-API.md). Company forks return these from a custom
 * [AuthProvider] (e.g. an OAuth bearer obtained via SSO, or a SAML assertion as a Custom header).
 */
sealed interface Credential {
    /** Apply this credential to an outbound request (typically an Authorization header). */
    fun applyTo(builder: Request.Builder)

    /** `Authorization: Bearer <token>` — Jira/Bamboo/Bitbucket/Sonar default. */
    data class Bearer(val token: String) : Credential {
        override fun applyTo(builder: Request.Builder) {
            builder.header("Authorization", "Bearer $token")
        }
    }

    /** `Authorization: token <token>` — Sourcegraph. */
    data class Token(val token: String) : Credential {
        override fun applyTo(builder: Request.Builder) {
            builder.header("Authorization", "token $token")
        }
    }

    /** `Authorization: Basic base64(user:pass)`. */
    data class Basic(val username: String, val password: String) : Credential {
        override fun applyTo(builder: Request.Builder) {
            val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
            builder.header("Authorization", "Basic $encoded")
        }
    }

    /** Arbitrary header — for SSO/SAML/API-key schemes a fork needs. */
    data class Custom(val headerName: String, val headerValue: String) : Credential {
        override fun applyTo(builder: Request.Builder) {
            builder.header(headerName, headerValue)
        }
    }
}
