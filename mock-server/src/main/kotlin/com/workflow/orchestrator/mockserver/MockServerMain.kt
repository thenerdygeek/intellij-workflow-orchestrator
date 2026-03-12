package com.workflow.orchestrator.mockserver

import com.workflow.orchestrator.mockserver.config.MockConfig
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*

fun main() {
    val config = MockConfig()
    printBanner(config)

    runBlocking {
        val jobs = listOf(
            launch { startServer("Jira", config.jiraPort) },
            launch { startServer("Bamboo", config.bambooPort) },
            launch { startServer("SonarQube", config.sonarPort) },
        )
        jobs.joinAll()
    }
}

private suspend fun startServer(name: String, port: Int) {
    try {
        embeddedServer(Netty, port = port) {
            routing {
                get("/health") {
                    call.respondText("$name mock is running")
                }
            }
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
        |║  Jira      → http://localhost:${config.jiraPort.toString().padEnd(18)}║
        |║  Bamboo    → http://localhost:${config.bambooPort.toString().padEnd(18)}║
        |║  SonarQube → http://localhost:${config.sonarPort.toString().padEnd(18)}║
        |║                                                  ║
        |║  Admin:  GET /__admin/state on any port          ║
        |║  Reset:  POST /__admin/reset on any port         ║
        |╚══════════════════════════════════════════════════╝
    """.trimMargin())
}
