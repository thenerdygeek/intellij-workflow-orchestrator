import { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import { RichBlock } from './RichBlock';
import { PlayControls } from './PlayControls';
import { useThemeStore } from '@/stores/themeStore';

// ── Types ──

interface FlowNode {
  id: string;
  label: string;
  tooltip?: string;
  color?: string;
}

interface FlowEdge {
  from: string;
  to: string;
  label?: string;
}

interface FlowGroup {
  id: string;
  label: string;
  nodeIds: string[];
  color?: string;
}

export interface FlowConfig {
  nodes: FlowNode[];
  edges: FlowEdge[];
  direction?: 'TB' | 'BT' | 'LR' | 'RL';
  groups?: FlowGroup[];
  animated?: boolean;
  highlightPath?: string[];
  pathLabels?: string[];
}

interface LayoutNode extends FlowNode {
  x: number;
  y: number;
  width: number;
  height: number;
}

interface LayoutEdge extends FlowEdge {
  points: Array<{ x: number; y: number }>;
}

interface LayoutGroup extends FlowGroup {
  x: number;
  y: number;
  width: number;
  height: number;
}

// ── Animation types ──

interface AnimationStep {
  activeNodeId: string;
  activeEdgeIndex: number | null;
  reverseEdge?: boolean;
  label?: string;
}

// ── Singleton lazy-load for dagre ──

interface DagreGraph {
  setGraph: (opts: Record<string, unknown>) => void;
  setDefaultEdgeLabel: (fn: () => Record<string, unknown>) => void;
  setNode: (id: string, opts: Record<string, unknown>) => void;
  setEdge: (from: string, to: string, opts?: Record<string, unknown>) => void;
  node: (id: string) => { x: number; y: number; width: number; height: number };
  edge: (from: string, to: string) => { points: Array<{ x: number; y: number }> };
  graph: () => { width: number; height: number };
}

interface DagreModule {
  graphlib: {
    Graph: new () => DagreGraph;
  };
  layout: (g: DagreGraph) => void;
}

let dagreModulePromise: Promise<DagreModule> | null = null;

function loadDagre(): Promise<DagreModule> {
  if (!dagreModulePromise) {
    dagreModulePromise = import('dagre').then((mod) => {
      // Handle both ESM default export and CJS — cast through unknown
      // because @types/dagre defines a richer Graph type than our minimal interface
      const raw = mod as unknown as { default?: DagreModule };
      const dagre = raw.default ?? (mod as unknown as DagreModule);
      return dagre;
    });
  }
  return dagreModulePromise;
}

// ── Layout ──

async function computeLayout(config: FlowConfig): Promise<{
  nodes: LayoutNode[];
  edges: LayoutEdge[];
  groups: LayoutGroup[];
  width: number;
  height: number;
}> {
  const dagre = await loadDagre();
  const g = new dagre.graphlib.Graph();

  g.setGraph({
    rankdir: config.direction ?? 'TB',
    nodesep: 50,
    ranksep: 60,
    marginx: 20,
    marginy: 20,
  });
  g.setDefaultEdgeLabel(() => ({}));

  for (const node of config.nodes) {
    g.setNode(node.id, {
      width: Math.max(120, node.label.length * 9 + 32),
      height: 40,
    });
  }

  for (const edge of config.edges) {
    g.setEdge(edge.from, edge.to, edge.label ? { label: edge.label } : {});
  }

  dagre.layout(g);

  const layoutNodes: LayoutNode[] = config.nodes.map((node) => {
    const pos = g.node(node.id);
    return { ...node, x: pos.x, y: pos.y, width: pos.width, height: pos.height };
  });

  const layoutEdges: LayoutEdge[] = config.edges.map((edge) => {
    const edgeData = g.edge(edge.from, edge.to);
    return { ...edge, points: edgeData.points };
  });

  // Compute group bounding boxes from laid-out node positions
  const GROUP_PADDING = 20;
  const GROUP_LABEL_HEIGHT = 20;
  const layoutGroups: LayoutGroup[] = (config.groups ?? []).map((group) => {
    const memberNodes = layoutNodes.filter((n) => group.nodeIds.includes(n.id));
    if (memberNodes.length === 0) {
      return { ...group, x: 0, y: 0, width: 0, height: 0 };
    }
    const minX = Math.min(...memberNodes.map((n) => n.x - n.width / 2));
    const maxX = Math.max(...memberNodes.map((n) => n.x + n.width / 2));
    const minY = Math.min(...memberNodes.map((n) => n.y - n.height / 2));
    const maxY = Math.max(...memberNodes.map((n) => n.y + n.height / 2));
    return {
      ...group,
      x: minX - GROUP_PADDING,
      y: minY - GROUP_PADDING - GROUP_LABEL_HEIGHT,
      width: maxX - minX + GROUP_PADDING * 2,
      height: maxY - minY + GROUP_PADDING * 2 + GROUP_LABEL_HEIGHT,
    };
  });

  const graphInfo = g.graph();
  return {
    nodes: layoutNodes,
    edges: layoutEdges,
    groups: layoutGroups,
    width: graphInfo.width ?? 400,
    height: graphInfo.height ?? 300,
  };
}

// ── Edge path builder ──

function buildEdgePath(points: Array<{ x: number; y: number }>): string {
  if (points.length === 0) return '';
  const first = points[0]!;
  let d = `M ${first.x} ${first.y}`;
  for (let i = 1; i < points.length; i++) {
    const p = points[i]!;
    d += ` L ${p.x} ${p.y}`;
  }
  return d;
}

// ── Animation step computation ──

function computeAnimationSteps(
  config: FlowConfig,
  layoutEdges: LayoutEdge[],
): AnimationStep[] {
  const { nodes, edges, highlightPath, pathLabels } = config;

  // If highlightPath provided, follow it
  if (highlightPath && highlightPath.length > 0) {
    const steps: AnimationStep[] = [];
    for (let i = 0; i < highlightPath.length; i++) {
      const nodeId = highlightPath[i]!;
      if (!nodes.some(n => n.id === nodeId)) continue; // skip invalid IDs

      let edgeIdx: number | null = null;
      let reverseEdge = false;
      if (i > 0) {
        const prevId = highlightPath[i - 1]!;
        edgeIdx = layoutEdges.findIndex(e => e.from === prevId && e.to === nodeId);
        if (edgeIdx === -1) {
          // Try reverse (response path)
          edgeIdx = layoutEdges.findIndex(e => e.from === nodeId && e.to === prevId);
          if (edgeIdx !== -1) reverseEdge = true;
          else edgeIdx = null;
        }
      }

      steps.push({
        activeNodeId: nodeId,
        activeEdgeIndex: edgeIdx,
        reverseEdge,
        label: i > 0 ? pathLabels?.[i - 1] : undefined,
      });
    }
    return steps;
  }

  // BFS from source nodes (no incoming edges)
  const incomingCount = new Map<string, number>();
  nodes.forEach(n => incomingCount.set(n.id, 0));
  edges.forEach(e => incomingCount.set(e.to, (incomingCount.get(e.to) ?? 0) + 1));
  const sources = nodes.filter(n => (incomingCount.get(n.id) ?? 0) === 0);
  if (sources.length === 0) return [];

  const visited = new Set<string>();
  const queue: string[] = sources.map(s => s.id);
  const steps: AnimationStep[] = [];

  while (queue.length > 0) {
    const nodeId = queue.shift()!;
    if (visited.has(nodeId)) continue;
    visited.add(nodeId);

    let edgeIdx: number | null = null;
    if (steps.length > 0) {
      edgeIdx = layoutEdges.findIndex(e => e.to === nodeId && visited.has(e.from));
      if (edgeIdx === -1) edgeIdx = null;
    }

    steps.push({ activeNodeId: nodeId, activeEdgeIndex: edgeIdx });
    edges.filter(e => e.from === nodeId && !visited.has(e.to)).forEach(e => queue.push(e.to));
  }

  return steps;
}

// ── Animation state helpers ──

function getNodeState(
  nodeId: string,
  step: number,
  steps: AnimationStep[],
): 'active' | 'visited' | 'unvisited' | 'normal' {
  if (steps.length === 0) return 'normal';
  if (steps[step]?.activeNodeId === nodeId) return 'active';
  for (let i = 0; i < step; i++) {
    if (steps[i]?.activeNodeId === nodeId) return 'visited';
  }
  return 'unvisited';
}

function getEdgeState(
  edgeIndex: number,
  step: number,
  steps: AnimationStep[],
): 'active' | 'visited' | 'unvisited' | 'normal' {
  if (steps.length === 0) return 'normal';
  if (steps[step]?.activeEdgeIndex === edgeIndex) return 'active';
  for (let i = 0; i < step; i++) {
    if (steps[i]?.activeEdgeIndex === edgeIndex) return 'visited';
  }
  return 'unvisited';
}

// ── Zoom/Pan hook (shared logic) ──

function useZoomPan() {
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const isPanningRef = useRef(false);
  const lastPosRef = useRef({ x: 0, y: 0 });

  const handleWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault();
    setZoom((prev) => Math.min(4, Math.max(0.25, prev - e.deltaY * 0.002)));
  }, []);

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    if (e.button !== 0) return;
    isPanningRef.current = true;
    lastPosRef.current = { x: e.clientX, y: e.clientY };
    e.preventDefault();
  }, []);

  const handleMouseMove = useCallback((e: React.MouseEvent) => {
    if (!isPanningRef.current) return;
    const dx = e.clientX - lastPosRef.current.x;
    const dy = e.clientY - lastPosRef.current.y;
    lastPosRef.current = { x: e.clientX, y: e.clientY };
    setPan((prev) => ({ x: prev.x + dx, y: prev.y + dy }));
  }, []);

  const handleMouseUp = useCallback(() => {
    isPanningRef.current = false;
  }, []);

  const handleDoubleClick = useCallback(() => {
    setZoom(1);
    setPan({ x: 0, y: 0 });
  }, []);

  const reset = useCallback(() => {
    setZoom(1);
    setPan({ x: 0, y: 0 });
  }, []);

  return {
    zoom,
    pan,
    reset,
    handlers: {
      onWheel: handleWheel,
      onMouseDown: handleMouseDown,
      onMouseMove: handleMouseMove,
      onMouseUp: handleMouseUp,
      onMouseLeave: handleMouseUp,
      onDoubleClick: handleDoubleClick,
    },
  };
}

