package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.core.services.BambooService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.memberFunctions

/**
 * Phase D guard — locks the public API shape of [BambooService] and [BambooServiceImpl]
 * at the Kotlin reflection level.
 *
 * Makes the wrong shape a compile-level invariant enforced by the test suite.
 * If a future contributor tries to re-introduce `getLatestBuild(planKey, branch)`
 * or `getRecentBuilds(planKey, n, branch)`, this test will fail before they merge.
 *
 * See `docs/architecture/automation-chainkey-unification-plan.md` Phase D.
 */
class BambooServiceShapeInvariantTest {

    @Test
    fun `BambooService getLatestBuild has exactly one parameter named chainKey`() {
        val fn = BambooService::class.memberFunctions.single { it.name == "getLatestBuild" }
        // parameters[0] is the receiver; parameters[1] is the first value parameter;
        // parameters[2] would be the kotlin.coroutines.Continuation injected by the compiler.
        val valueParams = fn.parameters.filter { it.kind == kotlin.reflect.KParameter.Kind.VALUE }
        assertEquals(1, valueParams.size,
            "getLatestBuild must have exactly one value parameter (chainKey). " +
                "Got ${valueParams.map { it.name }}")
        assertEquals("chainKey", valueParams[0].name,
            "getLatestBuild's single parameter must be named 'chainKey', " +
                "got '${valueParams[0].name}'")
    }

    @Test
    fun `BambooService getRecentBuilds has exactly two parameters chainKey and maxResults`() {
        val fn = BambooService::class.memberFunctions.single { it.name == "getRecentBuilds" }
        val valueParams = fn.parameters.filter { it.kind == kotlin.reflect.KParameter.Kind.VALUE }
        assertEquals(2, valueParams.size,
            "getRecentBuilds must have exactly two value parameters (chainKey, maxResults). " +
                "Got ${valueParams.map { it.name }}")
        assertEquals("chainKey", valueParams[0].name,
            "getRecentBuilds's first parameter must be named 'chainKey', " +
                "got '${valueParams[0].name}'")
        assertEquals("maxResults", valueParams[1].name,
            "getRecentBuilds's second parameter must be named 'maxResults', " +
                "got '${valueParams[1].name}'")
    }

    @Test
    fun `BambooServiceImpl does not contain resolveBranchPlanKey`() {
        val methodNames = BambooServiceImpl::class.declaredMemberFunctions.map { it.name }
        assertNull(
            methodNames.find { it == "resolveBranchPlanKey" },
            "resolveBranchPlanKey must not exist on BambooServiceImpl — " +
                "the buggy name-vs-shortName branch resolver was deleted in Phase D. " +
                "Use ChainKeyResolver EP instead."
        )
    }

    @Test
    fun `BambooService has no method with both a planKey and a branch parameter`() {
        val violations = BambooService::class.memberFunctions.filter { fn ->
            val paramNames = fn.parameters
                .filter { it.kind == kotlin.reflect.KParameter.Kind.VALUE }
                .map { it.name }
            paramNames.any { it == "branch" }
        }
        assertEquals(emptyList<Any>(), violations,
            "No BambooService method may have a 'branch' parameter — " +
                "callers must resolve to a chainKey first via ChainKeyResolver. " +
                "Offending methods: ${violations.map { it.name }}")
    }

    // ── Stage-picker shape invariants (automation-stage-picker-plan.md F2) ──────

    @Test
    fun `BambooService triggerBuild has exactly three value parameters chainKey variables stages`() {
        val fn = BambooService::class.memberFunctions.single { it.name == "triggerBuild" }
        val valueParams = fn.parameters.filter { it.kind == kotlin.reflect.KParameter.Kind.VALUE }
        assertEquals(3, valueParams.size,
            "triggerBuild must have exactly 3 value parameters (chainKey, variables, stages). " +
                "Got: ${valueParams.map { it.name }}")
        assertEquals("chainKey",  valueParams[0].name)
        assertEquals("variables", valueParams[1].name)
        assertEquals("stages",    valueParams[2].name)
    }

    @Test
    fun `BambooService triggerBuild does not have an executeAllStages Boolean parameter`() {
        val fn = BambooService::class.memberFunctions.single { it.name == "triggerBuild" }
        val paramNames = fn.parameters.filter { it.kind == kotlin.reflect.KParameter.Kind.VALUE }.map { it.name }
        assertNull(
            paramNames.find { it == "executeAllStages" },
            "triggerBuild must not expose an executeAllStages param — that detail is internal to " +
                "BambooApiClient.queueBuildWithStageSelection. Got params: $paramNames"
        )
    }

    @Test
    fun `BambooService triggerBuild stages parameter is nullable Set of String`() {
        val fn = BambooService::class.memberFunctions.single { it.name == "triggerBuild" }
        val stagesParam = fn.parameters
            .filter { it.kind == kotlin.reflect.KParameter.Kind.VALUE }
            .single { it.name == "stages" }
        // The KType for Set<String>? is nullable. Verify isMarkedNullable so callers can pass null.
        assertTrue(stagesParam.type.isMarkedNullable,
            "triggerBuild's 'stages' parameter must be nullable (Set<String>?) to indicate 'run all stages'. " +
                "Got type: ${stagesParam.type}")
    }
}
