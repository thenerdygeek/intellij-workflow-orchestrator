---
name: frontend-design
description: Use when producing interactive visualizations or React artifacts via render_artifact. Provides component APIs, design guidelines, Tailwind patterns, and IDE-specific interaction patterns. Load this skill BEFORE calling render_artifact.
user-invocable: true
preferred-tools: [render_artifact, think]
---

# Frontend Design for Artifacts

Produce distinctive, polished, interactive React components that render inside the IDE chat panel. Every artifact should look intentionally designed -- not like default grey-box AI output. Use strong visual hierarchy, color-coded semantics, purposeful whitespace, smooth hover states, and IDE-integrated click navigation. The goal is components a developer would screenshot and share, not just tolerate.

## Before Writing Code

Pause and run through three design decisions before touching JSX:

1. **What is this communicating?** Architecture overview, data flow, comparison table, status dashboard, dependency graph, class hierarchy? The answer determines the layout primitive (grid, tree, flow, chart).

2. **Pick an aesthetic direction:**
   - **Clean minimal** -- lots of whitespace, subtle borders, one accent color. Best for: architecture overviews, simple lists.
   - **Data-dense dashboard** -- compact cards, progress bars, badges, multiple metrics at once. Best for: build status, coverage reports, sprint summaries.
   - **Visual / diagrammatic** -- connected nodes, flow arrows, layered diagrams. Best for: data flow, sequence diagrams, dependency graphs.

3. **What is the interaction model?** Click a card to navigate to the file in the IDE? Hover for tooltip details? Tabs to switch views? Accordion to collapse sections? Selected state to highlight the active item? Decide before writing.

## Available in Scope

The sandbox provides these as scope variables. Use them directly -- they are NOT imports and NOT props. Do not write `import` statements. Do not destructure from function parameters.

### React Hooks

```
useState, useEffect, useCallback, useMemo, useRef, Fragment
```

### Bridge API

```jsx
// Navigate to a file in the IDE (opens editor, jumps to line)
bridge.navigateToFile(path, line)

// Theme detection
bridge.isDark        // boolean -- true when IDE is in dark theme

// Theme-aware CSS variable values (mirrors IDE colors)
bridge.colors        // Record<string, string> -- all CSS variables

// Project name
bridge.projectName   // string
```

### UI Components

These are available as scope variables. Use them directly in JSX.

**Card** -- container with header, title, description, and content sections:

```jsx
<Card className="rounded-lg border border-[var(--border)] bg-[var(--bg-card)]">
  <CardHeader>
    <CardTitle className="text-sm font-semibold">Module Name</CardTitle>
    <CardDescription className="text-xs text-[var(--fg-muted)]">
      3 services, 12 endpoints
    </CardDescription>
  </CardHeader>
  <CardContent>
    {/* content here */}
  </CardContent>
</Card>
```

**Badge** -- inline label with semantic variants:

```jsx
<Badge variant="default">Controller</Badge>
<Badge variant="success">Passing</Badge>
<Badge variant="warning">Degraded</Badge>
<Badge variant="error">Failed</Badge>
<Badge variant="outline">v2.1.0</Badge>
```

**Tabs** -- controlled tab groups for switching views:

```jsx
const [activeTab, setActiveTab] = useState("overview")

<Tabs value={activeTab} onValueChange={setActiveTab}>
  <TabsList>
    <TabsTrigger value="overview">Overview</TabsTrigger>
    <TabsTrigger value="dependencies">Dependencies</TabsTrigger>
    <TabsTrigger value="metrics">Metrics</TabsTrigger>
  </TabsList>
  <TabsContent value="overview">
    {/* overview content */}
  </TabsContent>
  <TabsContent value="dependencies">
    {/* dependencies content */}
  </TabsContent>
  <TabsContent value="metrics">
    {/* metrics content */}
  </TabsContent>
</Tabs>
```

**Progress** -- horizontal bar with semantic variants:

```jsx
<Progress value={85} variant="default" />
<Progress value={92} variant="success" />
<Progress value={60} variant="warning" />
<Progress value={30} variant="error" />
```

**Separator** -- horizontal or vertical divider:

```jsx
<Separator />
<Separator orientation="vertical" className="h-4" />
```

**Accordion** -- collapsible sections (use `defaultOpen` for initially expanded):

```jsx
<Accordion type="multiple" defaultValue={["section-1"]}>
  <AccordionItem value="section-1" defaultOpen>
    <AccordionTrigger>Core Module</AccordionTrigger>
    <AccordionContent>
      {/* content shown when expanded */}
    </AccordionContent>
  </AccordionItem>
  <AccordionItem value="section-2">
    <AccordionTrigger>API Module</AccordionTrigger>
    <AccordionContent>
      {/* collapsed by default */}
    </AccordionContent>
  </AccordionItem>
</Accordion>
```