// ── FlowDiagram component ──

interface FlowDiagramProps {
  source: string;
}

export function FlowDiagram({ source }: FlowDiagramProps) {
  const isDark = useThemeStore((s) => s.isDark);
  const getVar = useThemeStore((s) => s.getVar);
  const [layout, setLayout] = useState<{
    nodes: LayoutNode[];
    edges: LayoutEdge[];
    groups: LayoutGroup[];
    width: number;
    height: number;
  } | null>(null);
  const [config, setConfig] = useState<FlowConfig | null>(null);
  const [animStep, setAnimStep] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);
  const renderIdRef = useRef(0);
  const { zoom, pan, reset, handlers } = useZoomPan();

  const parseAndLayout = useCallback(async () => {
    const currentRender = ++renderIdRef.current;
    setIsLoading(true);
    setError(null);
    reset();

    try {
      const parsedConfig: FlowConfig = JSON.parse(source);

      if (!parsedConfig.nodes || !Array.isArray(parsedConfig.nodes)) {
        throw new Error('FlowConfig must have a "nodes" array');
      }
      if (!parsedConfig.edges || !Array.isArray(parsedConfig.edges)) {
        throw new Error('FlowConfig must have an "edges" array');
      }

      const result = await computeLayout(parsedConfig);
      if (currentRender !== renderIdRef.current) return;

      setLayout(result);
      setConfig(parsedConfig);
      setAnimStep(0);
      setIsLoading(false);
    } catch (err) {
      if (currentRender !== renderIdRef.current) return;
      setError(err instanceof Error ? err : new Error(String(err)));
      setIsLoading(false);
    }
  }, [source, reset]);

  useEffect(() => {
    void parseAndLayout();
  }, [parseAndLayout]);

  const zoomPercent = useMemo(() => Math.round(zoom * 100), [zoom]);

  // Animation steps
  const animSteps = useMemo(() => {
    if (!config?.animated || !layout) return [];
    return computeAnimationSteps(config, layout.edges);
  }, [config, layout]);

  const isAnimated = animSteps.length > 0;

  // Theme colors
  const fgColor = getVar('fg') || (isDark ? '#cccccc' : '#333333');
  const mutedColor = getVar('fg-muted') || (isDark ? '#888888' : '#999999');
  const borderColor = getVar('border') || (isDark ? '#444444' : '#dddddd');
  const linkColor = getVar('link') || (isDark ? '#6ca0dc' : '#2563eb');
  const accentColor = getVar('accent') || (isDark ? '#60a5fa' : '#3b82f6');

  return (
    <RichBlock
      type="flow"
      source={source}
      isLoading={isLoading}
      error={error}
      onRetry={() => void parseAndLayout()}
    >
      <div
        className="relative cursor-grab overflow-hidden active:cursor-grabbing"
        style={{ minHeight: 80 }}
        {...handlers}
      >
        {layout && (
          <div
            className="flex items-center justify-center p-4"
            style={{
              transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`,
              transformOrigin: 'center center',
              transition: 'none',
            }}
          >
            <svg
              width={layout.width}
              height={layout.height}
              viewBox={`0 0 ${layout.width} ${layout.height}`}
              style={{ overflow: 'visible' }}
            >
              <defs>
                {/* Arrowhead marker */}
                <marker
                  id="flow-arrowhead"
                  viewBox="0 0 10 10"
                  refX="9"
                  refY="5"
                  markerWidth="6"
                  markerHeight="6"
                  orient="auto-start-reverse"
                >
                  <path d="M 0 0 L 10 5 L 0 10 z" fill={mutedColor} />
                </marker>
                {/* Active arrowhead marker for animated edges */}
                {isAnimated && (
                  <marker
                    id="flow-arrowhead-active"
                    viewBox="0 0 10 10"
                    refX="9"
                    refY="5"
                    markerWidth="6"
                    markerHeight="6"
                    orient="auto-start-reverse"
                  >
                    <path d="M 0 0 L 10 5 L 0 10 z" fill={accentColor} />
                  </marker>
                )}
                {/* Glow filter for active nodes */}
                {isAnimated && (
                  <filter id="node-glow">
                    <feGaussianBlur stdDeviation="3" result="blur" />
                    <feMerge>
                      <feMergeNode in="blur" />
                      <feMergeNode in="SourceGraphic" />
                    </feMerge>
                  </filter>
                )}
              </defs>

              {/* Groups (rendered behind edges and nodes) */}
              {layout.groups.map((group) => {
                if (group.width === 0 && group.height === 0) return null;
                const groupFill = group.color
                  ? `${group.color}14`
                  : isDark
                    ? 'rgba(108,160,220,0.08)'
                    : 'rgba(37,99,235,0.06)';
                const groupStroke = group.color ?? borderColor;

                return (
                  <g key={`group-${group.id}`}>
                    <rect
                      x={group.x}
                      y={group.y}
                      width={group.width}
                      height={group.height}
                      rx={8}
                      ry={8}
                      fill={groupFill}
                      stroke={groupStroke}
                      strokeWidth={1}
                      strokeDasharray="6 4"
                      strokeOpacity={0.6}
                    />
                    <text
                      x={group.x + 10}
                      y={group.y + 14}
                      fontSize={11}
                      fontWeight={600}
                      fill={mutedColor}
                      fontFamily="var(--font-body)"
                    >
                      {group.label}
                    </text>
                  </g>
                );
              })}

              {/* Edges */}
              {layout.edges.map((edge, i) => {
                const path = buildEdgePath(edge.points);
                const midIdx = Math.floor(edge.points.length / 2);
                const midPoint = edge.points[midIdx];
                const edgeState = getEdgeState(i, animStep, animSteps);
                const currentStep = animSteps[animStep];
                const isActiveEdge = edgeState === 'active';
                const isReverse = isActiveEdge && currentStep?.reverseEdge;

                // Determine edge visual properties based on animation state
                const edgeStroke = edgeState === 'active' ? accentColor
                  : edgeState === 'visited' ? mutedColor
                  : edgeState === 'unvisited' ? borderColor
                  : mutedColor;
                const edgeStrokeWidth = edgeState === 'active' ? 2.5
                  : edgeState === 'visited' ? 1.5
                  : edgeState === 'unvisited' ? 1
                  : 1.5;
                const edgeOpacity = edgeState === 'active' ? 1
                  : edgeState === 'visited' ? 0.8
                  : edgeState === 'unvisited' ? 0.2
                  : 1;
                const showFlowDash = edgeState === 'normal';
                const markerEnd = edgeState === 'active'
                  ? 'url(#flow-arrowhead-active)'
                  : 'url(#flow-arrowhead)';

                return (
                  <g key={`edge-${i}`}>
                    {/* Edge path */}
                    <path
                      id={`edge-path-${i}`}
                      d={path}
                      fill="none"
                      stroke={edgeStroke}
                      strokeWidth={edgeStrokeWidth}
                      opacity={edgeOpacity}
                      markerEnd={markerEnd}
                    />
                    {/* Animated dash overlay — only for normal (non-animated) state */}
                    {showFlowDash && (
                      <path
                        d={path}
                        fill="none"
                        stroke={linkColor}
                        strokeWidth={1.5}
                        strokeDasharray="6 12"
                        strokeOpacity={0.5}
                        style={{ animation: 'flowDash 1s linear infinite' }}
                      />
                    )}
                    {/* Traveling dot for active edges */}
                    {isActiveEdge && (
                      <circle r="3" fill={accentColor}>
                        <animateMotion
                          dur="0.8s"
                          repeatCount="indefinite"
                          keyPoints={isReverse ? '1;0' : '0;1'}
                          keyTimes="0;1"
                          calcMode="linear"
                        >
                          <mpath href={`#edge-path-${i}`} />
                        </animateMotion>
                      </circle>
                    )}
                    {/* Edge label */}
                    {edge.label && midPoint && (
                      <text
                        x={midPoint.x}
                        y={midPoint.y - 8}
                        textAnchor="middle"
                        fontSize={10}
                        fill={mutedColor}
                        fontFamily="var(--font-body)"
                        opacity={edgeOpacity}
                      >
                        {edge.label}
                      </text>
                    )}
                  </g>
                );
              })}

              {/* Nodes */}
              {layout.nodes.map((node) => {
                const isHovered = hoveredNode === node.id;
                const nodeColor = node.color ?? linkColor;
                const nodeState = getNodeState(node.id, animStep, animSteps);

                // Determine node visual properties based on animation state
                // Active nodes: NO instant glow filter — the directional sweep IS the glow
                const nodeStroke = nodeState === 'active' ? nodeColor
                  : nodeState === 'visited' ? nodeColor
                  : nodeState === 'unvisited' ? borderColor
                  : isHovered ? nodeColor : borderColor;
                const nodeStrokeWidth = nodeState === 'active' ? 2
                  : nodeState === 'visited' ? 1.5
                  : nodeState === 'unvisited' ? 1.5
                  : isHovered ? 2 : 1.5;
                const nodeOpacity = nodeState === 'unvisited' ? 0.3 : 1;

                // Determine sweep direction for active glow
                // Based on flow direction + whether this step's edge is reversed
                const activeStepData = animSteps[animStep];
                const isReverseEntry = nodeState === 'active' && activeStepData?.reverseEdge;
                const dir = config?.direction ?? 'TB';
                // Sweep: entry side → exit side
                // LR: left→right (reversed: right→left)
                // RL: right→left (reversed: left→right)
                // TB: top→bottom (reversed: bottom→top)
                // BT: bottom→top (reversed: top→bottom)
                const sweepHorizontal = dir === 'LR' || dir === 'RL';
                const sweepForward = (dir === 'LR' || dir === 'TB') !== !!isReverseEntry;

                return (
                  <g
                    key={node.id}
                    onMouseEnter={() => setHoveredNode(node.id)}
                    onMouseLeave={() => setHoveredNode(null)}
                    style={{ cursor: 'default', opacity: nodeOpacity }}
                  >
                    {/* Node rect */}
                    <rect
                      x={node.x - node.width / 2}
                      y={node.y - node.height / 2}
                      width={node.width}
                      height={node.height}
                      rx={8}
                      ry={8}
                      fill={isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)'}
                      stroke={nodeStroke}
                      strokeWidth={nodeStrokeWidth}
                    />

                    {/* Directional glow sweep for active nodes — uses CSS clip-path animation */}
                    {nodeState === 'active' && (
                      <foreignObject
                        key={`sweep-${animStep}`}
                        x={node.x - node.width / 2 - 2}
                        y={node.y - node.height / 2 - 2}
                        width={node.width + 4}
                        height={node.height + 4}
                      >
                        <div
                          style={{
                            width: '100%',
                            height: '100%',
                            borderRadius: 8,
                            background: `${nodeColor}30`,
                            border: `2.5px solid ${nodeColor}`,
                            boxSizing: 'border-box',
                            animation: sweepHorizontal
                              ? (sweepForward
                                ? 'sweep-lr 0.8s ease-out forwards'
                                : 'sweep-rl 0.8s ease-out forwards')
                              : (sweepForward
                                ? 'sweep-tb 0.8s ease-out forwards'
                                : 'sweep-bt 0.8s ease-out forwards'),
                          }}
                        />
                      </foreignObject>
                    )}

                    {/* Node label */}
                    <text
                      x={node.x}
                      y={node.y + 4}
                      textAnchor="middle"
                      fontSize={12}
                      fill={fgColor}
                      fontFamily="var(--font-body)"
                    >
                      {node.label}
                    </text>

                    {/* Tooltip via foreignObject */}
                    {isHovered && node.tooltip && (
                      <foreignObject
                        x={node.x - 80}
                        y={node.y - node.height / 2 - 32}
                        width={160}
                        height={28}
                      >
                        <div
                          style={{
                            background: isDark ? '#333' : '#fff',
                            border: `1px solid ${borderColor}`,
                            borderRadius: 4,
                            padding: '2px 8px',
                            fontSize: 10,
                            color: fgColor,
                            textAlign: 'center',
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                          }}
                        >
                          {node.tooltip}
                        </div>
                      </foreignObject>
                    )}
                  </g>
                );
              })}
            </svg>
          </div>
        )}

        {/* Zoom indicator */}
        {zoomPercent !== 100 && (
          <div className="absolute right-2 bottom-2 rounded bg-[var(--code-bg)]/80 px-2 py-0.5 text-[10px] text-[var(--fg-muted)] backdrop-blur-sm">
            {zoomPercent}%
          </div>
        )}
      </div>

      {/* Animation caption */}
      {isAnimated && animSteps[animStep]?.label && (
        <div
          key={animStep}
          className="text-center text-[12px] py-1 animate-[fade-in_200ms_ease-out]"
          style={{ color: 'var(--fg-secondary, var(--fg-muted))' }}
        >
          {animSteps[animStep].label}
        </div>
      )}

      {/* Play controls for animated diagrams */}
      {isAnimated && (
        <PlayControls
          totalSteps={animSteps.length}
          currentStep={animStep}
          onStepChange={setAnimStep}
          autoPlayInterval={1200}
        />
      )}
    </RichBlock>
  );
}
