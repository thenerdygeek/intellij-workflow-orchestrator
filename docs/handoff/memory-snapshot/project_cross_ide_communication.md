---
name: Cross-IDE Agent Communication
description: Two IntelliJ instances communicating so agents can collaborate on cross-service debugging (e.g., service calls simulator, bug is in simulator)
type: project
---

## Use Case

User has two Spring Boot projects open in separate IntelliJ instances:
- **Service** — receives API request, does DB check + validation, calls Simulator API
- **Simulator** — receives call from Service, does its own validation + DB, responds

When debugging an issue in the Service API, the agent may discover the root cause is in Simulator. Currently the Service plugin has no context about Simulator code. With cross-IDE communication, the Service agent can wake up the Simulator agent, share relevant context (request/response, error details), and they coordinate to find the root cause. Interactive debugging across both services is also a goal.

## Design Decisions Made

1. **Both IDEs must be open** — no headless launching of the second IDE
2. **Receiving agent must be idle** — agent tab not in use, no active conversation running
3. **IPC mechanism: Unix Domain Sockets (JEP 380)** — researched 8 options, UDS won on all dimensions:
   - Zero corporate Windows firewall risk (no TCP/IP stack involved)
   - Native JDK 21 support (`java.net.UnixDomainSocketAddress`) — zero dependencies
   - Sub-millisecond latency
   - Cross-platform (Windows 10 17063+ and macOS)
   - No port conflicts (socket is a file path)
   - Socket path: `~/.workflow-orchestrator/ipc.sock` or `%LOCALAPPDATA%\WorkflowOrchestrator\ipc.sock`

## How to apply

This is a planned feature. When designing: the IPC research is at `reference_ipc_cross_instance_research.md`. The use case is cross-service debugging where one agent delegates investigation to another project's agent. Consider: discovery protocol, context handoff format, bidirectional messaging for back-and-forth investigation, integration with interactive debugger.
