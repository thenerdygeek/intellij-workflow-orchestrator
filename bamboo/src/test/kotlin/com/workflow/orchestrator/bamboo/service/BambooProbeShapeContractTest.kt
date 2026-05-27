package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanBranch
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.model.BambooPlanRef
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Probe-grounded contract test. The fixtures below are faithful copies of the SHAPE of the
 * real captured Bamboo DC 10.2.14 responses in
 * `tools/atlassian-probe/Result_Bamboo/bundle-repo.unpacked/raw/{result_latest_plan,plan_branches}.json`
 * (keys/names are the redaction-scrambled values from the bundle, so no secrets ship).
 *
 * It pins two facts the regression (commit def4cda98) got wrong:
 *  1. A branch PLAN key has two dash-segments (`SEXL-X0EAIMVIVXHUIPZ721`); only the build
 *     RESULT key gains a third (`LLDV-D3CNIGHAOBXGGIA-901`). The resolver returns the API's
 *     verbatim plan key regardless of shape — no `^.+-.+-\d+$` guessing.
 *  2. The real result wire shape (extra fields, multi-job Build stage, manual Release stage)
 *     deserializes into [BambooResultDto] and maps to per-job [com.workflow.orchestrator.bamboo.model.StageState]
 *     rows via [BambooBuildStructureMapper].
 */
class BambooProbeShapeContractTest {

    // Same decoder config the production client uses (BambooApiClient: Json { ignoreUnknownKeys = true }).
    private val json = Json { ignoreUnknownKeys = true }

