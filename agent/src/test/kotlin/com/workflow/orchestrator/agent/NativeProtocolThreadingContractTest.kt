package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Test

/**
 * Phase 4a Task 11 (C1/I2) — pins that the provider-selected [ToolProtocol] is resolved PER TASK
 * (not a stale `@Service`-level `val`) and threaded to EVERY prompt-build + dialect-guard site, so
 * on the native Anthropic path `presentTools` returns null everywhere and tools are presented ONLY
 * in the wire `tools:[]` field — never also in the system prompt (the historical double-presentation
 * dialect drift the whole design exists to kill).
 *
 * AgentService / SubagentRunner / SpawnAgentTool are not unit-instantiable (project services, live
 * brains), so these are source-text contracts — same shape as [ApiDebugDumpGatingContractTest]. The
 * proximity regexes deliberately tie `activeToolProtocol` / `AnthropicNativeProtocol` to each
 * specific site, so they fail if any one site reverts to the old single hardcoded `XmlToolProtocol()`.
 */
class NativeProtocolThreadingContractTest {

    private val agent by lazy {
        java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()
    }
    private val sub by lazy {
        java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt"
        ).readText()
    }
    private val spawn by lazy {
        java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt"
        ).readText()
    }

    @Test
    fun `agent resolves toolProtocol per provider, not a single hardcoded val`() {
        assert(Regex("""AnthropicNativeProtocol\(\)""").containsMatchIn(agent)) {
            "AgentService must construct AnthropicNativeProtocol() on the native path."
        }
        assert(Regex("""llmProvider\s*==\s*"anthropic"""").containsMatchIn(agent)) {
            "AgentService must guard the native protocol on llmProvider == \"anthropic\"."
        }
        // The guard and the native protocol must live together (the per-provider resolution),
        // not as two unrelated occurrences elsewhere in the file.
        val resolved = Regex(
            """llmProvider\s*==\s*"anthropic"[\s\S]{0,160}?AnthropicNativeProtocol\(\)"""
        ).containsMatchIn(agent)
        assert(resolved) {
            "The provider guard must resolve directly to AnthropicNativeProtocol() " +
                "(per-task protocol resolution)."
        }
    }

    @Test
    fun `the stale @Service-level XmlToolProtocol val is gone`() {
        // The old construction-time field `private val toolProtocol = XmlToolProtocol()` went stale
        // on a mid-session provider switch. It must be replaced by per-task resolution.
        val staleField = Regex(
            """private\s+val\s+toolProtocol\s*:\s*[\w.]*ToolProtocol\s*=\s*[\s\S]{0,40}?XmlToolProtocol\(\)"""
        ).containsMatchIn(agent)
        assert(!staleField) {
            "AgentService must NOT keep a single `private val toolProtocol = XmlToolProtocol()` " +
                "field — it goes stale when the user switches provider mid-session."
        }
    }

    @Test
    fun `the per-task activeToolProtocol feeds the orchestrator system-prompt tool injection`() {
        assert(Regex("""activeToolProtocol\.presentTools\(""").containsMatchIn(agent)) {
            "The orchestrator tool-definition injection must call activeToolProtocol.presentTools(defs) " +
                "(null under native so the §6c block is omitted)."
        }
    }

    @Test
    fun `the per-task activeToolProtocol feeds the XmlLlmProvider facade and the AgentLoop`() {
        val xmlProvider = Regex(
            """XmlLlmProvider\([\s\S]{0,240}?toolProtocol\s*=\s*activeToolProtocol"""
        ).containsMatchIn(agent)
        assert(xmlProvider) {
            "XmlLlmProvider facade must receive toolProtocol = activeToolProtocol."
        }
        val loop = Regex(
            """AgentLoop\([\s\S]{0,400}?toolProtocol\s*=\s*activeToolProtocol"""
        ).containsMatchIn(agent)
        assert(loop) {
            "AgentLoop must receive toolProtocol = activeToolProtocol."
        }
    }

    @Test
    fun `the per-task activeToolProtocol gates the resume dialect redaction`() {
        assert(Regex("""activeToolProtocol\.requiresDialectGuard""").containsMatchIn(agent)) {
            "The resume dialect-redaction site must gate on activeToolProtocol.requiresDialectGuard " +
                "(false under native → redaction walk skipped)."
        }
    }

    @Test
    fun `MessageStateHandler receives the per-task protocol on the live and resume paths`() {
        // Both the executeTask handler and the resumeSession handler must thread the per-task
        // protocol so the dialect chokepoint (consumeDialectDriftFlag) short-circuits under native.
        val count = Regex(
            """MessageStateHandler\([\s\S]{0,450}?toolProtocol\s*=\s*activeToolProtocol"""
        ).findAll(agent).count()
        assert(count >= 2) {
            "Both the live (executeTask) and resume MessageStateHandler constructions must pass " +
                "toolProtocol = activeToolProtocol (found $count, expected >= 2)."
        }
    }

    @Test
    fun `the sub-agent MessageStateHandler factory threads the provider-selected protocol`() {
        // Sub-agents run the native brain too, so their persistence handler's dialect chokepoint
        // must also see the provider-selected protocol (resolved per spawn, not a stale XML val).
        val factoryThreads = Regex(
            """MessageStateHandler\([\s\S]{0,500}?toolProtocol\s*=\s*resolveActiveToolProtocol\(\)"""
        ).containsMatchIn(agent)
        assert(factoryThreads) {
            "The subagentMessageStateHandlerFactory must pass toolProtocol = resolveActiveToolProtocol()."
        }
    }

    @Test
    fun `SubagentRunner accepts an injected toolProtocol defaulting to XML`() {
        assert(sub.contains("toolProtocol") && sub.contains("ToolProtocol")) {
            "SubagentRunner must reference an injected toolProtocol of type ToolProtocol."
        }
        // Constructor param (trailing comma proves it is a primary-constructor LIST element, not a
        // hardcoded class-body field) with a default of XmlToolProtocol() — default XML keeps every
        // existing non-Phase-4a caller compiling unchanged. compileKotlin already rejects having BOTH a
        // ctor param and a body field named toolProtocol (duplicate declaration), so the trailing-comma
        // form + a successful compile is the "the hardcoded body field is gone" guarantee.
        val ctorParam = Regex(
            """toolProtocol\s*:\s*[\w.]*ToolProtocol\s*=\s*[\s\S]{0,80}?XmlToolProtocol\(\)\s*,"""
        ).containsMatchIn(sub)
        assert(ctorParam) {
            "SubagentRunner must declare `toolProtocol: ToolProtocol = XmlToolProtocol(),` as a primary " +
                "constructor param (injectable; default XML preserves back-compat)."
        }
        // The injected param must actually be CONSUMED — fed into the inner AgentLoop and the prompt
        // builder — otherwise injection would be cosmetic and native sub-agents would still drift.
        assert(Regex("""toolProtocol\s*=\s*toolProtocol""").containsMatchIn(sub)) {
            "SubagentRunner must forward the injected toolProtocol into its AgentLoop."
        }
        assert(Regex("""toolProtocol\.presentTools\(""").containsMatchIn(sub)) {
            "SubagentRunner must build the §6c tool-doc block from toolProtocol.presentTools(...) " +
                "(null under native → block omitted)."
        }
    }

    @Test
    fun `SpawnAgentTool threads a settable protocol into every SubagentRunner without reading settings`() {
        // A settable field defaulting to XML — AgentService pushes the per-task protocol onto it.
        val field = Regex(
            """var\s+toolProtocol\s*:\s*[\w.]*ToolProtocol\s*=\s*[\s\S]{0,80}?XmlToolProtocol\(\)"""
        ).containsMatchIn(spawn)
        assert(field) {
            "SpawnAgentTool must expose `var toolProtocol: ToolProtocol = XmlToolProtocol()` so " +
                "AgentService can push the provider-selected protocol per task."
        }
        // Both the single-spawn and the parallel-fan-out SubagentRunner constructions must forward it.
        val forwarded = Regex(
            """SubagentRunner\([\s\S]{0,2500}?toolProtocol\s*=\s*toolProtocol"""
        ).findAll(spawn).count()
        assert(forwarded >= 2) {
            "Both SubagentRunner constructions (single + parallel fan-out) must forward " +
                "toolProtocol = toolProtocol (found $forwarded, expected >= 2)."
        }
        // Decoupling guard (mirrors ApiDebugDumpGatingContractTest): SpawnAgentTool must not reach
        // into AgentSettings on its hot path — that would NPE the bare-mock-Project spawn tests.
        assert("llmProvider" !in spawn) {
            "SpawnAgentTool must not read llmProvider — the protocol is pushed by AgentService " +
                "(reading the PROJECT-scoped AgentSettings here would break mock-Project spawn tests)."
        }
        // And AgentService must actually push it.
        assert(Regex("""spawnAgentTool\.toolProtocol\s*=\s*activeToolProtocol""").containsMatchIn(agent)) {
            "AgentService must push spawnAgentTool.toolProtocol = activeToolProtocol per task."
        }
    }
}