**Tooltip** -- hover information popup:

```jsx
<Tooltip content="Opens src/main/kotlin/UserService.kt in the editor">
  <span className="cursor-pointer underline decoration-dotted">UserService</span>
</Tooltip>
```

### Recharts

For data visualizations -- charts, graphs, metrics:

```
BarChart, Bar, LineChart, Line, AreaChart, Area, PieChart, Pie, Cell,
XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip, Legend,
ResponsiveContainer, RadialBarChart, RadialBar, ComposedChart,
Scatter, ScatterChart, Treemap
```

Use `ResponsiveContainer` with `width="100%"` and a fixed height. Use CSS variables for chart colors to stay theme-aware.

### Lucide Icons

Available directly by name. Use `size={16}` or `size={14}` for inline icon sizing:

```
FileCode, FileText, FolderTree, FolderOpen, GitBranch, GitCommit,
GitPullRequest, GitMerge, Database, Server, Shield, ShieldCheck,
Zap, Activity, BarChart3, PieChart as PieChartIcon, TrendingUp,
TrendingDown, CheckCircle2, XCircle, AlertTriangle, AlertCircle, Info,
Clock, Timer, ArrowRight, ArrowDown, ArrowUpRight, ChevronRight,
ChevronDown, ExternalLink, Link, Search, Eye, EyeOff, Lock, Unlock,
Package, Layers, Box, Cpu, Globe, Terminal, Code, Braces, Hash,
Settings, Wrench, Bug, TestTube, Play, Pause, RotateCcw, RefreshCw,
Plus, Minus, X, Check, Copy, Download, Upload, Trash2, Edit, Star
```

### D3 (Full Namespace)

The full `d3` library is available as a single scope variable. Useful for custom SVG visualizations, scales, geo projections, hierarchies, and force layouts.

```jsx
// Custom SVG arc chart
const arc = d3.arc().innerRadius(40).outerRadius(80)
const pie = d3.pie().value(d => d.value)

// Color scales
const color = d3.scaleOrdinal(d3.schemeCategory10)

// Geo projections (for maps)
const projection = d3.geoNaturalEarth1()
const pathGenerator = d3.geoPath(projection)
```

### Animation (motion/react)

Scope variables: `motion`, `AnimatePresence`, `useMotionValue`, `useTransform`, `useSpring`, `useInView`, `useScroll`, `useAnimation`

```jsx
// Animated card entrance
<motion.div
  initial={{ opacity: 0, y: 20 }}
  animate={{ opacity: 1, y: 0 }}
  transition={{ duration: 0.5 }}
>
  <Card>...</Card>
</motion.div>

// Staggered list animation
{items.map((item, i) => (
  <motion.div
    key={item.id}
    initial={{ opacity: 0, x: -20 }}
    animate={{ opacity: 1, x: 0 }}
    transition={{ delay: i * 0.1 }}
  >
    ...
  </motion.div>
))}

// AnimatePresence for mount/unmount
<AnimatePresence>
  {selected && (
    <motion.div
      key="detail"
      initial={{ opacity: 0, scale: 0.95 }}
      animate={{ opacity: 1, scale: 1 }}
      exit={{ opacity: 0, scale: 0.95 }}
    >
      ...
    </motion.div>
  )}
</AnimatePresence>
```

### Globe (cobe)

`createGlobe` renders an interactive 3D globe on a canvas element. Useful for showing geographic distribution, server locations, or global reach.

```jsx
export default function GlobeView() {
  const canvasRef = useRef(null)

  useEffect(() => {
    let phi = 0
    const globe = createGlobe(canvasRef.current, {
      devicePixelRatio: 2,
      width: 400,
      height: 400,
      phi: 0,
      theta: 0.3,
      dark: bridge.isDark ? 1 : 0,
      diffuse: 1.2,
      mapSamples: 16000,
      mapBrightness: bridge.isDark ? 2 : 6,
      baseColor: bridge.isDark ? [0.3, 0.3, 0.3] : [1, 1, 1],
      markerColor: [0.39, 0.4, 0.95],
      glowColor: bridge.isDark ? [0.1, 0.1, 0.2] : [1, 1, 1],
      markers: [
        { location: [37.78, -122.41], size: 0.07 },
        { location: [51.51, -0.13], size: 0.05 },
        { location: [35.68, 139.69], size: 0.06 },
      ],
      onRender(state) {
        state.phi = phi
        phi += 0.005
      },
    })
    return () => globe.destroy()
  }, [])

  return <canvas ref={canvasRef} width={400} height={400} className="mx-auto" />
}
```

### Geographic Maps (react-simple-maps)

Scope variables: `ComposableMap`, `Geographies`, `Geography`, `Marker`, `MapLine`, `ZoomableGroup`, `Graticule`, `Sphere`

