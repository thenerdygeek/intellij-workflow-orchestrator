package com.workflow.orchestrator.cody.agent.integrated

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.agent.CodyAgentProvider
import com.workflow.orchestrator.cody.agent.CodyAgentServer
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent provider that reuses the official Sourcegraph Cody plugin's agent.
 *
 * This class is ONLY loaded when `com.sourcegraph.jetbrains` is installed
 * (registered via plugin-withCody.xml). It uses reflection to access Cody's
 * internal `CodyAgentService` — no compile-time dependency needed.
 *
 * If any reflection call fails, [isAvailable] returns false and the
 * [StandaloneCodyAgentProvider][com.workflow.orchestrator.cody.agent.StandaloneCodyAgentProvider]
 * takes over.
 */
class SourcegraphPluginAgentProvider : CodyAgentProvider {

    private val log = Logger.getInstance(SourcegraphPluginAgentProvider::class.java)

    override val displayName = "Sourcegraph Cody Plugin"
    override val priority = 100

    // Cache server adapters per project location hash to avoid repeated reflection
    // Uses locationHash instead of Project as key to prevent memory leaks
    private val serverCache = ConcurrentHashMap<String, CodyAgentServer>()

    override suspend fun isAvailable(project: Project): Boolean {
        return try {
            val agentServiceClass = loadAgentServiceClass() ?: return false
            val service = project.getService(agentServiceClass) ?: return false
            // Check if the agent is connected by looking for an isConnected-like method
            val isConnected = findIsConnectedMethod(agentServiceClass, service)
            if (!isConnected) {
                log.debug("Sourcegraph Cody plugin is installed but agent is not connected")
            }
            isConnected
        } catch (e: Exception) {
            log.debug("Sourcegraph Cody plugin availability check failed", e)
            false
        }
    }

    override suspend fun acquireServer(project: Project): CodyAgentServer {
        val key = project.locationHash
        return serverCache.computeIfAbsent(key) {
            val sourcegraphServer = extractServerProxy(project)
                ?: throw IllegalStateException("Cannot acquire Sourcegraph Cody agent server")
            createAdapter(sourcegraphServer)
        }
    }

    override fun isRunning(project: Project): Boolean {
        return try {
            val agentServiceClass = loadAgentServiceClass() ?: return false
            val service = project.getService(agentServiceClass) ?: return false
            findIsConnectedMethod(agentServiceClass, service)
        } catch (e: Exception) {
            false
        }
    }

    override fun getServerOrNull(project: Project): CodyAgentServer? {
        val key = project.locationHash
        return if (isRunning(project)) serverCache[key] ?: try {
            val server = extractServerProxy(project) ?: return null
            createAdapter(server).also { serverCache[key] = it }
        } catch (e: Exception) {
            null
        } else null
    }

    override fun handlesDocumentSync(): Boolean = true

    override fun dispose(project: Project) {
        serverCache.remove(project.locationHash)
        // Don't dispose Sourcegraph's agent — it's managed by their plugin
    }

