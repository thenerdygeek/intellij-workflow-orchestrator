package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ArtifactPayload
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class RenderArtifactTool : AgentTool {
    override val name = "render_artifact"
    override val description = """Render an interactive React component in the chat as a visual artifact. Use alongside your text response when a visualization would help the user understand architecture, flows, hierarchies, or data comparisons.

The component renders in a sandboxed iframe with these scope variables (use directly — NOT as imports, NOT as props):

React: React, useState, useEffect, useCallback, useMemo, useRef, Fragment
Bridge: bridge.navigateToFile(path, line), bridge.isDark, bridge.colors, bridge.projectName
UI: Card, CardHeader, CardTitle, CardDescription, CardContent, Badge, Tabs, TabsList, TabsTrigger, TabsContent, Progress, Separator, Accordion, AccordionItem, Tooltip
Recharts: BarChart, Bar, LineChart, Line, AreaChart, Area, PieChart, Pie, Cell, XAxis, YAxis, CartesianGrid, RechartsTooltip, Legend, ResponsiveContainer, RadialBarChart, RadialBar, ComposedChart, Scatter, ScatterChart, Treemap, FunnelChart, Funnel, RadarChart, Radar, PolarGrid, PolarAngleAxis, PolarRadiusAxis, LabelList
Icons: All Lucide icons by name (Globe, FileCode, Server, Shield, Zap, Database, GitBranch, etc.)
Animation: motion, AnimatePresence, useMotionValue, useTransform, useSpring, useInView, useScroll, useAnimation
D3: d3 (full d3 namespace — d3.scaleLinear, d3.arc, d3.geoPath, etc.)
Globe: createGlobe (cobe library — renders globe on canvas)
Maps: ComposableMap, Geographies, Geography, Marker, MapLine, ZoomableGroup, Graticule, Sphere
Hand-drawn: rough (roughjs — rough.canvas(canvasEl).rectangle(...))

Use when: 3+ entities with relationships, multi-step flows, data comparisons as charts, or user explicitly asked for a visual.
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

        return ToolResult(
            content = "[Artifact: $title] Interactive component rendered in chat.",
            summary = "Rendered artifact: $title",
            tokenEstimate = 15,
            artifact = ArtifactPayload(title = title, source = source)
        )
    }
}
