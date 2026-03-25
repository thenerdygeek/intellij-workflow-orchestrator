package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.intellij.debugger.engine.SuspendContextImpl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Forces the current method to return immediately with a specified value.
 * Skips remaining method execution. Requires debug session to be paused.
 * Cannot be used on native methods.
 */
class ForceReturnTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "force_return"
    override val description = "Force the current method to return immediately with a specified value. " +
        "Skips remaining method execution. Use to test alternate code paths without code changes. " +
        "Cannot be used on native methods. Requires debug session to be paused."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            ),
            "return_value" to ParameterProperty(
                type = "string",
                description = "Value as string: \"null\", \"42\", \"true\", \"\\\"hello\\\"\". Omit for void methods."
            ),
            "return_type" to ParameterProperty(
                type = "string",
                description = "Return type: \"void\", \"null\", \"int\", \"long\", \"boolean\", \"string\", \"double\", \"float\", \"char\", \"byte\", \"short\". Default: \"auto\"",
                enumValues = listOf("auto", "void", "null", "int", "long", "boolean", "string", "double", "float", "char", "byte", "short")
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog)"
            )
        ),
        required = listOf("description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val returnValue = params["return_value"]?.jsonPrimitive?.content
        val returnType = params["return_type"]?.jsonPrimitive?.content ?: "auto"

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (!session.isSuspended) {
            return ToolResult(
                "Session is not suspended. Cannot force return while running.",
                "Not suspended",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            controller.executeOnManagerThread(session) { debugProcess, vmProxy ->
                if (!vmProxy.canForceEarlyReturn()) {
                    throw IllegalStateException(
                        "JVM does not support force early return. " +
                            "This requires a JDWP-compliant JVM with canForceEarlyReturn capability."
                    )
                }

                val suspendContext = debugProcess.suspendManager.getPausedContext()
                    ?: throw IllegalStateException("No suspended context available. Ensure the session is paused.")

                val thread = (suspendContext as? SuspendContextImpl)?.thread
                    ?: throw IllegalStateException("Cannot access the suspended thread. The thread may not be available.")

                // Resolve the effective return type
                val effectiveType = if (returnType == "auto") {
                    inferReturnType(returnValue)
                } else {
                    returnType
                }

                // Create JDI Value based on return_type
                val value: com.sun.jdi.Value? = when (effectiveType) {
                    "void" -> vmProxy.mirrorOfVoid()
                    "null" -> null
                    "int" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for int type")
                        vmProxy.mirrorOf(v.toInt())
                    }
                    "long" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for long type")
                        vmProxy.mirrorOf(v.toLong())
                    }
                    "boolean" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for boolean type")
                        vmProxy.mirrorOf(v.toBoolean())
                    }
                    "string" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for string type")
                        vmProxy.mirrorOf(v)
                    }
                    "double" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for double type")
                        vmProxy.mirrorOf(v.toDouble())
                    }
                    "float" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for float type")
                        vmProxy.mirrorOf(v.toFloat())
                    }
                    "char" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for char type")
                        if (v.isEmpty()) throw IllegalArgumentException("return_value for char type cannot be empty")
                        vmProxy.mirrorOf(v[0])
                    }
                    "byte" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for byte type")
                        vmProxy.mirrorOf(v.toByte())
                    }
                    "short" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for short type")
                        vmProxy.mirrorOf(v.toShort())
                    }
                    else -> throw IllegalArgumentException("Unknown return type: $effectiveType")
                }

                // Force return
                thread.forceEarlyReturn(value)
            }

            val sb = StringBuilder()
            sb.append("Forced early return from current method.\n")
            sb.append("Return type: $returnType\n")
            if (returnValue != null) {
                sb.append("Return value: $returnValue\n")
            } else {
                sb.append("Return value: (void/none)\n")
            }
            sb.append("The method will return immediately, skipping remaining execution.")

            val content = sb.toString()
            ToolResult(content, "Forced return with ${returnValue ?: "void"}", TokenEstimator.estimate(content))
        } catch (e: com.sun.jdi.IncompatibleThreadStateException) {
            ToolResult(
                "Thread is not in a compatible state for force return. The thread must be suspended at a non-native frame.",
                "Incompatible thread state",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } catch (e: com.sun.jdi.NativeMethodException) {
            ToolResult(
                "Cannot force return from a native method. Step out of the native method first.",
                "Native method",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } catch (e: com.sun.jdi.InvalidTypeException) {
            ToolResult(
                "Type mismatch: the return value type does not match the method's return type. ${e.message}",
                "Type mismatch",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } catch (e: IllegalStateException) {
            ToolResult(
                "Error: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } catch (e: IllegalArgumentException) {
            ToolResult(
                "Invalid parameter: ${e.message}",
                "Invalid parameter",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } catch (e: NumberFormatException) {
            ToolResult(
                "Invalid number format for return value: ${e.message}",
                "Number format error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } catch (e: Exception) {
            ToolResult(
                "Error forcing return: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    /**
     * Infers the return type from the value string when type is "auto".
     */
    private fun inferReturnType(returnValue: String?): String {
        if (returnValue == null) return "void"
        return when {
            returnValue == "null" -> "null"
            returnValue == "true" || returnValue == "false" -> "boolean"
            returnValue.startsWith("\"") && returnValue.endsWith("\"") -> "string"
            returnValue.contains(".") -> "double"
            returnValue.toLongOrNull() != null -> {
                val num = returnValue.toLong()
                if (num in Int.MIN_VALUE..Int.MAX_VALUE) "int" else "long"
            }
            else -> "string"
        }
    }
}