    private fun loadAgentServiceClass(): Class<*>? {
        return try {
            Class.forName("com.sourcegraph.cody.agent.CodyAgentService")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    private fun findIsConnectedMethod(serviceClass: Class<*>, service: Any): Boolean {
        // Try common method names the Sourcegraph plugin might expose
        for (methodName in listOf("isConnected", "getIsConnected")) {
            try {
                val method = serviceClass.getMethod(methodName)
                return method.invoke(service) as? Boolean ?: false
            } catch (e: NoSuchMethodException) {
                continue
            }
        }
        // Fallback: try to get the agent and check if it's non-null
        return try {
            val agentField = serviceClass.getDeclaredField("agent").apply { isAccessible = true }
            agentField.get(service) != null
        } catch (e: Exception) {
            // If we can't determine connection status, assume available
            // (acquireServer will fail with a clear error if not)
            true
        }
    }

    /**
     * Extracts the JSON-RPC server proxy from the Sourcegraph Cody plugin.
     * Path: CodyAgentService → agent (CodyAgent) → server (CodyAgentServer proxy)
     */
    private fun extractServerProxy(project: Project): Any? {
        return try {
            val serviceClass = loadAgentServiceClass() ?: return null
            val service = project.getService(serviceClass) ?: return null

            // Try to access the agent field or method
            val agent = tryGetField(service, "agent")
                ?: tryCallMethod(service, "getAgent")
                ?: return null

            // Get the server proxy from the agent
            tryGetField(agent, "server")
                ?: tryCallMethod(agent, "getServer")
        } catch (e: Exception) {
            log.warn("Failed to extract Sourcegraph server proxy", e)
            null
        }
    }

    /**
     * Creates a dynamic proxy that implements our [CodyAgentServer] and
     * forwards each method call to the Sourcegraph server object via reflection.
     *
     * This works because both sides implement the same JSON-RPC protocol
     * with identical method names and compatible signatures.
     */
    private fun createAdapter(sourcegraphServer: Any): CodyAgentServer {
        val handler = SourcegraphServerInvocationHandler(sourcegraphServer)
        return Proxy.newProxyInstance(
            CodyAgentServer::class.java.classLoader,
            arrayOf(CodyAgentServer::class.java),
            handler
        ) as CodyAgentServer
    }

    private fun tryGetField(obj: Any, fieldName: String): Any? {
        return try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(obj)
        } catch (e: Exception) {
            null
        }
    }

    private fun tryCallMethod(obj: Any, methodName: String): Any? {
        return try {
            val method = obj.javaClass.getMethod(methodName)
            method.invoke(obj)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * InvocationHandler that forwards method calls from our [CodyAgentServer]
 * to the Sourcegraph plugin's server proxy using reflection.
 *
 * Method matching is by name — since both implement the same Cody Agent
 * JSON-RPC protocol, method names are identical. Parameters are forwarded
 * as-is for simple types; for complex DTOs, JSON round-tripping is used.
 */
private class SourcegraphServerInvocationHandler(
    private val target: Any
) : InvocationHandler {

    private val log = Logger.getInstance(SourcegraphServerInvocationHandler::class.java)
    private val gson = com.google.gson.Gson()

    // Method cache to avoid repeated reflection lookups
    private val methodCache = ConcurrentHashMap<String, Method?>()

    override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
        // Handle Object methods (toString, equals, hashCode)
        if (method.declaringClass == Any::class.java) {
            return when (method.name) {
                "toString" -> "SourcegraphServerAdapter"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }

        val targetMethod = resolveMethod(method)
        if (targetMethod == null) {
            log.warn("Method '${method.name}' not found on Sourcegraph server, returning empty future")
            return if (method.returnType == CompletableFuture::class.java) {
                CompletableFuture.completedFuture(null)
            } else null
        }

        return try {
            if (args == null || args.isEmpty()) {
                targetMethod.invoke(target)
            } else {
                // Convert our DTO params to the Sourcegraph equivalent via JSON round-trip
                val convertedArgs = args.mapIndexed { index, arg ->
                    convertParam(arg, targetMethod.parameterTypes[index])
                }.toTypedArray()
                targetMethod.invoke(target, *convertedArgs)
            }
        } catch (e: Exception) {
            log.warn("Failed to invoke '${method.name}' on Sourcegraph server", e)
            if (method.returnType == CompletableFuture::class.java) {
                CompletableFuture.failedFuture<Any>(e)
            } else null
        }
    }

    private fun resolveMethod(ourMethod: Method): Method? {
        val cacheKey = "${ourMethod.name}/${ourMethod.parameterCount}"
        return methodCache.getOrPut(cacheKey) {
            try {
                // Find method on target by name with same parameter count
                target.javaClass.methods.firstOrNull { m ->
                    m.name == ourMethod.name && m.parameterCount == ourMethod.parameterCount
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Converts a parameter from our DTO type to the target's expected type.
     * Uses JSON round-trip: serialize our object → deserialize as their type.
     * Primitives and strings pass through directly.
     */
    private fun convertParam(arg: Any, targetType: Class<*>): Any {
        if (targetType.isInstance(arg)) return arg
        if (targetType == String::class.java) return arg.toString()

        // JSON round-trip for complex DTOs
        val json = gson.toJson(arg)
        return gson.fromJson(json, targetType)
    }
}
