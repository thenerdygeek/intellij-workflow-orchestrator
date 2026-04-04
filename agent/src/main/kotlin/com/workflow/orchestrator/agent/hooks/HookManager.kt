package com.workflow.orchestrator.agent.hooks

import com.intellij.openapi.diagnostic.Logger

/**
 * Manages hook registration and dispatch for all lifecycle events.
 *
 * Faithful port of Cline's hook orchestration pattern:
 * - Cline's HookFactory discovers hooks from filesystem directories
 * - Cline's executeHook() runs hooks with streaming, status tracking, abort support
 * - Cline's CombinedHookRunner merges global + workspace hooks
 *
 * We simplify to:
 * - Hooks loaded from .agent-hooks.json config file
 * - HookRunner executes each hook as a shell command
 * - HookManager dispatches to all registered hooks for a given type
 *
 * Zero-overhead when no hooks are configured: hasHook() check is O(1),
 * dispatch() returns Proceed immediately if no hooks exist for the type.
 * This matches Cline's pattern: "if (!hasHook) return { wasCancelled: false }"
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/hooks/hook-factory.ts">Cline HookFactory</a>
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/hooks/hook-executor.ts">Cline executeHook</a>
 */
class HookManager(
    private val runner: HookRunner
) {
    private val hooks = mutableMapOf<HookType, MutableList<HookConfig>>()

    companion object {
        private val LOG = Logger.getInstance(HookManager::class.java)
    }

    /**
     * Register a hook configuration.
     * Multiple hooks can be registered for the same type (Cline supports
     * global + workspace hooks for the same event).
     */
    fun register(config: HookConfig) {
        hooks.getOrPut(config.type) { mutableListOf() }.add(config)
        LOG.info("[HookManager] Registered ${config.type.hookName} hook: ${config.command.take(50)}")
    }

    /**
     * Unregister a hook by type and command.
     */
    fun unregister(type: HookType, command: String) {
        hooks[type]?.removeAll { it.command == command }
        if (hooks[type]?.isEmpty() == true) {
            hooks.remove(type)
        }
    }

    /**
     * Check if any hooks are registered for the given type.
     *
     * Matches Cline's HookFactory.hasHook() — used for early return
     * to avoid creating abort controllers and hook status messages
     * when no hooks exist.
     */
    fun hasHooks(type: HookType): Boolean = !hooks[type].isNullOrEmpty()

    /**
     * Check if any hooks are registered at all.
     */
    fun hasAnyHooks(): Boolean = hooks.values.any { it.isNotEmpty() }

    /**
     * Get the count of registered hooks for a type.
     */
    fun hookCount(type: HookType): Int = hooks[type]?.size ?: 0

    /**
     * Get total count of all registered hooks.
     */
    fun totalHookCount(): Int = hooks.values.sumOf { it.size }

    /**
     * Clear all registered hooks.
     */
    fun clearAll() {
        hooks.clear()
    }

    /**
     * Run all hooks for an event type. Returns the aggregate result.
     *
     * Faithful port of Cline's hook execution flow:
     * - For cancellable events: stop on first Cancel result
     *   (Cline: "if (result.cancel === true) { ... return }")
     * - For observation-only events: run all hooks, ignore exit codes
     *   (Cline: notification hooks ignore cancel/contextModification)
     * - No hooks registered: return Proceed immediately (zero overhead)
     *
     * Context modification from hooks:
     * - Cline's contextModification is passed back to the caller
     * - If multiple hooks provide context, they are concatenated
     * - Caller decides how to inject into conversation (e.g., ToolHookUtils.addHookContextToConversation)
     *
     * @param event the hook event to dispatch
     * @return aggregated HookResult (Proceed or Cancel)
     *
     * @see <a href="https://github.com/cline/cline/blob/main/src/core/hooks/hook-executor.ts">Cline executeHook</a>
     */
    suspend fun dispatch(event: HookEvent): HookResult {
        val typeHooks = hooks[event.type]
        if (typeHooks.isNullOrEmpty()) {
            return HookResult.Proceed()
        }

        LOG.info("[HookManager] Dispatching ${event.type.hookName} to ${typeHooks.size} hook(s)")

        // Collect context modifications from all hooks
        val contextModifications = mutableListOf<String>()

        for (hook in typeHooks) {
            val result = try {
                runner.execute(hook, event)
            } catch (e: Exception) {
                // Cline: hook errors are fail-open (non-fatal)
                LOG.warn("[HookManager] ${event.type.hookName} hook failed: ${e.message}")
                HookResult.Proceed()
            }

            when (result) {
                is HookResult.Cancel -> {
                    if (event.cancellable) {
                        // For cancellable events, stop on first Cancel
                        // Cline: "if (result.cancel === true) return fromHookOutput(result)"
                        LOG.info("[HookManager] ${event.type.hookName} cancelled by hook: ${result.reason}")
                        return result
                    } else {
                        // For observation-only events, log but continue
                        // Cline: "[Notification Hook] Ignoring unsupported cancel output"
                        LOG.info("[HookManager] ${event.type.hookName} cancel ignored (observation-only hook)")
                    }
                }
                is HookResult.Proceed -> {
                    result.contextModification?.let { contextModifications.add(it) }
                }
            }
        }

        // Aggregate context modifications
        val aggregatedContext = if (contextModifications.isNotEmpty()) {
            contextModifications.joinToString("\n\n")
        } else {
            null
        }

        return HookResult.Proceed(contextModification = aggregatedContext)
    }

    /**
     * Load hooks from .agent-hooks.json config file in the project root.
     *
     * Matches Cline's hook discovery pattern (HookFactory constructor + getAllHooksDirs):
     * Cline scans filesystem directories for executable scripts.
     * We read a JSON config file for portability.
     *
     * @param projectPath the project root directory path
     */
    fun loadFromConfigFile(projectPath: String) {
        val configs = HookConfigLoader.load(projectPath)
        for (config in configs) {
            register(config)
        }
        if (configs.isNotEmpty()) {
            LOG.info("[HookManager] Loaded ${configs.size} hook(s) from ${HookConfigLoader.CONFIG_FILE_NAME}")
        }
    }
}