```jsx
const geoUrl = "https://cdn.jsdelivr.net/npm/world-atlas@2/countries-110m.json"

<ComposableMap>
  <Geographies geography={geoUrl}>
    {({ geographies }) =>
      geographies.map(geo => (
        <Geography
          key={geo.rsSVGPath}
          geography={geo}
          fill="var(--fg-muted)"
          stroke="var(--border)"
        />
      ))
    }
  </Geographies>
  <Marker coordinates={[-122.41, 37.78]}>
    <circle r={4} fill="var(--accent)" />
  </Marker>
</ComposableMap>
```

Note: react-simple-maps needs a TopoJSON data source. For offline use, inline the geo data directly in the artifact source rather than fetching from a URL.

### Hand-drawn Graphics (roughjs)

`rough` provides a sketchy, hand-drawn rendering style on canvas or SVG. Useful for informal diagrams, wireframes, or whiteboard-style visuals.

```jsx
export default function SketchDiagram() {
  const canvasRef = useRef(null)

  useEffect(() => {
    const rc = rough.canvas(canvasRef.current)
    rc.rectangle(10, 10, 200, 80, { roughness: 1.5, fill: 'var(--accent)', fillStyle: 'hachure' })
    rc.circle(300, 50, 60, { roughness: 2, stroke: 'var(--success)' })
    rc.line(210, 50, 270, 50, { roughness: 1.5 })
  }, [])

  return <canvas ref={canvasRef} width={400} height={100} />
}
```

### Important

These are scope variables -- use directly, NOT as imports, NOT as props. Writing `import { useState } from 'react'` will cause a transpile error. Writing `export default function App({ bridge })` will shadow the scope variable. The correct pattern is:

```jsx
export default function App() {
  const [selected, setSelected] = useState(null)
  const dark = bridge.isDark
  // ...
}
```

## Design Guidelines

### Typography

- **Titles:** `text-sm font-semibold` -- card titles, section headers
- **Secondary text:** `text-xs text-[var(--fg-muted)]` -- descriptions, metadata, timestamps
- **Body text:** `text-sm` -- content paragraphs, list items
- **Monospace:** `font-mono text-xs` -- file paths, code references, metrics
- **Numbers / stats:** `text-lg font-bold` or `text-2xl font-bold` for hero metrics

### Color

Always use CSS variables for theme-aware colors. Never hardcode hex values.

**Background layers:**
- `bg-[var(--bg)]` -- page background
- `bg-[var(--bg-card)]` -- card surface
- `bg-[var(--bg-hover)]` -- hover states, selected items

**Text layers:**
- `text-[var(--fg)]` -- primary text
- `text-[var(--fg-muted)]` -- secondary, descriptions
- `text-[var(--fg-accent)]` -- links, interactive elements

**Borders:**
- `border-[var(--border)]` -- standard borders
- `border-[var(--border-active)]` -- selected / focused

**Semantic color-coding by entity type** -- use consistently across all artifacts:

| Entity | Color | Usage |
|--------|-------|-------|
| Controller / Endpoint | `var(--accent)` | Routes, API surfaces |
| Service / Business Logic | `var(--success)` | Core logic, services |
| Repository / Data | `var(--warning)` | Database, persistence |
| External / Integration | `var(--error)` | Third-party, external APIs |
| Utility / Config | `var(--fg-muted)` | Helpers, configuration |

### Layout

- **3+ items in a grid:** `grid grid-cols-2 gap-3` or `grid grid-cols-3 gap-3`
- **2 items side-by-side:** `flex gap-4`
- **Vertical list:** `flex flex-col gap-2`
- **Card padding:** `p-4` for card content, `p-6` for outer container
- **Max width:** let the container handle it -- do not set explicit max-width
- **Responsive:** the artifact panel resizes -- use percentage widths and `min-w-0` on flex children

### Interaction

Every clickable element should have three things:

1. **`onClick={() => bridge.navigateToFile(path, line)}`** -- navigate to the file in the IDE
2. **`hover:shadow-md` or `hover:bg-[var(--bg-hover)]`** -- visual hover feedback
3. **`cursor-pointer`** -- indicate interactivity

**Selected state** for items in a list or grid:

```jsx
className={`rounded-lg border p-4 cursor-pointer transition-all
  ${selected === item.id
    ? 'border-[var(--border-active)] bg-[var(--bg-hover)] shadow-md'
    : 'border-[var(--border)] hover:border-[var(--border-active)] hover:shadow-md'
  }`}
```

### Spacing and Borders

- **Cards:** `rounded-lg` -- consistent rounded corners
- **Badges:** `rounded-full` -- pill shape
- **Inner sections:** `rounded-md` -- slightly less rounding
- **Gaps:** `gap-2` between tight items, `gap-3` for card grids, `gap-4` for major sections
- **Dividers:** use `<Separator />` between major sections, not raw `<hr>` or border-bottom hacks

