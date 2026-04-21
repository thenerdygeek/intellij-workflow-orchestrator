package com.workflow.orchestrator.agent.ide

enum class IdeProduct {
    INTELLIJ_ULTIMATE,
    INTELLIJ_COMMUNITY,
    PYCHARM_PROFESSIONAL,
    PYCHARM_COMMUNITY,
    OTHER
}

enum class Edition { COMMUNITY, PROFESSIONAL, ULTIMATE, OTHER }
enum class Language { JAVA, KOTLIN, PYTHON }
enum class Framework { SPRING, DJANGO, FASTAPI, FLASK }
enum class BuildTool { MAVEN, GRADLE, PIP, POETRY, UV }

data class IdeContext(
    val product: IdeProduct,
    val productName: String,
    val edition: Edition,
    val languages: Set<Language>,
    val hasJavaPlugin: Boolean,
    val hasPythonPlugin: Boolean,
    val hasPythonCorePlugin: Boolean,
    val hasSpringPlugin: Boolean,
    val hasSpringBootPlugin: Boolean = false,
    val hasPersistencePlugin: Boolean = false,
    val hasDatabasePlugin: Boolean = false,
    val detectedFrameworks: Set<Framework>,
    val detectedBuildTools: Set<BuildTool>,
    val hasPyTestConfigType: Boolean = false,
    val isMultiModule: Boolean = false,
    val hasMicroservicesModule: Boolean = false,
) {
    val supportsJava: Boolean
        get() = Language.JAVA in languages

    val supportsPython: Boolean
        get() = Language.PYTHON in languages

    /** True when full Python plugin is available (Professional/Ultimate features) */
    val supportsPythonAdvanced: Boolean
        get() = hasPythonPlugin

    val supportsSpring: Boolean
        get() = hasSpringPlugin && supportsJava

    fun summary(): String = buildString {
        append("You are running in $productName.")
        if (languages.isNotEmpty()) {
            append("\nAvailable languages: ${languages.joinToString { it.name.lowercase().replaceFirstChar(Char::uppercase) }}.")
        }
        if (detectedFrameworks.isNotEmpty()) {
            append("\nDetected frameworks: ${detectedFrameworks.joinToString { it.name.lowercase().replaceFirstChar(Char::uppercase) }}.")
        }
        if (detectedBuildTools.isNotEmpty()) {
            append("\nBuild tools: ${detectedBuildTools.joinToString { it.name.lowercase() }}.")
        }
        if (isMultiModule) {
            append("\nProject structure: Multi-module (project_structure, build, runtime_config available as core tools).")
        }
    }
}

/**
 * Guards tool registration based on [IdeContext] — tools that cannot work in the
 * current IDE are never registered, so the LLM never sees them.
 */
object ToolRegistrationFilter {

    /** Universal tools (file, git, terminal, memory, planning) — always register */
    fun shouldRegisterUniversalTools(context: IdeContext): Boolean = true

    /** Database tools — always register (pure JDBC, no IDE dependency) */
    fun shouldRegisterDatabaseTools(context: IdeContext): Boolean = true

    /** Java/Kotlin PSI tools (find_definition, type_hierarchy, etc.) */
    fun shouldRegisterJavaPsiTools(context: IdeContext): Boolean =
        context.hasJavaPlugin

    /** Spring framework meta-tool */
    fun shouldRegisterSpringTools(context: IdeContext): Boolean =
        context.hasSpringPlugin && context.hasJavaPlugin

    /**
     * Microservices-backed multi-framework `endpoints` meta-tool.
     *
     * Requires the `com.intellij.modules.microservices` platform module, which
     * is bundled with Ultimate / PyCharm Pro / WebStorm / Rider / GoLand /
     * RubyMine. Not Spring-specific — covers Micronaut, JAX-RS, Ktor, gRPC,
     * OpenAPI, Retrofit, HTTP Client `.http` files, etc.
     */
    fun shouldRegisterMicroservicesEndpoints(context: IdeContext): Boolean =
        context.hasMicroservicesModule

    /**
     * The Spring meta-tool's `endpoints` and `boot_endpoints` actions.
     *
     * Mutually exclusive with [shouldRegisterMicroservicesEndpoints]: when the
     * microservices platform tool is registered, the Spring tool drops these
     * two actions (they would otherwise duplicate the new `endpoints` tool).
     */
    fun shouldRegisterSpringEndpointActions(context: IdeContext): Boolean =
        shouldRegisterSpringTools(context) && !context.hasMicroservicesModule

    /** Java/Kotlin build tools (Maven/Gradle actions in build meta-tool) */
    fun shouldRegisterJavaBuildTools(context: IdeContext): Boolean =
        context.hasJavaPlugin

    /** Java/Kotlin debug tools */
    fun shouldRegisterJavaDebugTools(context: IdeContext): Boolean =
        context.hasJavaPlugin

    /** Coverage tool */
    fun shouldRegisterCoverageTool(context: IdeContext): Boolean =
        context.edition == Edition.ULTIMATE || context.edition == Edition.PROFESSIONAL

    /** Whether a detected framework's meta-tool should be promoted to core */
    fun shouldPromoteFrameworkTool(context: IdeContext, framework: Framework): Boolean =
        framework in context.detectedFrameworks

    /** Whether multi-module management tools should be promoted to core */
    fun shouldPromoteMultiModuleTools(context: IdeContext): Boolean = context.isMultiModule

    // --- Python tool filters (for Plan B2/C) ---

    /** Python PSI tools — requires Python plugin (Pro or Core) */
    fun shouldRegisterPythonPsiTools(context: IdeContext): Boolean =
        context.supportsPython

    /** Python build tools (pip/poetry/uv actions in build meta-tool) */
    fun shouldRegisterPythonBuildTools(context: IdeContext): Boolean =
        context.supportsPython

    /** Python debug tools — basic (Community) */
    fun shouldRegisterPythonDebugTools(context: IdeContext): Boolean =
        context.supportsPython

    /** Python debug tools — advanced (Professional only: Django debug, remote interpreter) */
    fun shouldRegisterPythonAdvancedDebugTools(context: IdeContext): Boolean =
        context.supportsPythonAdvanced

    /** Django meta-tool */
    fun shouldRegisterDjangoTools(context: IdeContext): Boolean =
        context.supportsPython && Framework.DJANGO in context.detectedFrameworks

    /** FastAPI meta-tool */
    fun shouldRegisterFastApiTools(context: IdeContext): Boolean =
        context.supportsPython && Framework.FASTAPI in context.detectedFrameworks

    /** Flask meta-tool */
    fun shouldRegisterFlaskTools(context: IdeContext): Boolean =
        context.supportsPython && Framework.FLASK in context.detectedFrameworks
}
