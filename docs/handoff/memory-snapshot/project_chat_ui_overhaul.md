---
name: Chat UI Overhaul — React Migration
description: Full React migration of agent chat UI from monolithic HTML to React 18 + TypeScript + Tailwind CSS + Vite, using prompt-kit components
type: project
---

## Status: Implementation plan complete, ready for execution

**Spec:** `docs/superpowers/specs/2026-03-22-agentic-chat-ui-overhaul-design.md`
**Plan:** `docs/superpowers/plans/2026-03-22-agentic-chat-ui-overhaul.md` (9,986 lines, 22 tasks)
**Branch:** `feature/phase-3-agentic-ai-foundation` (worktree at `.worktrees/phase-3-agentic-ai/`)

## Key Decisions
- React 18 + TypeScript + Tailwind CSS v4 + Vite (not Preact — React is the gold standard)
- prompt-kit components (MIT, shadcn/ui-based, copy-paste model)
- Full feature parity in one go — no half-migrated state
- Shadow build — develop standalone via `npm run dev`, single cutover when complete
- JCEF only — drop Swing fallback (IntelliJ 2025.1+ guaranteed)
- Elevated IDE styling — prompt-kit aesthetic + JetBrains Mono + IDE colors
- Single panel layout + fullscreen popout (overlay + editor tab)
- Contextual input bar — clean by default, progressive disclosure
- Per-type visualization settings (toggles + behavior config)
- CSS transitions only — no motion/framer-motion library

## Architecture
- React app lives in `agent/webview/` (new Vite project)
- Builds to `agent/src/main/resources/webview/dist/`
- Served from JAR via existing CefResourceSchemeHandler
- All 70 JCEF bridges preserved (26 JS→Kotlin, 44 Kotlin→JS)
- Zustand for state management
- Lazy-loaded viz libs: Mermaid, Chart.js, KaTeX, dagre, diff2html

**Why:** The current `agent-chat.html` is a 4,374-line monolithic file with no component model, no TypeScript, no testing, markdown re-parsed every 300ms, and no virtual scrolling. The React migration fixes all of these while adding configurable rich visualizations.

**How to apply:** Execute the 22-task plan using superpowers:subagent-driven-development. Work in the phase-3 worktree. The plan has complete code for every component.
