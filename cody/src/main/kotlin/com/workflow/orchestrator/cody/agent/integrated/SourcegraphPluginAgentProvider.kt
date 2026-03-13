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
            // Use the static isConnected(project) method on CodyAgentService
            val isConnectedMethod = try {
                agentServiceClass.getMethod("isConnected", Project::class.java)
            } catch (e: NoSuchMethodException) {
                log.debug("CodyAgentService.isConnected(Project) not found, falling back")
                null
            }
            val connected = if (isConnectedMethod != null) {
                isConnectedMethod.invoke(null, project) as? Boolean ?: false
            } else {
                // Fallback: try instance-level check
                val service = project.getService(agentServiceClass) ?: return false
                findIsConnectedLegacy(agentServiceClass, service)
            }
            if (!connected) {
                log.debug("Sourcegraph Cody plugin is installed but agent is not connected")
            }
            connected
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
            logServerMethods(sourcegraphServer)
            createAdapter(sourcegraphServer)
        }
    }

    /**
     * Dumps all methods on the Sourcegraph server proxy to the log.
     * Invaluable for debugging protocol mismatches between our interface and theirs.
     */
    private fun logServerMethods(server: Any) {
        try {
            val methods = server.javaClass.methods
                .filter { it.declaringClass != Any::class.java }
                .sortedBy { it.name }
            log.info("[Cody:Integrated] Sourcegraph server proxy class: ${server.javaClass.name}")
            log.info("[Cody:Integrated] Exposed methods (${methods.size}):")
            for (m in methods) {
                val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                val annotations = m.annotations.joinToString(" ") { "@${it.annotationClass.simpleName}" }
                log.info("[Cody:Integrated]   ${m.name}($params) -> ${m.returnType.simpleName} $annotations")
            }
        } catch (e: Exception) {
            log.debug("[Cody:Integrated] Failed to log server methods", e)
        }
    }

    override fun isRunning(project: Project): Boolean {
        return try {
            val agentServiceClass = loadAgentServiceClass() ?: return false
            val isConnectedMethod = try {
                agentServiceClass.getMethod("isConnected", Project::class.java)
            } catch (e: NoSuchMethodException) { null }
            if (isConnectedMethod != null) {
                isConnectedMethod.invoke(null, project) as? Boolean ?: false
            } else {
                val service = project.getService(agentServiceClass) ?: return false
                findIsConnectedLegacy(agentServiceClass, service)
            }
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

    private fun findIsConnectedLegacy(serviceClass: Class<*>, service: Any): Boolean {
        // Try instance-level isConnected() (no-arg)
        for (methodName in listOf("isConnected", "getIsConnected")) {
            try {
                val method = serviceClass.getMethod(methodName)
                return method.invoke(service) as? Boolean ?: false
            } catch (e: NoSuchMethodException) {
                continue
            }
        }
        // Fallback: check if codyAgent future is completed with a non-null value
        return try {
            val future = tryGetField(service, "codyAgent")
            if (future is java.util.concurrent.CompletableFuture<*>) {
                future.getNow(null) != null
            } else {
                // Legacy: try "agent" field
                val agentField = serviceClass.getDeclaredField("agent").apply { isAccessible = true }
                agentField.get(service) != null
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extracts the JSON-RPC server proxy from the Sourcegraph Cody plugin.
     * Path: CodyAgentService → codyAgent (CompletableFuture<CodyAgent>) → .get() → server
     */
    private fun extractServerProxy(project: Project): Any? {
        return try {
            val serviceClass = loadAgentServiceClass() ?: return null
            val service = project.getService(serviceClass) ?: return null

            // Log CodyAgentService structure for debugging
            logClassStructure("CodyAgentService", serviceClass)

            // The field is "codyAgent: CompletableFuture<CodyAgent>"
            val future = tryGetField(service, "codyAgent") ?: run {
                log.debug("Field 'codyAgent' not found on CodyAgentService, trying legacy 'agent'")
                tryGetField(service, "agent")
                    ?: tryCallMethod(service, "getAgent")
            } ?: return null

            // Unwrap CompletableFuture if needed
            val agent = if (future is java.util.concurrent.CompletableFuture<*>) {
                future.getNow(null) ?: run {
                    log.debug("CodyAgent CompletableFuture is not yet completed")
                    return null
                }
            } else {
                future
            }

            // Log CodyAgent structure
            logClassStructure("CodyAgent", agent.javaClass)

            // Get the server proxy from the CodyAgent
            tryGetField(agent, "server")
                ?: tryCallMethod(agent, "getServer")
        } catch (e: Exception) {
            log.warn("Failed to extract Sourcegraph server proxy", e)
            null
        }
    }

    private fun logClassStructure(label: String, clazz: Class<*>) {
        try {
            val fields = clazz.declaredFields.sortedBy { it.name }
            log.info("[Cody:Integrated] $label fields (${fields.size}):")
            for (f in fields) {
                log.info("[Cody:Integrated]   ${f.name}: ${f.type.simpleName}")
            }
            val methods = clazz.declaredMethods.filter { it.name != "access\$" }.sortedBy { it.name }
            log.info("[Cody:Integrated] $label methods (${methods.size}):")
            for (m in methods) {
                val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                log.info("[Cody:Integrated]   ${m.name}($params) -> ${m.returnType.simpleName}")
            }
        } catch (e: Exception) {
            log.debug("[Cody:Integrated] Failed to log $label structure", e)
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
            // Fallback: use generic request(methodName, params) if available
            val jsonRpcName = method.getAnnotation(org.eclipse.lsp4j.jsonrpc.services.JsonRequest::class.java)?.value
            if (jsonRpcName != null && method.returnType == CompletableFuture::class.java) {
                log.info("Method '${method.name}' not found directly, trying generic request('$jsonRpcName', ...)")
                return tryGenericRequest(jsonRpcName, args)
            }
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
                val paramCount = ourMethod.parameterCount
                // Try exact name match first
                target.javaClass.methods.firstOrNull { m ->
                    m.name == ourMethod.name && m.parameterCount == paramCount
                }
                // Sourcegraph uses underscore naming derived from JSON-RPC method names:
                // "editTask/accept" → "editTask_accept" (slash replaced with underscore,
                // camelCase preserved within each segment). Use the @JsonRequest annotation
                // value to derive the correct Sourcegraph method name.
                ?: run {
                    val jsonRpcName = ourMethod.getAnnotation(
                        org.eclipse.lsp4j.jsonrpc.services.JsonRequest::class.java
                    )?.value ?: ourMethod.getAnnotation(
                        org.eclipse.lsp4j.jsonrpc.services.JsonNotification::class.java
                    )?.value
                    if (jsonRpcName != null) {
                        val underscored = jsonRpcName.replace('/', '_')
                        target.javaClass.methods.firstOrNull { m ->
                            m.name == underscored && m.parameterCount == paramCount
                        }
                    } else {
                        // Fallback: naive camelCase to underscore conversion
                        val underscored = camelToUnderscore(ourMethod.name)
                        if (underscored != ourMethod.name) {
                            target.javaClass.methods.firstOrNull { m ->
                                m.name == underscored && m.parameterCount == paramCount
                            }
                        } else null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Fallback: call the generic request(String, Object) method on the Sourcegraph server.
     * This allows us to invoke any JSON-RPC method by name even if it's not on the proxy interface.
     */
    private fun tryGenericRequest(methodName: String, args: Array<out Any>?): Any? {
        return try {
            val requestMethod = target.javaClass.methods.firstOrNull { m ->
                m.name == "request" && m.parameterCount == 2 &&
                    m.parameterTypes[0] == String::class.java
            }
            if (requestMethod != null) {
                val param = if (args != null && args.isNotEmpty()) {
                    // Convert our DTO to a generic object via JSON round-trip
                    val json = gson.toJson(args[0])
                    gson.fromJson(json, Any::class.java)
                } else null
                requestMethod.invoke(target, methodName, param)
            } else {
                log.warn("Generic request() method not found on Sourcegraph server")
                CompletableFuture.completedFuture(null)
            }
        } catch (e: Exception) {
            log.warn("Generic request('$methodName') failed: ${e.message}")
            CompletableFuture.failedFuture<Any>(e)
        }
    }

    /** Fallback: naive camelCase to underscore. Only used when @JsonRequest annotation is absent. */
    private fun camelToUnderscore(name: String): String {
        val sb = StringBuilder()
        for ((i, c) in name.withIndex()) {
            if (c.isUpperCase() && i > 0) {
                sb.append('_')
                sb.append(c.lowercaseChar())
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
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
