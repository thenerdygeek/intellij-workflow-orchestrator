package com.workflow.orchestrator.mockserver

import com.workflow.orchestrator.mockserver.admin.*
import com.workflow.orchestrator.mockserver.bamboo.*
import com.workflow.orchestrator.mockserver.bitbucket.*
import com.workflow.orchestrator.mockserver.chaos.*
import com.workflow.orchestrator.mockserver.config.MockConfig
import com.workflow.orchestrator.mockserver.jira.*
import com.workflow.orchestrator.mockserver.sonar.*
import com.workflow.orchestrator.mockserver.sourcegraph.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * Mutable holder so routes always see the current state even after scenario switching.
 * Routes call holder.state on each request, not a captured snapshot.
 */
class StateHolder<T>(var state: T)

private val JIRA_SCENARIOS = listOf("default", "happy-path", "empty-sprint", "large-sprint", "no-active-sprint", "transition-blocked")
private val BAMBOO_SCENARIOS = listOf("default", "happy-path", "all-builds-failing", "build-progression")
private val SONAR_SCENARIOS = listOf("default", "happy-path", "quality-gate-warn", "metrics-missing", "auth-invalid")
private val BITBUCKET_SCENARIOS = listOf("default", "all-merged", "empty", "happy-path")

fun main() {
    val config = MockConfig()

    val jiraHolder = StateHolder(JiraDataFactory.createDefaultState())
    val bambooHolder = StateHolder(BambooDataFactory.createDefaultState())
    val sonarHolder = StateHolder(SonarDataFactory.createDefaultState())

    val jiraChaos = ChaosConfig()
    val bambooChaos = ChaosConfig()
    val sonarChaos = ChaosConfig()

    val jiraAdmin = AdminState()
    val bambooAdmin = AdminState()
    val sonarAdmin = AdminState()

    val bitbucketHolder = StateHolder(BitbucketDataFactory.createDefaultState())
    val sourcegraphState = SourcegraphState(defaultScenario = config.sourcegraphDefaultScenario)

    val bitbucketChaos = ChaosConfig()
    val sourcegraphChaos = ChaosConfig()

    val bitbucketAdmin = AdminState()
    val sourcegraphAdmin = AdminState()

    printBanner(config)

    runBlocking {
        val jobs = listOf(
            launch(Dispatchers.IO) {
                startMockServer("Jira", config.jiraPort, jiraChaos, jiraAdmin,
                    routeSetup = { routing { jiraRoutes { jiraHolder.state } } },
                    getState = { buildJsonObject { put("issueCount", jiraHolder.state.issues.size); put("sprintCount", jiraHolder.state.sprints.size) } },
                    reset = { jiraHolder.state = JiraDataFactory.createDefaultState() },
                    loadScenario = { name ->
                        when (name) {
                            "default" -> { jiraHolder.state = JiraDataFactory.createDefaultState(); true }
                            "empty-sprint" -> { jiraHolder.state = JiraDataFactory.createEmptySprintState(); true }
                            "large-sprint" -> { jiraHolder.state = JiraDataFactory.createLargeSprintState(); true }
                            "no-active-sprint" -> { jiraHolder.state = JiraDataFactory.createNoActiveSprintState(); true }
                            "transition-blocked" -> { jiraHolder.state = JiraDataFactory.createTransitionBlockedState(); true }
                            "happy-path" -> { jiraHolder.state = JiraDataFactory.createHappyPathState(); true }
                            else -> false
                        }
                    },
                    scenarios = JIRA_SCENARIOS,
                )
            },
            launch(Dispatchers.IO) {
                startMockServer("Bamboo", config.bambooPort, bambooChaos, bambooAdmin,
                    routeSetup = { routing { bambooRoutes { bambooHolder.state } } },
                    getState = { buildJsonObject { put("planCount", bambooHolder.state.plans.size); put("buildCount", bambooHolder.state.builds.size) } },
                    reset = { bambooHolder.state.shutdown(); bambooHolder.state = BambooDataFactory.createDefaultState() },
                    loadScenario = { name ->
                        when (name) {
                            "default" -> { bambooHolder.state.shutdown(); bambooHolder.state = BambooDataFactory.createDefaultState(); true }
                            "all-builds-failing" -> { bambooHolder.state.shutdown(); bambooHolder.state = BambooDataFactory.createAllFailingState(); true }
                            "build-progression" -> { bambooHolder.state.shutdown(); bambooHolder.state = BambooDataFactory.createBuildProgressionState(); true }
                            "happy-path" -> { bambooHolder.state.shutdown(); bambooHolder.state = BambooDataFactory.createHappyPathState(); true }
                            else -> false
                        }
                    },
                    scenarios = BAMBOO_SCENARIOS,
                    onStop = { bambooHolder.state.shutdown() },
                )
            },
            launch(Dispatchers.IO) {
                startMockServer("SonarQube", config.sonarPort, sonarChaos, sonarAdmin,
                    routeSetup = { routing { sonarRoutes { sonarHolder.state } } },
                    getState = { buildJsonObject { put("projectCount", sonarHolder.state.projects.size); put("issueCount", sonarHolder.state.issues.size) } },
                    reset = { sonarHolder.state = SonarDataFactory.createDefaultState() },
                    loadScenario = { name ->
                        when (name) {
                            "default" -> { sonarHolder.state = SonarDataFactory.createDefaultState(); true }
                            "quality-gate-warn" -> { sonarHolder.state = SonarDataFactory.createQualityGateWarnState(); true }
                            "metrics-missing" -> { sonarHolder.state = SonarDataFactory.createMetricsMissingState(); true }
                            "auth-invalid" -> { sonarHolder.state = SonarDataFactory.createAuthInvalidState(); true }
                            "happy-path" -> { sonarHolder.state = SonarDataFactory.createHappyPathState(); true }
                            else -> false
                        }
                    },
                    scenarios = SONAR_SCENARIOS,
                )
            },
            launch(Dispatchers.IO) {
                startMockServer("Bitbucket", config.bitbucketPort, bitbucketChaos, bitbucketAdmin,
                    routeSetup = { routing { bitbucketRoutes { bitbucketHolder.state } } },
                    getState = { buildJsonObject { put("prCount", bitbucketHolder.state.prs.size) } },
                    reset = { bitbucketHolder.state = BitbucketDataFactory.createDefaultState() },
                    loadScenario = { name ->
                        when (name) {
                            "default" -> { bitbucketHolder.state = BitbucketDataFactory.createDefaultState(); true }
                            "all-merged" -> { bitbucketHolder.state = BitbucketDataFactory.createAllMergedState(); true }
                            "empty" -> { bitbucketHolder.state = BitbucketDataFactory.createEmptyState(); true }
                            "happy-path" -> { bitbucketHolder.state = BitbucketDataFactory.createHappyPathState(); true }
                            else -> false
                        }
                    },
                    scenarios = BITBUCKET_SCENARIOS,
                )
            },
            launch(Dispatchers.IO) {
                startMockServer("Sourcegraph", config.sourcegraphPort, sourcegraphChaos, sourcegraphAdmin,
                    routeSetup = { routing {
                        sourcegraphRoutes(sourcegraphState)
                        // Dynamic custom-scenario authoring (cowork POSTs a JSON turn-sequence → mock plays it back).
                        // Mounted under /__admin so the shared AuthPlugin exempts it (no token needed).
                        post("/__admin/sourcegraph/scenario/custom") {
                            when (val r = sourcegraphState.registerCustomScenario(call.receiveText())) {
                                is SourcegraphState.RegisterResult.Ok ->
                                    call.respondText(
                                        """{"message":"registered+activated '${r.name}' (${r.turnCount} turns)"}""",
                                        ContentType.Application.Json
                                    )
                                is SourcegraphState.RegisterResult.Error ->
                                    call.respondText(
                                        """{"error":"${r.message}"}""",
                                        ContentType.Application.Json,
                                        HttpStatusCode.BadRequest
                                    )
                            }
                        }
                    } },
                    getState = { sourcegraphState.stateSummary() },
                    reset = { sourcegraphState.resetTurnIndices() },
                    loadScenario = { name -> sourcegraphState.setDefaultScenario(name) },
                    scenarios = sourcegraphState.listScenarios(),
                )
            },
        )
        jobs.joinAll()
    }
}

