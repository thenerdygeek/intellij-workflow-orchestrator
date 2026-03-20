package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a specific test class or test method using Maven or Gradle.
 *
 * Auto-detects the build tool from the project (pom.xml vs build.gradle/build.gradle.kts)
 * and constructs a targeted test command. This is faster than running the full test suite
 * because it targets only the specified class or method.
 *
 * Pragmatic approach: uses ProcessBuilder to run the build tool's test command rather
 * than IntelliJ's RunManager/JUnit APIs, because creating programmatic run configurations
 * requires many optional plugin dependencies (JUnit plugin, etc.) and the process-based
 * approach provides more reliable structured output for the agent.
 */
class RunTestsTool : AgentTool {
    override val name = "run_tests"
    override val description = "Run a specific test class or test method using IntelliJ's test runner. Returns pass/fail with failure messages. Faster and more structured than 'mvn test'."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "class_name" to ParameterProperty(
                type = "string",
                description = "Fully qualified test class name (e.g., 'com.example.UserServiceTest')"
            ),
            "method" to ParameterProperty(
                type = "string",
                description = "Optional: specific test method name to run"
            )
        ),
        required = listOf("class_name")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER)

    companion object {
        private const val TIMEOUT_SECONDS = 120L
        private const val MAX_OUTPUT_CHARS = 4000
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'class_name' parameter is required",
                "Error: missing class_name",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val method = params["method"]?.jsonPrimitive?.content

        val testTarget = if (method != null) "$className#$method" else className
        val basePath = project.basePath
            ?: return ToolResult(
                "Error: no project base path available",
                "Error: no project",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val baseDir = File(basePath)
        val hasMaven = File(baseDir, "pom.xml").exists()
        val hasGradle = File(baseDir, "build.gradle").exists() ||
            File(baseDir, "build.gradle.kts").exists()

        val command = when {
            hasMaven -> buildMavenCommand(testTarget)
            hasGradle -> buildGradleCommand(testTarget, baseDir)
            else -> return ToolResult(
                "No Maven (pom.xml) or Gradle (build.gradle) build file found in project root.",
                "No build tool found",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val processBuilder = if (isWindows) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("sh", "-c", command)
            }

            processBuilder.directory(baseDir)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()
            val completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val output = process.inputStream.bufferedReader().readText()
            val truncatedOutput = if (output.length > MAX_OUTPUT_CHARS) {
                output.takeLast(MAX_OUTPUT_CHARS) + "\n... (output truncated, showing last $MAX_OUTPUT_CHARS chars)"
            } else {
                output
            }

            if (!completed) {
                process.destroyForcibly()
                return ToolResult(
                    "Test execution timed out after ${TIMEOUT_SECONDS}s for $testTarget.\nPartial output:\n$truncatedOutput",
                    "Test timeout",
                    TokenEstimator.estimate(truncatedOutput),
                    isError = true
                )
            }

            val exitCode = process.exitValue()
            if (exitCode == 0) {
                ToolResult(
                    "Tests PASSED for $testTarget.\n\n$truncatedOutput",
                    "Tests PASSED: $testTarget",
                    TokenEstimator.estimate(truncatedOutput)
                )
            } else {
                ToolResult(
                    "Tests FAILED for $testTarget (exit code $exitCode).\n\n$truncatedOutput",
                    "Tests FAILED: $testTarget",
                    TokenEstimator.estimate(truncatedOutput),
                    isError = true
                )
            }
        } catch (e: Exception) {
            ToolResult(
                "Error running tests: ${e.message}",
                "Test execution error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    /**
     * Build a Maven surefire command targeting a specific test class/method.
     * Uses -Dsurefire.useFile=false to get output on stdout instead of files.
     * Uses -q for quieter output (less Maven noise, more test output).
     */
    private fun buildMavenCommand(testTarget: String): String {
        return "mvn test -Dtest=$testTarget -Dsurefire.useFile=false -q"
    }

    /**
     * Build a Gradle test command targeting a specific test class/method.
     * Uses --no-daemon to avoid daemon startup overhead in agent context.
     * Converts Class#method format to Gradle's --tests filter syntax.
     */
    private fun buildGradleCommand(testTarget: String, baseDir: File): String {
        val gradleWrapper = if (File(baseDir, "gradlew").exists()) "./gradlew" else "gradle"
        // Gradle --tests accepts "com.example.MyTest.myMethod" (dot-separated)
        val gradleTarget = testTarget.replace('#', '.')
        return "$gradleWrapper test --tests '$gradleTarget' --no-daemon -q"
    }
}
