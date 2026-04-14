package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import java.util.concurrent.ConcurrentHashMap

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

    private val LOG = Logger.getInstance(ToolRegistry::class.java)

    // Tier 1: Core tools — always sent to LLM
    private val coreTools = ConcurrentHashMap<String, AgentTool>()

    // Tier 2: Deferred tools — available via tool_search, NOT sent to LLM initially
    private val deferredTools = ConcurrentHashMap<String, AgentTool>()
    private val deferredCategories = ConcurrentHashMap<String, String>()

    // Tier 3: Active deferred — loaded via tool_search during a session, sent to LLM
    private val activeDeferred = ConcurrentHashMap<String, AgentTool>()

    // ── Registration ──────────────────────────────────────────────────────

    /** Register a core tool (always sent to LLM). */
    fun registerCore(tool: AgentTool) {
        val existing = coreTools.put(tool.name, tool)
        if (existing != null) {
            LOG.warn("ToolRegistry: core tool '${tool.name}' registered twice, overwriting")
        }
        invalidateCache()
    }

    /** Register a deferred tool (available via tool_search, not sent initially). */
    fun registerDeferred(tool: AgentTool, category: String = "Other") {
        val existing = deferredTools.put(tool.name, tool)
        if (existing != null) {
            LOG.warn("ToolRegistry: deferred tool '${tool.name}' registered twice, overwriting")
        }
        deferredCategories[tool.name] = category
        invalidateCache()
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

    /** Returns tool definitions for the active tool set (core + active deferred). Cached. */
    fun getActiveDefinitions(): List<ToolDefinition> {
        return cachedActiveDefinitions ?: getActiveTools().values.map { it.toToolDefinition() }
            .also { cachedActiveDefinitions = it }
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
     *
     * Synchronized to prevent two concurrent callers from both passing the
     * containsKey checks and racing on deferredTools.remove() for the same key.
     */
    @Synchronized
    fun activateDeferred(toolName: String): AgentTool? {
        // Already active (core or active-deferred) — no-op, return the tool
        if (activeDeferred.containsKey(toolName)) return activeDeferred[toolName]
        if (coreTools.containsKey(toolName)) return coreTools[toolName]

        val tool = deferredTools.remove(toolName) ?: return null
        activeDeferred[toolName] = tool
        invalidateCache()
        return tool
    }

    private fun extractOneLiner(description: String): String =
        description.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: description.take(100)

    /**
     * Returns a catalog of deferred tools (name + first line of description).
     * Used to inject into the system prompt so the LLM knows what's available.
     */
    fun getDeferredCatalog(): List<Pair<String, String>> {
        return deferredTools.values.map { tool ->
            tool.name to extractOneLiner(tool.description)
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

    /**
     * Returns deferred tools grouped by category with one-line descriptions.
     * Category → list of (name, one-liner-description) pairs.
     *
     * This gives the LLM enough semantic signal to decide which tools to load
     * via tool_search, without sending the full parameter schemas.
     */
    fun getDeferredCatalogGroupedWithDescriptions(): Map<String, List<Pair<String, String>>> {
        val grouped = linkedMapOf<String, MutableList<Pair<String, String>>>()
        for ((name, tool) in deferredTools) {
            val category = deferredCategories[name] ?: "Other"
            grouped.getOrPut(category) { mutableListOf() }.add(name to extractOneLiner(tool.description))
        }
        return grouped
    }

    /** Remove a deferred or active-deferred tool by name. Returns the removed tool or null. */
    @Synchronized
    fun unregisterDeferred(toolName: String): AgentTool? {
        deferredCategories.remove(toolName)
        val removed = deferredTools.remove(toolName) ?: activeDeferred.remove(toolName)
        if (removed != null) invalidateCache()
        return removed
    }

    /**
     * Reset active deferred tools (for new sessions). Moves them back to deferred.
     *
     * Synchronized to prevent a concurrent activateDeferred() from inserting
     * into activeDeferred between the snapshot and clear(), which would lose that entry.
     */
    @Synchronized
    fun resetActiveDeferred() {
        val snapshot = HashMap(activeDeferred)
        activeDeferred.clear()
        for ((name, tool) in snapshot) {
            deferredTools[name] = tool
            deferredCategories.putIfAbsent(name, "Other")
        }
        invalidateCache()
    }

    // ── Global Access (for execution — all tools are callable) ───────────

    // Cached name/param sets, invalidated on any registration or activation change.
    // Avoids per-iteration allocation in the ReAct loop hot path.
    private var cachedToolNames: Set<String>? = null
    private var cachedParamNames: Set<String>? = null
    private var cachedActiveDefinitions: List<ToolDefinition>? = null

    private fun invalidateCache() {
        cachedToolNames = null
        cachedParamNames = null
        cachedActiveDefinitions = null
    }

    /** Get any tool by name (core, deferred, or active-deferred). */
    fun get(name: String): AgentTool? {
        return coreTools[name] ?: activeDeferred[name] ?: deferredTools[name]
    }

    /** Alias for backward compatibility with code that uses getTool(). */
    fun getTool(name: String): AgentTool? = get(name)

    private fun allToolMap(): Map<String, AgentTool> {
        val all = LinkedHashMap<String, AgentTool>(coreTools.size + activeDeferred.size + deferredTools.size)
        all.putAll(coreTools)
        all.putAll(activeDeferred)
        all.putAll(deferredTools)
        return all
    }

    /** All registered tools regardless of tier. */
    fun allTools(): Collection<AgentTool> = allToolMap().values

    /**
     * The XML parser must recognise tool tags for deferred tools even before
     * they are activated via tool_search, because [get] resolves all tiers.
     * Cached — invalidated on registration/activation changes.
     */
    fun allToolNames(): Set<String> {
        return cachedToolNames ?: allToolMap().keys.also { cachedToolNames = it }
    }

    /**
     * XML parser needs param names from all tiers to recognise parameter tags
     * inside tool calls for deferred tools. Cached alongside [allToolNames].
     */
    fun allParamNames(): Set<String> {
        return cachedParamNames ?: allToolMap().values
            .flatMapTo(LinkedHashSet()) { it.parameters.properties.keys }
            .also { cachedParamNames = it }
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
