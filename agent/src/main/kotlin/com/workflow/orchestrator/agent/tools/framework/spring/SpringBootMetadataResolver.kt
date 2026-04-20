package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.module.Module
import java.lang.reflect.Method

/**
 * Reflective facade over com.intellij.spring.boot's configuration-metadata APIs.
 *
 * Entry point: `SpringBootApplicationMetaConfigKeyManager.getInstance()` (singleton, no-arg).
 * The concrete impl adds `getAllMetaConfigKeys(Module)` which enumerates every key sourced
 * from the module's META-INF/spring-configuration-metadata.json files — the same index the
 * IDE uses for application.yml autocomplete.
 *
 * The resolver is self-gating — `ClassNotFoundException` when the Spring Boot plugin is absent
 * is caught during reflection initialization and every public method falls through to null /
 * empty results without raising. Callers do not need to consult
 * [com.workflow.orchestrator.agent.ide.IdeContext.hasSpringBootPlugin] before invoking; that
 * flag exists for future registration-layer decisions.
 *
 * Signatures verified 2026-04-21 against IU-2025.1. See
 * docs/research/2026-04-21-intellij-spring-boot-metadata-api-signatures.md for the full surface.
 */
internal object SpringBootMetadataResolver {

    data class MetaConfigKeyInfo(
        val name: String,
        val description: String?,
        val defaultValue: String?,
        val deprecated: Boolean,
        val deprecationReason: String?,
        val replacement: String?,
        val sourceType: String?,
    )

    @Volatile private var cached: Api? = null
    @Volatile private var initialized: Boolean = false

    @Volatile private var cachedGetAllKeys: Method? = null
    @Volatile private var cachedGetAllKeysInitialized: Boolean = false

    /** Returns metadata for a single property name, or null if not found / plugin absent. */
    fun findMetaConfigKey(module: Module, propertyName: String): MetaConfigKeyInfo? =
        allMetaConfigKeys(module).firstOrNull { it.name == propertyName }

    /** Returns all keys whose name starts with the given prefix (case-insensitive). */
    fun findMetaConfigKeysByPrefix(module: Module, prefix: String): List<MetaConfigKeyInfo> =
        allMetaConfigKeys(module).filter { it.name.startsWith(prefix, ignoreCase = true) }

    /** Enumerates every MetaConfigKey for the module. Empty when plugin absent or no keys. */
    fun allMetaConfigKeys(module: Module): List<MetaConfigKeyInfo> {
        val api = api() ?: return emptyList()
        val manager = try {
            api.getInstance.invoke(null)
        } catch (_: Exception) { return emptyList() } ?: return emptyList()

        // getAllMetaConfigKeys(Module) lives on the Impl, not the abstract base.
        // Resolve it on the concrete runtime class so it is found even when the
        // abstract type doesn't expose it. The method handle is cached to avoid
        // re-traversing the class hierarchy and acquiring the reflection lock on
        // every call (manager singleton means javaClass is stable within a JVM session).
        val getAllKeys = resolveGetAllKeys(manager) ?: return emptyList()

        val rawKeys: Collection<Any> = try {
            @Suppress("UNCHECKED_CAST")
            getAllKeys.invoke(manager, module) as? Collection<Any>
        } catch (_: Exception) { null } ?: return emptyList()

        return rawKeys.mapNotNull { toInfo(it) }
    }

    // ─── private helpers ───

    private fun toInfo(rawKey: Any): MetaConfigKeyInfo? = try {
        val name = invokeString(rawKey, "getName") ?: return null
        // getDescriptionText() returns a DescriptionText wrapper (Lazy<String?> wrapper),
        // not a raw String. Calling .toString() directly yields "ClassName@hash" garbage.
        val description = extractDescriptionText(invoke(rawKey, "getDescriptionText"))
        // getDefaultValue() returns String directly (confirmed in research doc).
        val defaultValue = invokeString(rawKey, "getDefaultValue")

        // Use getDeprecationFast() to avoid PSI resolve — see research-doc performance note.
        val deprecation = invoke(rawKey, "getDeprecationFast")
        val (reason, replacement) = if (deprecation != null) {
            extractDeprecationFields(deprecation)
        } else null to null

        val sourceType = invokeString(rawKey, "getSourceType")

        MetaConfigKeyInfo(
            name = name,
            description = description,
            defaultValue = defaultValue,
            deprecated = deprecation != null,
            deprecationReason = reason,
            replacement = replacement,
            sourceType = sourceType,
        )
    } catch (_: Exception) { null }

    /**
     * Extracts a human-readable string from a DescriptionText wrapper object.
     *
     * `getDescriptionText()` on `SpringBootApplicationMetaConfigKeyImpl` returns a
     * `DescriptionText` object (a Lazy<String?> wrapper), not a plain String. Calling
     * `.toString()` on it produces `com.intellij.spring.boot...DescriptionText@abcd1234`
     * which is useless. This helper tries common extractor method names in order, and
     * falls back to null rather than leaking garbage object-reference strings.
     *
     * The guard `!result.startsWith(target.javaClass.name + "@")` detects the default
     * Object.toString() output (ClassName@hashHex) and treats it as "no description".
     */
    private fun extractDescriptionText(target: Any?): String? {
        if (target == null) return null
        for (methodName in listOf("getText", "getValue", "getDescription", "toString")) {
            try {
                val result = target.javaClass.getMethod(methodName).invoke(target) as? String
                if (result != null && !result.startsWith(target.javaClass.name + "@")) return result
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    /**
     * Resolves and caches the `getAllMetaConfigKeys(Module)` method from the concrete
     * manager impl class. The manager is a singleton so its javaClass is stable within a
     * JVM session. Uses double-checked locking to avoid repeated Class.getMethod traversals.
     */
    private fun resolveGetAllKeys(manager: Any): Method? {
        if (cachedGetAllKeysInitialized) return cachedGetAllKeys
        synchronized(this) {
            if (cachedGetAllKeysInitialized) return cachedGetAllKeys
            cachedGetAllKeys = try {
                manager.javaClass.getMethod("getAllMetaConfigKeys", Module::class.java)
            } catch (_: NoSuchMethodException) { null }
            cachedGetAllKeysInitialized = true
            return cachedGetAllKeys
        }
    }

    /**
     * Extracts reason and replacement from a Deprecation object.
     * Tries both `getReason()`/`getReplacement()` (Java-style getters) and the
     * property accessor names in case the object is a Kotlin data class whose
     * getters are named differently at runtime.
     */
    private fun extractDeprecationFields(deprecation: Any): Pair<String?, String?> {
        val reason = invokeString(deprecation, "getReason")
            ?: invokeString(deprecation, "reason")
        val replacement = invokeString(deprecation, "getReplacement")
            ?: invokeString(deprecation, "replacement")
        return reason to replacement
    }

    private fun api(): Api? {
        if (initialized) return cached
        synchronized(this) {
            if (initialized) return cached
            cached = try {
                val mgrCls = Class.forName(
                    "com.intellij.spring.boot.application.metadata.SpringBootApplicationMetaConfigKeyManager"
                )
                // getInstance() takes no arguments — it is a static factory singleton.
                Api(getInstance = mgrCls.getMethod("getInstance"))
            } catch (_: Throwable) { null }
            initialized = true
            return cached
        }
    }

    private class Api(val getInstance: Method)

    private fun invoke(target: Any, methodName: String): Any? = try {
        target.javaClass.getMethod(methodName).invoke(target)
    } catch (_: Exception) { null }

    private fun invokeString(target: Any, methodName: String): String? =
        invoke(target, methodName) as? String
}
