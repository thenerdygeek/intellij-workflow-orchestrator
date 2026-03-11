package com.workflow.orchestrator.automation.api

import com.workflow.orchestrator.automation.model.DockerAuthChallenge
import com.workflow.orchestrator.automation.model.DockerAuthTokenResponse
import com.workflow.orchestrator.automation.model.DockerTagListResponse
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Docker Registry v2 API client.
 * @param registryUrl Base URL of the Docker Registry (e.g., "https://registry.example.com")
 * @param tokenProvider Returns a base64-encoded Basic auth token for the registry
 */
class DockerRegistryClient(
    private val registryUrl: String,
    private val tokenProvider: () -> String?
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val tokenCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val tagCache = ConcurrentHashMap<String, Pair<List<String>, Long>>()
    private val tagCacheTtlMs = 5 * 60 * 1000L

    suspend fun tagExists(serviceName: String, tag: String): ApiResult<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$registryUrl/v2/$serviceName/manifests/$tag")
                    .head()
                    .header("Accept", "application/vnd.docker.distribution.manifest.v2+json")
                    .build()

                executeWithAuth(request) { response ->
                    when (response.code) {
                        in 200..299 -> ApiResult.Success(true)
                        404 -> ApiResult.Success(false)
                        else -> ApiResult.Error(
                            ErrorType.SERVER_ERROR,
                            "Registry returned ${response.code}"
                        )
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Docker Registry: ${e.message}", e)
            }
        }

    suspend fun listTags(serviceName: String): ApiResult<List<String>> =
        withContext(Dispatchers.IO) {
            val cached = tagCache[serviceName]
            if (cached != null && System.currentTimeMillis() < cached.second) {
                return@withContext ApiResult.Success(cached.first)
            }

            try {
                val allTags = mutableListOf<String>()
                var path: String? = "/v2/$serviceName/tags/list?n=100"
                var pageCount = 0
                val maxPages = 50

                while (path != null && pageCount < maxPages) {
                    val request = Request.Builder()
                        .url("$registryUrl$path")
                        .get()
                        .header("Accept", "application/json")
                        .build()

                    val pageResult = executeWithAuth(request) { response ->
                        when (response.code) {
                            in 200..299 -> {
                                val body = response.body?.string() ?: ""
                                val tagList = json.decodeFromString<DockerTagListResponse>(body)
                                val tags = tagList.tags ?: emptyList()
                                allTags.addAll(tags)

                                val linkHeader = response.header("Link")
                                path = parseLinkHeader(linkHeader)

                                ApiResult.Success(tags)
                            }
                            404 -> {
                                path = null
                                ApiResult.Success(emptyList())
                            }
                            else -> {
                                path = null
                                ApiResult.Error(
                                    ErrorType.SERVER_ERROR,
                                    "Registry returned ${response.code}"
                                )
                            }
                        }
                    }

                    if (pageResult is ApiResult.Error) {
                        return@withContext pageResult as ApiResult<List<String>>
                    }
                    pageCount++
                }

                tagCache[serviceName] = allTags.toList() to
                    (System.currentTimeMillis() + tagCacheTtlMs)

                ApiResult.Success(allTags.toList())
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Docker Registry: ${e.message}", e)
            }
        }

    suspend fun getLatestReleaseTag(serviceName: String): ApiResult<String?> {
        return when (val result = listTags(serviceName)) {
            is ApiResult.Success -> {
                val semverPattern = Regex("""^\d+\.\d+\.\d+.*$""")
                val releaseTags = result.data.filter { semverPattern.matches(it) }
                val sorted = releaseTags.sortedWith(SemverComparator)
                ApiResult.Success(sorted.lastOrNull())
            }
            is ApiResult.Error -> ApiResult.Error(result.type, result.message, result.cause)
        }
    }

    private fun <T> executeWithAuth(
        request: Request,
        handler: (okhttp3.Response) -> ApiResult<T>
    ): ApiResult<T> {
        val response = httpClient.newCall(request).execute()
        return response.use {
            if (it.code == 401) {
                val challenge = parseWwwAuthenticate(it.header("WWW-Authenticate"))
                if (challenge != null) {
                    val token = fetchBearerToken(challenge)
                    if (token != null) {
                        val retryRequest = request.newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build()
                        val retryResponse = httpClient.newCall(retryRequest).execute()
                        return retryResponse.use { retry -> handler(retry) }
                    }
                }
                ApiResult.Error(ErrorType.AUTH_FAILED, "Docker Registry authentication failed")
            } else {
                handler(it)
            }
        }
    }

    internal fun parseWwwAuthenticate(header: String?): DockerAuthChallenge? {
        if (header == null || !header.startsWith("Bearer ")) return null
        val params = header.removePrefix("Bearer ").split(",").associate { part ->
            val (key, value) = part.trim().split("=", limit = 2)
            key.trim() to value.trim().removeSurrounding("\"")
        }
        val realm = params["realm"] ?: return null
        val service = params["service"] ?: ""
        val scope = params["scope"] ?: ""
        return DockerAuthChallenge(realm, service, scope)
    }

    private fun fetchBearerToken(challenge: DockerAuthChallenge): String? {
        val cacheKey = "${challenge.service}:${challenge.scope}"
        val cached = tokenCache[cacheKey]
        if (cached != null && System.currentTimeMillis() < cached.second) {
            return cached.first
        }

        val url = buildString {
            append(challenge.realm)
            append("?service=${challenge.service}")
            if (challenge.scope.isNotEmpty()) {
                append("&scope=${challenge.scope}")
            }
        }

        val request = Request.Builder().url(url).get().build()
        val basicToken = tokenProvider()
        val authRequest = if (basicToken != null) {
            request.newBuilder()
                .header("Authorization", "Basic $basicToken")
                .build()
        } else {
            request
        }

        return try {
            val response = httpClient.newCall(authRequest).execute()
            response.use {
                if (it.isSuccessful) {
                    val body = it.body?.string() ?: return null
                    val tokenResponse = json.decodeFromString<DockerAuthTokenResponse>(body)
                    val token = tokenResponse.effectiveToken()

                    val expiresAtMs = System.currentTimeMillis() +
                        (tokenResponse.expiresIn * 800L)
                    tokenCache[cacheKey] = token to expiresAtMs

                    token
                } else {
                    null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun parseLinkHeader(header: String?): String? {
        if (header == null) return null
        val match = Regex("""<([^>]+)>;\s*rel="next"""").find(header) ?: return null
        return match.groupValues[1]
    }

    internal object SemverComparator : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val aParts = a.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val bParts = b.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            for (i in 0 until maxOf(aParts.size, bParts.size)) {
                val aVal = aParts.getOrElse(i) { 0 }
                val bVal = bParts.getOrElse(i) { 0 }
                if (aVal != bVal) return aVal.compareTo(bVal)
            }
            return 0
        }
    }
}
