package com.workflow.orchestrator.core.auth

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    suspend fun testConnection(
        serviceType: ServiceType,
        baseUrl: String,
        token: String
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val endpoint = healthEndpoint(serviceType)
        val url = "${normalizedBaseUrl}$endpoint"
        val authHeader = buildAuthHeader(serviceType, token)

        log.info("[Core:Auth] Testing connection to ${serviceType.name} at $url (auth: ${authSchemeLabel(serviceType)})")

        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .get()
            .build()

        try {
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
                        log.error("[Core:Auth] Connection test failed for ${serviceType.name} — endpoint not found (404) at $url. Check the base URL.")
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
            ApiResult.Error(
                ErrorType.NETWORK_ERROR,
                "SSL certificate error: ${e.message}. For self-signed certs, add the certificate to your JDK truststore.",
                e
            )
        } catch (e: java.net.UnknownHostException) {
            log.error("[Core:Auth] Connection test failed for ${serviceType.name} — DNS resolution failed for host: ${e.message}", e)
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot resolve host: ${e.message}. Check the URL.", e)
        } catch (e: java.net.ConnectException) {
            log.error("[Core:Auth] Connection test failed for ${serviceType.name} — connection refused: ${e.message}", e)
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused: ${e.message}. Check that the service is running and the URL/port are correct.", e)
        } catch (e: IOException) {
            log.error("[Core:Auth] Connection test failed for ${serviceType.name} — network error: ${e.message}", e)
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach $normalizedBaseUrl: ${e.message}", e)
        }
    }

    /**
     * Builds the correct Authorization header value for each service type.
     *
     * - Jira Server, Bamboo, Bitbucket, SonarQube: Bearer PAT
     * - Sourcegraph (Cody Enterprise): "token <access_token>" (Sourcegraph-specific format)
     * - Nexus Docker Registry: Basic auth with token as username, empty password
     */
    private fun buildAuthHeader(serviceType: ServiceType, token: String): String = when (serviceType) {
        ServiceType.SOURCEGRAPH -> "token $token"
        ServiceType.NEXUS -> "Basic " + java.util.Base64.getEncoder()
            .encodeToString("$token:".toByteArray())
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
