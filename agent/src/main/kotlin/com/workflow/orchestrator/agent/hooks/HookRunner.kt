package com.workflow.orchestrator.agent.hooks

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * Executes hook commands as child processes with JSON event data via stdin.
 *
 * Faithful port of Cline's HookProcess + StdioHookRunner:
 * - Spawns the hook command via ProcessBuilder
 * - Passes event data as JSON string via stdin (Cline's HookProcess.run(inputJson))
 * - Sets environment variables for common fields (HOOK_TYPE, SESSION_ID, TOOL_NAME)
 * - Reads stdout for JSON response, stderr for error reporting
 * - Exit code 0 = Proceed, non-zero = Cancel (Cline's EXIT_CODE_SIGINT convention)
 * - Timeout = Proceed with warning (Cline: "Hook execution timed out after {timeout}ms")
 * - 1MB output size limit (Cline's MAX_HOOK_OUTPUT_SIZE)
 *
 * Key differences from Cline:
 * - We use ProcessBuilder instead of Node's child_process.spawn
 * - We use coroutines + withTimeout instead of setTimeout
 * - We parse JSON from stdout (Cline's parseJsonOutput pattern)
 * - No Windows PowerShell special-casing (JVM handles cross-platform)
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/hooks/HookProcess.ts">Cline HookProcess</a>
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/hooks/hook-factory.ts">Cline StdioHookRunner</a>
 */
open class HookRunner(
    private val workingDir: String? = null
) {
    companion object {
        private val LOG = Logger.getInstance(HookRunner::class.java)

        /** Maximum total output size (stdout + stderr combined). Matches Cline's MAX_HOOK_OUTPUT_SIZE. */
        private const val MAX_OUTPUT_SIZE = 1024 * 1024 // 1MB

        /** Maximum size for context modification to prevent prompt overflow. Matches Cline's limit. */
        private const val MAX_CONTEXT_MODIFICATION_SIZE = 50_000 // ~50KB

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }

    /**
     * Execute a hook command, passing event data as JSON via stdin.
     *
     * Ported from Cline's StdioHookRunner[exec] + HookProcess.run():
     * 1. Serialize event data to JSON (Cline: HookInput.toJSON)
     * 2. Spawn process with environment variables
     * 3. Write JSON to stdin, close stdin
     * 4. Wait for completion with timeout
     * 5. Parse JSON from stdout (with fallback extraction)
     * 6. Map exit code to HookResult
     *
     * @param hook the hook configuration (command + timeout)
     * @param event the hook event with type and data
     * @return HookResult based on exit code and stdout JSON
     */
    open suspend fun execute(hook: HookConfig, event: HookEvent): HookResult {
        return withContext(Dispatchers.IO) {
            try {
                val inputJson = buildInputJson(event, hook)

                // Build the process with shell execution (matches Cline's shell: true on Unix)
                val processBuilder = buildProcess(hook, event)

                val result = withTimeoutOrNull(hook.timeout) {
                    runProcess(processBuilder, inputJson)
                }

                if (result == null) {
                    // Timeout: Cline logs warning and treats as non-fatal.
                    // "Hook execution timed out after {timeout}ms"
                    LOG.warn("[HookRunner] ${event.type.hookName} hook timed out after ${hook.timeout}ms, proceeding")
                    return@withContext HookResult.Proceed()
                }

                interpretResult(result, event)
            } catch (e: Exception) {
                // Cline: "Hook script errors don't block tools, only explicit JSON response does"
                LOG.warn("[HookRunner] ${event.type.hookName} hook failed: ${e.message}")
                HookResult.Proceed()
            }
        }
    }

    /**
     * Build the JSON input to send to the hook via stdin.
     *
     * Matches Cline's HookRunner.completeParams():
     * - hookName: PascalCase hook type name
     * - timestamp: execution time in milliseconds since epoch
     * - Event-specific data nested under the hook type key
     *
     * @see <a href="https://github.com/cline/cline/blob/main/src/core/hooks/hook-factory.ts">Cline completeParams</a>
     */
    internal fun buildInputJson(event: HookEvent, hook: HookConfig): String {
        val map = buildMap<String, JsonElement> {
            put("hookName", JsonPrimitive(event.type.hookName))
            put("timestamp", JsonPrimitive(System.currentTimeMillis().toString()))

            // Event-specific data — Cline nests under the hook type key
            // e.g., { "preToolUse": { "tool": "edit_file", "parameters": {...} } }
            val eventDataKey = event.type.hookName.replaceFirstChar { it.lowercase() }
            val dataElements = event.data.mapValues { (_, v) -> toJsonElement(v) }
            put(eventDataKey, JsonObject(dataElements))

            // Also put top-level data fields for env var convenience
            for ((key, value) in event.data) {
                if (key !in this) {
                    put(key, toJsonElement(value))
                }
            }
        }

        return json.encodeToString(JsonObject(map))
    }

    /**
     * Build ProcessBuilder with environment variables set.
     *
     * Cline sets environment variables for common fields so hooks can
     * access them without parsing JSON. We follow the same pattern.
     */
    private fun buildProcess(hook: HookConfig, event: HookEvent): ProcessBuilder {
        val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        val command = if (isWindows) {
            listOf("cmd", "/c", hook.command)
        } else {
            listOf("/bin/sh", "-c", hook.command)
        }

        return ProcessBuilder(command).apply {
            redirectErrorStream(false)
            workingDir?.let { directory(java.io.File(it)) }

            // Set environment variables (Cline pattern)
            environment().apply {
                put("HOOK_TYPE", event.type.hookName)
                event.data["sessionId"]?.toString()?.let { put("SESSION_ID", it) }
                event.data["toolName"]?.toString()?.let { put("TOOL_NAME", it) }
                event.data["taskId"]?.toString()?.let { put("TASK_ID", it) }
                event.data["message"]?.toString()?.let { put("HOOK_MESSAGE", it) }
                event.data["task"]?.toString()?.let { put("HOOK_TASK", it) }
            }
        }
    }

    /**
     * Run the process, write stdin, collect stdout/stderr, wait for exit.
     *
     * Matches Cline's HookProcess flow:
     * 1. Start process
     * 2. Write inputJson to stdin, close stdin
     * 3. Read stdout and stderr (with size limit)
     * 4. Wait for exit code
     */
    private fun runProcess(processBuilder: ProcessBuilder, inputJson: String): ProcessResult {
        val process = processBuilder.start()

        try {
            // Write JSON to stdin (Cline: childProcess.stdin.write(inputJson); childProcess.stdin.end())
            process.outputStream.use { stdin ->
                stdin.write(inputJson.toByteArray(Charsets.UTF_8))
                stdin.flush()
            }

            // Read stdout and stderr with size limits (Cline: MAX_HOOK_OUTPUT_SIZE = 1MB)
            val stdout = readLimited(process.inputStream, MAX_OUTPUT_SIZE)
            val stderr = readLimited(process.errorStream, MAX_OUTPUT_SIZE)

            val exitCode = process.waitFor()

            return ProcessResult(exitCode, stdout, stderr)
        } finally {
            process.destroyForcibly()
        }
    }

    /**
     * Interpret the process result into a HookResult.
     *
     * Ported from Cline's StdioHookRunner[exec] result handling:
     * 1. Try to parse JSON from stdout (Cline's parseJsonOutput)
     * 2. If valid JSON with cancel:true -> Cancel
     * 3. If valid JSON with cancel:false -> Proceed (with optional contextModification)
     * 4. If no valid JSON: use exit code (0 = Proceed, non-zero = Cancel)
     * 5. For non-cancellable hooks: always Proceed regardless of exit code
     *
     * Cline: "If we have valid JSON, honor it regardless of exit code"
     */
    internal fun interpretResult(result: ProcessResult, event: HookEvent): HookResult {
        // Try parsing JSON from stdout (Cline's parseJsonOutput pattern)
        val parsedOutput = parseJsonOutput(result.stdout)

        if (parsedOutput != null) {
            val cancel = parsedOutput["cancel"]?.jsonPrimitive?.booleanOrNull ?: false

            // Extract and validate contextModification (Cline: MAX_CONTEXT_MODIFICATION_SIZE)
            var contextMod = parsedOutput["contextModification"]
                ?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            if (contextMod != null && contextMod.length > MAX_CONTEXT_MODIFICATION_SIZE) {
                LOG.warn("[HookRunner] contextModification of ${contextMod.length} bytes, truncating to $MAX_CONTEXT_MODIFICATION_SIZE")
                contextMod = contextMod.take(MAX_CONTEXT_MODIFICATION_SIZE) +
                    "\n\n[... context truncated due to size limit ...]"
            }

            val errorMessage = parsedOutput["errorMessage"]
                ?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            if (cancel && event.cancellable) {
                return HookResult.Cancel(
                    reason = errorMessage ?: "Hook requested cancellation",
                    contextModification = contextMod
                )
            }

            return HookResult.Proceed(contextModification = contextMod)
        }

        // No valid JSON — fall back to exit code interpretation
        // Cline: non-zero exit without JSON = execution error, still fail-open
        if (result.exitCode != 0 && event.cancellable) {
            val reason = result.stderr.trim().takeIf { it.isNotEmpty() }
                ?: "Hook exited with code ${result.exitCode}"
            return HookResult.Cancel(reason = reason)
        }

        return HookResult.Proceed()
    }

    /**
     * Parse JSON output from stdout, with fallback extraction.
     *
     * Ported from Cline's parseJsonOutput in StdioHookRunner:
     * 1. Try direct JSON.parse
     * 2. If that fails, scan from the end to find the last complete JSON object
     *    (handles debug output before/after the JSON response)
     */
    internal fun parseJsonOutput(stdout: String): JsonObject? {
        if (stdout.isBlank()) return null

        // Try direct parse first
        try {
            val element = Json.parseToJsonElement(stdout.trim())
            if (element is JsonObject) {
                return validateHookOutput(element)
            }
        } catch (_: Exception) {
            // Fall through to extraction
        }

        // Cline's fallback: scan from end to find last complete JSON object
        val lines = stdout.split("\n")
        var jsonCandidate = StringBuilder()
        var braceCount = 0
        var startCollecting = false

        for (i in lines.indices.reversed()) {
            val line = lines[i].trimEnd()
            for (j in line.indices.reversed()) {
                when (line[j]) {
                    '}' -> {
                        braceCount++
                        if (!startCollecting) startCollecting = true
                    }
                    '{' -> braceCount--
                }
            }
            if (startCollecting) {
                jsonCandidate.insert(0, "$line\n")
            }
            if (startCollecting && braceCount == 0) break
        }

        val candidate = jsonCandidate.toString().trim()
        if (candidate.isNotEmpty()) {
            try {
                val firstBrace = candidate.indexOf('{')
                val cleaned = if (firstBrace > 0) candidate.substring(firstBrace) else candidate
                val element = Json.parseToJsonElement(cleaned)
                if (element is JsonObject) {
                    return validateHookOutput(element)
                }
            } catch (_: Exception) {
                // Could not extract JSON
            }
        }

        return null
    }

    /**
     * Validate hook output JSON structure.
     *
     * Ported from Cline's validateHookOutput:
     * - cancel must be boolean if present
     * - contextModification must be string if present
     * - errorMessage must be string if present
     * - Rejects deprecated shouldContinue field
     */
    private fun validateHookOutput(obj: JsonObject): JsonObject? {
        // Reject deprecated shouldContinue
        if (obj.containsKey("shouldContinue")) {
            LOG.warn("[HookRunner] Hook output contains deprecated 'shouldContinue' field. Use 'cancel: true' instead.")
            return null
        }

        // Validate types
        obj["cancel"]?.let {
            if (it !is JsonPrimitive || it.booleanOrNull == null) {
                LOG.warn("[HookRunner] Invalid hook output: 'cancel' must be a boolean")
                return null
            }
        }
        obj["contextModification"]?.let {
            if (it !is JsonPrimitive || !it.isString) {
                LOG.warn("[HookRunner] Invalid hook output: 'contextModification' must be a string")
                return null
            }
        }
        obj["errorMessage"]?.let {
            if (it !is JsonPrimitive || !it.isString) {
                LOG.warn("[HookRunner] Invalid hook output: 'errorMessage' must be a string")
                return null
            }
        }

        return obj
    }

    /**
     * Read from an input stream with a size limit.
     * Matches Cline's 1MB MAX_HOOK_OUTPUT_SIZE truncation.
     */
    private fun readLimited(stream: java.io.InputStream, maxBytes: Int): String {
        val buffer = ByteArray(8192)
        val output = StringBuilder()
        var totalRead = 0

        while (totalRead < maxBytes) {
            val bytesRead = stream.read(buffer, 0, minOf(buffer.size, maxBytes - totalRead))
            if (bytesRead == -1) break
            output.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
            totalRead += bytesRead
        }

        // Drain remaining to prevent process blocking
        if (totalRead >= maxBytes) {
            try {
                while (stream.read(buffer) != -1) { /* discard */ }
            } catch (_: Exception) { /* ignore */ }
        }

        return output.toString()
    }

    /** Convert Any? to JsonElement for JSON serialization. */
    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) })
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}

/**
 * Raw result from a process execution.
 */
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)
