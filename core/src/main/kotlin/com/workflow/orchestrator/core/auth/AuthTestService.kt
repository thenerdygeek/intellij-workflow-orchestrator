package com.workflow.orchestrator.core.auth

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class AuthTestService {

    private val log = Logger.getInstance(AuthTestService::class.java)

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * @param token For most services, the PAT. For Nexus, the password.
     * @param username Only used for Nexus (which requires username + password).
     */
    suspend fun testConnection(
        serviceType: ServiceType,
        baseUrl: String,
        token: String,
        username: String? = null
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = baseUrl.trimEnd('/')

        // Sourcegraph uses GraphQL for auth (same path the Cody agent uses internally)
        if (serviceType == ServiceType.SOURCEGRAPH) {
            return@withContext testSourcegraphConnection(normalizedBaseUrl, token)
        }

        val endpoint = healthEndpoint(serviceType)
        val url = "${normalizedBaseUrl}$endpoint"
        val authHeader = buildAuthHeader(serviceType, token, username)

        log.info("[Core:Auth] Testing connection to ${serviceType.name} at $url (auth: ${authSchemeLabel(serviceType)})")

        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .get()
            .build()

        executeTestRequest(serviceType, normalizedBaseUrl, request)
    }

    /**
     * Tests Sourcegraph connection using the SAME GraphQL endpoint the Cody agent uses.
     *
     * The agent validates auth via `POST /.api/graphql` with a CurrentUser query,
     * NOT via `/.api/client-config` (which only exists on Sourcegraph >= 5.5.0).
     * Using the same endpoint ensures "Test Connection" result matches actual agent behavior.
     *
     * After auth succeeds, also checks if Cody is admin-enabled on the instance.
     */
    private fun testSourcegraphConnection(baseUrl: String, token: String): ApiResult<String> {
        log.info("[Core:Auth] Testing Sourcegraph connection at $baseUrl via GraphQL (same path as Cody agent)")

        // Step 1: Validate token via CurrentUser GraphQL query (what the agent does)
        val graphqlUrl = "$baseUrl/.api/graphql"
        val graphqlBody = """{"query":"query CurrentUser { currentUser { id username displayName } }"}"""
        val graphqlRequest = Request.Builder()
            .url(graphqlUrl)
            .header("Authorization", "token $token")
            .header("Content-Type", "application/json")
            .post(graphqlBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        try {
            val response = testClient.newCall(graphqlRequest).execute()
            response.use {
                val body = it.body?.string() ?: ""

                if (it.code == 401) {
                    log.error("[Core:Auth] Sourcegraph auth failed (401). Token may be invalid or expired. Response: ${body.take(200)}")
                    return ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid or expired access token (401)")
                }
                if (it.code !in 200..299) {
                    log.error("[Core:Auth] Sourcegraph returned ${it.code}: ${it.message}. Response: ${body.take(200)}")
                    return ApiResult.Error(ErrorType.SERVER_ERROR, "Server returned ${it.code}: ${it.message}")
                }

                // Check GraphQL response for errors
                if (body.contains("\"errors\"")) {
                    val errorMatch = Regex(""""message"\s*:\s*"([^"]+)"""").find(body)
                    val errorMsg = errorMatch?.groupValues?.get(1) ?: "Unknown GraphQL error"
                    log.error("[Core:Auth] Sourcegraph GraphQL error: $errorMsg")
                    return ApiResult.Error(ErrorType.AUTH_FAILED, "GraphQL error: $errorMsg")
                }

                // Extract username for display
                val usernameMatch = Regex(""""username"\s*:\s*"([^"]+)"""").find(body)
                val username = usernameMatch?.groupValues?.get(1) ?: "unknown"
                log.info("[Core:Auth] Sourcegraph auth successful — user: $username")

                // Step 2: Check if Cody is enabled on this instance
                val codyStatus = checkCodyEnabled(baseUrl, token)

                return when {
                    codyStatus == null -> {
                        // Couldn't determine — old Sourcegraph version, just report auth success
                        ApiResult.Success("Authenticated as $username (Cody status: unknown — Sourcegraph may be < 5.5.0)")
                    }
                    codyStatus -> {
                        ApiResult.Success("Authenticated as $username — Cody is enabled")
                    }
                    else -> {
                        log.warn("[Core:Auth] Sourcegraph auth succeeded but Cody is disabled by admin")
                        ApiResult.Error(ErrorType.FORBIDDEN,
                            "Authenticated as $username, but Cody is disabled by your site admin. " +
                            "Contact your Sourcegraph administrator to enable Cody.")
                    }
                }
            }
        } catch (e: javax.net.ssl.SSLException) {
            log.error("[Core:Auth] Sourcegraph SSL error: ${e.message}", e)
            return ApiResult.Error(ErrorType.NETWORK_ERROR,
                "SSL certificate error: ${e.message}. For self-signed certs, add the certificate to your JDK truststore.", e)
        } catch (e: java.net.UnknownHostException) {
            log.error("[Core:Auth] Sourcegraph DNS resolution failed: ${e.message}", e)
            return ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot resolve host: ${e.message}. Check the URL.", e)
        } catch (e: java.net.ConnectException) {
            log.error("[Core:Auth] Sourcegraph connection refused: ${e.message}", e)
            return ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused: ${e.message}. Check URL/port.", e)
        } catch (e: IOException) {
            log.error("[Core:Auth] Sourcegraph network error: ${e.message}", e)
            return ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach $baseUrl: ${e.message}", e)
        }
    }

    /**
     * Checks if Cody is enabled on the Sourcegraph instance.
     * Returns true/false, or null if the check couldn't be performed (old Sourcegraph version).
     */
    private fun checkCodyEnabled(baseUrl: String, token: String): Boolean? {
        return try {
            // Try /.api/client-config first (Sourcegraph >= 5.5.0)
            val configRequest = Request.Builder()
                .url("$baseUrl/.api/client-config")
                .header("Authorization", "token $token")
                .get()
                .build()

            val configResponse = testClient.newCall(configRequest).execute()
            configResponse.use {
                if (it.code == 404) {
                    // Old Sourcegraph version — try GraphQL fallback
                    return checkCodyEnabledViaGraphQL(baseUrl, token)
                }
                val body = it.body?.string() ?: ""
                val codyEnabled = Regex(""""codyEnabled"\s*:\s*(true|false)""").find(body)
                codyEnabled?.groupValues?.get(1)?.toBoolean()
            }
        } catch (e: Exception) {
            log.debug("[Core:Auth] Could not check Cody enabled status: ${e.message}")
            null
        }
    }

    private fun checkCodyEnabledViaGraphQL(baseUrl: String, token: String): Boolean? {
        return try {
            val query = """{"query":"query { site { isCodyEnabled } }"}"""
            val request = Request.Builder()
                .url("$baseUrl/.api/graphql")
                .header("Authorization", "token $token")
                .header("Content-Type", "application/json")
                .post(query.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            val response = testClient.newCall(request).execute()
            response.use {
                val body = it.body?.string() ?: ""
                val match = Regex(""""isCodyEnabled"\s*:\s*(true|false)""").find(body)
                match?.groupValues?.get(1)?.toBoolean()
            }
        } catch (e: Exception) {
            log.debug("[Core:Auth] GraphQL Cody enabled check failed: ${e.message}")
            null
        }
    }

    private fun executeTestRequest(
        serviceType: ServiceType,
        normalizedBaseUrl: String,
        request: Request
    ): ApiResult<String> {
        return try {
            val response = testClient.newCall(request).execute()
            response.use {
                val body = it.body?.string() ?: ""
                when (it.code) {
                    in 200..299 -> {
                        log.info("[Core:Auth] Connection test successful for ${serviceType.name} at $normalizedBaseUrl (${it.code})")
                        ApiResult.Success(body)
                    }
                    401 -> {
                        log.error("[Core:Auth] Connection test failed for ${serviceType.name} — authentication failed (401). Response: ${body.take(200)}")
                        ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid token or unauthorized (401)")
                    }
                    403 -> {
                        log.error("[Core:Auth] Connection test failed for ${serviceType.name} — forbidden (403). Response: ${body.take(200)}")
                        ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient permissions (403)")
                    }
                    404 -> {
                        log.error("[Core:Auth] Connection test failed for ${serviceType.name} — endpoint not found (404) at ${request.url}. Check the base URL.")
                        ApiResult.Error(ErrorType.SERVER_ERROR, "Endpoint not found (404). Check the base URL — it should not include a path like /rest or /api.")
                    }
                    else -> {
                        log.error("[Core:Auth] Connection test failed for ${serviceType.name} — server returned ${it.code}: ${it.message}. Response: ${body.take(200)}")
                        ApiResult.Error(
                            ErrorType.SERVER_ERROR,
                            "Server returned ${it.code}: ${it.message}"
                        )
                    }
                }
            }
        } catch (e: javax.net.ssl.SSLException) {
            log.error("[Core:Auth] Connection test failed for ${serviceType.name} — SSL error: ${e.message}. " +
                "If using a self-signed certificate, add it to the JDK truststore.", e)
            return ApiResult.Error(
                ErrorType.NETWORK_ERROR,
                "SSL certificate error: ${e.message}. For self-signed certs, add the certificate to your JDK truststore.",
                e
            )
        } catch (e: java.net.UnknownHostException) {
            log.error("[Core:Auth] Connection test failed for ${serviceType.name} — DNS resolution failed for host: ${e.message}", e)
            return ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot resolve host: ${e.message}. Check the URL.", e)
        } catch (e: java.net.ConnectException) {
            log.error("[Core:Auth] Connection test failed for ${serviceType.name} — connection refused: ${e.message}", e)
            return ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused: ${e.message}. Check that the service is running and the URL/port are correct.", e)
        } catch (e: IOException) {
            log.error("[Core:Auth] Connection test failed for ${serviceType.name} — network error: ${e.message}", e)
            return ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach $normalizedBaseUrl: ${e.message}", e)
        }
    }

    /**
     * Builds the correct Authorization header value for each service type.
     *
     * - Jira Server, Bamboo, Bitbucket, SonarQube: Bearer PAT
     * - Sourcegraph (Cody Enterprise): "token <access_token>" (Sourcegraph-specific format)
     * - Nexus Docker Registry: Basic auth with token as username, empty password
     */
    private fun buildAuthHeader(serviceType: ServiceType, token: String, username: String? = null): String = when (serviceType) {
        ServiceType.SOURCEGRAPH -> "token $token"
        ServiceType.NEXUS -> {
            val user = username ?: ""
            "Basic " + java.util.Base64.getEncoder()
                .encodeToString("$user:$token".toByteArray())
        }
        else -> "Bearer $token"
    }

    private fun authSchemeLabel(serviceType: ServiceType): String = when (serviceType) {
        ServiceType.SOURCEGRAPH -> "token"
        ServiceType.NEXUS -> "Basic"
        else -> "Bearer"
    }

    private fun healthEndpoint(serviceType: ServiceType): String = when (serviceType) {
        ServiceType.JIRA -> "/rest/api/2/myself"
        ServiceType.BAMBOO -> "/rest/api/latest/currentUser"
        ServiceType.SONARQUBE -> "/api/authentication/validate"
        ServiceType.BITBUCKET -> "/rest/api/1.0/users"
        ServiceType.SOURCEGRAPH -> "/.api/client-config"
        ServiceType.NEXUS -> "/v2/"
    }
}
