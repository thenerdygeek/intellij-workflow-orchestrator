package com.workflow.orchestrator.jira.workflow

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.jira.api.JiraApiClient

class TransitionExecutor(
    private val apiClient: JiraApiClient
) {
    suspend fun execute(
        issueKey: String,
        resolved: ResolvedTransition,
        fieldValues: Map<String, Any>? = null,
        comment: String? = null
    ): ApiResult<Unit> {
        return apiClient.transitionIssue(
            issueKey = issueKey,
            transitionId = resolved.transitionId,
            fields = fieldValues,
            comment = comment
        )
    }

    companion object {
        fun buildPayload(
            transitionId: String,
            fields: Map<String, Any>?,
            comment: String?
        ): String {
            val sb = StringBuilder()
            sb.append("""{"transition":{"id":"$transitionId"}""")

            if (!fields.isNullOrEmpty()) {
                sb.append(""","fields":{""")
                sb.append(fields.entries.joinToString(",") { (k, v) ->
                    val valueJson = when (v) {
                        is Map<*, *> -> {
                            v.entries.joinToString(",", "{", "}") { (mk, mv) ->
                                """"$mk":"$mv""""
                            }
                        }
                        else -> {
                            val escaped = v.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                            """{"name":"$escaped"}"""
                        }
                    }
                    """"$k":$valueJson"""
                })
                sb.append("}")
            }

            if (comment != null) {
                val escaped = comment
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                sb.append(""","update":{"comment":[{"add":{"body":"$escaped"}}]}""")
            }

            sb.append("}")
            return sb.toString()
        }
    }
}
