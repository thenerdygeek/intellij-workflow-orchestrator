package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ArtifactPayload
import com.workflow.orchestrator.agent.tools.ArtifactRenderResult
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
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
