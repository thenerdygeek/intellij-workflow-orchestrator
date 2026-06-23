package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.api.InternalApi
import com.workflow.orchestrator.core.model.CiGroupData
import com.workflow.orchestrator.core.model.PipelineData
import com.workflow.orchestrator.core.model.bamboo.ArtifactData
import com.workflow.orchestrator.core.model.bamboo.BuildChangeData
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildTriggerData
import com.workflow.orchestrator.core.model.bamboo.TestResultsData

/**
 * Neutral CI-server seam layered ABOVE the vendor-specific [BambooService] (Phase 0b-2 of the
 * plugin split). Captures the host-agnostic subset of CI operations so a future Jenkins /
 * GitHub-Actions connector can implement [CiService] without inheriting Bamboo vocabulary.
 *
 * SHAPE RESERVATION ONLY in 0b-2: the sole implementation is [BambooServiceImpl] (which also
 * implements [BambooService]); no consumer resolves [CiService] yet and there is no service/EP
 * registration — both arrive in the phase that adds the first neutral consumer (sibling to how
 * `NativeProtocol` was shaped before Phase 4). `public` + [InternalApi] = unfrozen-by-policy.
 *
 * Identifier convention: [pipelineId] selects WHICH buildable unit (Bamboo branch-chain key;
 * Jenkins job path); [buildId] selects a SPECIFIC build result (Bamboo result key; Jenkins build
 * number). Both are opaque strings interpreted by each implementation.
 *
 * Bamboo-specific operations (stage-level trigger, plan variables, plan-branch enable, 5-tier
 * plan auto-detect, plan short-name) are intentionally NOT here — they remain on [BambooService].
 */
@InternalApi
interface CiService {

    /** Latest build result for a pipeline. */
    suspend fun getLatestBuild(pipelineId: String): ToolResult<BuildResultData>

    /** A specific build result by its opaque id. */
    suspend fun getBuild(buildId: String): ToolResult<BuildResultData>

    /** Trigger a build with optional variable overrides. (Stage-level granularity stays on [BambooService].) */
    suspend fun triggerBuild(
        pipelineId: String,
        variables: Map<String, String> = emptyMap(),
    ): ToolResult<BuildTriggerData>

    /** Test the CI connection. */
    suspend fun testConnection(): ToolResult<Unit>

    /** Full log text for a build. */
    suspend fun getBuildLog(buildId: String): ToolResult<String>

    /** Test-result summary for a build. */
    suspend fun getTestResults(buildId: String): ToolResult<TestResultsData>

    /** Re-run only the failed jobs from a prior build. */
    suspend fun retryFailedJobs(pipelineId: String, buildNumber: Int): ToolResult<Unit>

    /** Stop a running build. */
    suspend fun stopBuild(buildId: String): ToolResult<Unit>

    /** Cancel a queued (not-yet-running) build. */
    suspend fun cancelBuild(buildId: String): ToolResult<Unit>

    /** Artifacts produced by a build. */
    suspend fun getArtifacts(buildId: String): ToolResult<List<ArtifactData>>

    /** Download an artifact to a local file. Returns true on success. */
    suspend fun downloadArtifact(artifactUrl: String, targetFile: java.io.File): ToolResult<Boolean>

    /**
     * Recent build results for a pipeline.
     *
     * NOTE: `maxResults` intentionally has NO default here, even though the conceptual default is 10.
     * [BambooServiceImpl] satisfies BOTH this method and `BambooService.getRecentBuilds(chainKey,
     * maxResults = 10)` with a single override (identical JVM signature). Kotlin forbids one override
     * from inheriting a default for the SAME parameter from two supertypes (it cannot prove the two
     * defaults agree → "MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES"). Keeping the default only on the
     * vendor `BambooService` (which has real callers) and dropping it on this consumer-less neutral
     * seam is the minimal behavior-preserving resolution — every existing call site passes both args
     * explicitly, so no behavior changes. Restore the default here only if `BambooService` drops its.
     */
    suspend fun getRecentBuilds(pipelineId: String, maxResults: Int): ToolResult<List<BuildResultData>>

    /** All pipelines visible to the authenticated user. */
    suspend fun getPipelines(): ToolResult<List<PipelineData>>

    /** Pipelines within a group. */
    suspend fun getPipelinesForGroup(groupKey: String): ToolResult<List<PipelineData>>

    /** Search pipelines by name or key. */
    suspend fun searchPipelines(query: String): ToolResult<List<PipelineData>>

    /** Running and queued builds for a pipeline. */
    suspend fun getRunningBuilds(pipelineId: String): ToolResult<List<BuildResultData>>

    /** CI groups visible to the authenticated user. */
    suspend fun getGroups(): ToolResult<List<CiGroupData>>

    /** Commits included in a specific build. */
    suspend fun getBuildChanges(buildId: String): ToolResult<List<BuildChangeData>>
}
