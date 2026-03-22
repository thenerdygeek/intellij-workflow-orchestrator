import { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import { RichBlock } from './RichBlock';
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
  const renderIdRef = useRef(0);
  const { zoom, pan, reset, handlers } = useZoomPan();

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
    </RichBlock>
  );
}
