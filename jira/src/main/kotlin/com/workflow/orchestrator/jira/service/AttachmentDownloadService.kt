package com.workflow.orchestrator.jira.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.HttpClientFactory
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.jira.api.dto.JiraAttachment
import com.workflow.orchestrator.jira.model.AttachmentDownloadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class AttachmentDownloadService(private val project: Project) {

    private val log = Logger.getInstance(AttachmentDownloadService::class.java)
    private val credentialStore = CredentialStore()
    private val thumbnailCache = ConcurrentHashMap<String, BufferedImage>()

    private val httpClient: OkHttpClient by lazy {
        HttpClientFactory.sharedPool.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(
                { credentialStore.getToken(ServiceType.JIRA) },
                AuthScheme.BEARER
            ))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    /**
     * Download a single attachment. Returns null on failure.
     */
    suspend fun downloadAttachment(
        attachment: JiraAttachment,
        targetDir: File? = null
    ): AttachmentDownloadResult? = withContext(Dispatchers.IO) {
        try {
            val url = attachment.content
            if (url.isBlank()) {
                log.warn("[Jira:Attachment] No content URL for attachment ${attachment.id}")
                return@withContext null
            }

            val dir = targetDir ?: createTempDir()
            dir.mkdirs()

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                log.warn("[Jira:Attachment] Download failed for ${attachment.filename}: HTTP ${response.code}")
                response.close()
                return@withContext null
            }

            val targetFile = File(dir, attachment.filename)
            response.body?.byteStream()?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                log.warn("[Jira:Attachment] Empty response body for ${attachment.filename}")
                response.close()
                return@withContext null
            }

            response.close()

            log.info("[Jira:Attachment] Downloaded ${attachment.filename} (${targetFile.length()} bytes)")

            AttachmentDownloadResult(
                file = targetFile,
                filename = attachment.filename,
                mimeType = attachment.mimeType,
                sizeBytes = targetFile.length(),
                attachmentId = attachment.id,
                sourceUrl = url
            )
        } catch (e: Exception) {
            log.warn("[Jira:Attachment] Failed to download ${attachment.filename}", e)
            null
        }
    }

    /**
     * Download all attachments. Returns results + summary message.
     */
    suspend fun downloadAll(
        attachments: List<JiraAttachment>,
        targetDir: File
    ): Pair<List<AttachmentDownloadResult>, String> {
        val results = mutableListOf<AttachmentDownloadResult>()
        for (attachment in attachments) {
            val result = downloadAttachment(attachment, targetDir)
            if (result != null) {
                results.add(result)
            }
        }
        val summary = "Downloaded ${results.size} of ${attachments.size} attachments to ${targetDir.absolutePath}"
        log.info("[Jira:Attachment] $summary")
        return Pair(results, summary)
    }

    /**
     * Download thumbnail image. Returns cached if available.
     */
    suspend fun downloadThumbnail(attachment: JiraAttachment): BufferedImage? {
        val thumbnailUrl = attachment.thumbnail ?: return null

        // Check cache first
        thumbnailCache[attachment.id]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(thumbnailUrl)
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    log.warn("[Jira:Attachment] Thumbnail download failed for ${attachment.filename}: HTTP ${response.code}")
                    response.close()
                    return@withContext null
                }

                val image = response.body?.byteStream()?.use { stream ->
                    ImageIO.read(stream)
                }
                response.close()

                if (image != null) {
                    // Evict oldest entries if cache is too large
                    if (thumbnailCache.size >= MAX_THUMBNAIL_CACHE_SIZE) {
                        val keysToRemove = thumbnailCache.keys().toList()
                            .take(thumbnailCache.size - MAX_THUMBNAIL_CACHE_SIZE + 1)
                        keysToRemove.forEach { thumbnailCache.remove(it) }
                    }
                    thumbnailCache[attachment.id] = image
                    log.info("[Jira:Attachment] Cached thumbnail for ${attachment.filename}")
                }

                image
            } catch (e: Exception) {
                log.warn("[Jira:Attachment] Failed to download thumbnail for ${attachment.filename}", e)
                null
            }
        }
    }

    private fun createTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "workflow-orchestrator-attachments")
        dir.mkdirs()
        return dir
    }

    companion object {
        private const val MAX_THUMBNAIL_CACHE_SIZE = 50
    }
}
