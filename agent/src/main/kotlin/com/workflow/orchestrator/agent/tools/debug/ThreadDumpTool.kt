package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Gets a thread dump from a debug session showing all threads, their states
 * (RUNNING, BLOCKED, WAITING), and stack traces. Essential for diagnosing
 * deadlocks and concurrency issues.
 */
class ThreadDumpTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "thread_dump"
    override val description = "Get a thread dump showing all threads, their states (RUNNING, BLOCKED, WAITING), " +
        "and stack traces. Essential for diagnosing deadlocks and concurrency issues. " +
        "Requires an active debug session."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            ),
            "include_stacks" to ParameterProperty(
                type = "boolean",
                description = "Include stack traces for each thread (default: true)"
            ),
            "max_frames" to ParameterProperty(
                type = "integer",
                description = "Maximum number of stack frames per thread (default: 20)"
            ),
            "include_daemon" to ParameterProperty(
                type = "boolean",
                description = "Include daemon threads (default: false)"
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val includeStacks = params["include_stacks"]?.jsonPrimitive?.booleanOrNull ?: true
        val maxFrames = params["max_frames"]?.jsonPrimitive?.intOrNull ?: 20
        val includeDaemon = params["include_daemon"]?.jsonPrimitive?.booleanOrNull ?: false

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return try {
            val threadInfos = controller.executeOnManagerThread(session) { _, vmProxy ->
                val vm = vmProxy.virtualMachine
                val allThreads = vm.allThreads()
                allThreads.mapNotNull { thread ->
                    val isDaemon = inferDaemon(thread)

                    if (!includeDaemon && isDaemon) return@mapNotNull null

                    val threadName = try {
                        thread.name()
                    } catch (_: Exception) {
                        "<unknown>"
                    }

                    val status = try {
                        thread.status()
                    } catch (_: Exception) {
                        THREAD_STATUS_UNKNOWN
                    }

                    val threadId = try {
                        thread.uniqueID()
                    } catch (_: Exception) {
                        -1L
                    }

                    val isSuspended = try {
                        thread.isSuspended
                    } catch (_: Exception) {
                        false
                    }

                    val frames = if (includeStacks) {
                        try {
                            thread.frames().take(maxFrames).map { frame ->
                                val location = frame.location()
                                val className = try { location.declaringType().name() } catch (_: Exception) { "<unknown>" }
                                val methodName = try { location.method().name() } catch (_: Exception) { "<unknown>" }
                                val sourceName = try { location.sourceName() } catch (_: Exception) { null }
                                val lineNumber = try { location.lineNumber() } catch (_: Exception) { -1 }
                                ThreadFrameInfo(className, methodName, sourceName, lineNumber)
                            }
                        } catch (_: Exception) {
                            null // frames unavailable (thread not suspended)
                        }
                    } else {
                        null
                    }

                    ThreadInfo(
                        name = threadName,
                        id = threadId,
                        status = status,
                        statusText = statusToString(status),
                        isDaemon = isDaemon,
                        isSuspended = isSuspended,
                        frames = frames
                    )
                }
            }

            if (threadInfos.isEmpty()) {
                return ToolResult(
                    "Thread dump returned empty — VM may be disconnected.",
                    "Empty thread dump",
                    ToolResult.ERROR_TOKEN_ESTIMATE
                )
            }

            val suspendedCount = threadInfos.count { it.isSuspended }
            val sb = StringBuilder()
            sb.append("Thread dump (${threadInfos.size} threads, $suspendedCount suspended):\n")

            for (thread in threadInfos) {
                sb.append("\n[${thread.statusText}] ${thread.name} (id=${thread.id}")
                if (thread.isDaemon) sb.append(", daemon")
                sb.append(")\n")

                if (thread.frames == null && includeStacks) {
                    sb.append("  (frames unavailable — thread not suspended)\n")
                } else if (thread.frames != null) {
                    for (frame in thread.frames) {
                        val sourceRef = if (frame.sourceName != null && frame.lineNumber > 0) {
                            "${frame.sourceName}:${frame.lineNumber}"
                        } else if (frame.sourceName != null) {
                            frame.sourceName
                        } else {
                            "Unknown Source"
                        }
                        sb.append("  ${frame.className}.${frame.methodName}($sourceRef)\n")
                    }
                }
            }

            val content = sb.toString().trimEnd()
            ToolResult(
                content,
                "Thread dump: ${threadInfos.size} threads, $suspendedCount suspended",
                TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult(
                "Error getting thread dump: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    /**
     * Infers daemon status via reflection since ThreadReference.isDaemon()
     * may not be available in all JDI stub versions.
     */
    private fun inferDaemon(thread: com.sun.jdi.ThreadReference): Boolean {
        return try {
            // Try reflection for isDaemon() method
            val method = thread.javaClass.getMethod("isDaemon")
            method.invoke(thread) as? Boolean ?: false
        } catch (_: Exception) {
            // Fall back to thread group name heuristic
            try {
                val groupName = thread.threadGroup()?.name() ?: ""
                groupName.contains("daemon", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }
    }

    private data class ThreadFrameInfo(
        val className: String,
        val methodName: String,
        val sourceName: String?,
        val lineNumber: Int
    )

    private data class ThreadInfo(
        val name: String,
        val id: Long,
        val status: Int,
        val statusText: String,
        val isDaemon: Boolean,
        val isSuspended: Boolean,
        val frames: List<ThreadFrameInfo>?
    )

    companion object {
        private const val THREAD_STATUS_UNKNOWN = -1
        private const val THREAD_STATUS_ZOMBIE = 0
        private const val THREAD_STATUS_RUNNING = 1
        private const val THREAD_STATUS_SLEEPING = 2
        private const val THREAD_STATUS_MONITOR = 3
        private const val THREAD_STATUS_WAIT = 4
        private const val THREAD_STATUS_NOT_STARTED = 5

        fun statusToString(status: Int): String = when (status) {
            THREAD_STATUS_RUNNING -> "RUNNING"
            THREAD_STATUS_SLEEPING -> "SLEEPING"
            THREAD_STATUS_MONITOR -> "BLOCKED"
            THREAD_STATUS_WAIT -> "WAITING"
            THREAD_STATUS_NOT_STARTED -> "NOT_STARTED"
            THREAD_STATUS_ZOMBIE -> "TERMINATED"
            else -> "UNKNOWN"
        }
    }
}
