package com.workflow.orchestrator.agent.tools.framework.endpoints

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import java.lang.reflect.Method

/**
 * Normalized async/messaging endpoint record — one per Kafka topic,
 * RabbitMQ queue, JMS destination, etc.
 */
internal data class DiscoveredAsyncEndpoint(
    val mqType: String,           // e.g. "KAFKA_TOPIC_TYPE", "RABBIT_MQ_QUEUE_TYPE", "ACTIVE_MQ_TOPIC_TYPE"
    val destinationName: String,  // the topic / queue name
    val accessType: String,       // SEND, RECEIVE, SEND_AND_RECEIVE, STREAM_FORWARDING, UNKNOWN
    val filePath: String?,
    val lineNumber: Int?,
    val handlerClass: String?,
    val handlerMethod: String?,
)

/**
 * Reflective facade over com.intellij.microservices.jvm.mq. Discovers
 * async messaging endpoints (Kafka, RabbitMQ, JMS, ActiveMQ) via the
 * MQResolverManager. Self-gates: returns empty list when the MQ SPI
 * or its resolvers (spring-messaging etc.) are not on the classpath.
 *
 * Signatures verified 2026-04-21 against IU-2025.1. See
 * docs/research/2026-04-21-intellij-async-endpoints-api-signatures.md.
 */
internal object AsyncEndpointsDiscoverer {

    private const val MQ_RESOLVER_MANAGER_FQN = "com.intellij.microservices.jvm.mq.MQResolverManager"
    private const val MQ_TYPES_FQN = "com.intellij.microservices.jvm.mq.MQTypes"

    fun discover(project: Project): List<DiscoveredAsyncEndpoint> {
        val api = api() ?: return emptyList()

        val manager = try {
            project.getService(api.managerClass) ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }

        val out = mutableListOf<DiscoveredAsyncEndpoint>()
        for ((typeName, typeInstance) in api.mqTypes) {
            val variants: List<Any> = try {
                val seq = api.getAllVariants.invoke(manager, typeInstance)
                // getAllVariants returns Sequence<MQTargetInfo>; convert to List reflectively.
                // Kotlin Sequence.toList() is compiled as a static extension — find it on
                // SequencesKt or fall back to the Iterable conversion path.
                toListReflective(seq)
            } catch (_: Exception) {
                emptyList()
            }

            for (target in variants) {
                try {
                    val destination = invoke(target, "getDestination") ?: continue
                    val name = invoke(destination, "getName") as? String ?: continue
                    val accessTypeObj = invoke(target, "getAccessType")
                    val accessTypeName = (if (accessTypeObj != null) invoke(accessTypeObj, "getName") else null) as? String ?: "UNKNOWN"
                    val psi = invoke(target, "resolveToPsiElement") as? PsiElement
                    val file = psi?.containingFile?.virtualFile?.path
                    val line = psi?.let { elem ->
                        val doc = PsiDocumentManager.getInstance(project)
                            .getDocument(elem.containingFile)
                        doc?.getLineNumber(elem.textOffset)?.plus(1)
                    }
                    val handlerClass = psi?.let { extractContainingClass(it) }
                    val handlerMethod = psi?.let { extractContainingMethod(it) }
                    out += DiscoveredAsyncEndpoint(
                        mqType = typeName,
                        destinationName = name,
                        accessType = accessTypeName,
                        filePath = file,
                        lineNumber = line,
                        handlerClass = handlerClass,
                        handlerMethod = handlerMethod,
                    )
                } catch (_: Exception) {
                    // Skip malformed variants; don't fail the whole discovery.
                }
            }
        }
        return out
    }

    /**
     * Converts an opaque Sequence<*> object to a List<Any> without a compile-time
     * import. Tries multiple paths that Kotlin Sequence can expose:
     *  1. Direct `.toList()` instance method (Kotlin Sequence has this as extension but
     *     it may be inlined/compiled into the class — check first)
     *  2. SequencesKt.toList(seq) static helper
     *  3. Iterable.forEach fallback via iterator()
     */
    private fun toListReflective(seq: Any?): List<Any> {
        if (seq == null) return emptyList()

        // Try instance method first (some Kotlin runtime versions add it)
        seq.javaClass.methods.firstOrNull { it.name == "toList" && it.parameterCount == 0 }
            ?.let { m ->
                @Suppress("UNCHECKED_CAST")
                return (m.invoke(seq) as? List<Any>) ?: emptyList()
            }

        // Try SequencesKt.toList(Sequence)
        try {
            val sequencesKt = Class.forName("kotlin.sequences.SequencesKt")
            val toList = sequencesKt.methods.firstOrNull { it.name == "toList" && it.parameterCount == 1 }
            if (toList != null) {
                @Suppress("UNCHECKED_CAST")
                return (toList.invoke(null, seq) as? List<Any>) ?: emptyList()
            }
        } catch (_: Exception) { /* fall through */ }

        // Iterator fallback
        return try {
            val iteratorMethod = seq.javaClass.getMethod("iterator")
            val iterator = iteratorMethod.invoke(seq)
            val hasNext = iterator.javaClass.getMethod("hasNext")
            val next = iterator.javaClass.getMethod("next")
            val result = mutableListOf<Any>()
            while (hasNext.invoke(iterator) as Boolean) {
                result += next.invoke(iterator) ?: continue
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractContainingClass(element: PsiElement): String? {
        var e: PsiElement? = element
        while (e != null) {
            if (e is PsiClass) return e.qualifiedName
            e = e.parent
        }
        return null
    }

    private fun extractContainingMethod(element: PsiElement): String? {
        var e: PsiElement? = element
        while (e != null) {
            if (e is PsiMethod) return e.name
            e = e.parent
        }
        return null
    }

    @Volatile private var cached: Api? = null
    @Volatile private var initialized: Boolean = false

    private fun api(): Api? {
        if (initialized) return cached
        synchronized(this) {
            if (initialized) return cached
            cached = buildApi()
            initialized = true
            return cached
        }
    }

    private fun buildApi(): Api? {
        return try {
            val managerClass = Class.forName(MQ_RESOLVER_MANAGER_FQN)
            val typesClass = Class.forName(MQ_TYPES_FQN)
            val getAllVariants = managerClass.methods.firstOrNull { it.name == "getAllVariants" && it.parameterCount == 1 }
                ?: return null

            // Load MQType interface to filter out non-MQType static fields (e.g., Kotlin
            // object INSTANCE fields). If MQType itself isn't loadable, fall back to
            // accepting all static fields — the getAllVariants call will throw
            // individually per bad field and we'll swallow it.
            val mqTypeInterface = try {
                Class.forName("com.intellij.microservices.jvm.mq.MQType")
            } catch (_: ClassNotFoundException) { null }

            val mqTypes = typesClass.fields
                .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .mapNotNull { field ->
                    val instance = try { field.get(null) } catch (_: Exception) { return@mapNotNull null }
                    if (instance == null) return@mapNotNull null
                    if (mqTypeInterface != null && !mqTypeInterface.isInstance(instance)) return@mapNotNull null
                    field.name to instance
                }

            if (mqTypes.isEmpty()) return null
            Api(managerClass = managerClass, getAllVariants = getAllVariants, mqTypes = mqTypes)
        } catch (_: Throwable) {
            null
        }
    }

    private fun invoke(target: Any?, methodName: String): Any? {
        if (target == null) return null
        return try { target.javaClass.getMethod(methodName).invoke(target) } catch (_: Exception) { null }
    }

    private class Api(
        val managerClass: Class<*>,
        val getAllVariants: Method,
        val mqTypes: List<Pair<String, Any>>,
    )
}
