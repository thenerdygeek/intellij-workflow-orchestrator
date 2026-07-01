---
name: UI/UX audit findings and fix plan status
description: Comprehensive agent tab UI/UX audit completed with 40+ findings. Fix plan written but NOT executed — user wants to discuss a chat screen overhaul first. Audit findings should become requirements for the new design.
type: project
---

## Status
- Audit completed: 2026-03-21
- Fix plan written: `docs/superpowers/plans/2026-03-21-ui-ux-audit-fixes.md` (11 tasks, 36 findings)
- Execution: **ON HOLD** — user has a chat screen overhaul in mind, discussing first

## Key Findings (use as requirements for any redesign)

**Critical:** Emoji icons (replace with SVG/unicode), hardcoded ANSI dark bg, light mode broken (9 rgba(255,255,255,x) occurrences)

**High:** Streaming→markdown re-render flash, no live elapsed time on running tools, command approval no risk distinction/allow-all, edit approval JSON fallback, token budget "150K" hardcoded, prefers-reduced-motion not respected, no focus-visible indicators, session resume shows no history

**Medium:** No agent label on messages, user msg orphaned indent, code copy button invisible until hover, diff card no max-height, thinking block not collapsible, all tool cards collapsed, no error retry button, tool args 300-char truncation, history panel wrong icon + no resume button, skill banner hardcoded color

**Deferred (needs architecture):** Queued message cancel, session message replay, Swing/JCEF visual disconnect, toolbar overflow menu

## Rich Chat UI Libraries (already implemented)
Core (bundled): marked.js, Prism.js, DOMPurify, ansi_up (~32KB)
Lazy-loaded: Mermaid.js, KaTeX, Chart.js, diff2html
All served via CefResourceSchemeHandler from JAR. Zero CDN dependency.
