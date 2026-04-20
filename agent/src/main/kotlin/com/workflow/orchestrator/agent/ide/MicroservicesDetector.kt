package com.workflow.orchestrator.agent.ide

/**
 * Runtime presence probe for IntelliJ's Microservices module
 * (`com.intellij.modules.microservices`).
 *
 * Bundled in Ultimate / PyCharm Pro / WebStorm / Rider / GoLand / RubyMine.
 * Absent on IntelliJ Community and PyCharm Community.
 *
 * We must not hard-reference microservices classes from any class the plugin
 * loads eagerly on IC — doing so would trigger NoClassDefFoundError at
 * plugin startup. This detector uses reflection so the probe itself is safe
 * on every edition. Once [isAvailable] returns true, downstream code (e.g.
 * EndpointsTool in `tools/framework/endpoints/`) is free to import
 * microservices classes directly — those classes only load when instantiated,
 * which is gated by a successful detection result.
 */
object MicroservicesDetector {

    /** Result cache — classpath doesn't change at runtime. */
    @Volatile private var cached: Boolean? = null

    fun isAvailable(): Boolean {
        cached?.let { return it }
        val result = isAvailableForClassLoader(MicroservicesDetector::class.java.classLoader)
        cached = result
        return result
    }

    /** Package-visible entry point for unit tests. */
    internal fun isAvailableForClassLoader(loader: ClassLoader): Boolean = try {
        Class.forName(
            "com.intellij.microservices.endpoints.EndpointsProvider",
            false,
            loader
        )
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
