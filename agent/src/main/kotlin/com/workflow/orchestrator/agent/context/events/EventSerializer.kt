package com.workflow.orchestrator.agent.context.events

import kotlinx.serialization.json.*
import java.time.Instant

/**
 * Handles polymorphic JSON serialization/deserialization of [Event] types.
 *
 * Each event serializes as a JSON object with a `"type"` discriminator field plus
 * the event's own fields, `"id"`, `"timestamp"`, and `"source"`.
 *
 * Uses a [JsonObject] wrapper approach rather than kotlinx.serialization's polymorphic
 * module, because Event types are not annotated with @Serializable (they use java.time.Instant).
 */
object EventSerializer {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Serialize an [Event] to a JSON string (single line, no trailing newline).
     */
    fun serialize(event: Event): String {
        val obj = toJsonObject(event)
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * Deserialize a JSON string back to an [Event].
     *
     * @throws IllegalArgumentException if the type discriminator is unknown
     */
    fun deserialize(jsonStr: String): Event {
        val obj = json.decodeFromString(JsonObject.serializer(), jsonStr)
        return fromJsonObject(obj)
    }

    // -------------------------------------------------------------------------
    // Serialization: Event -> JsonObject
    // -------------------------------------------------------------------------

    private fun toJsonObject(event: Event): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        map["type"] = JsonPrimitive(typeDiscriminator(event))
        map["id"] = JsonPrimitive(event.id)
        map["timestamp"] = JsonPrimitive(event.timestamp.toString())
        map["source"] = JsonPrimitive(event.source.name)

        when (event) {
            is MessageAction -> {
                map["content"] = JsonPrimitive(event.content)
                event.imageUrls?.let { urls ->
                    map["imageUrls"] = JsonArray(urls.map { JsonPrimitive(it) })
                }
            }
            is SystemMessageAction -> {
                map["content"] = JsonPrimitive(event.content)
            }
            is UserSteeringAction -> {
                map["content"] = JsonPrimitive(event.content)
            }
            is AgentThinkAction -> {
                map["thought"] = JsonPrimitive(event.thought)
            }
            is AgentFinishAction -> {
                map["finalThought"] = JsonPrimitive(event.finalThought)
                if (event.outputs.isNotEmpty()) {
                    map["outputs"] = JsonObject(event.outputs.mapValues { JsonPrimitive(it.value) })
                }
            }
            is DelegateAction -> {
                map["agentType"] = JsonPrimitive(event.agentType)
                map["prompt"] = JsonPrimitive(event.prompt)
                event.thought?.let { map["thought"] = JsonPrimitive(it) }
            }
            is CondensationAction -> {
                event.forgottenEventIds?.let { ids ->
                    map["forgottenEventIds"] = JsonArray(ids.map { JsonPrimitive(it) })
                }
                event.forgottenEventsStartId?.let { map["forgottenEventsStartId"] = JsonPrimitive(it) }
                event.forgottenEventsEndId?.let { map["forgottenEventsEndId"] = JsonPrimitive(it) }
                event.summary?.let { map["summary"] = JsonPrimitive(it) }
                event.summaryOffset?.let { map["summaryOffset"] = JsonPrimitive(it) }
            }
            is CondensationRequestAction -> {
                // No extra fields
            }
            is FileReadAction -> {
                map["toolCallId"] = JsonPrimitive(event.toolCallId)
                map["responseGroupId"] = JsonPrimitive(event.responseGroupId)
                map["path"] = JsonPrimitive(event.path)
            }
            is FileEditAction -> {
                map["toolCallId"] = JsonPrimitive(event.toolCallId)
                map["responseGroupId"] = JsonPrimitive(event.responseGroupId)
                map["path"] = JsonPrimitive(event.path)
                event.oldStr?.let { map["oldStr"] = JsonPrimitive(it) }
                event.newStr?.let { map["newStr"] = JsonPrimitive(it) }
            }
            is CommandRunAction -> {
                map["toolCallId"] = JsonPrimitive(event.toolCallId)
                map["responseGroupId"] = JsonPrimitive(event.responseGroupId)
                map["command"] = JsonPrimitive(event.command)
                event.cwd?.let { map["cwd"] = JsonPrimitive(it) }
            }
            is SearchCodeAction -> {
                map["toolCallId"] = JsonPrimitive(event.toolCallId)
                map["responseGroupId"] = JsonPrimitive(event.responseGroupId)
                map["query"] = JsonPrimitive(event.query)
                event.path?.let { map["path"] = JsonPrimitive(it) }
            }
            is DiagnosticsAction -> {
                map["toolCallId"] = JsonPrimitive(event.toolCallId)
                map["responseGroupId"] = JsonPrimitive(event.responseGroupId)
                event.path?.let { map["path"] = JsonPrimitive(it) }
            }
            is GenericToolAction -> {
                map["toolCallId"] = JsonPrimitive(event.toolCallId)
                map["responseGroupId"] = JsonPrimitive(event.responseGroupId)
                map["toolName"] = JsonPrimitive(event.toolName)
                map["arguments"] = JsonPrimitive(event.arguments)
            }
            is MetaToolAction -> {
                map["toolCallId"] = JsonPrimitive(event.toolCallId)
                map["responseGroupId"] = JsonPrimitive(event.responseGroupId)
                map["toolName"] = JsonPrimitive(event.toolName)
                map["actionName"] = JsonPrimitive(event.actionName)
                map["arguments"] = JsonPrimitive(event.arguments)
            }
            is FactRecordedAction -> {
                map["factType"] = JsonPrimitive(event.factType)
                event.path?.let { map["path"] = JsonPrimitive(it) }
                map["content"] = JsonPrimitive(event.content)
            }
            is PlanUpdatedAction -> {
                map["planJson"] = JsonPrimitive(event.planJson)
            }
            is SkillActivatedAction -> {
                map["skillName"] = JsonPrimitive(event.skillName)
                map["content"] = JsonPrimitive(event.content)
            }
            is SkillDeactivatedAction -> {
                map["skillName"] = JsonPrimitive(event.skillName)
            }
            is GuardrailRecordedAction -> {
                map["rule"] = JsonPrimitive(event.rule)
            }
            is MentionAction -> {
                map["paths"] = JsonArray(event.paths.map { JsonPrimitive(it) })
                map["content"] = JsonPrimitive(event.content)
            }
            // Observations
            is ToolResultObservation -> {
                map["toolCallId"] = JsonPrimitive(event.toolCallId)
                map["content"] = JsonPrimitive(event.content)
                map["isError"] = JsonPrimitive(event.isError)
                map["toolName"] = JsonPrimitive(event.toolName)
            }
            is CondensationObservation -> {
                map["content"] = JsonPrimitive(event.content)
            }
            is ErrorObservation -> {
                map["content"] = JsonPrimitive(event.content)
                event.errorId?.let { map["errorId"] = JsonPrimitive(it) }
            }
            is SuccessObservation -> {
                map["content"] = JsonPrimitive(event.content)
            }
        }
        return JsonObject(map)
    }

