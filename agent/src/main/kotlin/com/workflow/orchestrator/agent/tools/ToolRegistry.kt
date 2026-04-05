package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.api.dto.ToolDefinition

/**
 * Three-tier tool registry: core (always sent), deferred (loaded via tool_search),
 * and active-deferred (loaded during a session, sent to LLM).
 *
 * Reduces per-call schema tokens from ~10K (all tools) to ~4K (core only).
 * The LLM can discover and load deferred tools via the `tool_search` tool.
 *
 * Inspired by Claude Code's observable tool pattern where deferred tools appear
 * by name in system-reminder messages and are fetched on demand via ToolSearch.
 */
class ToolRegistry {

    // Tier 1: Core tools — always sent to LLM
    private val coreTools = mutableMapOf<String, AgentTool>()

    // Tier 2: Deferred tools — available via tool_search, NOT sent to LLM initially
    private val deferredTools = mutableMapOf<String, AgentTool>()
    private val deferredCategories = mutableMapOf<String, String>()

    // Tier 3: Active deferred — loaded via tool_search during a session, sent to LLM
    private val activeDeferred = mutableMapOf<String, AgentTool>()

    // ── Registration ──────────────────────────────────────────────────────

    /** Register a core tool (always sent to LLM). */
    fun registerCore(tool: AgentTool) {
        coreTools[tool.name] = tool
    }

    /** Register a deferred tool (available via tool_search, not sent initially). */
    fun registerDeferred(tool: AgentTool, category: String = "Other") {
        deferredTools[tool.name] = tool
        deferredCategories[tool.name] = category
    }

    /**
     * Legacy register method — delegates to registerCore for backward compatibility.
     * New code should use registerCore or registerDeferred explicitly.
     */
    fun register(tool: AgentTool) {
        registerCore(tool)
    }

    // ── Active Tools (sent to LLM) ───────────────────────────────────────

    /** Returns core + active deferred tools (what gets sent to the LLM). */
    fun getActiveTools(): Map<String, AgentTool> {
        val result = LinkedHashMap<String, AgentTool>(coreTools.size + activeDeferred.size)
        result.putAll(coreTools)
        result.putAll(activeDeferred)
        return result
    }

    /** Returns tool definitions for the active tool set (core + active deferred). */
    fun getActiveDefinitions(): List<ToolDefinition> {
        return getActiveTools().values.map { it.toToolDefinition() }
    }

    // ── Deferred Tool Search & Activation ────────────────────────────────

    /**
     * Search deferred tools by query (case-insensitive match against name and description).
     * Does NOT activate them — call [activateDeferred] to make them available to the LLM.
     */
    fun searchDeferred(query: String, maxResults: Int = 5): List<AgentTool> {
        val lowerQuery = query.lowercase()
        val terms = lowerQuery.split("\\s+".toRegex()).filter { it.isNotBlank() }

        return deferredTools.values
            .map { tool ->
                val nameAndDesc = "${tool.name} ${tool.description}".lowercase()
                val score = terms.count { term -> nameAndDesc.contains(term) }
                tool to score
            }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(maxResults)
            .map { (tool, _) -> tool }
    }

    /**
     * Activate a deferred tool — moves it from deferred to active-deferred.
     * Returns the tool if found, null if the name doesn't exist in deferred tools
     * or is already active.
     */
    fun activateDeferred(toolName: String): AgentTool? {
        // Already active (core or active-deferred) — no-op, return the tool
        if (activeDeferred.containsKey(toolName)) return activeDeferred[toolName]
        if (coreTools.containsKey(toolName)) return coreTools[toolName]

        val tool = deferredTools.remove(toolName) ?: return null
        activeDeferred[toolName] = tool
        return tool
    }

    /**
     * Returns a catalog of deferred tools (name + first line of description).
     * Used to inject into the system prompt so the LLM knows what's available.
     */
    fun getDeferredCatalog(): List<Pair<String, String>> {
        return deferredTools.values.map { tool ->
            val oneLiner = tool.description.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: tool.description.take(100)
            tool.name to oneLiner
        }
    }

    /**
     * Returns deferred tools grouped by category (category → list of tool names).
     * Compact representation for system prompt: the LLM scans by category,
     * then uses tool_search to load the actual schema.
     */
    fun getDeferredCatalogGrouped(): Map<String, List<String>> {
        val grouped = linkedMapOf<String, MutableList<String>>()
        for ((name, _) in deferredTools) {
            val category = deferredCategories[name] ?: "Other"
            grouped.getOrPut(category) { mutableListOf() }.add(name)
        }
        return grouped
    }

    /** Reset active deferred tools (for new sessions). Moves them back to deferred. */
    fun resetActiveDeferred() {
        deferredTools.putAll(activeDeferred)
        activeDeferred.clear()
    }

    // ── Global Access (for execution — all tools are callable) ───────────

    /** Get any tool by name (core, deferred, or active-deferred). */
    fun get(name: String): AgentTool? {
        return coreTools[name] ?: activeDeferred[name] ?: deferredTools[name]
    }

    /** Alias for backward compatibility with code that uses getTool(). */
    fun getTool(name: String): AgentTool? = get(name)

    /** All registered tools regardless of tier. */
    fun allTools(): Collection<AgentTool> {
        val all = LinkedHashMap<String, AgentTool>()
        all.putAll(coreTools)
        all.putAll(activeDeferred)
        all.putAll(deferredTools)
        return all.values
    }

    /** Total count of all registered tools across all tiers. */
    fun count(): Int = coreTools.size + deferredTools.size + activeDeferred.size

    /** Count of core tools only. */
    fun coreCount(): Int = coreTools.size

    /** Count of deferred tools (not yet activated). */
    fun deferredCount(): Int = deferredTools.size

    /** Count of active deferred tools. */
    fun activeDeferredCount(): Int = activeDeferred.size

    // ── Worker-type filtering (backward compatibility) ───────────────────

    fun getToolsForWorker(workerType: WorkerType): List<AgentTool> {
        return allTools().filter { workerType in it.allowedWorkers }
    }

    fun getToolDefinitionsForWorker(workerType: WorkerType): List<ToolDefinition> {
        return getToolsForWorker(workerType).map { it.toToolDefinition() }
    }
}
