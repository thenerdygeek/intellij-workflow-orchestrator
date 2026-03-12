package com.workflow.orchestrator.mockserver.bamboo

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class BambooPlan(
    val key: String,
    val shortName: String,
    val name: String,
    val branches: List<BambooBranch> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
)

@Serializable
data class BambooBranch(
    val key: String,
    val shortName: String,
)

@Serializable
data class BambooBuildResult(
    val buildResultKey: String,
    val planKey: String,
    val buildNumber: Int,
    val lifeCycleState: String,  // "Queued", "Running", "Finished", "Cancelled" — divergent!
    val state: String?,          // "Successful", "Failed", "PartiallySuccessful", "Cancelled"
    val stages: List<BambooStage> = emptyList(),
    val logEntries: List<String> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
)

@Serializable
data class BambooStage(
    val name: String,
    val state: String, // "Successful", "Failed", "Unknown"
    val lifeCycleState: String,
)

class BambooState {
    var currentUser = "mock.user"
    var plans: MutableList<BambooPlan> = mutableListOf()
    var builds: ConcurrentHashMap<String, BambooBuildResult> = ConcurrentHashMap()
    private val buildCounter = AtomicInteger(100)
    private val progressionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun triggerBuild(planKey: String, variables: Map<String, String> = emptyMap()): BambooBuildResult {
        val buildNumber = buildCounter.incrementAndGet()
        val buildKey = "$planKey-$buildNumber"
        val build = BambooBuildResult(
            buildResultKey = buildKey,
            planKey = planKey,
            buildNumber = buildNumber,
            lifeCycleState = "Queued",
            state = null,
            variables = variables,
        )
        builds[buildKey] = build
        startBuildProgression(buildKey, planKey)
        return build
    }

    fun cancelBuild(resultKey: String): Boolean {
        val build = builds[resultKey] ?: return false
        if (build.lifeCycleState == "Finished") return false
        builds[resultKey] = build.copy(lifeCycleState = "Cancelled", state = "Cancelled")
        return true
    }

    private fun startBuildProgression(buildKey: String, planKey: String) {
        progressionScope.launch {
            delay(10_000)
            val current = builds[buildKey] ?: return@launch
            if (current.lifeCycleState == "Cancelled") return@launch
            builds[buildKey] = current.copy(lifeCycleState = "Running", state = null)

            delay(30_000)
            val running = builds[buildKey] ?: return@launch
            if (running.lifeCycleState == "Cancelled") return@launch

            val (finalState, stages) = determineBuildOutcome(planKey)
            builds[buildKey] = running.copy(
                lifeCycleState = "Finished",
                state = finalState,
                stages = stages,
                logEntries = generateBuildLog(planKey, finalState),
            )
        }
    }

    private fun determineBuildOutcome(planKey: String): Pair<String, List<BambooStage>> {
        return when {
            planKey.contains("BUILD") -> "Successful" to listOf(
                BambooStage("Compile & Package", "Successful", "Finished"),
                BambooStage("Security Scan", "Successful", "Finished"),
                BambooStage("Integration Tests", "Successful", "Finished"),
            )
            planKey.contains("TEST") -> "Failed" to listOf(
                BambooStage("Compile & Package", "Successful", "Finished"),
                BambooStage("Security Scan", "Successful", "Finished"),
                BambooStage("Integration Tests", "Failed", "Finished"),
            )
            planKey.contains("SONAR") -> "PartiallySuccessful" to listOf(
                BambooStage("Compile & Package", "Successful", "Finished"),
                BambooStage("Security Scan", "Failed", "Finished"),
                BambooStage("Integration Tests", "Successful", "Finished"),
            )
            else -> "Successful" to emptyList()
        }
    }

    private fun generateBuildLog(planKey: String, state: String): List<String> = listOf(
        "\u001B[34m[INFO]\u001B[0m Scanning for projects...",
        "\u001B[34m[INFO]\u001B[0m Building workflow-service 1.0.0-SNAPSHOT",
        "\u001B[34m[INFO]\u001B[0m --------------------------------",
        "\u001B[34m[INFO]\u001B[0m --- maven-compiler-plugin:3.11.0:compile ---",
        "\u001B[34m[INFO]\u001B[0m Compiling 42 source files to /target/classes",
        if (state == "Failed") "\u001B[31m[ERROR]\u001B[0m Tests run: 128, Failures: 3, Errors: 0, Skipped: 2"
        else "\u001B[34m[INFO]\u001B[0m Tests run: 128, Failures: 0, Errors: 0, Skipped: 0",
        if (state == "Successful") "\u001B[32m[INFO] BUILD SUCCESS\u001B[0m"
        else "\u001B[31m[ERROR] BUILD FAILURE\u001B[0m",
        "\u001B[34m[INFO]\u001B[0m Total time: 2:34 min",
    )

    fun shutdown() {
        progressionScope.cancel()
    }
}
