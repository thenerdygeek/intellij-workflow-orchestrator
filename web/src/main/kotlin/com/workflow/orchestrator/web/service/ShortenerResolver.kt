package com.workflow.orchestrator.web.service

import com.workflow.orchestrator.core.web.WebError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class ShortenerResolver(private val client: OkHttpClient) {

    sealed class Result {
        data class Resolved(val finalUrl: String) : Result()
        data class Failed(val error: WebError) : Result()
    }

    suspend fun resolve(url: String): Result = withContext(Dispatchers.IO) {
        // Single GET with followRedirects(false): check Location header first,
        // then fall back to reading up to 1024 bytes for a meta-refresh tag.
        val req = Request.Builder().url(url).get().build()
        try {
            client.newCall(req).execute().use { resp ->
                // 3xx with Location header — primary redirect signal
                resp.header("Location")?.let { return@withContext Result.Resolved(it) }

                // 2xx HTML response — probe body for meta-refresh
                if (resp.code in 200..299 && (resp.header("Content-Type") ?: "").contains("html")) {
                    val body = resp.body?.source()?.let {
                        val buf = okio.Buffer()
                        it.read(buf, 1024)
                        buf.readUtf8()
                    } ?: ""
                    val match = Regex(
                        """<meta\s+http-equiv=["']refresh["']\s+content=["']\d+;\s*url=([^"']+)["']""",
                        RegexOption.IGNORE_CASE,
                    ).find(body)
                    if (match != null) return@withContext Result.Resolved(match.groupValues[1].trim())
                }

                Result.Failed(WebError.ShortenerUnresolved(url))
            }
        } catch (_: Exception) {
            Result.Failed(WebError.ShortenerUnresolved(url))
        }
    }
}
