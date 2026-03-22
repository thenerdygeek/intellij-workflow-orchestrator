# How to Execute the Chat UI Overhaul Plan

## Quick Start Prompt

Copy-paste this into a new Claude Code session:

---

```
Execute the agentic chat UI overhaul implementation plan using subagent-driven development.

**Plan location:** `docs/superpowers/plans/2026-03-22-agentic-chat-ui-overhaul.md`
**Spec location:** `docs/superpowers/specs/2026-03-22-agentic-chat-ui-overhaul-design.md`
**Work in worktree:** `.worktrees/phase-3-agentic-ai/` (branch: `feature/phase-3-agentic-ai-foundation`)

The plan has 22 tasks across 5 phases:
- Phase 1 (Tasks 1-3): Build pipeline — Vite + React + Tailwind scaffolding, JCEF bridge protocol, theme system
- Phase 2 (Tasks 4-7): Core chat — Zustand stores, message components, streaming pipeline, input bar
- Phase 3 (Tasks 8-12): Agentic components — tool cards, thinking blocks, plan card, approval gate, question wizard
- Phase 4 (Tasks 13-18): Rich visualizations — RichBlock wrapper, Shiki code blocks, Mermaid, Chart.js, KaTeX, diffs, settings UI
- Phase 5 (Tasks 19-22): Polish — animations, accessibility, Gradle integration, cutover & cleanup

Each task has complete code, exact file paths, and step-by-step instructions. Use subagent-driven development — dispatch one implementer subagent per task, then spec review + code quality review between tasks.

IMPORTANT CONTEXT:
- This is a shadow build. The old `agent-chat.html` stays working throughout. The React app is built standalone in `agent/webview/` via Vite.
- All work happens in the phase-3 worktree at `.worktrees/phase-3-agentic-ai/agent/webview/`
- prompt-kit components are installed via `npx shadcn add "https://prompt-kit.com/c/<component>.json"` — they get copied into the project
- The JCEF bridge has 70 methods (26 JS→Kotlin, 44 Kotlin→JS) — all documented in the plan
- Dev testing: `cd agent/webview && npm run dev` serves at localhost:5173 with mock bridge
- Production build: `cd agent/webview && npm run build` outputs to `agent/src/main/resources/webview/dist/`

Start with Task 1 and proceed sequentially. Tasks within a phase are sequential (each depends on the previous). Phases are sequential.
```

---

## Pre-Requisites

Before starting execution, ensure:

1. **Node.js 18+** is installed (`node --version`)
2. **npm** is available (`npm --version`)
3. The worktree exists: `.worktrees/phase-3-agentic-ai/`
4. You're working from the project root: `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin`

## What the Plan Produces

When all 22 tasks are complete:

```
agent/
  webview/                    # NEW — Complete React app
    src/                      # ~50 React components
    package.json              # All dependencies
    vite.config.ts            # Build config
    tailwind.config.ts        # IDE theme mapping
  src/main/
    resources/webview/dist/   # Built output (from npm run build)
    kotlin/.../ui/
      AgentCefPanel.kt        # Modified — serves React build
      CefResourceSchemeHandler.kt  # Modified — serves dist/
      AgentDashboardPanel.kt  # Simplified — JCEF only
      AgentVisualizationTab.kt  # NEW — editor tab popout
      # RichStreamingPanel.kt — DELETED
  src/main/resources/webview/
    agent-chat.html           # DELETED
    agent-plan.html           # DELETED
    lib/                      # DELETED (replaced by npm packages)
```

## Verification

After all tasks complete:
- `cd agent/webview && npm run build` — React app builds successfully
- `./gradlew :agent:test` — all tests pass
- `./gradlew verifyPlugin` — API compatibility OK
- `./gradlew buildPlugin` — full plugin builds
