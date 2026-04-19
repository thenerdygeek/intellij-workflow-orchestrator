package com.workflow.orchestrator.core.diagnostics

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticBundleBuilder {

    fun build(project: Project, includeCurrentSession: Boolean = false): File {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val agentDir = ProjectIdentifier.agentDir(basePath)
        val logsDir = File(agentDir, "logs")
        val diagnosticsDir = File(
            System.getProperty("user.home"),
            ".workflow-orchestrator/diagnostics"
        )
        diagnosticsDir.mkdirs()

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val zipFile = File(diagnosticsDir, "diagnostic-$timestamp.zip")

        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            addLogsToZip(zip, logsDir)
            addSettingsDump(zip, project)
            addSystemInfo(zip)
            if (includeCurrentSession) {
                addSessionFiles(zip, agentDir)
            }
        }

        return zipFile
    }

    private fun addLogsToZip(zip: ZipOutputStream, logsDir: File) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        logsDir.listFiles { f -> f.name.endsWith(".jsonl") && f.lastModified() >= cutoff }
            ?.forEach { logFile ->
                zip.putNextEntry(ZipEntry("logs/${logFile.name}"))
                logFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
    }

    private fun addSettingsDump(zip: ZipOutputStream, project: Project) {
        val state = PluginSettings.getInstance(project).state
        val json = buildString {
            append("{\n")
            append("  \"logLevel\": \"${state.logLevel}\",\n")
            append("  \"diagnosticJsonlEnabled\": ${state.diagnosticJsonlEnabled},\n")
            append("  \"retentionDays\": ${state.retentionDays},\n")
            append("  \"rawApiTraceMode\": \"${state.rawApiTraceMode}\",\n")
            append("  \"rawApiTraceRetentionDays\": ${state.rawApiTraceRetentionDays},\n")
            append("  \"costDisplayEnabled\": ${state.costDisplayEnabled}\n")
            append("}")
        }
        zip.putNextEntry(ZipEntry("settings.json"))
        zip.write(json.toByteArray())
        zip.closeEntry()
    }

    private fun addSystemInfo(zip: ZipOutputStream) {
        val appInfo = ApplicationInfo.getInstance()
        val info = buildString {
            appendLine("IDE: ${appInfo.fullApplicationName}")
            appendLine("Build: ${appInfo.build}")
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
            appendLine("JVM: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
            appendLine("Generated: ${LocalDateTime.now()}")
        }
        zip.putNextEntry(ZipEntry("system-info.txt"))
        zip.write(info.toByteArray())
        zip.closeEntry()
    }

    private fun addSessionFiles(zip: ZipOutputStream, agentDir: File) {
        val sessionsDir = File(agentDir, "sessions")
        if (!sessionsDir.exists()) return
        sessionsDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.take(1)
            ?.forEach { sessionDir ->
                listOf("api_conversation_history.json", "ui_messages.json").forEach { fname ->
                    val f = File(sessionDir, fname)
                    if (f.exists()) {
                        zip.putNextEntry(ZipEntry("session/${sessionDir.name}/$fname"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
    }
}
