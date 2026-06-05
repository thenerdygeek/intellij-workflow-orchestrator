package com.workflow.orchestrator.agent.monitor

import kotlinx.serialization.Serializable

/** Serializable description of a running monitor, persisted to monitors.json so monitors can be
 *  re-armed on session resume with the SAME id. params hold the raw tool params (all strings). */
@Serializable
data class MonitorSpec(
    val id: String,
    val sourceType: String,          // "shell" | "bamboo" | "pull_request" | "jira_ticket" | "jira_sprint" | "sonar_gate" | "sonar_issues"
    val description: String,
    val params: Map<String, String>, // e.g. {"command":..,"filter":..} / {"plan_key":..,"level":..} / {"pr_id":..,"aspects":..} / {"project_key":..,"min_severity":..}
)
