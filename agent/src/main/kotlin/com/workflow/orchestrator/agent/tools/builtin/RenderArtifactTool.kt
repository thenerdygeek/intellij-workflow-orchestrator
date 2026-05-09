package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ArtifactPayload
import com.workflow.orchestrator.agent.tools.ArtifactRenderResult
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class RenderArtifactTool : AgentTool {
    override val name = "render_artifact"
    override val description = """Render an interactive React component in the chat as a visual artifact. Use alongside your text response when a visualization would help the user understand architecture, flows, hierarchies, or data comparisons.

The component renders in a sandboxed iframe with these scope variables (use directly — NOT as imports, NOT as props):

React: React, useState, useEffect, useCallback, useMemo, useRef, useReducer, useLayoutEffect, useId, useTransition, Fragment
Bridge: bridge.navigateToFile(path, line), bridge.isDark, bridge.colors, bridge.projectName

UI (shadcn/ui compatible):
- Layout: Card/CardHeader/CardTitle/CardDescription/CardContent/CardFooter, Separator, ScrollArea/ScrollBar, Skeleton
- Actions: Button (variants: default/primary/destructive/outline/secondary/ghost/link; sizes: default/sm/lg/icon), Badge (variants), Toggle
- Feedback: Alert/AlertTitle/AlertDescription (variants: default/destructive/success/warning/info), Progress (variants), Tooltip/TooltipProvider
- Navigation & Disclosure: Tabs/TabsList/TabsTrigger/TabsContent, Accordion/AccordionItem/AccordionTrigger/AccordionContent, Breadcrumb/BreadcrumbList/BreadcrumbItem/BreadcrumbLink/BreadcrumbPage/BreadcrumbSeparator
- Overlays: Dialog/DialogTrigger/DialogContent/DialogHeader/DialogFooter/DialogTitle/DialogDescription/DialogClose, Sheet/SheetTrigger/SheetContent/SheetHeader/SheetFooter/SheetTitle/SheetDescription (side: top/bottom/left/right), Popover/PopoverTrigger/PopoverContent, HoverCard/HoverCardTrigger/HoverCardContent, DropdownMenu/DropdownMenuTrigger/DropdownMenuContent/DropdownMenuItem/DropdownMenuLabel/DropdownMenuSeparator/DropdownMenuShortcut
- Forms: Input, Label, Textarea, Select/SelectTrigger/SelectValue/SelectContent/SelectItem/SelectLabel/SelectSeparator/SelectGroup, Switch, Checkbox, Slider
- Data: Avatar/AvatarImage/AvatarFallback

Recharts: BarChart, Bar, LineChart, Line, AreaChart, Area, PieChart, Pie, Cell, XAxis, YAxis, CartesianGrid, RechartsTooltip, Legend, ResponsiveContainer, RadialBarChart, RadialBar, ComposedChart, Scatter, ScatterChart, Treemap, FunnelChart, Funnel, RadarChart, Radar, PolarGrid, PolarAngleAxis, PolarRadiusAxis, LabelList
Icons: All Lucide icons by name (Globe, FileCode, Server, Shield, Zap, Database, GitBranch, etc.)
Animation: motion, AnimatePresence, useMotionValue, useTransform, useSpring, useInView, useScroll, useAnimation
D3: d3 (full namespace — d3.scaleLinear, d3.arc, d3.geoPath, etc.)
Globe: createGlobe (cobe library — renders globe on canvas)
Maps: ComposableMap, Geographies, Geography, Marker, MapLine, ZoomableGroup, Graticule, Sphere
Hand-drawn: rough (roughjs — rough.canvas(canvasEl).rectangle(...))

Node/edge graphs (xyflow/react): ReactFlow namespace + shortcut identifiers ReactFlowCanvas, Background, Controls, MiniMap, Handle, Position, MarkerType, useNodesState, useEdgesState, useReactFlow, addEdge, applyNodeChanges, applyEdgeChanges, ReactFlowProvider. Use for architecture diagrams, dependency graphs, flow charts, state machines.

Tables (@tanstack/react-table, headless): ReactTable namespace + useReactTable, getCoreRowModel, getSortedRowModel, getFilteredRowModel, getPaginationRowModel, getGroupedRowModel, getExpandedRowModel, flexRender, createColumnHelper. You write the <table>/<thead>/<tbody> markup yourself; the hooks provide state + row models.

Date/time (date-fns): dateFns namespace + shortcut identifiers format, formatDistance, formatDistanceToNow, formatRelative, parseISO, addDays, subDays, addHours, subHours, differenceInDays/Hours/Minutes, isAfter, isBefore, isSameDay, startOfDay, endOfDay, startOfWeek, endOfWeek, startOfMonth, endOfMonth.

Colors (colord): colord(value) factory for color manipulation — e.g. colord('#6366f1').lighten(0.1).toHex(). Plus colordExtend for plugins.

Use when: 3+ entities with relationships, multi-step flows, data comparisons as charts, dashboards with tables/metrics/timelines, or user explicitly asked for a visual.
Do NOT use when: short text answers, fewer than 3 items, yes/no questions, or text is sufficient.

The source must export a default function component. All scope variables are available directly (do NOT write import statements — they cause errors). All data must be inline. Use Tailwind CSS classes, not inline styles.

Before calling render_artifact, load the frontend-design skill via use_skill("frontend-design") for component APIs and design guidelines."""

    override val parameters = FunctionParameters(
        properties = mapOf(
            "title" to ParameterProperty(
                type = "string",
                description = "Short title for the artifact (e.g., 'Authentication Flow', 'Module Dependencies')"
            ),
            "source" to ParameterProperty(
                type = "string",
                description = "JSX source code. Must export a default function component. bridge, useState, useEffect etc. are scope variables (use directly, NOT as props). All data must be inline."
            )
        ),
        required = listOf("title", "source")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override fun documentation(): ToolDocumentation = toolDoc("render_artifact") {
        summary {
            technical(
                "Renders an LLM-generated React component in a sandboxed iframe (react-runner via " +
                    "agent/webview/src/sandbox-main.ts) and SUSPENDS the agent loop until the iframe " +
                    "reports the actual outcome via JCEF bridge → ArtifactResultRegistry. Returns " +
                    "Success(heightPx) / RenderError(phase, message, missingSymbols, line) / " +
                    "Timeout(30s) / Skipped(headless). Self-repair loop: extractMissingSymbols parses " +
                    "ReferenceError/TypeError phrasings so the LLM gets back a structured list of " +
                    "missing identifiers + the canonical SCOPE_HINT and can self-correct on the next " +
                    "iteration. Sandbox scope: React hooks, shadcn/Radix UI primitives, Recharts, " +
                    "Lucide, motion/react, d3, cobe, roughjs, react-simple-maps, @xyflow/react, " +
                    "@tanstack/react-table, date-fns, colord. No imports, no network, all data inline."
            )
            plain(
                "Like asking the LLM to draw — instead of describing a chart in words or sketching " +
                    "ASCII art, it can ship a real interactive widget that renders inline in the chat: " +
                    "charts, dependency graphs, sortable tables, dashboards, mockups. The widget is a " +
                    "tiny React app running in a locked-down browser sandbox: no internet, no file " +
                    "access, all the data baked in. If the LLM tries to use something the sandbox " +
                    "doesn't have, the error comes back with a list of what IS available so the LLM " +
                    "can try again."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without render_artifact, the LLM falls back to ASCII art, Markdown tables, and " +
                "fenced code blocks for everything that wants to be visual. Architecture diagrams " +
                "become indented bullet trees; build-duration trends become tabulated numbers; " +
                "dependency graphs become 'A → B → C' chains. The user loses interactivity entirely — " +
                "no hover, no sort, no click-to-navigate, no dark-mode-aware charts. Markdown can " +
                "render images via the rich block pipeline, but the LLM has no path to GENERATE them; " +
                "this tool is the only one that lets the model produce non-trivial visual output."
        )
        llmMistake(
            "Writes `import { motion } from 'motion/react'` (or any other import statement). The " +
                "sandbox injects identifiers as scope variables — imports are syntactically rejected " +
                "by react-runner and surface as a parse-phase RenderError. The description and " +
                "SCOPE_HINT both warn against this; the self-repair loop usually catches it on retry."
        )
        llmMistake(
            "Reaches for a library not in scope — `Histogram` (not in Recharts), `framer-motion` " +
                "(only `motion/react` is exposed), `react-table v7` (only the v8 `@tanstack/react-table` " +
                "headless API), `Mermaid` (Mermaid is for chat code blocks, not artifacts). Surfaces " +
                "as ReferenceError; extractMissingSymbols catches the identifier and the LLM gets " +
                "the SCOPE_HINT inventory back so it can swap to a sandbox-native equivalent."
        )
        llmMistake(
            "Tries to fetch data at runtime (`fetch('...')`, `useEffect` calling an API). The " +
                "sandbox has no network egress — the request fails, the component renders empty, " +
                "and the LLM gets confused about why the chart is blank. Correct pattern: aggregate " +
                "the data on the Kotlin/agent side first, then bake it inline as a JS literal."
        )
        llmMistake(
            "Calls render_artifact for trivial output — a one-line answer wrapped in a `<Card>`, " +
                "a yes/no question rendered as a `<Badge>`, two PRs in a `<Table>`. The system prompt " +
                "explicitly discourages this (`Use when: 3+ entities …; Do NOT use when: short text " +
                "answers, fewer than 3 items, yes/no`), but the LLM occasionally over-reaches when " +
                "frontend-design skill is loaded."
        )
        llmMistake(
            "Forgets `export default` — the source-validator rejects with " +
                "'source must export a default function component' before the iframe is even " +
                "mounted. Cheap to detect, but a recurring source of churn when the LLM emits a " +
                "named export instead."
        )
        llmMistake(
            "Doesn't wire bridge.navigateToFile when the artifact lists files (a diagnostics view, " +
                "a call graph). The component renders fine but the user can't click through to source " +
                "— a missed feature, not a bug, but the IDE bridge is the whole point of rendering " +
                "in-IDE versus shipping a standalone HTML preview."
        )
        params {
            required("title", "string") {
                llmSeesIt(
                    "Short title for the artifact (e.g., 'Authentication Flow', 'Module Dependencies')"
                )
                humanReadable(
                    "The header shown above the rendered widget — like the caption on a figure in a " +
                        "paper. Keep it short; it's metadata, not content."
                )
                whenPresent(
                    "Used as the artifact card title in chat and echoed back in the success/error " +
                        "ToolResult content (`[Artifact: <title>] …`)."
                )
                constraint("kept short — title is rendered in the artifact card header, not inside the iframe")
                example("Authentication Flow")
                example("Module Dependencies")
                example("Build duration trend (last 30 builds)")
            }
            required("source", "string") {
                llmSeesIt(
                    "JSX source code. Must export a default function component. bridge, useState, " +
                        "useEffect etc. are scope variables (use directly, NOT as props). All data " +
                        "must be inline."
                )
                humanReadable(
                    "The actual React component, as a string of JSX. Same shape as a `.tsx` file — " +
                        "but with no import lines, no external data fetches, and a hard cap on size."
                )
                whenPresent(
                    "Validated for the literal text `export default` before being shipped to the " +
                        "iframe. The sandbox's react-runner compiles and mounts it against the " +
                        "fullScope object; failures surface back through the self-repair loop."
                )
                constraint("must contain the literal text `export default` — pre-render validator rejects otherwise")
                constraint("max 100,000 characters (~100KB) — larger sources are rejected with a 'simplify' nudge")
                constraint("no `import` statements — react-runner parses them as syntax errors")
                constraint("no runtime network access — `fetch`, `XHR`, dynamic `<script>` tags all fail silently")
                example("export default function Flow() { return <BarChart width={400} height={300} data={[…inline…]}>…</BarChart>; }")
            }
        }
        verdict {
            keep(
                "STRONG keep — this is the only tool that produces interactive output. Every other " +
                    "tool is text-bound (Markdown, code blocks, ASCII). The bundled webview surface " +
                    "(react-runner + Recharts + xyflow + tanstack-table + d3 + cobe + roughjs + " +
                    "react-simple-maps + date-fns + colord + shadcn/Radix + Lucide + motion) exists " +
                    "specifically to give this tool teeth: dependency graphs, dashboards, charts, " +
                    "sortable tables, IDE-bridge navigation. The self-repair loop (parse missing " +
                    "symbols → return SCOPE_HINT → LLM retries) makes it a pleasant tool to use; " +
                    "without that scaffolding the failure mode would be 'crash silently and give up'.",
                VerdictSeverity.STRONG,
            )
        }
        related(
            "attempt_completion",
            Relationship.SEE_ALSO,
            "Conceptual contrast: attempt_completion ends the session with a final text message; " +
                "render_artifact augments an in-progress assistant turn with a visual. Use " +
                "render_artifact alongside text in the same turn — not as a substitute for completion."
        )
        related(
            "read_file",
            Relationship.COMPOSE_WITH,
            "Read the source first to harvest data (function names, dependency edges, test counts), " +
                "then render_artifact with that data baked inline. The pipeline is data-gather → " +
                "summarise → render."
        )
        related(
            "search_code",
            Relationship.COMPOSE_WITH,
            "Aggregate matches with search_code (diagnostic counts, callers per function, etc.) " +
                "and render the aggregate as a chart or table — search_code's text output benefits " +
                "from being summarised visually."
        )
        related(
            "use_skill",
            Relationship.COMPLEMENT,
            "The system prompt instructs the LLM to load `frontend-design` via use_skill before " +
                "calling render_artifact for the canonical component APIs and design guidelines."
        )
        related(
            "ask_followup_question",
            Relationship.ALTERNATIVE,
            "When the LLM needs the user to MAKE a choice (multi-select, single-select), use " +
                "ask_followup_question's wizard mode — it has built-in option handling. " +
                "render_artifact is for displaying information, not collecting structured input."
        )
        downside(
            "Sandbox is iframe-isolated with no network egress — `fetch`, dynamic imports, and " +
                "external scripts all fail. ALL data must be inline as a JS literal in the source. " +
                "For large datasets, the LLM has to summarise/aggregate on the agent side first."
        )
        downside(
            "Bundle scope is fixed at compile time of the webview. Adding a new library (e.g. " +
                "react-pdf, three.js) requires editing `agent/webview/package.json` + " +
                "`sandbox-main.ts` fullScope, rebuilding the webview, and updating both the " +
                "description AND SCOPE_HINT (kept hand-in-sync — see the SCOPE_HINT KDoc warning " +
                "about prompt drift). The TODO(layer-1) note plans a build-time-generated scope list."
        )
        downside(
            "Description AND SCOPE_HINT can drift from the actual sandbox `fullScope` object. " +
                "Today they are kept in sync by hand. A scope-list mismatch produces a " +
                "particularly unhelpful failure mode: the LLM sees `Histogram` listed in the prompt, " +
                "the sandbox throws ReferenceError on `Histogram`, and the self-repair loop returns " +
                "the same prompt that caused the failure. Mitigated by the SCOPE_HINT comment at " +
                "the source — but not eliminated."
        )
        downside(
            "100KB source cap is generous for hand-written React but not for auto-generated " +
                "tables of 1000+ rows or graphs with thousands of nodes. Large datasets need " +
                "aggregation upstream — the LLM occasionally tries to inline a giant array and " +
                "gets the 'simplify' rejection."
        )
        downside(
            "30-second render timeout. Components that hit infinite loops (uncontrolled " +
                "useEffect, while-true), make blocking calls, or render before the webview is " +
                "visible time out and surface as `Timeout`. The error message instructs the LLM to " +
                "remove heavy computations and async-effects-that-never-resolve, but the underlying " +
                "cause is occasionally just a hidden webview at the moment of render."
        )
        downside(
            "AGENT_CONTROL classification under-sells the surface area. The tool only mutates " +
                "the rendered iframe (no filesystem, no process spawn, no IDE state), but it does " +
                "introduce sandboxed JS execution generated by the LLM. The CSP, lack of network " +
                "egress, and inline-data-only constraints are what make this safe — not the " +
                "tool's own purity."
        )
        observation(
            "render_artifact is one of the few tools that uses `suspendCancellableCoroutine` " +
                "via ArtifactResultRegistry. If the user cancels the agent run mid-render, the " +
                "deferred completes with cancellation and the iframe is left orphaned. The " +
                "cleanup contract on AgentCefPanel handles disposal, but mid-flight render " +
                "cancellation is a less-tested path than completion."
        )
        observation(
            "The Skipped path (no listener registered) returns optimistic success. This is " +
                "intentional for headless test contexts where there is no real iframe — but it " +
                "means tests cannot easily assert on render correctness. End-to-end render " +
                "validation requires a live AgentCefPanel."
        )
        flowchart(
            """
            flowchart TD
                A[LLM emits render_artifact title source] --> B{title param present}
                B -- no --> X1[Error missing title]
                B -- yes --> C{source param present}
                C -- no --> X2[Error missing source]
                C -- yes --> D{contains export default}
                D -- no --> X3[Error missing default export]
                D -- yes --> E{source under 100KB}
                E -- no --> X4[Error source too large]
                E -- yes --> F[Generate UUID renderId]
                F --> G[ArtifactResultRegistry.renderAndAwait]
                G --> H[AgentCefPanel.renderArtifact pushes payload to React]
                H --> I[ArtifactRenderer mounts iframe via react-runner]
                I --> J{component compiles and mounts}
                J -- ReferenceError --> K[extractMissingSymbols parses error]
                K --> L[postMessage RenderError back]
                J -- ok --> M[postMessage Success heightPx]
                J -- runtime throw --> N[postMessage RenderError phase=runtime]
                L --> O[ArtifactRenderer.reportToKotlin via JCEF bridge]
                M --> O
                N --> O
                O --> P[AgentController.parseAndDispatchArtifactResult]
                P --> Q[ArtifactResultRegistry.reportResult completes deferred]
                Q --> R{outcome}
                R -- Success --> S[ToolResult with artifact payload, 15 token estimate]
                R -- RenderError --> T[ToolResult error with missingSymbols + SCOPE_HINT for self-repair]
                R -- Timeout 30s --> U[ToolResult error suggesting simplification]
                R -- Skipped headless --> V[Optimistic success for tests]
            """
        )
        narrative("render_artifact")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val title = params["title"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'title' parameter is required.",
                summary = "Error: missing title",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val source = params["source"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'source' parameter is required.",
                summary = "Error: missing source",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (!source.contains("export default")) {
            return ToolResult(
                content = "Error: source must export a default function component.",
                summary = "Error: missing default export",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val maxSourceSize = 100_000 // 100KB
        if (source.length > maxSourceSize) {
            return ToolResult(
                content = "Error: source is too large (${source.length} chars, max $maxSourceSize). Simplify the component — use fewer inline data items or split into smaller visualizations.",
                summary = "Error: source exceeds ${maxSourceSize / 1000}KB limit",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val renderId = UUID.randomUUID().toString()
        val payload = ArtifactPayload(title = title, source = source, renderId = renderId)

        // Push the artifact to the webview and suspend until the sandbox iframe reports
        // back with success/failure, or until the timeout expires. This closes the
        // feedback loop: the LLM now sees whether the render actually succeeded and can
        // self-correct on missing symbols / runtime errors instead of assuming success.
        val registry = ArtifactResultRegistry.getInstance(project)
        val renderResult = registry.renderAndAwait(payload)

        return when (renderResult) {
            is ArtifactRenderResult.Success -> ToolResult(
                content = "[Artifact: $title] Interactive component rendered in chat." +
                    (renderResult.heightPx?.let { " (rendered at ${it}px height)" } ?: ""),
                summary = "Rendered artifact: $title",
                tokenEstimate = 15,
                artifact = payload
            )

            is ArtifactRenderResult.RenderError -> {
                val missing = renderResult.missingSymbols
                val lineHint = renderResult.line?.let { " at line $it" } ?: ""
                val missingBlock = if (missing.isNotEmpty()) {
                    "\n\nMissing symbols (not in sandbox scope): ${missing.joinToString(", ")}" +
                        "\n\n$SCOPE_HINT"
                } else ""
                ToolResult(
                    content = "Artifact render failed [${renderResult.phase}]${lineHint}: " +
                        "${renderResult.message}$missingBlock\n\n" +
                        "Rewrite the component without the missing symbols and call render_artifact again. " +
                        "All data must be inline. Do not use import statements — scope variables are " +
                        "provided directly by the sandbox.",
                    summary = "Artifact render failed: ${renderResult.phase} — ${renderResult.message.take(80)}",
                    tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE + missing.size * 3,
                    isError = true,
                    // Still attach the payload so the failed render is visible in chat
                    // for the user (they see "Failed to render: ..." in the RichBlock).
                    artifact = payload
                )
            }

            is ArtifactRenderResult.Timeout -> ToolResult(
                content = "Artifact render timed out after ${renderResult.timeoutMillis / 1000}s. " +
                    "The sandbox iframe did not report a render outcome. This usually means the " +
                    "component entered an infinite loop, made a blocking call, or the webview is " +
                    "not currently visible. Simplify the component (remove heavy computations, " +
                    "async effects that never resolve) and try again.",
                summary = "Artifact render timed out (${renderResult.timeoutMillis / 1000}s)",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
                artifact = payload
            )

            is ArtifactRenderResult.Skipped -> ToolResult(
                // Legacy optimistic-success path for headless/test contexts where no
                // UI listener is registered. Matches the pre-registry behavior.
                content = "[Artifact: $title] Interactive component rendered in chat.",
                summary = "Rendered artifact: $title",
                tokenEstimate = 15,
                artifact = payload
            )
        }
    }

    companion object {
        /**
         * Exact list of identifiers exposed by the sandbox scope, injected verbatim
         * into every render-failure tool result so the LLM can see the available
         * identifiers in context when rewriting. Listed as *identifiers* (not package
         * names) on purpose — naming packages like "motion/react" would prime the LLM
         * to emit `import { motion } from 'motion/react'`, which is exactly the failure
         * mode this loop is meant to fix.
         *
         * Kept in sync by hand with `agent/webview/src/sandbox-main.ts`. TODO(layer-1):
         * replace with a build-time-generated list so the prompt cannot drift.
         */
        private const val SCOPE_HINT =
            "Available identifiers in the sandbox scope (use directly, NO import statements):\n" +
                "- React hooks: React, useState, useEffect, useCallback, useMemo, useRef, " +
                "useReducer, useLayoutEffect, useId, useTransition, Fragment\n" +
                "- Bridge: bridge.navigateToFile, bridge.isDark, bridge.colors, bridge.projectName\n" +
                "- Layout: Card/CardHeader/CardTitle/CardDescription/CardContent/CardFooter, " +
                "Separator, ScrollArea/ScrollBar, Skeleton\n" +
                "- Actions: Button (variants), Badge (variants), Toggle\n" +
                "- Feedback: Alert/AlertTitle/AlertDescription, Progress, Tooltip/TooltipProvider\n" +
                "- Navigation: Tabs/TabsList/TabsTrigger/TabsContent, " +
                "Accordion/AccordionItem/AccordionTrigger/AccordionContent, " +
                "Breadcrumb/BreadcrumbList/BreadcrumbItem/BreadcrumbLink/BreadcrumbPage/BreadcrumbSeparator\n" +
                "- Overlays: Dialog/DialogTrigger/DialogContent/DialogHeader/DialogFooter/DialogTitle/DialogDescription/DialogClose, " +
                "Sheet/SheetTrigger/SheetContent/SheetHeader/SheetFooter/SheetTitle/SheetDescription, " +
                "Popover/PopoverTrigger/PopoverContent, HoverCard/HoverCardTrigger/HoverCardContent, " +
                "DropdownMenu/DropdownMenuTrigger/DropdownMenuContent/DropdownMenuItem/DropdownMenuLabel/DropdownMenuSeparator\n" +
                "- Forms: Input, Label, Textarea, " +
                "Select/SelectTrigger/SelectValue/SelectContent/SelectItem, Switch, Checkbox, Slider\n" +
                "- Data: Avatar/AvatarImage/AvatarFallback\n" +
                "- Recharts: BarChart, Bar, LineChart, Line, AreaChart, Area, PieChart, Pie, Cell, " +
                "XAxis, YAxis, CartesianGrid, RechartsTooltip, Legend, ResponsiveContainer, " +
                "RadialBarChart, RadialBar, ComposedChart, Scatter, ScatterChart, Treemap, " +
                "FunnelChart, Funnel, RadarChart, Radar, PolarGrid, PolarAngleAxis, PolarRadiusAxis, LabelList\n" +
                "- Animation (motion): motion, AnimatePresence, useMotionValue, useTransform, " +
                "useSpring, useInView, useScroll, useAnimation\n" +
                "- D3: d3 (full namespace)\n" +
                "- Globe: createGlobe\n" +
                "- Hand-drawn: rough\n" +
                "- Maps: ComposableMap, Geographies, Geography, Marker, MapLine, ZoomableGroup, Graticule, Sphere\n" +
                "- Node graphs: ReactFlow namespace, ReactFlowCanvas, Background, Controls, MiniMap, " +
                "Handle, Position, MarkerType, useNodesState, useEdgesState, useReactFlow, addEdge, " +
                "applyNodeChanges, applyEdgeChanges, ReactFlowProvider\n" +
                "- Tables (headless, tanstack): ReactTable namespace, useReactTable, getCoreRowModel, " +
                "getSortedRowModel, getFilteredRowModel, getPaginationRowModel, getGroupedRowModel, " +
                "getExpandedRowModel, flexRender, createColumnHelper\n" +
                "- Date/time (date-fns): dateFns namespace + format, formatDistance, formatDistanceToNow, " +
                "formatRelative, parseISO, addDays, subDays, addHours, subHours, " +
                "differenceInDays/Hours/Minutes, isAfter, isBefore, isSameDay, startOfDay/Week/Month, endOfDay/Week/Month\n" +
                "- Colors: colord, colordExtend\n" +
                "- Icons: all Lucide icons by name (Globe, FileCode, Server, Shield, Zap, " +
                "Database, GitBranch, ArrowRight, Check, X, Search, …)"
    }
}
