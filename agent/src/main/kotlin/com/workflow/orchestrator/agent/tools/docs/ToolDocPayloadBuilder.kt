package com.workflow.orchestrator.agent.tools.docs

import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.ApprovalPolicy
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolOutputConfig
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.estimateTokens
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Composes the wire-format [ToolDocPayload] sent to the JCEF tool-docs editor.
 *
 * The hand-authored half ([ToolDocumentation] from [AgentTool.documentation]) is
 * merged with [AutoDerivedMetadata] computed from the live [ToolRegistry] and the
 * [AgentLoop] / [ApprovalPolicy] / [ToolOutputConfig] constants — that way the
 * UI can never lie about whether a tool is in `WRITE_TOOLS`, what its registered
 * tier is, or what its approval policy is.
 *
 * Returns null when the tool exists but has no `documentation()` block — the UI
 * shows a "not yet documented" state for those.
 */
object ToolDocPayloadBuilder {

    private val json = Json { encodeDefaults = true }

    fun build(toolName: String, registry: ToolRegistry): ToolDocPayload? {
        val tool = registry.allTools().firstOrNull { it.name == toolName } ?: return null
        val doc = tool.documentation() ?: return null
        val metadata = computeMetadata(tool, registry)
        val narrative = doc.narrativeResource?.let { NarrativeLoader.load(it) }

        return ToolDocPayload(
            toolName = doc.toolName,
            tier = metadata.tier,
            sideEffect = doc.sideEffect,
            counterfactual = doc.counterfactual,
            commonLLMMistakes = doc.commonLLMMistakes,
            metadata = metadata,
            summary = doc.summary,
            whatLLMSees = doc.whatLLMSees,
            actions = doc.actions,
            singleActionParams = doc.singleActionParams,
            toolVerdict = doc.toolVerdict,
            auditNotes = doc.auditNotes,
            relatedTools = doc.relatedTools,
            flowchart = doc.flowchart,
            downsides = doc.downsides,
            narrative = narrative,
        )
    }

    /** Encode the payload to JSON. Convenience for editor bridge wiring. */
    fun encodeJson(payload: ToolDocPayload): String = json.encodeToString(payload)

    /** Encode-as-JSON shorthand: returns null when the tool has no documentation. */
    fun buildJson(toolName: String, registry: ToolRegistry): String? =
        build(toolName, registry)?.let(::encodeJson)

    private fun computeMetadata(tool: AgentTool, registry: ToolRegistry): AutoDerivedMetadata {
        val activeNames = registry.getActiveTools().keys
        val deferredCatalogNames = registry.getDeferredCatalog().map { it.first }.toSet()
        val tier = when {
            tool.name in activeNames && tool.name !in deferredCatalogNames -> "Core"
            tool.name in activeNames && tool.name in deferredCatalogNames -> "Active-deferred"
            else -> "Deferred"
        }

        val schemaJson = json.encodeToString(tool.toToolDefinition())
        val schemaTokenCost = estimateTokens(schemaJson)

        val approvalPolicy = describeApprovalPolicy(tool.name)
        val isWriteTool = tool.name in AgentLoop.WRITE_TOOLS

        return AutoDerivedMetadata(
            tier = tier,
            registrationCondition = describeRegistrationCondition(tool.name, tier),
            schemaTokenCost = schemaTokenCost,
            approvalPolicy = approvalPolicy,
            planModeBlocked = isWriteTool,
            allowedWorkers = tool.allowedWorkers.map { it.name }.sorted(),
            timeoutClass = describeTimeoutClass(tool.timeoutMs),
            outputCap = describeOutputCap(tool.outputConfig),
            isWriteTool = isWriteTool,
        )
    }

    private fun describeApprovalPolicy(toolName: String): String {
        val policy = ApprovalPolicy.forTool(toolName)
        return when {
            !policy.requiresApproval -> "ALWAYS_APPROVE"
            policy.allowSessionApproval -> "ALLOW_FOR_SESSION"
            else -> "ALWAYS_PER_INVOCATION"
        }
    }

    private fun describeTimeoutClass(timeoutMs: Long): String = when (timeoutMs) {
        AgentTool.DEFAULT_TOOL_TIMEOUT_MS -> "Default (120s)"
        AgentTool.LONG_TOOL_TIMEOUT_MS -> "Long (600s)"
        Long.MAX_VALUE -> "Unlimited"
        else -> "Custom (${timeoutMs / 1000}s)"
    }

    private fun describeOutputCap(cfg: ToolOutputConfig): String = when (cfg.maxChars) {
        ToolOutputConfig.DEFAULT_MAX_CHARS -> "Default (50K)"
        ToolOutputConfig.COMMAND_MAX_CHARS -> "Command (100K)"
        else -> "Custom (${cfg.maxChars / 1000}K)"
    }

    /**
     * Best-effort registration condition based on the tool name. Authoritative source
     * is `ToolRegistrationFilter` + `IdeContextDetector`, but those gates run at
     * registration time — by the time we render docs, the tool is already in (or
     * out of) the registry. So this is a human-readable label, not a runtime check.
     */
    private fun describeRegistrationCondition(toolName: String, tier: String): String = when {
        toolName == "java_runtime_exec" -> "Requires Java plugin"
        toolName == "python_runtime_exec" -> "Requires Python plugin"
        toolName in JAVA_GATED -> "Requires Java plugin"
        toolName in PYTHON_GATED -> "Requires Python plugin"
        toolName == "spring" -> "Requires Spring + Java plugin"
        toolName == "django" -> "Requires Django (manage.py + django dependency)"
        toolName == "fastapi" -> "Requires FastAPI dependency"
        toolName == "flask" -> "Requires Flask dependency"
        toolName == "jira" -> "Requires Jira URL configured"
        toolName == "sonar" -> "Requires SonarQube URL configured"
        toolName.startsWith("bamboo_") -> "Requires Bamboo URL configured"
        toolName.startsWith("bitbucket_") -> "Requires Bitbucket URL configured"
        toolName.startsWith("db_") -> "Requires database profile configured"
        tier == "Core" -> "Always"
        tier == "Active-deferred" -> "Loaded via tool_search this session"
        else -> "Available via tool_search"
    }

    private val JAVA_GATED = setOf(
        "build", "debug_step", "debug_inspect", "debug_breakpoints",
    )

    private val PYTHON_GATED = setOf<String>()
}
