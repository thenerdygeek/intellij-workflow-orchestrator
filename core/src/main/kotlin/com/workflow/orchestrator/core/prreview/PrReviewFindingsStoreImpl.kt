package com.workflow.orchestrator.core.prreview

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.services.ToolResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

@Service(Service.Level.PROJECT)
class PrReviewFindingsStoreImpl(private val project: Project) : PrReviewFindingsStore {

    private val mutex = Mutex()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class FindingsFile(val findings: List<PrReviewFinding>)

    override suspend fun add(finding: PrReviewFinding): ToolResult<PrReviewFinding> = mutex.withLock {
        val normalized = finding.copy(
            id = if (finding.id.isBlank()) UUID.randomUUID().toString() else finding.id
        )
        val path = pathFor(normalized.prId, normalized.sessionId)
        val current = readFile(path)
        writeFile(path, current + normalized)
        ToolResult.success(normalized, summary = "Finding ${normalized.id} added")
    }

    override suspend fun update(
        id: String,
        mutate: (PrReviewFinding) -> PrReviewFinding,
    ): ToolResult<PrReviewFinding> = mutex.withLock {
        val (path, found) = locate(id)
            ?: return@withLock ToolResult(
                data = PrReviewFinding(
                    id = id, prId = "", sessionId = "",
                    severity = FindingSeverity.NORMAL, message = "", createdAt = 0
                ),
                summary = "Finding $id not found",
                isError = true,
            )
        val current = readFile(path)
        val mutated = mutate(found)
        writeFile(path, current.map { if (it.id == id) mutated else it })
        ToolResult.success(mutated, summary = "Finding $id updated")
    }

    override suspend fun discard(id: String): ToolResult<Unit> {
        val res = update(id) { it.copy(discarded = true) }
        return if (res.isError) {
            ToolResult(data = Unit, summary = res.summary, isError = true)
        } else {
            ToolResult.success(Unit, summary = "Finding $id discarded")
        }
    }

    override suspend fun markPushed(
        id: String,
        bitbucketCommentId: String,
        pushedAt: Long,
    ): ToolResult<Unit> {
        val res = update(id) {
            it.copy(pushed = true, pushedCommentId = bitbucketCommentId, pushedAt = pushedAt)
        }
        return if (res.isError) {
            ToolResult(data = Unit, summary = res.summary, isError = true)
        } else {
            ToolResult.success(Unit, summary = "Finding $id marked pushed")
        }
    }

    override suspend fun list(
        prId: String,
        sessionId: String?,
        includeArchived: Boolean,
    ): ToolResult<List<PrReviewFinding>> = mutex.withLock {
        val base = baseDir().resolve(encodePrId(prId))
        if (!Files.isDirectory(base)) {
            return@withLock ToolResult.success(emptyList(), summary = "0 findings")
        }
        val sessionPaths = when (sessionId) {
            null -> Files.list(base).use { it.toList() }
            else -> listOf(base.resolve("$sessionId.json")).filter(Files::exists)
        }
        val combined = sessionPaths
            .flatMap { readFile(it) }
            .filter { includeArchived || !it.archived }
        ToolResult.success(combined, summary = "${combined.size} findings")
    }

    override suspend fun archiveSession(prId: String, sessionId: String): ToolResult<Unit> = mutex.withLock {
        val path = pathFor(prId, sessionId)
        val updated = readFile(path).map { it.copy(archived = true) }
        writeFile(path, updated)
        ToolResult.success(Unit, summary = "Archived ${updated.size} findings")
    }

    override suspend fun clear(prId: String, sessionId: String): ToolResult<Unit> = mutex.withLock {
        val path = pathFor(prId, sessionId)
        if (Files.exists(path)) Files.delete(path)
        ToolResult.success(Unit, summary = "Cleared findings")
    }

    // ---------- internal helpers ----------

    private fun baseDir(): Path {
        val home = System.getProperty("user.home")
        val projectName = project.name
        val projectPath = project.basePath ?: "unknown"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(projectPath.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(6)
        return Path.of(home, ".workflow-orchestrator", "$projectName-$hash", "agent", "pr-review-findings")
    }

    private fun pathFor(prId: String, sessionId: String): Path =
        baseDir().resolve(encodePrId(prId)).resolve("$sessionId.json")

    private fun encodePrId(prId: String): String =
        prId.replace("/", "_").replace(":", "_")

    private fun readFile(path: Path): List<PrReviewFinding> {
        if (!Files.exists(path)) return emptyList()
        return runCatching {
            json.decodeFromString<FindingsFile>(Files.readString(path)).findings
        }.getOrElse { emptyList() }
    }

    private fun writeFile(path: Path, findings: List<PrReviewFinding>) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(FindingsFile(findings)))
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun locate(id: String): Pair<Path, PrReviewFinding>? {
        val root = baseDir()
        if (!Files.isDirectory(root)) return null
        Files.walk(root).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .map { p -> readFile(p).firstOrNull { it.id == id }?.let { p to it } }
                .filter { it != null }
                .findFirst()
                .orElse(null)
        }
    }
}
