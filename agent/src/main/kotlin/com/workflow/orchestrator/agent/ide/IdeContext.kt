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
    val detectedFrameworks: Set<Framework>,
    val detectedBuildTools: Set<BuildTool>,
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
}
