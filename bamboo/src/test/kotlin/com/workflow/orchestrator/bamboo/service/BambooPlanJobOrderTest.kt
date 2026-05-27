package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.dto.BambooPlanConfigResponse
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins [BambooPlanJobOrder] + the [BambooPlanConfigResponse] DTO shape to the real
 * `GET /plan/{branchKey}?expand=stages.stage.plans.plan` response captured from the live
 * Bamboo (JSON form of the verified XML). Three jobs under one stage, in plan-defined order.
 * Decoded with the same `ignoreUnknownKeys` Json the client uses, so the response's many
 * extra fields (id, master, project, links, …) are tolerated.
 */
class BambooPlanJobOrderTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val configJson = """
      {
        "key": "PROJ-MYSERVICEV21234",
        "name": "MY PROJECT - my-service_v2 - TICKET-12345-feature-branch",
        "stages": {
          "size": 1,
          "stage": [
            {
              "name": "Build Stage",
              "description": "",
              "plans": {
                "expand": "plan",
                "size": 3,
                "plan": [
                  { "id": 100000001, "key": "PROJ-MYSERVICEV21234-BART", "shortName": "Build Artifacts",    "shortKey": "MYSERVICEV21234-BART", "type": "job", "enabled": true, "stageName": "Build Stage", "buildName": "Build Artifacts" },
                  { "id": 100000002, "key": "PROJ-MYSERVICEV21234-oss",  "shortName": "OSS Analysis",       "shortKey": "MYSERVICEV21234-oss",  "type": "job", "enabled": true, "stageName": "Build Stage", "buildName": "OSS Analysis" },
                  { "id": 100000003, "key": "PROJ-MYSERVICEV21234-SQAN", "shortName": "SonarQube Analysis", "shortKey": "MYSERVICEV21234-SQAN", "type": "job", "enabled": true, "stageName": "Build Stage", "buildName": "SonarQube Analysis" }
                ]
              },
              "manual": false
            }
          ]
        }
      }
    """.trimIndent()

    @Test
    fun `decodes plan-config and derives the plan-defined job order per stage`() {
        val resp = json.decodeFromString<BambooPlanConfigResponse>(configJson)
        assertEquals(1, resp.stages.stage.size)
        assertEquals("Build Stage", resp.stages.stage[0].name)
        assertEquals(3, resp.stages.stage[0].plans.plan.size)

        val order = BambooPlanJobOrder.fromConfig(resp)
        assertEquals(
            mapOf("Build Stage" to listOf("Build Artifacts", "OSS Analysis", "SonarQube Analysis")),
            order,
        )
    }
}