    // -------------------------------------------------------------------------
    // Deserialization: JsonObject -> Event
    // -------------------------------------------------------------------------

    private fun fromJsonObject(obj: JsonObject): Event {
        val type = obj["type"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'type' field in event JSON")
        val id = obj["id"]?.jsonPrimitive?.int ?: -1
        val timestamp = obj["timestamp"]?.jsonPrimitive?.content?.let { Instant.parse(it) } ?: Instant.EPOCH
        val source = obj["source"]?.jsonPrimitive?.content?.let { EventSource.valueOf(it) } ?: EventSource.SYSTEM

        return when (type) {
            "message_action" -> MessageAction(
                content = obj.str("content"),
                imageUrls = obj["imageUrls"]?.jsonArray?.map { it.jsonPrimitive.content },
                id = id, timestamp = timestamp, source = source
            )
            "system_message_action" -> SystemMessageAction(
                content = obj.str("content"),
                id = id, timestamp = timestamp, source = source
            )
            "user_steering" -> UserSteeringAction(
                content = obj.str("content"),
                id = id, timestamp = timestamp, source = source
            )
            "agent_think_action" -> AgentThinkAction(
                thought = obj.str("thought"),
                id = id, timestamp = timestamp, source = source
            )
            "agent_finish_action" -> AgentFinishAction(
                finalThought = obj.str("finalThought"),
                outputs = obj["outputs"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                id = id, timestamp = timestamp, source = source
            )
            "delegate_action" -> DelegateAction(
                agentType = obj.str("agentType"),
                prompt = obj.str("prompt"),
                thought = obj.strOrNull("thought"),
                id = id, timestamp = timestamp, source = source
            )
            "condensation_action" -> CondensationAction(
                forgottenEventIds = obj["forgottenEventIds"]?.jsonArray?.map { it.jsonPrimitive.int },
                forgottenEventsStartId = obj.intOrNull("forgottenEventsStartId"),
                forgottenEventsEndId = obj.intOrNull("forgottenEventsEndId"),
                summary = obj.strOrNull("summary"),
                summaryOffset = obj.intOrNull("summaryOffset"),
                id = id, timestamp = timestamp, source = source
            )
            "condensation_request_action" -> CondensationRequestAction(
                id = id, timestamp = timestamp, source = source
            )
            "file_read_action" -> FileReadAction(
                toolCallId = obj.str("toolCallId"),
                responseGroupId = obj.str("responseGroupId"),
                path = obj.str("path"),
                id = id, timestamp = timestamp, source = source
            )
            "file_edit_action" -> FileEditAction(
                toolCallId = obj.str("toolCallId"),
                responseGroupId = obj.str("responseGroupId"),
                path = obj.str("path"),
                oldStr = obj.strOrNull("oldStr"),
                newStr = obj.strOrNull("newStr"),
                id = id, timestamp = timestamp, source = source
            )
            "command_run_action" -> CommandRunAction(
                toolCallId = obj.str("toolCallId"),
                responseGroupId = obj.str("responseGroupId"),
                command = obj.str("command"),
                cwd = obj.strOrNull("cwd"),
                id = id, timestamp = timestamp, source = source
            )
            "search_code_action" -> SearchCodeAction(
                toolCallId = obj.str("toolCallId"),
                responseGroupId = obj.str("responseGroupId"),
                query = obj.str("query"),
                path = obj.strOrNull("path"),
                id = id, timestamp = timestamp, source = source
            )
            "diagnostics_action" -> DiagnosticsAction(
                toolCallId = obj.str("toolCallId"),
                responseGroupId = obj.str("responseGroupId"),
                path = obj.strOrNull("path"),
                id = id, timestamp = timestamp, source = source
            )
            "generic_tool_action" -> GenericToolAction(
                toolCallId = obj.str("toolCallId"),
                responseGroupId = obj.str("responseGroupId"),
                toolName = obj.str("toolName"),
                arguments = obj.str("arguments"),
                id = id, timestamp = timestamp, source = source
            )
            "meta_tool_action" -> MetaToolAction(
                toolCallId = obj.str("toolCallId"),
                responseGroupId = obj.str("responseGroupId"),
                toolName = obj.str("toolName"),
                actionName = obj.str("actionName"),
                arguments = obj.str("arguments"),
                id = id, timestamp = timestamp, source = source
            )
            "fact_recorded_action" -> FactRecordedAction(
                factType = obj.str("factType"),
                path = obj.strOrNull("path"),
                content = obj.str("content"),
                id = id, timestamp = timestamp, source = source
            )
            "plan_updated_action" -> PlanUpdatedAction(
                planJson = obj.str("planJson"),
                id = id, timestamp = timestamp, source = source
            )
            "skill_activated_action" -> SkillActivatedAction(
                skillName = obj.str("skillName"),
                content = obj.str("content"),
                id = id, timestamp = timestamp, source = source
            )
            "skill_deactivated_action" -> SkillDeactivatedAction(
                skillName = obj.str("skillName"),
                id = id, timestamp = timestamp, source = source
            )
            "guardrail_recorded_action" -> GuardrailRecordedAction(
                rule = obj.str("rule"),
                id = id, timestamp = timestamp, source = source
            )
            "mention_action" -> MentionAction(
                paths = obj["paths"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                content = obj.str("content"),
                id = id, timestamp = timestamp, source = source
            )
            "tool_result_observation" -> ToolResultObservation(
                toolCallId = obj.str("toolCallId"),
                content = obj.str("content"),
                isError = obj["isError"]?.jsonPrimitive?.boolean ?: false,
                toolName = obj.str("toolName"),
                id = id, timestamp = timestamp, source = source
            )
            "condensation_observation" -> CondensationObservation(
                content = obj.str("content"),
                id = id, timestamp = timestamp, source = source
            )
            "error_observation" -> ErrorObservation(
                content = obj.str("content"),
                errorId = obj.strOrNull("errorId"),
                id = id, timestamp = timestamp, source = source
            )
            "success_observation" -> SuccessObservation(
                content = obj.str("content"),
                id = id, timestamp = timestamp, source = source
            )
            else -> throw IllegalArgumentException("Unknown event type: $type")
        }
    }

    // -------------------------------------------------------------------------
    // Type discriminator mapping
    // -------------------------------------------------------------------------

    private fun typeDiscriminator(event: Event): String = when (event) {
        is MessageAction -> "message_action"
        is SystemMessageAction -> "system_message_action"
        is UserSteeringAction -> "user_steering"
        is AgentThinkAction -> "agent_think_action"
        is AgentFinishAction -> "agent_finish_action"
        is DelegateAction -> "delegate_action"
        is CondensationAction -> "condensation_action"
        is CondensationRequestAction -> "condensation_request_action"
        is FileReadAction -> "file_read_action"
        is FileEditAction -> "file_edit_action"
        is CommandRunAction -> "command_run_action"
        is SearchCodeAction -> "search_code_action"
        is DiagnosticsAction -> "diagnostics_action"
        is GenericToolAction -> "generic_tool_action"
        is MetaToolAction -> "meta_tool_action"
        is FactRecordedAction -> "fact_recorded_action"
        is PlanUpdatedAction -> "plan_updated_action"
        is SkillActivatedAction -> "skill_activated_action"
        is SkillDeactivatedAction -> "skill_deactivated_action"
        is GuardrailRecordedAction -> "guardrail_recorded_action"
        is MentionAction -> "mention_action"
        is ToolResultObservation -> "tool_result_observation"
        is CondensationObservation -> "condensation_observation"
        is ErrorObservation -> "error_observation"
        is SuccessObservation -> "success_observation"
    }

    // -------------------------------------------------------------------------
    // JsonObject extension helpers
    // -------------------------------------------------------------------------

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content ?: ""

    private fun JsonObject.strOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull
}
