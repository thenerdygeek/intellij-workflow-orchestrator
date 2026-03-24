import { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import { RichBlock } from './RichBlock';
import { PlayControls } from './PlayControls';
import { useThemeStore } from '@/stores/themeStore';

// ── Singleton lazy-load for mermaid ──

type MermaidModule = {
  default: {
    initialize: (config: Record<string, unknown>) => void;
    render: (id: string, source: string) => Promise<{ svg: string }>;
  };
};

let mermaidModulePromise: Promise<MermaidModule> | null = null;
let mermaidIdCounter = 0;

function loadMermaid(): Promise<MermaidModule> {
  if (!mermaidModulePromise) {
    mermaidModulePromise = import('mermaid') as Promise<MermaidModule>;
  }
  return mermaidModulePromise;
}

// ── Zoom/Pan hook ──

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

// ── MermaidDiagram component ──

interface MermaidDiagramProps {
  source: string;
}

export function MermaidDiagram({ source }: MermaidDiagramProps) {
  const isDark = useThemeStore((s) => s.isDark);
  const [svgContent, setSvgContent] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const svgContainerRef = useRef<HTMLDivElement>(null);
  const renderIdRef = useRef(0);
  const { zoom, pan, reset, handlers } = useZoomPan();

  // Sequence diagram animation state
  const isSequenceDiagram = source.trim().startsWith('sequenceDiagram');
  const [messageGroups, setMessageGroups] = useState<Element[][]>([]);
  const [seqStep, setSeqStep] = useState(0);

  const renderMermaid = useCallback(async () => {
    const currentRender = ++renderIdRef.current;
    setIsLoading(true);
    setError(null);
    reset();

    try {
      const mermaidModule = await loadMermaid();
      const mermaid = mermaidModule.default;

      mermaid.initialize({
        startOnLoad: false,
        theme: isDark ? 'dark' : 'default',
        securityLevel: 'strict',
        fontFamily: 'var(--font-body)',
      });

      if (currentRender !== renderIdRef.current) return;

      const id = `mermaid-${++mermaidIdCounter}`;
      const { svg } = await mermaid.render(id, source);

      if (currentRender !== renderIdRef.current) return;

      setSvgContent(svg);
      setIsLoading(false);
    } catch (err) {
      if (currentRender !== renderIdRef.current) return;
      setError(err instanceof Error ? err : new Error(String(err)));
      setIsLoading(false);
    }
  }, [source, isDark, reset]);

  useEffect(() => {
    void renderMermaid();
  }, [renderMermaid]);

  // Parse message elements from rendered SVG for sequence diagram animation
  useEffect(() => {
    if (!isSequenceDiagram || !svgContent || !svgContainerRef.current) return;

    // Wait a frame for DOM to update after dangerouslySetInnerHTML
    requestAnimationFrame(() => {
      const svgEl = svgContainerRef.current?.querySelector('svg');
      if (!svgEl) return;

      // Mermaid 11.x: each message has a .messageText element.
      // Walk up to parent <g> to get the full message group (line + arrowhead + text)
      const messageTexts = svgEl.querySelectorAll('.messageText');
      const groups: Element[][] = [];

      messageTexts.forEach(textEl => {
        const parentG = textEl.closest('g');
        if (parentG) {
          groups.push(Array.from(parentG.children));
        } else {
          groups.push([textEl]);
        }
      });

      if (groups.length === 0) return;

      setMessageGroups(groups);
      setSeqStep(0);

      // Initially hide all messages
      groups.forEach(group => {
        group.forEach(el => {
          (el as HTMLElement).style.opacity = '0';
          (el as HTMLElement).style.transition = 'opacity 300ms ease-out';
        });
      });
    });
  }, [svgContent, isSequenceDiagram]);

  // Reveal messages based on current step
  useEffect(() => {
    if (messageGroups.length === 0) return;
    messageGroups.forEach((group, i) => {
      group.forEach(el => {
        (el as HTMLElement).style.opacity = i <= seqStep ? '1' : '0';
      });
    });
  }, [seqStep, messageGroups]);

  const isAnimatedSequence = isSequenceDiagram && messageGroups.length > 0;

  const zoomPercent = useMemo(() => Math.round(zoom * 100), [zoom]);

  return (
    <RichBlock
      type="mermaid"
      source={source}
      isLoading={isLoading}
      error={error}
      onRetry={() => void renderMermaid()}
    >
      <div
        ref={containerRef}
        className="relative cursor-grab overflow-hidden active:cursor-grabbing"
        style={{ minHeight: 80 }}
        {...handlers}
      >
        {svgContent && (
          <div
            ref={svgContainerRef}
            className="flex items-center justify-center p-4"
            style={{
              transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`,
              transformOrigin: 'center center',
              transition: 'none',
            }}
            dangerouslySetInnerHTML={{ __html: svgContent }}
          />
        )}

        {/* Zoom indicator */}
        {zoomPercent !== 100 && (
          <div className="absolute right-2 bottom-2 rounded bg-[var(--code-bg)]/80 px-2 py-0.5 text-[10px] text-[var(--fg-muted)] backdrop-blur-sm">
            {zoomPercent}%
          </div>
        )}
      </div>

      {isAnimatedSequence && (
        <PlayControls
          totalSteps={messageGroups.length}
          currentStep={seqStep}
          onStepChange={setSeqStep}
          autoPlayInterval={1500}
        />
      )}
    </RichBlock>
  );
}