## Anti-Patterns -- NEVER

- **`style={{ }}`** -- use Tailwind classes. Inline styles bypass theme, break consistency, and bloat source.
- **Hardcoded colors** (`#333`, `rgb(100,100,100)`, `text-gray-600`) -- use CSS variables. Hardcoded colors break in dark/light theme switching.
- **Generic grey-on-white** -- the default AI look. Add color-coding, badges, icons, and visual hierarchy.
- **Walls of text** -- if there are more than 3 lines of prose, it should be a card, table, or collapsible section instead.
- **Missing hover states** -- every clickable element needs `hover:` feedback. No silent clicks.
- **Emojis as icons** -- use Lucide icons. Emojis render inconsistently and look unprofessional in an IDE context.
- **`border-gray-200`** or any Tailwind color literal for borders -- use `border-[var(--border)]` for theme awareness.
- **`import` statements** -- scope variables are pre-injected. Imports cause transpile errors.
- **Destructuring bridge from props** (`function App({ bridge })`) -- bridge is a scope variable, not a prop. This shadows it with `undefined`.
- **Missing `export default`** -- every artifact must have `export default function ComponentName()`.
- **Giant monolith components** -- extract helper functions or sub-components within the same file for readability.

## Component Pattern

The recommended pattern for most artifacts: a card grid with selected state, IDE navigation on click, semantic badges, and proper Tailwind theming.

```jsx
export default function ModuleOverview() {
  const [selected, setSelected] = useState(null)

  const modules = [
    {
      id: 'core',
      name: 'Core',
      path: 'core/src/main/kotlin/com/app/core',
      type: 'service',
      files: 24,
      coverage: 87,
      status: 'healthy',
    },
    {
      id: 'api',
      name: 'API Gateway',
      path: 'api/src/main/kotlin/com/app/api',
      type: 'controller',
      files: 12,
      coverage: 72,
      status: 'warning',
    },
    {
      id: 'data',
      name: 'Data Access',
      path: 'data/src/main/kotlin/com/app/data',
      type: 'repository',
      files: 18,
      coverage: 91,
      status: 'healthy',
    },
  ]

  const typeColor = {
    controller: 'var(--accent)',
    service: 'var(--success)',
    repository: 'var(--warning)',
  }

  const statusBadge = {
    healthy: { variant: 'success', icon: CheckCircle2 },
    warning: { variant: 'warning', icon: AlertTriangle },
    error: { variant: 'error', icon: XCircle },
  }

  return (
    <div className="p-4 flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-sm font-semibold text-[var(--fg)]">
            Project Modules
          </h2>
          <p className="text-xs text-[var(--fg-muted)]">
            {modules.length} modules in {bridge.projectName || 'project'}
          </p>
        </div>
        <Badge variant="outline">
          <Layers size={12} className="mr-1" />
          {modules.reduce((sum, m) => sum + m.files, 0)} files
        </Badge>
      </div>

      <Separator />

      <div className="grid grid-cols-2 gap-3">
        {modules.map((mod) => {
          const isSelected = selected === mod.id
          const StatusIcon = statusBadge[mod.status].icon

          return (
            <Card
              key={mod.id}
              className={`cursor-pointer transition-all ${
                isSelected
                  ? 'border-[var(--border-active)] bg-[var(--bg-hover)] shadow-md'
                  : 'border-[var(--border)] hover:border-[var(--border-active)] hover:shadow-md'
              }`}
              onClick={() => {
                setSelected(mod.id)
                bridge.navigateToFile(mod.path, 1)
              }}
            >
              <CardHeader>
                <div className="flex items-center justify-between">
                  <CardTitle className="text-sm font-semibold flex items-center gap-2">
                    <FolderOpen
                      size={14}
                      style={{ color: typeColor[mod.type] }}
                    />
                    {mod.name}
                  </CardTitle>
                  <Badge variant={statusBadge[mod.status].variant}>
                    <StatusIcon size={12} className="mr-1" />
                    {mod.status}
                  </Badge>
                </div>
                <CardDescription className="text-xs text-[var(--fg-muted)] font-mono">
                  {mod.path}
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="flex items-center justify-between text-xs mb-1">
                  <span className="text-[var(--fg-muted)]">Coverage</span>
                  <span className="font-mono font-semibold">{mod.coverage}%</span>
                </div>
                <Progress
                  value={mod.coverage}
                  variant={mod.coverage >= 80 ? 'success' : 'warning'}
                />
              </CardContent>
            </Card>
          )
        })}
      </div>
    </div>
  )
}
```

This pattern demonstrates: grid layout, selected state with visual feedback, `bridge.navigateToFile` on click, semantic color-coding by entity type, Badge with status variants, Progress bars, Card composition, CSS variable theming, monospace for file paths, and Lucide icons sized consistently at 12-14px.
