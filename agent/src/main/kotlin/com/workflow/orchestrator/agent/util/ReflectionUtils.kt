package com.workflow.orchestrator.agent.util

/**
 * Helpers for best-effort reflective calls that swallow exceptions and return null on failure.
 *
 * Reflection across optional IntelliJ plugin classes (Maven, Coverage, JUnit, Gradle, Spring Boot,
 * Remote Debug, etc.) is inherently brittle — the target method may not exist, the plugin may
 * not be installed, or the signature may have changed between IntelliJ versions. Most call sites
 * handle this by wrapping the call in `try { ... } catch (_: Exception) { null }`.
 *
 * This object centralises that pattern so individual tools don't each carry their own
 * `tryReflectiveCall` / `tryInvoke` helper.
 *
 * Two entry points:
 *
 *  - [tryReflective] — generic lambda wrapper. Use this for any multi-step reflective sequence
 *    (Class.forName → getMethod → invoke → cast, or for chained reflective access like
 *    `foo.javaClass.getMethod(...).invoke(foo).javaClass.getMethod(...).invoke(inner)`). The
 *    lambda runs inside a single try/catch and any [Exception] is converted to `null`.
 *
 *  - [tryInvoke] — single-method, no-arg invocation helper. Use this when you just want to
 *    call a zero-argument method by name on a target object and get its return value (or null
 *    if the method doesn't exist or throws). Equivalent to
 *    `target?.javaClass?.getMethod(methodName)?.invoke(target)` with exception handling.
 *
 * Both helpers catch [Exception] (not [Throwable]) — this matches the broadest case found
 * across the existing call sites, all of which used `catch (_: Exception)` or
 * `catch (e: Exception)`. Callers that need to distinguish between `NoSuchMethodException`,
 * `ClassNotFoundException`, `InvocationTargetException`, etc. for diagnostic logging should
 * continue to use their own try/catch rather than this helper.
 */
object ReflectionUtils {

    /**
     * Run [block] and return its result, or `null` if any [Exception] is thrown.
     *
     * Use this for multi-step reflective access where the whole sequence should fail silently
     * to `null`. Callers that need a non-null default should use the Elvis operator at the
     * call site:
     *
     * ```
     * val name = ReflectionUtils.tryReflective<String> {
     *     obj.javaClass.getMethod("getName").invoke(obj) as String
     * } ?: "unknown"
     * ```
     *
     * Does NOT catch [Error] subclasses (like `NoSuchMethodError` raised on missing members
     * linked at class-load time) — those indicate genuine bytecode mismatches that should
     * surface rather than be silently swallowed.
     */
    inline fun <T> tryReflective(block: () -> T): T? =
        try {
            block()
        } catch (_: Exception) {
            null
        }

    /**
     * Invoke a zero-argument method named [methodName] on [target] via reflection.
     *
     * Returns the method's return value, or `null` if [target] is null, the method does not
     * exist, or the invocation throws any [Exception]. This is equivalent to
     *
     * ```
     * target?.javaClass?.getMethod(methodName)?.invoke(target)
     * ```
     *
     * wrapped in a try/catch. Prefer this when you're just chasing a getter-style method name
     * on an opaque object (e.g. unwrapping `getDelegate()` / `getConsole()` wrappers).
     */
    fun tryInvoke(target: Any?, methodName: String): Any? =
        tryReflective { target?.javaClass?.getMethod(methodName)?.invoke(target) }
}
