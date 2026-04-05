# Specialist Agent Integration Design

**Date:** 2026-04-05
**Status:** Approved
**Scope:** Wire `AgentConfigLoader` specialist configs into `SpawnAgentTool` via an `agent_type` parameter

## Problem

The plugin ships 8 specialist agent configs (spring-boot-engineer, test-automator, code-reviewer, etc.) loaded by `AgentConfigLoader` from markdown files with YAML frontmatter. Each config defines a curated system prompt, tool allowlist, and max turns. However, `SpawnAgentTool` — the only tool that actually spawns sub-agents — knows nothing about these configs. It only supports a generic `scope` parameter (research/implement/review) with hardcoded prompts.

The config loading infrastructure exists but is disconnected from execution.

## Design

### Approach: Single Tool with `agent_type` Parameter

Add an optional `agent_type` parameter to the existing `agent` tool. When set, look up the named config from `AgentConfigLoader` and use its system prompt, tool allowlist, and max turns. When not set, fall back to the current scope-based behavior.

This avoids registering 8+ separate `use_subagent_*` tools that bloat the schema sent on every API call.

### Parameter Schema

New parameter added to `SpawnAgentTool.parameters`:

| Parameter | Type | Required | Description |
|---|---|---|---|
| `agent_type` | string | no | Name of a specialist agent. When set, `scope` is ignored. |

Existing parameters (`description`, `prompt`, `scope`, `max_iterations`, `prompt_2`..`prompt_5`) are unchanged.

Rules:
- `agent_type` set: config's prompt, tools, and max-turns are used; `scope` is ignored
- `agent_type` not set: current scope-based behavior (research/implement/review) is preserved
- Parallel prompts (`prompt_2`..`prompt_5`) only work with scope-based mode, not `agent_type`

### Dynamic Tool Description

The tool description includes a dynamically-built suffix listing available agent types and their descriptions. This is rebuilt when `AgentConfigLoader` reloads (including user-added agents from `~/.workflow-orchestrator/agents/`).

```
Available agent types:
- spring-boot-engineer: Spring Boot 3+ development — REST APIs, security, JPA, testing
- test-automator: JUnit 5 + MockK, slice tests, integration tests, TDD
- code-reviewer: Quality, security, performance, maintainability review
...
```

### Execution Flow

```
LLM calls agent(agent_type="test-automator", prompt="...")
  |
  +-- agent_type set?
  |   YES -> configLoader.getCachedConfig("test-automator")
  |          -> config not found? return error result
  |          -> resolve config.tools against toolRegistry (skip unknown)
  |          -> use config.systemPrompt as sub-agent system prompt
  |          -> use config.maxTurns (fall back to clampedIterations)
  |          -> infer planMode from whether config has write tools
  |   NO  -> fall back to scope-based behavior (unchanged)
  |
  +-- create SubagentRunner with resolved prompt/tools/maxIter/planMode
```

### Plan Mode Inference

When using `agent_type`, plan mode is inferred from the resolved tool set rather than hardcoded from scope:

```kotlin
val hasWriteTools = resolvedTools.keys.any { it in WRITE_TOOL_NAMES }
planMode = !hasWriteTools
```

This means:
- `code-reviewer` (no write tools) -> plan mode ON
- `spring-boot-engineer` (has edit_file, create_file, run_command) -> plan mode OFF
- `test-automator` (has edit_file, create_file, run_command) -> plan mode OFF

### Tool Resolution

Config tools are string names. Resolution uses the existing `ToolRegistry`:

```kotlin
fun resolveConfigTools(config: AgentConfig): Map<String, AgentTool> {
    return config.tools.mapNotNull { name ->
        toolRegistry.get(name)?.let { name to it }
    }.toMap()
}
```

Unknown tool names are logged as warnings and skipped. The `agent` tool is always excluded from resolved tools (depth-1 enforcement, same as scope-based mode).

## Changes Required

### `SpawnAgentTool`

- Constructor: accept `AgentConfigLoader` reference
- New `agent_type` parameter in schema
- `resolveFromConfig(configName)`: looks up config, resolves tools, infers plan mode
- `execute()`: check `agent_type` first, fall back to scope
- `buildDescription()`: dynamically includes available agent types from config loader
- Register as config change listener to rebuild description on reload

### `AgentService`

- Pass `AgentConfigLoader.getInstance()` to `SpawnAgentTool` constructor

### Agent MD Files (8 files)

- Fix `worker_complete` -> `attempt_completion` (the actual tool name)

### No Changes Required

- `SubagentRunner` — already accepts arbitrary system prompt + tools
- `AgentLoop` — no changes
- `AgentConfigLoader` — already has `getCachedConfig()`, change listener, hot-reload
- `ContextManager` — no changes

## Error Handling

| Scenario | Behavior |
|---|---|
| Unknown `agent_type` | Return error: "Unknown agent type 'foo'. Available: ..." |
| Config has no resolvable tools | Return error: "Agent 'foo' has no resolvable tools" |
| Both `agent_type` and `scope` set | `agent_type` wins, `scope` ignored silently |
| `agent_type` with parallel prompts | Only primary `prompt` used, extras ignored silently |

## Testing

- Unit test: `agent_type` resolves config and uses its prompt/tools
- Unit test: unknown `agent_type` returns error with available list
- Unit test: `agent_type` without `scope` works (no NPE)
- Unit test: `scope` still works when `agent_type` is absent (backward compat)
- Unit test: plan mode inferred correctly from config tool set
- Unit test: description includes dynamically loaded agent types