private suspend fun startMockServer(
    name: String,
    port: Int,
    chaosConfig: ChaosConfig,
    adminState: AdminState,
    routeSetup: Application.() -> Unit,
    getState: () -> JsonObject,
    reset: () -> Unit,
    loadScenario: (String) -> Boolean,
    scenarios: List<String>,
    onStop: (() -> Unit)? = null,
) {
    try {
        embeddedServer(Netty, port = port) {
            // Store shared chaos config as application attribute so the plugin reads it by reference
            attributes.put(ChaosConfigKey, chaosConfig)

            install(ContentNegotiation) { json() }
            install(CallLogging)
            install(ChaosPlugin)  // Reads config from attributes, not a copy
            install(AuthPlugin)
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    call.respondText(
                        """{"error":"${cause.message?.replace("\"", "'")}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            // Shutdown hook for coroutine cleanup
            if (onStop != null) {
                monitor.subscribe(ApplicationStopped) { onStop() }
            }

            routing {
                get("/health") { call.respondText("$name mock is running") }
                adminRoutes(name, chaosConfig, adminState, getState, reset, loadScenario, scenarios)
            }
            routeSetup()

        }.start(wait = true)
    } catch (e: Exception) {
        System.err.println("ERROR: Failed to start $name mock on port $port — ${e.message}")
        System.err.println("Is port $port already in use?")
        throw e
    }
}

private fun printBanner(config: MockConfig) {
    println("""
        |
        |╔══════════════════════════════════════════════════╗
        |║          Workflow Orchestrator Mock Server        ║
        |╠══════════════════════════════════════════════════╣
        |║  Jira        → http://localhost:${config.jiraPort.toString().padEnd(16)}║
        |║  Bamboo      → http://localhost:${config.bambooPort.toString().padEnd(16)}║
        |║  SonarQube   → http://localhost:${config.sonarPort.toString().padEnd(16)}║
        |║  Bitbucket   → http://localhost:${config.bitbucketPort.toString().padEnd(16)}║
        |║  Sourcegraph → http://localhost:${config.sourcegraphPort.toString().padEnd(16)}║
        |║                                                  ║
        |║  Chaos mode: OFF (enable via /__admin/chaos)     ║
        |║  Scenario:   default (adversarial)               ║
        |║                                                  ║
        |║  Admin:  GET /__admin/state on any port          ║
        |║  Reset:  POST /__admin/reset on any port         ║
        |╚══════════════════════════════════════════════════╝
    """.trimMargin())
}
