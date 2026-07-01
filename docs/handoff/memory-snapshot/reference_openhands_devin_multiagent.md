# OpenHands & Devin Multi-Agent Architecture

## OpenHands V0 (Legacy)
- AgentDelegateAction: dataclass with `agent` (name), `inputs` (dict). Controller spawns child AgentController sharing EventStream.
- One delegate at a time. Parent pauses while delegate runs. Events routed through on_event().
- CodeActAgent delegates to BrowsingAgent via `delegate_to_browsing_agent` tool call in function_calling.py.
- BrowsingAgent is fully independent: own system prompt, BrowserGym action space, accessibility tree observations.
- Delegation result returned as AgentDelegateObservation with tool_call_metadata for associating with parent's tool call.

## OpenHands V1 (Software Agent SDK)
- DelegateTool: standard tool, not special action. Two phases: spawn (assign IDs) then delegate (assign tasks).
- Parallel sub-agents via threads. Blocking until all complete. Consolidated results returned.
- Sub-agents inherit LLM config + workspace context. Independent conversations.
- max_children configurable.
- Paper (arXiv 2511.03690): event-sourced state, typed Action-Execution-Observation pattern, MCP integration.

## Devin
- Compound AI system: Planner (high-reasoning), Coder (code-specialized), Critic (adversarial reviewer).
- Managed Devins: full Devin instances in isolated VMs. Main session = coordinator.
- Structured output schemas for child sessions. State persists via notes across sessions.
- System prompt is SINGLE AGENT with rich tools (no multi-agent at prompt level). Multi-agent is platform-level.
- Planning/standard mode separation. Mandatory <think> before critical decisions.
- Explicit tool prohibitions: "never use grep, use search commands" pattern.

## Key Patterns
- Delegation as tool (V1) > delegation as special action (V0)
- Event-sourced state for replay/recovery
- Parallel sub-agents with consolidated results
- Microagents = keyword-triggered prompt injection
- Think-before-act at critical decision points
- Explicit prohibitions + redirections for tool selection

## Full research: docs/research/openhands-devin-multi-agent-research.md
