# Workflow Orchestrator -- Architecture Documentation

This directory contains architectural documentation for the Workflow Orchestrator IntelliJ plugin, with interactive Mermaid diagrams covering every layer of the system.

## Quick Start

Open **[index.html](index.html)** in a browser for an interactive, dark-themed view of all diagrams with collapsible sections.

## Document Index

| Document | Description |
|---|---|
| [Module Structure](module-structure.md) | Gradle submodules, dependency rules, cross-module communication |
| [Data Flow](data-flow.md) | Sequence diagrams for the 6 core workflows |
| [Service Layer](service-layer.md) | ToolResult pattern, service interfaces, API client chain |
| [Event Bus](event-bus.md) | All 21 WorkflowEvent types, emitters, and consumers |
| [Threading Model](threading-model.md) | EDT vs IO vs Background rules, common patterns |
| [External APIs](external-apis.md) | 6 external services, auth methods, key endpoints |
| [UI Structure](ui-structure.md) | Tool window tabs, settings pages, editor integrations |
| [Interactive View](index.html) | All diagrams rendered in one scrollable HTML page |

## Architecture at a Glance

The plugin is a **modular monolith** -- a single IntelliJ plugin composed of 10 Gradle submodules. Feature modules depend only on `:core` and communicate through an event bus backed by Kotlin `SharedFlow`. All external API calls are suspending functions on `Dispatchers.IO`, and all UI updates happen on the EDT.

```
:core  <--  :jira, :bamboo, :sonar, :cody, :pullrequest, :automation, :handover, :git-integration, :mock-server
```

The plugin consolidates six external services (Jira, Bamboo, SonarQube, Bitbucket, Nexus, Cody) into a single "Workflow" tool window with six tabs: Sprint, Build, PR, Quality, Automation, and Handover.
