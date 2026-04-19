package com.workflow.orchestrator.core.logging

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class StructuredLogger(private val owner: String, private val logDir: File? = null) {

    private val logger = Logger.getInstance(owner)

    fun info(event: String, fields: Map<String, Any?> = emptyMap(), t: Throwable? = null) {
        logger.info(formatMessage(event, fields))
        writeJsonl("info", event, fields)
    }

    fun warn(event: String, fields: Map<String, Any?> = emptyMap(), t: Throwable? = null) {
        val msg = formatMessage(event, fields)
        if (t != null) logger.warn(msg, t) else logger.warn(msg)
        writeJsonl("warn", event, fields)
    }

    fun error(event: String, fields: Map<String, Any?> = emptyMap(), t: Throwable? = null) {
        val msg = formatMessage(event, fields)
        if (t != null) logger.error(msg, t) else logger.error(msg)
        writeJsonl("error", event, fields)
    }

    fun timing(event: String): Timer = Timer(event)

    private fun formatMessage(event: String, fields: Map<String, Any?>): String {
        val fieldStr = fields.entries.joinToString(" ") { (k, v) -> "$k=${v?.toString() ?: "null"}" }
        return if (fieldStr.isEmpty()) "[$owner] event=$event" else "[$owner] event=$event $fieldStr"
    }

    private fun writeJsonl(level: String, event: String, fields: Map<String, Any?>) {
        if (logDir == null) return
        try {
            val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val file = File(logDir, "plugin-$date.jsonl")
            val ts = System.currentTimeMillis()
            val sb = StringBuilder()
            sb.append("{\"ts\":").append(ts)
            sb.append(",\"owner\":").append(jsonString(owner))
            sb.append(",\"event\":").append(jsonString(event))
            sb.append(",\"level\":").append(jsonString(level))
            for ((k, v) in fields) {
                sb.append(",").append(jsonString(k)).append(":").append(jsonString(v?.toString() ?: "null"))
            }
            sb.append("}")
            FileWriter(file, true).use { it.write(sb.toString() + "\n") }
        } catch (_: Exception) {
        }
    }

    // Minimal JSON string escaping — no external serialization dependency.
    private fun jsonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    inner class Timer(private val event: String) : AutoCloseable {
        private val startMs = System.currentTimeMillis()

        override fun close() {
            val elapsed = System.currentTimeMillis() - startMs
            info(event, mapOf("durationMs" to elapsed))
        }
    }
}
