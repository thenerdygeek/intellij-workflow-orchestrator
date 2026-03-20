package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object PlanPersistence {
    private val LOG = Logger.getInstance(PlanPersistence::class.java)
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private const val PLAN_FILENAME = "plan.json"

    fun save(plan: AgentPlan, sessionDir: File) {
        try {
            sessionDir.mkdirs()
            File(sessionDir, PLAN_FILENAME).writeText(json.encodeToString(plan))
        } catch (e: Exception) {
            LOG.warn("PlanPersistence: failed to save plan", e)
        }
    }

    fun load(sessionDir: File): AgentPlan? {
        val file = File(sessionDir, PLAN_FILENAME)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<AgentPlan>(file.readText())
        } catch (e: Exception) {
            LOG.warn("PlanPersistence: failed to load plan", e)
            null
        }
    }

    fun updateStepStatus(sessionDir: File, stepId: String, status: String) {
        val plan = load(sessionDir) ?: return
        val updatedSteps = plan.steps.map { step ->
            if (step.id == stepId) step.copy(status = status) else step
        }
        save(plan.copy(steps = updatedSteps), sessionDir)
    }
}