    /** Faithful trim of bundle-repo result_latest_plan.json: extra fields kept to exercise ignoreUnknownKeys. */
    private val realResultJson = """
        {
          "expand": "stages",
          "link": { "href": "https://bamboo/rest/api/latest/result/LLDV-D3CNIGHAOBXGGIA-901", "rel": "self" },
          "plan": { "key": "LLDV-D3CNIGHAOBXGGIA", "name": "Project - CI", "shortName": "CI", "type": "chain" },
          "planName": "redacted",
          "buildResultKey": "LLDV-D3CNIGHAOBXGGIA-901",
          "buildNumber": 582,
          "state": "Successful",
          "lifeCycleState": "Finished",
          "buildDurationInSeconds": 900,
          "vcsRevisionKey": "deadbeef",
          "stages": {
            "size": 2,
            "stage": [
              {
                "name": "Build Stage",
                "state": "Successful",
                "lifeCycleState": "Finished",
                "manual": false,
                "results": {
                  "size": 3,
                  "result": [
                    { "buildResultKey": "FXZV-A0CLVIKGLYVHCUI-ULVZ-390", "state": "Successful", "lifeCycleState": "Finished", "buildDurationInSeconds": 671, "plan": { "key": "FXZV-A0CLVIKGLYVHCUI-ULVZ", "name": "Build Artifacts Job", "shortName": "Build Artifacts" } },
                    { "buildResultKey": "DLAB-O8FJSOVVCUNQFAS-ZYYT1-090", "state": "Successful", "lifeCycleState": "Finished", "buildDurationInSeconds": 223, "plan": { "key": "DLAB-O8FJSOVVCUNQFAS-ZYYT1", "name": "SonarQube Analysis Job", "shortName": "SonarQube Analysis" } },
                    { "buildResultKey": "DLAB-O8FJSOVVCUNQFAS-PKGE-091", "state": "Successful", "lifeCycleState": "Finished", "buildDurationInSeconds": 40, "plan": { "key": "DLAB-O8FJSOVVCUNQFAS-PKGE", "name": "Package Job", "shortName": "Package" } }
                  ]
                }
              },
              {
                "name": "Release Stage",
                "state": "Unknown",
                "lifeCycleState": "NotBuilt",
                "manual": true,
                "results": {
                  "size": 1,
                  "result": [
                    { "buildResultKey": "PUEL-D4RTOTZAIZHKVSG-EOFS8-517", "state": "Unknown", "lifeCycleState": "NotBuilt", "buildDurationInSeconds": 0, "plan": { "key": "PUEL-D4RTOTZAIZHKVSG-EOFS8", "name": "Release Service Job", "shortName": "Release Service" } }
                  ]
                }
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `real result wire shape deserializes and maps to per-job stage rows`() {
        val dto = json.decodeFromString<BambooResultDto>(realResultJson)
        // Plan key is 2-segment; build result key is 3-segment — the distinction def4cda98 missed.
        assertEquals("LLDV-D3CNIGHAOBXGGIA-901", dto.buildResultKey)
        assertEquals(582, dto.buildNumber)
        assertEquals(2, dto.stages.stage.size)

        val state = BambooBuildStructureMapper.toBuildState(dto, planKey = "LLDV-D3CNIGHAOBXGGIA", branch = "develop")

        // 3 build jobs + 1 release job, flattened to 4 rows.
        assertEquals(4, state.stages.size)
        assertEquals(BuildStatus.SUCCESS, state.overallStatus)

        val first = state.stages[0]
        assertEquals("Build Artifacts", first.name)              // from job.plan.shortName
        assertEquals("FXZV-A0CLVIKGLYVHCUI-ULVZ-390", first.resultKey) // verbatim 4-segment job key
        assertEquals("Build Stage", first.stageName)
        assertFalse(first.manual)
        assertEquals(BuildStatus.SUCCESS, first.status)

        // The manual Release stage's job carries the manual flag and its own result key.
        val release = state.stages.last()
        assertEquals("Release Service", release.name)
        assertEquals("Release Stage", release.stageName)
        assertTrue(release.manual)
        assertEquals("PUEL-D4RTOTZAIZHKVSG-EOFS8-517", release.resultKey)
    }

    // Real redacted branch entries from bundle plan_branches.json (key = 2-segment plan key, shortName = git branch).
    private val realBranches = listOf(
        BambooPlanBranch(key = "DIJI-M2YJVOXCXQXATXY6", shortName = "release"),
        BambooPlanBranch(key = "QYJJ-M4AQBFYYVCLDTAYGTRD598", shortName = "qa-environment"),
        BambooPlanBranch(key = "XULM-F8EKIRDSUSZOJIPSGNR445", shortName = "feature-PROJ2-002-verify-the-changes"),
    )

    @Test
    fun `resolver returns the API verbatim branch plan key matched by shortName`() = runTest {
        val api = mockk<BambooApiClient>()
        coEvery { api.getPlanBranches("MASTER-PLAN") } returns ApiResult.Success(realBranches)
        val resolver = BambooPlanResolver(api)

        // Feature branch with a child plan → BranchPlan with the verbatim 2-segment key.
        assertEquals(
            BambooPlanRef.BranchPlan("XULM-F8EKIRDSUSZOJIPSGNR445", "MASTER-PLAN", "feature-PROJ2-002-verify-the-changes"),
            resolver.resolve("MASTER-PLAN", "feature-PROJ2-002-verify-the-changes", repoDefaultBranch = "develop"),
        )
        // 'release' matches a child plan too.
        assertEquals(
            BambooPlanRef.BranchPlan("DIJI-M2YJVOXCXQXATXY6", "MASTER-PLAN", "release"),
            resolver.resolve("MASTER-PLAN", "release", repoDefaultBranch = "develop"),
        )
        // Default branch with no child plan → MasterTrackedBranch (state 2).
        assertEquals(
            BambooPlanRef.MasterTrackedBranch("MASTER-PLAN", "develop"),
            resolver.resolve("MASTER-PLAN", "develop", repoDefaultBranch = "develop"),
        )
        // Unknown branch, not the default → strict empty state (null), no master substitution.
        assertNull(resolver.resolve("MASTER-PLAN", "feature/never-built", repoDefaultBranch = "develop"))
    }
}
