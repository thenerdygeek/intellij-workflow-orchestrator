package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * One-time lazy migration from scattered storage locations to unified
 * ~/.workflow-orchestrator/{ProjectName-hash}/ layout.
 */
object StorageMigration {

    private val LOG = Logger.getInstance(StorageMigration::class.java)
    private const val MARKER = "migration-v1.marker"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Run migration if not already done. Safe to call multiple times.
     */
    fun migrateIfNeeded(
        projectBasePath: String,
        newRoot: File = ProjectIdentifier.rootDir(projectBasePath),
        oldProjectAgentDir: File = File(projectBasePath, ".workflow/agent"),
        oldSystemSessionsDir: File = File(PathManager.getSystemPath(), "workflow-agent/sessions")
    ) {
        val marker = File(newRoot, MARKER)
        if (marker.exists()) return

        LOG.info("StorageMigration: starting migration for ${File(projectBasePath).name}")

        migrateProjectFiles(oldProjectAgentDir, newRoot)
        migrateSessions(oldSystemSessionsDir, File(newRoot, "agent/sessions"), projectBasePath)
        migrateTraces(oldProjectAgentDir, File(newRoot, "agent/sessions"))

        // Write marker
        newRoot.mkdirs()
        marker.createNewFile()
        LOG.info("StorageMigration: migration complete, marker written")

        // Clean up old project directory if empty
        if (oldProjectAgentDir.exists()) {
            deleteIfEmpty(oldProjectAgentDir)
            val workflowDir = oldProjectAgentDir.parentFile
            if (workflowDir?.name == ".workflow") {
                deleteIfEmpty(workflowDir)
            }
        }
    }

    /**
     * Migrate project-level files from {projectBasePath}/.workflow/agent/ to {newRoot}/agent/
     */
    fun migrateProjectFiles(oldAgentDir: File, newRoot: File) {
        if (!oldAgentDir.exists()) return

        val newAgentDir = File(newRoot, "agent")
        newAgentDir.mkdirs()

        moveFileIfExists(File(oldAgentDir, "core-memory.json"), File(newAgentDir, "core-memory.json"))
        moveFileIfExists(File(oldAgentDir, "guardrails.md"), File(newAgentDir, "guardrails.md"))
        moveDirIfExists(File(oldAgentDir, "memory"), File(newAgentDir, "memory"))
        moveDirIfExists(File(oldAgentDir, "archival"), File(newAgentDir, "archival"))
        moveDirIfExists(File(oldAgentDir, "metrics"), File(newAgentDir, "metrics"))
    }

    private fun migrateSessions(oldSessionsDir: File, newSessionsDir: File, projectBasePath: String) {
        if (!oldSessionsDir.exists()) return

        oldSessionsDir.listFiles()?.filter { it.isDirectory }?.forEach { sessionDir ->
            val metadataFile = File(sessionDir, "metadata.json")
            if (metadataFile.exists()) {
                try {
                    val metadata = json.parseToJsonElement(metadataFile.readText()).jsonObject
                    val sessionProjectPath = metadata["projectPath"]?.jsonPrimitive?.content
                    if (sessionProjectPath != null && File(sessionProjectPath).absolutePath == File(projectBasePath).absolutePath) {
                        val target = File(newSessionsDir, sessionDir.name)
                        if (!target.exists()) {
                            newSessionsDir.mkdirs()
                            sessionDir.renameTo(target)
                            LOG.info("StorageMigration: migrated session ${sessionDir.name}")
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("StorageMigration: failed to read metadata for ${sessionDir.name}: ${e.message}")
                }
            }
        }
    }

    private fun migrateTraces(oldAgentDir: File, newSessionsDir: File) {
        val oldTracesDir = File(oldAgentDir, "traces")
        if (!oldTracesDir.exists()) return

        oldTracesDir.listFiles()?.filter { it.name.endsWith(".trace.jsonl") }?.forEach { traceFile ->
            val sessionId = traceFile.name.removeSuffix(".trace.jsonl")
            val targetSessionDir = File(newSessionsDir, sessionId)
            if (targetSessionDir.exists()) {
                val targetTracesDir = File(targetSessionDir, "traces")
                targetTracesDir.mkdirs()
                traceFile.renameTo(File(targetTracesDir, "trace.jsonl"))
                LOG.info("StorageMigration: migrated trace for session $sessionId")
            }
        }
        deleteIfEmpty(oldTracesDir)
    }

    private fun moveFileIfExists(source: File, target: File) {
        if (source.exists()) {
            target.parentFile.mkdirs()
            source.renameTo(target)
        }
    }

    private fun moveDirIfExists(source: File, target: File) {
        if (source.exists() && source.isDirectory) {
            target.parentFile.mkdirs()
            source.renameTo(target)
        }
    }

    private fun deleteIfEmpty(dir: File) {
        if (dir.exists() && dir.isDirectory && dir.list()?.isEmpty() == true) {
            dir.delete()
        }
    }
}
