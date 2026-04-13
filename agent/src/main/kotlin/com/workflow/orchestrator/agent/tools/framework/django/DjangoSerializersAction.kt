package com.workflow.orchestrator.agent.tools.framework.django

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.workflow.orchestrator.agent.tools.framework.PythonFileScanner
import java.io.File

private data class SerializerEntry(
    val file: String,
    val name: String,
    val kind: String,   // "ModelSerializer", "Serializer", "HyperlinkedModelSerializer", etc.
    val model: String?
)

private val SERIALIZER_CLASS_PATTERN = Regex(
    """^class\s+(\w+)\s*\([^)]*(\w*Serializer)[^)]*\)""",
    RegexOption.MULTILINE
)
private val MODEL_META_PATTERN = Regex(
    """class\s+Meta\s*:[^c]*model\s*=\s*(\w+)""",
    setOf(RegexOption.DOT_MATCHES_ALL)
)

internal suspend fun executeSerializers(params: JsonObject, project: Project): ToolResult {
    val filter = params["filter"]?.jsonPrimitive?.content
    val basePath = project.basePath
        ?: return ToolResult(
            "Error: project base path not available",
            "Error: missing base path",
            ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )

    return try {
        withContext(Dispatchers.IO) {
            val baseDir = File(basePath)
            val serializerFiles = PythonFileScanner.scanPythonFiles(baseDir) {
                it.name == "serializers.py" || it.name == "serializer.py"
            }

            if (serializerFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No serializers.py files found in project.",
                    "No serializer files found",
                    5
                )
            }

            val allSerializers = mutableListOf<SerializerEntry>()

            for (serializerFile in serializerFiles) {
                val content = serializerFile.readText()
                val relPath = serializerFile.absolutePath.removePrefix(basePath).trimStart(File.separatorChar)

                for (match in SERIALIZER_CLASS_PATTERN.findAll(content)) {
                    val name = match.groupValues[1]
                    val kind = match.groupValues[2]
                    val classStart = match.range.first
                    val classEnd = findClassEnd(content, classStart)
                    val classBody = content.substring(classStart, classEnd)
                    val model = MODEL_META_PATTERN.find(classBody)?.groupValues?.get(1)
                    allSerializers.add(SerializerEntry(relPath, name, kind, model))
                }
            }

            val filtered = if (filter != null) {
                allSerializers.filter { s ->
                    s.name.contains(filter, ignoreCase = true) ||
                        s.model?.contains(filter, ignoreCase = true) == true ||
                        s.file.contains(filter, ignoreCase = true)
                }
            } else {
                allSerializers
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No serializers found$filterDesc.", "No serializers", 5)
            }

            val content = buildString {
                appendLine("Django REST serializers (${filtered.size} total):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, serializers) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (s in serializers.sortedBy { it.name }) {
                        val modelStr = if (s.model != null) " (model=${s.model})" else ""
                        appendLine("  ${s.name} extends ${s.kind}$modelStr")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} serializers",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading serializers: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun findClassEnd(content: String, classStart: Int): Int {
    val nextClass = Regex("""^class\s+\w+""", RegexOption.MULTILINE)
        .find(content, classStart + 1)
    return nextClass?.range?.first ?: content.length
}
