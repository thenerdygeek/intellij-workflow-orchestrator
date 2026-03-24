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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.image.BufferedImage
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class AttachmentDownloadService(private val project: Project) {

    private val log = Logger.getInstance(AttachmentDownloadService::class.java)
    private val credentialStore = CredentialStore()

    private val thumbnailCache: MutableMap<String, BufferedImage> = Collections.synchronizedMap(
        object : LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>?): Boolean {
                return size > MAX_THUMBNAIL_CACHE_SIZE
            }
        }
    )

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

            val isTempDownload = targetDir == null
            val dir = targetDir ?: createTempDir()

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.warn("[Jira:Attachment] Download failed for ${attachment.filename}: HTTP ${response.code}")
                    if (isTempDownload) dir.deleteRecursively()
                    return@withContext null
                }

                val body = response.body ?: run {
                    log.warn("[Jira:Attachment] Empty response body for ${attachment.filename}")
                    if (isTempDownload) dir.deleteRecursively()
                    return@withContext null
                }

                val safeName = attachment.filename.substringAfterLast('/').substringAfterLast('\\')
                    .replace("..", "_").ifBlank { "attachment_${attachment.id}" }
                val targetFile = File(dir, safeName)
                if (!targetFile.canonicalPath.startsWith(dir.canonicalPath)) {
                    log.warn("[Jira] Path traversal attempt blocked in attachment: ${attachment.filename}")
                    if (isTempDownload) dir.deleteRecursively()
                    return@withContext null
                }

                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                log.info("[Jira:Attachment] Downloaded ${attachment.filename} (${targetFile.length()} bytes)")

                AttachmentDownloadResult(
                    file = targetFile,
                    filename = attachment.filename,
                    mimeType = attachment.mimeType,
                    sizeBytes = targetFile.length(),
                    attachmentId = attachment.id,
                    sourceUrl = url
                )
            }
        } catch (e: Exception) {
            log.warn("[Jira:Attachment] Failed to download ${attachment.filename}", e)
            null
        }
    }

    /**
     * Download all attachments in parallel (max 3 concurrent). Returns results + summary message.
     */
    suspend fun downloadAll(
        attachments: List<JiraAttachment>,
        targetDir: File
    ): Pair<List<AttachmentDownloadResult>, String> {
        val results = coroutineScope {
            val semaphore = Semaphore(3)
            attachments.map { attachment ->
                async {
                    semaphore.withPermit { downloadAttachment(attachment, targetDir) }
                }
            }.awaitAll().filterNotNull()
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

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log.warn("[Jira:Attachment] Thumbnail download failed for ${attachment.filename}: HTTP ${response.code}")
                        return@withContext null
                    }

                    val image = response.body?.byteStream()?.use { stream ->
                        ImageIO.read(stream)
                    }

                    if (image != null) {
                        thumbnailCache[attachment.id] = image
                        log.info("[Jira:Attachment] Cached thumbnail for ${attachment.filename}")
                    }

                    image
                }
            } catch (e: Exception) {
                log.warn("[Jira:Attachment] Failed to download thumbnail for ${attachment.filename}", e)
                null
            }
        }
    }

    private fun createTempDir(): File {
        val dir = java.nio.file.Files.createTempDirectory("workflow-orchestrator-attachments").toFile()
        return dir
    }

    companion object {
        private const val MAX_THUMBNAIL_CACHE_SIZE = 50
    }
}
