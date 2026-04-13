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
