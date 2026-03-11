package com.workflow.orchestrator.core.auth

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

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun testConnection(
        serviceType: ServiceType,
        baseUrl: String,
        token: String
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val endpoint = healthEndpoint(serviceType)
        val url = "${baseUrl}$endpoint"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            val response = testClient.newCall(request).execute()
            response.use {
                when (it.code) {
                    in 200..299 -> ApiResult.Success(it.body?.string() ?: "")
                    401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid token or unauthorized")
                    403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient permissions")
                    else -> ApiResult.Error(
                        ErrorType.SERVER_ERROR,
                        "Server returned ${it.code}: ${it.message}"
                    )
                }
            }
        } catch (e: IOException) {
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach $baseUrl: ${e.message}", e)
        }
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
