package com.workflow.orchestrator.agent.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: FunctionParameters
)

@Serializable
data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, ParameterProperty>,
    val required: List<String> = emptyList()
)

@Serializable
data class ParameterProperty(
    val type: String,
    val description: String,
    @SerialName("enum") val enumValues: List<String>? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String // JSON string of arguments
)
